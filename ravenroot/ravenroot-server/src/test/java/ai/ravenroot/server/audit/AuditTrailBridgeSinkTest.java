package ai.ravenroot.server.audit;

import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.core.audit.AuditTrailAuthorizationSink;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditRecord;
import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.api.plugin.PluginActivationEvent;
import ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent;
import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.AuthorizationAuditEvent;
import ai.ravenroot.core.audit.InMemoryAuditTrail;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphMlParseException;
import ai.ravenroot.server.error.PayloadRejectionAuditEvent;
import ai.ravenroot.server.ratelimit.RateLimitAuditEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four bridges from an existing audit event into the SEC-13 durable trail.
 *
 * <p>These are the only production wiring the trail has, and they had no test at all: the server's own
 * tests construct {@code RavenrootServer} through the narrow constructor, which still installs the
 * stdout loggers, so nothing exercised the classes that actually reach the trail. A bridge that drops
 * the tenant, mislabels the category or inverts an outcome would have been invisible.</p>
 */
class AuditTrailBridgeSinkTest {

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    private InMemoryAuditTrail trail() {
        return new InMemoryAuditTrail(Clock.fixed(EPOCH, ZoneOffset.UTC), Duration.ofHours(24));
    }

    private static AuditRecord only(InMemoryAuditTrail trail, String tenantId) {
        List<AuditRecord> records = trail.read(tenantId, 0, 10);
        assertEquals(1, records.size(), () -> "expected exactly one record for " + tenantId + ": " + records);
        return records.get(0);
    }

    // ---- authorization ----------------------------------------------------------------------------

    @Test
    void anAllowedAuthorizationDecisionReachesTheTrailWithItsIdentityAndCorrelation() {
        try (var trail = trail()) {
            new AuditTrailAuthorizationSink(trail).record(new AuthorizationAuditEvent(EPOCH, "req-1",
                    "issuer|USER|alice", "acme", AuthorizationAction.EXECUTION_START, "execution", "e-1",
                    true, "policy allowed"));

            AuditRecord record = only(trail, "acme");
            assertEquals("acme", record.envelope().tenantId());
            assertEquals("issuer|USER|alice", record.envelope().principal());
            assertEquals(AuditCategory.DECISION, record.envelope().category());
            assertEquals("authorize:EXECUTION_START", record.envelope().action());
            assertEquals(AuditOutcome.ALLOWED, record.envelope().outcome());
            assertEquals("req-1", record.envelope().correlationId(),
                    "the request id is what ties this record to the rest of the request's evidence");
            assertEquals("e-1", record.envelope().resourceId());
        }
    }

    /**
     * A denial is the record a security trail exists for, and it must arrive as a denial. An audit
     * trail that only proves what was permitted answers the least interesting half of the question.
     */
    @Test
    void aDeniedAuthorizationDecisionReachesTheTrailAsDenied() {
        try (var trail = trail()) {
            new AuditTrailAuthorizationSink(trail).record(new AuthorizationAuditEvent(EPOCH, "req-2",
                    "issuer|USER|mallory", "acme", AuthorizationAction.ARTIFACT_APPROVE, "artifact", "a-9",
                    false, "role not permitted"));

            AuditRecord record = only(trail, "acme");
            assertEquals(AuditOutcome.DENIED, record.envelope().outcome());
            assertEquals("role not permitted", record.envelope().reason());
            assertEquals(AuditCategory.APPROVAL, record.envelope().category(),
                    "an approval gate is categorised as an approval even when it refuses");
        }
    }

    @Test
    void auditAdministrationAndAuditReadCarryTheirOwnCategories() {
        try (var trail = trail()) {
            var sink = new AuditTrailAuthorizationSink(trail);
            sink.record(new AuthorizationAuditEvent(EPOCH, "req-3", "issuer|USER|admin", "acme",
                    AuthorizationAction.AUDIT_ADMIN, "audit-trail", "acme", true, ""));
            sink.record(new AuthorizationAuditEvent(EPOCH, "req-4", "issuer|USER|reader", "acme",
                    AuthorizationAction.AUDIT_READ, "audit-trail", "acme", true, ""));

            List<AuditRecord> records = trail.read("acme", 0, 10);
            assertEquals(AuditCategory.ADMINISTRATION, records.get(0).envelope().category());
            assertEquals(AuditCategory.ACCESS, records.get(1).envelope().category());
        }
    }

