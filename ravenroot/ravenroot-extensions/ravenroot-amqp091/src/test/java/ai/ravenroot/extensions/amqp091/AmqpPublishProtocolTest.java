package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.ravenroot.extensions.amqp091.AmqpTestSupport.Event;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmqpPublishProtocolTest {
    @Test
    void mapsConfirmNackReturnCloseTimeoutAndPostPublishThrowExactlyOnce() {
        assertStatus(Event.CONFIRM, "CONFIRMED");
        assertStatus(Event.NACK, "NACKED");
        Map<String, Object> returned = execute(Event.RETURN, Map.of("confirmTimeoutMs", "100"));
        assertEquals("RETURNED", returned.get("status"));
        assertEquals("UNROUTABLE", returned.get("reason"));
        @SuppressWarnings("unchecked") Map<String, Object> metadata = (Map<String, Object>) returned.get("return");
        assertEquals(312, metadata.get("replyCode"));
        assertFalse(metadata.toString().contains("\n"));
        assertFalse(metadata.toString().contains("\r"));
        assertFalse(metadata.toString().contains("\0"));
        assertStatus(Event.CLOSE, "AMBIGUOUS");
        assertStatus(Event.THROW, "AMBIGUOUS");
        assertStatus(Event.SILENT, "AMBIGUOUS");

        assertStatus(Event.RETURN_THEN_CONFIRM, "RETURNED");
        assertStatus(Event.CONFIRM_THEN_RETURN, "CONFIRMED");
        assertStatus(Event.RETURN_THEN_CLOSE, "RETURNED");
        assertStatus(Event.RETURN_THEN_NACK, "NACKED");
    }

    @Test
    void retriesOnlyProvenPrePublishConnectionFailuresWithinOneDeadline() {
        var protocol = new AmqpTestSupport.FakeProtocol().temporaryFailures(2, Event.CONFIRM);
        AtomicInteger resolutions = new AtomicInteger();
        var behavior = AmqpTestSupport.behavior(protocol, reference -> {
            resolutions.incrementAndGet();
            return Optional.of(new SecretValue(AmqpTestSupport.SECRET.toCharArray()));
        }, new AmqpRuntimeControls(System::nanoTime, Runnable::run, 8, 8, 32), System::nanoTime, millis -> { });
        Map<String, Object> output = AmqpTestSupport.output(
                behavior.create(AmqpTestSupport.configuration(Map.of("retries", "2"))), AmqpTestSupport.payload());
        assertEquals("CONFIRMED", output.get("status"));
        assertEquals(3, output.get("attemptCount"));
        assertEquals(3, protocol.connects.get());
        assertEquals(1, protocol.publishes.get());
        assertEquals(1, resolutions.get(), "credential resolution must not be duplicated across retries");

        var postPublish = new AmqpTestSupport.FakeProtocol(Event.THROW, Event.CONFIRM);
        Map<String, Object> ambiguous = AmqpTestSupport.output(
                AmqpTestSupport.behavior(postPublish).create(AmqpTestSupport.configuration(Map.of("retries", "2"))),
                AmqpTestSupport.payload());
        assertEquals("AMBIGUOUS", ambiguous.get("status"));
        assertEquals(1, postPublish.connects.get(), "post-publish uncertainty must never be retried");
    }

    @Test
    void permanentConnectionRejectionIsTerminalAndTemporaryExhaustionIsTyped() {
        var permanent = new AmqpTestSupport.FakeProtocol().permanentFailure();
        Map<String, Object> rejected = AmqpTestSupport.output(
                AmqpTestSupport.behavior(permanent).create(AmqpTestSupport.configuration(Map.of("retries", "2"))),
                AmqpTestSupport.payload());
        assertEquals("PERMANENT_FAILURE", rejected.get("status"));
        assertEquals(1, rejected.get("attemptCount"));
        assertEquals(1, permanent.connects.get());

        var temporary = new AmqpTestSupport.FakeProtocol().temporaryFailures(3, Event.CONFIRM);
        Map<String, Object> failed = AmqpTestSupport.output(
                AmqpTestSupport.behavior(temporary).create(AmqpTestSupport.configuration(Map.of("retries", "2"))),
                AmqpTestSupport.payload());
        assertEquals("TEMPORARY_FAILURE", failed.get("status"));
        assertEquals(3, failed.get("attemptCount"));
        assertEquals(0, temporary.publishes.get());
    }

    @Test
    void totalDeadlineAlsoBoundsBackoff() {
        var ticker = new AmqpTestSupport.MutableTicker();
        var protocol = new AmqpTestSupport.FakeProtocol().temporaryFailures(2, Event.CONFIRM);
        var controls = new AmqpRuntimeControls(ticker, Runnable::run, 8, 8, 32);
        var behavior = AmqpTestSupport.behavior(protocol,
                reference -> Optional.of(new SecretValue(AmqpTestSupport.SECRET.toCharArray())), controls, ticker,
                millis -> ticker.advanceMillis(100));
        Map<String, Object> output = AmqpTestSupport.output(
                behavior.create(AmqpTestSupport.configuration(Map.of("confirmTimeoutMs", "100", "retries", "2"))),
                AmqpTestSupport.payload());
        assertEquals("TEMPORARY_FAILURE", output.get("status"));
        assertTrue((Integer) output.get("attemptCount") <= 3);
    }

    @Test
    void publishesTextJsonAndBase64WithBoundedMetadata() {
        var protocol = new AmqpTestSupport.FakeProtocol(Event.CONFIRM, Event.CONFIRM, Event.CONFIRM);
        var action = AmqpTestSupport.behavior(protocol).create(AmqpTestSupport.configuration(Map.of(
                "contentEncoding", "utf-8", "persistent", "true", "priority", "3", "expirationMs", "5000",
                "replyTo", "responses", "headers", "source=graph", "type", "order", "appId", "ravenroot")));
        assertEquals("CONFIRMED", AmqpTestSupport.output(action,
                Map.of("version", "amqp.publish.v1", "bodyText", "héllo")).get("status"));
        assertEquals("CONFIRMED", AmqpTestSupport.output(action,
                Map.of("version", "amqp.publish.v1", "bodyJson", Map.of("z", 1, "a", List.of(true)))).get("status"));
        assertEquals("CONFIRMED", AmqpTestSupport.output(action,
                Map.of("version", "amqp.publish.v1", "bodyBase64",
                        Base64.getEncoder().encodeToString(new byte[]{0, 1, 2}))).get("status"));

        assertArrayEquals("héllo".getBytes(StandardCharsets.UTF_8), protocol.publications.get(0).body());
        assertEquals("text/plain", protocol.publications.get(0).contentType());
        assertEquals("application/json", protocol.publications.get(1).contentType());
        assertArrayEquals(new byte[]{0, 1, 2}, protocol.publications.get(2).body());
        assertEquals("application/octet-stream", protocol.publications.get(2).contentType());
        var publication = protocol.publications.getFirst();
        assertTrue(publication.mandatory());
        assertTrue(publication.persistent());
        assertEquals(3, publication.priority());
        assertEquals("5000", publication.expiration());
        assertEquals("responses", publication.replyTo());
        assertEquals(Map.of("source", "graph"), publication.headers());
    }

    @Test
    void outputCarriesOnlySafeRoutingAndCorrelationMetadata() {
        Map<String, Object> output = execute(Event.CONFIRM, Map.of());
        assertEquals("orders", output.get("exchange"));
        assertEquals("created", output.get("routingKey"));
        assertEquals("m-1", output.get("messageId"));
        assertEquals("c-1", output.get("correlationId"));
        assertEquals(1, output.get("attemptCount"));
        assertFalse(output.toString().contains(AmqpTestSupport.SECRET));
    }

    @Test
    void reservedLiteralProfileIsRefusedBeforeCredentialOrProtocolAndExactExceptionReachesTransport() {
        AmqpProfile base = AmqpTestSupport.profile();
        AmqpProfile literal = new AmqpProfile(base.tenant(), base.name(), "127.0.0.1", base.port(), base.tls(),
                base.vhost(), base.username(), base.credentialRef(), base.defaultExchange(), base.exchanges(),
                base.defaultRoutingKey(), base.routingKeys(), base.headers(), base.replyTo(), base.allowPersistent(),
                base.maxPriority(), base.maxExpirationMs(), base.maxConcurrency(), base.maxPerSecond(),
                base.timeoutMs(), base.maxBodyBytes(), base.retries());
        AtomicInteger credentials = new AtomicInteger();
        var deniedProtocol = new AmqpTestSupport.FakeProtocol(Event.CONFIRM);
        var denied = new AmqpPublishNodeBehavior(reference -> {
            credentials.incrementAndGet();
            return Optional.of(new SecretValue(AmqpTestSupport.SECRET.toCharArray()));
        }, (tenant, name) -> Optional.of(literal), deniedProtocol,
                new AmqpRuntimeControls(System::nanoTime, Runnable::run, 8, 8, 32),
                System::nanoTime, millis -> { }, ReservedNetworkPolicy.denyAllReserved());
        assertEquals("PERMANENT_FAILURE", AmqpTestSupport.output(
                denied.create(AmqpTestSupport.configuration()), AmqpTestSupport.payload()).get("status"));
        assertEquals(0, credentials.get());
        assertEquals(0, deniedProtocol.connects.get());

        var allowedProtocol = new AmqpTestSupport.FakeProtocol(Event.CONFIRM);
        var allowed = new AmqpPublishNodeBehavior(
                reference -> Optional.of(new SecretValue(AmqpTestSupport.SECRET.toCharArray())),
                (tenant, name) -> Optional.of(literal), allowedProtocol,
                new AmqpRuntimeControls(System::nanoTime, Runnable::run, 8, 8, 32),
                System::nanoTime, millis -> { },
                ReservedNetworkPolicy.fromCommaSeparatedExceptions("127.0.0.1:LOOPBACK"));
        assertEquals("CONFIRMED", AmqpTestSupport.output(
                allowed.create(AmqpTestSupport.configuration()), AmqpTestSupport.payload()).get("status"));
        assertEquals(1, allowedProtocol.connects.get());
    }

    private static void assertStatus(Event event, String expected) {
        assertEquals(expected, execute(event, Map.of("confirmTimeoutMs", "100")).get("status"));
    }

    private static Map<String, Object> execute(Event event, Map<String, Object> configuration) {
        return AmqpTestSupport.output(AmqpTestSupport.behavior(new AmqpTestSupport.FakeProtocol(event))
                .create(AmqpTestSupport.configuration(configuration)), AmqpTestSupport.payload());
    }
}
