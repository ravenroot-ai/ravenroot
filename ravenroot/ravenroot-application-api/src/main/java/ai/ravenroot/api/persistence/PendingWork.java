package ai.ravenroot.api.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * A claimed unit of outstanding work (ADR 0010 sections 7 and 9).
 *
 * <p>A claim returns a <strong>projection</strong> — identities, kind, fence, lease and delivery
 * counter — and deliberately not the aggregate: returning the aggregate would force a remote adapter
 * to ship an entire causal graph on every claim. The caller loads the aggregate explicitly when it
 * needs it.</p>
 *
 * <p>Delivery is <strong>at-least-once</strong> and there is <strong>no ordering guarantee</strong>
 * across kinds. Anything stronger cannot be honoured by a distributed adapter, so promising it in
 * the port would make the conformance suite unsatisfiable rather than making the system stronger.</p>
 *
 * <p>Fan-in is deliberately <em>not</em> a kind here. It is state, not work, and it models arrivals
 * that precede invocation creation; CORE-03 adds it as a separately keyed compare-and-set resource.</p>
 */
public sealed interface PendingWork {

/**
 * Returns the execution aggregate to which the claimed work belongs.
 * @return durable execution key.
 */
    ExecutionKey key();

/**
 * Stable identity of this work item, used to acknowledge it.
 * @return identifier used to acknowledge this particular delivery.
 */
    UUID workItemId();

/**
 * Token that must be presented on any write derived from this work item.
 * @return token required by writes derived from this claim.
 */
    long fencingToken();

/**
 * When this claim's visibility window ends, on the store's clock.
 * @return store-clock deadline after which the claim may be redelivered.
 */
    Instant leaseExpiresAt();

/**
 * How many times this item has been claimed, starting at one. Rising values indicate redelivery.
 * @return one-based delivery count for redelivery diagnosis.
 */
    int deliveryAttempt();

/**
 * A scheduled node attempt is ready to be dispatched to the engine.
 * @param key the stable key used to identify the requested resource.
 * @param workItemId the stable work item id used to identify the requested resource.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param invocationId the stable invocation id used to identify the requested resource.
 * @param attemptId the stable attempt id used to identify the requested resource.
 * @param attemptOrdinal the attempt ordinal constraint applied while processing the request.
 * @param fencingToken the stable fencing token used to identify the requested resource.
 * @param leaseExpiresAt instant at which the pending-work lease expires.
 * @param deliveryAttempt number of earlier delivery attempts.
 * @param command command awaiting delivery.
 */
    record AttemptDispatch(ExecutionKey key, UUID workItemId, UUID traversalId, UUID invocationId,
                           UUID attemptId, int attemptOrdinal, long fencingToken,
                           Instant leaseExpiresAt, int deliveryAttempt,
                           ai.ravenroot.api.execution.NodeCommand command) implements PendingWork {
        /** Normalizes the command delivered for a claimed node attempt. */
        public AttemptDispatch {
            command = command == null ? ai.ravenroot.api.execution.NodeCommand.PROCESS : command;
        }

/**
 * Compatibility constructor for work recorded before structural commands.
 * @param key the stable key used to identify the requested resource.
 * @param workItemId the stable work item id used to identify the requested resource.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param invocationId the stable invocation id used to identify the requested resource.
 * @param attemptId the stable attempt id used to identify the requested resource.
 * @param attemptOrdinal the attempt ordinal constraint applied while processing the request.
 * @param fencingToken the stable fencing token used to identify the requested resource.
 * @param leaseExpiresAt instant at which the pending-work lease expires.
 * @param deliveryAttempt number of earlier delivery attempts.
 */
        public AttemptDispatch(ExecutionKey key, UUID workItemId, UUID traversalId, UUID invocationId,
                               UUID attemptId, int attemptOrdinal, long fencingToken,
                               Instant leaseExpiresAt, int deliveryAttempt) {
            this(key, workItemId, traversalId, invocationId, attemptId, attemptOrdinal, fencingToken,
                    leaseExpiresAt, deliveryAttempt, ai.ravenroot.api.execution.NodeCommand.PROCESS);
        }
    }

