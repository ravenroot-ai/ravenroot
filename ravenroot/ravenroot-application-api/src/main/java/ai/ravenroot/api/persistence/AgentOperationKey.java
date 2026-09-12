package ai.ravenroot.api.persistence;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Stable payload-free idempotency key for an agent resource operation. */
public final class AgentOperationKey {
    private AgentOperationKey() { }

    /**
     * Derives a stable payload-free operation identity.
     *
     * @param root owning process identity
     * @param grantId charged grant
     * @param nodeId graph node identity
     * @param invocationId logical invocation identity
     * @param attemptId exact execution attempt identity
     * @param kind operation kind
     * @param ordinal caller-owned monotonic ordinal
     * @param discriminator optional stable operation discriminator
     * @return bounded SHA-256 operation key
     */
    public static String of(ExecutionKey root, UUID grantId, String nodeId, UUID invocationId, UUID attemptId,
                            Kind kind, long ordinal, UUID discriminator) {
        if (root == null || grantId == null || invocationId == null || attemptId == null || kind == null) {
            throw new IllegalArgumentException("operation-key scope is required");
        }
        if (nodeId == null || nodeId.isBlank() || nodeId.length() > 256 || ordinal < 0) {
            throw new IllegalArgumentException("operation-key input is invalid");
        }
        try {
            var out = new ByteArrayOutputStream(256);
            put(out, "ravenroot-agent-operation-v2");
            put(out, root.tenantId());
            put(out, root.processInstanceId().toString());
            put(out, grantId.toString());
            put(out, nodeId);
            put(out, invocationId.toString());
            put(out, attemptId.toString());
            put(out, kind.name());
            out.writeBytes(ByteBuffer.allocate(Long.BYTES).putLong(ordinal).array());
            put(out, discriminator == null ? "" : discriminator.toString());
            return "agent-op:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(out.toByteArray()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static void put(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        out.writeBytes(bytes);
    }

    /** Supported payload-free operation categories. */
    public enum Kind {
        /** One model-provider turn. */
        MODEL_TURN,
        /** One model-proposed tool effect. */
        TOOL_CALL,
        /** One delegated child grant. */
        CHILD_GRANT
    }
}
