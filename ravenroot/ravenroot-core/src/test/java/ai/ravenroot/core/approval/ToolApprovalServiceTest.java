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
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

            var journal = await(store.readJournal(TENANT, 0, 100));
            assertTrue(journal.stream().anyMatch(row -> "TOOL_APPROVAL_REDEMPTION_REPLAY"
                    .equals(row.envelope().eventType())),
                    "a replayed redemption must leave a sanitized durable audit event");
            assertTrue(journal.stream()
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
    void refreshedSettlementStateDispatchesSafelyInMemory() throws Exception {
        assertRefreshedSettlementRaces((clock, name) -> new InMemoryExecutionStore(clock));
    }

    @Test
    void refreshedSettlementStateDispatchesSafelyInSqlite(@TempDir Path directory) throws Exception {
        assertRefreshedSettlementRaces((clock, name) ->
                new SqliteExecutionStore(directory.resolve(name + ".db"), clock));
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
            Fixture approvedThenCancelled = createRunning(store, NOW.plusSeconds(60), false);
            var service = new ToolApprovalService(store, fixed(NOW));
            service.request(approvedThenCancelled.key, approvedThenCancelled.request, "create");
            ToolApprovalResult approved = service.approve(approver(),
                    approvedThenCancelled.key.processInstanceId(), approvedThenCancelled.approvalId);
            UUID existingTrigger = approved.resumeTraversalId();
            ToolApprovalResult cancelled = service.cancel(requester(),
                    approvedThenCancelled.key.processInstanceId(), approvedThenCancelled.approvalId);
            assertEquals(ToolApprovalResult.Code.CANCELLED, cancelled.code());
            assertEquals(existingTrigger, cancelled.resumeTraversalId(),
                    "cancellation after approval must preserve the already-resolved handler trigger");
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
            var seenExecution = new java.util.concurrent.atomic.AtomicReference<UUID>();
            assertEquals(ToolApprovalResult.Code.POLICY_REVOKED,
                    service.redeem(revoked.message(), revoked.approvalId, "tool.test", ARGUMENTS,
                            digest(ARGUMENTS), invocation -> {
                                seenExecution.set(invocation.executionId());
                                return new ToolDecision(ToolDecision.Disposition.DENY, "revoked", "");
                            }).code());
            assertEquals(revoked.key.processInstanceId(), seenExecution.get());
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

    private static void assertRefreshedSettlementRaces(StoreFactory stores) throws Exception {
        for (RaceTarget target : RaceTarget.values()) {
            var clock = new MutableClock(NOW);
            Instant expiry = target.expires() ? NOW.plusSeconds(1) : NOW.plusSeconds(60);
            try (ExecutionStore store = stores.open(clock, target.name().toLowerCase());
                 var staleReader = Executors.newSingleThreadExecutor(r ->
                         new Thread(r, "stale-settlement-reader"))) {
                Fixture fixture = createRunning(store, expiry, false);
                var service = new ToolApprovalService(store, clock);
                service.request(fixture.key, fixture.request, "create");
                PendingWork.TimerDue timer = null;
                if (target == RaceTarget.FENCED_TIMER_EXPIRY) {
                    clock.now = expiry;
                    timer = await(store.claimDueTimers(TENANT, "timer-worker", 10,
                            Duration.ofSeconds(30))).getFirst();
                    clock.now = NOW;
                }

                var processSnapshotRead = new CountDownLatch(1);
                var winnerCommitted = new CountDownLatch(1);
                ExecutionStore racedStore = pauseFirstProcessSnapshot(
                        store, processSnapshotRead, winnerCommitted, new AtomicBoolean());
                var racedService = new ToolApprovalService(racedStore, clock);
                PendingWork.TimerDue claimedTimer = timer;
                var losingOperation = CompletableFuture.supplyAsync(() -> switch (target) {
                    case DENIAL -> racedService.deny(
                            approver(), fixture.key.processInstanceId(), fixture.approvalId);
                    case CANCELLATION -> racedService.cancel(
                            requester(), fixture.key.processInstanceId(), fixture.approvalId);
                    case EXPIRY -> racedService.expire(fixture.key, fixture.approvalId, "expiry-race");
                    case FENCED_TIMER_EXPIRY -> racedService.expireClaimedTimer(
                            claimedTimer, "timer-expiry-race");
                }, staleReader);

                assertTrue(processSnapshotRead.await(5, TimeUnit.SECONDS),
                        "losing operation did not reach the inner process snapshot for " + target);
                ToolApprovalResult winner;
                try {
                    winner = racedService.approve(
                            approver(), fixture.key.processInstanceId(), fixture.approvalId);
                    if (target.expires()) clock.now = expiry;
                } finally {
                    winnerCommitted.countDown();
                }
                Object loser = losingOperation.get(5, TimeUnit.SECONDS);

                assertEquals(ToolApprovalResult.Code.APPROVED, winner.code());
                ToolApprovalStatus expected = switch (target) {
                    case DENIAL -> ToolApprovalStatus.APPROVED;
                    case CANCELLATION -> ToolApprovalStatus.CANCELLED;
                    case EXPIRY, FENCED_TIMER_EXPIRY -> ToolApprovalStatus.EXPIRED;
                };
                assertEquals(expected, await(store.loadToolApproval(
                        fixture.key, fixture.approvalId)).orElseThrow().status());
                if (target == RaceTarget.DENIAL) {
                    assertEquals(ToolApprovalResult.Code.ALREADY_SETTLED,
                            ((ToolApprovalResult) loser).code());
                    assertTrue(await(store.readJournal(TENANT, 0, 100)).stream().anyMatch(row ->
                            "TOOL_APPROVAL_CONFLICTING_DECISION".equals(row.envelope().eventType())));
                } else if (target == RaceTarget.FENCED_TIMER_EXPIRY) {
                    assertEquals(Boolean.TRUE, loser);
                } else {
                    assertEquals(ToolApprovalResult.Code.valueOf(expected.name()),
                            ((ToolApprovalResult) loser).code());
                }
                var handler = await(store.loadHandler(fixture.key, fixture.approvalId)).orElseThrow();
                assertEquals(winner.resumeTraversalId(), handler.resumeTraversalId(),
                        "a refreshed transition must preserve the winner's trigger for " + target);
                assertEquals(2, await(store.load(fixture.key)).state().traversals().size(),
                        "only PENDING may create a resume traversal for " + target);
                String worker = target == RaceTarget.FENCED_TIMER_EXPIRY ? "timer-worker" : "worker";
                List<PendingWork> pending = await(store.claimPendingWork(
                        TENANT, worker, 10, Duration.ofSeconds(30)));
                assertEquals(1, pending.stream().filter(PendingWork.HandlerTrigger.class::isInstance).count());
                assertEquals(fixture.approvalId, pending.stream()
                        .filter(PendingWork.HandlerTrigger.class::isInstance)
                        .findFirst().orElseThrow().workItemId());
            }
        }
    }

    private static ExecutionStore pauseFirstProcessSnapshot(
            ExecutionStore delegate, CountDownLatch processSnapshotRead, CountDownLatch winnerCommitted,
            AtomicBoolean intercepted) {
        return (ExecutionStore) Proxy.newProxyInstance(ToolApprovalServiceTest.class.getClassLoader(),
                new Class<?>[] {ExecutionStore.class}, (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if ("load".equals(method.getName())
                                && Thread.currentThread().getName().equals("stale-settlement-reader")
                                && intercepted.compareAndSet(false, true)) {
                            @SuppressWarnings("unchecked")
                            CompletionStage<Object> loaded = (CompletionStage<Object>) result;
                            return loaded.thenApplyAsync(value -> {
                                processSnapshotRead.countDown();
                                try {
                                    if (!winnerCommitted.await(5, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("winning decision did not commit");
                                    }
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException("approval race interrupted", interrupted);
                                }
                                return value;
                            });
                        }
                        return result;
                    } catch (InvocationTargetException invoked) {
                        throw invoked.getCause();
                    }
                });
    }

    private enum RaceTarget {
        DENIAL,
        CANCELLATION,
        EXPIRY,
        FENCED_TIMER_EXPIRY;

        boolean expires() {
            return this == EXPIRY || this == FENCED_TIMER_EXPIRY;
        }
    }

    @FunctionalInterface
    private interface StoreFactory {
        ExecutionStore open(Clock clock, String name) throws Exception;
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
