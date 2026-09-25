package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.GraphAdmissionPhase;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.DeploymentStartupException;
import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.EngineState;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.Mailbox;
import ai.ravenroot.api.execution.NodeContext;
import ai.ravenroot.api.execution.NodeLifecycleState;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeStatus;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.execution.Scheduler;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultGraphDeployment;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.NodePackages;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImapStartupFailureDeploymentTest {
    private static final SecurityContext IDENTITY = new SecurityContext("request", ImapConsumerTestSupport.TENANT,
            "consumer", PrincipalType.WORKLOAD, "issuer");
    @TempDir Path directory;

    @Test
    void protocolFailureKeepsItsCauseAndDeclaredProjectionAcrossTheDeploymentBoundary() throws Exception {
        var sentinel = new ImapConsumerProtocol.Failure(true, "host=private.example password=secret");
        assertFailure(sentinel, (profile, folder, password, opening) -> { throw sentinel; },
                "imap-consumer-failed", true);
    }

    @Test
    void unknownRuntimeFailureKeepsItsCauseButProjectsOnlyTheGenericReason() throws Exception {
        var sentinel = new IllegalStateException("host=private.example password=secret");
        assertFailure(sentinel, (profile, folder, password, opening) -> { throw sentinel; },
                "STARTUP_FAILED", false);
    }

    private void assertFailure(Throwable sentinel, ImapConsumerProtocol protocol,
                               String expectedReason, boolean classified) throws Exception {
        NodeBehavior behavior = new MailImapConsumeNodeBehavior(
                (tenant, name) -> Optional.of(ImapConsumerTestSupport.profile()),
                ignored -> Optional.of(new SecretValue("credential".toCharArray())),
                (tenant, name) -> Optional.of(ImapConsumerTestSupport.policy()), protocol,
                task -> Thread.ofVirtual().start(task), Clock.systemUTC());
        BehaviorRegistry registry = NodePackages.register(new BehaviorRegistry(), nodePackage(behavior));
        AtomicInteger sinkCalls = new AtomicInteger();
        AtomicReference<Throwable> recorded = new AtomicReference<>();
        try (var store = new SqliteExecutionStore(directory.resolve(UUID.randomUUID() + ".db"), Clock.systemUTC());
             var engine = new DirectEngine()) {
            var deployment = deployment(engine, registry, store);
            deployment.installStartupFailureSink((id, failure, sourceNode, throwable) -> {
                sinkCalls.incrementAndGet();
                recorded.set(throwable);
            });

            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS));
            var publicFailure = ((DeploymentStartupException) thrown.getCause()).failure();

            assertEquals(expectedReason, publicFailure.reason());
            assertEquals(GraphAdmissionPhase.SOURCE_START, publicFailure.phase());
            assertEquals(publicFailure, deployment.status().failure().orElseThrow());
            assertEquals(classified, publicFailure.nodeId().isPresent());
            assertEquals(1, sinkCalls.get());
            assertTrue(hasIdentity(recorded.get(), sentinel));
            assertFalse(publicFailure.toString().contains("private.example"));
            assertFalse(publicFailure.toString().contains("secret"));
            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    private static DefaultGraphDeployment deployment(ExecutionEngine engine, BehaviorRegistry registry,
                                                     SqliteExecutionStore store) {
        return new DefaultGraphDeployment(DeploymentId.of("imap-startup-failure"), engine, registry,
                new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(), GRAPH.getBytes(StandardCharsets.UTF_8),
                DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY, store,
                DefaultGraphDeployment.DEFAULT_INBOX_RETENTION, "imap-startup-worker", Duration.ofSeconds(30));
    }

    private static NodePackage nodePackage(NodeBehavior behavior) {
        return new NodePackage() {
            @Override public String id() { return "test.imap.startup"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<NodeBehavior> behaviors() { return List.of(behavior); }
        };
    }

    private static boolean hasIdentity(Throwable candidate, Throwable expected) {
        for (Throwable current = candidate; current != null; current = current.getCause()) {
            if (current == expected) return true;
        }
        return false;
    }

    private static final class DirectEngine implements ExecutionEngine {
        private final Map<NodeRef, RavenNode> nodes = new ConcurrentHashMap<>();
        @Override public String id() { return "imap-startup-test"; }
        @Override public Set<EngineCapability> capabilities() { return Set.of(); }
        @Override public Scheduler scheduler() { return (delay, task) -> () -> true; }
        @Override public EngineState state() { return EngineState.RUNNING; }
        @Override public NodeRef spawn(String name, RavenNode node) {
            NodeRef ref = new NodeRef(name + "-" + UUID.randomUUID()); nodes.put(ref, node); return ref;
        }
        @Override public CompletionStage<NodeResult> send(NodeRef target, NodeMessage message) {
            RavenNode node = nodes.get(target);
            return node == null ? CompletableFuture.failedFuture(new IllegalArgumentException("unknown node"))
                    : node.onMessage(message, context(target));
        }
        @Override public Optional<NodeStatus> status(NodeRef target) {
            return nodes.containsKey(target)
                    ? Optional.of(new NodeStatus(target, NodeLifecycleState.RUNNING, null, 0)) : Optional.empty();
        }
        @Override public CompletionStage<Void> stop(NodeRef target) {
            nodes.remove(target); return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Void> cancel(NodeRef target) { return stop(target); }
        @Override public CompletionStage<Void> drain() { nodes.clear(); return CompletableFuture.completedFuture(null); }
        @Override public void close() { nodes.clear(); }
        private NodeContext context(NodeRef ref) {
            return new NodeContext() {
                @Override public NodeRef self() { return ref; }
                @Override public Scheduler scheduler() { return DirectEngine.this.scheduler(); }
                @Override public Mailbox mailbox() { return () -> 0; }
                @Override public CancellationSignal cancellation() {
                    return new CancellationSignal() {
                        @Override public boolean cancelled() { return false; }
                        @Override public void onCancel(Runnable listener) { }
                    };
                }
            };
        }
    }

    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="profile" for="node" attr.name="profile" attr.type="string"/>
              <graph id="g" edgedefault="directed">
                <node id="start"><data key="kind">START</data></node>
                <node id="listener"><data key="kind">BEHAVIOR</data><data key="behavior">mail.imap.consume</data><data key="profile">reader</data></node>
                <node id="end"><data key="kind">END</data></node>
                <node id="error"><data key="kind">ERROR</data></node>
                <edge source="start" target="listener"/><edge source="listener" target="end"/>
              </graph>
            </graphml>
            """;
}