    /**
     * A durable timer has come due on the store's clock.
     *
     * @param dueAt the original due instant rather than an opaque handle, so scheduling lag is
     *              computable by the runtime and by operators
 * @param key the stable key used to identify the requested resource.
 * @param workItemId the stable work item id used to identify the requested resource.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param invocationId the stable invocation id used to identify the requested resource.
 * @param payload bounded payload carried by the pending work.
 * @param fencingToken the stable fencing token used to identify the requested resource.
 * @param leaseExpiresAt instant at which the pending-work lease expires.
 * @param deliveryAttempt number of earlier delivery attempts.
     */
    record TimerDue(ExecutionKey key, UUID workItemId, UUID traversalId, UUID invocationId,
                    Instant dueAt, OpaquePayload payload, long fencingToken,
                    Instant leaseExpiresAt, int deliveryAttempt) implements PendingWork {
    }

    /**
     * An authorized external trigger correlated to a waiting invocation.
     *
     * <p>PERS-02 declared this kind and produced none, because there was no handler registration
     * surface yet; declaring it early kept PERS-05 additive rather than a breaking change to a sealed
     * type that adapters had already switched over exhaustively. PERS-05 is that surface, and this is
     * now produced: a {@link DurableHandler} that reaches a state reporting
     * {@link HandlerStatus#resumesProcess()} becomes claimable exactly once, and its
     * {@link #workItemId()} is the handler's own identity.</p>
     *
     * <p>{@code traversalId} is the <strong>re-entry</strong> traversal the terminal handler
     * transition committed, never the traversal that was waiting. That is the whole of "an authorized
     * trigger starts a new traversal of the existing process without reviving stale actor state": the
     * claimant is handed durable identities and a payload, and there is nothing here for it to resume
     * <em>into</em> — no continuation, no actor reference, nothing that could have survived only in a
     * process that is no longer running.</p>
     *
     * <p>{@code payload} is the outcome body the resolving principal supplied, already validated
     * against the handler's {@link HandlerPayloadSchema} at resolution time. An expiry carries an
     * empty payload, because nobody supplied one.</p>
     *
     * <p>{@code invocationId} is <strong>always absent</strong> on this kind, unlike on every other,
     * and that is a statement rather than an omission. A re-entry traversal holds no invocation when
     * it is committed: the claimant creates the first one. Naming the invocation that <em>waited</em>
     * would pair a new traversal with an invocation living under the old one — a pair no lookup
     * resolves, since asking the re-entry traversal for it yields nothing — and it is the invocation
     * whose wait is over, so it would also read as work still outstanding. The waiting invocation
     * stays reachable through {@link ExecutionStore#loadHandler(ExecutionKey, java.util.UUID)},
     * keyed by this item's own {@link #workItemId()}, which is the handler id.</p>
 * @param key the stable key used to identify the requested resource.
 * @param workItemId the stable work item id used to identify the requested resource.
 * @param traversalId the re-entry traversal committed with the handler's terminal transition.
 * @param invocationId always {@code null} for this kind; see above.
 * @param handlerName handler selected to process the pending work.
 * @param payload bounded payload carried by the pending work.
 * @param fencingToken the stable fencing token used to identify the requested resource.
 * @param leaseExpiresAt instant at which the pending-work lease expires.
 * @param deliveryAttempt number of earlier delivery attempts.
     */
    record HandlerTrigger(ExecutionKey key, UUID workItemId, UUID traversalId, UUID invocationId,
                          String handlerName, OpaquePayload payload, long fencingToken,
                          Instant leaseExpiresAt, int deliveryAttempt) implements PendingWork {
    }
}
