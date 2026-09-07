package ai.ravenroot.extensions.discord;

import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

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

    @Test void successfulCompletionPublishesAfterTheNodePermitIsReleased() throws Exception {
        var http = new TerminalOrderingHarness(1);
        NodeAction action = action(http, Map.of("maxConcurrency", "1"));
        var stages = new CopyOnWriteArrayList<CompletionStage<?>>();
        CompletionStage<NodeResult> first = action.handle(
                DiscordTestSupport.message(message("one", List.of())));
        stages.add(first);
        try {
            http.awaitCaptured();
            assertCapacity(await(action.handle(DiscordTestSupport.message(message("in-flight", List.of())))),
                    "local-capacity");
            var nextAtTerminal = invokeAtTerminal(first,
                    () -> action.handle(DiscordTestSupport.message(message("after-success", List.of()))));
            stages.add(nextAtTerminal);

            http.call(0).succeed();

            assertEquals("sent", status(await(first)));
            assertEquals("sent", status(await(nextAtTerminal)),
                    "terminal success must not be visible while the node permit is still held");
            assertEquals(2, http.dispatches());
        } finally {
            http.releaseAll();
            drain(stages);
        }
    }

    @Test void exceptionalCompletionPublishesAfterTheNodePermitIsReleased() throws Exception {
        var http = new TerminalOrderingHarness(1);
        NodeAction action = action(http, Map.of("maxConcurrency", "1"));
        var stages = new CopyOnWriteArrayList<CompletionStage<?>>();
        CompletionStage<NodeResult> first = action.handle(
                DiscordTestSupport.message(message("one", List.of())));
        stages.add(first);
        try {
            http.awaitCaptured();
            assertCapacity(await(action.handle(DiscordTestSupport.message(message("in-flight", List.of())))),
                    "local-capacity");
            var nextAtTerminal = invokeAtTerminal(first,
                    () -> action.handle(DiscordTestSupport.message(message("after-failure", List.of()))));
            stages.add(nextAtTerminal);

            http.call(0).fail(new NodePackageServiceException(NodePackageServiceException.Reason.TRANSPORT_FAILED));

            assertFailureWithin(first, DiscordException.Code.INDETERMINATE);
            assertEquals("sent", status(await(nextAtTerminal)),
                    "terminal failure must not be visible while the node permit is still held");
            assertEquals(2, http.dispatches());
        } finally {
            http.releaseAll();
            drain(stages);
        }
    }

    @Test void cancellationPublishesAfterTheManagedCallAndNodePermitAreReleased() throws Exception {
        var http = new TerminalOrderingHarness(1);
        NodeAction action = action(http, Map.of("maxConcurrency", "1"));
        TestCancellation cancellation = new TestCancellation();
        var stages = new CopyOnWriteArrayList<CompletionStage<?>>();
        CompletionStage<NodeResult> first = action.handle(
                DiscordTestSupport.message(message("one", List.of())), cancellation);
        stages.add(first);
        try {
            http.awaitCaptured();
            assertCapacity(await(action.handle(DiscordTestSupport.message(message("in-flight", List.of())))),
                    "local-capacity");
            var nextAtTerminal = invokeAtTerminal(first,
                    () -> action.handle(DiscordTestSupport.message(message("after-cancel", List.of()))));
            stages.add(nextAtTerminal);

            http.call(0).awaitCompletionObserved();
            cancellation.cancel();

            assertFailureWithin(first, DiscordException.Code.CANCELLED);
            assertEquals(1, http.call(0).cancellations());
            assertEquals("sent", status(await(nextAtTerminal)),
                    "terminal cancellation must not be visible while the node permit is still held");
            assertEquals(2, http.dispatches());
        } finally {
            http.releaseAll();
            drain(stages);
        }
    }

    @Test void successfulCompletionPublishesAfterTheSharedProfilePermitIsReleased() throws Exception {
        var http = new TerminalOrderingHarness(4);
        DiscordNodePackage nodePackage = DiscordTestSupport.nodePackage(directory.resolve("profile-permits.db"));
        var behavior = DiscordTestSupport.behavior(nodePackage, DiscordBehaviorDescriptors.SEND);
        List<NodeAction> actions = java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> createAction(behavior, http, Map.of()))
                .toList();
        var stages = new CopyOnWriteArrayList<CompletionStage<?>>();
        stages.add(actions.getFirst().handle(DiscordTestSupport.message(message("held-0", List.of()))));
        try {
            http.awaitCaptured(1);
            for (int index = 1; index < 4; index++) {
                stages.add(actions.get(index).handle(
                        DiscordTestSupport.message(message("held-" + index, List.of()))));
            }
            http.awaitCaptured(4);
            assertCapacity(await(actions.get(4).handle(
                    DiscordTestSupport.message(message("profile-full", List.of())))), "profile-capacity");
            @SuppressWarnings("unchecked")
            CompletionStage<NodeResult> first = (CompletionStage<NodeResult>) stages.getFirst();
            var nextAtTerminal = invokeAtTerminal(first,
                    () -> actions.get(4).handle(DiscordTestSupport.message(message("after-profile", List.of()))));
            stages.add(nextAtTerminal);

            http.call(0).succeed();

            assertEquals("sent", status(await(first)));
            assertEquals("sent", status(await(nextAtTerminal)),
                    "terminal success must not be visible while the shared profile permit is still held");
            assertEquals(5, http.dispatches());
        } finally {
            http.releaseAll();
            drain(stages);
        }
    }

    private NodeAction action(NodePackageServices services, Map<String, String> extra) {
        return action(services, extra, 1_048_576);
    }
    private NodeAction action(NodePackageServices services, Map<String, String> extra,
                              int maximumRequestBytes) {
        DiscordNodePackage nodePackage = DiscordTestSupport.nodePackage(
                directory.resolve("deliveries-" + maximumRequestBytes + ".db"), maximumRequestBytes);
        return createAction(DiscordTestSupport.behavior(nodePackage, DiscordBehaviorDescriptors.SEND), services, extra);
    }
    private static NodeAction createAction(NodeBehavior behavior, NodePackageServices services,
                                           Map<String, String> extra) {
        Map<String, Object> properties = new java.util.LinkedHashMap<>(); properties.put("discordProfile", DiscordTestSupport.PROFILE);
        properties.putAll(extra);
        return behavior.create(new NodeConfiguration("discord", DiscordBehaviorDescriptors.SEND, properties), services);
    }
    private static void assertCapacity(NodeResult result, String evidence) {
        assertEquals("capacity", status(result));
        assertEquals(evidence, ((Map<?, ?>) result.payload()).get("evidence"));
    }
    private static String status(NodeResult result) {
        return String.valueOf(((Map<?, ?>) result.payload()).get("status"));
    }
    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
    }
    private static void assertFailureWithin(CompletionStage<?> stage, DiscordException.Code code) {
        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> stage.toCompletableFuture().get(2, TimeUnit.SECONDS));
        assertInstanceOf(DiscordException.class, thrown.getCause());
        assertEquals(code, ((DiscordException) thrown.getCause()).code());
    }
    private static <T> CompletableFuture<T> invokeAtTerminal(
            CompletionStage<?> terminal, Supplier<CompletionStage<T>> invocation) {
        var observed = new CompletableFuture<T>();
        terminal.whenComplete((ignored, failure) -> {
            try {
                invocation.get().whenComplete((value, invocationFailure) -> {
                    if (invocationFailure == null) observed.complete(value);
                    else observed.completeExceptionally(invocationFailure);
                });
            } catch (Throwable invocationFailure) {
                observed.completeExceptionally(invocationFailure);
            }
        });
        return observed;
    }
    private static void drain(List<? extends CompletionStage<?>> stages) throws Exception {
        CompletableFuture<?>[] drained = stages.stream()
                .map(stage -> stage.handle((value, failure) -> null).toCompletableFuture())
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(drained).get(2, TimeUnit.SECONDS);
    }
    private static OutboundHttpResponse sentResponse() {
        return new OutboundHttpResponse(200, Map.of("content-type", List.of("application/json")),
                DiscordValues.jsonBytes(Map.of(
                        "id", "923456789012345678",
                        "channel_id", DiscordTestSupport.CHANNEL)), 65_536);
    }
    private static final class TerminalOrderingHarness implements NodePackageServices {
        private final int heldCalls;
        private final List<CountDownLatch> capturedCounts;
        private final List<ControlledCall> calls = new CopyOnWriteArrayList<>();
        private final AtomicInteger dispatches = new AtomicInteger();
        private final AtomicBoolean releasing = new AtomicBoolean();

        private TerminalOrderingHarness(int heldCalls) {
            this.heldCalls = heldCalls;
            this.capturedCounts = java.util.stream.IntStream.rangeClosed(1, heldCalls)
                    .mapToObj(CountDownLatch::new)
                    .toList();
        }
        private void awaitCaptured() throws InterruptedException {
            awaitCaptured(heldCalls);
        }
        private void awaitCaptured(int count) throws InterruptedException {
            assertTrue(capturedCounts.get(count - 1).await(2, TimeUnit.SECONDS),
                    "managed Discord calls were not captured");
            assertTrue(calls.size() >= count);
        }
        private ControlledCall call(int index) { return calls.get(index); }
        private int dispatches() { return dispatches.get(); }
        private void releaseAll() {
            releasing.set(true);
            calls.forEach(ControlledCall::succeed);
        }
        @Override public Set<NodePackageCapability> capabilities() {
            return Set.of(NodePackageCapability.OUTBOUND_HTTP);
        }
        @Override public ai.ravenroot.api.node.service.NodeCredentialService credentials() {
            return NodePackageServices.unavailable().credentials();
        }
        @Override public ai.ravenroot.api.node.service.OutboundHttpService outboundHttp() {
            return (message, request) -> {
                int ordinal = dispatches.getAndIncrement();
                if (ordinal >= heldCalls) return OutboundCall.completed(sentResponse());
                var call = new ControlledCall();
                calls.add(call);
                capturedCounts.forEach(CountDownLatch::countDown);
                if (releasing.get()) call.succeed();
                return call;
            };
        }
        @Override public ai.ravenroot.api.node.service.OutboundWebSocketService outboundWebSocket() {
            return NodePackageServices.unavailable().outboundWebSocket();
        }
    }
    private static final class ControlledCall implements OutboundCall<OutboundHttpResponse> {
        private final CompletableFuture<OutboundHttpResponse> completion = new CompletableFuture<>();
        private final CountDownLatch completionObserved = new CountDownLatch(1);
        private final AtomicInteger cancellations = new AtomicInteger();

        @Override public CompletionStage<OutboundHttpResponse> completion() {
            completionObserved.countDown();
            return completion;
        }
        @Override public boolean cancel() {
            cancellations.incrementAndGet();
            return completion.cancel(true);
        }
        private void succeed() { completion.complete(sentResponse()); }
        private void fail(Throwable failure) { completion.completeExceptionally(failure); }
        private int cancellations() { return cancellations.get(); }
        private void awaitCompletionObserved() throws InterruptedException {
            assertTrue(completionObserved.await(2, TimeUnit.SECONDS), "managed call was not active");
        }
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
