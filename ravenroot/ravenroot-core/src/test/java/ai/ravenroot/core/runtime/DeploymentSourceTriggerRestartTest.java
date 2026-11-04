package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.node.*;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.security.*;
import ai.ravenroot.core.programming.*;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** A real JVM dies after durable custody and inbox commit but before dispatch/acknowledgement. */
@Timeout(90)
class DeploymentSourceTriggerRestartTest {
    @TempDir Path directory;
    private static final String REGISTRATION = "11111111-1111-1111-1111-111111111111";
    private static final SecurityContext ACTOR = new SecurityContext("original-request", "tenant", "alice", PrincipalType.USER, "issuer");
    private static final String GRAPH = """
        <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
        <key id="kind" for="node" attr.name="kind" attr.type="string"/>
        <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
        <key id="zone" for="node" attr.name="zoneId" attr.type="string"/>
        <key id="times" for="node" attr.name="times" attr.type="string"/>
        <key id="mode" for="node" attr.name="mode" attr.type="string"/>
        <graph id="trigger" edgedefault="directed">
        <node id="start"><data key="kind">start</data></node>
        <node id="source"><data key="kind">behavior</data><data key="behavior">timer</data><data key="zone">UTC</data><data key="times">23:59:59</data><data key="mode">RECURRING</data></node>
        <node id="capture"><data key="kind">behavior</data><data key="behavior">test.trigger.capture</data></node>
        <node id="end"><data key="kind">end</data></node><node id="error"><data key="kind">error</data></node>
        <edge source="start" target="source"/><edge source="source" target="capture"/><edge source="capture" target="end"/>
        </graph></graphml>
        """;

    @Test void uncertainCommitSurvivesActualProcessDeathAndReplaysOriginalInputOnlyOnce() throws Exception {
        Path database = directory.resolve("trigger.db"), trace = directory.resolve("deliveries.txt");
        run(database, trace, "commit");
        assertFalse(Files.exists(trace), "uncertain acceptance must not dispatch before the crash");
        run(database, trace, "replay");
        run(database, trace, "replay");
        var deliveries = Files.readAllLines(trace);
        assertEquals(1, deliveries.size(), deliveries.toString());
        assertTrue(deliveries.getFirst().contains("original-input"), deliveries.toString());
        assertTrue(deliveries.getFirst().contains("original-request"), deliveries.toString());
        assertTrue(deliveries.getFirst().contains("alice"), deliveries.toString());
        assertTrue(deliveries.getFirst().contains("ApplicationCorrelationContext"), deliveries.toString());
        assertFalse(deliveries.getFirst().contains("replacement-input"), deliveries.toString());
    }

    @Test void equalClientRequestIdsFromDifferentActorsOwnDifferentDurableEvents() throws Exception {
        Path trace = directory.resolve("actors.txt");
        run(directory.resolve("actors.db"), trace, "actors");
        var deliveries = Files.readAllLines(trace);
        assertEquals(2, deliveries.size(), deliveries.toString());
        assertTrue(deliveries.stream().anyMatch(value -> value.contains("alice")));
        assertTrue(deliveries.stream().anyMatch(value -> value.contains("bob")));
        assertNotEquals(deliveries.get(0).substring(deliveries.get(0).lastIndexOf(' ')),
                deliveries.get(1).substring(deliveries.get(1).lastIndexOf(' ')), "actors cannot share a process identity");
    }

