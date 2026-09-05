package ai.ravenroot.extensions.slack;

import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class SlackPostMessageBehaviorTest {
    @TempDir Path directory;

    @Test void postsBoundedJsonThroughManagedCredentialAndRedactsContent() {
        var http = new SlackTestSupport.HttpHarness().reply(200,
                Map.of("ok", true, "channel", SlackTestSupport.CHANNEL, "ts", "1725451200.000001",
                        "message", Map.of("text", "remote-copy")));
        NodeAction action = action(http, Map.of());
        var result = action.handle(SlackTestSupport.message(message("hello"))).toCompletableFuture().join();
        assertEquals("sent", ((Map<?, ?>) result.payload()).get("status"));
        assertFalse(result.payload().toString().contains("hello")); assertFalse(result.payload().toString().contains("remote-copy"));
        var request = http.requests.getFirst();
        assertEquals("https://slack.com/api/chat.postMessage", request.destination().toString());
        assertEquals("slack-bot", request.credential().orElseThrow().bindingId());
        assertEquals("slack-bot-token", request.credential().orElseThrow().reference());
        assertEquals(1_048_576, request.limits().maximumRequestBytes());
        assertEquals(65_536, request.limits().maximumEncodedResponseBytes());
        assertTrue(request.representationPolicy().validates(200)); assertTrue(request.representationPolicy().validates(429));
        Map<String, Object> body = SlackValues.json(request.body());
        assertEquals("hello", body.get("text")); assertEquals(false, body.get("unfurl_links"));
        assertFalse(request.headers().containsKey("authorization"));
    }

    @Test void profileAuthorityAndEncodedRequestCeilingFailBeforeTransport() {
        var http = new SlackTestSupport.HttpHarness();
        Map<String, Object> forbidden = new java.util.LinkedHashMap<>(message("hello")); forbidden.put("channelId", "C99999999");
        assertFailure(action(http, Map.of()).handle(SlackTestSupport.message(forbidden)), SlackException.Code.FORBIDDEN);
        assertFailure(action(http, Map.of(), 64).handle(SlackTestSupport.message(message("x".repeat(100)))),
                SlackException.Code.INVALID_INPUT);
        assertTrue(http.requests.isEmpty());
    }

    @Test void retriesOnlyExplicitRateLimitAndSanitizesSlackErrorBodies() {
        var http = new SlackTestSupport.HttpHarness()
                .reply(429, Map.of("retry-after", List.of("1"), "content-type", List.of("application/json")),
                        Map.of("ok", false, "error", "secret-remote-error"))
                .reply(200, Map.of("ok", true, "channel", SlackTestSupport.CHANNEL, "ts", "1725451200.000002"));
        var result = action(http, Map.of("retries", "1")).handle(SlackTestSupport.message(message("hello")))
                .toCompletableFuture().join();
        assertEquals("sent", ((Map<?, ?>) result.payload()).get("status")); assertEquals(2, http.requests.size());
        assertFalse(result.payload().toString().contains("secret-remote-error"));

        var failed = new SlackTestSupport.HttpHarness();
        failed.pending = OutboundCall.failed(new NodePackageServiceException(NodePackageServiceException.Reason.TRANSPORT_FAILED));
        assertFailure(action(failed, Map.of("retries", "2")).handle(SlackTestSupport.message(message("hello"))),
                SlackException.Code.INDETERMINATE);
        assertEquals(1, failed.requests.size());
    }

    @Test void httpSuccessWithSlackFailureIsAContentFreeRejection() {
        var http = new SlackTestSupport.HttpHarness().reply(200,
                Map.of("ok", false, "error", "private-provider-detail"));
        var result = action(http, Map.of()).handle(SlackTestSupport.message(message("private-message")))
                .toCompletableFuture().join();
        assertEquals("rejected", ((Map<?, ?>) result.payload()).get("status"));
        assertEquals(200L, ((Map<?, ?>) result.payload()).get("code"));
        assertFalse(result.payload().toString().contains("private-provider-detail"));
        assertFalse(result.payload().toString().contains("private-message"));
        assertEquals(1, http.requests.size());
    }

    @Test void cancellationDuring429BackoffPreventsAnotherDispatch() {
        var http = new SlackTestSupport.HttpHarness().reply(429,
                Map.of("retry-after", List.of("2"), "content-type", List.of("application/json")),
                Map.of("ok", false, "error", "rate_limited"));
        TestCancellation cancellation = new TestCancellation();
        var result = action(http, Map.of("retries", "1")).handle(SlackTestSupport.message(message("hello")), cancellation);
        waitForRequests(http, 1); cancellation.cancel(); assertFailure(result, SlackException.Code.CANCELLED);
        assertEquals(1, http.requests.size());
    }

    @Test void cancellationReleasesConcurrencyAndCancelsManagedCall() {
        var http = new SlackTestSupport.HttpHarness(); CompletableFuture<OutboundHttpResponse> pending = new CompletableFuture<>();
        http.pending = new OutboundCall<>() {
            @Override public java.util.concurrent.CompletionStage<OutboundHttpResponse> completion() { return pending; }
            @Override public boolean cancel() { return pending.cancel(true); }
        };
        NodeAction action = action(http, Map.of("maxConcurrency", "1")); TestCancellation cancellation = new TestCancellation();
        var first = action.handle(SlackTestSupport.message(message("one")), cancellation); waitForRequests(http, 1);
        var second = action.handle(SlackTestSupport.message(message("two"))).toCompletableFuture().join();
        assertEquals("capacity", ((Map<?, ?>) second.payload()).get("status"));
        cancellation.cancel(); assertFailure(first, SlackException.Code.CANCELLED);
        http.pending = null; http.reply(200,
                Map.of("ok", true, "channel", SlackTestSupport.CHANNEL, "ts", "1725451200.000003"));
        assertEquals("sent", ((Map<?, ?>) action.handle(SlackTestSupport.message(message("three")))
                .toCompletableFuture().join().payload()).get("status"));
    }

    private NodeAction action(SlackTestSupport.HttpHarness services, Map<String, String> extra) {
        return action(services, extra, 1_048_576);
    }
    private NodeAction action(SlackTestSupport.HttpHarness services, Map<String, String> extra, int requestBytes) {
        SlackNodePackage nodePackage = SlackTestSupport.nodePackage(directory.resolve("deliveries-" + requestBytes + ".db"), requestBytes);
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("slackProfile", SlackTestSupport.PROFILE); properties.putAll(extra);
        return SlackTestSupport.behavior(nodePackage, SlackBehaviorDescriptors.POST_MESSAGE)
                .create(new NodeConfiguration("slack", SlackBehaviorDescriptors.POST_MESSAGE, properties), services);
    }
    private static Map<String, Object> message(String text) {
        return Map.of("version", "slack.message.v1", "channelId", SlackTestSupport.CHANNEL, "text", text,
                "threadTs", "1725451199.000001", "correlationId", "correlation-1");
    }
    private static void assertFailure(java.util.concurrent.CompletionStage<?> stage, SlackException.Code code) {
        Throwable failure = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join()).getCause();
        assertInstanceOf(SlackException.class, failure); assertEquals(code, ((SlackException) failure).code());
        assertFalse(failure.getMessage().contains("hello")); assertFalse(failure.getMessage().contains("slack-bot-token"));
    }
    private static void waitForRequests(SlackTestSupport.HttpHarness http, int count) {
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
