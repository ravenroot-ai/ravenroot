package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic migration of existing source packages to {@code SOURCE}.
 *
 * <h2>Derivation from a declared code fact, never from topology</h2>
 * <p>{@code implements InboundSourceCapable} is the plugin's own statement that this node opens a
 * deployment-scoped inbound resource — it is the same fact
 * {@link BehaviorRegistry#sourceCapableBehavior} already reads to decide whether to create a source.
 * Nothing here inspects edges, predecessors or reachability, which is what ADR 0024 rejects.</p>
 */
class NodeRuntimeNatureCatalogDerivationTest {

    // ------------------------------------------------------------------ derivation

    @Test
    void aSourceCapableBehaviorThatDeclaresNothingBecomesASource() {
        // The migration. Every existing source package -- kafka.consume, amqp.consume and the rest --
        // declares nothing, and must not read as WORKER: WORKER is the one nature that would be
        // actively wrong for a node whose listener the deployment already starts.
        BehaviorRegistry registry = registryWith(sourceCapable("kafka.consume", null, Set.of()));

        NodeTypeDescriptor published = registry.descriptor("kafka.consume").orElseThrow();
        assertEquals(NodeRuntimeNature.SOURCE, published.effectiveDefaultNature());
        assertEquals(Set.of(NodeRuntimeNature.SOURCE), published.effectiveAllowedNatures());
    }

    @Test
    void anOrdinaryBehaviorThatDeclaresNothingStaysAWorker() {
        // The matching negative. Derivation must reach source-capable behaviors and nothing else, or
        // it is not a derivation but a blanket rewrite.
        BehaviorRegistry registry = registryWith(ordinary("kafka.produce", null, Set.of()));

        NodeTypeDescriptor published = registry.descriptor("kafka.produce").orElseThrow();
        assertEquals(NodeRuntimeNature.WORKER, published.effectiveDefaultNature());
        assertEquals(Set.of(NodeRuntimeNature.WORKER), published.effectiveAllowedNatures());
        assertFalse(published.declaresNature(), "the descriptor's own declaration must be left alone");
    }

    @Test
    void anExplicitDeclarationWins() {
        // A package with an explicit nature keeps what it said. Derivation fills a gap; it
        // does not overrule an author who spoke.
        BehaviorRegistry registry = registryWith(sourceCapable("webhook.listen", NodeRuntimeNature.SOURCE,
                Set.of(NodeRuntimeNature.SOURCE, NodeRuntimeNature.WORKER)));

        NodeTypeDescriptor published = registry.descriptor("webhook.listen").orElseThrow();
        assertEquals(NodeRuntimeNature.SOURCE, published.effectiveDefaultNature());
        assertEquals(Set.of(NodeRuntimeNature.SOURCE, NodeRuntimeNature.WORKER),
                published.effectiveAllowedNatures());
    }

    // ------------------------------------------------------------------ the contradiction refusal

    @Test
    void refusesCatalogLoadWhenTheCodeAndTheDescriptorDisagree() {
        // The two halves of one package saying different things about what the node is. Left to run,
        // the interface wins silently at deployment: source discovery reads InboundSourceCapable, so
        // a listener starts for a node the catalog calls a worker.
        var failure = assertThrows(IllegalArgumentException.class, () -> registryWith(
                sourceCapable("lying.consume", NodeRuntimeNature.WORKER, Set.of(NodeRuntimeNature.WORKER))));

        assertTrue(failure.getMessage().contains("InboundSourceCapable"), failure.getMessage());
        assertTrue(failure.getMessage().contains("lying.consume"), failure.getMessage());
    }

    @Test
    void acceptsASourceCapableBehaviorWhoseAllowlistIncludesSource() {
        // The matching admission: a package may offer a choice, as long as SOURCE is in it.
        BehaviorRegistry registry = registryWith(sourceCapable("dual.consume", NodeRuntimeNature.SOURCE,
                Set.of(NodeRuntimeNature.SOURCE, NodeRuntimeNature.WORKER)));
        assertTrue(registry.descriptor("dual.consume").isPresent());
    }

    @Test
    void theDerivedNatureIsWhatTheCatalogApiPublishes() {
        // descriptors() and descriptor() must agree, or the editor is offered one contract and the
        // validator enforces another.
        BehaviorRegistry registry = registryWith(sourceCapable("kafka.consume", null, Set.of()));

        NodeTypeDescriptor listed = registry.descriptors().stream()
                .filter(type -> "kafka.consume".equals(type.behavior())).findFirst().orElseThrow();
        assertEquals(NodeRuntimeNature.SOURCE, listed.effectiveDefaultNature());
        assertEquals(List.of("SOURCE"), listed.allowedNatureIdentifiers());
    }

    @Test
    void derivationDoesNotDisturbSourceDiscovery() {
        // The resolved descriptor is held beside the factory rather than by wrapping it, because
        // sourceCapableBehavior pattern-matches the factory's concrete type. A decorator would break
        // that match and stop every source starting -- silently, since discovery would simply find
        // nothing.
        BehaviorRegistry registry = registryWith(sourceCapable("kafka.consume", null, Set.of()));
        assertTrue(registry.sourceCapableBehavior("kafka.consume").isPresent(),
                "wrapping the factory would silently disable inbound source discovery");
    }

    // ------------------------------------------------------------------ fixtures

    private static BehaviorRegistry registryWith(NodeBehavior behavior) {
        return NodePackages.register(new BehaviorRegistry(), new NodePackage() {
            @Override
            public String id() {
                return "test.package";
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
                return List.of(behavior);
            }
        });
    }

    private static NodeBehavior ordinary(String name, NodeRuntimeNature defaultNature,
                                         Set<NodeRuntimeNature> allowed) {
        return new PlainBehavior(descriptor(name, defaultNature, allowed));
    }

    private static NodeBehavior sourceCapable(String name, NodeRuntimeNature defaultNature,
                                              Set<NodeRuntimeNature> allowed) {
        return new SourceCapableBehavior(descriptor(name, defaultNature, allowed));
    }

    private static NodeTypeDescriptor descriptor(String name, NodeRuntimeNature defaultNature,
                                                 Set<NodeRuntimeNature> allowed) {
        return new NodeTypeDescriptor(name, name, "Test", "d", "actor", false, List.of(), Set.of(),
                defaultNature, allowed);
    }

    private static class PlainBehavior implements NodeBehavior {
        private final NodeTypeDescriptor descriptor;

        PlainBehavior(NodeTypeDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public NodeTypeDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public NodeAction create(NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(
                    ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()));
        }
    }

    /** Never started: this test is about what the catalog concludes from the interface, not about I/O. */
    private static final class SourceCapableBehavior extends PlainBehavior implements InboundSourceCapable {
        SourceCapableBehavior(NodeTypeDescriptor descriptor) {
            super(descriptor);
        }

        @Override
        public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
            return new InboundSource() {
                @Override
                public CompletionStage<Void> start(InboundSourceContext started) {
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletionStage<Void> stop() {
                    return CompletableFuture.completedFuture(null);
                }
            };
        }
    }
}
