package ai.ravenroot.server;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramAdmission;
import ai.ravenroot.api.programming.ProgramRequest;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.GraphRunner;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.programming.graalvm.GraalVmProgramRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
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
    private static final SecurityContext IDENTITY = new SecurityContext(
            "bigint-measurement", "tenant-a", "alice", PrincipalType.USER, "urn:ravenroot:test");

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void contrastsInProcessArithmeticWithColdProgramAndProvesWarmWorkerReuseIsUnavailable() throws Exception {
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
        long coldProgramStarted = System.nanoTime();
        assertEquals("1", runtime.execute(new FixedAdmission(artifact), request).toCompletableFuture().get());
        long coldProgramNanos = System.nanoTime() - coldProgramStarted;
        assertEquals(1, workerStarts.get(), "the cold governed invocation starts one isolated worker");

        long reuseProbeStarted = System.nanoTime();
        assertEquals("1", runtime.execute(new FixedAdmission(artifact), request).toCompletableFuture().get());
        long reuseProbeNanos = System.nanoTime() - reuseProbeStarted;
        assertEquals(2, workerStarts.get(), "the same runtime and artifact still start a fresh isolated worker");

        System.out.printf(java.util.Locale.ROOT,
                "bigint-op measurement: %,d in-process operations, 0 worker starts, %.0f ns/op; "
                        + "governed program coldNanos=%d coldWorkerStarts=1; "
                        + "same-runtime reuseProbeNanos=%d reuseProbeWorkerStarts=1 warmReuseAvailable=false%n",
                BIGINT_ITERATIONS, (double) bigintNanos / BIGINT_ITERATIONS,
                coldProgramNanos, reuseProbeNanos);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void reportsRegisterMachineNodeTraversalSerializationAndObservationMatrix() throws Exception {
        int[] increments = {10, 100, 1_000, 10_000};
        int[] digits = {10, 100, 1_000, 4_096};
        var increment = new GraphNode("increment", NodeKind.BEHAVIOR, "bigint-op", Map.of(
                "operation", "add", "left", "field:counter", "right", "literal:1", "target", "counter"));

        long coldStarted = System.nanoTime();
        Object cold = BehaviorRegistry.standard().create(increment).orElseThrow()
                .handle(new ai.ravenroot.api.execution.NodeMessage(IDENTITY, UUID.randomUUID(), UUID.randomUUID(),
                        increment.id(), Map.of("counter", "0"), Map.of()))
                .toCompletableFuture().get().payload();
        long coldNanos = System.nanoTime() - coldStarted;
        assertEquals("1", ((Map<?, ?>) cold).get("counter"));

        var handler = BehaviorRegistry.standard().create(increment).orElseThrow();
        for (int count : increments) {
            Object payload = Map.of("counter", "0");
            long started = System.nanoTime();
            for (int index = 0; index < count; index++) {
                payload = handler.handle(new ai.ravenroot.api.execution.NodeMessage(
                                IDENTITY, UUID.randomUUID(), UUID.randomUUID(), increment.id(), payload, Map.of()))
                        .toCompletableFuture().get().payload();
            }
            long nanos = System.nanoTime() - started;
            assertEquals(Integer.toString(count), ((Map<?, ?>) payload).get("counter"));
            System.out.printf(java.util.Locale.ROOT,
                    "register-machine matrix node increments=%d totalNanos=%d nanosPerIncrement=%.0f workers=0%n",
                    count, nanos, (double) nanos / count);
        }

        for (int size : digits) {
            String operand = "1" + "0".repeat(size - 1);
            var add = new GraphNode("wide-add", NodeKind.BEHAVIOR, "bigint-op", Map.of(
                    "operation", "add", "left", "field:value", "right", "literal:1", "target", "value"));
            var wideHandler = BehaviorRegistry.standard().create(add).orElseThrow();
            long arithmeticStarted = System.nanoTime();
            Object result = wideHandler.handle(new ai.ravenroot.api.execution.NodeMessage(
                            IDENTITY, UUID.randomUUID(), UUID.randomUUID(), add.id(), Map.of("value", operand), Map.of()))
                    .toCompletableFuture().get().payload();
            long arithmeticNanos = System.nanoTime() - arithmeticStarted;
            long serializationStarted = System.nanoTime();
            String json = PayloadJson.write(PayloadValue.fromJava(result, PayloadLimits.DEFAULTS));
            long serializationNanos = System.nanoTime() - serializationStarted;
            assertEquals(size, ((String) ((Map<?, ?>) result).get("value")).length());
            System.out.printf(java.util.Locale.ROOT,
                    "register-machine matrix digits=%d arithmeticNanos=%d payloadJsonNanos=%d jsonBytes=%d workers=0%n",
                    size, arithmeticNanos, serializationNanos, json.getBytes(StandardCharsets.UTF_8).length);
        }

        var log = new GraphNode("observe", NodeKind.BEHAVIOR, "log", Map.of("message", "counter={{payload.counter}}"));
        long logStarted = System.nanoTime();
        BehaviorRegistry.standard().create(log).orElseThrow().handle(new ai.ravenroot.api.execution.NodeMessage(
                IDENTITY, UUID.randomUUID(), UUID.randomUUID(), log.id(), Map.of("counter", "1"), Map.of()))
                .toCompletableFuture().get();
        long logNanos = System.nanoTime() - logStarted;

        var graph = counterGraph();
        try (var manager = GraphManager.from(graph);
             var engine = new PekkoExecutionEngine("register-machine-measurement-" + UUID.randomUUID());
             var runner = new GraphRunner(manager, engine, BehaviorRegistry.standard(), new ExecutionMonitor())) {
            long traversalColdStarted = System.nanoTime();
            runner.execute(IDENTITY, Map.of("counter", "1")).toCompletableFuture().get();
            long traversalColdNanos = System.nanoTime() - traversalColdStarted;
            for (int count : increments) {
                if (count > 100) {
                    System.out.printf(java.util.Locale.ROOT,
                            "register-machine matrix traversal increments=%d not-run=bounded-sample; "
                                    + "node-only-row-above-covers-this-size workers=0%n", count);
                    continue;
                }
                long started = System.nanoTime();
                var result = runner.execute(IDENTITY, Map.of("counter", Integer.toString(count)))
                        .toCompletableFuture().get();
                long nanos = System.nanoTime() - started;
                assertEquals("0", ((Map<?, ?>) result.payload()).get("counter"));
                System.out.printf(java.util.Locale.ROOT,
                        "register-machine matrix traversal increments=%d totalNanos=%d nanosPerIncrement=%.0f workers=0%n",
                        count, nanos, (double) nanos / count);
            }
            System.out.printf(java.util.Locale.ROOT,
                    "register-machine matrix coldNodeNanos=%d coldTraversalNanos=%d oneLogNanos=%d workers=0%n",
                    coldNanos, traversalColdNanos, logNanos);
        }
    }

    private static GraphDefinition counterGraph() {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("zero", NodeKind.BEHAVIOR, "bigint-op", Map.of(
                        "operation", "equal", "left", "field:counter", "right", "literal:0", "target", "isZero")),
                new GraphNode("branch", NodeKind.BEHAVIOR, "cel-decision", Map.of(
                        "expression", "payload.isZero", "trueOutcome", "done", "falseOutcome", "again")),
                new GraphNode("decrement", NodeKind.BEHAVIOR, "bigint-op", Map.of(
                        "operation", "subtract", "left", "field:counter", "right", "literal:1", "target", "counter")),
                GraphNode.end("end")), List.of(
                GraphEdge.to("start", "zero"), GraphEdge.to("zero", "branch"),
                new GraphEdge("branch", "end", "done"), new GraphEdge("branch", "decrement", "again"),
                GraphEdge.to("decrement", "zero")), Map.of("join.semantics", "declared"));
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
