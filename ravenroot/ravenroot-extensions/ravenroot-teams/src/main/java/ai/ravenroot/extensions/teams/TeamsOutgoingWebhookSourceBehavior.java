package ai.ravenroot.extensions.teams;

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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Managed source for Teams Outgoing Webhook activities forwarded by a trusted tenant relay. */
public final class TeamsOutgoingWebhookSourceBehavior implements NodeBehavior, InboundSourceCapable {
    private static final Duration HARD_ACK_BUDGET = Duration.ofMillis(4_500);
    private static final byte[] ACCEPTED = TeamsValues.jsonBytes(Map.of("type", "message", "text", "Accepted."));
    private final TeamsRuntime runtime;

    TeamsOutgoingWebhookSourceBehavior(TeamsRuntime runtime) {
        this.runtime = java.util.Objects.requireNonNull(runtime);
    }

    @Override public Set<NodePackageCapability> requiredServices() {
        return Set.of(NodePackageCapability.CREDENTIAL_RESOLUTION);
    }
    @Override public NodeTypeDescriptor descriptor() { return TeamsBehaviorDescriptors.outgoingWebhook(); }
    @Override public NodeAction create(NodeConfiguration configuration) {
        return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
    }
    @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
        return createSource(configuration, context, NodePackageServices.unavailable());
    }
    @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context,
                                                NodePackageServices services) {
        TeamsProfile profile = runtime.profile(context.identity().tenantId(), TeamsBehaviorDescriptors.profile(configuration));
        return new Source(profile, services, runtime.store(), runtime);
    }

    private static final class Source implements ManagedIngressSource {
        private final TeamsProfile profile;
        private final NodePackageServices services;
        private final TeamsDeliveryStore store;
        private final TeamsRuntime runtime;
        private InboundSourceContext context;
        private IngressRouteLease lease;
        private long generation;
        private boolean started;

        Source(TeamsProfile profile, NodePackageServices services, TeamsDeliveryStore store, TeamsRuntime runtime) {
            this.profile = profile; this.services = services; this.store = store; this.runtime = runtime;
        }

        @Override public synchronized CompletionStage<Void> start(InboundSourceContext context) {
            if (started) return CompletableFuture.completedFuture(null);
            this.context = context; generation++; started = true;
            return CompletableFuture.completedFuture(null);
        }

        @Override public synchronized CompletionStage<Void> activateManagedIngress(IngressRouteAuthority authority) {
            if (!started) return CompletableFuture.failedFuture(new TeamsException(TeamsException.Code.CONFIGURATION));
            if (lease == null) {
                lease = authority.acquire("teams.outgoing-webhook."
                                + TeamsValues.sha256(context.nodeId().getBytes(StandardCharsets.UTF_8)).substring(0, 20),
                        profile.webhookRoute(), Set.of("POST"), new Handler(this, generation));
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override public synchronized CompletionStage<Void> stop() {
            started = false; generation++;
            IngressRouteLease old = lease; lease = null; context = null;
            if (old != null) old.release();
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Void> rollback() { return stop(); }
        @Override public CompletionStage<Void> shutdown() { return stop(); }
        synchronized InboundSourceContext active(long expected) {
            return started && generation == expected ? context : null;
        }
    }

    private static final class Handler implements IngressRouteHandler {
        private final Source source;
        private final long generation;

        Handler(Source source, long generation) { this.source = source; this.generation = generation; }

        @Override public CompletionStage<IngressResponse> handle(IngressRequest request) {
            return handle(request, new IngressRequestContext(Instant.now().plus(HARD_ACK_BUDGET), new NeverCancelled()));
        }

        @Override public CompletionStage<IngressResponse> handle(IngressRequest request, IngressRequestContext window) {
            long started = System.nanoTime();
            InboundSourceContext context = source.active(generation);
            if (context == null || !request.principal().tenantId().equals(context.identity().tenantId())) return response(503);
            if (!"POST".equals(request.method()) || request.body().length == 0
                    || request.body().length > source.profile.maxRequestBytes()) return response(400);
            String contentType = request.headers().getOrDefault("content-type", "").toLowerCase(java.util.Locale.ROOT);
            String signature = request.headers().getOrDefault("x-ravenroot-teams-signature", "");
            if (!contentType.startsWith("application/json") || !signature.matches("[A-Za-z0-9+/]{43}="))
                return response(400);
            Duration budget = minimum(minimum(window.remaining(), Duration.ofMillis(source.profile.ackTimeoutMs())),
                    HARD_ACK_BUDGET);
            if (budget.isZero() || budget.isNegative()) return response(503);
            return CompletableFuture.supplyAsync(() -> accept(request, window, context, signature,
                    started + budget.toNanos()));
        }

        private IngressResponse accept(IngressRequest request, IngressRequestContext window,
                                       InboundSourceContext context, String signature, long deadlineNanos) {
            OutboundCall<CredentialLease> resolving = null;
            boolean resolved = false;
            char[] secret = null;
            byte[] key = null;
            try {
                resolving = source.services.credentials().resolve(context,
                        source.profile.signingSecretReference(), remaining(window, deadlineNanos));
                OutboundCall<CredentialLease> active = resolving;
                window.cancellation().onCancel(active::cancel);
                try (CredentialLease credential = active.completion().toCompletableFuture()
                        .get(Math.max(1, remaining(window, deadlineNanos).toMillis()), TimeUnit.MILLISECONDS)) {
                    resolved = true;
                    if (!live(window, deadlineNanos)) return empty(503);
                    secret = credential.copy();
                    key = decodeKey(secret);
                    if (!verify(key, request.body(), signature)) return empty(401);
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt(); return empty(503);
            } catch (Exception failure) { return empty(503); }
            finally {
                if (!resolved && resolving != null) resolving.cancel();
                if (secret != null) Arrays.fill(secret, '\0');
                if (key != null) Arrays.fill(key, (byte) 0);
            }
            if (!live(window, deadlineNanos) || source.active(generation) == null) return empty(503);
            try {
                return deliver(request.body(), context, window, deadlineNanos);
            } catch (TeamsException failure) {
                return empty(switch (failure.code()) {
                    case FORBIDDEN -> 403;
                    case CAPACITY -> 429;
                    case DURABILITY_UNAVAILABLE, CANCELLED -> 503;
                    default -> 400;
                });
            } catch (RuntimeException failure) { return empty(503); }
        }

        private IngressResponse deliver(byte[] body, InboundSourceContext context,
                                        IngressRequestContext window, long deadlineNanos) {
            Map<String, Object> root = TeamsValues.json(body);
            if (!"message".equals(TeamsValues.string(root.get("type"), 32))) return empty(403);
            String activityId = TeamsProfile.providerId(TeamsValues.string(root.get("id"), 160), 160);
            Instant timestamp;
            try { timestamp = Instant.parse(TeamsValues.string(root.get("timestamp"), 64)); }
            catch (RuntimeException invalid) { throw TeamsValues.invalid(); }
            if (!timestampAllowed(timestamp, source.runtime.clock.instant(), source.profile.signatureMaxAgeSeconds()))
                return empty(401);
            Map<String, Object> channelData = TeamsValues.object(root.get("channelData"));
            String tenantId = nestedId(channelData, "tenant");
            String teamId = nestedId(channelData, "team");
            String channelId = nestedId(channelData, "channel");
            if (!source.profile.microsoftTenantId().equalsIgnoreCase(tenantId)
                    || !source.profile.teamId().equals(teamId) || !source.profile.permitsChannel(channelId))
                return empty(403);
            String senderId = nestedId(root, "from");
            String recipientId = nestedId(root, "recipient");
            String conversationId = nestedId(root, "conversation");
            String text = TeamsValues.string(root.get("text"), source.profile.maxTextChars());
            if (text.codePointCount(0, text.length()) > source.profile.maxTextChars()) throw TeamsValues.invalid();

            source.store.bind(context.identity().tenantId(), source.profile.name(), "outgoing-webhook",
                    activityId, TeamsValues.sha256(body), deadlineNanos, window.cancellation());
            if (!live(window, deadlineNanos) || source.active(generation) == null) return empty(503);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("version", "teams.outgoing-message.v1");
            payload.put("activityId", activityId); payload.put("timestamp", timestamp.toString());
            payload.put("microsoftTenantId", tenantId); payload.put("teamId", teamId);
            payload.put("channelId", channelId); payload.put("senderId", senderId);
            payload.put("recipientId", recipientId); payload.put("conversationId", conversationId);
            payload.put("text", text);
            IngressReceipt receipt = context.ingress().offerDurably(context.identity(), IngressTarget.start(),
                    Map.copyOf(payload), context.nodeId(), source.profile.name() + ":outgoing-webhook:" + activityId);
            if (!live(window, deadlineNanos)) return empty(503);
            return switch (receipt) {
                case IngressReceipt.DurablyCommitted ignored -> accepted(source.profile.maxResponseBytes());
                case IngressReceipt.Duplicate ignored -> accepted(source.profile.maxResponseBytes());
                case IngressReceipt.Refused refused -> empty("buffer full".equals(refused.reason()) ? 429 : 503);
                case IngressReceipt.VolatileCustody ignored -> empty(503);
                case IngressReceipt.Ambiguous ignored -> empty(503);
            };
        }
    }

    private static String nestedId(Map<String, Object> parent, String field) {
        return TeamsProfile.providerId(TeamsValues.string(TeamsValues.object(parent.get(field)).get("id"), 160), 160);
    }

    private static boolean timestampAllowed(Instant supplied, Instant now, int maximumAgeSeconds) {
        Instant earliest = now.minusSeconds(maximumAgeSeconds);
        Instant latest = now.plusSeconds(maximumAgeSeconds);
        return !supplied.isBefore(earliest) && !supplied.isAfter(latest);
    }

    private static byte[] decodeKey(char[] chars) {
        byte[] encoded = new byte[chars.length];
        try {
            for (int index = 0; index < chars.length; index++) {
                if (chars[index] > 0x7f) throw new IllegalArgumentException();
                encoded[index] = (byte) chars[index];
            }
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException invalid) {
            throw new TeamsException(TeamsException.Code.AUTHENTICATION_FAILED);
        } finally { Arrays.fill(encoded, (byte) 0); }
    }

    private static boolean verify(byte[] key, byte[] body, String supplied) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(body), Base64.getDecoder().decode(supplied));
        } catch (Exception failure) { return false; }
    }

    private static Duration remaining(IngressRequestContext window, long deadlineNanos) {
        return minimum(window.remaining(), Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime())));
    }
    private static boolean live(IngressRequestContext window, long deadlineNanos) {
        return window.live() && deadlineNanos > System.nanoTime();
    }
    private static Duration minimum(Duration left, Duration right) { return left.compareTo(right) <= 0 ? left : right; }
    private static CompletionStage<IngressResponse> response(int status) {
        return CompletableFuture.completedFuture(empty(status));
    }
    private static IngressResponse accepted(long maximumBytes) {
        if (ACCEPTED.length > maximumBytes) return empty(503);
        return new IngressResponse(200, Map.of("Content-Type", "application/json"), ACCEPTED);
    }
    private static IngressResponse empty(int status) { return new IngressResponse(status, Map.of(), new byte[0]); }

    private static final class NeverCancelled implements ai.ravenroot.api.execution.CancellationSignal {
        @Override public boolean cancelled() { return false; }
        @Override public void onCancel(Runnable listener) { }
    }
}
