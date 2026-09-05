package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.AgentAuthorityControlState;
import ai.ravenroot.api.persistence.AgentAuthorityRootRegistration;
import ai.ravenroot.api.persistence.AgentBudgetOperation;
import ai.ravenroot.api.persistence.AgentBudgetVector;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionPauseRegistration;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.HandlerPayloadSchema;
import ai.ravenroot.api.persistence.HandlerRegistration;
import ai.ravenroot.api.persistence.HumanTaskMetadata;
import ai.ravenroot.api.persistence.HumanTaskQuery;
import ai.ravenroot.api.persistence.HumanTaskReentryMapping;
import ai.ravenroot.api.persistence.HumanTaskRegistration;
import ai.ravenroot.api.persistence.HumanTaskResponseSchema;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.ProcessInventoryQuery;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.persistence.TimerSchedule;
import ai.ravenroot.api.persistence.ToolApprovalRegistration;
import ai.ravenroot.api.payload.PayloadKind;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Adapter-level checks for malformed and relationally corrupt persisted UUIDs. */
class SqliteUuidCorruptionTest {

    private static final Instant EPOCH = Instant.parse("2026-09-01T00:00:00Z");
    private static final Duration LEASE_TTL = Duration.ofMinutes(1);
    private static final String HOSTILE = "malformed-uuid credential=never-log";

    @TempDir
    Path databaseDirectory;

    @ParameterizedTest(name = "{0}")
    @MethodSource("productionReadPaths")
    void everyProductionReadFamilyDecodesTheExactPersistedColumn(
            String name, ReadFamily family, String table, String column) throws Exception {
        Path file = databaseDirectory.resolve(name.replace(' ', '-') + ".db");
        var clock = new MutableClock(EPOCH);
        try (var store = new SqliteExecutionStore(file, clock)) {
            ReaderFixture fixture = seedReaderFixture(store, family);
            tamperReadPath(file, family, table, column);

            ExecutionStoreException thrown = storeFailure(() -> readFamily(store, fixture, family));
            var corrupted = assertInstanceOf(ExecutionStoreFailure.Corrupted.class, thrown.failure(), name);
            boolean hiddenByPendingAttemptJoin = family == ReadFamily.PENDING_ATTEMPT
                    && (table.equals("traversal")
                            || table.equals("invocation") && column.equals("invocation_id"));
            String problem = hiddenByPendingAttemptJoin
                    ? " is missing" : " is not a canonical UUID";
            assertEquals("stored " + table + "." + column + problem,
                    corrupted.reason(), name);
            assertFalse(thrown.getMessage().contains(HOSTILE), name);
            assertFalse(causeChain(thrown).stream()
                    .anyMatch(cause -> cause instanceof IllegalArgumentException
                            || String.valueOf(cause.getMessage()).contains(HOSTILE)), name);
        }
    }

    @Test
    void nullableUuidColumnsRemainAbsentThroughTheirProductionReaders() {
        Path file = databaseDirectory.resolve("nullable.db");
        var clock = new MutableClock(EPOCH);
        try (var store = new SqliteExecutionStore(file, clock)) {
            ReaderFixture timer = seedReaderFixture(store, ReadFamily.NULLABLE_TIMER);
            var due = await(store.claimDueTimers(timer.key.tenantId(), "timer-worker", 10, LEASE_TTL));
            var timerWork = assertInstanceOf(ai.ravenroot.api.persistence.PendingWork.TimerDue.class,
                    due.getFirst());
            assertEquals(null, timerWork.traversalId());
            assertEquals(null, timerWork.invocationId());

            ReaderFixture handler = seedReaderFixture(store, ReadFamily.HANDLER);
            assertEquals(null, await(store.handlers(handler.key)).getFirst().resumeTraversalId());

            ReaderFixture journal = seedReaderFixture(store, ReadFamily.NULLABLE_JOURNAL);
            var event = await(store.readJournal(journal.key.tenantId(), 0, 100)).stream()
                    .map(record -> record.envelope())
                    .filter(envelope -> envelope.processInstanceId().equals(journal.key.processInstanceId()))
                    .findFirst().orElseThrow();
            assertEquals(null, event.invocationId());
            assertEquals(null, event.attemptId());
            assertEquals(null, event.causationId());
        }
    }

