package ai.ravenroot.extensions.mattermost;

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

/** Sends a bounded post through managed egress and operator-approved credential placement. */
final class MattermostSendBehavior implements NodeBehavior {
    private static final Set<String> PAYLOAD_FIELDS = Set.of("version", "channelId", "text", "correlationId");
    private final MattermostRuntime runtime;
    MattermostSendBehavior(MattermostRuntime runtime) { this.runtime = runtime; }
    @Override public Set<NodePackageCapability> requiredServices() { return Set.of(NodePackageCapability.OUTBOUND_HTTP); }
    @Override public NodeTypeDescriptor descriptor() { return MattermostBehaviorDescriptors.send(); }
    @Override public NodeAction create(NodeConfiguration configuration) {
        return create(configuration, NodePackageServices.unavailable());
    }
    @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
        String profileName = MattermostBehaviorDescriptors.profile(configuration);
        String configuredChannel = property(configuration, "channelId");
        Map<String, String> tightening = new LinkedHashMap<>();
        for (String name : List.of("requestTimeoutMs", "maxTextChars", "maxConcurrency", "retries"))
            tightening.put(name, property(configuration, name));
        return new Action(runtime, services, profileName, configuredChannel, Map.copyOf(tightening));
    }

    private static final class Action implements NodeAction {
        private final MattermostRuntime runtime; private final NodePackageServices services;
        private final String profileName; private final String configuredChannel; private final Map<String, String> tightening;
        private final AtomicReference<Semaphore> nodeGate = new AtomicReference<>();
        Action(MattermostRuntime runtime, NodePackageServices services, String profileName,
               String configuredChannel, Map<String, String> tightening) {
            this.runtime = runtime; this.services = services; this.profileName = profileName;
            this.configuredChannel = configuredChannel; this.tightening = tightening;
        }
        @Override public CompletionStage<NodeResult> handle(NodeMessage message) { return handle(message, new NeverCancelled()); }
        @Override public CompletionStage<NodeResult> handle(NodeMessage message, CancellationSignal cancellation) {
            final Settings settings; final Payload payload;
            try {
                settings = Settings.from(runtime.profile(message.tenantId(), profileName), configuredChannel, tightening);
                payload = Payload.from(message.payload(), settings);
            } catch (RuntimeException failure) { return CompletableFuture.failedFuture(sanitize(failure, false)); }
            Semaphore local = nodeGate.updateAndGet(existing -> existing == null
                    ? new Semaphore(settings.maxConcurrency) : existing);
            Semaphore profile = runtime.gate(settings.profile);
            if (!local.tryAcquire()) return CompletableFuture.completedFuture(result("capacity", payload, 0, 0,
                    settings.profile.maxResponseBytes(), "local-capacity", ""));
            if (!profile.tryAcquire()) {
                local.release(); return CompletableFuture.completedFuture(result("capacity", payload, 0, 0,
                        settings.profile.maxResponseBytes(), "profile-capacity", ""));
            }
            String profileRateKey = message.tenantId() + "\u0000" + settings.profile.name();
            String channelRateKey = profileRateKey + "\u0000" + payload.channelId;
            if (!runtime.rates.allow(profileRateKey, settings.profile.maxPerSecond())
                    || !runtime.rates.allow(channelRateKey, settings.profile.maxPerSecond())) {
                profile.release(); local.release();
                return CompletableFuture.completedFuture(result("rate-limited", payload, 0, 429,
                        settings.profile.maxResponseBytes(), "local-rate-limit", ""));
            }
            CompletableFuture<NodeResult> result = new CompletableFuture<>();
            AtomicBoolean cancelled = new AtomicBoolean(cancellation.cancelled());
            AtomicReference<OutboundCall<OutboundHttpResponse>> active = new AtomicReference<>();
            cancellation.onCancel(() -> {
                cancelled.set(true); OutboundCall<?> call = active.get(); if (call != null) call.cancel();
            });
            Thread.startVirtualThread(() -> {
                NodeResult completed = null; RuntimeException failed = null;
                try { completed = send(message, settings, payload, profileRateKey, cancelled, active); }
                catch (RuntimeException failure) { failed = sanitize(failure, true); }
                finally { active.set(null); profile.release(); local.release(); }
                if (failed == null) result.complete(completed); else result.completeExceptionally(failed);
            });
            return result;
        }

        private NodeResult send(NodeMessage message, Settings settings, Payload payload, String rateKey,
                                AtomicBoolean cancelled, AtomicReference<OutboundCall<OutboundHttpResponse>> active) {
            byte[] body = MattermostValues.jsonBytes(Map.of("channel_id", payload.channelId, "message", payload.text));
            if (body.length > settings.profile.maxRequestBytes()) throw MattermostValues.invalid();
            long deadline = runtime.clock.millis() + settings.timeoutMs;
            for (int attempt = 1; attempt <= settings.retries + 1; attempt++) {
                if (cancelled.get()) throw new MattermostException(MattermostException.Code.CANCELLED);
                long remaining = deadline - runtime.clock.millis();
                if (remaining < 1) throw new MattermostException(MattermostException.Code.INDETERMINATE);
                OutboundHttpRequest request = new OutboundHttpRequest(settings.profile.posts(), "POST",
                        Map.of("accept", List.of("application/json"),
                                "content-type", List.of("application/json; charset=utf-8"),
                                "user-agent", List.of("ravenroot-mattermost/1")), body, Duration.ofMillis(remaining),
                        settings.profile.credential(), null,
                        ExternalIoLimits.compressedHttp(settings.profile.maxRequestBytes(),
                                settings.profile.maxResponseBytes(), settings.profile.maxResponseBytes(),
                                settings.profile.maxResponseBytes(), 100, Duration.ofMillis(remaining),
                                Set.of("application/json")), OutboundHttpRepresentationPolicy.ALL_STATUSES);
                final OutboundCall<OutboundHttpResponse> call;
                try { call = services.outboundHttp().execute(message, request); }
                catch (RuntimeException preDispatch) {
                    RuntimeException safe = sanitize(preDispatch, false);
                    if (safe instanceof MattermostException mattermost
                            && mattermost.code() == MattermostException.Code.CAPACITY && attempt <= settings.retries) continue;
                    throw safe;
                }
                active.set(call); if (cancelled.get()) call.cancel();
                final OutboundHttpResponse response;
                try { response = call.completion().toCompletableFuture().get(remaining, TimeUnit.MILLISECONDS); }
                catch (InterruptedException failure) {
                    Thread.currentThread().interrupt(); call.cancel(); throw new MattermostException(MattermostException.Code.CANCELLED);
                } catch (TimeoutException failure) {
                    call.cancel(); throw new MattermostException(MattermostException.Code.INDETERMINATE);
                } catch (ExecutionException failure) { throw sanitize(failure.getCause(), true); }
                finally { active.compareAndSet(call, null); }
                if (response.statusCode() == 429) {
                    long delay = retryAfterMillis(response.headers()); runtime.rates.blockFor(rateKey, delay);
                    if (attempt > settings.retries || delay < 1 || delay >= deadline - runtime.clock.millis())
                        return result("rate-limited", payload, attempt, 429, response.effectiveMaximumOutputBytes(),
                                "provider-rate-limit", "");
                    waitFor(delay, cancelled); continue;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String status = response.statusCode() == 401 ? "authentication-failed"
                            : response.statusCode() == 403 ? "forbidden"
                            : response.statusCode() >= 500 ? "indeterminate" : "rejected";
                    return result(status, payload, attempt, response.statusCode(),
                            response.effectiveMaximumOutputBytes(), "provider-status", "");
                }
                try {
                    Map<String, Object> remote = MattermostValues.json(response.body());
                    String postId = MattermostProfile.id(MattermostValues.string(remote.get("id"), 32));
                    String channel = MattermostProfile.id(MattermostValues.string(remote.get("channel_id"), 32));
                    if (!payload.channelId.equals(channel)) throw MattermostValues.invalid();
                    return result("sent", payload, attempt, response.statusCode(),
                            response.effectiveMaximumOutputBytes(), "provider-post-id", postId);
                } catch (RuntimeException invalid) {
                    throw new MattermostException(MattermostException.Code.RESPONSE_INVALID);
                }
            }
            throw new MattermostException(MattermostException.Code.INDETERMINATE);
        }
    }

    private record Settings(MattermostProfile profile, String channel, int timeoutMs,
                            int maxTextChars, int maxConcurrency, int retries) {
        static Settings from(MattermostProfile profile, String channel, Map<String, String> values) {
            if (!channel.isEmpty()) {
                MattermostProfile.id(channel); if (!profile.permitsChannel(channel)) throw MattermostValues.invalid();
            }
            return new Settings(profile, channel,
                    tighten(values.get("requestTimeoutMs"), profile.requestTimeoutMs(), 100),
                    tighten(values.get("maxTextChars"), profile.maxTextChars(), 1),
                    tighten(values.get("maxConcurrency"), profile.maxConcurrency(), 1),
                    tighten(values.get("retries"), profile.retries(), 0));
        }
        private static int tighten(String raw, int ceiling, int minimum) {
            if (raw == null || raw.isBlank()) return ceiling;
            try {
                int value = Integer.parseInt(raw);
                if (value < minimum || value > ceiling) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException failure) {
                throw new MattermostException(MattermostException.Code.CONFIGURATION);
            }
        }
    }
    private record Payload(String channelId, String text, String correlationId) {
        static Payload from(Object raw, Settings settings) {
            Map<String, Object> value = MattermostValues.object(raw); MattermostValues.exact(value, PAYLOAD_FIELDS);
            if (!"mattermost.message.v1".equals(MattermostValues.string(value.get("version"), 64)))
                throw MattermostValues.invalid();
            String channel = MattermostProfile.id(MattermostValues.string(value.get("channelId"), 32));
            if (!settings.profile.permitsChannel(channel)
                    || (!settings.channel.isEmpty() && !settings.channel.equals(channel)))
                throw new MattermostException(MattermostException.Code.FORBIDDEN);
            return new Payload(channel, MattermostValues.text(value.get("text"), settings.maxTextChars),
                    MattermostValues.optionalString(value.get("correlationId"), 128));
        }
    }
    private static NodeResult result(String status, Payload payload, int attempt, int code,
                                     long maximumBytes, String evidence, String postId) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("version", "mattermost.message.result.v1"); value.put("status", status);
        value.put("channelId", payload.channelId); value.put("correlationId", payload.correlationId);
        value.put("attempt", (long) attempt); value.put("code", (long) code); value.put("evidence", evidence);
        value.put("postId", postId);
        return MattermostValues.result("continue", value, maximumBytes);
    }
    private static long retryAfterMillis(Map<String, List<String>> headers) {
        String value = headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase("retry-after"))
                .flatMap(entry -> entry.getValue().stream()).findFirst().orElse("");
        try {
            long seconds = Long.parseLong(value.strip());
            return seconds < 1 ? -1 : seconds >= 300 ? 300_000 : seconds * 1_000;
        } catch (NumberFormatException failure) { return -1; }
    }
    private static void waitFor(long millis, AtomicBoolean cancelled) {
        long remaining = millis;
        while (remaining > 0) {
            if (cancelled.get()) throw new MattermostException(MattermostException.Code.CANCELLED);
            long slice = Math.min(remaining, 50); long before = System.nanoTime();
            try { Thread.sleep(slice); }
            catch (InterruptedException failure) {
                Thread.currentThread().interrupt(); throw new MattermostException(MattermostException.Code.CANCELLED);
            }
            remaining -= Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before));
        }
    }
    private static RuntimeException sanitize(Throwable raw, boolean dispatched) {
        Throwable failure = raw;
        while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) failure = failure.getCause();
        if (failure instanceof MattermostException mattermost) return mattermost;
        if (failure instanceof CancellationException) return new MattermostException(MattermostException.Code.CANCELLED);
        if (failure instanceof NodePackageServiceException service) return new MattermostException(switch (service.reason()) {
            case CREDENTIAL_UNAVAILABLE -> MattermostException.Code.AUTHENTICATION_FAILED;
            case DESTINATION_FORBIDDEN, RESOLUTION_REFUSED, TLS_REFUSED, PROTOCOL_REFUSED -> MattermostException.Code.FORBIDDEN;
            case REQUEST_TOO_LARGE, RESPONSE_TOO_LARGE -> MattermostException.Code.RESPONSE_INVALID;
            case ADMISSION_REFUSED, SERVICE_UNAVAILABLE, BUDGET_EXHAUSTED -> MattermostException.Code.CAPACITY;
            case CANCELLED -> MattermostException.Code.CANCELLED;
            case EFFECT_OUTCOME_INDETERMINATE -> MattermostException.Code.INDETERMINATE;
            case DEADLINE_EXCEEDED, TRANSPORT_FAILED -> dispatched
                    ? MattermostException.Code.INDETERMINATE : MattermostException.Code.TRANSPORT;
        });
        return new MattermostException(dispatched
                ? MattermostException.Code.INDETERMINATE : MattermostException.Code.INVALID_INPUT);
    }
    private static String property(NodeConfiguration configuration, String name) {
        return configuration.property(name, "").strip();
    }
    private static final class NeverCancelled implements CancellationSignal {
        @Override public boolean cancelled() { return false; }
        @Override public void onCancel(Runnable listener) { }
    }
}
