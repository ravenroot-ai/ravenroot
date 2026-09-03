package ai.ravenroot.api.persistence;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PERS-05 value types, asserted where they are decided rather than through an adapter.
 *
 * <p>Every rule here is one an adapter is expected to lean on: the state machine is what makes a
 * duplicate refusable without a clock, the payload check is what a store enforces on a caller's own
 * batch, and the record invariant is what classifies a bad row as corrupted rather than letting it
 * escape.</p>
 */
class DurableHandlerModelTest {

    private static final ExecutionKey KEY = new ExecutionKey("acme", UUID.randomUUID());

    @Test
    void aTerminalHandlerAcceptsNoFurtherTransitionAndEscalationStaysResolvable() {
        assertTrue(HandlerStatus.WAITING.canTransitionTo(HandlerStatus.ESCALATED));
        assertTrue(HandlerStatus.ESCALATED.canTransitionTo(HandlerStatus.RESOLVED));
        assertTrue(HandlerStatus.ESCALATED.canTransitionTo(HandlerStatus.DENIED));
        assertTrue(HandlerStatus.ESCALATED.canTransitionTo(HandlerStatus.EXPIRED));
        assertFalse(HandlerStatus.ESCALATED.canTransitionTo(HandlerStatus.ESCALATED),
                "replay tolerance is how a store applies a transition, not a legal state change");
        for (HandlerStatus terminal : new HandlerStatus[] {HandlerStatus.RESOLVED, HandlerStatus.DENIED,
                HandlerStatus.EXPIRED}) {
            assertTrue(terminal.terminal());
            assertTrue(terminal.resumesProcess());
            for (HandlerStatus next : HandlerStatus.values()) {
                assertFalse(terminal.canTransitionTo(next),
                        terminal + " must accept nothing further, or a duplicate would apply");
            }
        }
        assertFalse(HandlerStatus.WAITING.resumesProcess());
        assertFalse(HandlerStatus.ESCALATED.resumesProcess());
    }

    @Test
    void anIllegalTransitionIsRejectedByTheHandlerItself() {
        DurableHandler waiting = DurableHandler.waiting(KEY, registration(), 1L);
        DurableHandler resolved = waiting.apply(new HandlerTransition.Resolved(waiting.handlerId(),
                "issuer|USER|approver", UUID.randomUUID(), approvalPayload()), 2L);

        assertEquals(HandlerStatus.RESOLVED, resolved.status());
        assertEquals(2L, resolved.revision());
        assertThrows(IllegalStateException.class, () -> resolved.apply(
                new HandlerTransition.Denied(resolved.handlerId(), "issuer|USER|other", UUID.randomUUID(),
                        approvalPayload()), 3L));
        assertThrows(IllegalArgumentException.class, () -> waiting.apply(
                new HandlerTransition.Escalated(UUID.randomUUID(), "overdue"), 2L),
                "a transition aimed at another handler must not be folded over this one");
    }

    @Test
    void aResumingStatusWithoutItsTraversalCannotBeConstructed() {
        assertThrows(IllegalArgumentException.class, () -> new DurableHandler(UUID.randomUUID(), KEY,
                "approval", UUID.randomUUID(), UUID.randomUUID(), "invoice-42", "dedup-1", schema(),
                HandlerAuthorization.none(), HandlerStatus.RESOLVED, null, "issuer|USER|approver",
                approvalPayload(), 1L),
                "a resolved handler that named no re-entry point would strand the process silently");
        assertThrows(IllegalArgumentException.class, () -> new DurableHandler(UUID.randomUUID(), KEY,
                "approval", UUID.randomUUID(), UUID.randomUUID(), "invoice-42", "dedup-1", schema(),
                HandlerAuthorization.none(), HandlerStatus.WAITING, UUID.randomUUID(), "",
                approvalPayload(), 1L),
                "a re-entry recorded against a waiting handler would be one nothing authorized");
    }

    @Test
    void theDeclaredScheduleIsEnforcedOnContentTypeAndSizeAndNeverOnTheBytes() {
        HandlerPayloadSchema schema = new HandlerPayloadSchema("text/plain", "approval/v1", 4);

        assertEquals(Optional.empty(),
                schema.rejectionOf(OpaquePayload.of("okay".getBytes(StandardCharsets.UTF_8), "text/plain")));
        assertTrue(schema.rejectionOf(OpaquePayload.of("okay".getBytes(StandardCharsets.UTF_8),
                "application/json")).orElseThrow().contains("does not match"));
        String tooLarge = schema.rejectionOf(OpaquePayload.of("toolong".getBytes(StandardCharsets.UTF_8),
                "text/plain")).orElseThrow();
        assertTrue(tooLarge.contains("7 bytes"));
        assertFalse(tooLarge.contains("toolong"), "a refusal reason never echoes the payload");
        assertTrue(schema.rejectionOf(null).isPresent());
    }

