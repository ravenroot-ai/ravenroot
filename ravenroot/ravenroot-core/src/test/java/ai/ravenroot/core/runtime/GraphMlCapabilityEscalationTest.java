package ai.ravenroot.core.runtime;

import ai.ravenroot.api.ai.AgentRequest;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.api.security.ToolInvocation;
import ai.ravenroot.api.security.ToolPolicy;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-09 rule 2 and the required GraphML escalation test.
 *
 * <p>The original vector: {@code AgentNodeBehaviorFactory} read a tool-policy <em>name</em> from a
 * GraphML node property and put it on {@link AgentRequest} beside the trusted evaluator, with nothing
 * declaring which was authoritative. It was latent — no {@code AgentRuntime} implementation exists in
 * the repository, so nothing consumed it — but the first runtime to resolve a policy by that name
 * would have let a graph choose its own permissions.</p>
 *
 * <h2>The carrier nodes left the core, the vector did not</h2>
 * <p>{@code agent} and {@code llm-prompt} moved out of the core catalog (ADR 0029), so the hostile
 * graph below now targets {@code http-request} — the built-in that still consults an injected {@link
 * ToolPolicy} before it acts. <b>This is a widening, not a substitution of convenience.</b> The claim
 * under test was never about the agent node: it is that a GraphML document cannot nominate the policy
 * that governs it, nor widen the capability set the catalog declares for it, for <em>any</em>
 * behavior. Asserting it on only one affected node understates it,
 * and {@link #noBuiltInCatalogEntryAdvertisesAToolPolicyProperty} now asks it of the whole catalog
 * rather than of one entry.</p>
 *
 * <p>{@link #theAgentRequestHasNoComponentAGraphCouldPopulateWithAPolicyIdentity} is unchanged and
 * still targets {@link AgentRequest}: the record stays in {@code ravenroot-application-api} as the
 * embedding surface (the embedding-surface contract), so an embedder's agent node is exactly where the original
 * vector could reappear. Removing that case because the core no longer ships a node that builds one
 * would have retired the structural guard at the moment its only remaining caller became third-party
 * code this project cannot see.</p>
 */
class GraphMlCapabilityEscalationTest {

    /** The host the hostile node addresses. Nothing may reach it — see {@link #DENYING_TOOL_POLICY}. */
    private static final String HOSTILE_HOST = "escalation-sec09.invalid";

    private static final String HOSTILE_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="k1" for="node" attr.name="kind" attr.type="string"/>
              <key id="k2" for="node" attr.name="behavior" attr.type="string"/>
              <key id="k3" for="node" attr.name="url" attr.type="string"/>
              <key id="k4" for="node" attr.name="method" attr.type="string"/>
              <key id="k5" for="node" attr.name="toolPolicy" attr.type="string"/>
              <key id="k6" for="node" attr.name="capabilities" attr.type="string"/>
              <key id="k7" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="escalation" edgedefault="directed">
                <node id="error"><data key="k1">ERROR</data></node>
                <node id="start"><data key="k1">START</data></node>
                <node id="hostile">
                  <data key="k1">BEHAVIOR</data>
                  <data key="k2">http-request</data>
                  <data key="k3">https://escalation-sec09.invalid/probe</data>
                  <data key="k4">GET</data>
                  <data key="k5">allow-all</data>
                  <data key="k6">network,tools,unrestricted,admin</data>
                </node>
                <node id="end"><data key="k1">END</data></node>
                <edge id="e1" source="start" target="hostile"><data key="k7">continue</data></edge>
                <edge id="e2" source="hostile" target="end"><data key="k7">continue</data></edge>
              </graph>
            </graphml>
            """;

    /**
     * The catalog's own declaration for {@code http-request}. Written out rather than read from the
     * descriptor under test, because deriving the expected value from the subject asserts nothing.
     */
    private static final java.util.Set<String> HTTP_REQUEST_CAPABILITIES =
            java.util.Set.of("network", "credential-reference", "side-effect");

    /**
     * The trusted evaluator, and it refuses.
     *
     * <p>Denying is what makes the case airtight in two directions at once. The graph asked for
     * {@code allow-all}; if that name selected anything, the node would have been permitted and the
     * request would have been attempted. Because the injected evaluator is consulted instead, the run
     * stops with a {@link SecurityException} before {@code HttpRequestNodeBehaviorFactory} builds a
     * request — so no socket is opened to {@link #HOSTILE_HOST}, and the assertion does not depend on
     * that host failing to resolve.</p>
     */
    private static ToolPolicy denyingPolicy(List<ToolInvocation> sink) {
        return invocation -> {
            sink.add(invocation);
            return new ToolDecision(ToolDecision.Disposition.DENY,
                    "the trusted evaluator refused, and it is the only one that was asked", "");
        };
    }

    private static BehaviorEnvironment environment(ToolPolicy policy) {
        return new BehaviorEnvironment(null, null, null, null, ignored -> java.util.Optional.empty(), policy,
                new OutboundHttpPolicy(java.util.Set.of(HOSTILE_HOST), Duration.ofSeconds(1),
                        java.util.Set.of(443), 1024L, 1024L));
    }

    /**
     * A graph declaring its own tool policy and capabilities changes neither, and its node is refused
     * rather than substituted.
     *
     * <p>The outbound policy deliberately <em>permits</em> the host, so the run reaches the tool-policy
     * consult rather than stopping one step earlier on egress. If the graph's {@code
     * toolPolicy=allow-all} were honoured anywhere, this test would fail by succeeding.</p>
     *
     * <p>The event assertions are the substitution hazard negated precisely, carried over from the
     * case this replaces: {@code GraphRunner#fallback} contains a live pass-through placeholder shape,
     * and written that way the traversal would succeed and the graph would proceed as if the node had
     * run.</p>
     */
    @Test
    void aGraphDeclaringItsOwnToolPolicyAndCapabilitiesChangesNeither() {
        var observedInvocations = new ArrayList<ToolInvocation>();
        var registry = BehaviorRegistry.standard(environment(denyingPolicy(observedInvocations)));
        var engine = new DirectEngine();
        var monitor = new ExecutionMonitor();

        try (var manager = GraphManager.readGraphMl(
                     new ByteArrayInputStream(HOSTILE_GRAPH.getBytes(StandardCharsets.UTF_8)));
             var runner = new GraphRunner(manager, engine, registry, monitor)) {
            // Outside the assertThrows lambda: a handler that threw synchronously would escape
            // execute() entirely, leaving the traversal non-terminal with no EXECUTION_FAILED event,
            // and must fail this test rather than satisfy it.
            CompletionStage<GraphExecutionResult> stage = runner.execute(TestIdentities.TENANT_A, "payload");
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> stage.toCompletableFuture().get());

            Throwable rootCause = failure;
            while (rootCause.getCause() != null) {
                rootCause = rootCause.getCause();
            }
            assertInstanceOf(SecurityException.class, rootCause,
                    "the injected evaluator's refusal is what must stop this node: " + rootCause);

            var events = monitor.eventsAfter(0);
            assertTrue(events.stream().anyMatch(event ->
                            event.type() == ExecutionEventType.NODE_FAILED && "hostile".equals(event.nodeId())),
                    "the refused node must publish NODE_FAILED");
            assertFalse(events.stream().anyMatch(event ->
                            event.type() == ExecutionEventType.NODE_COMPLETED && "hostile".equals(event.nodeId())),
                    "a refused node must never complete");
            assertFalse(events.stream().anyMatch(event ->
                            event.type() == ExecutionEventType.NODE_DEFAULTED && "hostile".equals(event.nodeId())),
                    "the refusal must not degrade into the unknown-behavior pass-through");
            // "The stage failed" and "nothing downstream ran" are different facts: absorbIntoJoins
            // can absorb a branch failure into a fan-in and let the traversal succeed. They coincide
            // for this linear graph, and asserting both is what stops a later refactor making only
            // the first one true.
            assertFalse(events.stream().anyMatch(event ->
                            event.type() == ExecutionEventType.NODE_STARTED && "end".equals(event.nodeId())),
                    "nothing downstream of the refused node may run");
        }
        engine.close();

        // The trusted evaluator was consulted, and it is the one composed server-side. Exactly once:
        // more would mean the graph's own name selected a second evaluation somewhere.
        assertEquals(1, observedInvocations.size(),
                "the injected evaluator must be asked exactly once: " + observedInvocations);
        assertEquals("http.request", observedInvocations.getFirst().tool());

        // The declared capabilities are the catalog's, not the graph's. "unrestricted" and "admin"
        // appear nowhere, and the node's own property did not widen anything.
        NodeTypeDescriptor entry = registry.descriptor("http-request").orElseThrow();
        assertEquals(HTTP_REQUEST_CAPABILITIES, entry.capabilities());
        assertFalse(entry.capabilities().contains("unrestricted"));
        assertFalse(entry.capabilities().contains("admin"));
    }

    @Test
    void theHostileGraphSurvivesRoundTripByteForByteBecauseItsPropertiesAreMerelyIgnored() {
        // SEC-09 preserves what it does not interpret. "toolPolicy" and "capabilities" are not part of
        // any behavior's trusted schema, so they are ordinary unknown properties: inert, and still
        // exported exactly as authored. Rejecting them would violate the compatibility requirement.
        byte[] source = HOSTILE_GRAPH.getBytes(StandardCharsets.UTF_8);
        var exported = new java.io.ByteArrayOutputStream();
        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(source))) {
            var hostile = manager.definition().node("hostile");
            assertEquals("allow-all", hostile.properties().get("toolPolicy"));
            assertEquals("network,tools,unrestricted,admin", hostile.properties().get("capabilities"));
            manager.writeGraphMl(exported);
        }
        assertArrayEqualsBytes(source, exported.toByteArray());
    }

    @Test
    void theAgentRequestHasNoComponentAGraphCouldPopulateWithAPolicyIdentity() {
        // Structural, not conventional: there is no syntax on the request for a graph-chosen policy,
        // so an embedder's AgentRuntime cannot resolve one by accident. If a component named like a
        // policy identifier reappears, this fails and whoever added it has to justify it here.
        List<String> suspicious = Arrays.stream(AgentRequest.class.getRecordComponents())
                .filter(component -> component.getType() == String.class)
                .map(RecordComponent::getName)
                .filter(name -> name.toLowerCase(java.util.Locale.ROOT).contains("policy"))
                .toList();

        assertTrue(suspicious.isEmpty(),
                "AgentRequest declares graph-populatable policy identifiers " + suspicious
                        + "; tool authority must arrive only as the injected ToolPolicy evaluator");
        assertTrue(Arrays.stream(AgentRequest.class.getRecordComponents())
                        .anyMatch(component -> component.getType() == ToolPolicy.class),
                "the trusted evaluator must still be present, or nothing governs the agent at all");
    }

    /**
     * No built-in catalog entry advertises a {@code toolPolicy} property, and the check is over the
     * whole catalog.
     *
     * <p>It used to name the {@code agent} entry alone, because that was the entry that had once
     * advertised one. Advertising the property told graph authors they could name their own policy; it
     * was removed rather than documented as ignored. The named entry is gone, and pinning
     * the rule to the catalog rather than to one member is what keeps the guard from leaving with
     * it — the next behavior to advertise such a property fails here.</p>
     */
    @Test
    void noBuiltInCatalogEntryAdvertisesAToolPolicyProperty() {
        var registry = BehaviorRegistry.standard();
        var offenders = registry.descriptors().stream()
                .filter(descriptor -> descriptor.properties().stream()
                        .anyMatch(property -> property.name().toLowerCase(java.util.Locale.ROOT)
                                .contains("toolpolicy")))
                .map(NodeTypeDescriptor::behavior)
                .toList();

        assertTrue(offenders.isEmpty(), "these catalog entries advertise a tool-policy property, which "
                + "tells a graph author they may name the policy that governs them: " + offenders);

        // ANTI-VACUITY: the assertion above is an absence over a list this test does not build, so it
        // would pass just as well against an empty catalog. The genuine operative property of the
        // node the hostile graph targets is what proves the descriptors were really read.
        assertTrue(registry.descriptor("http-request").orElseThrow().properties().stream()
                        .anyMatch(property -> property.name().equals("url")),
                "the genuine operative properties must remain");
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
        assertEquals(new String(expected, StandardCharsets.UTF_8), new String(actual, StandardCharsets.UTF_8));
    }

    /** Synchronous engine that genuinely invokes the node, so the hostile node's path really runs. */
    private static final class DirectEngine implements ai.ravenroot.api.execution.ExecutionEngine {
        private final Map<ai.ravenroot.api.execution.NodeRef, ai.ravenroot.api.execution.RavenNode> nodes =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public String id() {
            return "direct";
        }

        @Override
        public java.util.Set<ai.ravenroot.api.execution.EngineCapability> capabilities() {
            return java.util.Set.of();
        }

        @Override
        public ai.ravenroot.api.execution.Scheduler scheduler() {
            return (delay, task) -> () -> true;
        }

        @Override
        public ai.ravenroot.api.execution.NodeRef spawn(String logicalName,
                                                         ai.ravenroot.api.execution.RavenNode node) {
            var ref = new ai.ravenroot.api.execution.NodeRef(logicalName + "-" + java.util.UUID.randomUUID());
            nodes.put(ref, node);
            return ref;
        }

        @Override
        public CompletionStage<ai.ravenroot.api.execution.NodeResult> send(
                ai.ravenroot.api.execution.NodeRef target, ai.ravenroot.api.execution.NodeMessage message) {
            return nodes.get(target).onMessage(message, new ai.ravenroot.api.execution.NodeContext() {
                @Override
                public ai.ravenroot.api.execution.NodeRef self() {
                    return target;
                }

                @Override
                public ai.ravenroot.api.execution.Scheduler scheduler() {
                    return DirectEngine.this.scheduler();
                }

                @Override
                public ai.ravenroot.api.execution.Mailbox mailbox() {
                    return () -> 0;
                }

                @Override
                public ai.ravenroot.api.execution.CancellationSignal cancellation() {
                    return StubEngineLifecycle.NEVER_CANCELLED;
                }
            });
        }

        @Override
        public CompletionStage<Void> stop(ai.ravenroot.api.execution.NodeRef target) {
            nodes.remove(target);
            return CompletableFuture.completedFuture(null);
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
            nodes.clear();
        }
    }
}
