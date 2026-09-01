package ai.ravenroot.server.payload;

import ai.ravenroot.api.payload.PayloadEnvelope;
import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;

import java.nio.charset.StandardCharsets;

/**
 * The HTTP representation of a graph submission carrying a structured payload (API-01).
 *
 * <h2>Why a negotiated body rather than a wider query string</h2>
 * <p>Before API-01 the payload was {@code ?payload=<text>}: a query parameter, and therefore capped by
 * the request-shape guard at 4 KiB and confined to whatever survives URL encoding. A structured
 * payload does not fit there, and widening the query budget to make it fit would relax a limit that
 * exists to bound pre-authentication work for every route at once.</p>
 *
 * <p>The body is the only part of the request with room, and the body already carries the GraphML
 * document. So the structured form is a <em>negotiated representation</em> of the same request: a
 * caller that sends {@value #MEDIA_TYPE} is sending one JSON object containing both the document and
 * the payload, and a caller that sends anything else is sending exactly what it sent before. Nothing
 * about the legacy request changes — not its content type, not its query parameter, not its response —
 * because the legacy request never reaches this class.</p>
 *
 * <h2>Budgets</h2>
 * <p>The envelope is read under its own {@link PayloadLimits}: {@link #envelopeLimits} allows one text
 * member as large as the GraphML budget, and allows the whole document to be as large as the GraphML
 * budget plus the payload budget plus framing. The payload member is then re-checked against the
 * strict payload budgets, so relaxing the text budget for the document does not relax it for the
 * payload. Both are enforced by counting during the parse.</p>
 */
public record StructuredSubmission(byte[] graphMl, PayloadEnvelope payload) {

    /** The media type that selects this representation. */
    public static final String MEDIA_TYPE = "application/vnd.ravenroot.execution.v1+json";

    /** The submission representation version this build implements. */
    public static final String CONTRACT = "ravenroot.execution/1";

    /** Framing slack: member names, punctuation and escaping of the embedded document. */
    private static final int FRAMING_SLACK = 64 * 1024;

    /** True when {@code contentType} selects the structured representation. */
    public static boolean selects(String contentType) {
        if (contentType == null) {
            return false;
        }
        int parameters = contentType.indexOf(';');
        String base = parameters < 0 ? contentType : contentType.substring(0, parameters);
        return MEDIA_TYPE.equalsIgnoreCase(base.trim());
    }

    /** The budget for the whole submission document, derived from the two it contains. */
    public static PayloadLimits envelopeLimits(PayloadLimits payloadLimits, int maxGraphBytes) {
        return new PayloadLimits(
                maxGraphBytes + payloadLimits.maxEncodedBytes() + FRAMING_SLACK,
                payloadLimits.maxDepth() + 2,
                payloadLimits.maxCollectionSize(),
                payloadLimits.maxValueCount() + 8,
                maxGraphBytes,
                payloadLimits.maxKeyLength());
    }

    /**
     * Reads a submission document.
     *
     * <p>A missing {@code payload} member is not an error: it is the structured spelling of "no
     * payload", and maps onto the same empty text the legacy path produces when {@code ?payload=} is
     * absent. That equivalence is deliberate, so a caller migrating one request at a time never has to
     * reason about two different notions of "nothing".</p>
     *
     * @throws ai.ravenroot.api.payload.PayloadException when the document is malformed, exceeds a
     *                                                   budget, or declares an unknown contract
     */
    public static StructuredSubmission read(byte[] body, PayloadLimits payloadLimits, int maxGraphBytes) {
        PayloadValue document = PayloadJson.read(body, envelopeLimits(payloadLimits, maxGraphBytes));
        if (!(document instanceof PayloadValue.MapValue members)) {
            throw PayloadException.malformed();
        }
        PayloadValue contract = members.entries().get("contract");
        if (contract != null
                && !(contract instanceof PayloadValue.TextValue text && CONTRACT.equals(text.value()))) {
            throw PayloadException.unsupportedContract(CONTRACT);
        }
        if (!(members.entries().get("graphml") instanceof PayloadValue.TextValue graphText)) {
            throw PayloadException.malformed();
        }
        PayloadValue payload = members.entries().get("payload");
        PayloadEnvelope envelope = payload == null
                ? PayloadEnvelope.legacyText("")
                : PayloadEnvelope.fromValue(payload, payloadLimits);
        byte[] graphBytes = graphText.value().getBytes(StandardCharsets.UTF_8);
        if (graphBytes.length > maxGraphBytes) {
            throw PayloadException.tooLarge(graphBytes.length, maxGraphBytes);
        }
        return new StructuredSubmission(graphBytes, envelope);
    }

    public StructuredSubmission {
        graphMl = graphMl.clone();
    }

    @Override
    public byte[] graphMl() {
        return graphMl.clone();
    }
}