    @ParameterizedTest
    @EnumSource(RelationalCorruption.class)
    void canonicalButUnknownChildRelationshipsFailAggregateReconstruction(
            RelationalCorruption corruption) throws Exception {
        Path file = databaseDirectory.resolve(corruption.name().toLowerCase() + ".db");
        ExecutionKey key = new ExecutionKey("acme", UUID.randomUUID());
        try (var store = new SqliteExecutionStore(file, new MutableClock(EPOCH))) {
            seedRunningAttempt(store, key);
            corruption.apply(file, key);

            ExecutionStoreFailure.Corrupted failure = corrupted(
                    () -> await(store.load(key)));
            assertEquals(key, failure.key());
            assertEquals("stored " + corruption.table + "." + corruption.column
                    + " does not reference stored state in this process", failure.reason());
        }
    }

    @Test
    void claimCannotHideAnInvocationWhoseTraversalReferenceIsCorruptAndRollsBack() throws Exception {
        Path file = databaseDirectory.resolve("claim.db");
        ExecutionKey key = new ExecutionKey("acme", UUID.randomUUID());
        try (var store = new SqliteExecutionStore(file, new MutableClock(EPOCH))) {
            Seed seed = seedRunningAttempt(store, key);
            execute(file, "UPDATE invocation SET traversal_id = ? WHERE tenant_id = ? "
                            + "AND process_instance_id = ? AND invocation_id = ?",
                    UUID.randomUUID().toString(), key.tenantId(), key.processInstanceId().toString(),
                    seed.childInvocationId.toString());

            ExecutionStoreFailure.Corrupted failure = corrupted(() -> await(
                    store.claimPendingWork(key.tenantId(), "worker", 1, LEASE_TTL)));
            assertEquals("stored traversal.traversal_id is missing", failure.reason(),
                    "a LEFT JOIN must expose the missing referenced traversal instead of silently "
                            + "hiding outstanding work");
            assertTrue(await(store.leases(key.tenantId())).isEmpty(),
                    "the failed claim transaction must not leave a lease");
            assertEquals(0, count(file, "SELECT COUNT(*) FROM work_claim"),
                    "the failed claim transaction must not leave a delivery receipt");
        }
    }

    @Test
    void claimCannotHideAnAttemptWhoseInvocationReferenceIsCorruptAndRollsBack() throws Exception {
        Path file = databaseDirectory.resolve("claim-attempt-owner.db");
        ExecutionKey key = new ExecutionKey("acme", UUID.randomUUID());
        try (var store = new SqliteExecutionStore(file, new MutableClock(EPOCH))) {
            Seed seed = seedRunningAttempt(store, key);
            execute(file, "UPDATE attempt SET invocation_id = ? WHERE tenant_id = ? "
                            + "AND process_instance_id = ? AND attempt_id = ?",
                    UUID.randomUUID().toString(), key.tenantId(), key.processInstanceId().toString(),
                    seed.attemptId.toString());

            ExecutionStoreFailure.Corrupted failure = corrupted(() -> await(
                    store.claimPendingWork(key.tenantId(), "worker", 1, LEASE_TTL)));
            assertEquals("stored invocation.invocation_id is missing", failure.reason(),
                    "the attempt must remain visible through the LEFT JOIN even when its owner is gone");
            assertTrue(await(store.leases(key.tenantId())).isEmpty());
            assertEquals(0, count(file, "SELECT COUNT(*) FROM work_claim"));
        }
    }

