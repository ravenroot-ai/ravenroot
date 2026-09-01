package ai.ravenroot.plugin.bundle;

import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.core.plugin.fixture.ReservedFixtureBehavior;
import ai.ravenroot.extensions.fixture.AllowedFixtureBehavior;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The end-to-end proof for PLAT-12: a tampered checksum, an undeclared file and a class
 * in a reserved package are each shown rejected on a real bundle directory on disk, before a clean
 * bundle is shown accepted. This is the suite the whole design was oriented around — everything else
 * in this module exists to make these five outcomes true.
 */
class PluginBundleValidatorTest {

    @Test
    void rejectsATamperedChecksum(@TempDir Path bundleDir) throws Exception {
        writeJarWithFixtureClass(bundleDir.resolve("plugin.jar"), AllowedFixtureBehavior.class,
                "ai/ravenroot/extensions/fixture/AllowedFixtureBehavior.class");
        String realChecksum = sha256Hex(bundleDir.resolve("plugin.jar"));
        String tamperedChecksum = flipFirstHexDigit(realChecksum);
        writeManifest(bundleDir, mainArtifactManifest(tamperedChecksum, Files.size(bundleDir.resolve("plugin.jar"))));

        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> PluginBundleValidator.validate(bundleDir));
        assertEquals(PluginBundleException.Reason.CHECKSUM_MISMATCH, rejection.reason());
    }

    @Test
    void rejectsASizeMismatch(@TempDir Path bundleDir) throws Exception {
        writeJarWithFixtureClass(bundleDir.resolve("plugin.jar"), AllowedFixtureBehavior.class,
                "ai/ravenroot/extensions/fixture/AllowedFixtureBehavior.class");
        String realChecksum = sha256Hex(bundleDir.resolve("plugin.jar"));
        long wrongSize = Files.size(bundleDir.resolve("plugin.jar")) + 1;
        writeManifest(bundleDir, mainArtifactManifest(realChecksum, wrongSize));

        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> PluginBundleValidator.validate(bundleDir));
        assertEquals(PluginBundleException.Reason.SIZE_MISMATCH, rejection.reason());
    }

    @Test
    void rejectsAnUndeclaredFile(@TempDir Path bundleDir) throws Exception {
        writeJarWithFixtureClass(bundleDir.resolve("plugin.jar"), AllowedFixtureBehavior.class,
                "ai/ravenroot/extensions/fixture/AllowedFixtureBehavior.class");
        writeManifest(bundleDir, mainArtifactManifest(
                sha256Hex(bundleDir.resolve("plugin.jar")), Files.size(bundleDir.resolve("plugin.jar"))));
        Files.writeString(bundleDir.resolve("extra-payload.bin"), "not declared anywhere");

        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> PluginBundleValidator.validate(bundleDir));
        assertEquals(PluginBundleException.Reason.UNDECLARED_FILE, rejection.reason());
    }

    @Test
    void rejectsAClassInAReservedPackageInsideAnOtherwiseWellFormedBundle(@TempDir Path bundleDir) throws Exception {
        // The checksum and size are correct for THIS jar, so the bundle passes every earlier check;
        // this proves the reserved-package scan is a real, separate gate and not merely inferred from
        // checksum validity.
        writeJarWithFixtureClass(bundleDir.resolve("plugin.jar"), ReservedFixtureBehavior.class,
                "ai/ravenroot/core/plugin/fixture/ReservedFixtureBehavior.class");
        writeManifest(bundleDir, mainArtifactManifest(
                sha256Hex(bundleDir.resolve("plugin.jar")), Files.size(bundleDir.resolve("plugin.jar"))));

        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> PluginBundleValidator.validate(bundleDir));
        assertEquals(PluginBundleException.Reason.RESERVED_PACKAGE, rejection.reason());
        assertEquals("ai.ravenroot.core.plugin.fixture.ReservedFixtureBehavior",
                rejection.diagnosticDetail().get("class"));
    }

    @Test
    void rejectsAMissingBundleDirectoryAsBundleNotFound(@TempDir Path tempDir) {
        Path bundleDir = tempDir.resolve("does-not-exist");

        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> PluginBundleValidator.validate(bundleDir));
        assertEquals(PluginBundleException.Reason.BUNDLE_NOT_FOUND, rejection.reason());
        assertEquals(bundleDir.toString(), rejection.diagnosticDetail().get("path"));
    }

    @Test
    void rejectsARegularFileInPlaceOfTheBundleDirectoryAsBundleNotFound(@TempDir Path tempDir) throws Exception {
        // The path exists -- it is a regular file, not a missing path -- so the message must not
        // claim it does not exist; it must stay true for this sub-case as well as the missing-path one.
        Path notADirectory = tempDir.resolve("file-not-dir-608.txt");
        Files.writeString(notADirectory, "this is a file, not a bundle directory");

        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> PluginBundleValidator.validate(notADirectory));
        assertEquals(PluginBundleException.Reason.BUNDLE_NOT_FOUND, rejection.reason());
        assertEquals(notADirectory.toString(), rejection.diagnosticDetail().get("path"));
    }

    @Test
    void rejectsABundleDirectoryWithNoManifestAsManifestNotFound(@TempDir Path bundleDir) throws Exception {
        // The directory is real and non-empty -- just missing ravenroot-plugin.json -- so this must
        // not be conflated with a bundle directory that does not exist at all.
        Files.writeString(bundleDir.resolve("plugin.jar"), "not a real jar, just present on disk");

        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> PluginBundleValidator.validate(bundleDir));
        assertEquals(PluginBundleException.Reason.MANIFEST_NOT_FOUND, rejection.reason());
        assertEquals(bundleDir.resolve(PluginBundleValidator.MANIFEST_FILE_NAME).toString(),
                rejection.diagnosticDetail().get("path"));
    }

    @Test
    void acceptsACleanBundle(@TempDir Path bundleDir) throws Exception {
        writeJarWithFixtureClass(bundleDir.resolve("plugin.jar"), AllowedFixtureBehavior.class,
                "ai/ravenroot/extensions/fixture/AllowedFixtureBehavior.class");
        writeManifest(bundleDir, mainArtifactManifest(
                sha256Hex(bundleDir.resolve("plugin.jar")), Files.size(bundleDir.resolve("plugin.jar"))));
        // A tolerated undeclared metadata file: present on disk, never in the manifest, still accepted.
        Files.writeString(bundleDir.resolve("LICENSE"), "Apache-2.0");

        PluginManifest manifest = PluginBundleValidator.validate(bundleDir);

        assertEquals("ai.ravenroot.extensions.fixture", manifest.id());
        assertEquals("plugin.jar", manifest.mainArtifact().fileName());
    }

    private static String mainArtifactManifest(String sha256, long sizeBytes) {
        return """
                {
                  "schemaVersion":"1",
                  "id":"ai.ravenroot.extensions.fixture",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["ai.ravenroot.extensions.fixture.AllowedFixtureBehavior"],
                  "behaviors":["fixture.probe"],
                  "mainArtifact":{"fileName":"plugin.jar","sha256":"%s","sizeBytes":%d}
                }
                """.formatted(NodeSdk.CONTRACT, sha256, sizeBytes);
    }

    private static void writeManifest(Path bundleDir, String json) throws IOException {
        Files.writeString(bundleDir.resolve(PluginBundleValidator.MANIFEST_FILE_NAME), json);
    }

    private static void writeJarWithFixtureClass(Path jarPath, Class<?> fixtureClass, String entryName)
            throws IOException {
        byte[] classBytes = readClassBytes(fixtureClass);
        try (OutputStream fileOut = Files.newOutputStream(jarPath);
             ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
            zipOut.putNextEntry(new ZipEntry(entryName));
            zipOut.write(classBytes);
            zipOut.closeEntry();
        }
    }

    private static byte[] readClassBytes(Class<?> type) throws IOException {
        String resourceName = type.getSimpleName() + ".class";
        try (InputStream in = type.getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IllegalStateException("Test fixture not compiled: " + resourceName);
            }
            return in.readAllBytes();
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

    private static String flipFirstHexDigit(String hex) {
        char first = hex.charAt(0);
        char flipped = first == '0' ? '1' : '0';
        return flipped + hex.substring(1);
    }
}
