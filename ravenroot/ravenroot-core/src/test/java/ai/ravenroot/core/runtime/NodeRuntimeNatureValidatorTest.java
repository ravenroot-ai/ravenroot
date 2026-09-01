package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeRuntimeNatureProperty;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The privilege boundary and the residency refusal (ADR 0024 §2).
 *
 * <p>Every refusal below is tested in both directions — the graph that must be refused, and the
 * closest graph that must be admitted — because a validator that refuses everything passes a
 * one-sided suite exactly as happily as one that decides correctly.</p>
 */
class NodeRuntimeNatureValidatorTest {

    private static final String WORKER_ONLY = "worker-only";
    private static final String CHOICE = "worker-or-source";
    private static final String AUTHORITY_BY_DEFAULT = "authority-by-default";

    private final NodeRuntimeNatureValidator validator = new NodeRuntimeNatureValidator(registry());

    // ------------------------------------------------------------------ the default, admitted

    @Test
    void admitsEveryGraphAuthoredBeforeThisIssue() {
        // No node declares a nature, so every one of them is a WORKER and nothing is refused. If this
        // fails, the nature property broke every existing graph.
        assertDoesNotThrow(() -> validator.validate(graph(WORKER_ONLY, Map.of())));
        assertDoesNotThrow(() -> validator.validate(graph(CHOICE, Map.of("owner", "workflow-team"))));
    }

    @Test
    void admitsAnExplicitDeclarationOfTheDefault() {
        assertDoesNotThrow(() -> validator.validate(graph(WORKER_ONLY, nature("WORKER"))));
    }

    @Test
    void admitsAChoiceTheCatalogPermits() {
        assertDoesNotThrow(() -> validator.validate(graph(CHOICE, nature("SOURCE"))));
    }

    // ------------------------------------------------------------------ the escalation refusal

    @Test
    void refusesAGraphEscalatingIntoAnAuthorityTheCatalogWithheld() {
        var failure = refusal(graph(WORKER_ONLY, nature("AUTHORITY")));

        assertEquals(NodeRuntimeNatureException.Reason.NATURE_NOT_PERMITTED, failure.reason());
        assertEquals("AUTHORITY", failure.diagnosticDetail().get("declaredNature"));
        assertEquals("[WORKER]", failure.diagnosticDetail().get("permittedNatures"));
    }

    @Test
    void refusesAGraphEscalatingIntoASourceTheCatalogWithheld() {
        // The other half of ADR 0024's sentence. A behavior the catalog calls a worker cannot be
        // talked into opening a deployment-scoped listener by the document that uses it.
        assertEquals(NodeRuntimeNatureException.Reason.NATURE_NOT_PERMITTED,
                refusal(graph(WORKER_ONLY, nature("SOURCE"))).reason());
    }

    @Test
    void refusesAChoiceOutsideAnExplicitAllowlistEvenWhenTheAllowlistIsWide() {
        // CHOICE permits WORKER and SOURCE. KEYED is neither, and a descriptor that opened two doors
        // did not thereby open the rest.
        assertEquals(NodeRuntimeNatureException.Reason.NATURE_NOT_PERMITTED,
                refusal(graph(CHOICE, nature("KEYED"))).reason());
    }

    // ------------------------------------------------------------------ the unconditional reach

    @Test
    void refusesANatureDeclaredOnANodeThatIsNotABehaviorNode() {
        // BehaviorPropertySchema returns early for these. This validator must not, or a START node
        // carrying AUTHORITY is admitted unchecked.
        GraphDefinition graph = new GraphDefinition(
                List.of(new GraphNode("start", NodeKind.START, null, nature("AUTHORITY")),
                        new GraphNode("probe", NodeKind.BEHAVIOR, WORKER_ONLY, Map.of()),
                        GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "probe"), GraphEdge.to("probe", "end")));

