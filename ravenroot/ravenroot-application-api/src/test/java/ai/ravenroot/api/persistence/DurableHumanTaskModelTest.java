package ai.ravenroot.api.persistence;

import ai.ravenroot.api.payload.PayloadKind;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurableHumanTaskModelTest {
    private static final ExecutionKey KEY = new ExecutionKey("acme", UUID.randomUUID());

    @Test
    void terminalStatesAcceptNothingAndEscalationRemainsResolvable() {
        assertTrue(HumanTaskStatus.WAITING.canTransitionTo(HumanTaskStatus.ESCALATED));
        assertTrue(HumanTaskStatus.ESCALATED.canTransitionTo(HumanTaskStatus.RESOLVED));
        for (HumanTaskStatus terminal : new HumanTaskStatus[] {HumanTaskStatus.RESOLVED,
                HumanTaskStatus.DENIED, HumanTaskStatus.EXPIRED, HumanTaskStatus.CANCELLED}) {
            assertTrue(terminal.terminal());
            for (HumanTaskStatus next : HumanTaskStatus.values()) {
                assertFalse(terminal.canTransitionTo(next));
            }
        }
    }

    @Test
    void generationFencesTransitionsAndExactReplayDoesNotAdvanceIt() {
        DurableHumanTask waiting = DurableHumanTask.waiting(KEY, registration(), 7L);
        var escalation = new HumanTaskTransition.Escalated(waiting.request().taskId(), 1L);
        DurableHumanTask escalated = waiting.apply(escalation, 8L);

        assertEquals(2L, escalated.generation());
        assertTrue(escalated.alreadyApplied(escalation));
        assertThrows(IllegalStateException.class, () -> escalated.apply(
                new HumanTaskTransition.Resolved(escalated.request().taskId(), 1L, "actor"), 9L));
    }

    @Test
    void displayAndSchemaInputsAreBoundedBeforePersistence() {
        assertThrows(IllegalArgumentException.class, () -> new HumanTaskMetadata(
                "é".repeat(HumanTaskMetadata.MAX_TITLE_UTF8_BYTES), "description"));
        assertThrows(IllegalArgumentException.class, () -> new HumanTaskResponseSchema(
                "application/json", "x".repeat(HumanTaskResponseSchema.MAX_SCHEMA_UTF8_BYTES + 1),
                "1", PayloadKind.MAP, 1024));
        assertThrows(IllegalArgumentException.class, () -> new HumanTaskMetadata(" ", "description"));
        assertThrows(IllegalArgumentException.class,
                () -> new HumanTaskMetadata("Review\nrequest", "description"));
    }

    @Test
    void continuationIsBoundedContentBoundAndDefensivelyCopied() {
        HumanTaskRegistration source = registration();
        byte[] continuation = {1, 2, 3};
        var registration = new HumanTaskRegistration(source.taskId(), source.traversalId(),
                source.invocationId(), source.attemptId(), source.nodeId(), source.correlationKey(),
                source.deduplicationKey(), source.metadata(), source.responseSchema(),
                source.responderRequirements(), source.requester(), source.graphVersionPin(),
                source.escalateAt(), source.expiresAt(), source.reentryMapping(), 2, continuation,
                ToolApprovalRegistration.digest(continuation));

        continuation[0] = 9;
        assertEquals(1, registration.continuation()[0]);
        byte[] returned = registration.continuation();
        returned[1] = 9;
        assertEquals(2, registration.continuation()[1]);
        assertThrows(IllegalArgumentException.class, () -> new HumanTaskRegistration(
                source.taskId(), source.traversalId(), source.invocationId(), source.attemptId(),
                source.nodeId(), source.correlationKey(), source.deduplicationKey(), source.metadata(),
                source.responseSchema(), source.responderRequirements(), source.requester(),
                source.graphVersionPin(), source.escalateAt(), source.expiresAt(), source.reentryMapping(),
                2, new byte[] {1}, ToolApprovalRegistration.digest(new byte[] {2})));
    }

    @Test
    void escalationMustPrecedeExpiryAndOutcomeMappingRejectsLiveStates() {
        Instant expiry = Instant.parse("2026-01-02T00:00:00Z");
        HumanTaskRegistration source = registration();
        assertThrows(IllegalArgumentException.class, () -> new HumanTaskRegistration(
                source.taskId(), source.traversalId(), source.invocationId(), source.attemptId(),
                source.nodeId(), source.correlationKey(), source.deduplicationKey(), source.metadata(),
                source.responseSchema(), source.responderRequirements(), source.requester(),
                source.graphVersionPin(), Optional.of(expiry), expiry, source.reentryMapping()));
        assertEquals("resolved", source.reentryMapping().outcomeFor(HumanTaskStatus.RESOLVED));
        assertThrows(IllegalArgumentException.class,
                () -> source.reentryMapping().outcomeFor(HumanTaskStatus.WAITING));
    }

    @Test
    void laterRedeliveryMatchesWithoutExtendingTheStoredDeadlines() {
        HumanTaskRegistration source = registration();
        HumanTaskRegistration later = new HumanTaskRegistration(
                source.taskId(), source.traversalId(), source.invocationId(), source.attemptId(),
                source.nodeId(), source.correlationKey(), source.deduplicationKey(), source.metadata(),
                source.responseSchema(), source.responderRequirements(), source.requester(),
                source.graphVersionPin(), source.escalateAt().map(value -> value.plusSeconds(30)),
                source.expiresAt().plusSeconds(30), source.reentryMapping());

        assertTrue(source.sameRequest(later));
        assertEquals(source.expiresAt(), DurableHumanTask.waiting(KEY, source, 1).request().expiresAt());
    }

    private static HumanTaskRegistration registration() {
        return new HumanTaskRegistration(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "human-review", "correlation", "dedupe",
                new HumanTaskMetadata("Review request", "Review the public request."),
                new HumanTaskResponseSchema("application/json", "urn:test:response", "1",
                        PayloadKind.MAP, 4096), HandlerAuthorization.ofRoles("REVIEWER"),
                new SecurityContext("request", KEY.tenantId(), "subject", PrincipalType.USER, "issuer"),
                new GraphVersionPin("graph-v1"),
                Optional.of(Instant.parse("2026-01-01T01:00:00Z")),
                Instant.parse("2026-01-02T00:00:00Z"),
                new HumanTaskReentryMapping("resolved", "denied", "expired", "cancelled"));
    }
}