    @Test
    void leaseListingCannotHideACanonicalButUnknownProcessReference() throws Exception {
        Path file = databaseDirectory.resolve("lease-process-owner.db");
        var clock = new MutableClock(EPOCH);
        ExecutionKey key = new ExecutionKey("acme", UUID.randomUUID());
        try (var store = new SqliteExecutionStore(file, clock)) {
            UUID traversalId = UUID.randomUUID();
            await(store.apply(Fixtures.creationBatch(key, traversalId)));
            await(store.claim(key, "lease-worker", LEASE_TTL));
            execute(file, "UPDATE lease SET process_instance_id = ? WHERE tenant_id = ?",
                    UUID.randomUUID().toString(), key.tenantId());

            ExecutionStoreFailure.Corrupted failure = corrupted(
                    () -> await(store.leases(key.tenantId())));
            assertEquals("stored process_instance.process_instance_id is missing", failure.reason(),
                    "the lease must remain visible through the LEFT JOIN when its owner is absent");
            assertTrue(await(store.findProcessInstance(key)).isPresent(),
                    "diagnosing the orphan lease must not mutate its former process");
        }
    }

    @Test
    void malformedTenantWideIdentityUsesOnlyTheRedactedDiagnosticSentinelAndCannotCrossTenants()
            throws Exception {
        Path file = databaseDirectory.resolve("tenant-isolation.db");
        var clock = new MutableClock(EPOCH);
        ExecutionKey damaged = new ExecutionKey("acme", UUID.randomUUID());
        ExecutionKey other = new ExecutionKey("globex", UUID.randomUUID());
        ExecutionKey legitimateZero = new ExecutionKey("globex", new UUID(0L, 0L));

        try (var store = new SqliteExecutionStore(file, clock)) {
            await(store.apply(Fixtures.creationBatch(damaged, UUID.randomUUID())));
            await(store.apply(Fixtures.creationBatch(other, UUID.randomUUID())));
            await(store.apply(Fixtures.creationBatch(legitimateZero, UUID.randomUUID())));
            execute(file, "UPDATE process_instance SET process_instance_id = ? WHERE tenant_id = ? "
                            + "AND process_instance_id = ?",
                    HOSTILE, damaged.tenantId(), damaged.processInstanceId().toString());

            var globex = await(store.listProcessInstances("globex",
                    ProcessInventoryQuery.everything(10)));
            assertEquals(Set.of(other.processInstanceId(), legitimateZero.processInstanceId()),
                    globex.items().stream().map(item -> item.key().processInstanceId())
                            .collect(java.util.stream.Collectors.toSet()),
                    "a corrupt row in another tenant cannot alter or escape into this tenant's page");
            assertTrue(await(store.findProcessInstance(other)).isPresent());
            assertTrue(await(store.findProcessInstance(legitimateZero)).isPresent(),
                    "the all-zero UUID is valid row data; only a failed tenant-wide decode uses it "
                            + "as a diagnostic placeholder");

            ExecutionStoreException thrown = storeFailure(() -> await(store.listProcessInstances(
                    damaged.tenantId(), ProcessInventoryQuery.everything(10))));
            var corrupted = assertInstanceOf(ExecutionStoreFailure.Corrupted.class, thrown.failure());
            assertEquals(new UUID(0L, 0L), corrupted.key().processInstanceId());
            assertEquals(damaged.tenantId(), corrupted.key().tenantId());
            assertEquals("stored process_instance.process_instance_id is not a canonical UUID",
                    corrupted.reason());
            assertFalse(thrown.getMessage().contains(HOSTILE));
            assertFalse(causeChain(thrown).stream()
                    .anyMatch(cause -> cause instanceof IllegalArgumentException
                            || String.valueOf(cause.getMessage()).contains(HOSTILE)));

            assertTrue(await(store.findProcessInstance(other)).isPresent(),
                    "diagnosing the damaged tenant must not mutate a healthy tenant");
        }
    }

