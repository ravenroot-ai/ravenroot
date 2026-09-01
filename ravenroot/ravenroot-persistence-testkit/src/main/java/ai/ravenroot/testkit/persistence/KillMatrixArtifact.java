package ai.ravenroot.testkit.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * The failure artifact for one cell of the crash/replay matrix (QA-03).
 *
 * <p>Relocated here from {@code ravenroot-persistence-sqlite} (QA-03 continuation) so that every
 * adapter's kill cells share one writer instead of each module growing its own copy. SQLite's cells
 * were the first to need it; the engine-adapter cell is the reason it had to leave that
 * module, because {@code ravenroot-pekko} and {@code ravenroot-server} cannot depend on
 * {@code ravenroot-persistence-sqlite}'s test sources to reach a package-private class.
 *
 * <h2>Reproducibility is achieved by determinism, not by seeding</h2>
 * <p>The contract requirement asks for "reproducible seeds and failure artifacts". This harness ships
 * artifacts and deliberately ships <b>no seed</b>, because there is nothing for a seed to vary.
 * The children here do not race: each is driven to an <em>announced</em> boundary, prints it, and is
 * killed at exactly that point. A seed reproduces a run you must hope lands on the same interleaving
 * again; an announced boundary reproduces the exact one, every time, on every machine.
 *
 * <p>Adding a seed field that nothing varies would be a false instrument — a control that looks like
 * it constrains something and constrains nothing — which is the defect class this matrix exists to
 * catch. Randomised kill timing would buy interleavings enumeration cannot reach, at the cost of a
 * suite that is flaky by design; a flaky P0 reliability matrix is worse than a narrower honest one,
 * because people stop reading it.
 *
 * <h2>What an artifact must contain</h2>
 * <p>Everything needed to re-run <em>this exact cell</em> without reconstructing anything, because an
 * artifact you have to build a command from is one nobody uses at three in the morning:
 * <ul>
 *   <li>the database file as it was left by the kill, copied before any recovery touches it;</li>
 *   <li>the child's full stdout/stderr transcript;</li>
 *   <li>the boundary identity — which cell, which side of the commit;</li>
 *   <li>expected versus observed revision;</li>
 *   <li>the literal command that re-runs the child at the same boundary.</li>
 * </ul>
 *
 * <h2>Why {@link #snapshot(Path)} exists as a separate step</h2>
 * <p>Opening a "recovered" store against the killed database file can checkpoint its {@code -wal}
 * sidecar into the main file, which changes what the file on disk means. A caller that captured the
 * artifact only after an assertion failed would therefore sometimes be capturing post-recovery state
 * and calling it "as the kill left it". {@link #snapshot(Path)} freezes the file and its sidecars
 * immediately after the kill, before anything reopens it; {@link #write} is then given the frozen copy
 * rather than the live path.</p>
 */
public final class KillMatrixArtifact {

    private final Path directory;

    private KillMatrixArtifact(Path directory) {
        this.directory = directory;
    }

    /**
     * Freezes {@code databaseFile} and its WAL/SHM/journal sidecars into a fresh temporary directory,
     * before anything else touches them, and returns the path to the frozen copy of the main file.
     *
     * <p>Call this immediately after the kill and before opening any "recovered" store. Pass the
     * returned path to {@link #write} if the test later fails; discard it otherwise.</p>
     */
    public static Path snapshot(Path databaseFile) {
        try {
            Path snapshotDir = Files.createTempDirectory("kill-matrix-snapshot");
            Path copy = snapshotDir.resolve(databaseFile.getFileName());
            if (Files.exists(databaseFile)) {
                Files.copy(databaseFile, copy, StandardCopyOption.REPLACE_EXISTING);
            }
            for (String suffix : List.of("-wal", "-shm", "-journal")) {
                Path sidecar = databaseFile.resolveSibling(databaseFile.getFileName() + suffix);
                if (Files.exists(sidecar)) {
                    Files.copy(sidecar, snapshotDir.resolve(sidecar.getFileName()),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return copy;
        } catch (IOException e) {
            throw new UncheckedIOException("could not snapshot " + databaseFile + " immediately after the kill",
                    e);
        }
    }

    /**
     * Writes an artifact for a failing cell and returns a message naming its location, suitable for
     * appending to the assertion that failed.
     *
     * @param cell            the boundary identity, for example {@code DISPATCH/BEFORE_COMMIT}
     * @param databaseFile    the database as the kill left it — ordinarily the path returned by a
     *                        prior {@link #snapshot}, not the live file, which recovery may have
     *                        already touched by the time a failure is known
     * @param transcript      every line the child printed before it died
     * @param command         the literal argv that re-runs the child at this boundary
     * @param expectedRevision the revision the cell asserted
     * @param observedRevision the revision actually found after recovery
     */
    public static String write(Path root, String cell, Path databaseFile, List<String> transcript,
                        List<String> command, long expectedRevision, long observedRevision) {
        try {
            Path directory = Files.createDirectories(root.resolve("kill-matrix").resolve(sanitise(cell)));
            var artifact = new KillMatrixArtifact(directory);
            artifact.copyDatabase(databaseFile);
            artifact.writeTranscript(transcript);
            artifact.writeReport(cell, databaseFile, command, expectedRevision, observedRevision);
            return System.lineSeparator() + "Failure artifact: " + directory.toAbsolutePath()
                    + System.lineSeparator() + "Re-run this exact cell with:" + System.lineSeparator()
                    + "  " + String.join(" ", command);
        } catch (IOException e) {
            // An artifact that cannot be written must not replace the real assertion failure with an
            // IO failure: the test is failing for a reason the reader needs, and this is a detail.
            return System.lineSeparator() + "(failure artifact could not be written: " + e + ")";
        }
    }

    private void copyDatabase(Path databaseFile) throws IOException {
        if (!Files.exists(databaseFile)) {
            Files.writeString(directory.resolve("database-MISSING.txt"),
                    "No database file at " + databaseFile.toAbsolutePath() + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            return;
        }
        Files.copy(databaseFile, directory.resolve(databaseFile.getFileName().toString()),
                StandardCopyOption.REPLACE_EXISTING);
        // SQLite's sidecars decide what the main file means after an abrupt death: a -wal holding
        // committed frames not yet checkpointed is the difference between "lost" and "recoverable",
        // so an artifact without them can mislead precisely when it matters most.
        for (String suffix : List.of("-wal", "-shm", "-journal")) {
            Path sidecar = databaseFile.resolveSibling(databaseFile.getFileName() + suffix);
            if (Files.exists(sidecar)) {
                Files.copy(sidecar, directory.resolve(sidecar.getFileName().toString()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void writeTranscript(List<String> transcript) throws IOException {
        Files.write(directory.resolve("child-output.txt"), transcript, StandardCharsets.UTF_8);
    }

    private void writeReport(String cell, Path databaseFile, List<String> command,
                             long expectedRevision, long observedRevision) throws IOException {
        String report = String.join(System.lineSeparator(), List.of(
                "Ravenroot crash/replay matrix — failure artifact (QA-03)",
                "",
                "cell:               " + cell,
                "database:           " + databaseFile.toAbsolutePath(),
                "expected revision:  " + expectedRevision,
                "observed revision:  " + observedRevision,
                "",
                "Re-run this exact cell:",
                "  " + String.join(" ", command),
                "",
                "This cell is deterministic: the child parks at an announced boundary and is killed",
                "there, so re-running reproduces the same interleaving exactly. There is no seed",
                "because nothing is randomised -- see KillMatrixArtifact's class javadoc.",
                "",
                "Scope of what a failure here means: process death, NOT machine death. A SIGKILL",
                "leaves the operating system page cache intact, so this evidence cannot distinguish",
                "synchronous=FULL from a reduced mode. See CrashReplayMatrixScopeTest.",
                "")) + System.lineSeparator();
        Files.writeString(directory.resolve("README.txt"), report, StandardCharsets.UTF_8);
    }

    private static String sanitise(String cell) {
        return cell.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
