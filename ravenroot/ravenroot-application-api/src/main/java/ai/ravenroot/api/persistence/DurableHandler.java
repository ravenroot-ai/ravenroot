package ai.ravenroot.api.persistence;

import java.util.UUID;

/**
 * The store-side view of one registered handler (PERS-05).
 *
 * <p>It is to {@link HandlerRegistration} what {@link StoredProcessInstance} is to
 * {@link ai.ravenroot.api.application.ProcessInstance}: the caller-authored registration plus
 * everything the store owns — the current {@link HandlerStatus}, the outcome that closed it, and the
 * revision at which it last changed.</p>
 *
 * <p>A terminal handler is <strong>retained, not deleted</strong>. It is the evidence that a
 * duplicate or late trigger is refused against, the record an operator reads to see who resolved a
 * human task and when, and the row that keeps a deduplication key from being reused. Deleting it on
 * resolution would make every one of those questions unanswerable and would silently re-open the
 * handler to a redelivered trigger.</p>
 *
 * @param handlerId        stable handler identity, and the {@link PendingWork#workItemId()} of the
 *                         trigger this handler produces when it reaches a terminal state
 * @param key              tenant-scoped identity of the owning process instance
 * @param name             opaque handler name
 * @param traversalId      the traversal that was waiting when this handler was registered
 * @param invocationId     the node invocation that was waiting
 * @param correlationKey   business identity an inbound trigger presents
 * @param deduplicationKey registration idempotency key
 * @param payloadSchema    the shape a trigger payload must have
 * @param authorization    what a principal must present before a trigger may act on this handler
 * @param status           current lifecycle state
 * @param resumeTraversalId the re-entry traversal committed with the terminal transition, or
 *                          {@code null} while the handler is not terminal
 * @param actor            audit-stable identity that closed the handler, or the empty string when
 *                         nobody did — an expiry has no actor, and inventing one would put a
 *                         fabricated principal into a record read as an audit trail
 * @param outcomePayload   body carried into the resumed traversal; an empty payload while the
 *                         handler is not terminal and for an expiry
 * @param revision         the process-instance revision at which this handler last changed, so a
 *                         caller can correlate it with the aggregate it is holding, exactly as
 *                         {@link IdempotencyRecord#recordedAtRevision()} does
 */
public record DurableHandler(UUID handlerId, ExecutionKey key, String name, UUID traversalId,
                             UUID invocationId, String correlationKey, String deduplicationKey,
                             HandlerPayloadSchema payloadSchema, HandlerAuthorization authorization,
                             HandlerStatus status, UUID resumeTraversalId, String actor,
                             OpaquePayload outcomePayload, long revision) {

    /** Validates a stored handler and the consistency of its terminal fields with its status. */
    public DurableHandler {
        if (handlerId == null) throw new IllegalArgumentException("handlerId cannot be null");
        if (key == null) throw new IllegalArgumentException("key cannot be null");
        name = HandlerRegistration.requireBoundedKey(name, "name");
        if (traversalId == null) throw new IllegalArgumentException("traversalId cannot be null");
        if (invocationId == null) throw new IllegalArgumentException("invocationId cannot be null");
        correlationKey = HandlerRegistration.requireBoundedKey(correlationKey, "correlationKey");
        deduplicationKey = HandlerRegistration.requireBoundedKey(deduplicationKey, "deduplicationKey");
        if (payloadSchema == null) throw new IllegalArgumentException("payloadSchema cannot be null");
        if (authorization == null) throw new IllegalArgumentException("authorization cannot be null");
        if (status == null) throw new IllegalArgumentException("status cannot be null");
        if (actor == null) actor = "";
        if (outcomePayload == null) {
            outcomePayload = OpaquePayload.empty(HandlerTransition.EMPTY_CONTENT_TYPE);
        }
        // The invariant a reconstructing adapter is classified Corrupted against: a resuming state
        // without its traversal would be a handler that closed a process and told nobody where it
        // went, and a traversal recorded against a still-waiting handler would be a re-entry nothing
        // authorized.
        if (status.resumesProcess() == (resumeTraversalId == null)) {
            throw new IllegalArgumentException("a " + status + " handler "
                    + (resumeTraversalId == null ? "requires" : "cannot carry") + " a resumeTraversalId");
        }
    }

    /**
     * Creates the initial stored view of a freshly registered handler.
     * @param key the stable key used to identify the requested resource.
     * @param registration caller-authored handler registration.
     * @param revision process-instance revision at which the registration commits.
     * @return waiting handler carrying no outcome.
     */
    public static DurableHandler waiting(ExecutionKey key, HandlerRegistration registration, long revision) {
        if (registration == null) throw new IllegalArgumentException("registration cannot be null");
        return new DurableHandler(registration.handlerId(), key, registration.name(),
                registration.traversalId(), registration.invocationId(), registration.correlationKey(),
                registration.deduplicationKey(), registration.payloadSchema(), registration.authorization(),
                HandlerStatus.WAITING, null, "", OpaquePayload.empty(HandlerTransition.EMPTY_CONTENT_TYPE),
                revision);
    }

    /**
     * Tests whether {@code registration} describes this same handler.
     *
     * <p>What "same" means is settled here rather than in each adapter, because it is the predicate
     * that decides whether a repeated registration is a safe retry or a caller bug, and two adapters
     * answering it differently would make the same batch a no-op against one store and a rejection
     * against another. The comparison covers identity and the fields that determine <em>what is being
     * waited for</em>; it deliberately excludes the payload schema and the authorization requirement,
     * because a retry that tightened either is still a retry of the same wait and refusing it would
     * strand a process on a crash boundary.</p>
     * @param registration caller-authored registration offered again.
     * @return whether the offered registration names this handler and the same wait.
     */
    public boolean matches(HandlerRegistration registration) {
        return registration != null
                && handlerId.equals(registration.handlerId())
                && name.equals(registration.name())
                && correlationKey.equals(registration.correlationKey())
                && deduplicationKey.equals(registration.deduplicationKey())
                && traversalId.equals(registration.traversalId())
                && invocationId.equals(registration.invocationId());
    }

    /**
     * Applies a legal handler transition, returning the resulting stored view.
     *
     * <p>Rejects an illegal transition with {@link IllegalStateException}, mirroring how
     * {@link ExecutionTransition#applyTo} lets the domain reject rather than the adapter. Adapters
     * translate that into {@link ExecutionStoreFailure.HandlerNotResolvable} when applying a caller's
     * batch and into {@link ExecutionStoreFailure.Corrupted} when replaying their own stored state.</p>
     * @param transition legal handler transition to fold over this handler.
     * @param revision process-instance revision at which the transition commits.
     * @return handler state after the transition.
     */
    public DurableHandler apply(HandlerTransition transition, long revision) {
        if (transition == null) throw new IllegalArgumentException("transition cannot be null");
        if (!handlerId.equals(transition.handlerId())) {
            throw new IllegalArgumentException("transition targets handler " + transition.handlerId()
                    + " but was applied to " + handlerId);
        }
        if (!status.canTransitionTo(transition.next())) {
            throw new IllegalStateException("Illegal handler transition: " + status + " -> "
                    + transition.next());
        }
        return new DurableHandler(handlerId, key, name, traversalId, invocationId, correlationKey,
                deduplicationKey, payloadSchema, authorization, transition.next(),
                transition.resumeTraversalId(), transition.actor(), transition.outcomePayload(), revision);
    }
}
