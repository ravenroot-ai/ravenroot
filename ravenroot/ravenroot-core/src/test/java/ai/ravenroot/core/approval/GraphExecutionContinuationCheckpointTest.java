package ai.ravenroot.core.approval;

import ai.ravenroot.core.runtime.GraphExecutionBudgetSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GraphExecutionContinuationCheckpointTest {
    @Test
    void roundTripPreservesPackageCheckpointAndEveryBudgetCounter() {
        byte[] inner = "package-v3".getBytes(StandardCharsets.UTF_8);
        var budget = new GraphExecutionBudgetSnapshot(17, 8, 4096, 1, 3);

        var decoded = GraphExecutionContinuationCheckpoint.read(
                GraphExecutionContinuationCheckpoint.VERSION,
                GraphExecutionContinuationCheckpoint.write(3, inner, budget));

        assertEquals(3, decoded.innerVersion());
        assertArrayEquals(inner, decoded.inner());
        assertEquals(budget, decoded.budget());
    }

    @Test
    void legacyUnknownMalformedAndUnsafeReentryStatesFailClosedWithTypedReasons() {
        assertReason(ToolApprovalContinuationCheckpointException.Reason.LEGACY_BUDGET_UNAVAILABLE,
                () -> GraphExecutionContinuationCheckpoint.read(1, new byte[] {1}));
        assertReason(ToolApprovalContinuationCheckpointException.Reason.UNKNOWN_VERSION,
                () -> GraphExecutionContinuationCheckpoint.read(99, new byte[] {1}));

        byte[] valid = GraphExecutionContinuationCheckpoint.write(1, new byte[] {7},
                new GraphExecutionBudgetSnapshot(1, 0, 1, 1, 0));
        assertReason(ToolApprovalContinuationCheckpointException.Reason.MALFORMED,
                () -> GraphExecutionContinuationCheckpoint.read(
                        GraphExecutionContinuationCheckpoint.VERSION,
                        java.util.Arrays.copyOf(valid, valid.length - 1)));

        byte[] noReservedHop = GraphExecutionContinuationCheckpoint.write(1, new byte[] {7},
                new GraphExecutionBudgetSnapshot(1, 0, 1, 0, 0));
        assertReason(ToolApprovalContinuationCheckpointException.Reason.UNSAFE_REENTRY_STATE,
                () -> GraphExecutionContinuationCheckpoint.read(
                        GraphExecutionContinuationCheckpoint.VERSION, noReservedHop));
    }

    private static void assertReason(ToolApprovalContinuationCheckpointException.Reason expected,
                                     org.junit.jupiter.api.function.Executable executable) {
        assertEquals(expected,
                assertThrows(ToolApprovalContinuationCheckpointException.class, executable).reason());
    }
}
