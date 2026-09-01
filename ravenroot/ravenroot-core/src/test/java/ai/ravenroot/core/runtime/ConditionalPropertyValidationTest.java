package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.catalog.PropertyCondition;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How conditions behave against a graph's values.
 *
 * <p>Two properties are asserted together because their interaction is the invariant most easily
 * satisfied in appearance: {@code requiredWhen} decides required-ness, and {@code visibleWhen}
 * decides nothing at all here. A hidden property is never required and its value is still checked --
 * skipping it would be a bypass, enforcing it would be a trap.
 */
class ConditionalPropertyValidationTest {
    @Test
    void requiresTheGuardedPropertyOnlyWhenTheConditionHolds() {
        var schema = schema();

        var failure = assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class,
                () -> schema.validate(graph(Map.of("mode", "WEBHOOK"))));
        assertTrue(failure.getMessage().contains("required"), failure.getMessage());

        assertDoesNotThrow(() -> schema.validate(graph(Map.of("mode", "LONG_POLLING"))),
                "the guarded property must not be required in the other mode");
        assertDoesNotThrow(() -> schema.validate(
                graph(Map.of("mode", "WEBHOOK", "callbackUrl", "https://example.invalid/hook"))));
    }

    /**
     * The bypass this design refuses. A value for a property whose visibleWhen does not hold is still
     * type-checked: hidden means "not shown", never "not validated".
     */
    @Test
    void stillValidatesTheValueOfAPropertyThatIsNotVisible() {
        var failure = assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class,
                () -> schema().validate(graph(Map.of("mode", "LONG_POLLING", "callbackUrl", "not a uri"))),
                "a hidden property carrying a value must still be type-checked, or a hand-authored "
                        + "graph could smuggle any value at all past the schema");
        assertTrue(failure.getMessage().contains("callbackUrl"), failure.getMessage());
    }

    /** Preserved, not removed: the value survives the round trip even while its condition is false. */
    @Test
    void preservesAHiddenPropertysValueRatherThanRemovingIt() {
        var node = node(Map.of("mode", "LONG_POLLING", "callbackUrl", "https://example.invalid/hook"));
        assertDoesNotThrow(() -> schema().validate(new GraphDefinition(
                List.of(GraphNode.start("start"), node, GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", node.id()), GraphEdge.to(node.id(), "end")))));
        assertTrue(node.properties().containsKey("callbackUrl"),
                "validation must not strip a hidden property; removing it would break the lossless "
                        + "round trip this schema already guarantees for unknown properties");
    }

    /**
     * The wiring, asserted rather than assumed: a malformed descriptor is refused at REGISTRATION.
     *
     * <p>Validation belongs in {@code registerFactory} precisely because it is the one
     * path every registration takes -- built-ins, SDK node packages and plugin bundles. Without this
     * test the validator could be correct and simply never called on the path that matters, which is
     * the shape of a control that cannot fire.
     */
    @Test
    void refusesAMalformedDescriptorAtRegistrationRatherThanAtUse() {
        var registry = new BehaviorRegistry();

        var failure = assertThrows(IllegalArgumentException.class,
                () -> registry.registerFactory(new DanglingFactory()));

        assertTrue(failure.getMessage().contains("does not declare"), failure.getMessage());
        assertTrue(registry.descriptor("test.dangling").isEmpty(),
                "a refused descriptor must not be registered; a partially-registered malformed "
                        + "behavior is worse than a refused one");
    }

    private static final class DanglingFactory implements NodeBehaviorFactory {
        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.dangling", "Dangling", "General", "d", "actor", false,
                    List.of(new NodePropertyDescriptor("url", "URL", NodePropertyType.STRING, false, "d", "",
                            List.of(), false, PropertyCondition.present("nosuch"), null)),
                    Set.of());
        }

        @Override
        public NodeHandler create(GraphNode node) {
            return message -> CompletableFuture.completedFuture(
                    new NodeResult("continue", message.payload(), Map.of()));
        }
    }

    private static BehaviorPropertySchema schema() {
        var registry = new BehaviorRegistry();
        registry.registerFactory(new ConditionalFactory());
        return new BehaviorPropertySchema(registry);
    }

    private static GraphDefinition graph(Map<String, Object> properties) {
        GraphNode node = node(properties);
        return new GraphDefinition(List.of(GraphNode.start("start"), node, GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", node.id()), GraphEdge.to(node.id(), "end")));
    }

    private static GraphNode node(Map<String, Object> properties) {
        return new GraphNode("conditional", NodeKind.BEHAVIOR, "test.conditional", properties);
    }

    private static final class ConditionalFactory implements NodeBehaviorFactory {
        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.conditional", "Conditional", "General", "d", "actor", false,
                    List.of(new NodePropertyDescriptor("mode", "Mode", NodePropertyType.STRING, true, "d", "",
                                    List.of("LONG_POLLING", "WEBHOOK"), false, null, null),
                            new NodePropertyDescriptor("callbackUrl", "Callback URL", NodePropertyType.URI,
                                    false, "d", "", List.of(), false,
                                    PropertyCondition.equalTo("mode", "WEBHOOK"),
                                    PropertyCondition.equalTo("mode", "WEBHOOK"))),
                    Set.of());
        }

        @Override
        public NodeHandler create(GraphNode node) {
            return message -> CompletableFuture.completedFuture(
                    new NodeResult("continue", message.payload(), Map.of()));
        }
    }
}
