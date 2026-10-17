package ai.ravenroot.core.runtime;

import ai.ravenroot.api.payload.PayloadValue;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphExecutionContinuationCheckpointCompatibilityTest {
    private static final int MAGIC = 0x52524232;

    @Test
    void versionsTwoAndThreeDecodeAsOrdinaryContinuationsWithoutCalledOutputs() throws Exception {
        for (int version : List.of(2, 3)) {
            GraphExecutionContinuationCheckpoint.Decoded decoded =
                    GraphExecutionContinuationCheckpoint.read(version, legacy(version - 1));
            assertEquals(7, decoded.innerVersion());
            assertArrayEquals(new byte[] { 1, 2, 3 }, decoded.inner());
            assertFalse(decoded.calledExecution());
            assertTrue(decoded.calledEndOutputs().isEmpty());
            assertTrue(decoded.joins().isEmpty());
        }
    }

    @Test
    void versionOneKeepsItsExplicitSafeRefusal() {
        GraphExecutionContinuationCheckpointException failure = assertThrows(
                GraphExecutionContinuationCheckpointException.class,
                () -> GraphExecutionContinuationCheckpoint.read(1, new byte[0]));
        assertEquals(GraphExecutionContinuationCheckpointException.Reason.LEGACY_BUDGET_UNAVAILABLE,
                failure.reason());
    }

    @Test
    void versionFourRoundTripsCalledModeAndPriorEndOutputsWhileOrdinaryModeStaysEmpty() {
        var budget = new GraphExecutionBudgetSnapshot(11, 12, 13, 1, 2);
        byte[] called = GraphExecutionContinuationCheckpoint.write(9, new byte[] { 4 }, budget,
                List.of(), true, List.of(PayloadValue.of("earlier-end")));
        var restored = GraphExecutionContinuationCheckpoint.read(
                GraphExecutionContinuationCheckpoint.VERSION, called);
        assertTrue(restored.calledExecution());
        assertEquals(List.of("earlier-end"),
                restored.calledEndOutputs().stream().map(PayloadValue::toJava).toList());

        var ordinary = GraphExecutionContinuationCheckpoint.read(
                GraphExecutionContinuationCheckpoint.VERSION,
                GraphExecutionContinuationCheckpoint.write(9, new byte[] { 4 }, budget));
        assertFalse(ordinary.calledExecution());
        assertTrue(ordinary.calledEndOutputs().isEmpty());
    }

    private static byte[] legacy(int format) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(format);
            output.writeInt(7);
            output.writeLong(10);
            output.writeLong(20);
            output.writeLong(30);
            output.writeInt(1);
            output.writeInt(2);
            output.writeInt(3);
            output.write(new byte[] { 1, 2, 3 });
            if (format >= 2) output.writeInt(0);
        }
        return bytes.toByteArray();
    }
}
