package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.ScheduledTask;
import ai.ravenroot.api.execution.Scheduler;
import ai.ravenroot.api.persistence.JoinBranchOutcome;
import ai.ravenroot.api.persistence.JoinFailureReason;
import ai.ravenroot.api.persistence.JoinKey;
import ai.ravenroot.api.persistence.JoinPhase;
import ai.ravenroot.api.persistence.JoinRecord;
import ai.ravenroot.api.persistence.JoinStore;
import ai.ravenroot.api.persistence.JoinStoreException;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fan-in semantics for <strong>one traversal</strong>: quorum, deduplication, late arrivals, branch
 * failure, timeout and restart recovery (CORE-03).
 *
 * <h2>Scope is one traversal, and that is the isolation mechanism</h2>
 * <p>A coordinator instance belongs to a single traversal and dies with it. Two concurrent
 * executions of the same graph therefore share no in-memory join state at all — they cannot
 * interfere, because there is nothing to interfere over. This is stronger than keying a shared map
 * by traversal id, and it is also what bounds the in-memory state: it is reachable only from the
 * live dispatch chain, so it is reclaimed when the traversal ends rather than needing a sweep that
 * can be forgotten.</p>
 *
 * <p>Isolation <em>within</em> a join — several branches arriving at once, on several threads or in
 * several processes — is the {@link JoinStore}'s compare-and-set, not a lock. Every branch reads the
 * record, decides, and writes back at the revision it read; exactly one write wins. A lock would
 * give the same answer on one JVM and no answer at all across two.</p>
 *
 * <h2>Payload lives here, correlation lives in the store</h2>
 * <p>{@link JoinRecord} says which branches are settled; this class holds what they carried. After a
 * restart the store still knows a branch counted but no process holds its payload, so the join
 * waits for the at-least-once redelivery that brings the payload back and recognises it as a
 * duplicate rather than a second arrival. That is why a duplicate re-evaluates the join instead of
 * being dropped on sight.</p>
 */
final class JoinCoordinator {

    /**
     * Bound on compare-and-set retries for one arrival. A retry happens only when another branch of
     * the <em>same</em> join wrote first, so the loop can run at most once per competing branch;
     * this ceiling is a guard against a misbehaving adapter, not against normal contention, and is
     * generous enough that reaching it means the store is not honouring its own revisions.
     */
    private static final int MAX_CAS_ATTEMPTS = 64;

    /**
     * How many iterations of retained state one join may accumulate before it is worth saying so.
     *
     * <p><b>An internal default, not a contract.</b> Nothing durable is keyed by it, no graph can set
     * it, no behaviour changes when it is crossed — the only consequence is one diagnostic event — so
     * a future version may move it without migrating anything or breaking anybody. It is stated here
     * rather than being a literal at the comparison so that the test which pins the "once per
     * crossing" property reads the same number the runtime does, instead of hard-coding a copy of it
     * that could drift.</p>
     *
     * <p>Sixteen because a graph whose cycle runs a handful of times is ordinary and says nothing,
     * while one that has gone round seventeen times inside a single traversal is retaining seventeen
     * iterations of arrival payloads and is worth a line in the log. Whether the runtime should also
     * <em>bound</em> this remains unspecified.</p>
     */
    static final int ITERATION_BACKLOG_THRESHOLD = 16;

    private final JoinStore store;
    private final Scheduler scheduler;
    private final ExecutionMonitor monitor;
    private final ExecutionMonitor.ExecutionIdentity identity;
    private final Map<String, JoinSpec> specs;
    private final Clock clock;
    private final UUID processInstanceId;
    private final UUID traversalId;
    private final String tenantId;
    private final Runnable timeoutRelinquishedObserver;

    /**
     * In-memory state for the joins this traversal has actually reached.
     *
     * <p><strong>Bounded by</strong> the number of fan-in nodes in the graph — at most one entry
     * each, for one traversal. <strong>Evicted by</strong> {@link #terminate()}, which the runner
     * calls when the traversal completes or fails, and by the whole coordinator becoming garbage
     * once the dispatch chain releases it.</p>
     */
    private final ConcurrentHashMap<String, LocalJoin> locals = new ConcurrentHashMap<>();

    private final AtomicInteger liveTimeouts = new AtomicInteger();

    /**
     * Whether this traversal is currently held by an operator, so that no join of it may run a
     * deadline.
     *
     * <p>Read and written under {@code gate}, the same monitor {@link #local(String)} creates joins
     * under, so that "held" and "the set of joins that exist" are one observation rather than two.
     * It seeds {@link LocalJoin#held}, which is where the decision is actually taken; this field is
     * only what a join reached <em>during</em> a hold is born from.</p>
     */
    private boolean joinTimeoutsHeld;

