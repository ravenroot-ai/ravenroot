package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.testkit.persistence.KillMatrixArtifact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the crash/replay matrix establishes, and what it does not.
 *
 * <p>This class asserts, <b>for the kill-cell test classes it can actually reach</b>, that the
 * matrix's scope cannot drift silently. A reliability suite whose limits live only in prose gets cited
 * for guarantees it never made — and a scan whose own reach is narrower than its assertions' names
 * claim is the same defect wearing a green build, which is precisely what this regression fix closes. See
 * {@link #harnessSources()} for exactly what "reach" means here, and the "not scanned" note below for
 * what it deliberately does not.
 *
 * <h2>1. Process death, not machine death</h2>
 * <p>Every cell in the matrix — in this module and in {@code ravenroot-server} alike — kills with a
 * real {@code SIGKILL}. That destroys the process without running a shutdown hook, a {@code finally}
 * or a close — but it <b>leaves the operating system page cache intact</b>. A commit that reached the
 * page cache and was never fsynced still reaches the file, so every cell would pass under
 * {@link SqliteStoreConfig.SynchronousMode#FULL} <em>and</em> under a reduced synchronous mode. The
 * matrix therefore proves atomicity and recovery across <b>process death</b> and must never be cited
 * as evidence of durability across power loss. Establishing the latter needs fsync-level fault
 * injection this environment does not have, so this suite deliberately makes no claim about power-loss
 * durability. This is a statement of the matrix's design invariant, true
 * everywhere it applies; {@link #harnessSources()}'s scan of it below is narrower than that, and says so.
 *
 * <h2>2. Reproducibility by determinism, not by seeding — scanned in this module, declared elsewhere</h2>
 * <p>See {@link KillMatrixArtifact}. No cell randomises anything, so no cell carries a seed. The two
 * scanning tests below verify that claim, and the page-cache statement above, by reading source — but
 * only for the kill-cell test classes {@link #harnessSources()} can reach, which is
 * {@code ravenroot-persistence-sqlite} alone. Those are, at the time of writing, five:
 * {@link SqliteKillAtCommitBoundaryTest}, {@link SqliteKillAtAttemptBoundaryTest},
 * {@link SqliteCrashBetweenCommitAndPublishTest}, {@link SqliteLeaseAbandonedByKillTest} and
 * {@link SqliteEmbedRegistrationKillAtCommitBoundaryTest} — the last of which kills a
 * process at the commit boundary of an embed <em>revocation</em> rather than of an execution batch,
 * because a revocation lost to a crash is the one durability gap in that store with a security
 * consequence rather than an operational one.
 *
 * <p><b>Two classes are not, and cannot be, reached by this scan:</b>
 * {@code ai.ravenroot.server.qa03.PekkoEngineDispatchKillTest} and
 * {@code ai.ravenroot.server.qa03.DeploymentIngressKillTest}, both in {@code ravenroot-server}. A
 * JUnit test whose working directory is this module has no standing to enumerate another module's
 * sources by relative path, and reaching across that boundary anyway — whether by a filesystem walk
 * assuming reactor layout or by shelling out to {@code git} from inside one module's test tree — is
 * the exact shape that a prior check was revised to remove: a JUnit guard whose Javadoc claimed a
 * reactor-wide scan while its root-resolution silently stopped at its own module, so a control byte
 * planted one module over went undetected under a claim of full coverage. That regression fix's own conclusion
 * was that reactor-wide enforcement belongs in a CI-level check, not inside one module's JUnit suite,
 * and this class does not re-introduce the pattern it was revised to remove under a different name.
 * Both excluded classes state the identical no-seed and page-cache declarations directly in their own
 * class Javadoc — that is where a reader must go to verify them, and this class points there rather
 * than silently implying its own scan already did.</p>
 *
 * <h2>3. Coverage is SQLite and Pekko — Akka is not exercised</h2>
 * <p>The documented requirements require SQLite and at least one engine adapter. Akka is not
 * buildable in this environment, so "at least one" is <b>Pekko</b>. A reader must not infer that both
 * engines were exercised.
 *
 * <h2>4. Causality invariants are not asserted here, and that is deliberate</h2>
 * <p>Loss and duplication are provable today and are proved. <b>Causality is not</b>: the journal
 * carries no envelopes yet; the decided causal model requires the trigger of a dispatch and, for
 * fan-in, the join-satisfying arrival. Approximating causality without
 * envelopes would produce a green cell asserting nothing, and a green cell is worse than a missing
 * one because it stops anyone looking. This invariant is therefore declared as depending on those
 * envelopes rather than faked here.
 */
class CrashReplayMatrixScopeTest {

    /** The two kill-cell test classes this scan structurally cannot reach; named so nothing is silent. */
    private static final List<String> NOT_SCANNED_CROSS_MODULE = List.of(
            "ai.ravenroot.server.qa03.PekkoEngineDispatchKillTest",
            "ai.ravenroot.server.qa03.DeploymentIngressKillTest");

    @Test
    @DisplayName("no kill-cell test class in this module carries a seed, because none randomises anything")
    void everyKillHarnessThisScanReachesCarriesNoSeed() throws Exception {
        // A seed field that nothing varies is a false instrument. If one is ever added in this
        // module, this fails and whoever added it has to justify the randomness that would make it
        // mean something. It cannot see ravenroot-server; NOT_SCANNED_CROSS_MODULE names who vouches
        // for those two instead (their own class Javadoc, read directly).
        List<Path> harnesses = harnessSources();
        assertEquals(5, harnesses.size(),
                "expected exactly the five kill-cell test classes this module currently has; found "
                        + harnesses + " -- if this changed on purpose, the count and the class Javadoc's "
                        + "file list both need updating together, not just one");
        assertTrue(harnesses.stream().anyMatch(p -> p.getFileName().toString()
                        .equals("SqliteLeaseAbandonedByKillTest.java")),
                "the lease-abandonment cell must be reachable by this scan; its name breaking the old "
                        + "SqliteKill*/SqliteCrash* prefix convention is exactly what made it invisible "
                        + "before this regression fix");
        for (Path harness : harnesses) {
            String source = Files.readString(harness);
            assertTrue(!source.contains("Random") && !source.contains("nextLong()"),
                    harness.getFileName() + " introduced randomness: it now needs a seed, and the "
                            + "matrix's reproducibility-by-determinism claim no longer holds");
        }
    }

    @Test
    @DisplayName("every kill-cell test class in this module states the page-cache limit in its own source")
    void everyKillHarnessThisScanReachesStatesThePageCacheLimit() throws Exception {
        for (Path harness : harnessSources()) {
            String source = Files.readString(harness);
            assertTrue(source.contains("page cache"),
                    harness.getFileName() + " does not state the page-cache limit. A kill suite that "
                            + "omits it will be cited as durability evidence it cannot supply");
        }
        assertEquals(2, NOT_SCANNED_CROSS_MODULE.size(),
                "this constant is the exclusion this class's Javadoc promises to carry in its own text; "
                        + "if a cross-module kill cell is added or removed, update this list and the "
                        + "Javadoc section above together");
    }

    @Test
    @DisplayName("the artifact names the cell, the revisions and the literal re-run command")
    void anArtifactCarriesEverythingNeededToRerunTheCell(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        Path database = dir.resolve("cell.db");
        Files.writeString(database, "not-a-real-database");
        List<String> command = List.of("java", "-cp", "cp", "KillAtAttemptBoundary", "db", "DISPATCH",
                "BEFORE_COMMIT", "7");

        String message = KillMatrixArtifact.write(dir, "DISPATCH/BEFORE_COMMIT", database,
                List.of("AT_BOUNDARY"), command, 7L, 9L);

        Path artifactDir = dir.resolve("kill-matrix").resolve("DISPATCH_BEFORE_COMMIT");
        assertTrue(Files.exists(artifactDir.resolve("cell.db")),
                "the database must be captured as the kill left it");
        assertTrue(Files.exists(artifactDir.resolve("child-output.txt")));
        String report = Files.readString(artifactDir.resolve("README.txt"));
        assertTrue(report.contains("DISPATCH/BEFORE_COMMIT"));
        assertTrue(report.contains("expected revision:  7"));
        assertTrue(report.contains("observed revision:  9"));
        assertTrue(report.contains(String.join(" ", command)),
                "the artifact must carry the literal re-run command, not the ingredients for one");
        assertTrue(report.contains("process death, NOT machine death"));
        assertTrue(message.contains("Re-run this exact cell with:"),
                "the failing assertion itself must point at the artifact");
    }

    /**
     * Every kill-cell test class {@code ravenroot-persistence-sqlite}'s own test tree contains, found
     * by scanning file names rather than a brittle fixed prefix: {@code *Test.java} whose name mentions
     * {@code Kill} or {@code Crash}, excluding this class itself.
     *
     * <p>The old filter matched only a {@code SqliteKill}/{@code SqliteCrash} <em>prefix</em>, which is
     * why {@link SqliteLeaseAbandonedByKillTest} — real, in this same directory, genuinely performing a
     * {@code SIGKILL} — was invisible to it: its name begins {@code SqliteLease}. A substring match
     * still cannot see {@code SqliteCrossProcessLeaseTest}, and correctly so: that class deliberately
     * exits its second process cleanly rather than killing it (see its own Javadoc), so it makes no
     * page-cache or no-seed claim for this scan to check.</p>
     *
     * <p>This method cannot leave {@code ravenroot-persistence-sqlite}'s own directory — see the class
     * Javadoc's "not scanned" note for the two classes that are out of its reach and why.</p>
     */
    private static List<Path> harnessSources() throws Exception {
        Path root = Path.of("src", "test", "java", "ai", "ravenroot", "persistence", "sqlite");
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith("Test.java")
                                && !name.equals("CrashReplayMatrixScopeTest.java")
                                && (name.contains("Kill") || name.contains("Crash"));
                    })
                    .sorted().toList();
        }
    }
}
