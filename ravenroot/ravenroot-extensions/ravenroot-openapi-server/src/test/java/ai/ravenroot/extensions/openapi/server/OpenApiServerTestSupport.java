package ai.ravenroot.extensions.openapi.server;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressDisposition;
import ai.ravenroot.api.deployment.IngressOverflowPolicy;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.deployment.RequestReplyAdmission;
import ai.ravenroot.api.deployment.RequestReplyBinding;
import ai.ravenroot.api.deployment.RequestReplyContext;
import ai.ravenroot.api.deployment.RequestReplyExchange;
import ai.ravenroot.api.deployment.RequestReplyIngress;
import ai.ravenroot.api.deployment.RequestReplyOutcome;
import ai.ravenroot.api.deployment.RequestReplyProjection;
import ai.ravenroot.api.deployment.RequestReplyTerminalState;
import ai.ravenroot.api.deployment.TrustedIngress;
import ai.ravenroot.api.application.ExecutionOutcome;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.ingress.IngressAuthorityDeclaration;
import ai.ravenroot.api.ingress.IngressPrincipal;
import ai.ravenroot.api.ingress.IngressRequest;
import ai.ravenroot.api.ingress.IngressRequestContext;
import ai.ravenroot.api.ingress.IngressRequestProjectionPolicy;
import ai.ravenroot.api.ingress.IngressResponse;
import ai.ravenroot.api.ingress.IngressRouteAuthority;
import ai.ravenroot.api.ingress.IngressRouteHandler;
import ai.ravenroot.api.ingress.IngressRouteLease;
import ai.ravenroot.api.ingress.IngressRouteOwner;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.payload.PayloadValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class OpenApiServerTestSupport {
    static final byte[] SPEC = fixture();

    static OpenApiServerProfile profile() {
        return new OpenApiServerProfile("orders", SPEC, sha256(SPEC), "/api",
                Set.of("createOrder", "specialOrder"), Set.of("USER"), "idempotency-key", null,
                4096, 128, 1_000, 2);
    }

    static OpenApiServerConfiguration configuration() {
        return new OpenApiServerConfiguration(new IngressAuthorityDeclaration(
                OpenApiServerConfiguration.PACKAGE_ID, "main", "/managed/openapi", Set.of("graph:execute"),
                8, 8, 8192, 1024, Duration.ofSeconds(2)),
                new IngressRequestProjectionPolicy(OpenApiServerConfiguration.PACKAGE_ID,
                        Set.of("content-type", "idempotency-key", "x-trace"), "idempotency-key",
                        1024, 32, 2048, 8, 2048, 256), Map.of("orders", profile()));
    }

    static IngressRequest request(String path, Map<String, java.util.List<String>> query,
                                  Map<String, String> headers, byte[] body) {
        var projected = new java.util.LinkedHashMap<>(headers);
        if (body.length != 0) projected.putIfAbsent("content-type", "application/json");
        return new IngressRequest(new IngressPrincipal("tenant-a", "alice", "issuer", "USER"),
                "POST", path, query, projected, body);
    }

    static String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static byte[] fixture() {
        try (var input = OpenApiServerTestSupport.class.getResourceAsStream("/orders-openapi.json")) {
            if (input == null) throw new IllegalStateException("missing OpenAPI fixture");
            return input.readAllBytes();
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    static final class FakeIngress implements TrustedIngress {
        final AtomicReference<Object> payload = new AtomicReference<>();
        final AtomicReference<String> key = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile IngressReceipt receipt = new IngressReceipt.DurablyCommitted("stored");
        volatile Runnable onOffer = () -> { };
        @Override public IngressDisposition offer(SecurityContext security, ai.ravenroot.api.deployment.IngressTarget target,
                                                  Object payload) { return IngressDisposition.ACCEPTED; }
        @Override public int bufferCapacity() { return 8; }
        @Override public IngressOverflowPolicy overflowPolicy() { return IngressOverflowPolicy.REJECT; }
        @Override public IngressReceipt offerDurably(SecurityContext security, ai.ravenroot.api.deployment.IngressTarget target,
                                                     Object payload, String sourceId, String idempotentKey) {
            calls.incrementAndGet(); this.payload.set(payload); this.key.set(idempotentKey); onOffer.run(); return receipt;
        }
    }

    static final class FakeContext implements InboundSourceContext {
        final FakeIngress ingress;
        final FakeRequestReply requestReply = new FakeRequestReply();
        FakeContext(FakeIngress ingress) { this.ingress = ingress; }
        @Override public DeploymentId deploymentId() { return DeploymentId.of("deployment-a"); }
        @Override public String nodeId() { return "openapi-node"; }
        @Override public SecurityContext identity() {
            return new SecurityContext("request", "tenant-a", "operator", PrincipalType.WORKLOAD, "runtime");
        }
        @Override public TrustedIngress ingress() { return ingress; }
        @Override public RequestReplyIngress requestReply() { return requestReply; }
        @Override public void reportDegraded(String reason) { }
        @Override public void reportHealthy() { }
    }

    static final class FakeRequestReply implements RequestReplyIngress {
        final AtomicReference<PayloadValue> payload = new AtomicReference<>();
        final AtomicReference<FakeExchange> exchange = new AtomicReference<>();
        volatile long bindingGeneration = 1;
        final AtomicReference<ProjectionGate> projectionGate = new AtomicReference<>();

        @Override public RequestReplyAdmission request(IngressTarget target, PayloadValue payload, Instant deadline) {
            return new RequestReplyAdmission.Refused(ai.ravenroot.api.deployment.RequestReplyRefusal.UNSUPPORTED);
        }

        @Override public RequestReplyAdmission requestProjected(IngressTarget target,
                                                                 RequestReplyProjection projection,
                                                                 Instant deadline) {
            ProjectionGate gate = projectionGate.getAndSet(null);
            if (gate != null) gate.pause(ProjectionGate.Position.BEFORE);
            UUID process = UUID.randomUUID();
            UUID traversal = UUID.randomUUID();
            var context = new RequestReplyContext(new RequestReplyBinding(
                    "tenant-a", "deployment-a", bindingGeneration, Optional.of("openapi-node")), target,
                    UUID.randomUUID(), process, traversal, deadline);
            PayloadValue projected = projection.project(context);
            if (gate != null) gate.pause(ProjectionGate.Position.AFTER);
            payload.set(projected);
            FakeExchange created = new FakeExchange(context);
            exchange.set(created);
            return new RequestReplyAdmission.Accepted(created);
        }

        ProjectionGate gateProjectionAt(ProjectionGate.Position position) {
            ProjectionGate created = new ProjectionGate(position);
            projectionGate.set(created);
            return created;
        }

        void clearProjectionGate() {
            projectionGate.set(null);
        }
    }

    static final class ProjectionGate {
        enum Position { BEFORE, AFTER }
        private final Position position;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        ProjectionGate(Position position) { this.position = position; }

        void awaitEntered() throws InterruptedException {
            if (!entered.await(2, TimeUnit.SECONDS)) throw new AssertionError("projection gate was not entered");
        }

        void release() { released.countDown(); }

        private void pause(Position location) {
            if (position != location) return;
            entered.countDown();
            try {
                if (!released.await(2, TimeUnit.SECONDS)) throw new AssertionError("projection gate was not released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("projection gate interrupted");
            }
        }
    }

    static final class FakeExchange implements RequestReplyExchange {
        private final RequestReplyContext context;
        private final CompletableFuture<RequestReplyOutcome> completion = new CompletableFuture<>();
        final AtomicInteger cancellations = new AtomicInteger();

        FakeExchange(RequestReplyContext context) { this.context = context; }
        @Override public UUID correlationId() { return context.correlationId(); }
        @Override public UUID processInstanceId() { return context.processInstanceId(); }
        @Override public UUID traversalId() { return context.traversalId(); }
        @Override public Instant deadline() { return context.deadline(); }
        @Override public java.util.concurrent.CompletionStage<RequestReplyOutcome> completion() { return completion; }
        @Override public boolean cancel() {
            boolean won = completion.complete(new RequestReplyOutcome(processInstanceId(), traversalId(),
                    RequestReplyTerminalState.CANCELLED, Optional.empty()));
            if (won) cancellations.incrementAndGet();
            return won;
        }

        boolean complete(Object result) {
            return completion.complete(new RequestReplyOutcome(processInstanceId(), traversalId(),
                    RequestReplyTerminalState.COMPLETED, Optional.of(new ExecutionOutcome(
                    processInstanceId(), traversalId(), ProcessInstanceStatus.COMPLETED, result,
                    Set.of("openapi-node"), Set.of()))));
        }

        boolean timeout() {
            return completion.complete(new RequestReplyOutcome(processInstanceId(), traversalId(),
                    RequestReplyTerminalState.TIMED_OUT, Optional.empty()));
        }
    }

    static final class FakeAuthority implements IngressRouteAuthority {
        final AtomicInteger acquisitions = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();
        volatile IngressRouteHandler handler;
        volatile boolean refuse;
        volatile String path;
        volatile Set<String> methods;

        @Override public IngressRouteLease acquire(String routeId, String relativePath, Set<String> methods,
                                                   IngressRouteHandler handler) {
            throw new AssertionError("prefix lease required");
        }
        @Override public IngressRouteLease acquirePrefix(String routeId, String relativePath, Set<String> methods,
                                                         IngressRouteHandler handler) {
            if (refuse) throw new IllegalStateException("collision detail must be sanitized");
            acquisitions.incrementAndGet(); this.handler = handler; this.path = relativePath; this.methods = Set.copyOf(methods);
            return new IngressRouteLease() {
                private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
                @Override public String routeId() { return routeId; }
                @Override public IngressRouteOwner owner() {
                    return new IngressRouteOwner(OpenApiServerConfiguration.PACKAGE_ID, "tenant-a", "deployment-a",
                            "openapi-node", 1);
                }
                @Override public void release() { if (closed.compareAndSet(false, true)) releases.incrementAndGet(); }
            };
        }

        IngressResponse invoke(IngressRequest request) {
            return handler.handle(request).toCompletableFuture().join();
        }

        java.util.concurrent.CompletionStage<IngressResponse> invokeStage(IngressRequest request,
                                                                          IngressRequestContext context) {
            return handler.handle(request, context);
        }
    }

    private OpenApiServerTestSupport() { }
}
