package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.DeploymentState;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.ingress.IngressResponse;
import ai.ravenroot.api.ingress.IngressRouteAuthority;
import ai.ravenroot.api.ingress.IngressRouteLease;
import ai.ravenroot.api.ingress.IngressRouteOwner;
import ai.ravenroot.api.ingress.ManagedIngress;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.ManagedIngressSource;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Production deployment lifecycle owns managed ingress identity, acquisition and retirement. */
class DefaultGraphDeploymentManagedIngressTest {
    private static final SecurityContext IDENTITY = new SecurityContext("request", "tenant-a", "principal-a",
            PrincipalType.WORKLOAD, "issuer-a");
    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <graph id="g" edgedefault="directed">
                <node id="start"><data key="kind">start</data></node>
                <node id="listener"><data key="kind">behavior</data><data key="behavior">test.managed</data></node>
                <node id="end"><data key="kind">end</data></node>
                <node id="error"><data key="kind">error</data></node>
                <edge source="start" target="listener"/><edge source="listener" target="end"/>
              </graph>
            </graphml>
            """;

    @Test void authorityIsAcquiredAfterSourceReadinessAndRetiredAcrossStopAndRestart() throws Exception {
        var events = new CopyOnWriteArrayList<String>();
        var ingress = new RecordingIngress(events);
        var behavior = new ManagedBehavior(events, false);
        var engine = new SpawnRecordingEngine();
        var deployment = deployment(engine, behavior, ingress);
        try {
            assertEquals(DeploymentState.READY,
                    deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS).state());
            IngressRouteOwner first = ingress.authorities.getFirst();
            assertEquals("test.managed.package", first.packageId());
            assertEquals("tenant-a", first.tenantId());
            assertEquals("managed-deployment", first.deploymentId());
            assertEquals("listener", first.nodeId());
            assertEquals(1, first.graphGeneration());
            assertEquals(List.of("source-start", "authority:1", "acquire:1"), events,
                    "ordinary source readiness precedes managed route activation");

            deployment.restart(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
            IngressRouteOwner second = ingress.authorities.getLast();
            assertEquals(2, second.graphGeneration());
            assertTrue(ingress.retired.contains(first), "restart retires the old generation first");

            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(ingress.retired.contains(second));
            assertEquals(ingress.authorities.size(), ingress.retired.size(), "no route owner is orphaned");
        } finally {
            deployment.shutdown().toCompletableFuture().get(10, TimeUnit.SECONDS);
            engine.close();
        }
    }

    @Test void routeActivationFailureRollsBackTheCurrentSourceAndRetiresItsPartialLease() throws Exception {
        var events = new CopyOnWriteArrayList<String>();
        var ingress = new RecordingIngress(events);
        var behavior = new ManagedBehavior(events, true);
        var engine = new SpawnRecordingEngine();
        var deployment = deployment(engine, behavior, ingress);
        try {
            assertThrows(Exception.class,
                    () -> deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertEquals(DeploymentState.FAILED, deployment.status().state());
            assertTrue(events.contains("source-rollback"),
                    "a source already ready when route activation fails must be rolled back");
            assertEquals(1, ingress.retired.size());
            assertEquals(ingress.authorities.getFirst(), ingress.retired.getFirst(),
                    "rollback retires the exact trusted generation that partially activated");
        } finally {
            deployment.shutdown().toCompletableFuture().get(10, TimeUnit.SECONDS);
            engine.close();
        }
    }

    private static DefaultGraphDeployment deployment(SpawnRecordingEngine engine, ManagedBehavior behavior,
                                                     RecordingIngress ingress) {
        var registry = NodePackages.register(new BehaviorRegistry(), new NodePackage() {
            @Override public String id() { return "test.managed.package"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<NodeBehavior> behaviors() { return List.of(behavior); }
        });
        var deployment = new DefaultGraphDeployment(DeploymentId.of("managed-deployment"), engine, registry,
                new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(),
                GRAPH.getBytes(StandardCharsets.UTF_8), DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY);
        deployment.installManagedIngress(ingress);
        return deployment;
    }

    private static final class ManagedBehavior implements NodeBehavior, InboundSourceCapable {
        private final List<String> events;
        private final boolean failActivation;

        private ManagedBehavior(List<String> events, boolean failActivation) {
            this.events = events;
            this.failActivation = failActivation;
        }

        @Override public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.managed", "Managed source", "Test", "Managed test source",
                    "actor", false, List.of(), Set.of(), null, Set.of());
        }

        @Override public NodeAction create(NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(
                    ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()));
        }

        @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
            return new ManagedIngressSource() {
                @Override public CompletionStage<Void> start(InboundSourceContext ignored) {
                    events.add("source-start");
                    return CompletableFuture.completedFuture(null);
                }

                @Override public CompletionStage<Void> activateManagedIngress(IngressRouteAuthority authority) {
                    authority.acquire("route", "/route", Set.of("POST"), request ->
                            CompletableFuture.completedFuture(new IngressResponse(202, Map.of(), new byte[0])));
                    if (failActivation) return CompletableFuture.failedFuture(
                            new IllegalStateException("synthetic route activation failure"));
                    return CompletableFuture.completedFuture(null);
                }

                @Override public CompletionStage<Void> stop() {
                    events.add("source-stop");
                    return CompletableFuture.completedFuture(null);
                }

                @Override public CompletionStage<Void> rollback() {
                    events.add("source-rollback");
                    return CompletableFuture.completedFuture(null);
                }
            };
        }
    }

    private static final class RecordingIngress implements ManagedIngress {
        private final List<String> events;
        private final List<IngressRouteOwner> authorities = new CopyOnWriteArrayList<>();
        private final List<IngressRouteOwner> retired = new CopyOnWriteArrayList<>();

        private RecordingIngress(List<String> events) {
            this.events = events;
        }

        @Override public IngressRouteAuthority authorityFor(IngressRouteOwner owner) {
            authorities.add(owner);
            events.add("authority:" + owner.graphGeneration());
            return (routeId, relativePath, methods, handler) -> {
                events.add("acquire:" + owner.graphGeneration());
                return new IngressRouteLease() {
                    @Override public String routeId() { return routeId; }
                    @Override public IngressRouteOwner owner() { return owner; }
                    @Override public void release() { }
                };
            };
        }

        @Override public void retire(IngressRouteOwner owner) {
            retired.add(owner);
            events.add("retire:" + owner.graphGeneration());
        }
    }
}
