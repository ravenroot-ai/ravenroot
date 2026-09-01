package ai.ravenroot.api.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The typed runtime-nature contract (ADR 0024 §2).
 *
 * <p>The two assertions that matter most here are the ones easiest to satisfy in appearance: that a
 * descriptor which says nothing permits <em>only</em> {@code WORKER}, and that "said nothing" is
 * distinguishable from "declared WORKER". Both are what stop the fail-closed reading from quietly
 * becoming the permissive one.</p>
 */
class NodeRuntimeNatureTest {

    // ------------------------------------------------------------------ the default, asserted

    @Test
    void aDescriptorAuthoredBeforeThisIssueMeansWorkerAndPermitsOnlyWorker() {
        NodeTypeDescriptor legacy = legacyDescriptor();

        assertEquals(NodeRuntimeNature.WORKER, legacy.effectiveDefaultNature());
        assertEquals(Set.of(NodeRuntimeNature.WORKER), legacy.effectiveAllowedNatures());
        assertFalse(legacy.declaresNature(), "a legacy descriptor declares nothing about nature");
    }

    @Test
    void anEmptyAllowedSetIsNotPermissive() {
        // The whole privilege boundary rests on this reading. If empty meant "anything", every
        // descriptor in the catalog -- all of which predate runtime natures -- would permit a graph to declare
        // AUTHORITY, and the escalation this rule prevents would ship as the default.
        NodeTypeDescriptor legacy = legacyDescriptor();

        assertFalse(legacy.effectiveAllowedNatures().contains(NodeRuntimeNature.AUTHORITY));
        assertFalse(legacy.effectiveAllowedNatures().contains(NodeRuntimeNature.SOURCE));
        assertFalse(legacy.effectiveAllowedNatures().contains(NodeRuntimeNature.KEYED));
    }

    @Test
    void absentIsDistinguishableFromDeclaredWorker() {
        // Not a nicety: BehaviorRegistry may supply SOURCE to a descriptor that said nothing and must
        // refuse one that said WORKER while implementing InboundSourceCapable. Collapse the two and
        // every legacy source package reads as a contradiction.
        assertFalse(legacyDescriptor().declaresNature());
        assertTrue(descriptor(NodeRuntimeNature.WORKER, Set.of(NodeRuntimeNature.WORKER)).declaresNature());
        assertTrue(descriptor(NodeRuntimeNature.WORKER, Set.of()).declaresNature());
        assertTrue(descriptor(null, Set.of(NodeRuntimeNature.WORKER)).declaresNature());
    }

    // ------------------------------------------------------------------ parsing

    @Test
    void parsesExactIdentifiersOnly() {
        assertEquals(NodeRuntimeNature.WORKER, NodeRuntimeNature.parse("WORKER").orElseThrow());
        assertEquals(NodeRuntimeNature.TRAVERSAL, NodeRuntimeNature.parse("TRAVERSAL").orElseThrow());
        assertEquals(NodeRuntimeNature.SOURCE, NodeRuntimeNature.parse("  SOURCE  ").orElseThrow());
        assertEquals(NodeRuntimeNature.AUTHORITY, NodeRuntimeNature.parse("AUTHORITY").orElseThrow());
        assertEquals(NodeRuntimeNature.KEYED, NodeRuntimeNature.parse("KEYED").orElseThrow());
    }

    @Test
    void refusesNearMissesRatherThanDefaultingThem() {
        // A near miss must not silently become WORKER: an author who wrote "authority" and got a
        // worker has been demoted without being told, which is the failure the declaration prevents.
        assertTrue(NodeRuntimeNature.parse("worker").isEmpty());
        assertTrue(NodeRuntimeNature.parse("Authority").isEmpty());
        assertTrue(NodeRuntimeNature.parse("SUPERVISOR").isEmpty());
        assertTrue(NodeRuntimeNature.parse("").isEmpty());
        assertTrue(NodeRuntimeNature.parse(null).isEmpty());
    }

    @Test
    void identifiersAreTheStableSerializedForm() {
        assertEquals(List.of("WORKER", "TRAVERSAL", "SOURCE", "AUTHORITY", "KEYED"),
                NodeRuntimeNatureProperty.allowedValues());
    }

    // ------------------------------------------------------------------ descriptor coherence

