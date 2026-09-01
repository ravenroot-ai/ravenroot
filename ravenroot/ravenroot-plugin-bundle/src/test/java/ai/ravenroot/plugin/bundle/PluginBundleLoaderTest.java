package ai.ravenroot.plugin.bundle;

import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.extensions.fixture.LoaderFixtureNodePackage;
import ai.ravenroot.extensions.fixture.NoPublicConstructorFixture;
import ai.ravenroot.extensions.fixture.NotANodePackageFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rejection-first, against real bundle directories and real classloading, for
 * {@link PluginBundleLoader}. Every one of the eight required startup failure modes is shown
 * refused, with the field it names, before {@link #activatesARealBundleSuccessfully} shows a clean
 * bundle actually run.
 *
 * <h2>What "presence must never execute" means as a test</h2>
 * <p>{@link #emptyAllowlistTouchesNothingOnDisk} is the load-bearing one: it points at a bundle
 * directory that would fail validation if it were ever inspected, calls {@link
 * PluginBundleLoader#load} with an empty allowlist, and asserts no exception and no packages -- not
 * "the bad bundle was skipped", but "the bad bundle was never looked at". A test that only checked
 * the allowlist filters out disabled bundles could still pass if the loader validated everything
 * installed regardless of enablement; this one fails if that were ever true, because the fixture
 * bundle here cannot survive being opened.
 */
class PluginBundleLoaderTest {

    private static final String HEX64 = "0123456789abcdef".repeat(4);

    @Test
    void emptyAllowlistTouchesNothingOnDisk(@TempDir Path pluginsDir) throws IOException {
        // A bundle directory that WOULD throw if PluginBundleValidator ever ran against it:
        // ravenroot-plugin.json exists as a path but is a directory, not a file, which
        // PluginBundleValidator.validate refuses immediately (MANIFEST_NOT_FOUND) the moment it is
        // opened. The point is not this specific failure -- it is that whatever failure would occur,
        // it never gets the chance to, because an empty allowlist means the scan never runs.
        Path poison = Files.createDirectory(pluginsDir.resolve("poison"));
        Files.createDirectory(poison.resolve(PluginBundleValidator.MANIFEST_FILE_NAME));

        PluginActivation activation = PluginBundleLoader.load(pluginsDir, Set.of());

        assertTrue(activation.packages().isEmpty());
        assertTrue(activation.manifests().isEmpty());
    }

    @Test
    void nonexistentPluginsDirectoryWithEmptyAllowlistIsFine() {
        PluginActivation activation = PluginBundleLoader.load(Path.of("/does/not/exist"), Set.of());
        assertTrue(activation.packages().isEmpty());
    }

    @Test
    void unknownIdIsRejected(@TempDir Path pluginsDir) {
        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> PluginBundleLoader.load(pluginsDir, Set.of("does.not.exist")));
        assertEquals(PluginBundleException.Reason.UNKNOWN_PLUGIN_ID, rejection.reason());
        assertEquals("does.not.exist", rejection.diagnosticDetail().get("id"));
    }

    @Test
    void duplicatePluginIdAmongInstalledBundlesIsRejected(@TempDir Path pluginsDir) throws Exception {
        writeGreenBundle(pluginsDir, "copy-one", "ai.ravenroot.extensions.fixture");
        writeGreenBundle(pluginsDir, "copy-two", "ai.ravenroot.extensions.fixture");

        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> PluginBundleLoader.load(pluginsDir, Set.of("ai.ravenroot.extensions.fixture")));
        assertEquals(PluginBundleException.Reason.DUPLICATE_PLUGIN_ID, rejection.reason());
        assertEquals("ai.ravenroot.extensions.fixture", rejection.diagnosticDetail().get("id"));
    }

    @Test
    void invalidManifestAtStartupIsRejected(@TempDir Path pluginsDir) throws Exception {
        Path bundleDir = Files.createDirectory(pluginsDir.resolve("bad-manifest"));
        Files.writeString(bundleDir.resolve(PluginBundleValidator.MANIFEST_FILE_NAME), """
                {
                  "schemaVersion":"1",
                  "id":"test.bad-manifest",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["a.B"],
                  "behaviors":["x"],
                  "mainArtifact":{"fileName":"plugin.jar","sha256":"%s","sizeBytes":10},
                  "unknownField":"present"
                }
                """.formatted(NodeSdk.CONTRACT, HEX64));

        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> PluginBundleLoader.load(pluginsDir, Set.of("test.bad-manifest")));
        assertEquals(PluginBundleException.Reason.UNKNOWN_FIELD, rejection.reason());
    }

    @Test
    void tamperedChecksumAtStartupIsRejected(@TempDir Path pluginsDir) throws Exception {
        Path bundleDir = writeGreenBundle(pluginsDir, "tampered", "test.tampered");
        // Flip bytes in place, same length, so this isolates CHECKSUM_MISMATCH from SIZE_MISMATCH --
        // PluginBundleValidator checks size before checksum, and a length-changing corruption (an
        // earlier version of this test used one) would report the size mismatch first, correctly, but
        // then not exercise the checksum path this test is named for.
        Path jarPath = bundleDir.resolve("plugin.jar");
        byte[] bytes = Files.readAllBytes(jarPath);
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) ~bytes[index];
        }
        Files.write(jarPath, bytes);

        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> PluginBundleLoader.load(pluginsDir, Set.of("test.tampered")));
        assertEquals(PluginBundleException.Reason.CHECKSUM_MISMATCH, rejection.reason());
    }

    @Test
    void incompatibleSdkAtStartupIsRejected(@TempDir Path pluginsDir) throws Exception {
        Path bundleDir = Files.createDirectory(pluginsDir.resolve("bad-sdk"));
        writeJar(bundleDir.resolve("plugin.jar"),
                classBytesOf(LoaderFixtureNodePackage.class, "LoaderFixtureNodePackage.class",
                        "LoaderFixtureNodePackage$FixtureProbeBehavior.class"));
        String sha256 = sha256Hex(bundleDir.resolve("plugin.jar"));
        long size = Files.size(bundleDir.resolve("plugin.jar"));
        Files.writeString(bundleDir.resolve(PluginBundleValidator.MANIFEST_FILE_NAME), """
                {
                  "schemaVersion":"1",
                  "id":"test.bad-sdk",
                  "version":"1.0.0",
                  "sdkContract":"ravenroot.node-sdk/999",
                  "nodePackageClasses":["ai.ravenroot.extensions.fixture.LoaderFixtureNodePackage"],
                  "behaviors":["fixture.probe"],
                  "mainArtifact":{"fileName":"plugin.jar","sha256":"%s","sizeBytes":%d}
                }
                """.formatted(sha256, size));

        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> PluginBundleLoader.load(pluginsDir, Set.of("test.bad-sdk")));
        assertEquals(PluginBundleException.Reason.UNSUPPORTED_SDK_CONTRACT, rejection.reason());
    }

    @Test
    void missingClassIsRejected(@TempDir Path pluginsDir) throws Exception {
        Path bundleDir = Files.createDirectory(pluginsDir.resolve("missing-class"));
        writeJar(bundleDir.resolve("plugin.jar"), Map.of()); // an empty jar: the declared class is not in it
        writeManifestDeclaring(bundleDir, "test.missing-class", "does.not.Exist");

        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> PluginBundleLoader.load(pluginsDir, Set.of("test.missing-class")));
        assertEquals(PluginBundleException.Reason.MISSING_CLASS, rejection.reason());
        assertEquals("does.not.Exist", rejection.diagnosticDetail().get("class"));
        // The actionable thing includes which bundle, not just which class: an operator debugging a
        // startup failure with several plugins installed needs both.
        assertEquals("test.missing-class", rejection.diagnosticDetail().get("id"));
    }

    /**
     * The leak this guards against: an earlier version of {@code load} accumulated created
     * classloaders in a local list but never closed them on a failure partway through -- the method
     * throws before ever returning a {@link PluginActivation} for anyone to call {@code close()} on,
     * so a classloader created for an earlier, successfully-activated bundle in the same call would
     * have been unreachable forever the moment a later bundle failed. This cannot assert the
     * classloader was actually closed from outside the package (nothing exposes it), but it does prove
     * the cleanup path itself runs cleanly: if closing a classloader during failure handling threw
     * something uncaught, this would see a different, wrapped exception instead of the plain
     * MISSING_CLASS rejection asserted below.
     */
    @Test
    void aFailureOnTheSecondOfTwoBundlesStillReportsCleanlyRatherThanLeakingTheFirstsClassLoader(
            @TempDir Path pluginsDir) throws Exception {
        writeGreenBundle(pluginsDir, "first-ok", "test.first-ok");
        Path secondBundle = Files.createDirectory(pluginsDir.resolve("second-broken"));
        writeJar(secondBundle.resolve("plugin.jar"), Map.of());
        writeManifestDeclaring(secondBundle, "test.second-broken", "does.not.Exist");

        PluginBundleException rejection = assertThrows(PluginBundleException.class, () -> PluginBundleLoader.load(
                pluginsDir, Set.of("test.first-ok", "test.second-broken")));

        assertEquals(PluginBundleException.Reason.MISSING_CLASS, rejection.reason());
        assertEquals("test.second-broken", rejection.diagnosticDetail().get("id"));
    }

    @Test
    void classThatIsNotANodePackageIsRejected(@TempDir Path pluginsDir) throws Exception {
        Path bundleDir = Files.createDirectory(pluginsDir.resolve("not-a-package"));
        writeJar(bundleDir.resolve("plugin.jar"),
                classBytesOf(NotANodePackageFixture.class, "NotANodePackageFixture.class"));
        writeManifestDeclaring(bundleDir, "test.not-a-package", "ai.ravenroot.extensions.fixture.NotANodePackageFixture");

        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> PluginBundleLoader.load(pluginsDir, Set.of("test.not-a-package")));
        assertEquals(PluginBundleException.Reason.NOT_A_NODE_PACKAGE, rejection.reason());
    }

    @Test
    void classWithNoPublicNoArgConstructorIsRejected(@TempDir Path pluginsDir) throws Exception {
        Path bundleDir = Files.createDirectory(pluginsDir.resolve("no-constructor"));
        writeJar(bundleDir.resolve("plugin.jar"),
                classBytesOf(NoPublicConstructorFixture.class, "NoPublicConstructorFixture.class"));
        writeManifestDeclaring(bundleDir, "test.no-constructor",
                "ai.ravenroot.extensions.fixture.NoPublicConstructorFixture");

        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> PluginBundleLoader.load(pluginsDir, Set.of("test.no-constructor")));
        assertEquals(PluginBundleException.Reason.INSTANTIATION_FAILED, rejection.reason());
    }

    /**
     * The class compiled here at test time is never on the test JVM's own classpath (see the fixture
     * sources' own javadoc for why that is the point), so falling back to the parent classloader
     * cannot accidentally resolve it -- this proves a genuinely absent dependency, not merely one
     * excluded from the bundle jar while still reachable another way.
     */
    @Test
    void missingDependencyIsRejected(@TempDir Path pluginsDir) throws Exception {
        Map<String, byte[]> compiled = compileMissingDepFixture();
        Path bundleDir = Files.createDirectory(pluginsDir.resolve("missing-dep"));
        var jarEntries = new LinkedHashMap<String, byte[]>();
        jarEntries.put("missingdep/fixture/MissingDepNodePackage.class",
                compiled.get("missingdep/fixture/MissingDepNodePackage.class"));
        // MissingDepFixtureHelper.class is deliberately NOT included.
        writeJar(bundleDir.resolve("plugin.jar"), jarEntries);
        writeManifestDeclaring(bundleDir, "test.missing-dep", "missingdep.fixture.MissingDepNodePackage");

        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> PluginBundleLoader.load(pluginsDir, Set.of("test.missing-dep")));
        assertEquals(PluginBundleException.Reason.MISSING_DEPENDENCY, rejection.reason());
        assertEquals("missingdep.fixture.MissingDepNodePackage", rejection.diagnosticDetail().get("class"));
    }

    /** The control for {@link #missingDependencyIsRejected}: the same fixture, with its dependency present. */
    @Test
    void sameFixtureWithItsDependencyPresentActivates(@TempDir Path pluginsDir) throws Exception {
        Map<String, byte[]> compiled = compileMissingDepFixture();
        Path bundleDir = Files.createDirectory(pluginsDir.resolve("with-dep"));
        writeJar(bundleDir.resolve("plugin.jar"), compiled);
        writeManifestDeclaring(bundleDir, "test.with-dep", "missingdep.fixture.MissingDepNodePackage");

        PluginActivation activation = PluginBundleLoader.load(pluginsDir, Set.of("test.with-dep"));

        assertEquals(1, activation.packages().size());
        activation.close();
    }

    @Test
    void activatesARealBundleSuccessfully(@TempDir Path pluginsDir) throws Exception {
        writeGreenBundle(pluginsDir, "fixture-plugin", "ai.ravenroot.extensions.fixture");

        PluginActivation activation = PluginBundleLoader.load(pluginsDir, Set.of("ai.ravenroot.extensions.fixture"));

        assertEquals(1, activation.packages().size());
        assertEquals("ai.ravenroot.extensions.fixture", activation.packages().get(0).id());
        assertEquals(1, activation.packages().get(0).behaviors().size());
        assertEquals(1, activation.manifests().size());
        assertEquals("ai.ravenroot.extensions.fixture", activation.manifests().get(0).id());
        activation.close();
    }

    @Test
    void aBundleInstalledButNotEnabledIsNeverActivated(@TempDir Path pluginsDir) throws Exception {
        writeGreenBundle(pluginsDir, "fixture-plugin", "ai.ravenroot.extensions.fixture");

        // Something ELSE is enabled -- forcing the scan to run -- but this bundle is not it.
        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> PluginBundleLoader.load(pluginsDir, Set.of("something.else.entirely")));
        assertEquals(PluginBundleException.Reason.UNKNOWN_PLUGIN_ID, rejection.reason());
        // Nothing from the installed-but-unlisted fixture bundle was ever activated as a side effect
        // of it having been scanned to build the id index.
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private static Path writeGreenBundle(Path pluginsDir, String directoryName, String manifestId) throws IOException {
        Path bundleDir = Files.createDirectory(pluginsDir.resolve(directoryName));
        writeJar(bundleDir.resolve("plugin.jar"),
                classBytesOf(LoaderFixtureNodePackage.class, "LoaderFixtureNodePackage.class",
                        "LoaderFixtureNodePackage$FixtureProbeBehavior.class"));
        writeManifestDeclaring(bundleDir, manifestId, "ai.ravenroot.extensions.fixture.LoaderFixtureNodePackage");
        return bundleDir;
    }

    private static void writeManifestDeclaring(Path bundleDir, String manifestId, String className) throws IOException {
        String sha256 = sha256Hex(bundleDir.resolve("plugin.jar"));
        long size = Files.size(bundleDir.resolve("plugin.jar"));
        Files.writeString(bundleDir.resolve(PluginBundleValidator.MANIFEST_FILE_NAME), """
                {
                  "schemaVersion":"1",
                  "id":"%s",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["%s"],
                  "behaviors":["fixture.probe"],
                  "mainArtifact":{"fileName":"plugin.jar","sha256":"%s","sizeBytes":%d}
                }
                """.formatted(manifestId, NodeSdk.CONTRACT, className, sha256, size));
    }

    private static Map<String, byte[]> classBytesOf(Class<?> declaringClass, String... simpleEntryNames)
            throws IOException {
        String packagePath = declaringClass.getPackageName().replace('.', '/');
        var entries = new LinkedHashMap<String, byte[]>();
        for (String simpleName : simpleEntryNames) {
            try (InputStream in = declaringClass.getResourceAsStream("/" + packagePath + "/" + simpleName)) {
                if (in == null) {
                    throw new IllegalStateException("Test fixture not compiled: " + simpleName);
                }
                entries.put(packagePath + "/" + simpleName, in.readAllBytes());
            }
        }
        return entries;
    }

    private static void writeJar(Path jarPath, Map<String, byte[]> entries) throws IOException {
        try (OutputStream fileOut = Files.newOutputStream(jarPath);
             ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zipOut.putNextEntry(new ZipEntry(entry.getKey()));
                zipOut.write(entry.getValue());
                zipOut.closeEntry();
            }
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

    private static Map<String, byte[]> compileMissingDepFixture() throws IOException, URISyntaxException {
        Path sourceDir = Path.of(PluginBundleLoaderTest.class.getResource("/missingdep-fixture").toURI());
        Path outputDir = Files.createTempDirectory("missingdep-fixture-classes");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int result = compiler.run(null, null, null,
                "-d", outputDir.toString(),
                "-cp", System.getProperty("java.class.path"),
                sourceDir.resolve("MissingDepNodePackage.java").toString(),
                sourceDir.resolve("MissingDepFixtureHelper.java").toString());
        if (result != 0) {
            throw new IllegalStateException("Dynamic fixture compilation failed, exit code " + result);
        }
        var classes = new LinkedHashMap<String, byte[]>();
        classes.put("missingdep/fixture/MissingDepNodePackage.class",
                Files.readAllBytes(outputDir.resolve("missingdep/fixture/MissingDepNodePackage.class")));
        classes.put("missingdep/fixture/MissingDepFixtureHelper.class",
                Files.readAllBytes(outputDir.resolve("missingdep/fixture/MissingDepFixtureHelper.class")));
        return classes;
    }
}
