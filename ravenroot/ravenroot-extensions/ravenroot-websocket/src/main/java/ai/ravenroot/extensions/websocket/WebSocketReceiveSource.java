package ai.ravenroot.extensions.websocket;

import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressDisposition;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundWebSocketListener;
import ai.ravenroot.api.node.service.OutboundWebSocketSession;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Generation-fenced source: stop and backpressure revoke authority before transport cancellation. */
final class WebSocketReceiveSource implements InboundSource {
    private final WebSocketSettings settings;
    private final NodePackageServices services;
    private final WebSocketAdmissionRegistry admission;
    private final WebSocketReconnectScheduler reconnectScheduler;
    private final Object lock = new Object();

    private volatile long generation;
    private volatile boolean stopped = true;
    private volatile Connection connection;
    private volatile ReceiveBuffer buffer;
    private InboundSourceContext context;
    private WebSocketAdmissionRegistry.Lease admissionLease;
    private CompletableFuture<Void> start = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> shutdown = CompletableFuture.completedFuture(null);

    WebSocketReceiveSource(WebSocketSettings settings, NodePackageServices services,
                           WebSocketAdmissionRegistry admission) {
        this(settings, services, admission, WebSocketReconnectScheduler.SYSTEM);
    }

    WebSocketReceiveSource(WebSocketSettings settings, NodePackageServices services,
                           WebSocketAdmissionRegistry admission,
                           WebSocketReconnectScheduler reconnectScheduler) {
        this.settings = java.util.Objects.requireNonNull(settings);
        this.services = java.util.Objects.requireNonNull(services);
        this.admission = java.util.Objects.requireNonNull(admission);
        this.reconnectScheduler = java.util.Objects.requireNonNull(reconnectScheduler);
    }

    @Override public CompletionStage<Void> start(InboundSourceContext value) {
        long current;
        synchronized (lock) {
            if (!stopped) return start;
            WebSocketAdmissionRegistry.Lease lease = admission.tryAcquire(value.identity().tenantId(),
                    settings.profile().name(), settings.profile().maxConcurrency());
            if (lease == null) {
                return CompletableFuture.failedFuture(
                        WebSocketException.of(WebSocketException.Code.CAPACITY_UNAVAILABLE));
            }
            admissionLease = lease;
            context = java.util.Objects.requireNonNull(value);
            stopped = false;
            current = ++generation;
            start = new CompletableFuture<>();
            shutdown = new CompletableFuture<>();
            buffer = new ReceiveBuffer(current, settings.profile().maxBufferedEvents());
        }
        open(current);
        return start;
    }

    private void open(long current) {
        Connection next = new Connection(current);
        synchronized (lock) {
            if (!active(current) || connection != null) return;
            connection = next;
        }
        OutboundCall<OutboundWebSocketSession> call;
        try {
            call = services.outboundWebSocket().open(context, WebSocketSendNodeBehavior.request(settings), next);
        } catch (RuntimeException failure) {
            openingFailed(next, failure);
            return;
        }
        next.attach(call);
        call.completion().whenComplete((opened, failure) -> {
            if (failure != null) openingFailed(next, failure);
            else opened(next, opened);
        });
    }

    private void opened(Connection expected, OutboundWebSocketSession opened) {
        boolean cancel;
        synchronized (lock) {
            cancel = !active(expected.generation) || connection != expected;
            if (!cancel) {
                expected.session = opened;
                context.reportHealthy();
                start.complete(null);
            }
        }
        if (cancel) {
            expected.session = opened;
            try { opened.cancel(); } catch (RuntimeException ignored) { }
        }
    }

    private void openingFailed(Connection expected, Throwable failure) {
        expected.settle();
        boolean initial;
        synchronized (lock) {
            if (!active(expected.generation) || connection != expected) return;
            connection = null;
            context.reportDegraded("websocket-connect-failed");
            initial = !start.isDone();
            if (initial) start.completeExceptionally(WebSocketSendNodeBehavior.map(failure, false));
        }
        if (initial) deactivate(expected.generation, false, null);
        else reconnect(expected.generation);
    }

    private void terminal(Connection expected, String reason) {
        expected.settle();
        boolean initial;
        synchronized (lock) {
            if (!active(expected.generation) || connection != expected) return;
            connection = null;
            context.reportDegraded(reason);
            initial = !start.isDone();
            if (initial) start.completeExceptionally(
                    WebSocketException.of(WebSocketException.Code.TRANSPORT_UNAVAILABLE));
        }
        if (initial) deactivate(expected.generation, false, null);
        else reconnect(expected.generation);
    }

    private void reconnect(long current) {
        reconnectScheduler.schedule(() -> {
                    synchronized (lock) {
                        if (!active(current) || connection != null) return;
                    }
                    open(current);
                }, settings.profile().reconnectBackoffMs());
    }

    @Override public CompletionStage<Void> stop() {
        return deactivate(generation, false, null);
    }

    @Override public CompletionStage<Void> rollback() {
        return stop();
    }

