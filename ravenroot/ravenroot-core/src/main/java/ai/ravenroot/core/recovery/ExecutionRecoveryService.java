package ai.ravenroot.core.recovery;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.InventoryDisposition;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.ProcessInventoryEntry;
import ai.ravenroot.api.persistence.ProcessInventoryQuery;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.catalog.AttemptRepeatability;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.core.runtime.GraphExecutionLimits;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Recovers accepted work after a crash (PERS-04, ADR 0022). The first production caller of
 * {@code claimPendingWork}, {@code claimDueTimers} and {@code ack}.
 *
 * <h2>What recovery means here</h2>
 * <p>Generic attempt recovery makes outstanding items <em>dispatchable</em> with correct lease,
 * fencing and idempotency semantics; it does not reconstruct arbitrary graph execution. Reserved
 * handler triggers may instead carry a bounded, versioned continuation that a trusted
 * {@link RecoveryDispatcher} understands. Unsupported continuations remain claimable and
 * unacknowledged, so recovery fails closed without losing the durable wait.</p>
 *
 * <h2>The one rule the whole thing rests on</h2>
 * <p><strong>The {@code RUNNING} transition is committed under the fence before the engine send.</strong>
 * That single ordering partitions every claimed attempt into two decidable cases and leaves no third:
 * {@code SCHEDULED} means the effect provably never started, so dispatch freely; {@code RUNNING} means
 * it was sent and the outcome was never learned, which is the ambiguity recovery must handle.</p>
 *
 * <h2>Why {@code RUNNING} alone is the discriminator, not {@code deliveryAttempt > 1}</h2>
 * <p>ADR 0022 phrases the detection rule as a redelivery of a {@code RUNNING} attempt, which is
 * correct for every attempt that reached the engine through this loop. It is not exhaustive, because
 * the primary submission path does not claim its work: an attempt that path drove to {@code RUNNING}
 * and then lost to a crash is claimed <em>here for the first time</em>, with
 * {@code deliveryAttempt == 1}, and is every bit as ambiguous. Keying on the delivery counter would
 * silently re-dispatch exactly those. This implementation therefore treats {@code RUNNING} as
 * sufficient on its own — strictly safer than the written rule, never weaker, and reported as a
 * deviation rather than absorbed.</p>
 *
 * <h2>Tenants come from configuration</h2>
 * <p>{@code claimPendingWork} is tenant-scoped per call and the port gains no tenant enumeration. A
 * "list tenants" operation would have to undo physical isolation, and
 * cross-tenant fairness is a scheduling policy the runtime owns rather than something a store can
 * express. This loop visits the configured tenants in order and is the fairness policy.</p>
 */
public final class ExecutionRecoveryService {

    private final ExecutionStore store;
    private final List<String> tenantIds;
    private final String workerId;
    private final int batchLimit;
    private final Duration leaseTtl;
    private final RepeatabilityDeclarations declarations;
    private final RecoveryDispatcher dispatcher;
    private final int maxRecoveryDeliveriesPerAttempt;

    public ExecutionRecoveryService(ExecutionStore store, List<String> tenantIds, String workerId,
                                    int batchLimit, Duration leaseTtl,
                                    RepeatabilityDeclarations declarations,
                                    RecoveryDispatcher dispatcher) {
        this(store, tenantIds, workerId, batchLimit, leaseTtl, declarations, dispatcher,
                GraphExecutionLimits.DEFAULTS.maxRecoveryDeliveriesPerAttempt());
    }

