package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.*;
import ai.ravenroot.api.deployment.*;
import ai.ravenroot.api.execution.*;
import ai.ravenroot.api.node.*;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class DisconnectedSourceCommandTest {
    private static String graph(String command) { return """
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="command" for="edge" attr.name="command" attr.type="string"/>
              <key id="joins" for="graph" attr.name="join.semantics" attr.type="string"/>
              <graph id="disconnected" edgedefault="directed"><data key="joins">declared</data>
                <node id="start"><data key="kind">start</data></node>
                <node id="input"><data key="kind">behavior</data><data key="behavior">input</data></node>
                <node id="worker"><data key="kind">behavior</data><data key="behavior">worker</data></node>
                <node id="end"><data key="kind">end</data></node><node id="error"><data key="kind">error</data></node>
                <edge source="start" target="end"/>
                <edge source="input" target="worker"><data key="command">COMMAND</data></edge>
                <edge source="worker" target="end"><data key="command">process</data></edge>
              </graph>
            </graphml>
            """.replace("COMMAND", command); }

    @Test void disconnectedSourcePathIsComposedAndNamedCommandIsDelivered() throws Exception {
        var sources = new ConcurrentHashMap<String, InboundSourceContext>();
        var effects = new CopyOnWriteArrayList<NodeMessage>(); var creates = new AtomicInteger();
        try (var engine = new SameThreadExecutionEngine()) {
            var deployment = deployment(engine, sources, effects, creates, "handle");
            deployment.start(TestIdentities.TENANT_A).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(1, creates.get()); assertTrue(effects.isEmpty());
            assertEquals(IngressDisposition.ACCEPTED, sources.get("input").ingress().offer(
                    TestIdentities.TENANT_A, IngressTarget.node("input"), "source-event"));
            assertEquals(1, effects.size());
            assertEquals("handle", effects.getFirst().command().name());
            assertEquals("source-event", effects.getFirst().payload());
            deployment.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void disconnectedSourceCannotHideAnUndeclaredCommandUntilItsFirstEvent() throws Exception {
        var sources = new ConcurrentHashMap<String, InboundSourceContext>();
        var effects = new CopyOnWriteArrayList<NodeMessage>(); var creates = new AtomicInteger();
        try (var engine = new SameThreadExecutionEngine()) {
            var deployment = deployment(engine, sources, effects, creates, "untrusted-command");
            assertThrows(ExecutionException.class,
                    () -> deployment.start(TestIdentities.TENANT_A).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertTrue(sources.isEmpty()); assertTrue(effects.isEmpty()); assertEquals(0, creates.get());
        }
    }
    private static DefaultGraphDeployment deployment(SameThreadExecutionEngine engine,
            Map<String, InboundSourceContext> sources, List<NodeMessage> effects, AtomicInteger creates, String command) {
        class Source implements NodeBehavior, InboundSourceCapable {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor("input", "Input", "Test", "Source", "actor", false,
                        List.of(), Set.of(), NodeRuntimeNature.SOURCE, Set.of(NodeRuntimeNature.SOURCE));
            }
            @Override public NodeAction create(NodeConfiguration configuration) { throw new AssertionError("source handler"); }
            @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
                return new InboundSource() {
                    @Override public CompletionStage<Void> start(InboundSourceContext active) {
                        sources.put(active.nodeId(), active); return CompletableFuture.completedFuture(null);
                    }
                    @Override public CompletionStage<Void> stop() { return CompletableFuture.completedFuture(null); }
                };
            }
        }
        var worker = new NodeBehavior() {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor("worker", "Worker", "Test", "Worker", "actor", false,
                        List.of(), Set.of(), null, Set.of(), Set.of("handle"));
            }
            @Override public NodeAction create(NodeConfiguration configuration) {
                creates.incrementAndGet(); return message -> {
                    effects.add(message); return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                };
            }
        };
        var nodePackage = new NodePackage() {
            @Override public String id() { return "test.disconnected-source"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<NodeBehavior> behaviors() { return List.of(new Source(), worker); }
        };
        return new DefaultGraphDeployment(DeploymentId.of("disconnected"), engine,
                NodePackages.register(new BehaviorRegistry(), nodePackage), new ExecutionMonitor(),
                ExecutionIdentitySource.randomUuids(), graph(command).getBytes(StandardCharsets.UTF_8),
                DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY);
    }
}
