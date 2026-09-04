package ai.ravenroot.core.approval;

import ai.ravenroot.api.persistence.ToolApprovalRegistration;
import ai.ravenroot.core.runtime.GraphExecutionBudgetSnapshot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

/** Core-owned envelope around a package checkpoint and its trusted graph-budget snapshot. */
final class GraphExecutionContinuationCheckpoint {
    static final int VERSION = 2;
    private static final int MAGIC = 0x52524232; // RRB2
    private static final int FORMAT = 1;

    private GraphExecutionContinuationCheckpoint() { }

    static byte[] write(int innerVersion, byte[] inner, GraphExecutionBudgetSnapshot budget) {
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
            }
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > ToolApprovalRegistration.MAX_CONTINUATION_BYTES) {
                throw new ToolApprovalContinuationCheckpointException(
                        ToolApprovalContinuationCheckpointException.Reason.MALFORMED);
            }
            return encoded;
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory checkpoint encoding failed", impossible);
        }
    }

    static Decoded read(int version, byte[] encoded) {
        if (version == 1) {
            throw new ToolApprovalContinuationCheckpointException(
                    ToolApprovalContinuationCheckpointException.Reason.LEGACY_BUDGET_UNAVAILABLE);
        }
        if (version != VERSION) {
            throw new ToolApprovalContinuationCheckpointException(
                    ToolApprovalContinuationCheckpointException.Reason.UNKNOWN_VERSION);
        }
        if (encoded == null || encoded.length > ToolApprovalRegistration.MAX_CONTINUATION_BYTES) {
            throw malformed();
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC || input.readInt() != FORMAT) throw malformed();
            int innerVersion = input.readInt();
            if (innerVersion < 1) throw malformed();
            var budget = new GraphExecutionBudgetSnapshot(input.readLong(), input.readLong(), input.readLong(),
                    input.readInt(), input.readInt());
            if (budget.inFlightHops() != 1) {
                throw new ToolApprovalContinuationCheckpointException(
                        ToolApprovalContinuationCheckpointException.Reason.UNSAFE_REENTRY_STATE);
            }
            int length = input.readInt();
            if (length < 0 || length > ToolApprovalRegistration.MAX_CONTINUATION_BYTES
                    || length != input.available()) throw malformed();
            byte[] inner = input.readNBytes(length);
            if (inner.length != length || input.read() != -1) throw malformed();
            return new Decoded(innerVersion, inner, budget);
        } catch (EOFException truncated) {
            throw malformed();
        } catch (IOException | IllegalArgumentException invalid) {
            if (invalid instanceof ToolApprovalContinuationCheckpointException typed) throw typed;
            throw malformed();
        }
    }

    private static ToolApprovalContinuationCheckpointException malformed() {
        return new ToolApprovalContinuationCheckpointException(
                ToolApprovalContinuationCheckpointException.Reason.MALFORMED);
    }

    record Decoded(int innerVersion, byte[] inner, GraphExecutionBudgetSnapshot budget) {
        Decoded {
            inner = inner.clone();
        }
        @Override public byte[] inner() { return inner.clone(); }
    }
}
