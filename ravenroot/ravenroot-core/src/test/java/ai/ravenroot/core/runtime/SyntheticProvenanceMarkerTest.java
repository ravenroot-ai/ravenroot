package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.EngineState;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.Mailbox;
import ai.ravenroot.api.execution.NodeContext;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeStatus;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.execution.Scheduler;
import ai.ravenroot.core.graph.GraphNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The REG-03 evidence: the provenance marker, observed where a hostile graph would attack it.
 *
 * <h2>Why this class names the contract in string literals</h2>
 * <p>The contract requires the suppression and falsification tests to <strong>fail against the
 * pre-modification implementation</strong>, which is what distinguishes a meaningful test from one
 * that merely restates the code it was written beside. A test that referenced
 * {@code SyntheticProvenance} could not be compiled against that tree at all, and a compile error is
 * weaker evidence than a counted failure. So this class depends on nothing REG-03 introduced: it
 * spells the attribute key and the marker's fields as literals and asserts on plain maps, exactly as
 * an integrator reading the published contract would.</p>
 *
 * <h2>Why the generative node is a stub</h2>
 * <p>No node the core ships can produce model-generated content, so there is nothing shipped to run.
 * Previously, {@code llm-prompt} and {@code agent} had no registered {@code ModelProvider} or
 * {@code AgentRuntime} and refused when reached; those two node types are now in a plugin bundle
 * rather than the core (ADR 0029). The stub is therefore
 * not a convenience: it is the only way to exercise the runtime marking path from inside this module,
 * and it is now also the accurate shape, because a generative descriptor reaching this runtime is by
 * construction one the core did not write.</p>
 *
 * <p>That makes it important that the stub does not quietly become the thing under test. It declares
 * a catalog capability and nothing else, and {@code SyntheticProvenanceCatalogTest} separately pins
 * both halves against the real catalog: that no shipped descriptor is generative, and that the
 * recognition rule still answers {@code true} for one that declares the capability. The stub stands
 * in for the bundle; it does not stand in for the catalog.</p>
 */
class SyntheticProvenanceMarkerTest {

    private static final String MARKER = "ravenroot.provenance.synthetic";
    private static final String CONTRACT = "ravenroot.provenance/1";
    private static final String GENERATED_TEXT = "a sentence the model produced";

    /** What a generative node type looks like to the catalog: a declared capability, nothing more. */
    private record GenerativeStubFactory(String behavior, String capability, Object output)
            implements NodeBehaviorFactory {

        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor(behavior, behavior, "AI", "Test stand-in for a generative node type.",
                    "agent", false, List.of(), Set.of(capability, "external-provider"));
        }

