package ai.ravenroot.api.persistence;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * QA-07 fuzz coverage of {@link EventEnvelope}'s serialization and digest boundary --
 * {@code canonicalForm} and {@link EventEnvelope#digestMatchesContent()}. This covers that boundary
 * only, not crash timing (PERS-07's own {@code Kill*} infrastructure already owns that). It uses the
 * established {@code fuzz} tag, {@code -Pfuzz} profile,
 * gitignored {@code .jqwik-database}, properties generate their target field directly rather than
 * mutating a whole valid instance.
 *
 * <h2>Why Property 1 uses reflection, and why that was verified before being written this way</h2>
 * <p>The class's own Javadoc on {@code putText} says: "every absent field is encoded as a distinct
 * marker rather than as an empty one, so {@code null} and {@code ""} do not collide either." That
 * hazard is exactly what this suite must show the marker prevents. But every text field
 * {@code canonicalForm} passes to {@code putText} -- {@code tenantId}, {@code eventType},
 * {@code correlationId}, {@code graphVersion}, {@code payload.contentType()} -- is non-null by the
 * time any public construction path (the canonical constructor's own compact body, or
 * {@link EventEnvelope#of}) finishes: {@code tenantId}/{@code eventType}/{@code correlationId} are
 * rejected if blank, {@code graphVersion} is coerced from {@code null} to {@code ""} in the
 * canonical constructor itself, and {@link OpaquePayload#of} rejects a blank content type. Checked
 * empirically, not assumed; {@code validateScalar} previously demonstrated the same dead-branch risk:
 * there is today no public call that reaches {@code canonicalForm} with an actual {@code null}
 * string. {@code putText}'s {@code ABSENT} branch is therefore currently dead code for this record's
 * field set -- a property built only against the public API would pass identically whether the
 * marker mutation below were applied or not, exactly the false-negative shape this suite must catch.
 * Reflection into the {@code private static} {@code canonicalForm} method is what
 * lets this property supply an actual {@code null}, so it tests the real method under the real
 * hazard its own documentation names, rather than a hazard the current public surface happens not to
 * expose. If a future change makes any of these fields genuinely nullable through the public API,
 * this property starts mattering there too without needing to change.
 */
class EventEnvelopeFuzzTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    // ------------------------------------------------------------------------------------------
    // Property 1 (lead) -- a text field that is null and the same field as "" must never produce
    // the same canonical form, whatever the surrounding fields are. This mutation needs no
    // "why a refactor could introduce this" argument: the
    // class's own Javadoc already states the hazard the PRESENT/ABSENT marker exists to prevent.
    //
    // Red control: temporarily changed putText to
    //   `putBytes(out, (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));`
    // -- collapsing null and "" to the identical encoding, exactly the collision the Javadoc warns
    // about. Targeted graphVersion specifically, since it is the one field the public API even
    // accepts null for (before coercing it away) -- reflection bypasses that coercion to reach
    // canonicalForm with the null the public API itself would never let through. This property
    // failed on try 1: canonicalForm(graphVersion=null) and canonicalForm(graphVersion="") produced
    // byte-identical output for every generated combination of the other fields. Reverted
    // immediately after confirming the failure; passes again against the restored source.
    // ------------------------------------------------------------------------------------------

    @Tag("fuzz")
    @Property(tries = 100)
    void nullVersusEmptyGraphVersionNeverCollideInCanonicalForm(
            @ForAll("shortTexts") String tenantId, @ForAll("shortTexts") String eventType,
            @ForAll("shortTexts") String correlationId, @ForAll("shortTexts") String payloadBody) {
        // Every other argument, including the three UUIDs, must be IDENTICAL between the two calls
        // -- generated once, here, and passed into both. canonicalForm generating its own fresh
        // UUID.randomUUID() per call was tried first and is a known false-positive shape: it would
        // always report "different", regardless of
        // whether the marker collision exists, because eventId/processInstanceId/traversalId would
        // never match between the two calls either way. Caught before this property was trusted,
        // not after.
        UUID eventId = UUID.randomUUID();
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        byte[] withNull = canonicalForm(eventId, processInstanceId, traversalId, tenantId, eventType,
                correlationId, null, payloadBody);
        byte[] withEmpty = canonicalForm(eventId, processInstanceId, traversalId, tenantId, eventType,
                correlationId, "", payloadBody);
        assertFalse(java.util.Arrays.equals(withNull, withEmpty),
                "canonicalForm(graphVersion=null) collided with canonicalForm(graphVersion=\"\") for "
                        + "tenantId=" + tenantId + " eventType=" + eventType);
    }

    @Provide
    Arbitrary<String> shortTexts() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(8);
    }

    private static byte[] canonicalForm(UUID eventId, UUID processInstanceId, UUID traversalId,
                                        String tenantId, String eventType, String correlationId,
                                        String graphVersion, String payloadBody) {
        try {
            Method method = EventEnvelope.class.getDeclaredMethod("canonicalForm",
                    int.class, UUID.class, String.class, String.class, UUID.class, UUID.class,
                    UUID.class, UUID.class, UUID.class, String.class, String.class, Instant.class,
                    OpaquePayload.class);
            method.setAccessible(true);
            OpaquePayload payload = OpaquePayload.of(payloadBody.getBytes(StandardCharsets.UTF_8), "text/plain");
            return (byte[]) method.invoke(null, EventEnvelope.CURRENT_VERSION, eventId, tenantId,
                    eventType, processInstanceId, traversalId, null, null, null, correlationId,
                    graphVersion, NOW, payload);
        } catch (NoSuchMethodException | IllegalAccessException reflectionSetupFailed) {
            throw new AssertionError("EventEnvelope#canonicalForm's signature moved; update this test's "
                    + "reflective lookup", reflectionSetupFailed);
        } catch (InvocationTargetException invoked) {
            if (invoked.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AssertionError(invoked.getCause());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 2 -- every envelope EventEnvelope.of() builds matches its own digest immediately,
    // across generated field combinations rather than one or two hand-picked ones. No specific
    // defect targets this; it is the round-trip sanity floor everything else stands on.
    // ------------------------------------------------------------------------------------------

    @Tag("fuzz")
    @Property(tries = 200)
    void everyConstructedEnvelopeMatchesItsOwnDigest(@ForAll("shortTexts") String tenantId,
            @ForAll("shortTexts") String eventType, @ForAll("shortTexts") String correlationId,
            @ForAll("shortTexts") String payloadBody, @ForAll boolean withCausation) {
        EventEnvelope envelope = envelope(tenantId, eventType, correlationId, payloadBody, withCausation);
        assertTrue(envelope.digestMatchesContent());
    }

    // ------------------------------------------------------------------------------------------
    // Property 3 -- tampering any one digest-covered field, holding the original digest fixed
    // (exactly what a rewritten storage row looks like when read back), is always detected.
    // Generalizes EventEnvelopeTest#everyCoveredFieldChangesTheDigest and
    // SqliteJournalIntegrityTest's two hand-picked column tampers (event_type, payload_bytes) into
    // a property over which field is tampered and what it is tampered to, using the public
    // canonical constructor directly -- which is public precisely so a reader can reconstruct a
    // stored envelope with its stored (possibly stale) digest, per that constructor's own Javadoc.
    // ------------------------------------------------------------------------------------------

    @Tag("fuzz")
    @Property(tries = 200)
    void tamperingAnyOneFieldIsDetectedAgainstTheOriginalDigest(
            @ForAll("shortTexts") String originalEventType, @ForAll("shortTexts") String tamperedEventType) {
        if (originalEventType.equals(tamperedEventType)) {
            return; // not a tamper
        }
        EventEnvelope original = envelope("acme", originalEventType, "corr-1", "body", false);
        EventEnvelope tampered = new EventEnvelope(original.envelopeVersion(), original.eventId(),
                original.tenantId(), tamperedEventType, original.processInstanceId(), original.traversalId(),
                original.invocationId(), original.attemptId(), original.causationId(), original.correlationId(),
                original.graphVersion(), original.occurredAt(), original.payload(), original.digest());
        assertFalse(tampered.digestMatchesContent(),
                "eventType changed from " + originalEventType + " to " + tamperedEventType
                        + " but the stale digest was still accepted");
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static EventEnvelope envelope(String tenantId, String eventType, String correlationId,
                                          String payloadBody, boolean withCausation) {
        return EventEnvelope.of(UUID.randomUUID(), tenantId, eventType, UUID.randomUUID(), UUID.randomUUID(),
                null, null, withCausation ? UUID.randomUUID() : null, correlationId, "graph-v1", NOW,
                OpaquePayload.of(payloadBody.getBytes(StandardCharsets.UTF_8), "text/plain"));
    }
}
