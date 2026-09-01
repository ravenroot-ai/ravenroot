package ai.ravenroot.api.embed;

import ai.ravenroot.api.security.AuthorizationDecision;
import ai.ravenroot.api.security.AuthorizationDeniedException;
import ai.ravenroot.api.security.AuthorizationService;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizedEmbedRegistrationAdministrationTest {

    private static final Clock CLOCK = Clock.fixed(EmbedFixtures.AT, ZoneOffset.UTC);

    @Test
    void provisionAndRevokeAreReachableOnlyByAnOperatorPrincipal() {
        var recorded = new ArrayList<EmbedRegistrationAuditSink.Event>();
        var authority = new InMemoryEmbedRegistrationAuthority();
        var administration = administration(authority, recorded);

        // Every embed HTTP route authenticates a WORKLOAD. This is the whole of «never from a graph,
        // a payload, a plugin or a browser route»: the roles are as wide as they can be and it is
        // still denied, so widening a token's roles cannot buy provisioning.
        assertThrows(AuthorizationDeniedException.class, () -> administration.provision(
                EmbedFixtures.workloadPretendingToBeOperator(),
                EmbedFixtures.command(0, "sha256:a", "start")));
        assertThrows(AuthorizationDeniedException.class, () -> administration.revoke(
                EmbedFixtures.workloadPretendingToBeOperator(),
                new EmbedRevokeCommand(EmbedFixtures.REGISTRATION, EmbedFixtures.TENANT, 1)));
        assertInstanceOf(EmbedRegistrationResolution.Unavailable.class,
                authority.resolveCurrent(EmbedFixtures.workload(), EmbedFixtures.REGISTRATION),
                "nothing was written by the denied commands");

        assertInstanceOf(EmbedProvisionOutcome.Provisioned.class, administration.provision(
                EmbedFixtures.operator(), EmbedFixtures.command(0, "sha256:a", "start")));
    }

    /**
     * The central policy's own half of operator-only, asserted separately from the monitor's.
     *
     * <p>Two guards, two tests. Without this one, deleting the principal-type rule from
     * {@code DefaultAuthorizationService} would leave every test green because the monitor still
     * refuses — and a deployment that injects its own {@code AuthorizationService} relies on the
     * policy, not on the monitor, for the decision it audits.</p>
     */
    @Test
    void theCentralPolicyDeniesEmbedAdministrationToAWorkloadPrincipal() {
        var service = new DefaultAuthorizationService(event -> { });
        var decision = service.decide(EmbedFixtures.workloadPretendingToBeOperator(),
                ai.ravenroot.api.security.AuthorizationAction.EMBED_REGISTRATION_ADMIN,
                ai.ravenroot.api.security.ProtectedResource.owned("embed-registration",
                        EmbedFixtures.REGISTRATION, EmbedFixtures.TENANT));
        assertFalse(decision.allowed());
        assertEquals("embed registration administration requires an operator principal",
                decision.reason());
    }

    /**
     * The monitor's own half, asserted against a policy that allows everything.
     *
     * <p>Without this one, deleting the principal-type check from the monitor would leave every test
     * green because the default policy still refuses — and the monitor is what a composition root
     * binds, so it is one substitution away from being the only guard left.</p>
     */
    @Test
    void theMonitorItselfRefusesAWorkloadPrincipalEvenUnderAPermissivePolicy() {
        AuthorizationService permissive = (context, action, resource) ->
                new AuthorizationDecision(true, "test policy allows everything");
        var authority = new InMemoryEmbedRegistrationAuthority();
        var administration = new AuthorizedEmbedRegistrationAdministration(permissive, authority,
                event -> { }, CLOCK);

        assertThrows(AuthorizationDeniedException.class, () -> administration.provision(
                EmbedFixtures.workloadPretendingToBeOperator(),
                EmbedFixtures.command(0, "sha256:a", "start")));
        assertThrows(AuthorizationDeniedException.class, () -> administration.revoke(
                EmbedFixtures.workloadPretendingToBeOperator(),
                new EmbedRevokeCommand(EmbedFixtures.REGISTRATION, EmbedFixtures.TENANT, 1)));
        assertInstanceOf(EmbedRegistrationResolution.Unavailable.class,
                authority.resolveCurrent(EmbedFixtures.workload(), EmbedFixtures.REGISTRATION));
    }

    @Test
    void theViewerRoleAndTheEmbedSessionScopeDoNotGrantAdministration() {
        var service = new DefaultAuthorizationService(event -> { });
        var viewer = new RequestContext("request", "person", PrincipalType.USER, EmbedFixtures.ISSUER,
                EmbedFixtures.TENANT, Set.of(Role.VIEWER),
                Set.of("ravenroot.embed.registration.admin"));
        assertFalse(service.decide(viewer, ai.ravenroot.api.security.AuthorizationAction.EMBED_REGISTRATION_ADMIN,
                ai.ravenroot.api.security.ProtectedResource.owned("embed-registration",
                        EmbedFixtures.REGISTRATION, EmbedFixtures.TENANT)).allowed());

        var wrongScope = new RequestContext("request", "person", PrincipalType.USER, EmbedFixtures.ISSUER,
                EmbedFixtures.TENANT, Set.of(Role.OPERATOR),
                Set.of("ravenroot.embed.session.create", "ravenroot.embed.graph.read"));
        assertFalse(service.decide(wrongScope,
                ai.ravenroot.api.security.AuthorizationAction.EMBED_REGISTRATION_ADMIN,
                ai.ravenroot.api.security.ProtectedResource.owned("embed-registration",
                        EmbedFixtures.REGISTRATION, EmbedFixtures.TENANT)).allowed());
    }

    /** A cross-tenant command is refused even when the injected policy would have allowed it. */
    @Test
    void aPermissivePolicyStillCannotAuthorizeACrossTenantProvision() {
        var recorded = new ArrayList<EmbedRegistrationAuditSink.Event>();
        AuthorizationService permissive = (context, action, resource) ->
                new AuthorizationDecision(true, "test policy allows everything");
        var authority = new InMemoryEmbedRegistrationAuthority();
        var administration = new AuthorizedEmbedRegistrationAdministration(permissive, authority,
                recorded::add, CLOCK);

        var foreignOperator = new RequestContext("request", "operator-2", PrincipalType.USER,
                EmbedFixtures.ISSUER, "tenant-b", Set.of(Role.PLATFORM_ADMIN),
                Set.of("ravenroot.embed.registration.admin"));
        assertThrows(AuthorizationDeniedException.class, () -> administration.provision(foreignOperator,
                EmbedFixtures.command(0, "sha256:a", "start")));
        assertTrue(recorded.stream().anyMatch(event ->
                        event.outcome() == EmbedRegistrationAuditSink.Outcome.DENIED
                                && "TENANT_MISMATCH".equals(event.detail())),
                "the refused cross-tenant attempt is audited");
    }

    @Test
    void everyOutcomeIsAuditedAndNoRecordCarriesASecretOrGraphData() {
        var recorded = new ArrayList<EmbedRegistrationAuditSink.Event>();
        var authority = new InMemoryEmbedRegistrationAuthority();
        var administration = administration(authority, recorded);
        var operator = EmbedFixtures.operator();

        administration.provision(operator, EmbedFixtures.command(0, "sha256:a", "start"));
        administration.provision(operator, EmbedFixtures.command(0, "sha256:b", "start"));
        administration.provision(operator, EmbedFixtures.command(1,
                "sha256:c", "start"));
        administration.revoke(operator,
                new EmbedRevokeCommand(EmbedFixtures.REGISTRATION, EmbedFixtures.TENANT, 2));

        List<String> outcomes = recorded.stream()
                .map(event -> event.phase().name() + "/" + event.outcome().name() + "/" + event.detail())
                .toList();
        assertEquals(List.of(
                "PROVISION/ATTEMPTED/EXPECTED_REVISION", "PROVISION/ALLOWED/PROVISIONED",
                "PROVISION/ATTEMPTED/EXPECTED_REVISION", "PROVISION/CONFLICT/REVISION_CONFLICT",
                "PROVISION/ATTEMPTED/EXPECTED_REVISION", "PROVISION/ALLOWED/PROVISIONED",
                "REVOKE/ATTEMPTED/EXPECTED_REVISION", "REVOKE/ALLOWED/REVOKED"), outcomes);

        for (EmbedRegistrationAuditSink.Event event : recorded) {
            assertEquals(EmbedFixtures.TENANT, event.tenantId());
            assertEquals("operator-1", event.principal());
            assertEquals(EmbedFixtures.REGISTRATION, event.registrationId());
            String flattened = (event.requestId() + " " + event.principal() + " " + event.tenantId()
                    + " " + event.registrationId() + " " + event.detail()).toLowerCase(Locale.ROOT);
            for (String forbidden : List.of("sha256", "graph-a", "parent.example", "start",
                    "node", "digest", "bearer", "ticket", "challenge", "key")) {
                assertFalse(flattened.contains(forbidden),
                        forbidden + " reached an audit record: " + flattened);
            }
        }
    }

    /**
     * The mutant an independent gate built, and the reason the previous version of this suite was
     * covering its own headline guarantee by accident.
     *
     * <p>The sink here fails exactly once -- on the {@code ATTEMPTED} record -- and then works. That
     * is an ordinary shape for an audit backend with a hiccup, and it is the shape under which
     * removing the fail-closed branch produced a durably written registration, no error, and no
     * failing test: the old test's sink threw on every call, so what actually went red was an
     * unguarded terminal record throwing out of the method, not the assertion below.
     *
     * <p>Both assertions matter. The first says the operation was refused; the second says the store
     * was never touched, which is the half a "returns Unavailable" assertion alone cannot see.</p>
     */
    @Test
    void anAttemptThatCannotBeRecordedRefusesEvenWhenTheSinkRecoversImmediately() {
        var recorded = new ArrayList<EmbedRegistrationAuditSink.Event>();
        var authority = new InMemoryEmbedRegistrationAuthority();
        var administration = new AuthorizedEmbedRegistrationAdministration(
                new DefaultAuthorizationService(event -> { }), authority,
                failingOnCall(1, recorded), CLOCK);

        var outcome = administration.provision(EmbedFixtures.operator(),
                EmbedFixtures.command(0, "sha256:a", "start"));

        assertInstanceOf(EmbedProvisionOutcome.Unavailable.class, outcome,
                "an operation whose attempt could not be recorded must be refused");
        assertInstanceOf(EmbedRegistrationResolution.Unavailable.class,
                authority.resolveCurrent(EmbedFixtures.workload(), EmbedFixtures.REGISTRATION),
                "and it must not have reached the store");
        assertTrue(recorded.isEmpty(), "the failed attempt record is the only one that was tried");
    }

    /**
     * The other half of the same defect: a terminal record that cannot be written.
     *
     * <p>Here the operation already happened. Reporting {@code Unavailable} would tell an operator
     * that nothing was written when a registration is live -- which is what the runbook's exit-code
     * table used to promise. {@link EmbedProvisionOutcome.AppliedUnrecorded} says both true things at
     * once, and the store below confirms the first of them.</p>
     */
    @Test
    void aTerminalRecordThatCannotBeWrittenSaysTheChangeTookEffect() {
        var recorded = new ArrayList<EmbedRegistrationAuditSink.Event>();
        var authority = new InMemoryEmbedRegistrationAuthority();
        var administration = new AuthorizedEmbedRegistrationAdministration(
                new DefaultAuthorizationService(event -> { }), authority,
                failingOnCall(2, recorded), CLOCK);

        // assertDoesNotThrow, not a bare call: "reported as an outcome rather than thrown at the
        // caller" is half of what this test asserts, and a bare call would surface a regression as a
        // raw error rather than as this assertion failing with a sentence.
        var outcome = assertDoesNotThrow(() -> administration.provision(EmbedFixtures.operator(),
                        EmbedFixtures.command(0, "sha256:a", "start")),
                "a terminal audit failure must reach the caller as an outcome, not as an exception");

        var applied = assertInstanceOf(EmbedProvisionOutcome.AppliedUnrecorded.class, outcome);
        assertEquals(1, applied.revision());
        var live = assertInstanceOf(EmbedRegistrationResolution.Available.class,
                authority.resolveCurrent(EmbedFixtures.workload(), EmbedFixtures.REGISTRATION));
        assertEquals(1, live.aggregate().revision(),
                "the registration is live; only its terminal audit record is missing");
        assertEquals(List.of("PROVISION/ATTEMPTED/EXPECTED_REVISION"), recorded.stream()
                        .map(event -> event.phase().name() + "/" + event.outcome().name() + "/"
                                + event.detail()).toList(),
                "the attempt was recorded and the terminal record is the one that was lost");
    }

    /** A revocation that committed and could not be recorded must not read as "it did not happen". */
    @Test
    void aRevocationThatCommittedButCouldNotBeRecordedSaysSo() {
        var authority = new InMemoryEmbedRegistrationAuthority();
        assertInstanceOf(EmbedProvisionOutcome.Provisioned.class,
                authority.provision(EmbedFixtures.command(0, "sha256:a", "start")));
        var administration = new AuthorizedEmbedRegistrationAdministration(
                new DefaultAuthorizationService(event -> { }), authority,
                failingOnCall(2, new ArrayList<>()), CLOCK);

        var outcome = assertDoesNotThrow(() -> administration.revoke(EmbedFixtures.operator(),
                        new EmbedRevokeCommand(EmbedFixtures.REGISTRATION, EmbedFixtures.TENANT, 1)),
                "a terminal audit failure must reach the caller as an outcome, not as an exception");

        assertEquals(2, assertInstanceOf(EmbedRevokeOutcome.AppliedUnrecorded.class, outcome).revision());
        assertInstanceOf(EmbedRegistrationResolution.Unavailable.class,
                authority.resolveCurrent(EmbedFixtures.workload(), EmbedFixtures.REGISTRATION),
                "the revocation is real, and the operator must not be told otherwise");
    }

    /**
     * A refused privileged attempt leaves an audit trace.
     *
     * <p>{@code requireOperator} used to throw before writing anything, so the one class of event an
     * audit trail most needs -- a privileged operation refused -- was the only one that left no
     * record, while the cross-tenant refusal beside it did write one.</p>
     */
    @Test
    void aRefusedPrivilegedAttemptIsAuditedBeforeItThrows() {
        var recorded = new ArrayList<EmbedRegistrationAuditSink.Event>();
        var authority = new InMemoryEmbedRegistrationAuthority();
        var administration = administration(authority, recorded);

        assertThrows(AuthorizationDeniedException.class, () -> administration.provision(
                EmbedFixtures.workloadPretendingToBeOperator(),
                EmbedFixtures.command(0, "sha256:a", "start")));

        assertEquals(List.of("PROVISION/DENIED/PRINCIPAL_TYPE"), recorded.stream()
                .map(event -> event.phase().name() + "/" + event.outcome().name() + "/"
                        + event.detail()).toList());
        assertInstanceOf(EmbedRegistrationResolution.Unavailable.class,
                authority.resolveCurrent(EmbedFixtures.workload(), EmbedFixtures.REGISTRATION));

        recorded.clear();
        assertThrows(AuthorizationDeniedException.class, () -> administration.revoke(
                EmbedFixtures.workloadPretendingToBeOperator(),
                new EmbedRevokeCommand(EmbedFixtures.REGISTRATION, EmbedFixtures.TENANT, 1)));
        assertEquals(List.of("REVOKE/DENIED/PRINCIPAL_TYPE"), recorded.stream()
                .map(event -> event.phase().name() + "/" + event.outcome().name() + "/"
                        + event.detail()).toList());
    }

    /** A denial by the central policy is audited too, not only a denial by the principal-type check. */
    @Test
    void aPolicyDenialIsAuditedBeforeItThrows() {
        var recorded = new ArrayList<EmbedRegistrationAuditSink.Event>();
        var administration = administration(new InMemoryEmbedRegistrationAuthority(), recorded);
        var underprivileged = new RequestContext("request", "person", PrincipalType.USER,
                EmbedFixtures.ISSUER, EmbedFixtures.TENANT, Set.of(Role.VIEWER),
                Set.of("ravenroot.embed.registration.admin"));

        assertThrows(AuthorizationDeniedException.class, () -> administration.provision(
                underprivileged, EmbedFixtures.command(0, "sha256:a", "start")));
        assertEquals(List.of("PROVISION/DENIED/POLICY_DENIED"), recorded.stream()
                .map(event -> event.phase().name() + "/" + event.outcome().name() + "/"
                        + event.detail()).toList());
    }

    /**
     * The one principal for whom the monitor's own tenant check is not defence in depth.
     *
     * <p>{@code DefaultAuthorizationService} skips the tenant comparison entirely when the context
     * carries {@code PLATFORM_ADMIN}, which grants {@code EMBED_REGISTRATION_ADMIN} to that role.
     * So for a platform administrator this check is the whole of the cross-tenant defence, and a
     * comment calling it redundant is how it gets deleted later.</p>
     */
    @Test
    void aPlatformAdminIsStoppedByTheMonitorsOwnTenantCheck() {
        var recorded = new ArrayList<EmbedRegistrationAuditSink.Event>();
        var authority = new InMemoryEmbedRegistrationAuthority();
        var administration = administration(authority, recorded);
        var platformAdmin = new RequestContext("request", "platform-operator", PrincipalType.USER,
                EmbedFixtures.ISSUER, "tenant-elsewhere", Set.of(Role.PLATFORM_ADMIN),
                Set.of("ravenroot.embed.registration.admin"));

        var decision = new DefaultAuthorizationService(event -> { }).decide(platformAdmin,
                ai.ravenroot.api.security.AuthorizationAction.EMBED_REGISTRATION_ADMIN,
                ai.ravenroot.api.security.ProtectedResource.owned("embed-registration",
                        EmbedFixtures.REGISTRATION, EmbedFixtures.TENANT));
        assertTrue(decision.allowed(),
                "the central policy does allow this: it exempts PLATFORM_ADMIN from the tenant check");

        assertThrows(AuthorizationDeniedException.class, () -> administration.provision(platformAdmin,
                EmbedFixtures.command(0, "sha256:a", "start")));
        assertTrue(recorded.stream().anyMatch(event ->
                "TENANT_MISMATCH".equals(event.detail())));
        assertInstanceOf(EmbedRegistrationResolution.Unavailable.class,
                authority.resolveCurrent(EmbedFixtures.workload(), EmbedFixtures.REGISTRATION));
    }

    /** «detail is an enum name, never free text» is now a constructor rule rather than a habit. */
    @Test
    void anAuditDetailThatIsNotATokenIsRefusedByTheEventItself() {
        for (String freeText : List.of("PROVISIONED:sha256:abcdef", "https://parent.example",
                "provisioned", "PINNED THE SNAPSHOT", "graph-a/v3")) {
            assertThrows(IllegalArgumentException.class, () -> new EmbedRegistrationAuditSink.Event(
                    EmbedFixtures.AT, "request", EmbedFixtures.TENANT, "operator-1",
                    EmbedFixtures.REGISTRATION, 1, EmbedRegistrationAuditSink.Phase.PROVISION,
                    EmbedRegistrationAuditSink.Outcome.ALLOWED, freeText), freeText);
        }
        assertEquals("PROVISIONED", new EmbedRegistrationAuditSink.Event(EmbedFixtures.AT, "request",
                EmbedFixtures.TENANT, "operator-1", EmbedFixtures.REGISTRATION, 1,
                EmbedRegistrationAuditSink.Phase.PROVISION,
                EmbedRegistrationAuditSink.Outcome.ALLOWED, "PROVISIONED").detail());
    }

    /**
     * A sink that throws on the {@code nth} call and records every other one.
     *
     * <p>The call index is what makes the two halves of the fail-closed guarantee separable: 1 is the
     * attempt record, 2 is the terminal one.</p>
     */
    private static EmbedRegistrationAuditSink failingOnCall(int failing,
                                                            List<EmbedRegistrationAuditSink.Event> recorded) {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        return event -> {
            if (calls.incrementAndGet() == failing) {
                throw new IllegalStateException("audit backend hiccup");
            }
            recorded.add(event);
        };
    }

    /** Fail-closed: an attempt that cannot be recorded is an operation that does not happen. */
    @Test
    void anUnwritableAuditTrailRefusesTheOperationBeforeTheStoreIsTouched() {
        var authority = new InMemoryEmbedRegistrationAuthority();
        var administration = new AuthorizedEmbedRegistrationAdministration(
                new DefaultAuthorizationService(event -> { }), authority,
                event -> {
                    throw new IllegalStateException("audit volume is full");
                }, CLOCK);

        assertInstanceOf(EmbedProvisionOutcome.Unavailable.class, administration.provision(
                EmbedFixtures.operator(), EmbedFixtures.command(0, "sha256:a", "start")));
        assertInstanceOf(EmbedRegistrationResolution.Unavailable.class,
                authority.resolveCurrent(EmbedFixtures.workload(), EmbedFixtures.REGISTRATION),
                "the store must not have been written when the attempt could not be recorded");
    }

    @Test
    void aStoreFailureIsReportedAsUnavailableAndAuditedAsFailed() {
        var recorded = new ArrayList<EmbedRegistrationAuditSink.Event>();
        var administration = new AuthorizedEmbedRegistrationAdministration(
                new DefaultAuthorizationService(event -> { }),
                new ThrowingAuthority(), recorded::add, CLOCK);

        assertInstanceOf(EmbedProvisionOutcome.Unavailable.class, administration.provision(
                EmbedFixtures.operator(), EmbedFixtures.command(0, "sha256:a", "start")));
        assertTrue(recorded.stream().anyMatch(event ->
                event.outcome() == EmbedRegistrationAuditSink.Outcome.FAILED
                        && "STORE_FAILURE".equals(event.detail())));
    }

    private static AuthorizedEmbedRegistrationAdministration administration(
            EmbedRegistrationAuthority authority, List<EmbedRegistrationAuditSink.Event> recorded) {
        return new AuthorizedEmbedRegistrationAdministration(
                new DefaultAuthorizationService(event -> { }), authority, recorded::add, CLOCK);
    }

    private static final class ThrowingAuthority implements EmbedRegistrationAuthority {
        @Override
        public EmbedProvisionOutcome provision(EmbedProvisionCommand command) {
            throw new IllegalStateException("/var/lib/ravenroot/embed-registrations.db is locked");
        }

        @Override
        public EmbedRevokeOutcome revoke(EmbedRevokeCommand command) {
            throw new IllegalStateException("/var/lib/ravenroot/embed-registrations.db is locked");
        }

        @Override
        public EmbedRegistrationResolution resolveCurrent(RequestContext workload, String registrationId) {
            return EmbedRegistrationResolution.Temporary.INSTANCE;
        }

        @Override
        public boolean isCurrent(EmbedRegistrationAggregate captured) {
            return false;
        }
    }
}
