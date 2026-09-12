package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.GraphVersion;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue 91 acceptance criterion 4 (a stale owner cannot publish lifecycle state after a takeover) and
 * part of criterion 7 (lease expiry, takeover, and refusal of the superseded holder), proved across a
 * genuinely separate operating-system process rather than by analogy against two connections in one
 * JVM. Modeled directly on {@link SqliteCrossProcessLeaseTest}, adapted from {@code ExecutionStore}'s
 * lease vocabulary to {@link DeploymentRegistry}'s: there is no distinct "held by another" failure
 * here, a live foreign lease is simply {@code Conflict} (ADR 0038 D0/D1 — {@code acquire} does not
 * distinguish the reason a lease is still live from any other reason a write could not proceed), and
 * fencing is verified against all four lease-authorized operations, not the execution-write path.
 *
 * <p>The fence assertion is the load-bearing one, exactly as in the execution-store analogue: a counter
 * kept anywhere but the database would restart at zero in this process and let a stale token, from a
 * process this one never shared memory with, be accepted as current.</p>
 */
class SqliteDeploymentCrossProcessLeaseTest {

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(30);

    @TempDir
    Path databaseDirectory;

    @Test
    void aLeaseTakenByAnotherProcessExcludesThisOneAndATakeoverStrictlyAdvancesTheFence() throws Exception {
        Path file = databaseDirectory.resolve("cross-process.db");
        String tenant = "acme";
        MutableClock clock = new MutableClock(EPOCH);

        DeploymentId deploymentId;
        long revisionAfterCreate;
        try (DeploymentRegistry registry = new SqliteDeploymentRegistry(file, clock,
                t -> DeploymentId.of("dep-1"))) {
            var content = new GraphVersion.Content(1, "graph".getBytes(StandardCharsets.UTF_8), "alice", EPOCH);
            var create = new DeploymentRegistry.CreateCommand(tenant, "create", "a".repeat(64));
            DeploymentRegistry.Record made = registry.create(content, create).toCompletableFuture().join();
            deploymentId = made.deploymentId();
            revisionAfterCreate = made.revision();
        }

        long fenceFromOtherProcess = claimInAnotherProcess(file, tenant, deploymentId, revisionAfterCreate);

        try (DeploymentRegistry registry = new SqliteDeploymentRegistry(file, clock,
                t -> DeploymentId.of("unused-here"))) {
            DeploymentRegistry.Record current = registry.get(tenant, deploymentId).toCompletableFuture().join()
                    .orElseThrow();
            assertEquals(fenceFromOtherProcess, current.lease().fence(),
                    "this process reads the lease the other process actually wrote to the shared file");

            var contendedCommand = new DeploymentRegistry.Command(tenant, deploymentId, "contend",
                    "b".repeat(64), RevisionExpectation.exactly(current.revision()));
            DeploymentRegistry.RegistryException contended = failureOf(() ->
                    await(registry.acquire("worker-here", TTL, contendedCommand)));
            assertInstanceOf(DeploymentRegistry.FailureReason.Conflict.class, contended.reason(),
                    "a lease taken in another process must exclude a fresh acquire from this one; if it "
                            + "did not, both processes would believe they own the deployment");

            clock.set(current.lease().expiresAt());
            var takeoverCommand = new DeploymentRegistry.Command(tenant, deploymentId, "takeover",
                    "c".repeat(64), RevisionExpectation.exactly(current.revision()));
            DeploymentRegistry.Record takenOver = await(registry.acquire("worker-here", TTL, takeoverCommand));
            assertTrue(takenOver.lease().fence() > fenceFromOtherProcess,
                    "the fencing counter lives in the database, so this process continues the other "
                            + "process's sequence rather than starting its own; a repeated token would let "
                            + "the abandoned worker write as though it were still the current owner");

            DeploymentRegistry.Lease stale = new DeploymentRegistry.Lease(tenant, deploymentId,
                    "worker-in-another-process", fenceFromOtherProcess, EPOCH, EPOCH.plus(TTL));
            assertStaleLeaseIsFencedEverywhere(registry, stale, takenOver, clock);
        }
    }

