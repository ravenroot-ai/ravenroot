package ai.ravenroot.plugin.bundle;

import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.extensions.fixture.AllowedFixtureBehavior;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rejection-first: an invalid bundle candidate fails the whole run and copies nothing, before an
 * empty source and a valid bundle are each shown to produce exactly the destination they should.
 * This is what the Dockerfile's build stage calls; its "empty produces empty, unconditionally"
 * behaviour is what makes the image parity proof possible.
 */
class PluginBundleBuildCopyTest {

    @Test
    void anInvalidCandidateFailsTheRunAndCopiesNothing(@TempDir Path workspace) throws IOException {
        Path sourceDir = Files.createDirectory(workspace.resolve("source"));
        Path destDir = workspace.resolve("dest");
        Path badBundle = Files.createDirectory(sourceDir.resolve("bad-plugin"));
        // A manifest that references an artifact never present on disk -- this is the same kind of
        // rejection PluginBundleValidatorTest exercises directly; here the point is that the build
        // copy step surfaces it as a whole-run failure rather than skipping just this candidate.
        Files.writeString(badBundle.resolve(PluginBundleValidator.MANIFEST_FILE_NAME), """
                {
                  "schemaVersion":"1",
                  "id":"bad.plugin",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["bad.plugin.Missing"],
                  "behaviors":["bad.probe"],
                  "mainArtifact":{"fileName":"missing.jar","sha256":"%s","sizeBytes":1}
                }
                """.formatted(NodeSdk.CONTRACT, "0".repeat(64)));

        assertThrows(PluginBundleException.class, () -> PluginBundleBuildCopy.run(sourceDir, destDir));

        // destDir is created (main()'s contract is "always exists"), but nothing was copied into it.
        assertTrue(Files.isDirectory(destDir));
        assertFalse(Files.list(destDir).findAny().isPresent());
    }

    @Test
    void aNonexistentSourceProducesAnEmptyDestination(@TempDir Path workspace) throws IOException {
        Path sourceDir = workspace.resolve("does-not-exist");
        Path destDir = workspace.resolve("dest");

        List<String> copied = PluginBundleBuildCopy.run(sourceDir, destDir);

        assertTrue(copied.isEmpty());
        assertTrue(Files.isDirectory(destDir));
        assertFalse(Files.list(destDir).findAny().isPresent());
    }

    @Test
    void aTopLevelFileWithoutAParentBundleDirectoryIsNeverACandidate(@TempDir Path workspace) throws IOException {
        Path sourceDir = Files.createDirectory(workspace.resolve("source"));
        Path destDir = workspace.resolve("dest");
        Files.writeString(sourceDir.resolve("README.md"), "not a bundle");
        Files.writeString(sourceDir.resolve(".gitkeep"), "");

        List<String> copied = PluginBundleBuildCopy.run(sourceDir, destDir);

        assertTrue(copied.isEmpty());
        assertFalse(Files.list(destDir).findAny().isPresent());
    }

    @Test
    void aSubdirectoryWithoutAManifestIsNeverACandidate(@TempDir Path workspace) throws IOException {
        Path sourceDir = Files.createDirectory(workspace.resolve("source"));
        Path destDir = workspace.resolve("dest");
        Files.createDirectory(sourceDir.resolve("scratch"));
        Files.writeString(sourceDir.resolve("scratch").resolve("notes.txt"), "a developer's scratch dir");

        List<String> copied = PluginBundleBuildCopy.run(sourceDir, destDir);

        assertTrue(copied.isEmpty());
        assertFalse(Files.list(destDir).findAny().isPresent());
    }

    @Test
    void aValidBundleIsCopiedInFull(@TempDir Path workspace) throws Exception {
        Path sourceDir = Files.createDirectory(workspace.resolve("source"));
        Path destDir = workspace.resolve("dest");
        Path bundle = Files.createDirectory(sourceDir.resolve("fixture-plugin"));
        writeJarWithFixtureClass(bundle.resolve("plugin.jar"));
        String sha256 = sha256Hex(bundle.resolve("plugin.jar"));
        long size = Files.size(bundle.resolve("plugin.jar"));
        Files.writeString(bundle.resolve(PluginBundleValidator.MANIFEST_FILE_NAME), """
                {
                  "schemaVersion":"1",
                  "id":"ai.ravenroot.extensions.fixture",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["ai.ravenroot.extensions.fixture.AllowedFixtureBehavior"],
                  "behaviors":["fixture.probe"],
                  "mainArtifact":{"fileName":"plugin.jar","sha256":"%s","sizeBytes":%d}
                }
                """.formatted(NodeSdk.CONTRACT, sha256, size));

        List<String> copied = PluginBundleBuildCopy.run(sourceDir, destDir);

        assertEquals(List.of("fixture-plugin"), copied);
        Path copiedManifest = destDir.resolve("fixture-plugin").resolve(PluginBundleValidator.MANIFEST_FILE_NAME);
        Path copiedJar = destDir.resolve("fixture-plugin").resolve("plugin.jar");
        assertTrue(Files.isRegularFile(copiedManifest));
        assertTrue(Files.isRegularFile(copiedJar));
        assertEquals(size, Files.size(copiedJar));
        // The copy is itself still a valid bundle -- proves content integrity, not just presence.
        PluginBundleValidator.validate(destDir.resolve("fixture-plugin"));
    }

    private static void writeJarWithFixtureClass(Path jarPath) throws IOException {
        byte[] classBytes;
        try (InputStream in = AllowedFixtureBehavior.class.getResourceAsStream("AllowedFixtureBehavior.class")) {
            classBytes = in.readAllBytes();
        }
        try (OutputStream fileOut = Files.newOutputStream(jarPath);
             ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
            zipOut.putNextEntry(new ZipEntry("ai/ravenroot/extensions/fixture/AllowedFixtureBehavior.class"));
            zipOut.write(classBytes);
            zipOut.closeEntry();
        }
    }

    private static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(file));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