    public ExecutionRecoveryService(ExecutionStore store, List<String> tenantIds, String workerId,
                                    int batchLimit, Duration leaseTtl,
                                    RepeatabilityDeclarations declarations,
                                    RecoveryDispatcher dispatcher,
                                    int maxRecoveryDeliveriesPerAttempt) {
        this.store = Objects.requireNonNull(store, "store");
        this.tenantIds = List.copyOf(Objects.requireNonNull(tenantIds, "tenantIds"));
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        if (batchLimit < 1) throw new IllegalArgumentException("batchLimit must be positive");
        this.batchLimit = batchLimit;
        this.leaseTtl = Objects.requireNonNull(leaseTtl, "leaseTtl");
        this.declarations = declarations == null ? RepeatabilityDeclarations.NONE_DECLARED : declarations;
        this.dispatcher = dispatcher == null ? RecoveryDispatcher.NONE : dispatcher;
        if (maxRecoveryDeliveriesPerAttempt < 1
                || maxRecoveryDeliveriesPerAttempt > GraphExecutionLimits.HARD_MAX_RECOVERY_DELIVERIES) {
            throw new IllegalArgumentException("maxRecoveryDeliveriesPerAttempt is outside supported bounds");
        }
        this.maxRecoveryDeliveriesPerAttempt = maxRecoveryDeliveriesPerAttempt;
    }

    /**
     * Runs one sweep across every configured tenant and returns what it decided about each claimed
     * item, in claim order.
     *
     * <p>One sweep rather than a loop, so the caller owns the schedule and a test owns the clock.</p>
     */
    public List<RecoveryOutcome> sweepOnce() {
        var outcomes = new ArrayList<RecoveryOutcome>();
        for (String tenantId : tenantIds) {
            outcomes.addAll(sweepOnce(tenantId));
        }
        return List.copyOf(outcomes);
    }

    /** Runs the same bounded sweep for one configured tenant, used by authenticated decision routes. */
    public List<RecoveryOutcome> sweepOnce(String tenantId) {
        if (!tenantIds.contains(Objects.requireNonNull(tenantId, "tenantId"))) return List.of();
        var outcomes = new ArrayList<RecoveryOutcome>();
        for (PendingWork.TimerDue timer : await(store.claimDueTimers(
                tenantId, workerId, batchLimit, leaseTtl))) {
            outcomes.add(dispatchTimer(timer));
        }
        for (PendingWork item : await(store.claimPendingWork(
                tenantId, workerId, batchLimit, leaseTtl))) {
            outcomes.add(recover(item));
        }
        return List.copyOf(outcomes);
    }

    /**
     * Drives only timers a trusted dispatcher explicitly recognises, then acknowledges their fence.
     *
     * <h2>Why the rebuild gate is deliberately not applied here</h2>
     * <p>A due timer closes a durable wait; it does not rebuild anything. The two dispatchers that
     * own timers settle an approval or a human task by committing store transitions under this
     * claim's own fencing token — no graph is loaded, no runner is built, no authored behaviour runs
     * — so refusing a timer because the execution's pinned document or manifest does not resolve
     * here withholds a wait for a rebuild that this path never performs. It would leave a task that
     * can never expire and an approval that can never lapse, with no bound and nothing to park,
     * because a timer has no attempt to hold a decision against.</p>
     *
     * <p>What the expiry produces <em>is</em> gated: settling the wait commits a re-entry traversal
     * and makes a {@link PendingWork.HandlerTrigger} claimable, and that item rebuilds a runner and
     * is admitted or withheld like any other. So the gate still stands exactly where a rebuild
     * happens, and the wait ahead of it is allowed to end.</p>
     */
    private RecoveryOutcome dispatchTimer(PendingWork.TimerDue timer) {
        if (!dispatcher.canDispatch(timer)) {
            return new RecoveryOutcome.Deferred(timer.key(), timer.workItemId(),
                    "no timer dispatcher available");
        }
        try {
            dispatcher.dispatch(timer, timer.workItemId().toString());
            acknowledge(timer);
            afterAcknowledged(timer);
            return new RecoveryOutcome.HandlerDispatched(timer.key(), timer.workItemId(),
                    timer.traversalId());
        } catch (RuntimeException unavailable) {
            return new RecoveryOutcome.Deferred(timer.key(), timer.workItemId(),
                    "timer dispatch unavailable");
        }
    }