    /** Criterion 4: the process that lost the takeover cannot publish lifecycle state through any of the four lease-authorized operations. */
    private void assertStaleLeaseIsFencedEverywhere(DeploymentRegistry registry, DeploymentRegistry.Lease stale,
                                                     DeploymentRegistry.Record current, MutableClock clock) {
        String tenant = current.tenantId();
        DeploymentId deploymentId = current.deploymentId();

        var renewCommand = new DeploymentRegistry.Command(tenant, deploymentId, "renew-old", "d".repeat(64),
                RevisionExpectation.exactly(current.revision()));
        assertInstanceOf(DeploymentRegistry.FailureReason.Fenced.class,
                failureOf(() -> await(registry.renew(stale, TTL, renewCommand))).reason());

        var releaseCommand = new DeploymentRegistry.Command(tenant, deploymentId, "release-old", "e".repeat(64),
                RevisionExpectation.exactly(current.revision()));
        assertInstanceOf(DeploymentRegistry.FailureReason.Fenced.class,
                failureOf(() -> await(registry.release(stale, releaseCommand))).reason());

        var observation = new DeploymentRegistry.Observation(DeploymentRegistry.ObservedKind.READY, 1L, 0,
                clock.instant());
        var observeCommand = new DeploymentRegistry.Command(tenant, deploymentId, "observe-old", "f".repeat(64),
                RevisionExpectation.exactly(current.revision()));
        assertInstanceOf(DeploymentRegistry.FailureReason.Fenced.class,
                failureOf(() -> await(registry.observe(observation, stale, observeCommand))).reason());

        var failureReport = new DeploymentRegistry.Failure("STALE_OWNER", "synthetic failure from a fenced owner",
                clock.instant());
        var failCommand = new DeploymentRegistry.Command(tenant, deploymentId, "fail-old", "0".repeat(64),
                RevisionExpectation.exactly(current.revision()));
        assertInstanceOf(DeploymentRegistry.FailureReason.Fenced.class,
                failureOf(() -> await(registry.fail(failureReport, stale, failCommand))).reason());

        // None of the four refused attempts wrote anything: the aggregate is exactly where the
        // takeover left it.
        DeploymentRegistry.Record unchanged = await(registry.get(tenant, deploymentId)).orElseThrow();
        assertEquals(current, unchanged, "a fenced attempt must be refused atomically, not partially applied");
    }

    private long claimInAnotherProcess(Path file, String tenant, DeploymentId deploymentId, long expectedRevision)
            throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process child = new ProcessBuilder(List.of(java.toString(), "-cp",
                System.getProperty("java.class.path"), ClaimDeploymentLeaseInAnotherProcess.class.getName(),
                file.toString(), tenant, deploymentId.value(), String.valueOf(expectedRevision), EPOCH.toString(),
                TTL.toString())).redirectErrorStream(true).start();

        var transcript = new ArrayList<String>();
        String claimed = null;
        try (var output = new BufferedReader(new InputStreamReader(child.getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null) {
                transcript.add(line);
                if (line.startsWith(ClaimDeploymentLeaseInAnotherProcess.CLAIMED)) {
                    claimed = line;
                }
            }
        }
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the claiming process did not exit");
        assertEquals(0, child.exitValue(), "the claiming process failed: " + transcript);
        assertNotNull(claimed, "the other process never reported a claim: " + transcript);
        return Long.parseLong(claimed.substring(ClaimDeploymentLeaseInAnotherProcess.CLAIMED.length())
                .split(" ")[0]);
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private static DeploymentRegistry.RegistryException failureOf(Runnable operation) {
        CompletionException thrown = assertThrows(CompletionException.class, operation::run);
        return assertInstanceOf(DeploymentRegistry.RegistryException.class, thrown.getCause());
    }
}
