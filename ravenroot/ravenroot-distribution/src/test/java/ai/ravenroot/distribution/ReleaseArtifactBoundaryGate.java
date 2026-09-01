package ai.ravenroot.distribution;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The actual, non-skippable release condition for EU AI Act art. 50 role
 * determination). Everything else in this package - {@link DefaultDistributionModelAdapterBoundaryTest}
 * and {@link ReleaseArtifactModelAdapterBoundaryIT} - is a well-reported JUnit test, and every JUnit
 * test in this reactor is skippable: {@code -DskipTests} suppresses Surefire, and, critically,
 * Failsafe honors the same property for {@code integration-test}/{@code verify} too. That is exactly
 * the command {@code Dockerfile}'s {@code java-build} stage runs - {@code mvn -B -DskipTests clean
 * package} - so until this class existed, the artifact that stage produces (and that {@code runtime}
 * copies into the published image) could be built without either check having run at all.
 *
 * <p><b>Why this belongs on the build side.</b> Workflow ordering and required-status configuration
 * are external conventions that can change independently of the artifact build. The art. 99(4)(g)
 * ceiling of EUR 15,000,000 or 3% of worldwide
 * turnover attaches to operators - provider or deployer - so on the qualification recorded in
 * {@code docs/architecture/decisions/0017-article-50-product-decisions.md} it does not reach this
 * project today; it is what would be at stake if that qualification fell or drifted, which is
 * exactly what this condition exists to prevent. A release condition carrying that consequence
 * cannot rest on a convention. This class is the mechanism:
 * it runs inside the Maven build itself, at {@code package}, through a plugin ({@code
 * exec-maven-plugin}'s {@code exec} goal, in {@code ravenroot-distribution/pom.xml}, bound after both
 * the shade and assembly executions in the same phase) that does not participate in the {@code
 * skipTests}/{@code maven.test.skip} convention at all, so no test-skipping flag can silence it.
 *
 * <p><b>What that guarantee does not cover, and how the gap is closed.</b> This class's
 * Javadoc previously stopped at the paragraph above, which is true but incomplete on its own:
 * {@code exec-maven-plugin}'s {@code exec} goal has its own, generic skip property, {@code
 * exec.skip}, unrelated to the test-skipping convention - {@code -Dexec.skip=true} silences this
 * execution while leaving {@code skipTests}/{@code maven.test.skip} untouched, and {@code mvn
 * -Dexec.skip=true -DskipTests clean package} was measured to complete with exit 0 and produce a
 * {@code ravenroot.jar} this class never inspected. A single execution bound to a single plugin
 * cannot make its own binding immune to that plugin's own skip property; describing it as if it
 * could was the same defect class as the gap this note replaces - a comment asserting more than the
 * code guarantees. The fix is not in this class (the check itself was never wrong; it was never
 * asked to run) but in {@code ravenroot-distribution/pom.xml}: a second, redundant execution of this
 * same class, bound instead to a {@code maven-antrun-plugin} execution whose skip property ({@code
 * maven.antrun.skip}) is independent of {@code exec.skip}, so no single flag silences both bindings
 * of this check. See that pom's comments on the {@code exec-maven-plugin} and (redundant)
 * {@code maven-antrun-plugin} executions for the measured bypass and the verified skip-property
 * independence.
 *
 * <p><b>Cost, chosen deliberately.</b> This does not rerun the whole test suite - that is precisely
 * what {@code -DskipTests} exists to let a contributor or CI stage skip, and this class does not take
 * that away. It reruns only the release-artifact predicates (P1/P2/P3/P4/P6, via the shared {@link
 * ReleaseArtifactBoundaryChecks} and {@link BuiltArtifactIndices} - same code {@link
 * ReleaseArtifactModelAdapterBoundaryIT} uses, so the two cannot silently diverge), which measured
 * under two seconds against today's artifacts. That is the entire tax on {@code mvn -DskipTests
 * clean package}: a couple of seconds, always, in exchange for the property actually holding instead
 * of being stated.
 *
 * <p><b>Deliberately not tolerant of {@code -Dmaven.test.skip=true}.</b> {@code exec-maven-plugin}'s
 * {@code exec} goal (not {@code java} - see the pom comment on this execution for why: {@code exec}
 * forks a real process, so this class's {@code System.exit(1)} on a violation sets that process's
 * exit code and fails the Maven execution normally) is configured with {@code classpathScope=test},
 * so it needs {@code target/test-classes} to exist - which {@code -DskipTests} still compiles (it
 * only skips running the suite). {@code -Dmaven.test.skip=true} is a strictly more aggressive flag,
 * not used anywhere in this repository today, that skips compiling test sources too, which would
 * make this class simply not exist to run. Rather than let that surface as an opaque
 * {@code ClassNotFoundException} from the {@code exec} execution - fail-closed, but an unexplained
 * failure is an operability trap that invites someone to "fix" it by removing the gate instead of by
 * fixing their command - {@code ravenroot-distribution/pom.xml} refuses that flag explicitly, via a
 * {@code maven-antrun-plugin} execution (id {@code refuse-maven-test-skip}) that checks for this
 * class's compiled output and fails with a message naming the actual problem. That execution is
 * bound to the {@code process-test-classes} phase specifically so it always runs before
 * both {@code package}-phase executions of this check regardless of plugin declaration order in the
 * pom - phase order, not file order, is what makes it run first. Either way, the property holds: no
 * invocation of {@code package} in this module can finish successfully while this class's checks are
 * violated, or while whatever this class needs to run is itself missing.
 */
public final class ReleaseArtifactBoundaryGate {

