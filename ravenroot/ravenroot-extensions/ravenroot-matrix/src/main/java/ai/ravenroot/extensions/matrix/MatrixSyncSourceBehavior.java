package ai.ravenroot.extensions.matrix;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.ExternalIoLimits;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundHttpRepresentationPolicy;
import ai.ravenroot.api.node.service.OutboundHttpRequest;
import ai.ravenroot.api.node.service.OutboundHttpResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Semaphore;

/** Deployment-owned, cursor-safe Matrix Client-Server /sync polling source. */
public final class MatrixSyncSourceBehavior implements NodeBehavior, InboundSourceCapable {
    private final MatrixRuntime runtime;
    MatrixSyncSourceBehavior(MatrixRuntime runtime) { this.runtime = java.util.Objects.requireNonNull(runtime); }

    @Override public Set<NodePackageCapability> requiredServices() { return Set.of(NodePackageCapability.OUTBOUND_HTTP); }
    @Override public NodeTypeDescriptor descriptor() { return MatrixBehaviorDescriptors.sync(); }
    @Override public NodeAction create(NodeConfiguration configuration) {
        return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
    }
    @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
        return createSource(configuration, context, NodePackageServices.unavailable());
    }
    @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context,
                                                NodePackageServices services) {
        MatrixProfile profile = runtime.profile(context.identity().tenantId(), MatrixBehaviorDescriptors.profile(configuration));
        int poll = tighten(configuration.property("pollTimeoutMs").orElse(""), profile.pollTimeoutMs(), 0);
        int events = tighten(configuration.property("maxEventsPerSync").orElse(""), profile.maxEventsPerSync(), 1);
        return new Source(profile, poll, events, services, runtime);
    }

    private static int tighten(String raw, int ceiling, int minimum) {
        if (raw.isBlank()) return ceiling;
        try { int value = Integer.parseInt(raw); if (value < minimum || value > ceiling) throw new NumberFormatException(); return value; }
        catch (NumberFormatException failure) { throw new MatrixException(MatrixException.Code.CONFIGURATION); }
    }

    private static final class Source implements InboundSource {
        private final MatrixProfile profile;
        private final int pollTimeoutMs;
        private final int maxEvents;
        private final NodePackageServices services;
        private final MatrixSyncStore store;
        private final MatrixRuntime runtime;
        private final Object lifecycle = new Object();
        private volatile boolean stopRequested;
        private long generation;
        private Thread worker;
        private OutboundCall<OutboundHttpResponse> active;
        private StoreCancellation activeStore;
        private CompletableFuture<Void> readiness = CompletableFuture.completedFuture(null);
        private CompletableFuture<Void> stopped = CompletableFuture.completedFuture(null);

        Source(MatrixProfile profile, int pollTimeoutMs, int maxEvents,
               NodePackageServices services, MatrixRuntime runtime) {
            this.profile = profile; this.pollTimeoutMs = pollTimeoutMs; this.maxEvents = maxEvents;
            this.services = services; this.runtime = runtime; this.store = runtime.store();
        }

        @Override public CompletionStage<Void> start(InboundSourceContext context) {
            CompletableFuture<Void> ready = new CompletableFuture<>();
            synchronized (lifecycle) {
                if (worker != null && worker.isAlive()) return readiness;
                stopRequested = false; generation++; long session = generation;
                stopped = new CompletableFuture<>(); readiness = ready;
                worker = Thread.ofVirtual().name("ravenroot-matrix-sync").unstarted(
                        () -> run(context, ready, session));
                worker.start();
            }
            return ready;
        }

        @Override public CompletionStage<Void> stop() {
            CompletableFuture<Void> completion;
            synchronized (lifecycle) {
                if (worker == null) return CompletableFuture.completedFuture(null);
                stopRequested = true; generation++;
                if (active != null) active.cancel();
                if (activeStore != null) activeStore.cancel();
                worker.interrupt();
                completion = stopped;
            }
            return completion;
        }
        @Override public CompletionStage<Void> rollback() { return stop(); }
        @Override public CompletionStage<Void> shutdown() { return stop(); }
        private void run(InboundSourceContext context, CompletableFuture<Void> ready, long session) {
            MatrixSyncStore.SourceKey key = new MatrixSyncStore.SourceKey(context.identity().tenantId(), profile.name(),
                    context.deploymentId().value(), context.nodeId());
            boolean first = true;
            try {
                String storedCursor = store.cursor(key);
                String cursor = storedCursor == null && !profile.initialSince().isEmpty()
                        ? profile.initialSince() : storedCursor;
                while (current(session)) {
                    try {
                        SyncPage page = poll(context, cursor, first && cursor == null);
                        if (!current(session)) break;
                        if (first && cursor == null && profile.initialSyncMode() == MatrixProfile.InitialSyncMode.SKIP) {
                            advance(key, storedCursor, page.nextBatch);
                        } else {
                            if (page.gapped) throw new SourceFailure("matrix-sync-gap");
                            if (page.events.size() > maxEvents) throw new SourceFailure("matrix-sync-event-limit");
                            for (Event event : page.events) {
                                if (!current(session)) return;
                                bindEvent(key, event);
                                IngressReceipt receipt = context.ingress().offerDurably(context.identity(),
                                        IngressTarget.start(), event.payload(profile.name()), context.nodeId(),
                                        sourceId(key) + ":" + event.eventId);
                                if (!receipt.acknowledgeable()) {
                                    if (receipt instanceof IngressReceipt.VolatileCustody)
                                        throw new SourceFailure("matrix-durable-ingress-required");
                                    throw new SourceFailure(receipt instanceof IngressReceipt.Ambiguous
                                            ? "matrix-ingress-ambiguous" : "matrix-ingress-refused");
                                }
                            }
                            advance(key, storedCursor, page.nextBatch);
                        }
                        cursor = page.nextBatch; storedCursor = page.nextBatch; first = false;
                        context.reportHealthy(); ready.complete(null);
                    } catch (SourceFailure failure) {
                        if (!ready.isDone()) { ready.completeExceptionally(new IllegalStateException(failure.reason)); break; }
                        context.reportDegraded(failure.reason); awaitBackoff();
                    } catch (RuntimeException failure) {
                        if (!ready.isDone()) { ready.completeExceptionally(new IllegalStateException("matrix-sync-failed")); break; }
                        context.reportDegraded("matrix-sync-reconnecting"); awaitBackoff();
                    }
                }
            } finally {
                synchronized (lifecycle) {
                    if (!ready.isDone()) ready.completeExceptionally(new IllegalStateException("Matrix sync stopped before readiness"));
                    active = null; worker = null; stopped.complete(null); lifecycle.notifyAll();
                }
            }
        }

        private SyncPage poll(InboundSourceContext context, String cursor, boolean initial) {
            Semaphore gate = runtime.gate(profile);
            if (!gate.tryAcquire()) throw new SourceFailure("matrix-sync-capacity");
            String rateKey = context.identity().tenantId() + "\u0000" + profile.name();
            if (!runtime.rates.allow(rateKey, profile.maxPerSecond())) {
                gate.release(); throw new SourceFailure("matrix-sync-rate-limit");
            }
            StringBuilder path = new StringBuilder("/_matrix/client/v3/sync?timeout=")
                    .append(initial ? 0 : pollTimeoutMs);
            if (cursor != null) path.append("&since=").append(encode(cursor));
            OutboundHttpRequest request = new OutboundHttpRequest(profile.endpoint(path.toString()), "GET",
                    Map.of("accept", List.of("application/json"), "user-agent", List.of("ravenroot-matrix/1")),
                    null, Duration.ofMillis(profile.requestTimeoutMs()), profile.credential(), null,
                    ExternalIoLimits.compressedHttp(profile.maxRequestBytes(), profile.maxResponseBytes(),
                            profile.maxResponseBytes(), profile.maxResponseBytes(), 100,
                            Duration.ofMillis(profile.requestTimeoutMs()), Set.of("application/json")),
                    OutboundHttpRepresentationPolicy.ALL_STATUSES);
            OutboundCall<OutboundHttpResponse> call = null;
            boolean completed = false;
            try {
                call = services.outboundHttp().execute(context, request);
                OutboundCall<OutboundHttpResponse> started = call;
                synchronized (lifecycle) { active = started; if (stopRequested) started.cancel(); }
                OutboundHttpResponse response = call.completion().toCompletableFuture()
                        .get(profile.requestTimeoutMs(), TimeUnit.MILLISECONDS);
                completed = true;
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    throw new SourceFailure(response.statusCode() == 401 || response.statusCode() == 403
                            ? "matrix-sync-authentication" : "matrix-sync-provider-status");
                return SyncPage.parse(response.body(), profile);
            } catch (SourceFailure failure) { throw failure; }
            catch (InterruptedException failure) {
                Thread.currentThread().interrupt(); throw new SourceFailure("matrix-sync-cancelled");
            } catch (Exception failure) { throw new SourceFailure("matrix-sync-transport"); }
            finally {
                if (!completed && call != null) call.cancel();
                synchronized (lifecycle) { if (active == call) active = null; }
                gate.release();
            }
        }

        private void bindEvent(MatrixSyncStore.SourceKey key, Event event) {
            StoreCancellation operation = beginStore();
            try {
                store.bindEvent(key, event.eventId, event.bindingDigest(), deadline(), operation);
            } finally { endStore(operation); }
        }

        private void advance(MatrixSyncStore.SourceKey key, String expected, String next) {
            StoreCancellation operation = beginStore();
            try { store.advance(key, expected, next, deadline(), operation); }
            finally { endStore(operation); }
        }

        private StoreCancellation beginStore() {
            StoreCancellation operation = new StoreCancellation();
            synchronized (lifecycle) {
                if (stopRequested) operation.cancel();
                else activeStore = operation;
            }
            return operation;
        }

        private void endStore(StoreCancellation operation) {
            synchronized (lifecycle) { if (activeStore == operation) activeStore = null; }
        }

        private boolean current(long session) {
            synchronized (lifecycle) { return !stopRequested && generation == session; }
        }
        private long deadline() { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(profile.requestTimeoutMs()); }
        private void awaitBackoff() {
            try { Thread.sleep(profile.retryBackoffMs()); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        private static String sourceId(MatrixSyncStore.SourceKey key) {
            return "matrix-sync:" + MatrixValues.sha256((key.tenant() + "\u0000" + key.profile() + "\u0000"
                    + key.deployment() + "\u0000" + key.node()).getBytes(StandardCharsets.UTF_8));
        }

        private static final class StoreCancellation implements CancellationSignal {
            private final AtomicReference<Runnable> listener = new AtomicReference<>();
            private final AtomicBoolean cancelled = new AtomicBoolean();
            private final AtomicBoolean delivered = new AtomicBoolean();
            @Override public boolean cancelled() { return cancelled.get(); }
            @Override public void onCancel(Runnable value) {
                if (!listener.compareAndSet(null, value))
                    throw new IllegalStateException("one cancellation listener per store operation");
                if (cancelled.get()) fire();
            }
            void cancel() { cancelled.set(true); fire(); }
            private void fire() {
                Runnable value = listener.get();
                if (value != null && delivered.compareAndSet(false, true)) value.run();
            }
        }
    }

    private record Event(String eventId, String roomId, String type,
                         String sender, long originServerTs, Map<String, Object> content) {
        String bindingDigest() {
            // Matrix unsigned data (including age) is homeserver-local and may change across replay.
            return MatrixValues.sha256(MatrixValues.jsonBytes(Map.of("eventId", eventId, "roomId", roomId,
                    "type", type, "sender", sender, "originServerTs", originServerTs, "content", content)));
        }
        Map<String, Object> payload(String profile) {
            Map<String, Object> value = new LinkedHashMap<>(); value.put("version", "matrix.event.v1");
            value.put("profile", profile); value.put("eventId", eventId); value.put("roomId", roomId);
            value.put("eventType", type); value.put("sender", sender); value.put("originServerTs", originServerTs);
            value.put("content", content); return Map.copyOf(value);
        }
    }

    private record SyncPage(String nextBatch, List<Event> events, boolean gapped) {
        static SyncPage parse(byte[] body, MatrixProfile profile) {
            Map<String, Object> root = MatrixValues.json(body);
            String next = MatrixProfile.opaque(MatrixValues.string(root.get("next_batch"), 2_048), 2_048);
            Object roomsValue = root.get("rooms");
            if (roomsValue == null) return new SyncPage(next, List.of(), false);
            Object joinedValue = MatrixValues.object(roomsValue).get("join");
            if (joinedValue == null) return new SyncPage(next, List.of(), false);
            Map<String, Object> joined = MatrixValues.object(joinedValue);
            List<Event> events = new ArrayList<>(); boolean gap = false;
            for (Map.Entry<String, Object> entry : joined.entrySet()) {
                String room = MatrixProfile.matrixId(entry.getKey(), '!', 255);
                if (!profile.permitsRoom(room)) continue;
                Object timelineValue = MatrixValues.object(entry.getValue()).get("timeline");
                if (timelineValue == null) continue;
                Map<String, Object> timeline = MatrixValues.object(timelineValue);
                if (timeline.containsKey("limited")) {
                    if (!(timeline.get("limited") instanceof Boolean limited)) throw MatrixValues.invalid();
                    if (limited) gap = true;
                }
                if (!timeline.containsKey("events")) throw MatrixValues.invalid();
                Object rawEvents = timeline.get("events");
                if (!(rawEvents instanceof List<?> list)) throw MatrixValues.invalid();
                for (Object raw : list) {
                    Map<String, Object> event = MatrixValues.object(raw);
                    String type = MatrixValues.string(event.get("type"), 128);
                    if (!profile.eventTypes().contains(type)) continue;
                    String eventId = MatrixProfile.opaque(MatrixValues.string(event.get("event_id"), 255), 255);
                    String sender = MatrixProfile.matrixId(MatrixValues.string(event.get("sender"), 255), '@', 255);
                    long timestamp = MatrixValues.number(event.get("origin_server_ts"), 0, Long.MAX_VALUE);
                    Map<String, Object> content = MatrixValues.object(event.get("content"));
                    events.add(new Event(eventId, room, type, sender, timestamp, content));
                }
            }
            return new SyncPage(next, List.copyOf(events), gap);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
    private static final class SourceFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String reason;
        SourceFailure(String reason) { super(reason); this.reason = reason; }
    }
}