    private static Stream<Arguments> productionReadPaths() {
        return Stream.of(
                readPath("aggregate traversal id", ReadFamily.AGGREGATE, "traversal", "traversal_id"),
                readPath("aggregate invocation traversal", ReadFamily.AGGREGATE,
                        "invocation", "traversal_id"),
                readPath("aggregate invocation id", ReadFamily.AGGREGATE,
                        "invocation", "invocation_id"),
                readPath("aggregate parent child", ReadFamily.AGGREGATE,
                        "invocation_parent", "invocation_id"),
                readPath("aggregate parent id", ReadFamily.AGGREGATE,
                        "invocation_parent", "parent_invocation_id"),
                readPath("aggregate attempt owner", ReadFamily.AGGREGATE,
                        "attempt", "invocation_id"),
                readPath("aggregate attempt id", ReadFamily.AGGREGATE, "attempt", "attempt_id"),
                readPath("lease process id", ReadFamily.LEASE, "lease", "process_instance_id"),
                readPath("process inventory id", ReadFamily.PROCESS_INVENTORY,
                        "process_instance", "process_instance_id"),
                readPath("traversal inventory id", ReadFamily.TRAVERSAL_INVENTORY,
                        "traversal", "traversal_id"),
                readPath("pending invocation traversal", ReadFamily.PENDING_ATTEMPT,
                        "invocation", "traversal_id"),
                readPath("pending joined invocation", ReadFamily.PENDING_ATTEMPT,
                        "invocation", "invocation_id"),
                readPath("pending joined traversal", ReadFamily.PENDING_ATTEMPT,
                        "traversal", "traversal_id"),
                readPath("pending attempt owner", ReadFamily.PENDING_ATTEMPT,
                        "attempt", "invocation_id"),
                readPath("pending attempt id", ReadFamily.PENDING_ATTEMPT,
                        "attempt", "attempt_id"),
                readPath("timer id", ReadFamily.TIMER, "timer", "timer_id"),
                readPath("timer traversal", ReadFamily.TIMER, "timer", "traversal_id"),
                readPath("timer invocation", ReadFamily.TIMER, "timer", "invocation_id"),
                readPath("handler process", ReadFamily.HANDLER_BY_CORRELATION,
                        "execution_handler", "process_instance_id"),
                readPath("handler id", ReadFamily.HANDLER, "execution_handler", "handler_id"),
                readPath("handler traversal", ReadFamily.HANDLER,
                        "execution_handler", "traversal_id"),
                readPath("handler invocation", ReadFamily.HANDLER,
                        "execution_handler", "invocation_id"),
                readPath("handler resume traversal", ReadFamily.HANDLER,
                        "execution_handler", "resume_traversal_id"),
                readPath("global authority budget process", ReadFamily.GLOBAL_AUTHORITY,
                        "agent_authority_budget", "process_instance_id"),
                readPath("approval id", ReadFamily.APPROVAL, "tool_approval", "approval_id"),
                readPath("approval traversal", ReadFamily.APPROVAL, "tool_approval", "traversal_id"),
                readPath("approval invocation", ReadFamily.APPROVAL,
                        "tool_approval", "invocation_id"),
                readPath("approval attempt", ReadFamily.APPROVAL, "tool_approval", "attempt_id"),
                readPath("approval call", ReadFamily.APPROVAL, "tool_approval", "call_id"),
                readPath("pause process", ReadFamily.PAUSE_BY_TRAVERSAL,
                        "execution_pause", "process_instance_id"),
                readPath("pause id", ReadFamily.PAUSE, "execution_pause", "pause_id"),
                readPath("pause traversal", ReadFamily.PAUSE, "execution_pause", "traversal_id"),
                readPath("pause invocation", ReadFamily.PAUSE,
                        "execution_pause", "after_invocation_id"),
                readPath("human task process", ReadFamily.HUMAN_TASK,
                        "human_task", "process_instance_id"),
                readPath("human task id", ReadFamily.HUMAN_TASK, "human_task", "task_id"),
                readPath("human task traversal", ReadFamily.HUMAN_TASK,
                        "human_task", "traversal_id"),
                readPath("human task invocation", ReadFamily.HUMAN_TASK,
                        "human_task", "invocation_id"),
                readPath("human task attempt", ReadFamily.HUMAN_TASK,
                        "human_task", "attempt_id"),
                readPath("journal process", ReadFamily.JOURNAL,
                        "event_journal", "process_instance_id"),
                readPath("journal event", ReadFamily.JOURNAL, "event_journal", "event_id"),
                readPath("journal traversal", ReadFamily.JOURNAL,
                        "event_journal", "traversal_id"),
                readPath("journal invocation", ReadFamily.JOURNAL,
                        "event_journal", "invocation_id"),
                readPath("journal attempt", ReadFamily.JOURNAL, "event_journal", "attempt_id"),
                readPath("journal causation", ReadFamily.JOURNAL,
                        "event_journal", "causation_id"));
    }

