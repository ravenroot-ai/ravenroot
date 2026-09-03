package ai.ravenroot.core.handler;

import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditRecord;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.HandlerEventData;
import ai.ravenroot.api.persistence.HandlerPayloadSchema;
import ai.ravenroot.api.persistence.HandlerRegistration;
import ai.ravenroot.api.persistence.HandlerStatus;
import ai.ravenroot.api.persistence.JournalRecord;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.audit.InMemoryAuditTrail;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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
 * PERS-05: the reference monitor for durable waits.
 *
 * <p>Every refusal here is asserted twice — once for the outcome the caller receives, once for the
 * audit record it leaves. A refusal that changed no execution state and wrote no audit record would
 * be invisible, which is the failure mode the acceptance criterion is aimed at.</p>
 */
class DurableHandlerServiceTest {

    private static final String TENANT = "acme";
    private static final String CONTENT_TYPE = "application/vnd.ravenroot.test-approval";

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryExecutionStore store = new InMemoryExecutionStore(clock);
    private final InMemoryAuditTrail auditTrail = new InMemoryAuditTrail(clock, Duration.ofDays(1));
    private final DurableHandlerService service = new DurableHandlerService(store, auditTrail, clock);

    @AfterEach
    void closeStore() {
        store.close();
        auditTrail.close();
    }

    @Test
    void anAuthorizedResolutionReEntersTheProcessAndIsAudited() {
        Fixture fixture = waitingHandler(TENANT, "invoice-42");

        HandlerTriggerOutcome outcome = service.resolve(approver(TENANT), "approval", "invoice-42",
                payload("approved"));

        var accepted = assertInstanceOf(HandlerTriggerOutcome.Accepted.class, outcome);
        assertEquals(HandlerStatus.RESOLVED, accepted.status());
        assertTrue(await(store.load(fixture.key)).state().traversals()
                        .containsKey(accepted.resumeTraversalId()),
                "the re-entry traversal is durable state, so no live continuation has to survive");

        List<PendingWork> claimed = await(store.claimPendingWork(TENANT, "worker-1", 10,
                Duration.ofSeconds(30)));
        assertEquals(1, claimed.size());
        var trigger = assertInstanceOf(PendingWork.HandlerTrigger.class, claimed.getFirst());
        assertEquals(accepted.resumeTraversalId(), trigger.traversalId());
        assertEquals("approved", new String(trigger.payload().bytes(), StandardCharsets.UTF_8));

        AuditRecord audited = onlyAuditRecord();
        assertEquals(AuditOutcome.ALLOWED, audited.envelope().outcome());
        assertEquals("handler.resolve", audited.envelope().action());
        assertEquals(fixture.handlerId.toString(), audited.envelope().resourceId());

        JournalRecord journalled = await(store.readJournal(TENANT, 0, 10)).getLast();
        assertEquals(HandlerEventData.HANDLER_RESOLVED, journalled.envelope().eventType());
        assertEquals(fixture.handlerId,
                HandlerEventData.handlerId(journalled.envelope().payload()).orElseThrow(),
                "the event distinguishes the handler from the process, traversal and invocation "
                        + "beside it rather than leaving a reader to infer which is which");
        assertEquals(accepted.resumeTraversalId(), journalled.envelope().traversalId());
    }

    @Test
    void aPrincipalWithoutTheDeclaredRoleIsRefusedAndNothingIsWritten() {
        Fixture fixture = waitingHandler(TENANT, "invoice-42");
        long revisionBefore = await(store.load(fixture.key)).revision();

        HandlerTriggerOutcome outcome = service.resolve(viewer(TENANT), "approval", "invoice-42",
                payload("approved"));

        assertInstanceOf(HandlerTriggerOutcome.Unauthorized.class, outcome);
        assertEquals(revisionBefore, await(store.load(fixture.key)).revision());
        assertEquals(HandlerStatus.WAITING,
                await(store.loadHandler(fixture.key, fixture.handlerId)).orElseThrow().status());
        assertEquals(AuditOutcome.DENIED, onlyAuditRecord().envelope().outcome());
    }

