package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.persistence.RevisionExpectation;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Acquires a deployment lease from a genuinely separate operating-system process, for
 * {@link SqliteDeploymentCrossProcessLeaseTest}.
 *
 * <p>Modeled directly on {@link ClaimLeaseInAnotherProcess}: the point of a second JVM is that nothing
 * is shared but the file, which is the only way to actually exercise "coordinated across operating
 * system processes" rather than proving it by analogy against two connections in one JVM.</p>
 */
public final class ClaimDeploymentLeaseInAnotherProcess {

    static final String CLAIMED = "CLAIMED ";

    private ClaimDeploymentLeaseInAnotherProcess() {
    }

    public static void main(String[] args) {
        Path databaseFile = Path.of(args[0]);
        String tenant = args[1];
        DeploymentId deploymentId = DeploymentId.of(args[2]);
        long expectedRevision = Long.parseLong(args[3]);
        Instant now = Instant.parse(args[4]);
        Duration ttl = Duration.parse(args[5]);

        try (DeploymentRegistry registry = new SqliteDeploymentRegistry(databaseFile,
                Clock.fixed(now, ZoneOffset.UTC), unused -> DeploymentId.of("unused-in-child"))) {
            var command = new DeploymentRegistry.Command(tenant, deploymentId, "acquire-in-another-process",
                    "a".repeat(64), RevisionExpectation.exactly(expectedRevision));
            DeploymentRegistry.Record acquired = registry.acquire("worker-in-another-process", ttl, command)
                    .toCompletableFuture().join();
            System.out.println(CLAIMED + acquired.lease().fence() + " " + acquired.lease().expiresAt());
            System.out.flush();
        }
    }
}