    private static Arguments readPath(String name, ReadFamily family, String table, String column) {
        return Arguments.of(name, family, table, column);
    }

    private static void tamperReadPath(Path file, ReadFamily family, String table, String column)
            throws Exception {
        if (family == ReadFamily.PENDING_ATTEMPT && table.equals("invocation")) {
            execute(file, "UPDATE invocation SET " + column + " = ? WHERE invocation_id = "
                            + "(SELECT invocation_id FROM attempt LIMIT 1)", HOSTILE);
            return;
        }
        execute(file, "UPDATE " + table + " SET " + column
                        + " = ? WHERE rowid = (SELECT rowid FROM " + table + " LIMIT 1)",
                HOSTILE);
    }

    private static ReaderFixture seedReaderFixture(SqliteExecutionStore store, ReadFamily family) {
        ExecutionKey key = new ExecutionKey("tenant-" + family.name().toLowerCase(), UUID.randomUUID());
        if (family == ReadFamily.LEASE) {
            UUID traversalId = UUID.randomUUID();
            await(store.apply(Fixtures.creationBatch(key, traversalId)));
            await(store.claim(key, "lease-worker", LEASE_TTL));
            return new ReaderFixture(key, traversalId, null, null, null);
        }

        Seed seed = seedRunningAttempt(store, key);
        ReaderFixture fixture = new ReaderFixture(key, seed.traversalId, seed.childInvocationId,
                seed.attemptId, UUID.randomUUID());
        return switch (family) {
            case AGGREGATE, PROCESS_INVENTORY, TRAVERSAL_INVENTORY, PENDING_ATTEMPT -> fixture;
            case TIMER -> scheduleTimer(store, fixture, true);
            case NULLABLE_TIMER -> scheduleTimer(store, fixture, false);
            case HANDLER, HANDLER_BY_CORRELATION -> registerHandler(store, fixture);
            case GLOBAL_AUTHORITY -> registerAuthorityBudget(store, fixture);
            case APPROVAL -> registerApproval(store, fixture);
            case PAUSE, PAUSE_BY_TRAVERSAL -> registerPause(store, fixture);
            case HUMAN_TASK -> registerHumanTask(store, fixture);
            case JOURNAL -> appendJournal(store, fixture, true);
            case NULLABLE_JOURNAL -> appendJournal(store, fixture, false);
            case LEASE -> throw new AssertionError("handled above");
        };
    }

    private static ReaderFixture scheduleTimer(SqliteExecutionStore store, ReaderFixture fixture,
                                               boolean scoped) {
        apply(store, fixture.key, builder -> builder.scheduleTimer(new TimerSchedule(
                fixture.itemId, EPOCH.minusSeconds(1),
                scoped ? fixture.traversalId : null,
                scoped ? fixture.invocationId : null,
                OpaquePayload.empty("application/octet-stream"))));
        return fixture;
    }

    private static ReaderFixture registerHandler(SqliteExecutionStore store, ReaderFixture fixture) {
        var registration = new HandlerRegistration(fixture.itemId, "handler", fixture.traversalId,
                fixture.invocationId, "correlation", "deduplication",
                new HandlerPayloadSchema("application/json", "urn:test", 1024),
                HandlerAuthorization.ofRoles("APPROVER"));
        apply(store, fixture.key, builder -> builder.registerHandler(registration));
        return fixture;
    }

