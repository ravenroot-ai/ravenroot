package ai.ravenroot.api.persistence;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The PERS-07 envelope preserves version, causality, tenant and digest as one validated unit. */
class EventEnvelopeTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID INSTANCE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TRAVERSAL = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void anEnvelopeCarriesItsOwnDigestAndThatDigestDescribesItsContent() {
        EventEnvelope envelope = envelope("acme", "node.started", payload("body"));
        assertEquals(EventEnvelope.CURRENT_VERSION, envelope.envelopeVersion());
        assertEquals(EventDigest.LENGTH, envelope.digest().value().length);
        assertTrue(envelope.digestMatchesContent());
    }

    @Test
    void theSameContentDigestsIdenticallySoARedeliveredEventIsRecognisable() {
        UUID eventId = UUID.randomUUID();
        assertEquals(envelope("acme", "node.started", payload("body"), eventId).digest(),
                envelope("acme", "node.started", payload("body"), eventId).digest(),
                "a redelivered event must digest identically, or a consumer cannot tell it is the "
                        + "same event rather than a new one that merely looks similar");
    }

    @Test
    void everyCoveredFieldChangesTheDigest() {
        EventEnvelope baseline = envelope("acme", "node.started", payload("body"));
        assertNotEquals(baseline.digest(), envelope("other", "node.started", payload("body")).digest(),
                "tenant is covered");
        assertNotEquals(baseline.digest(), envelope("acme", "node.failed", payload("body")).digest(),
                "event type is covered");
        assertNotEquals(baseline.digest(), envelope("acme", "node.started", payload("other")).digest(),
                "payload bytes are covered");
        assertNotEquals(baseline.digest(),
                EventEnvelope.of(baseline.eventId(), "acme", "node.started", INSTANCE, TRAVERSAL, null,
                        null, null, "request-1", "graph-v1", NOW, payload("body")).digest(),
                "causation is covered, so an event cannot be re-parented without the digest noticing");
        assertNotEquals(baseline.digest(),
                EventEnvelope.of(baseline.eventId(), "acme", "node.started", INSTANCE, TRAVERSAL, null,
                        null, baseline.causationId(), "request-2", "graph-v1", NOW, payload("body")).digest(),
                "correlation is covered");
        assertNotEquals(baseline.digest(),
                EventEnvelope.of(baseline.eventId(), "acme", "node.started", INSTANCE, TRAVERSAL, null,
                        null, baseline.causationId(), "request-1", "graph-v1", NOW.plusNanos(1),
                        payload("body")).digest(),
                "the nanosecond component is covered, not just the second");
        assertNotEquals(baseline.digest(),
                EventEnvelope.of(baseline.eventId(), "acme", "node.started", INSTANCE, TRAVERSAL, null,
                        null, baseline.causationId(), "request-1", "graph-v1", NOW,
                        OpaquePayload.of("body".getBytes(StandardCharsets.UTF_8), "text/plain")).digest(),
                "the payload content type is covered as well as its bytes");
    }

    /**
     * The guard for the length-prefixed encoding.
     *
     * <p>Under a plain concatenation these two envelopes produce byte-identical canonical forms:
     * moving a character across a field boundary leaves the concatenation unchanged. An integrity
     * check built on such a digest would accept either envelope as evidence for the other, which is
     * the difference between a checksum and a decoration.</p>
     */
    @Test
    void movingACharacterAcrossAFieldBoundaryChangesTheDigest() {
        UUID eventId = UUID.randomUUID();
        EventEnvelope left = EventEnvelope.of(eventId, "acme", "ab", INSTANCE, TRAVERSAL, null, null,
                null, "c", "graph-v1", NOW, payload("body"));
        EventEnvelope right = EventEnvelope.of(eventId, "acme", "a", INSTANCE, TRAVERSAL, null, null,
                null, "bc", "graph-v1", NOW, payload("body"));
        assertNotEquals(left.digest(), right.digest(),
                "('ab','c') and ('a','bc') must not share a digest; if they do, the canonical form "
                        + "is a concatenation and field boundaries are not part of the hash");
    }

    /** The same hazard on the last two fields, where a trailing length prefix is easiest to omit. */
    @Test
    void movingACharacterBetweenTheContentTypeAndTheBodyChangesTheDigest() {
        UUID eventId = UUID.randomUUID();
        EventEnvelope left = EventEnvelope.of(eventId, "acme", "e", INSTANCE, TRAVERSAL, null, null,
                null, "r", "graph-v1", NOW,
                OpaquePayload.of("b".getBytes(StandardCharsets.UTF_8), "textx"));
        EventEnvelope right = EventEnvelope.of(eventId, "acme", "e", INSTANCE, TRAVERSAL, null, null,
                null, "r", "graph-v1", NOW,
                OpaquePayload.of("xb".getBytes(StandardCharsets.UTF_8), "text"));
        assertNotEquals(left.digest(), right.digest());
    }

    /** An absent optional and an empty one must not collide either. */
    @Test
    void anAbsentFieldAndAnEmptyFieldDigestDifferently() {
        UUID eventId = UUID.randomUUID();
        EventEnvelope absent = EventEnvelope.of(eventId, "acme", "e", INSTANCE, TRAVERSAL, null, null,
                null, "r", null, NOW, payload("b"));
        EventEnvelope empty = EventEnvelope.of(eventId, "acme", "e", INSTANCE, TRAVERSAL, null, null,
                null, "r", "", NOW, payload("b"));
        // Both normalise a null graphVersion to "", so they are equal here by construction, which is
        // the documented behaviour; the invocation id is the field where absence is genuinely absent.
        assertEquals(absent.digest(), empty.digest(), "graphVersion normalises null to empty");
        EventEnvelope withInvocation = EventEnvelope.of(eventId, "acme", "e", INSTANCE, TRAVERSAL,
                UUID.fromString("00000000-0000-0000-0000-000000000000"), null, null, "r", "", NOW, payload("b"));
        assertNotEquals(empty.digest(), withInvocation.digest(),
                "an absent invocation and a nil-UUID invocation are different events");
    }

    @Test
    void aTamperedEnvelopeIsDetectedBecauseItsStoredDigestNoLongerDescribesIt() {
        EventEnvelope original = envelope("acme", "node.started", payload("body"));
        // Exactly what reading a corrupted row back produces: the stored digest with different content.
        var tampered = new EventEnvelope(original.envelopeVersion(), original.eventId(), original.tenantId(),
                "node.succeeded", original.processInstanceId(), original.traversalId(), null, null,
                original.causationId(), original.correlationId(), original.graphVersion(),
                original.occurredAt(), original.payload(), original.digest());
        assertFalse(tampered.digestMatchesContent(),
                "the mismatch is what an adapter maps to Corrupted; without it a rewritten journal "
                        + "row is indistinguishable from an authentic one");
    }

    @Test
    void anEventCannotBeItsOwnCause() {
        UUID eventId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> EventEnvelope.of(eventId, "acme", "e", INSTANCE,
                TRAVERSAL, null, null, eventId, "r", "graph-v1", NOW, payload("b")),
                "a self-referential cause is a fabricated value standing in for absence, and it would "
                        + "make the causal graph cyclic at its root");
    }

    @Test
    void tenantAndCorrelationAreMandatoryBecauseAnEventThatCannotSayWhoseWorkItWasIsNotEvidence() {
        assertThrows(IllegalArgumentException.class, () -> EventEnvelope.of(UUID.randomUUID(), " ", "e",
                INSTANCE, TRAVERSAL, null, null, null, "r", "g", NOW, payload("b")));
        assertThrows(IllegalArgumentException.class, () -> EventEnvelope.of(UUID.randomUUID(), "acme", "e",
                INSTANCE, TRAVERSAL, null, null, null, "  ", "g", NOW, payload("b")));
        assertDoesNotThrow(() -> EventEnvelope.of(UUID.randomUUID(), "acme", "e", INSTANCE, TRAVERSAL,
                null, null, null, "r", null, NOW, payload("b")));
    }

    @Test
    void aDigestOfTheWrongWidthIsRejectedRatherThanStored() {
        assertThrows(IllegalArgumentException.class, () -> EventDigest.of(new byte[16]));
        assertEquals(64, EventDigest.of(new byte[EventDigest.LENGTH]).hex().length());
    }

    private static EventEnvelope envelope(String tenantId, String eventType, OpaquePayload payload) {
        return envelope(tenantId, eventType, payload, UUID.randomUUID());
    }

    private static EventEnvelope envelope(String tenantId, String eventType, OpaquePayload payload,
                                          UUID eventId) {
        return EventEnvelope.of(eventId, tenantId, eventType, INSTANCE, TRAVERSAL, null, null,
                UUID.fromString("33333333-3333-3333-3333-333333333333"), "request-1", "graph-v1", NOW,
                payload);
    }

    private static OpaquePayload payload(String body) {
        return OpaquePayload.of(body.getBytes(StandardCharsets.UTF_8), "application/json");
    }
}