        assertEquals(NodeRuntimeNatureException.Reason.DECLARED_ON_NON_BEHAVIOR_NODE,
                refusal(graph).reason());
    }

    @Test
    void refusesANatureDeclaredByABehaviorTheCatalogDoesNotKnow() {
        // The other early return. The deployment's unknown-behavior mode governs whether an unknown
        // behavior may execute; it has never governed whether one may claim a privilege.
        assertEquals(NodeRuntimeNatureException.Reason.DECLARED_BY_UNCATALOGUED_BEHAVIOR,
                refusal(graph("not-registered", nature("AUTHORITY"))).reason());
    }

    @Test
    void admitsAnUncataloguedBehaviorThatClaimsNothing() {
        // The matching admission. Unknown behaviors remain pass-through when they claim no nature.
        assertDoesNotThrow(() -> validator.validate(graph("not-registered", Map.of("owner", "team"))));
    }

    @Test
    void admitsANonBehaviorNodeThatClaimsNothing() {
        assertDoesNotThrow(() -> validator.validate(new GraphDefinition(
                List.of(new GraphNode("start", NodeKind.START, null, Map.of("owner", "team")),
                        new GraphNode("probe", NodeKind.BEHAVIOR, WORKER_ONLY, Map.of()),
                        GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "probe"), GraphEdge.to("probe", "end")))));
    }

    // ------------------------------------------------------------------ unknown values

    @Test
    void refusesAnUnknownNatureRatherThanDefaultingIt() {
        var failure = refusal(graph(CHOICE, nature("SUPERVISOR")));
        assertEquals(NodeRuntimeNatureException.Reason.UNKNOWN_NATURE, failure.reason());
        assertEquals("SUPERVISOR", failure.diagnosticDetail().get("declaredNature"));
    }

    @Test
    void refusesAMiscasedIdentifier() {
        // "authority" would be admitted and silently demoted to WORKER by any case-insensitive or
        // defaulting parse. It is refused instead.
        assertEquals(NodeRuntimeNatureException.Reason.UNKNOWN_NATURE,
                refusal(graph(CHOICE, nature("source"))).reason());
    }

    @Test
    void refusesABlankDeclaration() {
        assertEquals(NodeRuntimeNatureException.Reason.UNKNOWN_NATURE,
                refusal(graph(CHOICE, nature("  "))).reason());
    }

    // ------------------------------------------------------------------ the residency refusal

    @Test
    void refusesADeployWhoseEffectiveNatureNeedsAnUnimplementedResidency() {
        // Declared by the catalog rather than by the graph, so the graph is blameless and the refusal
        // still fires: it is the effective value that matters, not who supplied it.
        var failure = refusal(graph(AUTHORITY_BY_DEFAULT, Map.of()));
        assertEquals(NodeRuntimeNatureException.Reason.RESIDENCY_NOT_IMPLEMENTED, failure.reason());
        assertEquals("AUTHORITY", failure.diagnosticDetail().get("declaredNature"));
    }

    @Test
    void refusesAPermittedKeyedChoiceForTheSameReason() {
        assertEquals(NodeRuntimeNatureException.Reason.RESIDENCY_NOT_IMPLEMENTED,
                refusal(graph("keyed-allowed", nature("KEYED"))).reason());
    }

    /**
     * The inversion marker. Implementing the resident lifecycles empties
     * {@link NodeRuntimeNature#requiresUnimplementedResidency()}, and this assertion is what turns
     * red — visibly, at the exact line that has to be reconsidered — instead of the refusal quietly
     * being deleted along with its test.
     */
    @Test
    void residencyRefusalIsScopedToExactlyTheUnimplementedNatures() {
        assertTrue(NodeRuntimeNature.AUTHORITY.requiresUnimplementedResidency());
        assertTrue(NodeRuntimeNature.KEYED.requiresUnimplementedResidency());
        assertFalse(NodeRuntimeNature.SOURCE.requiresUnimplementedResidency(),
                "SOURCE has a working implementation and must remain deployable");
        assertDoesNotThrow(() -> validator.validate(graph(CHOICE, nature("SOURCE"))));
    }

    // ------------------------------------------------------------------ the sanitisation policy

    @Test
    void thePublicMessageCarriesNoGraphContent() {
        // A privilege refusal fires exactly when the server has decided the submission is not
        // trustworthy, so echoing the submission back is the disclosure GraphMlRejection exists to
        // prevent. The node id, the behavior name and the declared value are the submitter's text.
        var failure = refusal(graph(WORKER_ONLY, nature("AUTHORITY")));
        String message = failure.getMessage();

        assertTrue(message.contains(NodeRuntimeNatureProperty.NAME), message);
        assertTrue(message.contains("may never"), message);
        assertFalse(message.contains("probe"), "the node id reached the public message: " + message);
        assertFalse(message.contains(WORKER_ONLY), "the behavior name reached the public message: " + message);
        assertFalse(message.contains("AUTHORITY"), "the declared value reached the public message: " + message);
    }

    @Test
    void theDiagnosticChannelCarriesEverythingTheOperatorNeeds() {
        var failure = refusal(graph(WORKER_ONLY, nature("AUTHORITY")));

        assertEquals("probe", failure.diagnosticDetail().get("nodeId"));
        assertEquals(WORKER_ONLY, failure.diagnosticDetail().get("behavior"));
        assertEquals("AUTHORITY", failure.diagnosticDetail().get("declaredNature"));
        assertEquals("WORKER", failure.diagnosticDetail().get("defaultNature"));
    }

    // ------------------------------------------------------------------ wiring

    @Test
    void graphRunnerRefusesAnEscalatingGraphBeforeSpawningAnySingleActor() {
        // The unit tests above prove the validator decides correctly; this proves it is consulted, and
        // consulted early enough to matter. "Before any actor exists" is the whole claim: on the
        // deployment path the runner is built before DefaultGraphDeployment starts any inbound source,
        // so a refusal here precedes both the actors and the sources.
        var engine = new SpawnCountingEngine();
        var graph = ai.ravenroot.core.graph.GraphManager.from(graph(WORKER_ONLY, nature("AUTHORITY")));

        var failure = assertThrows(NodeRuntimeNatureException.class,
                () -> new GraphRunner(graph, engine, registry(), new ExecutionMonitor()));

        assertEquals(NodeRuntimeNatureException.Reason.NATURE_NOT_PERMITTED, failure.reason());
        assertEquals(0, engine.spawns, "no actor may be spawned for a graph that fails nature validation");
        graph.close();
        engine.close();
    }

    @Test
    void graphRunnerStillBuildsAGraphThatDeclaresAPermittedNature() {
        var engine = new SpawnCountingEngine();
        var graph = ai.ravenroot.core.graph.GraphManager.from(graph(CHOICE, nature("SOURCE")));

        try (var runner = new GraphRunner(graph, engine, registry(), new ExecutionMonitor())) {
            // Inverted by ADR 0024 §3, and the new number says more than the old one did. This
            // asserted 3 -- every node spawned at construction -- which held only while nature made no
            // difference to residency. It now asserts exactly 1: the node that declared SOURCE is
            // resident, and the ordinary WORKER nodes around it are not created until a traversal
            // reaches them. So this case now proves the declared nature actually selects a lifecycle,
            // which is the property runtime-nature selection exists to make true, rather than merely proving the
            // graph was accepted.
            assertEquals(1, engine.spawns,
                    "only the node declaring SOURCE is resident; the WORKER nodes are demand-driven");
            assertTrue(runner != null);
        }
        graph.close();
        engine.close();
    }

    // ------------------------------------------------------------------ fixtures

    private NodeRuntimeNatureException refusal(GraphDefinition graph) {
        return assertThrows(NodeRuntimeNatureException.class, () -> validator.validate(graph));
    }

    private static Map<String, Object> nature(String value) {
        return Map.of(NodeRuntimeNatureProperty.NAME, value);
    }

    private static GraphDefinition graph(String behavior, Map<String, Object> properties) {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("probe", NodeKind.BEHAVIOR, behavior, properties),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "probe"), GraphEdge.to("probe", "end")));
    }

    private static BehaviorRegistry registry() {
        return new BehaviorRegistry()
                // Says nothing about nature: every descriptor in the real catalog looks like this.
                .registerFactory(factory(new NodeTypeDescriptor(WORKER_ONLY, "Worker only", "Test",
                        "d", "actor", false, List.of(), Set.of())))
                .registerFactory(factory(new NodeTypeDescriptor(CHOICE, "Choice", "Test", "d", "actor",
                        false, List.of(), Set.of(), NodeRuntimeNature.WORKER,
                        Set.of(NodeRuntimeNature.WORKER, NodeRuntimeNature.SOURCE))))
                .registerFactory(factory(new NodeTypeDescriptor(AUTHORITY_BY_DEFAULT, "Authority", "Test",
                        "d", "actor", false, List.of(), Set.of(), NodeRuntimeNature.AUTHORITY,
                        Set.of(NodeRuntimeNature.AUTHORITY))))
                .registerFactory(factory(new NodeTypeDescriptor("keyed-allowed", "Keyed", "Test", "d",
                        "actor", false, List.of(), Set.of(), NodeRuntimeNature.WORKER,
                        Set.of(NodeRuntimeNature.WORKER, NodeRuntimeNature.KEYED))));
    }

    private static NodeBehaviorFactory factory(NodeTypeDescriptor descriptor) {
        return new NodeBehaviorFactory() {
            @Override
            public NodeTypeDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public NodeHandler create(GraphNode node) {
                return message -> java.util.concurrent.CompletableFuture.completedFuture(
                        ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()));
            }
        };
    }

    private static final class SpawnCountingEngine implements ai.ravenroot.api.execution.ExecutionEngine {
        private int spawns;

        @Override
        public String id() {
            return "spawn-counting";
        }

        @Override
        public Set<ai.ravenroot.api.execution.EngineCapability> capabilities() {
            return Set.of();
        }

        @Override
        public ai.ravenroot.api.execution.Scheduler scheduler() {
            return (delay, task) -> () -> true;
        }

        @Override
        public ai.ravenroot.api.execution.NodeRef spawn(String logicalName,
                                                         ai.ravenroot.api.execution.RavenNode node) {
            spawns++;
            return new ai.ravenroot.api.execution.NodeRef(logicalName);
        }

        @Override
        public java.util.concurrent.CompletionStage<ai.ravenroot.api.execution.NodeResult> send(
                ai.ravenroot.api.execution.NodeRef target, ai.ravenroot.api.execution.NodeMessage message) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()));
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> stop(ai.ravenroot.api.execution.NodeRef target) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public ai.ravenroot.api.execution.EngineState state() {
            return ai.ravenroot.api.execution.EngineState.RUNNING;
        }

        @Override
        public java.util.Optional<ai.ravenroot.api.execution.NodeStatus> status(
                ai.ravenroot.api.execution.NodeRef target) {
            return java.util.Optional.of(StubEngineLifecycle.running(target));
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> cancel(ai.ravenroot.api.execution.NodeRef target) {
            return stop(target);
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> drain() {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
        }
    }
}
