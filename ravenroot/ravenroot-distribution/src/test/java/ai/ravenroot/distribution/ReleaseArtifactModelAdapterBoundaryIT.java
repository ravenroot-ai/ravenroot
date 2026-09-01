package ai.ravenroot.distribution;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The legally authoritative, JUnit-reported release condition for EU AI Act art. 50
 * role determination). Inspects the artifacts {@code ravenroot-distribution} actually assembles -
 * {@code ravenroot.jar} (the shaded jar the container image runs, {@code Dockerfile}'s {@code
 * runtime} stage) and {@code ravenroot-bin.zip}'s {@code lib/} jar set (what {@code bin/ravenroot}/
 * {@code bin/ravenroot-server} put on the classpath) - not the source tree or the resolved
 * build-time classpath.
 *
 * <p><b>Why the artifact, not the classpath (the legal boundary analysis).</b> What is "placed on the market"
 * under arts. 3(9)/(10) of the AI Act is the artifact made available, not the tree that produced it.
 * {@link DefaultDistributionModelAdapterBoundaryTest} is a fast, sound pre-filter - for the classes
 * and service registrations it checks, the resolved compile+runtime classpath and the shaded jar are
 * class-for-class identical today (no {@code <relocations>}, no artifact excludes beyond signature
 * files in the shade execution) - but three real drift channels only an artifact-level check closes:
 * <ol>
 *   <li>a concrete adapter arriving as a dependency's own transitive dependency - already covered
 *   structurally by the pre-filter's classpath walk too, but confirmed again here against the actual
 *   jar contents rather than trusted by argument;
 *   <li>{@code maven-shade-plugin}'s {@code ServicesResourceTransformer} merging {@code
 *   META-INF/services} entries from dependencies into the one fat jar - the merge itself is not a
 *   detection gap for the pre-filter (it already scans every classpath jar's own service file before
 *   any merging happens), but the merged file inside the real jar is what P6 below reads directly;
 *   <li>{@code ravenroot-ui/dist} (build output, what is actually packaged) diverging from {@code
 *   ravenroot-ui/public} (source, what the pre-filter reads) at release time - this is the one the
 *   pre-filter genuinely cannot see. This class reads {@code ui/examples/**} straight out of the built
 *   jar/zip, wherever its content came from.
 * </ol>
 *
 * <p><b>Why {@code verify}, not {@code test}.</b> {@code ravenroot.jar} and {@code ravenroot-bin.zip}
 * are produced by {@code package}, so a class needing to open them cannot run at {@code test} (see
 * {@link DefaultDistributionModelAdapterBoundaryTest}'s Javadoc for why that check instead scans the
 * pre-package classpath). This class is a Failsafe integration test (name ends {@code IT}, default
 * {@code integration-test}/{@code verify} bindings, no custom phase configuration needed) - Maven
 * runs {@code package} before either of those phases, so both artifacts exist by the time this runs.
 *
 * <p><b>Which build this runs against, and the {@code akka}/profile boundary (the packaging-boundary analysis).</b>
 * This class inspects whatever {@code target/ravenroot.jar} and {@code target/ravenroot-bin.zip} the
 * invocation that ran it produced - it does not force a specific profile. A Maven profile does not
 * produce a separate artifact under separate coordinates; it changes the contents of the one artifact
 * this module always publishes as {@code ravenroot.jar}/{@code ravenroot-bin.zip}. So "default
 * distribution" is not fixed to "the no-profile build": it is whatever the build that actually
 * assembles the published artifact produces. Verified today: the {@code akka} profile adds no
 * ModelProvider/AgentRuntime reference (it swaps the actor runtime, {@code ravenroot-akka} has zero
 * hits for either symbol), and the default published-image build path never sets
 * {@code MAVEN_PROFILES}, so today's published image is the
 * no-profile build. A true separate optional-adapter artifact must get its own Maven coordinates,
 * excluded from the release reactor - never a profile of this module - because only that shape makes
 * "additional artifact, not part of the default distribution" true instead of merely stated.
 *
 * <p><b>This class alone is not sufficient - it is skippable.</b> {@code Dockerfile}'s {@code
 * java-build} stage runs {@code mvn -DskipTests clean package}, which never reaches this class's
 * {@code verify} phase - {@code -DskipTests} would suppress it even if it did. The property "the
 * default distribution artifact cannot be produced without this check having run and passed" is
 * therefore enforced by {@link ReleaseArtifactBoundaryGate}, not by this class: that gate reruns the
 * exact same predicates (via the shared {@link ReleaseArtifactBoundaryChecks} and {@link
 * BuiltArtifactIndices}) bound to the {@code package} phase itself through {@code exec-maven-plugin},
 * a mechanism {@code -DskipTests}/{@code skipTests} does not touch. This class still exists because a
 * gate that only prints to a build log and exits nonzero is a poor experience for a contributor
 * running the ordinary {@code mvn verify} - it gets none of Surefire/Failsafe's per-assertion
 * reporting. Keep both: the gate is the guarantee, this class is the diagnosis.
 */
class ReleaseArtifactModelAdapterBoundaryIT {

    private static final Path RAVENROOT_JAR = Path.of("target", "ravenroot.jar");
    private static final Path RAVENROOT_BIN_ZIP = Path.of("target", "ravenroot-bin.zip");
    private static final Path BIN_ZIP_EXTRACT_DIR = Path.of("target", "it-artifact-check", "bin");

    private static BuiltArtifactIndices artifacts;

    @BeforeAll
    static void buildIndicesFromBuiltArtifacts() throws IOException {
        artifacts = BuiltArtifactIndices.build(RAVENROOT_JAR, RAVENROOT_BIN_ZIP, BIN_ZIP_EXTRACT_DIR);
    }

    @Test
    void bothIndicesSeeTheDefaultDistributionDependencyGraph() {
        List<String> violations = new ArrayList<>();
        violations.addAll(ReleaseArtifactBoundaryChecks.sanityViolations(artifacts.jarIndex(), artifacts.jarLabel()));
        violations.addAll(ReleaseArtifactBoundaryChecks.sanityViolations(artifacts.binZipIndex(), artifacts.binZipLabel()));
        assertTrue(violations.isEmpty(), () -> "Anti-false-green guard failed: " + violations
                + ". If this fails, every other assertion in this class is vacuous.");
    }

    @Test
    void builtArtifactsContainNoConcreteModelProvider() {
        List<String> violations = new ArrayList<>();
        violations.addAll(ReleaseArtifactBoundaryChecks.concreteImplementationViolations(artifacts.jarIndex(),
                ReleaseArtifactBoundaryChecks.MODEL_PROVIDER_INTERNAL, ReleaseArtifactBoundaryChecks.MODEL_PROVIDER_FQCN, artifacts.jarLabel()));
        violations.addAll(ReleaseArtifactBoundaryChecks.concreteImplementationViolations(artifacts.binZipIndex(),
                ReleaseArtifactBoundaryChecks.MODEL_PROVIDER_INTERNAL, ReleaseArtifactBoundaryChecks.MODEL_PROVIDER_FQCN, artifacts.binZipLabel()));
        assertTrue(violations.isEmpty(), () -> String.join("; ", violations));
    }

    @Test
    void builtArtifactsContainNoConcreteAgentRuntime() {
        List<String> violations = new ArrayList<>();
        violations.addAll(ReleaseArtifactBoundaryChecks.concreteImplementationViolations(artifacts.jarIndex(),
                ReleaseArtifactBoundaryChecks.AGENT_RUNTIME_INTERNAL, ReleaseArtifactBoundaryChecks.AGENT_RUNTIME_FQCN, artifacts.jarLabel()));
        violations.addAll(ReleaseArtifactBoundaryChecks.concreteImplementationViolations(artifacts.binZipIndex(),
                ReleaseArtifactBoundaryChecks.AGENT_RUNTIME_INTERNAL, ReleaseArtifactBoundaryChecks.AGENT_RUNTIME_FQCN, artifacts.binZipLabel()));
        assertTrue(violations.isEmpty(), () -> String.join("; ", violations));
    }

    /** P3, against the artifact rather than the build-time classpath - see class Javadoc and {@link
     * DefaultDistributionModelAdapterBoundaryTest#defaultDistributionCompositionRootNeverRegistersAnAdapter}
     * for the underlying reasoning, unchanged here. */
    @Test
    void builtArtifactsCompositionRootNeverRegistersAnAdapter() {
        List<String> violations = new ArrayList<>();
        violations.addAll(ReleaseArtifactBoundaryChecks.registrationReachabilityViolations(artifacts.jarIndex(), artifacts.jarLabel()));
        violations.addAll(ReleaseArtifactBoundaryChecks.registrationReachabilityViolations(artifacts.binZipIndex(), artifacts.binZipLabel()));
        assertTrue(violations.isEmpty(), () -> String.join("; ", violations));
    }

    @Test
    void builtArtifactsRegisterNoModelAdapterServiceProvider() {
        List<String> violations = new ArrayList<>();
        violations.addAll(ReleaseArtifactBoundaryChecks.serviceRegistrationViolations(artifacts.jarIndex(), artifacts.jarLabel()));
        violations.addAll(ReleaseArtifactBoundaryChecks.serviceRegistrationViolations(artifacts.binZipIndex(), artifacts.binZipLabel()));
        assertTrue(violations.isEmpty(), () -> String.join("; ", violations));
    }

    /** Anti-vacuity note (the regression analysis, anti-vacuity regression): before this, a build that never packaged
     * {@code ravenroot-ui/dist} - e.g. CI's {@code java-test} job, see {@link
     * DefaultDistributionModelAdapterBoundaryTest}'s Javadoc - inspected zero {@code
     * ui/examples/**} resources and passed silently, indistinguishable from a run that inspected real
     * resources and found them clean. That is sound (nothing packaged cannot violate P4) but was not
     * reported, so a broken scan and a legitimately empty one looked identical. This test still never
     * fails solely because nothing was packaged - forcing that would break the exact CI job that
     * deliberately does not build the UI first - but it now prints an unmissable, always-present
     * report of how many resources it actually looked at, landing in this test's captured output
     * (and therefore the Surefire/Failsafe XML report) every run, not only when something is wrong. */
    @Test
    void builtArtifactsShipNoAiInvokingGraphResource() throws IOException {
        List<Path> jarsToScan = new ArrayList<>();
        jarsToScan.add(RAVENROOT_JAR);
        jarsToScan.addAll(artifacts.binZipLibJars());
        var scan = ReleaseArtifactBoundaryChecks.combinedGraphResourceScan(jarsToScan);

        System.out.println("P4 inspected " + scan.entriesScanned() + " ui/examples/** "
                + "resource(s) across " + jarsToScan.size() + " jar(s) (" + RAVENROOT_JAR + " and "
                + artifacts.binZipLibJars().size() + " bin.zip lib jar(s)).");
        if (scan.entriesScanned() == 0) {
            System.out.println("=".repeat(79));
            System.out.println("P4 INSPECTED ZERO RESOURCES THIS RUN. This is sound only if "
                    + "ravenroot-ui/dist was genuinely never packaged into this build (expected for CI's "
                    + "java-test job); it gives P4 no assurance for this run either way. Confirm "
                    + "separately that a run which DID package ravenroot-ui/dist also passed this "
                    + "assertion before relying on it.");
            System.out.println("=".repeat(79));
        }

        assertTrue(scan.violations().isEmpty(), () -> "Release condition violated (EU AI Act art. "
                + "50): a ui/examples graph resource inside the built artifact declares an AI-invoking "
                + "node, or a previously schema-incompatible JSON resource now looks like one: "
                + scan.violations() + ". Unlike DefaultDistributionModelAdapterBoundaryTest's equivalent "
                + "check, this reads the artifact directly, so it does not matter whether ravenroot-ui/dist "
                + "diverged from ravenroot-ui/public before this artifact was built.");
    }

    /**
     * P7: the predicate that survives the AI nodes leaving the core.
     *
     * <p>Every assertion above keys on the {@code ModelProvider}/{@code AgentRuntime} SPI, directly
     * (P1/P2/P6) or through a call graph rooted in a {@code main} (P3). A plugin bundle whose node
     * reaches a model endpoint with its own HTTP client satisfies none of that and is loaded
     * reflectively, so it would pass every one of them. This one reads what the bundle declares about
     * its node types instead, and is therefore indifferent both to the SPI and to how the class is
     * wired - see {@code ReleaseArtifactBoundaryChecks#shippedPluginBundleScan} for what "shipped"
     * means here and why it is a directory rather than the jar.</p>
     *
     * <p>Reports its inspected count on every run, for the reason the P4 test above does.</p>
     */
    @Test
    void shippedPluginBundlesDeclareNoGenerativeCapability() {
        var scan = ReleaseArtifactBoundaryChecks.shippedPluginBundleScan();
        System.out.println(scan.report());
        assertTrue(scan.violations().isEmpty(), () -> String.join("; ", scan.violations()));
    }

    /** P8: the jar/zip variant, inert under today's assembly - see {@code
     * ReleaseArtifactBoundaryChecks#artifactEmbeddedBundleViolations} for why it is kept anyway. */
    @Test
    void builtArtifactsEmbedNoPluginBundle() throws IOException {
        List<String> violations = ReleaseArtifactBoundaryChecks.artifactEmbeddedBundleViolations(
                RAVENROOT_JAR, BIN_ZIP_EXTRACT_DIR);
        assertTrue(violations.isEmpty(), () -> String.join("; ", violations));
    }

    /**
     * P9: the {@code AssistantProvider} inventory. <b>Asserts nothing about which
     * implementations are present</b>; the assistant path includes {@code AnthropicAssistantProvider},
     * armed by {@code
     * RAVENROOT_ASSISTANT_PROVIDER=anthropic} alone. What was wrong was that the gate never named
     * {@code AssistantProvider}, so nobody reading it could see that surface at all.
     *
     * <p>The only thing that fails here is an inventory that could not have been taken, which {@link
     * #bothIndicesSeeTheDefaultDistributionDependencyGraph} already covers through {@code
     * sanityViolations}; this test re-states it locally so a reader of this one assertion is not
     * required to trust another to know the list is real. The list lands in the Failsafe report every
     * run, which is what makes it verifiable in CI rather than only on a developer's terminal.</p>
     */
    @Test
    void builtArtifactsAssistantProviderInventoryIsReported() {
        List<String> jarInventory = ReleaseArtifactBoundaryChecks.assistantProviderInventory(artifacts.jarIndex());
        List<String> binZipInventory = ReleaseArtifactBoundaryChecks.assistantProviderInventory(artifacts.binZipIndex());

        System.out.println("P9 AssistantProvider implementations in " + artifacts.jarLabel()
                + ": " + jarInventory);
        System.out.println("P9 AssistantProvider implementations in " + artifacts.binZipLabel()
                + ": " + binZipInventory);

        assertTrue(!jarInventory.isEmpty() && !binZipInventory.isEmpty(),
                () -> "The AssistantProvider inventory is empty for at least one artifact ("
                        + artifacts.jarLabel() + ": " + jarInventory + "; " + artifacts.binZipLabel() + ": "
                        + binZipInventory + "). This artifact ships at least one implementation, so an "
                        + "empty inventory means this scan is not seeing ravenroot-server's classes - a "
                        + "report that reads exactly like a clean one while being vacuous.");
        assertTrue(jarInventory.stream().anyMatch(name -> name.contains("OpenAiCompatibleAssistantProvider"))
                        && binZipInventory.stream().anyMatch(
                                name -> name.contains("OpenAiCompatibleAssistantProvider")),
                () -> "The OpenAI-compatible Assistant provider must be visible in both P9 "
                        + "inventories (jar=" + jarInventory + "; binary ZIP=" + binZipInventory + ")");
    }
}