    @Test
    void authorizationIsAConjunctionSoAddingARequirementNarrows() {
        var required = new HandlerAuthorization(Set.of("APPROVER", "TENANT_ADMIN"),
                Set.of("ravenroot.execute"));

        assertTrue(required.satisfiedBy(Set.of("APPROVER", "TENANT_ADMIN", "VIEWER"),
                Set.of("ravenroot.execute", "ravenroot.read")));
        assertFalse(required.satisfiedBy(Set.of("APPROVER"), Set.of("ravenroot.execute")),
                "holding one of two declared roles must not satisfy a requirement for both");
        assertFalse(required.satisfiedBy(Set.of("APPROVER", "TENANT_ADMIN"), Set.of()));
        assertTrue(HandlerAuthorization.none().satisfiedBy(null, null),
                "a principal holding nothing is an input, not a programming error");
        assertThrows(IllegalArgumentException.class,
                () -> HandlerAuthorization.ofRoles("APPROVER\nTENANT_ADMIN"),
                "a token carrying a newline would split into two authorities in a delimited column");
    }

    @Test
    void aHandlerIdentityIsDecodedOnlyFromItsOwnMediaTypeAndCanonicalForm() {
        UUID handlerId = UUID.randomUUID();

        assertEquals(Optional.of(handlerId),
                HandlerEventData.handlerId(HandlerEventData.payload(handlerId)));
        assertEquals(Optional.empty(), HandlerEventData.handlerId(
                OpaquePayload.of(handlerId.toString().getBytes(StandardCharsets.UTF_8), "text/plain")),
                "a foreign body must not become a plausible-looking handler identity");
        assertEquals(Optional.empty(), HandlerEventData.handlerId(
                OpaquePayload.of("not-a-uuid-not-a-uuid-not-a-uuid-abc".getBytes(StandardCharsets.UTF_8),
                        HandlerEventData.CONTENT_TYPE)));
        assertEquals(Optional.empty(), HandlerEventData.handlerId(null));
        assertEquals(HandlerEventData.HANDLER_RESOLVED,
                HandlerEventData.eventTypeFor(HandlerStatus.RESOLVED));
        assertEquals(HandlerEventData.HANDLER_REGISTERED,
                HandlerEventData.eventTypeFor(HandlerStatus.WAITING));
        assertTrue(HandlerEventData.isHandlerEvent(HandlerEventData.HANDLER_TRIGGER_REFUSED));
        assertFalse(HandlerEventData.isHandlerEvent("NODE_COMPLETED"));
    }

    @Test
    void handlerKeysAreBoundedInBytesRatherThanInCharacters() {
        String maximal = "e".repeat(HandlerRegistration.MAX_KEY_UTF8_BYTES);
        assertEquals(maximal, HandlerRegistration.requireBoundedKey(maximal, "correlationKey"));
        assertThrows(IllegalArgumentException.class,
                () -> HandlerRegistration.requireBoundedKey(maximal + "e", "correlationKey"));
        assertThrows(IllegalArgumentException.class, () -> HandlerRegistration.requireBoundedKey(
                "é".repeat(HandlerRegistration.MAX_KEY_UTF8_BYTES), "correlationKey"),
                "a two-byte character counts as two, or the bound means something different per key");
        assertThrows(IllegalArgumentException.class,
                () -> HandlerRegistration.requireBoundedKey(" ", "correlationKey"));
    }

    @Test
    void aRepeatedRegistrationMatchesOnlyWhenItNamesTheSameWait() {
        HandlerRegistration registration = registration();
        DurableHandler stored = DurableHandler.waiting(KEY, registration, 1L);

        assertTrue(stored.matches(registration));
        assertFalse(stored.matches(new HandlerRegistration(UUID.randomUUID(), registration.name(),
                registration.traversalId(), registration.invocationId(), registration.correlationKey(),
                registration.deduplicationKey(), schema(), HandlerAuthorization.none())));
        assertFalse(stored.matches(new HandlerRegistration(registration.handlerId(), registration.name(),
                registration.traversalId(), UUID.randomUUID(), registration.correlationKey(),
                registration.deduplicationKey(), schema(), HandlerAuthorization.none())));
        assertFalse(stored.matches(null));
    }

    private static HandlerRegistration registration() {
        return new HandlerRegistration(UUID.randomUUID(), "approval", UUID.randomUUID(),
                UUID.randomUUID(), "invoice-42", "dedup-1", schema(),
                HandlerAuthorization.ofRoles("APPROVER"));
    }

    private static HandlerPayloadSchema schema() {
        return new HandlerPayloadSchema("application/vnd.ravenroot.test-approval", "approval/v1", 1024);
    }

    private static OpaquePayload approvalPayload() {
        return OpaquePayload.of("approved".getBytes(StandardCharsets.UTF_8),
                "application/vnd.ravenroot.test-approval");
    }
}
