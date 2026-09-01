package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.DeploymentState;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code SOURCE} node has two runtime aspects, and neither is the absence of the other.
 *
 * <p>{@code SOURCE} has an inbound resource driven through {@code InboundSourceCapable}, while
 * {@code GraphRunner}'s spawn loop also creates its dispatch actor because its nature is not
 * {@code WORKER}. The two roles coexist: a source node gets a dispatch actor <em>and</em> an inbound
 * resource, with the ordering that makes the pair safe. This test asserts both roles.</p>
 */
class SourceNodeDualLifecycleTest {

    private static final SecurityContext IDENTITY = new SecurityContext("dual-request", "tenant-dual",
            "subject", PrincipalType.WORKLOAD, "urn:ravenroot:dual");

    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <graph id="g" edgedefault="directed">
                <node id="start"><data key="kind">start</data></node>
                <node id="listener">
                  <data key="kind">behavior</data>
                  <data key="behavior">test.listener</data>
                </node>
                <node id="end"><data key="kind">end</data></node>
                <node id="error"><data key="kind">error</data></node>
                <edge source="start" target="listener"/>
                <edge source="listener" target="end"/>
              </graph>
            </graphml>
            """;

    /**
     * The dispatch actor exists, and it exists because the node is an edge target.
     *
     * <p>Asserted through the engine's own spawn count rather than through {@code GraphRunner}'s
     * bookkeeping, so it is a statement about actors and not about the runner's opinion of them. One
     * actor, for the one node whose nature is not {@code WORKER}: {@code start} and {@code end} are
     * ordinary workers and are created only when a traversal reaches them.
     */
    @Test
    void aSourceNodeGetsADispatchActorAtStartupAndOrdinaryNodesDoNot() throws Exception {
        var engine = new SpawnRecordingEngine();
        var events = new CopyOnWriteArrayList<String>();
        var deployment = deployment(engine, events);
        try {
            var status = deployment.start(IDENTITY).toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(DeploymentState.READY, status.state());

            assertEquals(List.of("listener"), engine.spawnedLogicalNames(),
                    "exactly the SOURCE node gets a dispatch actor at startup. If this reports an "
                            + "empty list, residency was removed from a nature whose reachability "
                            + "depends on the still-open ingress-topology contract; if it "
                            + "reports start/end too, demand-driven worker creation has regressed");
        } finally {
            deployment.stop().toCompletableFuture().get(30, TimeUnit.SECONDS);
            engine.close();
        }
    }

    /**
     * The inbound resource is started after the runner is built and stopped before it is closed.
     *
     * <p>That ordering is the whole reason the two aspects can coexist: the dispatch actor must exist
     * before any inbound event can arrive, and admission must end before the actor that would serve it
     * goes away. Both halves are asserted, because they fail independently.
     */
    @Test
    void theInboundResourceStartsAfterTheDispatchActorAndStopsBeforeIt() throws Exception {
        var engine = new SpawnRecordingEngine();
        var events = new CopyOnWriteArrayList<String>();
        var deployment = deployment(engine, events);
        deployment.start(IDENTITY).toCompletableFuture().get(30, TimeUnit.SECONDS);
        deployment.stop().toCompletableFuture().get(30, TimeUnit.SECONDS);
        engine.close();

        assertEquals(List.of("spawn:listener", "source-start", "source-stop"), events,
                "the dispatch actor must exist before the source can deliver anything into it, and "
                        + "the source must close admission before the actor serving it is stopped");
    }

    /**
     * An ordinary stop is not a process shutdown, and the SPI has always said so.
     *
     * <p>{@code InboundSource#shutdown()} documents the case of a source holding something shared
     * across restarts of its own deployment -- a process-wide connection pool is its own example --
     * and until now <b>nothing in any {@code src/main} ever called it</b>. Both shipped sources
     * override it to delegate to {@code stop()}, so the gap was invisible: every implementation
     * happened to be indifferent to a distinction the runtime never made.
     */
    @Test
    void stoppingADeploymentUsesStopAndClosingTheApplicationUsesShutdown() throws Exception {
        var engine = new SpawnRecordingEngine();
        var stopped = new CopyOnWriteArrayList<String>();
        var deployment = deployment(engine, stopped);
        deployment.start(IDENTITY).toCompletableFuture().get(30, TimeUnit.SECONDS);
        deployment.stop().toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertTrue(stopped.contains("source-stop"), "an operator stop releases through stop(): " + stopped);
        assertTrue(!stopped.contains("source-shutdown"),
                "an operator stopping one deployment is not the process ending: " + stopped);
        engine.close();

        var engine2 = new SpawnRecordingEngine();
        var closing = new CopyOnWriteArrayList<String>();
        var second = deployment(engine2, closing);
        second.start(IDENTITY).toCompletableFuture().get(30, TimeUnit.SECONDS);
        second.shutdown().toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertTrue(closing.contains("source-shutdown"),
                "application shutdown must release through shutdown(), which is the only condition "
                        + "that hook exists to signal: " + closing);
        engine2.close();
    }

    @Test
    void deploymentPassesTheExactPackageBoundServiceViewToAnSdkTwoSource() throws Exception {
        var engine = new SpawnRecordingEngine();
        var events = new CopyOnWriteArrayList<String>();
        NodePackageServices expected = NodePackageServices.unavailable();
        var received = new AtomicReference<NodePackageServices>();
        var deployment = deployment(engine, events, expected, received);
        try {
            deployment.start(IDENTITY).toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertSame(expected, received.get());
        } finally {
            deployment.stop().toCompletableFuture().get(30, TimeUnit.SECONDS);
            engine.close();
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static DefaultGraphDeployment deployment(SpawnRecordingEngine engine, List<String> events) {
        return deployment(engine, events, null, new AtomicReference<>());
    }

    private static DefaultGraphDeployment deployment(SpawnRecordingEngine engine, List<String> events,
                                                     NodePackageServices expected,
                                                     AtomicReference<NodePackageServices> received) {
        engine.events = events;
        NodePackage nodePackage = new NodePackage() {
            @Override
            public String id() {
                return "test.listener.package";
            }

            @Override
            public String version() {
                return "1.0.0";
            }

            @Override
            public String sdkContract() {
                return NodeSdk.CONTRACT;
            }

            @Override
            public List<NodeBehavior> behaviors() {
                return List.of(new ListenerBehavior(events, expected, received));
            }
        };
        var registry = expected == null
                ? NodePackages.register(new BehaviorRegistry(), nodePackage)
                : NodePackages.register(new BehaviorRegistry(), nodePackage,
                        NodePackageServiceRegistry.builder().grant(nodePackage.id(), expected).build());
        return new DefaultGraphDeployment(DeploymentId.of("dual"), engine, registry,
                new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(),
                GRAPH.getBytes(StandardCharsets.UTF_8),
                DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY);
    }

    /** Source-capable, so the catalog derives {@code SOURCE} from the interface it implements. */
    private static final class ListenerBehavior implements NodeBehavior, InboundSourceCapable {
        private final List<String> events;
        private final NodePackageServices expected;
        private final AtomicReference<NodePackageServices> received;

        private ListenerBehavior(List<String> events, NodePackageServices expected,
                                 AtomicReference<NodePackageServices> received) {
            this.events = events;
            this.expected = expected;
            this.received = received;
        }

        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.listener", "Test listener", "Test",
                    "A source-capable behavior used to pin the dual lifecycle.", "actor", false,
                    List.of(), Set.of(), null, Set.of());
        }

        @Override
        public NodeAction create(NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(
                    ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()));
        }

        @Override
        public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
            return new InboundSource() {
                @Override
                public CompletionStage<Void> start(InboundSourceContext started) {
                    events.add("source-start");
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletionStage<Void> stop() {
                    events.add("source-stop");
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletionStage<Void> shutdown() {
                    events.add("source-shutdown");
                    return CompletableFuture.completedFuture(null);
                }
            };
        }

        @Override
        public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context,
                                          NodePackageServices services) {
            if (expected != null) {
                received.set(services);
            }
            return createSource(configuration, context);
        }
    }
}
