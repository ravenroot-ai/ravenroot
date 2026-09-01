package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.embed.EmbedProjectionBudget;
import ai.ravenroot.api.embed.EmbedProvisionOutcome;
import ai.ravenroot.api.embed.EmbedRegistrationResolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real {@code SIGKILL} of a real process, placed at the two instants where the atomicity of a
 * revocation is decided.
 *
 * <h2>Why the boundary is chosen rather than sampled</h2>
 * <p>Killing a writer "somewhere during the revoke" lands in statement execution essentially every
 * time and almost never in the microseconds around {@code COMMIT}. Such a test proves that an
 * unfinished transaction leaves no trace, which nobody doubts, while never exercising the instant
 * where the answer could differ. {@link CommitBoundary} is the package-private seam that lets the
 * child park exactly at the boundary and announce it, so the kill is placed rather than hoped for.</p>
 *
 * <h2>What each direction proves</h2>
 * <ul>
 *   <li><strong>Before the commit</strong>: the revocation is fully written into the transaction and
 *   is then killed. After recovery the registration is exactly as it was — same revision, still
 *   active — so a half-applied revocation cannot leave a registration in a state nobody chose.</li>
 *   <li><strong>After the commit</strong>: the same revocation, killed with nothing between
 *   {@code COMMIT} returning and the signal. After recovery it is revoked, terminally, and a
 *   provision at the surviving revision is still refused. This is the property that matters: an
 *   operator who revoked and then lost the host must not find the embed serving again.</li>
 * </ul>
 *
 * <p>What it does <em>not</em> prove: durability against host failure. A {@code SIGKILL} leaves the
 * page cache intact, so an unsynced commit would still reach the file. That is why the store sets
 * {@code PRAGMA synchronous=FULL} rather than trusting this test to catch the difference.</p>
 */
class SqliteEmbedRegistrationKillAtCommitBoundaryTest {

    private static final Clock CLOCK = Clock.fixed(EmbedRegistrationFixtures.AT, ZoneOffset.UTC);

    @TempDir
    Path directory;

    @Test
    void aRevocationKilledBeforeItsCommitLeavesTheRegistrationExactlyAsItWas() throws Exception {
        long revision = seed();

        KillResult killed = runUntilBoundaryThenKill(KillEmbedRegistrationAtCommitBoundary.BEFORE_COMMIT,
                revision);
        assertTrue(killed.exitCode() != 0,
                "a SIGKILLed process must not report a clean exit; got " + killed.exitCode());

        try (var recovered = open()) {
            var loaded = assertInstanceOf(EmbedRegistrationResolution.Available.class,
                    recovered.resolveCurrent(EmbedRegistrationFixtures.workload(),
                            EmbedRegistrationFixtures.REGISTRATION)).aggregate();
            assertEquals(revision, loaded.revision(),
                    "an interrupted revocation must leave the revision where the last commit left it");
            assertTrue(loaded.active());
        }
    }

    @Test
    void aRevocationKilledImmediatelyAfterItsCommitIsStillARevocation() throws Exception {
        long revision = seed();

        KillResult killed = runUntilBoundaryThenKill(KillEmbedRegistrationAtCommitBoundary.AFTER_COMMIT,
                revision);
        assertTrue(killed.exitCode() != 0,
                "a SIGKILLed process must not report a clean exit; got " + killed.exitCode());

        try (var recovered = open()) {
            assertInstanceOf(EmbedRegistrationResolution.Unavailable.class,
                    recovered.resolveCurrent(EmbedRegistrationFixtures.workload(),
                            EmbedRegistrationFixtures.REGISTRATION),
                    "a committed revocation that a crash undoes is the failure this store must not have");
            assertEquals(EmbedProvisionOutcome.Reason.REGISTRATION_REVOKED,
                    assertInstanceOf(EmbedProvisionOutcome.Rejected.class,
                            recovered.provision(EmbedRegistrationFixtures.command(revision + 1,
                                    "sha256:after", "start"))).reason());
        }
    }

    private long seed() {
        try (var store = open()) {
            var provisioned = assertInstanceOf(EmbedProvisionOutcome.Provisioned.class,
                    store.provision(EmbedRegistrationFixtures.command(0, "sha256:a", "start")));
            assertFalse(provisioned.aggregate().revision() < 1);
            return provisioned.aggregate().revision();
        }
    }

    private SqliteEmbedRegistrationStore open() {
        return SqliteEmbedRegistrationStore.openUnder(directory, CLOCK, EmbedProjectionBudget.DEFAULTS);
    }

    private record KillResult(int exitCode, List<String> transcript) {
    }

    private KillResult runUntilBoundaryThenKill(String mode, long expectedRevision) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        var command = List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                KillEmbedRegistrationAtCommitBoundary.class.getName(), directory.toString(), mode,
                Long.toString(expectedRevision));
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
                "the child never reached the " + mode + " boundary; transcript: " + transcript);
        assertFalse(transcript.contains(KillEmbedRegistrationAtCommitBoundary.COMPLETED),
                "the child finished its revocation instead of parking, so nothing was killed and this "
                        + "test would have proved nothing; transcript: " + transcript);
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the child survived destroyForcibly()");
        return new KillResult(child.exitValue(), List.copyOf(transcript));
    }

    private static Optional<String> readUntilBoundary(BufferedReader output, List<String> transcript)
            throws Exception {
        String line;
        while ((line = output.readLine()) != null) {
            transcript.add(line);
            if (KillEmbedRegistrationAtCommitBoundary.AT_BOUNDARY.equals(line)) return Optional.of(line);
        }
        return Optional.empty();
    }
}
