package ai.ravenroot.api.persistence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Deterministic bounded binary encoding used by execution-store adapters. */
public final class SagaSnapshotCodec {
    private static final int MAGIC = 0x52525347; // RRSG
    private static final int FORMAT = 2;
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private SagaSnapshotCodec() { }

    /**
     * Encodes a validated saga snapshot.
     * @param snapshot snapshot
     * @return bounded deterministic bytes
     */
    public static byte[] encode(SagaSnapshot snapshot) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeInt(FORMAT);
            text(out, snapshot.key().tenantId()); uuid(out, snapshot.key().processInstanceId());
            uuid(out, snapshot.sagaId()); uuid(out, snapshot.traversalId());
            SagaDefinition definition = snapshot.definition();
            out.writeInt(definition.contractVersion()); text(out, definition.scopeId());
            text(out, definition.graphDigest()); text(out, definition.participantDigest());
            out.writeInt(definition.steps().size());
            for (var entry : new java.util.TreeMap<>(definition.steps()).entrySet()) {
                SagaStepDefinition step = entry.getValue();
                text(out, step.stepId()); text(out, step.nodeId()); text(out, step.participantContract());
                nullableText(out, step.compensationNodeId()); out.writeBoolean(step.irreversible());
                out.writeBoolean(step.businessCompletionRequired()); out.writeInt(step.dependencies().size());
                for (String dependency : step.dependencies().stream().sorted().toList()) text(out, dependency);
            }
            out.writeLong(snapshot.revision()); text(out, snapshot.disposition().name());
            out.writeBoolean(snapshot.cancellationRequested()); out.writeBoolean(snapshot.graphCompleted());
            instant(out, snapshot.deadline());
            instant(out, snapshot.createdAt()); instant(out, snapshot.updatedAt()); text(out, snapshot.actionableReason());
            out.writeInt(snapshot.occurrences().size());
            for (var entry : new java.util.TreeMap<UUID, SagaStepSnapshot>(snapshot.occurrences()).entrySet()) {
                SagaStepSnapshot step = entry.getValue();
                uuid(out, step.occurrenceId()); text(out, step.stepId()); uuid(out, step.invocationId());
                text(out, step.forwardOperationId()); text(out, step.compensationOperationId());
                text(out, step.payloadFingerprint()); text(out, step.status().name());
                text(out, step.receipt().contentType()); bytes(out, step.receipt().bytes());
                text(out, step.detail()); instant(out, step.updatedAt());
            }
            out.flush();
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_BYTES) throw new IllegalArgumentException("encoded saga exceeds 4 MiB");
            return encoded;
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Decodes and validates stored saga bytes.
     * @param encoded encoded snapshot
     * @return saga snapshot
     */
    public static SagaSnapshot decode(byte[] encoded) {
        if (encoded == null || encoded.length > MAX_BYTES) throw new IllegalArgumentException("invalid saga encoding size");
        try {
            var input = new ByteArrayInputStream(encoded);
            var in = new DataInputStream(input);
            if (in.readInt() != MAGIC || in.readInt() != FORMAT) throw new IllegalArgumentException("unknown saga encoding");
            ExecutionKey key = new ExecutionKey(text(in), uuid(in)); UUID sagaId = uuid(in);
            UUID traversalId = uuid(in);
            int contract = in.readInt(); String scope = text(in); String graph = text(in); String participants = text(in);
            int definitionSize = count(in, 4096); var definitions = new LinkedHashMap<String, SagaStepDefinition>();
            for (int index = 0; index < definitionSize; index++) {
                String stepId = text(in), nodeId = text(in), participant = text(in), compensation = nullableText(in);
                boolean irreversible = in.readBoolean(), business = in.readBoolean();
                int dependencies = count(in, 4096); var values = new ArrayList<String>(dependencies);
                for (int dependency = 0; dependency < dependencies; dependency++) values.add(text(in));
                definitions.put(stepId, new SagaStepDefinition(stepId, nodeId, participant, compensation,
                        values, irreversible, business));
            }
            SagaDefinition definition = new SagaDefinition(contract, scope, graph, participants, definitions);
            long revision = in.readLong(); SagaDisposition disposition = SagaDisposition.valueOf(text(in));
            boolean cancellation = in.readBoolean(); boolean graphCompleted = in.readBoolean();
            Instant deadline = instant(in); Instant created = instant(in);
            Instant updated = instant(in); String reason = text(in);
            int occurrenceSize = count(in, 4096); var occurrences = new LinkedHashMap<UUID, SagaStepSnapshot>();
            for (int index = 0; index < occurrenceSize; index++) {
                UUID occurrence = uuid(in); String stepId = text(in); UUID invocation = uuid(in);
                String forward = text(in), compensation = text(in), fingerprint = text(in);
                SagaStepStatus status = SagaStepStatus.valueOf(text(in)); String contentType = text(in);
                OpaquePayload receipt = OpaquePayload.of(bytes(in, 64 * 1024), contentType);
                String detail = text(in); Instant stepUpdated = instant(in);
                occurrences.put(occurrence, new SagaStepSnapshot(occurrence, stepId, invocation, forward,
                        compensation, fingerprint, status, receipt, detail, stepUpdated));
            }
            if (input.available() != 0) throw new IllegalArgumentException("trailing saga encoding bytes");
            return new SagaSnapshot(key, sagaId, traversalId, definition, revision, disposition, cancellation,
                    occurrences, deadline, created, updated, reason, graphCompleted);
        } catch (IOException | IllegalArgumentException failure) {
            throw failure instanceof IllegalArgumentException invalid ? invalid
                    : new IllegalArgumentException("malformed saga encoding", failure);
        }
    }

    private static void text(DataOutputStream out, String value) throws IOException { bytes(out, value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static String text(DataInputStream in) throws IOException { return new String(bytes(in, 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8); }
    private static void nullableText(DataOutputStream out, String value) throws IOException { out.writeBoolean(value != null); if (value != null) text(out, value); }
    private static String nullableText(DataInputStream in) throws IOException { return in.readBoolean() ? text(in) : null; }
    private static void bytes(DataOutputStream out, byte[] value) throws IOException { out.writeInt(value.length); out.write(value); }
    private static byte[] bytes(DataInputStream in, int max) throws IOException { int length = in.readInt(); if (length < 0 || length > max) throw new IllegalArgumentException("invalid saga field length"); return in.readNBytes(length); }
    private static int count(DataInputStream in, int max) throws IOException { int value = in.readInt(); if (value < 0 || value > max) throw new IllegalArgumentException("invalid saga collection size"); return value; }
    private static void uuid(DataOutputStream out, UUID value) throws IOException { out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void instant(DataOutputStream out, Instant value) throws IOException { out.writeBoolean(value != null); if (value != null) { out.writeLong(value.getEpochSecond()); out.writeInt(value.getNano()); } }
    private static Instant instant(DataInputStream in) throws IOException { return in.readBoolean() ? Instant.ofEpochSecond(in.readLong(), in.readInt()) : null; }
}
