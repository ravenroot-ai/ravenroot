package ai.ravenroot.plugin.bundle;

import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.provenance.SyntheticProvenance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Refusal-first, then the three ways a scan can find nothing — each of which must be
 * distinguishable from the others in the result, not merely absent from the violation list.
 *
 * <p>The case that matters most here is the one no other predicate in this repository covers: a
 * bundle whose node declares {@code "ai"} while implementing neither watched SPI. That bundle is
 * invisible to {@code ReleaseArtifactBoundaryChecks}' P1/P2/P3/P6 by construction, so a test that
 * only proved "a generative bundle is refused" through some SPI-shaped fixture would be proving the
 * wrong thing. Every fixture below declares no SPI at all.</p>
 */
class GenerativeCapabilityScanTest {

    /** The set the scan filters on is the runtime's own, not a copy — if that ever stops being true,
     * a bundle could be shippable and still be marked synthetic at runtime, or the reverse. */
    @Test
    void theTriggeringSetIsTheOneTheRuntimeMarksWith() {
        assertEquals(SyntheticProvenance.GENERATIVE_CAPABILITIES, java.util.Set.of("ai", "agentic"));
    }

    @Test
    void aBundleDeclaringAiIsRefusedEvenThoughItImplementsNoWatchedSpi(@TempDir Path plugins) throws IOException {
        writeBundle(plugins, "ai-bundle", "com.example.ai", "[\"ai\"]");

        GenerativeCapabilityScan.Result result = GenerativeCapabilityScan.scanDirectories(List.of(plugins));

        assertEquals(1, result.bundlesInspected());
        assertEquals(1, result.violations().size());
        String violation = result.violations().get(0);
        assertTrue(violation.contains("com.example.ai"), violation);
        assertTrue(violation.contains("ravenroot-plugin.json"), violation);
        assertTrue(violation.contains("[ai]"), violation);
    }

    @Test
    void agenticIsRefusedTooAndCaseAndPaddingDoNotEvadeTheCheck(@TempDir Path plugins) throws IOException {
        // Normalisation is SyntheticProvenance's, reached through the Collection overload, so a
        // manifest cannot slip past by declaring "  AGENTIC " where the runtime would still mark it.
        writeBundle(plugins, "agentic-bundle", "com.example.agentic", "[\"  AGENTIC \"]");

        GenerativeCapabilityScan.Result result = GenerativeCapabilityScan.scanDirectories(List.of(plugins));

        assertEquals(1, result.violations().size());
        assertTrue(result.violations().get(0).contains("[agentic]"), result.violations().get(0));
    }

    @Test
    void aBundleDeclaringOnlyNonGenerativeCapabilitiesPasses(@TempDir Path plugins) throws IOException {
        writeBundle(plugins, "mail-bundle", "com.example.mail", "[\"outbound-http\",\"templating\"]");

        GenerativeCapabilityScan.Result result = GenerativeCapabilityScan.scanDirectories(List.of(plugins));

        assertEquals(1, result.bundlesInspected());
        assertTrue(result.violations().isEmpty(), result.violations()::toString);
    }

    /** The distinction the whole field exists for: an explicitly empty list is an answer. */
    @Test
    void aBundleDeclaringAnEmptyCapabilityListPasses(@TempDir Path plugins) throws IOException {
        writeBundle(plugins, "quiet-bundle", "com.example.quiet", "[]");

        GenerativeCapabilityScan.Result result = GenerativeCapabilityScan.scanDirectories(List.of(plugins));

        assertEquals(1, result.bundlesInspected());
        assertTrue(result.violations().isEmpty(), result.violations()::toString);
    }

    /** ...and silence is not. This is the anti-false-green case: every bundle built before the field
     * existed would otherwise be silently exempt from the only check that can see it. */
    @Test
    void aBundleWhoseManifestOmitsTheFieldIsRefusedRatherThanAssumedClean(@TempDir Path plugins) throws IOException {
        writeBundle(plugins, "legacy-bundle", "com.example.legacy", null);

        GenerativeCapabilityScan.Result result = GenerativeCapabilityScan.scanDirectories(List.of(plugins));

        assertEquals(1, result.bundlesInspected());
        assertEquals(1, result.violations().size());
        String violation = result.violations().get(0);
        assertTrue(violation.contains("nodeCapabilities"), violation);
        assertTrue(violation.contains("com.example.legacy"), violation);
    }

