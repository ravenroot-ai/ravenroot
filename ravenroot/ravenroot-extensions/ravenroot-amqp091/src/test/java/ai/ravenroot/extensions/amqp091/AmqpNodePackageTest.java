package ai.ravenroot.extensions.amqp091;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmqpNodePackageTest {
    @Test
    void exposesPublishAndConsumeBehaviorsWithSafeInspectorContracts() {
        var nodePackage = new AmqpNodePackage();
        assertEquals("ai.ravenroot.extensions.amqp091", nodePackage.id());
        assertEquals(2, nodePackage.behaviors().size());
        var descriptor = nodePackage.behaviors().stream()
                .filter(behavior -> behavior.descriptor().behavior().equals(AmqpPublishNodeBehavior.BEHAVIOR))
                .findFirst().orElseThrow().descriptor();
        assertEquals("amqp.publish", descriptor.behavior());
        var names = descriptor.properties().stream().map(property -> property.name()).collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.Set.of("brokerProfile", "exchange", "routingKey", "mandatory", "contentType",
                "contentEncoding", "persistent", "priority", "expirationMs", "messageId", "correlationId",
                "replyTo", "type", "appId", "headers", "confirmTimeoutMs", "maxConcurrency", "retries",
                // The publish offers the recovery contract, because a consumer keyed on
                // messageId can make a repeat harmless. amqp.consume does not — see
                // AmqpRecoveryRepeatabilityTest for what each of the two tells the recovery loop.
                "recovery.repeatable"), names);
        assertTrue(descriptor.capabilities().containsAll(java.util.Set.of("network", "credential-reference", "side-effect")));
        assertFalse(names.stream().anyMatch(name -> name.matches("(?i).*(host|port|password|credential|tls|vhost).*")));
        var consume = nodePackage.behaviors().stream()
                .filter(behavior -> behavior.descriptor().behavior().equals(AmqpConsumeNodeBehavior.BEHAVIOR))
                .findFirst().orElseThrow().descriptor();
        assertTrue(consume.capabilities().contains("inbound-source"));
    }

    @Test
    void profileRejectsPlaintextRemoteAndUnboundedAuthority() {
        AmqpProfile valid = AmqpTestSupport.profile();
        assertTrue(valid.allowsExchange("orders"));
        assertFalse(valid.allowsExchange("attacker"));
        assertThrows(IllegalArgumentException.class, () -> new AmqpProfile(valid.tenant(), valid.name(),
                "remote.example", 5672, false, valid.vhost(), valid.username(), valid.credentialRef(),
                valid.defaultExchange(), valid.exchanges(), valid.defaultRoutingKey(), valid.routingKeys(),
                valid.headers(), valid.replyTo(), valid.allowPersistent(), valid.maxPriority(), valid.maxExpirationMs(),
                valid.maxConcurrency(), valid.maxPerSecond(), valid.timeoutMs(), valid.maxBodyBytes(), valid.retries()));
    }

    @Test
    void environmentProfileIsTenantScopedAndStrict() {
        String value = "broker.example;5671;true;/;user;secret;orders;audit;created;updated;trace,source;responses;"
                + "true;5;60000;2;10;1000;4096;2";
        String key = EnvironmentAmqpProfileResolver.environmentVariableName("t", "p");
        var resolver = new EnvironmentAmqpProfileResolver(Map.of(key, value));
        assertTrue(resolver.resolve("t", "p").isPresent());
        assertTrue(resolver.resolve("other", "p").isEmpty());
        assertTrue(new EnvironmentAmqpProfileResolver(Map.of(key, value.replace(";true;/", ";TRUE;/")))
                .resolve("t", "p").isEmpty());
    }
}
