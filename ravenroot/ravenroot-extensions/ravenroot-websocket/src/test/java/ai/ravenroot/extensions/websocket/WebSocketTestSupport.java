package ai.ravenroot.extensions.websocket;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressDisposition;
import ai.ravenroot.api.deployment.IngressOverflowPolicy;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.deployment.TrustedIngress;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodeCredentialService;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundHttpService;
import ai.ravenroot.api.node.service.OutboundWebSocketListener;
import ai.ravenroot.api.node.service.OutboundWebSocketRequest;
import ai.ravenroot.api.node.service.OutboundWebSocketService;
import ai.ravenroot.api.node.service.OutboundWebSocketSession;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class WebSocketTestSupport {
    static WebSocketProfile profile(int concurrency, int buffered) {
        return new WebSocketProfile("events", URI.create("wss://socket.example.test/events"),
                Map.of("X-Client", List.of("ravenroot")), List.of("events.v1"),
                "handshake", "socket-secret", 16, 2, 2_000, 10, concurrency, buffered);
    }

    static WebSocketProfileResolver resolver(WebSocketProfile profile) {
        return name -> profile.name().equals(name) ? java.util.Optional.of(profile) : java.util.Optional.empty();
    }

    static NodeConfiguration configuration() {
        return new NodeConfiguration("socket", WebSocketSendNodeBehavior.BEHAVIOR,
                Map.of("websocketProfile", "events"));
    }

    static NodeMessage message(String tenant, Object payload) {
        SecurityContext security = new SecurityContext("request", tenant, "alice",
                PrincipalType.USER, "test");
        return new NodeMessage(security, UUID.randomUUID(), UUID.randomUUID(), "socket", payload, Map.of());
    }

    static Map<String, Object> text(String value) {
        return Map.of("version", "websocket.send.v1", "encoding", "text", "data", value);
    }

    static Map<String, Object> binary(String value) {
        return Map.of("version", "websocket.send.v1", "encoding", "base64", "data", value);
    }

    static WebSocketException failure(CompletionStage<?> stage) {
        Throwable failure;
        try {
            stage.toCompletableFuture().join();
            throw new AssertionError("expected failure");
        } catch (java.util.concurrent.CompletionException expected) {
            failure = expected.getCause();
        }
        if (!(failure instanceof WebSocketException safe)) throw new AssertionError(failure);
        return safe;
    }

    static final class FakeTransport implements NodePackageServices {
        final CopyOnWriteArrayList<OpenAttempt> opens = new CopyOnWriteArrayList<>();

        @Override public Set<NodePackageCapability> capabilities() {
            return Set.of(NodePackageCapability.OUTBOUND_WEBSOCKET);
        }

        @Override public NodeCredentialService credentials() { return NodePackageServices.unavailable().credentials(); }
        @Override public OutboundHttpService outboundHttp() { return NodePackageServices.unavailable().outboundHttp(); }

        @Override public OutboundWebSocketService outboundWebSocket() {
            return new OutboundWebSocketService() {
                @Override public OutboundCall<OutboundWebSocketSession> open(NodeMessage message,
                        OutboundWebSocketRequest request, OutboundWebSocketListener listener) {
                    return add(message.tenantId(), request, listener);
                }

                @Override public OutboundCall<OutboundWebSocketSession> open(InboundSourceContext context,
                        OutboundWebSocketRequest request, OutboundWebSocketListener listener) {
                    return add(context.identity().tenantId(), request, listener);
                }
            };
        }

        private OpenAttempt add(String tenant, OutboundWebSocketRequest request,
                                OutboundWebSocketListener listener) {
            OpenAttempt attempt = new OpenAttempt(tenant, request, listener);
            opens.add(attempt);
            return attempt;
        }

        OpenAttempt awaitOpen(int index) {
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (opens.size() <= index && System.nanoTime() < deadline) Thread.onSpinWait();
            if (opens.size() <= index) throw new AssertionError("missing open " + index);
            return opens.get(index);
        }
    }

    static final class OpenAttempt implements OutboundCall<OutboundWebSocketSession> {
        final String tenant;
        final OutboundWebSocketRequest request;
        final OutboundWebSocketListener listener;
        final CompletableFuture<OutboundWebSocketSession> completion = new CompletableFuture<>();
        final FakeSession session;
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile boolean cancelSettles = true;

        OpenAttempt(String tenant, OutboundWebSocketRequest request, OutboundWebSocketListener listener) {
            this.tenant = tenant;
            this.request = request;
            this.listener = listener == null ? new OutboundWebSocketListener() { } : listener;
            session = new FakeSession(this.listener);
        }

        void succeed() { completion.complete(session); }
        void fail(NodePackageServiceException.Reason reason) {
            completion.completeExceptionally(new NodePackageServiceException(reason));
        }

        @Override public CompletionStage<OutboundWebSocketSession> completion() { return completion; }
        @Override public boolean cancel() {
            if (!cancelled.compareAndSet(false, true)) return false;
            return !cancelSettles || completion.completeExceptionally(
                    new NodePackageServiceException(NodePackageServiceException.Reason.CANCELLED));
        }
    }

    static final class FakeSession implements OutboundWebSocketSession {
        final OutboundWebSocketListener listener;
        final CompletableFuture<Void> send = new CompletableFuture<>();
        final CompletableFuture<Void> close = new CompletableFuture<>();
        final AtomicInteger textCalls = new AtomicInteger();
        final AtomicInteger binaryCalls = new AtomicInteger();
        final AtomicInteger cancelCalls = new AtomicInteger();
        volatile CountDownLatch sendEntered = new CountDownLatch(0);
        volatile CountDownLatch sendRelease = new CountDownLatch(0);
        volatile RuntimeException synchronousSendFailure;
        volatile String text;
        volatile byte[] binary;

        FakeSession(OutboundWebSocketListener listener) { this.listener = listener; }

        @Override public CompletionStage<Void> sendText(String value) {
            textCalls.incrementAndGet(); text = value; beforeSendReturns(); return send;
        }

        @Override public CompletionStage<Void> sendBinary(byte[] value) {
            binaryCalls.incrementAndGet(); binary = value.clone(); beforeSendReturns(); return send;
        }

        void blockSend() {
            sendEntered = new CountDownLatch(1);
            sendRelease = new CountDownLatch(1);
        }

        private void beforeSendReturns() {
            sendEntered.countDown();
            try {
                if (!sendRelease.await(2, TimeUnit.SECONDS)) throw new AssertionError("send release timeout");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            if (synchronousSendFailure != null) throw synchronousSendFailure;
        }

        @Override public CompletionStage<Void> close(int statusCode, String reason) { return close; }

        @Override public boolean cancel() {
            if (cancelCalls.getAndIncrement() != 0) return false;
            listener.onFailure(new NodePackageServiceException(NodePackageServiceException.Reason.CANCELLED));
            return true;
        }

        void terminal() { listener.onClosed(1000, "done"); }
        void failTerminal(NodePackageServiceException.Reason reason) {
            listener.onFailure(new NodePackageServiceException(reason));
        }
        void text(String value) { listener.onText(value); }
        void binary(byte[] value) { listener.onBinary(value.clone()); }
    }

    static final class FakeIngress implements TrustedIngress {
        final CopyOnWriteArrayList<Object> payloads = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile IngressDisposition disposition = IngressDisposition.ACCEPTED;
        volatile CountDownLatch entered = new CountDownLatch(0);
        volatile CountDownLatch release = new CountDownLatch(0);

        @Override public IngressDisposition offer(SecurityContext security, IngressTarget target, Object payload) {
            calls.incrementAndGet(); payloads.add(payload); entered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("ingress release timeout");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return disposition;
            }
            return disposition;
        }

        @Override public int bufferCapacity() { return 64; }
        @Override public IngressOverflowPolicy overflowPolicy() { return IngressOverflowPolicy.REJECT; }
    }

    static final class FakeContext implements InboundSourceContext {
        final String tenant;
        final FakeIngress ingress;
        final CopyOnWriteArrayList<String> degraded = new CopyOnWriteArrayList<>();
        final AtomicInteger healthy = new AtomicInteger();

        FakeContext(String tenant, FakeIngress ingress) { this.tenant = tenant; this.ingress = ingress; }
        @Override public DeploymentId deploymentId() { return DeploymentId.of("deployment"); }
        @Override public String nodeId() { return "socket"; }
        @Override public SecurityContext identity() {
            return new SecurityContext("source", tenant, "operator", PrincipalType.WORKLOAD, "test");
        }
        @Override public TrustedIngress ingress() { return ingress; }
        @Override public void reportDegraded(String reason) { degraded.add(reason); }
        @Override public void reportHealthy() { healthy.incrementAndGet(); }
    }

    static final class ManualReconnectScheduler implements WebSocketReconnectScheduler {
        private final Object lock = new Object();
        final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        final CopyOnWriteArrayList<Long> delays = new CopyOnWriteArrayList<>();

        @Override public void schedule(Runnable task, long delayMillis) {
            synchronized (lock) {
                delays.add(delayMillis);
                tasks.add(task);
            }
        }

        int pending() {
            synchronized (lock) {
                return tasks.size();
            }
        }

        void triggerAll() {
            java.util.ArrayList<Runnable> ready = new java.util.ArrayList<>();
            synchronized (lock) {
                for (Runnable task; (task = tasks.poll()) != null;) ready.add(task);
            }
            // Advance only the timers that were pending at this explicit trigger. A reconnect task
            // may synchronously reach another terminal transport and schedule the next generation;
            // consuming that new timer here would collapse two independent backoff boundaries.
            ready.forEach(Runnable::run);
        }
    }

    private WebSocketTestSupport() { }
}
