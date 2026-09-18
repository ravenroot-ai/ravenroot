package ai.ravenroot.server;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramAdmission;
import ai.ravenroot.api.programming.ProgramRequest;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.programming.graalvm.GraalVmProgramRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Focused measurement of the lifecycle avoided by {@code bigint-op}.
 *
 * <p>This deliberately makes no timing threshold or multiplier claim: process startup depends on the
 * host. It reports both normalized durations and the structural count that does not depend on the
 * scheduler: repeated {@code bigint-op} calls start zero worker processes, while every isolated
 * {@code program} invocation below starts one real worker JVM.</p>
 *
 * <p>The server module already depends on both core and the GraalVM adapter and already owns the real
 * worker supervisor fixture used here. Keeping the measurement downstream avoids widening the
 * adapter module's dependency graph or its load-guarded ordinary test reactor.</p>
 *
 * <p>The measurement is deliberately excluded from ordinary test runs because it starts additional
 * real worker JVMs. Run it explicitly with {@code -Dravenroot.bigint.measurement=true}; the complete
 * Maven command is published in the core node reference.</p>
 */
@EnabledIfSystemProperty(named = "ravenroot.bigint.measurement", matches = "true")
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
        var workerStarts = new AtomicInteger();
        Object payload = Map.of("counter", "0");
        long bigintStarted = System.nanoTime();
        for (int index = 0; index < BIGINT_ITERATIONS; index++) {
            payload = handler.handle(new NodeMessage(IDENTITY, UUID.randomUUID(), UUID.randomUUID(),
                            node.id(), payload, Map.of()))
                    .toCompletableFuture().get().payload();
        }
        long bigintNanos = System.nanoTime() - bigintStarted;
        assertEquals(Integer.toString(BIGINT_ITERATIONS), ((Map<?, ?>) payload).get("counter"));
        assertEquals(0, workerStarts.get(), "in-process bigint operations must not start workers");

        String source = "(request) => (BigInt(request.payload.counter) + 1n).toString()";
        GeneratedArtifact artifact = artifact(source);
        var request = new ProgramRequest(UUID.randomUUID(), "program-increment", Map.of("counter", "0"), Map.of());
        var runtime = new GraalVmProgramRuntime(
                new PythonProgramNodeLiveExecutionTest.LiveGraalVmWorkerSupervisor(workerStarts),
                PythonProgramNodeLiveExecutionTest.policy());
        long programStarted = System.nanoTime();
        for (int index = 0; index < PROGRAM_ITERATIONS; index++) {
            assertEquals("1", runtime.execute(new FixedAdmission(artifact), request).toCompletableFuture().get());
        }
        long programNanos = System.nanoTime() - programStarted;
        assertEquals(PROGRAM_ITERATIONS, workerStarts.get(), "each isolated program invocation starts a worker");

        System.out.printf(java.util.Locale.ROOT,
                "bigint-op measurement: %,d in-process operations, 0 worker starts, %.0f ns/op; "
                        + "program measurement: %,d invocations, %,d real worker starts, %.0f ns/invocation%n",
                BIGINT_ITERATIONS, (double) bigintNanos / BIGINT_ITERATIONS,
                PROGRAM_ITERATIONS, workerStarts.get(), (double) programNanos / PROGRAM_ITERATIONS);
    }

    private static GeneratedArtifact artifact(String source) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(source.getBytes(StandardCharsets.UTF_8)));
        Instant now = Instant.now();
        return new GeneratedArtifact("bigint-measurement", "javascript", hash, source,
                ArtifactState.ACTIVE, 1, now, now, Map.of());
    }

    /** Fixed test admission; artifact revocation is covered by the core registry tests. */
    private record FixedAdmission(GeneratedArtifact artifact) implements ProgramAdmission {
        @Override
        public String artifactId() {
            return artifact.id();
        }

        @Override
        public GeneratedArtifact unverifiedSnapshot() {
            return artifact;
        }

        @Override
        public GeneratedArtifact redeem() {
            return artifact;
        }

        @Override
        public void onRevoked(Runnable cancellation) { }

        @Override
        public void close() { }
    }
}
