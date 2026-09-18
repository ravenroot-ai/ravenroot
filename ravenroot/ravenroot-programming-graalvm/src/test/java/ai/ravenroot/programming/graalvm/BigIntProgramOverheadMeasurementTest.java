package ai.ravenroot.programming.graalvm;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramRequest;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Focused measurement of the lifecycle avoided by {@code bigint-op}.
 *
 * <p>This deliberately makes no timing threshold or multiplier claim: process startup depends on the
 * host. It reports both normalized durations and the structural count that does not depend on the
 * scheduler: repeated {@code bigint-op} calls start zero worker processes, while every isolated
 * {@code program} invocation below starts one real worker JVM.</p>
 */
class BigIntProgramOverheadMeasurementTest {
    private static final int BIGINT_ITERATIONS = 10_000;
    private static final int PROGRAM_ITERATIONS = 2;
    private static final SecurityContext IDENTITY = new SecurityContext(
            "bigint-measurement", "tenant-a", "alice", PrincipalType.USER, "urn:ravenroot:test");

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void contrastsRepeatedInProcessArithmeticWithRepeatedProgramWorkerStarts() throws Exception {
        var node = new GraphNode("increment", NodeKind.BEHAVIOR, "bigint-op", Map.of(
                "operation", "add", "left", "field:counter", "right", "literal:1", "target", "counter"));
        var handler = BehaviorRegistry.standard().create(node).orElseThrow();
        Object payload = Map.of("counter", "0");
        long bigintStarted = System.nanoTime();
        for (int index = 0; index < BIGINT_ITERATIONS; index++) {
            payload = handler.handle(new NodeMessage(IDENTITY, UUID.randomUUID(), UUID.randomUUID(),
                            node.id(), payload, Map.of()))
                    .toCompletableFuture().get().payload();
        }
        long bigintNanos = System.nanoTime() - bigintStarted;
        assertEquals(Integer.toString(BIGINT_ITERATIONS), ((Map<?, ?>) payload).get("counter"));

        String source = "(request) => (BigInt(request.payload.counter) + 1n).toString()";
        GeneratedArtifact artifact = artifact(source);
        var request = new ProgramRequest(UUID.randomUUID(), "program-increment", Map.of("counter", "0"), Map.of());
        long programStarted = System.nanoTime();
        for (int index = 0; index < PROGRAM_ITERATIONS; index++) {
            byte[] response = RealWorkerRun.capture(ProgramWireProtocol.Mode.EXECUTE, artifact, request);
            assertEquals("1", ProgramWireProtocol.readResponse(new java.io.ByteArrayInputStream(response)));
        }
        long programNanos = System.nanoTime() - programStarted;

        System.out.printf(java.util.Locale.ROOT,
                "bigint-op measurement: %,d in-process operations, 0 worker starts, %.0f ns/op; "
                        + "program measurement: %,d invocations, %,d real worker starts, %.0f ns/invocation%n",
                BIGINT_ITERATIONS, (double) bigintNanos / BIGINT_ITERATIONS,
                PROGRAM_ITERATIONS, PROGRAM_ITERATIONS, (double) programNanos / PROGRAM_ITERATIONS);
    }

    private static GeneratedArtifact artifact(String source) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(source.getBytes(StandardCharsets.UTF_8)));
        Instant now = Instant.now();
        return new GeneratedArtifact("bigint-measurement", "javascript", hash, source,
                ArtifactState.ACTIVE, 1, now, now, Map.of());
    }
}
