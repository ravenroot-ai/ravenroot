package ai.ravenroot.api.persistence;

import java.util.Objects;

/**
 * The output half of a durable execution result: a retention state, the metadata that stays true
 * whether or not bytes were kept, and the bytes themselves when they were.
 *
 * <p>Split out of {@link DurableExecutionResult} because these six values are one decision. A record
 * that carried them flat would let a caller construct a {@link ResultPayloadState#WITHHELD} state
 * beside a stored payload, or {@link ResultPayloadState#RETAINED} beside nothing, and neither
 * combination has a meaning. The canonical constructor here refuses both.</p>
 *
 * <h2>Metadata outlives the bytes on purpose</h2>
 * <p>{@link #bytes()} and {@link #contentType()} describe the projection that <em>was</em> produced,
 * not what survived the decision. A refused payload therefore still reports how large it was and
 * what it would have been, which is exactly what an operator needs in order to decide whether the
 * cap is set correctly — a state that said only "too large" would leave them raising the limit
 * blind.</p>
 *
 * @param state       what became of the payload; see {@link ResultPayloadState}
 * @param redacted    whether recognised credential material was replaced before storage. Meaningful
 *                    only alongside {@link ResultPayloadState#RETAINED}: it qualifies bytes that are
 *                    present, and is {@code false} where there are none
 * @param truncated   whether the stored projection is shorter than what the execution produced.
 *                    Meaningful only alongside {@link ResultPayloadState#RETAINED}, for the reason
 *                    {@code redacted} is
 * @param bytes       size in bytes of the encoded projection, whether or not it was stored; zero
 *                    exactly when the execution produced no payload
 * @param contentType media type of the encoded projection, or {@code null} when the execution
 *                    produced no payload
 * @param retained    the stored bytes, present exactly when {@code state} is
 *                    {@link ResultPayloadState#RETAINED}
 */
public record ExecutionResultPayload(ResultPayloadState state, boolean redacted, boolean truncated,
                                     int bytes, String contentType, OpaquePayload retained) {

    /**
     * Refuses every combination of state and bytes that has no meaning, so no adapter and no reader
     * can be shown one.
     */
    public ExecutionResultPayload {
        Objects.requireNonNull(state, "state");
        if (bytes < 0) {
            throw new IllegalArgumentException("payload bytes cannot be negative");
        }
        boolean present = state == ResultPayloadState.RETAINED;
        if (present == (retained == null)) {
            throw new IllegalArgumentException(
                    "payload state " + state + " is incompatible with " + (retained == null
                            ? "absent stored bytes" : "stored bytes"));
        }
        if (state == ResultPayloadState.NONE && (bytes != 0 || contentType != null)) {
            throw new IllegalArgumentException(
                    "a payload that was never produced cannot carry a size or a content type");
        }
        if (!present && (redacted || truncated)) {
            throw new IllegalArgumentException(
                    "redaction and truncation describe stored bytes, and " + state + " has none");
        }
        if (retained != null && retained.size() != bytes) {
            throw new IllegalArgumentException("stored payload is " + retained.size()
                    + " bytes but the record declares " + bytes);
        }
    }

    /**
     * The state of an execution that produced nothing.
     *
     * @return a payload record reporting {@link ResultPayloadState#NONE}.
     */
    public static ExecutionResultPayload none() {
        return new ExecutionResultPayload(ResultPayloadState.NONE, false, false, 0, null, null);
    }

    /**
     * The state of an execution whose projected payload was stored.
     *
     * @param stored    the encoded projection, whose size becomes {@link #bytes()} and whose media
     *                  type becomes {@link #contentType()}.
     * @param redacted  whether recognised credential material was replaced before encoding.
     * @param truncated whether the projection is shorter than what the execution produced.
     * @return a payload record reporting {@link ResultPayloadState#RETAINED}.
     */
    public static ExecutionResultPayload retained(OpaquePayload stored, boolean redacted,
                                                  boolean truncated) {
        Objects.requireNonNull(stored, "stored");
        return new ExecutionResultPayload(ResultPayloadState.RETAINED, redacted, truncated,
                stored.size(), stored.contentType(), stored);
    }

    /**
     * The state of a payload refused for size, keeping the metadata that says by how much.
     *
     * @param bytes       size of the projection that was refused.
     * @param contentType media type the projection would have carried.
     * @return a payload record reporting {@link ResultPayloadState#WITHHELD}.
     */
    public static ExecutionResultPayload withheld(int bytes, String contentType) {
        return new ExecutionResultPayload(ResultPayloadState.WITHHELD, false, false, bytes,
                Objects.requireNonNull(contentType, "contentType"), null);
    }

    /**
     * The state of a payload that does not project onto the closed payload model.
     *
     * @return a payload record reporting {@link ResultPayloadState#UNCONVERTIBLE}.
     */
    public static ExecutionResultPayload unconvertible() {
        return new ExecutionResultPayload(ResultPayloadState.UNCONVERTIBLE, false, false, 0, null, null);
    }

    /**
     * The same metadata with the bytes dropped and the state moved to
     * {@link ResultPayloadState#EXPIRED}.
     *
     * <p>Applied by an adapter on the way out, never stored. A payload that was already absent is
     * returned unchanged rather than relabelled: an execution that produced nothing has not expired,
     * and saying it did would invent a payload in order to report its loss.</p>
     *
     * @return this record when it holds no bytes, and otherwise a copy reporting
     *         {@link ResultPayloadState#EXPIRED}.
     */
    public ExecutionResultPayload expired() {
        if (state != ResultPayloadState.RETAINED) {
            return this;
        }
        return new ExecutionResultPayload(ResultPayloadState.EXPIRED, false, false, bytes, contentType,
                null);
    }

    /**
     * Whether a reader is being handed the bytes the execution produced.
     *
     * @return whether {@link #state()} is {@link ResultPayloadState#RETAINED}.
     */
    public boolean available() {
        return state == ResultPayloadState.RETAINED;
    }
}