    // ---- rate limit -------------------------------------------------------------------------------

    @Test
    void aRateLimitRejectionReachesTheTrailAsADeniedAccess() {
        try (var trail = trail()) {
            new AuditTrailRateLimitSink(trail).record(new RateLimitAuditEvent(EPOCH, "req-5", "203.0.113.7",
                    false, "acme", "issuer|USER|alice", "POST", "/executions", "rate_limited", "tenant",
                    429, 30));

            AuditRecord record = only(trail, "acme");
            assertEquals(AuditCategory.ACCESS, record.envelope().category());
            assertEquals("ratelimit.reject", record.envelope().action());
            assertEquals(AuditOutcome.DENIED, record.envelope().outcome());
            assertEquals("POST /executions", record.envelope().resourceId());
            String detail = new String(record.envelope().detail().bytes(), StandardCharsets.UTF_8);
            assertTrue(detail.contains("clientAddress=203.0.113.7"),
                    () -> "the client address is the field an operator acts on: " + detail);
        }
    }

    /**
     * The pre-authentication case, which is also the one that made the append cost attacker-reachable:
     * these land in a single pseudo-tenant chain that an unauthenticated caller can drive.
     */
    @Test
    void aPreAuthenticationRejectionLandsInThePlaceholderTenantChain() {
        try (var trail = trail()) {
            new AuditTrailRateLimitSink(trail).record(new RateLimitAuditEvent(EPOCH, "req-6", "203.0.113.9",
                    true, RateLimitAuditEvent.UNKNOWN, RateLimitAuditEvent.UNKNOWN, "GET", "/status",
                    "rate_limited", "address", 429, 5));

            AuditRecord record = only(trail, RateLimitAuditEvent.UNKNOWN);
            assertEquals(RateLimitAuditEvent.UNKNOWN, record.envelope().principal(),
                    "pre-authentication the server does not know who is calling, and must not invent it");
            assertEquals(AuditOutcome.DENIED, record.envelope().outcome());
        }
    }

    // ---- artifact lifecycle -----------------------------------------------------------------------

    /**
     * The mis-recording the regression analysis found: an attempt is written before the mutation runs, and reading it
     * back as {@code ALLOWED} claimed an operation that may still fail had already taken effect.
     */
    @Test
    void anArtifactAttemptIsRecordedAsAttemptedRatherThanAllowed() {
        try (var trail = trail()) {
            new AuditTrailArtifactLifecycleSink(trail).record(new ArtifactLifecycleAuditEvent(EPOCH, "req-7",
                    "issuer|USER|alice", "acme", "ARTIFACT_APPROVE", "a-1", "sha", ArtifactState.GENERATED,
                    ArtifactLifecycleAuditEvent.Disposition.ATTEMPT, 1L, "evidence-digest-fixture"));

            AuditRecord record = only(trail, "acme");
            assertEquals(AuditCategory.VERSION, record.envelope().category());
            assertEquals(AuditOutcome.ATTEMPTED, record.envelope().outcome(),
                    "an attempt has no result yet; ALLOWED asserts it was permitted and took effect");
        }
    }

    @Test
    void anArtifactOperationThatFailsAfterItsAttemptIsRecordedAsFailed() {
        try (var trail = trail()) {
            var sink = new AuditTrailArtifactLifecycleSink(trail);
            sink.record(new ArtifactLifecycleAuditEvent(EPOCH, "req-8", "issuer|USER|alice", "acme",
                    "ARTIFACT_ACTIVATE", "a-2", "sha", ArtifactState.APPROVED,
                    ArtifactLifecycleAuditEvent.Disposition.ATTEMPT, 1L, "evidence-digest-fixture"));
            sink.record(new ArtifactLifecycleAuditEvent(EPOCH, "req-8", "issuer|USER|alice", "acme",
                    "ARTIFACT_ACTIVATE", "a-2", "sha", ArtifactState.APPROVED,
                    ArtifactLifecycleAuditEvent.Disposition.FAILED, 1L, "evidence-digest-fixture"));

            List<AuditRecord> records = trail.read("acme", 0, 10);
            assertEquals(2, records.size());
            assertEquals(AuditOutcome.ATTEMPTED, records.get(0).envelope().outcome());
            assertEquals(AuditOutcome.FAILED, records.get(1).envelope().outcome());
            assertTrue(records.stream().noneMatch(r -> r.envelope().outcome() == AuditOutcome.ALLOWED),
                    () -> "an operation that failed must not read back anywhere as ALLOWED: " + records);
            assertEquals(records.get(0).envelope().correlationId(), records.get(1).envelope().correlationId(),
                    "the terminal record is tied to its attempt by the correlation id");
        }
    }

