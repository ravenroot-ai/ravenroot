package ai.ravenroot.extensions.mattermost;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
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

import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Token-authenticated, restart-safe Mattermost outgoing-webhook source. */
final class MattermostOutgoingWebhookSourceBehavior implements NodeBehavior, InboundSourceCapable {
    private static final Duration HARD_ACK_BUDGET = Duration.ofMillis(2_800);
    private static final Set<String> CALLBACK_FIELDS = Set.of("token", "team_id", "team_domain", "channel_id",
            "channel_name", "timestamp", "user_id", "user_name", "post_id", "text", "trigger_word");
    private static final Set<String> REQUIRED_FIELDS = Set.of("token", "team_id", "channel_id", "timestamp",
            "user_id", "post_id", "text");
    private final MattermostRuntime runtime;
    MattermostOutgoingWebhookSourceBehavior(MattermostRuntime runtime) { this.runtime = runtime; }
    @Override public Set<NodePackageCapability> requiredServices() {
        return Set.of(NodePackageCapability.CREDENTIAL_RESOLUTION);
    }
    @Override public NodeTypeDescriptor descriptor() { return MattermostBehaviorDescriptors.outgoing(); }
    @Override public NodeAction create(NodeConfiguration configuration) {
        return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
    }
    @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
        return createSource(configuration, context, NodePackageServices.unavailable());
    }
    @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context,
                                                NodePackageServices services) {
        MattermostProfile profile = runtime.profile(context.identity().tenantId(),
                MattermostBehaviorDescriptors.profile(configuration));
        return new Source(profile, services, runtime.store(), runtime);
    }

    private static final class Source implements ManagedIngressSource {
        private final MattermostProfile profile; private final NodePackageServices services;
        private final MattermostDeliveryStore store; private final MattermostRuntime runtime;
        private InboundSourceContext context; private IngressRouteLease lease;
        private long generation; private boolean started;
        Source(MattermostProfile profile, NodePackageServices services,
               MattermostDeliveryStore store, MattermostRuntime runtime) {
            this.profile = profile; this.services = services; this.store = store; this.runtime = runtime;
        }
        @Override public synchronized CompletionStage<Void> start(InboundSourceContext context) {
            if (started) return CompletableFuture.completedFuture(null);
            this.context = java.util.Objects.requireNonNull(context); generation++; started = true;
            return CompletableFuture.completedFuture(null);
        }
        @Override public synchronized CompletionStage<Void> activateManagedIngress(IngressRouteAuthority authority) {
            if (!started) return CompletableFuture.failedFuture(
                    new MattermostException(MattermostException.Code.CONFIGURATION));
            if (lease == null) {
                String sourceId = MattermostValues.sha256(context.nodeId().getBytes(StandardCharsets.UTF_8)).substring(0, 20);
                lease = authority.acquire("mattermost.outgoing." + sourceId, profile.outgoingWebhookRoute(),
                        Set.of("POST"), new Handler(this, generation, sourceId));
            }
            return CompletableFuture.completedFuture(null);
        }
        @Override public synchronized CompletionStage<Void> stop() {
            started = false; generation++; IngressRouteLease old = lease; lease = null; context = null;
            if (old != null) old.release(); return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Void> rollback() { return stop(); }
        @Override public CompletionStage<Void> shutdown() { return stop(); }
        synchronized InboundSourceContext active(long expected) {
            return started && generation == expected ? context : null;
        }
    }

    private static final class Handler implements IngressRouteHandler {
        private final Source source; private final long generation; private final String sourceId;
        Handler(Source source, long generation, String sourceId) {
            this.source = source; this.generation = generation; this.sourceId = sourceId;
        }
        @Override public CompletionStage<IngressResponse> handle(IngressRequest request) {
            return handle(request, new IngressRequestContext(Instant.now().plus(HARD_ACK_BUDGET), new NeverCancelled()));
        }
        @Override public CompletionStage<IngressResponse> handle(IngressRequest request, IngressRequestContext window) {
            long started = System.nanoTime(); InboundSourceContext context = source.active(generation);
            if (context == null || !request.principal().tenantId().equals(context.identity().tenantId())) return response(503);
            if (!"POST".equals(request.method()) || request.body().length == 0
                    || request.body().length > source.profile.maxRequestBytes()) return response(400);
            Duration budget = minimum(minimum(window.remaining(),
                    Duration.ofMillis(source.profile.requestTimeoutMs())), HARD_ACK_BUDGET);
            if (budget.isZero() || budget.isNegative()) return response(503);
            long deadlineNanos;
            try { deadlineNanos = Math.addExact(started, budget.toNanos()); }
            catch (ArithmeticException overflow) { return response(503); }
            return CompletableFuture.supplyAsync(() -> accept(request, window, context, deadlineNanos));
        }

        private IngressResponse accept(IngressRequest request, IngressRequestContext window,
                                       InboundSourceContext context, long deadlineNanos) {
            Semaphore gate = source.runtime.gate(source.profile);
            if (!gate.tryAcquire()) return empty(429);
            try {
                Map<String, String> callback = parse(request);
                if (!source.profile.teamId().equals(callback.get("team_id"))
                        || !source.profile.permitsChannel(callback.get("channel_id"))) return empty(403);
                if (!authenticate(callback.get("token"), context, window, deadlineNanos)) return empty(401);
                if (!live(window, deadlineNanos) || source.active(generation) == null) return empty(503);
                String postId = MattermostProfile.id(required(callback, "post_id", 32));
                Map<String, Object> payload = payload(callback, postId);
                source.store.bind(context.identity().tenantId(),
                        source.profile.name(), sourceId, postId,
                        MattermostValues.sha256(MattermostValues.jsonBytes(payload)),
                        deadlineNanos, window.cancellation());
                if (!source.runtime.rates.allow(context.identity().tenantId() + "\u0000"
                        + source.profile.name() + "\u0000inbound", source.profile.maxPerSecond())) return empty(429);
                if (!live(window, deadlineNanos) || source.active(generation) == null) return empty(503);
                IngressReceipt receipt = context.ingress().offerDurably(context.identity(), IngressTarget.start(),
                        Map.copyOf(payload), context.nodeId(), source.profile.name() + ":" + sourceId + ":" + postId);
                if (!live(window, deadlineNanos) || source.active(generation) == null) return empty(503);
                return switch (receipt) {
                    case IngressReceipt.DurablyCommitted ignored -> empty(200);
                    case IngressReceipt.Duplicate ignored -> empty(200);
                    case IngressReceipt.Refused refused -> empty("buffer full".equals(refused.reason()) ? 429 : 503);
                    case IngressReceipt.VolatileCustody ignored -> empty(503);
                    case IngressReceipt.Ambiguous ignored -> empty(503);
                };
            } catch (MattermostException failure) {
                return empty(switch (failure.code()) {
                    case FORBIDDEN -> 409;
                    case CAPACITY -> 429;
                    case DURABILITY_UNAVAILABLE, CANCELLED -> 503;
                    default -> 400;
                });
            } catch (RuntimeException failure) { return empty(503); }
            finally { gate.release(); }
        }

        private boolean authenticate(String supplied, InboundSourceContext context,
                                     IngressRequestContext window, long deadlineNanos) {
            OutboundCall<CredentialLease> resolving = source.services.credentials().resolve(context,
                    source.profile.webhookTokenReference(), remaining(window, deadlineNanos));
            window.cancellation().onCancel(resolving::cancel);
            char[] secret = null; byte[] expected = null; byte[] actual = null;
            CompletableFuture<CredentialLease> future = resolving.completion().toCompletableFuture();
            try (CredentialLease lease = future.get(
                    Math.max(1, remaining(window, deadlineNanos).toMillis()), TimeUnit.MILLISECONDS)) {
                if (!live(window, deadlineNanos)) return false;
                secret = lease.copy(); expected = utf8(secret); actual = supplied.getBytes(StandardCharsets.UTF_8);
                return MessageDigest.isEqual(expected, actual);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt(); resolving.cancel(); closeLate(future);
                throw new MattermostException(MattermostException.Code.CANCELLED);
            } catch (TimeoutException failure) {
                resolving.cancel(); closeLate(future);
                throw new MattermostException(MattermostException.Code.DURABILITY_UNAVAILABLE);
            } catch (ExecutionException failure) {
                throw new MattermostException(window.cancellation().cancelled()
                        ? MattermostException.Code.CANCELLED : MattermostException.Code.DURABILITY_UNAVAILABLE);
            }
            finally {
                if (secret != null) Arrays.fill(secret, '\0');
                if (expected != null) Arrays.fill(expected, (byte) 0);
                if (actual != null) Arrays.fill(actual, (byte) 0);
            }
        }

        private Map<String, Object> payload(Map<String, String> callback, String postId) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("version", "mattermost.outgoing-webhook.v1"); payload.put("postId", postId);
            payload.put("teamId", callback.get("team_id")); payload.put("channelId", callback.get("channel_id"));
            payload.put("userId", callback.get("user_id")); payload.put("timestamp", callback.get("timestamp"));
            payload.put("text", MattermostValues.text(callback.get("text"), source.profile.maxTextChars()));
            payload.put("teamDomain", callback.getOrDefault("team_domain", ""));
            payload.put("channelName", callback.getOrDefault("channel_name", ""));
            payload.put("userName", callback.getOrDefault("user_name", ""));
            payload.put("triggerWord", callback.getOrDefault("trigger_word", ""));
            return payload;
        }

        private static void closeLate(CompletableFuture<CredentialLease> future) {
            future.whenComplete((lease, failure) -> { if (lease != null) lease.close(); });
        }
    }

    private static Map<String, String> parse(IngressRequest request) {
        String contentType = request.headers().getOrDefault("content-type", "").toLowerCase(Locale.ROOT);
        Map<String, String> value;
        if (contentType.startsWith("application/x-www-form-urlencoded")) value = form(request.body());
        else if (contentType.startsWith("application/json")) value = json(request.body());
        else throw MattermostValues.invalid();
        if (!CALLBACK_FIELDS.containsAll(value.keySet()) || !value.keySet().containsAll(REQUIRED_FIELDS))
            throw MattermostValues.invalid();
        required(value, "token", 512); MattermostProfile.id(required(value, "team_id", 32));
        MattermostProfile.id(required(value, "channel_id", 32)); MattermostProfile.id(required(value, "user_id", 32));
        MattermostProfile.id(required(value, "post_id", 32));
        if (!required(value, "timestamp", 20).matches("[0-9]{1,20}")) throw MattermostValues.invalid();
        MattermostValues.text(value.get("text"), 16_383);
        optional(value, "team_domain", 64); optional(value, "channel_name", 64);
        optional(value, "user_name", 64); optional(value, "trigger_word", 128);
        return value;
    }
    private static Map<String, String> form(byte[] body) {
        String encoded = new String(body, StandardCharsets.UTF_8); Map<String, String> result = new LinkedHashMap<>();
        for (String field : encoded.split("&", -1)) {
            int equals = field.indexOf('='); if (equals < 1) throw MattermostValues.invalid();
            String key = decode(field.substring(0, equals)); String value = decode(field.substring(equals + 1));
            if (key.length() > 80 || result.putIfAbsent(key, value) != null || result.size() > CALLBACK_FIELDS.size())
                throw MattermostValues.invalid();
        }
        return Map.copyOf(result);
    }
    private static Map<String, String> json(byte[] body) {
        Map<String, Object> raw = MattermostValues.json(body); Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            String text;
            if (value instanceof String string) text = string;
            else if ("timestamp".equals(key) && value instanceof Number number) text = Long.toString(
                    MattermostValues.number(number, 0, Long.MAX_VALUE));
            else throw MattermostValues.invalid();
            result.put(key, text);
        });
        return Map.copyOf(result);
    }
    private static String decode(String value) {
        try {
            String decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
            if (decoded.indexOf('\ufffd') >= 0) throw MattermostValues.invalid();
            return decoded;
        } catch (IllegalArgumentException invalid) { throw MattermostValues.invalid(); }
    }
    private static String required(Map<String, String> value, String name, int maximum) {
        String member = value.get(name);
        if (member == null || member.isBlank() || member.length() > maximum
                || member.codePoints().anyMatch(c -> c < 0x20 || c == 0x7f)) throw MattermostValues.invalid();
        return member;
    }
    private static String optional(Map<String, String> value, String name, int maximum) {
        String member = value.getOrDefault(name, "");
        if (member.length() > maximum || member.codePoints().anyMatch(c -> c == 0 || c == 0x7f))
            throw MattermostValues.invalid();
        return member;
    }
    private static byte[] utf8(char[] chars) {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] result = new byte[encoded.remaining()]; encoded.get(result);
        if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0); return result;
    }
    private static Duration remaining(IngressRequestContext window, long deadlineNanos) {
        return minimum(window.remaining(), Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime())));
    }
    private static boolean live(IngressRequestContext window, long deadlineNanos) {
        return window.live() && System.nanoTime() < deadlineNanos;
    }
    private static Duration minimum(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }
    private static CompletionStage<IngressResponse> response(int status) {
        return CompletableFuture.completedFuture(empty(status));
    }
    private static IngressResponse empty(int status) { return new IngressResponse(status, Map.of(), new byte[0]); }
    private static final class NeverCancelled implements ai.ravenroot.api.execution.CancellationSignal {
        @Override public boolean cancelled() { return false; }
        @Override public void onCancel(Runnable listener) { }
    }
}
