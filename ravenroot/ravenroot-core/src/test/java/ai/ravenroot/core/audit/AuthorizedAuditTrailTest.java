package ai.ravenroot.core.audit;

import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditEnvelope;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditRecord;
import ai.ravenroot.api.audit.AuthorizedAuditTrail;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.security.AuthorizationDeniedException;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-13/SEC-03 wiring: {@link AuthorizedAuditTrail} gates read/export/redact through
 * {@code AuthorizationService} exactly as {@code AuthorizedRavenrootApplication} gates the
 * application, and reading the trail leaves its own {@link AuditCategory#ACCESS} trace.
 */
class AuthorizedAuditTrailTest {
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    private static RequestContext context(String tenant, Role role) {
        return new RequestContext("req-1", "alice", PrincipalType.USER, "issuer", tenant, Set.of(role),
                Set.of("ravenroot.audit.read", "ravenroot.audit.export", "ravenroot.audit.admin"));
    }

    private static AuditEnvelope envelope(String tenant, String action) {
        return AuditEnvelope.of(tenant, "issuer|USER|bob", AuditCategory.DECISION, action, "r", "r-1",
                AuditOutcome.ALLOWED, "policy allowed", UUID.randomUUID().toString(), EPOCH,
                OpaquePayload.empty("text/plain"));
    }

    @Test
    void tenantAdminMayReadTheirOwnTenantAndTheReadIsItselfAudited() {
        var backing = new InMemoryAuditTrail(Clock.fixed(EPOCH, ZoneOffset.UTC), Duration.ofHours(24));
        backing.append(envelope("tenant-a", "authorize:EXECUTION_START"));
        var authorization = new DefaultAuthorizationService(e -> { });
        var trail = new AuthorizedAuditTrail(backing, authorization);

        var result = trail.read(context("tenant-a", Role.TENANT_ADMIN), "tenant-a", 0, 10);
        assertEquals(1, result.size());

        // The read itself must now be visible in the very trail it just served.
        var afterRead = backing.read("tenant-a", 0, 10);
        assertEquals(2, afterRead.size(), "the read appends its own ACCESS record");
        AuditRecord selfRecord = afterRead.get(1);
        assertEquals(AuditCategory.ACCESS, selfRecord.envelope().category());
        assertEquals("audit.read", selfRecord.envelope().action());
    }

    @Test
    void viewerIsDeniedAndNoAccessRecordIsFabricatedForADeniedRead() {
        var backing = new InMemoryAuditTrail(Clock.fixed(EPOCH, ZoneOffset.UTC), Duration.ofHours(24));
        backing.append(envelope("tenant-a", "authorize:EXECUTION_START"));
        var authorization = new DefaultAuthorizationService(e -> { });
        var trail = new AuthorizedAuditTrail(backing, authorization);

        assertThrows(AuthorizationDeniedException.class,
                () -> trail.read(context("tenant-a", Role.VIEWER), "tenant-a", 0, 10));
        assertEquals(1, backing.read("tenant-a", 0, 10).size(), "a denied read appends nothing");
    }

    @Test
    void tenantAdminCannotRedactButPlatformAdminCan() {
        var backing = new InMemoryAuditTrail(Clock.fixed(EPOCH, ZoneOffset.UTC), Duration.ofHours(24));
        backing.append(envelope("tenant-a", "authorize:EXECUTION_START"));
        var authorization = new DefaultAuthorizationService(e -> { });
        var trail = new AuthorizedAuditTrail(backing, authorization);

        assertThrows(AuthorizationDeniedException.class,
                () -> trail.redact(context("tenant-a", Role.TENANT_ADMIN), "tenant-a", 1, 1, "reason"));

        AuditRecord tombstone = trail.redact(context("tenant-a", Role.PLATFORM_ADMIN), "tenant-a", 1, 1, "reason");
        assertEquals(AuditCategory.ADMINISTRATION, tombstone.envelope().category());
        assertTrue(backing.read("tenant-a", 0, 10).get(0).redacted());
    }

    @Test
    void crossTenantReadIsDenied() {
        var backing = new InMemoryAuditTrail(Clock.fixed(EPOCH, ZoneOffset.UTC), Duration.ofHours(24));
        backing.append(envelope("tenant-b", "authorize:EXECUTION_START"));
        var authorization = new DefaultAuthorizationService(e -> { });
        var trail = new AuthorizedAuditTrail(backing, authorization);

        assertThrows(AuthorizationDeniedException.class,
                () -> trail.read(context("tenant-a", Role.TENANT_ADMIN), "tenant-b", 0, 10));
    }
}
