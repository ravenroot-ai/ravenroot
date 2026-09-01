package ai.ravenroot.api.application;

/** Identifies the lifecycle scope for which a stable execution identity is requested. */
public enum ExecutionIdentityKind {
    /** Stable identity assigned to one persisted process instance. */
    PROCESS_INSTANCE,
    /** Stable identity assigned to one ingress or re-entry traversal. */
    TRAVERSAL,
    /** Stable identity assigned to one visit to a logical graph node. */
    NODE_INVOCATION,
    /** Stable identity assigned to one delivery attempt for an invocation. */
    NODE_ATTEMPT,

    /**
     * The identity of one published {@link ai.ravenroot.api.persistence.EventEnvelope}.
     *
     * <p>It is minted here rather than by the store because it is the envelope's deduplication key
     * <em>and</em> the value another envelope names as its {@code causationId}: the producer has to
     * hold it before it publishes the effect, and a store-assigned identity would not exist yet.</p>
     *
     * <p>Unlike every kind above, a single traversal asks for many of these. An implementation that
     * answers a fixed identity per kind is therefore valid for the four scopes above and invalid
     * here, and the envelope says so rather than storing it: two envelopes sharing an id collide on
     * the inbox deduplication key, and an effect that drew the same id as its cause is rejected by
     * {@code EventEnvelope}'s "an event cannot be its own cause".</p>
     */
    EVENT
}
