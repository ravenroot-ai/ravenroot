package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.*;
import ai.ravenroot.api.execution.*;
import ai.ravenroot.api.node.*;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.security.*;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;
import ai.ravenroot.core.programming.*;
import ai.ravenroot.core.runtime.*;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import ai.ravenroot.extensions.mail.MixedExampleMail;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.rabbitmq.client.ConnectionFactory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import java.io.ByteArrayInputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the public mixed graph against real HTTP, SMTP, RabbitMQ, Pekko and SQLite. */
class AmqpMixedDeploymentExampleTest {
    private static final SecurityContext IDENTITY = new SecurityContext("example", "example", "operator", PrincipalType.USER, "fixture");

    @Test void oneBoundedStartLoopAndFourListenersKeepTheirIndependentSourcePaths() throws Exception {
        Path repository = Path.of(System.getProperty("basedir"), "../../..").toAbsolutePath().normalize();
        Path output = Files.createDirectories(Path.of("target", "mixed-deployment-example", UUID.randomUUID().toString())).toAbsolutePath();
        var smtp = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp")); smtp.start();
        var calls = new ConcurrentHashMap<String, AtomicInteger>();
        var http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        var externalExecutor = Executors.newVirtualThreadPerTaskExecutor(); http.setExecutor(externalExecutor);
        byte[] mail = "{\"version\":\"mail.send.v1\",\"to\":[\"to@example.test\"],\"text\":\"a sibling completed despite a source boundary\"}".getBytes(StandardCharsets.UTF_8);
        http.createContext("/", exchange -> {
            calls.computeIfAbsent(exchange.getRequestURI().getPath(), ignored -> new AtomicInteger()).incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, mail.length); exchange.getResponseBody().write(mail); exchange.close();
        }); http.start();
        try (var broker = new GenericContainer<>(DockerImageName.parse("rabbitmq:4.1-management-alpine"))
                .withEnv("RABBITMQ_DEFAULT_USER", "fixture").withEnv("RABBITMQ_DEFAULT_PASS", "fixture-password")
                .withExposedPorts(5672).waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1))
                .withStartupTimeout(Duration.ofMinutes(2))) {
            broker.start(); var connectionFactory = new ConnectionFactory(); connectionFactory.setHost("127.0.0.1");
            connectionFactory.setPort(broker.getMappedPort(5672)); connectionFactory.setUsername("fixture"); connectionFactory.setPassword("fixture-password");
            try (var connection = connectionFactory.newConnection(); var channel = connection.createChannel();
                 var store = new SqliteExecutionStore(output.resolve("history.db"), Clock.systemUTC());
                 var engine = new PekkoExecutionEngine("mixed-source-example")) {
                for (int i = 1; i <= 4; i++) channel.queueDeclare("mixed." + i, false, false, false, null);
                var sourceStarts = new AtomicInteger();
                var sources = sourceBehavior(broker.getMappedPort(5672), sourceStarts);
                var mailBehavior = MixedExampleMail.sender(smtp.getSmtp().getPort());
                var nodePackage = new NodePackage() {
                    @Override public String id() { return "example.mixed.operators"; }
                    @Override public String version() { return "1.0.0"; }
                    @Override public String sdkContract() { return NodeSdk.CONTRACT; }
                    @Override public List<NodeBehavior> behaviors() { return List.of(sources, mailBehavior); }
                };
                int httpPort = http.getAddress().getPort();
                var environment = new BehaviorEnvironment(null, null, null, null, null,
                        ignored -> new ToolDecision(ToolDecision.Disposition.ALLOW, "fixture", ""),
                        new OutboundHttpPolicy(Set.of("localhost"), Duration.ofSeconds(5), Set.of(httpPort), 4096, 4096));
                var registry = NodePackages.register(BehaviorRegistry.standard(environment), nodePackage);
                var limits = GraphExecutionLimits.fromEnvironment(Map.of(GraphExecutionLimits.MAX_TRAVERSAL_STEPS_VARIABLE, "30",
                        GraphExecutionLimits.MAX_AMPLIFIED_DELIVERIES_VARIABLE, "40"));
                var monitor = new ExecutionMonitor();
                try (var application = new DefaultRavenrootApplication(engine, monitor, registry,
                        new InMemoryArtifactRegistry(), new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(), store,
                        4, UnknownBehaviorPolicy.refuse(), limits)) {
                    String graph = Files.readString(repository.resolve("docs/examples/correlation/mixed-start-four-sources.graphml"))
                            .replace("http://localhost:19090", "http://localhost:" + httpPort);
                    application.registerLocalDeployment(IDENTITY, "mixed-example", new ByteArrayInputStream(graph.getBytes(StandardCharsets.UTF_8)));
                    assertEquals(LocalDeploymentState.READY, application.startLocalDeployment(IDENTITY, "mixed-example").toCompletableFuture().get(20, TimeUnit.SECONDS).orElseThrow().state());
                    assertEquals(4, sourceStarts.get()); assertTrue(calls.isEmpty()); assertEquals(0, smtp.getReceivedMessages().length);
                    assertTrue(store.initialStart("example", "mixed-example").toCompletableFuture().join().isEmpty());
                    for (int i = 1; i <= 4; i++) assertEquals(1, channel.queueDeclarePassive("mixed." + i).getConsumerCount());
                    var binding = application.localDeploymentView("example", "mixed-example").orElseThrow().source();
                    var initial = application.startLocalDeploymentExecution(IDENTITY, "mixed-example", binding.incarnationId(), binding.graphVersion(),
                            "initial-client-1", PayloadValue.of("initial"));
                    assertEquals(EmbedDeploymentStart.Outcome.ACCEPTED, initial.outcome());
                    await(() -> count(calls, "/loop") > 0);
                    for (int i = 1; i <= 4; i++) channel.basicPublish("", "mixed." + i,
                            new com.rabbitmq.client.AMQP.BasicProperties.Builder().messageId("transport-" + i).build(),
                            ("external event " + i).getBytes(StandardCharsets.UTF_8));
                    await(() -> { for (int i = 1; i <= 4; i++) if (count(calls, "/source-" + i) != 1) return false; return true; });
                    await(() -> smtp.getReceivedMessages().length == 2);
                    await(() -> monitor.eventsAfter(0).stream().anyMatch(event -> event.type() == ExecutionEventType.EXECUTION_FAILED
                            && initial.traversalId().equals(event.traversalId())));
                    assertEquals(1, count(calls, "/prepare")); assertTrue(count(calls, "/loop") > 1); assertTrue(count(calls, "/loop") < 30);
                    var initialState = store.load(new ExecutionKey("example", initial.processInstanceId())).toCompletableFuture().join().state();
                    assertTrue(initialState.traversals().get(initial.traversalId()).invocations().size() <= 30);
                    assertTrue(monitor.eventsAfter(0).stream().anyMatch(event -> event.type() == ExecutionEventType.SOURCE_BOUNDARY
                            && "amqp-1".equals(event.nodeId()) && initial.traversalId().equals(event.traversalId())));
                    for (int i = 1; i <= 4; i++) {
                        String source = "amqp-" + i;
                        var emission = monitor.eventsAfter(0).stream().filter(event -> event.type() == ExecutionEventType.NODE_STARTED && source.equals(event.nodeId()))
                                .filter(event -> source.equals(store.load(new ExecutionKey("example", event.processInstanceId())).toCompletableFuture().join()
                                        .state().traversals().get(event.traversalId()).ingressNodeId())).toList();
                        assertEquals(1, emission.size(), "only that source's actual broker event emits " + source);
                        assertNotEquals(initial.traversalId(), emission.getFirst().traversalId());
                        var stored = store.load(new ExecutionKey("example", emission.getFirst().processInstanceId())).toCompletableFuture().join().state();
                        assertEquals(source, stored.traversals().get(emission.getFirst().traversalId()).ingressNodeId());
                    }
                    assertEquals(4, sourceStarts.get(), "graph edges and emissions never reopen listeners");
                    var repeated = application.startLocalDeploymentExecution(IDENTITY, "mixed-example", binding.incarnationId(), binding.graphVersion(),
                            "independent-client-2", PayloadValue.of("different"));
                    assertEquals(EmbedDeploymentStart.Outcome.DUPLICATE, repeated.outcome());
                    assertEquals(initial.processInstanceId(), repeated.processInstanceId()); assertEquals(initial.traversalId(), repeated.traversalId());
                    assertEquals(1, count(calls, "/prepare"));
                    Files.writeString(output.resolve("observations.json"), "{\"listeners\":4,\"sourceEvents\":4,\"smtpMessages\":2,\"startTraversalLimit\":30,\"initialStartReplayed\":false,\"realBroker\":true,\"realHttp\":true}");
                }
            }
        } finally { http.stop(0); externalExecutor.close(); smtp.stop(); }
        System.out.println("mixed deployment executed example evidence=" + output);
    }

    private static NodeBehavior sourceBehavior(int port, AtomicInteger starts) {
        var profiles = (AmqpProfileResolver) (tenant, name) -> {
            if (!tenant.equals("example") || !name.matches("mixed-[1-4]")) return Optional.empty();
            String queue = "mixed." + name.charAt(name.length() - 1);
            return Optional.of(new AmqpProfile(tenant, name, "127.0.0.1", port, false, "/", "fixture", "fixture-password", "",
                    Set.of(""), queue, Set.of(queue), Set.of(), Set.of(), true, 0, 60000, 4, 100, 10000, 4096, 0));
        };
        var policies = (AmqpConsumerPolicyResolver) (tenant, name) -> profiles.resolve(tenant, name).map(profile ->
                new AmqpConsumerPolicy(tenant, name, profile.defaultRoutingKey(), 4, Set.of(), "", 4096, 1024, 100, 1000, 3, "reject", 1000));
        var credentials = (CredentialResolver) reference -> Optional.of(new SecretValue("fixture-password".toCharArray()));
        var delegate = new AmqpConsumeNodeBehavior(credentials, profiles, policies, new RabbitMqAmqpConsumerProtocol(),
                task -> Thread.startVirtualThread(task), Clock.systemUTC());
        class ConfiguredSource implements NodeBehavior, InboundSourceCapable {
            @Override public NodeTypeDescriptor descriptor() { return delegate.descriptor(); }
            @Override public NodeAction create(NodeConfiguration configuration) { return delegate.create(configuration); }
            @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
                starts.incrementAndGet();
                return new AmqpConsumerSource(configuration, credentials, profiles, policies, new RabbitMqAmqpConsumerProtocol(),
                        task -> Thread.startVirtualThread(task), Clock.systemUTC(), ignored -> {}, () -> 0.5,
                        ReservedNetworkPolicy.fromCommaSeparatedExceptions("127.0.0.1:LOOPBACK"));
            }
        }
        return new ConfiguredSource();
    }
    private static int count(Map<String, AtomicInteger> calls, String path) { return calls.getOrDefault(path, new AtomicInteger()).get(); }
    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), "bounded mixed example wait expired");
    }
}