    private CompletionStage<Void> deactivate(long expectedGeneration, boolean degraded, String reason) {
        Connection oldConnection;
        ReceiveBuffer oldBuffer;
        WebSocketAdmissionRegistry.Lease oldLease;
        CompletableFuture<Void> result;
        synchronized (lock) {
            if (stopped || generation != expectedGeneration) return shutdown;
            stopped = true;
            ++generation;
            if (degraded && context != null) context.reportDegraded(reason);
            oldConnection = connection;
            connection = null;
            oldBuffer = buffer;
            buffer = null;
            oldLease = admissionLease;
            admissionLease = null;
            result = shutdown;
        }
        if (oldBuffer != null) oldBuffer.close();
        CompletionStage<Void> settlement = oldConnection == null
                ? CompletableFuture.completedFuture(null) : oldConnection.cancelAndSettlement();
        settlement.whenComplete((ignored, failure) -> {
            try {
                if (oldLease != null) oldLease.close();
            } finally {
                result.complete(null);
            }
        });
        return result;
    }

    private void revokeForBackpressure(long current) {
        deactivate(current, true, "websocket-ingress-backpressure");
    }

    private boolean active(long current) {
        return !stopped && generation == current;
    }

    private boolean active(Connection expected) {
        return active(expected.generation) && connection == expected;
    }

    int bufferedEvents() {
        ReceiveBuffer current = buffer;
        return current == null ? 0 : current.inUse();
    }

    private final class Connection implements OutboundWebSocketListener {
        private final long generation;
        private final CompletableFuture<Void> settlement = new CompletableFuture<>();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private volatile OutboundCall<OutboundWebSocketSession> opening;
        private volatile OutboundWebSocketSession session;

        private Connection(long generation) {
            this.generation = generation;
        }

        private void attach(OutboundCall<OutboundWebSocketSession> value) {
            opening = value;
            if (cancelRequested.get()) try { value.cancel(); } catch (RuntimeException ignored) { }
        }

        private void settle() {
            settlement.complete(null);
        }

        private CompletionStage<Void> cancelAndSettlement() {
            if (cancelRequested.compareAndSet(false, true)) {
                OutboundCall<OutboundWebSocketSession> currentOpening = opening;
                OutboundWebSocketSession currentSession = session;
                if (currentSession != null) {
                    try { currentSession.cancel(); } catch (RuntimeException ignored) { }
                } else if (currentOpening != null) {
                    try { currentOpening.cancel(); } catch (RuntimeException ignored) { }
                }
            }
            return settlement;
        }

        @Override public void onText(String text) {
            ReceiveBuffer current = buffer;
            if (current != null && current.generation == generation && active(this)) current.offer("text", text);
        }

        @Override public void onBinary(byte[] bytes) {
            ReceiveBuffer current = buffer;
            if (current != null && current.generation == generation && active(this)) {
                current.offer("base64", Base64.getEncoder().encodeToString(bytes));
            }
        }

        @Override public void onClosed(int status, String reason) {
            terminal(this, "websocket-closed");
        }

        @Override public void onFailure(NodePackageServiceException failure) {
            terminal(this, "websocket-transport-failed");
        }
    }

    private final class ReceiveBuffer implements AutoCloseable {
        private final long generation;
        private final Semaphore permits;
        private final ArrayBlockingQueue<Event> queue;
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final Thread worker;

        private ReceiveBuffer(long generation, int capacity) {
            this.generation = generation;
            permits = new Semaphore(capacity, true);
            queue = new ArrayBlockingQueue<>(capacity);
            worker = Thread.ofVirtual().name("ravenroot-websocket-receive-buffer").start(this::drain);
        }

        private void offer(String encoding, String data) {
            if (!open.get() || !active(generation)) return;
            if (!permits.tryAcquire()) {
                revokeForBackpressure(generation);
                return;
            }
            Event event = new Event(encoding, data);
            if (!open.get() || !active(generation) || !queue.offer(event)) {
                permits.release();
                if (open.get()) revokeForBackpressure(generation);
            }
        }

        private void drain() {
            while (open.get() || !queue.isEmpty()) {
                Event event;
                try {
                    event = queue.poll(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (event == null) continue;
                try {
                    if (!open.get() || !active(generation)) continue;
                    InboundSourceContext current = context;
                    Map<String, Object> payload = Map.of(
                            "version", "websocket.receive.event.v1",
                            "encoding", event.encoding,
                            "data", event.data,
                            "connection", Map.of(
                                    "origin", settings.profile().destination().getScheme() + "://"
                                            + settings.profile().destination().getAuthority(),
                                    "subprotocols", settings.profile().subprotocols()));
                    IngressDisposition disposition = current.ingress().offer(current.identity(),
                            IngressTarget.start(), payload);
                    if (disposition != IngressDisposition.ACCEPTED) revokeForBackpressure(generation);
                } catch (RuntimeException failure) {
                    revokeForBackpressure(generation);
                } finally {
                    permits.release();
                }
            }
        }

        private int inUse() {
            return settings.profile().maxBufferedEvents() - permits.availablePermits();
        }

        @Override public void close() {
            if (!open.compareAndSet(true, false)) return;
            List<Event> discarded = new java.util.ArrayList<>();
            queue.drainTo(discarded);
            discarded.forEach(ignored -> permits.release());
            worker.interrupt();
        }
    }

    private record Event(String encoding, String data) { }
}
