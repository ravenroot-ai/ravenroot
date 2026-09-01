package ai.ravenroot.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the probe split ("readiness on /ready, liveness on /health")
 * against the shipped deployment artifacts themselves, not against a claim about them -- a
 * manifest is configuration, and a silent revert of a YAML path leaves no other trace for
 * anything to catch. This is the checkable form of the sentence in
 * {@code docs/runbooks/plat-02-readiness-drain-and-alerts.md} §1.
 *
 * <p>Text-based, not a full YAML/Helm parse: {@code deploy/helm/ravenroot/templates/deployment.yaml}
 * is a Go template, not valid standalone YAML (rendering it would require invoking the {@code helm}
 * binary, an extra tool dependency this check does not need to prove a literal path string is in
 * the right block). Deliberately the same targeted-scan style {@code scripts/check_argline.py}
 * already uses in this repository for exactly the same reason -- a full parser would be more
 * machinery than the property being checked warrants.</p>
 */
class DeploymentProbeWiringTest {
    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    @Test
    void rawKubernetesManifestReadinessProbeTargetsReadyAndLivenessProbeTargetsHealth() throws IOException {
        assertProbeWiring(REPO_ROOT.resolve("deploy/kubernetes/ravenroot.yaml"));
    }

    @Test
    void helmDeploymentTemplateReadinessProbeTargetsReadyAndLivenessProbeTargetsHealth() throws IOException {
        assertProbeWiring(REPO_ROOT.resolve("deploy/helm/ravenroot/templates/deployment.yaml"));
    }

    /**
     * Docker has no readiness concept (an unhealthy container is restarted, never drained), so its
     * {@code HEALTHCHECK} stays on {@code /health} deliberately -- see the Dockerfile's own comment
     * on the line this asserts. Checked against the Java source that actually decides the target
     * ({@code RavenrootHealthcheck}), which is the artifact a revert of the wrong file would not
     * touch, rather than against the Dockerfile's CMD line, which never spells out a path at all
     * (the path is hardcoded in this class, not passed as an argument).
     */
    @Test
    void dockerHealthcheckStillTargetsHealthNotReady() throws IOException {
        Path healthcheckSource = REPO_ROOT.resolve(
                "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/RavenrootHealthcheck.java");
        String source = Files.readString(healthcheckSource);
        assertTrue(source.contains("\"http://127.0.0.1:\" + port + \"/health\""),
                () -> "RavenrootHealthcheck must still target /health -- Docker restarts on "
                        + "unhealthy rather than draining, so pointing it at /ready would restart "
                        + "the container during a normal drain. File: " + healthcheckSource);
    }

    private static void assertProbeWiring(Path manifestPath) throws IOException {
        String text = Files.readString(manifestPath);
        assertEquals("/ready", pathWithinProbeBlock(text, "readinessProbe", manifestPath),
                () -> manifestPath + ": readinessProbe must target /ready, not /health -- see this "
                        + "class's own Javadoc and docs/runbooks/plat-02-readiness-drain-and-alerts.md §1");
        assertEquals("/health", pathWithinProbeBlock(text, "livenessProbe", manifestPath),
                () -> manifestPath + ": livenessProbe must stay on /health -- pointing it at /ready "
                        + "would restart the pod during a normal drain instead of rotating it out");
    }

    /** The value of the first {@code path:} key found strictly after the named probe's own
     * {@code <name>:} line and strictly before the next sibling {@code *Probe:} line (or end of
     * file) -- so a {@code path:} that belongs to the OTHER probe can never be matched here. */
    private static String pathWithinProbeBlock(String manifestText, String probeName, Path manifestPath) {
        List<String> lines = manifestText.lines().toList();
        int start = -1;
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).stripLeading().startsWith(probeName + ":")) {
                start = index;
                break;
            }
        }
        if (start < 0) {
            throw new AssertionError(manifestPath + " has no " + probeName + ": block at all");
        }
        Pattern pathLine = Pattern.compile("^\\s*path:\\s*(\\S+)\\s*$");
        Pattern nextProbe = Pattern.compile("^\\s*\\w*Probe:\\s*$");
        for (int index = start + 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (index > start + 1 && nextProbe.matcher(line).matches()) {
                break;
            }
            Matcher match = pathLine.matcher(line);
            if (match.matches()) {
                return match.group(1);
            }
        }
        throw new AssertionError(manifestPath + "'s " + probeName + ": block has no path: line "
                + "within it (searched from line " + (start + 1) + ")");
    }
}
