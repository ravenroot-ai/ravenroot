package ai.ravenroot.extensions.openapi.server;

import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.ingress.IngressRequest;
import ai.ravenroot.api.ingress.IngressResponse;
import ai.ravenroot.api.ingress.IngressRouteAuthority;
import ai.ravenroot.api.ingress.IngressRouteLease;
import ai.ravenroot.api.node.ManagedIngressSource;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;

/** One graph-generation source; the managed lease is the only externally reachable resource. */
final class OpenApiReceiveSource implements ManagedIngressSource {
    private static final Map<String, String> JSON_HEADER = Map.of("Content-Type", "application/json");
    private final OpenApiServerConfiguration configuration;
    private final OpenApiReceiveNodeBehavior.Settings settings;
    private final OpenApiAdmissionRegistry admission;
    private final Semaphore localAdmission;
    private State state = State.NEW;
    private InboundSourceContext context;
    private OpenApiIngressPlan plan;
    private OpenApiAdmissionRegistry.Handle profileAdmission;
    private IngressRouteLease lease;
    private long generation;

    OpenApiReceiveSource(OpenApiServerConfiguration configuration, OpenApiReceiveNodeBehavior.Settings settings,
                         OpenApiAdmissionRegistry admission) {
        this.configuration = configuration; this.settings = settings; this.admission = admission;
        this.localAdmission = new Semaphore(settings.maxConcurrency(), true);
    }

    @Override public synchronized CompletionStage<Void> start(InboundSourceContext context) {
        if (state == State.STARTED || state == State.ACTIVE) return CompletableFuture.completedFuture(null);
        if (state != State.NEW && state != State.STOPPED) return failed(OpenApiServerException.Code.LIFECYCLE_INVALID);
        try {
            OpenApiIngressPlan compiled = OpenApiIngressPlan.compile(settings.profile(), settings.operations(),
                    configuration.projection().allowedHeaders());
            String gateKey = context.identity().tenantId() + "\u0000" + settings.profile().name();
            OpenApiAdmissionRegistry.Handle gate = admission.open(gateKey, settings.profile().maxConcurrency());
            this.context = context; this.plan = compiled; this.profileAdmission = gate; generation++; this.state = State.STARTED;
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException failure) {
            clear();
            return CompletableFuture.failedFuture(sanitize(failure));
        }
    }

    @Override public synchronized CompletionStage<Void> activateManagedIngress(IngressRouteAuthority authority) {
        if (state == State.ACTIVE) return CompletableFuture.completedFuture(null);
        if (state != State.STARTED) return failed(OpenApiServerException.Code.LIFECYCLE_INVALID);
        try {
            String routeId = "openapi.receive." + shortHash(context.nodeId());
            long leaseGeneration = generation;
            IngressRouteLease acquired = authority.acquirePrefix(routeId, settings.profile().routeBase(),
                    plan.methods(), request -> handle(request, leaseGeneration));
            lease = acquired; state = State.ACTIVE;
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(
                    new OpenApiServerException(OpenApiServerException.Code.ROUTE_UNAVAILABLE));
        }
    }

