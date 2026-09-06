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
 * Acquires a deployment lease from a genuinely separate operating-system process and then holds it
 * forever, for {@link SqliteDeploymentPartitionByKillTest} -- the "partition" row issue 91's acceptance
 * criteria name: the holder is killed without ever releasing.
 *
 * <p>Modeled directly on {@link HoldLeaseUntilKilledInAnotherProcess}. Deliberately not
 * try-with-resources: this class removes the one thing every other cross-process test still leaves in
 * place, the chance for the adapter to run its own {@code close()} before the process ends. The lease
 * here is abandoned, not released, and only {@code SIGKILL} ends the process.</p>
 */
public final class HoldDeploymentLeaseUntilKilledInAnotherProcess {

    static final String CLAIMED = "CLAIMED ";
    static final String AT_BOUNDARY = "AT_BOUNDARY";

    private HoldDeploymentLeaseUntilKilledInAnotherProcess() {
    }

    public static void main(String[] args) throws Exception {
        Path databaseFile = Path.of(args[0]);
        String tenant = args[1];
        DeploymentId deploymentId = DeploymentId.of(args[2]);
        long expectedRevision = Long.parseLong(args[3]);
        Instant now = Instant.parse(args[4]);
        Duration ttl = Duration.parse(args[5]);

        // Deliberately not try-with-resources: see the class Javadoc.
        var registry = new SqliteDeploymentRegistry(databaseFile, Clock.fixed(now, ZoneOffset.UTC),
                unused -> DeploymentId.of("unused-in-child"));
        var command = new DeploymentRegistry.Command(tenant, deploymentId, "acquire-in-another-process",
                "a".repeat(64), RevisionExpectation.exactly(expectedRevision));
        DeploymentRegistry.Record acquired = registry.acquire("worker-in-another-process", ttl, command)
                .toCompletableFuture().join();
        System.out.println(CLAIMED + acquired.lease().fence() + " " + acquired.lease().expiresAt());
        System.out.println(AT_BOUNDARY);
        System.out.flush();
        // Long enough that a parent which failed to kill us hits its own timeout and reports that,
        // rather than this process quietly exiting and inventing a pass.
        Thread.sleep(Duration.ofMinutes(10));
    }
}
