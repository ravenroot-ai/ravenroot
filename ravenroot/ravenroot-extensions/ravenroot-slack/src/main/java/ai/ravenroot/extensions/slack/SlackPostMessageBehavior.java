package ai.ravenroot.extensions.slack;

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

/** Bounded Slack channel-message sender using only the managed HTTP service. */
public final class SlackPostMessageBehavior implements NodeBehavior {
    private static final Set<String> PAYLOAD_FIELDS = Set.of("version", "channelId", "text", "threadTs", "correlationId");
    private final SlackRuntime runtime;
    SlackPostMessageBehavior(SlackRuntime runtime) { this.runtime = runtime; }
    @Override public Set<NodePackageCapability> requiredServices() { return Set.of(NodePackageCapability.OUTBOUND_HTTP); }
    @Override public NodeTypeDescriptor descriptor() { return SlackBehaviorDescriptors.postMessage(); }
    @Override public NodeAction create(NodeConfiguration configuration) { return create(configuration, NodePackageServices.unavailable()); }
    @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
        String profileName = SlackBehaviorDescriptors.profile(configuration);
        String configuredChannel = property(configuration, "channelId");
        Map<String, String> tightening = new LinkedHashMap<>();
        for (String name : List.of("requestTimeoutMs", "maxTextChars", "maxConcurrency", "retries"))
            tightening.put(name, property(configuration, name));
        return new Action(runtime, services, profileName, configuredChannel, Map.copyOf(tightening));
    }

    private static final class Action implements NodeAction {
        private final SlackRuntime runtime; private final NodePackageServices services;
        private final String profileName; private final String configuredChannel; private final Map<String, String> tightening;
        private final AtomicReference<Semaphore> nodeGate = new AtomicReference<>();
        Action(SlackRuntime runtime, NodePackageServices services, String profileName,
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
            Semaphore local = nodeGate.updateAndGet(existing -> existing == null ? new Semaphore(settings.maxConcurrency) : existing);
            Semaphore profile = runtime.gate(settings.profile);
            if (!local.tryAcquire()) return CompletableFuture.completedFuture(result("capacity", payload, 0, 0,
                    settings.profile.maxResponseBytes(), "local-capacity"));
            if (!profile.tryAcquire()) { local.release(); return CompletableFuture.completedFuture(result("capacity", payload,
                    0, 0, settings.profile.maxResponseBytes(), "profile-capacity")); }
            String workspaceRateKey = message.tenantId() + "\u0000" + settings.profile.name();
            String channelRateKey = workspaceRateKey + "\u0000" + payload.channelId;
            if (!runtime.rates.allow(workspaceRateKey, settings.profile.maxPerSecond())
                    || !runtime.rates.allow(channelRateKey, settings.profile.maxPerSecond())) {
                profile.release(); local.release(); return CompletableFuture.completedFuture(result("rate-limited", payload,
                        0, 429, settings.profile.maxResponseBytes(), "local-rate-limit"));
            }
            CompletableFuture<NodeResult> result = new CompletableFuture<>();
            AtomicBoolean cancelled = new AtomicBoolean(cancellation.cancelled());
            AtomicReference<OutboundCall<OutboundHttpResponse>> active = new AtomicReference<>();
            cancellation.onCancel(() -> { cancelled.set(true); OutboundCall<?> call = active.get(); if (call != null) call.cancel(); });
            Thread.startVirtualThread(() -> {
                try { result.complete(send(message, settings, payload, workspaceRateKey, cancelled, active)); }
                catch (RuntimeException failure) { result.completeExceptionally(sanitize(failure, true)); }
                finally { active.set(null); profile.release(); local.release(); }
            });
            return result;
        }

        private NodeResult send(NodeMessage message, Settings settings, Payload payload, String rateKey,
                                AtomicBoolean cancelled, AtomicReference<OutboundCall<OutboundHttpResponse>> active) {
            byte[] body = payload.body();
            if (body.length > settings.profile.maxRequestBytes()) throw new SlackException(SlackException.Code.INVALID_INPUT);
            long deadline = runtime.clock.millis() + settings.timeoutMs;
            for (int attempt = 1; attempt <= settings.retries + 1; attempt++) {
                if (cancelled.get()) throw new SlackException(SlackException.Code.CANCELLED);
                long remaining = deadline - runtime.clock.millis();
                if (remaining < 1) throw new SlackException(SlackException.Code.INDETERMINATE);
                OutboundHttpRequest request = new OutboundHttpRequest(settings.profile.postMessage(), "POST",
                        Map.of("accept", List.of("application/json"), "content-type", List.of("application/json; charset=utf-8"),
                                "user-agent", List.of("ravenroot-slack/1")), body, Duration.ofMillis(remaining),
                        settings.profile.credential(), null,
                        ExternalIoLimits.compressedHttp(settings.profile.maxRequestBytes(), settings.profile.maxResponseBytes(),
                                settings.profile.maxResponseBytes(), settings.profile.maxResponseBytes(), 100,
                                Duration.ofMillis(remaining), Set.of("application/json")),
                        OutboundHttpRepresentationPolicy.ALL_STATUSES);
                final OutboundCall<OutboundHttpResponse> call;
                try { call = services.outboundHttp().execute(message, request); }
                catch (RuntimeException preDispatch) {
                    RuntimeException safe = sanitize(preDispatch, false);
                    if (safe instanceof SlackException slack && slack.code() == SlackException.Code.CAPACITY
                            && attempt <= settings.retries) continue;
                    throw safe;
                }
                active.set(call); if (cancelled.get()) call.cancel();
                final OutboundHttpResponse response;
                try { response = call.completion().toCompletableFuture().get(remaining, TimeUnit.MILLISECONDS); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); call.cancel(); throw new SlackException(SlackException.Code.CANCELLED); }
                catch (TimeoutException failure) { call.cancel(); throw new SlackException(SlackException.Code.INDETERMINATE); }
                catch (ExecutionException failure) { throw sanitize(failure.getCause(), true); }
                finally { active.compareAndSet(call, null); }
                if (response.statusCode() == 429) {
                    long delay = retryAfterMillis(response.headers()); runtime.rates.blockFor(rateKey, delay);
                    if (attempt > settings.retries || delay < 1 || delay >= deadline - runtime.clock.millis())
                        return result("rate-limited", payload, attempt, 429, response.effectiveMaximumOutputBytes(), "provider-rate-limit");
                    waitFor(delay, cancelled); continue;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String status = response.statusCode() == 401 ? "authentication-failed"
                            : response.statusCode() == 403 ? "forbidden"
                            : response.statusCode() >= 500 ? "indeterminate" : "rejected";
                    return result(status, payload, attempt, response.statusCode(),
                            response.effectiveMaximumOutputBytes(), "provider-status");
                }
                try {
                    Map<String, Object> remote = SlackValues.json(response.body());
                    if (!(remote.get("ok") instanceof Boolean ok)) throw SlackValues.invalid();
                    if (!ok) return result("rejected", payload, attempt, response.statusCode(),
                            response.effectiveMaximumOutputBytes(), "provider-rejection");
                    String channel = SlackProfile.slackId(SlackValues.string(remote.get("channel"), 32));
                    String ts = SlackValues.string(remote.get("ts"), 64);
                    if (!payload.channelId.equals(channel) || !ts.matches("[0-9]{1,20}\\.[0-9]{1,20}")) throw SlackValues.invalid();
                    return result("sent", payload, attempt, response.statusCode(), response.effectiveMaximumOutputBytes(), ts);
                } catch (RuntimeException invalid) { throw new SlackException(SlackException.Code.RESPONSE_INVALID); }
            }
            throw new SlackException(SlackException.Code.RATE_LIMITED);
        }
    }

    private record Settings(SlackProfile profile, String channel, int timeoutMs, int maxTextChars,
                            int maxConcurrency, int retries) {
        static Settings from(SlackProfile profile, String channel, Map<String, String> values) {
            if (!channel.isEmpty()) { SlackProfile.slackId(channel); if (!profile.permitsChannel(channel)) throw SlackValues.invalid(); }
            return new Settings(profile, channel,
                    tighten(values.get("requestTimeoutMs"), profile.requestTimeoutMs(), 100),
                    tighten(values.get("maxTextChars"), profile.maxTextChars(), 1),
                    tighten(values.get("maxConcurrency"), profile.maxConcurrency(), 1),
                    tighten(values.get("retries"), profile.retries(), 0));
        }
        private static int tighten(String raw, int ceiling, int minimum) {
            if (raw == null || raw.isBlank()) return ceiling;
            try { int value = Integer.parseInt(raw); if (value < minimum || value > ceiling) throw new NumberFormatException(); return value; }
            catch (NumberFormatException failure) { throw new SlackException(SlackException.Code.CONFIGURATION); }
        }
    }
    private record Payload(String channelId, String text, String threadTs, String correlationId) {
        static Payload from(Object raw, Settings settings) {
            Map<String, Object> value = SlackValues.object(raw); SlackValues.exact(value, PAYLOAD_FIELDS);
            if (!"slack.message.v1".equals(SlackValues.string(value.get("version"), 64))) throw SlackValues.invalid();
            String channel = SlackProfile.slackId(SlackValues.string(value.get("channelId"), 32));
            if (!settings.profile.permitsChannel(channel) || (!settings.channel.isEmpty() && !settings.channel.equals(channel)))
                throw new SlackException(SlackException.Code.FORBIDDEN);
            String text = SlackValues.string(value.get("text"), settings.maxTextChars);
            if (text.codePointCount(0, text.length()) > settings.maxTextChars) throw SlackValues.invalid();
            String thread = SlackValues.optionalString(value.get("threadTs"), 64);
            if (!thread.isEmpty() && !thread.matches("[0-9]{1,20}\\.[0-9]{1,20}")) throw SlackValues.invalid();
            return new Payload(channel, text, thread, SlackValues.optionalString(value.get("correlationId"), 128));
        }
        byte[] body() {
            Map<String, Object> value = new LinkedHashMap<>(); value.put("channel", channelId); value.put("text", text);
            if (!threadTs.isEmpty()) value.put("thread_ts", threadTs);
            value.put("unfurl_links", false); value.put("unfurl_media", false);
            return SlackValues.jsonBytes(value);
        }
    }
    private static NodeResult result(String status, Payload payload, int attempt, int code,
                                     long maximumBytes, String evidence) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("version", "slack.message.result.v1"); value.put("status", status);
        value.put("channelId", payload.channelId); value.put("correlationId", payload.correlationId);
        value.put("attempt", (long) attempt); value.put("code", (long) code); value.put("evidence", evidence);
        return SlackValues.result("continue", value, maximumBytes);
    }
    private static long retryAfterMillis(Map<String, List<String>> headers) {
        String value = headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase("retry-after"))
                .flatMap(entry -> entry.getValue().stream()).findFirst().orElse("");
        try {
            long seconds = Long.parseLong(value.strip());
            return seconds < 1 ? -1 : seconds >= 300 ? 300_000 : seconds * 1_000;
        }
        catch (NumberFormatException failure) { return -1; }
    }
    private static void waitFor(long millis, AtomicBoolean cancelled) {
        long remaining = millis;
        while (remaining > 0) {
            if (cancelled.get()) throw new SlackException(SlackException.Code.CANCELLED);
            long slice = Math.min(remaining, 50); long before = System.nanoTime();
            try { Thread.sleep(slice); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new SlackException(SlackException.Code.CANCELLED); }
            remaining -= Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before));
        }
    }
    private static RuntimeException sanitize(Throwable raw, boolean dispatched) {
        Throwable failure = raw;
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null)
            failure = failure.getCause();
        if (failure instanceof SlackException slack) return slack;
        if (failure instanceof CancellationException) return new SlackException(SlackException.Code.CANCELLED);
        if (failure instanceof NodePackageServiceException service) return new SlackException(switch (service.reason()) {
            case CREDENTIAL_UNAVAILABLE -> SlackException.Code.AUTHENTICATION_FAILED;
            case DESTINATION_FORBIDDEN, RESOLUTION_REFUSED, TLS_REFUSED, PROTOCOL_REFUSED -> SlackException.Code.FORBIDDEN;
            case REQUEST_TOO_LARGE, RESPONSE_TOO_LARGE -> SlackException.Code.RESPONSE_INVALID;
            case ADMISSION_REFUSED, SERVICE_UNAVAILABLE, BUDGET_EXHAUSTED -> SlackException.Code.CAPACITY;
            case CANCELLED -> SlackException.Code.CANCELLED;
            case EFFECT_OUTCOME_INDETERMINATE -> SlackException.Code.INDETERMINATE;
            case DEADLINE_EXCEEDED, TRANSPORT_FAILED -> dispatched ? SlackException.Code.INDETERMINATE : SlackException.Code.TRANSPORT;
        });
        return new SlackException(dispatched ? SlackException.Code.INDETERMINATE : SlackException.Code.INVALID_INPUT);
    }
    private static String property(NodeConfiguration configuration, String name) { return configuration.property(name, "").strip(); }
    private static final class NeverCancelled implements CancellationSignal {
        @Override public boolean cancelled() { return false; }
        @Override public void onCancel(Runnable listener) { }
    }
}
