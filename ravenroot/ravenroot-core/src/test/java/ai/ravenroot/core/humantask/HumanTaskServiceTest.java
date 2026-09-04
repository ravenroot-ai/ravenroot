package ai.ravenroot.core.humantask;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.payload.PayloadEnvelope;
import ai.ravenroot.api.payload.PayloadKind;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.HumanTaskMetadata;
import ai.ravenroot.api.persistence.HumanTaskReentryMapping;
import ai.ravenroot.api.persistence.HumanTaskResponseSchema;
import ai.ravenroot.api.persistence.HumanTaskStatus;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.recovery.ExecutionRecoveryService;
import ai.ravenroot.core.recovery.RecoveryOutcome;
import ai.ravenroot.core.recovery.RepeatabilityDeclarations;
import ai.ravenroot.core.runtime.ExecutionRecorder;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HumanTaskServiceTest {
    private static final String TENANT = "acme";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String CONTENT_TYPE = "application/vnd.ravenroot.payload+json";

    @TempDir
    Path directory;

    @Test
    void suspensionAndResolutionAreAtomicGenerationFencedAndPayloadSafe() throws Exception {
        try (var store = sqlite("resolution", Clock.fixed(NOW, ZoneOffset.UTC))) {
            Fixture fixture = running(store);
            var service = new HumanTaskService(store, Clock.fixed(NOW, ZoneOffset.UTC));
            ExecutionRecorder recorder = ExecutionRecorder.open(store, fixture.key, "worker",
                    Duration.ofSeconds(30), 1);
            HumanTaskResult suspended;
            try (recorder; var binding = service.bindLive(fixture.key, recorder)) {
                suspended = service.suspend(fixture.message(), definition());
            }

            assertEquals(HumanTaskResult.Code.CREATED, suspended.code());
            assertEquals(HumanTaskStatus.WAITING, suspended.task().status());
            assertEquals(TraversalStatus.WAITING,
                    store.load(fixture.key).toCompletableFuture().join().state()
                            .traversals().get(fixture.traversalId).status());
            assertEquals(HumanTaskResult.Code.NOT_FOUND,
                    service.resolve(otherTenant(), suspended.task().request().taskId(), 1, response()).code(),
                    "cross-tenant task IDs must reveal no existence");
            assertEquals(HumanTaskResult.Code.UNAUTHORIZED,
                    service.resolve(requester(), suspended.task().request().taskId(), 1, response()).code());
            assertEquals(HumanTaskResult.Code.STALE_GENERATION,
                    service.resolve(responder(), suspended.task().request().taskId(), 2, response()).code());

            HumanTaskResult resolved = service.resolve(responder(), suspended.task().request().taskId(),
                    1, response());
            assertEquals(HumanTaskResult.Code.RESOLVED, resolved.code());
            assertEquals(2, resolved.task().generation());
            assertEquals(HumanTaskStatus.RESOLVED, resolved.task().status());
            assertEquals(HumanTaskResult.Code.ALREADY_APPLIED,
                    service.resolve(responder(), suspended.task().request().taskId(), 1, response()).code());

            List<PendingWork> work = store.claimPendingWork(TENANT, "recovery", 20,
                    Duration.ofSeconds(30)).toCompletableFuture().join();
            assertEquals(1, work.stream().filter(PendingWork.HandlerTrigger.class::isInstance).count());
            var journal = store.readJournal(TENANT, 0, 100).toCompletableFuture().join();
            assertTrue(journal.stream().anyMatch(row -> "HUMAN_TASK_RESOLVED".equals(
                    row.envelope().eventType())));
            assertFalse(journal.stream().anyMatch(row -> new String(row.envelope().payload().bytes(),
                    StandardCharsets.UTF_8).contains("approved")),
                    "response values must never enter durable audit event payloads");
        }
    }

    @Test
    void responseContractAndRequesterCancellationAreEnforced() throws Exception {
        try (var store = sqlite("contract", Clock.fixed(NOW, ZoneOffset.UTC))) {
            Fixture fixture = running(store);
            var service = new HumanTaskService(store, Clock.fixed(NOW, ZoneOffset.UTC));
            ExecutionRecorder recorder = ExecutionRecorder.open(store, fixture.key, "worker",
                    Duration.ofSeconds(30), 1);
            HumanTaskResult suspended;
            try (recorder; var binding = service.bindLive(fixture.key, recorder)) {
                suspended = service.suspend(fixture.message(), definition());
            }
            OpaquePayload wrongSchema = OpaquePayload.of(PayloadEnvelope.of("wrong", "1",
                    PayloadValue.map(Map.of("decision", PayloadValue.of("approved")))).toJson()
                    .getBytes(StandardCharsets.UTF_8), CONTENT_TYPE);
            assertEquals(HumanTaskResult.Code.PAYLOAD_REFUSED,
                    service.resolve(responder(), suspended.task().request().taskId(), 1, wrongSchema).code());
            assertEquals(HumanTaskResult.Code.CANCELLED,
                    service.cancel(requester(), suspended.task().request().taskId(), 1).code(),
                    "the original requester may cancel without holding responder roles");
        }
    }

    @Test
    void recoveryAppliesEscalationAndExpiryUnderTheClaimFence() throws Exception {
        var clock = new MutableClock(NOW);
        try (var store = sqlite("recovery", clock)) {
            Fixture fixture = running(store);
            var service = new HumanTaskService(store, clock);
            ExecutionRecorder recorder = ExecutionRecorder.open(store, fixture.key, "worker",
                    Duration.ofSeconds(30), 1);
            UUID taskId;
            try (recorder; var binding = service.bindLive(fixture.key, recorder)) {
                taskId = service.suspend(fixture.message(), definition()).task().request().taskId();
            }
            var recovery = new ExecutionRecoveryService(store, List.of(TENANT), "timer-worker", 10,
                    Duration.ofSeconds(30), RepeatabilityDeclarations.NONE_DECLARED,
                    new HumanTaskHandlerDispatcher(store, service, HumanTaskContinuationExecutor.NONE));

            clock.now = NOW.plus(Duration.ofMinutes(5));
            assertTrue(recovery.sweepOnce().stream()
                    .anyMatch(RecoveryOutcome.HandlerDispatched.class::isInstance));
            assertEquals(HumanTaskStatus.ESCALATED,
                    store.loadHumanTask(TENANT, taskId).toCompletableFuture().join()
                            .orElseThrow().status());

            clock.now = NOW.plus(Duration.ofHours(1));
            assertTrue(recovery.sweepOnce().stream()
                    .anyMatch(RecoveryOutcome.HandlerDispatched.class::isInstance));
            assertEquals(HumanTaskStatus.EXPIRED,
                    store.loadHumanTask(TENANT, taskId).toCompletableFuture().join()
                            .orElseThrow().status());
            assertTrue(store.claimDueTimers(TENANT, "timer-worker", 10, Duration.ofSeconds(30))
                    .toCompletableFuture().join().isEmpty());
        }
    }

    @Test
    void aLateSweepExpiresInsteadOfBeingStrandedOnTheEarlierEscalationTimer() throws Exception {
        var clock = new MutableClock(NOW);
        try (var store = sqlite("late-sweep", clock)) {
            Fixture fixture = running(store);
            var service = new HumanTaskService(store, clock);
            UUID taskId;
            try (var recorder = ExecutionRecorder.open(store, fixture.key, "worker",
                    Duration.ofSeconds(30), 1); var binding = service.bindLive(fixture.key, recorder)) {
                taskId = service.suspend(fixture.message(), definition()).task().request().taskId();
            }
            var recovery = new ExecutionRecoveryService(store, List.of(TENANT), "late-timer-worker", 10,
                    Duration.ofSeconds(30), RepeatabilityDeclarations.NONE_DECLARED,
                    new HumanTaskHandlerDispatcher(store, service, HumanTaskContinuationExecutor.NONE));

            clock.now = NOW.plus(Duration.ofHours(2));
            recovery.sweepOnce();

            assertEquals(HumanTaskStatus.EXPIRED,
                    store.loadHumanTask(TENANT, taskId).toCompletableFuture().join()
                            .orElseThrow().status());
            assertTrue(store.claimDueTimers(TENANT, "late-timer-worker", 10, Duration.ofSeconds(30))
                    .toCompletableFuture().join().isEmpty());
        }
    }

    @Test
    void aDecisionArrivingAfterTheDeadlineAtomicallyWinsAsExpiry() throws Exception {
        var clock = new MutableClock(NOW);
        try (var store = sqlite("late-decision", clock)) {
            Fixture fixture = running(store);
            var service = new HumanTaskService(store, clock);
            HumanTaskResult task;
            try (var recorder = ExecutionRecorder.open(store, fixture.key, "worker",
                    Duration.ofSeconds(30), 1); var binding = service.bindLive(fixture.key, recorder)) {
                task = service.suspend(fixture.message(), definition());
            }

            clock.now = NOW.plus(Duration.ofHours(1));
            HumanTaskResult late = service.resolve(responder(), task.task().request().taskId(),
                    task.task().generation(), response());

            assertEquals(HumanTaskResult.Code.EXPIRED, late.code());
            assertEquals(HumanTaskStatus.EXPIRED, late.task().status());
        }
    }

    @Test
    void durableCapabilityIsRequiredForTheRestartSafeContract() {
        try (var store = new InMemoryExecutionStore(Clock.fixed(NOW, ZoneOffset.UTC))) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new HumanTaskService(store, Clock.fixed(NOW, ZoneOffset.UTC)));
            assertTrue(failure.getMessage().contains("durable human tasks"));
        }
    }

    @Test
    void terminalResolveRetriesMustExactlyMatchActorPayloadAndMediaType() throws Exception {
        try (var store = sqlite("exact-redelivery", Clock.fixed(NOW, ZoneOffset.UTC))) {
            Fixture fixture = running(store);
            var service = new HumanTaskService(store, Clock.fixed(NOW, ZoneOffset.UTC));
            HumanTaskResult suspended;
            try (var recorder = ExecutionRecorder.open(store, fixture.key, "worker",
                    Duration.ofSeconds(30), 1); var binding = service.bindLive(fixture.key, recorder)) {
                suspended = service.suspend(fixture.message(), definition());
            }
            UUID taskId = suspended.task().request().taskId();
            assertEquals(HumanTaskResult.Code.RESOLVED,
                    service.resolve(responder(), taskId, 1, response()).code());
            assertEquals(HumanTaskResult.Code.ALREADY_APPLIED,
                    service.resolve(responder(), taskId, 1, response()).code());

            assertEquals(HumanTaskResult.Code.ALREADY_SETTLED,
                    service.resolve(responder("another-responder"), taskId, 1, response()).code());
            OpaquePayload different = OpaquePayload.of(PayloadEnvelope.of("release.decision", "1",
                    PayloadValue.map(Map.of("decision", PayloadValue.of("rejected")))).toJson()
                    .getBytes(StandardCharsets.UTF_8), CONTENT_TYPE);
            assertEquals(HumanTaskResult.Code.ALREADY_SETTLED,
                    service.resolve(responder(), taskId, 1, different).code());
            assertEquals(HumanTaskResult.Code.PAYLOAD_REFUSED,
                    service.resolve(responder(), taskId, 1,
                            OpaquePayload.of("not-json".getBytes(StandardCharsets.UTF_8), CONTENT_TYPE)).code());
            assertEquals(HumanTaskResult.Code.PAYLOAD_REFUSED,
                    service.resolve(responder(), taskId, 1,
                            OpaquePayload.of(response().bytes(), "application/json")).code());
        }
    }

    private static HumanTaskDefinition definition() {
        return new HumanTaskDefinition(new HumanTaskMetadata("Review release", "Check the bounded facts."),
                new HumanTaskResponseSchema(CONTENT_TYPE, "release.decision", "1",
                        PayloadKind.MAP, 4096), HandlerAuthorization.ofRoles(Role.APPROVER.name()),
                Optional.of(Duration.ofMinutes(5)), Duration.ofHours(1),
                new HumanTaskReentryMapping("resolved", "denied", "expired", "cancelled"));
    }

    private static OpaquePayload response() {
        String json = PayloadEnvelope.of("release.decision", "1",
                PayloadValue.map(Map.of("decision", PayloadValue.of("approved")))).toJson();
        return OpaquePayload.of(json.getBytes(StandardCharsets.UTF_8), CONTENT_TYPE);
    }

    private SqliteExecutionStore sqlite(String name, Clock clock) {
        return new SqliteExecutionStore(directory.resolve(name + ".db"), clock);
    }

    private static Fixture running(ExecutionStore store) {
        ExecutionKey key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        NodeAttempt attempt = new NodeAttempt(attemptId, 1, NodeAttemptStatus.RUNNING);
        NodeInvocation invocation = new NodeInvocation(invocationId, "review", Set.of(),
                NodeInvocationStatus.RUNNING, List.of(attempt));
        Traversal traversal = new Traversal(traversalId, "review", TraversalStatus.RUNNING,
                Map.of(invocationId, invocation));
        store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(),
                        ProcessInstanceStatus.RUNNING, Map.of(traversalId, traversal)),
                        new GraphVersionPin("graph-v1"))).build()).toCompletableFuture().join();
        NodeMessage message = new NodeMessage(SecurityContext.of(requester()), key.processInstanceId(),
                traversalId, invocationId, attemptId, Set.of(), "review", Map.of("secret", "not copied"),
                Map.of(), ai.ravenroot.api.execution.NodeCommand.PROCESS);
        return new Fixture(key, traversalId, message);
    }

    private static RequestContext requester() {
        return new RequestContext("requester-call", "requester", PrincipalType.USER, "issuer", TENANT,
                Set.of(), Set.of());
    }

    private static RequestContext responder() {
        return responder("responder");
    }

    private static RequestContext responder(String subject) {
        return new RequestContext("responder-call", subject, PrincipalType.USER, "issuer", TENANT,
                Set.of(Role.APPROVER), Set.of());
    }

    private static RequestContext otherTenant() {
        return new RequestContext("other-call", "responder", PrincipalType.USER, "issuer", "other",
                Set.of(Role.APPROVER), Set.of());
    }

    private record Fixture(ExecutionKey key, UUID traversalId, NodeMessage message) { }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
