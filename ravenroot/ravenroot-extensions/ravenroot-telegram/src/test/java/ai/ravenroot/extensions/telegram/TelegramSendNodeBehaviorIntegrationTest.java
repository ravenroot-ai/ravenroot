package ai.ravenroot.extensions.telegram;

import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.SecretValue;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TelegramSendNodeBehaviorIntegrationTest {
    @Test void sendsJsonToTheFixedTokenPathOverTheInjectedHttpsFixture() throws Exception {
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(200, "{\"ok\":true,\"result\":{\"message_id\":42,\"date\":1700000000}}");
            Map<String, Object> payload = new LinkedHashMap<>(TelegramTestSupport.textPayload("hello 🌍"));
            payload.put("parseMode", "HTML");
            payload.put("inlineKeyboard", java.util.List.of(java.util.List.of(
                    Map.of("text", "Open", "url", "https://example.test/path"),
                    Map.of("text", "Ack", "callbackData", "ack"))));
            NodeResult result = send(telegram, payload, 0);

            assertEquals("SENT", output(result).get("status"));
            assertEquals(42L, output(result).get("messageId"));
            assertEquals(1, telegram.requests().size());
            var request = telegram.requests().getFirst();
            assertEquals("/bot" + TelegramTestSupport.TOKEN + "/sendMessage", request.path());
            assertTrue(request.contentType().startsWith("application/json"));
            assertTrue(request.body().contains("hello 🌍"));
            assertFalse(output(result).toString().contains(TelegramTestSupport.TOKEN));
        }
    }

    @Test void classifiesSafeBotApiFailuresAndReturnsOnlyDocumentedParameters() throws Exception {
        assertStatus(401, "{\"ok\":false,\"error_code\":401,\"description\":\"token=" + TelegramTestSupport.TOKEN + "\"}", "REJECTED");
        assertStatus(403, "{\"ok\":false,\"error_code\":403}", "REJECTED");
        assertStatus(500, "{\"ok\":false,\"error_code\":500}", "TEMPORARY_FAILURE");
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(400, "{\"ok\":false,\"error_code\":400,\"parameters\":{\"migrate_to_chat_id\":-100999}}");
            Map<String, Object> output = output(send(telegram, TelegramTestSupport.textPayload("migrate"), 0));
            assertEquals("REJECTED", output.get("status"));
            assertEquals(-100999L, output.get("migrate_to_chat_id"));
        }
    }

    @Test void retriesOnlyBounded429AndNeverRetriesServerOrMalformedResponses() throws Exception {
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(429, "{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":0}}");
            telegram.enqueue(200, "{\"ok\":true,\"result\":{\"message_id\":7,\"date\":8}}");
            Map<String, Object> output = output(send(telegram, TelegramTestSupport.textPayload("retry"), 1));
            assertEquals("SENT", output.get("status"));
            assertEquals(2, output.get("attempt"));
            assertEquals(2, telegram.requests().size());
        }
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(500, "{\"ok\":false,\"error_code\":500}");
            assertEquals("TEMPORARY_FAILURE", output(send(telegram, TelegramTestSupport.textPayload("once"), 3)).get("status"));
            assertEquals(1, telegram.requests().size());
        }
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(200, "application/json", "not-json");
            assertEquals("AMBIGUOUS", output(send(telegram, TelegramTestSupport.textPayload("unknown"), 3)).get("status"));
            assertEquals(1, telegram.requests().size());
        }
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(200, "application/json", " ".repeat(65_537));
            assertEquals("AMBIGUOUS", output(send(telegram, TelegramTestSupport.textPayload("large"), 3)).get("status"));
            assertEquals(1, telegram.requests().size());
        }
    }

    @Test void uploadsOnlyBoundedInlinePhotoBytesAsMultipart() throws Exception {
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(200, "{\"ok\":true,\"result\":{\"message_id\":5,\"date\":6}}");
            byte[] photo = { 1, 2, 3, 4 };
            Map<String, Object> payload = new LinkedHashMap<>(TelegramTestSupport.textPayload("caption"));
            payload.put("photo", Map.of("contentBase64", Base64.getEncoder().encodeToString(photo),
                    "filename", "safe.jpg", "mimeType", "image/jpeg"));
            assertEquals("SENT", output(send(telegram, payload, 0)).get("status"));
            var request = telegram.requests().getFirst();
            assertEquals("/bot" + TelegramTestSupport.TOKEN + "/sendPhoto", request.path());
            assertTrue(request.contentType().startsWith("multipart/form-data; boundary="));
            assertTrue(request.body().contains("name=\"photo\"; filename=\"safe.jpg\""));
        }
    }

    @Test void resolvesEveryInvocationSoRotationRevocationAndSecretClearingTakeEffect() throws Exception {
        String replacement = "987654:zyxwvutsrqponmlkjihgfedcbaZYXWVU";
        AtomicReference<String> current = new AtomicReference<>(TelegramTestSupport.TOKEN);
        ArrayList<SecretValue> issued = new ArrayList<>();
        CredentialResolver credentials = ignored -> {
            String value = current.get();
            if (value == null) return Optional.empty();
            SecretValue secret = new SecretValue(value.toCharArray());
            issued.add(secret);
            return Optional.of(secret);
        };
        String tenant = "tenant-rotation";
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(200, "{\"ok\":true,\"result\":{\"message_id\":1,\"date\":1}}");
            telegram.enqueue(200, "{\"ok\":true,\"result\":{\"message_id\":2,\"date\":2}}");
            var action = TelegramTestSupport.action(tenant, credentials, telegram.client(), telegram.origin(), 0);
            action.handle(TelegramTestSupport.message(tenant, TelegramTestSupport.textPayload("first"))).toCompletableFuture().join();
            current.set(replacement);
            action.handle(TelegramTestSupport.message(tenant, TelegramTestSupport.textPayload("second"))).toCompletableFuture().join();
            assertTrue(telegram.requests().get(0).path().contains(TelegramTestSupport.TOKEN));
            assertTrue(telegram.requests().get(1).path().contains(replacement));
            assertTrue(issued.stream().flatMapToInt(secret -> new String(secret.copy()).chars()).allMatch(character -> character == 0));

            current.set(null);
            CompletionException revoked = assertThrows(CompletionException.class, () -> action.handle(
                    TelegramTestSupport.message(tenant, TelegramTestSupport.textPayload("third"))).toCompletableFuture().join());
            assertEquals(TelegramSendException.Code.CREDENTIAL_UNAVAILABLE,
                    ((TelegramSendException) revoked.getCause()).code());
            assertEquals(2, telegram.requests().size());
        }
    }

    @Test void providerCredentialResolverKeepsTenantProfileReferencesIsolated() throws Exception {
        String otherToken = "777777:abcdefghijklmnopqrstuvWXYZABCD";
        var provider = new ai.ravenroot.api.security.SecretProvider() {
            @Override public String id() { return "test-provider"; }
            @Override public Optional<SecretValue> get(String reference) {
                return switch (reference) {
                    case "tenant-a-bot" -> Optional.of(new SecretValue(TelegramTestSupport.TOKEN.toCharArray()));
                    case "tenant-b-bot" -> Optional.of(new SecretValue(otherToken.toCharArray()));
                    default -> Optional.empty();
                };
            }
        };
        var credentials = new ai.ravenroot.core.security.ProviderCredentialResolver(provider);
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(200, "{\"ok\":true,\"result\":{\"message_id\":1,\"date\":1}}");
            telegram.enqueue(200, "{\"ok\":true,\"result\":{\"message_id\":2,\"date\":2}}");
            TelegramProfileResolver profiles = (tenant, name) -> Optional.of(TelegramTestSupport.profile(tenant,
                    tenant.equals("tenant-a") ? "tenant-a-bot" : "tenant-b-bot", 0));
            var behavior = new TelegramSendNodeBehavior(credentials, profiles, telegram.client(), telegram.origin());
            var configuration = new ai.ravenroot.api.node.NodeConfiguration("telegram", "telegram.send",
                    Map.of("botProfile", TelegramTestSupport.PROFILE));
            behavior.create(configuration).handle(TelegramTestSupport.message("tenant-a",
                    TelegramTestSupport.textPayload("a"))).toCompletableFuture().join();
            behavior.create(configuration).handle(TelegramTestSupport.message("tenant-b",
                    TelegramTestSupport.textPayload("b"))).toCompletableFuture().join();
            assertTrue(telegram.requests().get(0).path().contains(TelegramTestSupport.TOKEN));
            assertFalse(telegram.requests().get(0).path().contains(otherToken));
            assertTrue(telegram.requests().get(1).path().contains(otherToken));
            assertFalse(telegram.requests().get(1).path().contains(TelegramTestSupport.TOKEN));
        }
    }

    @Test void rejectsResolverCrossTenantProfilesBeforeCredentialOrNetworkUse() throws Exception {
        try (var telegram = new FakeTelegramHttps()) {
            var behavior = new TelegramSendNodeBehavior(reference -> fail("credential must not be resolved"),
                    (tenant, name) -> Optional.of(TelegramTestSupport.profile("different-tenant", "secret", 0)),
                    telegram.client(), telegram.origin());
            var action = behavior.create(new ai.ravenroot.api.node.NodeConfiguration("telegram", "telegram.send",
                    Map.of("botProfile", TelegramTestSupport.PROFILE)));
            CompletionException failure = assertThrows(CompletionException.class, () -> action.handle(
                    TelegramTestSupport.message("tenant-a", TelegramTestSupport.textPayload("isolated")))
                    .toCompletableFuture().join());
            assertEquals(TelegramSendException.Code.CONFIGURATION, ((TelegramSendException) failure.getCause()).code());
            assertTrue(telegram.requests().isEmpty());
        }
    }

    @Test void retriesConnectRefusalAsPreAcceptanceAndReportsTheFinalAttempt() throws Exception {
        int unusedPort;
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            unusedPort = socket.getLocalPort();
        }
        // This previously trusted the comment/name alone -- bind, release, hope it stays free --
        // and let the retry logic under test (the very thing this method is pinning) tell us, via its own
        // CONNECT_FAILED/TEMPORARY_FAILURE classification, whether the premise held. That is the same
        // hazard covered by JdkHeaderCapConnectFailureClassificationTest#aRefusedConnectIsInconclusiveNotDirectProof:
        // any misclassification the behavior under test might commit would
        // have been recyclable as "the port was busy," never surfacing as a failure. The premise -- is the
        // just-released port actually refusing connections right now -- is instead established with a
        // plain, bare socket connect, independently of and before the behavior under test ever runs.
        Exception premise = null;
        try (var rawProbe = new java.net.Socket()) {
            rawProbe.connect(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(),
                    unusedPort), 2_000);
        } catch (Exception refusedOrNot) {
            premise = refusedOrNot;
        }
        assumeTrue(premise instanceof ConnectException,
                "premise not met: a plain connect() to the just-released ephemeral port " + unusedPort
                        + " was not refused (" + premise + ") -- something else claimed it in the release "
                        + "window, so this run proves nothing about the connect-refusal retry logic under "
                        + "test, and the premise is reported as skipped, not as a defect.");
        String tenant = "tenant-connect-refusal";
        var client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofMillis(200))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        var action = TelegramTestSupport.action(tenant,
                ignored -> Optional.of(new SecretValue(TelegramTestSupport.TOKEN.toCharArray())), client,
                java.net.URI.create("https://localhost:" + unusedPort), 2);
        Map<String, Object> output = output(action.handle(TelegramTestSupport.message(tenant,
                TelegramTestSupport.textPayload("pre-connect"))).toCompletableFuture().join());
        assertEquals("TEMPORARY_FAILURE", output.get("status"));
        assertEquals(3, output.get("attempt"));
        assertEquals("CONNECT_FAILED", output.get("message"));
    }

    @Test void profileMethodAuthorityAndLocalRateLimitApplyBeforeTransport() throws Exception {
        String authorityTenant = "tenant-method-authority";
        AtomicReference<Integer> resolutions = new AtomicReference<>(0);
        try (var telegram = new FakeTelegramHttps()) {
            var profile = new TelegramProfile(authorityTenant, TelegramTestSupport.PROFILE, "telegram-bot",
                    java.util.Set.of("*"), java.util.Set.of("sendMessage"), java.util.Set.of("example.test"), false,
                    2, 30, 1_000, 2_000, 4_096, 1_024, 20, 0);
            var behavior = new TelegramSendNodeBehavior(reference -> {
                resolutions.set(resolutions.get() + 1);
                return Optional.of(new SecretValue(TelegramTestSupport.TOKEN.toCharArray()));
            }, (tenant, name) -> Optional.of(profile), telegram.client(), telegram.origin());
            var action = behavior.create(new ai.ravenroot.api.node.NodeConfiguration("telegram", "telegram.send",
                    Map.of("botProfile", TelegramTestSupport.PROFILE)));
            Map<String, Object> photo = new LinkedHashMap<>(TelegramTestSupport.textPayload("caption"));
            photo.put("photo", Map.of("contentBase64", Base64.getEncoder().encodeToString(new byte[]{1}),
                    "filename", "photo.jpg", "mimeType", "image/jpeg"));
            CompletionException failure = assertThrows(CompletionException.class, () -> action.handle(
                    TelegramTestSupport.message(authorityTenant, photo)).toCompletableFuture().join());
            assertEquals(TelegramSendException.Code.INVALID_INPUT, ((TelegramSendException) failure.getCause()).code());
            assertEquals(0, resolutions.get());
            assertTrue(telegram.requests().isEmpty());
        }

        String rateTenant = "tenant-rate-" + java.util.UUID.randomUUID();
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(200, "{\"ok\":true,\"result\":{\"message_id\":1,\"date\":1}}");
            var profile = new TelegramProfile(rateTenant, TelegramTestSupport.PROFILE, "telegram-bot",
                    java.util.Set.of("*"), java.util.Set.of("sendMessage"), java.util.Set.of(), false,
                    2, 1, 1_000, 2_000, 4_096, 1_024, 0, 0);
            var action = new TelegramSendNodeBehavior(ignored -> Optional.of(new SecretValue(TelegramTestSupport.TOKEN.toCharArray())),
                    (tenant, name) -> Optional.of(profile), telegram.client(), telegram.origin())
                    .create(new ai.ravenroot.api.node.NodeConfiguration("telegram", "telegram.send",
                            Map.of("botProfile", TelegramTestSupport.PROFILE)));
            assertEquals("SENT", output(action.handle(TelegramTestSupport.message(rateTenant,
                    TelegramTestSupport.textPayload("one"))).toCompletableFuture().join()).get("status"));
            assertEquals("RATE_LIMITED", output(action.handle(TelegramTestSupport.message(rateTenant,
                    TelegramTestSupport.textPayload("two"))).toCompletableFuture().join()).get("status"));
            assertEquals(1, telegram.requests().size());
        }
    }

    private static NodeResult send(FakeTelegramHttps telegram, Map<String, Object> payload, int retries) throws Exception {
        String tenant = "tenant-" + java.util.UUID.randomUUID();
        CredentialResolver credentials = ignored -> Optional.of(new SecretValue(TelegramTestSupport.TOKEN.toCharArray()));
        return TelegramTestSupport.action(tenant, credentials, telegram.client(), telegram.origin(), retries)
                .handle(TelegramTestSupport.message(tenant, payload)).toCompletableFuture().join();
    }
    private static void assertStatus(int http, String response, String expected) throws Exception {
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(http, response);
            Map<String, Object> output = output(send(telegram, TelegramTestSupport.textPayload("status"), 2));
            assertEquals(expected, output.get("status"));
            assertEquals(1, telegram.requests().size());
            assertFalse(output.toString().contains(TelegramTestSupport.TOKEN));
        }
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> output(NodeResult result) {
        return (Map<String, Object>) result.payload();
    }
}
