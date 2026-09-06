package ai.ravenroot.extensions.matrix;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.ExternalIoLimits;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundHttpRepresentationPolicy;
import ai.ravenroot.api.node.service.OutboundHttpRequest;
import ai.ravenroot.api.node.service.OutboundHttpResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded Matrix m.room.message sender. */
public final class MatrixSendBehavior implements NodeBehavior {
    private static final Set<String> PAYLOAD_FIELDS = Set.of("version", "roomId", "text", "correlationId");
    private final MatrixRuntime runtime;

    MatrixSendBehavior(MatrixRuntime runtime) { this.runtime = runtime; }
    @Override public Set<NodePackageCapability> requiredServices() { return Set.of(NodePackageCapability.OUTBOUND_HTTP); }
    @Override public NodeTypeDescriptor descriptor() { return MatrixBehaviorDescriptors.send(); }
    @Override public NodeAction create(NodeConfiguration configuration) {
        return create(configuration, NodePackageServices.unavailable());
    }
    @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
        String profileName = MatrixBehaviorDescriptors.profile(configuration);
        String configuredRoom = configuration.property("roomId").orElse("");
        Map<String, String> tightening = new LinkedHashMap<>();
        for (String name : List.of("requestTimeoutMs", "maxTextChars", "maxConcurrency"))
            tightening.put(name, configuration.property(name).orElse(""));
        return new Action(runtime, services, profileName, configuredRoom, Map.copyOf(tightening));
    }

    private static final class Action implements NodeAction {
        private final MatrixRuntime runtime; private final NodePackageServices services;
        private final String profileName; private final String configuredRoom; private final Map<String, String> tightening;
        private final AtomicReference<Semaphore> nodeGate = new AtomicReference<>();
        Action(MatrixRuntime runtime, NodePackageServices services, String profileName,
               String configuredRoom, Map<String, String> tightening) {
            this.runtime = runtime; this.services = services; this.profileName = profileName;
            this.configuredRoom = configuredRoom; this.tightening = tightening;
        }

        @Override public CompletionStage<NodeResult> handle(NodeMessage message) { return handle(message, new NeverCancelled()); }
        @Override public CompletionStage<NodeResult> handle(NodeMessage message, CancellationSignal cancellation) {
            final Settings settings; final Payload payload;
            try {
                settings = Settings.from(runtime.profile(message.tenantId(), profileName), configuredRoom, tightening);
                payload = Payload.from(message.payload(), settings);
            } catch (RuntimeException failure) { return CompletableFuture.failedFuture(sanitize(failure, false)); }
            Semaphore local = nodeGate.updateAndGet(existing -> existing == null
                    ? new Semaphore(settings.maxConcurrency) : existing);
            Semaphore profile = runtime.gate(settings.profile);
            if (!local.tryAcquire()) return CompletableFuture.completedFuture(result("capacity", payload, 0,
                    settings.profile.maxResponseBytes(), "local-capacity"));
            if (!profile.tryAcquire()) {
                local.release(); return CompletableFuture.completedFuture(result("capacity", payload, 0,
                        settings.profile.maxResponseBytes(), "profile-capacity"));
            }
            String rateKey = message.tenantId() + "\u0000" + settings.profile.name();
            if (!runtime.rates.allow(rateKey, settings.profile.maxPerSecond())) {
                profile.release(); local.release(); return CompletableFuture.completedFuture(result("rate-limited", payload,
                        429, settings.profile.maxResponseBytes(), "local-rate-limit"));
            }
            CompletableFuture<NodeResult> result = new CompletableFuture<>();
            AtomicReference<OutboundCall<OutboundHttpResponse>> active = new AtomicReference<>();
            AtomicBoolean cancelled = new AtomicBoolean(cancellation.cancelled());
            cancellation.onCancel(() -> { cancelled.set(true); OutboundCall<?> call = active.get(); if (call != null) call.cancel(); });
            Thread.startVirtualThread(() -> {
                NodeResult outcome = null;
                RuntimeException failure = null;
                try { outcome = send(message, settings, payload, cancelled, active); }
                catch (RuntimeException caught) { failure = sanitize(caught, true); }
                finally { active.set(null); profile.release(); local.release(); }
                if (failure == null) result.complete(outcome); else result.completeExceptionally(failure);
            });
            return result;
        }

        private NodeResult send(NodeMessage message, Settings settings, Payload payload, AtomicBoolean cancelled,
                                AtomicReference<OutboundCall<OutboundHttpResponse>> active) {
            if (cancelled.get()) throw new MatrixException(MatrixException.Code.CANCELLED);
            byte[] body = payload.body();
            if (body.length > settings.profile.maxRequestBytes()) throw new MatrixException(MatrixException.Code.INVALID_INPUT);
            String txn = "rr_" + MatrixValues.sha256((payload.correlationId + "\u0000"
                    + MatrixValues.sha256(body)).getBytes(StandardCharsets.UTF_8)).substring(0, 48);
            String path = "/_matrix/client/v3/rooms/" + encode(payload.roomId)
                    + "/send/m.room.message/" + encode(txn);
            OutboundHttpRequest request = new OutboundHttpRequest(settings.profile.endpoint(path), "PUT",
                    Map.of("accept", List.of("application/json"), "content-type", List.of("application/json; charset=utf-8"),
                            "user-agent", List.of("ravenroot-matrix/1")), body, Duration.ofMillis(settings.timeoutMs),
                    settings.profile.credential(), null,
                    ExternalIoLimits.compressedHttp(settings.profile.maxRequestBytes(), settings.profile.maxResponseBytes(),
                            settings.profile.maxResponseBytes(), settings.profile.maxResponseBytes(), 100,
                            Duration.ofMillis(settings.timeoutMs), Set.of("application/json")),
                    OutboundHttpRepresentationPolicy.ALL_STATUSES);
            final OutboundCall<OutboundHttpResponse> call;
            try { call = services.outboundHttp().execute(message, request); }
            catch (RuntimeException preDispatch) { throw sanitize(preDispatch, false); }
            active.set(call); if (cancelled.get()) call.cancel();
            try {
                OutboundHttpResponse response = call.completion().toCompletableFuture()
                        .get(settings.timeoutMs, TimeUnit.MILLISECONDS);
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    Map<String, Object> remote = MatrixValues.json(response.body());
                    String eventId = MatrixProfile.opaque(MatrixValues.string(remote.get("event_id"), 255), 255);
                    return result("sent", payload, response.statusCode(), response.effectiveMaximumOutputBytes(), eventId);
                }
                String status = response.statusCode() == 401 ? "authentication-failed"
                        : response.statusCode() == 403 ? "forbidden"
                        : response.statusCode() == 429 ? "rate-limited"
                        : response.statusCode() >= 500 ? "indeterminate" : "rejected";
                return result(status, payload, response.statusCode(), response.effectiveMaximumOutputBytes(), "provider-status");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt(); call.cancel(); throw new MatrixException(MatrixException.Code.CANCELLED);
            } catch (TimeoutException failure) { call.cancel(); throw new MatrixException(MatrixException.Code.INDETERMINATE); }
            catch (ExecutionException failure) { throw sanitize(failure.getCause(), true); }
            catch (MatrixException failure) { throw failure; }
            catch (RuntimeException failure) { throw new MatrixException(MatrixException.Code.RESPONSE_INVALID); }
            finally { active.compareAndSet(call, null); }
        }
    }

    private record Settings(MatrixProfile profile, String room, int timeoutMs, int maxTextChars, int maxConcurrency) {
        static Settings from(MatrixProfile profile, String room, Map<String, String> values) {
            if (!room.isEmpty() && !profile.permitsRoom(MatrixProfile.matrixId(room, '!', 255)))
                throw new MatrixException(MatrixException.Code.FORBIDDEN);
            return new Settings(profile, room, tighten(values.get("requestTimeoutMs"), profile.requestTimeoutMs(), 1_000),
                    tighten(values.get("maxTextChars"), profile.maxTextChars(), 1),
                    tighten(values.get("maxConcurrency"), profile.maxConcurrency(), 1));
        }
        private static int tighten(String raw, int ceiling, int minimum) {
            if (raw == null || raw.isBlank()) return ceiling;
            try { int value = Integer.parseInt(raw); if (value < minimum || value > ceiling) throw new NumberFormatException(); return value; }
            catch (NumberFormatException failure) { throw new MatrixException(MatrixException.Code.CONFIGURATION); }
        }
    }

    private record Payload(String roomId, String text, String correlationId) {
        static Payload from(Object raw, Settings settings) {
            Map<String, Object> value = MatrixValues.object(raw); MatrixValues.exact(value, PAYLOAD_FIELDS);
            if (!"matrix.message.v1".equals(MatrixValues.string(value.get("version"), 64))) throw MatrixValues.invalid();
            String room = MatrixProfile.matrixId(MatrixValues.string(value.get("roomId"), 255), '!', 255);
            if (!settings.profile.permitsRoom(room) || (!settings.room.isEmpty() && !settings.room.equals(room)))
                throw new MatrixException(MatrixException.Code.FORBIDDEN);
            String text = MatrixValues.string(value.get("text"), settings.maxTextChars);
            if (text.codePointCount(0, text.length()) > settings.maxTextChars) throw MatrixValues.invalid();
            String correlation = MatrixProfile.opaque(MatrixValues.string(value.get("correlationId"), 128), 128);
            return new Payload(room, text, correlation);
        }
        byte[] body() { return MatrixValues.jsonBytes(Map.of("msgtype", "m.text", "body", text)); }
    }

    private static NodeResult result(String status, Payload payload, int code, long maximumBytes, String evidence) {
        Map<String, Object> value = new LinkedHashMap<>(); value.put("version", "matrix.message.result.v1");
        value.put("status", status); value.put("roomId", payload.roomId); value.put("correlationId", payload.correlationId);
        value.put("attempt", 1L); value.put("code", (long) code); value.put("evidence", evidence);
        return MatrixValues.result("continue", value, maximumBytes);
    }
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
    private static RuntimeException sanitize(Throwable raw, boolean dispatched) {
        Throwable failure = raw;
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null)
            failure = failure.getCause();
        if (failure instanceof MatrixException matrix) return matrix;
        if (failure instanceof CancellationException) return new MatrixException(MatrixException.Code.CANCELLED);
        if (failure instanceof NodePackageServiceException service) return new MatrixException(switch (service.reason()) {
            case CREDENTIAL_UNAVAILABLE -> MatrixException.Code.AUTHENTICATION_FAILED;
            case DESTINATION_FORBIDDEN, RESOLUTION_REFUSED, TLS_REFUSED, PROTOCOL_REFUSED -> MatrixException.Code.FORBIDDEN;
            case REQUEST_TOO_LARGE, RESPONSE_TOO_LARGE -> MatrixException.Code.RESPONSE_INVALID;
            case ADMISSION_REFUSED, SERVICE_UNAVAILABLE, BUDGET_EXHAUSTED -> MatrixException.Code.CAPACITY;
            case CANCELLED -> MatrixException.Code.CANCELLED;
            case EFFECT_OUTCOME_INDETERMINATE -> MatrixException.Code.INDETERMINATE;
            case DEADLINE_EXCEEDED, TRANSPORT_FAILED -> dispatched ? MatrixException.Code.INDETERMINATE : MatrixException.Code.TRANSPORT;
        });
        return new MatrixException(dispatched ? MatrixException.Code.INDETERMINATE : MatrixException.Code.TRANSPORT);
    }
    private static final class NeverCancelled implements CancellationSignal {
        @Override public boolean cancelled() { return false; }
        @Override public void onCancel(Runnable listener) { }
    }
}
