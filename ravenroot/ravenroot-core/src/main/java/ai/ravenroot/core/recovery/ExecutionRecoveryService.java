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
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.catalog.AttemptRepeatability;
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
 * <p>Making outstanding items <em>dispatchable</em> with correct lease, fencing and idempotency
 * semantics — not resuming execution. Resuming would require re-parsing the graph, and the graph
 * bytes are stored nowhere; only a hash is pinned, and no definition store exists. Parked and
 * recovered state is meaningful and human-decidable without the definition, which is why this is a
 * complete unit of work even without a definition store.</p>
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
            for (PendingWork item : await(store.claimPendingWork(tenantId, workerId, batchLimit, leaseTtl))) {
                outcomes.add(recover(item));
            }
        }
        return List.copyOf(outcomes);
    }

    private RecoveryOutcome recover(PendingWork item) {
        if (!(item instanceof PendingWork.AttemptDispatch attemptItem)) {
            // Timers and handler triggers carry no ambiguity of their own: they are signals to a
            // waiting invocation, not effects that may already have happened. They are left
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
        return switch (attempt.status()) {
            case SCHEDULED -> dispatchNeverStarted(attemptItem, stored);
            case RUNNING -> resolveAmbiguity(attemptItem, stored);
            // Unreachable through a conforming adapter, which excludes these from the claim query.
            // Handled anyway: a claim that raced a concurrent transition must be acknowledged rather
            // than re-decided, and a PARKED attempt must never be re-decided by a machine at all.
            case PARKED -> acknowledgeStale(attemptItem, "already parked, awaiting a human decision");
            case WAITING -> acknowledgeStale(attemptItem, "waiting on a timer or trigger");
            case COMPLETED, FAILED -> acknowledgeStale(attemptItem, "already terminal");
        };
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
                                                 StoredProcessInstance stored) {
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
    private RecoveryOutcome resolveAmbiguity(PendingWork.AttemptDispatch item, StoredProcessInstance stored) {
        String nodeId = nodeIdOf(stored.state(), item);
        AttemptRepeatability declaration = declarationOf(nodeId);
        if (item.deliveryAttempt() > maxRecoveryDeliveriesPerAttempt) {
            String cause = "recovery delivery limit exceeded on delivery " + item.deliveryAttempt();
            return park(item, stored, declaration, cause);
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
     * Reads the declaration and converts any failure of the source into
     * {@link AttemptRepeatability#UNDECLARED}.
     *
     * <p>A source that throws has not declared anything. Letting the exception escape would abort the
     * sweep and leave the ambiguous attempt neither dispatched nor parked — which is the one outcome
     * worse than either, because nothing records that a decision was owed.</p>
     */
    private AttemptRepeatability declarationOf(String nodeId) {
        try {
            AttemptRepeatability declared = declarations.declaredFor(nodeId);
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
