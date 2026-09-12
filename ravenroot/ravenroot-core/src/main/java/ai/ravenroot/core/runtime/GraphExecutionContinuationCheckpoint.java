package ai.ravenroot.core.runtime;

import ai.ravenroot.api.persistence.ToolApprovalRegistration;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Core-owned envelope shared by durable graph continuations and their trusted budget snapshot. */
public final class GraphExecutionContinuationCheckpoint {
    public static final int VERSION = 3;
    private static final int MAGIC = 0x52524232; // RRB2
    private static final int FORMAT = 2;
    private static final PayloadLimits CHECKPOINT_PAYLOAD_LIMITS = new PayloadLimits(
            PayloadLimits.HARD_MAX_ENCODED_BYTES, PayloadLimits.HARD_MAX_DEPTH,
            PayloadLimits.HARD_MAX_COLLECTION_SIZE, PayloadLimits.HARD_MAX_VALUE_COUNT,
            PayloadLimits.HARD_MAX_TEXT_LENGTH, PayloadLimits.HARD_MAX_KEY_LENGTH);

    private GraphExecutionContinuationCheckpoint() { }

    /** Encodes a package checkpoint and the exact graph budget active at suspension. */
    public static byte[] write(int innerVersion, byte[] inner, GraphExecutionBudgetSnapshot budget) {
        return write(innerVersion, inner, budget, List.of());
    }

