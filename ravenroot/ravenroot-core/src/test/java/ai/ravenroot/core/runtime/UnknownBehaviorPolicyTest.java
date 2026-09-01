package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-09: the decision point exists and is consulted, and the default is unchanged.
 *
 * <p>{@code RAVENROOT_UNKNOWN_BEHAVIOR=refuse} selects the refusing production mode. ADR 0003,
 * ADR 0006, the README and
 * threat-model invariant INV-04 agree that the rule is conditional rather than
 * unconditional. {@code UnknownBehaviorRefusalSelectionTest} in {@code ravenroot-server}
 * owns the end-to-end proof that selecting it actually fails a run.
 *
 * <p>The default remains pass-through, and that is what this class guards.
 * The two halves here remain equally load-bearing: the seam must genuinely be consulted, and an
 * unconfigured deployment must genuinely still pass through. Asserting only the first would let a
 * change that quietly flipped the default look correct — which, now that the switch exists, is a
 * more reachable mistake than it was when nothing could select anything.
 */
class UnknownBehaviorPolicyTest {

    private static final String UNKNOWN = "not-in-the-catalog";
    private static final String KNOWN = "known-behavior";

    private final JoinTestEngine engine = new JoinTestEngine();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    /** Records every behavior name the runtime asks about, and admits all of them. */
    private static final class RecordingPolicy implements UnknownBehaviorPolicy {
        private final List<String> asked = new CopyOnWriteArrayList<>();

