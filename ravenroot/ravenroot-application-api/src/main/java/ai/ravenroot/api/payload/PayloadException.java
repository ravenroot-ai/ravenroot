package ai.ravenroot.api.payload;

import java.util.Map;

/**
 * A classified payload rejection that discloses nothing derived from the payload.
 *
 * <p>The split is the one {@code GraphMlRejectionDetail} already declares for GraphML, and it is
 * repeated here rather than invented differently:</p>
 *
 * <ul>
 *   <li>{@link #getMessage()} is <b>public</b>. It is assembled by {@link PayloadRejection} from text
 *       authored in that file and from server-side limit numbers only, so it is safe to return to
 *       whoever submitted the payload.</li>
 *   <li>{@link #diagnosticDetail()} is <b>not public</b>. It carries what was actually wrong — an
 *       offset, an observed size, a token class — and must only reach a server-side sink.</li>
 *   <li>{@link #incidentId()} appears in both and is the only thing that joins them.</li>
 * </ul>
 *
 * <p>It extends {@link IllegalArgumentException} so that adapters which already map an invalid
 * argument to a 400 keep working unchanged if they never learn about this type; adapters that do
 * learn about it get the stable {@link #code()} instead.</p>
 */
public final class PayloadException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /**
     * The closed classification vocabulary. A caller may switch on these; new constants are additive
     * and an unknown one must be treated as a generic rejection.
     */
    public enum Reason {
        /** The encoded payload exceeds the configured byte budget. */
        TOO_LARGE(413),
        /** Nesting exceeds the configured depth budget. */
        DEPTH_LIMIT_EXCEEDED(413),
        /** A single list or map exceeds the configured element budget. */
        COLLECTION_LIMIT_EXCEEDED(413),
        /** The whole tree exceeds the configured value-count budget. */
        VALUE_COUNT_LIMIT_EXCEEDED(413),
        /** A text value exceeds the configured length budget. */
        TEXT_TOO_LONG(413),
        /** A map key exceeds the configured length budget. */
        KEY_TOO_LONG(413),
        /** The same map key appears twice, so the intended value is ambiguous. */
        DUPLICATE_KEY(400),
        /** The encoded form is not well-formed. */
        MALFORMED(400),
        /** The value is outside the payload type model. */
        UNSUPPORTED_TYPE(400),
        /** The declared kind does not describe the supplied value. */
        KIND_MISMATCH(400),
        /** The declared contract version is not one this build implements. */
        UNSUPPORTED_CONTRACT_VERSION(415),
        /** The payload presents reserved security naming. */
        RESERVED_KEY(400);

        private final int recommendedStatus;

        Reason(int recommendedStatus) {
            this.recommendedStatus = recommendedStatus;
        }

/**
 * The stable machine-readable code, identical on every surface.
 * @return stable API code prefixed with {@code PAYLOAD_}
 */
        public String code() {
            return "PAYLOAD_" + name();
        }

/**
 * The HTTP status an adapter should use. Present so two adapters cannot disagree.
 * @return HTTP status adapters should use when exposing this reason
 */
        public int recommendedStatus() {
            return recommendedStatus;
        }
    }

    /**
     * Rejections a transport needs to raise for itself.
     *
     * <p>A transport that frames a payload inside a larger document — an HTTP submission body, a
     * message envelope — discovers its own malformed and oversized cases before the payload module
     * ever sees them. These factories exist so that it raises them through the same classification
     * vocabulary, the same incident-id scheme and the same sanitisation policy, instead of growing a
     * second one. They take no message: the wording still comes from the policy.</p>
 * @return the public malformed-payload rejection with no payload-derived detail
     */
    public static PayloadException malformed() {
        return PayloadRejection.malformed(0, null);
    }

/**
 * Creates a rejection for a representation version this build cannot safely decode.
 * @param observedContract untrusted representation version found at the transport boundary
 * @return the classified unsupported-contract rejection
 */
    public static PayloadException unsupportedContract(String observedContract) {
        return PayloadRejection.unsupportedContractVersion(observedContract);
    }

/**
 * Creates a rejection for an encoded payload exceeding its byte budget.
 * @param observedBytes encoded byte count measured by the transport
 * @param limitBytes maximum encoded byte count accepted by policy
 * @return the classified size-limit rejection
 */
    public static PayloadException tooLarge(int observedBytes, int limitBytes) {
        return PayloadRejection.tooLarge(observedBytes, limitBytes);
    }

/**
 * Classification selected by the payload boundary.
 */
    private final Reason reason;
/**
 * Opaque correlation handle that joins public and server-side diagnostics.
 */
    private final String incidentId;
/**
 * Server-side detail retained for observability and never safe for a submitter response.
 */
    private final Map<String, String> diagnosticDetail;

    PayloadException(Reason reason, String message, String incidentId, Map<String, String> diagnosticDetail) {
        super(message);
        this.reason = reason;
        this.incidentId = incidentId;
        this.diagnosticDetail = diagnosticDetail;
    }

/**
 * Returns the closed rejection classification.
 * @return reason used to derive the public code and recommended HTTP status
 */
    public Reason reason() {
        return reason;
    }

/**
 * The stable machine-readable code. Equal to {@code reason().code()}.
 * @return the stable machine-readable rejection code
 */
    public String code() {
        return reason.code();
    }

/**
 * Correlation handle. Safe to return to the caller; it reveals nothing about the payload.
 * @return correlation handle safe to disclose to the submitter
 */
    public String incidentId() {
        return incidentId;
    }

    /**
     * The payload-derived explanation of the rejection.
     *
     * <p>Never place this in a response, a redirect, an error page or any other artefact the
     * submitter can observe.</p>
 * @return immutable server-only diagnostics describing the rejected payload
     */
    public Map<String, String> diagnosticDetail() {
        return diagnosticDetail;
    }
}