        @Override
        public NodeHandler create(GraphNode node) {
            // Deliberately returns no attributes at all: if the marker appears it was derived from
            // the descriptor by the runner, and cannot have been contributed by the behavior.
            return message -> CompletableFuture.completedFuture(NodeResult.continueWith(output));
        }
    }

    @Test
    void aNodeTheCatalogDeclaresGenerativeHasItsOutputMarked() {
        var observed = observers();
        var registry = new BehaviorRegistry()
                .registerFactory(new GenerativeStubFactory("stub-llm", "ai", GENERATED_TEXT));
        record(registry, observed, "downstream");

        runLinear(registry, List.of("generator:stub-llm", "downstream:downstream"), "human question");

        Map<String, Object> marker = markerAt(observed, "downstream");
        assertNotNull(marker, "content produced by a node the catalog declares generative must be marked");
        assertEquals(CONTRACT, marker.get("contract"));
        assertEquals("stub-llm", marker.get("behavior"), "the marker records the producing node type");
        assertEquals(List.of("ai"), marker.get("capabilities"),
                "the marker records the capability that made the node generative, not a node name");
        assertEquals("generator", marker.get("nodeId"));
        assertTrue(String.valueOf(marker.get("contentDigest")).startsWith("sha256:"),
                "the marker must be bound to the content it describes");
    }

    @Test
    void theAgenticCapabilityMarksAsWellSoTheRuleIsTheCapabilityAndNotOneName() {
        var observed = observers();
        var registry = new BehaviorRegistry()
                .registerFactory(new GenerativeStubFactory("stub-agent", "agentic", GENERATED_TEXT));
        record(registry, observed, "downstream");

        runLinear(registry, List.of("generator:stub-agent", "downstream:downstream"), "human question");

        Map<String, Object> marker = markerAt(observed, "downstream");
        assertNotNull(marker, "a node declaring 'agentic' is generative for marking purposes");
        assertEquals(List.of("agentic"), marker.get("capabilities"));
    }

    @Test
    void aNodeTheCatalogDoesNotDeclareGenerativeProducesNoMarker() {
        var observed = observers();
        var registry = new BehaviorRegistry()
                .register("deterministic", message ->
                        CompletableFuture.completedFuture(NodeResult.continueWith("computed, not generated")));
        record(registry, observed, "downstream");

        runLinear(registry, List.of("worker:deterministic", "downstream:downstream"), "input");

        assertFalse(message(observed, "downstream").attributes().containsKey(MARKER),
                "deterministic output must not carry a provenance marker");
    }

    /**
     * Suppression: a node forwards generated content unchanged while returning no attributes at all.
     *
     * <p>This is the cheapest attack a graph author has — the marker travels in a map the node
     * controls, so simply not repeating it would erase it. It must not, because the surviving marker
     * is recomputed by the runner from the map it wrote for the inbound hop rather than read back out
     * of the result.</p>
     */
    @Test
    void aHostileGraphCannotSuppressTheMarkerOnContentItForwardsUnchanged() {
        var observed = observers();
        var registry = new BehaviorRegistry()
                .registerFactory(new GenerativeStubFactory("stub-llm", "ai", GENERATED_TEXT))
                // Returns Map.of(): every attribute the runner produced is dropped on the floor.
                .register("suppressor", message -> CompletableFuture.completedFuture(
                        new NodeResult("continue", message.payload(), Map.of())));
        record(registry, observed, "downstream");

        runLinear(registry, List.of("generator:stub-llm", "hostile:suppressor", "downstream:downstream"),
                "human question");

        NodeMessage delivered = message(observed, "downstream");
        assertEquals(GENERATED_TEXT, delivered.payload(), "the hostile node forwarded the content unchanged");
        Map<String, Object> marker = markerAt(observed, "downstream");
        assertNotNull(marker, "a node must not be able to erase provenance from content it forwards");
        assertEquals("stub-llm", marker.get("behavior"));
    }

    /**
     * Falsification, part one: a graph with no generative node fabricates a marker.
     *
     * <p>The forged value is well-formed — it names the contract and a plausible digest — because a
     * malformed one would be refused by shape rather than by authority, and it is authority that is
     * being tested.</p>
     */
    @Test
    void aHostileGraphCannotFabricateAMarkerOverContentNoModelGenerated() {
        var observed = observers();
        var forged = Map.<String, Object>of(
                "contract", CONTRACT,
                "nodeId", "forger",
                "behavior", "stub-llm",
                "capabilities", List.of("ai"),
                "contentDigest", "sha256:0000000000000000000000000000000000000000000000000000000000000000");
        var registry = new BehaviorRegistry()
                .register("forger", message -> {
                    var hostile = new LinkedHashMap<String, Object>(message.attributes());
                    hostile.put(MARKER, forged);
                    // Case variation: an XML-strict reader sees a different key, a human does not.
                    hostile.put("RavenRoot.Provenance.Synthetic", forged);
                    return CompletableFuture.completedFuture(
                            new NodeResult("continue", "written by a person", hostile));
                });
        record(registry, observed, "downstream");

        runLinear(registry, List.of("forger:forger", "downstream:downstream"), "input");

        NodeMessage delivered = message(observed, "downstream");
        assertFalse(delivered.attributes().containsKey(MARKER),
                "a node must have no syntax for claiming provenance it does not have");
        assertTrue(delivered.attributes().keySet().stream()
                        .noneMatch(key -> key.toLowerCase(java.util.Locale.ROOT)
                                .startsWith("ravenroot.provenance.")),
                "the whole provenance namespace is the runtime's, in any casing");
    }

    /**
     * Falsification, part two: a node replays a <em>genuine</em> marker onto content it substituted.
     *
     * <p>Strictly harder than fabricating one, because nothing about the value is wrong — it is the
     * marker the runtime itself minted one hop earlier. What makes it a lie is the content it is
     * attached to, which is why the binding and not the value is what the runtime checks.</p>
     */
    @Test
    void aHostileGraphCannotReplayAGenuineMarkerOntoContentItSubstituted() {
        var observed = observers();
        var captured = new ArrayList<Object>();
        var registry = new BehaviorRegistry()
                .registerFactory(new GenerativeStubFactory("stub-llm", "ai", GENERATED_TEXT))
                .register("replayer", message -> {
                    var replayed = new LinkedHashMap<String, Object>(message.attributes());
                    captured.add(replayed.get(MARKER));
                    return CompletableFuture.completedFuture(
                            new NodeResult("continue", "written by a person", replayed));
                });
        record(registry, observed, "downstream");

        runLinear(registry, List.of("generator:stub-llm", "hostile:replayer", "downstream:downstream"),
                "human question");

        // Without this the test would be vacuous: "no marker downstream" is trivially true of an
        // implementation that mints none, so the replay must be shown to have had something to steal.
        assertEquals(1, captured.size(), "the hostile node must have run exactly once");
        assertNotNull(captured.getFirst(), "the hostile node must have captured a genuine marker to replay");

        NodeMessage delivered = message(observed, "downstream");
        assertEquals("written by a person", delivered.payload());
        assertFalse(delivered.attributes().containsKey(MARKER),
                "a marker minted for one content value must not be transferable to another");
    }

    /**
     * The explicit propagation semantics: the marker follows the content,
     * not the execution.
     *
     * <p>Both halves are asserted in one test on purpose. "No marker downstream" is vacuously true of
     * an implementation that never marks anything, so on its own it would be evidence of nothing; it
     * only means something next to the assertion that the generated content upstream <em>was</em>
     * marked in the same run.</p>
     */
    @Test
    void theMarkerFollowsTheContentAndNotTheExecution() {
        var observed = observers();
        var registry = new BehaviorRegistry()
                .registerFactory(new GenerativeStubFactory("stub-llm", "ai", GENERATED_TEXT))
                // Exactly NodeMessage.next(...) semantics: attributes forward verbatim, payload replaced.
                .register("substituter", message -> CompletableFuture.completedFuture(
                        new NodeResult("continue", "deterministic replacement", message.attributes())));
        record(registry, observed, "afterGenerator");
        record(registry, observed, "afterSubstitution");

        runLinear(registry, List.of("generator:stub-llm", "seen-generated:afterGenerator",
                "hostile:substituter", "seen-substituted:afterSubstitution"), "human question");

        NodeMessage generated = message(observed, "afterGenerator");
        assertEquals(GENERATED_TEXT, generated.payload());
        assertTrue(generated.attributes().containsKey(MARKER),
                "the model's own output must be marked, or the negative half below proves nothing");

        NodeMessage substituted = message(observed, "afterSubstitution");
        assertEquals("deterministic replacement", substituted.payload());
        assertFalse(substituted.attributes().containsKey(MARKER),
                "attributes survive payload substitution, so a per-execution marker would ride onto "
                        + "content the model never produced");

        // The hazard itself, at the type level: NodeMessage.next(...) does carry the attribute map
        // across a substituted payload. The marker is safe despite that, not because of it.
        NodeMessage routed = generated.next(UUID.randomUUID(), UUID.randomUUID(), "elsewhere", "something else");
        assertTrue(routed.attributes().containsKey(MARKER),
                "next(...) copies attributes verbatim — this is why marking cannot be per-execution");
        assertEquals("something else", routed.payload());
    }

    /**
     * The other half of the boundary: the marker cannot enter from the graph document either.
     *
     * <p>This one already passed before REG-03 — {@code ReservedGraphProperties} refuses the whole
     * {@code ravenroot.} prefix at ingest — and it is asserted here because that is the guarantee the
     * marker's choice of namespace <em>relies</em> on. A later widening of what graphs may declare
     * would break provenance silently, and this is what would notice.</p>
     */
    @Test
    void graphContentCannotDeclareTheMarkerAsAProperty() {
        var registry = new BehaviorRegistry()
                .register("deterministic", message ->
                        CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())));
        String poisoned = """
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="node-behavior" for="node" attr.name="behavior" attr.type="string"/>
                  <key id="claim" for="node" attr.name="ravenroot.provenance.synthetic" attr.type="string"/>
                  <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
                  <graph id="poisoned" edgedefault="directed">
                    <node id="error"><data key="node-kind">ERROR</data></node>
                    <node id="start"><data key="node-kind">START</data></node>
                    <node id="worker">
                      <data key="node-kind">BEHAVIOR</data>
                      <data key="node-behavior">deterministic</data>
                      <data key="claim">generated-by-a-model</data>
                    </node>
                    <node id="end"><data key="node-kind">END</data></node>
                    <edge id="e1" source="start" target="worker"><data key="edge-outcome">continue</data></edge>
                    <edge id="e2" source="worker" target="end"><data key="edge-outcome">continue</data></edge>
                  </graph>
                </graphml>
                """;

        try (var harness = new Harness(registry)) {
            assertThrows(RuntimeException.class, () -> harness.run(poisoned, "input"),
                    "a graph declaring the provenance namespace must be refused at ingest");
        }
    }

    /** A graph that is accepted today is still accepted, and still produces its result. */
    @Test
    void introducingTheMarkerRejectsNothingThatWasAcceptedBefore() {
        var observed = observers();
        var registry = new BehaviorRegistry()
                .register("deterministic", message -> CompletableFuture.completedFuture(
                        new NodeResult("continue", message.payload(),
                                Map.of("business.attribute", "preserved", "ravenroot.logged", true))));
        record(registry, observed, "downstream");

        runLinear(registry, List.of("worker:deterministic", "downstream:downstream"), "input");

        NodeMessage delivered = message(observed, "downstream");
        assertEquals("input", delivered.payload());
        assertEquals("preserved", delivered.attributes().get("business.attribute"),
                "ordinary attributes must be untouched by provenance handling");
        assertEquals(true, delivered.attributes().get("ravenroot.logged"),
                "other runtime-owned attributes must be untouched too");
    }

    // ---------------------------------------------------------------- harness

    private static Map<String, NodeMessage> observers() {
        return new LinkedHashMap<>();
    }

    /** Registers an observing behavior that records the message delivered to it, keyed by its name. */
    private static void record(BehaviorRegistry registry, Map<String, NodeMessage> sink, String behavior) {
        registry.register(behavior, message -> {
            sink.put(behavior, message);
            return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        });
    }

    private static NodeMessage message(Map<String, NodeMessage> observed, String behavior) {
        NodeMessage delivered = observed.get(behavior);
        if (delivered == null) {
            throw new AssertionError("observer '" + behavior + "' never ran; observed " + observed.keySet());
        }
        return delivered;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> markerAt(Map<String, NodeMessage> observed, String behavior) {
        Object value = message(observed, behavior).attributes().get(MARKER);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    /** Runs {@code start -> steps... -> end}, each step given as {@code nodeId:behavior}. */
    private static void runLinear(BehaviorRegistry registry, List<String> steps, Object payload) {
        var xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="node-behavior" for="node" attr.name="behavior" attr.type="string"/>
                  <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
                  <graph id="provenance" edgedefault="directed">
                    <node id="error"><data key="node-kind">ERROR</data></node>
                    <node id="start"><data key="node-kind">START</data></node>
                """);
        var ids = new ArrayList<String>();
        ids.add("start");
        for (String step : steps) {
            String id = step.substring(0, step.indexOf(':'));
            String behavior = step.substring(step.indexOf(':') + 1);
            ids.add(id);
            xml.append("    <node id=\"").append(id).append("\"><data key=\"node-kind\">BEHAVIOR</data>")
                    .append("<data key=\"node-behavior\">").append(behavior).append("</data></node>\n");
        }
        ids.add("end");
        xml.append("    <node id=\"end\"><data key=\"node-kind\">END</data></node>\n");
        for (int i = 0; i < ids.size() - 1; i++) {
            xml.append("    <edge id=\"e").append(i).append("\" source=\"").append(ids.get(i))
                    .append("\" target=\"").append(ids.get(i + 1))
                    .append("\"><data key=\"edge-outcome\">continue</data></edge>\n");
        }
        xml.append("  </graph>\n</graphml>\n");

        try (var harness = new Harness(registry)) {
            harness.run(xml.toString(), payload);
        }
    }

    private static final class Harness implements AutoCloseable {
        private final ai.ravenroot.core.persistence.InMemoryExecutionStore store =
                new ai.ravenroot.core.persistence.InMemoryExecutionStore();
        private final DefaultRavenrootApplication application;

        Harness(BehaviorRegistry registry) {
            this.application = new DefaultRavenrootApplication(new DirectEngine(), new ExecutionMonitor(), registry,
                    new ai.ravenroot.core.programming.InMemoryArtifactRegistry(),
                    new ai.ravenroot.core.programming.DisabledProgramRuntime(),
                    ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), store);
        }

        void run(String graphMl, Object payload) {
            application.startGraphMl(TestIdentities.TENANT_A, UUID.randomUUID(),
                    new ByteArrayInputStream(graphMl.getBytes(StandardCharsets.UTF_8)), payload);
        }

        @Override
        public void close() {
            application.close();
            store.close();
        }
    }

    /** Synchronous engine that genuinely invokes the node, so propagation is observable in-thread. */
    private static final class DirectEngine implements ExecutionEngine {
        private final Map<NodeRef, RavenNode> nodes = new ConcurrentHashMap<>();

        @Override
        public String id() {
            return "direct";
        }

        @Override
        public Set<EngineCapability> capabilities() {
            return Set.of();
        }

        @Override
        public Scheduler scheduler() {
            return (delay, task) -> () -> true;
        }

        @Override
        public NodeRef spawn(String logicalName, RavenNode node) {
            var ref = new NodeRef(logicalName + "-" + UUID.randomUUID());
            nodes.put(ref, node);
            return ref;
        }

        @Override
        public CompletionStage<NodeResult> send(NodeRef target, NodeMessage message) {
            RavenNode node = nodes.get(target);
            return node == null
                    ? CompletableFuture.failedFuture(new IllegalArgumentException("unknown node " + target.value()))
                    : node.onMessage(message, context(target));
        }

        @Override
        public CompletionStage<Void> stop(NodeRef target) {
            nodes.remove(target);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public EngineState state() {
            return EngineState.RUNNING;
        }

        @Override
        public Optional<NodeStatus> status(NodeRef target) {
            return Optional.of(StubEngineLifecycle.running(target));
        }

        @Override
        public CompletionStage<Void> cancel(NodeRef target) {
            return stop(target);
        }

        @Override
        public CompletionStage<Void> drain() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            nodes.clear();
        }

        private NodeContext context(NodeRef ref) {
            return new NodeContext() {
                @Override
                public NodeRef self() {
                    return ref;
                }

                @Override
                public Scheduler scheduler() {
                    return DirectEngine.this.scheduler();
                }

                @Override
                public Mailbox mailbox() {
                    return () -> 0;
                }

                @Override
                public CancellationSignal cancellation() {
                    return StubEngineLifecycle.NEVER_CANCELLED;
                }
            };
        }
    }
}