    @Test
    void refusesADefaultOutsideItsOwnAllowlist() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> descriptor(NodeRuntimeNature.AUTHORITY, Set.of(NodeRuntimeNature.WORKER)));
        assertTrue(failure.getMessage().contains("not among its allowed natures"), failure.getMessage());
    }

    @Test
    void acceptsADefaultInsideItsAllowlist() {
        NodeTypeDescriptor choice =
                descriptor(NodeRuntimeNature.WORKER, Set.of(NodeRuntimeNature.WORKER, NodeRuntimeNature.SOURCE));
        assertEquals(NodeRuntimeNature.WORKER, choice.effectiveDefaultNature());
        assertEquals(Set.of(NodeRuntimeNature.WORKER, NodeRuntimeNature.SOURCE), choice.effectiveAllowedNatures());
        assertEquals(List.of("WORKER", "SOURCE"), choice.allowedNatureIdentifiers());
    }

    // ------------------------------------------------------------------ residency predicate

    @Test
    void residencyPredicateNamesExactlyAuthorityAndKeyed() {
        // The single definition behind the deploy refusal. Residency support empties it, and the refusal tests
        // invert visibly rather than being deleted.
        assertFalse(NodeRuntimeNature.WORKER.requiresUnimplementedResidency());
        assertFalse(NodeRuntimeNature.TRAVERSAL.requiresUnimplementedResidency());
        assertFalse(NodeRuntimeNature.SOURCE.requiresUnimplementedResidency());
        assertTrue(NodeRuntimeNature.AUTHORITY.requiresUnimplementedResidency());
        assertTrue(NodeRuntimeNature.KEYED.requiresUnimplementedResidency());
    }

    // ------------------------------------------------------------------ effective resolution

    @Test
    void resolvesDeclaredValueOverTheDescriptorDefault() {
        NodeTypeDescriptor type =
                descriptor(NodeRuntimeNature.WORKER, Set.of(NodeRuntimeNature.WORKER, NodeRuntimeNature.SOURCE));
        assertEquals(NodeRuntimeNature.SOURCE, NodeRuntimeNatureProperty.effectiveNature(
                type, Map.of(NodeRuntimeNatureProperty.NAME, "SOURCE")));
        assertEquals(NodeRuntimeNature.WORKER, NodeRuntimeNatureProperty.effectiveNature(type, Map.of()));
    }

    @Test
    void aNodeWithNoCatalogEntryIsAlwaysTheDefault() {
        assertEquals(NodeRuntimeNature.WORKER, NodeRuntimeNatureProperty.effectiveNature(null, Map.of()));
        assertEquals(NodeRuntimeNature.WORKER, NodeRuntimeNatureProperty.effectiveNature(
                null, Map.of(NodeRuntimeNatureProperty.NAME, "AUTHORITY")));
    }

    @Test
    void declaredByReportsThePropertyEvenWhenItsValueIsNonsense() {
        assertTrue(NodeRuntimeNatureProperty.declaredBy(Map.of(NodeRuntimeNatureProperty.NAME, "nonsense")));
        assertFalse(NodeRuntimeNatureProperty.declaredBy(Map.of("owner", "team")));
        assertFalse(NodeRuntimeNatureProperty.declaredBy(null));
    }

    // ------------------------------------------------------------------ anti-collision at catalog load

    @Test
    void refusesABehaviorThatDeclaresThePlatformOwnedNameAsItsOwnProperty() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> NodeTypeDescriptorValidator.validate(new NodeTypeDescriptor(
                        "colliding", "Colliding", "Test", "d", "actor", false,
                        List.of(NodePropertyDescriptor.optional(NodeRuntimeNatureProperty.NAME, "Nature",
                                NodePropertyType.STRING, "d", "")),
                        Set.of())));
        assertTrue(failure.getMessage().contains("platform-owned"), failure.getMessage());
        assertTrue(failure.getMessage().contains(NodeRuntimeNatureProperty.NAME), failure.getMessage());
    }

    @Test
    void acceptsABehaviorThatDeclaresOrdinaryPropertiesBesideIt() {
        NodeTypeDescriptorValidator.validate(new NodeTypeDescriptor(
                "ordinary", "Ordinary", "Test", "d", "actor", false,
                List.of(NodePropertyDescriptor.optional("nature", "Nature", NodePropertyType.STRING, "d", ""),
                        NodePropertyDescriptor.optional("runtime.mode", "Mode", NodePropertyType.STRING, "d", "")),
                Set.of()));
    }

    // ------------------------------------------------------------------ fixtures

    private static NodeTypeDescriptor legacyDescriptor() {
        return new NodeTypeDescriptor("legacy", "Legacy", "Test", "d", "actor", false, List.of(), Set.of());
    }

    private static NodeTypeDescriptor descriptor(NodeRuntimeNature defaultNature,
                                                 Set<NodeRuntimeNature> allowed) {
        return new NodeTypeDescriptor("typed", "Typed", "Test", "d", "actor", false, List.of(), Set.of(),
                defaultNature, allowed);
    }
}