        @Override
        public boolean admits(String behavior) {
            asked.add(behavior);
            return true;
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void theSeamIsConsultedForABehaviorTheTrustedCatalogDoesNotContain() {
        var policy = new RecordingPolicy();

        try (var runner = runnerWith(graphNaming(UNKNOWN), new BehaviorRegistry(),
                new ExecutionMonitor(), policy)) {
            assertTrue(policy.asked.contains(UNKNOWN),
                    "the runtime must ask the policy about a behavior its catalog does not contain. "
                            + "A seam nothing consults is not a seam -- it is dead code that will read "
                            + "as a working control to the next person. Asked: " + policy.asked);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void theSeamIsNotConsultedForABehaviorTheTrustedCatalogDoesContain() {
        var policy = new RecordingPolicy();
        var registry = new BehaviorRegistry().register(KNOWN,
                message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())));

        try (var runner = runnerWith(graphNaming(KNOWN), registry, new ExecutionMonitor(), policy)) {
            assertEquals(List.of(), policy.asked,
                    "a registered behavior is not an unknown one and must never reach this question. "
                            + "Consulting it here would put a policy decision in front of every node "
                            + "and would let the policy override the trusted catalog");
        }
    }

    /**
     * The half that keeps four documents true. A runner built the way every shipped caller builds one
     * must still execute an unknown behavior as the observable pass-through.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void theShippedDefaultStillExecutesAnUnknownBehaviorAsThePassThrough() throws Exception {
        var monitor = new ExecutionMonitor();
        Throwable executionFailure = null;

        try (var runner = new GraphRunner(GraphManager.from(graphNaming(UNKNOWN)), engine,
                new BehaviorRegistry(), monitor)) {
            try {
                runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture()
                        .get(20, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException failed) {
                // Captured rather than propagated so the assertions below can say WHICH document
                // stopped being true. A bare ExecutionException here would report only that
                // something threw, which is the least useful form of this failure.
                executionFailure = failed.getCause();
            }
        }

        List<ExecutionEvent> events = monitor.eventsAfter(0);
        assertNull(executionFailure,
                "a traversal over an unknown behavior must still succeed by default. This is the "
                        + "shipped path, so if it refuses, ADR 0003, ADR 0006, the README and INV-04 "
                        + "are all false under the shipped default contract: " + executionFailure);
        assertTrue(hasEvent(events, ExecutionEventType.NODE_DEFAULTED, "probe"),
                "the default must remain the pass-through those four documents describe. If this "
                        + "fails, the fail-closed branch became reachable without the mode that is "
                        + "supposed to select it");
        assertFalse(hasEvent(events, ExecutionEventType.NODE_FAILED, "probe"),
                "nothing may refuse an unknown behavior by default");
    }

    /**
     * The refusal is shaped to match the CORE-05 refusal exactly, because an operator
     * who has learned one unconfigured-thing failure should not have to learn a second one.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aRefusedUnknownBehaviorFailsTheNodeWhenTheTraversalReachesIt() throws Exception {
        var monitor = new ExecutionMonitor();

        try (var runner = runnerWith(graphNaming(UNKNOWN), new BehaviorRegistry(), monitor,
                UnknownBehaviorPolicy.refuse())) {
            // Obtained outside the assertThrows below so that a refusal escaping execute() itself
            // would surface here rather than being counted as the expected failure.
            //
            // HONEST LIMIT, measured: this does NOT discriminate between returning a failed stage
            // and throwing synchronously from the handler. Rewriting the refusal as a synchronous
            // throw leaves all six tests green, because JoinTestEngine dispatches onMessage on a
            // pool and turns any throw into a failed stage. Distinguishing the two needs a
            // synchronous engine -- the DirectEngine in UnconfiguredAdapterBindingRefusalTest is
            // written for exactly that and documents itself as "a synchronous throw is not
            // absorbed".
            // Recorded rather than implied, so nobody reads this line as protection it does not give.
            var stage = runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture();

            var thrown = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> stage.get(20, TimeUnit.SECONDS),
                    "a refused node must fail the traversal");
            Throwable rootCause = rootCauseOf(thrown);

            assertInstanceOf(IllegalStateException.class, rootCause,
                    "the refusal must be its own condition, the same one CORE-05 produces for a "
                            + "runtime id that resolves to nothing -- not a generic argument failure");
            assertFalse(rootCause instanceof SecurityException,
                    "a SecurityException would say a policy was consulted and denied, which is a "
                            + "different fact from 'this deployment does not have that behavior'");
            assertFalse(rootCause instanceof IllegalArgumentException,
                    "BehaviorPropertyException extends IllegalArgumentException, so an "
                            + "IllegalArgumentException here would be indistinguishable from a "
                            + "schema violation and would go green for the wrong reason");
            assertTrue(rootCause.getMessage().contains("probe"),
                    "the failure must name the refusing node: " + rootCause.getMessage());
            assertTrue(rootCause.getMessage().contains(UNKNOWN),
                    "the failure must name the behavior nobody installed: " + rootCause.getMessage());
        }

        List<ExecutionEvent> events = monitor.eventsAfter(0);
        assertTrue(hasEvent(events, ExecutionEventType.NODE_FAILED, "probe"),
                "a refused node reports NODE_FAILED");
        assertFalse(hasEvent(events, ExecutionEventType.NODE_COMPLETED, "probe"),
                "a refused node must never complete");
        assertFalse(hasEvent(events, ExecutionEventType.NODE_DEFAULTED, "probe"),
                "refusing must never look like the pass-through it is refusing to perform. "
                        + "NODE_DEFAULTED is the signal that an unknown behavior RAN as a "
                        + "pass-through, and emitting it here would tell an operator the opposite "
                        + "of what happened");
        assertFalse(hasEvent(events, ExecutionEventType.NODE_STARTED, "end"),
                "nothing downstream of the refused node may run. 'The stage failed' and 'nothing "
                        + "downstream ran' are different facts and both have to hold");
    }

    /** The graph must still construct: CORE-05 moved this class of refusal to execution on purpose. */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aRefusingPolicyStillLetsTheGraphConstructSoItStaysInspectable() {
        try (var runner = runnerWith(graphNaming(UNKNOWN), new BehaviorRegistry(),
                new ExecutionMonitor(), UnknownBehaviorPolicy.refuse())) {
            assertNotNull(runner, "a graph naming a behavior this deployment lacks must still "
                    + "construct, or it could not be opened in the editor to be fixed");
        }
    }

    /**
     * The mirror of the rule {@code GraphMlCapabilityEscalationTest} pins for agent runtimes: a graph
     * admitted once must not be armed retroactively, and a graph refused once must stay refused.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void registeringTheBehaviorAfterConstructionDoesNotRetroactivelyArmTheNode() throws Exception {
        var registry = new BehaviorRegistry();
        var monitor = new ExecutionMonitor();

        try (var runner = runnerWith(graphNaming(UNKNOWN), registry, monitor,
                UnknownBehaviorPolicy.refuse())) {
            // The registry is a live map with a public register and no unregister, so a
            // per-invocation lookup would silently make an already-refused graph live.
            registry.register(UNKNOWN, message ->
                    CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())));

            var stage = runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture();
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> stage.get(20, TimeUnit.SECONDS),
                    "arming the behavior after the graph was composed must not revive the node");
        }

        assertFalse(hasEvent(monitor.eventsAfter(0), ExecutionEventType.NODE_COMPLETED, "probe"),
                "the late registration must not have let the node run");
    }

    private static Throwable rootCauseOf(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private GraphRunner runnerWith(GraphDefinition graph, BehaviorRegistry registry,
                                   ExecutionMonitor monitor, UnknownBehaviorPolicy policy) {
        return new GraphRunner(GraphManager.from(graph), engine, registry, monitor,
                ExecutionIdentitySource.randomUuids(), null, Clock.systemUTC(),
                Duration.ofSeconds(5), policy);
    }

    private static GraphDefinition graphNaming(String behavior) {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                GraphNode.behavior("probe", behavior),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "probe"), GraphEdge.to("probe", "end")));
    }

    private static boolean hasEvent(List<ExecutionEvent> events, ExecutionEventType type, String nodeId) {
        return events.stream().anyMatch(event -> event.type() == type
                && (nodeId == null || nodeId.equals(event.nodeId())));
    }
}
