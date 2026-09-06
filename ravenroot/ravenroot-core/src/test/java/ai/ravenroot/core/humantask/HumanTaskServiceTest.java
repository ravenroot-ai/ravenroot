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
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.HandlerPayloadSchema;
import ai.ravenroot.api.persistence.HandlerRegistration;
import ai.ravenroot.api.persistence.HumanTaskAttentionLocator;
import ai.ravenroot.api.persistence.HumanTaskMetadata;
import ai.ravenroot.api.persistence.HumanTaskCommentRequirement;
import ai.ravenroot.api.persistence.HumanTaskConfirmationAction;
import ai.ravenroot.api.persistence.HumanTaskConfirmationPresentation;
import ai.ravenroot.api.persistence.HumanTaskExecutionLimits;
import ai.ravenroot.api.persistence.HumanTaskPolicy;
import ai.ravenroot.api.persistence.HumanTaskQuery;
import ai.ravenroot.api.persistence.HumanTaskReentryMapping;
import ai.ravenroot.api.persistence.HumanTaskRegistration;
import ai.ravenroot.api.persistence.HumanTaskResponseSchema;
import ai.ravenroot.api.persistence.HumanTaskStatus;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.ToolApprovalRegistration;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HumanTaskServiceTest {
    private static final String TENANT = "acme";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String CONTENT_TYPE = "application/vnd.ravenroot.payload+json";
    private static final HumanTaskPolicy STRICT_POLICY = new HumanTaskPolicy(
            100, 200, 10, 20, 30, 40,
            10, 20, 10, 2, 10,
            300, 5, 10,
            5, 6, 7, 8, 9, 2);

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
    void ambiguousActiveLabelsAreRejectedBeforeTaskOrTraversalMutation() throws Exception {
        try (var store = sqlite("ambiguous-labels", Clock.fixed(NOW, ZoneOffset.UTC))) {
            Fixture fixture = running(store);
            var service = new HumanTaskService(store, Clock.fixed(NOW, ZoneOffset.UTC));
            var presentation = new HumanTaskConfirmationPresentation(1, "Confirm this task.",
                    HumanTaskCommentRequirement.OPTIONAL,
                    List.of(HumanTaskConfirmationAction.RESOLVE, HumanTaskConfirmationAction.DENY),
                    "Proceed now", " ＰＲＯＣＥＥＤ\u00a0 NOW ", "");
            var definition = new HumanTaskDefinition(
                    new HumanTaskMetadata("Review release", "Check the bounded facts."),
                    new HumanTaskResponseSchema(HumanTaskConfirmationPresentation.RESPONSE_CONTENT_TYPE,
                            HumanTaskConfirmationPresentation.RESPONSE_SCHEMA,
                            HumanTaskConfirmationPresentation.RESPONSE_SCHEMA_VERSION,
                            PayloadKind.SCALAR, 4096),
                    HandlerAuthorization.ofRoles(Role.APPROVER.name()),
                    Optional.of(Duration.ofMinutes(5)), Duration.ofHours(1),
                    new HumanTaskReentryMapping("resolved", "denied", "expired", "cancelled"),
                    HumanTaskPolicy.DEFAULTS.executionLimits(4096), presentation);

            try (var recorder = ExecutionRecorder.open(store, fixture.key, "worker",
                    Duration.ofSeconds(30), 1);
                 var binding = service.bindLive(fixture.key, recorder)) {
                assertThrows(IllegalArgumentException.class,
                        () -> service.suspend(fixture.message(), definition));
            }

            assertTrue(service.inbox(requester(), HumanTaskQuery.everything(10)).items().isEmpty(),
                    "admission refusal must precede durable task creation");
            assertEquals(TraversalStatus.RUNNING,
                    store.load(fixture.key).toCompletableFuture().join().state()
                            .traversals().get(fixture.traversalId).status(),
                    "admission refusal must precede traversal suspension");
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
    void compositionRejectsAnIncompatibleHumanTaskResponseCapacityBeforeServing() throws Exception {
        try (var sqlite = sqlite("incompatible-response-capacity", Clock.fixed(NOW, ZoneOffset.UTC))) {
            ExecutionStore incompatible = (ExecutionStore) Proxy.newProxyInstance(
                    ExecutionStore.class.getClassLoader(), new Class<?>[]{ExecutionStore.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("maxHumanTaskResponsePayloadBytes")) {
                            return HumanTaskPolicy.DEFAULTS.maxResponseBytes() - 1;
                        }
                        try {
                            return method.invoke(sqlite, arguments);
                        } catch (InvocationTargetException wrapped) {
                            throw wrapped.getCause();
                        }
                    });

            var failure = assertThrows(IllegalArgumentException.class,
                    () -> new HumanTaskService(incompatible, Clock.fixed(NOW, ZoneOffset.UTC),
                            HumanTaskPolicy.DEFAULTS));
            assertEquals("human-task response policy exceeds the durable store capacity",
                    failure.getMessage());
            assertFalse(failure.getMessage().contains(directory.toString()));
        }
    }

    @Test
    void historicalInvalidSchemaLabelsStillListReadRefuseResolutionAndCancelAfterReopen()
            throws Exception {
        Path database = directory.resolve("legacy-schema.db");
        HistoricalTask schemaTask;
        HistoricalTask versionTask;
        try (var store = new SqliteExecutionStore(database, Clock.fixed(NOW, ZoneOffset.UTC))) {
            schemaTask = historicalTaskSkeleton(store);
            versionTask = historicalTaskSkeleton(store);
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            insertHistoricalTask(connection, schemaTask, "s".repeat(129), "1");
            insertHistoricalTask(connection, versionTask, "release.decision",
                    "historical version with spaces");
        }
        try (var reopened = new SqliteExecutionStore(database, Clock.fixed(NOW, ZoneOffset.UTC))) {
            var service = new HumanTaskService(reopened, Clock.fixed(NOW, ZoneOffset.UTC));
            var historicalSchema = reopened.loadHumanTask(TENANT, schemaTask.taskId())
                    .toCompletableFuture().join().orElseThrow();
            var historicalVersion = reopened.loadHumanTask(TENANT, versionTask.taskId())
                    .toCompletableFuture().join().orElseThrow();
            assertEquals(129, historicalSchema.request().responseSchema().schema().length());
            assertEquals("historical version with spaces",
                    historicalVersion.request().responseSchema().schemaVersion());
            assertEquals(Set.of(schemaTask.taskId(), versionTask.taskId()), service.inbox(requester(),
                            HumanTaskQuery.outstanding(10)).items().stream()
                    .map(task -> task.request().taskId()).collect(java.util.stream.Collectors.toSet()));
            assertEquals(HumanTaskResult.Code.PAYLOAD_REFUSED,
                    service.resolve(responder(), schemaTask.taskId(), 1, response()).code());
            assertEquals(HumanTaskResult.Code.PAYLOAD_REFUSED,
                    service.resolve(responder(), versionTask.taskId(), 1, response()).code());
            assertEquals(HumanTaskResult.Code.CANCELLED,
                    service.cancel(requester(), schemaTask.taskId(), 1).code());
            assertEquals(HumanTaskResult.Code.CANCELLED,
                    service.cancel(requester(), versionTask.taskId(), 1).code());
        }
    }

    @Test
    void historicalAmbiguousPresentationRemainsReadableAndCancellableAfterReopen()
            throws Exception {
        Path database = directory.resolve("historical-confirmation-labels.db");
        HistoricalTask task;
        try (var store = new SqliteExecutionStore(database, Clock.fixed(NOW, ZoneOffset.UTC))) {
            task = historicalTaskSkeleton(store);
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            insertHistoricalTask(connection, task, HumanTaskConfirmationPresentation.RESPONSE_SCHEMA,
                    HumanTaskConfirmationPresentation.RESPONSE_SCHEMA_VERSION);
            makeHistoricalEmbeddedConfirmation(connection, task);
        }
        try (var reopened = new SqliteExecutionStore(database, Clock.fixed(NOW, ZoneOffset.UTC))) {
            var service = new HumanTaskService(reopened, Clock.fixed(NOW, ZoneOffset.UTC));
            var stored = reopened.loadHumanTask(TENANT, task.taskId())
                    .toCompletableFuture().join().orElseThrow();
            assertEquals("\u00a0", stored.request().confirmationPresentation().prompt());
            assertEquals("Proceed", stored.request().confirmationPresentation().resolveLabel());
            assertEquals("proceed", stored.request().confirmationPresentation().cancelLabel());

            var attention = service.attention(requester(),
                    new HumanTaskAttentionLocator(task.taskId(), 1)).orElseThrow();
            assertEquals(List.of(HumanTaskConfirmationAction.CANCEL), attention.availableActions());
            assertEquals("Proceed", attention.presentation().resolveLabel());
            assertEquals("proceed", attention.presentation().cancelLabel());
            assertEquals(HumanTaskResult.Code.CANCELLED,
                    service.cancel(requester(), task.taskId(), 1).code(),
                    "current admission must not be reapplied to a historical task's cancellation path");
        }
    }

    @Test
    void serviceRefusesEveryCurrentPolicyDimensionBeforeWritingATask() throws Exception {
        try (var store = new SqliteExecutionStore(directory.resolve("service-policy.db"),
                Clock.fixed(NOW, ZoneOffset.UTC), STRICT_POLICY)) {
            Fixture fixture = running(store);
            var service = new HumanTaskService(store, Clock.fixed(NOW, ZoneOffset.UTC), STRICT_POLICY);
            try (var recorder = ExecutionRecorder.open(store, fixture.key, "worker",
                    Duration.ofSeconds(30), 1); var binding = service.bindLive(fixture.key, recorder)) {
                for (HumanTaskDefinition invalid : invalidDefinitions()) {
                    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                            () -> service.suspend(fixture.message(), invalid));
                    assertTrue(refused.getMessage().startsWith("human-task registration refused:"));
                    assertTrue(service.inbox(requester(), HumanTaskQuery.everything(10))
                            .items().isEmpty());
                }
            }
        }
    }

    @Test
    void durationBoundariesAndExactReplayUseOneAuthoritativeTimestamp() throws Exception {
        try (var store = new SqliteExecutionStore(directory.resolve("duration-boundary.db"),
                Clock.fixed(NOW, ZoneOffset.UTC), STRICT_POLICY)) {
            Fixture fixture = running(store);
            var service = new HumanTaskService(store, Clock.fixed(NOW, ZoneOffset.UTC), STRICT_POLICY);
            HumanTaskDefinition boundary = strictDefinition(new HumanTaskMetadata("Review", "Check"),
                    strictSchema("response", "1", 100), strictAuthorization(),
                    Optional.of(Duration.ofSeconds(20)), Duration.ofSeconds(40),
                    STRICT_POLICY.executionLimits(100));
            try (var recorder = ExecutionRecorder.open(store, fixture.key, "worker",
                    Duration.ofSeconds(30), 1); var binding = service.bindLive(fixture.key, recorder)) {
                assertEquals(HumanTaskResult.Code.CREATED,
                        service.suspend(fixture.message(), boundary).code());
                assertEquals(HumanTaskResult.Code.ALREADY_APPLIED,
                        service.suspend(fixture.message(), boundary).code());
                HumanTaskDefinition changed = strictDefinition(
                        new HumanTaskMetadata("Changed", "Check"), boundary.responseSchema(),
                        boundary.responderRequirements(), boundary.escalationDelay(),
                        boundary.expiryDelay(), boundary.executionLimits());
                assertEquals(HumanTaskResult.Code.ALREADY_SETTLED,
                        service.suspend(fixture.message(), changed).code());
            }
        }

        try (var store = new SqliteExecutionStore(directory.resolve("duration-overflow.db"),
                Clock.fixed(NOW, ZoneOffset.UTC), STRICT_POLICY)) {
            Fixture fixture = running(store);
            var service = new HumanTaskService(store, Clock.fixed(NOW, ZoneOffset.UTC), STRICT_POLICY);
            HumanTaskDefinition overflow = strictDefinition(new HumanTaskMetadata("Review", "Check"),
                    strictSchema("response", "1", 100), strictAuthorization(), Optional.empty(),
                    Duration.ofSeconds(Long.MAX_VALUE), STRICT_POLICY.executionLimits(100));
            try (var recorder = ExecutionRecorder.open(store, fixture.key, "worker",
                    Duration.ofSeconds(30), 1); var binding = service.bindLive(fixture.key, recorder)) {
                IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                        () -> service.suspend(fixture.message(), overflow));
                assertEquals("human-task registration refused: expiry is outside active policy",
                        refused.getMessage());
            }
        }
    }

    @Test
    void exactAdapterRedeliveryPrecedesATighterCurrentPolicyButChangedDedupConflicts() throws Exception {
        Path database = directory.resolve("policy-redelivery.db");
        HumanTaskRegistration accepted;
        ExecutionKey key;
        try (var store = new SqliteExecutionStore(database, Clock.fixed(NOW, ZoneOffset.UTC))) {
            Fixture fixture = running(store);
            key = fixture.key();
            var service = new HumanTaskService(store, Clock.fixed(NOW, ZoneOffset.UTC));
            try (var recorder = ExecutionRecorder.open(store, key, "worker",
                    Duration.ofSeconds(30), 1); var binding = service.bindLive(key, recorder)) {
                accepted = service.suspend(fixture.message(), definition()).task().request();
            }
        }
        HumanTaskPolicy defaults = HumanTaskPolicy.DEFAULTS;
        HumanTaskPolicy tighter = new HumanTaskPolicy(defaults.defaultResponseBytes(),
                defaults.maxResponseBytes(), defaults.defaultEscalationSeconds(),
                defaults.maxEscalationSeconds(), defaults.defaultExpirySeconds(),
                defaults.maxExpirySeconds(), 5, defaults.maxDescriptionUtf8Bytes(),
                defaults.maxResponseSchemaUtf8Bytes(), defaults.maxAuthorizationTokens(),
                defaults.maxAuthorizationTokenUtf8Bytes(), defaults.decisionBodyMaxBytes(),
                defaults.inboxDefaultPageSize(), defaults.inboxMaxPageSize(),
                defaults.responseMaxDepth(), defaults.responseMaxCollectionSize(),
                defaults.responseMaxValueCount(), defaults.responseMaxTextLength(),
                defaults.responseMaxKeyLength(), defaults.writeAttempts());
        try (var reopened = new SqliteExecutionStore(database, Clock.fixed(NOW, ZoneOffset.UTC), tighter)) {
            long revision = reopened.load(key).toCompletableFuture().join().revision();
            assertDoesNotThrow(() -> reopened.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(revision))
                    .registerHumanTask(accepted).build()).toCompletableFuture().join());
            assertEquals(1, reopened.listHumanTasks(TENANT, HumanTaskQuery.everything(10))
                    .toCompletableFuture().join().items().size());

            HumanTaskRegistration changed = copyRegistration(accepted,
                    new HumanTaskMetadata("Other title", accepted.metadata().description()));
            long afterReplay = reopened.load(key).toCompletableFuture().join().revision();
            var failure = assertThrows(java.util.concurrent.CompletionException.class,
                    () -> reopened.apply(ExecutionBatch.to(key)
                            .expecting(RevisionExpectation.exactly(afterReplay))
                            .registerHumanTask(changed).build()).toCompletableFuture().join());
            ExecutionStoreException storeFailure = assertInstanceOf(ExecutionStoreException.class,
                    failure.getCause());
            assertInstanceOf(ai.ravenroot.api.persistence.ExecutionStoreFailure.InvalidRequest.class,
                    storeFailure.failure());
            assertTrue(storeFailure.getMessage().contains("deduplication key"));
            var service = new HumanTaskService(reopened, Clock.fixed(NOW, ZoneOffset.UTC), tighter);
            assertEquals(HumanTaskResult.Code.CANCELLED,
                    service.cancel(requester(), accepted.taskId(), 1).code(),
                    "policy A tasks must remain cancellable after reopening under tighter policy B");
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

    private static HistoricalTask historicalTaskSkeleton(SqliteExecutionStore store) {
        Fixture fixture = running(store);
        UUID taskId = UUID.randomUUID();
        String correlation = taskId.toString();
        String deduplication = "human-task:" + fixture.message().attemptId();
        var handler = new HandlerRegistration(taskId, "human-task", fixture.traversalId(),
                fixture.message().invocationId(), correlation, deduplication,
                new HandlerPayloadSchema(CONTENT_TYPE, "release.decision", 4096),
                HandlerAuthorization.ofRoles(Role.APPROVER.name()));
        long revision = store.load(fixture.key()).toCompletableFuture().join().revision();
        long waitingRevision = store.apply(ExecutionBatch.to(fixture.key())
                .expecting(RevisionExpectation.exactly(revision))
                .apply(new ExecutionTransition.AttemptTransitioned(fixture.traversalId(),
                        fixture.message().invocationId(), fixture.message().attemptId(),
                        NodeAttemptStatus.WAITING))
                .apply(new ExecutionTransition.InvocationTransitioned(fixture.traversalId(),
                        fixture.message().invocationId(), NodeInvocationStatus.WAITING))
                .apply(new ExecutionTransition.TraversalTransitioned(fixture.traversalId(),
                        TraversalStatus.WAITING))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.WAITING))
                .registerHandler(handler).build()).toCompletableFuture().join().revision();
        return new HistoricalTask(fixture.key(), fixture.traversalId(),
                fixture.message().invocationId(), fixture.message().attemptId(), taskId,
                correlation, deduplication, waitingRevision);
    }

    private static void insertHistoricalTask(java.sql.Connection connection, HistoricalTask task,
                                             String schema, String schemaVersion) throws Exception {
        String columns = "tenant_id, process_instance_id, task_id, traversal_id, invocation_id, "
                + "attempt_id, node_id, correlation_key, deduplication_key, title, description, "
                + "response_content_type, response_schema, response_schema_version, response_kind, "
                + "response_max_bytes, required_roles, required_scopes, requester_request_id, "
                + "requester_subject, requester_principal_type, requester_issuer, graph_version_pin, "
                + "escalate_at_epoch_second, escalate_at_nano, expires_at_epoch_second, expires_at_nano, "
                + "resolved_outcome, denied_outcome, expired_outcome, cancelled_outcome, "
                + "decision_body_max_bytes, response_max_depth, response_max_collection_size, "
                + "response_max_value_count, response_max_text_length, response_max_key_length, "
                + "write_attempts, continuation_version, continuation, continuation_digest, status, "
                + "actor, generation, revision";
        try (var statement = connection.prepareStatement(
                "INSERT INTO human_task (" + columns + ") VALUES (" + "?,".repeat(44) + "?)")) {
            int index = 1;
            statement.setString(index++, task.key().tenantId());
            statement.setString(index++, task.key().processInstanceId().toString());
            statement.setString(index++, task.taskId().toString());
            statement.setString(index++, task.traversalId().toString());
            statement.setString(index++, task.invocationId().toString());
            statement.setString(index++, task.attemptId().toString());
            statement.setString(index++, "review");
            statement.setString(index++, task.correlationKey());
            statement.setString(index++, task.deduplicationKey());
            statement.setString(index++, "Historical review");
            statement.setString(index++, "Persisted before current label admission.");
            statement.setString(index++, CONTENT_TYPE);
            statement.setString(index++, schema);
            statement.setString(index++, schemaVersion);
            statement.setString(index++, PayloadKind.MAP.name());
            statement.setInt(index++, 4096);
            statement.setString(index++, Role.APPROVER.name());
            statement.setString(index++, "");
            statement.setString(index++, "requester-call");
            statement.setString(index++, "requester");
            statement.setString(index++, PrincipalType.USER.name());
            statement.setString(index++, "issuer");
            statement.setString(index++, "graph-v1");
            statement.setNull(index++, java.sql.Types.BIGINT);
            statement.setNull(index++, java.sql.Types.INTEGER);
            Instant expiry = NOW.plus(Duration.ofHours(1));
            statement.setLong(index++, expiry.getEpochSecond());
            statement.setInt(index++, expiry.getNano());
            statement.setString(index++, "resolved");
            statement.setString(index++, "denied");
            statement.setString(index++, "expired");
            statement.setString(index++, "cancelled");
            HumanTaskExecutionLimits limits = HumanTaskPolicy.DEFAULTS.executionLimits(4096);
            statement.setInt(index++, limits.decisionBodyMaxBytes());
            statement.setInt(index++, limits.responsePayload().maxDepth());
            statement.setInt(index++, limits.responsePayload().maxCollectionSize());
            statement.setInt(index++, limits.responsePayload().maxValueCount());
            statement.setInt(index++, limits.responsePayload().maxTextLength());
            statement.setInt(index++, limits.responsePayload().maxKeyLength());
            statement.setInt(index++, limits.writeAttempts());
            byte[] continuation = new byte[0];
            statement.setInt(index++, 1);
            statement.setBytes(index++, continuation);
            statement.setString(index++, ToolApprovalRegistration.digest(continuation));
            statement.setString(index++, HumanTaskStatus.WAITING.name());
            statement.setString(index++, "");
            statement.setLong(index++, 1);
            statement.setLong(index, task.revision());
            assertEquals(1, statement.executeUpdate(),
                    "the historical row must be inserted without current HumanTask admission");
        }
    }

    private static void makeHistoricalEmbeddedConfirmation(
            java.sql.Connection connection, HistoricalTask task) throws Exception {
        try (var statement = connection.prepareStatement("""
                UPDATE human_task
                   SET response_content_type = ?, response_schema = ?, response_schema_version = ?,
                       response_kind = 'SCALAR', confirmation_version = 1,
                       confirmation_prompt = ?, confirmation_comment_requirement = 'OPTIONAL',
                       confirmation_actions = 'RESOLVE,CANCEL', confirmation_resolve_label = ?,
                       confirmation_deny_label = '', confirmation_cancel_label = ?,
                       confirmation_max_prompt_bytes = 64,
                       confirmation_max_action_label_bytes = 64,
                       confirmation_max_comment_bytes = 4096
                 WHERE tenant_id = ? AND task_id = ?
                """)) {
            statement.setString(1, HumanTaskConfirmationPresentation.RESPONSE_CONTENT_TYPE);
            statement.setString(2, HumanTaskConfirmationPresentation.RESPONSE_SCHEMA);
            statement.setString(3, HumanTaskConfirmationPresentation.RESPONSE_SCHEMA_VERSION);
            statement.setString(4, "\u00a0");
            statement.setString(5, "Proceed");
            statement.setString(6, "proceed");
            statement.setString(7, task.key().tenantId());
            statement.setString(8, task.taskId().toString());
            assertEquals(1, statement.executeUpdate(),
                    "the historical fixture must bypass only current admission");
        }
    }

    private static List<HumanTaskDefinition> invalidDefinitions() {
        HumanTaskMetadata metadata = new HumanTaskMetadata("Review", "Check");
        HumanTaskResponseSchema schema = strictSchema("response", "1", 100);
        HandlerAuthorization authorization = strictAuthorization();
        Optional<Duration> escalation = Optional.of(Duration.ofSeconds(10));
        Duration expiry = Duration.ofSeconds(30);
        HumanTaskExecutionLimits limits = STRICT_POLICY.executionLimits(100);
        var invalid = new ArrayList<HumanTaskDefinition>();
        invalid.add(strictDefinition(new HumanTaskMetadata("x".repeat(11), "Check"), schema,
                authorization, escalation, expiry, limits));
        invalid.add(strictDefinition(new HumanTaskMetadata("Review", "x".repeat(21)), schema,
                authorization, escalation, expiry, limits));
        invalid.add(strictDefinition(metadata, strictSchema("response", "1", 201), authorization,
                escalation, expiry, strictLimits(201, 5, 6, 7, 8, 9, 300, 2)));
        invalid.add(strictDefinition(metadata, strictSchema("abcdefghijk", "1", 100), authorization,
                escalation, expiry, limits));
        invalid.add(strictDefinition(metadata, strictSchema("bad schema", "1", 100), authorization,
                escalation, expiry, limits));
        invalid.add(strictDefinition(metadata, strictSchema("response", "bad version", 100),
                authorization, escalation, expiry, limits));
        invalid.add(strictDefinition(metadata, strictSchema("response", "v".repeat(129), 100),
                authorization, escalation, expiry, limits));
        invalid.add(strictDefinition(metadata, schema,
                new HandlerAuthorization(Set.of("a", "b", "c"), Set.of("scope")),
                escalation, expiry, limits));
        invalid.add(strictDefinition(metadata, schema,
                new HandlerAuthorization(Set.of("role"), Set.of("a", "b", "c")),
                escalation, expiry, limits));
        invalid.add(strictDefinition(metadata, schema,
                new HandlerAuthorization(Set.of("x".repeat(11)), Set.of("scope")),
                escalation, expiry, limits));
        invalid.add(strictDefinition(metadata, schema,
                new HandlerAuthorization(Set.of("role"), Set.of("x".repeat(11))),
                escalation, expiry, limits));
        invalid.add(strictDefinition(metadata, schema, authorization, escalation,
                Duration.ofSeconds(41), limits));
        invalid.add(strictDefinition(metadata, schema, authorization,
                Optional.of(Duration.ofSeconds(21)), expiry, limits));
        invalid.add(strictDefinition(metadata, schema, authorization, escalation, expiry,
                strictLimits(100, 6, 6, 7, 8, 9, 300, 2)));
        invalid.add(strictDefinition(metadata, schema, authorization, escalation, expiry,
                strictLimits(100, 5, 7, 7, 8, 9, 300, 2)));
        invalid.add(strictDefinition(metadata, schema, authorization, escalation, expiry,
                strictLimits(100, 5, 6, 8, 8, 9, 300, 2)));
        invalid.add(strictDefinition(metadata, schema, authorization, escalation, expiry,
                strictLimits(100, 5, 6, 7, 9, 9, 300, 2)));
        invalid.add(strictDefinition(metadata, schema, authorization, escalation, expiry,
                strictLimits(100, 5, 6, 7, 8, 10, 300, 2)));
        invalid.add(strictDefinition(metadata, schema, authorization, escalation, expiry,
                strictLimits(100, 5, 6, 7, 8, 9, 301, 2)));
        invalid.add(strictDefinition(metadata, schema, authorization, escalation, expiry,
                strictLimits(100, 5, 6, 7, 8, 9, 300, 3)));
        return invalid;
    }

    private static HumanTaskDefinition strictDefinition(HumanTaskMetadata metadata,
                                                        HumanTaskResponseSchema schema,
                                                        HandlerAuthorization authorization,
                                                        Optional<Duration> escalation,
                                                        Duration expiry,
                                                        HumanTaskExecutionLimits limits) {
        return new HumanTaskDefinition(metadata, schema, authorization, escalation, expiry,
                new HumanTaskReentryMapping("resolved", "denied", "expired", "cancelled"), limits);
    }

    private static HumanTaskResponseSchema strictSchema(String schema, String version, int maxBytes) {
        return new HumanTaskResponseSchema(CONTENT_TYPE, schema, version, PayloadKind.MAP, maxBytes);
    }

    private static HandlerAuthorization strictAuthorization() {
        return new HandlerAuthorization(Set.of("role"), Set.of("scope"));
    }

    private static HumanTaskExecutionLimits strictLimits(int bytes, int depth, int collection,
                                                         int values, int text, int key, int body,
                                                         int attempts) {
        return new HumanTaskExecutionLimits(
                new PayloadLimits(bytes, depth, collection, values, text, key), body, attempts);
    }

    private static HumanTaskRegistration copyRegistration(HumanTaskRegistration source,
                                                          HumanTaskMetadata metadata) {
        return new HumanTaskRegistration(source.taskId(), source.traversalId(), source.invocationId(),
                source.attemptId(), source.nodeId(), source.correlationKey(), source.deduplicationKey(),
                metadata, source.responseSchema(), source.responderRequirements(), source.requester(),
                source.graphVersionPin(), source.escalateAt(), source.expiresAt(),
                source.reentryMapping(), source.executionLimits(), source.continuationVersion(),
                source.continuation(), source.continuationDigest());
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

    private record HistoricalTask(ExecutionKey key, UUID traversalId, UUID invocationId,
                                  UUID attemptId, UUID taskId, String correlationKey,
                                  String deduplicationKey, long revision) { }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
