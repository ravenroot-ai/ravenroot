package ai.ravenroot.api.programming;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;

import java.nio.charset.StandardCharsets;

/** Canonical interpretation of the GraphML {@code testPayload} editor value. */
public final class ProgramTestPayload {
    /** Default literal used when an editor supplies no test payload. */
public static final String DEFAULT_TEXT = "test payload";

    private ProgramTestPayload() { }

    /**
 * Strict JSON becomes structured data; text that is not JSON remains literal text.
* @param editorValue GraphML editor value; {@code null} selects the documented default
* @return structured payload for valid JSON, otherwise the literal text
 */
    public static Object parse(String editorValue) {
        String value = editorValue == null ? DEFAULT_TEXT : editorValue;
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        try {
            return PayloadJson.read(utf8, PayloadLimits.DEFAULTS).toJava();
        } catch (RuntimeException notJson) {
            return value;
        }
    }

    /**
 * Versioned digest of the canonical Ravenroot payload value, separate from source identity.
* @param payload payload to encode, inspect, or forward
* @return lowercase digest of the canonical bounded payload
 */
    public static String sha256(Object payload) {
        PayloadValue bounded = PayloadValue.fromJava(payload, PayloadLimits.DEFAULTS);
        byte[] canonical = PayloadJson.write(bounded).getBytes(StandardCharsets.UTF_8);
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update("ravenroot.program-smoke-payload.v1\0".getBytes(StandardCharsets.US_ASCII));
            digest.update(java.nio.ByteBuffer.allocate(Long.BYTES).putLong(canonical.length).array());
            digest.update(canonical);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
