package ai.ravenroot.extensions.discord;

import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class DiscordSendNodeBehaviorTest {
    @TempDir Path directory;

    @Test void sendsThroughManagedBindingWithFiniteLimitsAndDisabledMentions() {
        var http = new DiscordTestSupport.HttpHarness().reply(200,
                Map.of("id", "923456789012345678", "channel_id", DiscordTestSupport.CHANNEL, "content", "remote-copy"));
        NodeAction action = action(http, Map.of());
        var result = action.handle(DiscordTestSupport.message(message("hello", List.of()))).toCompletableFuture().join();
        assertEquals("sent", ((Map<?, ?>) result.payload()).get("status"));
        assertFalse(result.payload().toString().contains("hello")); assertFalse(result.payload().toString().contains("remote-copy"));
        var request = http.requests.getFirst();
        assertEquals("https://discord.com/api/v10/channels/" + DiscordTestSupport.CHANNEL + "/messages",
                request.destination().toString());
        assertEquals("discord-bot", request.credential().orElseThrow().bindingId());
        assertEquals("discord-bot-token", request.credential().orElseThrow().reference());
        assertEquals(1_048_576, request.limits().maximumRequestBytes());
        assertEquals(65_536, request.limits().maximumEncodedResponseBytes());
        assertTrue(request.representationPolicy().validates(200)); assertFalse(request.representationPolicy().validates(429));
        Map<String, Object> body = DiscordValues.json(request.body());
        assertEquals(Map.of("parse", List.of()), body.get("allowed_mentions")); assertEquals("hello", body.get("content"));
        assertFalse(request.headers().containsKey("authorization"));
    }

    @Test void encodesOnlyBoundedInlineAttachmentsAndRejectsAuthorityExpansionBeforeTransport() {
        var http = new DiscordTestSupport.HttpHarness().reply(200,
                Map.of("id", "923456789012345678", "channel_id", DiscordTestSupport.CHANNEL));
        NodeAction action = action(http, Map.of("maxAttachmentBytes", "8", "maxAttachments", "1"));
        Map<String, Object> attachment = Map.of("contentBase64", java.util.Base64.getEncoder()
                .encodeToString("file".getBytes(StandardCharsets.UTF_8)), "filename", "proof.txt", "mediaType", "text/plain");
        action.handle(DiscordTestSupport.message(message("", List.of(attachment)))).toCompletableFuture().join();
        String multipart = new String(http.requests.getFirst().body(), StandardCharsets.ISO_8859_1);
        assertTrue(multipart.contains("name=\"payload_json\"")); assertTrue(multipart.contains("name=\"files[0]\""));
        assertTrue(multipart.contains("proof.txt")); assertTrue(multipart.contains("\"allowed_mentions\":{\"parse\":[]}"));

        Map<String, Object> remote = new java.util.LinkedHashMap<>(message("hello", List.of()));
        remote.put("channelId", "999999999999999999");
        assertFailure(action.handle(DiscordTestSupport.message(remote)), DiscordException.Code.FORBIDDEN);
        Map<String, Object> url = new java.util.LinkedHashMap<>(message("hello", List.of())); url.put("url", "https://example.invalid");
        assertFailure(action.handle(DiscordTestSupport.message(url)), DiscordException.Code.INVALID_INPUT);
        assertEquals(1, http.requests.size());
    }

    @Test void retriesOnlyDefiniteRateLimitAndNeverRetriesAmbiguousTransport() {
        var http = new DiscordTestSupport.HttpHarness()
                .reply(429, Map.of("retry-after", List.of("0.001")), Map.of("message", "do-not-read"))
                .reply(200, Map.of("id", "923456789012345678", "channel_id", DiscordTestSupport.CHANNEL));
        var result = action(http, Map.of("retries", "1")).handle(
                DiscordTestSupport.message(message("hello", List.of()))).toCompletableFuture().join();
        assertEquals("sent", ((Map<?, ?>) result.payload()).get("status")); assertEquals(2, http.requests.size());

        var failed = new DiscordTestSupport.HttpHarness();
        failed.pending = OutboundCall.failed(new NodePackageServiceException(NodePackageServiceException.Reason.TRANSPORT_FAILED));
        assertFailure(action(failed, Map.of("retries", "2")).handle(
                DiscordTestSupport.message(message("hello", List.of()))), DiscordException.Code.INDETERMINATE);
        assertEquals(1, failed.requests.size());
    }

    @Test void providerBucketExhaustionBlocksTheNextLocalDispatch() {
        var http = new DiscordTestSupport.HttpHarness().reply(200,
                Map.of("x-ratelimit-remaining", List.of("0"), "x-ratelimit-reset-after", List.of("1")),
                Map.of("id", "923456789012345678", "channel_id", DiscordTestSupport.CHANNEL));
        NodeAction action = action(http, Map.of());
        assertEquals("sent", ((Map<?, ?>) action.handle(DiscordTestSupport.message(message("one", List.of())))
                .toCompletableFuture().join().payload()).get("status"));
        assertEquals("rate-limited", ((Map<?, ?>) action.handle(DiscordTestSupport.message(message("two", List.of())))
                .toCompletableFuture().join().payload()).get("status"));
        assertEquals(1, http.requests.size());
    }

    @Test void rejectsFullyEncodedJsonAndMultipartBeyondTheOperatorRequestCeiling() {
        var jsonHttp = new DiscordTestSupport.HttpHarness();
        assertFailure(action(jsonHttp, Map.of(), 64).handle(DiscordTestSupport.message(
                message("x".repeat(100), List.of()))), DiscordException.Code.INVALID_INPUT);
        assertTrue(jsonHttp.requests.isEmpty());

        var multipartHttp = new DiscordTestSupport.HttpHarness();
        Map<String, Object> attachment = Map.of("contentBase64", java.util.Base64.getEncoder()
                .encodeToString("x".repeat(128).getBytes(StandardCharsets.UTF_8)),
                "filename", "proof.txt", "mediaType", "text/plain");
        assertFailure(action(multipartHttp, Map.of(), 300).handle(DiscordTestSupport.message(
                message("", List.of(attachment)))), DiscordException.Code.INVALID_INPUT);
        assertTrue(multipartHttp.requests.isEmpty(), "multipart framing must count toward the request ceiling");
    }

    @Test void cancellationDuringProviderBackoffStopsBeforeAnotherDispatch() {
        var http = new DiscordTestSupport.HttpHarness().reply(429,
                Map.of("retry-after", List.of("1")), Map.of("message", "not-retained"));
        TestCancellation cancellation = new TestCancellation();
        var result = action(http, Map.of("retries", "1")).handle(
                DiscordTestSupport.message(message("hello", List.of())), cancellation);
        waitForRequests(http, 1); cancellation.cancel();
        assertFailure(result, DiscordException.Code.CANCELLED);
        assertEquals(1, http.requests.size());
    }

    @Test void cancellationCancelsManagedCallAndConcurrencyPermitRecovers() {
        var http = new DiscordTestSupport.HttpHarness(); CompletableFuture<OutboundHttpResponse> pending = new CompletableFuture<>();
        http.pending = new OutboundCall<>() {
            @Override public java.util.concurrent.CompletionStage<OutboundHttpResponse> completion() { return pending; }
            @Override public boolean cancel() { return pending.cancel(true); }
        };
        NodeAction action = action(http, Map.of("maxConcurrency", "1")); TestCancellation cancellation = new TestCancellation();
        var first = action.handle(DiscordTestSupport.message(message("one", List.of())), cancellation);
        waitForRequests(http, 1);
        var second = action.handle(DiscordTestSupport.message(message("two", List.of()))).toCompletableFuture().join();
        assertEquals("capacity", ((Map<?, ?>) second.payload()).get("status"));
        cancellation.cancel(); assertFailure(first, DiscordException.Code.CANCELLED);

        http.pending = null; http.reply(200, Map.of("id", "923456789012345678", "channel_id", DiscordTestSupport.CHANNEL));
        var third = action.handle(DiscordTestSupport.message(message("three", List.of()))).toCompletableFuture().join();
        assertEquals("sent", ((Map<?, ?>) third.payload()).get("status"));
    }

    private NodeAction action(DiscordTestSupport.HttpHarness services, Map<String, String> extra) {
        return action(services, extra, 1_048_576);
    }
    private NodeAction action(DiscordTestSupport.HttpHarness services, Map<String, String> extra,
                              int maximumRequestBytes) {
        DiscordNodePackage nodePackage = DiscordTestSupport.nodePackage(
                directory.resolve("deliveries-" + maximumRequestBytes + ".db"), maximumRequestBytes);
        Map<String, Object> properties = new java.util.LinkedHashMap<>(); properties.put("discordProfile", DiscordTestSupport.PROFILE);
        properties.putAll(extra);
        return DiscordTestSupport.behavior(nodePackage, DiscordBehaviorDescriptors.SEND)
                .create(new NodeConfiguration("discord", DiscordBehaviorDescriptors.SEND, properties), services);
    }
    private static Map<String, Object> message(String content, List<Map<String, Object>> attachments) {
        return Map.of("version", "discord.message.v1", "channelId", DiscordTestSupport.CHANNEL,
                "content", content, "attachments", attachments, "correlationId", "correlation-1");
    }
    private static void assertFailure(java.util.concurrent.CompletionStage<?> stage, DiscordException.Code code) {
        Throwable failure = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join()).getCause();
        assertInstanceOf(DiscordException.class, failure); assertEquals(code, ((DiscordException) failure).code());
        assertFalse(failure.getMessage().contains("hello")); assertFalse(failure.getMessage().contains("discord-bot-token"));
    }
    private static void waitForRequests(DiscordTestSupport.HttpHarness http, int count) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (http.requests.size() < count && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(http.requests.size() >= count);
    }
    private static final class TestCancellation implements CancellationSignal {
        private final List<Runnable> listeners = new CopyOnWriteArrayList<>(); private volatile boolean cancelled;
        @Override public boolean cancelled() { return cancelled; }
        @Override public void onCancel(Runnable listener) { if (cancelled) listener.run(); else listeners.add(listener); }
        void cancel() { cancelled = true; listeners.forEach(Runnable::run); }
    }
}