    private static ReaderFixture registerAuthorityBudget(SqliteExecutionStore store,
                                                          ReaderFixture fixture) {
        var root = new AgentAuthorityRootRegistration("runtime", 1,
                security(fixture.key), "policy", "rates", EPOCH.plusSeconds(3600),
                Set.of("data:read"), Set.of("tool:call"),
                new AgentBudgetVector(10, 10, 10, 10, 10, 10, 3, 3, 3), "USD");
        apply(store, fixture.key, builder -> builder.applyAgentBudget(
                new AgentBudgetOperation.RegisterRoot(root, 0)));
        return fixture;
    }

    private static ReaderFixture registerApproval(SqliteExecutionStore store, ReaderFixture fixture) {
        transitionAttemptToRunning(store, fixture);
        byte[] arguments = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] continuation = "checkpoint".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var registration = new ToolApprovalRegistration(fixture.itemId, fixture.traversalId,
                fixture.invocationId, fixture.attemptId, UUID.randomUUID(), "child", "tool.call",
                arguments, ToolApprovalRegistration.digest(arguments), security(fixture.key),
                new GraphVersionPin("graph-v1"), "policy", EPOCH.plusSeconds(300),
                HandlerAuthorization.ofRoles("APPROVER"), false, 1, continuation,
                ToolApprovalRegistration.digest(continuation));
        apply(store, fixture.key, builder -> builder.registerToolApproval(registration));
        return fixture;
    }

    private static ReaderFixture registerPause(SqliteExecutionStore store, ReaderFixture fixture) {
        apply(store, fixture.key, builder -> builder
                .apply(new ExecutionTransition.AttemptTransitioned(fixture.traversalId,
                        fixture.invocationId, fixture.attemptId, NodeAttemptStatus.RUNNING))
                .apply(new ExecutionTransition.AttemptTransitioned(fixture.traversalId,
                        fixture.invocationId, fixture.attemptId, NodeAttemptStatus.COMPLETED))
                .apply(new ExecutionTransition.InvocationTransitioned(fixture.traversalId,
                        fixture.invocationId, NodeInvocationStatus.COMPLETED)));
        byte[] continuation = "checkpoint".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var registration = new ExecutionPauseRegistration(fixture.itemId, fixture.traversalId,
                fixture.invocationId, "next", "PROCESS", "process", security(fixture.key),
                new GraphVersionPin("graph-v1"), 1, continuation,
                ExecutionPauseRegistration.digest(continuation));
        apply(store, fixture.key, builder -> builder
                .apply(new ExecutionTransition.TraversalTransitioned(fixture.traversalId,
                        TraversalStatus.WAITING))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.WAITING))
                .registerExecutionPause(registration));
        return fixture;
    }

    private static ReaderFixture registerHumanTask(SqliteExecutionStore store, ReaderFixture fixture) {
        transitionAttemptToRunning(store, fixture);
        byte[] continuation = {1, 2, 3};
        var registration = new HumanTaskRegistration(fixture.itemId, fixture.traversalId,
                fixture.invocationId, fixture.attemptId, "human-review", "correlation", "deduplication",
                new HumanTaskMetadata("Review", "Review details"),
                new HumanTaskResponseSchema("application/json", "urn:test", "1", PayloadKind.MAP, 1024),
                HandlerAuthorization.ofRoles("REVIEWER"), security(fixture.key),
                new GraphVersionPin("graph-v1"), java.util.Optional.empty(), EPOCH.plusSeconds(300),
                new HumanTaskReentryMapping("resolved", "denied", "expired", "cancelled"),
                1, continuation, ToolApprovalRegistration.digest(continuation));
        apply(store, fixture.key, builder -> builder.registerHumanTask(registration));
        return fixture;
    }

    private static ReaderFixture appendJournal(SqliteExecutionStore store, ReaderFixture fixture,
                                                boolean scoped) {
        UUID causationId = scoped ? UUID.randomUUID() : null;
        EventEnvelope envelope = EventEnvelope.of(fixture.itemId, fixture.key.tenantId(), "test.event",
                fixture.key.processInstanceId(), fixture.traversalId,
                scoped ? fixture.invocationId : null, scoped ? fixture.attemptId : null,
                causationId, "correlation", "graph-v1", EPOCH,
                OpaquePayload.empty("application/json"));
        apply(store, fixture.key, builder -> builder.publish(envelope));
        return fixture;
    }

    private static void transitionAttemptToRunning(SqliteExecutionStore store, ReaderFixture fixture) {
        apply(store, fixture.key, builder -> builder.apply(
                new ExecutionTransition.AttemptTransitioned(fixture.traversalId,
                        fixture.invocationId, fixture.attemptId, NodeAttemptStatus.RUNNING)));
    }

    private static void apply(SqliteExecutionStore store, ExecutionKey key,
                              java.util.function.UnaryOperator<ExecutionBatch.Builder> change) {
        StoredProcessInstance current = await(store.load(key));
        ExecutionBatch.Builder builder = ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(current.revision()));
        await(store.apply(change.apply(builder).build()));
    }

    private static SecurityContext security(ExecutionKey key) {
        return new SecurityContext("request", key.tenantId(), "principal",
                PrincipalType.WORKLOAD, "issuer");
    }

    private static void readFamily(SqliteExecutionStore store, ReaderFixture fixture,
                                   ReadFamily family) {
        switch (family) {
            case AGGREGATE -> await(store.load(fixture.key));
            case LEASE -> await(store.leases(fixture.key.tenantId()));
            case PROCESS_INVENTORY -> await(store.listProcessInstances(fixture.key.tenantId(),
                    ProcessInventoryQuery.everything(10)));
            case TRAVERSAL_INVENTORY -> await(store.listTraversals(fixture.key));
            case PENDING_ATTEMPT -> await(store.claimPendingWork(fixture.key.tenantId(),
                    "attempt-worker", 10, LEASE_TTL));
            case TIMER -> await(store.claimDueTimers(fixture.key.tenantId(),
                    "timer-worker", 10, LEASE_TTL));
            case HANDLER -> await(store.handlers(fixture.key));
            case HANDLER_BY_CORRELATION -> await(store.findHandler(fixture.key.tenantId(),
                    "handler", "correlation"));
            case GLOBAL_AUTHORITY -> await(store.transitionAgentAuthorityControl(
                    AgentAuthorityControlState.ACTIVE, 0, AgentAuthorityControlState.KILLED));
            case APPROVAL -> await(store.toolApprovals(fixture.key));
            case PAUSE -> await(store.executionPauses(fixture.key));
            case PAUSE_BY_TRAVERSAL -> await(store.findHeldExecutionPause(fixture.key.tenantId(),
                    fixture.traversalId));
            case HUMAN_TASK -> await(store.listHumanTasks(fixture.key.tenantId(),
                    HumanTaskQuery.everything(10)));
            case JOURNAL -> await(store.readJournal(fixture.key.tenantId(), 0, 10));
            case NULLABLE_TIMER, NULLABLE_JOURNAL -> throw new AssertionError("success-only fixture");
        }
    }

    private static Seed seedRunningAttempt(SqliteExecutionStore store, ExecutionKey key) {
        UUID traversalId = UUID.randomUUID();
        UUID parentInvocationId = UUID.randomUUID();
        UUID childInvocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        StoredProcessInstance created = await(store.apply(Fixtures.creationBatch(key, traversalId)));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId,
                        TraversalStatus.RUNNING))
                .apply(new ExecutionTransition.InvocationAdded(traversalId,
                        new NodeInvocation(parentInvocationId, "parent", Set.of(),
                                NodeInvocationStatus.SCHEDULED, List.of())))
                .apply(new ExecutionTransition.InvocationTransitioned(traversalId,
                        parentInvocationId, NodeInvocationStatus.RUNNING))
                .apply(new ExecutionTransition.InvocationAdded(traversalId,
                        new NodeInvocation(childInvocationId, "child", Set.of(parentInvocationId),
                                NodeInvocationStatus.SCHEDULED, List.of())))
                .apply(new ExecutionTransition.InvocationTransitioned(traversalId,
                        childInvocationId, NodeInvocationStatus.RUNNING))
                .apply(new ExecutionTransition.AttemptAdded(traversalId, childInvocationId,
                        new NodeAttempt(attemptId, 1, NodeAttemptStatus.SCHEDULED)))
                .build()));
        return new Seed(traversalId, parentInvocationId, childInvocationId, attemptId);
    }

    private static void execute(Path file, String sql, String... values) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.createStatement().execute("PRAGMA foreign_keys=OFF");
            for (int index = 0; index < values.length; index++) {
                statement.setString(index + 1, values[index]);
            }
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static int count(Path file, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static ExecutionStoreFailure.Corrupted corrupted(Runnable operation) {
        return assertInstanceOf(ExecutionStoreFailure.Corrupted.class,
                storeFailure(operation).failure());
    }

    private static ExecutionStoreException storeFailure(Runnable operation) {
        CompletionException thrown = assertThrows(CompletionException.class, operation::run);
        ExecutionStoreException failure = ExecutionStoreException.unwrap(thrown);
        assertNotNull(failure, "the adapter must not leak a raw persistence exception");
        return failure;
    }

    private static List<Throwable> causeChain(Throwable thrown) {
        var causes = new java.util.ArrayList<Throwable>();
        Throwable current = thrown;
        while (current != null && !causes.contains(current)) {
            causes.add(current);
            current = current.getCause();
        }
        return List.copyOf(causes);
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private record Seed(UUID traversalId, UUID parentInvocationId, UUID childInvocationId,
                        UUID attemptId) {
    }

    private record ReaderFixture(ExecutionKey key, UUID traversalId, UUID invocationId,
                                 UUID attemptId, UUID itemId) {
    }

    private enum ReadFamily {
        AGGREGATE,
        LEASE,
        PROCESS_INVENTORY,
        TRAVERSAL_INVENTORY,
        PENDING_ATTEMPT,
        TIMER,
        NULLABLE_TIMER,
        HANDLER,
        HANDLER_BY_CORRELATION,
        GLOBAL_AUTHORITY,
        APPROVAL,
        PAUSE,
        PAUSE_BY_TRAVERSAL,
        HUMAN_TASK,
        JOURNAL,
        NULLABLE_JOURNAL
    }

    private enum RelationalCorruption {
        INVOCATION_TRAVERSAL("invocation", "traversal_id",
                "UPDATE invocation SET traversal_id = ? WHERE tenant_id = ? "
                        + "AND process_instance_id = ? AND node_id = 'child'"),
        PARENT_CHILD("invocation_parent", "invocation_id",
                "UPDATE invocation_parent SET invocation_id = ? WHERE tenant_id = ? "
                        + "AND process_instance_id = ?"),
        PARENT_PARENT("invocation_parent", "parent_invocation_id",
                "UPDATE invocation_parent SET parent_invocation_id = ? WHERE tenant_id = ? "
                        + "AND process_instance_id = ?"),
        ATTEMPT_OWNER("attempt", "invocation_id",
                "UPDATE attempt SET invocation_id = ? WHERE tenant_id = ? "
                        + "AND process_instance_id = ?");

        private final String table;
        private final String column;
        private final String sql;

        RelationalCorruption(String table, String column, String sql) {
            this.table = table;
            this.column = column;
            this.sql = sql;
        }

        private void apply(Path file, ExecutionKey key) throws Exception {
            execute(file, sql, UUID.randomUUID().toString(), key.tenantId(),
                    key.processInstanceId().toString());
        }
    }
}
