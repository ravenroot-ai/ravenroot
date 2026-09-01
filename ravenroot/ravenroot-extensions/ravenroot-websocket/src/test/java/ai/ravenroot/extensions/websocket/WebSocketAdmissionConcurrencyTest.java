package ai.ravenroot.extensions.websocket;

import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;

import static ai.ravenroot.extensions.websocket.WebSocketTestSupport.failure;
import static ai.ravenroot.extensions.websocket.WebSocketTestSupport.message;
import static ai.ravenroot.extensions.websocket.WebSocketTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketAdmissionConcurrencyTest {
    @Test void concurrentSendSendHasExactlyMaximumWinnersAndOneRefusal() throws Exception {
        WebSocketAdmissionRegistry admission = new WebSocketAdmissionRegistry();
        WebSocketProfile profile = WebSocketTestSupport.profile(2, 2);
        var transport = new WebSocketTestSupport.FakeTransport();
        NodeAction action = new WebSocketSendNodeBehavior(WebSocketTestSupport.resolver(profile), admission)
                .create(WebSocketTestSupport.configuration(), transport);
        var results = invokeTogether(3, index -> action.handle(message("tenant-a", text("m" + index)))
                .toCompletableFuture());

        assertEquals(2, transport.opens.size());
        assertEquals(1, results.stream().filter(CompletableFuture::isCompletedExceptionally)
                .filter(result -> failure(result).code() == WebSocketException.Code.CAPACITY_UNAVAILABLE).count());
        assertEquals(1, admission.size());

        transport.opens.forEach(open -> open.fail(NodePackageServiceException.Reason.TRANSPORT_FAILED));
        results.stream().filter(result -> !result.isDone()).forEach(result ->
                assertEquals(WebSocketException.Code.TRANSPORT_UNAVAILABLE, failure(result).code()));
        assertEquals(0, admission.size());
    }

    @Test void concurrentSendReceiveSharesMaximumAndReturnsRegistryToBaseline() throws Exception {
        WebSocketAdmissionRegistry admission = new WebSocketAdmissionRegistry();
        WebSocketProfile profile = WebSocketTestSupport.profile(2, 2);
        var receiveTransport = new WebSocketTestSupport.FakeTransport();
        var scheduler = new WebSocketTestSupport.ManualReconnectScheduler();
        var source = new WebSocketReceiveSource(new WebSocketSettings(profile,
                profile.maximumMessageBytes(), profile.maximumFragments(), profile.timeoutMs()),
                receiveTransport, admission, scheduler);
        var started = source.start(new WebSocketTestSupport.FakeContext("tenant-a",
                new WebSocketTestSupport.FakeIngress()));
        receiveTransport.awaitOpen(0).succeed();
        started.toCompletableFuture().join();

        var sendTransport = new WebSocketTestSupport.FakeTransport();
        NodeAction send = new WebSocketSendNodeBehavior(WebSocketTestSupport.resolver(profile), admission)
                .create(WebSocketTestSupport.configuration(), sendTransport);
        var results = invokeTogether(2, index -> send.handle(message("tenant-a", text("m" + index)))
                .toCompletableFuture());

        assertEquals(1, sendTransport.opens.size());
        assertEquals(1, results.stream().filter(CompletableFuture::isCompletedExceptionally)
                .filter(result -> failure(result).code() == WebSocketException.Code.CAPACITY_UNAVAILABLE).count());
        sendTransport.opens.getFirst().fail(NodePackageServiceException.Reason.TRANSPORT_FAILED);
        source.stop().toCompletableFuture().join();
        assertEquals(0, admission.size());
        assertEquals(0, scheduler.pending());
    }

    @Test void concurrentDifferentTenantsDoNotShareOneTenantGate() throws Exception {
        WebSocketAdmissionRegistry admission = new WebSocketAdmissionRegistry();
        WebSocketProfile profile = WebSocketTestSupport.profile(1, 2);
        var transport = new WebSocketTestSupport.FakeTransport();
        NodeAction action = new WebSocketSendNodeBehavior(WebSocketTestSupport.resolver(profile), admission)
                .create(WebSocketTestSupport.configuration(), transport);
        CyclicBarrier barrier = new CyclicBarrier(3);
        CopyOnWriteArrayList<CompletableFuture<?>> results = new CopyOnWriteArrayList<>();
        Thread a = Thread.ofVirtual().start(() -> invoke(barrier, () -> results.add(
                action.handle(message("tenant-a", text("a"))).toCompletableFuture())));
        Thread b = Thread.ofVirtual().start(() -> invoke(barrier, () -> results.add(
                action.handle(message("tenant-b", text("b"))).toCompletableFuture())));
        barrier.await();
        a.join(Duration.ofSeconds(1));
        b.join(Duration.ofSeconds(1));

        assertEquals(2, transport.opens.size());
        assertEquals(2, admission.size());
        transport.opens.forEach(open -> open.fail(NodePackageServiceException.Reason.TRANSPORT_FAILED));
        results.forEach(result -> assertEquals(WebSocketException.Code.TRANSPORT_UNAVAILABLE,
                failure(result).code()));
        assertEquals(0, admission.size());
    }

    private static List<CompletableFuture<?>> invokeTogether(int count,
            java.util.function.IntFunction<CompletableFuture<?>> invocation) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(count + 1);
        CopyOnWriteArrayList<CompletableFuture<?>> results = new CopyOnWriteArrayList<>();
        List<Thread> workers = java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> Thread.ofVirtual().start(() -> invoke(barrier,
                        () -> results.add(invocation.apply(index))))).toList();
        barrier.await();
        for (Thread worker : workers) worker.join(Duration.ofSeconds(1));
        assertTrue(workers.stream().noneMatch(Thread::isAlive));
        return List.copyOf(results);
    }

    private static void invoke(CyclicBarrier barrier, Runnable action) {
        try {
            barrier.await();
            action.run();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