    @Test
    void aBundleWithAnUnparseableManifestIsRefused(@TempDir Path plugins) throws IOException {
        Path bundle = Files.createDirectories(plugins.resolve("broken-bundle"));
        Files.writeString(bundle.resolve(PluginBundleValidator.MANIFEST_FILE_NAME), "{ not json");

        GenerativeCapabilityScan.Result result = GenerativeCapabilityScan.scanDirectories(List.of(plugins));

        assertEquals(1, result.bundlesInspected());
        assertEquals(1, result.violations().size());
        assertTrue(result.violations().get(0).contains("broken-bundle"), result.violations().get(0));
    }

    /**
     * An absent directory ships nothing, so it is sound — but the result must still say so. A caller
     * printing only {@code violations} would render this identically to a directory full of clean
     * bundles, which is the reporting failure P4 was revised twice to fix.
     */
    @Test
    void anAbsentDirectoryIsRecordedRatherThanSilentlyPassed(@TempDir Path workspace) {
        Path missing = workspace.resolve("nowhere");

        GenerativeCapabilityScan.Result result = GenerativeCapabilityScan.scanDirectories(List.of(missing));

        assertTrue(result.violations().isEmpty());
        assertEquals(0, result.bundlesInspected());
        assertEquals(List.of(missing), result.directoriesAbsent());
        assertTrue(result.directoriesFound().isEmpty());
        assertTrue(result.report().contains("absent"), result.report());
    }

    /** An existing but empty convention directory is the normal case today, and reports as such. */
    @Test
    void anEmptyDirectoryReportsZeroInspectedRatherThanNothingAtAll(@TempDir Path plugins) throws IOException {
        Files.writeString(plugins.resolve("README.md"), "loose files never claimed to be bundles");
        Files.writeString(plugins.resolve(".gitkeep"), "");

        GenerativeCapabilityScan.Result result = GenerativeCapabilityScan.scanDirectories(List.of(plugins));

        assertTrue(result.violations().isEmpty());
        assertEquals(0, result.bundlesInspected());
        assertEquals(List.of(plugins), result.directoriesFound());
        assertTrue(result.report().contains("inspected 0 plugin bundle"), result.report());
        assertFalse(result.report().contains("absent"), result.report());
    }

    @Test
    void oneGenerativeBundleAmongCleanOnesIsStillFound(@TempDir Path plugins) throws IOException {
        writeBundle(plugins, "a-clean", "com.example.a", "[]");
        writeBundle(plugins, "b-generative", "com.example.b", "[\"ai\",\"templating\"]");
        writeBundle(plugins, "c-clean", "com.example.c", "[\"outbound-http\"]");

        GenerativeCapabilityScan.Result result = GenerativeCapabilityScan.scanDirectories(List.of(plugins));

        assertEquals(3, result.bundlesInspected());
        assertEquals(1, result.violations().size());
        assertTrue(result.violations().get(0).contains("com.example.b"), result.violations().get(0));
    }

    /**
     * Writes a bundle whose manifest is self-consistent (the artifact it declares exists, with the
     * right digest and size) so the scan is exercised on a document that would also survive
     * {@link PluginBundleValidator}. {@code capabilitiesJson} of {@code null} omits the key.
     */
    private static void writeBundle(Path pluginsDir, String directoryName, String id, String capabilitiesJson)
            throws IOException {
        Path bundle = Files.createDirectories(pluginsDir.resolve(directoryName));
        byte[] jar = ("fake jar bytes for " + id).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(bundle.resolve("main.jar"), jar);
        String capabilitiesMember = capabilitiesJson == null ? ""
                : "  \"" + PluginManifest.NODE_CAPABILITIES_KEY + "\":" + capabilitiesJson + ",\n";
        Files.writeString(bundle.resolve(PluginBundleValidator.MANIFEST_FILE_NAME), """
                {
                  "schemaVersion":"1",
                  "id":"%s",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["%s.Package"],
                  "behaviors":["%s.probe"],
                %s  "mainArtifact":{"fileName":"main.jar","sha256":"%s","sizeBytes":%d}
                }
                """.formatted(id, NodeSdk.CONTRACT, id, id, capabilitiesMember, sha256Hex(jar), jar.length));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