    @Test
    void aDeniedArtifactOperationIsRecordedAsDenied() {
        try (var trail = trail()) {
            new AuditTrailArtifactLifecycleSink(trail).record(new ArtifactLifecycleAuditEvent(EPOCH, "req-9",
                    "issuer|USER|alice", "acme", "ARTIFACT_APPROVE", "a-3", "sha", ArtifactState.GENERATED,
                    ArtifactLifecycleAuditEvent.Disposition.DENIED, 1L, "evidence-digest-fixture"));

            assertEquals(AuditOutcome.DENIED, only(trail, "acme").envelope().outcome());
        }
    }

    /** Whatever the sinks write must still verify as a chain; a bridge cannot quietly break linkage. */
    @Test
    void recordsWrittenThroughTheBridgesFormAVerifiableChain() {
        try (var trail = trail()) {
            var authorization = new AuditTrailAuthorizationSink(trail);
            for (int i = 0; i < 5; i++) {
                authorization.record(new AuthorizationAuditEvent(EPOCH, "req-" + i, "issuer|USER|alice",
                        "acme", AuthorizationAction.EXECUTION_READ, "execution", "e-" + i, true, ""));
            }
            new AuditTrailRateLimitSink(trail).record(new RateLimitAuditEvent(EPOCH, "req-x", "203.0.113.7",
                    false, "acme", "issuer|USER|alice", "GET", "/e", "rate_limited", "tenant", 429, 1));

            var result = trail.verify("acme");
            assertTrue(result.intact(), () -> String.valueOf(result.anomalies()));
            assertEquals(6L, result.checkedThroughSequence());
        }
    }

    // ---- graphml rejection (FIX-03) ----------------------------------------------------------------

    /**
     * A real rejection, not a hand-built fixture: {@code GraphMlParseException}'s constructor is
     * package-private to {@code ai.ravenroot.core.graph} (FIX-03's own sanitisation policy), so this
     * triggers one through the public {@link GraphManager#readGraphMl} the same way a caller would.
     */
    private static GraphMlParseException malformedGraphMl() {
        try {
            GraphManager.readGraphMl(new ByteArrayInputStream("<not-well-formed".getBytes(StandardCharsets.UTF_8)));
        } catch (GraphMlParseException rejection) {
            return rejection;
        }
        throw new AssertionError("expected GraphManager.readGraphMl to reject malformed XML");
    }

    @Test
    void aGraphMlRejectionReachesTheTrailAsADeniedAccessKeyedByItsIncidentId() {
        try (var trail = trail()) {
            var rejection = malformedGraphMl();
            new AuditTrailGraphMlRejectionSink(trail).record(new GraphMlRejectionAuditEvent(EPOCH, "req-10",
                    "acme", "issuer|USER|alice", rejection));

            AuditRecord record = only(trail, "acme");
            assertEquals(AuditCategory.ACCESS, record.envelope().category());
            assertEquals("graphml.reject", record.envelope().action());
            assertEquals(AuditOutcome.DENIED, record.envelope().outcome());
            assertEquals(rejection.incidentId(), record.envelope().resourceId(),
                    "the incident id is what joins this record to the response the caller received");
            assertEquals(rejection.reason().name(), record.envelope().reason());
            assertEquals("req-10", record.envelope().correlationId());
            String detail = new String(record.envelope().detail().bytes(), StandardCharsets.UTF_8);
            assertTrue(detail.contains("\"message\""),
                    () -> "the sanitised public message is recorded alongside the document-derived detail: "
                            + detail);
        }
    }