    @Test
    void aTriggerFromAnotherTenantIsIndistinguishableFromOneForAnUnknownKey() {
        Fixture fixture = waitingHandler(TENANT, "invoice-42");

        HandlerTriggerOutcome crossTenant = service.resolve(approver("intruder"), "approval",
                "invoice-42", payload("approved"));
        HandlerTriggerOutcome unknownKey = service.resolve(approver("intruder"), "approval",
                "invoice-does-not-exist", payload("approved"));

        assertInstanceOf(HandlerTriggerOutcome.NotFound.class, crossTenant);
        assertEquals(crossTenant.reason(), unknownKey.reason(),
                "distinguishing the two would let a caller enumerate another tenant's correlation keys");
        assertEquals(HandlerStatus.WAITING,
                await(store.loadHandler(fixture.key, fixture.handlerId)).orElseThrow().status());
        // Audited against the intruder's own tenant: an audit record is written where the actor is,
        // not where the resource it guessed at happens to live.
        List<AuditRecord> intruderRecords = auditTrail.read("intruder", 0, 10);
        assertEquals(2, intruderRecords.size());
        intruderRecords.forEach(record ->
                assertEquals(AuditOutcome.DENIED, record.envelope().outcome()));
        assertTrue(auditTrail.read(TENANT, 0, 10).isEmpty(),
                "a refused cross-tenant probe must not write into the tenant it was probing");
    }

    @Test
    void aDuplicateResolutionIsRefusedDeterministicallyAndAudited() {
        Fixture fixture = waitingHandler(TENANT, "invoice-42");
        service.resolve(approver(TENANT), "approval", "invoice-42", payload("approved"));
        long revisionAfterFirst = await(store.load(fixture.key)).revision();

        HandlerTriggerOutcome second = service.resolve(approver(TENANT), "approval", "invoice-42",
                payload("approved"));
        HandlerTriggerOutcome third = service.resolve(approver(TENANT), "approval", "invoice-42",
                payload("approved"));

        // A settled handler is no longer live, so the lookup no longer finds it: the refusal is
        // NotFound rather than AlreadySettled, and it is the same refusal every time.
        assertFalse(second.accepted());
        assertEquals(second.reason(), third.reason(), "the refusal must not drift between retries");
        assertEquals(revisionAfterFirst, await(store.load(fixture.key)).revision(),
                "a duplicate must not commit a second re-entry traversal");
        assertEquals(1, await(store.claimPendingWork(TENANT, "worker-1", 10, Duration.ofSeconds(30))).size(),
                "one handler produces exactly one trigger no matter how often it is triggered");

        List<AuditRecord> records = auditTrail.read(TENANT, 0, 10);
        assertEquals(3, records.size(), "every refusal leaves evidence, not only the accepted one");
        assertEquals(AuditOutcome.ALLOWED, records.get(0).envelope().outcome());
        assertEquals(AuditOutcome.DENIED, records.get(1).envelope().outcome());
        assertEquals(AuditOutcome.DENIED, records.get(2).envelope().outcome());
    }

    @Test
    void aLateResolutionAfterAnExpiryIsRefusedAndTheTimeoutRouteStands() {
        Fixture fixture = waitingHandler(TENANT, "invoice-42");

        HandlerTriggerOutcome expired = service.expire(fixture.key, fixture.handlerId, "request-1");
        var accepted = assertInstanceOf(HandlerTriggerOutcome.Accepted.class, expired);
        assertEquals(HandlerStatus.EXPIRED, accepted.status());
        assertEquals("", await(store.loadHandler(fixture.key, fixture.handlerId)).orElseThrow().actor(),
                "a deadline is not an actor and must not be recorded as one");

        HandlerTriggerOutcome late = service.resolve(approver(TENANT), "approval", "invoice-42",
                payload("approved"));
        assertFalse(late.accepted());
        assertEquals(HandlerStatus.EXPIRED,
                await(store.loadHandler(fixture.key, fixture.handlerId)).orElseThrow().status());

        // Redelivery of the expiry timer is answered without changing anything, because the timer
        // that drives it is delivered at least once.
        HandlerTriggerOutcome redelivered = service.expire(fixture.key, fixture.handlerId, "request-1");
        assertInstanceOf(HandlerTriggerOutcome.AlreadySettled.class, redelivered);
        assertEquals(accepted.resumeTraversalId(),
                await(store.loadHandler(fixture.key, fixture.handlerId)).orElseThrow().resumeTraversalId());
    }

