package ai.ravenroot.extensions.openapi.server;

import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.RequestReplyAdmission;
import ai.ravenroot.api.deployment.RequestReplyExchange;
import ai.ravenroot.api.deployment.RequestReplyRefusal;
import ai.ravenroot.api.deployment.RequestReplyTerminalState;
import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.ingress.IngressRequest;
import ai.ravenroot.api.ingress.IngressRequestContext;
import ai.ravenroot.api.ingress.IngressResponse;
import ai.ravenroot.api.ingress.IngressRouteAuthority;
import ai.ravenroot.api.ingress.IngressRouteHandler;
import ai.ravenroot.api.ingress.IngressRouteLease;
import ai.ravenroot.api.node.ManagedIngressSource;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** One graph-generation source; the managed lease is the only externally reachable resource. */
final class OpenApiRequestReplySource implements ManagedIngressSource {
    private static final CancellationSignal NEVER_CANCELLED = new CancellationSignal() {
        @Override public boolean cancelled() { return false; }
        @Override public void onCancel(Runnable listener) { }
    };
    private final OpenApiServerConfiguration configuration;
    private final OpenApiRequestReplyNodeBehavior.Settings settings;
    private final OpenApiAdmissionRegistry admission;
    private final OpenApiResponseRegistry responses;
    private final Semaphore localAdmission;
    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    private State state = State.NEW;
    private InboundSourceContext context;
    private OpenApiIngressPlan plan;
    private OpenApiAdmissionRegistry.Handle profileAdmission;
    private IngressRouteLease lease;
    private long generation;

    OpenApiRequestReplySource(OpenApiServerConfiguration configuration, OpenApiRequestReplyNodeBehavior.Settings settings,
                         OpenApiAdmissionRegistry admission, OpenApiResponseRegistry responses) {
        this.configuration = configuration; this.settings = settings; this.admission = admission;
        this.responses = responses;
        this.localAdmission = new Semaphore(settings.maxConcurrency(), true);
    }

