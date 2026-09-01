package ai.ravenroot.plugin.bundle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Rejection-first: a missing artifact and a symlink escaping the bundle root are shown rejected
 * before a legitimate in-bundle file is shown to resolve. {@link BundlePaths} is what makes the
 * escape check real rather than a string match on {@code ..} — see its class javadoc.
 */
class BundlePathsTest {

    @Test
    void resolveWithinBundleRejectsAMissingFile(@TempDir Path bundleRoot) {
        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> BundlePaths.resolveWithinBundle(bundleRoot, "does-not-exist.jar"));
        assertEquals(PluginBundleException.Reason.ARTIFACT_MISSING, rejection.reason());
    }

    @Test
    void resolveWithinBundleRejectsASymlinkThatEscapesTheBundleRoot(@TempDir Path workspace) throws IOException {
        Path bundleRoot = Files.createDirectory(workspace.resolve("bundle"));
        Path outside = Files.createDirectory(workspace.resolve("outside"));
        Path secret = Files.writeString(outside.resolve("secret.jar"), "not part of the bundle");
        Path escapingLink = bundleRoot.resolve("payload.jar");
        assumeSymlinksSupported(bundleRoot);
        Files.createSymbolicLink(escapingLink, secret);

        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> BundlePaths.resolveWithinBundle(bundleRoot, "payload.jar"));
        assertEquals(PluginBundleException.Reason.SYMLINK_ESCAPES_BUNDLE, rejection.reason());
    }

    @Test
    void listRegularFilesRejectsAnEscapingSymlinkEvenWhenNotTheOnlyEntry(@TempDir Path workspace) throws IOException {
        Path bundleRoot = Files.createDirectory(workspace.resolve("bundle"));
        Path outside = Files.createDirectory(workspace.resolve("outside"));
        Path secret = Files.writeString(outside.resolve("secret.jar"), "not part of the bundle");
        Files.writeString(bundleRoot.resolve("legitimate.jar"), "actually in the bundle");
        assumeSymlinksSupported(bundleRoot);
        Files.createSymbolicLink(bundleRoot.resolve("escape.jar"), secret);

        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> BundlePaths.listRegularFiles(bundleRoot));
        assertEquals(PluginBundleException.Reason.SYMLINK_ESCAPES_BUNDLE, rejection.reason());
    }

    @Test
    void resolveWithinBundleAcceptsALegitimateFile(@TempDir Path bundleRoot) throws IOException {
        Files.writeString(bundleRoot.resolve("payload.jar"), "contents");

        Path resolved = BundlePaths.resolveWithinBundle(bundleRoot, "payload.jar");

        assertEquals(bundleRoot.resolve("payload.jar").toRealPath(), resolved.toRealPath());
    }

    @Test
    void listRegularFilesReturnsExactlyWhatIsOnDisk(@TempDir Path bundleRoot) throws IOException {
        Files.writeString(bundleRoot.resolve("ravenroot-plugin.json"), "{}");
        Files.writeString(bundleRoot.resolve("payload.jar"), "contents");

        List<String> names = BundlePaths.listRegularFiles(bundleRoot);

        assertEquals(2, names.size());
        assertTrue(names.contains("ravenroot-plugin.json"));
        assertTrue(names.contains("payload.jar"));
    }

    /** Symlink creation requires a privilege this sandbox may not grant; skip rather than false-fail. */
    private static void assumeSymlinksSupported(Path directory) {
        Path probeTarget = directory.resolve(".symlink-probe-target");
        Path probeLink = directory.resolve(".symlink-probe-link");
        try {
            Files.writeString(probeTarget, "probe");
            Files.createSymbolicLink(probeLink, probeTarget);
        } catch (java.nio.file.FileSystemException unsupported) {
            assumeTrue(false, "Symbolic links are not supported in this environment");
        } catch (IOException failed) {
            throw new java.io.UncheckedIOException(failed);
        } finally {
            try {
                Files.deleteIfExists(probeLink);
                Files.deleteIfExists(probeTarget);
            } catch (IOException ignored) {
                // best-effort cleanup of the probe only
            }
        }
    }
}