    /**
     * Discovery path: identifies {@link InventoryDisposition#INTERRUPTED} process
     * instances — non-terminal, with no lease or an expired one — directly from the durable
     * inventory, across every tenant this service was constructed with.
     *
     * <h2>Why this exists beside {@link #sweepOnce()}, and what it deliberately does not do</h2>
     * <p>{@link #sweepOnce()} discovers work at <em>attempt</em> granularity, through
     * {@code claimPendingWork}: it finds attempts a dispatcher can act on, claimed and fenced,
     * ready to be dispatched or parked. This method discovers at <em>instance</em> granularity,
     * through the inventory's own derived {@link InventoryDisposition}, which the store computes
     * from the same lease that {@code claimPendingWork} consults — but reports it directly, as a
     * cohort an operator or a caller can see, rather than only implicitly through which attempts
     * happen to still be claimable. Before this method, the only way to learn "which instances are
     * stuck after a restart" was to already know their ids, or to infer it from
     * {@code claimPendingWork}'s side effects; this reads it straight off the inventory instead.</p>
     *
     * <p>This method does <strong>not</strong> dispatch, park, or otherwise mutate anything it
     * finds, and does not replace {@link #sweepOnce()}: a {@link ProcessInventoryEntry} carries no
     * attempt identity or fencing token, so it cannot drive {@link #dispatchNeverStarted} or
     * {@link #resolveAmbiguity} directly. It is deliberately read-only discovery, left for a caller
     * to act on (typically by triggering {@link #sweepOnce()} for the same tenant, or by surfacing
     * the cohort to an operator) — narrowing scope rather than silently reimplementing attempt-level
     * recovery from coarser data.</p>
     *
     * <p>Requires {@link StoreCapability#PROCESS_INVENTORY}; the store itself rejects the query with
     * {@code ExecutionStoreFailure.CapabilityNotSupported} when the capability is not declared,
     * exactly as every other inventory read does.</p>
     * @return interrupted process instances across every configured tenant, most recently created first
     */
    public List<ProcessInventoryEntry> discoverInterrupted() {
        var interrupted = new ArrayList<ProcessInventoryEntry>();
        for (String tenantId : tenantIds) {
            interrupted.addAll(discoverInterrupted(tenantId));
        }
        return List.copyOf(interrupted);
    }

    /**
     * The single-tenant half of {@link #discoverInterrupted()}, exposed separately so a caller that
     * already knows which tenant it cares about — an operator console, or a test simulating one
     * tenant's restart — is not forced to sweep every configured tenant to ask about one.
     * @param tenantId tenant whose interrupted cohort is requested.
     * @return that tenant's interrupted process instances, most recently created first.
     */
    public List<ProcessInventoryEntry> discoverInterrupted(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        var interrupted = new ArrayList<ProcessInventoryEntry>();
        var query = ProcessInventoryQuery.outstanding(batchLimit);
        while (true) {
            var page = await(store.listProcessInstances(tenantId, query));
            for (ProcessInventoryEntry entry : page.items()) {
                if (entry.disposition() == InventoryDisposition.INTERRUPTED) {
                    interrupted.add(entry);
                }
            }
            if (page.nextCursor().isEmpty()) {
                break;
            }
            query = query.after(page.nextCursor().get());
        }
        return List.copyOf(interrupted);
    }

    /**
     * The bounded form of {@link #discoverInterrupted()}, which stops once {@code limit} instances
     * have been found rather than paging a tenant to its end.
     *
     * <p>Exists because a caller that blocks on the answer must be able to bound how long it blocks.
     * {@link #discoverInterrupted()} pages until the cursor runs out, which is right for an operator
     * asking "what is stuck" and wrong for a startup gate holding readiness closed: a deployment
     * inheriting a very large interrupted cohort would refuse traffic for the whole scan, turning a
     * safety check into the outage it exists to prevent. The instances beyond the limit are not
     * lost — they are still claimed and decided by the ordinary sweep, which is what acts on them in
     * any case. Only the report is truncated, and the caller is told that it was.</p>
     *
     * @param limit greatest number of interrupted instances to return; must be positive.
     * @return interrupted instances across the configured tenants, at most {@code limit} of them.
     */
    public List<ProcessInventoryEntry> discoverInterrupted(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        var interrupted = new ArrayList<ProcessInventoryEntry>();
        for (String tenantId : tenantIds) {
            // The limit reaches the pager itself rather than trimming its result. Collecting a
            // tenant's whole cohort and cutting it afterwards would leave the scan exactly as long as
            // it was -- the same page round trips, the same rows resident -- and the caller blocking
            // on this is holding readiness closed, which is the one thing the bound exists to stop.
            // A tenant that fills the limit therefore ends the scan mid-page, and later tenants are
            // not visited at all.
            pageInterrupted(tenantId, limit - interrupted.size(), interrupted);
            if (interrupted.size() >= limit) {
                break;
            }
        }
        return List.copyOf(interrupted);
    }

