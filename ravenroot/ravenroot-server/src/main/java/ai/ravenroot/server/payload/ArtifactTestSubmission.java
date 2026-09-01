package ai.ravenroot.server.payload;

import ai.ravenroot.api.payload.PayloadEnvelope;
import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;

import java.nio.charset.StandardCharsets;

/**
 * Bounded request-body representation for a program-artifact smoke test.
 *
 * <p>{@code application/json} carries one raw Ravenroot payload value; {@code text/plain} carries
 * literal text, even if that text happens to look like JSON. That media-type split is intentional:
 * guessing from a first character would turn a program's test evidence into a different value when
 * an editor changes only its transport. The legacy query parameter is handled by the route, not by
 * this class, so it cannot accidentally bypass the body budget.</p>
 */
public final class ArtifactTestSubmission {
    private ArtifactTestSubmission() { }

    public static boolean supports(String contentType) {
        return contentType == null || baseType(contentType).equals("application/json") || baseType(contentType).equals("text/plain");
    }

    public static PayloadEnvelope read(byte[] body, String contentType, PayloadLimits limits) {
        if (body.length > limits.maxEncodedBytes()) throw PayloadException.tooLarge(body.length, limits.maxEncodedBytes());
        if (baseType(contentType).equals("application/json")) {
            PayloadValue value = PayloadJson.read(body, limits);
            return PayloadEnvelope.of(value);
        }
        String text = new String(body, StandardCharsets.UTF_8);
        PayloadValue value = PayloadValue.of(text);
        limits.enforce(value);
        return PayloadEnvelope.legacyText(text);
    }

    private static String baseType(String contentType) {
        if (contentType == null) return "text/plain";
        int parameters = contentType.indexOf(';');
        return (parameters < 0 ? contentType : contentType.substring(0, parameters)).trim().toLowerCase(java.util.Locale.ROOT);
    }
}
