package ai.ravenroot.core.runtime;

import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.DurableToolApproval;
import ai.ravenroot.api.persistence.AgentBudgetOperation;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionPauseRegistration;
import ai.ravenroot.api.persistence.ExecutionPauseTransition;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.LeaseHandle;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HandlerRegistration;
import ai.ravenroot.api.persistence.TimerSchedule;
import ai.ravenroot.api.persistence.ToolApprovalRegistration;
import ai.ravenroot.api.persistence.HumanTaskRegistration;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.execution.NodeMessage;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Holds one process instance's lease for the life of a traversal and writes the runner's lifecycle
 * transitions through the {@link ExecutionStore} under that fence.
 *
 * <h2>The lease is the whole of the coexistence design</h2>
 * <p>{@code claimPendingWork} already skips any instance whose lease is held by another worker and
 * has not expired. PERS-04 relied on exactly that when it widened the claim query to include
 * {@code RUNNING} attempts — the adapter comment states the reason: <em>only a runtime that stopped
 * renewing, that is one that died, lets its instance become claimable again</em>. So a live engine
 * and the recovery sweep are kept apart by <strong>using</strong> the guard the widening already
 * assumed, rather than by inventing a second mechanism to protect the first.</p>
 *
 * <h2>Renewal failure is a stop; release is an optimisation. Do not merge these paths</h2>
 * <p>They look like two halves of lease housekeeping and they are not.
 * {@link ExecutionStore#release(LeaseHandle)} is best-effort by contract: a release that fails leaves
 * the lease to expire on the store's clock, which is the ordinary crash path, so nothing depends on
 * it succeeding. <strong>Renewal failure is the opposite.</strong> A worker whose renewal failed has
 * lost the fence, which means some other worker may now be executing this instance; its own next
 * write will be refused with {@link ExecutionStoreFailure.FencedOut} — but that refusal arrives
 * <em>after</em> the effect it was recording already happened. So the moment the fence is lost this
 * recorder stops writing and reports, rather than continuing and discovering it later. A future
 * reader who "simplifies" these two into one tolerant path reintroduces exactly that window.</p>
 *
 * <h2>Ordering</h2>
 * <p>Every transition is committed before the action it describes reaches the engine, which is what
 * makes a persisted {@code RUNNING} mean "sent, outcome unknown" rather than "about to be sent".
 * PERS-04's recovery loop reads it that way and parks on it; that reading is only sound because this
 * class writes in that order.</p>
 *
 * <h2>The residual window: a fence can be lost while a node is still executing</h2>
 * <p>The ordering above protects the gap <em>before</em> a node runs — a crash there leaves
 * {@code RUNNING} committed and nothing ever having been sent, which recovery already reads correctly
 * as "outcome unknown, safe to treat as ambiguous." It says nothing about the much longer gap
 * <em>during</em> a node's own execution: a worker that renewed successfully, dispatched, and then had
 * its <em>next</em> renewal fail — because of ordinary network partition or scheduling delay, not a
 * crash — has lost the fence while its node's external call may still be genuinely in flight. That
 * call is not cancelled by losing the fence; nothing here or in the engine reaches into it. Its result,
 * whatever it is, arrives after this recorder already knows it must refuse to write again.
 *
 * <p><strong>What is and is not closed.</strong> {@link #record} refuses up front once
 * {@link #fenceLost} is set, and the store refuses the write in the store even if this recorder's own
 * flag has not caught up yet (both are exercised, independently, by
 * {@code ExecutionRecorderCrossProcessFenceLossTest} in this module's own test tree, across real
 * operating-system processes rather than threads sharing a clock). So the store never records two
 * conflicting truths about one attempt, and a worker that takes over never has to guess whether the
 * write it is looking at might be superseded later. What is <em>not</em> closed, and cannot be by
 * anything at this layer, is the external effect itself: if the node's call was going to succeed, it
 * still does, and nobody is told. That is why {@code AttemptRepeatability} exists and why its own
 * Javadoc states the same window from the node author's side — this class is where the window is
 * created, that is where an author decides what to do about it.</p>
 *
 * <p><strong>One narrowing was considered and declined, and the trigger to revisit it is here so the
 * next reader does not re-derive the question.</strong> Re-verifying the fence synchronously
 * immediately before the send would close the gap between a failed background renewal and the next
 * node's dispatch — but only that gap. The dominant exposure is the node's own execution duration,
 * seconds to minutes, and that is not closable at this layer at all: both options leave the same worst
 * case and differ only in the width of a much smaller sub-window. The price is a store round trip on
 * every single dispatch, paid always, for a benefit that lands only in the rare split-brain. What
 * would reopen it: workload evidence that the renewal cadence above is too coarse in practice. Absent
 * that, this is deliberately left as it is, and the window above is declared rather than quietly
 * narrowed.</p>
 */
public final class ExecutionRecorder implements AutoCloseable {

    /** How much of the TTL may elapse before renewal runs. A third leaves two retries of headroom. */
    private static final double RENEWAL_FRACTION = 1.0 / 3.0;

    private final ExecutionStore store;
    private final ExecutionKey key;
    private final Duration leaseTtl;
    private final ScheduledExecutorService renewals;

    private LeaseHandle lease;
    private long revision;
    private volatile boolean fenceLost;
    private volatile ExecutionStoreFailure fenceLostBecause;
    private ScheduledFuture<?> renewalTask;

    private ExecutionRecorder(ExecutionStore store, ExecutionKey key, Duration leaseTtl,
                              LeaseHandle lease, long revision, ScheduledExecutorService renewals) {
        this.store = store;
        this.key = key;
        this.leaseTtl = leaseTtl;
        this.lease = lease;
        this.revision = revision;
        this.renewals = renewals;
    }

    /**
     * Claims the instance and starts renewing. Fails closed with
     * {@link ExecutionInstanceBusyException} when another worker holds it.
     *
     * @param revision the revision the instance is at, from the batch that created it
     */
    public static ExecutionRecorder open(ExecutionStore store, ExecutionKey key, String workerId,
                                         Duration leaseTtl, long revision) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(leaseTtl, "leaseTtl");
        LeaseHandle lease;
        try {
            lease = await(store.claim(key, workerId, leaseTtl));
        } catch (ExecutionStoreException refused) {
            if (refused.failure() instanceof ExecutionStoreFailure.LeaseHeldByAnother) {
                throw new ExecutionInstanceBusyException(key, refused);
            }
            throw refused;
        }
        var renewals = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "ravenroot-lease-" + key.processInstanceId());
            thread.setDaemon(true);
            return thread;
        });
        var recorder = new ExecutionRecorder(store, key, leaseTtl, lease, revision, renewals);
        recorder.startRenewing();
        return recorder;
    }

    /**
     * Adopts the instance lease already acquired by a pending-work claim.
     * The raw claim projection is the authority; this method never issues a second claim that could
     * advance the fence between approval redemption and its effect.
     */
    public static ExecutionRecorder resumeClaimed(ExecutionStore store,
                                                   ai.ravenroot.api.persistence.PendingWork claimed,
                                                   String workerId, Duration leaseTtl, long revision) {
        Objects.requireNonNull(claimed, "claimed");
        Objects.requireNonNull(workerId, "workerId");
        var lease = new LeaseHandle(claimed.key(), workerId, claimed.fencingToken(),
                claimed.leaseExpiresAt().minus(leaseTtl), claimed.leaseExpiresAt());
        var renewals = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable,
                    "ravenroot-reentry-lease-" + claimed.key().processInstanceId());
            thread.setDaemon(true);
            return thread;
        });
        var recorder = new ExecutionRecorder(store, claimed.key(), leaseTtl, lease, revision, renewals);
        recorder.startRenewing();
        return recorder;
    }

    /**
     * Renewal runs on a period well inside the TTL, because its purpose is to keep <em>healthy</em>
     * long-running work out of the recovery sweep's hands. Without it a node that legitimately runs
     * longer than one TTL would have its instance become claimable while it is still executing, and
     * PERS-04 would park work that is proceeding perfectly well — a harmless residual case
     * only while no dispatcher existed to run one, and not harmless from here on.
     */
    private void startRenewing() {
        long periodMillis = Math.max(1L, (long) (leaseTtl.toMillis() * RENEWAL_FRACTION));
        renewalTask = renewals.scheduleAtFixedRate(this::renewQuietly, periodMillis, periodMillis,
                TimeUnit.MILLISECONDS);
    }

    private void renewQuietly() {
        try {
            renewNow();
        } catch (RuntimeException alreadyRecorded) {
            // renewNow has already marked the fence lost; the scheduled task must not die noisily on
            // a condition the write path is about to report with far more context.
        }
    }

    /** Renews immediately. Visible for tests, which drive the clock rather than waiting on it. */
    public synchronized void renewNow() {
        if (fenceLost) {
            return;
        }
        try {
            lease = await(store.renew(lease, leaseTtl));
        } catch (ExecutionStoreException lost) {
            loseFence(lost.failure());
            throw lost;
        }
    }

    /**
     * Applies {@code transitions} and {@code events} as one fenced, all-or-nothing batch, then
     * advances the expected revision.
     *
     * <p>Synchronous on purpose. The caller is recording an intention it is about to act on, and an
     * asynchronous write would let the action overtake the record — which is the one ordering this
     * design does not survive losing.</p>
     */
    public synchronized void record(List<ExecutionTransition> transitions, List<EventEnvelope> events) {
        record(transitions, events, List.of());
    }

    /** Adds durable agent accounting to the same fenced commit as its lifecycle and audit events. */
    public synchronized void record(List<ExecutionTransition> transitions, List<EventEnvelope> events,
                                    List<AgentBudgetOperation> agentBudgetOperations) {
        requireFence();
        if ((transitions == null || transitions.isEmpty()) && (events == null || events.isEmpty())
                && (agentBudgetOperations == null || agentBudgetOperations.isEmpty())) {
            return;
        }
        var batch = ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(revision))
                .fencedBy(lease);
        if (transitions != null) {
            transitions.forEach(batch::apply);
        }
        if (events != null && store.supports(StoreCapability.EVENT_JOURNAL)) {
            // Journalled inside this same transaction. An adapter without the capability would
            // reject the batch, taking the aggregate write down with it, so the events are omitted
            // rather than the transitions lost — the transitions are the durability guarantee and
            // the journal is a projection of them.
            //
            // This path initially used only an EMPTY list, and that was deliberate rather than
            // an omission. The journal is append-only, so a node event published then with an
            // absent-but-existing causationId would have been a permanently false row that no later
            // later update could amend — backfilling causation on an immutable log is rewriting history.
            // An empty journal is honest; a semantically premature one is wrong forever. EventEnvelope's
            // causation contract supplies the causal model, and this list is filled from
            // GraphRunner.ExecutionState.
            events.forEach(batch::publish);
        }
        if (agentBudgetOperations != null) {
            agentBudgetOperations.forEach(batch::applyAgentBudget);
        }
        try {
            StoredProcessInstance applied = await(store.apply(batch.build()));
            revision = applied.revision();
        } catch (ExecutionStoreException failed) {
            if (failed.failure() instanceof ExecutionStoreFailure.FencedOut
                    || failed.failure() instanceof ExecutionStoreFailure.LeaseLost) {
                loseFence(failed.failure());
            }
            throw failed;
        }
    }

    /**
     * Commits one managed tool suspension through this recorder's live fence.
     *
     * <p>The approval, handler, timer, journal row, and three WAITING transitions are one batch.
     * No independent writer can race the runner's revision between the node dispatch and this wait.</p>
     */
    public synchronized void suspendForToolApproval(ToolApprovalRegistration approval,
                                                     HandlerRegistration handler,
                                                     TimerSchedule timer,
                                                     EventEnvelope event) {
        suspendForToolApproval(approval, handler, timer, event, null);
    }

    /** Approval wait plus its optional HELD economic reservation, in one fenced commit. */
    public synchronized void suspendForToolApproval(ToolApprovalRegistration approval,
                                                     HandlerRegistration handler,
                                                     TimerSchedule timer,
                                                     EventEnvelope event,
                                                     AgentBudgetOperation budgetOperation) {
        requireFence();
        if (!approval.traversalId().equals(handler.traversalId())
                || !approval.invocationId().equals(handler.invocationId())
                || !approval.approvalId().equals(handler.handlerId())) {
            throw new IllegalArgumentException("approval and handler scope do not match");
        }
        var batch = ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(revision))
                .fencedBy(lease)
                .apply(new ExecutionTransition.AttemptTransitioned(approval.traversalId(),
                        approval.invocationId(), approval.attemptId(), NodeAttemptStatus.WAITING))
                .apply(new ExecutionTransition.InvocationTransitioned(approval.traversalId(),
                        approval.invocationId(), NodeInvocationStatus.WAITING))
                .apply(new ExecutionTransition.TraversalTransitioned(approval.traversalId(),
                        TraversalStatus.WAITING))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.WAITING))
                .registerToolApproval(approval)
                .registerHandler(handler)
                .scheduleTimer(timer);
        if (store.supports(StoreCapability.EVENT_JOURNAL)) {
            batch.publish(event);
        }
        if (budgetOperation != null) batch.applyAgentBudget(budgetOperation);
        StoredProcessInstance applied = await(store.apply(batch.build()));
        revision = applied.revision();
    }

    /** Atomically parks one invocation behind a first-class durable human task. */
    public synchronized void suspendForHumanTask(HumanTaskRegistration task,
                                                 HandlerRegistration handler,
                                                 List<TimerSchedule> timers,
                                                 EventEnvelope event) {
        requireFence();
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(timers, "timers");
        if (!task.traversalId().equals(handler.traversalId())
                || !task.invocationId().equals(handler.invocationId())
                || !task.taskId().equals(handler.handlerId())) {
            throw new IllegalArgumentException("human task and handler scope do not match");
        }
        var batch = ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(revision))
                .fencedBy(lease)
                .apply(new ExecutionTransition.AttemptTransitioned(task.traversalId(),
                        task.invocationId(), task.attemptId(), NodeAttemptStatus.WAITING))
                .apply(new ExecutionTransition.InvocationTransitioned(task.traversalId(),
                        task.invocationId(), NodeInvocationStatus.WAITING))
                .apply(new ExecutionTransition.TraversalTransitioned(task.traversalId(),
                        TraversalStatus.WAITING))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.WAITING))
                .registerHumanTask(task)
                .registerHandler(handler);
        timers.forEach(batch::scheduleTimer);
        if (store.supports(StoreCapability.EVENT_JOURNAL)) {
            batch.publish(Objects.requireNonNull(event, "event"));
        }
        StoredProcessInstance applied = await(store.apply(batch.build()));
        revision = applied.revision();
    }

    /**
     * Commits one durable operator hold through this recorder's live fence.
     *
     * <p>The hold and the two {@code WAITING} transitions are one batch, which is what
     * {@link StoreCapability#EXECUTION_PAUSES} asserts and what stops a restart from finding either
     * half without the other.</p>
     *
     * <p>The traversal moves to {@code WAITING} and that is not bookkeeping: the aggregate refuses
     * to add an invocation to a traversal that is not {@code RUNNING}, so from this commit onward no
     * node of this traversal can be recorded as started by <em>any</em> process until something
     * transitions it back. Only {@link #settleExecutionPause} does. The runtime gate stops the next
     * hop in the process that took the hold; this stops every hop in every other process, including
     * the one that starts after a restart.</p>
     *
     * <p>Nothing claimable is written. A hold produces no {@link ai.ravenroot.api.persistence.PendingWork}
     * of any kind and leaves no {@code SCHEDULED} or {@code RUNNING} attempt behind, so a recovery
     * sweep has nothing to return for a held traversal — which is why recovery leaves it held
     * without needing a rule that says so.</p>
     *
     * @param pause the hold to commit, carrying its bounded continuation.
     */
    public synchronized void commitExecutionPause(ExecutionPauseRegistration pause) {
        requireFence();
        Objects.requireNonNull(pause, "pause");
        var batch = ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(revision))
                .fencedBy(lease)
                .apply(new ExecutionTransition.TraversalTransitioned(pause.traversalId(),
                        TraversalStatus.WAITING))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.WAITING))
                .registerExecutionPause(pause);
        StoredProcessInstance applied = await(store.apply(batch.build()));
        revision = applied.revision();
    }

    /**
     * Settles one durable hold and puts its traversal back into the state its outcome calls for.
     *
     * <p>One batch, because the two are one decision. A settlement that committed without the
     * traversal transition would leave a traversal nothing holds and nothing may add an invocation
     * to — permanently stuck in exactly the way a durable hold exists to prevent — and a transition
     * without the settlement would leave a running traversal every reader still reports as held.</p>
     *
     * @param transition the settlement to apply.
     * @param traversalId the held traversal.
     * @param traversalStatus the state the traversal moves to, or {@code null} when the caller's own
     *                        teardown is about to write the traversal's end and a transition here
     *                        would be the first of two, leaving the second illegal.
     * @param processStatus the state the process instance moves to, under the same rule.
     */
    public synchronized void settleExecutionPause(ExecutionPauseTransition transition, UUID traversalId,
                                                  TraversalStatus traversalStatus,
                                                  ProcessInstanceStatus processStatus) {
        settleExecutionPause(transition, traversalId, traversalStatus, processStatus, null);
    }

    /**
     * Settles one durable hold and puts its traversal into the state its outcome calls for, recording
     * why that state was reached.
     *
     * <p>The reason travels in the same batch as the statuses for the reason the statuses travel with
     * the settlement: they are one decision. A hold given up by an operator ends its traversal as
     * {@code FAILED}, which on its own describes a fault that did not occur — the reason beside it is
     * the only thing that says the traversal was stopped rather than broken, so a batch that
     * committed the status now and the reason later would leave a durable, restart-visible window
     * reporting an incident. See {@link ai.ravenroot.api.application.ExecutionTerminationReason}.</p>
     *
     * @param transition the settlement to apply.
     * @param traversalId the held traversal.
     * @param traversalStatus the state the traversal moves to, or {@code null} when the caller's own
     *                        teardown is about to write the traversal's end and a transition here
     *                        would be the first of two, leaving the second illegal.
     * @param processStatus the state the process instance moves to, under the same rule.
     * @param terminationReason why those states were reached, or {@code null} when nothing
     *                          distinguishes the termination. Ignored for a non-terminal status,
     *                          which the aggregate refuses to qualify.
     */
    public synchronized void settleExecutionPause(ExecutionPauseTransition transition, UUID traversalId,
                                                  TraversalStatus traversalStatus,
                                                  ProcessInstanceStatus processStatus,
                                                  ai.ravenroot.api.application.ExecutionTerminationReason
                                                          terminationReason) {
        requireFence();
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(traversalId, "traversalId");
        var batch = ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(revision))
                .fencedBy(lease)
                .applyExecutionPause(transition);
        if (traversalStatus != null) {
            batch.apply(new ExecutionTransition.TraversalTransitioned(traversalId, traversalStatus,
                    traversalStatus.terminal() ? terminationReason : null));
        }
        if (processStatus != null) {
            batch.apply(new ExecutionTransition.ProcessTransitioned(processStatus,
                    processStatus.terminal() ? terminationReason : null));
        }
        StoredProcessInstance applied = await(store.apply(batch.build()));
        revision = applied.revision();
    }

    /**
     * Confirms that a payload-free core signal names this exact durably registered invocation.
     *
     * <p>The task's lifecycle is deliberately not part of this proof. A responder can settle the
     * task after its registration commits but before the runner observes the suspension signal. The
     * immutable registration remains the authority in that legal interleaving.</p>
     */
    public synchronized boolean confirmsHumanTask(UUID taskId, NodeMessage message) {
        if (!key.tenantId().equals(message.security().tenantId())
                || !key.processInstanceId().equals(message.processInstanceId())) {
            return false;
        }
        var task = await(store.loadHumanTask(key.tenantId(), taskId)).orElse(null);
        if (task == null || !task.key().equals(key)) return false;
        HumanTaskRegistration request = task.request();
        return request.traversalId().equals(message.traversalId())
                && request.invocationId().equals(message.invocationId())
                && request.attemptId().equals(message.attemptId())
                && request.nodeId().equals(message.nodeId())
                && request.requester().equals(message.security());
    }

    /** Commits the exact redeemed effect outcome through this recorder's current claim fence. */
    public synchronized void completeToolApproval(UUID approvalId, boolean succeeded,
                                                  EventEnvelope event) {
        completeToolApproval(approvalId, succeeded, event, null);
    }

    /** Outcome transition plus optional reservation settlement through the same claim fence. */
    public synchronized void completeToolApproval(UUID approvalId, boolean succeeded,
                                                  EventEnvelope event,
                                                  AgentBudgetOperation budgetOperation) {
        requireFence();
        Objects.requireNonNull(approvalId, "approvalId");
        var transition = succeeded
                ? new ai.ravenroot.api.persistence.ToolApprovalTransition.Succeeded(approvalId)
                : new ai.ravenroot.api.persistence.ToolApprovalTransition.Failed(approvalId);
        for (int writeAttempt = 1; writeAttempt <= 3; writeAttempt++) {
            var batch = ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(revision))
                    .fencedBy(lease)
                    .applyToolApproval(transition);
            if (store.supports(StoreCapability.EVENT_JOURNAL)) {
                batch.publish(Objects.requireNonNull(event, "event"));
            }
            if (budgetOperation != null) batch.applyAgentBudget(budgetOperation);
            try {
                StoredProcessInstance applied = await(store.apply(batch.build()));
                revision = applied.revision();
                return;
            } catch (ExecutionStoreException failed) {
                if (failed.failure() instanceof ExecutionStoreFailure.ConcurrencyConflict
                        && writeAttempt < 3) {
                    revision = await(store.load(key)).revision();
                    continue;
                }
                if (failed.failure() instanceof ExecutionStoreFailure.FencedOut
                        || failed.failure() instanceof ExecutionStoreFailure.LeaseLost) {
                    loseFence(failed.failure());
                }
                throw failed;
            }
        }
    }

    /**
     * Confirms that a core signal names the exact invocation this recorder durably registered.
     * Later approval lifecycle transitions do not invalidate that immutable proof.
     */
    public synchronized boolean confirmsToolApproval(UUID approvalId, NodeMessage message) {
        if (!key.tenantId().equals(message.security().tenantId())
                || !key.processInstanceId().equals(message.processInstanceId())) {
            return false;
        }
        DurableToolApproval approval = await(store.loadToolApproval(key, approvalId)).orElse(null);
        if (approval == null) return false;
        ToolApprovalRegistration request = approval.request();
        return request.traversalId().equals(message.traversalId())
                && request.invocationId().equals(message.invocationId())
                && request.attemptId().equals(message.attemptId())
                && request.nodeId().equals(message.nodeId())
                && request.requester().equals(message.security());
    }

    /** Immutable graph pin this fenced recorder is writing under. */
    public synchronized GraphVersionPin graphVersionPin() {
        return await(store.load(key)).graphVersionPin();
    }

    /** Current fenced aggregate snapshot used to seed a trusted re-entry runner. */
    public synchronized ai.ravenroot.api.application.ProcessInstance storedState() {
        requireFence();
        return await(store.load(key)).state();
    }

    private void loseFence(ExecutionStoreFailure because) {
        fenceLost = true;
        fenceLostBecause = because;
    }

    private void requireFence() {
        if (fenceLost) {
            throw new IllegalStateException("This worker lost the fence on " + key.processInstanceId()
                    + " (" + fenceLostBecause + ") and must not write again: another worker may be "
                    + "executing this instance, and a refusal would arrive after the effect");
        }
    }

    /** Whether this recorder still holds the fence. False means every further write is refused. */
    public boolean holdsFence() {
        return !fenceLost;
    }

    /** The revision the instance is at after the last successful write. */
    public synchronized long revision() {
        return revision;
    }

    /**
     * Whether the composed store can hold a traversal durably.
     *
     * <p>Read rather than assumed for the reason {@link #supportsJournal()} is read: an adapter that
     * has not implemented holds rejects the whole batch, which would take the traversal's own
     * transitions down with it. A store that cannot hold durably simply keeps #130's process-local
     * hold, which is a weaker guarantee and not a broken one.</p>
     *
     * @return whether {@link StoreCapability#EXECUTION_PAUSES} is declared.
     */
    public boolean supportsExecutionPauses() {
        return store.supports(StoreCapability.EXECUTION_PAUSES);
    }

    /**
     * Whether the store behind this recorder journals published events.
     *
     * <p>Asked <em>before</em> an envelope is authored, not only inside {@link #record}, so a caller
     * threading causation through its own control flow never carries a causation identifier naming
     * an event this store silently declined to write.</p>
     */
    public boolean supportsJournal() {
        return store.supports(StoreCapability.EVENT_JOURNAL);
    }

    /**
     * The tenant every batch this recorder writes is scoped to.
     *
     * <p>Exposed so an {@link EventEnvelope} author takes its {@code tenantId} from the batch's own
     * key rather than from a second source that agrees with it today. The store rejects an envelope
     * whose tenant differs from the batch's, so a caller reading it from anywhere else is a caller
     * whose envelopes are correct only while the two sources happen not to have drifted.</p>
     */
    public String tenantId() {
        return key.tenantId();
    }

    /** The process instance every batch this recorder writes is scoped to. @see #tenantId() */
    public java.util.UUID processInstanceId() {
        return key.processInstanceId();
    }

    /**
     * Stops renewing and releases the lease, so an orderly shutdown hands the instance back at once
     * instead of leaving it locked for a whole TTL.
     *
     * <p>Best-effort by design, and that is ADR 0010 section 13.1's invariant rather than laziness: a
     * {@code kill -9} performs neither this nor a release, so its lease simply expires. Making an
     * orderly shutdown depend on the release succeeding would give crash recovery and orderly
     * recovery two different paths, and every recovery test would then be evidence about the path
     * nobody experiences in production.</p>
     */
    @Override
    public void close() {
        if (renewalTask != null) {
            renewalTask.cancel(false);
        }
        renewals.shutdownNow();
        try {
            if (!fenceLost) {
                await(store.release(lease));
            }
        } catch (RuntimeException expiryWillHandleIt) {
            // A failed release is the crash path, which the store already handles by expiry.
        }
    }

    /** Stops local renewal while retaining the claim for the caller's subsequent acknowledgement. */
    public void detachForAcknowledgement() {
        if (renewalTask != null) renewalTask.cancel(false);
        renewals.shutdownNow();
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException wrapped) {
            ExecutionStoreException failure = ExecutionStoreException.unwrap(wrapped);
            throw failure == null ? wrapped : failure;
        }
    }
}