    /** Pages one tenant only until {@code remaining} interrupted instances have been collected. */
    private void pageInterrupted(String tenantId, int remaining, List<ProcessInventoryEntry> into) {
        var query = ProcessInventoryQuery.outstanding(Math.min(batchLimit, Math.max(1, remaining)));
        while (true) {
            var page = await(store.listProcessInstances(tenantId, query));
            for (ProcessInventoryEntry entry : page.items()) {
                if (entry.disposition() == InventoryDisposition.INTERRUPTED) {
                    into.add(entry);
                    if (--remaining <= 0) {
                        return;
                    }
                }
            }
            if (page.nextCursor().isEmpty()) {
                return;
            }
            query = query.after(page.nextCursor().get());
        }
    }

    private RecoveryOutcome recover(PendingWork item) {
        if (item instanceof PendingWork.HandlerTrigger trigger) {
            // A trigger rebuilds a runner, so it is gated. It carries no attempt, so there is nothing
            // to park and nothing to bound: a handler this deployment cannot rebuild stays waiting,
            // exactly as it did before any of this existed, and the startup report is what surfaces it.
            RecoveryAdmission admission = admissionOf(trigger);
            if (!admission.proceeds()) {
                return new RecoveryOutcome.Deferred(trigger.key(), trigger.workItemId(),
                        "recovery is withheld for this execution: " + admission.detail());
            }
            return dispatchHandler(trigger);
        }
        if (!(item instanceof PendingWork.AttemptDispatch attemptItem)) {
            // Timers carry no ambiguity of their own: they are signals to a waiting invocation,
            // not effects that may already have happened. They are left
            // unacknowledged so nothing is lost, because a lost timer is worse than a duplicate one.
            return new RecoveryOutcome.Deferred(item.key(), item.workItemId(),
                    "no dispatcher for " + item.getClass().getSimpleName());
        }

        StoredProcessInstance stored;
        try {
            stored = await(store.load(attemptItem.key()));
        } catch (ExecutionStoreException absent) {
            return acknowledgeStale(attemptItem, "process instance is gone");
        }

        NodeAttempt attempt = findAttempt(stored.state(), attemptItem);
        if (attempt == null) {
            return acknowledgeStale(attemptItem, "attempt is gone");
        }
        // Asked after the aggregate is in hand rather than before it, so the admission and the
        // decision that follows read one instance rather than two. A stale claim is settled above
        // without asking at all, which is what it was already doing.
        RecoveryAdmission admission = admissionOf(attemptItem);
        return switch (attempt.status()) {
            case SCHEDULED -> dispatchNeverStarted(attemptItem, stored, admission);
            case RUNNING -> resolveAmbiguity(attemptItem, stored, attempt, admission);
            // Unreachable through a conforming adapter, which excludes these from the claim query.
            // Handled anyway: a claim that raced a concurrent transition must be acknowledged rather
            // than re-decided, and a PARKED attempt must never be re-decided by a machine at all.
            case PARKED -> acknowledgeStale(attemptItem, "already parked, awaiting a human decision");
            case WAITING -> acknowledgeStale(attemptItem, "waiting on a timer or trigger");
            case COMPLETED, FAILED -> acknowledgeStale(attemptItem, "already terminal");
        };
    }

