package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.core.graph.GraphNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BehaviorRegistry#registerFactory} must refuse a name already registered rather than
 * replace it, on every path a registration can arrive through. Two paths, deliberately distinct:
 *
 * <ul>
 *   <li>{@link #registerFactoryItselfRefusesADirectCollision()} calls {@code registerFactory} twice
 *       directly, with no wrapper in between -- the built-in registration shape
 *       ({@code StandardBehaviorFactories.all(...).forEach(registry::registerFactory)}), and the
 *       canonical choke point already used by {@code NodeTypeDescriptorValidator}.</li>
 *   <li>{@link #aPluginFactoryCannotSilentlyTakeOverABuiltInsName()} covers a plugin colliding with a
 *       <em>built-in</em>, not two plugins colliding with each other. It
 *       deliberately bypasses {@code NodePackages#register} rather than going through it: that method
 *       already carries its own, narrower, pre-existing collision guard (a redundant belt this test
 *       does not remove), and going through it here would prove that guard works, not prove that
 *       {@code registerFactory} does. Registering the plugin-shaped factory directly is what isolates
 *       the collision handled here -- confirmed by the mutation below only reddening the
 *       method it is meant to test.</li>
 * </ul>
 */
class BehaviorNameCollisionTest {
    @Test
    void registerFactoryItselfRefusesADirectCollision() {
        var registry = new BehaviorRegistry();
        var first = new StubFactory("duplicate-name", "first");
        var second = new StubFactory("duplicate-name", "second");
        registry.registerFactory(first);

        var failure = assertThrows(IllegalArgumentException.class, () -> registry.registerFactory(second));
        assertTrue(failure.getMessage().contains("duplicate-name"),
                () -> "the refusal must name the colliding behavior: " + failure.getMessage());

        // The original registration is untouched -- not merely "an exception was thrown somewhere",
        // but the map genuinely still holds what was there before the second call, not the second's.
        assertEquals("first", registry.descriptor("duplicate-name").orElseThrow().description());
    }

    /**
     * The required case: a plugin colliding with a built-in, not with another plugin. Uses a
     * real built-in ({@code "log"}, from {@link BehaviorRegistry#standard()}) so this is the actual
     * catalog a deployment would run, not a fixture standing in for one.
     */
    @Test
    void aPluginFactoryCannotSilentlyTakeOverABuiltInsName() {
        var registry = BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults());
        var builtInDescriptorBefore = registry.descriptor("log").orElseThrow();

        // The real adapter shape NodePackages#register installs, registered directly rather than
        // through that method --
        // see the class Javadoc for why that isolation is what makes this test prove what it claims.
        var plugin = new NodePackages.SdkNodeBehaviorFactory(new PluginBehavior("log"));

        assertThrows(IllegalArgumentException.class, () -> registry.registerFactory(plugin));

        // The built-in's own descriptor is exactly what it was -- a graph naming "log" still gets the
        // built-in, never the plugin, and nothing about the built-in's catalog entry changed. Value
        // equality, not reference identity: NodeTypeDescriptor is a record LogNodeBehaviorFactory
        // builds fresh on every call, so two genuinely-unchanged reads are equal but never the same
        // object -- assertSame here would fail for a reason unrelated to what this test is about.
        assertEquals(builtInDescriptorBefore, registry.descriptor("log").orElseThrow());
    }

    /** A minimal, non-SDK factory -- exactly the shape a built-in is registered as. */
    private record StubFactory(String name, String description) implements NodeBehaviorFactory {
        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor(name, name, "Custom", description, "actor", false, List.of(), Set.of());
        }

        @Override
        public NodeHandler create(GraphNode node) {
            return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        }
    }

    /** An SDK-shaped {@link NodeBehavior}, representing what a real plugin would declare. */
    private record PluginBehavior(String name) implements NodeBehavior {
        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor(name, name, "Custom", "A plugin's own '" + name + "'", "actor", false,
                    List.of(), Set.of());
        }

        @Override
        public NodeAction create(NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        }
    }
}
