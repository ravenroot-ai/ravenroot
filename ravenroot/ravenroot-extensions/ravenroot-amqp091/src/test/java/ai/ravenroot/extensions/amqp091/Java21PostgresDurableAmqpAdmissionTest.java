package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.DurableConsumerIngress;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.deployment.RequestReplyLimits;
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
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionManifestReferences;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionKey;
import ai.ravenroot.api.persistence.GraphDefinitionReferences;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.manifest.ExecutionManifestResolver;
import ai.ravenroot.core.manifest.ExecutionManifestService;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultGraphDeployment;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.GraphExecutionLimits;
import ai.ravenroot.core.runtime.NodePackages;
import ai.ravenroot.core.runtime.UnknownBehaviorPolicy;
import ai.ravenroot.persistence.postgresql.PostgresExecutionManifestStore;
import ai.ravenroot.persistence.postgresql.PostgresExecutionStore;
import ai.ravenroot.persistence.postgresql.PostgresGraphDefinitionStore;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real PostgreSQL admission regression executed in a one-carrier Java 21 child JVM. */
class Java21PostgresDurableAmqpAdmissionTest {
    @Test
    void realPostgresAmqpAndStableSourceAdmissionsProgressOnOneJava21Carrier() throws Exception {
        try (var postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))) {
            postgres.start();
            Path java21 = java21Executable();
            ProcessBuilder launch = new ProcessBuilder(java21.toString(),
                    "-XX:ActiveProcessorCount=1", "-Djdk.virtualThreadScheduler.parallelism=1",
                    "-Djdk.tracePinnedThreads=full",
                    "-cp", System.getProperty("java.class.path"), Child.class.getName());
            launch.redirectErrorStream(true);
            launch.environment().put("RAVENROOT_TEST_JDBC_URL", postgres.getJdbcUrl());
            launch.environment().put("RAVENROOT_TEST_JDBC_USER", postgres.getUsername());
            launch.environment().put("RAVENROOT_TEST_JDBC_PASSWORD", postgres.getPassword());
            Process child = launch.start();
            var captured = new ByteArrayOutputStream();
            Thread outputReader = Thread.ofPlatform().name("java21-postgres-child-output").start(() -> {
                try {
                    child.getInputStream().transferTo(captured);
                } catch (IOException closedAfterForcedTermination) {
                    // The timeout assertion below owns the useful failure if termination closes it.
                }
            });
            long timeoutSeconds = Long.getLong("ravenroot.test.java21.childTimeoutSeconds", 90L);
            boolean exited = child.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            String threadDump = exited ? "" : childThreadDump(java21, child.pid());
            if (!exited) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
            }
            outputReader.join(10_000);
            String output = captured.toString(StandardCharsets.UTF_8);
            assertTrue(exited, () -> "single-carrier Java 21 child timed out:\n" + output + threadDump);
            assertEquals(0, child.exitValue(), () -> "single-carrier Java 21 child failed:\n" + output);
            assertTrue(output.contains("JAVA21_POSTGRES_AMQP_OK"), () -> "missing child proof:\n" + output);
            assertFalse(output.contains("reason:MONITOR"), () -> "Java 21 carrier was pinned:\n" + output);
        }
    }

    private static String childThreadDump(Path javaExecutable, long pid) {
        Path jcmd = javaExecutable.resolveSibling("jcmd");
        if (!Files.isExecutable(jcmd)) return "\n(no jcmd available at " + jcmd + ")\n";
        try {
            Process diagnostic = new ProcessBuilder(jcmd.toString(), Long.toString(pid), "Thread.print").start();
            boolean exited = diagnostic.waitFor(10, TimeUnit.SECONDS);
            if (!exited) diagnostic.destroyForcibly();
            return "\nJava 21 child thread dump:\n"
                    + new String(diagnostic.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    + new String(diagnostic.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return "\n(unable to capture child thread dump: " + failure + ")\n";
        }
    }

    private static Path java21Executable() {
        String configured = System.getProperty("ravenroot.test.java21.executable");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("RAVENROOT_TEST_JAVA21_EXECUTABLE");
        }
        Path executable;
        if (configured != null && !configured.isBlank()) {
            executable = Path.of(configured);
        } else if (Runtime.version().feature() == 21) {
            executable = Path.of(System.getProperty("java.home"), "bin", "java");
        } else {
            throw new AssertionError("Java 21 is required; run tests on Java 21 or set "
                    + "-Dravenroot.test.java21.executable=/path/to/java");
        }
        if (!Files.isExecutable(executable)) {
            throw new AssertionError("Java 21 executable is unavailable: " + executable);
        }
        return executable;
    }

    /** Main entrypoint stays in this test's classpath so the fork executes exactly the tested build. */
    public static final class Child {
        private static final SecurityContext IDENTITY = new SecurityContext("single-carrier-request",
                AmqpTestSupport.TENANT, "consumer", PrincipalType.WORKLOAD, "issuer");

        public static void main(String[] ignored) throws Exception {
            if (Runtime.version().feature() != 21) {
                throw new AssertionError("expected Java 21, got " + Runtime.version());
            }
            assertEquals(1, Runtime.getRuntime().availableProcessors(),
                    "the child must observe ActiveProcessorCount=1");
            assertEquals("1", System.getProperty("jdk.virtualThreadScheduler.parallelism"),
                    "the child must run with requested scheduler parallelism 1");
            PGSimpleDataSource dataSource = new PGSimpleDataSource();
            dataSource.setURL(required("RAVENROOT_TEST_JDBC_URL"));
            dataSource.setUser(required("RAVENROOT_TEST_JDBC_USER"));
            dataSource.setPassword(required("RAVENROOT_TEST_JDBC_PASSWORD"));
            Clock clock = Clock.systemUTC();
            DeploymentId deploymentId = DeploymentId.of("java21-postgres-amqp");
            byte[] graph = GRAPH.getBytes(StandardCharsets.UTF_8);
            var running = ConcurrentHashMap.<ExecutionKey>newKeySet();

            try (var realExecutions = new PostgresExecutionStore(dataSource, clock);
                 var definitions = new PostgresGraphDefinitionStore(dataSource, clock,
                         GraphDefinitionReferences.NONE);
                 var manifests = new PostgresExecutionManifestStore(dataSource, clock,
                         ExecutionManifestReferences.NONE);
                 var engine = new DirectEngine()) {
                ExecutionStore executions = observing(realExecutions, running);
                var delivery = AmqpConsumerTestSupport.delivery(1L, "one-carrier-message", false);
                String key = AmqpDeliveryEvent.project(delivery, AmqpConsumerTestSupport.policy(), 1)
                        .idempotentKey();
                UUID processId = UUID.nameUUIDFromBytes((IDENTITY.tenantId() + '\0'
                        + deploymentId.value() + "/listener" + '\0' + key)
                        .getBytes(StandardCharsets.UTF_8));
                ExecutionKey expectedAmqpExecution = new ExecutionKey(IDENTITY.tenantId(), processId);
                var owner = new VerifyingOwner(expectedAmqpExecution, running, definitions, manifests,
                        new GraphDefinitionKey(IDENTITY.tenantId(), GraphContentId.of(graph)));
                var protocol = new SingleOwnerProtocol(owner);
                NodeBehavior amqp = new AmqpConsumeNodeBehavior(
                        ignoredReference -> Optional.of(new SecretValue("credential".toCharArray())),
                        (tenant, name) -> Optional.of(AmqpTestSupport.profile(tenant, name, 4, 100, 1_000, 2)),
                        (tenant, name) -> Optional.of(AmqpConsumerTestSupport.policy()), protocol,
                        task -> Thread.ofVirtual().name("amqp-one-carrier").start(task), clock);
                var capture = new CaptureSourceBehavior();
                BehaviorRegistry behaviors = NodePackages.register(
                        BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                        nodePackage(amqp, capture));
                var manifestService = new ExecutionManifestService(manifests,
                        ExecutionManifestResolver.from(engine, executions.capabilities(), behaviors,
                                UnknownBehaviorPolicy.passThrough(), GraphExecutionLimits.DEFAULTS, null), clock);
                var completions = new CountDownLatch(2);
                var completionCount = new AtomicInteger();
                var monitor = new ExecutionMonitor();
                monitor.subscribe(event -> {
                    if (event.type() == ExecutionEventType.EXECUTION_COMPLETED) {
                        completionCount.incrementAndGet();
                        completions.countDown();
                    }
                });
                var deployment = new DefaultGraphDeployment(deploymentId, engine, behaviors, monitor,
                        ExecutionIdentitySource.randomUuids(), graph,
                        DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY, executions,
                        DefaultGraphDeployment.DEFAULT_INBOX_RETENTION, "java21-postgres-worker",
                        Duration.ofSeconds(30),
                        RequestReplyLimits.defaults(DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY),
                        definitions, GraphExecutionLimits.DEFAULTS, null, manifestService);
                deployment.start(IDENTITY).toCompletableFuture().get(20, TimeUnit.SECONDS);
                assertTrue(owner.pollEntered.await(10, TimeUnit.SECONDS), "AMQP source did not start polling");
                assertTrue(capture.started.await(10, TimeUnit.SECONDS), "stable source did not start");

                try (DurableConsumerIngress stable = capture.context.ingress()
                        .openDurableConsumer("single-carrier-reader")) {
                    var stableReceipt = new CompletableFuture<IngressReceipt>();
                    Thread.ofVirtual().name("stable-one-carrier").start(() -> {
                        try {
                            stableReceipt.complete(stable.offerDurably(IDENTITY, IngressTarget.start(),
                                    "stable payload", "stream-1", "stable-key-1"));
                        } catch (Throwable failure) {
                            stableReceipt.completeExceptionally(failure);
                        }
                    });
                    owner.deliver(delivery);

                    assertTrue(owner.acked.await(20, TimeUnit.SECONDS), "AMQP delivery was not acknowledged");
                    assertFalse(owner.premature.get(), "AMQP acknowledged before durable acceptance completed");
                    assertTrue(owner.nacks.get() == 0, "AMQP delivery was unexpectedly rejected");
                    assertTrue(stableReceipt.get(20, TimeUnit.SECONDS).acknowledgeable(),
                            "stable durable source admission did not complete");
                    assertTrue(completions.await(20, TimeUnit.SECONDS), "both admitted traversals must complete");
                    assertEquals(2, completionCount.get());
                }
                deployment.stop().toCompletableFuture().get(20, TimeUnit.SECONDS);
                System.out.println("JAVA21_POSTGRES_AMQP_OK acks=1 traversals=" + completionCount.get());
            }
        }

        private static String required(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) throw new IllegalStateException("missing " + name);
            return value;
        }

        private static ExecutionStore observing(PostgresExecutionStore delegate, Set<ExecutionKey> running) {
            return (ExecutionStore) Proxy.newProxyInstance(ExecutionStore.class.getClassLoader(),
                    new Class<?>[]{ExecutionStore.class}, (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(delegate, args);
                            if (method.getName().equals("apply") && args[0] instanceof ExecutionBatch batch
                                    && batch.transitions().stream().anyMatch(transition -> transition
                                            instanceof ExecutionTransition.ProcessTransitioned)
                                    && result instanceof CompletionStage<?> stage) {
                                return stage.thenApply(stored -> {
                                    running.add(batch.key());
                                    return stored;
                                });
                            }
                            return result;
                        } catch (InvocationTargetException wrapped) {
                            throw wrapped.getCause();
                        }
                    });
        }

        private static NodePackage nodePackage(NodeBehavior... behaviors) {
            return new NodePackage() {
                @Override public String id() { return "test.java21.postgres.sources"; }
                @Override public String version() { return "1.0.0"; }
                @Override public String sdkContract() { return NodeSdk.CONTRACT; }
                @Override public List<NodeBehavior> behaviors() { return List.of(behaviors); }
            };
        }
    }

    private static final class SingleOwnerProtocol implements AmqpConsumerProtocol {
        private final Owner owner;
        private SingleOwnerProtocol(Owner owner) { this.owner = owner; }
        @Override public Owner open(AmqpProfile profile, AmqpConsumerPolicy policy, char[] password, int prefetch) {
            return owner;
        }
    }

    private static final class VerifyingOwner implements AmqpConsumerProtocol.Owner {
        private final ExecutionKey expected;
        private final Set<ExecutionKey> running;
        private final PostgresGraphDefinitionStore definitions;
        private final PostgresExecutionManifestStore manifests;
        private final GraphDefinitionKey definition;
        private final LinkedBlockingQueue<AmqpConsumerProtocol.Event> events = new LinkedBlockingQueue<>();
        private final CountDownLatch pollEntered = new CountDownLatch(1);
        private final CountDownLatch acked = new CountDownLatch(1);
        private final AtomicBoolean premature = new AtomicBoolean();
        private final AtomicInteger nacks = new AtomicInteger();
        private volatile boolean stopped;

        private VerifyingOwner(ExecutionKey expected, Set<ExecutionKey> running,
                PostgresGraphDefinitionStore definitions, PostgresExecutionManifestStore manifests,
                GraphDefinitionKey definition) {
            this.expected = expected;
            this.running = running;
            this.definitions = definitions;
            this.manifests = manifests;
            this.definition = definition;
        }

        void deliver(AmqpConsumerProtocol.Delivery delivery) { events.add(delivery); }
        @Override public AmqpConsumerProtocol.Event poll(Duration timeout) throws AmqpConsumerProtocol.Failure {
            pollEntered.countDown();
            if (stopped) return new AmqpConsumerProtocol.Idle();
            try {
                AmqpConsumerProtocol.Event event = events.poll(100, TimeUnit.MILLISECONDS);
                return event == null ? new AmqpConsumerProtocol.Idle() : event;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AmqpConsumerProtocol.Failure(false, "interrupted");
            }
        }
        @Override public void ack(long deliveryTag) {
            boolean durable = running.contains(expected)
                    && definitions.contains(definition).toCompletableFuture().join()
                    && manifests.contains(expected).toCompletableFuture().join();
            premature.set(!durable);
            acked.countDown();
        }
        @Override public void nack(long deliveryTag, boolean requeue) { nacks.incrementAndGet(); }
        @Override public void wakeup() { events.offer(new AmqpConsumerProtocol.Idle()); }
        @Override public void close(Duration timeout) { stopped = true; wakeup(); }
    }

    private static final class CaptureSourceBehavior implements NodeBehavior, InboundSourceCapable {
        private volatile InboundSourceContext context;
        private final CountDownLatch started = new CountDownLatch(1);
        @Override public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.capture.source", "Capture source", "Test", "", "actor", false,
                    List.of(), Set.of(), NodeRuntimeNature.SOURCE, Set.of(NodeRuntimeNature.SOURCE));
        }
        @Override public NodeAction create(NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        }
        @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext ignored) {
            return new InboundSource() {
                @Override public CompletionStage<Void> start(InboundSourceContext startedContext) {
                    context = startedContext;
                    started.countDown();
                    return CompletableFuture.completedFuture(null);
                }
                @Override public CompletionStage<Void> stop() { return CompletableFuture.completedFuture(null); }
            };
        }
    }

    private static final class DirectEngine implements ExecutionEngine {
        private final Map<NodeRef, RavenNode> nodes = new ConcurrentHashMap<>();
        @Override public String id() { return "java21-postgres-direct"; }
        @Override public Set<EngineCapability> capabilities() { return Set.of(); }
        @Override public Scheduler scheduler() { return (delay, task) -> () -> true; }
        @Override public EngineState state() { return EngineState.RUNNING; }
        @Override public NodeRef spawn(String name, RavenNode node) {
            NodeRef ref = new NodeRef(name + "-" + UUID.randomUUID());
            nodes.put(ref, node);
            return ref;
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
            nodes.remove(target);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Void> cancel(NodeRef target) { return stop(target); }
        @Override public CompletionStage<Void> drain() {
            nodes.clear();
            return CompletableFuture.completedFuture(null);
        }
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
              <key id="profile" for="node" attr.name="brokerProfile" attr.type="string"/>
              <graph id="java21-postgres" edgedefault="directed">
                <node id="start"><data key="kind">START</data></node>
                <node id="listener"><data key="kind">BEHAVIOR</data><data key="behavior">amqp.consume</data><data key="profile">orders</data></node>
                <node id="capture"><data key="kind">BEHAVIOR</data><data key="behavior">test.capture.source</data></node>
                <node id="end"><data key="kind">END</data></node>
                <node id="error"><data key="kind">ERROR</data></node>
                <edge source="start" target="listener"/><edge source="listener" target="capture"/><edge source="capture" target="end"/>
              </graph>
            </graphml>
            """;
}