    private RecoveryOutcome dispatchHandler(PendingWork.HandlerTrigger trigger) {
        if (!dispatcher.canDispatch(trigger)) {
            return new RecoveryOutcome.Deferred(trigger.key(), trigger.workItemId(),
                    "no handler re-entry dispatcher available");
        }
        try {
            dispatcher.dispatch(trigger, trigger.workItemId().toString());
            acknowledge(trigger);
            afterAcknowledged(trigger);
            return new RecoveryOutcome.HandlerDispatched(trigger.key(), trigger.workItemId(),
                    trigger.traversalId());
        } catch (RuntimeException unavailable) {
            return new RecoveryOutcome.Deferred(trigger.key(), trigger.workItemId(),
                    "handler re-entry dispatch unavailable");
        }
    }

    /**
     * The attempt is still {@code SCHEDULED}, so the write-ordering invariant proves no effect began.
     *
     * <p>{@code canDispatch} is consulted <em>before</em> the {@code RUNNING} write. Writing
     * {@code RUNNING} for work that then could not be sent would manufacture the very ambiguity this
     * method exists to rule out: the next sweep would see {@code RUNNING}, could not know the send
     * never happened, and would park work that provably never started.</p>
     */
    private RecoveryOutcome dispatchNeverStarted(PendingWork.AttemptDispatch item,
                                                 StoredProcessInstance stored,
                                                 RecoveryAdmission admission) {
        if (!admission.proceeds()) {
            // Withheld without a bound, and deliberately never parked, however long the refusal
            // lasts. The aggregate refuses SCHEDULED -> PARKED outright, and that refusal encodes the
            // reason: parking asks a human to adjudicate an effect, and this attempt provably never
            // produced one. Recording a park here would put a question in front of an operator whose
            // honest answer is already known. Nothing is at risk while it waits — no effect is in
            // flight, the attempt is untouched, and a corrected deployment dispatches it normally.
            return new RecoveryOutcome.Deferred(item.key(), item.workItemId(),
                    "recovery is withheld for this execution: " + admission.detail());
        }
        if (!dispatcher.canDispatch(item)) {
            return new RecoveryOutcome.Deferred(item.key(), item.workItemId(), "no dispatcher available");
        }
        await(store.apply(ExecutionBatch.to(item.key())
                .expecting(RevisionExpectation.exactly(stored.revision()))
                .fencedBy(item.fencingToken())
                .apply(new ExecutionTransition.AttemptTransitioned(item.traversalId(), item.invocationId(),
                        item.attemptId(), NodeAttemptStatus.RUNNING))
                .build()));
        dispatcher.dispatch(item, effectKeyOf(item));
        return new RecoveryOutcome.Dispatched(item.key(), item.workItemId(), item.attemptId());
    }

