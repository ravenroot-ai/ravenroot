package ai.ravenroot.plugin.bundle;

import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.extensions.fixture.LoaderFixtureNodePackage;
import ai.ravenroot.extensions.fixture.NoPublicConstructorFixture;
import ai.ravenroot.extensions.fixture.NotANodePackageFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code PluginCli} run as a real subprocess, the same way {@code plugin.sh} invokes it -- exercising
 * the actual entry point, its exit codes and its stdout/stderr split, not just the library methods it
 * delegates to (already covered by {@code PluginBundleValidatorTest} and {@code PluginManifestTest}).
 */
class PluginCliTest {

    @Test
    void validateSucceedsOnACleanBundle(@TempDir Path pluginsDir) throws Exception {
        Path bundleDir = writeGreenBundle(pluginsDir, "clean-bundle");

        Result result = runCli("validate", bundleDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ai.ravenroot.extensions.fixture"));
    }

    @Test
    void validateFailsWithDiagnosticDetailOnStderr(@TempDir Path pluginsDir) throws Exception {
        Path bundleDir = Files.createDirectory(pluginsDir.resolve("broken"));
        Files.writeString(bundleDir.resolve(PluginBundleValidator.MANIFEST_FILE_NAME), "not json");

        Result result = runCli("validate", bundleDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("MALFORMED_MANIFEST"),
                () -> "expected the reason on stderr: " + result.stderr());
    }

    @Test
    void listOnAnEmptyDirectoryReportsNothingFoundRatherThanFailing(@TempDir Path pluginsDir) throws Exception {
        Result result = runCli("list", pluginsDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("no plugin bundles found"));
    }

    @Test
    void listReportsEachBundleAndExitsNonZeroWhenAnyIsInvalid(@TempDir Path pluginsDir) throws Exception {
        writeGreenBundle(pluginsDir, "good");
        Path badBundle = Files.createDirectory(pluginsDir.resolve("bad"));
        Files.writeString(badBundle.resolve(PluginBundleValidator.MANIFEST_FILE_NAME), "not json");

        Result result = runCli("list", pluginsDir.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("OK       good"), () -> result.stdout());
        assertTrue(result.stdout().contains("INVALID  bad"), () -> result.stdout());
    }

    @Test
    void manifestIdPrintsExactlyTheIdOnSuccess(@TempDir Path pluginsDir) throws Exception {
        Path bundleDir = writeGreenBundle(pluginsDir, "clean-bundle");

        Result result = runCli("manifest-id", bundleDir.toString());

        assertEquals(0, result.exitCode());
        assertEquals("ai.ravenroot.extensions.fixture", result.stdout().strip());
    }

    @Test
    void generateManifestProducesASelfValidatingBundle(@TempDir Path workspace) throws Exception {
        Path mainJar = workspace.resolve("main.jar");
        writeJarWithClass(mainJar, LoaderFixtureNodePackage.class, "LoaderFixtureNodePackage.class",
                "LoaderFixtureNodePackage$FixtureProbeBehavior.class");
        Path outputDir = workspace.resolve("generated-bundle");

        Result result = runCli("generate-manifest", outputDir.toString(), mainJar.toString(),
                "ai.ravenroot.extensions.fixture.LoaderFixtureNodePackage");

        assertEquals(0, result.exitCode(), () -> "stderr: " + result.stderr());
        assertTrue(result.stdout().contains("id: ai.ravenroot.extensions.fixture"));
        // The generated manifest is re-validated by the same validator any other bundle would be.
        PluginManifest manifest = PluginBundleValidator.validate(outputDir);
        assertEquals("ai.ravenroot.extensions.fixture", manifest.id());
        assertEquals(1, manifest.behaviors().size());
    }

    @Test
    void generateManifestBindsPinnedDependencyCopyToExpectedDigest(@TempDir Path workspace) throws Exception {
        Path mainJar = workspace.resolve("main.jar");
        writeJarWithClass(mainJar, LoaderFixtureNodePackage.class, "LoaderFixtureNodePackage.class",
                "LoaderFixtureNodePackage$FixtureProbeBehavior.class");
        Path driver = workspace.resolve("operator-driver.jar");
        writeJarWithClass(driver, NotANodePackageFixture.class, "NotANodePackageFixture.class");
        String digest = sha256Hex(driver);
        Path output = workspace.resolve("pinned-bundle");

        Result accepted = runCli("generate-manifest", output.toString(), mainJar.toString(),
                "ai.ravenroot.extensions.fixture.LoaderFixtureNodePackage",
                "--pinned-dependency", driver.toString(), digest);

        assertEquals(0, accepted.exitCode(), accepted::stderr);
        PluginManifest manifest = PluginBundleValidator.validate(output);
        assertEquals(List.of("operator-driver.jar"),
                manifest.dependencyArtifacts().stream().map(PluginArtifact::fileName).toList());
        assertEquals(digest, manifest.dependencyArtifacts().getFirst().sha256Hex());

        Path refusedOutput = workspace.resolve("refused-bundle");
        Result refused = runCli("generate-manifest", refusedOutput.toString(), mainJar.toString(),
                "ai.ravenroot.extensions.fixture.LoaderFixtureNodePackage",
                "--pinned-dependency", driver.toString(), "0".repeat(64));
        assertEquals(1, refused.exitCode());
        assertTrue(Files.notExists(refusedOutput.resolve("operator-driver.jar")));
        assertTrue(Files.notExists(refusedOutput.resolve(PluginBundleValidator.MANIFEST_FILE_NAME)));
    }

    @Test
    void generateManifestAcceptsRepeatedPinnedDependenciesInDeclaredOrder(@TempDir Path workspace)
            throws Exception {
        Path mainJar = workspace.resolve("main.jar");
        writeJarWithClass(mainJar, LoaderFixtureNodePackage.class, "LoaderFixtureNodePackage.class",
                "LoaderFixtureNodePackage$FixtureProbeBehavior.class");
        Path postgresql = workspace.resolve("postgresql-42.7.7.jar");
        Path mysql = workspace.resolve("mysql-connector-j-9.5.0.jar");
        writeJarWithClass(postgresql, NotANodePackageFixture.class, "NotANodePackageFixture.class");
        writeJarWithClass(mysql, NoPublicConstructorFixture.class, "NoPublicConstructorFixture.class");
        Path output = workspace.resolve("multi-pinned-bundle");

        Result result = runCli("generate-manifest", output.toString(), mainJar.toString(),
                "ai.ravenroot.extensions.fixture.LoaderFixtureNodePackage",
                "--pinned-dependency", postgresql.toString(), sha256Hex(postgresql),
                "--pinned-dependency", mysql.toString(), sha256Hex(mysql));

        assertEquals(0, result.exitCode(), result::stderr);
        PluginManifest manifest = PluginBundleValidator.validate(output);
        assertEquals(List.of("postgresql-42.7.7.jar", "mysql-connector-j-9.5.0.jar"),
                manifest.dependencyArtifacts().stream().map(PluginArtifact::fileName).toList());
    }

    @Test
    void generateManifestRefusesDuplicatePinnedFilenamesBeforeWriting(@TempDir Path workspace)
            throws Exception {
        Path mainJar = workspace.resolve("main.jar");
        writeJarWithClass(mainJar, LoaderFixtureNodePackage.class, "LoaderFixtureNodePackage.class",
                "LoaderFixtureNodePackage$FixtureProbeBehavior.class");
        Path first = workspace.resolve("first/driver.jar");
        Path second = workspace.resolve("second/driver.jar");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        writeJarWithClass(first, NotANodePackageFixture.class, "NotANodePackageFixture.class");
        writeJarWithClass(second, NoPublicConstructorFixture.class, "NoPublicConstructorFixture.class");
        Path output = workspace.resolve("duplicate-bundle");

        Result result = runCli("generate-manifest", output.toString(), mainJar.toString(),
                "ai.ravenroot.extensions.fixture.LoaderFixtureNodePackage",
                "--pinned-dependency", first.toString(), sha256Hex(first),
                "--pinned-dependency", second.toString(), sha256Hex(second));

        assertEquals(2, result.exitCode());
        assertTrue(result.stderr().contains("Duplicate artifact filename"), result::stderr);
        assertTrue(Files.notExists(output));
    }

    @Test
    void validateRefusesMissingAndExtraArtifactsFromMultiPinnedBundle(@TempDir Path workspace)
            throws Exception {
        Path mainJar = workspace.resolve("main.jar");
        writeJarWithClass(mainJar, LoaderFixtureNodePackage.class, "LoaderFixtureNodePackage.class",
                "LoaderFixtureNodePackage$FixtureProbeBehavior.class");
        Path postgresql = workspace.resolve("postgresql-42.7.7.jar");
        Path mysql = workspace.resolve("mysql-connector-j-9.5.0.jar");
        writeJarWithClass(postgresql, NotANodePackageFixture.class, "NotANodePackageFixture.class");
        writeJarWithClass(mysql, NoPublicConstructorFixture.class, "NoPublicConstructorFixture.class");
        Path output = workspace.resolve("closed-bundle");
        assertEquals(0, runCli("generate-manifest", output.toString(), mainJar.toString(),
                "ai.ravenroot.extensions.fixture.LoaderFixtureNodePackage",
                "--pinned-dependency", postgresql.toString(), sha256Hex(postgresql),
                "--pinned-dependency", mysql.toString(), sha256Hex(mysql)).exitCode());

        Files.delete(output.resolve(mysql.getFileName()));
        assertEquals(1, runCli("validate", output.toString()).exitCode(),
                "a declared driver artifact must not be missing");

        Files.copy(mysql, output.resolve(mysql.getFileName()));
        Files.writeString(output.resolve("undeclared-driver.jar"), "extra");
        assertEquals(1, runCli("validate", output.toString()).exitCode(),
                "an undeclared driver artifact must not be present");
    }

    @Test
    void generateManifestRefusesAClassThatIsNotANodePackage(@TempDir Path workspace) throws Exception {
        Path mainJar = workspace.resolve("main.jar");
        writeJarWithClass(mainJar, NotANodePackageFixture.class, "NotANodePackageFixture.class");
        Path outputDir = workspace.resolve("generated-bundle");

        Result result = runCli("generate-manifest", outputDir.toString(), mainJar.toString(),
                "ai.ravenroot.extensions.fixture.NotANodePackageFixture");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("does not implement NodePackage"), () -> result.stderr());
    }

