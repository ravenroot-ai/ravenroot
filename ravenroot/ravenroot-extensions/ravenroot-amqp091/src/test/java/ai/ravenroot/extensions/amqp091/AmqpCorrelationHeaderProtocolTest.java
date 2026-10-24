package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.security.SecretValue;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AmqpCorrelationHeaderProtocolTest {
    private static final String HEADER = "X-Business-Operation";
    private static final ApplicationCorrelationContext CONTEXT = new ApplicationCorrelationContext("ingestion-42",
            Optional.of("partition-2"), Optional.of("silver"), "round-3", Optional.of("sales.csv"));

    @Test void actualRabbitPublishAndSourceRoundTripPreservesIndependentTransportIdentities() throws Exception {
        try (var broker = new GenericContainer<>(DockerImageName.parse("rabbitmq:4.1-management-alpine"))
                .withEnv("RABBITMQ_DEFAULT_USER", "fixture").withEnv("RABBITMQ_DEFAULT_PASS", "fixture-password")
                .withExposedPorts(5672).waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1))
                .withStartupTimeout(Duration.ofMinutes(2))) {
            broker.start();
            var profile = profile(broker.getMappedPort(5672), Set.of(HEADER, "X-Business-Part", "X-Business-Round"));
            var connectionFactory = new ConnectionFactory(); connectionFactory.setHost(profile.host());
            connectionFactory.setPort(profile.port()); connectionFactory.setUsername("fixture"); connectionFactory.setPassword("fixture-password");
            try (var connection = connectionFactory.newConnection(); var channel = connection.createChannel()) {
                channel.queueDeclare("orders.q", false, false, false, null);
                var ingress = new AmqpConsumerTestSupport.Ingress(4);
                var policy = new AmqpConsumerPolicy(AmqpTestSupport.TENANT, AmqpTestSupport.PROFILE, "orders.q", 4,
                        profile.headers(), "", 4096, 2048, 100, 1000, 3, "reject", 1000);
                var source = new AmqpConsumerSource(new NodeConfiguration("consume", AmqpConsumeNodeBehavior.BEHAVIOR,
                        Map.of("brokerProfile", AmqpTestSupport.PROFILE, "queue", "orders.q")),
                        ignored -> Optional.of(new SecretValue("fixture-password".toCharArray())),
                        (tenant, name) -> Optional.of(profile), (tenant, name) -> Optional.of(policy),
                        new RabbitMqAmqpConsumerProtocol(), task -> Thread.startVirtualThread(task), Clock.systemUTC(), ignored -> {}, () -> 0.5,
                        ai.ravenroot.api.security.egress.ReservedNetworkPolicy.fromCommaSeparatedExceptions("127.0.0.1:LOOPBACK"));
                try {
                    source.start(new AmqpConsumerTestSupport.Context(ingress)).toCompletableFuture().orTimeout(15, TimeUnit.SECONDS).join();
                    var publisher = behavior(profile, new RabbitMqAmqpProtocol());
                    var payload = Map.<String, Object>of("version", "amqp.publish.v1", "bodyText", "bounded external result",
                            "messageId", "transport-message-7", "correlationId", "transport-waiter-9", "replyTo", "responses");
                    var action = publisher.create(AmqpTestSupport.configuration(Map.of(
                            CorrelationHeaderMapping.APPLICATION, HEADER, CorrelationHeaderMapping.CONTRIBUTION, "X-Business-Part",
                            CorrelationHeaderMapping.ROUND, "X-Business-Round")));
                    var result = (Map<?, ?>) action.handle(message(payload, CONTEXT)).toCompletableFuture().orTimeout(15, TimeUnit.SECONDS).join().payload();
                    assertEquals("CONFIRMED", result.get("status"));
                    channel.basicPublish("", "orders.q", new com.rabbitmq.client.AMQP.BasicProperties.Builder()
                            .messageId("binary-header-message").headers(Map.of(HEADER, new byte[]{65, 66})).build(), new byte[]{1});
                    channel.basicPublish("", "orders.q", new com.rabbitmq.client.AMQP.BasicProperties.Builder()
                            .messageId("invalid-utf8-message").headers(Map.of(HEADER,
                                    com.rabbitmq.client.impl.LongStringHelper.asLongString(new byte[]{(byte) 0xc3, 0x28}))).build(), new byte[]{1});
                    channel.basicPublish("", "orders.q", new com.rabbitmq.client.AMQP.BasicProperties.Builder()
                            .messageId("control-header-message").headers(Map.of(HEADER, "line\ncontrol")).build(), new byte[]{1});
                    assertTrue(ingress.offered.await(15, TimeUnit.SECONDS), "actual consumer did not deliver to durable ingress");
                    var event = ingress.payloads.stream().filter(value -> "transport-message-7".equals(value.get("messageId"))).findFirst().orElseThrow();
                    for (String binaryId : List.of("binary-header-message", "invalid-utf8-message", "control-header-message")) {
                        var binary = ingress.payloads.stream().filter(value -> binaryId.equals(value.get("messageId"))).findFirst().orElseThrow();
                        assertEquals("base64", ((Map<?, ?>) ((Map<?, ?>) binary.get("headers")).get(HEADER)).get("format"));
                        assertThrows(CorrelationSelector.Invalid.class, () -> new CorrelationSelector("/headers/" + HEADER, CorrelationLimits.DEFAULTS)
                                .selectId(PayloadValue.fromJava(binary, CorrelationLimits.DEFAULTS.input())));
                    }
                    assertEquals("amqp.delivery.v1", event.get("version"));
                    assertEquals("transport-message-7", event.get("messageId"));
                    assertEquals("transport-waiter-9", event.get("correlationId"));
                    assertEquals("responses", event.get("replyTo"));
                    var limits = CorrelationLimits.DEFAULTS;
                    var mapping = new CorrelationMapping(CorrelationMapping.Mode.REQUIRE_EXISTING,
                            List.of(new CorrelationSelector("/headers/" + HEADER, limits)),
                            Optional.of(new CorrelationSelector("/headers/X-Business-Part", limits)), Optional.empty(),
                            Optional.of(new CorrelationSelector("/headers/X-Business-Round", limits)), Optional.empty(), limits);
                    var extracted = mapping.extract(PayloadValue.fromJava(event, limits.input()), UUID::randomUUID);
                    assertEquals(CONTEXT.applicationCorrelationId(), extracted.applicationCorrelationId());
                    assertEquals(CONTEXT.contributionId(), extracted.contributionId()); assertEquals(CONTEXT.roundId(), extracted.roundId());
                    assertNotEquals(event.get("correlationId"), extracted.applicationCorrelationId());
                    assertFalse(payload.containsKey("headers"), "mapping must not mutate caller input");
                } finally { source.stop().toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join(); }
            }
        }
    }

    @Test void refusesMissingContextConflictsAndCaseDuplicatesBeforeProtocolIo() {
        var protocol = new AmqpTestSupport.FakeProtocol();
        var action = behavior(profile(5672, Set.of(HEADER, "x-business-operation")), protocol)
                .create(AmqpTestSupport.configuration(Map.of(CorrelationHeaderMapping.APPLICATION, HEADER)));
        for (var input : List.of(message(AmqpTestSupport.payload(), null),
                message(payload(Map.of(HEADER, "spoof")), CONTEXT),
                message(payload(Map.of(HEADER, "ingestion-42", "x-business-operation", "ingestion-42")), CONTEXT))) {
            var result = (Map<?, ?>) action.handle(input).toCompletableFuture().join().payload();
            assertEquals("REJECTED", result.get("status")); assertTrue(result.toString().contains("CORRELATION_MAPPING_REFUSED"));
        }
        assertEquals(0, protocol.connects.get()); assertEquals(0, protocol.publishes.get());
    }

    @Test void mappedHeadersStillRequireProfileAuthorityAndNativeSizeBounds() {
        var protocol = new AmqpTestSupport.FakeProtocol();
        var action = behavior(profile(5672, Set.of("trace")), protocol)
                .create(AmqpTestSupport.configuration(Map.of(CorrelationHeaderMapping.APPLICATION, HEADER)));
        var result = (Map<?, ?>) action.handle(message(AmqpTestSupport.payload(), CONTEXT)).toCompletableFuture().join().payload();
        assertEquals("REJECTED", result.get("status")); assertTrue(result.toString().contains("INVALID_HEADER"));
        var permitted = behavior(profile(5672, Set.of(HEADER)), protocol)
                .create(AmqpTestSupport.configuration(Map.of(CorrelationHeaderMapping.APPLICATION, HEADER)));
        var large = new ApplicationCorrelationContext("a".repeat(257), Optional.empty(), Optional.empty(), "0", Optional.empty());
        assertEquals("REJECTED", ((Map<?, ?>) permitted.handle(message(AmqpTestSupport.payload(), large)).toCompletableFuture().join().payload()).get("status"));
        assertEquals(0, protocol.connects.get());
    }

    @Test void reservedAndDuplicateTargetsRefuseAtConstruction() {
        var behavior = behavior(profile(5672, Set.of(HEADER)), new AmqpTestSupport.FakeProtocol());
        for (String target : List.of("correlationId", "replyTo", "X-Correlation-ID", "X-Ravenroot-Payload-SHA256"))
            assertThrows(IllegalArgumentException.class, () -> behavior.create(AmqpTestSupport.configuration(Map.of(CorrelationHeaderMapping.APPLICATION, target))));
        assertThrows(IllegalArgumentException.class, () -> behavior.create(AmqpTestSupport.configuration(Map.of(
                CorrelationHeaderMapping.APPLICATION, HEADER, CorrelationHeaderMapping.ROUND, "x-business-operation"))));
    }
    private static Map<String, Object> payload(Map<String, Object> headers) {
        var payload = new LinkedHashMap<String, Object>(AmqpTestSupport.payload()); payload.put("headers", headers); return payload;
    }
    private static NodeMessage message(Object payload, ApplicationCorrelationContext context) {
        var base = AmqpTestSupport.message(payload);
        return new NodeMessage(base.security(), base.processInstanceId(), base.traversalId(), base.invocationId(), base.attemptId(),
                base.parentInvocationIds(), base.nodeId(), payload, base.attributes(), base.command(), context);
    }
    private static AmqpProfile profile(int port, Set<String> headers) {
        return new AmqpProfile(AmqpTestSupport.TENANT, AmqpTestSupport.PROFILE, "127.0.0.1", port, false, "/", "fixture", "fixture-password",
                "", Set.of(""), "orders.q", Set.of("orders.q"), headers, Set.of("responses"), true, 5, 60000, 4, 100, 10000, 4096, 0);
    }
    private static AmqpPublishNodeBehavior behavior(AmqpProfile profile, AmqpProtocol protocol) {
        return new AmqpPublishNodeBehavior(ignored -> Optional.of(new SecretValue("fixture-password".toCharArray())),
                (tenant, name) -> Optional.of(profile), protocol,
                new AmqpRuntimeControls(System::nanoTime, Runnable::run, 32, 16, 128), System::nanoTime, millis -> {},
                ai.ravenroot.api.security.egress.ReservedNetworkPolicy.fromCommaSeparatedExceptions("127.0.0.1:LOOPBACK"));
    }
}
