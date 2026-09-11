package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.ExecutionIoCapacityCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.node.service.NodeExternalIoCapacity;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.core.persistence.InMemoryExecutionManifestStore;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Package capacity callbacks run only after every graph admission validator has accepted. */
class ExternalIoAdmissionOrderingTest {

    @Test void malformedDeclaredPropertyDoesNotReachCapacityOrAction() {
        assertRefusedBeforeExternalIo(graph("""
                <data key="count">not-an-integer</data>
                """, "", ""), GraphExecutionLimits.DEFAULTS, false);
    }

    @Test void missingCapabilityDoesNotReachCapacityOrAction() {
        assertRefusedBeforeExternalIo(graph("", """
                <node id="guard"><data key="kind">BEHAVIOR</data><data key="behavior">needs-capability</data></node>
                """, "<edge id=\"guard-io\" source=\"guard\" target=\"io\"/>"),
                GraphExecutionLimits.DEFAULTS, true);
    }

    @Test void forbiddenRuntimeNatureDoesNotReachCapacityOrAction() {
        assertRefusedBeforeExternalIo(graph("""
                <data key="nature">SOURCE</data>
                """, "", ""), GraphExecutionLimits.DEFAULTS, false);
    }

    @Test void complexityRefusalDoesNotReachCapacityOrAction() {
        GraphExecutionLimits defaults = GraphExecutionLimits.DEFAULTS;
        GraphExecutionLimits limits = new GraphExecutionLimits(defaults.graphMl(), defaults.payload(), 1,
                defaults.maxResidentActors(), defaults.maxLiveActorsPerTraversal(),
                defaults.maxInFlightHopsPerTraversal(), defaults.maxQueuedAdmissionsPerNode(),
                defaults.maxTraversalSteps(), defaults.maxAmplifiedDeliveries(),
                defaults.maxCumulativePayloadBytes(), defaults.maxRecoveryDeliveriesPerAttempt());
        assertRefusedBeforeExternalIo(graph("", """
                <node id="other"><data key="kind">END</data></node>
                """, "<edge id=\"io-other\" source=\"io\" target=\"other\"/>"), limits, false);
    }

    private static void assertRefusedBeforeExternalIo(String graph, GraphExecutionLimits limits,
                                                       boolean missingCapability) {
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger actions = new AtomicInteger();
        BehaviorRegistry behaviors = NodePackages.register(new BehaviorRegistry(), ioPackage(resolutions, actions));
        if (missingCapability) {
            behaviors.registerFactory(new NodeBehaviorFactory() {
                @Override public NodeTypeDescriptor descriptor() {
                    return new NodeTypeDescriptor("needs-capability", "Guard", "Test", "", "actor", false,
                            List.of(), Set.of());
                }
                @Override public void validate(ai.ravenroot.core.graph.GraphNode node) {
                    throw new IllegalStateException("required runtime capability is unavailable");
                }
                @Override public NodeHandler create(ai.ravenroot.core.graph.GraphNode node) {
                    actions.incrementAndGet();
                    throw new AssertionError("action must not be created");
                }
            });
        }
        var application = new DefaultRavenrootApplication(new SameThreadExecutionEngine(),
                new ExecutionMonitor(), behaviors, new InMemoryArtifactRegistry(),
                new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(), null, 0,
                UnknownBehaviorPolicy.passThrough(), null, null, null, limits, null,
                new InMemoryExecutionManifestStore(Clock.systemUTC()));
        try {
            assertThrows(RuntimeException.class, () -> application.startGraphMl(TestIdentities.TENANT_A,
                    UUID.randomUUID(), new ByteArrayInputStream(graph.getBytes(StandardCharsets.UTF_8)), "payload"));
            assertEquals(0, resolutions.get());
            assertEquals(0, actions.get());
        } finally {
            application.close();
        }
    }

    private static NodePackage ioPackage(AtomicInteger resolutions, AtomicInteger actions) {
        final class IoBehavior implements NodeBehavior, ExecutionIoCapacityCapable {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor("test.io", "I/O", "Test", "", "actor", false,
                        List.of(NodePropertyDescriptor.optional("count", "Count", NodePropertyType.INTEGER,
                                "", "1")), Set.of());
            }
            @Override public NodeAction create(NodeConfiguration configuration) {
                actions.incrementAndGet();
                return message -> CompletableFuture.completedFuture(
                        ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()));
            }
            @Override public NodeExternalIoCapacity resolveExecutionIoCapacity(NodeConfiguration configuration) {
                resolutions.incrementAndGet();
                return new NodeExternalIoCapacity(512, 4, Duration.ofSeconds(2), 2);
            }
            @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services,
                                               NodeExternalIoCapacity capacity) {
                return create(configuration);
            }
        }
        NodeBehavior behavior = new IoBehavior();
        return new NodePackage() {
            @Override public String id() { return "test.io.package"; }
            @Override public String version() { return "1"; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<NodeBehavior> behaviors() { return List.of(behavior); }
        };
    }

    private static String graph(String ioProperties, String extraNode, String extraEdge) {
        String startTarget = extraNode.isBlank() ? "io" : "guard";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
                  <key id="count" for="node" attr.name="count" attr.type="string"/>
                  <key id="nature" for="node" attr.name="runtime.nature" attr.type="string"/>
                  <graph id="io" edgedefault="directed">
                    <node id="start"><data key="kind">START</data></node>
                    <node id="io"><data key="kind">BEHAVIOR</data><data key="behavior">test.io</data>%s</node>
                    %s
                    <node id="error"><data key="kind">ERROR</data></node>
                    <node id="end"><data key="kind">END</data></node>
                    <edge id="start-next" source="start" target="%s"/>
                    <edge id="io-end" source="io" target="end"/>
                    %s
                  </graph>
                </graphml>
                """.formatted(ioProperties, extraNode, startTarget, extraEdge);
    }
}
