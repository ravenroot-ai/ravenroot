package ai.ravenroot.extensions.github;

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
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Relay-compatible GitHub webhook source; HMAC verification precedes JSON parsing and durable offer. */
public final class GithubEventsSourceBehavior implements NodeBehavior, InboundSourceCapable {
    public static final String BEHAVIOR = "github-events-source";
    private final GithubRuntime runtime;
    GithubEventsSourceBehavior(GithubRuntime runtime) { this.runtime = runtime; }
    @Override public Set<NodePackageCapability> requiredServices() { return Set.of(NodePackageCapability.CREDENTIAL_RESOLUTION); }
    @Override public NodeTypeDescriptor descriptor() { return GithubBehaviorDescriptors.descriptor(BEHAVIOR,
            "Receive GitHub event", "Verifies and durably accepts a relayed GitHub webhook.", true, false, false); }
    @Override public NodeAction create(NodeConfiguration configuration) {
        return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
    }
    @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
        return createSource(configuration, context, NodePackageServices.unavailable());
    }
    @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context,
                                                NodePackageServices services) {
        GithubProfile profile = runtime.requireProfile(context.identity().tenantId(), GithubBehaviorDescriptors.profile(configuration));
        return new Source(profile, services);
    }

    private static final class Source implements ManagedIngressSource {
        private final GithubProfile profile; private final NodePackageServices services;
        private InboundSourceContext context; private IngressRouteLease lease; private long generation; private boolean started;
        Source(GithubProfile profile, NodePackageServices services) { this.profile = profile; this.services = services; }

        @Override public synchronized CompletionStage<Void> start(InboundSourceContext context) {
            if (started) return CompletableFuture.completedFuture(null);
            this.context = context; generation++; started = true; return CompletableFuture.completedFuture(null);
        }
        @Override public synchronized CompletionStage<Void> activateManagedIngress(IngressRouteAuthority authority) {
            if (!started) return CompletableFuture.failedFuture(new GithubException(GithubException.Code.CONFIGURATION));
            if (lease != null) return CompletableFuture.completedFuture(null);
            long expected = generation;
            lease = authority.acquire("github.events." + GithubValues.sha256(context.nodeId()).substring(0, 20),
                    profile.route(), Set.of("POST"), new Handler(this, expected));
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
        private final Source source; private final long generation;
        Handler(Source source, long generation) { this.source = source; this.generation = generation; }
        @Override public CompletionStage<IngressResponse> handle(IngressRequest request) {
            return handle(request, new IngressRequestContext(java.time.Instant.now().plusSeconds(30), new NeverCancelled()));
        }
        @Override public CompletionStage<IngressResponse> handle(IngressRequest request, IngressRequestContext window) {
            InboundSourceContext context = source.active(generation);
            if (context == null || !request.principal().tenantId().equals(context.identity().tenantId())) return response(503);
            if (!"POST".equals(request.method()) || request.body().length > source.profile.maxRequestBytes()) return response(400);
            String signature = request.headers().getOrDefault("x-hub-signature-256", "");
            String delivery = request.headers().getOrDefault("x-github-delivery", "");
            String event = request.headers().getOrDefault("x-github-event", "");
            if (!signature.matches("sha256=[0-9a-f]{64}") || !delivery.matches("[A-Za-z0-9-]{1,128}")
                    || !event.matches("[A-Za-z0-9_]{1,64}")) return response(400);
            if (!source.profile.webhookEvents().containsKey(event)) return response(403);
            return CompletableFuture.supplyAsync(() -> accept(request, window, context, signature, delivery, event));
        }

        private IngressResponse accept(IngressRequest request, IngressRequestContext window,
                                       InboundSourceContext context, String signature, String delivery, String event) {
            OutboundCall<CredentialLease> resolving = source.services.credentials().resolve(context,
                    source.profile.webhookSecretReference(), bounded(window.remaining(), source.profile.timeoutMs()));
            window.cancellation().onCancel(resolving::cancel);
            char[] secret = null; byte[] key = null;
            try (CredentialLease lease = resolving.completion().toCompletableFuture()
                    .get(Math.max(1, bounded(window.remaining(), source.profile.timeoutMs()).toMillis()), TimeUnit.MILLISECONDS)) {
                if (!window.live()) return empty(503);
                secret = lease.copy(); key = utf8(secret);
                byte[] expected = hmac(key, request.body());
                byte[] supplied = java.util.HexFormat.of().parseHex(signature.substring(7));
                if (!MessageDigest.isEqual(expected, supplied)) return empty(401);
            } catch (Exception failure) { return empty(window.live() ? 503 : 503); }
            finally { if (secret != null) Arrays.fill(secret, '\0'); if (key != null) Arrays.fill(key, (byte) 0); }
            if (!window.live() || source.active(generation) == null) return empty(503);
            final Map<String, Object> body;
            try { body = GithubValues.json(request.body()); }
            catch (RuntimeException invalid) { return empty(400); }
            if (!matchesAuthority(body, source.profile)) return empty(403);
            String action;
            try { action = body.get("action") == null ? "" : GithubValues.string(body.get("action"), 64); }
            catch (RuntimeException invalid) { return empty(400); }
            Set<String> actions = source.profile.webhookEvents().get(event);
            if (!actions.isEmpty() && !actions.contains(action)) return empty(403);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("version", "github.event.v1"); payload.put("event", event); payload.put("action", action);
            payload.put("deliveryId", delivery); payload.put("repositoryId", source.profile.repositoryId());
            payload.put("installationId", source.profile.installationId()); payload.put("body", body);
            IngressReceipt receipt;
            try { receipt = context.ingress().offerDurably(context.identity(), IngressTarget.start(), Map.copyOf(payload),
                    context.nodeId(), source.profile.name() + ":" + delivery); }
            catch (RuntimeException failure) { return empty(503); }
            return switch (receipt) {
                case IngressReceipt.DurablyCommitted ignored -> receipt(delivery, "committed");
                case IngressReceipt.Duplicate ignored -> receipt(delivery, "duplicate");
                case IngressReceipt.Refused refused -> empty("buffer full".equals(refused.reason()) ? 429 : 503);
                case IngressReceipt.VolatileCustody ignored -> empty(503);
                case IngressReceipt.Ambiguous ignored -> empty(503);
            };
        }

        private static boolean matchesAuthority(Map<String, Object> body, GithubProfile profile) {
            try {
                Map<String, Object> repository = GithubValues.object(body.get("repository"));
                Map<String, Object> installation = GithubValues.object(body.get("installation"));
                return GithubValues.number(repository.get("id"), 1, Long.MAX_VALUE) == profile.repositoryId()
                        && GithubValues.number(installation.get("id"), 1, Long.MAX_VALUE) == profile.installationId();
            } catch (RuntimeException invalid) { return false; }
        }

        private static byte[] utf8(char[] chars) {
            ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
            byte[] result = new byte[encoded.remaining()]; encoded.get(result); return result;
        }
        private static byte[] hmac(byte[] key, byte[] body) {
            try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(body); }
            catch (Exception impossible) { throw new IllegalStateException(impossible); }
        }
        private static Duration bounded(Duration remaining, int ceilingMs) {
            Duration ceiling = Duration.ofMillis(ceilingMs); return remaining.compareTo(ceiling) < 0 ? remaining : ceiling;
        }
        private static IngressResponse receipt(String delivery, String disposition) {
            return new IngressResponse(202, Map.of("Content-Type", "application/json"), GithubValues.jsonBytes(Map.of(
                    "version", "github.event.receipt.v1", "deliveryId", delivery, "receipt", disposition)));
        }
        private static CompletionStage<IngressResponse> response(int status) { return CompletableFuture.completedFuture(empty(status)); }
        private static IngressResponse empty(int status) { return new IngressResponse(status, Map.of(), new byte[0]); }
    }

    private static final class NeverCancelled implements ai.ravenroot.api.execution.CancellationSignal {
        @Override public boolean cancelled() { return false; }
        @Override public void onCancel(Runnable listener) { }
    }
}