    private void run(Path database, Path trace, String phase) throws Exception {
        Path output = directory.resolve(phase + UUID.randomUUID() + ".log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Worker.class.getName(), database.toString(), trace.toString(), phase)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(25, TimeUnit.SECONDS), "child JVM did not terminate");
            assertEquals(0, process.exitValue(), Files.readString(output));
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); } }
    }

    public static class Worker {
        public static void main(String[] arguments) throws Exception {
            var file = Path.of(arguments[0]); var trace = Path.of(arguments[1]); boolean commit = arguments[2].equals("commit");
            var delivered = new CountDownLatch(arguments[2].equals("actors") ? 2 : 1);
            try (var store = new SqliteExecutionStore(file, Clock.systemUTC()); var engine = new SameThreadExecutionEngine()) {
                ExecutionStore boundary = commit ? (ExecutionStore) Proxy.newProxyInstance(ExecutionStore.class.getClassLoader(),
                        new Class<?>[] { ExecutionStore.class }, (proxy, method, args) -> {
                            try {
                                Object result = method.invoke(store, args);
                                if (method.getName().equals("recordInboxDelivery"))
                                    return ((CompletionStage<?>) result).thenCompose(ignored -> CompletableFuture.failedFuture(new IllegalStateException("lost acknowledgement")));
                                return result;
                            } catch (InvocationTargetException failure) { throw failure.getCause(); }
                        }) : store;
                var capture = new NodeBehavior() {
                    @Override public NodeTypeDescriptor descriptor() {
                        return new NodeTypeDescriptor("test.trigger.capture", "Capture", "Test", "Capture original durable input", "actor", false, List.of(), Set.of());
                    }
                    @Override public NodeAction create(NodeConfiguration configuration) {
                        return message -> {
                            try { Files.writeString(trace, message.payload() + " " + message.security() + " " + message.applicationCorrelation() + " " + message.processInstanceId() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
                            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                            delivered.countDown();
                            return CompletableFuture.completedFuture(ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()));
                        };
                    }
                };
                var registry = NodePackages.register(BehaviorRegistry.standard(), new NodePackage() {
                    public String id() { return "test.trigger"; } public String version() { return "1.0.0"; }
                    public String sdkContract() { return NodeSdk.CONTRACT; } public List<NodeBehavior> behaviors() { return List.of(capture); }
                });
                try (var app = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), registry,
                        new InMemoryArtifactRegistry(), new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(), boundary, 8, UnknownBehaviorPolicy.refuse())) {
                    var registered = app.registerDurableLocalDeployment(ACTOR, "automation", DeploymentId.of(REGISTRATION),
                            new ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8)));
                    app.startLocalDeployment(ACTOR, "automation").toCompletableFuture().get(10, TimeUnit.SECONDS);
                    var view = app.localDeploymentView("tenant", "automation").orElseThrow().source();
                    var retryActor = new SecurityContext(commit ? "original-request" : "retry-http-request", "tenant", "alice", PrincipalType.USER, "issuer");
                    var receipt = app.triggerLocalDeploymentSource(retryActor, "automation", REGISTRATION, view.incarnationId(),
                            view.graphVersion(), "source", "same-operation", new PayloadValue.TextValue(commit ? "original-input" : "replacement-input"));
                    if (arguments[2].equals("actors")) {
                        if (!receipt.acknowledgeable()) throw new AssertionError(receipt);
                        var bob = new SecurityContext("bob-http", "tenant", "bob", PrincipalType.USER, "issuer");
                        var other = app.triggerLocalDeploymentSource(bob, "automation", REGISTRATION, view.incarnationId(),
                                view.graphVersion(), "source", "same-operation", new PayloadValue.TextValue("bob-input"));
                        if (!other.acknowledgeable() || !delivered.await(10, TimeUnit.SECONDS)) throw new AssertionError(other);
                        return;
                    }
                    if (commit) {
                        if (!(receipt instanceof IngressReceipt.Ambiguous)) throw new AssertionError(receipt);
                        Runtime.getRuntime().halt(0);
                    }
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    if (!Files.exists(trace) && !delivered.await(10, TimeUnit.SECONDS)) throw new AssertionError("original custody did not recover");
                    if (!Files.exists(trace)) throw new AssertionError("original custody did not recover");
                    // Recovery may own the same traversal briefly; reconciliation must converge to Duplicate.
                    do {
                        receipt = app.triggerLocalDeploymentSource(retryActor, "automation", REGISTRATION, view.incarnationId(),
                                view.graphVersion(), "source", "same-operation", new PayloadValue.TextValue("replacement-input"));
                    } while (receipt instanceof IngressReceipt.Ambiguous && System.nanoTime() < until);
                    if (!(receipt instanceof IngressReceipt.Duplicate)) throw new AssertionError(receipt);
                }
            }
        }
    }
}
