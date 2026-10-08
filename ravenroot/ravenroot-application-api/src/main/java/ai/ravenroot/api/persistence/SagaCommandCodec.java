package ai.ravenroot.api.persistence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Deterministic bounded binary encoding used by execution-store saga outboxes. */
public final class SagaCommandCodec {
    private static final int VERSION = 1;
    private static final int MAX_COMMAND_METADATA_BYTES = 16 * 1024;
    static final int MAX_ENCODED_BYTES = MAX_COMMAND_METADATA_BYTES + SagaCommandIntent.MAX_PAYLOAD_BYTES;

    private SagaCommandCodec() { }

    /**
     * Encodes an immutable application command.
     * @param intent command to encode
     * @return encoded bytes
     */
    public static byte[] encode(SagaCommandIntent intent) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            uuid(out, intent.messageId()); uuid(out, intent.sagaId());
            text(out, intent.operationId()); text(out, intent.destination()); text(out, intent.commandType());
            out.writeInt(intent.schemaVersion()); text(out, intent.payload().contentType());
            byte[] payload = intent.payload().bytes(); out.writeInt(payload.length); out.write(payload);
            text(out, intent.payloadFingerprint());
            out.writeBoolean(intent.causalMessageId() != null);
            if (intent.causalMessageId() != null) uuid(out, intent.causalMessageId());
            instant(out, intent.notBefore()); out.writeInt(intent.maxAttempts()); out.flush();
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_ENCODED_BYTES) {
                throw new IllegalArgumentException("encoded saga command exceeds 272 KiB");
            }
            return encoded;
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    /**
     * Decodes and validates an application command.
     * @param encoded trusted-store bytes
     * @return command
     */
    public static SagaCommandIntent decode(byte[] encoded) {
        if (encoded == null || encoded.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("invalid saga command encoding");
        }
        try {
            var in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != VERSION) throw new IllegalArgumentException("unsupported saga command encoding");
            UUID message = uuid(in), saga = uuid(in); String operation = text(in), destination = text(in), type = text(in);
            int schema = in.readInt(); String contentType = text(in); int size = in.readInt();
            if (size < 0 || size > SagaCommandIntent.MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("invalid saga command payload length");
            }
            OpaquePayload payload = OpaquePayload.of(in.readNBytes(size), contentType); String fingerprint = text(in);
            UUID causal = in.readBoolean() ? uuid(in) : null; Instant notBefore = instant(in); int attempts = in.readInt();
            if (in.available() != 0) throw new IllegalArgumentException("trailing saga command bytes");
            return new SagaCommandIntent(message, saga, operation, destination, type, schema, payload,
                    fingerprint, causal, notBefore, attempts);
        } catch (IOException malformed) { throw new IllegalArgumentException("malformed saga command encoding", malformed); }
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
    private static String text(DataInputStream in) throws IOException {
        int size = in.readInt(); if (size < 0 || size > MAX_ENCODED_BYTES) throw new IOException("invalid text length");
        return new String(in.readNBytes(size), StandardCharsets.UTF_8);
    }
    private static void uuid(DataOutputStream out, UUID value) throws IOException { out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void instant(DataOutputStream out, Instant value) throws IOException { out.writeLong(value.getEpochSecond()); out.writeInt(value.getNano()); }
    private static Instant instant(DataInputStream in) throws IOException { return Instant.ofEpochSecond(in.readLong(), in.readInt()); }
}
