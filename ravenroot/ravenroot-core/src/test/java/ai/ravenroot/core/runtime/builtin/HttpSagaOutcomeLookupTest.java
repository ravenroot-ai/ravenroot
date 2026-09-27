package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.RetryClassified;
import ai.ravenroot.api.persistence.Retryability;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.api.security.ToolPolicy;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.runtime.NodeHandler;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpSagaOutcomeLookupTest {
    private static final String TOKEN = "participant-secret";
    private static final ToolPolicy ALLOW = ignored -> new ToolDecision(
            ToolDecision.Disposition.ALLOW, "test", "");

    @Test
    void lostResponseUsesTheSameCredentialAndAcceptsOnlyTheBoundParticipantReceipt() throws Exception {
        try (var participant = new Participant()) {
            participant.forwardStatus = 0;
            participant.lookupState = "APPLIED";
            String operation = "forward:" + UUID.randomUUID();

            var result = handler(participant, ALLOW).handle(message(operation, false))
                    .toCompletableFuture().join();

            assertEquals("continue", result.outcome());
            assertEquals(Boolean.TRUE, result.attributes().get("saga.reconciled"));
            assertEquals("Bearer " + TOKEN, participant.forwardAuthorization.get());
            assertEquals("Bearer " + TOKEN, participant.lookupAuthorization.get());
            assertEquals(1, participant.forwardCalls.get());
            assertEquals(1, participant.lookupCalls.get());
        }
    }

    @Test
    void serverErrorAfterCommitAndConflictReplayUseGovernedOutcomeLookup() throws Exception {
        for (int status : new int[]{500, 409}) {
            try (var participant = new Participant()) {
                participant.forwardStatus = status;
                participant.lookupState = "APPLIED";
                String operation = "forward:" + UUID.randomUUID();

                var result = handler(participant, ALLOW).handle(message(operation, false))
                        .toCompletableFuture().join();

                assertEquals("continue", result.outcome());
                assertEquals(Boolean.TRUE, result.attributes().get("saga.reconciled"));
                assertEquals(1, participant.lookupCalls.get(), "HTTP " + status + " must be reconciled");
            }
        }
    }

    @Test
    void compensationAcceptsOnlyTheCompensatedPhaseForItsExactOperation() throws Exception {
        try (var participant = new Participant()) {
            participant.forwardStatus = 500;
            participant.lookupState = "COMPENSATED";
            String operation = "compensate:" + UUID.randomUUID();
            assertEquals("continue", handler(participant, ALLOW)
                    .handle(message(operation, true)).toCompletableFuture().join().outcome());
        }

        try (var participant = new Participant()) {
            participant.forwardStatus = 500;
            participant.lookupState = "APPLIED";
            Throwable failure = failed(handler(participant, ALLOW)
                    .handle(message("compensate:" + UUID.randomUUID(), true)));
            assertEquals(Retryability.INDETERMINATE,
                    assertInstanceOf(RetryClassified.class, failure).retryability());
        }
    }

    @Test
    void anExactNotAppliedReceiptIsTheOnlyLookupResultClassifiedAsNoEffect() throws Exception {
        try (var participant = new Participant()) {
            participant.forwardStatus = 500;
            participant.lookupState = "NOT_APPLIED";
            Throwable failure = failed(handler(participant, ALLOW)
                    .handle(message("forward:" + UUID.randomUUID(), false)));

            assertEquals(Retryability.RETRYABLE_NO_EFFECT,
                    assertInstanceOf(RetryClassified.class, failure).retryability());
        }
    }

    @Test
    void malformedMismatchedAndWrongPhaseReceiptsRemainUnknown() throws Exception {
        for (ReceiptFault fault : ReceiptFault.values()) {
            try (var participant = new Participant()) {
                participant.forwardStatus = 500;
                participant.lookupState = fault == ReceiptFault.WRONG_PHASE ? "COMPENSATED" : "APPLIED";
                participant.fault = fault;
                Throwable failure = failed(handler(participant, ALLOW)
                        .handle(message("forward:" + UUID.randomUUID(), false)));

                assertEquals(Retryability.INDETERMINATE,
                        assertInstanceOf(RetryClassified.class, failure).retryability(), fault.name());
            }
        }
    }

    @Test
    void revokedCurrentAuthorityBlocksLookupWithoutInventingAnOutcome() throws Exception {
        try (var participant = new Participant()) {
            participant.forwardStatus = 500;
            AtomicInteger decisions = new AtomicInteger();
            ToolPolicy revokeBeforeLookup = ignored -> decisions.incrementAndGet() == 1
                    ? new ToolDecision(ToolDecision.Disposition.ALLOW, "forward allowed", "")
                    : new ToolDecision(ToolDecision.Disposition.DENY, "lookup revoked", "");

            assertThrows(CompletionException.class, () -> handler(participant, revokeBeforeLookup)
                    .handle(message("forward:" + UUID.randomUUID(), false)).toCompletableFuture().join());
            assertEquals(1, participant.forwardCalls.get());
            assertEquals(0, participant.lookupCalls.get());
        }
    }

    private static Throwable failed(java.util.concurrent.CompletionStage<?> stage) {
        CompletionException thrown = assertThrows(CompletionException.class,
                () -> stage.toCompletableFuture().join());
        Throwable cause = thrown.getCause();
        while (cause instanceof CompletionException nested && nested.getCause() != null) {
            cause = nested.getCause();
        }
        return cause;
    }

    private static NodeHandler handler(Participant participant, ToolPolicy toolPolicy) {
        int port = participant.server.getAddress().getPort();
        var policy = new OutboundHttpPolicy(Set.of("localhost"), Duration.ofSeconds(2), Set.of(port),
                4096, 4096);
        var factory = new HttpRequestNodeBehaviorFactory(policy,
                reference -> "participant-token".equals(reference)
                        ? Optional.of(new SecretValue(TOKEN.toCharArray())) : Optional.empty(), toolPolicy);
        return factory.create(new GraphNode("participant", NodeKind.BEHAVIOR, "http-request", Map.of(
                "url", "http://localhost:" + port + "/effects",
                "method", "POST",
                "body", "{\"orderId\":\"486\"}",
                "credentialRef", "participant-token",
                "saga.participant", "http-idempotency-v1",
                "saga.adapter", "ravenroot.http-idempotency.v1",
                "saga.outcomeLookupUrl", "http://localhost:" + port + "/operations/"
                        + "{{attributes.sagaOperationId}}")));
    }

    private static NodeMessage message(String operation, boolean compensation) {
        String forward = compensation ? "forward:causal" : operation;
        String undo = compensation ? operation : "compensate:future";
        return new NodeMessage(new SecurityContext("request", "tenant", "operator", PrincipalType.USER,
                "urn:ravenroot:test"), UUID.randomUUID(), UUID.randomUUID(), "participant", Map.of(),
                Map.of("sagaOperationId", operation, "sagaForwardOperationId", forward,
                        "sagaCompensationOperationId", undo));
    }

    private enum ReceiptFault { MALFORMED, OPERATION, FINGERPRINT, WRONG_PHASE }

    private static final class Participant implements AutoCloseable {
        private final AtomicReference<String> fingerprint = new AtomicReference<>();
        private final AtomicReference<String> forwardAuthorization = new AtomicReference<>();
        private final AtomicReference<String> lookupAuthorization = new AtomicReference<>();
        private final AtomicInteger forwardCalls = new AtomicInteger();
        private final AtomicInteger lookupCalls = new AtomicInteger();
        private final HttpServer server;
        private volatile int forwardStatus;
        private volatile String lookupState = "APPLIED";
        private volatile ReceiptFault fault;

        private Participant() throws IOException {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/effects", exchange -> {
                exchange.getRequestBody().readAllBytes();
                forwardCalls.incrementAndGet();
                forwardAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                fingerprint.set(exchange.getRequestHeaders().getFirst("X-Ravenroot-Payload-SHA256"));
                if (forwardStatus == 0) {
                    exchange.close();
                    return;
                }
                byte[] body = "participant response".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(forwardStatus, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/operations", exchange -> {
                lookupCalls.incrementAndGet();
                lookupAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                String operation = exchange.getRequestURI().getPath().substring("/operations/".length());
                String body;
                if (fault == ReceiptFault.MALFORMED) {
                    body = "{";
                } else {
                    String reportedOperation = fault == ReceiptFault.OPERATION ? "different" : operation;
                    String reportedFingerprint = fault == ReceiptFault.FINGERPRINT
                            ? "0".repeat(64) : fingerprint.get();
                    body = "{\"operationId\":\"" + reportedOperation + "\",\"fingerprint\":\""
                            + reportedFingerprint + "\",\"state\":\"" + lookupState + "\"}";
                }
                byte[] response = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