    /**
     * The attempt is {@code RUNNING}: it was dispatched and the outcome was never learned.
     *
     * <p>Only an explicit {@code repeatable} declaration on the node instance authorises sending it
     * again. Everything else — declared not repeatable, declared nothing, a value the reader could
     * not recognise, a type that never declared the property — parks.</p>
     */
    private RecoveryOutcome resolveAmbiguity(PendingWork.AttemptDispatch item, StoredProcessInstance stored,
                                             NodeAttempt attempt, RecoveryAdmission admission) {
        if (admission.repairableByWaiting()) {
            // Withheld without consuming a delivery, and the store's counter is what makes that a
            // claim rather than a hope. It counts claims, not decisions, so a delivery on which
            // nothing was dispatched still increments it; left alone, an outage would spend the whole
            // budget and the limit below would fire the moment the outage ended, parking en masse
            // work whose only fault was being in flight at the wrong moment. The mark below is
            // subtracted at evaluation, and it is durable because the outage and the recovery
            // routinely straddle a restart.
            recordWithheld(item, stored);
            return new RecoveryOutcome.Deferred(item.key(), item.workItemId(),
                    "recovery is withheld until this execution can be classified: " + admission.detail());
        }
        String nodeId = nodeIdOf(stored.state(), item);
        // Read only when the execution was admitted. A withheld one has no readable declaration by
        // construction — the document behind it is the thing that did not resolve — so asking would
        // spend a second aggregate read and a second manifest verification to be told UNDECLARED.
        AttemptRepeatability declaration = admission.proceeds()
                ? declarationOf(item.key(), nodeId) : AttemptRepeatability.UNDECLARED;
        // Deliveries that reached a decision, not claims. See NodeAttempt.decidedDeliveries.
        int decided = attempt.decidedDeliveries(item.deliveryAttempt());
        if (decided > maxRecoveryDeliveriesPerAttempt) {
            // The bound that ends a deterministic refusal. An execution this deployment will never
            // rebuild strands an effect that already happened and whose outcome nobody knows, and
            // withholding that forever is worse than the park it replaced: the park at least puts the
            // decision in front of a human. The cause names the deployment fault rather than
            // reporting the attempt as though its author had declared nothing, so the operator reads
            // why the machine gave up instead of being asked to adjudicate a silence.
            //
            // The bound is maxRecoveryDeliveriesPerAttempt rather than a second knob: it is already
            // the operator's control over how long recovery keeps re-delivering one attempt before
            // calling a human, and a deterministic refusal is that same wait arriving by a different
            // route. A separate setting would be a second thing to tune for one behaviour.
            String cause = admission.proceeds()
                    ? "recovery delivery limit exceeded on decided delivery " + decided
                    : "recovery delivery limit exceeded on decided delivery " + decided
                            + "; this deployment cannot rebuild the execution: " + admission.detail();
            return park(item, stored, declaration, cause);
        }
        if (!admission.proceeds()) {
            return new RecoveryOutcome.Deferred(item.key(), item.workItemId(),
                    "recovery is withheld for this execution: " + admission.detail());
        }
        if (declaration.authorisesReDispatch()) {
            if (!dispatcher.canDispatch(item)) {
                return new RecoveryOutcome.Deferred(item.key(), item.workItemId(), "no dispatcher available");
            }
            // The status is already RUNNING and stays RUNNING: the redelivery is visible in
            // deliveryAttempt, not in the aggregate. The store's idempotency machinery deduplicates
            // the repeat under the attempt id.
            dispatcher.dispatch(item, effectKeyOf(item));
            return new RecoveryOutcome.ReDispatched(item.key(), item.workItemId(), item.attemptId());
        }

        String cause = "dispatched with unknown outcome on delivery " + item.deliveryAttempt()
                + "; node '" + nodeId + "' is " + declaration.name().toLowerCase(java.util.Locale.ROOT)
                .replace('_', ' ');
        return park(item, stored, declaration, cause);
    }

    /**
     * Raises the attempt's withheld high-water mark to this delivery, under the claim's own fence.
     *
     * <p>Best effort by design. The mark is an optimisation of the delivery limit, not a safety
     * property: failing to write it costs exactly one delivery of budget, which is the behaviour that
     * existed before the mark did, whereas letting the failure escape would abort the sweep and leave
     * every item behind this one undecided. The write is fenced and revision-checked like every other
     * write here, so a stale owner cannot raise a mark after takeover, and re-writing a value the
     * attempt already carries is a no-op rather than a conflict.</p>
     */
    private void recordWithheld(PendingWork.AttemptDispatch item, StoredProcessInstance stored) {
        try {
            await(store.apply(ExecutionBatch.to(item.key())
                    .expecting(RevisionExpectation.exactly(stored.revision()))
                    .fencedBy(item.fencingToken())
                    .apply(new ExecutionTransition.RecoveryWithheld(item.traversalId(),
                            item.invocationId(), item.attemptId(), item.deliveryAttempt()))
                    .build()));
        } catch (RuntimeException notRecorded) {
            // See above: one delivery of budget, and the sweep continues.
        }
    }

    private RecoveryOutcome park(PendingWork.AttemptDispatch item, StoredProcessInstance stored,
                                 AttemptRepeatability declaration, String cause) {
        await(store.apply(ExecutionBatch.to(item.key())
                .expecting(RevisionExpectation.exactly(stored.revision()))
                .fencedBy(item.fencingToken())
                .apply(new ExecutionTransition.AttemptParked(item.traversalId(), item.invocationId(),
                        item.attemptId(), cause))
                .build()));
        // The park commits first and the acknowledgement follows, and the order is safe rather than
        // merely convenient: a conforming adapter excludes PARKED attempts from the claim query, so
        // the moment the park is durable the item can no longer be redelivered whether or not the
        // acknowledgement lands. A crash in between leaves an inert claim row and loses nothing.
        acknowledge(item);
        return new RecoveryOutcome.Parked(item.key(), item.workItemId(), item.attemptId(), declaration, cause);
    }

