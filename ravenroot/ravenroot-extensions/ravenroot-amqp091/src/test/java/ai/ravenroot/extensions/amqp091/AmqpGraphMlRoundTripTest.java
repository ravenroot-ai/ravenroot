package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AmqpGraphMlRoundTripTest {
    @Test
    void roundTripsOnlyOpaqueProfileAuthorizedDefaultsAndTightening() throws Exception {
        Map<String, Object> properties = Map.ofEntries(
                Map.entry("brokerProfile", "production-orders"), Map.entry("exchange", "orders"),
                Map.entry("routingKey", "created"), Map.entry("mandatory", "true"),
                Map.entry("contentType", "application/json"), Map.entry("contentEncoding", "utf-8"),
                Map.entry("persistent", "true"), Map.entry("priority", "3"),
                Map.entry("expirationMs", "5000"), Map.entry("messageId", "graph-default"),
                Map.entry("correlationId", "graph-correlation"), Map.entry("replyTo", "responses"),
                Map.entry("type", "order-created"), Map.entry("appId", "ravenroot"),
                Map.entry("headers", "source=graph"), Map.entry("confirmTimeoutMs", "750"),
                Map.entry("maxConcurrency", "2"), Map.entry("retries", "1"));
        var definition = new GraphDefinition(List.of(GraphNode.start("start"),
                new GraphNode("publish", NodeKind.BEHAVIOR, AmqpPublishNodeBehavior.BEHAVIOR, properties),
                GraphNode.error("error"), GraphNode.end("end")), List.of());
        byte[] xml;
        try (var graph = GraphManager.from(definition); var output = new ByteArrayOutputStream()) {
            graph.writeGraphMl(output);
            xml = output.toByteArray();
        }
        String serialized = new String(xml, StandardCharsets.UTF_8);
        assertFalse(serialized.contains(AmqpTestSupport.SECRET));
        assertFalse(serialized.contains("broker.example.test"));
        assertFalse(serialized.contains("amqp-orders"));
        assertFalse(serialized.matches("(?s).*(password|credentialRef|username|vhost|tls).*"));
        try (var reread = GraphManager.readGraphMl(new ByteArrayInputStream(xml))) {
            GraphNode node = reread.definition().node("publish");
            assertEquals(AmqpPublishNodeBehavior.BEHAVIOR, node.behavior());
            assertEquals("production-orders", node.properties().get("brokerProfile"));
            assertEquals("true", node.properties().get("mandatory"));
            assertEquals("2", node.properties().get("maxConcurrency"));
            assertEquals("1", node.properties().get("retries"));
            assertEquals(properties.keySet(), node.properties().keySet());
        }
    }

    @Test
    void consumerRoundTripContainsOnlyOpaqueAuthorityAndSafeTightening() throws Exception {
        Map<String, Object> properties = Map.ofEntries(
                Map.entry("brokerProfile", "production-orders"), Map.entry("queue", "orders.q"),
                Map.entry("prefetch", "4"), Map.entry("maxInFlight", "3"),
                Map.entry("retryBackoffMs", "100"), Map.entry("maxRetryBackoffMs", "1000"),
                Map.entry("drainTimeoutMs", "500"), Map.entry("poisonAttempts", "3"),
                Map.entry("poisonPolicy", "dead-letter"), Map.entry("deadLetterMode", "broker-dlx"),
                Map.entry("checkpointPolicy", "require-durable"));
        var definition = new GraphDefinition(List.of(GraphNode.start("start"),
                new GraphNode("consume", NodeKind.BEHAVIOR, AmqpConsumeNodeBehavior.BEHAVIOR, properties),
                GraphNode.error("error"), GraphNode.end("end")), List.of());
        byte[] xml;
        try (var graph = GraphManager.from(definition); var output = new ByteArrayOutputStream()) {
            graph.writeGraphMl(output);
            xml = output.toByteArray();
        }
        String serialized = new String(xml, StandardCharsets.UTF_8);
        assertFalse(serialized.contains(AmqpTestSupport.SECRET));
        assertFalse(serialized.contains("broker.example.test"));
        assertFalse(serialized.contains("amqp-orders"));
        assertFalse(serialized.matches("(?s).*(password|credentialRef|username|vhost|tls).*"));
        try (var reread = GraphManager.readGraphMl(new ByteArrayInputStream(xml))) {
            GraphNode node = reread.definition().node("consume");
            assertEquals(AmqpConsumeNodeBehavior.BEHAVIOR, node.behavior());
            assertEquals(properties, node.properties());
        }
    }
}
