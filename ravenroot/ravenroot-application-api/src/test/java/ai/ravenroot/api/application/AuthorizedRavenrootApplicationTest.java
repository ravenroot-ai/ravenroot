package ai.ravenroot.api.application;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.payload.PayloadEnvelope;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.ArtifactTestResult;
import ai.ravenroot.api.programming.ArtifactLifecycleAuditSink;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.security.AuthorizationDeniedException;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizedRavenrootApplicationTest {
    @Test
    void sourceSessionsRequireDistinctPermissionsAndAlwaysDelegateTheAuthenticatedTenant() throws Exception {
        var raw = new FakeApplication();
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true);
        var operator = context("tenant-a", Role.OPERATOR,
                "ravenroot.execute", "ravenroot.observe", "ravenroot.execution.control");

        SourceSessionStatus started = facade.startSourceSession(
                operator, "session", new ByteArrayInputStream(new byte[0]));
        assertEquals(SourceSessionState.LISTENING, started.state());
        assertTrue(facade.sourceSession(operator, "session").isPresent());
        assertTrue(facade.stopSourceSession(operator, "session").toCompletableFuture().get().isPresent());
        assertEquals(List.of("tenant-a", "tenant-a", "tenant-a"), raw.observedSourceSessionTenants);

        assertThrows(AuthorizationDeniedException.class, () -> facade.startSourceSession(
                context("tenant-a", Role.OPERATOR, "ravenroot.observe"), "denied",
                new ByteArrayInputStream(new byte[0])));
        assertThrows(AuthorizationDeniedException.class, () -> facade.sourceSession(
                context("tenant-a", Role.OPERATOR, "ravenroot.execute"), "session"));
        assertThrows(AuthorizationDeniedException.class, () -> facade.stopSourceSession(
                context("tenant-a", Role.OPERATOR, "ravenroot.execution.read"), "session"));
        assertEquals(3, raw.observedSourceSessionTenants.size(),
                "authorization must fail before the tenant-scoped delegate is reached");

        assertTrue(facade.sourceSession(context("tenant-b", Role.OPERATOR,
                "ravenroot.observe"), "session").isEmpty(),
                "the same id in another tenant must not disclose the sibling");
        assertEquals("tenant-b", raw.observedSourceSessionTenants.getLast());
    }
    /**
     * The process-local deployment lifecycle resolves its tenant from the authenticated
     * context and from nowhere else, and each of the seven operations is gated by its own action.
     *
     * <p>The structural half of the claim is checked by what these signatures <em>cannot</em> do:
     * none of them accepts a tenant, so the list of tenants the delegate observed can only ever be
     * the callers' own. A sibling asking for the same id gets the same empty answer an unknown id
     * gets.</p>
     */
    @Test
    void localDeploymentsRequireDistinctPermissionsAndAlwaysDelegateTheAuthenticatedTenant() throws Exception {
        var raw = new FakeApplication();
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true);
        var operator = context("tenant-a", Role.OPERATOR,
                "ravenroot.execute", "ravenroot.observe", "ravenroot.execution.control");

        assertEquals(LocalDeploymentState.REGISTERED, facade.registerLocalDeployment(
                operator, "deployment", new ByteArrayInputStream(new byte[0])).state());
        assertEquals(1, facade.localDeployments(operator).size());
        assertTrue(facade.localDeployment(operator, "deployment").isPresent());
        assertEquals(LocalDeploymentState.READY, facade.startLocalDeployment(operator, "deployment")
                .toCompletableFuture().get().orElseThrow().state());
        assertEquals(LocalDeploymentState.STOPPED, facade.stopLocalDeployment(operator, "deployment")
                .toCompletableFuture().get().orElseThrow().state());
        assertEquals(LocalDeploymentState.READY, facade.restartLocalDeployment(operator, "deployment")
                .toCompletableFuture().get().orElseThrow().state());
        assertEquals(LocalDeploymentState.STOPPED, facade.undeployLocalDeployment(operator, "deployment")
                .toCompletableFuture().get().orElseThrow().state());
        assertEquals(List.of("tenant-a", "tenant-a", "tenant-a", "tenant-a", "tenant-a", "tenant-a", "tenant-a"),
                raw.observedDeploymentTenants);

        assertThrows(AuthorizationDeniedException.class, () -> facade.registerLocalDeployment(
                context("tenant-a", Role.OPERATOR, "ravenroot.observe"), "denied",
                new ByteArrayInputStream(new byte[0])));
        assertThrows(AuthorizationDeniedException.class, () -> facade.localDeployment(
                context("tenant-a", Role.OPERATOR, "ravenroot.execute"), "deployment"));
        assertThrows(AuthorizationDeniedException.class, () -> facade.stopLocalDeployment(
                context("tenant-a", Role.OPERATOR, "ravenroot.observe"), "deployment"));
        assertThrows(AuthorizationDeniedException.class, () -> facade.undeployLocalDeployment(
                context("tenant-a", Role.OPERATOR, "ravenroot.observe"), "deployment"));
        assertEquals(7, raw.observedDeploymentTenants.size(),
                "authorization must fail before the tenant-scoped delegate is reached");

        var sibling = context("tenant-b", Role.OPERATOR, "ravenroot.observe", "ravenroot.execution.control");
        assertTrue(facade.localDeployment(sibling, "deployment").isEmpty(),
                "the same id in another tenant must not disclose the sibling");
        assertTrue(facade.localDeployments(sibling).isEmpty());
        assertTrue(facade.undeployLocalDeployment(sibling, "deployment").toCompletableFuture().get().isEmpty());
        assertEquals("tenant-b", raw.observedDeploymentTenants.getLast());
    }

    /**
     * The API-02 surface has three public constructors. The widest one additionally requires an
     * explicit {@link ExecutionControlAuditSink} for cancel/drain's own CONTROL-category records, so
     * the count below is deliberately 3 rather than evidence of a regression this guard failed to
     * catch: the two invariants that actually matter (every constructor still requires
     * an explicit {@link ArtifactLifecycleAuditSink}; no constructor short enough to be a silent no-op)
     * both still hold, and the widest constructor makes {@link ExecutionControlAuditSink} exactly as
     * mandatory-and-explicit as {@link ArtifactLifecycleAuditSink} already was -- see this class's own
     * layered-constructor Javadoc.
     */
    @Test
    void testPassthroughUsesTheExistingExecutionAuthorizationAndForwardsPolicyExplicitly() {
        var raw = new FakeApplication();
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true);

        assertThrows(AuthorizationDeniedException.class, () -> facade.startGraphMl(
                context("tenant-a", Role.OPERATOR, "ravenroot.observe"),
                new ByteArrayInputStream(new byte[0]), PayloadEnvelope.legacyText("payload"),
                PayloadLimits.DEFAULTS, ExecutionPolicy.TEST_PASSTHROUGH));
        assertEquals(0, raw.startCalls, "authorization must fail before the delegate sees Test");

        facade.startGraphMl(context("tenant-a", Role.OPERATOR, "ravenroot.execute"),
                new ByteArrayInputStream(new byte[0]), PayloadEnvelope.legacyText("payload"),
                PayloadLimits.DEFAULTS, ExecutionPolicy.TEST_PASSTHROUGH);
        assertEquals(ExecutionPolicy.TEST_PASSTHROUGH, raw.observedPolicy);
    }

    @Test
    void unsupportedTestPolicyFailsClosedInsteadOfFallingBackToStandard() {
        RavenrootApplication legacy = new LegacyApplication();
        assertThrows(UnsupportedOperationException.class, () -> legacy.startGraphMl(
                new ai.ravenroot.api.security.SecurityContext("request", "tenant", "subject",
                        PrincipalType.USER, "issuer"), UUID.randomUUID(),
                new ByteArrayInputStream(new byte[0]), "payload", ExecutionPolicy.TEST_PASSTHROUGH));
    }

    @Test
    void exposesOnlyExplicitLifecycleAuditConstructors() {
        var constructors = AuthorizedRavenrootApplication.class.getConstructors();

        assertEquals(3, constructors.length);
        assertTrue(Arrays.stream(constructors).allMatch(constructor -> Arrays.stream(constructor.getParameterTypes())
                .anyMatch(ArtifactLifecycleAuditSink.class::equals)),
                "every public facade constructor must require an explicit lifecycle audit sink");
        assertFalse(Arrays.stream(constructors).anyMatch(constructor -> constructor.getParameterCount() <= 2),
                "the legacy silent-noop constructors must not remain public API");
    }

    /** The mirror image of the guard above, for the execution-control audit sink. */
    @Test
    void widestConstructorRequiresAnExplicitExecutionControlAuditSink() {
        var constructors = AuthorizedRavenrootApplication.class.getConstructors();
        assertTrue(Arrays.stream(constructors).anyMatch(constructor -> Arrays.stream(constructor.getParameterTypes())
                        .anyMatch(ExecutionControlAuditSink.class::equals)),
                "at least one public facade constructor must require an explicit execution-control audit sink");
    }

    @Test
    void recordsOwnershipAndEnforcesObjectLevelTenantChecks() {
        var raw = new FakeApplication();
        var audit = new ArrayList<ai.ravenroot.api.security.AuthorizationAuditEvent>();
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(audit::add),
                event -> { }, true);
        var developerA = context("tenant-a", Role.DEVELOPER, "ravenroot.artifact.manage");
        var developerB = context("tenant-b", Role.DEVELOPER, "ravenroot.artifact.manage");

        GeneratedArtifact created = facade.createProgramArtifact(developerA, "javascript", "source",
                Map.of("name", "safe"));
        assertEquals("tenant-a", created.metadata().get(AuthorizedRavenrootApplication.OWNER_TENANT_METADATA));
        assertEquals("issuer|USER|alice",
                created.metadata().get(AuthorizedRavenrootApplication.CREATOR_METADATA));
        facade.validateProgramArtifact(developerA, created.id());
        assertThrows(AuthorizationDeniedException.class,
                () -> facade.validateProgramArtifact(developerB, created.id()));

        raw.artifacts.add(artifact("legacy", Map.of()));
        assertThrows(AuthorizationDeniedException.class,
                () -> facade.validateProgramArtifact(developerA, "legacy"));
        assertThrows(IllegalArgumentException.class, () -> facade.createProgramArtifact(developerA,
                "javascript", "source",
                Map.of(AuthorizedRavenrootApplication.OWNER_TENANT_METADATA, "tenant-b")));
        assertThrows(IllegalArgumentException.class, () -> facade.createProgramArtifact(developerA,
                "javascript", "source", Map.of("evidence.approved.approver", "spoofed")));
        assertEquals(6, audit.size());
    }

    @Test
    void preventsDeveloperFromEscalatingIntoApproval() {
        var raw = new FakeApplication();
        raw.artifacts.add(artifact("owned",
                Map.of(AuthorizedRavenrootApplication.OWNER_TENANT_METADATA, "tenant-a")));
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true);

        assertThrows(AuthorizationDeniedException.class, () -> facade.approveProgramArtifact(
                context("tenant-a", Role.DEVELOPER, "ravenroot.artifact.approve"), "owned", "self approval"));
    }

    @Test
    void enforcesDualControlAndRecordsTrustedApprovalEvidence() {
        var raw = new FakeApplication();
        raw.artifacts.add(artifact("tested", ArtifactState.TESTED, Map.of(
                AuthorizedRavenrootApplication.OWNER_TENANT_METADATA, "tenant-a",
                AuthorizedRavenrootApplication.CREATOR_METADATA, "issuer|USER|alice")));
        var lifecycleAudit = new ArrayList<ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent>();
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                lifecycleAudit::add, true);

        assertThrows(AuthorizationDeniedException.class, () -> facade.approveProgramArtifact(
                context("alice", "tenant-a", Role.APPROVER, "ravenroot.artifact.approve"),
                "tested", "creator must not approve"));
        GeneratedArtifact approved = facade.approveProgramArtifact(
                context("bob", "tenant-a", Role.APPROVER, "ravenroot.artifact.approve"),
                "tested", "peer approval");
        assertEquals("issuer|USER|bob",
                approved.metadata().get(AuthorizedRavenrootApplication.APPROVER_METADATA));
        assertEquals("peer approval", approved.metadata().get("evidence.approved.reason"));
        assertThrows(AuthorizationDeniedException.class, () -> facade.activateProgramArtifact(
                context("alice", "tenant-a", Role.APPROVER, "ravenroot.artifact.activate"), "tested"));
        assertEquals(ArtifactState.ACTIVE, facade.activateProgramArtifact(
                context("bob", "tenant-a", Role.APPROVER, "ravenroot.artifact.activate"), "tested").state());
        // SEC-12 adds the two SUCCEEDED records. Every operation is closed by a terminal
        // record: the denials by DENIED, and the approval and the activation that actually
        // took effect by SUCCEEDED. Previously those two were evidenced only by an ATTEMPT nothing
        // contradicted, which is an inference from absence and reads identically to a process killed
        // mid-approval. Binding approver and activation immutably requires the positive record, so
        // the positive record is the point rather than noise in the sequence.
        assertEquals(List.of(
                        ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent.Disposition.ATTEMPT,
                        ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent.Disposition.DENIED,
                        ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent.Disposition.ATTEMPT,
                        ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent.Disposition.SUCCEEDED,
                        ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent.Disposition.ATTEMPT,
                        ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent.Disposition.DENIED,
                        ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent.Disposition.ATTEMPT,
                        ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent.Disposition.SUCCEEDED),
                lifecycleAudit.stream()
                        .map(ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent::disposition).toList());
        assertEquals(List.of(ArtifactState.APPROVED, ArtifactState.ACTIVE),
                lifecycleAudit.stream()
                        .filter(event -> event.disposition()
                                == ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent.Disposition.SUCCEEDED)
                        .map(ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent::state).toList(),
                "each success record carries the state actually reached, so the trail states the "
                        + "outcome instead of restating the intent");
        assertEquals(List.of("ARTIFACT_APPROVE", "ARTIFACT_APPROVE", "ARTIFACT_APPROVE", "ARTIFACT_APPROVE",
                        "ARTIFACT_ACTIVATE", "ARTIFACT_ACTIVATE", "ARTIFACT_ACTIVATE", "ARTIFACT_ACTIVATE"),
                lifecycleAudit.stream().map(ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent::action).toList());
    }

    @Test
    void activationWithoutTrustedApprovalProducesAttemptAndDeniedAudit() {
        var raw = new FakeApplication();
        raw.artifacts.add(artifact("approved", ArtifactState.APPROVED, Map.of(
                AuthorizedRavenrootApplication.OWNER_TENANT_METADATA, "tenant-a",
                AuthorizedRavenrootApplication.CREATOR_METADATA, "issuer|USER|alice")));
        var lifecycleAudit = new ArrayList<ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent>();
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                lifecycleAudit::add, true);

        assertThrows(AuthorizationDeniedException.class, () -> facade.activateProgramArtifact(
                context("bob", "tenant-a", Role.APPROVER, "ravenroot.artifact.activate"), "approved"));
        assertEquals(List.of(
                        ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent.Disposition.ATTEMPT,
                        ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent.Disposition.DENIED),
                lifecycleAudit.stream()
                        .map(ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent::disposition).toList());
        assertEquals(ArtifactState.APPROVED, raw.find("approved").state());
    }

    @Test
    void lifecycleAuditFailsClosedForEveryMutationAndCrossTenantLookupDoesNotEnumerate() {
        var raw = new FakeApplication();
        Map<String, String> owned = Map.of(
                AuthorizedRavenrootApplication.OWNER_TENANT_METADATA, "tenant-a",
                AuthorizedRavenrootApplication.CREATOR_METADATA, "issuer|USER|alice");
        raw.artifacts.add(artifact("generated", owned));
        raw.artifacts.add(artifact("validated", ArtifactState.VALIDATED, owned));
        raw.artifacts.add(artifact("tested", ArtifactState.TESTED, owned));
        raw.artifacts.add(artifact("approved", ArtifactState.APPROVED, Map.of(
                AuthorizedRavenrootApplication.OWNER_TENANT_METADATA, "tenant-a",
                AuthorizedRavenrootApplication.CREATOR_METADATA, "issuer|USER|alice",
                AuthorizedRavenrootApplication.APPROVER_METADATA, "issuer|USER|bob")));
        raw.artifacts.add(artifact("active", ArtifactState.ACTIVE, owned));
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { throw new IllegalStateException("offline"); }, true);
        assertThrows(AuthorizationDeniedException.class, () -> facade.createProgramArtifact(
                context("alice", "tenant-a", Role.DEVELOPER, "ravenroot.artifact.manage"),
                "javascript", "source", Map.of()));
        assertThrows(AuthorizationDeniedException.class, () -> facade.validateProgramArtifact(
                context("alice", "tenant-a", Role.DEVELOPER, "ravenroot.artifact.manage"), "generated"));
        assertThrows(AuthorizationDeniedException.class, () -> facade.testProgramArtifact(
                context("alice", "tenant-a", Role.DEVELOPER, "ravenroot.artifact.manage"), "validated", "payload"));
        assertThrows(AuthorizationDeniedException.class, () -> facade.approveProgramArtifact(
                context("bob", "tenant-a", Role.APPROVER, "ravenroot.artifact.approve"), "tested", "review"));
        assertThrows(AuthorizationDeniedException.class, () -> facade.activateProgramArtifact(
                context("bob", "tenant-a", Role.APPROVER, "ravenroot.artifact.activate"), "approved"));
        assertThrows(AuthorizationDeniedException.class, () -> facade.retireProgramArtifact(
                context("bob", "tenant-a", Role.APPROVER, "ravenroot.artifact.retire"), "active", "retire"));
        assertEquals(0, raw.createCalls);
        assertEquals(0, raw.validationCalls);
        assertEquals(0, raw.testCalls);
        assertEquals(0, raw.approvalCalls);
        assertEquals(0, raw.activationCalls);
        assertEquals(0, raw.retirementCalls);
        assertEquals(ArtifactState.GENERATED, raw.find("generated").state());
        assertEquals(ArtifactState.VALIDATED, raw.find("validated").state());
        assertEquals(ArtifactState.TESTED, raw.find("tested").state());
        assertEquals(ArtifactState.APPROVED, raw.find("approved").state());
        assertEquals(ArtifactState.ACTIVE, raw.find("active").state());

        var availableAudit = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true);
        assertThrows(AuthorizationDeniedException.class, () -> availableAudit.validateProgramArtifact(
                context("mallory", "tenant-b", Role.DEVELOPER, "ravenroot.artifact.manage"), "generated"));
        assertThrows(AuthorizationDeniedException.class, () -> availableAudit.validateProgramArtifact(
                context("mallory", "tenant-b", Role.DEVELOPER, "ravenroot.artifact.manage"), "missing"));
    }

    @Test
    void retireHasDistinctRoleScopeAndTrustedEvidence() {
        var raw = new FakeApplication();
        raw.artifacts.add(artifact("active", ArtifactState.ACTIVE, Map.of(
                AuthorizedRavenrootApplication.OWNER_TENANT_METADATA, "tenant-a",
                AuthorizedRavenrootApplication.CREATOR_METADATA, "issuer|USER|alice")));
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true);
        assertThrows(AuthorizationDeniedException.class, () -> facade.retireProgramArtifact(
                context("bob", "tenant-a", Role.APPROVER, "ravenroot.artifact.activate"),
                "active", "wrong scope"));
        GeneratedArtifact retired = facade.retireProgramArtifact(
                context("bob", "tenant-a", Role.APPROVER, "ravenroot.artifact.retire"),
                "active", "superseded");
        assertEquals(ArtifactState.RETIRED, retired.state());
        assertEquals("issuer|USER|bob", retired.metadata().get("evidence.retired.retiredBy"));
    }

    @Test
    void listsOnlyKnownOwnershipWithoutLeakingCrossTenantOrLegacyIdentifiers() {
        var raw = new FakeApplication();
        raw.artifacts.add(artifact("tenant-a-id",
                Map.of(AuthorizedRavenrootApplication.OWNER_TENANT_METADATA, "tenant-a")));
        raw.artifacts.add(artifact("tenant-b-id",
                Map.of(AuthorizedRavenrootApplication.OWNER_TENANT_METADATA, "tenant-b")));
        raw.artifacts.add(artifact("legacy-secret-id", Map.of()));
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true);

        var tenantArtifacts = facade.programArtifacts(
                context("tenant-a", Role.VIEWER, "ravenroot.artifact.read"));
        assertEquals(List.of("tenant-a-id"), tenantArtifacts.stream().map(GeneratedArtifact::id).toList());

        var platformArtifacts = facade.programArtifacts(
                context("platform", Role.PLATFORM_ADMIN, "ravenroot.artifact.read"));
        assertEquals(List.of("tenant-a-id", "tenant-b-id"),
                platformArtifacts.stream().map(GeneratedArtifact::id).toList());
    }

    /**
     * The tenant a result read is scoped to must come from the authenticated request and from
     * nothing the caller supplies.
     *
     * <p>Asserted on the delegate's observed argument rather than on the returned value, because the
     * claim under test is about <em>what is asked of the store</em>, not about what is filtered out
     * of an answer. A test that only checked the result could still pass if the implementation
     * fetched every tenant's execution and then excluded the wrong ones — which is precisely the
     * shape this design exists to rule out.</p>
     */
    @Test
    void aResultReadIsScopedToTheAuthenticatedTenantAndRequiresExecutionRead() {
        var raw = new FakeApplication();
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true);
        UUID executionId = UUID.randomUUID();

        var lookup = facade.executionResult(
                context("tenant-a", Role.OPERATOR, "ravenroot.observe"), executionId);

        assertEquals(List.of("tenant-a"), raw.observedResultTenants,
                "the delegate must be asked for the authenticated tenant, not for everything");
        assertEquals(executionId, lookup.executionId());

        // A principal without the read scope is refused before the id is used for anything at all.
        assertThrows(AuthorizationDeniedException.class, () -> facade.executionResult(
                context("tenant-a", Role.OPERATOR, "ravenroot.execute"), executionId));
        assertEquals(List.of("tenant-a"), raw.observedResultTenants,
                "a denied principal must not reach the delegate, or the refusal leaks existence");
    }

    @Test
    void filtersReplayAndLiveExecutionEventsByRecordedSubmissionOwnership() throws Exception {
        var raw = new FakeApplication();
        UUID executionA = UUID.randomUUID();
        UUID executionB = UUID.randomUUID();
        UUID unknownExecution = UUID.randomUUID();
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true, 4_096, ids(executionA, executionB));
        var operatorA = context("tenant-a", Role.OPERATOR, "ravenroot.execute");
        var operatorB = context("tenant-b", Role.OPERATOR, "ravenroot.execute");
        facade.startGraphMl(operatorA, new ByteArrayInputStream(new byte[0]), "");
        facade.startGraphMl(operatorB, new ByteArrayInputStream(new byte[0]), "");
        raw.events.add(event(1, executionA));
        raw.events.add(event(2, executionB));
        raw.events.add(event(3, unknownExecution));

        var observerA = context("tenant-a", Role.OPERATOR, "ravenroot.observe");
        var observerB = context("tenant-b", Role.OPERATOR, "ravenroot.observe");
        var platform = context("platform", Role.PLATFORM_ADMIN, "ravenroot.observe");
        assertEquals(List.of(executionA),
                facade.executionEventsAfter(observerA, 0).stream().map(ExecutionEvent::executionId).toList());
        assertEquals(List.of(executionB),
                facade.executionEventsAfter(observerB, 0).stream().map(ExecutionEvent::executionId).toList());
        assertEquals(List.of(executionA, executionB),
                facade.executionEventsAfter(platform, 0).stream().map(ExecutionEvent::executionId).toList());

        var liveA = new ArrayList<ExecutionEvent>();
        var livePlatform = new ArrayList<ExecutionEvent>();
        try (var ignoredA = facade.subscribeToExecutionEvents(observerA, liveA::add);
             var ignoredPlatform = facade.subscribeToExecutionEvents(platform, livePlatform::add)) {
            raw.emit(event(4, executionB));
            raw.emit(event(5, unknownExecution));
            raw.emit(event(6, executionA));
        }
        assertEquals(List.of(executionA), liveA.stream().map(ExecutionEvent::executionId).toList());
        assertEquals(List.of(executionB, executionA),
                livePlatform.stream().map(ExecutionEvent::executionId).toList());
    }

    @Test
    void authorDiagnosticsCannotCrossTenantReplayOrLiveSubscriptions() throws Exception {
        var raw = new FakeApplication();
        UUID executionA = UUID.randomUUID();
        UUID executionB = UUID.randomUUID();
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true, 4_096, ids(executionA, executionB));
        facade.startGraphMl(context("tenant-a", Role.OPERATOR, "ravenroot.execute"),
                new ByteArrayInputStream(new byte[0]), "");
        facade.startGraphMl(context("tenant-b", Role.OPERATOR, "ravenroot.execute"),
                new ByteArrayInputStream(new byte[0]), "");
        var eventA = diagnosticEvent(1, executionA, "tenant-a", "tenant-a-diagnostic");
        var eventB = diagnosticEvent(2, executionB, "tenant-b", "tenant-b-diagnostic");
        raw.events.addAll(List.of(eventA, eventB));

        var observerA = context("tenant-a", Role.OPERATOR, "ravenroot.observe");
        var observerB = context("tenant-b", Role.OPERATOR, "ravenroot.observe");
        assertEquals(List.of("tenant-a-diagnostic"), facade.executionEventsAfter(observerA, 0).stream()
                .map(event -> event.authorMessage().value()).toList());
        assertEquals(List.of("tenant-b-diagnostic"), facade.executionEventsAfter(observerB, 0).stream()
                .map(event -> event.authorMessage().value()).toList());

        var liveA = new ArrayList<ExecutionEvent>();
        var liveB = new ArrayList<ExecutionEvent>();
        try (var ignoredA = facade.subscribeToExecutionEvents(observerA, liveA::add);
             var ignoredB = facade.subscribeToExecutionEvents(observerB, liveB::add)) {
            raw.emit(eventB);
            raw.emit(eventA);
        }
        assertEquals(List.of("tenant-a-diagnostic"), liveA.stream()
                .map(event -> event.authorMessage().value()).toList());
        assertEquals(List.of("tenant-b-diagnostic"), liveB.stream()
                .map(event -> event.authorMessage().value()).toList());
    }

    @Test
    void publishesTheEarliestSynchronousEventOnlyToItsTenantAndPlatform() throws Exception {
        var raw = new FakeApplication();
        UUID execution = UUID.randomUUID();
        raw.publishEventDuringStart = true;
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true, 4_096, ids(execution));
        var tenantA = context("tenant-a", Role.OPERATOR, "ravenroot.observe");
        var tenantB = context("tenant-b", Role.OPERATOR, "ravenroot.observe");
        var platform = context("platform", Role.PLATFORM_ADMIN, "ravenroot.observe");
        var liveA = new ArrayList<ExecutionEvent>();
        var liveB = new ArrayList<ExecutionEvent>();
        var livePlatform = new ArrayList<ExecutionEvent>();

        try (var ignoredA = facade.subscribeToExecutionEvents(tenantA, liveA::add);
             var ignoredB = facade.subscribeToExecutionEvents(tenantB, liveB::add);
             var ignoredPlatform = facade.subscribeToExecutionEvents(platform, livePlatform::add)) {
            ExecutionSubmission submission = facade.startGraphMl(
                    context("tenant-a", Role.OPERATOR, "ravenroot.execute"),
                    new ByteArrayInputStream(new byte[0]), "payload");
            assertEquals(execution, submission.executionId());
        }

        assertEquals(List.of(execution), liveA.stream().map(ExecutionEvent::executionId).toList());
        assertTrue(liveB.isEmpty(), "another tenant must not see the first synchronous event");
        assertEquals(List.of(execution), livePlatform.stream().map(ExecutionEvent::executionId).toList());
        assertEquals(liveA, facade.executionEventsAfter(tenantA, 0));
        assertEquals(liveB, facade.executionEventsAfter(tenantB, 0));
        assertEquals(livePlatform, facade.executionEventsAfter(platform, 0));
    }

    @Test
    void rollsBackFailedStartupAndRejectsIdentifierCollisionBeforeCallingDelegate() {
        var raw = new FakeApplication();
        UUID retained = UUID.randomUUID();
        UUID failed = UUID.randomUUID();
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true, 1, ids(retained, failed, retained));
        var tenantAStart = context("tenant-a", Role.OPERATOR, "ravenroot.execute");
        var tenantBStart = context("tenant-b", Role.OPERATOR, "ravenroot.execute");

        facade.startGraphMl(tenantAStart, new ByteArrayInputStream(new byte[0]), "");
        raw.startFailure = new IllegalStateException("delegate startup failed");
        assertThrows(IllegalStateException.class,
                () -> facade.startGraphMl(tenantBStart, new ByteArrayInputStream(new byte[0]), ""));
        assertEquals(2, raw.startCalls);
        assertThrows(IllegalStateException.class,
                () -> facade.startGraphMl(tenantBStart, new ByteArrayInputStream(new byte[0]), ""));
        assertEquals(2, raw.startCalls, "an ownership collision must fail before trusted startup");

        raw.events.add(event(1, retained));
        raw.events.add(event(2, failed));
        var tenantARead = context("tenant-a", Role.OPERATOR, "ravenroot.observe");
        var tenantBRead = context("tenant-b", Role.OPERATOR, "ravenroot.observe");
        var platform = context("platform", Role.PLATFORM_ADMIN, "ravenroot.observe");
        assertEquals(List.of(retained),
                facade.executionEventsAfter(tenantARead, 0).stream().map(ExecutionEvent::executionId).toList());
        assertTrue(facade.executionEventsAfter(tenantBRead, 0).isEmpty());
        assertEquals(List.of(retained),
                facade.executionEventsAfter(platform, 0).stream().map(ExecutionEvent::executionId).toList());
    }

    @Test
    void dropsEventsAfterDeterministicOwnershipEviction() {
        var raw = new FakeApplication();
        UUID evicted = UUID.randomUUID();
        UUID retained = UUID.randomUUID();
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true, 1, ids(evicted, retained));
        var operator = context("tenant-a", Role.OPERATOR, "ravenroot.execute");
        facade.startGraphMl(operator, new ByteArrayInputStream(new byte[0]), "");
        facade.startGraphMl(operator, new ByteArrayInputStream(new byte[0]), "");
        raw.events.add(event(1, evicted));
        raw.events.add(event(2, retained));

        var observer = context("tenant-a", Role.OPERATOR, "ravenroot.observe");
        assertEquals(List.of(retained),
                facade.executionEventsAfter(observer, 0).stream().map(ExecutionEvent::executionId).toList());
    }

    @Test
    void durableReplayIsScopedToTheAuthenticatedTenantByConstructionNotByFiltering() {
        var raw = new FakeApplication();
        raw.durableJournalAvailable = true;
        raw.durableEventsByTenant.put("tenant-a", List.of(durableEvent(1, "tenant-a"), durableEvent(2, "tenant-a")));
        raw.durableEventsByTenant.put("tenant-b", List.of(durableEvent(1, "tenant-b")));
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true);
        var observerA = context("tenant-a", Role.OPERATOR, "ravenroot.observe");
        var observerB = context("tenant-b", Role.OPERATOR, "ravenroot.observe");

        List<Long> offsetsA = facade.durableEventsAfter(observerA, 0, 100).stream()
                .map(DurableExecutionEvent::journalOffset).toList();
        List<Long> offsetsB = facade.durableEventsAfter(observerB, 0, 100).stream()
                .map(DurableExecutionEvent::journalOffset).toList();

        assertEquals(List.of(1L, 2L), offsetsA);
        assertEquals(List.of(1L), offsetsB);
        // Not "tenant-b's row filtered out of a mixed read" -- proven by the fixture itself never
        // holding a mixed list to filter: FakeApplication.durableEventsAfter is keyed by tenantId and
        // cannot return tenant-b's list for tenant-a's key. A test that merely asserted the two
        // result lists were disjoint would pass equally against a filtering implementation; this one
        // additionally asserts the exact tenant-a shape, which a lookup under the wrong key could not
        // produce by accident.
        assertTrue(raw.durableEventsByTenant.containsKey("tenant-b"), "fixture assumption: both tenants seeded");
    }

    @Test
    void durableReplayRequiresTheSameCollectionLevelAuthorizationAsLiveObservation() {
        var raw = new FakeApplication();
        raw.durableJournalAvailable = true;
        raw.durableEventsByTenant.put("tenant-a", List.of(durableEvent(1, "tenant-a")));
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true);
        var unscoped = new RequestContext("request", "mallory", PrincipalType.USER, "issuer", "tenant-a",
                Set.of(Role.OPERATOR), Set.of("ravenroot.execute"));

        assertThrows(AuthorizationDeniedException.class, () -> facade.durableEventsAfter(unscoped, 0, 100),
                "a caller without the execution-events read scope must be refused before the delegate "
                        + "is ever asked for its tenant's journal");
    }

    @Test
    void durableReplayUnavailabilityIsDeclaredRatherThanDiscoveredByException() {
        var raw = new FakeApplication();
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true);
        assertFalse(facade.durableEventJournalAvailable(),
                "the fake starts with no durable store, matching an embedder that composed none");
        var operator = context("tenant-a", Role.OPERATOR, "ravenroot.observe");
        assertThrows(IllegalStateException.class, () -> facade.durableEventsAfter(operator, 0, 100));
    }

    @Test
    void derivesTheDelegateIdentityFromTheAuthenticatedContextAndRejectsPayloadSpoofing() {
        var raw = new FakeApplication();
        UUID execution = UUID.randomUUID();
        // Two identifiers for two submissions that reach the delegate. The rejected spoofing payload
        // deliberately consumes none: it is refused before an identifier is reserved.
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true, 4_096, ids(execution, UUID.randomUUID()));
        var operator = context("alice", "tenant-a", Role.OPERATOR, "ravenroot.execute");

        facade.startGraphMl(operator, new ByteArrayInputStream(new byte[0]), "payload");

        assertEquals(1, raw.observedIdentities.size());
        var propagated = raw.observedIdentities.getFirst();
        assertEquals("tenant-a", propagated.tenantId());
        assertEquals("alice", propagated.subject());
        assertEquals(operator.requestId(), propagated.requestId(),
                "the delegate must inherit the request's correlation id, not mint a second one");
        assertEquals("issuer|USER|alice", propagated.qualifiedIdentity());

        // A payload that carries reserved security keys is refused before the delegate is reached.
        assertThrows(IllegalArgumentException.class, () -> facade.startGraphMl(operator,
                new ByteArrayInputStream(new byte[0]),
                Map.of("ravenroot.security.tenantId", "tenant-b")));
        assertEquals(1, raw.startCalls, "a spoofing payload must not reach trusted startup");

        // An ordinary map payload is unaffected: the guard is narrow.
        facade.startGraphMl(context("alice", "tenant-a", Role.OPERATOR, "ravenroot.execute"),
                new ByteArrayInputStream(new byte[0]), Map.of("customerId", "42"));
        assertEquals(2, raw.startCalls);
    }

    /**
     * {@code startGraphMl(RequestContext, InputStream, Object)} must enforce the same
     * {@link PayloadLimits} as the remote surface. The {@code Object} overload converts its payload
     * through {@link ai.ravenroot.api.payload.PayloadValue#fromJava}, the same conversion used by the
     * {@link PayloadEnvelope} overload, so an oversized value is refused while the tree is built and
     * never reaches {@code startAuthorized} or {@code raw.startGraphMl}.
     */
    @Test
    void aTextPayloadBeyondTheDeclaredBudgetIsRefusedOnTheObjectOverloadToo() {
        var raw = new FakeApplication();
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true);
        var operator = context("alice", "tenant-a", Role.OPERATOR, "ravenroot.execute");
        String oversized = "x".repeat(PayloadLimits.DEFAULTS.maxTextLength() + 1);

        var rejection = assertThrows(ai.ravenroot.api.payload.PayloadException.class,
                () -> facade.startGraphMl(operator, new ByteArrayInputStream(new byte[0]), oversized));
        assertEquals("PAYLOAD_TEXT_TOO_LONG", rejection.code());
        assertEquals(0, raw.startCalls, "an oversized payload must not reach the delegate");

        // A payload within budget is unaffected: this is a ceiling, not a new restriction on ordinary use.
        String withinBudget = "x".repeat(PayloadLimits.DEFAULTS.maxTextLength());
        facade.startGraphMl(operator, new ByteArrayInputStream(new byte[0]), withinBudget);
        assertEquals(1, raw.startCalls);
    }

    /**
     * The existing reserved-key coverage on this overload
     * ({@code derivesTheDelegateIdentityFromTheAuthenticatedContextAndRejectsPayloadSpoofing}, above)
     * uses a top-level key, which the former top-level scan already refused -- it proves nothing
     * about the current tree walk. This test does: the reserved key sits one level
     * below the top, inside a nested map, exactly the shape the removed top-level-only scan let
     * through. Before the tree walk this call ran to completion because the scan never looked past
     * the top level; now {@link PayloadValue#requireNoReservedKeys} walks the tree and refuses it.
     */
    @Test
    void aReservedKeyNestedBelowTheTopLevelIsRefusedOnTheObjectOverloadToo() {
        var raw = new FakeApplication();
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true);
        var operator = context("alice", "tenant-a", Role.OPERATOR, "ravenroot.execute");
        Map<String, Object> nested = Map.of("outer",
                Map.of("ravenroot.security.tenantId", "attacker"));

        var rejection = assertThrows(ai.ravenroot.api.payload.PayloadException.class,
                () -> facade.startGraphMl(operator, new ByteArrayInputStream(new byte[0]), nested));
        assertEquals("PAYLOAD_RESERVED_KEY", rejection.code());
        assertEquals(0, raw.startCalls, "a nested spoofing payload must not reach trusted startup");

        // An ordinary nested map is unaffected: the guard is narrow, not merely shallow.
        facade.startGraphMl(operator, new ByteArrayInputStream(new byte[0]),
                Map.of("outer", Map.of("customerId", "42")));
        assertEquals(1, raw.startCalls);
    }

    // ---- API-02: cancel and drain -----------------------------------------------------------

    @Test
    void cancelStopsAnActiveTraversalAndReportsThatEffectsMayPersist() {
        var raw = new FakeApplication();
        var controlAudit = new ArrayList<ExecutionControlAuditEvent>();
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true, AuthorizedRavenrootApplication.DEFAULT_EXECUTION_OWNERSHIP_LIMIT,
                controlAudit::add);
        var operator = context("tenant-a", Role.OPERATOR, "ravenroot.execute");
        var submission = facade.startGraphMl(operator, new ByteArrayInputStream(new byte[0]), "");
        UUID traversalId = submission.traversalId();

        raw.cancelTraversalResult = true;
        var control = context("tenant-a", Role.OPERATOR, "ravenroot.execution.control");
        CancelResult result = facade.cancelExecution(control, traversalId);

        assertEquals(CancelResult.Outcome.CANCELLED, result.outcome());
        assertEquals(traversalId, result.traversalId());
        assertFalse(result.note().isBlank(), "the result itself must carry the persisted-effects "
                + "statement, not only a Javadoc comment -- see CancelResult's own Javadoc");
        assertTrue(result.note().toLowerCase(java.util.Locale.ROOT).contains("persist"),
                "an operator reading CANCELLED must be told effects already issued may persist");
        assertEquals(1, raw.cancelCalls);
        assertEquals(traversalId, raw.lastCancelledTraversalId);
        assertEquals(List.of(ExecutionControlAuditEvent.Disposition.ATTEMPT,
                        ExecutionControlAuditEvent.Disposition.SUCCEEDED),
                controlAudit.stream().map(ExecutionControlAuditEvent::disposition).toList(),
                "cancel must audit an ATTEMPT before the mutation and a terminal record after it");
    }

    @Test
    void cancelIsIdempotentOnceAlreadyCancelled() {
        var raw = new FakeApplication();
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true, AuthorizedRavenrootApplication.DEFAULT_EXECUTION_OWNERSHIP_LIMIT,
                event -> { });
        var operator = context("tenant-a", Role.OPERATOR, "ravenroot.execute");
        var submission = facade.startGraphMl(operator, new ByteArrayInputStream(new byte[0]), "");
        UUID traversalId = submission.traversalId();
        var control = context("tenant-a", Role.OPERATOR, "ravenroot.execution.control");

        raw.cancelTraversalResult = true;
        assertEquals(CancelResult.Outcome.CANCELLED, facade.cancelExecution(control, traversalId).outcome());

        // The delegate no longer finds it active (it already stopped), but the registry remembers it
        // was THIS cancel that stopped it -- so a second call must not read back as ALREADY_COMPLETED.
        raw.cancelTraversalResult = false;
        CancelResult second = facade.cancelExecution(control, traversalId);
        assertEquals(CancelResult.Outcome.ALREADY_CANCELLED, second.outcome(),
                "a repeat cancel of an already-cancelled traversal is idempotent, not an error, and "
                        + "must be distinguishable from a traversal that merely ran to completion");
    }

    @Test
    void cancelDistinguishesCompletionFromCancellationForATraversalNeverActuallyCancelled() {
        var raw = new FakeApplication();
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true, AuthorizedRavenrootApplication.DEFAULT_EXECUTION_OWNERSHIP_LIMIT,
                event -> { });
        var operator = context("tenant-a", Role.OPERATOR, "ravenroot.execute");
        var submission = facade.startGraphMl(operator, new ByteArrayInputStream(new byte[0]), "");
        UUID traversalId = submission.traversalId();
        var control = context("tenant-a", Role.OPERATOR, "ravenroot.execution.control");

        // Never active under this id (ran to completion on its own, from this facade's point of view):
        // a bare "not in the active map" check cannot tell this apart from "never existed" -- that is
        // exactly the ownership registry's job (see ExecutionOwnershipRegistry#markCancelled's Javadoc).
        raw.cancelTraversalResult = false;
        CancelResult result = facade.cancelExecution(control, traversalId);
        assertEquals(CancelResult.Outcome.ALREADY_COMPLETED, result.outcome(),
                "a traversal that ran to completion must be a distinguishable outcome, not a silent "
                        + "success indistinguishable from a fresh cancellation");
        assertFalse(result.note().isBlank());
    }

    @Test
    void cancelFailsClosedForUnknownOwnership() {
        var raw = new FakeApplication();
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true);
        var control = context("tenant-a", Role.OPERATOR, "ravenroot.execution.control");

        // Never reserved by this facade at all -- ExecutionOwnershipRegistry#owner returns null exactly
        // as it would for an id evicted past its bound, so this must fail closed the same way, not be
        // reported as a fourth CancelResult outcome (see CancelResult's own Javadoc on why there is no
        // UNKNOWN member).
        assertThrows(AuthorizationDeniedException.class,
                () -> facade.cancelExecution(control, UUID.randomUUID()));
        assertEquals(0, raw.cancelCalls, "an unknown traversal must never reach the delegate at all");
    }

    @Test
    void cancelDeniesCrossTenantEvenWithTheRightRoleAndScope() {
        var raw = new FakeApplication();
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true);
        var operator = context("tenant-a", Role.OPERATOR, "ravenroot.execute");
        var submission = facade.startGraphMl(operator, new ByteArrayInputStream(new byte[0]), "");

        var wrongTenant = context("tenant-b", Role.OPERATOR, "ravenroot.execution.control");
        assertThrows(AuthorizationDeniedException.class,
                () -> facade.cancelExecution(wrongTenant, submission.traversalId()));
        assertEquals(0, raw.cancelCalls);
    }

    @Test
    void drainRequiresPlatformScopeRatherThanAnyTenantsOwnOperator() {
        var raw = new FakeApplication();
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true);

        // Same action cancel uses, but a tenant's own OPERATOR must not be able to drain the whole
        // server through it -- see AuthorizedRavenrootApplication.DRAIN_RESOURCE_TENANT's Javadoc.
        var tenantOperator = context("tenant-a", Role.OPERATOR, "ravenroot.execution.control");
        assertThrows(AuthorizationDeniedException.class,
                () -> facade.drain(tenantOperator, java.time.Duration.ofSeconds(1)));
        assertEquals(0, raw.drainCalls);

        var platformAdmin = context(AuthorizedRavenrootApplication.DRAIN_RESOURCE_TENANT, Role.PLATFORM_ADMIN,
                "ravenroot.execution.control");
        raw.drainResult = true;
        assertEquals(DrainResult.Outcome.DRAINED, facade.drain(platformAdmin, java.time.Duration.ofSeconds(1))
                .outcome());
        assertEquals(1, raw.drainCalls);
    }

    @Test
    void drainReportsTimedOutWhenTheDelegateDoesNotConfirmCompletion() {
        var raw = new FakeApplication();
        var controlAudit = new ArrayList<ExecutionControlAuditEvent>();
        var facade = new AuthorizedRavenrootApplication(raw, new DefaultAuthorizationService(event -> { }),
                event -> { }, true, AuthorizedRavenrootApplication.DEFAULT_EXECUTION_OWNERSHIP_LIMIT,
                controlAudit::add);
        var platformAdmin = context(AuthorizedRavenrootApplication.DRAIN_RESOURCE_TENANT, Role.PLATFORM_ADMIN,
                "ravenroot.execution.control");

        raw.drainResult = false;
        var bound = java.time.Duration.ofMillis(250);
        DrainResult result = facade.drain(platformAdmin, bound);

        assertEquals(DrainResult.Outcome.TIMED_OUT, result.outcome());
        assertEquals(bound, raw.lastDrainBound, "the configured bound must reach the delegate unchanged");
        assertEquals(List.of(ExecutionControlAuditEvent.Disposition.ATTEMPT,
                        ExecutionControlAuditEvent.Disposition.SUCCEEDED),
                controlAudit.stream().map(ExecutionControlAuditEvent::disposition).toList(),
                "TIMED_OUT is a successful drain command that reported an honest partial result, not a "
                        + "FAILED audit disposition -- the command itself did not fail");
    }

    private static RequestContext context(String tenant, Role role, String scope) {
        return context("alice", tenant, role, scope);
    }

    private static RequestContext context(String tenant, Role role, String... scopes) {
        return new RequestContext("request", "alice", PrincipalType.USER, "issuer", tenant, Set.of(role),
                Set.of(scopes));
    }

    private static RequestContext context(String subject, String tenant, Role role, String scope) {
        return new RequestContext("request", subject, PrincipalType.USER, "issuer", tenant, Set.of(role),
                Set.of(scope));
    }

    private static GeneratedArtifact artifact(String id, Map<String, String> metadata) {
        return artifact(id, ArtifactState.GENERATED, metadata);
    }

    private static GeneratedArtifact artifact(String id, ArtifactState state, Map<String, String> metadata) {
        return new GeneratedArtifact(id, "javascript", "sha", "source", state, 1,
                Instant.EPOCH, Instant.EPOCH, metadata);
    }

    private static ExecutionEvent event(long sequence, UUID executionId) {
        return event(sequence, executionId, "tenant-a");
    }

    private static ExecutionEvent event(long sequence, UUID executionId, String tenantId) {
        return new ExecutionEvent(sequence, Instant.EPOCH, tenantId, "request", "test", "graph", executionId,
                ExecutionEventType.NODE_COMPLETED, "node", 0, false, "done");
    }

    private static ExecutionEvent diagnosticEvent(long sequence, UUID executionId, String tenantId,
                                                   String diagnostic) {
        return new ExecutionEvent(sequence, Instant.EPOCH, tenantId, "request", "test", "graph",
                executionId, executionId, null, null, ExecutionEventType.NODE_FAILED, "node", 0, false,
                diagnostic, null, null, null, null, null, 0, "IllegalStateException",
                RuntimeActivityData.message(diagnostic), null);
    }

    private static DurableExecutionEvent durableEvent(long journalOffset, String tenantId) {
        return new DurableExecutionEvent(UUID.randomUUID(), journalOffset, journalOffset, tenantId,
                "NODE_COMPLETED", UUID.randomUUID(), UUID.randomUUID(), null, null, null, "request", "graph",
                Instant.EPOCH, null);
    }

    private static java.util.function.Supplier<UUID> ids(UUID... values) {
        var remaining = new ArrayDeque<>(List.of(values));
        return remaining::removeFirst;
    }

    private static final class FakeApplication implements RavenrootApplication {
        private final List<GeneratedArtifact> artifacts = new ArrayList<>();
        private final List<ExecutionEvent> events = new ArrayList<>();
        private final List<Consumer<ExecutionEvent>> listeners = new ArrayList<>();
        private final List<ai.ravenroot.api.security.SecurityContext> observedIdentities = new ArrayList<>();
        /** Every tenant id the facade asked this delegate about, in order. */
        private final List<String> observedResultTenants = new ArrayList<>();
        private final List<String> observedSourceSessionTenants = new ArrayList<>();

        @Override
        public SourceSessionStatus startSourceSession(ai.ravenroot.api.security.SecurityContext security,
                                                      String sessionId, InputStream graphMl) {
            observedSourceSessionTenants.add(security.tenantId());
            return SourceSessionStatus.of(sessionId, SourceSessionState.LISTENING, 1);
        }

        @Override
        public java.util.Optional<SourceSessionStatus> sourceSession(String tenantId, String sessionId) {
            observedSourceSessionTenants.add(tenantId);
            return tenantId.equals("tenant-a")
                    ? java.util.Optional.of(SourceSessionStatus.of(sessionId, SourceSessionState.LISTENING, 1))
                    : java.util.Optional.empty();
        }

        @Override
        public CompletionStage<java.util.Optional<SourceSessionStatus>> stopSourceSession(
                String tenantId, String sessionId) {
            observedSourceSessionTenants.add(tenantId);
            return CompletableFuture.completedFuture(tenantId.equals("tenant-a")
                    ? java.util.Optional.of(SourceSessionStatus.of(sessionId, SourceSessionState.STOPPED, 1))
                    : java.util.Optional.empty());
        }

        private final List<String> observedDeploymentTenants = new ArrayList<>();

        @Override
        public LocalDeploymentStatus registerLocalDeployment(ai.ravenroot.api.security.SecurityContext security,
                                                             String deploymentId, InputStream graphMl) {
            observedDeploymentTenants.add(security.tenantId());
            return LocalDeploymentStatus.of(deploymentId, LocalDeploymentState.REGISTERED, 0);
        }

        @Override
        public List<LocalDeploymentStatus> localDeployments(String tenantId) {
            observedDeploymentTenants.add(tenantId);
            return tenantId.equals("tenant-a")
                    ? List.of(LocalDeploymentStatus.of("deployment", LocalDeploymentState.READY, 0))
                    : List.of();
        }

        @Override
        public java.util.Optional<LocalDeploymentStatus> localDeployment(String tenantId, String deploymentId) {
            observedDeploymentTenants.add(tenantId);
            return tenantId.equals("tenant-a")
                    ? java.util.Optional.of(LocalDeploymentStatus.of(deploymentId, LocalDeploymentState.READY, 0))
                    : java.util.Optional.empty();
        }

        @Override
        public CompletionStage<java.util.Optional<LocalDeploymentStatus>> startLocalDeployment(
                ai.ravenroot.api.security.SecurityContext security, String deploymentId) {
            return deploymentCommand(security.tenantId(), deploymentId, LocalDeploymentState.READY);
        }

        @Override
        public CompletionStage<java.util.Optional<LocalDeploymentStatus>> stopLocalDeployment(
                String tenantId, String deploymentId) {
            return deploymentCommand(tenantId, deploymentId, LocalDeploymentState.STOPPED);
        }

        @Override
        public CompletionStage<java.util.Optional<LocalDeploymentStatus>> restartLocalDeployment(
                ai.ravenroot.api.security.SecurityContext security, String deploymentId) {
            return deploymentCommand(security.tenantId(), deploymentId, LocalDeploymentState.READY);
        }

        @Override
        public CompletionStage<java.util.Optional<LocalDeploymentStatus>> undeployLocalDeployment(
                String tenantId, String deploymentId) {
            return deploymentCommand(tenantId, deploymentId, LocalDeploymentState.STOPPED);
        }

        private CompletionStage<java.util.Optional<LocalDeploymentStatus>> deploymentCommand(
                String tenantId, String deploymentId, LocalDeploymentState state) {
            observedDeploymentTenants.add(tenantId);
            return CompletableFuture.completedFuture(tenantId.equals("tenant-a")
                    ? java.util.Optional.of(LocalDeploymentStatus.of(deploymentId, state, 0))
                    : java.util.Optional.empty());
        }

        @Override
        public ExecutionLookup executionResult(String tenantId, UUID executionId) {
            observedResultTenants.add(tenantId);
            return new ExecutionLookup.Unknown(executionId);
        }

        private int createCalls;
        private int validationCalls;
        private int testCalls;
        private int approvalCalls;
        private int activationCalls;
        private int retirementCalls;
        private int startCalls;
        private ExecutionPolicy observedPolicy;
        private boolean publishEventDuringStart;
        private RuntimeException startFailure;
        // API-02: configurable per test, matching how startFailure above is configured -- the
        // fake reports whatever the test primed rather than modelling real traversal lifecycle, since
        // the ownership/cancelled-flag bookkeeping under test lives entirely in
        // AuthorizedRavenrootApplication and ExecutionOwnershipRegistry, not in this delegate.
        private boolean cancelTraversalResult;
        private int cancelCalls;
        private UUID lastCancelledTraversalId;
        private boolean drainResult;
        private int drainCalls;
        private java.time.Duration lastDrainBound;

        @Override
        public boolean cancelTraversal(UUID traversalId) {
            cancelCalls++;
            lastCancelledTraversalId = traversalId;
            return cancelTraversalResult;
        }

        @Override
        public boolean drain(java.time.Duration bound) {
            drainCalls++;
            lastDrainBound = bound;
            return drainResult;
        }

        @Override public ApplicationStatus status() { throw new UnsupportedOperationException(); }
        @Override public RuntimeSnapshot runtimeSnapshot() { throw new UnsupportedOperationException(); }
        @Override public List<NodeTypeDescriptor> nodeTypes() { return List.of(); }
        @Override public List<GeneratedArtifact> programArtifacts() { return List.copyOf(artifacts); }

        @Override
        public GeneratedArtifact createProgramArtifact(String language, String source, Map<String, String> metadata) {
            createCalls++;
            GeneratedArtifact result = artifact("created", metadata);
            artifacts.add(result);
            return result;
        }

        @Override
        public CompletionStage<GeneratedArtifact> validateProgramArtifact(String id) {
            validationCalls++;
            return CompletableFuture.completedFuture(find(id));
        }

        @Override
        public CompletionStage<ArtifactTestResult> testProgramArtifact(String id, Object payload) {
            testCalls++;
            return CompletableFuture.completedFuture(new ArtifactTestResult(find(id), payload));
        }

        @Override
        public GeneratedArtifact approveProgramArtifact(String id, Map<String, String> evidence) {
            approvalCalls++;
            return replace(id, ArtifactState.APPROVED, "approved", evidence);
        }
        @Override
        public GeneratedArtifact activateProgramArtifact(String id, Map<String, String> evidence) {
            activationCalls++;
            return replace(id, ArtifactState.ACTIVE, "active", evidence);
        }
        @Override
        public GeneratedArtifact retireProgramArtifact(String id, Map<String, String> evidence) {
            retirementCalls++;
            return replace(id, ArtifactState.RETIRED, "retired", evidence);
        }
        @Override public GraphSummary inspectGraphMl(InputStream graphMl) { throw new UnsupportedOperationException(); }
        @Override public ExecutionSubmission startGraphMl(ai.ravenroot.api.security.SecurityContext security,
                                                          UUID executionId, InputStream graphMl, Object payload) {
            startCalls++;
            observedIdentities.add(security);
            if (publishEventDuringStart) {
                ExecutionEvent first = event(1, executionId);
                events.add(first);
                emit(first);
            }
            if (startFailure != null) {
                RuntimeException failure = startFailure;
                startFailure = null;
                throw failure;
            }
            return new ExecutionSubmission(executionId, "graph");
        }
        @Override public ExecutionSubmission startGraphMl(ai.ravenroot.api.security.SecurityContext security,
                                                          UUID executionId, InputStream graphMl, Object payload,
                                                          ExecutionPolicy policy) {
            observedPolicy = policy;
            return startGraphMl(security, executionId, graphMl, payload);
        }
        @Override public List<ExecutionEvent> executionEventsAfter(long sequence) {
            return events.stream().filter(event -> event.sequence() > sequence).toList();
        }
        @Override public AutoCloseable subscribeToExecutionEvents(Consumer<ExecutionEvent> listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }
        // Tenant-keyed rather than a single flat list, unlike `events` above: this fake exists
        // specifically to prove structural tenant scoping (durableEventsAfter is called with a bare
        // tenantId, never a RequestContext, and must be unable to reach any other tenant's list no
        // matter what the caller's authorization state is), so the fixture itself must make a
        // cross-tenant read a wrong *key*, not merely a filtered-out row.
        private final Map<String, List<DurableExecutionEvent>> durableEventsByTenant = new java.util.LinkedHashMap<>();
        private boolean durableJournalAvailable;

        @Override public boolean durableEventJournalAvailable() {
            return durableJournalAvailable;
        }
        @Override public List<DurableExecutionEvent> durableEventsAfter(String tenantId, long afterOffset, int limit) {
            if (!durableJournalAvailable) {
                throw new IllegalStateException("no durable journal configured");
            }
            return durableEventsByTenant.getOrDefault(tenantId, List.of()).stream()
                    .filter(event -> event.journalOffset() > afterOffset)
                    .limit(limit)
                    .toList();
        }
        @Override public void close() { }

        private GeneratedArtifact find(String id) {
            return artifacts.stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElseThrow();
        }

        private GeneratedArtifact replace(String id, ArtifactState state, String evidencePrefix,
                                          Map<String, String> evidence) {
            GeneratedArtifact current = find(id);
            var metadata = new java.util.LinkedHashMap<>(current.metadata());
            evidence.forEach((key, value) -> metadata.put("evidence." + evidencePrefix + "." + key, value));
            var changed = new GeneratedArtifact(current.id(), current.language(), current.sha256(), current.source(),
                    state, current.revision() + 1, current.createdAt(), Instant.now(), metadata);
            artifacts.set(artifacts.indexOf(current), changed);
            return changed;
        }

        private void emit(ExecutionEvent event) {
            List.copyOf(listeners).forEach(listener -> listener.accept(event));
        }
    }

    /** Implements only the legacy abstract surface; the explicit-policy method is the API default. */
    private static final class LegacyApplication implements RavenrootApplication {
        @Override public ApplicationStatus status() { throw new UnsupportedOperationException(); }
        @Override public RuntimeSnapshot runtimeSnapshot() { throw new UnsupportedOperationException(); }
        @Override public List<NodeTypeDescriptor> nodeTypes() { return List.of(); }
        @Override public List<GeneratedArtifact> programArtifacts() { return List.of(); }
        @Override public GeneratedArtifact createProgramArtifact(String language, String source,
                                                                  Map<String, String> metadata) {
            throw new UnsupportedOperationException();
        }
        @Override public CompletionStage<GeneratedArtifact> validateProgramArtifact(String id) {
            throw new UnsupportedOperationException();
        }
        @Override public CompletionStage<ArtifactTestResult> testProgramArtifact(String id, Object payload) {
            throw new UnsupportedOperationException();
        }
        @Override public GeneratedArtifact approveProgramArtifact(String id, Map<String, String> evidence) {
            throw new UnsupportedOperationException();
        }
        @Override public GeneratedArtifact activateProgramArtifact(String id, Map<String, String> evidence) {
            throw new UnsupportedOperationException();
        }
        @Override public GeneratedArtifact retireProgramArtifact(String id, Map<String, String> evidence) {
            throw new UnsupportedOperationException();
        }
        @Override public GraphSummary inspectGraphMl(InputStream graphMl) { throw new UnsupportedOperationException(); }
        @Override public ExecutionSubmission startGraphMl(ai.ravenroot.api.security.SecurityContext security,
                                                          UUID executionId, InputStream graphMl, Object payload) {
            return new ExecutionSubmission(executionId, "legacy");
        }
        @Override public List<ExecutionEvent> executionEventsAfter(long sequence) { return List.of(); }
        @Override public AutoCloseable subscribeToExecutionEvents(Consumer<ExecutionEvent> listener) {
            return () -> { };
        }
        @Override public boolean durableEventJournalAvailable() { return false; }
        @Override public List<DurableExecutionEvent> durableEventsAfter(String tenantId, long afterOffset, int limit) {
            throw new UnsupportedOperationException();
        }
        @Override public void close() { }
    }
}
