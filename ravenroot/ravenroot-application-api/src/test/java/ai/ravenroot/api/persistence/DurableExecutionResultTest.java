package ai.ravenroot.api.persistence;

import ai.ravenroot.api.application.ExecutionTerminationReason;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.RuntimeActivityData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The payload boundary and the identity comparison, which are the two things this record decides. */
class DurableExecutionResultTest {

    private static final ExecutionKey KEY = new ExecutionKey("tenant-a", UUID.randomUUID());
    private static final UUID TRAVERSAL = UUID.randomUUID();
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final int CAP = 64 * 1024;

    private static DurableExecutionResult completed(Object payload, ExecutionResultNodes nodes) {
        return DurableExecutionResult.of(KEY, TRAVERSAL, new GraphVersionPin("graph-v1"),
                ProcessInstanceStatus.COMPLETED, null, START, START.plusSeconds(1), payload, nodes,
                null, CAP);
    }

    @Test
    void aValueTheClosedPayloadModelCannotRepresentIsReportedAsUnconvertibleRatherThanStored() {
        // Pins the exact marker RuntimeActivityData substitutes for an unrepresentable value. It is a
        // private detail of that class, and a change to it would silently turn every unconvertible
        // payload into a stored string that reads like a result.
        ExecutionResultPayload projected = DurableExecutionResult.project(new Object(), CAP);
        assertEquals(ResultPayloadState.UNCONVERTIBLE, projected.state());
        assertNull(projected.retained());
        assertEquals(0, projected.bytes());
    }

    @Test
    void aStringThatHappensToBeTheMarkerIsData_notARefusal() {
        ExecutionResultPayload projected =
                DurableExecutionResult.project("[ravenroot:truncated:unsupported-type]", CAP);
        assertEquals(ResultPayloadState.RETAINED, projected.state(),
                "the marker is only a refusal when the projection substituted it, not when the "
                        + "execution produced it");
    }

    @Test
    void anExecutionThatProducedNothingIsNotAnExecutionWhoseOutputWasRefused() {
        assertEquals(ResultPayloadState.NONE, DurableExecutionResult.project(null, CAP).state());
        assertEquals(ResultPayloadState.WITHHELD,
                DurableExecutionResult.project(Map.of("answer", 42L), 1).state());
        assertTrue(DurableExecutionResult.project(Map.of("answer", 42L), 1).bytes() > 1,
                "the size that was refused survives the refusal");
    }

    @Test
    void credentialMaterialIsReplacedAndTheReplacementIsDeclaredRatherThanSilent() {
        ExecutionResultPayload projected =
                DurableExecutionResult.project(Map.of("apiKey", "sk-live-0123456789"), CAP);
        assertEquals(ResultPayloadState.RETAINED, projected.state());
        assertTrue(projected.redacted());
        assertFalse(new String(projected.retained().bytes(), java.nio.charset.StandardCharsets.UTF_8)
                .contains("sk-live-0123456789"));
    }

    @Test
    void theFingerprintDoesNotDependOnTheIterationOrderOfTheSetsItWasBuiltFrom() {
        var forward = new LinkedHashSet<>(List.of("alpha", "beta", "gamma"));
        var reversed = new LinkedHashSet<>(List.of("gamma", "beta", "alpha"));
        DurableExecutionResult first = completed(Map.of("answer", 42L),
                ExecutionResultNodes.of(forward, List.of(), List.of(), List.of(), List.of()));
        DurableExecutionResult second = completed(Map.of("answer", 42L),
                ExecutionResultNodes.of(reversed, List.of(), List.of(), List.of(), List.of()));
        assertEquals(first.fingerprint(), second.fingerprint(),
                "two writes of the same result must compare equal, or a duplicate terminal event "
                        + "would be refused as a conflicting one");
    }

    @Test
    void theFingerprintIgnoresTheDeadlineTheStoreAssignsAndNothingElse() {
        DurableExecutionResult built = completed(Map.of("answer", 42L), ExecutionResultNodes.empty());
        assertEquals(built.fingerprint(),
                built.withRetainedUntil(START.plusSeconds(3600)).fingerprint(),
                "a retry arriving later must not be refused for carrying a later deadline");
        assertNotEquals(built.fingerprint(),
                completed(Map.of("answer", 43L), ExecutionResultNodes.empty()).fingerprint());
        assertNotEquals(built.fingerprint(),
                DurableExecutionResult.of(KEY, TRAVERSAL, new GraphVersionPin("graph-v1"),
                        ProcessInstanceStatus.FAILED, ExecutionTerminationReason.CANCELLED, START,
                        START.plusSeconds(1), null, ExecutionResultNodes.empty(), null, CAP)
                        .fingerprint());
    }