    /**
     * The document-derived detail — never public, never in the response — is exactly what the audit
     * record must carry, or the whole reason for routing here (FIX-03's "server-side sink" constraint)
     * is unmet.
     */
    @Test
    void aGraphMlRejectionCarriesItsDiagnosticDetailNotJustTheSanitisedMessage() {
        try (var trail = trail()) {
            var rejection = malformedGraphMl();
            new AuditTrailGraphMlRejectionSink(trail).record(new GraphMlRejectionAuditEvent(EPOCH, "req-11",
                    "acme", "issuer|USER|alice", rejection));

            String detail = new String(only(trail, "acme").envelope().detail().bytes(), StandardCharsets.UTF_8);
            assertFalse(rejection.diagnosticDetail().isEmpty(),
                    "the fixture must actually exercise a rejection that carries diagnostic detail");
            rejection.diagnosticDetail().forEach((key, value) -> assertTrue(detail.contains(escapeForAssertion(value)),
                    () -> "diagnostic detail key '" + key + "' did not reach the audit record: " + detail));
        }
    }

    /** The pre-authentication case: no principal was resolved, so both fields are the honest placeholder. */
    @Test
    void aGraphMlRejectionBeforeAuthenticationLandsInThePlaceholderTenantChain() {
        try (var trail = trail()) {
            new AuditTrailGraphMlRejectionSink(trail).record(new GraphMlRejectionAuditEvent(EPOCH, "req-12",
                    GraphMlRejectionAuditEvent.UNKNOWN, GraphMlRejectionAuditEvent.UNKNOWN, malformedGraphMl()));

            AuditRecord record = only(trail, GraphMlRejectionAuditEvent.UNKNOWN);
            assertEquals(GraphMlRejectionAuditEvent.UNKNOWN, record.envelope().principal(),
                    "pre-authentication the server does not know who is calling, and must not invent it");
        }
    }

    // ---- payload rejection (API-01) ----------------------------------------------------------------

    @Test
    void aPayloadRejectionReachesTheTrailAsADeniedAccessKeyedByItsIncidentId() {
        try (var trail = trail()) {
            var rejection = PayloadException.malformed();
            new AuditTrailPayloadRejectionSink(trail).record(new PayloadRejectionAuditEvent(EPOCH, "req-13",
                    "acme", "issuer|USER|alice", rejection));

            AuditRecord record = only(trail, "acme");
            assertEquals(AuditCategory.ACCESS, record.envelope().category());
            assertEquals("payload.reject", record.envelope().action());
            assertEquals(AuditOutcome.DENIED, record.envelope().outcome());
            assertEquals(rejection.incidentId(), record.envelope().resourceId());
            assertEquals(rejection.code(), record.envelope().reason());
            assertEquals("req-13", record.envelope().correlationId());
        }
    }

    @Test
    void aPayloadRejectionBeforeAuthenticationLandsInThePlaceholderTenantChain() {
        try (var trail = trail()) {
            new AuditTrailPayloadRejectionSink(trail).record(new PayloadRejectionAuditEvent(EPOCH, "req-14",
                    PayloadRejectionAuditEvent.UNKNOWN, PayloadRejectionAuditEvent.UNKNOWN,
                    PayloadException.malformed()));

            AuditRecord record = only(trail, PayloadRejectionAuditEvent.UNKNOWN);
            assertEquals(PayloadRejectionAuditEvent.UNKNOWN, record.envelope().principal());
        }
    }

    /** Both new bridges must not break the same chain-integrity property every other bridge proves. */
    @Test
    void graphMlAndPayloadRejectionRecordsFormAVerifiableChainTogetherWithEverythingElse() {
        try (var trail = trail()) {
            new AuditTrailAuthorizationSink(trail).record(new AuthorizationAuditEvent(EPOCH, "req-15",
                    "issuer|USER|alice", "acme", AuthorizationAction.EXECUTION_READ, "execution", "e-15", true, ""));
            new AuditTrailGraphMlRejectionSink(trail).record(new GraphMlRejectionAuditEvent(EPOCH, "req-16",
                    "acme", "issuer|USER|alice", malformedGraphMl()));
            new AuditTrailPayloadRejectionSink(trail).record(new PayloadRejectionAuditEvent(EPOCH, "req-17",
                    "acme", "issuer|USER|alice", PayloadException.malformed()));

            var result = trail.verify("acme");
            assertTrue(result.intact(), () -> String.valueOf(result.anomalies()));
            assertEquals(3L, result.checkedThroughSequence());
        }
    }

