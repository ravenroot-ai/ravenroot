package ai.ravenroot.devharness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The control that makes "this is never published" checkable instead of promised.
 *
 * <h2>Why this exists, in the shape the rest of the repository uses</h2>
 * <p>Three checks in {@code ravenroot-distribution} guard the release artifact's own boundary, and
 * {@code ReleaseArtifactBoundaryGate} is bound to {@code package} precisely so that
 * {@code -DskipTests} cannot skip it. That posture — <em>controls, not intentions</em> — is what this
 * module has to match, because a convenient bench is exactly the object that breaks ADR 0017's third
 * decision by inattention rather than by decision. Someone adds a publish step "just to share the
 * build with a colleague", and nothing anywhere fails.
 *
 * <p>The three existing checks cannot see this risk: they inspect what is <em>inside</em>
 * {@code ravenroot.jar} and {@code ravenroot-bin.zip}, and this module is outside both by
 * construction. The new risk is that this module's coordinates get <em>added to a pipeline</em>, and
 * that is a fact about the repository's own release surfaces rather than about a built jar. So this
 * check reads those surfaces.
 *
 * <h2>What is checked</h2>
 * <ol>
 *   <li>Every workflow under {@code .github/workflows}: a line naming this module is legal only
 *   inside a job on {@link #BUILD_AND_TEST_JOBS}. Any other job — the one that builds and pushes the
 *   image, the smoke test that runs a container, anything added later — fails.</li>
 *   <li>{@code Dockerfile}, {@code Dockerfile.ci}, {@code compose.yaml} and everything under
 *   {@code deploy/}: this module may not be named at all.</li>
 *   <li>{@code ravenroot/pom.xml}: this module may not appear in the release reactor's
 *   {@code <modules>} — the single edit that would make it part of the published build.</li>
 *   <li>This module's own {@code pom.xml} still sets {@code maven.deploy.skip} and
 *   {@code maven.install.skip}, so removing either is a build failure rather than a quiet change.</li>
 * </ol>
 *
 * <h2>What it deliberately does NOT check, and the limit that follows</h2>
 * <p><b>It cannot see the third condition.</b> Whether this bench is used for real internal work is
 * not observable from the repository, and no test here pretends otherwise. That condition is a
 * commitment, and ADR 0017 already names it the fragile one.
 *
 * <p><b>It runs in this module's own build.</b> Unlike {@code ReleaseArtifactBoundaryGate}, which is
 * bound to a phase of a module the release always builds, this check runs when someone builds the
 * harness. That is weaker in one specific way, and the weakness is worth stating rather than
 * glossing: an edit that added these coordinates to a publish job would be caught by the next build
 * of this module, not at the instant of the edit. Wiring this module into the existing
 * {@code backend-build} / {@code backend-test} CI jobs — beside {@code ravenroot-sample} and the
 * adapters, on the same install-then-build sequence — is what closes that gap, and is recommended
 * rather than done here because it edits a shared workflow file.
 */
class NotAReleaseArtifactTest {

    /**
     * This module's directory name and artifactId — the same token, which is why one search finds
     * both a workflow path reference and a Maven coordinate reference.
     */
    private static final String TOKEN = "ravenroot-dev-harness";

    /**
     * The only jobs in which naming this module is legitimate: compiling it and running its tests.
     *
     * <p>An allowlist rather than a blocklist of publish-shaped words, and the difference is the
     * point. A blocklist of {@code publish}, {@code deploy}, {@code release} is a list of the words
     * someone thought of, and the next pipeline will use a fourth. An allowlist fails closed on every
     * job that does not exist yet, which is the only class of job this control is actually for.
     */
    private static final Set<String> BUILD_AND_TEST_JOBS = Set.of("backend-build", "backend-test");

    private static final Path REPOSITORY_ROOT = repositoryRoot();

    @Test
    @DisplayName("no workflow names this module outside a build or test job")
    void noWorkflowNamesThisModuleOutsideABuildOrTestJob() throws IOException {
        List<Path> workflows = workflows();
        // ANTI-FALSE-GREEN: "found nothing wrong" and "found nothing to look at" must not look the
        // same. Without this, a check that resolved the wrong root would pass silently forever --
        // which is the exact failure mode ReleaseArtifactBoundaryChecks#sanityViolations exists for.
        assertFalse(workflows.isEmpty(), "no workflow files were scanned; this check would be vacuous");

        var violations = new ArrayList<String>();
        for (Path workflow : workflows) {
            violations.addAll(offendingJobReferences(workflow));
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    @DisplayName("no container or deployment surface names this module at all")
    void noContainerOrDeploymentSurfaceNamesThisModule() throws IOException {
        List<Path> surfaces = new ArrayList<>(List.of(
                REPOSITORY_ROOT.resolve("Dockerfile"),
                REPOSITORY_ROOT.resolve("Dockerfile.ci"),
                REPOSITORY_ROOT.resolve("compose.yaml")));
        Path deploy = REPOSITORY_ROOT.resolve("deploy");
        if (Files.isDirectory(deploy)) {
            try (Stream<Path> tree = Files.walk(deploy)) {
                tree.filter(Files::isRegularFile).forEach(surfaces::add);
            }
        }

        var scanned = 0;
        var violations = new ArrayList<String>();
        for (Path surface : surfaces) {
            if (!Files.isRegularFile(surface)) {
                continue;
            }
            scanned++;
            if (Files.readString(surface, StandardCharsets.UTF_8).contains(TOKEN)) {
                violations.add("Release boundary violated (ADR 0017): " + surface
                        + " names " + TOKEN + ". This module is source-only and must never be built "
                        + "into a container image or a deployment descriptor.");
            }
        }
        // ANTI-FALSE-GREEN, same reasoning as above.
        assertTrue(scanned >= 3, "expected at least Dockerfile, Dockerfile.ci and compose.yaml; scanned " + scanned);
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    @DisplayName("the release reactor does not list this module")
    void theReleaseReactorDoesNotListThisModule() throws IOException {
        Path reactor = REPOSITORY_ROOT.resolve("ravenroot").resolve("pom.xml");
        assertTrue(Files.isRegularFile(reactor), "the release reactor pom was not found at " + reactor);

        String pom = Files.readString(reactor, StandardCharsets.UTF_8);
        assertFalse(pom.contains(TOKEN),
                "Release boundary violated (ADR 0017): ravenroot/pom.xml names " + TOKEN
                        + ". Adding this module to the release reactor would build a program that "
                        + "registers a ModelProvider into the published artifact.");
        // ANTI-FALSE-GREEN: prove the file really is the reactor pom, so the assertion above is not
        // passing because it read something unrelated.
        assertTrue(pom.contains("<artifactId>ravenroot-core</artifactId>"),
                "the file scanned does not look like the release reactor pom");
    }

    @Test
    @DisplayName("this module still refuses to be installed or deployed")
    void thisModuleStillRefusesToBeInstalledOrDeployed() throws IOException {
        Path pom = REPOSITORY_ROOT.resolve(TOKEN).resolve("pom.xml");
        assertTrue(Files.isRegularFile(pom), "this module's own pom was not found at " + pom);

        String text = Files.readString(pom, StandardCharsets.UTF_8);
        // Whitespace-tolerant, because an IDE reformat must not be able to disarm a control.
        assertTrue(text.replaceAll("\\s+", "").contains("<maven.deploy.skip>true</maven.deploy.skip>"),
                "maven.deploy.skip is no longer set: this module could be published to a repository");
        assertTrue(text.replaceAll("\\s+", "").contains("<maven.install.skip>true</maven.install.skip>"),
                "maven.install.skip is no longer set: this module could be resolved by another project");
    }

    // ---- workflow scanning -------------------------------------------------------------------

    /**
     * Every reference to this module in {@code workflow} that sits outside an allowlisted job.
     *
     * <p>Jobs are the two-space keys under a top-level {@code jobs:}, which is how GitHub Actions
     * workflows are written and how the one in this repository is written. Text-level parsing rather
     * than a YAML library, for the same reason {@code PayloadJson} is hand-written: the check must
     * hold with no dependency this module would otherwise not need, and the grammar it needs is one
     * indentation rule.
     */
    private static List<String> offendingJobReferences(Path workflow) throws IOException {
        var violations = new ArrayList<String>();
        String currentJob = "";
        boolean insideJobs = false;
        int lineNumber = 0;

        for (String line : Files.readAllLines(workflow, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.startsWith("jobs:")) {
                insideJobs = true;
                currentJob = "";
                continue;
            }
            if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")) {
                // A new top-level key ends the jobs block.
                insideJobs = false;
                currentJob = "";
            }
            if (insideJobs && line.matches("^ {2}[A-Za-z0-9_-]+:\\s*$")) {
                currentJob = line.trim().replace(":", "");
            }
            if (!line.contains(TOKEN)) {
                continue;
            }
            if (insideJobs && BUILD_AND_TEST_JOBS.contains(currentJob.toLowerCase(Locale.ROOT))) {
                continue;
            }
            violations.add("Release boundary violated (ADR 0017): " + workflow + ":"
                    + lineNumber + " names " + TOKEN
                    + (currentJob.isEmpty() ? " outside any job" : " in job '" + currentJob + "'")
                    + ". This module is source-only and may be named only in "
                    + BUILD_AND_TEST_JOBS + ". A job that publishes, deploys, releases or containerises "
                    + "it would put a composed AI system on the market -- see ADR 0017 and "
                    + "docs/architecture/model-provider-adapters.md.");
        }
        return violations;
    }

    private static List<Path> workflows() throws IOException {
        Path directory = REPOSITORY_ROOT.resolve(".github").resolve("workflows");
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> tree = Files.list(directory)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .sorted()
                    .toList();
        }
    }

    /**
     * Walks up from the working directory to the first ancestor holding the reactor and this module.
     *
     * <p>Surefire runs with the module directory as the working directory, so the parent is normally
     * the answer; walking makes the check independent of how it was invoked instead of assuming one
     * depth. Every assertion above then re-establishes that the root it found is the real one, so a
     * wrong answer here surfaces as a failure rather than as a vacuous pass.
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("ravenroot/pom.xml"))
                    && Files.isRegularFile(candidate.resolve("ravenroot-dev-harness/pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new UncheckedIOException(new IOException(
                "could not locate the repository root from " + Path.of("").toAbsolutePath()));
    }
}