    @Test
    void verifySha256AcceptsOnlyTheExactRegularArtifact(@TempDir Path workspace) throws Exception {
        Path artifact = workspace.resolve("driver.jar");
        Files.writeString(artifact, "driver fixture");
        String digest = sha256Hex(artifact);

        assertEquals(0, runCli("verify-sha256", artifact.toString(), digest).exitCode());
        assertEquals(1, runCli("verify-sha256", artifact.toString(), "0".repeat(64)).exitCode());
        assertEquals(1, runCli("verify-sha256", artifact.toString(), digest.toUpperCase()).exitCode());

        Path symlink = workspace.resolve("driver-link.jar");
        Files.createSymbolicLink(symlink, artifact.getFileName());
        assertEquals(1, runCli("verify-sha256", symlink.toString(), digest).exitCode());
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private static Path writeGreenBundle(Path pluginsDir, String directoryName) throws Exception {
        Path bundleDir = Files.createDirectory(pluginsDir.resolve(directoryName));
        Path jarPath = bundleDir.resolve("plugin.jar");
        writeJarWithClass(jarPath, LoaderFixtureNodePackage.class, "LoaderFixtureNodePackage.class",
                "LoaderFixtureNodePackage$FixtureProbeBehavior.class");
        String sha256 = sha256Hex(jarPath);
        long size = Files.size(jarPath);
        Files.writeString(bundleDir.resolve(PluginBundleValidator.MANIFEST_FILE_NAME), """
                {
                  "schemaVersion":"1",
                  "id":"ai.ravenroot.extensions.fixture",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["ai.ravenroot.extensions.fixture.LoaderFixtureNodePackage"],
                  "behaviors":["fixture.probe"],
                  "mainArtifact":{"fileName":"plugin.jar","sha256":"%s","sizeBytes":%d}
                }
                """.formatted(NodeSdk.CONTRACT, sha256, size));
        return bundleDir;
    }

    private static void writeJarWithClass(Path jarPath, Class<?> declaringClass, String... simpleNames)
            throws IOException {
        String packagePath = declaringClass.getPackageName().replace('.', '/');
        try (OutputStream fileOut = Files.newOutputStream(jarPath);
             ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
            for (String simpleName : simpleNames) {
                try (InputStream in = declaringClass.getResourceAsStream("/" + packagePath + "/" + simpleName)) {
                    if (in == null) {
                        throw new IllegalStateException("Test fixture not compiled: " + simpleName);
                    }
                    zipOut.putNextEntry(new ZipEntry(packagePath + "/" + simpleName));
                    zipOut.write(in.readAllBytes());
                    zipOut.closeEntry();
                }
            }
        }
    }

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(file));
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Runs PluginCli as a genuine subprocess, on this test JVM's own classpath (already has
     * everything). Reads stdout then stderr sequentially rather than concurrently -- safe only
     * because every command exercised here produces at most a few hundred bytes on either stream,
     * well under typical OS pipe buffer sizes; a command with large output on both streams
     * simultaneously could deadlock this pattern (the child blocks writing to a full pipe while this
     * method is still blocked reading the other one) and would need concurrent draining instead.
     */
    private static Result runCli(String... args) throws IOException, InterruptedException {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        List<String> command = new ArrayList<>(List.of(javaBin, "-cp", System.getProperty("java.class.path"),
                "ai.ravenroot.plugin.bundle.PluginCli"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).start();
        String stdout = drain(process.getInputStream());
        String stderr = drain(process.getErrorStream());
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        int exitCode = finished ? process.exitValue() : -1;
        return new Result(exitCode, stdout, stderr);
    }

    private static String drain(InputStream stream) throws IOException {
        var buffer = new ByteArrayOutputStream();
        stream.transferTo(buffer);
        return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}
