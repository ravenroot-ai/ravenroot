package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.ExecutionSubmission;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.DeploymentState;
import ai.ravenroot.api.deployment.IngressDisposition;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR 0021 D5's identity half: {@code deploymentId}/{@code workloadId} must
 * actually reach {@link ExecutionEvent}, not merely compile into the shape that could carry them.
 *
 * <p>A value merely <em>present</em> on an event does not establish correlation; it must identify the
 * same item across every event that item produces. This
 * class asserts the stronger claim directly -- every event belonging to one traversal shares one
 * {@code workloadId}, and a second, concurrent traversal gets a different one -- rather than checking
 * presence on a single event and calling it correlation.
 */
class DefaultGraphDeploymentObservabilityTest {
    private static final ai.ravenroot.api.security.SecurityContext IDENTITY = new ai.ravenroot.api.security.SecurityContext(
            "observability-request", "observability-tenant", "observability-subject",
            ai.ravenroot.api.security.PrincipalType.WORKLOAD, "urn:ravenroot:observability");

    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="wiring" edgedefault="directed">
                <node id="error"><data key="node-kind">ERROR</data></node>
                <node id="start"><data key="node-kind">START</data></node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="start-end" source="start" target="end">
                  <data key="edge-outcome">continue</data>
                </edge>
              </graph>
            </graphml>
            """;

    @Test
    void everyEventOfADeploymentTraversalCarriesTheSameDeploymentAndWorkloadIdentityAndTwoTraversalsDiffer()
            throws Exception {
        var engine = new JoinTestEngine();
        var monitor = new ExecutionMonitor();
        var byTraversal = new ConcurrentHashMap<UUID, List<ExecutionEvent>>();
        var completed = new CountDownLatch(2);
        try (var subscription = monitor.subscribe(event -> {
            byTraversal.computeIfAbsent(event.traversalId(), ignored -> new CopyOnWriteArrayList<>()).add(event);
            if (event.type() == ExecutionEventType.EXECUTION_COMPLETED) {
                completed.countDown();
            }
        })) {
            var deployment = new DefaultGraphDeployment(DeploymentId.of("orders"), engine,
                    BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()), monitor,
                    ExecutionIdentitySource.randomUuids(), graphBytes(),
                    DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY);
            var status = deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(DeploymentState.READY, status.state());

            assertEquals(IngressDisposition.ACCEPTED,
                    deployment.ingress().offer(IDENTITY, IngressTarget.start(), "first"));
            assertEquals(IngressDisposition.ACCEPTED,
                    deployment.ingress().offer(IDENTITY, IngressTarget.start(), "second"));

            assertTrue(completed.await(10, TimeUnit.SECONDS), "both traversals must complete");
            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } finally {
            engine.close();
        }

        assertEquals(2, byTraversal.size(), "two ingress offers must start two distinct traversals");
        String expectedGraphVersion = sha256Hex(graphBytes());
        List<String> workloadIdsSeen = new java.util.ArrayList<>();
        for (var events : byTraversal.values()) {
            assertTrue(events.size() >= 4, "expected at least EXECUTION_STARTED/NODE_*/EXECUTION_COMPLETED: "
                    + events.size());
            Set<String> deploymentIds = events.stream().map(ExecutionEvent::deploymentId)
                    .collect(Collectors.toSet());
            Set<String> workloadIds = events.stream().map(ExecutionEvent::workloadId).collect(Collectors.toSet());
            assertEquals(Set.of("orders"), deploymentIds,
                    "every event of this traversal must carry the same deploymentId: " + events);
            assertEquals(1, workloadIds.size(),
                    "every event of this traversal must carry the SAME workloadId -- a value that changes "
                            + "mid-traversal is present but does not correlate: " + events);
            String workloadId = workloadIds.iterator().next();
            assertNotEquals(null, workloadId);
            workloadIdsSeen.add(workloadId);
            events.forEach(event -> assertEquals(expectedGraphVersion, event.graphVersion(),
                    "the real GraphML hash must reach every event, not a placeholder"));
        }
        assertNotEquals(workloadIdsSeen.get(0), workloadIdsSeen.get(1),
                "two distinct traversals must not share one workloadId, or activity from one item would be "
                        + "misattributed to the other");
    }

    @Test
    void aPlaygroundSubmissionCarriesNoDeploymentOrWorkloadIdentity() throws Exception {
        var engine = new JoinTestEngine();
        var monitor = new ExecutionMonitor();
        var events = new CopyOnWriteArrayList<ExecutionEvent>();
        var completed = new CountDownLatch(1);
        try (var subscription = monitor.subscribe(event -> {
            events.add(event);
            if (event.type() == ExecutionEventType.EXECUTION_COMPLETED) {
                completed.countDown();
            }
        })) {
            var application = new DefaultRavenrootApplication(engine, monitor,
                    BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()), new InMemoryArtifactRegistry(),
                    new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(), null, 0);
            ExecutionSubmission submission = application.startGraphMl(IDENTITY, UUID.randomUUID(), graphStream(),
                    "payload");
            assertTrue(completed.await(10, TimeUnit.SECONDS));
            application.close();

            List<ExecutionEvent> own = events.stream()
                    .filter(event -> event.traversalId().equals(submission.traversalId())).toList();
            assertTrue(own.size() >= 4);
            own.forEach(event -> {
                assertNull(event.deploymentId(),
                        "a one-shot/playground submission must never carry a deploymentId: " + event);
                assertNull(event.workloadId(),
                        "a one-shot/playground submission must never carry a workloadId: " + event);
            });
        } finally {
            engine.close();
        }
    }

    private static byte[] graphBytes() {
        return GRAPH.getBytes(StandardCharsets.UTF_8);
    }

    private static ByteArrayInputStream graphStream() {
        return new ByteArrayInputStream(graphBytes());
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
