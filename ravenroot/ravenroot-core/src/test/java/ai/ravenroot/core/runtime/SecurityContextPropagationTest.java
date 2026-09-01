package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionSubmission;
import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.CancellationSignal;
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
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-07 evidence: one request's identity, observed at every boundary it crosses.
 *
 * <p>The graph runs {@code start -> spoofer -> observer -> end}. The {@code spoofer} node does exactly
 * what an attacker-authored graph would do — returns attributes that claim a different tenant and
 * principal — and the {@code observer} node records the identity it was actually delivered. The
 * assertion is that the observer's identity is the ingress identity, unchanged and in fact the same
 * object, while the hostile attributes travel alongside it as ordinary data with no effect.</p>
 *
 * <p>The trace covers submission, node, store key and event, all
 * carrying one tenant and one correlation id.</p>
 */
class SecurityContextPropagationTest {

    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="node-behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="propagation" edgedefault="directed">
                <node id="error"><data key="node-kind">ERROR</data></node>
                <node id="start"><data key="node-kind">START</data></node>
                <node id="spoofer">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">spoof-identity</data>
                </node>
                <node id="observer">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">observe-identity</data>
                </node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="e1" source="start" target="spoofer"><data key="edge-outcome">continue</data></edge>
                <edge id="e2" source="spoofer" target="observer"><data key="edge-outcome">continue</data></edge>
                <edge id="e3" source="observer" target="end"><data key="edge-outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    @Test
    void oneRequestKeepsOneIdentityAcrossExecutionNodeStoreAndEvent() {
        var observed = new ArrayList<NodeMessage>();
        var registry = new BehaviorRegistry()
                .register("spoof-identity", message -> {
                    // Exactly the attack under test: a node claiming to be somebody else.
                    var hostile = new LinkedHashMap<String, Object>(message.attributes());
                    hostile.put("ravenroot.security.tenantId", "tenant-evil");
                    hostile.put("tenantId", "tenant-evil");
                    hostile.put("subject", "mallory");
                    return CompletableFuture.completedFuture(
                            new NodeResult("continue", message.payload(), hostile));
                })
                .register("observe-identity", message -> {
                    observed.add(message);
                    return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                });

        var monitor = new ExecutionMonitor();
        var store = new InMemoryExecutionStore();
        var engine = new DirectExecutionEngine();
        var application = new DefaultRavenrootApplication(engine, monitor, registry,
                new ai.ravenroot.core.programming.InMemoryArtifactRegistry(),
                new ai.ravenroot.core.programming.DisabledProgramRuntime(),
                ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), store);

        SecurityContext ingress = TestIdentities.TENANT_A;
        ExecutionSubmission submission = application.startGraphMl(ingress, UUID.randomUUID(),
                new ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8)), "payload");

        // ---- boundary 1: the node ----
        assertEquals(1, observed.size(), "the observer node must have run exactly once");
        NodeMessage delivered = observed.getFirst();
        assertSame(ingress, delivered.security(),
                "identity must be carried by reference, not reconstructed per hop");
        assertEquals("tenant-a", delivered.tenantId());
        assertEquals("alice", delivered.security().subject());

        // The hostile attributes did arrive — they are ordinary data — and changed nothing.
        assertEquals("tenant-evil", delivered.attributes().get("ravenroot.security.tenantId"));
        assertEquals("tenant-a", delivered.tenantId(),
                "a node-supplied attribute must not be able to restate the tenant");

        // ---- boundary 2: NodeMessage.next(...) keeps identity while the node controls payload ----
        NodeMessage routed = delivered.next(UUID.randomUUID(), UUID.randomUUID(), "elsewhere", "other-payload");
        assertSame(ingress, routed.security());
        assertEquals("other-payload", routed.payload());

        // ---- boundary 3: the store ----
        var stored = store.load(new ExecutionKey("tenant-a", submission.processInstanceId()))
                .toCompletableFuture().join();
        assertEquals(submission.processInstanceId(), stored.state().processInstanceId());
        assertEquals("tenant-a", stored.tenantId());

        // ---- boundary 4: the events ----
        List<ExecutionEvent> events = monitor.eventsAfter(0);
        assertFalse(events.isEmpty(), "the traversal must have published events");
        assertTrue(events.stream().allMatch(event -> "tenant-a".equals(event.tenantId())),
                "every event of this traversal belongs to the submitting tenant");
        assertTrue(events.stream().allMatch(event -> ingress.requestId().equals(event.requestId())),
                "every event carries the ingress correlation id, which is what makes the audit joinable");

        application.close();
        store.close();
    }

    @Test
    void aSecondTenantsSubmissionIsNeverAttributedToTheFirst() {
        var observed = new ArrayList<NodeMessage>();
        var registry = new BehaviorRegistry()
                .register("spoof-identity", message ->
                        CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())))
                .register("observe-identity", message -> {
                    observed.add(message);
                    return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                });
        var monitor = new ExecutionMonitor();
        var store = new InMemoryExecutionStore();
        var engine = new DirectExecutionEngine();
        var application = new DefaultRavenrootApplication(engine, monitor, registry,
                new ai.ravenroot.core.programming.InMemoryArtifactRegistry(),
                new ai.ravenroot.core.programming.DisabledProgramRuntime(),
                ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), store);

        application.startGraphMl(TestIdentities.TENANT_A, UUID.randomUUID(),
                new ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8)), "a");
        application.startGraphMl(TestIdentities.TENANT_B, UUID.randomUUID(),
                new ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8)), "b");

        assertEquals(List.of("tenant-a", "tenant-b"), observed.stream().map(NodeMessage::tenantId).toList());
        assertEquals(List.of("alice", "mallory"),
                observed.stream().map(message -> message.security().subject()).toList());

        application.close();
        store.close();
    }

    /**
     * A synchronous engine that genuinely invokes the node, unlike the pass-through stub used by the
     * store test. Propagation is only observable if node code actually runs, and running it on the
     * calling thread keeps the assertions deterministic without a Pekko dependency core cannot have.
     */
    private static final class DirectExecutionEngine implements ExecutionEngine {
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
            if (node == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("unknown node " + target.value()));
            }
            return node.onMessage(message, context(target));
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
                    return DirectExecutionEngine.this.scheduler();
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