    @Test
    void anOversizedNodeSetIsBoundedWithItsOverflowWrittenDownRatherThanDropped() {
        List<String> many = IntStream.range(0, ExecutionResultNodes.MAX_ENTRIES + 10)
                .mapToObj(index -> String.format("node-%05d", index)).toList();
        ExecutionResultNodes bounded =
                ExecutionResultNodes.of(many, List.of(), List.of(), List.of(), List.of());
        assertEquals(ExecutionResultNodes.MAX_ENTRIES, bounded.visitedNodes().size());
        assertEquals(RuntimeActivityData.TRUNCATION_MARKER, bounded.visitedNodes().getLast());
        assertTrue(bounded.truncated());

        // Idempotent: reading a stored record and writing it back must not change what is stored, or
        // the fingerprint would move on every round trip.
        ExecutionResultNodes again = new ExecutionResultNodes(bounded.visitedNodes(),
                bounded.defaultedNodes(), bounded.bypassedNodes(), bounded.handledFailureNodes(),
                bounded.untakenEdges());
        assertEquals(bounded, again);
    }

    @Test
    void nodeSetsAreOrderedAndDeduplicatedRegardlessOfWhatWasOffered() {
        ExecutionResultNodes nodes = ExecutionResultNodes.of(List.of("b", "a", "b"), List.of(),
                List.of(), List.of(), List.of());
        assertEquals(List.of("a", "b"), nodes.visitedNodes());
        assertFalse(nodes.truncated());
        assertEquals(new TreeSet<>(List.of("a", "b")).stream().toList(), nodes.visitedNodes());
    }

    @Test
    void aFailureClassifierThatCouldCarryRuntimeTextIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new DurableExecutionResult(KEY, TRAVERSAL,
                new GraphVersionPin("graph-v1"), ProcessInstanceStatus.FAILED, null, START,
                START.plusSeconds(1), null, ExecutionResultPayload.none(),
                "connection refused to 10.0.0.4:5432", ExecutionResultNodes.empty()));

        DurableExecutionResult fromThrowable = DurableExecutionResult.of(KEY, TRAVERSAL,
                new GraphVersionPin("graph-v1"), ProcessInstanceStatus.FAILED, null, START,
                START.plusSeconds(1), null, ExecutionResultNodes.empty(),
                new IllegalStateException("connection refused to 10.0.0.4:5432"), CAP);
        assertEquals("java.lang.IllegalStateException", fromThrowable.failureClassifier(),
                "only the type is kept; the message is assembled from the values that caused the "
                        + "failure and routinely carries them");
    }

    @Test
    void aNonTerminalStatusIsNotADurableResult() {
        assertThrows(IllegalArgumentException.class, () -> new DurableExecutionResult(KEY, TRAVERSAL,
                new GraphVersionPin("graph-v1"), ProcessInstanceStatus.RUNNING, null, START,
                START.plusSeconds(1), null, ExecutionResultPayload.none(), null,
                ExecutionResultNodes.empty()));
    }

    @Test
    void aPayloadStateCannotDisagreeWithWhetherBytesArePresent() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionResultPayload(
                ResultPayloadState.RETAINED, false, false, 0, "application/json", null));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionResultPayload(
                ResultPayloadState.NONE, false, false, 7, "application/json",
                OpaquePayload.of(new byte[7], "application/json")));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionResultPayload(
                ResultPayloadState.WITHHELD, true, false, 7, "application/json", null));
    }

    @Test
    void anExpiredReadDropsTheBytesAndKeepsTheMetadataThatSaysWhatWasLost() {
        DurableExecutionResult stored =
                completed(Map.of("answer", 42L), ExecutionResultNodes.empty())
                        .withRetainedUntil(START.plusSeconds(60));
        DurableExecutionResult aged = stored.expired();
        assertEquals(ResultPayloadState.EXPIRED, aged.payload().state());
        assertNull(aged.payload().retained());
        assertEquals(stored.payload().bytes(), aged.payload().bytes());
        assertEquals(stored.status(), aged.status());

        DurableExecutionResult nothing = completed(null, ExecutionResultNodes.empty());
        assertEquals(nothing, nothing.expired(),
                "an execution that produced nothing has not expired; saying it did would invent a "
                        + "payload in order to report its loss");
    }
}