    /** Encodes a continuation plus the arrived join branches whose payload must survive suspension. */
    static byte[] write(int innerVersion, byte[] inner, GraphExecutionBudgetSnapshot budget,
                        List<JoinState> joins) {
        if (innerVersion < 1) throw new IllegalArgumentException("inner continuation version must be positive");
        java.util.Objects.requireNonNull(inner, "inner");
        java.util.Objects.requireNonNull(budget, "budget");
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(FORMAT);
                output.writeInt(innerVersion);
                output.writeLong(budget.traversalSteps());
                output.writeLong(budget.amplifiedDeliveries());
                output.writeLong(budget.payloadBytes());
                output.writeInt(budget.inFlightHops());
                output.writeInt(budget.liveActors());
                output.writeInt(inner.length);
                output.write(inner);
                output.writeInt(joins.size());
                for (JoinState join : joins) {
                    output.writeUTF(join.joinNodeId());
                    output.writeUTF(join.branchId());
                    writePayload(output, join.payload());
                    writePayload(output, join.attributes());
                    output.writeInt(join.parentInvocationIds().size());
                    for (UUID parent : join.parentInvocationIds()) {
                        output.writeLong(parent.getMostSignificantBits());
                        output.writeLong(parent.getLeastSignificantBits());
                    }
                    output.writeUTF(join.command());
                    output.writeInt(join.iteration().size());
                    for (var entry : new java.util.TreeMap<>(join.iteration()).entrySet()) {
                        output.writeUTF(entry.getKey());
                        output.writeInt(entry.getValue());
                    }
                }
            }
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > ToolApprovalRegistration.MAX_CONTINUATION_BYTES) throw malformed();
            return encoded;
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory checkpoint encoding failed", impossible);
        }
    }

    /** Strictly decodes the shared envelope; legacy checkpoints cannot safely reconstruct budgets. */
    public static Decoded read(int version, byte[] encoded) {
        if (version == 1) {
            throw new GraphExecutionContinuationCheckpointException(
                    GraphExecutionContinuationCheckpointException.Reason.LEGACY_BUDGET_UNAVAILABLE);
        }
        if (version != 2 && version != VERSION) {
            throw new GraphExecutionContinuationCheckpointException(
                    GraphExecutionContinuationCheckpointException.Reason.UNKNOWN_VERSION);
        }
        if (encoded == null || encoded.length > ToolApprovalRegistration.MAX_CONTINUATION_BYTES) {
            throw malformed();
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) throw malformed();
            int format = input.readInt();
            if (format != 1 && format != FORMAT) throw malformed();
            if ((version == 2) != (format == 1)) throw malformed();
            int innerVersion = input.readInt();
            if (innerVersion < 1) throw malformed();
            var budget = new GraphExecutionBudgetSnapshot(input.readLong(), input.readLong(), input.readLong(),
                    input.readInt(), input.readInt());
            if (format == 1 && budget.inFlightHops() != 1) {
                throw new GraphExecutionContinuationCheckpointException(
                        GraphExecutionContinuationCheckpointException.Reason.UNSAFE_REENTRY_STATE);
            }
            int length = input.readInt();
            if (length < 0 || length > ToolApprovalRegistration.MAX_CONTINUATION_BYTES
                    || (format == 1 && length != input.available())) throw malformed();
            byte[] inner = input.readNBytes(length);
            if (inner.length != length) throw malformed();
            var joins = new ArrayList<JoinState>();
            if (format == FORMAT) {
                int count = input.readInt();
                if (count < 0 || count > 10_000) throw malformed();
                var identities = new java.util.HashSet<String>();
                for (int index = 0; index < count; index++) {
                    String joinNodeId = input.readUTF();
                    String branchId = input.readUTF();
                    if (!identities.add(joinNodeId + "\u0000" + branchId)) throw malformed();
                    PayloadValue payload = readPayload(input);
                    PayloadValue attributes = readPayload(input);
                    int parentCount = input.readInt();
                    if (parentCount < 0 || parentCount > 10_000) throw malformed();
                    var parents = new java.util.LinkedHashSet<UUID>();
                    for (int parent = 0; parent < parentCount; parent++) {
                        parents.add(new UUID(input.readLong(), input.readLong()));
                    }
                    String command = input.readUTF();
                    int lapCount = input.readInt();
                    if (lapCount < 0 || lapCount > 10_000) throw malformed();
                    var laps = new java.util.LinkedHashMap<String, Integer>();
                    for (int lap = 0; lap < lapCount; lap++) {
                        String join = input.readUTF();
                        int value = input.readInt();
                        if (value < 0 || laps.put(join, value) != null) throw malformed();
                    }
                    joins.add(new JoinState(joinNodeId, branchId, payload, attributes,
                            parents, command, laps));
                }
                if (budget.inFlightHops() != joins.size() + 1) {
                    throw new GraphExecutionContinuationCheckpointException(
                            GraphExecutionContinuationCheckpointException.Reason.UNSAFE_REENTRY_STATE);
                }
            }
            if (input.read() != -1) throw malformed();
            return new Decoded(innerVersion, inner,
                    new GraphExecutionBudgetSnapshot(budget.traversalSteps(), budget.amplifiedDeliveries(),
                            budget.payloadBytes(), 1, budget.liveActors()), joins);
        } catch (EOFException truncated) {
            throw malformed();
        } catch (IOException | IllegalArgumentException invalid) {
            if (invalid instanceof GraphExecutionContinuationCheckpointException typed) throw typed;
            throw malformed();
        }
    }

    private static void writePayload(DataOutputStream output, PayloadValue value) throws IOException {
        byte[] encoded = PayloadJson.write(value).getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static PayloadValue readPayload(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > ToolApprovalRegistration.MAX_CONTINUATION_BYTES) throw malformed();
        byte[] encoded = input.readNBytes(length);
        if (encoded.length != length) throw malformed();
        return PayloadJson.read(encoded, CHECKPOINT_PAYLOAD_LIMITS);
    }

    private static GraphExecutionContinuationCheckpointException malformed() {
        return new GraphExecutionContinuationCheckpointException(
                GraphExecutionContinuationCheckpointException.Reason.MALFORMED);
    }

    /** Decoded immutable package checkpoint and graph budget. */
    public record Decoded(int innerVersion, byte[] inner, GraphExecutionBudgetSnapshot budget,
                          List<JoinState> joins) {
        public Decoded {
            inner = inner.clone();
            joins = List.copyOf(joins == null ? List.of() : joins);
        }

        public Decoded(int innerVersion, byte[] inner, GraphExecutionBudgetSnapshot budget) {
            this(innerVersion, inner, budget, List.of());
        }

        @Override public byte[] inner() { return inner.clone(); }
    }

    /** Bounded trusted representation of one join arrival retained across a Human Task boundary. */
    public record JoinState(String joinNodeId, String branchId, PayloadValue payload,
                            PayloadValue attributes, Set<UUID> parentInvocationIds,
                            String command, Map<String, Integer> iteration) {
        public JoinState {
            if (joinNodeId == null || joinNodeId.isBlank() || branchId == null || branchId.isBlank()) {
                throw new IllegalArgumentException("join and branch identifiers cannot be blank");
            }
            java.util.Objects.requireNonNull(payload, "payload");
            java.util.Objects.requireNonNull(attributes, "attributes");
            parentInvocationIds = Set.copyOf(parentInvocationIds == null ? Set.of() : parentInvocationIds);
            if (command == null || command.isBlank()) throw new IllegalArgumentException("command cannot be blank");
            iteration = Map.copyOf(iteration == null ? Map.of() : iteration);
        }
    }
}