    @Test
    void aPayloadThatDoesNotMatchTheDeclaredSchemaIsRefusedAndAudited() {
        Fixture fixture = waitingHandler(TENANT, "invoice-42");

        HandlerTriggerOutcome outcome = service.resolve(approver(TENANT), "approval", "invoice-42",
                OpaquePayload.of(new byte[] {1, 2, 3}, "application/octet-stream"));

        var refused = assertInstanceOf(HandlerTriggerOutcome.PayloadRefused.class, outcome);
        assertTrue(refused.reason().contains("does not match the handler's declared"),
                "the reason names the media types and never the bytes");
        assertEquals(HandlerStatus.WAITING,
                await(store.loadHandler(fixture.key, fixture.handlerId)).orElseThrow().status());
        assertEquals(AuditOutcome.DENIED, onlyAuditRecord().envelope().outcome());
    }

    @Test
    void escalationIsRepeatableAndKeepsTheHandlerResolvable() {
        Fixture fixture = waitingHandler(TENANT, "invoice-42");

        assertTrue(service.escalate(fixture.key, fixture.handlerId, "overdue", "request-1").accepted());
        assertTrue(service.escalate(fixture.key, fixture.handlerId, "overdue", "request-1").accepted(),
                "an at-least-once escalation timer must not turn a retry into an incident");
        assertEquals(HandlerStatus.ESCALATED,
                await(store.loadHandler(fixture.key, fixture.handlerId)).orElseThrow().status());
        assertTrue(await(store.claimPendingWork(TENANT, "worker-1", 10, Duration.ofSeconds(30))).isEmpty(),
                "an escalation resumes nothing, so it must produce no trigger");

        assertTrue(service.resolve(approver(TENANT), "approval", "invoice-42", payload("approved"))
                .accepted(), "escalation unsticks a task; it does not close it");
    }

    @Test
    void aDenialIsAnOutcomeAndResumesTheProcessJustAsAResolutionDoes() {
        Fixture fixture = waitingHandler(TENANT, "invoice-42");

        HandlerTriggerOutcome outcome = service.deny(approver(TENANT), "approval", "invoice-42",
                OpaquePayload.of("rejected by finance".getBytes(StandardCharsets.UTF_8), "text/plain"));

        var accepted = assertInstanceOf(HandlerTriggerOutcome.Accepted.class, outcome);
        assertEquals(HandlerStatus.DENIED, accepted.status());
        assertEquals(1, await(store.claimPendingWork(TENANT, "worker-1", 10, Duration.ofSeconds(30))).size(),
                "the process continues down the route its author declared for a refusal");
        assertEquals(AuditOutcome.ALLOWED, onlyAuditRecord().envelope().outcome(),
                "the principal was permitted to act; what they decided is the payload, not the outcome");
    }

    // ---------------------------------------------------------------- fixtures

    private record Fixture(ExecutionKey key, UUID traversalId, UUID invocationId, UUID handlerId) {
    }

    private Fixture waitingHandler(String tenantId, String correlationKey) {
        var key = new ExecutionKey(tenantId, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID handlerId = UUID.randomUUID();
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
                        correlationKey, "dedup-" + handlerId,
                        new HandlerPayloadSchema(CONTENT_TYPE, "approval/v1", 1024),
                        HandlerAuthorization.ofRoles(Role.APPROVER.name())))
                .build()));
        return new Fixture(key, traversalId, invocationId, handlerId);
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

    private AuditRecord onlyAuditRecord() {
        List<AuditRecord> records = auditTrail.read(TENANT, 0, 10);
        assertEquals(1, records.size(), "exactly one audit record per trigger decision");
        return records.getFirst();
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
