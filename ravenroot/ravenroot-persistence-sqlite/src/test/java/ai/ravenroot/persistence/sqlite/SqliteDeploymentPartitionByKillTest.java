package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.GraphVersion;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.testkit.persistence.KillMatrixArtifact;
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
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue 91 acceptance criterion 7's "partition" row: the lease holder is {@code destroyForcibly()}d
 * without ever releasing, which is a genuinely different event from {@link SqliteDeploymentCrossProcessLeaseTest}
 * proving. That test's second process exits cleanly, so its store's {@code close()} runs; ADR 0038 D0
 * rule 1 declares a lease "time-bounded ownership evaluated against the authority's clock ... lazily
 * rather than by a background reaper", which is a claim about abandonment, not about clean shutdown, and
 * a test that only ever closes the other side never exercises it. Modeled on
 * {@link SqliteLeaseAbandonedByKillTest}: the killed process never releases anything, so the lease must
 * still exclude a new claimant until it expires on this process's clock, and only then does a takeover
 * succeed -- with the killed process's own fence permanently refused afterwards.
 *
 * <h2>Process death, not machine death</h2>
 * <p>The child is ended with a real {@code SIGKILL} via {@code Process#destroyForcibly()}, which
 * leaves the operating system page cache intact: a commit that reached the page cache but was never
 * fsynced still reaches the file after this kind of kill. This cell therefore says nothing about
 * durability across power loss, only about recovery from process death, exactly as
 * {@link CrashReplayMatrixScopeTest} requires every kill-cell class in this module to state. No seed:
 * the child is driven to an announced boundary and killed there, so the interleaving is exact and
 * reproducible without randomising anything.</p>
 */
class SqliteDeploymentPartitionByKillTest {

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(30);

    /**
     * Deliberately not {@code databaseDirectory}: a JUnit {@code @TempDir} is deleted immediately after
     * the test method returns, which would delete a failure artifact at the exact moment a reader needs
     * it. {@code target/} survives until the next {@code mvn clean}.
     */
    private static final Path ARTIFACT_ROOT = Path.of("target");

    @TempDir
    Path databaseDirectory;

    @Test
    void aLeaseHeldByAKilledProcessStillExcludesUntilExpiryThenTakeoverPermanentlyRefusesTheDeadHoldersFence()
            throws Exception {
        Path file = databaseDirectory.resolve("partition-by-kill.db");
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

        KillResult killed = runUntilBoundaryThenKill(file, tenant, deploymentId, revisionAfterCreate);
        assertTrue(killed.exitCode() != 0, "a SIGKILLed process must not report a clean exit; got "
                + killed.exitCode());
        long fenceFromKilledProcess = fenceFrom(killed.transcript());
        // Captured before any recovery below can touch the live file, used only if this cell fails.
        Path snapshot = KillMatrixArtifact.snapshot(file);

        try {
            try (DeploymentRegistry registry = new SqliteDeploymentRegistry(file, clock,
                    t -> DeploymentId.of("unused-here"))) {
                DeploymentRegistry.Record current = registry.get(tenant, deploymentId).toCompletableFuture().join()
                        .orElseThrow();
                assertEquals(fenceFromKilledProcess, current.lease().fence(),
                        "the kill released nothing: the lease the killed process wrote is still there");

                var contendedCommand = new DeploymentRegistry.Command(tenant, deploymentId, "contend",
                        "b".repeat(64), RevisionExpectation.exactly(current.revision()));
                DeploymentRegistry.RegistryException contended = failureOf(() ->
                        await(registry.acquire("worker-here", TTL, contendedCommand)));
                assertInstanceOf(DeploymentRegistry.FailureReason.Conflict.class, contended.reason(),
                        "a lease held by a killed process must still exclude a new claimant -- the kill "
                                + "released nothing, exactly as ADR 0038 D0 rule 1 requires");

                clock.set(current.lease().expiresAt());
                var takeoverCommand = new DeploymentRegistry.Command(tenant, deploymentId, "takeover",
                        "c".repeat(64), RevisionExpectation.exactly(current.revision()));
                DeploymentRegistry.Record takenOver = await(registry.acquire("worker-here", TTL, takeoverCommand));
                assertTrue(takenOver.lease().fence() > fenceFromKilledProcess,
                        "the fencing counter lives in the database and survives the kill, so this claim "
                                + "continues the killed process's sequence rather than restarting it");

                // The partition's asymmetry: the killed process never gets a chance to stop writing on
                // its own -- it is dead -- so the store's refusal is the only thing standing between its
                // stale fence and a write against a deployment someone else is now responsible for.
                DeploymentRegistry.Lease stale = new DeploymentRegistry.Lease(tenant, deploymentId,
                        "worker-in-another-process", fenceFromKilledProcess, EPOCH, EPOCH.plus(TTL));
                var releaseCommand = new DeploymentRegistry.Command(tenant, deploymentId, "release-old",
                        "d".repeat(64), RevisionExpectation.exactly(takenOver.revision()));
                DeploymentRegistry.RegistryException fenced = failureOf(() ->
                        await(registry.release(stale, releaseCommand)));
                assertInstanceOf(DeploymentRegistry.FailureReason.Fenced.class, fenced.reason(),
                        "a write presented with the killed process's fence must be refused permanently -- "
                                + "accepting it would let a process that no longer exists still mutate "
                                + "state the current owner believes only it can change");
            }
        } catch (AssertionError failure) {
            throw withArtifact(failure, "DEPLOYMENT_LEASE/PARTITION_BY_KILL", snapshot, killed, file, tenant,
                    deploymentId, revisionAfterCreate);
        }
    }

    private static long fenceFrom(List<String> transcript) {
        for (String line : transcript) {
            if (line.startsWith(HoldDeploymentLeaseUntilKilledInAnotherProcess.CLAIMED)) {
                return Long.parseLong(line.substring(
                        HoldDeploymentLeaseUntilKilledInAnotherProcess.CLAIMED.length()).split(" ")[0]);
            }
        }
        throw new AssertionError("the killed process never reported a claim; transcript: " + transcript);
    }

    private AssertionError withArtifact(AssertionError failure, String cell, Path snapshot, KillResult killed,
                                        Path liveFile, String tenant, DeploymentId deploymentId, long revision) {
        long observedRevision;
        try (DeploymentRegistry registry = new SqliteDeploymentRegistry(snapshot, new MutableClock(EPOCH),
                t -> DeploymentId.of("unused-in-artifact"))) {
            observedRevision = registry.get(tenant, deploymentId).toCompletableFuture().join()
                    .map(DeploymentRegistry.Record::revision).orElse(-1L);
        } catch (RuntimeException unreadable) {
            observedRevision = -1L;
        }
        String artifactMessage = KillMatrixArtifact.write(ARTIFACT_ROOT, cell, snapshot, killed.transcript(),
                rerunCommand(liveFile, tenant, deploymentId, revision), revision, observedRevision);
        return new AssertionError(failure.getMessage() + artifactMessage, failure);
    }

    private static List<String> rerunCommand(Path file, String tenant, DeploymentId deploymentId, long revision) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                HoldDeploymentLeaseUntilKilledInAnotherProcess.class.getName(), file.toString(), tenant,
                deploymentId.value(), String.valueOf(revision), EPOCH.toString(), TTL.toString());
    }

    private record KillResult(int exitCode, List<String> transcript) {
    }

    private KillResult runUntilBoundaryThenKill(Path file, String tenant, DeploymentId deploymentId, long revision)
            throws Exception {
        var command = new ArrayList<>(rerunCommand(file, tenant, deploymentId, revision));
        Process child = new ProcessBuilder(command).redirectErrorStream(true).start();

        var transcript = new ArrayList<String>();
        Optional<String> boundary;
        try (var output = new BufferedReader(new InputStreamReader(child.getInputStream(),
                StandardCharsets.UTF_8))) {
            boundary = readUntilBoundary(output, transcript);
        } finally {
            child.destroyForcibly();
        }

        assertTrue(boundary.isPresent(), "the child never reached the lease-held boundary; transcript: "
                + transcript);
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the child survived destroyForcibly()");
        return new KillResult(child.exitValue(), List.copyOf(transcript));
    }

    private static Optional<String> readUntilBoundary(BufferedReader output, List<String> transcript)
            throws Exception {
        String line;
        while ((line = output.readLine()) != null) {
            transcript.add(line);
            if (HoldDeploymentLeaseUntilKilledInAnotherProcess.AT_BOUNDARY.equals(line)) {
                return Optional.of(line);
            }
        }
        return Optional.empty();
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private static DeploymentRegistry.RegistryException failureOf(Runnable operation) {
        CompletionException thrown = assertThrows(CompletionException.class, operation::run);
        return assertInstanceOf(DeploymentRegistry.RegistryException.class, thrown.getCause());
    }
}