    private static String escapeForAssertion(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    // ---- plugin activation (PLAT-12) ---------------------------------------------------------------

    @Test
    void anActivatedPluginReachesTheTrailAsAllowed() {
        try (var trail = trail()) {
            new AuditTrailPluginActivationSink(trail).record(new PluginActivationEvent(EPOCH,
                    "ai.ravenroot.extensions.mail", PluginActivationEvent.Outcome.ACTIVATED, "ACTIVATED",
                    "version=1.0.0;sdkContract=ravenroot.node-sdk/1", "aabbccdd"));

            AuditRecord record = only(trail, "-");
            assertEquals(AuditCategory.VERSION, record.envelope().category());
            assertEquals("plugin.activate", record.envelope().action());
            assertEquals(AuditOutcome.ALLOWED, record.envelope().outcome());
            assertEquals("ai.ravenroot.extensions.mail", record.envelope().resourceId());
            assertEquals("aabbccdd", record.envelope().correlationId(),
                    "the incident id is what ties this record to the console message shown for the same event");
        }
    }

    /**
     * A refused activation is the record PLAT-12's fail-closed startup exists for. The full,
     * unredacted detail belongs here even though a console message showing the same event would have
     * neutralized and capped it -- this is the complete record, not a substitute for the console line.
     */
    @Test
    void aRefusedPluginActivationReachesTheTrailAsDeniedWithFullDetail() {
        try (var trail = trail()) {
            new AuditTrailPluginActivationSink(trail).record(new PluginActivationEvent(EPOCH,
                    "ai.ravenroot.extensions.mail", PluginActivationEvent.Outcome.REFUSED, "CHECKSUM_MISMATCH",
                    "Plugin bundle artifact does not match its declared checksum {name=ravenroot-mail.jar}",
                    "11223344"));

            AuditRecord record = only(trail, "-");
            assertEquals("plugin.refuse", record.envelope().action());
            assertEquals(AuditOutcome.DENIED, record.envelope().outcome());
            assertEquals("CHECKSUM_MISMATCH", record.envelope().reason());
            String detail = new String(record.envelope().detail().bytes(), StandardCharsets.UTF_8);
            assertTrue(detail.contains("ravenroot-mail.jar"),
                    () -> "the audit record carries the full, unredacted detail: " + detail);
        }
    }

    /**
     * A failure discovered before any plugin id could be attributed (an unreadable manifest, say)
     * still has to land somewhere auditable -- the pseudo-tenant chain, same as the pre-authentication
     * rate-limit case, because there is no real identity to scope it to either.
     */
    @Test
    void aPluginActivationFailureWithNoAttributablePluginIdStillLandsInThePlaceholderChain() {
        try (var trail = trail()) {
            new AuditTrailPluginActivationSink(trail).record(new PluginActivationEvent(EPOCH, null,
                    PluginActivationEvent.Outcome.REFUSED, "UNKNOWN_PLUGIN_ID", "Enabled plugin id is not installed",
                    "55667788"));

            AuditRecord record = only(trail, "-");
            assertEquals("-", record.envelope().resourceId());
            assertEquals(AuditOutcome.DENIED, record.envelope().outcome());
        }
    }

    @Test
    void pluginActivationRecordsAlsoFormAVerifiableChain() {
        try (var trail = trail()) {
            var sink = new AuditTrailPluginActivationSink(trail);
            sink.record(new PluginActivationEvent(EPOCH, "plugin.a", PluginActivationEvent.Outcome.ACTIVATED,
                    "ACTIVATED", "version=1.0.0", "id-1"));
            sink.record(new PluginActivationEvent(EPOCH, "plugin.b", PluginActivationEvent.Outcome.REFUSED,
                    "MISSING_CLASS", "class=plugin.b.Missing", "id-2"));

            var result = trail.verify("-");
            assertTrue(result.intact(), () -> String.valueOf(result.anomalies()));
            assertEquals(2L, result.checkedThroughSequence());
        }
    }
}
