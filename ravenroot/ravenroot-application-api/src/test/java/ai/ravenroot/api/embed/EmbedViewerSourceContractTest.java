package ai.ravenroot.api.embed;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbedViewerSourceContractTest {
    @Test
    void compatibilityConstructorRemainsASnapshotAndDeploymentFactoryIsExplicitlyOptIn() {
        var projection = new EmbedGraphProjection("1.0", "graph", "version", "digest",
                java.util.List.of(), java.util.List.of());
        var grant = new VerifiedEmbedGraphGrant("tenant", "resource", "deployment", 1,
                "graph", "version", "digest", "policy");
        var snapshot = new EmbedProvisionCommand("registration", 0, "issuer", "subject", "tenant",
                "https://parent.example", Set.of(EmbedCapability.GRAPH_READ), Optional.empty(), grant,
                EmbedSnapshotLifecycle.ACTIVE, EmbedProjectionEligibility.allowed("policy"), projection);
        assertInstanceOf(EmbedViewerSource.Snapshot.class, snapshot.source());

        var deployment = EmbedProvisionCommand.deployment("live", 0, "issuer", "subject", "tenant",
                "https://parent.example", Optional.empty(), "orders");
        assertEquals(new EmbedViewerSource.Deployment("orders"), deployment.source());
        assertTrue(deployment.capabilities().contains(EmbedCapability.DEPLOYMENT_OBSERVE));
        assertInstanceOf(EmbedViewerSource.Deployment.class,
                deployment.aggregateAt(Instant.EPOCH).source());
    }

    @Test
    void deploymentSourceCannotBeConstructedWithoutItsDistinctCapability() {
        var projection = new EmbedGraphProjection("1.0", "deployment", "deferred", "deferred",
                java.util.List.of(), java.util.List.of());
        var graph = new VerifiedEmbedGraphGrant("tenant", "resource", "deployment", 1,
                "deployment", "deferred", "deferred", "policy");
        var session = new VerifiedEmbedSessionGrant("registration", 1, "issuer", "subject", "tenant",
                "https://parent.example", Set.of(EmbedCapability.GRAPH_READ), graph);
        assertThrows(IllegalArgumentException.class, () -> new EmbedRegistrationAggregate("registration", 1,
                EmbedRegistrationState.ACTIVE, session, EmbedSnapshotLifecycle.ACTIVE,
                EmbedProjectionEligibility.allowed("policy"), projection, Instant.EPOCH,
                EmbedViewerSource.deployment("deployment")));
    }
}
