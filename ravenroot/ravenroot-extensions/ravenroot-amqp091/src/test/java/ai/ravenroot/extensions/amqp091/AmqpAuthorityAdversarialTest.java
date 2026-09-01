package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.SecretValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmqpAuthorityAdversarialTest {
    @Test
    void graphPriorityAndExpirationAreEffectivePayloadCapsIncludingNullAndZero() {
        var protocol = new AmqpTestSupport.FakeProtocol(
                AmqpTestSupport.Event.CONFIRM, AmqpTestSupport.Event.CONFIRM,
                AmqpTestSupport.Event.CONFIRM, AmqpTestSupport.Event.CONFIRM,
                AmqpTestSupport.Event.CONFIRM, AmqpTestSupport.Event.CONFIRM,
                AmqpTestSupport.Event.CONFIRM, AmqpTestSupport.Event.CONFIRM);
        AtomicInteger credentials = new AtomicInteger();
        var behavior = AmqpTestSupport.behavior(protocol, reference -> {
            credentials.incrementAndGet();
            return Optional.of(new SecretValue(AmqpTestSupport.SECRET.toCharArray()));
        }, new AmqpRuntimeControls(System::nanoTime, Runnable::run, 8, 8, 64),
                System::nanoTime, millis -> { });

        var tightened = behavior.create(AmqpTestSupport.configuration(
                Map.of("priority", "2", "expirationMs", "100")));
        assertEquals("CONFIRMED", AmqpTestSupport.output(tightened, AmqpTestSupport.payload()).get("status"));
        assertEquals(2, protocol.publications.getFirst().priority(), "graph value is also the default");
        assertEquals("100", protocol.publications.getFirst().expiration());

        var clearedPriority = new LinkedHashMap<>(AmqpTestSupport.payload());
        clearedPriority.put("priority", null);
        assertEquals("CONFIRMED", AmqpTestSupport.output(tightened, clearedPriority).get("status"));
        assertEquals(null, protocol.publications.get(1).priority());
        assertEquals("100", protocol.publications.get(1).expiration());

        assertEquals("REJECTED", AmqpTestSupport.output(tightened,
                payloadWith("expirationMs", null)).get("status"),
                "payload null must not cancel a finite graph TTL");
        assertEquals("CONFIRMED", AmqpTestSupport.output(tightened,
                payloadWith("expirationMs", 100)).get("status"));
        assertEquals("100", protocol.publications.get(2).expiration());

        assertEquals("REJECTED", AmqpTestSupport.output(tightened,
                payloadWith("priority", 3)).get("status"));
        assertEquals("REJECTED", AmqpTestSupport.output(tightened,
                payloadWith("expirationMs", 101)).get("status"));
        assertEquals("REJECTED", AmqpTestSupport.output(tightened,
                payloadWith("expirationMs", -1)).get("status"));

        var zero = behavior.create(AmqpTestSupport.configuration(
                Map.of("priority", "0", "expirationMs", "0")));
        assertEquals("CONFIRMED", AmqpTestSupport.output(zero, AmqpTestSupport.payload()).get("status"));
        assertEquals(0, protocol.publications.get(3).priority());
        assertEquals("0", protocol.publications.get(3).expiration());
        assertEquals("REJECTED", AmqpTestSupport.output(zero,
                payloadWith("expirationMs", null)).get("status"));
        assertEquals("CONFIRMED", AmqpTestSupport.output(zero,
                payloadWith("expirationMs", 0)).get("status"));
        assertEquals("0", protocol.publications.get(4).expiration());
        assertEquals("REJECTED", AmqpTestSupport.output(zero, payloadWith("priority", 1)).get("status"));
        assertEquals("REJECTED", AmqpTestSupport.output(zero, payloadWith("expirationMs", 1)).get("status"));

        var absent = behavior.create(AmqpTestSupport.configuration());
        assertEquals("CONFIRMED", AmqpTestSupport.output(absent, AmqpTestSupport.payload()).get("status"));
        assertEquals(null, protocol.publications.get(5).priority());
        assertEquals(null, protocol.publications.get(5).expiration());
        assertEquals("CONFIRMED", AmqpTestSupport.output(absent,
                payloadWith("expirationMs", null)).get("status"));
        assertEquals(null, protocol.publications.get(6).expiration());
        assertEquals("CONFIRMED", AmqpTestSupport.output(absent,
                payloadWith("expirationMs", 60_000)).get("status"));
        assertEquals("60000", protocol.publications.get(7).expiration());
        assertEquals("REJECTED", AmqpTestSupport.output(absent,
                payloadWith("expirationMs", 60_001)).get("status"));
        assertEquals(8, credentials.get(), "cap violations must reject before secret resolution");
        assertEquals(8, protocol.connects.get());
        assertEquals(8, protocol.publishes.get());
    }

    @Test
    void everyShortstrIsBoundedByUtf8OctetsBeforeSecretsOrWire() {
        String exactly255Bytes = "é".repeat(127) + "a";
        String over255Bytes = "é".repeat(128);
        AtomicInteger credentials = new AtomicInteger();
        var protocol = new AmqpTestSupport.FakeProtocol(AmqpTestSupport.Event.CONFIRM);
        var behavior = AmqpTestSupport.behavior(protocol, reference -> {
            credentials.incrementAndGet();
            return Optional.of(new SecretValue(AmqpTestSupport.SECRET.toCharArray()));
        }, new AmqpRuntimeControls(System::nanoTime, Runnable::run, 8, 8, 64),
                System::nanoTime, millis -> { });

        assertEquals("CONFIRMED", AmqpTestSupport.output(behavior.create(AmqpTestSupport.configuration()),
                payloadWith("contentType", exactly255Bytes)).get("status"));
        for (String field : List.of("contentType", "contentEncoding", "messageId", "correlationId", "type", "appId")) {
            assertEquals("REJECTED", AmqpTestSupport.output(behavior.create(AmqpTestSupport.configuration()),
                    payloadWith(field, over255Bytes)).get("status"), field);
        }
        assertEquals(1, credentials.get(), "wire-size violations must reject before secret resolution");
        assertEquals(1, protocol.connects.get());

        AmqpProfile valid = AmqpTestSupport.profile();
        assertThrows(IllegalArgumentException.class, () -> new AmqpProfile(valid.tenant(), valid.name(),
                valid.host(), valid.port(), valid.tls(), over255Bytes, valid.username(), valid.credentialRef(),
                valid.defaultExchange(), valid.exchanges(), valid.defaultRoutingKey(), valid.routingKeys(),
                valid.headers(), valid.replyTo(), valid.allowPersistent(), valid.maxPriority(), valid.maxExpirationMs(),
                valid.maxConcurrency(), valid.maxPerSecond(), valid.timeoutMs(), valid.maxBodyBytes(), valid.retries()));

        AmqpProfile boundary = new AmqpProfile(valid.tenant(), valid.name(), valid.host(), valid.port(), valid.tls(),
                exactly255Bytes, valid.username(), valid.credentialRef(), exactly255Bytes, Set.of(),
                exactly255Bytes, Set.of(), valid.headers(), Set.of(exactly255Bytes), valid.allowPersistent(),
                valid.maxPriority(), valid.maxExpirationMs(), valid.maxConcurrency(), valid.maxPerSecond(),
                valid.timeoutMs(), valid.maxBodyBytes(), valid.retries());
        assertTrue(boundary.allowsExchange(exactly255Bytes));
        assertTrue(boundary.allowsRoutingKey(exactly255Bytes));
        assertTrue(boundary.allowsReplyTo(exactly255Bytes));
        assertThrows(IllegalArgumentException.class, () -> new AmqpProfile(valid.tenant(), valid.name(),
                valid.host(), valid.port(), valid.tls(), valid.vhost(), valid.username(), valid.credentialRef(),
                over255Bytes, Set.of(), valid.defaultRoutingKey(), valid.routingKeys(), valid.headers(),
                valid.replyTo(), valid.allowPersistent(), valid.maxPriority(), valid.maxExpirationMs(),
                valid.maxConcurrency(), valid.maxPerSecond(), valid.timeoutMs(), valid.maxBodyBytes(), valid.retries()));

        for (String field : List.of("exchange", "routingKey", "contentType", "contentEncoding", "messageId",
                "correlationId", "replyTo", "type", "appId")) {
            assertEquals("REJECTED", AmqpTestSupport.output(behavior.create(AmqpTestSupport.configuration()),
                    payloadWith(field, over255Bytes)).get("status"), field + " payload shortstr");
            assertEquals("REJECTED", AmqpTestSupport.output(behavior.create(AmqpTestSupport.configuration(
                    Map.of(field, over255Bytes))), AmqpTestSupport.payload()).get("status"),
                    field + " graph shortstr");
        }
        assertEquals(1, credentials.get(), "all graph and payload wire-size violations reject before credentials");
    }

    @Test
    void graphCannotSupplyEndpointCredentialTlsVhostOrUnknownAuthority() {
        for (String forbidden : List.of("host", "port", "tls", "vhost", "username", "credentialRef", "password")) {
            AtomicInteger profiles = new AtomicInteger();
            AtomicInteger credentials = new AtomicInteger();
            var protocol = new AmqpTestSupport.FakeProtocol(AmqpTestSupport.Event.CONFIRM);
            var behavior = new AmqpPublishNodeBehavior(reference -> {
                credentials.incrementAndGet();
                return Optional.of(new SecretValue(AmqpTestSupport.SECRET.toCharArray()));
            }, (tenant, name) -> {
                profiles.incrementAndGet();
                return Optional.of(AmqpTestSupport.profile());
            }, protocol, new AmqpRuntimeControls(System::nanoTime, Runnable::run, 8, 8, 16),
                    System::nanoTime, millis -> { });
            var properties = new LinkedHashMap<String, Object>();
            properties.put("brokerProfile", AmqpTestSupport.PROFILE);
            properties.put(forbidden, "attacker-controlled");
            Map<String, Object> output = AmqpTestSupport.output(behavior.create(new NodeConfiguration(
                    "publish", AmqpPublishNodeBehavior.BEHAVIOR, properties)), AmqpTestSupport.payload());
            assertEquals("REJECTED", output.get("status"), forbidden);
            assertEquals(0, profiles.get(), forbidden + " must be rejected before profile resolution");
            assertEquals(0, credentials.get(), forbidden);
            assertEquals(0, protocol.connects.get(), forbidden);
        }
    }

    @Test
    void profileIdentityAndPublicationAuthorityAreExactAndTenantScoped() {
        AtomicInteger credentials = new AtomicInteger();
        var protocol = new AmqpTestSupport.FakeProtocol(AmqpTestSupport.Event.CONFIRM);
        var mismatch = new AmqpPublishNodeBehavior(reference -> {
            credentials.incrementAndGet();
            return Optional.empty();
        }, (tenant, name) -> Optional.of(AmqpTestSupport.profile("other", name, 1, 1, 1_000, 0)),
                protocol, new AmqpRuntimeControls(System::nanoTime, Runnable::run, 8, 8, 16),
                System::nanoTime, millis -> { });
        assertEquals("REJECTED", AmqpTestSupport.output(mismatch.create(AmqpTestSupport.configuration()),
                AmqpTestSupport.payload()).get("status"));
        assertEquals(0, credentials.get());

        var behavior = AmqpTestSupport.behavior(protocol);
        for (Map<String, Object> payload : List.<Map<String, Object>>of(
                Map.of("version", "amqp.publish.v1", "bodyText", "x", "exchange", "orders-extra"),
                Map.of("version", "amqp.publish.v1", "bodyText", "x", "routingKey", "created.extra"),
                Map.of("version", "amqp.publish.v1", "bodyText", "x", "replyTo", "responses-extra"),
                Map.of("version", "amqp.publish.v1", "bodyText", "x", "headers", Map.of("trace-extra", "x")))) {
            assertEquals("REJECTED", AmqpTestSupport.output(behavior.create(AmqpTestSupport.configuration()), payload)
                    .get("status"));
        }
        assertEquals(0, protocol.connects.get());
    }

    @Test
    void malformedAndOverBudgetBodiesMetadataAndTighteningRejectBeforeSecretsOrWire() {
        AtomicInteger credentials = new AtomicInteger();
        var protocol = new AmqpTestSupport.FakeProtocol();
        var behavior = AmqpTestSupport.behavior(protocol, reference -> {
            credentials.incrementAndGet();
            return Optional.of(new SecretValue(AmqpTestSupport.SECRET.toCharArray()));
        }, new AmqpRuntimeControls(System::nanoTime, Runnable::run, 8, 8, 64),
                System::nanoTime, millis -> { });

        List<Object> invalidPayloads = new ArrayList<>();
        invalidPayloads.add(Map.of("version", "amqp.publish.v1", "bodyText", "x", "unknown", "x"));
        invalidPayloads.add(Map.of("version", "other", "bodyText", "x"));
        invalidPayloads.add(Map.of("version", "amqp.publish.v1"));
        invalidPayloads.add(Map.of("version", "amqp.publish.v1", "bodyText", "x", "bodyBase64", "eA=="));
        invalidPayloads.add(Map.of("version", "amqp.publish.v1", "bodyBase64", "%%%"));
        invalidPayloads.add(Map.of("version", "amqp.publish.v1", "bodyText", "x".repeat(4_097)));
        invalidPayloads.add(Map.of("version", "amqp.publish.v1", "bodyText", "x", "persistent", "true"));
        invalidPayloads.add(Map.of("version", "amqp.publish.v1", "bodyText", "x", "priority", 6));
        invalidPayloads.add(Map.of("version", "amqp.publish.v1", "bodyText", "x", "expirationMs", 60_001));
        invalidPayloads.add(Map.of("version", "amqp.publish.v1", "bodyText", "x", "messageId", "x".repeat(129)));
        invalidPayloads.add(Map.of("version", "amqp.publish.v1", "bodyText", "x", "headers", "not-a-map"));
        for (Object payload : invalidPayloads) {
            assertEquals("REJECTED", AmqpTestSupport.output(behavior.create(AmqpTestSupport.configuration()), payload)
                    .get("status"));
        }

        for (Map<String, Object> invalidConfiguration : List.<Map<String, Object>>of(
                Map.of("mandatory", "false"), Map.of("mandatory", "TRUE"), Map.of("maxConcurrency", "5"),
                Map.of("retries", "3"), Map.of("confirmTimeoutMs", "99"), Map.of("priority", "6"),
                Map.of("expirationMs", "60001"), Map.of("headers", "forbidden=value"),
                Map.of("replyTo", "forbidden"), Map.of("exchange", "forbidden"))) {
            assertEquals("REJECTED", AmqpTestSupport.output(
                    behavior.create(AmqpTestSupport.configuration(invalidConfiguration)), AmqpTestSupport.payload())
                    .get("status"));
        }
        assertEquals(0, credentials.get());
        assertEquals(0, protocol.connects.get());
    }

    @Test
    void environmentCredentialKeysAreInjectiveAndMalformedUtf16FailsClosed() {
        String left = EnvironmentAmqpCredentialResolver.environmentVariableName("tenant-a/mail");
        String right = EnvironmentAmqpCredentialResolver.environmentVariableName("tenant_a-mail");
        assertFalse(left.equals(right));
        var resolver = new EnvironmentAmqpCredentialResolver(Map.of(left, "left", right, "right"));
        try (SecretValue first = resolver.resolve("tenant-a/mail").orElseThrow();
             SecretValue second = resolver.resolve("tenant_a-mail").orElseThrow()) {
            assertFalse(java.util.Arrays.equals(first.copy(), second.copy()));
        }
        assertTrue(resolver.resolve("\uD800").isEmpty());
    }

    private static Map<String, Object> payloadWith(String name, Object value) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("version", "amqp.publish.v1");
        payload.put("bodyText", "x");
        payload.put(name, value);
        return payload;
    }
}
