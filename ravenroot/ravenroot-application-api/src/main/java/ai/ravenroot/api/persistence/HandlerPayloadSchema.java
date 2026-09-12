package ai.ravenroot.api.persistence;

import java.util.Optional;

/**
 * The shape a trigger's payload must have before a durable handler will accept it (PERS-05).
 *
 * <h2>What the store checks, and what it refuses to check</h2>
 * <p>The store enforces the two properties it can decide without understanding the payload: the
 * declared {@link #contentType()} must match exactly, and the byte count must not exceed
 * {@link #maxBytes()}. {@link #schemaRef()} is an <strong>opaque reference</strong> the store records
 * and never resolves. Resolving it would mean the persistence port had acquired a schema registry, a
 * validator and an opinion about payload dialects, and a remote adapter would then have to ship one
 * too; worse, an adapter that validated slightly differently from another would still pass a
 * conformance suite written in the port's own vocabulary, so two adapters could disagree about which
 * triggers are admissible with nothing to catch it.</p>
 *
 * <p>The content type is checked rather than merely recorded because a payload arriving under an
 * unexpected media type is the case where a validator downstream is most likely to guess. The size
 * bound is checked because it is the handler's own bound and is narrower than
 * {@link ExecutionStore#maxPayloadBytes()}: a handler expecting an approval decision should not be
 * the route by which a megabyte reaches the journal.</p>
 *
 * @param contentType media type every trigger payload for this handler must carry, exactly
 * @param schemaRef   opaque schema identity recorded for the runtime and for operators; never
 *                    resolved or interpreted by the store
 * @param maxBytes    inclusive upper bound on the trigger payload size, in bytes
 */
public record HandlerPayloadSchema(String contentType, String schemaRef, int maxBytes) {

    /** Validates the declared payload contract before a handler can be registered. */
    public HandlerPayloadSchema {
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("contentType cannot be blank");
        }
        if (schemaRef == null || schemaRef.isBlank()) {
            throw new IllegalArgumentException("schemaRef cannot be blank");
        }
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes cannot be negative");
        }
    }

    /**
     * Returns why {@code payload} does not conform, or empty when it does.
     *
     * <p>An outcome rather than an exception, because both answers are ordinary: a non-conforming
     * trigger is a deterministic refusal the caller must audit, not a programming error, and the
     * reason travels into {@link ExecutionStoreFailure.InvalidRequest} and into an audit record. The
     * text names the media types and the sizes and never the bytes.</p>
     * @param payload trigger payload offered for this handler.
     * @return payload-safe non-conformance reason, or empty when the payload conforms.
     */
    public Optional<String> rejectionOf(OpaquePayload payload) {
        if (payload == null) {
            return Optional.of("a trigger payload is mandatory");
        }
        if (!contentType.equals(payload.contentType())) {
            return Optional.of("payload content type " + payload.contentType()
                    + " does not match the handler's declared " + contentType);
        }
        if (payload.size() > maxBytes) {
            return Optional.of("payload of " + payload.size() + " bytes exceeds the handler's declared "
                    + maxBytes + "-byte bound");
        }
        return Optional.empty();
    }
}
