package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.embed.EmbedCapability;
import ai.ravenroot.api.embed.EmbedGraphProjection;
import ai.ravenroot.api.embed.EmbedProjectionEligibility;
import ai.ravenroot.api.embed.EmbedProvisionCommand;
import ai.ravenroot.api.embed.EmbedSnapshotLifecycle;
import ai.ravenroot.api.embed.EmbedTheme;
import ai.ravenroot.api.embed.VerifiedEmbedGraphGrant;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Shared shapes for the durable adapter's tests; the equivalent of the API module's own fixtures. */
final class EmbedRegistrationFixtures {

    static final String TENANT = "tenant-a";
    static final String ISSUER = "https://issuer.example";
    static final String SUBJECT = "workload-1";
    static final String REGISTRATION = "registration-1";
    static final String POLICY = "policy-4";
    static final Instant AT = Instant.parse("2026-02-01T00:00:00Z");

    private EmbedRegistrationFixtures() {
    }

    static VerifiedEmbedGraphGrant graphGrant(String digest) {
        return new VerifiedEmbedGraphGrant(TENANT, "resource-a", "deployment-a", 7, "graph-a", "v3",
                digest, POLICY);
    }

    /** A layout and an edge are present so the codec's whole grammar round-trips, not just its keys. */
    static EmbedGraphProjection projection(String digest, String... nodeIds) {
        var nodes = new java.util.ArrayList<EmbedGraphProjection.Node>();
        boolean first = true;
        for (String id : nodeIds) {
            nodes.add(new EmbedGraphProjection.Node(id, first ? "START" : "PASSTHROUGH",
                    first ? new EmbedGraphProjection.Layout(1.5, -2.25, 30, 40) : null));
            first = false;
        }
        List<EmbedGraphProjection.Edge> edges = nodeIds.length > 1
                ? List.of(new EmbedGraphProjection.Edge(nodeIds[0], nodeIds[1]))
                : List.of();
        return new EmbedGraphProjection(EmbedGraphProjection.CURRENT_CONTRACT_VERSION, "graph-a", "v3",
                digest, List.copyOf(nodes), edges);
    }

    static EmbedProvisionCommand command(long expectedRevision, String digest, String... nodeIds) {
        return new EmbedProvisionCommand(REGISTRATION, expectedRevision, ISSUER, SUBJECT, TENANT,
                "https://parent.example", Set.of(EmbedCapability.GRAPH_READ), Optional.of(EmbedTheme.DARK),
                graphGrant(digest), EmbedSnapshotLifecycle.PUBLISHED,
                EmbedProjectionEligibility.allowed(POLICY), projection(digest, nodeIds));
    }

    static RequestContext workload() {
        return new RequestContext("request-1", SUBJECT, PrincipalType.WORKLOAD, ISSUER, TENANT,
                Set.of(Role.VIEWER), Set.of("ravenroot.embed.session.create"));
    }
}
