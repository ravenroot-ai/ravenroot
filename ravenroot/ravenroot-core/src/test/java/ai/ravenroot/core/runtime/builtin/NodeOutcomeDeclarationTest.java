package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.NodeBehaviorFactory;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every built-in behavior declares the outcomes it can produce, and the two parameterized ones resolve
 * against the node's own property values.
 *
 * <h2>What this test is defending</h2>
 * <p>The outcome is the only thing {@code GraphDefinition#nextEdges} matches an outgoing edge on, and
 * previously no descriptor said what a behavior could produce — so an author wired an edge by writing
 * a string and hoping. The risk this test exists to catch is not that the field is missing; it is that
 * a declaration says something the behavior does not do. Hence
 * {@link #everyDeclaredOutcomeIsOneTheBehaviorCanActuallyProduce()}, which reads the declarations back
 * against the hard-wired {@code continue} the seven non-parameterized behaviors emit.</p>
 */
class NodeOutcomeDeclarationTest {

    private static List<NodeTypeDescriptor> descriptors() {
        return StandardBehaviorFactories.all(BehaviorEnvironment.safeDefaults()).stream()
                .map(NodeBehaviorFactory::descriptor)
                .toList();
    }

    private static NodeTypeDescriptor descriptor(String behavior) {
        return descriptors().stream()
                .filter(type -> type.behavior().equals(behavior))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No built-in behavior named " + behavior));
    }

    /** Coverage is exhaustive: every built-in, not a sample. */
    @TestFactory
    Stream<DynamicTest> everyBuiltInBehaviorDeclaresAtLeastOneOutcome() {
        return descriptors().stream().map(type -> DynamicTest.dynamicTest(type.behavior(), () ->
                assertFalse(type.outcomes().isEmpty(),
                        "Behavior '" + type.behavior() + "' declares no outcomes, so the edge inspector "
                                + "can suggest nothing for an edge leaving it and an author is back to "
                                + "guessing the string that selects the edge")));
    }

    /**
     * The five behaviors that hard-wire {@code continue} declare exactly that, and nothing else.
     *
     * <p>Written as an explicit expected set rather than derived, because deriving it from the same
     * descriptors under test would assert nothing. The values come from reading each factory's
     * {@code create}: each returns {@code new NodeResult("continue", ...)} unconditionally.</p>
     *
     * <p><strong>No failure outcome is declared for any of them, deliberately.</strong> None of the
     * five produces one — an error inside the handler fails the node, which is a different route than
     * a second outcome — and declaring {@code fail} here would reproduce the original defect inverted:
     * an author would wire a branch that can never be selected.</p>
     *
     * <p>There were seven until {@code llm-prompt} and {@code agent} left the core. They are dropped
     * from the list rather than kept as names {@link #descriptor} would now fail to find; the
     * derived {@link #everyBuiltInBehaviorDeclaresAtLeastOneOutcome} above is what keeps the
     * shrinkage from being a hole, since it covers whatever the catalog actually contains.</p>
     */
    @TestFactory
    Stream<DynamicTest> theFiveFixedBehaviorsDeclareOnlyContinue() {
        return Stream.of("log", "delay", "template", "cel-transform", "program")
                .map(behavior -> DynamicTest.dynamicTest(behavior, () -> {
                    NodeTypeDescriptor type = descriptor(behavior);
                    assertEquals(Set.of("continue"), type.resolveOutcomes(property -> null),
                            "Behavior '" + behavior + "' returns new NodeResult(\"continue\", ...) "
                                    + "unconditionally, so 'continue' is the only outcome it can produce");
                    assertTrue(type.outcomes().stream().noneMatch(NodeOutcomeDescriptor::parameterized),
                            "Behavior '" + behavior + "' hard-wires its outcome, so nothing about it is "
                                    + "read from a node property");
                }));
    }

    @Test
    void celDecisionDeclaresItsTwoOutcomesAsPropertyDerived() {
        NodeTypeDescriptor type = descriptor("cel-decision");
        assertTrue(type.outcomes().stream().allMatch(NodeOutcomeDescriptor::parameterized));
        assertEquals(List.of("trueOutcome", "falseOutcome"),
                type.outcomes().stream().map(NodeOutcomeDescriptor::fromProperty).toList(),
                "declaration order is published to the editor and should read true before false");
    }

    /** A node that configures neither property gets the behavior's own defaults. */
    @Test
    void celDecisionResolvesToItsDeclaredDefaultsWhenTheNodeConfiguresNothing() {
        assertEquals(Set.of("true", "false"), descriptor("cel-decision").resolveOutcomes(property -> null));
    }

    /**
     * The case the whole design exists for: custom names, not defaults.
     *
     * <p>A static per-type set would answer "true"/"false" here, which is the wrong answer for this
     * node and would make any consumer built on it wrong exactly where an author needs it most.</p>
     */
    @Test
    void celDecisionResolvesTheAuthorsOwnNames() {
        Map<String, String> configured = Map.of("trueOutcome", "approved", "falseOutcome", "rejected");
        assertEquals(Set.of("approved", "rejected"),
                descriptor("cel-decision").resolveOutcomes(configured::get));
    }

    @Test
    void httpRequestResolvesTheAuthorsOwnNames() {
        Map<String, String> configured = Map.of("successOutcome", "ok", "failureOutcome", "retry");
        assertEquals(Set.of("ok", "retry"), descriptor("http-request").resolveOutcomes(configured::get));
    }

    @Test
    void httpRequestResolvesToItsDeclaredDefaultsWhenTheNodeConfiguresNothing() {
        assertEquals(Set.of("continue", "error"),
                descriptor("http-request").resolveOutcomes(property -> null));
    }

    @Test
    void boundaryGuardDeclaresOnlyItsTwoFixedDecisions() {
        NodeTypeDescriptor type = descriptor("boundary-guard");
        assertEquals(Set.of("continue", "violation"), type.resolveOutcomes(property -> null));
        assertTrue(type.outcomes().stream().noneMatch(NodeOutcomeDescriptor::parameterized));
    }

    /**
     * A blank property resolves to {@code continue}, not to the declared default.
     *
     * <p>This mirrors the run-time path rather than choosing a convention:
     * {@code NodeProperties.string} substitutes a default for an ABSENT property only — it returns the
     * blank for one present and empty — and {@code NodeResult}'s compact constructor then coerces the
     * blank outcome to {@code continue}. So this node really does emit {@code continue} on the true
     * branch, and answering "true" here would name an outcome it cannot produce.</p>
     */
    @Test
    void aBlankOutcomePropertyResolvesToContinueRatherThanToTheDeclaredDefault() {
        Map<String, String> configured = Map.of("trueOutcome", "", "falseOutcome", "no");
        assertEquals(Set.of("continue", "no"), descriptor("cel-decision").resolveOutcomes(configured::get));
    }

    /** Two properties set to one name are one outcome, because one outcome is all nextEdges sees. */
    @Test
    void twoPropertiesNamingTheSameOutcomeCollapseToOne() {
        Map<String, String> configured = Map.of("trueOutcome", "done", "falseOutcome", "done");
        assertEquals(Set.of("done"), descriptor("cel-decision").resolveOutcomes(configured::get));
    }

    /**
     * The declarations are true of the behaviors, checked against what they actually return.
     *
     * <p>Every other case here reads a descriptor against an expectation written in this file. This one
     * closes the loop the other way: it builds each fixed behavior's handler and asserts the outcome it
     * emits is one the descriptor declared. Without it, a wrong declaration and a wrong expectation
     * could agree with each other and the suite would stay green.</p>
     */
    @Test
    void everyDeclaredOutcomeIsOneTheBehaviorCanActuallyProduce() throws Exception {
        var factory = new DelayNodeBehaviorFactory();
        var node = new ai.ravenroot.core.graph.GraphNode("pause",
                ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "delay", Map.of("durationMs", 0));
        var identity = new ai.ravenroot.api.security.SecurityContext("request-outcome", "tenant-a",
                "tester", ai.ravenroot.api.security.PrincipalType.USER, "urn:ravenroot:test");
        var message = new ai.ravenroot.api.execution.NodeMessage(identity, java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), "pause", "payload", Map.of());
        var result = factory.create(node).handle(message).toCompletableFuture()
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(factory.descriptor().resolveOutcomes(property -> null).contains(result.outcome()),
                "delay emitted outcome '" + result.outcome() + "', which its own descriptor does not "
                        + "declare, so the editor would suggest an outcome the node never produces");
    }

    /**
     * A property-derived outcome naming a property the descriptor does not declare is refused.
     *
     * <p>Nothing could resolve it — the editor has no field to read and no default to fall back to — so
     * the outcome would silently disappear from the suggestions rather than be visibly wrong.</p>
     */
    @Test
    void anOutcomeReadFromAnUndeclaredPropertyIsRefusedAtConstruction() {
        var error = assertThrows(IllegalArgumentException.class, () ->
                new NodeTypeDescriptor("x", "X", "General", "", "actor", false, List.of(), Set.of())
                        .withOutcomes(NodeOutcomeDescriptor.fromProperty("missing", "never resolvable")));
        assertTrue(error.getMessage().contains("missing"), error.getMessage());
    }

    /** An outcome is a fixed name or a property reference, never both and never neither. */
    @Test
    void anOutcomeMustBeEitherFixedOrPropertyDerived() {
        assertThrows(IllegalArgumentException.class, () -> new NodeOutcomeDescriptor("a", "b", ""));
        assertThrows(IllegalArgumentException.class, () -> new NodeOutcomeDescriptor("", "", ""));
    }
}
