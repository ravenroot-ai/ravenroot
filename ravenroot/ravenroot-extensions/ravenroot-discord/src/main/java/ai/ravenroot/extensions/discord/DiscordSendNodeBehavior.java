package ai.ravenroot.extensions.discord;

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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

/** Bounded Discord channel-message sender using only the managed HTTP service. */
public final class DiscordSendNodeBehavior implements NodeBehavior {
    private static final Set<String> PAYLOAD_FIELDS = Set.of("version", "channelId", "content", "attachments", "correlationId");
    private final DiscordRuntime runtime;
    DiscordSendNodeBehavior(DiscordRuntime runtime) { this.runtime = runtime; }

    @Override public Set<NodePackageCapability> requiredServices() { return Set.of(NodePackageCapability.OUTBOUND_HTTP); }
    @Override public NodeTypeDescriptor descriptor() { return DiscordBehaviorDescriptors.send(); }
    @Override public NodeAction create(NodeConfiguration configuration) { return create(configuration, NodePackageServices.unavailable()); }
    @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
        String profileName = DiscordBehaviorDescriptors.profile(configuration);
        String configuredChannel = property(configuration, "channelId");
        Map<String, String> tightening = new LinkedHashMap<>();
        for (String name : List.of("requestTimeoutMs", "maxContentChars", "maxAttachmentBytes",
                "maxAttachments", "maxConcurrency", "retries")) tightening.put(name, property(configuration, name));
        return new Action(runtime, services, profileName, configuredChannel, Map.copyOf(tightening));
    }

    private static final class Action implements NodeAction {
        private final DiscordRuntime runtime; private final NodePackageServices services;
        private final String profileName; private final String configuredChannel; private final Map<String, String> tightening;
        private final AtomicReference<Semaphore> nodeGate = new AtomicReference<>();
        Action(DiscordRuntime runtime, NodePackageServices services, String profileName,
               String configuredChannel, Map<String, String> tightening) {
            this.runtime = runtime; this.services = services; this.profileName = profileName;
            this.configuredChannel = configuredChannel; this.tightening = tightening;
        }
        @Override public CompletionStage<NodeResult> handle(NodeMessage message) {
            return handle(message, new NeverCancelled());
        }
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
            String rateKey = message.tenantId() + "\u0000" + settings.profile.name();
            if (!runtime.rates.allow(rateKey, settings.profile.maxPerSecond())) {
                profile.release(); local.release(); return CompletableFuture.completedFuture(result("rate-limited", payload,
                        0, 429, settings.profile.maxResponseBytes(), "local-rate-limit"));
            }
            CompletableFuture<NodeResult> result = new CompletableFuture<>();
            AtomicBoolean cancelled = new AtomicBoolean(cancellation.cancelled());
            AtomicReference<OutboundCall<OutboundHttpResponse>> active = new AtomicReference<>();
            cancellation.onCancel(() -> { cancelled.set(true); OutboundCall<?> call = active.get(); if (call != null) call.cancel(); });
            Thread.startVirtualThread(() -> {
                try { result.complete(send(message, settings, payload, rateKey, cancelled, active)); }
                catch (RuntimeException failure) { result.completeExceptionally(sanitize(failure, true)); }
                finally { active.set(null); profile.release(); local.release(); }
            });
            return result;
        }

        private NodeResult send(NodeMessage message, Settings settings, Payload payload, String rateKey,
                                AtomicBoolean cancelled, AtomicReference<OutboundCall<OutboundHttpResponse>> active) {
            RequestBody body = payload.body();
            if (body.bytes.length > settings.profile.maxRequestBytes())
                throw new DiscordException(DiscordException.Code.INVALID_INPUT);
            long deadline = runtime.clock.millis() + settings.timeoutMs;
            for (int attempt = 1; attempt <= settings.retries + 1; attempt++) {
                if (cancelled.get()) throw new DiscordException(DiscordException.Code.CANCELLED);
                long remaining = deadline - runtime.clock.millis();
                if (remaining < 1) throw new DiscordException(DiscordException.Code.INDETERMINATE);
                OutboundHttpRequest request = new OutboundHttpRequest(settings.profile.channelMessages(payload.channelId), "POST",
                        Map.of("accept", List.of("application/json"), "content-type", List.of(body.contentType),
                                "user-agent", List.of("ravenroot-discord/1")), body.bytes,
                        Duration.ofMillis(remaining), settings.profile.credential(), null,
                        ExternalIoLimits.compressedHttp(settings.profile.maxRequestBytes(), settings.profile.maxResponseBytes(),
                                settings.profile.maxResponseBytes(), settings.profile.maxResponseBytes(), 100,
                                Duration.ofMillis(remaining), Set.of("application/json")),
                        OutboundHttpRepresentationPolicy.SUCCESS_ONLY);
                final OutboundCall<OutboundHttpResponse> call;
                try { call = services.outboundHttp().execute(message, request); }
                catch (RuntimeException preDispatch) {
                    RuntimeException safe = sanitize(preDispatch, false);
                    if (safe instanceof DiscordException discord && discord.code() == DiscordException.Code.CAPACITY
                            && attempt <= settings.retries) continue;
                    throw safe;
                }
                active.set(call); if (cancelled.get()) call.cancel();
                final OutboundHttpResponse response;
                try { response = call.completion().toCompletableFuture().get(remaining, TimeUnit.MILLISECONDS); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); call.cancel(); throw new DiscordException(DiscordException.Code.CANCELLED); }
                catch (TimeoutException failure) { call.cancel(); throw new DiscordException(DiscordException.Code.INDETERMINATE); }
                catch (ExecutionException failure) {
                    RuntimeException safe = sanitize(failure.getCause(), true);
                    if (safe instanceof DiscordException discord && discord.code() == DiscordException.Code.CAPACITY
                            && attempt <= settings.retries) continue;
                    throw safe;
                } finally { active.compareAndSet(call, null); }
                long providerBlock = providerBlockMillis(response.headers());
                if (providerBlock > 0) runtime.rates.blockFor(rateKey, providerBlock);
                if (response.statusCode() == 429) {
                    if (attempt > settings.retries) return result("rate-limited", payload, attempt, 429,
                            response.effectiveMaximumOutputBytes(), "provider-rate-limit");
                    long delay = retryAfterMillis(response.headers());
                    if (delay < 1 || delay >= deadline - runtime.clock.millis()) return result("rate-limited", payload,
                            attempt, 429, response.effectiveMaximumOutputBytes(), "provider-rate-limit");
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
                    Map<String, Object> remote = DiscordValues.json(response.body());
                    String id = DiscordProfile.snowflake(DiscordValues.string(remote.get("id"), 20));
                    String channel = DiscordProfile.snowflake(DiscordValues.string(remote.get("channel_id"), 20));
                    if (!payload.channelId.equals(channel)) throw DiscordValues.invalid();
                    return result("sent", payload, attempt, response.statusCode(),
                            response.effectiveMaximumOutputBytes(), id);
                } catch (RuntimeException invalid) { throw new DiscordException(DiscordException.Code.RESPONSE_INVALID); }
            }
            throw new DiscordException(DiscordException.Code.RATE_LIMITED);
        }
    }

    private record Settings(DiscordProfile profile, String channel, int timeoutMs, int maxContentChars,
                            int maxAttachmentBytes, int maxAttachments, int maxConcurrency, int retries) {
        static Settings from(DiscordProfile profile, String channel, Map<String, String> values) {
            if (!channel.isEmpty()) { DiscordProfile.snowflake(channel); if (!profile.permitsChannel(channel)) throw DiscordValues.invalid(); }
            return new Settings(profile, channel,
                    tighten(values.get("requestTimeoutMs"), profile.requestTimeoutMs(), 100),
                    tighten(values.get("maxContentChars"), profile.maxContentChars(), 1),
                    tighten(values.get("maxAttachmentBytes"), profile.maxAttachmentBytes(), 1),
                    tighten(values.get("maxAttachments"), profile.maxAttachments(), 0),
                    tighten(values.get("maxConcurrency"), profile.maxConcurrency(), 1),
                    tighten(values.get("retries"), profile.retries(), 0));
        }
        private static int tighten(String raw, int ceiling, int minimum) {
            if (raw == null || raw.isBlank()) return ceiling;
            try { int value = Integer.parseInt(raw); if (value < minimum || value > ceiling) throw new NumberFormatException(); return value; }
            catch (NumberFormatException failure) { throw new DiscordException(DiscordException.Code.CONFIGURATION); }
        }
    }

    private record Payload(String channelId, String content, List<Attachment> attachments, String correlationId) {
        static Payload from(Object raw, Settings settings) {
            Map<String, Object> value = DiscordValues.object(raw); DiscordValues.exact(value, PAYLOAD_FIELDS);
            if (!"discord.message.v1".equals(DiscordValues.string(value.get("version"), 64))) throw DiscordValues.invalid();
            String channel = DiscordProfile.snowflake(DiscordValues.string(value.get("channelId"), 20));
            if (!settings.profile.permitsChannel(channel) || (!settings.channel.isEmpty() && !settings.channel.equals(channel)))
                throw new DiscordException(DiscordException.Code.FORBIDDEN);
            String content = DiscordValues.optionalString(value.get("content"), settings.maxContentChars);
            if (content.codePointCount(0, content.length()) > settings.maxContentChars) throw DiscordValues.invalid();
            List<Map<String, Object>> rawAttachments = value.get("attachments") == null ? List.of()
                    : DiscordValues.objectList(value.get("attachments"), settings.maxAttachments);
            List<Attachment> attachments = new ArrayList<>(); long bytes = 0;
            for (Map<String, Object> item : rawAttachments) {
                Attachment attachment = Attachment.from(item, settings.maxAttachmentBytes); bytes += attachment.bytes.length;
                if (bytes > settings.maxAttachmentBytes) throw DiscordValues.invalid(); attachments.add(attachment);
            }
            if (content.isEmpty() && attachments.isEmpty()) throw DiscordValues.invalid();
            return new Payload(channel, content, List.copyOf(attachments),
                    DiscordValues.optionalString(value.get("correlationId"), 128));
        }

        RequestBody body() {
            Map<String, Object> payload = new LinkedHashMap<>();
            if (!content.isEmpty()) payload.put("content", content);
            payload.put("allowed_mentions", Map.of("parse", List.of()));
            if (attachments.isEmpty()) return new RequestBody(DiscordValues.jsonBytes(payload), "application/json");
            List<Map<String, Object>> descriptors = new ArrayList<>();
            for (int i = 0; i < attachments.size(); i++) descriptors.add(Map.of("id", (long) i, "filename", attachments.get(i).filename));
            payload.put("attachments", List.copyOf(descriptors));
            String boundary = "ravenroot-" + UUID.randomUUID();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                textPart(out, boundary, "payload_json", new String(DiscordValues.jsonBytes(payload), StandardCharsets.UTF_8));
                for (int i = 0; i < attachments.size(); i++) filePart(out, boundary, i, attachments.get(i));
                out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
                return new RequestBody(out.toByteArray(), "multipart/form-data; boundary=" + boundary);
            } catch (IOException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    private record Attachment(byte[] bytes, String filename, String mediaType) {
        Attachment { bytes = bytes.clone(); }
        static Attachment from(Map<String, Object> value, int maximumBytes) {
            DiscordValues.exact(value, Set.of("contentBase64", "filename", "mediaType"));
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(DiscordValues.string(value.get("contentBase64"), maximumBytes * 2)); }
            catch (IllegalArgumentException failure) { throw DiscordValues.invalid(); }
            if (bytes.length == 0 || bytes.length > maximumBytes) throw DiscordValues.invalid();
            String filename = DiscordValues.string(value.get("filename"), 100);
            if (!filename.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) throw DiscordValues.invalid();
            String media = DiscordValues.string(value.get("mediaType"), 64).toLowerCase(Locale.ROOT);
            if (!Set.of("image/png", "image/jpeg", "image/gif", "text/plain", "application/pdf").contains(media))
                throw DiscordValues.invalid();
            return new Attachment(bytes, filename, media);
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    private record RequestBody(byte[] bytes, String contentType) { }

    private static void textPart(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"\r\nContent-Type: application/json\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
    private static void filePart(ByteArrayOutputStream out, String boundary, int index, Attachment attachment) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[" + index
                + "]\"; filename=\"" + attachment.filename + "\"\r\nContent-Type: " + attachment.mediaType
                + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(attachment.bytes); out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    private static NodeResult result(String status, Payload payload, int attempt, int code,
                                     long maximumBytes, String evidence) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("version", "discord.message.result.v1"); value.put("status", status);
        value.put("channelId", payload.channelId); value.put("correlationId", payload.correlationId);
        value.put("attempt", (long) attempt); value.put("code", (long) code); value.put("evidence", evidence);
        return DiscordValues.result("continue", value, maximumBytes);
    }

    private static long retryAfterMillis(Map<String, List<String>> headers) {
        return secondsMillis(header(headers, "retry-after"));
    }
    private static long providerBlockMillis(Map<String, List<String>> headers) {
        if (!"0".equals(header(headers, "x-ratelimit-remaining").strip())) return -1;
        return secondsMillis(header(headers, "x-ratelimit-reset-after"));
    }
    private static long secondsMillis(String value) {
        if (value.isEmpty()) return -1;
        try {
            double seconds = Double.parseDouble(value);
            if (!Double.isFinite(seconds) || seconds <= 0) return -1;
            return Math.min(300_000, Math.max(1, (long) Math.ceil(seconds * 1_000)));
        } catch (NumberFormatException failure) { return -1; }
    }
    private static String header(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream()).findFirst().orElse("");
    }
    private static void waitFor(long millis, AtomicBoolean cancelled) {
        long remaining = millis;
        while (remaining > 0) {
            if (cancelled.get()) throw new DiscordException(DiscordException.Code.CANCELLED);
            long slice = Math.min(remaining, 50); long before = System.nanoTime();
            try { Thread.sleep(slice); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new DiscordException(DiscordException.Code.CANCELLED); }
            remaining -= Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before));
        }
    }
    private static RuntimeException sanitize(Throwable raw, boolean dispatched) {
        Throwable failure = raw;
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null)
            failure = failure.getCause();
        if (failure instanceof DiscordException discord) return discord;
        if (failure instanceof CancellationException) return new DiscordException(DiscordException.Code.CANCELLED);
        if (failure instanceof NodePackageServiceException service) return new DiscordException(switch (service.reason()) {
            case CREDENTIAL_UNAVAILABLE -> DiscordException.Code.AUTHENTICATION_FAILED;
            case DESTINATION_FORBIDDEN, RESOLUTION_REFUSED, TLS_REFUSED, PROTOCOL_REFUSED -> DiscordException.Code.FORBIDDEN;
            case REQUEST_TOO_LARGE, RESPONSE_TOO_LARGE -> DiscordException.Code.RESPONSE_INVALID;
            case ADMISSION_REFUSED, SERVICE_UNAVAILABLE, BUDGET_EXHAUSTED -> DiscordException.Code.CAPACITY;
            case CANCELLED -> DiscordException.Code.CANCELLED;
            case EFFECT_OUTCOME_INDETERMINATE -> DiscordException.Code.INDETERMINATE;
            case DEADLINE_EXCEEDED, TRANSPORT_FAILED -> dispatched
                    ? DiscordException.Code.INDETERMINATE : DiscordException.Code.TRANSPORT;
        });
        return new DiscordException(dispatched ? DiscordException.Code.INDETERMINATE : DiscordException.Code.INVALID_INPUT);
    }
    private static String property(NodeConfiguration configuration, String name) { return configuration.property(name, "").strip(); }
    private static final class NeverCancelled implements CancellationSignal {
        @Override public boolean cancelled() { return false; }
        @Override public void onCancel(Runnable listener) { }
    }
}
