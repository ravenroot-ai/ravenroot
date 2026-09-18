package ai.ravenroot.pekko;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.RegisterMachineProfile;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.GraphRunner;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisterMachineExampleLibraryTest {
    private static final SecurityContext IDENTITY = new SecurityContext(
            "register-library", "tenant-a", "alice", PrincipalType.USER, "urn:ravenroot:test");
    private static final Path EXAMPLES = Path.of("../../docs/examples/register-machine");

    @Test
    void finiteLibraryExamplesAreExecutableAndPublishTheirExpectedExactValues() throws Exception {
        var expectations = List.of(
                new Expected("counter-decjz.graphml", "counter", "0"),
                new Expected("addition.graphml", "sum", "111111111011111111100"),
                new Expected("multiplication.graphml", "product", "121932631112635269"),
                new Expected("gcd.graphml", "a", "21"),
                new Expected("beyond-long.graphml", "wide", "922337203685477580812345678901234567891"),
                new Expected("observation.graphml", "counter", "41"),
                new Expected("division.graphml", "quotient", "12"),
                new Expected("division.graphml", "remainder", "3"),
                new Expected("encoded-stack.graphml", "popped", "42"),
                new Expected("encoded-stack.graphml", "stack", "17"));
        try (var files = Files.list(EXAMPLES)) {
            assertEquals(List.of("addition.graphml", "beyond-long.graphml", "counter-decjz.graphml",
                            "division.graphml", "encoded-stack.graphml", "gcd.graphml", "incremental-pi.graphml",
                            "multiplication.graphml", "nonterminating.graphml", "observation.graphml"),
                    files.filter(path -> path.getFileName().toString().endsWith(".graphml"))
                            .map(path -> path.getFileName().toString()).sorted().toList(),
                    "the tested list is the maintained GraphML inventory");
        }
        for (Expected expected : expectations) {
            var monitor = new ExecutionMonitor();
            try (var input = Files.newInputStream(EXAMPLES.resolve(expected.file()));
                 var manager = GraphManager.readGraphMl(input);
                 var engine = new PekkoExecutionEngine("register-library-" + UUID.randomUUID());
                 var runner = new GraphRunner(manager, engine, BehaviorRegistry.standard(), monitor)) {
                assertTrue(RegisterMachineProfile.validate(manager.definition()).conforms(), expected.file());
                var result = runner.execute(IDENTITY, Map.of()).toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertEquals(expected.value(), ((Map<?, ?>) result.payload()).get(expected.field()), expected.file());
                long logs = monitor.eventsAfter(0).stream().filter(event -> event.authorOutput() != null).count();
                assertEquals(expected.file().equals("observation.graphml") ? 1 : 0, logs,
                        "bigint operations do not log implicitly");
            }
        }
    }

    @Test
    void incrementalPiLogsOnlySuccessiveDigitsAndRemainsExternallyCancellable() throws Exception {
        assertConforms("incremental-pi.graphml");
        var monitor = new ExecutionMonitor();
        var digits = new CopyOnWriteArrayList<String>();
        UUID traversalId = UUID.randomUUID();
        try (var engine = new PekkoExecutionEngine("register-pi-" + UUID.randomUUID());
             var application = new DefaultRavenrootApplication(engine, monitor);
             AutoCloseable subscription = monitor.subscribe(event -> {
                 if (traversalId.equals(event.traversalId()) && event.authorOutput() != null) {
                     digits.add(event.authorOutput().value().toJava().toString().replace("pi digit=", ""));
                 }
             });
             InputStream graph = Files.newInputStream(EXAMPLES.resolve("incremental-pi.graphml"))) {
            application.startGraphMl(IDENTITY, traversalId, graph, Map.of());
            assertTrue(await(() -> digits.size() >= 6, Duration.ofSeconds(20)), digits.toString());
            assertEquals(List.of("3", "1", "4", "1", "5", "9"), new ArrayList<>(digits.subList(0, 6)));
            assertTrue(application.cancelTraversal(traversalId));
            assertTrue(awaitQuiescence(digits, Duration.ofSeconds(10)));
        }
    }

    @Test
    void nonterminatingFixtureCancelsBetweenNodesAndStartsNoLaterIteration() throws Exception {
        assertConforms("nonterminating.graphml");
        var monitor = new ExecutionMonitor();
        var increments = new AtomicInteger();
        UUID traversalId = UUID.randomUUID();
        try (var engine = new PekkoExecutionEngine("register-nonterm-" + UUID.randomUUID());
             var application = new DefaultRavenrootApplication(engine, monitor);
             AutoCloseable subscription = monitor.subscribe(event -> {
                 if (traversalId.equals(event.traversalId()) && event.type() == ExecutionEventType.NODE_STARTED
                         && "increment".equals(event.nodeId())) increments.incrementAndGet();
             });
             InputStream graph = Files.newInputStream(EXAMPLES.resolve("nonterminating.graphml"))) {
            application.startGraphMl(IDENTITY, traversalId, graph, Map.of());
            assertTrue(await(() -> increments.get() >= 25, Duration.ofSeconds(20)));
            assertTrue(application.cancelTraversal(traversalId));
            assertTrue(awaitQuiescence(increments, Duration.ofSeconds(10)));
        }
    }

    private static boolean await(java.util.function.BooleanSupplier condition, Duration bound)
            throws InterruptedException {
        long deadline = System.nanoTime() + bound.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static void assertConforms(String file) throws Exception {
        try (var input = Files.newInputStream(EXAMPLES.resolve(file)); var manager = GraphManager.readGraphMl(input)) {
            assertTrue(RegisterMachineProfile.validate(manager.definition()).conforms(), file);
        }
    }

    private static boolean awaitQuiescence(List<?> values, Duration bound) throws InterruptedException {
        return await(() -> { int before = values.size(); try { Thread.sleep(200); } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return false; } return values.size() == before; }, bound);
    }

    private static boolean awaitQuiescence(AtomicInteger value, Duration bound) throws InterruptedException {
        return await(() -> { int before = value.get(); try { Thread.sleep(200); } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return false; } return value.get() == before; }, bound);
    }

    private record Expected(String file, String field, String value) { }
}
