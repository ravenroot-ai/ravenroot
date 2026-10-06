package ai.ravenroot.extensions.telegram;

import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;
import org.junit.jupiter.api.Test;

import java.net.NetworkInterface;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TelegramUrlHostIdentityTest {
    @Test void directAndEnvironmentProfilesPreserveEveryZoneByte() {
        for (boolean environment : List.of(false, true)) {
            for (String host : List.of("[FE80::A%ETH0]", "[FE80::A%eth0]",
                    "FE80::A%ETH0", "[FE80::A%25ETH0]", "[FE80::A%2]", "[FE80::A%252]")) {
                TelegramProfile profile = profile(host, environment);
                String exact = host.replace("FE80::A", "fe80::a");
                assertEquals(Set.of(exact), profile.allowedUrlHosts());
                assertTrue(profile.allowsUrlHost(exact));
                assertTrue(profile.allowsUrlHost(host));
                for (String other : List.of("[fe80::a%ETH0]", "[fe80::a%eth0]",
                        "fe80::a%ETH0", "[fe80::a%25ETH0]", "[fe80::a%2]", "[fe80::a%252]")) {
                    assertEquals(exact.equals(other), profile.allowsUrlHost(other), host + " / " + other);
                }
            }
            assertTrue(profile("EXAMPLE.TEST", environment).allowsUrlHost("example.test"));
            assertTrue(profile("example.test", environment).allowsUrlHost("EXAMPLE.TEST"));
            assertTrue(profile("*", environment).allowsUrlHost("[fe80::a%Other]"));
            assertFalse(profile("", environment).allowsUrlHost("example.test"));
            assertFalse(profile("*.test", environment).allowsUrlHost("example.test"));
        }
    }

    @Test void numericScopesRemainSeparateThroughBothRealCallers() throws Exception {
        ReservedNetworkPolicy policy = ReservedNetworkPolicy.fromCommaSeparatedExceptions(
                "localhost:LOOPBACK,[fe80::a%2]:LINK_LOCAL,[fe80::a%25252]:LINK_LOCAL");
        String exact = "https://[fe80::a%2]/game";
        String different = "https://[fe80::a%252]/game";
        assertTrue(policy.permitsLiteral(URI.create(exact).getHost()));
        assertTrue(policy.permitsLiteral(URI.create(different).getHost()));
        for (boolean environment : List.of(false, true)) {
            TelegramProfile profile = profile("[FE80::A%2]", environment);
            assertBothCallers(profile, exact, policy, true);
            assertBothCallers(profile, different, policy, false);
            assertBothCallers(profile("*", environment), different, policy, true);
            assertBothCallers(profile("*", environment), exact,
                    ReservedNetworkPolicy.shippedDefault(), false);
        }
    }

    @Test void dnsCaseAndUrlSafetyRemainEnforcedThroughBothRealCallers() throws Exception {
        for (boolean environment : List.of(false, true)) {
            TelegramProfile profile = profile("EXAMPLE.TEST", environment);
            assertBothCallers(profile, "https://example.test/game", ReservedNetworkPolicy.shippedDefault(), true);
            assertBothCallers(profile, "https://EXAMPLE.TEST/game", ReservedNetworkPolicy.shippedDefault(), true);
            for (String invalid : List.of("https://other.test/game", "http://example.test/game",
                    "https://user:secret-sentinel@example.test/game", "https://[invalid")) {
                assertBothCallers(profile, invalid, ReservedNetworkPolicy.shippedDefault(), false);
            }
        }
    }

    @Test void resolvableNamedInterfaceCannotWidenEitherCallerProfile() throws Exception {
        // Physical named scopes depend on the JDK/OS. The deterministic profile matrix above
        // always runs; this separate observation uses an existing interface, never creates one.
        String host = null;
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            String name = network.getName();
            if (name.equals(name.toUpperCase(Locale.ROOT))) continue;
            String candidate = "[fe80::a%" + name + "]";
            ReservedNetworkPolicy policy = namedPolicy(candidate);
            if (policy.permitsLiteral(candidate)) { host = candidate; break; }
        }
        assumeTrue(host != null, "No resolvable named IPv6 interface with a case-distinct name");
        ReservedNetworkPolicy policy = namedPolicy(host);
        String url = "https://" + host + "/game";
        assertTrue(policy.permitsLiteral(host));
        for (boolean environment : List.of(false, true)) {
            assertBothCallers(profile(host, environment), url, policy, true);
            assertBothCallers(profile(host.toUpperCase(Locale.ROOT), environment), url, policy, false);
        }
    }

    private static ReservedNetworkPolicy namedPolicy(String host) {
        return ReservedNetworkPolicy.fromCommaSeparatedExceptions("localhost:LOOPBACK," + host + ":LINK_LOCAL");
    }

    private static TelegramProfile profile(String host, boolean environment) {
        if (environment) {
            String key = EnvironmentTelegramProfileResolver.environmentVariableName("tenant", "primary");
            return new EnvironmentTelegramProfileResolver(Map.of(key,
                    "telegram-bot;-100123;sendMessage,answerCallbackQuery;" + host
                            + ";false;4;30;1000;2000;4096;10000000;20;0"))
                    .resolve("tenant", "primary").orElseThrow();
        }
        return new TelegramProfile("tenant", "primary", "telegram-bot", Set.of("-100123"),
                Set.of("sendMessage", "answerCallbackQuery"), host.isEmpty() ? Set.of() : Set.of(host),
                false, 4, 30, 1_000, 2_000, 4_096, 10_000_000, 20, 0);
    }

    private static void assertBothCallers(TelegramProfile profile, String url,
                                          ReservedNetworkPolicy policy, boolean allowed) throws Exception {
        var keyboard = List.of(List.of(Map.of("text", "go", "url", url)));
        if (allowed) {
            assertEquals(url, TelegramSendNodeBehavior.keyboard(keyboard, 20, profile, policy)
                    .getFirst().getFirst().get("url"));
        } else {
            assertSafeRefusal(assertThrows(TelegramSendException.class,
                    () -> TelegramSendNodeBehavior.keyboard(keyboard, 20, profile, policy)), url);
        }
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(200, "{\"ok\":true,\"result\":true}");
            var controls = new TelegramRuntimeControls(System::nanoTime, Runnable::run, 32, 16, 4_096, 4_096);
            var behavior = new TelegramActionNodeBehavior(TelegramActionNodeBehavior.Kind.ANSWER_CALLBACK,
                    ignored -> Optional.of(new SecretValue(TelegramTestSupport.TOKEN.toCharArray())),
                    (tenant, name) -> Optional.of(profile), telegram.client(), telegram.origin(), controls, policy);
            var action = behavior.create(new NodeConfiguration("telegram", behavior.descriptor().behavior(),
                    Map.of("botProfile", "primary")));
            var message = TelegramTestSupport.message("tenant", Map.of("version", "telegram.answer.callback.v1",
                    "callbackId", "callback-1", "url", url));
            if (allowed) {
                var output = (Map<?, ?>) action.handle(message).toCompletableFuture().join().payload();
                assertEquals("ANSWERED", output.get("status"));
                assertEquals(1, telegram.requests().size());
                assertTrue(telegram.requests().getFirst().path().endsWith("/answerCallbackQuery"));
                assertTrue(telegram.requests().getFirst().body().contains("\"url\":\"" + url + "\""));
            } else {
                CompletionException failure = assertThrows(CompletionException.class,
                        () -> action.handle(message).toCompletableFuture().join());
                assertSafeRefusal(assertInstanceOf(TelegramSendException.class, failure.getCause()), url);
                assertTrue(telegram.requests().isEmpty());
            }
        }
    }

    private static void assertSafeRefusal(TelegramSendException failure, String url) {
        assertEquals(TelegramSendException.Code.INVALID_INPUT, failure.code());
        assertFalse(failure.getMessage().contains(url));
        assertFalse(failure.getMessage().contains("secret-sentinel"));
        assertNull(failure.getCause());
    }
}
