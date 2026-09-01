package ai.ravenroot.server.qa03;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultGraphDeployment;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.testkit.persistence.KillMatrixArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The crash/replay matrix's deployment-hosted-traversal cell.
 *
 * <p>Kills a real {@link DefaultGraphDeployment}, wired to a real {@link PekkoExecutionEngine} and a
 * real {@link SqliteExecutionStore}, at the exact instant its {@code offerDurably} has durably
 * committed the inbound event's dedup receipt but has not yet created the process instance the
 * traversal would run on. See {@link DeploymentIngressKillBoundary}'s Javadoc for the full boundary
 * rationale and why a real production seam was neither needed nor available (ADR 0010 section 12.4).
 *
 * <h2>What this proves</h2>
 * <p>{@link ai.ravenroot.api.deployment.TrustedIngress#offerDurably}'s own Javadoc declares a
 * guarantee narrower than "resumes after a crash": <em>"the fact of commitment is never lost, and a
 * redelivered copy of the same event is always recognised as a duplicate rather than re-admitted"</em>
 * — nothing more. This cell proves exactly that boundary, both directions: the receipt survives the
 * kill, no process instance was ever created (the traversal is genuinely and permanently lost, not
 * merely unresumed), and a redelivery of the identical event after the kill is recognised as
 * {@link IngressReceipt.Duplicate} rather than starting a second traversal.</p>
 *
 * <h2>Coverage is Pekko; process death, not machine death; no seed</h2>
 * <p>Same declared scope as the rest of the matrix (see {@code CrashReplayMatrixScopeTest} in
 * {@code ravenroot-persistence-sqlite}, and {@code PekkoEngineDispatchKillTest}'s class Javadoc for why
 * this module's cells do not participate in that class's scan).</p>
 */
class DeploymentIngressKillTest {

    /**
     * Where a failing cell's {@code KillMatrixArtifact} is written. Deliberately <b>not</b>
     * {@code databaseDirectory}: that field is a JUnit {@code @TempDir}, deleted immediately after the
     * test method returns by default, which would delete the artifact at the exact moment a reader
     * needs it. {@code target/} survives until the next {@code mvn clean}, the same lifetime
     * surefire's own reports get.
     */
    private static final Path ARTIFACT_ROOT = Path.of("target");

    @TempDir
    Path databaseDirectory;

    @Test
    void aRedeliveredEventAfterAKillBetweenTheReceiptAndTheInstanceIsRecognisedAsDuplicate() throws Exception {
        Path file = databaseDirectory.resolve("deployment-ingress-kill.db");

        KillResult killed = runUntilBoundaryThenKill(file);
        assertTrue(killed.exitCode() != 0, "a SIGKILLed process must not report a clean exit; got "
                + killed.exitCode());
        Path snapshot = KillMatrixArtifact.snapshot(file);

        try {
            var key = new ExecutionKey(DeploymentIngressKillBoundary.TENANT, DeploymentIngressKillBoundary.INSTANCE_ID);
            try (var recovered = openAt(file)) {
                assertEquals(1L, await(recovered.inboxRecordCount(DeploymentIngressKillBoundary.TENANT)),
                        "the dedup receipt was committed before the kill and is durable -- the whole "
                                + "point of 'the fact of commitment is never lost'");

                var noInstance = assertThrows(ExecutionStoreException.class, () -> await(recovered.load(key)),
                        "the process instance must never have been created: the kill landed on "
                                + "openTraversalRecorder's first write, before it could commit");
                assertInstanceOf(ExecutionStoreFailure.NotFound.class, noInstance.failure());

                // Redelivery: the same event, offered again through a fresh deployment over the same
                // recovered store -- what a restarted runtime, or the source's own at-least-once
                // redelivery, actually does.
                IngressReceipt redelivered = redeliverThroughFreshDeployment(recovered);
                var duplicate = assertInstanceOf(IngressReceipt.Duplicate.class, redelivered,
                        "a redelivered copy of an event already durably committed must be recognised as "
                                + "a duplicate, never re-admitted as a second traversal");
                assertEquals(DeploymentIngressKillBoundary.IDEMPOTENT_KEY, duplicate.idempotentKey());

                assertEquals(1L, await(recovered.inboxRecordCount(DeploymentIngressKillBoundary.TENANT)),
                        "redelivery must not add a second receipt");
                var stillNoInstance = assertThrows(ExecutionStoreException.class, () -> await(recovered.load(key)),
                        "and must not silently start the traversal it could never resume -- "
                                + "TrustedIngress#offerDurably's guarantee is narrower than resumption, "
                                + "and a redelivery that quietly started one would be a correctness "
                                + "surprise, not a recovery");
                assertInstanceOf(ExecutionStoreFailure.NotFound.class, stillNoInstance.failure());
            }
        } catch (AssertionError failure) {
            throw withArtifact(failure, "DEPLOYMENT_INGRESS/BEFORE_INSTANCE_CREATED", snapshot, killed, file);
        }
    }

    /** Offers the identical event again, through a second deployment over the same recovered store. */
    private IngressReceipt redeliverThroughFreshDeployment(SqliteExecutionStore recovered) throws Exception {
        var security = new SecurityContext("qa03-redelivery", DeploymentIngressKillBoundary.TENANT,
                "qa03-redelivery-worker", PrincipalType.USER, "urn:ravenroot:qa03");
        try (var engine = new PekkoExecutionEngine("qa03-deployment-ingress-redelivery")) {
            var deployment = new DefaultGraphDeployment(DeploymentId.of("qa03-deployment"), engine,
                    BehaviorRegistry.standard(), new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(),
                    DeploymentIngressKillBoundary.GRAPH.getBytes(StandardCharsets.UTF_8),
                    DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY, recovered,
                    DefaultGraphDeployment.DEFAULT_INBOX_RETENTION, "qa03-redelivery-worker",
                    DeploymentIngressKillBoundary.LEASE_TTL);
            deployment.start(security).toCompletableFuture().get(20, TimeUnit.SECONDS);
            IngressReceipt receipt = deployment.ingress().offerDurably(security, IngressTarget.start(), "payload",
                    DeploymentIngressKillBoundary.SOURCE_ID, DeploymentIngressKillBoundary.IDEMPOTENT_KEY);
            deployment.stop().toCompletableFuture().get(20, TimeUnit.SECONDS);
            return receipt;
        }
    }

    private SqliteExecutionStore openAt(Path file) {
        return new SqliteExecutionStore(file, Clock.fixed(DeploymentIngressKillBoundary.NOW, ZoneOffset.UTC));
    }

    /** Appends the {@code KillMatrixArtifact} location to a failing assertion and rethrows it. */
    private AssertionError withArtifact(AssertionError failure, String cell, Path snapshot, KillResult killed,
                                        Path liveFile) {
        long observed;
        try (var store = openAt(snapshot)) {
            observed = store.load(new ExecutionKey(DeploymentIngressKillBoundary.TENANT,
                    DeploymentIngressKillBoundary.INSTANCE_ID)).toCompletableFuture().join().revision();
        } catch (RuntimeException notCreatedOrUnreadable) {
            // Expected in the passing case too: no process instance was ever created. -1 says so in
            // the artifact rather than masking it as an IO failure.
            observed = -1L;
        }
        String artifactMessage = KillMatrixArtifact.write(ARTIFACT_ROOT, cell, snapshot, killed.transcript(),
                rerunCommand(liveFile), 0L, observed);
        return new AssertionError(failure.getMessage() + artifactMessage, failure);
    }

    private static List<String> rerunCommand(Path file) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                DeploymentIngressKillBoundary.class.getName(), file.toString());
    }

    /** The child's exit code and full transcript, both needed later if the cell fails and an artifact is written. */
    private record KillResult(int exitCode, List<String> transcript) {
    }

    /**
     * Runs the child, waits for it to announce the boundary, kills it and returns its exit code.
     *
     * <p>Every step is asserted rather than assumed: the announcement must arrive, the child must
     * actually die, and it must not have printed {@link DeploymentIngressKillBoundary#COMPLETED}. A
     * kill test whose child finished normally would otherwise report a pass having tested nothing.</p>
     */
    private KillResult runUntilBoundaryThenKill(Path file) throws Exception {
        var command = new ArrayList<>(rerunCommand(file));
        Process child = new ProcessBuilder(command).redirectErrorStream(true).start();

        var transcript = new ArrayList<String>();
        Optional<String> boundary;
        try (var output = new BufferedReader(new InputStreamReader(child.getInputStream(),
                StandardCharsets.UTF_8))) {
            boundary = readUntilBoundary(output, transcript);
        } finally {
            child.destroyForcibly();
        }

        assertTrue(boundary.isPresent(),
                "the child never reached the deployment-ingress boundary; transcript: " + transcript);
        assertFalse(transcript.contains(DeploymentIngressKillBoundary.COMPLETED),
                "the child completed instead of parking at the boundary, so nothing was killed and this "
                        + "cell would have proved nothing; transcript: " + transcript);
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the child survived destroyForcibly()");
        return new KillResult(child.exitValue(), List.copyOf(transcript));
    }

    private static Optional<String> readUntilBoundary(BufferedReader output, List<String> transcript)
            throws Exception {
        String line;
        while ((line = output.readLine()) != null) {
            transcript.add(line);
            if (DeploymentIngressKillBoundary.AT_BOUNDARY.equals(line)) {
                return Optional.of(line);
            }
        }
        return Optional.empty();
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException wrapped) {
            var failure = ExecutionStoreException.unwrap(wrapped);
            throw failure == null ? wrapped : failure;
        }
    }
}
