package ai.ravenroot.core.pause;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.persistence.ExecutionPauseRegistration;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The bounded state a held traversal needs in order to be continued by a process that did not hold
 * it: the payload and attributes of the one dispatch the hold withheld.
 *
 * <h2>Why only this, and why the rest is deliberately absent</h2>
 * <p>Everything else the withheld dispatch carried is either already durable or is not durable
 * anywhere in this system. The node, the command, the graph pin and the invocation the hold sits
 * behind are columns on {@link ExecutionPauseRegistration}; the traversal's history is the aggregate.
 * What is <em>not</em> here is join state, iteration context and sibling branches — none of which is
 * persisted by anything today, {@link ai.ravenroot.api.persistence.JoinRecord} having settled that a
 * branch payload comes back with its redelivery rather than out of a store. A hold taken where any
 * of those would be needed is therefore not a durably safe boundary at all, and
 * {@code GraphRunner} refuses to commit one rather than persisting a continuation that would be
 * wrong on resume.</p>
 *
 * <h2>Encoding</h2>
 * <p>Canonical JSON through {@link PayloadJson}, which orders map keys deterministically — so the
 * digest over these bytes is a function of the value and not of the iteration order of whatever
 * produced it. {@link PayloadValue#fromJava(Object, PayloadLimits)} is also the admission test: a
 * payload it will not represent has no encoding here, and {@link #of} answers empty rather than
 * inventing one.</p>
 */
public record ExecutionPauseContinuation(PayloadValue payload, PayloadValue attributes) {

    /**
     * Schema version of the encoded form.
     *
     * <p>Stored beside the bytes so a build that does not recognise a version refuses to resume
     * rather than decoding a shape it is guessing at. A held traversal that cannot be decoded stays
     * held, which is the failure this whole issue is about not getting wrong.</p>
     */
    public static final int VERSION = 1;

    /** Budgets applied to both halves of the continuation, and to the encoded whole. */
    public static final PayloadLimits LIMITS = PayloadLimits.DEFAULTS;

    private static final String PAYLOAD = "payload";
    private static final String ATTRIBUTES = "attributes";

    /** Rejects a half-built continuation. */
    public ExecutionPauseContinuation {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(attributes, "attributes");
    }

    /**
     * Converts one withheld dispatch, or answers empty when it is not durably expressible.
     *
     * <p>Empty is the ordinary answer for a payload the type model does not cover — an arbitrary
     * Java object a behaviour returned — and it is an answer rather than a failure because the
     * caller's response is to keep the hold process-local, not to fail the traversal.</p>
     *
     * @param payload the withheld dispatch's payload, which may be {@code null}.
     * @param attributes the withheld dispatch's attributes.
     * @return the continuation, or empty when either half cannot be represented within the budgets.
     */
    public static Optional<ExecutionPauseContinuation> of(Object payload, Map<String, Object> attributes) {
        try {
            PayloadValue encodedPayload = PayloadValue.fromJava(payload, LIMITS);
            PayloadValue encodedAttributes = PayloadValue.fromJava(
                    attributes == null ? Map.of() : attributes, LIMITS);
            return Optional.of(new ExecutionPauseContinuation(encodedPayload, encodedAttributes));
        } catch (RuntimeException notRepresentable) {
            return Optional.empty();
        }
    }

    /**
     * Encodes this continuation canonically, or answers empty when the encoding exceeds its budget.
     *
     * @return canonical UTF-8 bytes, or empty when the encoded form is larger than
     *         {@link ExecutionPauseRegistration#MAX_CONTINUATION_BYTES}.
     */
    public Optional<byte[]> encode() {
        var wrapper = new LinkedHashMap<String, PayloadValue>();
        wrapper.put(ATTRIBUTES, attributes);
        wrapper.put(PAYLOAD, payload);
        byte[] encoded;
        try {
            encoded = PayloadJson.write(new PayloadValue.MapValue(wrapper))
                    .getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException notEncodable) {
            return Optional.empty();
        }
        return encoded.length > ExecutionPauseRegistration.MAX_CONTINUATION_BYTES
                ? Optional.empty() : Optional.of(encoded);
    }

    /**
     * Decodes a stored continuation, rejecting a version this build does not know.
     *
     * @param version the stored schema version.
     * @param encoded the stored canonical bytes.
     * @return the decoded continuation.
     * @throws IllegalArgumentException when the version is unrecognised or the bytes do not decode.
     */
    public static ExecutionPauseContinuation decode(int version, byte[] encoded) {
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported execution pause continuation version "
                    + version + "; this build understands " + VERSION);
        }
        PayloadValue decoded = PayloadJson.read(Objects.requireNonNull(encoded, "encoded"), LIMITS);
        if (!(decoded instanceof PayloadValue.MapValue wrapper)) {
            throw new IllegalArgumentException("execution pause continuation must decode to a map");
        }
        PayloadValue payload = wrapper.entries().get(PAYLOAD);
        PayloadValue attributes = wrapper.entries().get(ATTRIBUTES);
        if (payload == null || attributes == null) {
            throw new IllegalArgumentException("execution pause continuation is missing a component");
        }
        return new ExecutionPauseContinuation(payload, attributes);
    }

    /**
     * Projects the withheld payload back onto the runtime's own value space.
     *
     * @return the payload as the runtime hands it to a node, {@code null} for an absent payload.
     */
    public Object payloadValue() {
        return payload instanceof PayloadValue.NullValue ? null : payload.toJava();
    }

    /**
     * Projects the withheld attributes back onto the runtime's own value space.
     *
     * @return the attributes as the runtime hands them to a node, never {@code null}.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> attributeValues() {
        Object projected = attributes.toJava();
        return projected instanceof Map<?, ?> map ? Map.copyOf((Map<String, Object>) map) : Map.of();
    }
}
