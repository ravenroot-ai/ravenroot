package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.provenance.SyntheticProvenance;
import ai.ravenroot.core.graph.ReservedGraphProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half of REG-03 the runtime test cannot reach: the rule against the <em>real</em> catalog.
 *
 * <p>{@code SyntheticProvenanceMarkerTest} runs a stub, because no node the core ships can produce
 * model-generated content. Previously, {@code llm-prompt} and {@code agent} refused when reached
 * without a configured adapter; those two node types are now in a plugin bundle rather than the core
 * (ADR 0029). This class asks the complementary
 * question of the descriptors the product really ships.</p>
 *
 * <h2>What the catalog move changed here, and what it deliberately did not</h2>
 * <p>The claim "the catalog calls these generative" had two named subjects and now has none, so it is
 * <b>inverted rather than deleted</b>: no shipped node type is generative, which is precisely the
 * property delivered by the catalog move and guarded on the bundle side. Inverting a
 * claim is how a case goes vacuous, so the recognition rule itself — {@link
 * SyntheticProvenance#isGenerative} answering <em>true</em> for a descriptor that declares a
 * generative capability — is kept under test on {@link #aBundleShapedBehaviorDeclaringAGenerativeCapabilityIsMarked},
 * against a descriptor the core does not know. That is not a weaker case than the old one: it is the
 * same rule asked of the shape the product now actually takes, because a bundle node is exactly a
 * descriptor Ravenroot did not write. The core's handle on generativity is the descriptor capability,
 * and it never was the SPI.</p>
 */
class SyntheticProvenanceCatalogTest {

    private static final BehaviorRegistry CATALOG = BehaviorRegistry.standard();

    private static NodeTypeDescriptor shipped(String behavior) {
        return CATALOG.descriptor(behavior).orElseThrow(
                () -> new AssertionError("the standard catalog no longer ships '" + behavior + "'"));
    }

    /**
     * A descriptor a bundle could publish, used wherever a case needs a generative node type. The core
     * ships none, and minting a marker requires one — so this is what those cases mint
     * from, rather than a name the catalog no longer answers to.
     */
    private static NodeTypeDescriptor bundleGenerative(String behavior, String capability) {
        return new NodeTypeDescriptor(behavior, behavior, "AI",
                "A generative behavior supplied from outside the core.", "agent", false, List.of(),
                Set.of(capability, "external-provider"));
    }

    /**
     * The recognition rule, on the shape the product now takes: a descriptor Ravenroot did not write.
     *
     * <p>This is the positive half of {@link #noShippedNodeTypeIsGenerative}. Without it that method
     * would pass equally well if {@code isGenerative} had been broken to return {@code false} for
     * everything, which is the exact way an inverted assertion goes quietly vacuous.</p>
     */
    @Test
    void aGenerativeCapabilityIsRecognisedByCapabilityAndNotByName() {
        assertTrue(SyntheticProvenance.isGenerative(bundleGenerative("acme-prompt", "ai")),
                "a node declaring 'ai' must be marked without being named anywhere");
        assertEquals(List.of("ai"),
                SyntheticProvenance.generativeCapabilities(bundleGenerative("acme-prompt", "ai")));

        assertTrue(SyntheticProvenance.isGenerative(bundleGenerative("acme-agent", "agentic")));
        assertEquals(List.of("agentic"),
                SyntheticProvenance.generativeCapabilities(bundleGenerative("acme-agent", "agentic")));
    }

    /**
     * No node type in the shipped catalog is generative.
     *
     * <p>Derived over the whole catalog rather than over a listed sample: a sample is what let the two
     * AI nodes sit outside it for as long as they did, and the property this asserts is about the
     * catalog as a whole. The roster assertion at the end is the anti-vacuity half — an empty registry
     * satisfies "none is generative" perfectly.</p>
     */
    @Test
    void noShippedNodeTypeIsGenerative() {
        var shipped = CATALOG.descriptors();
        for (NodeTypeDescriptor descriptor : shipped) {
            assertFalse(SyntheticProvenance.isGenerative(descriptor),
                    descriptor.behavior() + " produces deterministic or externally-fetched content and "
                            + "must not be marked as model-generated. A node that does reach a model "
                            + "belongs in a bundle (ADR 0029), not in this catalog");
            assertTrue(SyntheticProvenance.mint("n1", descriptor, "text").isEmpty(),
                    descriptor.behavior() + " must have no code path that mints a marker");
        }
        assertEquals(Set.of("boundary-guard", "cel-decision", "cel-transform", "delay", "human-task",
                        "http-request", "json-parse", "json-path", "log", "program", "template"),
                shipped.stream().map(NodeTypeDescriptor::behavior).collect(java.util.stream.Collectors.toSet()),
                "the core catalog changed shape; the loop above says nothing about a catalog that is "
                        + "empty or unrecognisable, so update this roster deliberately");
    }

    @Test
    void anApplicationRegisteredBehaviorIsNotGenerativeUnlessItSaysSo() {
        var descriptor = new BehaviorRegistry()
                .register("custom", message -> null)
                .descriptor("custom")
                .orElseThrow();
        assertFalse(SyntheticProvenance.isGenerative(descriptor),
                "a plain application handler must not acquire a provenance claim by accident");
    }

    @Test
    void aBundleShapedBehaviorDeclaringAGenerativeCapabilityIsMarked() {
        // The point of deriving from the descriptor: this factory is unknown to Ravenroot. That is
        // how EVERY generative node reaches a
        // Ravenroot deployment, so this case now carries the whole positive weight of the rule.
        var descriptor = new NodeTypeDescriptor("acme-copywriter", "Acme copywriter", "AI",
                "A third-party generative behavior.", "agent", false, List.of(), Set.of("ai", "acme-internal"));
        assertTrue(SyntheticProvenance.isGenerative(descriptor));
        assertTrue(SyntheticProvenance.mint("n1", descriptor, "text").isPresent());
    }

    @Test
    void aNonGenerativeDescriptorCannotMintAMarkerAtAll() {
        assertTrue(SyntheticProvenance.mint("n1", shipped("template"), "text").isEmpty(),
                "there must be no code path that marks content from a node the catalog never declared "
                        + "generative");
    }

    @Test
    void theMarkerKeyIsInsideTheNamespaceGraphsAreRefusedAtIngest() {
        assertTrue(ReservedGraphProperties.isReserved(SyntheticProvenance.ATTRIBUTE),
                "the marker's unforgeability from graph content is exactly this");
        assertTrue(ReservedGraphProperties.isReserved(SyntheticProvenance.KEY_PREFIX + "anything"));
        assertTrue(SyntheticProvenance.isProvenanceKey("  RavenRoot.Provenance.Synthetic  "),
                "namespace ownership must not be defeated by casing or padding");
        assertFalse(SyntheticProvenance.isProvenanceKey("ravenroot.logged"));
    }

    // ---- the content binding, which is what makes propagation per-content ----

    @Test
    void equalContentBindsEqualAndDifferentContentDoesNot() {
        assertEquals(SyntheticProvenance.bind("hello"), SyntheticProvenance.bind("hello"));
        assertEquals(SyntheticProvenance.bind(Map.of("a", 1, "b", 2)),
                SyntheticProvenance.bind(new LinkedHashMap<>(Map.of("b", 2, "a", 1))),
                "map binding must not depend on iteration order");
        assertNotEquals(SyntheticProvenance.bind("hello"), SyntheticProvenance.bind("hello "));
        assertNotEquals(SyntheticProvenance.bind(List.of("a", "b")), SyntheticProvenance.bind(List.of("b", "a")),
                "list order is content");
        assertNotEquals(SyntheticProvenance.bind(1), SyntheticProvenance.bind(1L),
                "a widening must fail closed rather than be conflated");
        assertNotEquals(SyntheticProvenance.bind("1"), SyntheticProvenance.bind(1));
        assertEquals(SyntheticProvenance.bind(new BigDecimal("1E+2")), SyntheticProvenance.bind(new BigDecimal("100")));
    }

    @Test
    void contentWithNoCanonicalFormIsRefusedRatherThanApproximated() {
        assertTrue(SyntheticProvenance.bind(new Object()).isEmpty(),
                "an arbitrary object has no value identity; toString() would be an identity hash");
        assertTrue(SyntheticProvenance.bind(List.of(List.of(new Object()))).isEmpty(),
                "the refusal must survive nesting");
    }

    @Test
    void anUnboundMarkerDescribesNothingSoItNeverPropagates() {
        var opaque = new Object();
        Map<String, Object> marker = SyntheticProvenance
                .mint("n1", bundleGenerative("acme-prompt", "ai"), opaque).orElseThrow();
        assertEquals(SyntheticProvenance.UNBOUND, marker.get(SyntheticProvenance.CONTENT_DIGEST_FIELD),
                "unbindable content still gets a marker on its own output");
        assertFalse(SyntheticProvenance.describes(marker, opaque),
                "but it can never be shown to describe a downstream payload, so it stops here");
    }

    @Test
    void aMarkerDescribesOnlyTheContentItWasMintedFor() {
        Map<String, Object> marker = SyntheticProvenance
                .mint("n1", bundleGenerative("acme-prompt", "ai"), "generated text").orElseThrow();
        assertTrue(SyntheticProvenance.describes(marker, "generated text"));
        assertFalse(SyntheticProvenance.describes(marker, "generated text!"));
        assertFalse(SyntheticProvenance.describes(marker, List.of("generated text")),
                "embedding the content in a larger structure is derivation, not the same content");
        assertFalse(SyntheticProvenance.describes(marker, null));
    }

    @Test
    void aMalformedOrForeignMarkerIsNotReadAsAMarker() {
        assertTrue(SyntheticProvenance.read(Map.of()).isEmpty());
        assertTrue(SyntheticProvenance.read(Map.of(SyntheticProvenance.ATTRIBUTE, "true")).isEmpty(),
                "a bare truthy value is not a provenance claim");
        assertTrue(SyntheticProvenance.read(Map.of(SyntheticProvenance.ATTRIBUTE,
                Map.of("contract", "somebody.else/1", "contentDigest", "sha256:aa"))).isEmpty());
        assertTrue(SyntheticProvenance.read(Map.of(SyntheticProvenance.ATTRIBUTE,
                Map.of(SyntheticProvenance.CONTRACT_FIELD, SyntheticProvenance.CONTRACT))).isEmpty(),
                "a marker with no binding is not a marker");
        assertTrue(SyntheticProvenance.read(Map.of(SyntheticProvenance.ATTRIBUTE,
                SyntheticProvenance.mint("n1", bundleGenerative("acme-agent", "agentic"), "x").orElseThrow()))
                .isPresent());
    }
}