    /**
     * The verdict of the first join that was still holding a parked branch when the traversal ended.
     *
     * <p>A branch parks on a join and is released by that join settling — Proceed, Failed or timeout
     * all complete every waiter. So a branch still parked at {@link #terminate()} means its join
     * never settled and the traversal ended anyway, which can only happen if that branch was
     * abandoned: {@link ai.ravenroot.core.runtime.GraphRunner} short-circuited on a sibling's failure
     * and then absorbed that failure into a join, so the parent stage completed successfully while
     * this branch was still waiting.</p>
     *
     * <p>Recording it is what stops the traversal claiming success in that state. The alternative
     * outcome is not a mild one: the traversal reports COMPLETED with no result and with its end node
     * never executed, because the work that would have produced the result is the work that was
     * abandoned. Silence is the worst possible answer here, so the abandoned branch's own join
     * failure becomes the traversal's failure.</p>
     */
    private final java.util.concurrent.atomic.AtomicReference<JoinFailureException> abandonedBranch =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Set once {@link #releaseInMemory} has returned for this coordinator's one and only run of it —
     * the point at which {@link #abandonedBranch} holds its final value and can no longer change.
     * FIX-35.
     *
     * <p>This is deliberately a fact about <em>this coordinator</em>, not about any one caller's
     * stage. FIX-17 covered a caller — a re-entrant second {@link #terminate()} — being handed a
     * stage that claimed completion while {@link #releaseInMemory} was still on the stack elsewhere;
     * FIX-17 closed that by making every caller share the one real {@link #termination} stage. This
     * flag closes the same class of gap a different way: even a caller holding a stage that
     * (correctly today, or incorrectly after some future regression) claims completion cannot turn
     * that belief into a wrong answer here, because the flag is not derived from any stage at all.
     * Volatile is sufficient: the single write happens-before every read that matters, because every
     * read reachable from {@link #terminate()}'s own contract is ordered after the {@link #released}
     * stage this flag is set immediately before.</p>
     */
    private volatile boolean releaseComplete;

    /**
     * The join verdict of a branch this traversal abandoned, or {@code null} if none was abandoned.
     *
     * <h2>The contract (FIX-35)</h2>
     * <p>{@code null} is a legitimate answer in exactly one state: {@link #terminate()}'s stage has
     * completed <em>and</em> every join this traversal reached settled with nothing left parked —
     * see {@code JoinRetainedStateTest#reportsNothingAbandonedWhenNoBranchWasEverParked}. In every
     * other state the question is not yet answerable, and this method says so loudly instead of
     * returning the same {@code null} a genuine "nothing abandoned" would produce.
     *
     * <p><b>Why a silent {@code null} here admitted the re-entrant termination defect.</b> Before this
     * contract was enforced, "not yet decided" and "genuinely nothing" were the same bit pattern.
     * {@code GraphRunner} reads this getter exactly once {@link #terminate()}'s own stage has
     * completed — the correct calling convention — so the getter itself had no way to tell a caller
     * relying on a WRONG belief about completion (a re-entrant second caller handed a stage
     * that claimed to be done while it was not) from one relying on a correct belief. Both saw
     * {@code null}, and for the wrong caller that silently became "this traversal has nothing
     * abandoned", which is how a traversal with a genuinely abandoned branch reported COMPLETED with
     * no result. Throwing here removes that ambiguity regardless of which caller, or which future
     * regression, produces the premature read.
     *
     * @throws IllegalStateException if this coordinator's {@link #terminate()} has not yet finished
     *         releasing every parked branch — call this only after awaiting the stage
     *         {@link #terminate()} returns.
     */
    JoinFailureException abandonedBranchFailure() {
        if (!releaseComplete) {
            throw new IllegalStateException("abandonedBranchFailure() was read before terminate()'s "
                    + "stage completed for this coordinator; the answer is not yet decided, which is "
                    + "not the same thing as \"nothing was abandoned\" -- await the stage terminate() "
                    + "returns before calling this (#235)");
        }
        return abandonedBranch.get();
    }

    /**
     * The drain barrier that makes eviction airtight.
     *
     * <p>A boolean "terminated" flag alone is not enough, and the gap is small enough to survive a
     * green suite: a branch can pass the flag check and then be <em>inside</em> its compare-and-set
     * when the traversal ends, so its write lands after the discard and the record is retained
     * forever. Measured at roughly 1.6% of failing traversals — a leak that is invisible per run and
     * unbounded over a process lifetime.</p>
     *
     * <p>So termination is two-phase: refuse new operations, wait for the ones already running to
     * return, and only then discard. A branch parked waiting for the join's verdict is deliberately
     * not counted, because it is released <em>by</em> termination — counting it would make
     * termination wait for something only termination can unblock.</p>
     *
     * <p>The barrier covers <em>arming a timeout</em> as well as writing to the store, so that
     * {@link #terminate()} waits for a {@link Scheduler#schedule} call that is already running —
     * and for the handoff that follows it, up to and including the cancellation the losing side
     * performs — before the record discard and before its own stage completes.
     * {@link LocalJoin#armTimeoutFor(int)} cannot hold {@code gate} across that call, because an
     * implementation that blocks there would then block every terminating thread behind it, so it
     * does what a store settle does: claim a slot, run outside the lock, release.</p>
     *
     * <p><strong>What the barrier deliberately does not gate is the release of process memory.</strong>
     * Cancelling a timer and completing a parked branch are local operations, and putting them
     * behind the drain made them hostage to a store that might never answer: the caller's bounded
     * wait would expire, and the timer would stay armed — holding the coordinator, the ingress
     * {@code SecurityContext} and every arrival payload for the configured duration of a traversal
     * that had already ended. {@link #terminate()} therefore performs them unconditionally and
     * up front, and only {@link JoinStore#discard} waits. The cancellation of a timer that is being
     * created at that moment is made safe by {@link LocalJoin}'s own handoff lock rather than by
     * this barrier.</p>
     */
    private final Object gate = new Object();
    private int operationsInFlight;
    private boolean terminated;
    private final CompletableFuture<Void> drained = new CompletableFuture<>();

    /**
     * The one stage every {@link #terminate()} caller receives. FIX-17.
     *
     * <p>Written and read only while holding {@code gate}, which is why it needs no {@code volatile}
     * — and why every {@code return} path in {@link #terminate()} must stay inside that monitor. It
     * is assigned before the first caller leaves the lock, so a second caller can never observe
     * {@code terminated == true} together with a null stage here.</p>
     *
     * <p>Before this existed, the idempotence guard answered a second caller with
     * {@code CompletableFuture.completedFuture(null)} — a stage that was already complete while the
     * first caller was still inside {@link #releaseInMemory}, which is the only thing that ever
     * writes {@link #abandonedBranch}. That contradicted the contract stated on
     * {@link #abandonedBranchFailure()} ("meaningful only once terminate()'s stage has completed"),
     * and {@code GraphRunner} reads it exactly that way, so the second caller saw {@code null} and
     * the traversal reported COMPLETED with no result and its end node never executed.</p>
     */
    private CompletionStage<Void> termination;

    JoinCoordinator(JoinStore store, Scheduler scheduler, ExecutionMonitor monitor,
                    ExecutionMonitor.ExecutionIdentity identity, Map<String, JoinSpec> specs, Clock clock) {
        this(store, scheduler, monitor, identity, specs, clock, () -> { });
    }

    JoinCoordinator(JoinStore store, Scheduler scheduler, ExecutionMonitor monitor,
                    ExecutionMonitor.ExecutionIdentity identity, Map<String, JoinSpec> specs, Clock clock,
                    Runnable timeoutRelinquishedObserver) {
        this.store = store;
        this.scheduler = scheduler;
        this.monitor = monitor;
        this.identity = identity;
        this.specs = specs;
        this.clock = clock;
        this.processInstanceId = identity.processInstanceId();
        this.traversalId = identity.traversalId();
        this.tenantId = identity.security().tenantId();
        this.timeoutRelinquishedObserver = java.util.Objects.requireNonNull(timeoutRelinquishedObserver,
                "timeoutRelinquishedObserver");
    }

    boolean isJoin(String nodeId) {
        return specs.containsKey(nodeId);
    }

    /** Scheduled timeouts that have neither fired nor been cancelled. Must return to zero. */
    int liveTimeoutCount() {
        return liveTimeouts.get();
    }

    /**
     * Joins whose deadline is suspended: a budget is recorded and nothing is scheduled for it.
     *
     * <p>Distinct from {@link #liveTimeoutCount()} rather than folded into it, because they answer
     * different questions and a held join answers them differently. Nothing is scheduled for a held
     * join, so it is correctly absent from the live count — which is what makes "no scheduled task
     * remains" hold during a hold as well as at a terminal state.</p>
     *
     * <p>It exists as a separate reading for the reason the manual scheduler's cancellation mutant
     * exists: without it, "the pause suspended this deadline", "this join never had one" and "this
     * deadline was cancelled for good" are three states behind one zero, and a test could not tell a
     * working suspension from a suspension that quietly did nothing.</p>
     */
    int suspendedTimeoutCount() {
        return (int) locals.values().stream().filter(LocalJoin::isDeadlineHeld).count();
    }

    /**
     * Stops every deadline this traversal's joins are running, keeping what is left of each budget.
     *
     * <p>Called when an operator's hold is installed. The flag and the snapshot are taken under the
     * same monitor {@link #local(String)} creates joins under, which is what makes the sweep total:
     * a join already reached is in {@code reached} and is suspended below, and a join first reached
     * afterwards is born held. There is no third case, and in particular no window in which a join
     * is created between the flag being set and the snapshot being taken.</p>
     *
     * <p>The suspension itself happens outside that monitor. It cancels at the scheduler, and the
     * whole reason {@link LocalJoin#armTimeoutFor(int)} does not hold a lock across a scheduler call
     * applies here word for word.</p>
     *
     * <p>Idempotent: a second hold over an already-held traversal — which
     * {@code GraphRunner.pauseTraversal} refuses anyway — suspends budgets that are already
     * suspended and changes none of them.</p>
     */
    void suspendTimeouts() {
        List<LocalJoin> reached;
        synchronized (gate) {
            joinTimeoutsHeld = true;
            reached = List.copyOf(locals.values());
        }
        for (LocalJoin local : reached) {
            local.holdDeadline();
        }
    }

    /**
     * Re-arms every suspended deadline with exactly the budget it had left when the hold was taken.
     *
     * <p>The mirror of {@link #suspendTimeouts()}, and it clears the flag under the same monitor for
     * the same reason: a join reached after this point arms normally with a full budget, and one
     * reached before it is in the snapshot and is re-armed with what it had left. A join that
     * settled or failed during the hold has already discarded its budget and is re-armed with
     * nothing.</p>
     */
    void resumeTimeouts() {
        List<LocalJoin> reached;
        synchronized (gate) {
            joinTimeoutsHeld = false;
            reached = List.copyOf(locals.values());
        }
        for (LocalJoin local : reached) {
            local.releaseDeadline();
        }
    }

    /**
     * Branches parked across every join this coordinator holds -- arrived, not enough on their own
     * to satisfy the join, and still waiting for an outcome.
     *
     * <p>Sums {@link LocalJoin#liveWaiterCount()} over {@link #locals}, so it goes to zero the
     * instant {@link #terminate()} clears that map, exactly like {@link #liveTimeoutCount()} does
     * for {@link #liveTimeouts}. Unlike a scheduled timeout, a parked branch carries no promise that
     * anything will ever revisit it: nothing but a live worker instance completing this join's
     * quorum, or a timeout firing, can ever settle it. A traversal with this nonzero while both of
     * those are zero has nothing left, anywhere, that could ever move it forward.</p>
     */
    int liveParkedBranchCount() {
        return locals.values().stream().mapToInt(LocalJoin::liveWaiterCount).sum();
    }

    /**
     * Presents one branch result at {@code joinNodeId}.
     *
     * <p>The returned stage carries the decision. A {@link JoinDecision.Wait} branch does not resolve
     * immediately: it resolves when the join does, so the fan-out genuinely waits for the join's
     * outcome instead of completing as soon as every branch has been <em>handed over</em>. Without
     * that, a join that timed out would leave the traversal completing successfully with no result,
     * which is the one outcome a timeout must never produce.</p>
     */
    CompletionStage<JoinDecision> arrive(String joinNodeId, JoinArrival arrival) {
        LocalJoin local = local(joinNodeId);
        if (local == null) {
            return CompletableFuture.completedFuture(
                    new JoinDecision.Discarded(JoinDecision.Discarded.Reason.LATE,
                            arrival.branchId().value()));
        }
        // Keyed by the branch's stable string, which is the predecessor's node id for a whole-output
        // branch, nodeId#ordinal for a child and either of those plus @lap for an arrival on a later
        // firing of this join. The map stays insert-only: ADR 0019's retry argument
        // depends on payloads and branchFailures never losing an entry, so a richer identity may add
        // keys and must never remove or reset one. It is a *richer key*, not a reset one — a second
        // lap adds b0@1 beside b0, it does not overwrite b0.
        local.payloads.put(arrival.branchId().value(), arrival);
        return settle(local, arrival.branchId().value(), arrival.iteration(), JoinBranchOutcome.ARRIVED, true);
    }

    /**
     * Records that {@code branchId} can no longer deliver to {@code joinNodeId}.
     *
     * <p>A failed branch neither counts toward the quorum nor fails the join on its own; it only
     * removes one of the ways the quorum could still be reached. The join fails when what remains is
     * not enough, which for an {@code all} join is the first failure and for a {@code k of n} join
     * may be never.</p>
     */
    CompletionStage<JoinDecision> fail(String joinNodeId, String branchId, Throwable cause) {
        return fail(joinNodeId, branchId, cause, 0);
    }

    /**
     * @param lap which firing of {@code joinNodeId} this branch was going to contribute to, read from
     *            the {@link IterationContext} of the resolution that produced the failure. A
     *            branch does not simply fail — it fails <em>on a lap</em>, and reporting every failure
     *            into bucket 0 would let one broken second lap fail a first lap that had already
     *            succeeded, or be discarded as a duplicate of a bucket-0 failure that is unrelated.
     */
    CompletionStage<JoinDecision> fail(String joinNodeId, String branchId, Throwable cause, int lap) {
        LocalJoin local = local(joinNodeId);
        if (local == null) {
            return CompletableFuture.completedFuture(
                    new JoinDecision.Discarded(JoinDecision.Discarded.Reason.LATE, branchId));
        }
        String key = BranchId.atLap(branchId, lap);
        local.branchFailures.put(key, cause);
        // Deliberately does not park. A failed branch has nothing to continue with, so waiting for
        // the join's outcome would tell it nothing it can act on — and it would replace the decision
        // the caller needs ("was this failure recorded?") with the join's eventual verdict, which is
        // a different question with a different answer.
        return settle(local, key, lap, JoinBranchOutcome.FAILED, false);
    }

    /**
     * Records that {@code branchId} will never be dispatched, so the join must stop counting on it.
     *
     * <p>This is the case a join had no way to hear about before, and its absence did not merely
     * make the join wrong — it made the join <em>non-terminating</em>. A branch that is never taken
     * is not a late arrival, not a timeout and not a failure: nothing on it ever runs, so nothing on
     * it ever reports. The join therefore waited for it forever, and with it the whole traversal,
     * its actors, its payloads and its ingress security context.</p>
     *
     * <p>Like {@link #fail}, it does not park: there is no branch here to continue, only a fact to
     * record. The verdict still reaches the traversal, because the branch that <em>is</em> live is
     * parked on this join and is completed by the same settle.</p>
     */
    CompletionStage<JoinDecision> notTaken(String joinNodeId, String branchId) {
        return notTaken(joinNodeId, branchId, 0);
    }

    /**
     * @param lap which firing of {@code joinNodeId} this branch will not reach, read from the
     *            {@link IterationContext} of the routing decision that proved it dead. A branch
     *            that a decision node declines on the second lap says nothing about the first, and
     *            recording it into bucket 0 would retroactively kill an iteration that had already
     *            completed.
     */
    CompletionStage<JoinDecision> notTaken(String joinNodeId, String branchId, int lap) {
        LocalJoin local = local(joinNodeId);
        if (local == null) {
            return CompletableFuture.completedFuture(
                    new JoinDecision.Discarded(JoinDecision.Discarded.Reason.LATE, branchId));
        }
        return settle(local, BranchId.atLap(branchId, lap), lap, JoinBranchOutcome.NOT_TAKEN, false);
    }

    /**
     * Releases everything this traversal accumulated: pending waiters, scheduled timeouts and the
     * store's records for this traversal's joins.
     *
     * <p>Called on every completion path, successful or not. A traversal that failed still has to
     * discard its join records, or a failing graph would leak one record per join per run — the
     * exact shape of leak that only appears once the system is under real fault load.</p>
     */
    CompletionStage<Void> terminate() {
        boolean alreadyIdle;
        List<LocalJoin> reached;
        CompletableFuture<Void> released;
        CompletionStage<Void> mine;
        synchronized (gate) {
            if (terminated) {
                // The first caller's stage, never a fresh completed one. A second caller that was
                // handed CompletableFuture.completedFuture(null) here could read abandonedBranch
                // before the first caller had written it -- see the field javadoc on `termination`.
                return termination;
            }
            terminated = true;
            alreadyIdle = operationsInFlight == 0;
            reached = List.copyOf(locals.values());
            locals.clear();
            // Assigned before this monitor is released, so `terminated` is never visible without it.
            // `released` expresses as a value the ordering the first caller previously got from
            // statement order alone: the stage cannot complete until releaseInMemory has returned.
            // RE-ENTRANCY, and it is reached today rather than hypothetical. GraphRunner#close ->
            // releaseTraversals() is the first terminate(); its releaseInMemory completes a parked
            // waiter, that completion propagates through allOrFirstFailure to GraphRunner's
            // `.thenCompose(error -> release(traversalId, coordinator))`, and release() calls
            // terminate() again on this same thread while releaseInMemory is still on the stack.
            // Before FIX-17 that re-entrant call got completedFuture(null), so the very next
            // `.handle(...)` read abandonedBranchFailure() before it was written -- and that
            // sequence lies on the ordinary shutdown path every traversal takes, not in a window a
            // test has to construct to reach it.
            // It is safe now because every consumer of this stage COMPOSES on it (thenCompose /
            // handle) instead of blocking: the re-entrant caller registers a continuation and
            // unwinds, releaseInMemory returns, `released` completes, and the chain resumes. The one
            // blocking wait, releaseTraversals' allOf(...).get(shutdownBound), belongs to the first
            // caller and runs after its own releaseInMemory has returned, and is bounded anyway.
            // A consumer that BLOCKS on this stage from inside a parked branch's continuation would
            // deadlock itself; consumers must compose continuations instead.
            released = new CompletableFuture<>();
            termination = released.thenCompose(ignored -> drained)
                    .thenCompose(ignored -> discardRecords(reached));
            mine = termination;
        }
        // Process memory is released here, unconditionally, and NOT behind the drain. Everything
        // below this line is local: cancelling a timer and completing futures this process owns.
        // Putting it after the drain made it conditional on a dependency the caller does not
        // control — a store that never answers left the timeout armed and the parked branches
        // pending, and the runner's bounded wait then walked away from both. The store record is
        // the only thing that genuinely needs the drain, so it is the only thing still behind it.
        releaseInMemory(reached);
        // Set between the call above and completing `released`, and nowhere else: this is the one
        // instant at which every LocalJoin#releaseWaiters() this coordinator will ever run has
        // returned, so `abandonedBranch` cannot change again. See its own Javadoc for why this is a
        // fact about the coordinator rather than a derivative of `released`/`termination`.
        releaseComplete = true;
        released.complete(null);
        if (alreadyIdle) {
            drained.complete(null);
        }
        return mine;
    }

    /**
     * Cancels every armed timeout and releases every parked branch. Runs before the drain, so an
     * operation may still be in flight against the store while it runs.
     *
     * <p><strong>Why an early release cannot strand a branch.</strong> The race to worry about is a
     * settle that has not yet decided {@link JoinDecision.Wait}: it could park on a waiter list that
     * has already been drained and wait for a completion that will never come. It cannot.
     * {@link LocalJoin#completeAllWaiters} sets {@code releasedEveryBucket} while holding
     * {@code waitersLock}, and {@link LocalJoin#waiter(int)} re-reads that same field while holding the
     * same monitor. So a branch that reaches {@code waiter(lap)} after this method has run observes
     * {@code releasedEveryBucket} and returns {@code Discarded(LATE)} immediately instead of adding
     * itself to the list — including on a bucket that did not exist when the release ran, which is the
     * interleaving the flag exists for and that a per-bucket flag alone would miss. Either
     * the branch parked before the release and was completed by it, or it parks never and is
     * answered inline; there is no third interleaving. This is the same guard that already protects
     * a branch racing an ordinary settle, so no new ordering is being relied on.
     *
     * <p><strong>Why an early cancel cannot orphan a timer.</strong> Cancellation and the assignment
     * of the {@code timeout} handle are two halves of one handoff under {@link LocalJoin}'s own
     * lock — see {@link LocalJoin#armTimeoutFor(int)}. Whichever half runs second performs the
     * cancellation, so a timer created after this method has run is cancelled by the thread that
     * created it rather than left armed.
     */
    private void releaseInMemory(List<LocalJoin> reached) {
        for (LocalJoin local : reached) {
            local.cancelTimeout();
            local.releaseWaiters();
        }
    }

    /**
     * Discards this traversal's records. Behind the drain, because a settle already inside its
     * compare-and-set must not be allowed to write after the discard and retain the record forever.
     */
    private CompletionStage<Void> discardRecords(List<LocalJoin> reached) {
        var discards = new ArrayList<CompletableFuture<?>>();
        for (LocalJoin local : reached) {
            discards.add(store.discard(local.key).toCompletableFuture()
                    // A discard that fails must not fail the traversal: the record is then an orphan
                    // that purgeSettledBefore reclaims, which is the same path a crashed runtime
                    // leaves behind and is therefore already covered.
                    .exceptionally(ignored -> Boolean.FALSE));
        }
        return CompletableFuture.allOf(discards.toArray(CompletableFuture[]::new));
    }

    private LocalJoin local(String joinNodeId) {
        JoinSpec spec = specs.get(joinNodeId);
        if (spec == null) {
            throw new IllegalStateException("Node " + joinNodeId + " is not a fan-in join");
        }
        LocalJoin local;
        // Checked and created under the same lock termination takes, so a join entry cannot be
        // created after the sweep that was supposed to remove it. The entry would have been
        // harmless — no store record, no scheduled timeout, and it dies with this coordinator — but
        // "bounded because nothing important escapes" is a weaker claim than "cannot be created".
        synchronized (gate) {
            if (terminated) {
                return null;
            }
            // Born held when the traversal is held, and seeded here rather than pushed afterwards
            // because a join first reached during a hold has no arming for the pause sweep to have
            // found. Reading the flag inside this monitor is what pairs it with the sweep's own
            // write: a join created before the flag was set is in the snapshot the sweep took, and
            // one created after it reads the value the sweep wrote.
            local = locals.computeIfAbsent(joinNodeId, ignored -> new LocalJoin(spec,
                    new JoinKey(tenantId, processInstanceId, traversalId, joinNodeId),
                    joinTimeoutsHeld));
        }
        // NO DEADLINE IS ARMED HERE, and that is a correction rather than an omission. This method is
        // reached by every report — including a straggler of an iteration that
        // has already fired — and it does not know which iteration the report belongs to. Arming from
        // the in-memory `firedThrough + 1` therefore armed a deadline on behalf of a branch that was
        // about to be answered LATE, for a bucket that on an acyclic graph will never receive
        // anything. The record stays OPEN because the join re-arms, so the timer could not tell that
        // the join was finished, and it failed a `k of n` join that had already succeeded.
        //
        // The arming now lives in attemptSettle, after the report's own lap has been compared with
        // the bucket the record says is being filled. A bucket with no report is not incomplete, it
        // does not exist, and it has no deadline.
        return local;
    }

    /** Claims a slot in the drain barrier, or refuses because the traversal is over. */
    private boolean enterOperation() {
        synchronized (gate) {
            if (terminated) {
                return false;
            }
            operationsInFlight++;
            return true;
        }
    }

    private void leaveOperation() {
        boolean drainedNow;
        synchronized (gate) {
            operationsInFlight--;
            drainedNow = terminated && operationsInFlight == 0;
        }
        if (drainedNow) {
            drained.complete(null);
        }
    }

    private CompletionStage<JoinDecision> settle(LocalJoin local, String branchId, int lap,
                                                 JoinBranchOutcome outcome, boolean parkWhileWaiting) {
        if (!enterOperation()) {
            return CompletableFuture.completedFuture(
                    new JoinDecision.Discarded(JoinDecision.Discarded.Reason.LATE, branchId));
        }
        return attemptSettle(local, branchId, lap, outcome, 1)
                .whenComplete((ignored, error) -> leaveOperation())
                .thenCompose(decision -> switch (decision) {
            case JoinDecision.Wait waiting -> parkWhileWaiting
                    ? local.waiter(lap)
                    : CompletableFuture.<JoinDecision>completedFuture(waiting);
            case JoinDecision.Proceed proceed -> {
                // Per bucket, both of them. The deadline is re-armed for the bucket now being filled
                // rather than cancelled for good, and only the branches parked on THIS lap are
                // released — a branch parked on lap k+1 (which cannot exist yet) or a branch that
                // parks on lap k+1 a moment from now is not answered by lap k's firing.
                local.releaseTimeoutFor(lap);
                local.completeWaiters(lap, null);
                yield CompletableFuture.completedFuture(proceed);
            }
            case JoinDecision.Failed failed -> {
                // A failure is terminal for the whole join, not for one lap: the record is FAILED and
                // no further bucket can ever fire, so every parked branch on every bucket is owed the
                // verdict.
                local.cancelTimeout();
                local.completeAllWaiters(failed.failure());
                yield CompletableFuture.completedFuture(failed);
            }
            case JoinDecision.Discarded discarded -> CompletableFuture.completedFuture(discarded);
        });
    }

    private CompletionStage<JoinDecision> attemptSettle(LocalJoin local, String branchId, int lap,
                                                        JoinBranchOutcome outcome, int attempt) {
        if (attempt > MAX_CAS_ATTEMPTS) {
            return CompletableFuture.failedFuture(new JoinStoreException(
                    JoinStoreException.Reason.UNAVAILABLE, local.key,
                    "join " + local.key.joinNodeId() + " did not converge after " + MAX_CAS_ATTEMPTS
                            + " compare-and-set attempts"));
        }
        // Stamped once per bucket, on whichever report reaches it first, and never overwritten by a
        // compare-and-set retry. This is what PLAT-01's joinWait measures from now that a firing does
        // not write settledAt: the record's own openedAt is the instant the FIRST lap opened, so
        // reusing it would report lap 3's wait as the whole age of the traversal.
        local.bucketOpenedAt.putIfAbsent(lap, clock.instant());
        return store.load(local.key).thenCompose(found -> {
            JoinRecord current = found.orElseGet(() -> JoinRecord.opening(local.key, clock.instant()));
            // Both terminal phases mean "this join is over", and for a long time both were answered
            // the same way. They are not the same answer. SATISFIED means the downstream work
            // already ran, so a branch arriving now genuinely has nothing to do and is discarded.
            // FAILED means a verdict of failure was reached — and if the process that reached it
            // died before delivering it, this arrival is the first one in a position to carry it.
            // Discarding it instead handed the traversal a silent success: no result, the end node
            // never executed, and nobody told that the join had failed.
            //
            // Re-arming narrows what SATISFIED can mean without changing what it does here. A join that
            // re-arms never writes it, so a record in this phase is either a one-shot join (not
            // reachable yet) or one written by an earlier runtime. Both mean "fired at
            // bucket 0 and never again", and both answer an arrival the same way they always did.
            if (current.phase() == JoinPhase.SATISFIED) {
                publishDiscard(local, branchId, JoinDecision.Discarded.Reason.LATE);
                return CompletableFuture.completedFuture(
                        new JoinDecision.Discarded(JoinDecision.Discarded.Reason.LATE, branchId));
            }
            if (current.phase() == JoinPhase.FAILED) {
                // Read, not rewritten: the record is already terminal and already correct, so this
                // path performs no compare-and-set at all. It republishes nothing either — the
                // failure was announced when it was decided, and announcing it once per redelivered
                // branch would turn one join failure into as many as the graph has branches.
                return CompletableFuture.completedFuture(
                        new JoinDecision.Failed(recordedFailure(local, current)));
            }
            // The bucket this join is currently filling. Buckets fire strictly in order, because an
            // arrival can only carry lap k+1 if it descends causally from this join firing at lap k
            // (see IterationContext), so "the oldest unfired bucket" is always exactly this one and a
            // scalar marker is enough to name it.
            int firedThrough = firedThrough(current);
            int target = firedThrough + 1;
            if (lap < target) {
                // A redelivery into a bucket that already continued downstream. This is the case
                // JoinPhase.SATISFIED used to answer for the whole join, and it is why firedThrough
                // has to be persisted: after a restart the record of a fired bucket is otherwise
                // indistinguishable from the record of one still being filled, and the redelivery
                // would fire it a second time and run everything downstream twice.
                publishDiscard(local, branchId, JoinDecision.Discarded.Reason.LATE);
                return CompletableFuture.completedFuture(
                        new JoinDecision.Discarded(JoinDecision.Discarded.Reason.LATE, branchId));
            }
            // The one place a deadline is armed, and it is here rather than anywhere earlier because
            // here is the first point that knows the report belongs to the bucket being filled. A
            // straggler of an already-fired iteration has been answered above and arms nothing; a
            // report of a bucket beyond the one being filled — which the causal token makes
            // unreachable from a traversal, and which a coordinator-level caller can still mint —
            // arms nothing either, because the bucket it would guard has said nothing yet.
            //
            // Idempotent and monotone: refused when a deadline for this bucket or a later one is
            // already armed, so one iteration gets one deadline however many compare-and-set retries
            // it takes, and an acyclic graph schedules exactly what it scheduled before re-arming.
            if (lap == target) {
                local.armTimeoutFor(target);
            }
            // A recorded NOT_TAKEN is provisional: it was written by a predecessor that proved the
            // branch dead, without the branch itself taking part. If the branch then genuinely
            // arrives or fails, it has spoken for itself and overrides that conclusion. Nothing
            // moves the other way, so a branch's outcome is still monotone and a real arrival can
            // still only be counted once — once PER ITERATION, which is the same
            // invariant over a richer key rather than a weaker invariant.
            JoinBranchOutcome recorded = current.branches().get(branchId);
            boolean duplicate = recorded != null && !supersedes(outcome, recorded);
            var content = duplicate ? current.branches() : current.plus(branchId, outcome);
            JoinDecision decision = evaluate(local, content, target);

            if (duplicate && decision instanceof JoinDecision.Wait) {
                // Nothing to write: the branch already had an outcome and the refreshed payload did
                // not change the verdict. Writing anyway would burn a revision and make every
                // redelivery a lost race for whichever branch was mid-decision. Per bucket now, and
                // that is what keeps it true: without the lap in the key every second-lap arrival
                // would look like a duplicate of the first lap's and burn a revision each time.
                publishDiscard(local, branchId, JoinDecision.Discarded.Reason.DUPLICATE);
                return CompletableFuture.completedFuture(
                        new JoinDecision.Discarded(JoinDecision.Discarded.Reason.DUPLICATE, branchId));
            }

            boolean fires = decision instanceof JoinDecision.Proceed;
            JoinRecord desired = switch (decision) {
                // The two shapes of a firing, and the whole of what JoinSpec.rearm selects. A join
                // that re-arms records how far it has fired and stays OPEN, so the next lap's
                // arrivals are first arrivals rather than late ones; a one-shot join writes the
                // terminal phase, which is exactly the behaviour a one-shot join has.
                case JoinDecision.Proceed ignored -> local.spec.rearm()
                        ? current.next(content, JoinPhase.OPEN, null, null, target)
                        : current.next(content, JoinPhase.SATISFIED, clock.instant(), null, target);
                // The reason rides along in this same write rather than in a second one, so there
                // is no interval in which the store says FAILED without saying why. Every origin of
                // failure goes through here — a quorum proven unreachable and a branch that is
                // never taken alike — so a re-emerged verdict names its own cause and not a
                // stand-in for it.
                case JoinDecision.Failed failure -> current.next(content, JoinPhase.FAILED,
                        clock.instant(), persisted(failure.failure().reason()));
                default -> current.next(content, JoinPhase.OPEN, null);
            };
            return store.compareAndSet(desired)
                    .<JoinDecision>thenApply(stored -> {
                        if (fires) {
                            // In-memory mirror of the marker just persisted, read by releaseWaiters
                            // and by the timeout to know which bucket they are talking about.
                            local.noteFired(target);
                            monitor.joinSatisfied(identity, local.key.joinNodeId(), local.spec.quorum(),
                                    bucketOf(stored.branches(), target).values().stream()
                                            .filter(JoinBranchOutcome.ARRIVED::equals).count(),
                                    local.bucketWait(target, clock.instant()));
                        } else if (decision instanceof JoinDecision.Failed failed) {
                            monitor.joinFailed(identity, local.key.joinNodeId(), failed.failure(), joinWait(stored));
                        }
                        reportIterationBacklog(local, stored);
                        return decision;
                    })
                    .exceptionallyCompose(error -> isConflict(error)
                            ? attemptSettle(local, branchId, lap, outcome, attempt + 1)
                            : CompletableFuture.failedFuture(error));
        });
    }

    /**
     * How far {@code record} says its join has already fired, as an index, with {@code -1} for never.
     *
     * <p>Three shapes of record answer this, and only the first is written by a runtime that has
     * The marker is present and is the answer; the marker is absent and the phase is
     * {@link JoinPhase#SATISFIED}, which is a legacy one-shot firing and therefore bucket 0; the
     * marker is absent and the phase is not satisfied, which is a join that has never fired. No
     * migration turns the second and third into the first, and none is needed — records produced
     * without cyclic execution cannot hold a lap above zero.</p>
     */
    private static int firedThrough(JoinRecord record) {
        Integer marker = record.firedThrough();
        if (marker != null) {
            return marker;
        }
        return record.phase() == JoinPhase.SATISFIED ? 0 : -1;
    }

    /** The branch outcomes of one iteration, re-keyed by the bare branch names {@link JoinSpec} lists. */
    private static Map<String, JoinBranchOutcome> bucketOf(Map<String, JoinBranchOutcome> content, int lap) {
        var bucket = new java.util.LinkedHashMap<String, JoinBranchOutcome>();
        content.forEach((key, outcome) -> {
            if (BranchId.lapIn(key) == lap) {
                bucket.put(BranchId.branchIn(key), outcome);
            }
        });
        return bucket;
    }

    /**
     * Publishes the iteration diagnostic when this join crosses the threshold.
     *
     * <h2>What is counted, and why it is not the initially proposed metric</h2>
     * <p>The design called for the count of <em>unfired</em> iterations. That number is provably
     * {@code <= 1} and can never cross any threshold above one, so the event it would produce is
     * unreachable from an executing graph — an alarm an operator would build and that would never
     * fire. The proof is short and worth stating because it is a property of the correlation rather
     * than of this method: the only writer of a join's own lap counter is
     * {@code IterationContext.firing(J, k)}, which {@code GraphRunner} calls with the bucket J has
     * just fired, and it writes {@code k + 1}. Every other hop inherits the context unchanged and the
     * fan-in merge takes a pointwise maximum over contributors that all read the same lap for J. So no
     * message can present itself at J carrying a lap above {@code firedThrough + 1}, no bucket beyond
     * the one being filled can hold a report, and "iterations waiting" is always zero or one.
     *
     * <p>This diagnostic makes the real growth visible: one bucket of arrival payloads is retained <em>per lap</em>
     * for the life of the traversal, because ADR 0019's insert-only invariant forbids compacting the
     * buckets that have already fired. So the number reported is the count of iterations this join is
     * <strong>retaining</strong>, which is the quantity that actually grows and the one an operator
     * would act on. Everything else the design fixed is unchanged: diagnostic only, an internal
     * threshold rather than a contract, and one event per crossing.
     *
     * <p>Because the record is insert-only, the count is monotone within a traversal: it crosses once
     * and never returns, so the latch below is set once and never cleared. That is stated rather than
     * left to be inferred from an {@code else} branch nothing can reach.
     *
     * <p>Diagnostic only. Nothing here refuses an arrival, fails a join or applies back pressure:
     * no such behaviors are currently specified.
     */
    private void reportIterationBacklog(LocalJoin local, JoinRecord stored) {
        int highest = 0;
        for (String key : stored.branches().keySet()) {
            highest = Math.max(highest, BranchId.lapIn(key));
        }
        int retained = highest + 1;
        if (retained > ITERATION_BACKLOG_THRESHOLD && local.backlogReported.compareAndSet(false, true)) {
            monitor.joinIterationBacklog(identity, local.key.joinNodeId(), retained,
                    ITERATION_BACKLOG_THRESHOLD);
        }
    }

    /**
     * Decides the join from the record it would produce plus the payloads this process holds.
     *
     * <p>The quorum test is over branches that are recorded {@code ARRIVED} <em>and</em> whose
     * payload is available here. Those two sets differ only after a restart, and that is exactly the
     * case that matters: a branch the store says arrived, whose payload died with the previous
     * process, must not be counted as if the join could produce a result from it. The join waits for
     * the redelivery instead, which is what makes recovery correct rather than merely non-crashing.
     * </p>
     */
    private JoinDecision evaluate(LocalJoin local, Map<String, JoinBranchOutcome> content, int target) {
        JoinSpec spec = local.spec;
        var ready = new ArrayList<JoinArrival>();
        var arrived = new ArrayList<String>();
        var failed = new ArrayList<String>();
        var notTaken = new ArrayList<String>();
        // Only the bucket being filled. Everything below counts branches against spec.branchCount(),
        // and a whole-record count would see every lap's arrivals at once: three laps of a three-branch
        // join would look like nine arrivals against three branches, so the quorum would appear met by
        // branches that belong to iterations that already fired. The bucket is also re-keyed to the
        // bare branch names, so outstandingBranches keeps comparing like with like.
        var bucket = bucketOf(content, target);
        bucket.forEach((branch, outcome) -> {
            switch (outcome) {
                case ARRIVED -> {
                    arrived.add(branch);
                    JoinArrival held = local.payloads.get(BranchId.atLap(branch, target));
                    if (held != null) {
                        ready.add(held);
                    }
                }
                case FAILED -> failed.add(branch);
                case NOT_TAKEN -> notTaken.add(branch);
            }
        });
        int outstanding = spec.branchCount() - bucket.size();

        if (ready.size() >= spec.quorum()) {
            // Every ready arrival is carried, not just the first k. Discarding a result that
            // genuinely arrived, because a counter was already satisfied, loses data for nothing.
            // Order is by branch id so the merged payload is the same on every run; the previous
            // implementation ordered by parent invocation UUID, which is random, so an `all` join
            // produced its list in a different order on each execution.
            return new JoinDecision.Proceed(ready);
        }
        if (arrived.size() + outstanding < spec.quorum()) {
            return new JoinDecision.Failed(
                    unreachable(local, target, arrived, failed, outstandingBranches(spec, bucket), notTaken));
        }
        return new JoinDecision.Wait();
    }

    private JoinFailureException unreachable(LocalJoin local, int lap, List<String> arrived, List<String> failed,
                                             List<String> outstanding, List<String> notTaken) {
        // Nothing failed and branches were never taken: the quorum was unreachable from the moment
        // the graph was written, not from the moment something broke. Naming that separately is what
        // turns an operator's search for a missing exception into a look at the join's policy.
        JoinFailureException.Reason reason = failed.isEmpty() && !notTaken.isEmpty()
                ? JoinFailureException.Reason.BRANCH_NOT_TAKEN
                : JoinFailureException.Reason.QUORUM_UNREACHABLE;
        var failure = new JoinFailureException(reason, local.key.joinNodeId(), local.spec.quorum(),
                arrived, failed, outstanding, notTaken);
        // The branch names in the verdict are bare, because that is what an operator reading it should
        // see; the causes are held under the lap-qualified key, because that is what distinguishes the
        // failure of this iteration from the failure of a previous one.
        failed.stream()
                .map(branch -> local.branchFailures.get(BranchId.atLap(branch, lap)))
                .filter(java.util.Objects::nonNull)
                .forEach(failure::addSuppressed);
        return failure;
    }

    /**
     * Rebuilds the verdict a previous process recorded, so a branch redelivered after a restart
     * carries the join's own failure instead of a manufactured one.
     *
     * <p>Everything here comes from the record and the graph: the reason and the branch outcomes are
     * what was persisted, the quorum and the branch list are what the graph says. Nothing is
     * inferred.</p>
     *
     * <p><b>The suppressed branch exceptions are attached when this process still holds them, and
     * only then.</b> FIX-16. The omission this method used to make unconditionally describes
     * exactly one situation: <em>a cross-process restart</em>, where the causes lived in the process
     * that died and cannot be recovered from a record that never carried them. There the loop below
     * finds an empty {@link LocalJoin#branchFailures} and is a no-op, so the deliberately thinner
     * verdict survives untouched — that behaviour is unchanged and intended.
     *
     * <p>It does <em>not</em> describe the in-process redelivery path, which reaches this same method
     * from {@code attemptSettle} when a sibling branch reports into an already-{@code FAILED} join.
     * There the causes are still in memory, and discarding them produced two non-equivalent verdicts
     * for one join failure: whichever reached the traversal first decided whether the branch's own
     * error survived. Measured at roughly 1% of failing traversals, and the marker on the losing path
     * read {@code inMemoryCauses=[b1]} — the cause was in memory at that instant and was dropped
     * anyway.
     *
     * <p><b>Do not "restore" the unconditional discard.</b> It reads like a deliberate choice to keep
     * the rebuilt verdict thin in all cases; it was a statement about what is <em>possible</em> after
     * a restart, not a preference. See {@code JoinSemanticsTest} and
     * {@code JoinVerdictEquivalenceTest}.
     */
    private JoinFailureException recordedFailure(LocalJoin local, JoinRecord record) {
        // The iteration that failed is the one that was being filled: a firing advances firedThrough
        // and leaves the record OPEN, so a FAILED record failed on the bucket after the last one that
        // fired. Reading the whole record instead would report every branch of every completed
        // iteration as a contributor to a failure that happened on one of them.
        int lap = firedThrough(record) + 1;
        var bucket = bucketOf(record.branches(), lap);
        var arrived = new ArrayList<String>();
        var failed = new ArrayList<String>();
        var notTaken = new ArrayList<String>();
        bucket.forEach((branch, outcome) -> {
            switch (outcome) {
                case ARRIVED -> arrived.add(branch);
                case FAILED -> failed.add(branch);
                case NOT_TAKEN -> notTaken.add(branch);
            }
        });
        var rebuilt = new JoinFailureException(recovered(record.failureReason()), local.key.joinNodeId(),
                local.spec.quorum(), arrived, failed, outstandingBranches(local.spec, bucket),
                notTaken);
        failed.stream()
                .map(branch -> local.branchFailures.get(BranchId.atLap(branch, lap)))
                .filter(java.util.Objects::nonNull)
                .forEach(rebuilt::addSuppressed);
        return rebuilt;
    }

    /**
     * The runtime's reason as the persistence port states it.
     *
     * <p>Exhaustive and without a {@code default}, so a reason added to either enum is a compile
     * error here rather than a value that quietly persists as something else.</p>
     */
    private static JoinFailureReason persisted(JoinFailureException.Reason reason) {
        return switch (reason) {
            case QUORUM_UNREACHABLE -> JoinFailureReason.QUORUM_UNREACHABLE;
            case BRANCH_NOT_TAKEN -> JoinFailureReason.BRANCH_NOT_TAKEN;
            case TIMEOUT -> JoinFailureReason.TIMEOUT;
            // UNRECORDED describes a record that was read, never one that is being written: it is
            // produced only by recovered() below, and a verdict recovered from a record is
            // re-delivered rather than re-persisted.
            case UNRECORDED -> throw new IllegalStateException(
                    "an unrecorded reason is never written back to a join record");
        };
    }

    /**
     * The persisted reason as the runtime states it, including the absence of one.
     *
     * <p>{@code null} is what a record written before the reason was persisted looks like. It
     * becomes {@link JoinFailureException.Reason#UNRECORDED} rather than a guess: the join did fail,
     * so the verdict is still delivered, and it says the cause was not recorded instead of naming
     * one nothing observed.</p>
     */
    private static JoinFailureException.Reason recovered(JoinFailureReason reason) {
        if (reason == null) {
            return JoinFailureException.Reason.UNRECORDED;
        }
        return switch (reason) {
            case QUORUM_UNREACHABLE -> JoinFailureException.Reason.QUORUM_UNREACHABLE;
            case BRANCH_NOT_TAKEN -> JoinFailureException.Reason.BRANCH_NOT_TAKEN;
            case TIMEOUT -> JoinFailureException.Reason.TIMEOUT;
        };
    }

    /** Whether an incoming outcome overrides one already recorded. Only NOT_TAKEN is overridable. */
    private static boolean supersedes(JoinBranchOutcome incoming, JoinBranchOutcome recorded) {
        return recorded == JoinBranchOutcome.NOT_TAKEN && incoming != JoinBranchOutcome.NOT_TAKEN;
    }

    /**
     * The branches of one iteration that have said nothing yet.
     *
     * <p>{@code bucket} must already be one iteration's worth, keyed by the bare names
     * {@link JoinSpec#branches()} lists — {@link #bucketOf} is what produces that. Passing the whole
     * record instead would compare {@code b0} against keys like {@code b0@2} and report every branch
     * of the graph as outstanding on every lap after the first.</p>
     */
    private static List<String> outstandingBranches(JoinSpec spec, Map<String, JoinBranchOutcome> bucket) {
        return spec.branches().stream().filter(branch -> !bucket.containsKey(branch)).toList();
    }

    private void publishDiscard(LocalJoin local, String branchId, JoinDecision.Discarded.Reason reason) {
        monitor.joinArrivalDiscarded(identity, local.key.joinNodeId(), branchId, reason.name());
    }

    /**
     * How long {@code stored} waited between opening and settling (PLAT-01).
     *
     * <p>{@link JoinRecord#next} always carries {@code openedAt} forward unchanged from the record it
     * supersedes, so this is the same instant the join was first written, however many
     * compare-and-set retries it took to reach a terminal phase. Called only where {@code stored} is
     * already known terminal (FAILED, or SATISFIED on a one-shot join), so {@code settledAt} is never
     * absent here — {@link JoinRecord}'s own constructor already refuses a terminal record without
     * one.
     *
     * <p><b>A firing no longer comes through here.</b> A join that re-arms stays
     * {@link JoinPhase#OPEN}, so it has no {@code settledAt} to subtract and this method would have
     * thrown a {@link NullPointerException} on every {@code JOIN_SATISFIED} it published. The per-lap
     * duration is measured in memory instead, from the instant the bucket's first report was seen to
     * the instant it fired — see {@link LocalJoin#bucketWait}. On the first lap the two definitions
     * coincide, which is why {@code JoinWaitDurationTest} reads the same number it always did.</p>
     */
    private static Duration joinWait(JoinRecord stored) {
        return Duration.between(stored.openedAt(), stored.settledAt());
    }

    private static boolean isConflict(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof JoinStoreException failure
                    && failure.reason() == JoinStoreException.Reason.CONCURRENCY_CONFLICT) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * The timeout path: settle the join as failed if it is still open when the deadline passes.
     *
     * <p>A timeout fires exactly once and is never re-armed, so it gets exactly one chance to settle
     * the join — which is why a compare-and-set conflict here has to be retried rather than
     * swallowed. A conflict does <em>not</em> imply that a branch settled the join. The far more
     * common concurrent write is a branch recording its arrival and leaving the phase
     * {@link JoinPhase#OPEN}, and treating that as "the branch won" abandoned a timeout against a
     * join that was still open, had just lost its only deadline, and would then wait forever.</p>
     *
     * <p>Abandoning is correct only when the reloaded phase is genuinely terminal, which the reload
     * at the top of each attempt is what establishes.</p>
     *
     * <h2>Per-iteration deadlines and their unchanged invariants</h2>
     * <p>A deadline is no longer the join's; it is <em>one iteration's</em>. A join that re-arms
     * cancels the deadline of the bucket it just fired and arms a fresh one for the bucket now being
     * filled, so a cycle that runs for an hour is not measured against a single deadline armed at the
     * first arrival. Everything above still holds inside one bucket.
     *
     * <p>What that costs is that "the record is terminal" is no longer sufficient to know the deadline
     * has nothing to report — a fired bucket leaves the record {@link JoinPhase#OPEN}. So the guard is
     * the bucket itself: the task carries the bucket it was armed for, and abandons if the reloaded
     * record says that bucket has already fired. Without it, a timer that fired in the instant before
     * its cancellation would fail the join over an iteration that had just succeeded.
     *
     * @param armedFor    the iteration this deadline was guarding
     * @param generation  the arming this task belongs to, so a task that fired just as it was being
     *                    replaced does not act on behalf of the deadline that replaced it
     */
    private void onTimeout(LocalJoin local, int armedFor, int generation) {
        liveTimeouts.decrementAndGet();
        if (!local.isCurrentTimeoutGeneration(generation)) {
            // Already superseded by a re-arm or by termination. Cheap and local; the authoritative
            // check is the bucket comparison in attemptTimeout, which also covers the case of a
            // generation that is current here and stale by the time the record is read.
            return;
        }
        // The same drain barrier the settle path uses. A timeout that has already fired cannot be
        // cancelled, so without this its store write could land after discardEverything and retain
        // the record the traversal was closed to release.
        if (!enterOperation()) {
            return;
        }
        attemptTimeout(local, armedFor, 1).whenComplete((ignored, error) -> leaveOperation());
    }

    private CompletionStage<Void> attemptTimeout(LocalJoin local, int armedFor, int attempt) {
        if (attempt > MAX_CAS_ATTEMPTS) {
            return CompletableFuture.completedFuture(null);
        }
        return store.load(local.key).thenCompose(found -> {
            JoinRecord current = found.orElse(null);
            if (current == null || current.phase().terminal()) {
                // Genuinely settled by someone else. The join has an outcome and the timeout has
                // nothing left to report.
                return CompletableFuture.<Void>completedFuture(null);
            }
            if (firedThrough(current) >= armedFor) {
                // The iteration this deadline guarded has fired. The record is still OPEN because the
                // join re-armed, so the phase check above cannot see this and the marker has to.
                return CompletableFuture.<Void>completedFuture(null);
            }
            var bucket = bucketOf(current.branches(), armedFor);
            var arrived = new ArrayList<String>();
            var failed = new ArrayList<String>();
            var notTaken = new ArrayList<String>();
            bucket.forEach((branch, outcome) -> {
                switch (outcome) {
                    case ARRIVED -> arrived.add(branch);
                    case FAILED -> failed.add(branch);
                    case NOT_TAKEN -> notTaken.add(branch);
                }
            });
            var failure = new JoinFailureException(JoinFailureException.Reason.TIMEOUT,
                    local.key.joinNodeId(), local.spec.quorum(), arrived, failed,
                    outstandingBranches(local.spec, bucket), notTaken);
            return store.compareAndSet(current.next(current.branches(), JoinPhase.FAILED, clock.instant(),
                            persisted(failure.reason())))
                    .<Void>thenApply(stored -> {
                        monitor.joinFailed(identity, local.key.joinNodeId(), failure, joinWait(stored));
                        // Every bucket, not only the one that expired: the record is FAILED, so no
                        // later bucket can ever fire either and a branch parked on one would wait
                        // forever for an answer that is already decided.
                        local.completeAllWaiters(failure);
                        return null;
                    })
                    .exceptionallyCompose(error -> isConflict(error)
                            ? attemptTimeout(local, armedFor, attempt + 1)
                            : CompletableFuture.<Void>completedFuture(null));
        }).exceptionally(ignored -> null);
    }

    /** Per-join state for this traversal. One instance per fan-in node the traversal reaches. */
    private final class LocalJoin {
        private final JoinSpec spec;
        private final JoinKey key;
        /** Branch id to its latest delivered result. At most {@link JoinSpec#branchCount()} entries. */
        private final ConcurrentHashMap<String, JoinArrival> payloads = new ConcurrentHashMap<>();
        /** Branch id to the error that stopped it. At most {@link JoinSpec#branchCount()} entries. */
        private final ConcurrentHashMap<String, Throwable> branchFailures = new ConcurrentHashMap<>();
        /**
         * Branches parked in {@link JoinDecision.Wait}, <strong>by iteration</strong>.
         *
         * <p>The latch used to be one-shot for the whole join, which is right while a join fires once
         * and wrong the moment it re-arms: the second lap's branches would find it already tripped and
         * be answered {@code LATE} inline instead of parking, and a bucket left incomplete at the end
         * of the traversal would then have nothing parked for {@link #releaseWaiters()} to find — so
         * the traversal would report success with an iteration silently dropped, which is exactly the
         * same lost-terminal defect, moved one layer down.</p>
         *
         * <p>At most {@link JoinSpec#branchCount()} entries per bucket, and at most one bucket per lap
         * the traversal actually reaches. Guarded by {@link #waitersLock}.</p>
         */
        private final Map<Integer, WaiterBucket> waiters = new java.util.LinkedHashMap<>();
        /** Guards {@link #waiters} and {@link #releasedEveryBucket}. */
        private final Object waitersLock = new Object();
        /**
         * Set by {@link #releaseWaiters()} so that a branch arriving at {@link #waiter(int)} after the
         * traversal ended is answered inline rather than parking on a bucket created after the sweep.
         *
         * <p>With one shared latch this was implicit: a bucket that does not exist could not be
         * missed. With a map, a branch deciding {@code Wait} concurrently with the release could
         * create a fresh entry a moment after the release iterated the map, and park on it forever.
         * The flag closes that under the same monitor the release and the park both take, which is the
         * ordering {@link JoinCoordinator#releaseInMemory} already documents.</p>
         */
        private boolean releasedEveryBucket;
        /** When each bucket's first report was seen, for the per-lap PLAT-01 duration. */
        private final ConcurrentHashMap<Integer, java.time.Instant> bucketOpenedAt = new ConcurrentHashMap<>();
        /** In-memory mirror of {@link JoinRecord#firedThrough()}; {@code -1} until this join fires. */
        private volatile int firedThrough = -1;
        /** Whether the iteration diagnostic has already been published; set once, never cleared. */
        private final AtomicBoolean backlogReported = new AtomicBoolean();
        /**
         * Guards the {@link #timeout} handle, {@link #timeoutRelinquished},
         * {@link #timeoutArmedFor}, {@link #timeoutGeneration} and the three budget fields
         * {@link #held}, {@link #armedBudget}, {@link #armedAt} and {@link #suspendedBudget}, and
         * nothing else.
         *
         * <p>Everything a pause needs to decide about one join's deadline lives under this one
         * monitor — deliberately, and it is the reason the hold flag is mirrored here rather than
         * read from the coordinator. A flag on the coordinator and a budget on the join are two
         * locks, and "is this join held?" and "how much budget does it have left?" would then be
         * answerable only as two observations that a pause landing between them can separate: the
         * arming thread reads "not held", the pause records nothing because no handle is published
         * yet, and the handle is published a moment later against a traversal that is holding. One
         * monitor makes that one decision instead.</p>
         */
        private final Object timeoutLock = new Object();
        private ScheduledTask timeout;
        /**
         * The highest iteration a deadline has been armed for, or {@code -1} for none.
         *
         * <p>Replaces the {@code timeoutScheduled} boolean, which could express "this join has had its
         * one deadline" and nothing else. Comparing against a bucket says the same thing for a join
         * that fires once and the right thing for one that re-arms.</p>
         */
        private int timeoutArmedFor = -1;
        /** Set once this join no longer wants a deadline at all, whether one was armed yet or not. */
        private boolean timeoutRelinquished;
        /**
         * Which arming the currently-wanted deadline belongs to.
         *
         * <p>{@link #timeoutRelinquished} alone cannot express a re-arm: it says "never again", and a
         * re-arm needs "not that one, this one". Bumping a counter under the same lock lets the
         * handoff in {@link #armTimeout} stay symmetric — the loser still cancels — while a deadline
         * replaced by a firing is distinguishable from a deadline the join has given up on.</p>
         */
        private int timeoutGeneration;
        /**
         * Whether this join's traversal is currently held by an operator, so that its deadline must
         * not be live.
         *
         * <p>Mirrored from {@link JoinCoordinator#joinTimeoutsHeld} rather than read from it, for the
         * reason given on {@link #timeoutLock}. It is seeded at construction, under the coordinator's
         * {@code gate}, so a join first reached <em>during</em> a hold is born held and arms nothing;
         * it is pushed thereafter by {@link JoinCoordinator#suspendTimeouts()} and
         * {@link JoinCoordinator#resumeTimeouts()}.</p>
         */
        private boolean held;
        /**
         * The budget the currently-armed (or currently-suspended) deadline was given, or
         * {@code null} when this join has no deadline at all.
         *
         * <p>Not the same as {@link JoinSpec#timeout()} once a pause has happened: after the first
         * resume it is what was left at the pause boundary, which is the whole point.</p>
         */
        private Duration armedBudget;
        /**
         * When {@link #armedBudget} started being consumed, or {@code null} while this join is
         * suspended and its budget is therefore not being consumed by anything.
         *
         * <p>That {@code null} is the representation of "paused time is excluded": there is no
         * separate accumulator of held intervals to keep in step, because a held join simply has no
         * instant from which time is running.</p>
         */
        private java.time.Instant armedAt;
        /**
         * The budget a suspended deadline will be re-armed with, or {@code null} when this join has
         * nothing suspended.
         *
         * <p>Cleared by every path that ends this deadline — a firing, a failure, and termination —
         * because a budget that outlived the deadline it belonged to would be re-armed by the next
         * resume over a join that has already settled, which is the "settle a join twice" and the
         * "leak a timer" failure in one field.</p>
         */
        private Duration suspendedBudget;

        private LocalJoin(JoinSpec spec, JoinKey key, boolean held) {
            this.spec = spec;
            this.key = key;
            this.held = held;
        }

        /** Records that this join has fired through {@code bucket}, mirroring the persisted marker. */
        private void noteFired(int bucket) {
            // Monotone: two branches racing on the same firing both write, and a compare-and-set that
            // lost has already been retried against the record, so the highest value is the true one.
            synchronized (timeoutLock) {
                firedThrough = Math.max(firedThrough, bucket);
            }
        }

        /** How long the bucket waited between its first report and {@code firedAt} (PLAT-01, per lap). */
        private Duration bucketWait(int bucket, java.time.Instant firedAt) {
            java.time.Instant opened = bucketOpenedAt.get(bucket);
            // Never absent in practice: attemptSettle stamps the bucket before it can decide anything
            // about it. Falling back to firedAt yields Duration.ZERO rather than a NullPointerException
            // out of a diagnostic, because a wrong duration is a worse reason to fail a traversal than
            // no duration is.
            return Duration.between(opened == null ? firedAt : opened, firedAt);
        }

        private boolean isCurrentTimeoutGeneration(int generation) {
            synchronized (timeoutLock) {
                return !timeoutRelinquished && timeoutGeneration == generation;
            }
        }

        /**
         * How much of this deadline's budget has not been consumed yet.
         *
         * <p><b>The one place a remaining budget is ever computed</b>, called by every path that
         * needs one — the pause sweep, the publish step of an arming that a pause overtook, and the
         * assertion that reads it. A second spelling of this arithmetic somewhere else is how a
         * clamp gets applied on one path and not the other.</p>
         *
         * <p>Both clamps are load bearing and neither is defensive tidying:</p>
         * <ul>
         *   <li><b>Never negative.</b> A pause can land after the budget has run out but before the
         *       scheduler has fired, so the honest remainder is below zero.
         *       {@link Scheduler#schedule} takes a non-negative delay by contract, and a join whose
         *       budget was already spent at the pause boundary is owed exactly no more of it — so it
         *       times out immediately on resume rather than during the hold.</li>
         *   <li><b>Never more than the configured timeout.</b> The measurement is a wall clock's, so
         *       a backwards adjustment during a hold would otherwise hand the join more budget than
         *       its graph ever gave it. Forwards it can only take budget away, which the clamp above
         *       floors. This is the bound that keeps a clock step from changing the contract rather
         *       than only the timing.</li>
         * </ul>
         *
         * @return the remaining budget, or {@code null} when this join has no deadline
         */
        private Duration remainingLocked() {
            if (armedBudget == null) {
                return null;
            }
            if (armedAt == null) {
                // Already suspended: no time is running, so nothing has been consumed since.
                return armedBudget;
            }
            Duration left = armedBudget.minus(Duration.between(armedAt, clock.instant()));
            if (left.isNegative()) {
                left = Duration.ZERO;
            }
            if (left.compareTo(spec.timeout()) > 0) {
                left = spec.timeout();
            }
            return left;
        }

        /**
         * Stops this join's budget from being consumed and records what is left of it.
         *
         * <p>Idempotent, and deliberately so: it is reached both by the pause sweep and by the
         * publish step of an arming the pause overtook, and which of the two runs second is a race
         * neither can win. Running it twice records the same budget the second time, because the
         * first call cleared {@link #armedAt} and {@link #remainingLocked()} then returns the stored
         * value unchanged.</p>
         *
         * <p>The generation bump is what makes a firing that is already in flight harmless: it is
         * the same counter {@link #isCurrentTimeoutGeneration(int)} consults, so a timeout that
         * cannot be cancelled because it is already running is refused instead of failing work an
         * operator has just frozen.</p>
         */
        private void suspendBudgetLocked() {
            if (timeoutRelinquished || armedBudget == null) {
                return;
            }
            Duration left = remainingLocked();
            armedBudget = left;
            armedAt = null;
            suspendedBudget = left;
            timeoutGeneration++;
        }

        /** Forgets this deadline's budget entirely, so no resume can re-arm it. */
        private void clearBudgetLocked() {
            armedBudget = null;
            armedAt = null;
            suspendedBudget = null;
        }

        /**
         * Arms this join's deadline.
         *
         * <p>{@code schedule} is deliberately called <em>outside</em> every lock — holding one
         * across a scheduler call would let a slow or blocking implementation stall every thread
         * trying to terminate — so there is a window between asking for the timer and being able to
         * store the handle that cancels it. A cancellation landing inside that window used to run
         * against a still-null field, cancel nothing, and return; the timer assigned a moment later
         * then had no owner and held the coordinator, its payloads and the traversal's security
         * context for the whole configured duration of a traversal that had already ended.</p>
         *
         * <p>The window is closed by making the handoff symmetric rather than by ordering it.
         * Publishing the handle and relinquishing it both happen under {@code timeoutLock}, and the
         * loser of that race performs the cancellation: if {@code cancelTimeout} has already run,
         * this method cancels the timer it just created instead of publishing it. Cancellation is
         * therefore correct on both interleavings and does not depend on the drain barrier, which
         * is what lets termination cancel timers without waiting for the store.</p>
         *
         * <p>The drain slot is still claimed, for the reason a store settle claims one: it makes
         * termination wait for a schedule call that is already running, so the record discard is
         * ordered after it rather than racing it. It is held <strong>through the handoff</strong>
         * and not merely across the scheduler call. Released in the {@code finally} that closed the
         * scheduler call alone, it opened the drain while {@code scheduled} was armed, unpublished
         * and not yet cancelled: {@link #terminate()}'s stage — and so {@code close()} — could
         * complete with a task still live at the scheduler and {@link #liveTimeoutCount()} still
         * non-zero, the cancellation landing an instant later on another thread. Nothing was lost
         * by that, because this same thread cancels a moment later and a timer that fires inside the
         * window is refused by the barrier in {@link #onTimeout}; what was lost was the quiescence
         * an embedder relies on when it closes the engine and then dismantles the scheduler. Holding
         * the slot to the end of the handoff nests nothing: {@code timeoutLock} is already released
         * where {@code leaveOperation} takes {@code gate}, and {@link #terminate()} releases
         * {@code gate} before {@link #releaseInMemory} takes {@code timeoutLock}.</p>
         */
        /**
         * Arms a deadline for iteration {@code lap}, unless one is already armed for it or for a later
         * one.
         *
         * <p>Previously this was {@code scheduleTimeoutOnce}, guarded by a boolean that could only be
         * flipped once, because a join had exactly one deadline for its whole life. A join that
         * re-arms needs one deadline <em>per iteration</em>, so the guard became "not for this bucket
         * or a later one" — which is the same once-only guarantee restated over a bucket instead of
         * over the join, and therefore still schedules exactly one task per join on every acyclic
         * graph.</p>
         *
         * <p><b>Armed lazily, at the first report of the bucket, rather than eagerly at the firing of
         * the previous one.</b> Arming at the firing was the obvious reading of "re-armed at the
         * firing" and it is wrong in a way the suite catches: a join that fires and is then never
         * reached again — every acyclic graph — would leave a deadline armed for a bucket that will
         * never receive anything, doubling both the scheduled and the cancelled count and holding the
         * coordinator for the configured duration of a traversal that has ended. A deadline for the
         * oldest incomplete bucket, when there is no incomplete bucket, is no deadline.</p>
         *
         * <p>The handoff invariant still holds verbatim: {@code
         * schedule} is called outside every lock, publishing the handle and relinquishing it both
         * happen under {@code timeoutLock}, and whichever side loses that race performs the
         * cancellation. The generation counter is what extends that to a deadline superseded by a
         * later bucket rather than by termination — {@link #timeoutRelinquished} says "never again"
         * and cannot say "not that one, this one". The drain slot is held through the whole handoff,
         * for the reason stated there: so that {@link #terminate()}'s stage cannot complete with a
         * task live at the scheduler.</p>
         */
        private void armTimeoutFor(int lap) {
            if (!spec.hasTimeout() || !enterOperation()) {
                return;
            }
            try {
                int generation;
                ScheduledTask superseded;
                boolean heldNow;
                synchronized (timeoutLock) {
                    if (timeoutRelinquished || timeoutArmedFor >= lap) {
                        return;
                    }
                    generation = ++timeoutGeneration;
                    timeoutArmedFor = lap;
                    // Only reachable when a deadline for an older bucket is still published, which the
                    // ordinary path never leaves behind — the firing releases it. It happens after a
                    // recovery, where the mirror-driven arming in local() guessed an already-fired
                    // bucket before the record corrected it.
                    superseded = timeout;
                    timeout = null;
                    armedBudget = spec.timeout();
                    armedAt = clock.instant();
                    suspendedBudget = null;
                    // A join first reached while the traversal is held gets its full budget recorded
                    // and nothing scheduled. This is not the same case as a pause overtaking an
                    // arming — that one is handled by the generation check at the publish step below,
                    // and needs no clause of its own — but a bucket that opens during a hold has no
                    // arming for a pause to overtake, so it has to decide here.
                    heldNow = held;
                    if (heldNow) {
                        armedAt = null;
                        suspendedBudget = armedBudget;
                    }
                }
                if (superseded != null && superseded.cancel()) {
                    liveTimeouts.decrementAndGet();
                }
                if (heldNow) {
                    return;
                }
                scheduleDeadline(lap, spec.timeout(), generation);
            } finally {
                leaveOperation();
            }
        }

        /**
         * Asks the scheduler for a deadline and completes the handoff that publishes its handle.
         *
         * <p><b>The only call to {@link Scheduler#schedule} this class makes.</b> Both arming paths
         * — a bucket receiving its first report, and a resume re-arming what a hold suspended — claim
         * a generation under {@link #timeoutLock} and then come here, so the window between asking
         * for a timer and being able to cancel it exists once and is closed once. A resume that
         * scheduled through a second copy of this handoff would be a second place for that window to
         * be got wrong, and the losing side of it is what stops a timer from outliving the join.</p>
         *
         * <p>The caller holds a drain slot, so {@link #terminate()} waits for a scheduler call that
         * is already running rather than discarding the record behind it.</p>
         *
         * @param lap        the bucket this deadline guards
         * @param budget     the delay to give the scheduler: the full configured timeout for a fresh
         *                   arming, and exactly what was left at the pause boundary for a resume
         * @param generation the arming this deadline belongs to; a handle whose generation has moved
         *                   on by the time it is published is cancelled instead of published
         */
        private void scheduleDeadline(int lap, Duration budget, int generation) {
            ScheduledTask scheduled;
            liveTimeouts.incrementAndGet();
            try {
                scheduled = scheduler.schedule(budget, () -> onTimeout(this, lap, generation));
            } catch (RuntimeException error) {
                liveTimeouts.decrementAndGet();
                throw error;
            }
            boolean cancelItNow;
            synchronized (timeoutLock) {
                // A pause that landed while this call was in flight has already bumped the
                // generation, so it is refused here by the check that was already here. That is why
                // suspension needs no clause of its own at this step: it supersedes an arming in
                // exactly the way a later bucket does, and the budget it recorded is the one a
                // resume will re-arm with.
                cancelItNow = timeoutRelinquished || timeoutGeneration != generation;
                if (!cancelItNow) {
                    timeout = scheduled;
                }
            }
            if (cancelItNow && scheduled.cancel()) {
                liveTimeouts.decrementAndGet();
            }
        }

        /**
         * Stops this join's deadline consuming its budget, because the traversal has been held.
         *
         * <p>Idempotent and safe against every other path: a join with no deadline records nothing,
         * one that has relinquished its deadline for good records nothing, and one whose arming is
         * still in flight is covered by the generation bump inside
         * {@link #suspendBudgetLocked()}.</p>
         */
        private void holdDeadline() {
            ScheduledTask task;
            synchronized (timeoutLock) {
                held = true;
                suspendBudgetLocked();
                task = timeout;
                timeout = null;
            }
            if (task != null && task.cancel()) {
                liveTimeouts.decrementAndGet();
            }
        }

        /**
         * Re-arms this join's deadline with exactly the budget that was left when the hold was taken.
         *
         * <p>Nothing is re-armed for a join that had no deadline when the hold landed, nor for one
         * that settled or was given up on during the hold: {@link #suspendedBudget} is cleared by
         * every one of those paths, so "resume everything that was suspended" cannot resurrect a
         * deadline for a bucket that has already fired.</p>
         */
        private void releaseDeadline() {
            if (!spec.hasTimeout() || !enterOperation()) {
                return;
            }
            try {
                int generation;
                Duration budget;
                int lap;
                synchronized (timeoutLock) {
                    held = false;
                    if (timeoutRelinquished || suspendedBudget == null) {
                        return;
                    }
                    budget = suspendedBudget;
                    suspendedBudget = null;
                    armedBudget = budget;
                    armedAt = clock.instant();
                    generation = ++timeoutGeneration;
                    lap = timeoutArmedFor;
                }
                scheduleDeadline(lap, budget, generation);
            } finally {
                leaveOperation();
            }
        }

        /** Whether this join is holding a budget it has not been asked to consume. Diagnostics. */
        private boolean isDeadlineHeld() {
            synchronized (timeoutLock) {
                return suspendedBudget != null;
            }
        }

        /**
         * Releases the deadline of the bucket that just fired, without giving up on the ones after it.
         *
         * <p>The join is not over — it re-arms — so {@link #cancelTimeout()} would be wrong here: its
         * flag says "never again", and the next iteration would then run with no deadline at all,
         * which is worse than the original defect because it is silent. Nothing is armed in
         * its place either; {@link #armTimeoutFor} does that when the next bucket actually receives
         * something. So a join that fires and is never reached again ends with no live task, exactly
         * as it did before.</p>
         */
        private void releaseTimeoutFor(int firedBucket) {
            if (!spec.hasTimeout()) {
                return;
            }
            ScheduledTask task;
            synchronized (timeoutLock) {
                if (timeoutRelinquished || timeoutArmedFor > firedBucket) {
                    return;
                }
                timeoutGeneration++;
                // Left at the fired bucket rather than reset, so that a stale arming for an iteration
                // that has already fired is still refused.
                timeoutArmedFor = firedBucket;
                task = timeout;
                timeout = null;
                // The budget belonged to the bucket that just fired and dies with it. Left behind, a
                // resume arriving after this firing would re-arm a deadline for a bucket that has
                // already continued downstream — and that deadline could only ever fail a join that
                // had already succeeded. The next bucket gets its own full budget when it actually
                // receives something, exactly as it does when no hold is involved.
                clearBudgetLocked();
            }
            if (task != null && task.cancel()) {
                liveTimeouts.decrementAndGet();
            }
        }

        /**
         * Cancels this join's deadline for good: if one is armed it is cancelled, and if one is being
         * armed right now it is refused.
         *
         * <p>Idempotent, and safe to call while {@link #armTimeout()} is in flight: the flag is what a
         * concurrent scheduler thread reads to decide that it must cancel the timer it is about to
         * return rather than publish it. Unlike {@link #rearmTimeout()} this is terminal — the join
         * has failed, or the traversal has ended — so no replacement is armed and
         * {@link #liveTimeoutCount()} returns to zero.</p>
         */
        private void cancelTimeout() {
            ScheduledTask task;
            synchronized (timeoutLock) {
                timeoutRelinquished = true;
                timeoutGeneration++;
                task = timeout;
                timeout = null;
                // A suspended budget is a deadline this join is still owed, so giving the deadline up
                // for good has to give the budget up with it. Otherwise a resume racing the failure
                // or the teardown that reached this line would re-arm a task against a join that is
                // already terminal, and terminate() would have completed with one live at the
                // scheduler.
                clearBudgetLocked();
                timeoutRelinquishedObserver.run();
            }
            if (task != null && task.cancel()) {
                liveTimeouts.decrementAndGet();
            }
        }

        /**
         * Branches currently parked here, waiting for this join to settle. Diagnostics only.
         *
         * <p>Read-only over {@link #waiters}: it takes the same monitor {@link #waiter(int)} and
         * {@link #completeWaiters} already use and only sums each bucket's size, so it does not touch
         * {@link #payloads} or {@link #branchFailures} and cannot affect ADR 0019's insert-only
         * invariant on those two maps. Summing across buckets rather than reading one list is the only
         * required behavior here: a branch parked on any iteration is a branch this traversal is still
         * waiting on, which is the question {@code unreachableTraversalIds} asks.</p>
         */
        private int liveWaiterCount() {
            synchronized (waitersLock) {
                return waiters.values().stream().mapToInt(bucket -> bucket.parked.size()).sum();
            }
        }

        private CompletionStage<JoinDecision> waiter(int lap) {
            var pending = new CompletableFuture<JoinDecision>();
            boolean parked;
            synchronized (waitersLock) {
                // Re-checked under the same lock completeWaiters holds. Without it a branch that
                // decided Wait just as the join settled would park on a future nobody will ever
                // complete, and the traversal would hang on a join that already has an outcome.
                // Per bucket now: an earlier lap having settled says nothing about this one.
                WaiterBucket bucket = waiters.computeIfAbsent(lap, ignored -> new WaiterBucket());
                parked = !releasedEveryBucket && !bucket.settled;
                if (parked) {
                    bucket.parked.add(pending);
                }
            }
            if (!parked) {
                return CompletableFuture.completedFuture(
                        new JoinDecision.Discarded(JoinDecision.Discarded.Reason.LATE, key.joinNodeId()));
            }
            return pending;
        }

        /** The branches parked on one iteration, and whether that iteration has been answered. */
        private static final class WaiterBucket {
            private final List<CompletableFuture<JoinDecision>> parked = new ArrayList<>();
            private boolean settled;
        }

        /**
         * Settles one iteration's waiters: claims them under {@code waitersLock}, and completes them
         * <strong>outside</strong> that monitor.
         *
         * <p>The two halves are separate on purpose. Marking the bucket settled and draining its
         * list has to happen under the monitor, because that is the handshake {@link #waiter(int)}
         * re-reads to decide it must not park — that half is documented at
         * {@link JoinCoordinator#releaseInMemory}. Completing the futures is the other half, and it
         * runs after the monitor is released because completing a future <em>runs its dependents on
         * this thread</em>: every downstream continuation of a parked branch, which is arbitrary
         * code this class does not own. Under the monitor, one branch's continuation would hold
         * {@code waitersLock} for as long as it ran, and any other branch of this same join entering
         * {@link #waiter(int)} or settling it would wait behind it. A continuation that blocks makes
         * that wait unbounded — and blocking one deliberately is exactly how
         * {@code JoinTerminateIdempotenceTest} constructs its red, so this is a continuation that
         * demonstrably exists rather than one that might.
         *
         * <p><strong>The suite will not catch you moving it.</strong> Hoisting the completion loop
         * inside the {@code synchronized} block was measured against the whole of
         * {@code ravenroot-core}: 577 tests, still green, in the same time. It does not hang and it
         * does not go red, because no path here takes {@code waitersLock} while holding {@code gate}, so
         * there is no lock cycle to close — a property of the current callers, not a guarantee about
         * future ones. No test currently defends this invariant, so moving the completion loop under
         * {@code waitersLock} would remove a concurrency guarantee without making the suite fail.
         *
         * <p>Dependents therefore run synchronously on the completing thread, inside this method. A
         * caller that must be sure of what a waiter observes at the instant of delivery has to have
         * finished preparing the verdict before calling — see {@link #releaseWaiters()}.
         *
         * @param lap which iteration is being answered; only branches parked on it are completed,
         *            because a firing of bucket k tells a branch parked on bucket k+1 nothing
         * @return how many branches were actually parked here when the join settled
         */
        private int completeWaiters(int lap, Throwable failure) {
            List<CompletableFuture<JoinDecision>> parked;
            synchronized (waitersLock) {
                WaiterBucket bucket = waiters.computeIfAbsent(lap, ignored -> new WaiterBucket());
                bucket.settled = true;
                parked = List.copyOf(bucket.parked);
                bucket.parked.clear();
            }
            return deliver(parked, failure);
        }

        /**
         * Answers every parked branch of every iteration. For an outcome that is terminal for the
         * whole join — a failure verdict or a timeout — where {@link #completeWaiters(int, Throwable)}
         * would leave later buckets parked on a join that can never fire again.
         */
        private int completeAllWaiters(Throwable failure) {
            List<CompletableFuture<JoinDecision>> parked;
            synchronized (waitersLock) {
                releasedEveryBucket = true;
                parked = new ArrayList<>();
                waiters.values().forEach(bucket -> {
                    bucket.settled = true;
                    parked.addAll(bucket.parked);
                    bucket.parked.clear();
                });
            }
            return deliver(parked, failure);
        }

        /** Completes claimed waiters outside the monitor. See the two callers' shared javadoc above. */
        private int deliver(List<CompletableFuture<JoinDecision>> parked, Throwable failure) {
            for (var waiter : parked) {
                if (failure == null) {
                    waiter.complete(new JoinDecision.Discarded(
                            JoinDecision.Discarded.Reason.LATE, key.joinNodeId()));
                } else {
                    waiter.completeExceptionally(failure);
                }
            }
            return parked.size();
        }

        /**
         * Releases waiters when the traversal ends without the join settling — the runner failed
         * elsewhere, or was closed. They are completed rather than left pending, because a pending
         * future here is a thread parked forever on a traversal that no longer exists.
         *
         * <p>How many were parked is not a diagnostic detail. Zero is the ordinary case and says
         * every branch of this join was accounted for. More than zero says the traversal ended while
         * a branch was still waiting for this join's answer, which is only reachable when that branch
         * was abandoned rather than resolved — so the verdict is kept, and
         * {@link #abandonedBranchFailure()} lets the runner refuse to call that traversal a success.
         * </p>
         */
        private void releaseWaiters() {
            // The iteration that was still being filled when the traversal ended. Its branches are the
            // ones that are outstanding; earlier laps completed and later ones cannot exist, because a
            // lap only begins when its predecessor fires. Reporting the whole of `payloads` and
            // `branchFailures` instead would name every branch of every completed iteration in a
            // verdict about one incomplete one.
            int lap = firedThrough + 1;
            List<String> failed = branchFailures.keySet().stream()
                    .filter(branch -> BranchId.lapIn(branch) == lap)
                    .map(BranchId::branchIn)
                    .toList();
            List<String> arrived = payloads.keySet().stream()
                    .filter(branch -> BranchId.lapIn(branch) == lap)
                    .map(BranchId::branchIn)
                    .toList();
            var failure = new JoinFailureException(JoinFailureException.Reason.QUORUM_UNREACHABLE,
                    key.joinNodeId(), spec.quorum(), arrived, failed, spec.branches());
            // FIX-34, the same correction FIX-16 made at recordedFailure() and for the
            // same reason: the branch ids say which branch broke, the Throwables say what broke it,
            // and naming the first while discarding the second is the whole defect. Attached here
            // rather than left out because this method runs from releaseInMemory on a coordinator
            // whose traversal is ending in-process, so the causes are still in memory by
            // construction. The rule that a verdict rebuilt after a genuine cross-process
            // restart stays thinner is untouched: such a process holds no branchFailures at all, so
            // this loop finds nothing and is a no-op, exactly as it is at recordedFailure().
            //
            // Before completeWaiters, not after. The waiters are handed this same instance, and a
            // consumer that reads it on completion -- GraphRunner reads abandonedBranchFailure()
            // exactly that way -- would otherwise observe the verdict before the causes below are
            // attached.
            failed.stream()
                    .map(branch -> branchFailures.get(BranchId.atLap(branch, lap)))
                    .filter(java.util.Objects::nonNull)
                    .forEach(failure::addSuppressed);
            // Every bucket, because the traversal is over for all of them, and because a branch parked
            // on the incomplete lap is precisely what must stop this traversal reporting success.
            // The latch is per bucket because a shared latch would already be open after the first
            // lap: the second lap's branches would not park, this loop would find nothing, and the
            // traversal could report success after dropping the incomplete lap.
            if (completeAllWaiters(failure) > 0) {
                abandonedBranch.compareAndSet(null, failure);
            }
        }
    }

    /**
     * Restores the joins of {@code tenantId} that a previous process left open.
     *
     * <p>Returns them rather than resuming them: resuming needs the traversal's own state, which is
     * PERS-04's, and a coordinator that decided to resume on its own would be making a recovery
     * policy decision from inside a fan-in helper.</p>
     */
    static CompletionStage<List<JoinRecord>> recoverable(JoinStore store, String tenantId) {
        return store.openJoins(tenantId);
    }
}
