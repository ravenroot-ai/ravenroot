package ai.ravenroot.distribution;

import ai.ravenroot.api.node.NodeSdk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P7's RED control on the {@code ravenroot-distribution} side.
 *
 * <h2>Why this class had to exist</h2>
 * <p>Before it, P7's only coverage here was {@link
 * ReleaseArtifactModelAdapterBoundaryIT#shippedPluginBundlesDeclareNoGenerativeCapability()},
 * which asserts <em>zero violations against a clean repository</em>. That assertion is equally green
 * whether the predicate works or never looks at anything — the same defect class as a
 * check inside a condition that silently never fired was indistinguishable from one that fired and
 * passed. {@code GenerativeCapabilityScan}'s own unit tests cover the scan, but nothing exercised
 * {@link ReleaseArtifactBoundaryChecks#shippedPluginBundleScan()} — the entry point the
 * {@code package}-phase gate actually calls — with a non-empty result.</p>
 *
 * <h2>And why it is the test that makes the override honest</h2>
 * <p>{@link ReleaseArtifactBoundaryChecks#SHIPPED_PLUGIN_DIRS_PROPERTY} was introduced with a
 * javadoc saying it exists "so a test can point P7 at a fixture tree without moving the real
 * convention directory", and then no test used it — a written reason that nothing exercised, which
 * is the same family of defect as a false cost previously removed from the QA note. This class
 * is that test; the property's javadoc is now true because this file makes it so.</p>
 *
 * <p>The manifests below declare an artifact that does not exist on disk, deliberately: P7 reads
 * {@code ravenroot-plugin.json} and nothing else — no checksum verification, no class loading — so
 * a fixture that had to produce a real hashed jar would be testing {@code PluginBundleValidator}
 * instead of this predicate.</p>
 */
class ShippedPluginBundleScanTest {

    private static final String HEX64 = "0123456789abcdef".repeat(4);

    @AfterEach
    void clearOverride() {
        System.clearProperty(ReleaseArtifactBoundaryChecks.SHIPPED_PLUGIN_DIRS_PROPERTY);
    }

    /**
     * The critical case: a bundle whose node declares {@code ai} while
     * implementing neither watched SPI. P1/P2/P3/P6 are structurally blind to it — this fails.
     */
    @Test
    void aShippedBundleDeclaringAiFailsP7AndTheMessageNamesFileBundleAndReason(@TempDir Path plugins)
            throws IOException {
        writeBundle(plugins, "ai-bundle", "com.example.ai", "[\"ai\"]");
        pointP7At(plugins);

        var scan = ReleaseArtifactBoundaryChecks.shippedPluginBundleScan();

        assertEquals(1, scan.bundlesInspected(), () -> "P7 did not inspect the fixture bundle: " + scan.report());
        assertEquals(1, scan.violations().size(), () -> String.join("; ", scan.violations()));
        String violation = scan.violations().get(0);
        assertTrue(violation.contains("ravenroot-plugin.json"), () -> "no file named: " + violation);
        assertTrue(violation.contains("com.example.ai"), () -> "no bundle named: " + violation);
        assertTrue(violation.contains("[ai]"), () -> "no reason named: " + violation);
    }

    /**
     * The local half: the operator convention directory feeds a custom image, so even a
     * generative bundle there is not evidence that Ravenroot is publishing it.
     */
    @Test
    void anOperatorLocalGenerativeBundleIsOutsideTheOfficialPublicationScan(@TempDir Path workspace)
            throws IOException {
        writeBundle(workspace.resolve("ravenroot-plugins"), "ai-bundle", "com.example.ai", "[\"ai\"]");

        var scan = ReleaseArtifactBoundaryChecks.shippedPluginBundleScan(Optional.of(workspace));

        assertTrue(scan.violations().isEmpty(), () -> String.join("; ", scan.violations()));
        assertEquals(0, scan.bundlesInspected(), scan::report);
        assertEquals(java.util.List.of(workspace.resolve("ci-artifacts/backend/plugins")),
                scan.scan().directoriesAbsent(), scan::report);
    }

    /**
     * The publication half: moving the same manifest into the directory assembled from
     * {@code PUBLISHED_PLUGINS} changes only the boundary and must make P7 red.
     */
    @Test
    void anOfficiallyStagedGenerativeBundleFailsThePublicationScan(@TempDir Path workspace)
            throws IOException {
        Path published = workspace.resolve("ci-artifacts/backend/plugins");
        writeBundle(published, "ai-bundle", "com.example.ai", "[\"ai\"]");

        var scan = ReleaseArtifactBoundaryChecks.shippedPluginBundleScan(Optional.of(workspace));

        assertEquals(1, scan.bundlesInspected(), scan::report);
        assertEquals(1, scan.violations().size(), () -> String.join("; ", scan.violations()));
        assertTrue(scan.violations().get(0).contains("com.example.ai"), scan.violations().get(0));
        assertTrue(scan.violations().get(0).contains("[ai]"), scan.violations().get(0));
    }

    /** The GREEN half over the same entry point, so a red result above is known to mean something. */
    @Test
    void aShippedBundleDeclaringNoGenerativeCapabilityPassesP7AndIsStillCounted(@TempDir Path plugins)
            throws IOException {
        writeBundle(plugins, "mail-bundle", "com.example.mail", "[\"network\",\"side-effect\"]");
        pointP7At(plugins);

        var scan = ReleaseArtifactBoundaryChecks.shippedPluginBundleScan();

        assertTrue(scan.violations().isEmpty(), () -> String.join("; ", scan.violations()));
        assertEquals(1, scan.bundlesInspected(),
                () -> "A clean bundle must still be counted, or a pass cannot be told from a scan that "
                        + "looked at nothing: " + scan.report());
        assertTrue(scan.report().contains("inspected 1 plugin bundle"), scan.report());
    }

    /** Anti-false-green: silence about capabilities is not a clean bill of health. */
    @Test
    void aShippedBundleWhoseManifestOmitsTheFieldFailsP7AsIndeterminate(@TempDir Path plugins) throws IOException {
        writeBundle(plugins, "legacy-bundle", "com.example.legacy", null);
        pointP7At(plugins);

        var scan = ReleaseArtifactBoundaryChecks.shippedPluginBundleScan();

        assertEquals(1, scan.violations().size(), () -> String.join("; ", scan.violations()));
        assertTrue(scan.violations().get(0).contains("nodeCapabilities"), scan.violations().get(0));
    }

    /**
     * The override must <em>replace</em> the convention directories, not add to them. If it merely
     * added, every assertion above would silently also be scanning the real {@code ravenroot-plugins/}
     * of whatever checkout the suite runs in, and a developer with a bundle installed there would
     * see these tests fail for reasons that have nothing to do with their fixture.
     */
    @Test
    void theOverrideReplacesTheConventionDirectoriesRatherThanAddingToThem(@TempDir Path plugins)
            throws IOException {
        writeBundle(plugins, "only-bundle", "com.example.only", "[]");
        pointP7At(plugins);

        var scan = ReleaseArtifactBoundaryChecks.shippedPluginBundleScan();

        assertEquals(1, scan.bundlesInspected(), scan::report);
        assertEquals(java.util.List.of(plugins), scan.scan().directoriesFound(), scan::report);
        assertTrue(scan.scan().directoriesAbsent().isEmpty(), scan::report);
    }

    /** A directory that is not there ships nothing — sound, but it must be reported, never silent. */
    @Test
    void anAbsentOverrideDirectoryIsReportedRatherThanPassingQuietly(@TempDir Path workspace) {
        Path missing = workspace.resolve("nowhere");
        pointP7At(missing);

        var scan = ReleaseArtifactBoundaryChecks.shippedPluginBundleScan();

        assertTrue(scan.violations().isEmpty(), () -> String.join("; ", scan.violations()));
        assertEquals(0, scan.bundlesInspected());
        assertEquals(java.util.List.of(missing), scan.scan().directoriesAbsent());
        assertTrue(scan.report().contains("absent"), scan.report());
        assertFalse(scan.report().contains("inspected 1 "), scan.report());
    }

    private static void pointP7At(Path directory) {
        System.setProperty(ReleaseArtifactBoundaryChecks.SHIPPED_PLUGIN_DIRS_PROPERTY, directory.toString());
    }

    /** {@code capabilitiesJson} of {@code null} omits the key entirely — the legacy bundle shape. */
    private static void writeBundle(Path pluginsDir, String directoryName, String id, String capabilitiesJson)
            throws IOException {
        Path bundle = Files.createDirectories(pluginsDir.resolve(directoryName));
        String capabilitiesMember = capabilitiesJson == null ? ""
                : "  \"nodeCapabilities\":" + capabilitiesJson + ",\n";
        Files.writeString(bundle.resolve("ravenroot-plugin.json"), """
                {
                  "schemaVersion":"1",
                  "id":"%s",
                  "version":"1.0.0",
                  "sdkContract":"%s",
                  "nodePackageClasses":["%s.Package"],
                  "behaviors":["%s.probe"],
                %s  "mainArtifact":{"fileName":"main.jar","sha256":"%s","sizeBytes":10}
                }
                """.formatted(id, NodeSdk.CONTRACT, id, id, capabilitiesMember, HEX64));
    }
}
