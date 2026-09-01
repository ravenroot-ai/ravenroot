package ai.ravenroot.api.embed;

import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The one place embed tests build a coherent registration.
 *
 * <p>Every value here is deliberately distinct — tenant, issuer, subject, graph, version and digest
 * are all different strings — so a test that asserts on the wrong one fails rather than passing on a
 * coincidence. Helpers take the parts a test wants to vary and derive the rest, because the aggregate
 * refuses incoherent combinations and a fixture that hard-codes all twelve fields makes every test
 * that varies one of them a copy of this file.</p>
 */
final class EmbedFixtures {

    static final String TENANT = "tenant-a";
    static final String ISSUER = "https://issuer.example";
    static final String SUBJECT = "workload-1";
    static final String PARENT_ORIGIN = "https://parent.example";
    static final String REGISTRATION = "registration-1";
    static final String POLICY = "policy-4";
    static final Instant AT = Instant.parse("2026-02-01T00:00:00Z");

    private EmbedFixtures() {
    }

    static VerifiedEmbedGraphGrant graphGrant(String digest) {
        return graphGrant(digest, POLICY);
    }

    static VerifiedEmbedGraphGrant graphGrant(String digest, String policyRevision) {
        return new VerifiedEmbedGraphGrant(TENANT, "resource-a", "deployment-a", 7, "graph-a", "v3",
                digest, policyRevision);
    }

    static EmbedGraphProjection projection(String digest, String... nodeIds) {
        return new EmbedGraphProjection(EmbedGraphProjection.CURRENT_CONTRACT_VERSION, "graph-a", "v3",
                digest,
                java.util.Arrays.stream(nodeIds)
                        .map(id -> new EmbedGraphProjection.Node(id, "PASSTHROUGH", null))
                        .toList(),
                List.of());
    }

    static EmbedProvisionCommand command(long expectedRevision, String digest, String... nodeIds) {
        return new EmbedProvisionCommand(REGISTRATION, expectedRevision, ISSUER, SUBJECT, TENANT,
                PARENT_ORIGIN, Set.of(EmbedCapability.GRAPH_READ), Optional.empty(), graphGrant(digest),
                EmbedSnapshotLifecycle.PUBLISHED, EmbedProjectionEligibility.allowed(POLICY),
                projection(digest, nodeIds));
    }

    static EmbedRegistrationAggregate aggregate(long expectedRevision, String digest, String... nodeIds) {
        return command(expectedRevision, digest, nodeIds).aggregateAt(AT);
    }

    /** The workload context the browser boundary presents; matches the fixture registration exactly. */
    static RequestContext workload() {
        return new RequestContext("request-1", SUBJECT, PrincipalType.WORKLOAD, ISSUER, TENANT,
                Set.of(Role.VIEWER), Set.of("ravenroot.embed.session.create",
                "ravenroot.embed.graph.read"));
    }

    static RequestContext operator() {
        return new RequestContext("request-2", "operator-1", PrincipalType.USER, ISSUER, TENANT,
                Set.of(Role.OPERATOR), Set.of("ravenroot.embed.registration.admin"));
    }

    /** An operator principal that is authenticated as a workload; used to prove operator-only. */
    static RequestContext workloadPretendingToBeOperator() {
        return new RequestContext("request-3", SUBJECT, PrincipalType.WORKLOAD, ISSUER, TENANT,
                Set.of(Role.OPERATOR, Role.TENANT_ADMIN, Role.PLATFORM_ADMIN),
                Set.of("ravenroot.embed.registration.admin"));
    }
}
