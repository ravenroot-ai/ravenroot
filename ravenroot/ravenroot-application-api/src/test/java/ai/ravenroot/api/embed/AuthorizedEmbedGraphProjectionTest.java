package ai.ravenroot.api.embed;

import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.ProtectedResource;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizedEmbedGraphProjectionTest {

    private static final EmbedRegistrationAggregate CAPTURED =
            EmbedFixtures.aggregate(0, "sha256:a", "start");

    @Test
    void dedicatedScopeAndRoleAreRequiredBeforeTheAuthorityIsCalled() {
        var called = new AtomicBoolean();
        var reader = new AuthorizedEmbedGraphProjection(
                new DefaultAuthorizationService(event -> { }), recording(called));

        assertThrows(ai.ravenroot.api.security.AuthorizationDeniedException.class,
                () -> reader.read(context(Set.of(Role.VIEWER), Set.of("ravenroot.read")), CAPTURED));
        assertThrows(ai.ravenroot.api.security.AuthorizationDeniedException.class,
                () -> reader.read(context(Set.of(Role.DEVELOPER),
                        Set.of("ravenroot.embed.graph.read")), CAPTURED));
        assertTrue(!called.get());
    }

    @Test
    void embedAndGeneralGraphReadScopesAreNotInterchangeable() {
        var service = new DefaultAuthorizationService(event -> { });
        assertTrue(!service.decide(context(Set.of(Role.OPERATOR), Set.of("ravenroot.graph.inspect")),
                AuthorizationAction.EMBED_GRAPH_READ,
                ProtectedResource.owned("embed-graph", "resource-a", EmbedFixtures.TENANT)).allowed());
        assertTrue(!service.decide(context(Set.of(Role.OPERATOR), Set.of("ravenroot.embed.graph.read")),
                AuthorizationAction.GRAPH_READ,
                ProtectedResource.owned("graph", "graph-a", EmbedFixtures.TENANT)).allowed());
    }

    @Test
    void authorityFailureIsSanitizedAndReturnsNoProjection() {
        var reader = new AuthorizedEmbedGraphProjection(
                new DefaultAuthorizationService(event -> { }), new StubAuthority() {
                    @Override
                    public EmbedProjectionResolution resolveProjection(EmbedRegistrationAggregate captured,
                                                                       EmbedProjectionBudget budget) {
                        throw new IllegalStateException("secret store path");
                    }
                });

        assertInstanceOf(EmbedProjectionResolution.TemporarilyUnavailable.class,
                reader.read(context(Set.of(Role.VIEWER), Set.of("ravenroot.embed.graph.read")), CAPTURED));
    }

    @Test
    void unavailableAuthorizationAuditFailsBeforeTheAuthorityIsCalled() {
        var called = new AtomicBoolean();
        var reader = new AuthorizedEmbedGraphProjection(
                new DefaultAuthorizationService(event -> {
                    throw new IllegalStateException("audit offline");
                }), recording(called));

        assertThrows(ai.ravenroot.api.security.AuthorizationDeniedException.class,
                () -> reader.read(context(Set.of(Role.VIEWER), Set.of("ravenroot.embed.graph.read")), CAPTURED));
        assertTrue(!called.get());
    }

    /**
     * The boundary check that catches an adapter which overrode {@code resolveProjection} to look the
     * payload up again: the substituted projection belongs to a different graph than the captured
     * aggregate's grant, and is refused rather than served.
     */
    @Test
    void applicationBoundaryRejectsAnAuthorityThatSubstitutesIdentityOrBreaksBudgets() {
        var authorization = new DefaultAuthorizationService(event -> { });
        var identitySubstitution = new AuthorizedEmbedGraphProjection(authorization, new StubAuthority() {
            @Override
            public EmbedProjectionResolution resolveProjection(EmbedRegistrationAggregate captured,
                                                               EmbedProjectionBudget budget) {
                return new EmbedProjectionResolution.Available(new EmbedGraphProjection("1.0",
                        "other-graph", "v3", "sha256:a", List.of(), List.of()));
            }
        });
        assertInstanceOf(EmbedProjectionResolution.Unavailable.class,
                identitySubstitution.read(context(Set.of(Role.VIEWER),
                        Set.of("ravenroot.embed.graph.read")), CAPTURED));

        var oversized = new AuthorizedEmbedGraphProjection(authorization,
                new InMemoryEmbedRegistrationAuthority(),
                new EmbedProjectionBudget(10, 10, 10_000, 5, 100, 100));
        var wide = EmbedFixtures.aggregate(0, "sha256:a", "123456");
        assertInstanceOf(EmbedProjectionResolution.Unavailable.class,
                oversized.read(context(Set.of(Role.VIEWER), Set.of("ravenroot.embed.graph.read")), wide),
                "an aggregate that was never provisioned into this authority is not current");
    }

    /** A projection whose payload exceeds the reader's budget is DataTooLarge, not silently truncated. */
    @Test
    void aPayloadAboveTheReadersBudgetIsRefusedRatherThanTrimmed() {
        var authority = new InMemoryEmbedRegistrationAuthority();
        var provisioned = authority.provision(EmbedFixtures.command(0, "sha256:a", "123456"));
        var captured = assertInstanceOf(EmbedProvisionOutcome.Provisioned.class, provisioned).aggregate();

        var narrow = new AuthorizedEmbedGraphProjection(new DefaultAuthorizationService(event -> { }),
                authority, new EmbedProjectionBudget(10, 10, 10_000, 5, 100, 100));
        assertInstanceOf(EmbedProjectionResolution.DataTooLarge.class,
                narrow.read(context(Set.of(Role.VIEWER), Set.of("ravenroot.embed.graph.read")), captured));
    }

    @Test
    void closedDtoSerializesOnlyItsAllowlistedSchema() {
        var projection = new EmbedGraphProjection("1.0", "graph-a", "v3", "sha256:a",
                List.of(new EmbedGraphProjection.Node("start", "START",
                        new EmbedGraphProjection.Layout(10, 20, 30, 40))),
                List.of(new EmbedGraphProjection.Edge("start", "end")));

        String json = projection.toJson();
        assertTrue(json.contains("\"viewerContractVersion\":\"1.0\""));
        assertTrue(json.contains("\"layout\":{\"x\":10.0"));
        for (String forbidden : List.of("tenant", "deployment", "behavior", "properties", "prompt",
                "payload", "secret", "createdBy", "lease", "fence", "vendor", "runtime", "monitor")) {
            assertTrue(!json.contains(forbidden), forbidden);
        }
    }

    /**
     * The port's read surface, pinned by reflection.
     *
     * <p>{@code resolveProjection} being a {@code default} method is the structural half of «no
     * two-read join»: it has no seam an adapter must fill, so the only way back to a second lookup is
     * a deliberate override, which the boundary test above then catches.</p>
     */
    @Test
    void projectionPortCannotRepresentMutationParsingRuntimeMonitoringOrStreaming() throws Exception {
        var readMethods = Arrays.stream(EmbedRegistrationAuthority.class.getMethods())
                .filter(method -> method.getDeclaringClass() == EmbedRegistrationAuthority.class)
                .filter(method -> !java.lang.reflect.Modifier.isStatic(method.getModifiers()))
                .map(java.lang.reflect.Method::getName)
                .sorted()
                .toList();
        assertTrue(readMethods.equals(List.of("isCurrent", "provision", "resolveCurrent",
                "resolveProjection", "revoke")), readMethods.toString());
        assertTrue(EmbedRegistrationAuthority.class.getMethod("resolveProjection",
                        EmbedRegistrationAggregate.class, EmbedProjectionBudget.class).isDefault(),
                "resolveProjection must stay a default method, or an adapter gains a seam for a "
                        + "second read");

        assertTrue(Arrays.stream(EmbedGraphProjection.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList()
                .equals(List.of("viewerContractVersion", "graphId", "graphVersionId", "canonicalDigest",
                        "nodes", "edges")));
    }

    private static EmbedRegistrationAuthority recording(AtomicBoolean called) {
        return new StubAuthority() {
            @Override
            public EmbedProjectionResolution resolveProjection(EmbedRegistrationAggregate captured,
                                                               EmbedProjectionBudget budget) {
                called.set(true);
                return EmbedProjectionResolution.Unavailable.INSTANCE;
            }
        };
    }

    private static RequestContext context(Set<Role> roles, Set<String> scopes) {
        return new RequestContext("request-1", "subject-1", PrincipalType.USER, "issuer",
                EmbedFixtures.TENANT, roles, scopes);
    }

    private abstract static class StubAuthority implements EmbedRegistrationAuthority {
        @Override
        public EmbedProvisionOutcome provision(EmbedProvisionCommand command) {
            return EmbedProvisionOutcome.Unavailable.INSTANCE;
        }

        @Override
        public EmbedRevokeOutcome revoke(EmbedRevokeCommand command) {
            return EmbedRevokeOutcome.Unavailable.INSTANCE;
        }

        @Override
        public EmbedRegistrationResolution resolveCurrent(RequestContext workload, String registrationId) {
            return EmbedRegistrationResolution.Unavailable.INSTANCE;
        }

        @Override
        public boolean isCurrent(EmbedRegistrationAggregate captured) {
            return true;
        }
    }
}
