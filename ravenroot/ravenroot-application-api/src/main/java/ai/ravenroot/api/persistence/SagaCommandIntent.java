package ai.ravenroot.api.persistence;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Application command committed atomically with the saga state that created it.
 * @param messageId stable message identity
 * @param sagaId owning saga identity
 * @param operationId stable business operation identity
 * @param destination governed destination name
 * @param commandType versioned application command type
 * @param schemaVersion positive command schema version
 * @param payload bounded opaque command body
 * @param payloadFingerprint SHA-256 of payload bytes
 * @param causalMessageId prior message that caused this command, or null
 * @param notBefore earliest delivery instant
 * @param maxAttempts bounded delivery attempt ceiling
 */
public record SagaCommandIntent(UUID messageId, UUID sagaId, String operationId, String destination,
                                String commandType, int schemaVersion, OpaquePayload payload,
                                String payloadFingerprint, UUID causalMessageId, Instant notBefore,
                                int maxAttempts) {
    /** Validates and defensively snapshots the durable value. */
    public SagaCommandIntent {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(sagaId, "sagaId");
        operationId = token(operationId, "operationId", 256);
        destination = token(destination, "destination", 256);
        commandType = token(commandType, "commandType", 128);
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
        Objects.requireNonNull(payload, "payload");
        if (payload.size() > 256 * 1024) throw new IllegalArgumentException("saga command exceeds 256 KiB");
        if (payloadFingerprint == null || !payloadFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("payloadFingerprint must be SHA-256");
        }
        String computedPayload = sha256(payload.bytes());
        if (!computedPayload.equals(payloadFingerprint)) {
            throw new IllegalArgumentException("payloadFingerprint does not match the command payload");
        }
        Objects.requireNonNull(notBefore, "notBefore");
        if (maxAttempts < 1 || maxAttempts > 100) throw new IllegalArgumentException("maxAttempts out of range");
    }

    /**
     * Digest of the complete immutable command identity checked on an idempotent store replay.
     *
     * @return SHA-256 over destination, command contract and bounded payload
     */
    public String identityFingerprint() {
        var digest = digest();
        update(digest, destination);
        update(digest, commandType);
        update(digest, Integer.toString(schemaVersion));
        update(digest, payload.contentType());
        digest.update(payload.bytes());
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String token(String value, String name, int limit) {
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return value;
    }

    private static String sha256(byte[] value) {
        return HexFormat.of().formatHex(digest().digest(value));
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
