package ai.ravenroot.testkit.audit;

import ai.ravenroot.api.audit.AuditCapability;
import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditEnvelope;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditRecord;
import ai.ravenroot.api.audit.AuditTrail;
import ai.ravenroot.api.audit.AuditTrailException;
import ai.ravenroot.api.audit.AuditTrailFailure;
import ai.ravenroot.api.audit.ChainVerificationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reusable conformance suite every {@link AuditTrail} adapter must extend, following the
 * {@code ExecutionStoreContract} / ADR 0010 section 1 precedent so that a future durable adapter has a
 * contract to conform to instead of leaving the {@code JoinStore} gap without one.
 *
 * <p>Test methods are {@code final}: a subclass supplies a factory, never a weaker assertion.
 * {@link AuditCapability#DURABLE} is capability-gated exactly as {@code StoreCapability.DURABLE} is —
 * absence skips the reopen assertion via {@link Assumptions#assumeTrue}, a visible skip rather than a
 * silent pass; presence never skips it.</p>
 */
public abstract class AuditTrailContract {
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final String TENANT = "acme";
    private static final String OTHER_TENANT = "globex";

    private AuditTrail trail;

    /**
     * Creates (or reopens) an adapter instance backed by {@code clock}, using {@code trailId} as the
     * backing identity for adapters that persist across the call. A durable adapter must use
     * {@code trailId} so a reopen reconnects to the same bytes on disk, exactly as a process restart
     * against the same directory would.
     */
    protected abstract AuditTrail createTrail(String trailId, Clock clock);

    @AfterEach
    final void closeTrail() {
        if (trail != null) {
            trail.close();
        }
        trail = null;
    }

    protected final AuditTrail trail() {
        if (trail == null) {
            trail = createTrail("default", Clock.fixed(EPOCH, ZoneOffset.UTC));
        }
        return trail;
    }

    private static AuditEnvelope envelope(String tenantId, String action) {
        return AuditEnvelope.of(tenantId, "issuer|USER|alice", AuditCategory.DECISION, action,
                "resource", "r-1", AuditOutcome.ALLOWED, "policy allowed", UUID.randomUUID().toString(), EPOCH);
    }

    @Test
    final void appendAssignsContiguousSequenceStartingAtOne() {
        AuditRecord first = trail().append(envelope(TENANT, "a"));
        AuditRecord second = trail().append(envelope(TENANT, "b"));
        AuditRecord third = trail().append(envelope(TENANT, "c"));
        assertEquals(1, first.sequence());
        assertEquals(2, second.sequence());
        assertEquals(3, third.sequence());
    }

    @Test
    final void theFirstRecordHasNoPreviousDigestAndLaterRecordsChainToTheirPredecessor() {
        AuditRecord first = trail().append(envelope(TENANT, "a"));
        AuditRecord second = trail().append(envelope(TENANT, "b"));
        assertNull(first.previousDigest());
        assertEquals(first.digest(), second.previousDigest());
    }

    @Test
    final void aStoredRecordsDigestDescribesItsOwnContent() {
        AuditRecord stored = trail().append(envelope(TENANT, "a"));
        assertTrue(stored.digestMatchesContent(),
                "the digest must describe the content it came back with, exactly the property "
                        + "EventEnvelope.digestMatchesContent asserts for the event journal");
    }

    @Test
    final void separateTenantsHaveIndependentChains() {
        trail().append(envelope(TENANT, "a"));
        AuditRecord otherFirst = trail().append(envelope(OTHER_TENANT, "z"));
        assertEquals(1, otherFirst.sequence(), "a second tenant's chain starts at one independently");
        assertEquals(1, trail().read(TENANT, 0, 10).size());
        assertEquals(1, trail().read(OTHER_TENANT, 0, 10).size());
        assertTrue(trail().read(OTHER_TENANT, 0, 10).stream().noneMatch(r -> r.envelope().tenantId().equals(TENANT)),
                "one tenant's read never contains another's records");
    }

    @Test
    final void readIsScopedByAfterSequenceAndLimit() {
        trail().append(envelope(TENANT, "a"));
        trail().append(envelope(TENANT, "b"));
        trail().append(envelope(TENANT, "c"));
        List<AuditRecord> page = trail().read(TENANT, 1, 1);
        assertEquals(1, page.size());
        assertEquals(2, page.get(0).sequence());
    }

    @Test
    final void headReportsTheLastAppendedRecord() {
        assertTrue(trail().head(TENANT).isEmpty());
        trail().append(envelope(TENANT, "a"));
        AuditRecord second = trail().append(envelope(TENANT, "b"));
        assertEquals(second, trail().head(TENANT).orElseThrow());
    }

    @Test
    final void aFreshlyWrittenChainVerifiesIntact() {
        trail().append(envelope(TENANT, "a"));
        trail().append(envelope(TENANT, "b"));
        trail().append(envelope(TENANT, "c"));
        ChainVerificationResult result = trail().verify(TENANT);
        assertTrue(result.intact(), () -> "unexpected anomalies: " + result.anomalies());
        assertEquals(3, result.checkedThroughSequence());
    }

    @Test
    final void anEmptyTenantChainVerifiesIntact() {
        assertTrue(trail().verify("never-touched").intact());
    }

    // There is deliberately no "append rejects a blank tenant" assertion here: AuditEnvelope's own
    // canonical constructor already refuses a blank tenantId before an envelope can exist, so no
    // conforming caller can ever hand append() one. Each adapter's own InvalidRequest guard on that
    // path is defense in depth, kept for a boundary that should never be reachable rather than
    // removed, but the TCK cannot exercise what the type system already forbids.

    @Test
    final void readRejectsABlankTenant() {
        assertThrows(AuditTrailException.class, () -> trail().read("", 0, 10));
    }

    @Test
    final void redactionPreservesChainShapeAndAppendsAnAdministrationTombstone() {
        trail().append(envelope(TENANT, "a"));
        AuditRecord second = trail().append(envelope(TENANT, "b"));
        trail().append(envelope(TENANT, "c"));

        AuditRecord tombstone = trail().redact(TENANT, 2, 2, "retention window elapsed", "issuer|USER|admin");
        assertEquals(AuditCategory.ADMINISTRATION, tombstone.envelope().category());
        assertEquals(4, tombstone.sequence(), "the tombstone is appended, never inserted");

        List<AuditRecord> all = trail().read(TENANT, 0, 10);
        assertEquals(4, all.size(), "redaction never removes or renumbers a record");
        AuditRecord redacted = all.get(1);
        assertTrue(redacted.redacted());
        assertEquals(second.sequence(), redacted.sequence(), "sequence is preserved by redaction");
        assertEquals(second.digest(), redacted.digest(), "digest is preserved by redaction, not recomputed");
        assertEquals(second.previousDigest(), redacted.previousDigest());

        ChainVerificationResult result = trail().verify(TENANT);
        assertTrue(result.intact(),
                () -> "a legitimate redaction must never read back as a gap or a tamper: " + result.anomalies());
    }

    @Test
    final void redactionOutOfRangeIsRejected() {
        trail().append(envelope(TENANT, "a"));
        var thrown = assertThrows(AuditTrailException.class,
                () -> trail().redact(TENANT, 1, 5, "reason", "issuer|USER|admin"));
        assertTrue(thrown.reason() instanceof AuditTrailFailure.RedactionOutOfRange);
    }

    @Test
    final void redactingAnAlreadyRedactedRecordIsRejected() {
        trail().append(envelope(TENANT, "a"));
        trail().redact(TENANT, 1, 1, "first pass", "issuer|USER|admin");
        assertThrows(AuditTrailException.class,
                () -> trail().redact(TENANT, 1, 1, "second pass", "issuer|USER|admin"));
    }

    @Test
    final void retentionPolicyIsDeclaredAndNonNegative() {
        assertFalse(trail().retentionPolicy().minimumRetention().isNegative());
    }

    // ---- capability-gated: DURABLE ------------------------------------------------------------

    @Test
    final void durableAdapterSurvivesACloseAndReopenWithAnIdenticallyVerifyingChain() {
        Assumptions.assumeTrue(trail().supports(AuditCapability.DURABLE),
                "AuditCapability.DURABLE is not declared by this adapter; skipped, not passed");
        Clock clock = Clock.fixed(EPOCH, ZoneOffset.UTC);
        AuditTrail first = createTrail("reopen-target", clock);
        try {
            first.append(envelope(TENANT, "a"));
            first.append(envelope(TENANT, "b"));
        } finally {
            first.close();
        }
        AuditTrail reopened = createTrail("reopen-target", clock);
        try {
            assertEquals(2, reopened.read(TENANT, 0, 10).size());
            assertEquals(2, reopened.head(TENANT).orElseThrow().sequence());
            assertTrue(reopened.verify(TENANT).intact());
        } finally {
            reopened.close();
        }
    }
}
