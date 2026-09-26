package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.RequestReplyLimits;
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
import ai.ravenroot.api.execution.NodeLifecycleState;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.execution.Scheduler;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.payload.PayloadEnvelope;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.persistence.GraphDefinitionReferences;
import ai.ravenroot.api.persistence.HumanTaskQuery;
import ai.ravenroot.api.persistence.HumanTaskStatus;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.publication.PublicationAuditSink;
import ai.ravenroot.api.publication.PublicationPolicyResolver;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.humantask.HumanTaskHandlerDispatcher;
import ai.ravenroot.core.humantask.HumanTaskService;
import ai.ravenroot.core.humantask.PinnedGraphHumanTaskContinuationExecutor;
import ai.ravenroot.core.recovery.ExecutionRecoveryService;
import ai.ravenroot.core.recovery.RecoveryOutcome;
import ai.ravenroot.core.recovery.RepeatabilityDeclarations;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultGraphDeployment;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.GraphExecutionLimits;
import ai.ravenroot.core.runtime.NodePackages;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;
import ai.ravenroot.extensions.spel.SpelNodePackage;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.persistence.sqlite.SqliteGraphDefinitionStore;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Complete broker -> Human Task -> SpEL -> broker proof against an isolated RabbitMQ. */
class AmqpHumanTaskWorkflowIntegrationTest {
    private static final String IMAGE =
            "rabbitmq@sha256:ddc75301edf58a8332934cf2d801be7cbf8d65c6458d747364a8046238ff1c89";
    private static final String TENANT = "tenant-a";
    private static final String PROFILE = "workflow";
    private static final String USER = "ravenroot";
    private static final String PASSWORD = "integration-password";
    private static final Duration LEASE_TTL = Duration.ofSeconds(30);

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void rabbitIngressResolvedHumanTaskAndSpelPublishAConfirmedMessage(@TempDir Path directory)
            throws Exception {
        Assumptions.assumeTrue(command(Duration.ofSeconds(10), "docker", "info").exitCode() == 0,
                "Docker is required for the pinned RabbitMQ integration");
        requireSuccess(command(Duration.ofMinutes(2), "docker", "pull", IMAGE), "RabbitMQ image pull");

        String inputQueue = "ingress-" + UUID.randomUUID();
        String outputQueue = "out-" + UUID.randomUUID();
        var captured = new AtomicReference<Map<?, ?>>();
        try (RabbitServer rabbit = RabbitServer.start();
             Connection administration = rabbit.connect();
             Channel channel = administration.createChannel();
             var store = new SqliteExecutionStore(directory.resolve("workflow.db"), Clock.systemUTC());
             var definitions = new SqliteGraphDefinitionStore(directory.resolve("workflow.db"),
                     Clock.systemUTC(), GraphDefinitionReferences.NONE);
             var engine = new DirectEngine()) {
            channel.queueDeclare(inputQueue, true, false, false, Map.of());
            channel.queueDeclare(outputQueue, true, false, false, Map.of());

            AmqpProfile profile = profile(rabbit.port(), outputQueue);
            var credentials = (ai.ravenroot.api.security.CredentialResolver) ignored ->
                    Optional.of(new SecretValue(PASSWORD.toCharArray()));
            NodeBehavior consume = new AmqpConsumeNodeBehavior(credentials,
                    (tenant, name) -> Optional.of(profile),
                    (tenant, name) -> Optional.of(policy(inputQueue)), new RabbitMqAmqpConsumerProtocol(),
                    task -> Thread.ofVirtual().name("amqp-workflow-consumer").start(task), Clock.systemUTC());
            NodeBehavior publish = new AmqpPublishNodeBehavior(credentials,
                    (tenant, name) -> Optional.of(profile), new RabbitMqAmqpProtocol(),
                    AmqpRuntimeControls.PRODUCTION, System::nanoTime, Thread::sleep,
                    ReservedNetworkPolicy.shippedDefault());
            var tasks = new HumanTaskService(store, Clock.systemUTC());
            BehaviorRegistry behaviors = BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults(),
                    PublicationPolicyResolver.none(), PublicationAuditSink.noop(), tasks)
                    .register("capture-confirm", message -> {
                        captured.set((Map<?, ?>) message.payload());
                        return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                    });
            behaviors = NodePackages.registerAll(behaviors,
                    List.of(packageWith(consume, publish), new SpelNodePackage()));
            byte[] graph = graph(inputQueue).getBytes(StandardCharsets.UTF_8);
            var monitor = new ExecutionMonitor();
            var deployment = new DefaultGraphDeployment(DeploymentId.of("amqp-human-task-workflow"),
                    engine, behaviors, monitor, ExecutionIdentitySource.randomUuids(), graph, 8, store,
                    DefaultGraphDeployment.DEFAULT_INBOX_RETENTION, "workflow-live", LEASE_TTL,
                    RequestReplyLimits.defaults(8), definitions, GraphExecutionLimits.DEFAULTS,
                    null, tasks, null);
            try {
                deployment.start(identity()).toCompletableFuture().get(10, TimeUnit.SECONDS);
                channel.basicPublish("", inputQueue, new AMQP.BasicProperties.Builder()
                                .contentType("text/plain").messageId("incoming-1").build(),
                        "request".getBytes(StandardCharsets.UTF_8));

                var task = awaitTask(tasks);
                assertEquals(HumanTaskStatus.RESOLVED,
                        tasks.resolve(approver(), task.request().taskId(), task.generation(), response())
                                .task().status());
                var continuation = new PinnedGraphHumanTaskContinuationExecutor(definitions, store, tasks,
                        engine, behaviors, monitor, ExecutionIdentitySource.randomUuids(),
                        "workflow-recovery", LEASE_TTL);
                var recovery = new ExecutionRecoveryService(store, List.of(TENANT), "workflow-recovery",
                        10, LEASE_TTL, RepeatabilityDeclarations.NONE_DECLARED,
                        new HumanTaskHandlerDispatcher(store, tasks, continuation));
                List<RecoveryOutcome> outcomes = recovery.sweepOnce();
                assertTrue(outcomes.stream().anyMatch(RecoveryOutcome.HandlerDispatched.class::isInstance),
                        outcomes::toString);

                Map<?, ?> result = captured.get();
                assertNotNull(result, "the composed runtime must reach the publisher result");
                assertEquals("CONFIRMED", result.get("status"));
                GetResponse delivered = channel.basicGet(outputQueue, true);
                assertNotNull(delivered, "a CONFIRMED result must correspond to broker delivery");
                assertEquals("approved release", new String(delivered.getBody(), StandardCharsets.UTF_8));
                assertEquals(task.request().taskId().toString(), delivered.getProps().getMessageId());
            } finally {
                deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    private static AmqpProfile profile(int port, String outputQueue) {
        return new AmqpProfile(TENANT, PROFILE, "localhost", port, false, "/", USER,
                "workflow-secret", "", Set.of(), outputQueue, Set.of(), Set.of(), Set.of(),
                false, 0, 0, 4, 100, 5_000, 4_096, 0);
    }

    private static AmqpConsumerPolicy policy(String inputQueue) {
        return new AmqpConsumerPolicy(TENANT, PROFILE, inputQueue, 4, Set.of(), "",
                4_096, 0, 100, 1_000, 3, "reject", 1_000);
    }

    private static NodePackage packageWith(NodeBehavior... behaviors) {
        return new NodePackage() {
            @Override public String id() { return "test.amqp.workflow"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<NodeBehavior> behaviors() { return List.of(behaviors); }
        };
    }

    private static ai.ravenroot.api.persistence.DurableHumanTask awaitTask(HumanTaskService tasks)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var items = tasks.inbox(requester(), HumanTaskQuery.everything(10)).items();
            if (!items.isEmpty()) return items.getFirst();
            Thread.sleep(20);
        }
        throw new AssertionError("AMQP ingress did not reach the Human Task");
    }

    private static OpaquePayload response() {
        return OpaquePayload.of(PayloadEnvelope.of("release.decision", "1",
                        PayloadValue.map(Map.of("bodyText", PayloadValue.of("approved release"))))
                .toJson().getBytes(StandardCharsets.UTF_8),
                "application/vnd.ravenroot.payload+json");
    }

    private static SecurityContext identity() {
        return new SecurityContext("workflow-request", TENANT, "consumer", PrincipalType.WORKLOAD, "issuer");
    }

    private static RequestContext requester() {
        return new RequestContext("requester-call", "requester", PrincipalType.USER, "issuer", TENANT,
                Set.of(), Set.of());
    }

    private static RequestContext approver() {
        return new RequestContext("approver-call", "approver", PrincipalType.USER, "issuer", TENANT,
                Set.of(Role.APPROVER), Set.of());
    }

    private static String graph(String inputQueue) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
                  <key id="profile" for="node" attr.name="brokerProfile" attr.type="string"/>
                  <key id="queue" for="node" attr.name="queue" attr.type="string"/>
                  <key id="title" for="node" attr.name="title" attr.type="string"/>
                  <key id="schema" for="node" attr.name="responseSchema" attr.type="string"/>
                  <key id="schema-version" for="node" attr.name="responseSchemaVersion" attr.type="string"/>
                  <key id="roles" for="node" attr.name="authorizedRoles" attr.type="string"/>
                  <key id="expression" for="node" attr.name="expression" attr.type="string"/>
                  <key id="repeatable" for="node" attr.name="recovery.repeatable" attr.type="string"/>
                  <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
                  <graph id="amqp-human-task-workflow" edgedefault="directed">
                    <node id="error"><data key="kind">ERROR</data></node>
                    <node id="start"><data key="kind">START</data></node>
                    <node id="listener"><data key="kind">BEHAVIOR</data><data key="behavior">amqp.consume</data><data key="profile">workflow</data><data key="queue">%s</data></node>
                    <node id="review"><data key="kind">BEHAVIOR</data><data key="behavior">human-task</data><data key="title">Approve release</data><data key="schema">release.decision</data><data key="schema-version">1</data><data key="roles">APPROVER</data></node>
                    <node id="transform"><data key="kind">BEHAVIOR</data><data key="behavior">spel.transform</data><data key="expression">{'version':'amqp.publish.v1','bodyText':response.bodyText,'messageId':taskId}</data></node>
                    <node id="publish"><data key="kind">BEHAVIOR</data><data key="behavior">amqp.publish</data><data key="profile">workflow</data><data key="repeatable">not-repeatable</data></node>
                    <node id="capture"><data key="kind">BEHAVIOR</data><data key="behavior">capture-confirm</data></node>
                    <node id="end"><data key="kind">END</data></node>
                    <edge source="start" target="listener"/>
                    <edge source="listener" target="review"/>
                    <edge source="review" target="transform"><data key="outcome">resolved</data></edge>
                    <edge source="transform" target="publish"/>
                    <edge source="publish" target="capture"/>
                    <edge source="capture" target="end"/>
                  </graph>
                </graphml>
                """.formatted(inputQueue);
    }

    private record CommandResult(int exitCode, String output) { }

    private static CommandResult command(Duration timeout, String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            return new CommandResult(-1, "command timed out");
        }
        return new CommandResult(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip());
    }

    private static void requireSuccess(CommandResult result, String operation) {
        if (result.exitCode() != 0) throw new IllegalStateException(operation + " failed: " + result.output());
    }

    private static final class RabbitServer implements AutoCloseable {
        private final String name;
        private final int port;

        private RabbitServer(String name, int port) { this.name = name; this.port = port; }
        int port() { return port; }

        static RabbitServer start() throws Exception {
            String name = "ravenroot-amqp-test-" + UUID.randomUUID();
            CommandResult started = command(Duration.ofSeconds(30), "docker", "run", "-d", "--rm",
                    "--name", name, "-e", "RABBITMQ_DEFAULT_USER=" + USER, "-e",
                    "RABBITMQ_DEFAULT_PASS=" + PASSWORD, "-p", "127.0.0.1::5672", IMAGE);
            requireSuccess(started, "RabbitMQ start");
            try {
                CommandResult mapped = command(Duration.ofSeconds(10), "docker", "port", name, "5672/tcp");
                requireSuccess(mapped, "RabbitMQ port discovery");
                int port = Integer.parseInt(mapped.output().substring(mapped.output().lastIndexOf(':') + 1));
                RabbitServer server = new RabbitServer(name, port);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (System.nanoTime() < deadline) {
                    try (Connection ignored = server.connect()) { return server; }
                    catch (Exception unavailable) { Thread.sleep(100); }
                }
                throw new IllegalStateException("RabbitMQ did not become ready");
            } catch (Exception failure) {
                command(Duration.ofSeconds(10), "docker", "rm", "-f", name);
                throw failure;
            }
        }

        Connection connect() throws Exception {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost("127.0.0.1");
            factory.setPort(port);
            factory.setUsername(USER);
            factory.setPassword(PASSWORD);
            factory.setConnectionTimeout(1_000);
            factory.setHandshakeTimeout(1_000);
            return factory.newConnection("ravenroot-amqp-workflow-test");
        }

        @Override public void close() throws Exception {
            requireSuccess(command(Duration.ofSeconds(10), "docker", "rm", "-f", name),
                    "RabbitMQ cleanup");
        }
    }

    private static final class DirectEngine implements ExecutionEngine {
        private final Map<NodeRef, RavenNode> nodes = new ConcurrentHashMap<>();
        @Override public String id() { return "amqp-workflow-test"; }
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
}