    /**
     * The dispatcher's own answer about whether this execution may be acted on here.
     *
     * <p>A dispatcher that cannot answer has not admitted anything, and the failure is reported as
     * retryable rather than deterministic: an exception from an admission check is a fault in the
     * check, not evidence about the deployment, and treating it as deterministic would spend the
     * delivery budget and park work on the strength of a bug.</p>
     */
    private RecoveryAdmission admissionOf(PendingWork item) {
        try {
            RecoveryAdmission admission = dispatcher.admits(item);
            return admission == null ? RecoveryAdmission.admitted() : admission;
        } catch (RuntimeException unreadable) {
            return RecoveryAdmission.retryable("the admission check could not be completed");
        }
    }

    /**
     * Reads the declaration and converts any failure of the source into
     * {@link AttemptRepeatability#UNDECLARED}.
     *
     * <p>A source that throws has not declared anything. Letting the exception escape would abort the
     * sweep and leave the ambiguous attempt neither dispatched nor parked — which is the one outcome
     * worse than either, because nothing records that a decision was owed.</p>
     *
     * <p>The instance is passed as well as the node, because after a restart the node id alone does
     * not identify a declaration: the same id in two graphs is two different authors' assertions.
     * A source that resolves the document an execution was pinned to needs the instance to find it;
     * a source that is already a snapshot of one graph ignores it through the interface default.</p>
     */
    private AttemptRepeatability declarationOf(ExecutionKey key, String nodeId) {
        try {
            AttemptRepeatability declared = declarations.declaredFor(key, nodeId);
            return declared == null ? AttemptRepeatability.UNDECLARED : declared;
        } catch (RuntimeException unreadable) {
            return AttemptRepeatability.UNDECLARED;
        }
    }

    private RecoveryOutcome acknowledgeStale(PendingWork.AttemptDispatch item, String reason) {
        acknowledge(item);
        return new RecoveryOutcome.Stale(item.key(), item.workItemId(), reason);
    }

    private void acknowledge(PendingWork item) {
        try {
            await(store.ack(item));
        } catch (ExecutionStoreException alreadyGone) {
            // An item another worker acknowledged first, or one whose instance vanished. Both mean
            // the acknowledgement's purpose is already served.
        }
    }

    private void afterAcknowledged(PendingWork item) {
        try {
            dispatcher.afterAcknowledged(item);
        } catch (RuntimeException cleanupFailure) {
            // A dispatcher cleanup cannot undo the durable acknowledgement. Any retained lease is
            // bounded by its TTL, so keep the successful recovery outcome truthful.
        }
    }

    /** The idempotency key of a re-dispatch is the attempt id. */
    private static String effectKeyOf(PendingWork.AttemptDispatch item) {
        return item.attemptId().toString();
    }

    private static NodeAttempt findAttempt(ProcessInstance state, PendingWork.AttemptDispatch item) {
        NodeInvocation invocation = invocationOf(state, item);
        if (invocation == null) {
            return null;
        }
        return invocation.attempts().stream()
                .filter(candidate -> candidate.attemptId().equals(item.attemptId()))
                .findFirst()
                .orElse(null);
    }

    private static String nodeIdOf(ProcessInstance state, PendingWork.AttemptDispatch item) {
        NodeInvocation invocation = invocationOf(state, item);
        return invocation == null ? "" : invocation.nodeId();
    }

    private static NodeInvocation invocationOf(ProcessInstance state, PendingWork.AttemptDispatch item) {
        Traversal traversal = state.traversals().get(item.traversalId());
        return traversal == null ? null : traversal.invocations().get(item.invocationId());
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException wrapped) {
            ExecutionStoreException failure = ExecutionStoreException.unwrap(wrapped);
            throw failure == null ? wrapped : failure;
        }
    }

    /** Identifies this recovery worker to the store; exposed so an operator can correlate leases. */
    public String workerId() {
        return workerId;
    }
}
