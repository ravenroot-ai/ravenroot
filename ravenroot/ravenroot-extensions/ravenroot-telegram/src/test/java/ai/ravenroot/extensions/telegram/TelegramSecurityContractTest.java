package ai.ravenroot.extensions.telegram;

import ai.ravenroot.api.security.EnvironmentKeyCodec;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TelegramSecurityContractTest {
    @Test void descriptorCarriesOnlyOpaqueProfileAndTighteningProperties() {
        for (var descriptor : new TelegramNodePackage().behaviors().stream().map(behavior -> behavior.descriptor()).toList()) {
            assertEquals("Telegram", descriptor.category());
            Set<String> names = descriptor.properties().stream().map(property -> property.name())
                    .collect(java.util.stream.Collectors.toSet());
            assertTrue(names.contains("botProfile"));
            assertFalse(names.stream().anyMatch(name -> name.toLowerCase()
                    .matches(".*(token|secret|credential|url|host|method|chat).*")));
        }
    }

    @Test void packageDeclaresTheSdkAndUniqueBehavior() {
        var nodePackage = new TelegramNodePackage();
        assertEquals(ai.ravenroot.api.node.NodeSdk.CONTRACT, nodePackage.sdkContract());
        assertEquals(Set.of("telegram.send", "telegram.answer.callback", "telegram.edit.message",
                        "telegram.delete.message"),
                nodePackage.behaviors().stream().map(behavior -> behavior.descriptor().behavior())
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(4, nodePackage.behaviors().size());
    }

    @Test void productionOriginIsFixedAndNeverGraphConfiguration() {
        assertEquals("https://api.telegram.org", TelegramSendNodeBehavior.PRODUCTION_ORIGIN.toString());
        assertEquals(TelegramSendNodeBehavior.PRODUCTION_ORIGIN, TelegramBotApiClient.PRODUCTION_ORIGIN);
        assertTrue(new TelegramNodePackage().behaviors().stream().flatMap(behavior -> behavior.descriptor()
                        .properties().stream()).noneMatch(property ->
                        Set.of("url", "host", "origin", "baseUrl").contains(property.name())));
    }

    @Test void graphLimitsCanOnlyTightenTheOperatorProfile() {
        String tenant = "tenant-tightening";
        AtomicInteger credentials = new AtomicInteger();
        var behavior = new TelegramSendNodeBehavior(reference -> { credentials.incrementAndGet(); return Optional.empty(); },
                (requestedTenant, name) -> Optional.of(TelegramTestSupport.profile(tenant, "credential", 1)));
        var action = behavior.create(new ai.ravenroot.api.node.NodeConfiguration("telegram", "telegram.send",
                Map.of("botProfile", TelegramTestSupport.PROFILE, "requestTimeoutMs", "3000", "retries", "2")));
        CompletionException failure = assertThrows(CompletionException.class, () -> action.handle(
                TelegramTestSupport.message(tenant, TelegramTestSupport.textPayload("tightening")))
                .toCompletableFuture().join());
        assertEquals(TelegramSendException.Code.CONFIGURATION, ((TelegramSendException) failure.getCause()).code());
        assertEquals(0, credentials.get());
    }

    @Test void environmentKeysAreInjectiveAndInvalidProfilesFailClosed() {
        assertNotEquals(EnvironmentKeyCodec.hex("tenant-a"), EnvironmentKeyCodec.hex("tenant_a"));
        assertEquals("C3A9", EnvironmentKeyCodec.hex("é"));
        String key = "RAVENROOT_TELEGRAM_PROFILE_74656E616E74_70726F66696C65";
        var valid = new EnvironmentTelegramProfileResolver(Map.of(key,
                "credential;*;sendMessage;;false;1;1;100;100;10;10;0;0"));
        assertTrue(valid.resolve("tenant", "profile").isPresent());
        var invalid = new EnvironmentTelegramProfileResolver(Map.of(key,
                "credential;*;sendMessage;;not-a-boolean;1;1;100;100;10;10;0;0"));
        assertTrue(invalid.resolve("tenant", "profile").isEmpty());
    }

    @Test void actionProfilesCannotCrossTenantBoundaries() throws Exception {
        AtomicInteger credentials = new AtomicInteger();
        try (var telegram = new FakeTelegramHttps()) {
            var behavior = new TelegramActionNodeBehavior(TelegramActionNodeBehavior.Kind.DELETE_MESSAGE,
                    ignored -> { credentials.incrementAndGet(); return Optional.empty(); },
                    (tenant, name) -> Optional.of(TelegramTestSupport.profile("different-tenant", "secret", 0)),
                    telegram.client(), telegram.origin());
            var action = behavior.create(new ai.ravenroot.api.node.NodeConfiguration("telegram",
                    "telegram.delete.message", Map.of("botProfile", TelegramTestSupport.PROFILE)));
            CompletionException failure = assertThrows(CompletionException.class, () -> action.handle(
                    TelegramTestSupport.message("tenant-a", Map.of("version", "telegram.delete.message.v1",
                            "chatId", "-100123", "messageId", 1))).toCompletableFuture().join());
            assertEquals(TelegramSendException.Code.CONFIGURATION,
                    ((TelegramSendException) failure.getCause()).code());
            assertEquals(0, credentials.get());
            assertTrue(telegram.requests().isEmpty());
        }
    }
}
