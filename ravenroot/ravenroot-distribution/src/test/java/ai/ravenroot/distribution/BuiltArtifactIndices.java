package ai.ravenroot.distribution;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Builds a {@link ClassGraphIndex} for each of the two artifacts {@code ravenroot-distribution}
 * assembles - {@code ravenroot.jar} and {@code ravenroot-bin.zip}'s {@code lib/} jar set - so
 * {@link ReleaseArtifactModelAdapterBoundaryIT} (the reported, JUnit-visible form of the check) and
 * {@link ReleaseArtifactBoundaryGate} (the non-skippable, {@code package}-phase form - see that
 * class's Javadoc for why both exist) build the exact same indices from the exact same code, rather
 * than two independent extraction implementations that could quietly drift apart.
 */
final class BuiltArtifactIndices {

    static final Set<String> WATCHED_SPI = Set.of("ai.ravenroot.api.ai.ModelProvider", "ai.ravenroot.api.ai.AgentRuntime");

    private final ClassGraphIndex jarIndex;
    private final ClassGraphIndex binZipIndex;
    private final List<Path> binZipLibJars;
    private final Path jarPath;
    private final Path binZipPath;

    private BuiltArtifactIndices(ClassGraphIndex jarIndex, ClassGraphIndex binZipIndex, List<Path> binZipLibJars,
                                  Path jarPath, Path binZipPath) {
        this.jarIndex = jarIndex;
        this.binZipIndex = binZipIndex;
        this.binZipLibJars = binZipLibJars;
        this.jarPath = jarPath;
        this.binZipPath = binZipPath;
    }

    ClassGraphIndex jarIndex() {
        return jarIndex;
    }

    ClassGraphIndex binZipIndex() {
        return binZipIndex;
    }

    List<Path> binZipLibJars() {
        return binZipLibJars;
    }

    Path jarPath() {
        return jarPath;
    }

    Path binZipPath() {
        return binZipPath;
    }

    String jarLabel() {
        return jarPath.toString();
    }

    String binZipLabel() {
        return binZipPath + "!/lib/*.jar";
    }

    /**
     * @param ravenrootJar     {@code target/ravenroot.jar}, produced by the shade execution
     * @param ravenrootBinZip  {@code target/ravenroot-bin.zip}, produced by the assembly execution
     * @param extractDir       scratch directory the zip is extracted into; cleared first if present
     * @throws IllegalStateException if either artifact is missing, or the zip contains no lib jars -
     *         both are treated as fatal rather than a silent empty scan, because the property this
     *         check exists to guard cannot be evaluated at all without both artifacts.
     */
    static BuiltArtifactIndices build(Path ravenrootJar, Path ravenrootBinZip, Path extractDir) throws IOException {
        if (!Files.isRegularFile(ravenrootJar)) {
            throw new IllegalStateException(ravenrootJar + " does not exist. This check must run after "
                    + "the package phase's shade execution has produced it.");
        }
        if (!Files.isRegularFile(ravenrootBinZip)) {
            throw new IllegalStateException(ravenrootBinZip + " does not exist. This check must run "
                    + "after the package phase's assembly execution has produced it.");
        }

        var jarIndex = new ClassGraphIndex();
        jarIndex.addJar(ravenrootJar, WATCHED_SPI);

        if (Files.exists(extractDir)) {
            deleteRecursively(extractDir);
        }
        Files.createDirectories(extractDir);
        extractZip(ravenrootBinZip, extractDir);
        List<Path> libJars = findLibJars(extractDir);
        if (libJars.isEmpty()) {
            throw new IllegalStateException(ravenrootBinZip + " was extracted but no lib/*.jar was found "
                    + "under it. The assembly layout (src/assembly/ravenroot.xml) moved, or extraction "
                    + "failed silently.");
        }

        var binZipIndex = new ClassGraphIndex();
        for (Path libJar : libJars) {
            binZipIndex.addJar(libJar, WATCHED_SPI);
        }

        return new BuiltArtifactIndices(jarIndex, binZipIndex, libJars, ravenrootJar, ravenrootBinZip);
    }

    private static List<Path> findLibJars(Path extractedBinZipRoot) throws IOException {
        try (var walk = Files.walk(extractedBinZipRoot)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().replace(java.io.File.separatorChar, '/').contains("/lib/"))
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .toList();
        }
    }

    private static void extractZip(Path zipFile, Path targetDir) throws IOException {
        try (var zip = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                Path out = targetDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(targetDir)) {
                    // Zip-slip guard: this archive is our own build output, but a check that extracts
                    // arbitrary zip content should not assume that stays true forever.
                    throw new IOException("Zip entry escapes extraction directory: " + entry.getName());
                }
                Files.createDirectories(out.getParent());
                Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
