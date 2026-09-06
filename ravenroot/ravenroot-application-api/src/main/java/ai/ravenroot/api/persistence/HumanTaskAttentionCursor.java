package ai.ravenroot.api.persistence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque, self-contained Human Task attention page cursor.
 *
 * <p>The token contains an immutable {@code (createdAt, taskId)} boundary and a digest of the tenant
 * and exact query context. It deliberately does not name or require the cursor row: continuation
 * therefore remains valid after that task settles or its owning process is purged.</p>
 *
 * @param value unpadded Base64URL cursor token.
 */
public record HumanTaskAttentionCursor(String value) {
    private static final int VERSION = 1;
    private static final int DIGEST_BYTES = 32;
    private static final int ENCODED_BYTES = 1 + DIGEST_BYTES + Long.BYTES + Integer.BYTES
            + Long.BYTES + Long.BYTES;
    private static final int ENCODED_CHARACTERS = 82;

    /** Rejects malformed cursor text before an adapter tries to use it. */
    public HumanTaskAttentionCursor {
        if (value == null || value.length() != ENCODED_CHARACTERS
                || !value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("invalid human-task attention cursor");
        }
    }

    /**
     * Issues a cursor for one already-authorized page boundary.
     *
     * @param tenantId authenticated tenant scope.
     * @param query exact query whose scope the cursor belongs to.
     * @param authorization caller authority to bind into the cursor scope.
     * @param createdAt immutable task creation time.
     * @param taskId immutable task identity.
     * @return opaque scoped cursor.
     */
    public static HumanTaskAttentionCursor issue(String tenantId, HumanTaskAttentionQuery query,
                                                 HumanTaskAttentionAuthorization authorization,
                                                 Instant createdAt, UUID taskId) {
        if (createdAt == null) throw new IllegalArgumentException("createdAt cannot be null");
        if (taskId == null) throw new IllegalArgumentException("taskId cannot be null");
        try {
            var bytes = new ByteArrayOutputStream(ENCODED_BYTES);
            try (var output = new DataOutputStream(bytes)) {
                output.writeByte(VERSION);
                output.write(scopeDigest(tenantId, query, authorization));
                output.writeLong(createdAt.getEpochSecond());
                output.writeInt(createdAt.getNano());
                output.writeLong(taskId.getMostSignificantBits());
                output.writeLong(taskId.getLeastSignificantBits());
            }
            return new HumanTaskAttentionCursor(Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(bytes.toByteArray()));
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory cursor encoding failed", impossible);
        }
    }

    /**
     * Decodes this cursor only when it belongs to the supplied tenant and exact query scope.
     *
     * @param tenantId authenticated tenant scope.
     * @param query exact query being continued.
     * @param authorization current caller authority, which must match the issuing page.
     * @return immutable exclusive ordering boundary.
     */
    public Boundary boundary(String tenantId, HumanTaskAttentionQuery query,
                             HumanTaskAttentionAuthorization authorization) {
        try {
            byte[] encoded = Base64.getUrlDecoder().decode(value);
            if (encoded.length != ENCODED_BYTES) throw invalid();
            try (var input = new DataInputStream(new ByteArrayInputStream(encoded))) {
                if (input.readUnsignedByte() != VERSION) throw invalid();
                byte[] storedDigest = input.readNBytes(DIGEST_BYTES);
                if (!MessageDigest.isEqual(storedDigest,
                        scopeDigest(tenantId, query, authorization))) throw invalid();
                Instant createdAt = Instant.ofEpochSecond(input.readLong(), input.readInt());
                UUID taskId = new UUID(input.readLong(), input.readLong());
                if (input.available() != 0) throw invalid();
                return new Boundary(createdAt, taskId);
            }
        } catch (IllegalArgumentException | IOException invalid) {
            throw invalid();
        }
    }

    /**
     * Immutable ordering position carried inside a validated cursor.
     *
     * @param createdAt task creation time.
     * @param taskId task identity used to break creation-time ties.
     */
    public record Boundary(Instant createdAt, UUID taskId) {
        /** Validates the decoded boundary. */
        public Boundary {
            if (createdAt == null) throw new IllegalArgumentException("createdAt cannot be null");
            if (taskId == null) throw new IllegalArgumentException("taskId cannot be null");
        }
    }

    private static byte[] scopeDigest(String tenantId, HumanTaskAttentionQuery query,
                                      HumanTaskAttentionAuthorization authorization) {
        HandlerRegistration.requireBoundedKey(tenantId, "tenantId");
        if (query == null) throw new IllegalArgumentException("query cannot be null");
        if (authorization == null) throw new IllegalArgumentException("authorization cannot be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, tenantId);
            update(digest, query.graphVersion());
            update(digest, query.deploymentId().orElse(""));
            update(digest, query.processInstanceId().map(UUID::toString).orElse(""));
            update(digest, query.traversalId().map(UUID::toString).orElse(""));
            update(digest, query.nodeId().orElse(""));
            update(digest, query.taskId().map(UUID::toString).orElse(""));
            update(digest, query.generation().map(String::valueOf).orElse(""));
            update(digest, authorization.actor());
            authorization.roles().stream().sorted().forEach(role -> update(digest, role));
            update(digest, "roles-end");
            authorization.scopes().stream().sorted().forEach(scope -> update(digest, scope));
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("human-task attention cursor does not belong to this context");
    }
}