    @Override public synchronized CompletionStage<Void> start(InboundSourceContext context) {
        if (state == State.STARTED || state == State.ACTIVE) return CompletableFuture.completedFuture(null);
        if (state != State.NEW && state != State.STOPPED) return failed(OpenApiServerException.Code.LIFECYCLE_INVALID);
        try {
            OpenApiIngressPlan compiled = OpenApiIngressPlan.compileRequestReply(settings.profile(), settings.operations(),
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
            String routeId = "openapi.request-reply." + shortHash(context.nodeId());
            long leaseGeneration = generation;
            IngressRouteLease acquired = authority.acquirePrefix(routeId, settings.profile().routeBase(),
                    plan.methods(), new IngressRouteHandler() {
                        @Override public CompletionStage<IngressResponse> handle(IngressRequest request) {
                            return OpenApiRequestReplySource.this.handle(request, leaseGeneration,
                                    new IngressRequestContext(Instant.now().plusMillis(settings.deadlineMs()),
                                            NEVER_CANCELLED));
                        }

                        @Override public CompletionStage<IngressResponse> handle(
                                IngressRequest request, IngressRequestContext requestContext) {
                            return OpenApiRequestReplySource.this.handle(request, leaseGeneration, requestContext);
                        }
                    });
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
        java.util.List<Pending> oldPending;
        synchronized (this) {
            if (state == State.STOPPED || state == State.NEW) { state = State.STOPPED; return CompletableFuture.completedFuture(null); }
            state = State.STOPPING; oldLease = lease; lease = null; oldAdmission = profileAdmission; profileAdmission = null;
            oldPending = java.util.List.copyOf(pending.values());
            pending.clear();
        }
        RuntimeException cleanup = null;
        try { if (oldLease != null) oldLease.release(); }
        catch (RuntimeException failure) { cleanup = failure; }
        for (Pending value : oldPending) {
            try { value.cancelForStop(); }
            catch (RuntimeException failure) { cleanup = append(cleanup, failure); }
        }
        try { if (oldAdmission != null) oldAdmission.close(); }
        catch (RuntimeException failure) { cleanup = append(cleanup, failure); }
        finally { synchronized (this) { clear(); state = State.STOPPED; } }
        return cleanup == null ? CompletableFuture.completedFuture(null) : CompletableFuture.failedFuture(cleanup);
    }

    @Override public CompletionStage<Void> rollback() { return stop(); }
    @Override public CompletionStage<Void> shutdown() { return stop(); }

    private CompletionStage<IngressResponse> handle(IngressRequest request, long expectedGeneration,
                                                     IngressRequestContext requestContext) {
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
        RequestPermit requestPermit = new RequestPermit(profilePermit);
        Instant deadline = earlier(requestContext.deadline(), Instant.now().plusMillis(settings.deadlineMs()));
        if (!active(expectedGeneration)) {
            requestPermit.close();
            return response(503);
        }
        try {
            String key = request.headers().get(settings.profile().idempotencyHeader());
            if (!validKey(key, settings.maxIdempotencyBytes())) {
                requestPermit.close();
                return response(400);
            }
            OpenApiIngressPlan.Match match = activePlan.match(request);
            if (!deadline.isAfter(Instant.now()) || !active(expectedGeneration)
                    || requestContext.cancellation().cancelled()) {
                requestPermit.close();
                return response(504);
            }
            return handleSync(request, requestContext, expectedGeneration, activeContext,
                    match, key, deadline, requestPermit);
        } catch (OpenApiIngressPlan.RequestFailure failure) {
            requestPermit.close();
            return response(failure.status());
        } catch (RuntimeException failure) {
            requestPermit.close();
            return response(400);
        }
    }

    private synchronized boolean active(long expectedGeneration) {
        return state == State.ACTIVE && generation == expectedGeneration;
    }

    int pendingCount() {
        return pending.size();
    }

    private CompletionStage<IngressResponse> handleSync(
            IngressRequest request, IngressRequestContext requestContext, long expectedGeneration,
            InboundSourceContext activeContext, OpenApiIngressPlan.Match match, String key,
            Instant deadline, RequestPermit requestPermit) {
        var projectedCorrelation = new java.util.concurrent.atomic.AtomicReference<UUID>();
        RequestReplyAdmission admission;
        try {
            admission = activeContext.requestReply().requestProjected(settings.profile().target(), exchange -> {
                projectedCorrelation.set(exchange.correlationId());
                if (!responses.open(exchange.correlationId(), activeContext.identity().tenantId(),
                        exchange.processInstanceId(), exchange.traversalId(), activeContext.nodeId(),
                        expectedGeneration)) {
                    throw OpenApiValues.invalid();
                }
                return synchronousPayload(request, match, key, exchange);
            }, deadline);
        } catch (RuntimeException failure) {
            UUID allocated = projectedCorrelation.get();
            if (allocated != null) responses.close(allocated);
            requestPermit.close();
            return response(500);
        }
        if (admission instanceof RequestReplyAdmission.Refused refused) {
            UUID allocated = projectedCorrelation.get();
            if (allocated != null) responses.close(allocated);
            requestPermit.close();
            return response(refusalStatus(refused.reason()));
        }
        RequestReplyExchange exchange = ((RequestReplyAdmission.Accepted) admission).exchange();
        if (!exchange.correlationId().equals(projectedCorrelation.get())) {
            responses.close(exchange.correlationId());
            exchange.cancel();
            requestPermit.close();
            return response(500);
        }
        Pending registered = new Pending(exchange, requestPermit);
        synchronized (this) {
            if (state != State.ACTIVE || generation != expectedGeneration
                    || pending.putIfAbsent(exchange.correlationId(), registered) != null) {
                responses.close(exchange.correlationId());
                exchange.cancel();
                registered.release();
                return response(503);
            }
        }
        try {
            requestContext.cancellation().onCancel(exchange::cancel);
        } catch (RuntimeException invalidSignal) {
            exchange.cancel();
        }
        return exchange.completion().handle((outcome, failure) -> {
            pending.remove(exchange.correlationId(), registered);
            registered.release();
            try {
                if (failure != null || outcome == null
                        || !exchange.processInstanceId().equals(outcome.processInstanceId())
                        || !exchange.traversalId().equals(outcome.traversalId())) {
                    return empty(500);
                }
                if (outcome.state() == RequestReplyTerminalState.TIMED_OUT) return empty(504);
                if (outcome.state() == RequestReplyTerminalState.CANCELLED) return empty(503);
                if (outcome.state() != RequestReplyTerminalState.COMPLETED
                        || !responses.consume(exchange.correlationId(), activeContext.identity().tenantId(),
                                exchange.processInstanceId(), exchange.traversalId(), activeContext.nodeId(),
                                expectedGeneration, outcome.payload())) {
                    return empty(500);
                }
                return match.projectResponse(outcome.payload(), exchange.correlationId(),
                        settings.maxResponseBytes());
            } catch (RuntimeException invalidResponse) {
                return empty(500);
            } finally {
                responses.close(exchange.correlationId());
            }
        });
    }

    private PayloadValue synchronousPayload(IngressRequest request, OpenApiIngressPlan.Match match, String key,
                                             ai.ravenroot.api.deployment.RequestReplyContext exchange) {
        Map<String, Object> value = new LinkedHashMap<>(
                OpenApiValues.object(payload(request, match, key, "openapi.request.v2").toJava(), "request"));
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("correlationId", exchange.correlationId().toString());
        runtime.put("processInstanceId", exchange.processInstanceId().toString());
        runtime.put("traversalId", exchange.traversalId().toString());
        runtime.put("deadline", exchange.deadline().toString());
        runtime.put("deploymentId", exchange.binding().deploymentId());
        runtime.put("generation", exchange.binding().generation());
        runtime.put("sourceNodeId", exchange.binding().nodeId().orElse(null));
        value.put("requestReply", runtime);
        return PayloadValue.fromJava(value, requestPayloadLimits());
    }

    private PayloadValue payload(IngressRequest request, OpenApiIngressPlan.Match match, String key,
                                 String version) {
        Map<String, Object> principal = new LinkedHashMap<>();
        principal.put("tenantId", request.principal().tenantId()); principal.put("subject", request.principal().subject());
        principal.put("issuer", request.principal().issuer()); principal.put("principalType", request.principal().principalType());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("version", version); value.put("operationId", match.operationId());
        value.put("method", request.method()); value.put("path", match.path()); value.put("query", match.query());
        value.put("headers", match.headers()); value.put("body", match.body()); value.put("principal", Map.copyOf(principal));
        value.put("idempotencyKey", key);
        return PayloadValue.fromJava(value, requestPayloadLimits());
    }

    private PayloadLimits requestPayloadLimits() {
        int encoded = Math.min(PayloadLimits.DEFAULTS.maxEncodedBytes(),
                Math.addExact(settings.maxRequestBytes(), 64 * 1024));
        return new PayloadLimits(encoded, 32, 512, 10_000,
                Math.min(PayloadLimits.DEFAULTS.maxTextLength(), Math.max(settings.maxRequestBytes(), 1)), 128);
    }

    private static int refusalStatus(RequestReplyRefusal refusal) {
        return switch (refusal) {
            case CAPACITY_EXHAUSTED -> 429;
            case INVALID_DEADLINE -> 504;
            default -> 503;
        };
    }

    private static Instant earlier(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static boolean validKey(String key, int maximumBytes) {
        if (key == null || key.isBlank() || key.getBytes(StandardCharsets.UTF_8).length > maximumBytes) return false;
        return key.codePoints().noneMatch(value -> value < 0x20 || value == 0x7f);
    }

    private static CompletionStage<IngressResponse> response(int status) {
        return CompletableFuture.completedFuture(empty(status));
    }

    private static IngressResponse empty(int status) { return new IngressResponse(status, Map.of(), new byte[0]); }

    private synchronized void clear() { context = null; plan = null; lease = null; profileAdmission = null; }

    private final class RequestPermit implements AutoCloseable {
        private final OpenApiAdmissionRegistry.Permit profilePermit;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RequestPermit(OpenApiAdmissionRegistry.Permit profilePermit) {
            this.profilePermit = profilePermit;
        }

        @Override public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    profilePermit.close();
                } finally {
                    localAdmission.release();
                }
            }
        }
    }

    private final class Pending {
        private final RequestReplyExchange exchange;
        private final RequestPermit permit;

        private Pending(RequestReplyExchange exchange, RequestPermit permit) {
            this.exchange = exchange;
            this.permit = permit;
        }

        private void release() { permit.close(); }

        private void cancelForStop() {
            boolean cancelled = false;
            try {
                cancelled = exchange.cancel();
            } catch (RuntimeException failure) {
                responses.close(exchange.correlationId());
                throw failure;
            }
            finally { release(); }
            if (cancelled) responses.close(exchange.correlationId());
        }
    }

    private static RuntimeException append(RuntimeException current, RuntimeException next) {
        if (current == null) return next;
        current.addSuppressed(next);
        return current;
    }

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
