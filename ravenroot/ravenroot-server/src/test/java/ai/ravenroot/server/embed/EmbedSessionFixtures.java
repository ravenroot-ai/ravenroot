package ai.ravenroot.server.embed;

import ai.ravenroot.api.embed.EmbedCapability;
import ai.ravenroot.api.embed.EmbedGraphProjection;
import ai.ravenroot.api.embed.EmbedProjectionEligibility;
import ai.ravenroot.api.embed.EmbedProvisionCommand;
import ai.ravenroot.api.embed.EmbedRegistrationAggregate;
import ai.ravenroot.api.embed.EmbedSnapshotLifecycle;
import ai.ravenroot.api.embed.VerifiedEmbedGraphGrant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One coherent registration at a chosen revision, for the server-side session tests.
 *
 * <p>Extracted from {@code EmbedLaunchTicketAuthorityTest} when {@code EmbedBrowserSessionAuthority}
 * got its own suite: two copies of a fixture that must agree about what "the same registration"
 * means is how two suites end up asserting different things while both look right.</p>
 */
final class EmbedSessionFixtures {

    static final Instant AT = Instant.parse("2026-08-26T00:00:00Z");

    private EmbedSessionFixtures() {
    }

    /** The digest varies with the revision, so two revisions are never accidentally {@code equals}. */
    static EmbedRegistrationAggregate registration(long revision) {
        var graphGrant = new VerifiedEmbedGraphGrant("tenant", "resource", "deployment", 1,
                "graph", "version", "digest-r" + revision, "policy");
        var projection = new EmbedGraphProjection(EmbedGraphProjection.CURRENT_CONTRACT_VERSION,
                "graph", "version", "digest-r" + revision, List.of(), List.of());
        return new EmbedProvisionCommand("reg", revision - 1, "issuer", "subject", "tenant",
                "https://parent.example", Set.of(EmbedCapability.GRAPH_READ), Optional.empty(),
                graphGrant, EmbedSnapshotLifecycle.PUBLISHED,
                EmbedProjectionEligibility.allowed("policy"), projection)
                .aggregateAt(AT);
    }
}
