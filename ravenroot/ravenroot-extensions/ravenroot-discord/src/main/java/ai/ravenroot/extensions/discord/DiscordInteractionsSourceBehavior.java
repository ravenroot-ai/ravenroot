package ai.ravenroot.extensions.discord;

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
import ai.ravenroot.api.node.service.NodePackageServices;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Authenticated-relay endpoint for Discord PING and guild application-command interactions. */
public final class DiscordInteractionsSourceBehavior implements NodeBehavior, InboundSourceCapable {
    private static final byte[] ED25519_PREFIX = java.util.HexFormat.of().parseHex("302a300506032b6570032100");
    private final DiscordRuntime runtime;
    DiscordInteractionsSourceBehavior(DiscordRuntime runtime) { this.runtime = runtime; }

    @Override public NodeTypeDescriptor descriptor() { return DiscordBehaviorDescriptors.interactions(); }
    @Override public NodeAction create(NodeConfiguration configuration) {
        return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
    }
    @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
        return createSource(configuration, context, NodePackageServices.unavailable());
    }
    @Override public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context,
                                                NodePackageServices services) {
        return new Source(runtime.profile(context.identity().tenantId(), DiscordBehaviorDescriptors.profile(configuration)), runtime);
    }

    private static final class Source implements ManagedIngressSource {
        private final DiscordProfile profile; private final DiscordRuntime runtime;
        private InboundSourceContext context; private IngressRouteLease lease; private long generation; private boolean started;
        Source(DiscordProfile profile, DiscordRuntime runtime) { this.profile = profile; this.runtime = runtime; }
        @Override public synchronized CompletionStage<Void> start(InboundSourceContext context) {
            if (started) return CompletableFuture.completedFuture(null);
            this.context = context; generation++; started = true; return CompletableFuture.completedFuture(null);
        }
        @Override public synchronized CompletionStage<Void> activateManagedIngress(IngressRouteAuthority authority) {
            if (!started) return CompletableFuture.failedFuture(new DiscordException(DiscordException.Code.CONFIGURATION));
            if (lease == null) lease = authority.acquire("discord.interactions."
                    + DiscordValues.sha256(context.nodeId().getBytes(StandardCharsets.UTF_8)).substring(0, 20),
                    profile.route(), Set.of("POST"), new Handler(this, generation));
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
            return handle(request, new IngressRequestContext(Instant.now().plusMillis(2_800), new NeverCancelled()));
        }
        @Override public CompletionStage<IngressResponse> handle(IngressRequest request, IngressRequestContext window) {
            InboundSourceContext context = source.active(generation);
            if (context == null || !request.principal().tenantId().equals(context.identity().tenantId())) return response(503);
            if (!"POST".equals(request.method()) || request.body().length > source.profile.maxRequestBytes()) return response(400);
            String signature = request.headers().getOrDefault("x-signature-ed25519", "");
            String timestamp = request.headers().getOrDefault("x-signature-timestamp", "");
            if (!signature.matches("[0-9a-fA-F]{128}") || !timestamp.matches("[0-9]{1,20}")) return response(400);
            byte[] body = request.body();
            if (!verify(source.profile, signature, timestamp, body, source.runtime.clock.instant())) return response(401);
            if (!window.live()) return response(503);
            final Map<String, Object> root;
            try { root = DiscordValues.json(body); }
            catch (RuntimeException invalid) { return response(400); }
            String application;
            long type;
            try {
                application = DiscordProfile.snowflake(DiscordValues.string(root.get("application_id"), 20));
                type = DiscordValues.number(root.get("type"), 1, 12);
            } catch (RuntimeException invalid) { return response(400); }
            if (!source.profile.applicationId().equals(application)) return response(403);
            if (type == 1) return json(200, Map.of("type", 1L));
            if (type != 2) return response(403);
            return CompletableFuture.supplyAsync(() -> accept(body, root, context, window));
        }

        private IngressResponse accept(byte[] body, Map<String, Object> root,
                                       InboundSourceContext context, IngressRequestContext window) {
            try {
                String interaction = DiscordProfile.snowflake(DiscordValues.string(root.get("id"), 20));
                String guild = DiscordProfile.snowflake(DiscordValues.string(root.get("guild_id"), 20));
                String channel = DiscordProfile.snowflake(DiscordValues.string(root.get("channel_id"), 20));
                Map<String, Object> data = DiscordValues.object(root.get("data"));
                String command = DiscordValues.string(data.get("name"), 32);
                if (!source.profile.permits(guild, channel, command)) return empty(403);
                source.runtime.store().bind(context.identity().tenantId(), source.profile.name(),
                        source.profile.applicationId(), interaction, DiscordValues.sha256(body));
                if (!window.live() || source.active(generation) == null) return empty(503);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("version", "discord.interaction.v1"); payload.put("interactionId", interaction);
                payload.put("applicationId", source.profile.applicationId()); payload.put("guildId", guild);
                payload.put("channelId", channel); payload.put("command", command);
                payload.put("data", withoutToken(data));
                IngressReceipt receipt = context.ingress().offerDurably(context.identity(), IngressTarget.start(),
                        Map.copyOf(payload), context.nodeId(), source.profile.name() + ":" + interaction);
                if (!window.live()) return empty(503);
                return switch (receipt) {
                    case IngressReceipt.DurablyCommitted ignored -> acknowledgement();
                    case IngressReceipt.Duplicate ignored -> acknowledgement();
                    case IngressReceipt.Refused refused -> empty("buffer full".equals(refused.reason()) ? 429 : 503);
                    case IngressReceipt.VolatileCustody ignored -> empty(503);
                    case IngressReceipt.Ambiguous ignored -> empty(503);
                };
            } catch (DiscordException failure) {
                return empty(switch (failure.code()) {
                    case FORBIDDEN -> 409; case CAPACITY -> 429; case DURABILITY_UNAVAILABLE -> 503; default -> 400;
                });
            } catch (RuntimeException failure) { return empty(503); }
        }

        private static Map<String, Object> withoutToken(Map<String, Object> data) {
            Map<String, Object> safe = new LinkedHashMap<>(data); safe.remove("token"); return Map.copyOf(safe);
        }
        private static IngressResponse acknowledgement() {
            return jsonNow(200, Map.of("type", 4L, "data", Map.of(
                    "content", "Accepted for processing.", "flags", 64L)));
        }
    }

    private static boolean verify(DiscordProfile profile, String supplied, String timestamp, byte[] body, Instant now) {
        try {
            long seconds = Long.parseLong(timestamp);
            long age = now.getEpochSecond() - seconds;
            if (age > profile.signatureMaxAgeSeconds() || age < -profile.futureSkewSeconds()) return false;
            byte[] encoded = new byte[ED25519_PREFIX.length + profile.publicKey().length];
            System.arraycopy(ED25519_PREFIX, 0, encoded, 0, ED25519_PREFIX.length);
            System.arraycopy(profile.publicKey(), 0, encoded, ED25519_PREFIX.length, profile.publicKey().length);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded)));
            verifier.update(timestamp.getBytes(StandardCharsets.US_ASCII)); verifier.update(body);
            return verifier.verify(java.util.HexFormat.of().parseHex(supplied));
        } catch (RuntimeException | java.security.GeneralSecurityException failure) { return false; }
    }
    private static CompletionStage<IngressResponse> response(int status) { return CompletableFuture.completedFuture(empty(status)); }
    private static CompletionStage<IngressResponse> json(int status, Map<String, ?> value) { return CompletableFuture.completedFuture(jsonNow(status, value)); }
    private static IngressResponse jsonNow(int status, Map<String, ?> value) {
        return new IngressResponse(status, Map.of("Content-Type", "application/json"), DiscordValues.jsonBytes(value));
    }
    private static IngressResponse empty(int status) { return new IngressResponse(status, Map.of(), new byte[0]); }
    private static final class NeverCancelled implements ai.ravenroot.api.execution.CancellationSignal {
        @Override public boolean cancelled() { return false; }
        @Override public void onCancel(Runnable listener) { }
    }
}
