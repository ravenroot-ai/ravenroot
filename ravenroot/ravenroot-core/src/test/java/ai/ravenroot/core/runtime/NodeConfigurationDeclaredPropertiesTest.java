package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodeBypassProperty;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.TrustedIngress;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A behavior sees the properties its descriptor declares, and never the editor's presentation data
 * or the platform's own flags.
 *
 * <p>The defect this pins: {@code mail.imap.consume}, {@code kafka.consume} and {@code amqp.consume}
 * fail closed on any property they did not declare, which is correct for their own configuration and
 * catastrophic for everything else the document carries. The editor annotates every node it
 * serializes with geometry, and the platform writes {@code execution.bypass}. Both arrived as node
 * properties, so a graph that had ever been opened in the editor could not start at all — only
 * hand-written GraphML could.</p>
 *
 * <p>These tests use a source behavior that refuses unknown properties exactly the way the three real
 * ones do, so they fail on the unfixed boundary and describe the contract rather than the fix.</p>
 */
class NodeConfigurationDeclaredPropertiesTest {

    /** Every annotation the editor writes onto a node it serializes, plus the platform's bypass flag. */
    private static Map<String, Object> editorAnnotations(String bypass) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("layoutX", "120.0");
        properties.put("layoutY", "64.0");
        properties.put("layoutWidth", "80.0");
        properties.put("layoutHeight", "52.0");
        properties.put("name", "Consume mailbox");
        properties.put("classification", "actor");
        properties.put("description", "");
        if (bypass != null) {
            properties.put(NodeBypassProperty.NAME, bypass);
        }
        return properties;
    }

    private static Map<String, Object> configured(Map<String, Object> extra) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("profile", "mailbox-a");
        properties.putAll(extra);
        return properties;
    }

    @Test
    void aHandWrittenGraphWithNoEditorAnnotationsStillStarts() {
        AtomicReference<NodeConfiguration> seen = new AtomicReference<>();

        startSource(configured(Map.of("pollIntervalMs", "5000")), seen);

        assertEquals(Map.of("profile", "mailbox-a", "pollIntervalMs", "5000"), seen.get().properties());
    }

    @Test
    void aGraphCarryingEditorGeometryStarts() {
        AtomicReference<NodeConfiguration> seen = new AtomicReference<>();

        startSource(configured(editorAnnotations(null)), seen);

        assertEquals(Map.of("profile", "mailbox-a"), seen.get().properties());
    }

    /**
     * Both bypass values, because the refusal was on the key's presence and not on its value: with
     * the check reading {@code properties().keySet()}, unticking the Inspector's bypass box wrote
     * {@code false} and left the graph exactly as unstartable as ticking it had.
     */
    @Test
    void bothBypassValuesStartAndNeitherReachesTheBehavior() {
        for (String bypass : NodeBypassProperty.allowedValues()) {
            AtomicReference<NodeConfiguration> seen = new AtomicReference<>();

            startSource(configured(editorAnnotations(bypass)), seen);

            assertEquals(Map.of("profile", "mailbox-a"), seen.get().properties(), bypass);
            assertTrue(seen.get().property(NodeBypassProperty.NAME).isEmpty(), bypass);
        }
    }

    /**
     * The filter narrows what a behavior sees, not what the document keeps: the bypass flag and the
     * geometry are still on the {@link GraphNode}, where the bypass validator and the round-trip
     * contract read them.
     */
    @Test
    void theNodeItselfKeepsEveryAnnotationTheDocumentWrote() {
        Map<String, Object> stored = configured(editorAnnotations("true"));
        GraphNode node = new GraphNode("consume", NodeKind.BEHAVIOR, "test.consume", stored);

        assertEquals(stored, node.properties());
        assertTrue(NodeBypassProperty.declaredBy(node.properties()));
    }

    /** A property the descriptor declares is still delivered, whatever else the document carries. */
    @Test
    void declaredPropertiesSurviveAlongsideTheAnnotations() {
        AtomicReference<NodeConfiguration> seen = new AtomicReference<>();
        Map<String, Object> stored = configured(editorAnnotations("false"));
        stored.put("pollIntervalMs", "30000");

        startSource(stored, seen);

        assertEquals(Map.of("profile", "mailbox-a", "pollIntervalMs", "30000"), seen.get().properties());
    }

    /**
     * The handler path is the same boundary, so a worker node gets the same view a source does.
     */
    @Test
    void theHandlerPathFiltersTheSameWay() {
        AtomicReference<NodeConfiguration> seen = new AtomicReference<>();
        BehaviorRegistry registry = NodePackages.register(new BehaviorRegistry(),
                packageWith(strictBehavior(seen)), NodePackageServiceRegistry.empty());

        registry.create(new GraphNode("consume", NodeKind.BEHAVIOR, "test.consume",
                configured(editorAnnotations("true")))).orElseThrow();

        assertEquals(Map.of("profile", "mailbox-a"), seen.get().properties());
    }

    /**
     * The strict check keeps its teeth where the descriptor gives it authority. A behavior still sees
     * — and still refuses — a declared property whose value it will not accept; narrowing the map
     * removed the keys the node never owned, not the node's judgement over the keys it does.
     */
    @Test
    void aDeclaredPropertyWithARefusedValueStillFailsClosed() {
        AtomicReference<NodeConfiguration> seen = new AtomicReference<>();
        Map<String, Object> stored = configured(editorAnnotations("true"));
        stored.put("pollIntervalMs", "not-a-number");

        assertThrows(IllegalStateException.class, () -> startSource(stored, seen));
    }

    private static void startSource(Map<String, Object> properties, AtomicReference<NodeConfiguration> seen) {
        BehaviorRegistry registry = NodePackages.register(new BehaviorRegistry(),
                packageWith(strictBehavior(seen)), NodePackageServiceRegistry.empty());

        registry.sourceCapableFactory("test.consume").orElseThrow()
                .createSource(new GraphNode("consume", NodeKind.BEHAVIOR, "test.consume", properties),
                        sourceContext());
    }

    /** A source shaped like the three real ones: it declares two properties and refuses anything else. */
    private static NodeBehavior strictBehavior(AtomicReference<NodeConfiguration> seen) {
        final class StrictSource implements NodeBehavior, InboundSourceCapable {
            private static final Set<String> CONFIGURATION = Set.of("profile", "pollIntervalMs");

            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor("test.consume", "Strict consume", "Test", "", "source", false,
                        List.of(NodePropertyDescriptor.required("profile", "Profile",
                                        NodePropertyType.STRING, "Operator-authorized profile."),
                                NodePropertyDescriptor.optional("pollIntervalMs", "Poll interval (ms)",
                                        NodePropertyType.INTEGER, "Tightening-only poll interval.", "")),
                        Set.of());
            }

            @Override public NodeAction create(NodeConfiguration configuration) {
                resolve(configuration);
                seen.set(configuration);
                return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
            }

            @Override public InboundSource createSource(NodeConfiguration configuration,
                                                        InboundSourceContext context) {
                resolve(configuration);
                seen.set(configuration);
                return new InboundSource() {
                    @Override public CompletionStage<Void> start(InboundSourceContext started) {
                        return CompletableFuture.completedFuture(null);
                    }
                    @Override public CompletionStage<Void> stop() {
                        return CompletableFuture.completedFuture(null);
                    }
                };
            }

            /** The construct the issue quotes, verbatim in shape. */
            private static void resolve(NodeConfiguration configuration) {
                if (!CONFIGURATION.containsAll(configuration.properties().keySet())) {
                    throw new IllegalStateException("unknown-graph-property");
                }
                String poll = configuration.property("pollIntervalMs", "");
                if (!poll.isEmpty() && !poll.chars().allMatch(Character::isDigit)) {
                    throw new IllegalStateException("invalid-poll-interval");
                }
            }
        }
        return new StrictSource();
    }

    private static NodePackage packageWith(NodeBehavior behavior) {
        return new NodePackage() {
            @Override public String id() { return "test.declared-properties"; }
            @Override public String version() { return "1"; }
            @Override public String sdkContract() { return NodeSdk.LEGACY_CONTRACT; }
            @Override public List<NodeBehavior> behaviors() { return List.of(behavior); }
        };
    }

    private static InboundSourceContext sourceContext() {
        return new InboundSourceContext() {
            @Override public DeploymentId deploymentId() { return DeploymentId.of("declared-properties-test"); }
            @Override public String nodeId() { return "consume"; }
            @Override public ai.ravenroot.api.security.SecurityContext identity() { return TestIdentities.TENANT_A; }
            @Override public TrustedIngress ingress() { throw new UnsupportedOperationException(); }
            @Override public void reportDegraded(String sanitizedReason) { }
            @Override public void reportHealthy() { }
        };
    }
}
