package ai.ravenroot.extensions.slack;

import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.ingress.IngressRequest;
import ai.ravenroot.api.ingress.IngressRequestContext;
import ai.ravenroot.api.ingress.IngressResponse;
import ai.ravenroot.api.ingress.IngressRouteAuthority;
import ai.ravenroot.api.ingress.IngressRouteHandler;
import ai.ravenroot.api.ingress.IngressRouteLease;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.ManagedIngressSource;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.CredentialLease;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Shared raw-body signature and durable-ingress implementation for Slack callbacks. */
abstract class SlackIngressSourceBehavior implements NodeBehavior, InboundSourceCapable {
    enum Kind { EVENTS, COMMANDS }
    private static final Duration HARD_ACK_BUDGET = Duration.ofMillis(2_800);
    private final SlackRuntime runtime;
    private final Kind kind;
    SlackIngressSourceBehavior(SlackRuntime runtime, Kind kind) {
        this.runtime = java.util.Objects.requireNonNull(runtime); this.kind = java.util.Objects.requireNonNull(kind);
    }
    @Override public Set<NodePackageCapability> requiredServices() {
        return Set.of(NodePackageCapability.CREDENTIAL_RESOLUTION);
    }
    @Override public NodeAction create(NodeConfiguration configuration) {
        return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
    }
    @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
        return createSource(configuration, context, NodePackageServices.unavailable());
    }
    @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context,
                                                NodePackageServices services) {
        SlackProfile profile = runtime.profile(context.identity().tenantId(), SlackBehaviorDescriptors.profile(configuration));
        return new Source(profile, services, runtime, kind);
    }

    private static final class Source implements ManagedIngressSource {
        private final SlackProfile profile; private final NodePackageServices services;
        private final SlackRuntime runtime; private final Kind kind;
        private InboundSourceContext context; private IngressRouteLease lease; private long generation; private boolean started;
        Source(SlackProfile profile, NodePackageServices services, SlackRuntime runtime, Kind kind) {
            this.profile = profile; this.services = services; this.runtime = runtime; this.kind = kind;
        }
        @Override public synchronized CompletionStage<Void> start(InboundSourceContext context) {
            if (started) return CompletableFuture.completedFuture(null);
            this.context = context; generation++; started = true; return CompletableFuture.completedFuture(null);
        }
        @Override public synchronized CompletionStage<Void> activateManagedIngress(IngressRouteAuthority authority) {
            if (!started) return CompletableFuture.failedFuture(new SlackException(SlackException.Code.CONFIGURATION));
            if (lease == null) {
                String route = kind == Kind.EVENTS ? profile.eventsRoute() : profile.commandsRoute();
                lease = authority.acquire("slack." + kind.name().toLowerCase() + "."
                                + SlackValues.sha256(context.nodeId().getBytes(StandardCharsets.UTF_8)).substring(0, 20),
                        route, Set.of("POST"), new Handler(this, generation));
            }
            return CompletableFuture.completedFuture(null);
        }
        @Override public synchronized CompletionStage<Void> stop() {
            started = false; generation++; IngressRouteLease old = lease; lease = null; context = null;
            if (old != null) old.release(); return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Void> rollback() { return stop(); }
        @Override public CompletionStage<Void> shutdown() { return stop(); }
        synchronized InboundSourceContext active(long expected) { return started && generation == expected ? context : null; }
    }

    private static final class Handler implements IngressRouteHandler {
        private final Source source; private final long generation;
        Handler(Source source, long generation) { this.source = source; this.generation = generation; }
        @Override public CompletionStage<IngressResponse> handle(IngressRequest request) {
            return handle(request, new IngressRequestContext(Instant.now().plus(HARD_ACK_BUDGET), new NeverCancelled()));
        }
        @Override public CompletionStage<IngressResponse> handle(IngressRequest request, IngressRequestContext window) {
            long started = System.nanoTime();
            InboundSourceContext context = source.active(generation);
            if (context == null || !request.principal().tenantId().equals(context.identity().tenantId())) return response(503);
            if (!"POST".equals(request.method()) || request.body().length > source.profile.maxRequestBytes()) return response(400);
            String signature = request.headers().getOrDefault("x-slack-signature", "");
            String timestamp = request.headers().getOrDefault("x-slack-request-timestamp", "");
            if (!signature.matches("v0=[0-9a-f]{64}") || !timestamp.matches("[0-9]{1,20}")) return response(400);
            Duration budget = minimum(minimum(window.remaining(), Duration.ofMillis(source.profile.requestTimeoutMs())), HARD_ACK_BUDGET);
            if (budget.isZero()) return response(503);
            return CompletableFuture.supplyAsync(() -> accept(request, window, context, signature, timestamp,
                    started + budget.toNanos()));
        }

        private IngressResponse accept(IngressRequest request, IngressRequestContext window,
                                       InboundSourceContext context, String signature, String timestamp,
                                       long deadlineNanos) {
            if (!timestampAllowed(timestamp, source.runtime.clock.instant(), source.profile.signatureMaxAgeSeconds()))
                return empty(401);
            OutboundCall<CredentialLease> resolving = source.services.credentials().resolve(context,
                    source.profile.signingSecretReference(), remaining(window, deadlineNanos));
            window.cancellation().onCancel(resolving::cancel);
            char[] secret = null; byte[] key = null;
            try (CredentialLease lease = resolving.completion().toCompletableFuture()
                    .get(Math.max(1, remaining(window, deadlineNanos).toMillis()), TimeUnit.MILLISECONDS)) {
                if (!live(window, deadlineNanos)) return empty(503);
                secret = lease.copy(); key = utf8(secret);
                if (!verify(key, timestamp, request.body(), signature)) return empty(401);
            } catch (Exception failure) { return empty(503); }
            finally { if (secret != null) Arrays.fill(secret, '\0'); if (key != null) Arrays.fill(key, (byte) 0); }
            if (!live(window, deadlineNanos) || source.active(generation) == null) return empty(503);
            try {
                return source.kind == Kind.EVENTS ? event(request.body(), context, window, deadlineNanos)
                        : command(request, timestamp, context, window, deadlineNanos);
            } catch (SlackException failure) {
                return empty(switch (failure.code()) {
                    case FORBIDDEN -> 409; case CAPACITY -> 429; case DURABILITY_UNAVAILABLE -> 503; default -> 400;
                });
            } catch (RuntimeException failure) { return empty(503); }
        }

        private IngressResponse event(byte[] body, InboundSourceContext context,
                                      IngressRequestContext window, long deadlineNanos) {
            Map<String, Object> root = SlackValues.json(body);
            String type = SlackValues.string(root.get("type"), 64);
            String team = SlackProfile.slackId(SlackValues.string(root.get("team_id"), 32));
            String app = SlackProfile.slackId(SlackValues.string(root.get("api_app_id"), 32));
            if (!source.profile.teamId().equals(team) || !source.profile.applicationId().equals(app)) return empty(403);
            if ("url_verification".equals(type)) {
                String challenge = SlackValues.string(root.get("challenge"), 512);
                return live(window, deadlineNanos) ? json(200, Map.of("challenge", challenge)) : empty(503);
            }
            if (!"event_callback".equals(type)) return empty(403);
            String eventId = SlackValues.string(root.get("event_id"), 128);
            if (!eventId.matches("[A-Za-z0-9._:-]{1,128}")) return empty(400);
            Map<String, Object> event = SlackValues.object(root.get("event"));
            String eventType = SlackValues.string(event.get("type"), 80);
            if (!source.profile.eventTypes().contains(eventType)) return empty(403);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("version", "slack.event.v1"); payload.put("eventId", eventId);
            payload.put("teamId", team); payload.put("applicationId", app); payload.put("eventType", eventType);
            payload.put("event", event);
            return deliver(context, window, deadlineNanos, "event", eventId, body, payload);
        }

        private IngressResponse command(IngressRequest request, String timestamp, InboundSourceContext context,
                                        IngressRequestContext window, long deadlineNanos) {
            String contentType = request.headers().getOrDefault("content-type", "").toLowerCase(java.util.Locale.ROOT);
            if (!contentType.startsWith("application/x-www-form-urlencoded")) return empty(400);
            Map<String, String> form = form(request.body());
            String team = SlackProfile.slackId(required(form, "team_id", 32));
            String app = SlackProfile.slackId(required(form, "api_app_id", 32));
            String channel = SlackProfile.slackId(required(form, "channel_id", 32));
            String user = SlackProfile.slackId(required(form, "user_id", 32));
            String command = required(form, "command", 33);
            String text = optional(form, "text", source.profile.maxTextChars());
            if (!source.profile.teamId().equals(team) || !source.profile.applicationId().equals(app)
                    || !source.profile.channelIds().contains(channel) || !source.profile.commands().contains(command))
                return empty(403);
            byte[] keyBytes = (timestamp + "\u0000").getBytes(StandardCharsets.UTF_8);
            byte[] combined = new byte[keyBytes.length + request.body().length];
            System.arraycopy(keyBytes, 0, combined, 0, keyBytes.length);
            System.arraycopy(request.body(), 0, combined, keyBytes.length, request.body().length);
            String delivery = SlackValues.sha256(combined);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("version", "slack.command.v1"); payload.put("teamId", team);
            payload.put("applicationId", app); payload.put("channelId", channel); payload.put("userId", user);
            payload.put("command", command); payload.put("text", text);
            return deliver(context, window, deadlineNanos, "command", delivery, request.body(), payload);
        }

        private IngressResponse deliver(InboundSourceContext context, IngressRequestContext window, long deadlineNanos,
                                        String kind, String delivery, byte[] body, Map<String, Object> payload) {
            source.runtime.store().bind(context.identity().tenantId(), source.profile.name(), kind,
                    delivery, SlackValues.sha256(body));
            if (!live(window, deadlineNanos) || source.active(generation) == null) return empty(503);
            IngressReceipt receipt = context.ingress().offerDurably(context.identity(), IngressTarget.start(),
                    Map.copyOf(payload), context.nodeId(), source.profile.name() + ":" + kind + ":" + delivery);
            if (!live(window, deadlineNanos)) return empty(503);
            return switch (receipt) {
                case IngressReceipt.DurablyCommitted ignored -> empty(200);
                case IngressReceipt.Duplicate ignored -> empty(200);
                case IngressReceipt.Refused refused -> empty("buffer full".equals(refused.reason()) ? 429 : 503);
                case IngressReceipt.VolatileCustody ignored -> empty(503);
                case IngressReceipt.Ambiguous ignored -> empty(503);
            };
        }
    }

    private static boolean timestampAllowed(String timestamp, Instant now, int maximumAgeSeconds) {
        try {
            long supplied = Long.parseLong(timestamp);
            long current = now.getEpochSecond();
            long earliest = Math.subtractExact(current, maximumAgeSeconds);
            long latest = Math.addExact(current, maximumAgeSeconds);
            return supplied >= earliest && supplied <= latest;
        } catch (ArithmeticException | NumberFormatException invalid) { return false; }
    }
    private static boolean verify(byte[] key, String timestamp, byte[] body, String supplied) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(("v0:" + timestamp + ":").getBytes(StandardCharsets.UTF_8));
            byte[] expected = mac.doFinal(body);
            return MessageDigest.isEqual(expected, java.util.HexFormat.of().parseHex(supplied.substring(3)));
        } catch (Exception failure) { return false; }
    }
    private static Map<String, String> form(byte[] body) {
        if (body.length == 0) throw SlackValues.invalid();
        String encoded = new String(body, StandardCharsets.UTF_8);
        Map<String, String> result = new LinkedHashMap<>();
        for (String field : encoded.split("&", -1)) {
            int equals = field.indexOf('=');
            if (equals < 1) throw SlackValues.invalid();
            String key = decode(field.substring(0, equals)); String value = decode(field.substring(equals + 1));
            if (key.length() > 80 || result.putIfAbsent(key, value) != null || result.size() > 64) throw SlackValues.invalid();
        }
        return Map.copyOf(result);
    }
    private static String decode(String value) {
        try { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
        catch (IllegalArgumentException invalid) { throw SlackValues.invalid(); }
    }
    private static String required(Map<String, String> form, String name, int maximum) {
        String value = form.get(name);
        if (value == null || value.isBlank() || value.length() > maximum
                || value.codePoints().anyMatch(c -> c < 0x20 || c == 0x7f)) throw SlackValues.invalid();
        return value;
    }
    private static String optional(Map<String, String> form, String name, int maximum) {
        String value = form.getOrDefault(name, "");
        if (value.length() > maximum || value.codePoints().anyMatch(c -> c == 0 || c == 0x7f)) throw SlackValues.invalid();
        return value;
    }
    private static byte[] utf8(char[] chars) {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] result = new byte[encoded.remaining()]; encoded.get(result);
        if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0); return result;
    }
    private static Duration remaining(IngressRequestContext window, long deadlineNanos) {
        long nanos = Math.max(0, deadlineNanos - System.nanoTime());
        return minimum(window.remaining(), Duration.ofNanos(nanos));
    }
    private static boolean live(IngressRequestContext window, long deadlineNanos) {
        return window.live() && deadlineNanos > System.nanoTime();
    }
    private static Duration minimum(Duration left, Duration right) { return left.compareTo(right) <= 0 ? left : right; }
    private static CompletionStage<IngressResponse> response(int status) {
        return CompletableFuture.completedFuture(empty(status));
    }
    private static IngressResponse json(int status, Map<String, ?> value) {
        return new IngressResponse(status, Map.of("Content-Type", "application/json"), SlackValues.jsonBytes(value));
    }
    private static IngressResponse empty(int status) { return new IngressResponse(status, Map.of(), new byte[0]); }
    private static final class NeverCancelled implements ai.ravenroot.api.execution.CancellationSignal {
        @Override public boolean cancelled() { return false; }
        @Override public void onCancel(Runnable listener) { }
    }
}