    @Override public CompletionStage<Void> stop() {
        IngressRouteLease oldLease;
        OpenApiAdmissionRegistry.Handle oldAdmission;
        synchronized (this) {
            if (state == State.STOPPED || state == State.NEW) { state = State.STOPPED; return CompletableFuture.completedFuture(null); }
            state = State.STOPPING; oldLease = lease; lease = null; oldAdmission = profileAdmission; profileAdmission = null;
        }
        try { if (oldLease != null) oldLease.release(); }
        finally {
            if (oldAdmission != null) oldAdmission.close();
            synchronized (this) { clear(); state = State.STOPPED; }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override public CompletionStage<Void> rollback() { return stop(); }
    @Override public CompletionStage<Void> shutdown() { return stop(); }

    private CompletionStage<IngressResponse> handle(IngressRequest request, long expectedGeneration) {
        final InboundSourceContext activeContext;
        final OpenApiIngressPlan activePlan;
        final OpenApiAdmissionRegistry.Handle activeProfile;
        synchronized (this) {
            if (state != State.ACTIVE || generation != expectedGeneration) return response(503);
            activeContext = context; activePlan = plan; activeProfile = profileAdmission;
        }
        if (!activeContext.identity().tenantId().equals(request.principal().tenantId())
                || !settings.profile().allowedPrincipalTypes().contains(request.principal().principalType())) {
            return response(403);
        }
        if (request.body().length > settings.maxRequestBytes()) return response(413);
        if (!localAdmission.tryAcquire()) return response(429);
        OpenApiAdmissionRegistry.Permit profilePermit = activeProfile.tryAcquire();
        if (profilePermit == null) {
            localAdmission.release(); return response(active(expectedGeneration) ? 429 : 503);
        }
        long deadline = System.nanoTime() + java.time.Duration.ofMillis(settings.deadlineMs()).toNanos();
        try (profilePermit) {
            if (!active(expectedGeneration)) return response(503);
            String key = request.headers().get(settings.profile().idempotencyHeader());
            if (!validKey(key, settings.maxIdempotencyBytes())) return response(400);
            OpenApiIngressPlan.Match match = activePlan.match(request);
            if (System.nanoTime() >= deadline || !active(expectedGeneration)) return response(503);
            Object payload = payload(request, match, key);
            IngressReceipt receipt;
            try {
                receipt = activeContext.ingress().offerDurably(activeContext.identity(), settings.profile().target(),
                        payload, activeContext.nodeId(), match.operationId() + ":" + key);
            } catch (RuntimeException failure) {
                return response(503);
            }
            if (System.nanoTime() >= deadline || !active(expectedGeneration)) return response(503);
            return switch (receipt) {
                case IngressReceipt.DurablyCommitted ignored -> accepted(key, "committed");
                case IngressReceipt.Duplicate ignored -> accepted(key, "duplicate");
                case IngressReceipt.Refused refused -> response("buffer full".equals(refused.reason()) ? 429 : 503);
                case IngressReceipt.VolatileCustody ignored -> response(503);
                case IngressReceipt.Ambiguous ignored -> response(503);
            };
        } catch (OpenApiIngressPlan.RequestFailure failure) {
            return response(failure.status());
        } catch (RuntimeException failure) {
            return response(400);
        } finally {
            localAdmission.release();
        }
    }

    private synchronized boolean active(long expectedGeneration) {
        return state == State.ACTIVE && generation == expectedGeneration;
    }

    private Object payload(IngressRequest request, OpenApiIngressPlan.Match match, String key) {
        Map<String, Object> principal = new LinkedHashMap<>();
        principal.put("tenantId", request.principal().tenantId()); principal.put("subject", request.principal().subject());
        principal.put("issuer", request.principal().issuer()); principal.put("principalType", request.principal().principalType());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("version", "openapi.request.v1"); value.put("operationId", match.operationId());
        value.put("method", request.method()); value.put("path", match.path()); value.put("query", match.query());
        value.put("headers", match.headers()); value.put("body", match.body()); value.put("principal", Map.copyOf(principal));
        value.put("idempotencyKey", key);
        PayloadValue canonical = PayloadValue.fromJava(value, new PayloadLimits(settings.maxRequestBytes() + 64 * 1024,
                32, 512, 10_000, Math.max(settings.maxRequestBytes(), 256 * 1024), 128));
        return canonical.toJava();
    }

    private static boolean validKey(String key, int maximumBytes) {
        if (key == null || key.isBlank() || key.getBytes(StandardCharsets.UTF_8).length > maximumBytes) return false;
        return key.codePoints().noneMatch(value -> value < 0x20 || value == 0x7f);
    }

    private static CompletionStage<IngressResponse> accepted(String key, String receipt) {
        Map<String, PayloadValue> fields = new LinkedHashMap<>();
        fields.put("idempotencyKey", PayloadValue.of(key)); fields.put("receipt", PayloadValue.of(receipt));
        fields.put("version", PayloadValue.of("openapi.receive.receipt.v1"));
        byte[] body = PayloadJson.write(PayloadValue.map(fields)).getBytes(StandardCharsets.UTF_8);
        return CompletableFuture.completedFuture(new IngressResponse(202, JSON_HEADER, body));
    }

    private static CompletionStage<IngressResponse> response(int status) {
        return CompletableFuture.completedFuture(new IngressResponse(status, Map.of(), new byte[0]));
    }

    private synchronized void clear() { context = null; plan = null; lease = null; profileAdmission = null; }

    private static RuntimeException sanitize(RuntimeException failure) {
        return failure instanceof OpenApiServerException safe ? safe : OpenApiValues.invalid();
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 12);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static CompletionStage<Void> failed(OpenApiServerException.Code code) {
        return CompletableFuture.failedFuture(new OpenApiServerException(code));
    }

    private enum State { NEW, STARTED, ACTIVE, STOPPING, STOPPED }
}
