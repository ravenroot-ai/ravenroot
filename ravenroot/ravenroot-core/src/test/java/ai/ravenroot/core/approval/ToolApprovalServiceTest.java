package ai.ravenroot.core.approval;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.ToolApprovalRegistration;
import ai.ravenroot.api.persistence.ToolApprovalStatus;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolApprovalServiceTest {
    private static final String TENANT = "acme";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final byte[] ARGUMENTS = "{\"value\":1}".getBytes(StandardCharsets.UTF_8);

    @Test
    void exactGrantIsSeparatedScopedConsumedOnceAndAudited() throws Exception {
        try (var store = new InMemoryExecutionStore(fixed(NOW))) {
            Fixture fixture = createRunning(store, NOW.plusSeconds(60), false);
            var service = new ToolApprovalService(store, fixed(NOW));

            assertEquals(ToolApprovalResult.Code.CREATED,
                    service.request(fixture.key, fixture.request, "request-create").code());
            var waiting = await(store.load(fixture.key)).state().traversals().get(fixture.traversalId);
            assertEquals(TraversalStatus.WAITING, waiting.status());
            assertEquals(NodeInvocationStatus.WAITING,
                    waiting.invocations().get(fixture.invocationId).status());
            assertEquals(NodeAttemptStatus.WAITING,
                    waiting.invocations().get(fixture.invocationId).attempts().getFirst().status());

            assertEquals(ToolApprovalResult.Code.UNAUTHORIZED,
                    service.approve(requester(), fixture.key.processInstanceId(), fixture.approvalId).code(),
                    "qualified requester identity cannot approve when separation is enabled");
            assertEquals(ToolApprovalResult.Code.APPROVED,
                    service.approve(approver(), fixture.key.processInstanceId(), fixture.approvalId).code());

            NodeMessage message = fixture.message();
            assertEquals(ToolApprovalResult.Code.SCOPE_MISMATCH,
                    service.redeem(message, fixture.approvalId, "tool.test",
                            "{\"value\":2}".getBytes(StandardCharsets.UTF_8), digest("{\"value\":2}"),
                            invocation -> new ToolDecision(ToolDecision.Disposition.ALLOW, "still allowed", ""))
                            .code());
            assertEquals(ToolApprovalStatus.APPROVED,
                    await(store.loadToolApproval(fixture.key, fixture.approvalId)).orElseThrow().status());

            assertEquals(ToolApprovalResult.Code.CONSUMED,
                    service.redeem(message, fixture.approvalId, "tool.test", ARGUMENTS, digest(ARGUMENTS),
                            invocation -> new ToolDecision(ToolDecision.Disposition.REQUIRE_APPROVAL,
                                    "unchanged", "policy-v1")).code());
            assertEquals(ToolApprovalResult.Code.ALREADY_SETTLED,
                    service.redeem(message, fixture.approvalId, "tool.test", ARGUMENTS, digest(ARGUMENTS),
                            invocation -> new ToolDecision(ToolDecision.Disposition.ALLOW, "allowed", "")).code());
            assertEquals(ToolApprovalResult.Code.SUCCEEDED,
                    service.complete(fixture.key, fixture.approvalId, true, "effect").code());
            assertEquals(ToolApprovalStatus.SUCCEEDED,
                    await(store.loadToolApproval(fixture.key, fixture.approvalId)).orElseThrow().status());

            assertTrue(await(store.readJournal(TENANT, 0, 100)).stream()
                    .allMatch(row -> !new String(row.envelope().payload().bytes(), StandardCharsets.UTF_8)
                            .contains("value")), "journal payloads must never contain canonical arguments");
        }
    }

    @Test
    void concurrentConflictingDecisionsHaveOneDurableWinner() throws Exception {
        try (var store = new InMemoryExecutionStore(fixed(NOW))) {
            Fixture fixture = createRunning(store, NOW.plusSeconds(60), false);
            var service = new ToolApprovalService(store, fixed(NOW));
            service.request(fixture.key, fixture.request, "create");

            var approve = CompletableFuture.supplyAsync(() ->
                    service.approve(approver(), fixture.key.processInstanceId(), fixture.approvalId));
            var deny = CompletableFuture.supplyAsync(() ->
                    service.deny(approver(), fixture.key.processInstanceId(), fixture.approvalId));
            ToolApprovalResult left = approve.get();
            ToolApprovalResult right = deny.get();

            long winners = List.of(left, right).stream()
                    .filter(result -> result.code() == ToolApprovalResult.Code.APPROVED
                            || result.code() == ToolApprovalResult.Code.DENIED)
                    .count();
            assertEquals(1, winners);
            assertTrue(List.of(ToolApprovalStatus.APPROVED, ToolApprovalStatus.DENIED).contains(
                    await(store.loadToolApproval(fixture.key, fixture.approvalId)).orElseThrow().status()));
            assertEquals(1, await(store.claimPendingWork(TENANT, "worker", 10,
                    java.time.Duration.ofSeconds(10))).stream()
                    .filter(PendingWork.HandlerTrigger.class::isInstance).count());
        }
    }

    @Test
    void expiryCancellationAndPolicyRevocationAreTerminalAndIdempotent() throws Exception {
        var clock = new MutableClock(NOW);
        try (var store = new InMemoryExecutionStore(clock)) {
            Fixture expired = createRunning(store, NOW.plusSeconds(1), false);
            var service = new ToolApprovalService(store, clock);
            service.request(expired.key, expired.request, "create");
            clock.now = NOW.plusSeconds(1);
            assertEquals(ToolApprovalResult.Code.EXPIRED,
                    service.expire(expired.key, expired.approvalId, "timer").code());
            assertEquals(ToolApprovalResult.Code.ALREADY_APPLIED,
                    service.expire(expired.key, expired.approvalId, "timer-replay").code());
        }

        try (var store = new InMemoryExecutionStore(fixed(NOW))) {
            Fixture cancelled = createRunning(store, NOW.plusSeconds(60), false);
            var service = new ToolApprovalService(store, fixed(NOW));
            service.request(cancelled.key, cancelled.request, "create");
            assertEquals(ToolApprovalResult.Code.CANCELLED,
                    service.cancel(requester(), cancelled.key.processInstanceId(), cancelled.approvalId).code());
            assertEquals(ToolApprovalResult.Code.ALREADY_APPLIED,
                    service.cancel(requester(), cancelled.key.processInstanceId(), cancelled.approvalId).code());
        }

        try (var store = new InMemoryExecutionStore(fixed(NOW))) {
            Fixture revoked = createRunning(store, NOW.plusSeconds(60), false);
            var service = new ToolApprovalService(store, fixed(NOW));
            service.request(revoked.key, revoked.request, "create");
            service.approve(approver(), revoked.key.processInstanceId(), revoked.approvalId);
            assertEquals(ToolApprovalResult.Code.POLICY_REVOKED,
                    service.redeem(revoked.message(), revoked.approvalId, "tool.test", ARGUMENTS,
                            digest(ARGUMENTS), invocation -> new ToolDecision(
                                    ToolDecision.Disposition.DENY, "revoked", "")).code());
            assertEquals(ToolApprovalStatus.CANCELLED,
                    await(store.loadToolApproval(revoked.key, revoked.approvalId)).orElseThrow().status());
        }
    }

    private static Fixture createRunning(ExecutionStore store, Instant expiry,
                                         boolean requesterMayApprove) throws Exception {
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversal = UUID.randomUUID();
        UUID invocation = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        UUID approval = UUID.randomUUID();
        UUID call = UUID.randomUUID();
        NodeAttempt nodeAttempt = new NodeAttempt(attempt, 1, NodeAttemptStatus.RUNNING);
        NodeInvocation nodeInvocation = new NodeInvocation(invocation, "agent", Set.of(),
                NodeInvocationStatus.RUNNING, List.of(nodeAttempt));
        Traversal running = new Traversal(traversal, "agent", TraversalStatus.RUNNING,
                Map.of(invocation, nodeInvocation));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(),
                        ProcessInstanceStatus.RUNNING, Map.of(traversal, running)),
                        new GraphVersionPin("graph-v1")))
                .build()));
        var request = new ToolApprovalRegistration(approval, traversal, invocation, attempt, call,
                "agent", "tool.test", ARGUMENTS, digest(ARGUMENTS),
                requesterIdentity(), new GraphVersionPin("graph-v1"), "policy-v1", expiry,
                HandlerAuthorization.ofRoles(Role.APPROVER.name()), requesterMayApprove, 1,
                "checkpoint-v1".getBytes(StandardCharsets.UTF_8),
                digest("checkpoint-v1".getBytes(StandardCharsets.UTF_8)));
        return new Fixture(key, traversal, invocation, attempt, approval, request);
    }

    private static RequestContext requester() {
        return new RequestContext("requester-call", "requester", PrincipalType.USER, "issuer", TENANT,
                Set.of(Role.APPROVER), Set.of());
    }

    private static RequestContext approver() {
        return new RequestContext("approver-call", "approver", PrincipalType.USER, "issuer", TENANT,
                Set.of(Role.APPROVER), Set.of());
    }

    private static SecurityContext requesterIdentity() {
        return SecurityContext.of(requester());
    }

    private static Clock fixed(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static String digest(String value) throws Exception {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(byte[] value) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static <T> T await(java.util.concurrent.CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private record Fixture(ExecutionKey key, UUID traversalId, UUID invocationId, UUID attemptId,
                           UUID approvalId, ToolApprovalRegistration request) {
        NodeMessage message() {
            return new NodeMessage(requesterIdentity(), key.processInstanceId(), traversalId,
                    invocationId, attemptId, "agent", null, Map.of());
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