    private static final Path RAVENROOT_JAR = Path.of("target", "ravenroot.jar");
    private static final Path RAVENROOT_BIN_ZIP = Path.of("target", "ravenroot-bin.zip");
    private static final Path BIN_ZIP_EXTRACT_DIR = Path.of("target", "package-phase-artifact-check", "bin");

    private ReleaseArtifactBoundaryGate() {
    }

    public static void main(String[] args) {
        List<String> violations = new ArrayList<>();
        int graphResourceEntriesScanned = -1; // -1: never computed (an earlier failure stopped us first)
        // P7/P9 reports, printed after the verdict whether or not anything was found. Both start
        // as "never computed" for the same reason graphResourceEntriesScanned does.
        ReleaseArtifactBoundaryChecks.ShippedBundleScan shippedBundles = null;
        List<String> assistantProviders = null;
        try {
            BuiltArtifactIndices artifacts = BuiltArtifactIndices.build(RAVENROOT_JAR, RAVENROOT_BIN_ZIP, BIN_ZIP_EXTRACT_DIR);

            // Anti-false-green guard first: if the scan itself is not seeing the artifacts, every
            // other check below is meaningless, and this must still fail loud rather than pass quiet.
            violations.addAll(ReleaseArtifactBoundaryChecks.sanityViolations(artifacts.jarIndex(), artifacts.jarLabel()));
            violations.addAll(ReleaseArtifactBoundaryChecks.sanityViolations(artifacts.binZipIndex(), artifacts.binZipLabel()));

            if (violations.isEmpty()) {
                violations.addAll(ReleaseArtifactBoundaryChecks.allViolations(
                        artifacts.jarIndex(), artifacts.jarLabel(), List.of(RAVENROOT_JAR)));
                violations.addAll(ReleaseArtifactBoundaryChecks.allViolations(
                        artifacts.binZipIndex(), artifacts.binZipLabel(), artifacts.binZipLibJars()));

                // P4 anti-vacuity report: allViolations() above already
                // folds P4's violations in, but discards how many ui/examples/** resources it actually
                // looked at - and "found nothing wrong" must never look the same as "found nothing to
                // look at". Recomputed here (a second, cheap jar scan) purely for this report.
                List<Path> allJarsForGraphScan = new ArrayList<>();
                allJarsForGraphScan.add(RAVENROOT_JAR);
                allJarsForGraphScan.addAll(artifacts.binZipLibJars());
                graphResourceEntriesScanned =
                        ReleaseArtifactBoundaryChecks.combinedGraphResourceScan(allJarsForGraphScan).entriesScanned();

                // P7/P8. Deliberately NOT folded into allViolations(): that method is
                // called once per artifact index, and P7 is a single scan of the repository's shipped
                // bundle directories - folding it in would report every finding twice and would make
                // the existing per-artifact predicates' contract read as something it is not. Keeping
                // allViolations() byte-for-byte unchanged is also what makes it checkable that
                // P1/P2/P3/P4/P6 are exactly as strong after this change as before it.
                shippedBundles = ReleaseArtifactBoundaryChecks.shippedPluginBundleScan();
                violations.addAll(shippedBundles.violations());
                violations.addAll(ReleaseArtifactBoundaryChecks.artifactEmbeddedBundleViolations(
                        RAVENROOT_JAR, BIN_ZIP_EXTRACT_DIR));

                // P9: inventory, never a refusal. sanityViolations() above already
                // failed the run if this list could only be empty for the wrong reason.
                assistantProviders = ReleaseArtifactBoundaryChecks.assistantProviderInventory(artifacts.jarIndex());
            }
        } catch (IllegalStateException | IOException e) {
            violations.add("The release-artifact boundary check could not run at all: "
                    + e.getMessage());
        }

        // The P7/P9 reports are printed before the verdict, so they are present on a red run
        // too: on a failure caused by P7 the count is the first thing a reader needs, and an
        // inventory that only appears when everything passed is an inventory nobody consults.
        if (shippedBundles != null) {
            System.out.println(shippedBundles.report());
        }
        if (assistantProviders != null) {
            System.out.println("P9 AssistantProvider implementations present in "
                    + RAVENROOT_JAR + ": " + assistantProviders + ". Reported, not refused: this "
                    + "inventory makes the AssistantProvider surface explicit.");
        }

        if (!violations.isEmpty()) {
            System.err.println();
            System.err.println("=".repeat(79));
            System.err.println("RELEASE CONDITION FAILED (EU AI Act art. 50) - "
                    + violations.size() + " violation(s). This build phase (package) cannot complete "
                    + "while the default distribution artifact fails this check; see "
                    + "ReleaseArtifactBoundaryGate's Javadoc for why this runs here and cannot be "
                    + "skipped with -DskipTests.");
            for (String violation : violations) {
                System.err.println("-".repeat(79));
                System.err.println(violation);
            }
            System.err.println("=".repeat(79));
            System.exit(1);
        }

        if (graphResourceEntriesScanned == 0) {
            System.out.println("=".repeat(79));
            System.out.println("P4 inspected ZERO ui/examples/** resources across "
                    + RAVENROOT_JAR + " and " + RAVENROOT_BIN_ZIP + "'s lib jars. This is sound only if "
                    + "ravenroot-ui/dist was genuinely never packaged into this build; it gives P4 no "
                    + "assurance for this run either way.");
            System.out.println("=".repeat(79));
        }

        System.out.println("Release-artifact boundary check passed for " + RAVENROOT_JAR
                + " and " + RAVENROOT_BIN_ZIP + " (" + graphResourceEntriesScanned
                + " ui/examples/** resource(s) inspected, "
                + (shippedBundles == null ? -1 : shippedBundles.bundlesInspected())
                + " shipped plugin bundle(s) inspected)");
    }
}
