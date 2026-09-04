package ai.ravenroot.extensions.openapi.server;

import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.NodeCommand;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.ingress.IngressRequestContext;
import ai.ravenroot.api.ingress.IngressResponse;
import ai.ravenroot.api.node.ManagedIngressSource;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiRequestReplySourceTest {
    @Test void declaredRespondCommandProducesTheOnlySuccessfulHttpResponse() throws Exception {
        Fixture fixture = new Fixture(2);
        fixture.context.requestReply.bindingGeneration = 41;
        fixture.activate();

        CompletionStage<IngressResponse> waiting = fixture.invoke(new TestCancellation());
        OpenApiServerTestSupport.FakeExchange exchange = fixture.exchange();
        Map<?, ?> request = (Map<?, ?>) fixture.context.requestReply.payload.get().toJava();
        Map<?, ?> runtime = (Map<?, ?>) request.get("requestReply");
        assertEquals(exchange.correlationId().toString(), runtime.get("correlationId"));
        assertEquals(exchange.processInstanceId().toString(), runtime.get("processInstanceId"));
        assertEquals(exchange.traversalId().toString(), runtime.get("traversalId"));
        assertEquals("openapi-node", runtime.get("sourceNodeId"));
        assertEquals(41L, runtime.get("generation"));

        NodeResult response = fixture.respond(exchange, proposal(exchange, 200,
                Map.of("result-id", "reply"), "application/json", Map.of("result", "accepted")));
        OpenApiIngressPlan.Match declared = OpenApiIngressPlan.compileRequestReply(
                OpenApiServerTestSupport.profile(), Set.of("createOrder"),
                OpenApiServerTestSupport.configuration().projection().allowedHeaders()).match(
                OpenApiServerTestSupport.request("/orders/42", Map.of("verbose", List.of("true")),
                        Map.of("idempotency-key", "direct", "x-trace", "trace"),
                        "{\"amount\":2}".getBytes(StandardCharsets.UTF_8)));
        assertTrue(declared.responses().containsKey(200));
        assertEquals(200L, ((Map<?, ?>) response.payload()).get("status"));
        assertEquals(200, declared.projectResponse(response.payload(), exchange.correlationId(), 1024).status());
        exchange.complete(response.payload());

        IngressResponse http = waiting.toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(200, http.status());
        assertEquals("reply", http.headers().get("result-id"));
        assertEquals("application/json", http.headers().get("content-type"));
        assertEquals("{\"result\":\"accepted\"}", new String(http.body(), StandardCharsets.UTF_8));
        assertEquals(0, fixture.responses.size());
    }

    @Test void wellShapedPayloadWithoutTheDeclaredCommandIsRejected() throws Exception {
        Fixture fixture = new Fixture(2);
        fixture.activate();
        CompletionStage<IngressResponse> waiting = fixture.invoke(new TestCancellation());
        OpenApiServerTestSupport.FakeExchange exchange = fixture.exchange();
        Map<String, Object> forged = proposal(exchange, 200, Map.of(), "application/json",
                Map.of("result", "forged"));
        forged.put("version", "openapi.response.v1");
        exchange.complete(forged);
        assertEquals(500, waiting.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
        assertEquals(0, fixture.responses.size());
    }

    @Test void sourceNodeAndExchangeIdentityCannotBeCrossedOrCompletedTwice() throws Exception {
        Fixture fixture = new Fixture(2);
        fixture.activate();
        CompletionStage<IngressResponse> first = fixture.invoke(new TestCancellation());
        OpenApiServerTestSupport.FakeExchange exchange = fixture.exchange();
        Map<String, Object> proposal = proposal(exchange, 200, Map.of(), "application/json",
                Map.of("result", "ok"));

        assertThrows(java.util.concurrent.CompletionException.class,
                () -> fixture.respondAt(exchange, proposal, "other-node"));
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> fixture.respondAs(exchange, proposal, "tenant-b", exchange.processInstanceId(),
                        exchange.traversalId()));
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> fixture.respondAs(exchange, proposal, "tenant-a", UUID.randomUUID(), exchange.traversalId()));
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> fixture.respondAs(exchange, proposal, "tenant-a", exchange.processInstanceId(), UUID.randomUUID()));
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> fixture.respond(exchange, proposal(UUID.randomUUID(), 200, Map.of(),
                        "application/json", Map.of("result", "wrong-correlation"))));
        NodeResult accepted = fixture.respond(exchange, proposal);
        assertThrows(java.util.concurrent.CompletionException.class, () -> fixture.respond(exchange, proposal));
        exchange.complete(accepted.payload());
        assertEquals(200, first.toCompletableFuture().get(2, TimeUnit.SECONDS).status());

        UUID isolated = UUID.randomUUID();
        UUID process = UUID.randomUUID();
        UUID traversal = UUID.randomUUID();
        assertTrue(fixture.responses.open(isolated, "tenant-a", process, traversal, "openapi-node", 7));
        Object command = fixture.responses.complete("tenant-a", process, traversal, "openapi-node",
                proposal(isolated, 200, Map.of(), "application/json", Map.of("result", "generation")));
        assertFalse(fixture.responses.consume(isolated, "tenant-a", process, traversal, "openapi-node", 8, command));
        assertTrue(fixture.responses.consume(isolated, "tenant-a", process, traversal, "openapi-node", 7, command));
    }

    @Test void timeoutCancellationAndStopReleasePermitsAndClearPendingExactlyOnce() throws Exception {
        Fixture fixture = new Fixture(1);
        fixture.activate();
        TestCancellation cancellation = new TestCancellation();
        CompletionStage<IngressResponse> first = fixture.invoke(cancellation);
        OpenApiServerTestSupport.FakeExchange cancelled = fixture.exchange();
        assertEquals(429, fixture.invoke(new TestCancellation()).toCompletableFuture()
                .get(2, TimeUnit.SECONDS).status());
        cancellation.cancel();
        assertEquals(503, first.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
        assertEquals(1, cancelled.cancellations.get());
        assertEquals(0, ((OpenApiRequestReplySource) fixture.source).pendingCount());

        CompletionStage<IngressResponse> timed = fixture.invoke(new TestCancellation());
        OpenApiServerTestSupport.FakeExchange timedExchange = fixture.exchange();
        timedExchange.timeout();
        assertEquals(504, timed.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
        assertThrows(java.util.concurrent.CompletionException.class, () -> fixture.respond(timedExchange,
                proposal(timedExchange, 200, Map.of(), "application/json", Map.of("result", "late"))));

        CompletionStage<IngressResponse> stopped = fixture.invoke(new TestCancellation());
        OpenApiServerTestSupport.FakeExchange stoppedExchange = fixture.exchange();
        fixture.source.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(503, stopped.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
        assertEquals(1, stoppedExchange.cancellations.get());
        assertFalse(stoppedExchange.complete(proposal(stoppedExchange, 200, Map.of(),
                "application/json", Map.of("result", "too-late"))));
        assertEquals(0, fixture.responses.size());
        assertEquals(0, ((OpenApiRequestReplySource) fixture.source).pendingCount());

        fixture.source.start(fixture.context).toCompletableFuture().join();
        fixture.source.activateManagedIngress(fixture.authority).toCompletableFuture().join();
        CompletionStage<IngressResponse> terminalWinner = fixture.invoke(new TestCancellation());
        OpenApiServerTestSupport.FakeExchange terminalExchange = fixture.exchange();
        NodeResult response = fixture.respond(terminalExchange, proposal(terminalExchange, 200, Map.of(),
                "application/json", Map.of("result", "terminal-won")));
        terminalExchange.complete(response.payload());
        fixture.source.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(200, terminalWinner.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
        assertEquals(0, terminalExchange.cancellations.get());
        assertEquals(0, ((OpenApiRequestReplySource) fixture.source).pendingCount());

        fixture.source.start(fixture.context).toCompletableFuture().join();
        fixture.source.activateManagedIngress(fixture.authority).toCompletableFuture().join();
        CompletionStage<IngressResponse> reusable = fixture.invoke(new TestCancellation());
        fixture.exchange().timeout();
        assertEquals(504, reusable.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
    }

    @Test void stopRollbackAndReplacementFenceCallsPausedAroundRuntimeProjection() throws Exception {
        lifecycleRace(OpenApiServerTestSupport.ProjectionGate.Position.BEFORE, false);
        lifecycleRace(OpenApiServerTestSupport.ProjectionGate.Position.AFTER, true);
    }

    @Test void completionAndDeadlineRaceHasExactlyOneTerminalWinnerAndReleasesThePermit() throws Exception {
        Fixture fixture = new Fixture(1);
        fixture.activate();

        CompletionStage<IngressResponse> completed = fixture.invoke(new TestCancellation());
        OpenApiServerTestSupport.FakeExchange completedExchange = fixture.exchange();
        NodeResult declared = fixture.respond(completedExchange, proposal(completedExchange, 200, Map.of(),
                "application/json", Map.of("result", "completed-first")));
        assertTrue(completedExchange.complete(declared.payload()));
        assertFalse(completedExchange.timeout());
        assertEquals(200, completed.toCompletableFuture().get(2, TimeUnit.SECONDS).status());

        CompletionStage<IngressResponse> timedOut = fixture.invoke(new TestCancellation());
        OpenApiServerTestSupport.FakeExchange timedExchange = fixture.exchange();
        assertTrue(timedExchange.timeout());
        assertFalse(timedExchange.complete(proposal(timedExchange, 200, Map.of(),
                "application/json", Map.of("result", "completed-late"))));
        assertEquals(504, timedOut.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
        assertEquals(0, ((OpenApiRequestReplySource) fixture.source).pendingCount());
        assertEquals(0, fixture.responses.size());

        CompletionStage<IngressResponse> reusable = fixture.invoke(new TestCancellation());
        fixture.exchange().timeout();
        assertEquals(504, reusable.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
    }

    @Test void concurrentExchangesRejectCrossRequestRepliesAndMayFinishOutOfOrder() throws Exception {
        Fixture fixture = new Fixture(2);
        fixture.activate();
        CompletionStage<IngressResponse> first = fixture.invoke(new TestCancellation());
        OpenApiServerTestSupport.FakeExchange firstExchange = fixture.exchange();
        CompletionStage<IngressResponse> second = fixture.invoke(new TestCancellation());
        OpenApiServerTestSupport.FakeExchange secondExchange = fixture.exchange();
        assertNotEquals(firstExchange.correlationId(), secondExchange.correlationId());
        assertNotEquals(firstExchange.processInstanceId(), secondExchange.processInstanceId());
        assertNotEquals(firstExchange.traversalId(), secondExchange.traversalId());

        assertThrows(java.util.concurrent.CompletionException.class, () -> fixture.respond(secondExchange,
                proposal(firstExchange, 200, Map.of(), "application/json", Map.of("result", "crossed"))));
        NodeResult secondResponse = fixture.respond(secondExchange, proposal(secondExchange, 200, Map.of(),
                "application/json", Map.of("result", "second")));
        NodeResult firstResponse = fixture.respond(firstExchange, proposal(firstExchange, 200, Map.of(),
                "application/json", Map.of("result", "first")));
        secondExchange.complete(secondResponse.payload());
        firstExchange.complete(firstResponse.payload());

        assertEquals("{\"result\":\"first\"}", new String(first.toCompletableFuture()
                .get(2, TimeUnit.SECONDS).body(), StandardCharsets.UTF_8));
        assertEquals("{\"result\":\"second\"}", new String(second.toCompletableFuture()
                .get(2, TimeUnit.SECONDS).body(), StandardCharsets.UTF_8));
        assertEquals(0, fixture.responses.size());
    }

    @Test void invalidResponseDimensionsFailClosedAndReleaseCapacity() throws Exception {
        Fixture fixture = new Fixture(1);
        fixture.activate();
        List<Map<String, Object>> invalid = List.of(
                proposal(UUID.randomUUID(), 201, Map.of(), null, null),
                proposal(UUID.randomUUID(), 200, Map.of("undeclared", "value"),
                        "application/json", Map.of("result", "ok")),
                proposal(UUID.randomUUID(), 200, Map.of(), "text/plain", Map.of("result", "ok")),
                proposal(UUID.randomUUID(), 200, Map.of(), "application/json", Map.of("unexpected", "value")),
                proposal(UUID.randomUUID(), 200, Map.of(), "application/json", Map.of("result", "x".repeat(65))));
        for (Map<String, Object> template : invalid) {
            CompletionStage<IngressResponse> waiting = fixture.invoke(new TestCancellation());
            OpenApiServerTestSupport.FakeExchange exchange = fixture.exchange();
            Map<String, Object> proposed = new LinkedHashMap<>(template);
            proposed.put("correlationId", exchange.correlationId().toString());
            NodeResult declared = fixture.respond(exchange, proposed);
            exchange.complete(declared.payload());
            assertEquals(500, waiting.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
            assertEquals(0, fixture.responses.size());
        }
        CompletionStage<IngressResponse> recovered = fixture.invoke(new TestCancellation());
        fixture.exchange().timeout();
        assertEquals(504, recovered.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
    }

    @Test void asynchronousDescriptorAndPayloadContractRemainUnchanged() {
        var async = new OpenApiReceiveNodeBehavior(OpenApiServerTestSupport::configuration);
        assertEquals(Set.of(), async.descriptor().commands());
        assertFalse(async.descriptor().properties().stream().anyMatch(property -> property.name().equals("mode")));
        assertTrue(new OpenApiRequestReplyNodeBehavior(OpenApiServerTestSupport::configuration)
                .descriptor().commands().contains("respond"));
    }

    @Test void forbiddenResponseHeaderProfileFailsBeforePublishingTheRuntimeRoute() {
        OpenApiServerProfile profile = OpenApiIngressPlanTest.profileWithResponseHeader("set-cookie");
        OpenApiServerConfiguration base = OpenApiServerTestSupport.configuration();
        OpenApiServerConfiguration configuration = new OpenApiServerConfiguration(
                base.authority(), base.projection(), Map.of("orders", profile));
        OpenApiRequestReplyNodeBehavior behavior = new OpenApiRequestReplyNodeBehavior(() -> configuration);
        OpenApiServerTestSupport.FakeContext context = new OpenApiServerTestSupport.FakeContext(
                new OpenApiServerTestSupport.FakeIngress());
        OpenApiServerTestSupport.FakeAuthority authority = new OpenApiServerTestSupport.FakeAuthority();
        ManagedIngressSource source = (ManagedIngressSource) behavior.createSource(new NodeConfiguration(
                "openapi-node", OpenApiRequestReplyNodeBehavior.BEHAVIOR, Map.of("apiProfile", "orders")), context);

        assertThrows(java.util.concurrent.CompletionException.class,
                () -> source.start(context).toCompletableFuture().join());
        assertNull(authority.handler);
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> source.activateManagedIngress(authority).toCompletableFuture().join());
        assertEquals(0, authority.acquisitions.get());
    }

    private static void lifecycleRace(OpenApiServerTestSupport.ProjectionGate.Position position,
                                      boolean rollback) throws Exception {
        Fixture fixture = new Fixture(1);
        fixture.activate();
        OpenApiServerTestSupport.ProjectionGate gate = fixture.context.requestReply.gateProjectionAt(position);
        CompletableFuture<IngressResponse> oldRequest = CompletableFuture.supplyAsync(() ->
                fixture.invoke(new TestCancellation()).toCompletableFuture().join());
        CompletionStage<IngressResponse> replacement;
        try {
            gate.awaitEntered();
            fixture.context.requestReply.clearProjectionGate();
            CompletionStage<Void> retirement = rollback ? fixture.source.rollback() : fixture.source.stop();
            retirement.toCompletableFuture().get(2, TimeUnit.SECONDS);
            fixture.source.start(fixture.context).toCompletableFuture().get(2, TimeUnit.SECONDS);
            fixture.source.activateManagedIngress(fixture.authority).toCompletableFuture().get(2, TimeUnit.SECONDS);
            replacement = fixture.invoke(new TestCancellation());
            fixture.exchange().timeout();
            assertEquals(504, replacement.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
        } finally {
            gate.release();
        }
        assertEquals(503, oldRequest.get(2, TimeUnit.SECONDS).status());
        assertEquals(0, ((OpenApiRequestReplySource) fixture.source).pendingCount());
        assertEquals(0, fixture.responses.size());
    }

    private static Map<String, Object> proposal(OpenApiServerTestSupport.FakeExchange exchange, int status,
                                                Map<String, Object> headers, Object mediaType, Object body) {
        return proposal(exchange.correlationId(), status, headers, mediaType, body);
    }

    private static Map<String, Object> proposal(UUID correlationId, int status,
                                                Map<String, Object> headers, Object mediaType, Object body) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("correlationId", correlationId.toString());
        result.put("operationId", "createOrder");
        result.put("status", status);
        result.put("headers", headers);
        result.put("mediaType", mediaType);
        result.put("body", body);
        return result;
    }

    private static final class Fixture {
        final OpenApiAdmissionRegistry admission = new OpenApiAdmissionRegistry();
        final OpenApiResponseRegistry responses = new OpenApiResponseRegistry();
        final OpenApiRequestReplyNodeBehavior behavior = new OpenApiRequestReplyNodeBehavior(
                OpenApiServerTestSupport::configuration, admission, responses);
        final OpenApiServerTestSupport.FakeContext context = new OpenApiServerTestSupport.FakeContext(
                new OpenApiServerTestSupport.FakeIngress());
        final OpenApiServerTestSupport.FakeAuthority authority = new OpenApiServerTestSupport.FakeAuthority();
        final ManagedIngressSource source;
        final NodeAction action;

        Fixture(int concurrency) {
            Map<String, Object> properties = Map.of("apiProfile", "orders", "operations", "createOrder",
                    "maxConcurrency", Integer.toString(concurrency));
            NodeConfiguration node = new NodeConfiguration("openapi-node",
                    OpenApiRequestReplyNodeBehavior.BEHAVIOR, properties);
            source = (ManagedIngressSource) behavior.createSource(node, context);
            action = behavior.create(node);
        }

        void activate() {
            source.start(context).toCompletableFuture().join();
            source.activateManagedIngress(authority).toCompletableFuture().join();
        }

        CompletionStage<IngressResponse> invoke(CancellationSignal cancellation) {
            return authority.invokeStage(OpenApiServerTestSupport.request("/orders/42",
                    Map.of("verbose", List.of("true")),
                    Map.of("idempotency-key", UUID.randomUUID().toString(), "x-trace", "trace"),
                    "{\"amount\":2}".getBytes(StandardCharsets.UTF_8)),
                    new IngressRequestContext(Instant.now().plusSeconds(2), cancellation));
        }

        OpenApiServerTestSupport.FakeExchange exchange() {
            return context.requestReply.exchange.get();
        }

        NodeResult respond(OpenApiServerTestSupport.FakeExchange exchange, Object payload) {
            return respondAt(exchange, payload, "openapi-node");
        }

        NodeResult respondAt(OpenApiServerTestSupport.FakeExchange exchange, Object payload, String nodeId) {
            return respondAs(exchange, payload, "tenant-a", exchange.processInstanceId(), exchange.traversalId(), nodeId);
        }

        NodeResult respondAs(OpenApiServerTestSupport.FakeExchange exchange, Object payload, String tenantId,
                             UUID processInstanceId, UUID traversalId) {
            return respondAs(exchange, payload, tenantId, processInstanceId, traversalId, "openapi-node");
        }

        NodeResult respondAs(OpenApiServerTestSupport.FakeExchange exchange, Object payload, String tenantId,
                             UUID processInstanceId, UUID traversalId, String nodeId) {
            SecurityContext security = new SecurityContext("request", tenantId, "operator",
                    PrincipalType.WORKLOAD, "runtime");
            NodeMessage message = new NodeMessage(security, processInstanceId,
                    traversalId, UUID.randomUUID(), UUID.randomUUID(), Set.of(), nodeId,
                    payload, Map.of(), NodeCommand.application("respond"));
            return action.handle(message).toCompletableFuture().join();
        }
    }

    private static final class TestCancellation implements CancellationSignal {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Runnable> listener = new AtomicReference<>();
        @Override public boolean cancelled() { return cancelled.get(); }
        @Override public void onCancel(Runnable callback) {
            listener.set(callback);
            if (cancelled.get()) callback.run();
        }
        void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                Runnable callback = listener.get();
                if (callback != null) callback.run();
            }
        }
    }
}
