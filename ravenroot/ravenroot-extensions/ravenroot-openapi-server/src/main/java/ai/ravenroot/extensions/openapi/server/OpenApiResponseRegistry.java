package ai.ravenroot.extensions.openapi.server;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Process-local proof that a terminal payload passed through the declared {@code respond} command. */
final class OpenApiResponseRegistry {
    private static final PayloadLimits COMMAND_LIMITS = new PayloadLimits(
            16 * 1024 * 1024, 32, 10_000, 100_000, 16 * 1024 * 1024, 256);
    private static final Set<String> PROPOSAL_FIELDS = Set.of(
            "correlationId", "operationId", "status", "headers", "mediaType", "body");
    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    boolean open(UUID correlationId, String tenantId, UUID processInstanceId, UUID traversalId,
                 String nodeId, long generation) {
        return entries.putIfAbsent(correlationId,
                new Entry(tenantId, processInstanceId, traversalId, nodeId, generation)) == null;
    }

    Object complete(String tenantId, UUID processInstanceId, UUID traversalId, String nodeId, Object raw) {
        Map<String, Object> proposal = OpenApiValues.object(raw, "response proposal");
        OpenApiValues.exactKeys(proposal, PROPOSAL_FIELDS, "response proposal");
        if (!proposal.keySet().equals(PROPOSAL_FIELDS)) throw OpenApiValues.invalid();
        UUID correlationId = canonicalUuid(proposal.get("correlationId"));
        Entry entry = entries.get(correlationId);
        if (entry == null || !entry.tenantId.equals(tenantId)
                || !entry.processInstanceId.equals(processInstanceId)
                || !entry.traversalId.equals(traversalId) || !entry.nodeId.equals(nodeId)) {
            throw OpenApiValues.invalid();
        }
        Map<String, Object> command = new LinkedHashMap<>(proposal);
        command.put("version", "openapi.response.v1");
        Object bounded = PayloadValue.fromJava(command, COMMAND_LIMITS).toJava();
        String digest = digest(bounded);
        if (!entry.responseDigest.compareAndSet(null, digest)) throw OpenApiValues.invalid();
        return bounded;
    }

    boolean consume(UUID correlationId, String tenantId, UUID processInstanceId, UUID traversalId,
                    String nodeId, long generation, Object command) {
        Entry entry = entries.get(correlationId);
        if (entry == null || !entry.tenantId.equals(tenantId)
                || !entry.processInstanceId.equals(processInstanceId)
                || !entry.traversalId.equals(traversalId) || !entry.nodeId.equals(nodeId)
                || entry.generation != generation) {
            return false;
        }
        String expected = entry.responseDigest.get();
        return expected != null && expected.equals(digest(command)) && entries.remove(correlationId, entry);
    }

    void close(UUID correlationId) {
        entries.remove(correlationId);
    }

    int size() {
        return entries.size();
    }

    private static UUID canonicalUuid(Object raw) {
        if (!(raw instanceof String text)) throw OpenApiValues.invalid();
        try {
            UUID value = UUID.fromString(text);
            if (!value.toString().equals(text)) throw OpenApiValues.invalid();
            return value;
        } catch (IllegalArgumentException invalid) {
            throw OpenApiValues.invalid();
        }
    }

    private static String digest(Object command) {
        try {
            byte[] canonical = PayloadJson.write(PayloadValue.fromJava(command, COMMAND_LIMITS))
                    .getBytes(StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class Entry {
        private final String tenantId;
        private final UUID processInstanceId;
        private final UUID traversalId;
        private final String nodeId;
        private final long generation;
        private final AtomicReference<String> responseDigest = new AtomicReference<>();

        private Entry(String tenantId, UUID processInstanceId, UUID traversalId, String nodeId, long generation) {
            this.tenantId = tenantId;
            this.processInstanceId = processInstanceId;
            this.traversalId = traversalId;
            this.nodeId = nodeId;
            this.generation = generation;
        }
    }
}
