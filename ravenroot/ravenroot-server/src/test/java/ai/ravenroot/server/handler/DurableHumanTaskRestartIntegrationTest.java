package ai.ravenroot.server.handler;

import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditRecord;
import ai.ravenroot.api.audit.AuditTrail;
import ai.ravenroot.api.persistence.DurableHandler;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.HandlerPayloadSchema;
import ai.ravenroot.api.persistence.HandlerRegistration;
import ai.ravenroot.api.persistence.HandlerStatus;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.audit.FileAuditTrail;
import ai.ravenroot.core.handler.DurableHandlerService;
import ai.ravenroot.core.handler.HandlerTriggerOutcome;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERS-05, end to end and against real files: a human task created before a complete server shutdown
 * is authorized and resolved after the restart.
 *
 * <p>Nothing here is mocked. The execution state is a SQLite file, the audit trail is a directory of
 * files, and every phase closes both and reopens them the way a process restart does — so a
 * "durability" that only held while an object graph stayed alive would fail this test rather than
 * pass it. The worker that claims the resumed work uses a name that did not exist during the wait,
 * which is the point: no actor is revived, because none is named anywhere in the path.</p>
 */
class DurableHumanTaskRestartIntegrationTest {

    private static final String TENANT = "acme";
    private static final String CONTENT_TYPE = "application/vnd.ravenroot.test-approval";
    private static final Duration TTL = Duration.ofSeconds(30);

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void aHumanTaskCreatedBeforeAShutdownIsAuthorizedAndResolvedAfterTheRestart(@TempDir Path directory) {
        Path databaseFile = directory.resolve("execution.db");
        Path auditDirectory = directory.resolve("audit");
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID handlerId = UUID.randomUUID();

        // ---- before the shutdown: the process parks on a human task.
        try (ExecutionStore store = new SqliteExecutionStore(databaseFile, clock)) {
            assertTrue(store.supports(StoreCapability.DURABLE_HANDLERS));
            assertTrue(store.supports(StoreCapability.DURABLE));
            registerHumanTask(store, key, traversalId, invocationId, handlerId);
            assertEquals(TraversalStatus.WAITING,
                    await(store.load(key)).state().traversals().get(traversalId).status());
        }

        // ---- the restart. Both the store and the trail are closed and reopened from disk.
        UUID resumeTraversalId;
        try (ExecutionStore store = new SqliteExecutionStore(databaseFile, clock);
             AuditTrail auditTrail = new FileAuditTrail(auditDirectory, clock, Duration.ofDays(1))) {
            var service = new DurableHandlerService(store, auditTrail, clock);

            DurableHandler survived = await(store.findHandler(TENANT, "approval", "invoice-42"))
                    .orElseThrow();
            assertEquals(handlerId, survived.handlerId());
            assertEquals(HandlerStatus.WAITING, survived.status());
            assertEquals(Set.of(Role.APPROVER.name()), survived.authorization().requiredRoles(),
                    "the authorization requirement has to survive with the task, or the task becomes "
                            + "either unresolvable or resolvable by anyone");

            assertInstanceOf(HandlerTriggerOutcome.Unauthorized.class,
                    service.resolve(viewer(TENANT), "approval", "invoice-42", payload("approved")),
                    "the requirement is enforced after the restart, not only while it was in memory");
            assertInstanceOf(HandlerTriggerOutcome.NotFound.class,
                    service.resolve(approver("intruder"), "approval", "invoice-42", payload("approved")),
                    "a cross-tenant trigger is indistinguishable from one for an unknown key");

            var accepted = assertInstanceOf(HandlerTriggerOutcome.Accepted.class,
                    service.resolve(approver(TENANT), "approval", "invoice-42", payload("approved")));
            resumeTraversalId = accepted.resumeTraversalId();

            HandlerTriggerOutcome duplicate =
                    service.resolve(approver(TENANT), "approval", "invoice-42", payload("approved"));
            assertFalse(duplicate.accepted(), "the second resolution is refused, not applied twice");

            List<AuditRecord> audited = auditTrail.read(TENANT, 0, 10);
            assertEquals(3, audited.size(), "unauthorized, allowed and duplicate all leave evidence");
            assertEquals(AuditOutcome.DENIED, audited.get(0).envelope().outcome());
            assertEquals(AuditOutcome.ALLOWED, audited.get(1).envelope().outcome());
            assertEquals(AuditOutcome.DENIED, audited.get(2).envelope().outcome());
            assertTrue(auditTrail.verify(TENANT).intact(), "the audit chain is intact across the run");
        }

        // ---- a second restart: the resumed work is claimed by a worker that did not exist before.
        try (ExecutionStore store = new SqliteExecutionStore(databaseFile, clock);
             AuditTrail auditTrail = new FileAuditTrail(auditDirectory, clock, Duration.ofDays(1))) {
            assertEquals(HandlerStatus.RESOLVED,
                    await(store.loadHandler(key, handlerId)).orElseThrow().status());
            assertTrue(await(store.load(key)).state().traversals().containsKey(resumeTraversalId));

            List<PendingWork> claimed = await(store.claimPendingWork(TENANT, "worker-born-after", 10, TTL));
            assertEquals(1, claimed.size());
            var trigger = assertInstanceOf(PendingWork.HandlerTrigger.class, claimed.getFirst());
            assertEquals(handlerId, trigger.workItemId());
            assertEquals(resumeTraversalId, trigger.traversalId());
            assertEquals("approved", new String(trigger.payload().bytes(), StandardCharsets.UTF_8));
            await(store.ack(trigger));

            assertEquals(3, auditTrail.read(TENANT, 0, 10).size(),
                    "the audit records written before the restart are still there");
        }

        // ---- and a third: an acknowledged trigger stays acknowledged across a restart.
        try (ExecutionStore store = new SqliteExecutionStore(databaseFile, clock)) {
            assertTrue(await(store.claimPendingWork(TENANT, "worker-born-later-still", 10, TTL)).isEmpty(),
                    "a resumed process must not be resumed a second time by a redelivered trigger");
        }
    }

    private void registerHumanTask(ExecutionStore store, ExecutionKey key, UUID traversalId,
                                   UUID invocationId, UUID handlerId) {
        StoredProcessInstance created = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(),
                        ProcessInstanceStatus.ACCEPTED, Map.of(traversalId, new Traversal(traversalId,
                                "start", TraversalStatus.ACCEPTED, Map.of()))),
                        new GraphVersionPin("graph-v1")))
                .build()));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .apply(new ExecutionTransition.InvocationAdded(traversalId,
                        new NodeInvocation(invocationId, "await-approval", Set.of(),
                                NodeInvocationStatus.SCHEDULED, List.of())))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.WAITING))
                .registerHandler(new HandlerRegistration(handlerId, "approval", traversalId, invocationId,
                        "invoice-42", "wait-invoice-42",
                        new HandlerPayloadSchema(CONTENT_TYPE, "approval/v1", 1024),
                        HandlerAuthorization.ofRoles(Role.APPROVER.name())))
                .build()));
    }

    private static RequestContext approver(String tenantId) {
        return new RequestContext("request-1", "approver", PrincipalType.USER, "issuer", tenantId,
                Set.of(Role.APPROVER), Set.of("ravenroot.execute"));
    }

    private static RequestContext viewer(String tenantId) {
        return new RequestContext("request-2", "watcher", PrincipalType.USER, "issuer", tenantId,
                Set.of(Role.VIEWER), Set.of("ravenroot.read"));
    }

    private static OpaquePayload payload(String body) {
        return OpaquePayload.of(body.getBytes(StandardCharsets.UTF_8), CONTENT_TYPE);
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
