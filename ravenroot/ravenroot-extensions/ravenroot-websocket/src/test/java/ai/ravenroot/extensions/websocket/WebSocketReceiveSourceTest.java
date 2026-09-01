package ai.ravenroot.extensions.websocket;

import ai.ravenroot.api.deployment.IngressDisposition;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

import static ai.ravenroot.extensions.websocket.WebSocketTestSupport.failure;
import static ai.ravenroot.extensions.websocket.WebSocketTestSupport.message;
import static ai.ravenroot.extensions.websocket.WebSocketTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketReceiveSourceTest {
    @Test void manualReconnectSchedulerDoesNotCollapseAReentrantBackoffGeneration() {
        var scheduler = new WebSocketTestSupport.ManualReconnectScheduler();
        java.util.concurrent.atomic.AtomicInteger executions = new java.util.concurrent.atomic.AtomicInteger();
        scheduler.schedule(() -> {
            executions.incrementAndGet();
            scheduler.schedule(executions::incrementAndGet, 20);
        }, 10);

        scheduler.triggerAll();

        assertEquals(1, executions.get());
        assertEquals(1, scheduler.pending());
        scheduler.triggerAll();
        assertEquals(2, executions.get());
        assertEquals(0, scheduler.pending());
    }

    @Test void noConnectionExistsBeforeSourceStart() {
        var transport = new WebSocketTestSupport.FakeTransport();
        WebSocketProfile profile = WebSocketTestSupport.profile(1, 2);
        var behavior = new WebSocketReceiveNodeBehavior(WebSocketTestSupport.resolver(profile));
        var context = new WebSocketTestSupport.FakeContext("tenant-a", new WebSocketTestSupport.FakeIngress());

        behavior.createSource(WebSocketTestSupport.configuration(), context, transport);

        assertEquals(0, transport.opens.size());
    }

    @Test void exactReceiveBufferIsObservableAndDrainsInOrder() throws Exception {
        Fixture fixture = fixture(1, 2);
        fixture.ingress.entered = new CountDownLatch(1);
        fixture.ingress.release = new CountDownLatch(1);
        fixture.open.succeed();
        fixture.started.toCompletableFuture().join();

        fixture.open.session.text("one");
        assertTrue(fixture.ingress.entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
        fixture.open.session.text("two");
        await(() -> fixture.source.bufferedEvents() == 2);
        assertTrue(fixture.context.degraded.isEmpty());

        fixture.ingress.release.countDown();
        await(() -> fixture.ingress.calls.get() == 2);
        assertEquals(List.of("one", "two"), data(fixture.ingress));
        stop(fixture);
    }

    @Test void limitPlusOneRevokesGenerationAndClosesWithoutReconnect() throws Exception {
        Fixture fixture = fixture(1, 2);
        fixture.ingress.entered = new CountDownLatch(1);
        fixture.ingress.release = new CountDownLatch(1);
        fixture.open.succeed();
        fixture.started.toCompletableFuture().join();

        fixture.open.session.text("one");
        assertTrue(fixture.ingress.entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
        fixture.open.session.text("two");
        fixture.open.session.text("three");

        await(() -> fixture.open.session.cancelCalls.get() == 1);
        assertTrue(fixture.context.degraded.contains("websocket-ingress-backpressure"));
        fixture.ingress.release.countDown();
        assertEquals(0, fixture.scheduler.pending());
        fixture.scheduler.triggerAll();
        assertEquals(1, fixture.transport.opens.size());
        assertEquals(0, fixture.source.bufferedEvents());
    }

    @Test void runtimeIngressRefusalRevokesInsteadOfBufferingOrReconnecting() {
        Fixture fixture = fixture(1, 2);
        fixture.ingress.disposition = IngressDisposition.REJECTED_BUFFER_FULL;
        fixture.open.succeed();
        fixture.started.toCompletableFuture().join();

        fixture.open.session.text("one");

        await(() -> fixture.open.session.cancelCalls.get() == 1);
        assertTrue(fixture.context.degraded.contains("websocket-ingress-backpressure"));
        assertEquals(0, fixture.scheduler.pending());
        fixture.scheduler.triggerAll();
        assertEquals(1, fixture.transport.opens.size());
    }

    @Test void closeReconnectsExactlyOneSessionAndFencesOldCallbacks() {
        Fixture fixture = fixture(1, 2);
        fixture.open.succeed();
        fixture.started.toCompletableFuture().join();
        fixture.open.session.terminal();

        assertEquals(1, fixture.scheduler.pending());
        fixture.scheduler.triggerAll();
        WebSocketTestSupport.OpenAttempt replacement = fixture.transport.awaitOpen(1);
        replacement.succeed();
        fixture.open.session.text("late");
        replacement.session.text("current");
        await(() -> fixture.ingress.calls.get() == 1);

        assertEquals(List.of("current"), data(fixture.ingress));
        assertEquals(2, fixture.transport.opens.size());
        replacement.session.terminal();
        fixture.source.stop().toCompletableFuture().join();
    }

    @Test void terminalBeforeReconnectOpenSettlementSchedulesOnlyOneBackoff() {
        Fixture fixture = fixture(1, 2);
        fixture.open.succeed();
        fixture.started.toCompletableFuture().join();
        fixture.open.session.terminal();
        assertEquals(1, fixture.scheduler.pending());
        fixture.scheduler.triggerAll();
        WebSocketTestSupport.OpenAttempt reconnect = fixture.transport.awaitOpen(1);

        reconnect.listener.onClosed(1006, "closed-before-open-settlement");
        reconnect.fail(NodePackageServiceException.Reason.TRANSPORT_FAILED);

        assertEquals(1, fixture.scheduler.pending(),
                "one connection reaching terminal through listener and open settlement has one backoff");
        stop(fixture);
    }

    @Test void stopAndRestartFenceOldGenerationAndReleaseSharedAdmission() {
        WebSocketAdmissionRegistry admission = new WebSocketAdmissionRegistry();
        WebSocketProfile profile = WebSocketTestSupport.profile(1, 2);
        var transport = new WebSocketTestSupport.FakeTransport();
        var source = source(profile, transport, admission);
        var firstIngress = new WebSocketTestSupport.FakeIngress();
        var firstContext = new WebSocketTestSupport.FakeContext("tenant-a", firstIngress);
        var firstStart = source.start(firstContext);
        var first = transport.awaitOpen(0);
        first.succeed(); firstStart.toCompletableFuture().join();

        source.stop().toCompletableFuture().join();
        var secondIngress = new WebSocketTestSupport.FakeIngress();
        var secondContext = new WebSocketTestSupport.FakeContext("tenant-a", secondIngress);
        var secondStart = source.start(secondContext);
        var second = transport.awaitOpen(1);
        second.succeed(); secondStart.toCompletableFuture().join();
        first.session.text("late");
        second.session.binary(new byte[]{1, 2, 3});
        await(() -> secondIngress.calls.get() == 1);

        assertEquals(0, firstIngress.calls.get());
        @SuppressWarnings("unchecked") Map<String, Object> event = (Map<String, Object>) secondIngress.payloads.getFirst();
        assertEquals("base64", event.get("encoding"));
        assertEquals("AQID", event.get("data"));
        source.stop().toCompletableFuture().join();
        assertEquals(0, admission.size());
    }

    @Test void sendAndReceiveShareTrustedTenantProfileAdmission() {
        WebSocketAdmissionRegistry admission = new WebSocketAdmissionRegistry();
        WebSocketProfile profile = WebSocketTestSupport.profile(1, 2);
        var receiveTransport = new WebSocketTestSupport.FakeTransport();
        var sendTransport = new WebSocketTestSupport.FakeTransport();
        var source = source(profile, receiveTransport, admission);
        var context = new WebSocketTestSupport.FakeContext("tenant-a", new WebSocketTestSupport.FakeIngress());
        var started = source.start(context);
        receiveTransport.awaitOpen(0).succeed(); started.toCompletableFuture().join();
        NodeAction send = new WebSocketSendNodeBehavior(WebSocketTestSupport.resolver(profile), admission)
                .create(WebSocketTestSupport.configuration(), sendTransport);

        assertEquals(WebSocketException.Code.CAPACITY_UNAVAILABLE,
                failure(send.handle(message("tenant-a", text("blocked")))).code());
        assertEquals(0, sendTransport.opens.size());
        source.stop().toCompletableFuture().join();

        var accepted = send.handle(message("tenant-a", text("allowed")));
        sendTransport.awaitOpen(0).fail(NodePackageServiceException.Reason.TRANSPORT_FAILED);
        assertEquals(WebSocketException.Code.TRANSPORT_UNAVAILABLE, failure(accepted).code());
    }

    @Test void differentTenantCanSendWhileReceiveLeaseIsActive() {
        WebSocketAdmissionRegistry admission = new WebSocketAdmissionRegistry();
        WebSocketProfile profile = WebSocketTestSupport.profile(1, 2);
        var receiveTransport = new WebSocketTestSupport.FakeTransport();
        var sendTransport = new WebSocketTestSupport.FakeTransport();
        var source = source(profile, receiveTransport, admission);
        var started = source.start(new WebSocketTestSupport.FakeContext("tenant-a",
                new WebSocketTestSupport.FakeIngress()));
        receiveTransport.awaitOpen(0).succeed(); started.toCompletableFuture().join();
        NodeAction send = new WebSocketSendNodeBehavior(WebSocketTestSupport.resolver(profile), admission)
                .create(WebSocketTestSupport.configuration(), sendTransport);

        var accepted = send.handle(message("tenant-b", text("allowed")));
        sendTransport.awaitOpen(0).fail(NodePackageServiceException.Reason.TRANSPORT_FAILED);
        assertEquals(WebSocketException.Code.TRANSPORT_UNAVAILABLE, failure(accepted).code());
        source.stop().toCompletableFuture().join();
    }

    @Test void initialOpenFailureIsSanitizedAndReleasesAdmission() {
        WebSocketAdmissionRegistry admission = new WebSocketAdmissionRegistry();
        WebSocketProfile profile = WebSocketTestSupport.profile(1, 2);
        var transport = new WebSocketTestSupport.FakeTransport();
        var source = source(profile, transport, admission);
        var context = new WebSocketTestSupport.FakeContext("tenant-a", new WebSocketTestSupport.FakeIngress());
        var started = source.start(context);
        transport.awaitOpen(0).fail(NodePackageServiceException.Reason.TLS_REFUSED);

        assertEquals(WebSocketException.Code.TLS_REFUSED, failure(started).code());
        assertEquals(0, admission.size());
        assertTrue(context.degraded.contains("websocket-connect-failed"));
    }

    @Test void terminalBeforeOpenCompletionFailsStartAndFencesLateSession() {
        WebSocketAdmissionRegistry admission = new WebSocketAdmissionRegistry();
        WebSocketProfile profile = WebSocketTestSupport.profile(1, 2);
        var transport = new WebSocketTestSupport.FakeTransport();
        var scheduler = new WebSocketTestSupport.ManualReconnectScheduler();
        var source = source(profile, transport, admission, scheduler);
        var context = new WebSocketTestSupport.FakeContext("tenant-a",
                new WebSocketTestSupport.FakeIngress());
        var started = source.start(context);
        WebSocketTestSupport.OpenAttempt opening = transport.awaitOpen(0);

        opening.listener.onClosed(1006, "peer-secret-must-not-escape");
        assertEquals(WebSocketException.Code.TRANSPORT_UNAVAILABLE, failure(started).code());
        assertTrue(context.degraded.contains("websocket-closed"));
        assertFalse(context.degraded.toString().contains("peer-secret"));
        assertEquals(0, admission.size());
        assertEquals(0, scheduler.pending());

        opening.succeed();
        assertEquals(1, opening.session.cancelCalls.get());
        assertEquals(0, context.ingress.calls.get());
        assertEquals(0, admission.size());
    }

    private static Fixture fixture(int concurrency, int buffered) {
        WebSocketAdmissionRegistry admission = new WebSocketAdmissionRegistry();
        WebSocketProfile profile = WebSocketTestSupport.profile(concurrency, buffered);
        var transport = new WebSocketTestSupport.FakeTransport();
        var ingress = new WebSocketTestSupport.FakeIngress();
        var context = new WebSocketTestSupport.FakeContext("tenant-a", ingress);
        var scheduler = new WebSocketTestSupport.ManualReconnectScheduler();
        WebSocketReceiveSource source = source(profile, transport, admission, scheduler);
        var started = source.start(context);
        return new Fixture(source, transport, ingress, context, transport.awaitOpen(0), started, scheduler);
    }

    private static WebSocketReceiveSource source(WebSocketProfile profile,
                                                  WebSocketTestSupport.FakeTransport transport,
                                                  WebSocketAdmissionRegistry admission) {
        return source(profile, transport, admission, WebSocketReconnectScheduler.SYSTEM);
    }

    private static WebSocketReceiveSource source(WebSocketProfile profile,
                                                  WebSocketTestSupport.FakeTransport transport,
                                                  WebSocketAdmissionRegistry admission,
                                                  WebSocketReconnectScheduler scheduler) {
        return new WebSocketReceiveSource(new WebSocketSettings(profile, profile.maximumMessageBytes(),
                profile.maximumFragments(), profile.timeoutMs()), transport, admission, scheduler);
    }

    private static void stop(Fixture fixture) {
        fixture.source.stop().toCompletableFuture().join();
    }

    private static List<String> data(WebSocketTestSupport.FakeIngress ingress) {
        return ingress.payloads.stream().map(value -> {
            @SuppressWarnings("unchecked") Map<String, Object> event = (Map<String, Object>) value;
            return (String) event.get("data");
        }).toList();
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(condition.getAsBoolean(), "condition did not become true");
    }

    private record Fixture(WebSocketReceiveSource source, WebSocketTestSupport.FakeTransport transport,
                           WebSocketTestSupport.FakeIngress ingress, WebSocketTestSupport.FakeContext context,
                           WebSocketTestSupport.OpenAttempt open, CompletionStage<Void> started,
                           WebSocketTestSupport.ManualReconnectScheduler scheduler) { }
}
