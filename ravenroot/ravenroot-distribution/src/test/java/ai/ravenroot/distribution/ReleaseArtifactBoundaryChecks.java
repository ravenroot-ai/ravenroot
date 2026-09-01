package ai.ravenroot.distribution;

import ai.ravenroot.distribution.ClassGraphIndex.MethodRef;
import ai.ravenroot.plugin.bundle.GenerativeCapabilityScan;
import ai.ravenroot.plugin.bundle.PluginManifest;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The actual release-artifact violation predicates (P1, P2, P3, P4, P6), as pure functions returning
 * violation descriptions rather than JUnit assertions. Shared by {@link
 * ReleaseArtifactModelAdapterBoundaryIT} (the reported form, run at {@code verify}) and {@link
 * ReleaseArtifactBoundaryGate} (the non-skippable form, run at {@code package} - see that class's
 * Javadoc for why a second caller exists at all) so the release condition is defined exactly once.
 */
final class ReleaseArtifactBoundaryChecks {

    static final String MODEL_PROVIDER_FQCN = "ai.ravenroot.api.ai.ModelProvider";
    static final String AGENT_RUNTIME_FQCN = "ai.ravenroot.api.ai.AgentRuntime";
    static final String MODEL_PROVIDER_INTERNAL = "ai/ravenroot/api/ai/ModelProvider";
    static final String AGENT_RUNTIME_INTERNAL = "ai/ravenroot/api/ai/AgentRuntime";
    static final Set<String> WATCHED_SPI = Set.of(MODEL_PROVIDER_FQCN, AGENT_RUNTIME_FQCN);
    /**
     * The behavior names P4 refuses inside a shipped {@code ui/examples/**} graph resource.
     *
     * <p><b>Kept deliberately, and not because it is harmless to keep.</b> The two node
     * types left the core catalog, so a shipped example naming one would now degrade to the
     * unknown-behavior pass-through rather than reach a model — which is precisely the argument for
     * keeping this: P4 refuses the <em>document</em>, and a released artefact shipping a graph that
     * says {@code llm-prompt} is a released artefact telling an operator that this product runs LLM
     * prompts. That claim is what the release condition forbids in the artefact, independently of
     * whether the node would execute. The names are also the ones a bundle will reuse, so this
     * set stops a bundle's example from arriving in the jar by the back door.</p>
     *
     * <p>Re-derive rather than extend by reflex: this is a list of <em>names</em> and the product's
     * real handle on generativity is the descriptor capability (the generative-provenance contract), which P7 and
     * P8 read out of bundle manifests. This predicate covers the one surface a capability cannot be
     * read from — a GraphML document, which carries node names and no descriptors.</p>
     */
    static final Set<String> AI_INVOKING_NODE_BEHAVIORS = Set.of("llm-prompt", "agent");

    /** P9's subject: inventoried and reported, never refused - see {@link
     * #assistantProviderInventory}. */
    static final String ASSISTANT_PROVIDER_FQCN = "ai.ravenroot.server.assistant.provider.AssistantProvider";
    static final String ASSISTANT_PROVIDER_INTERNAL = "ai/ravenroot/server/assistant/provider/AssistantProvider";

    /**
     * Overrides {@link #shippedPluginBundleDirectories}, {@link File#pathSeparator}-separated, so P7
     * can be pointed at a fixture tree instead of the real convention directories.
     *
     * <p>Exercised by {@link ShippedPluginBundleScanTest}, which is the only caller that sets it and
     * the reason it exists — including a case asserting that it <em>replaces</em> the convention
     * directories rather than adding to them. Stated here because an escape hatch whose declared
     * purpose no test performs is indistinguishable from dead code; the test exercises it directly.</p>
     */
    static final String SHIPPED_PLUGIN_DIRS_PROPERTY = "ravenroot.shippedPluginDirs";

    /** Bundle directories relative to the repository root that hold bundles staged for official
     * publication. The operator-owned {@code ravenroot-plugins/} directory is deliberately absent:
     * it feeds custom images, not an image published by Ravenroot. */
    private static final List<String> SHIPPED_PLUGIN_DIRS_RELATIVE_TO_ROOT =
            List.of("ci-artifacts/backend/plugins");

    private static final String BUNDLE_MANIFEST_FILE_NAME = "ravenroot-plugin.json";

    private static final MethodRef SERVER_ENTRY =
            new MethodRef("ai/ravenroot/server/RavenrootServerMain", "main", "([Ljava/lang/String;)V");
    private static final MethodRef CLI_ENTRY =
            new MethodRef("ai/ravenroot/cli/RavenrootCliMain", "main", "([Ljava/lang/String;)V");
    private static final Map<String, String> REGISTER_TARGETS = Map.of(
            "ai/ravenroot/core/ai/ModelProviderRegistry", "register",
            "ai/ravenroot/core/ai/AgentRuntimeRegistry", "register");

    private ReleaseArtifactBoundaryChecks() {
    }

    /** Anti-false-green guard: if this returns anything, every other check against {@code index} is
     * vacuous - the index is not seeing the artifact's real contents. */
    static List<String> sanityViolations(ClassGraphIndex index, String artifactLabel) {
        List<String> violations = new ArrayList<>();
        if (!index.containsClass(MODEL_PROVIDER_INTERNAL)) {
            violations.add(MODEL_PROVIDER_FQCN + " was not found inside " + artifactLabel
                    + ". The scanning mechanism is not seeing this artifact's real contents.");
        }
        if (!index.containsClass(AGENT_RUNTIME_INTERNAL)) {
            violations.add(AGENT_RUNTIME_FQCN + " was not found inside " + artifactLabel
                    + ". Same failure mode as above.");
        }
        if (!index.hasCallGraphDataFor("ai/ravenroot/server/RavenrootServerMain")) {
            violations.add("No call-graph data was recorded for RavenrootServerMain inside " + artifactLabel
                    + "; the P3 check for this artifact would be vacuous.");
        }
        // The AssistantProvider inventory (P9) is a report, not a refusal - but a report
        // that finds nothing is worthless unless "nothing to find" can be told apart from "not
        // looking". Both halves are asserted: the interface itself must be in the artifact, and at
        // least one concrete implementation must be, because this artifact ships three
        // (Scripted, Anthropic and OpenAI-compatible). Zero would mean the scan is
        // not reaching ravenroot-server's classes at all, which would make P9 an empty list that
        // reads exactly like a clean one.
        if (!index.containsClass(ASSISTANT_PROVIDER_INTERNAL)) {
            violations.add(ASSISTANT_PROVIDER_FQCN + " was not found inside " + artifactLabel
                    + ". The scanning mechanism is not seeing this artifact's real contents, so the "
                    + "AssistantProvider inventory would be vacuous.");
        } else if (index.concreteImplementationsOf(ASSISTANT_PROVIDER_INTERNAL).isEmpty()) {
            violations.add("No concrete " + ASSISTANT_PROVIDER_FQCN + " implementation was found inside "
                    + artifactLabel + ", but this artifact is known to ship at least one. An empty "
                    + "inventory here means the scan is not seeing ravenroot-server's "
                    + "classes, not that the artifact stopped shipping an assistant provider.");
        }
        return violations;
    }

    /**
     * P9: the concrete {@code AssistantProvider} implementations present in the
     * artifact. <b>Not a violation list.</b>
     *
     * <p>The artifact boundary contract keeps the dedicated Assistant providers inside
     * {@code ravenroot-server}; {@code OpenAiCompatibleAssistantProvider} belongs to that same
     * production plane without arming the separately governed executable-node registry. This remains
     * inventory rather than refusal: a future change that means to forbid one belongs in a predicate
     * that says so.</p>
     *
     * @return the implementations found, sorted by the index's own iteration; empty only in a
     *         situation {@link #sanityViolations} already fails on
     */
    static List<String> assistantProviderInventory(ClassGraphIndex index) {
        List<String> found = new ArrayList<>(index.concreteImplementationsOf(ASSISTANT_PROVIDER_INTERNAL));
        found.sort(String::compareTo);
        return List.copyOf(found);
    }

    /** P1/P2: a concrete class in {@code artifactLabel} whose ancestry reaches {@code targetInternal}. */
    static List<String> concreteImplementationViolations(ClassGraphIndex index, String targetInternal,
                                                           String targetFqcn, String artifactLabel) {
        List<String> implementations = index.concreteImplementationsOf(targetInternal);
        if (implementations.isEmpty()) {
            return List.of();
        }
        return List.of("Release condition violated (EU AI Act art. 50): the built artifact "
                + artifactLabel + " contains a concrete " + targetFqcn + " implementation: " + implementations
                + ". This is the object the AI Act qualification is actually assessed on (the legal analysis, "
                + "the legal boundary) - a source-tree check passing here is not sufficient. Move this class to an "
                + "optional, separately-installed adapter artifact with its own Maven coordinates, "
                + "excluded from the release reactor.");
    }

    /**
     * P3: a reachable call to ModelProviderRegistry#register or AgentRuntimeRegistry#register from
     * either composition root (RavenrootServerMain#main, RavenrootCliMain#main) inside {@code
     * artifactLabel}.
     *
     * <h2>What this predicate is for (the release-artifact boundary)</h2>
     * <p>It used to call itself "the determinative condition", and its failure message said the call
     * is "the act that actually arms an llm-prompt/agent node in every graph built after it". Both
     * were true while the artefact shipped such a node. It now ships none, so the sentence
     * described nothing and has been rewritten.</p>
     *
     * <p><b>P3 keeps its teeth and loses only a false explanation.</b> A reachable {@code register(...)}
     * is still forbidden, and the reason is now the one that does not depend on the catalogue: the
     * released artefact must not compose a model adapter into itself. The embedding seam is supplied
     * from outside the artefact -- by an application that embeds Ravenroot and composes its own
     * {@code BehaviorEnvironment} -- or it is not supplied at all. See
     * DefaultDistributionModelAdapterBoundaryTest for why a concrete class alone does not arm
     * anything.</p>
     *
     * <p>The "determinative condition" title now belongs to {@link #shippedPluginBundleScan} (P7),
     * and this is not a demotion: the risk moved from "a concrete {@code ModelProvider} in the jar" to
     * "a bundle with a generative capability in the image", which is the route the product's own
     * design now takes.</p>
     */
    static List<String> registrationReachabilityViolations(ClassGraphIndex index, String artifactLabel) {
        List<String> violations = new ArrayList<>();
        for (MethodRef entry : List.of(SERVER_ENTRY, CLI_ENTRY)) {
            index.registerCallPathFrom(entry, REGISTER_TARGETS).ifPresent(path ->
                    violations.add("Release condition violated (EU AI Act art. 50): in "
                            + artifactLabel + ", a call to ModelProviderRegistry#register or "
                            + "AgentRuntimeRegistry#register is reachable from composition root "
                            + entry.display() + ": " + String.join(" -> ", path) + ". The released "
                            + "artifact must not compose a model adapter into itself: the embedding "
                            + "seam is supplied from outside the artifact - by an application that "
                            + "embeds Ravenroot and composes its own BehaviorEnvironment - or it is "
                            + "not supplied at all. (Previously this message said the call arms an "
                            + "llm-prompt/agent node in every graph built after it. That node type is "
                            + "no longer in the artifact, so the sentence described nothing; the "
                            + "prohibition is unchanged and its reason is stated above.)"));
        }
        return violations;
    }

    /** P6, a prospective sentinel (the release-boundary analysis): neither registry is ServiceLoader-based
     * today (both use explicit register(...) composition, see P3 above), so this is inert until that
     * changes - a tripwire for the day it does, not the primary guarantee. */
    static List<String> serviceRegistrationViolations(ClassGraphIndex index, String artifactLabel) {
        List<String> violations = new ArrayList<>();
        for (String spi : WATCHED_SPI) {
            var registrations = index.serviceRegistrationsFor(spi);
            if (!registrations.isEmpty()) {
                violations.add("Prospective sentinel tripped (EU AI Act art. 50): "
                        + artifactLabel + " contains a META-INF/services/" + spi + " registration: "
                        + registrations + ". Inert under today's explicit register(...) composition, but "
                        + "not if either registry has since become ServiceLoader-based - "
                        + "maven-shade-plugin's ServicesResourceTransformer merges exactly this kind of "
                        + "registration in from any dependency.");
            }
        }
        return violations;
    }

    /** Result of scanning one jar for {@code ui/examples/**} resources: the violations found, and how
     * many resources were actually looked at - see {@link #combinedGraphResourceScan}, which exists
     * because "found nothing wrong" and "found nothing to look at" must never be allowed to look the
     * same (the regression analysis, anti-vacuity regression: P4 was vacuous, indistinguishable from sound, in any build that
     * had not packaged {@code ravenroot-ui/dist}). */
    record GraphResourceScan(List<String> violations, int entriesScanned) {
    }

    /** P4: a {@code ui/examples/**} resource inside {@code jarPath} declaring an AI-invoking node, or
     * a previously schema-incompatible JSON resource that no longer is one. Reads the artifact
     * directly, so it does not matter whether ravenroot-ui/dist diverged from ravenroot-ui/public
     * before this artifact was built (the gap DefaultDistributionModelAdapterBoundaryTest cannot
     * close on its own). */
    static GraphResourceScan graphResourceScan(Path jarPath) throws IOException {
        List<String> violations = new ArrayList<>();
        int entriesScanned = 0;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().contains("ui/examples/")) {
                    continue;
                }
                String label = jarPath + "!/" + entry.getName();
                if (entry.getName().endsWith(".graphml")) {
                    entriesScanned++;
                    try (InputStream input = jar.getInputStream(entry)) {
                        violations.addAll(GraphResourceInspector.aiInvokingNodeBehaviors(
                                input, label, AI_INVOKING_NODE_BEHAVIORS));
                    }
                } else if (entry.getName().endsWith(".json")) {
                    entriesScanned++;
                    try (InputStream input = jar.getInputStream(entry)) {
                        violations.addAll(GraphResourceInspector.suspiciousJsonKeys(input, label));
                    }
                }
            }
        }
        return new GraphResourceScan(violations, entriesScanned);
    }

    /** Sums {@link #graphResourceScan} over every jar that makes up one artifact (the shaded jar
     * itself, or each {@code lib/*.jar} for the bin.zip channel). An {@code entriesScanned() == 0}
     * result means this artifact shipped no {@code ui/examples/**} resource at all - sound (nothing
     * packaged, nothing to violate P4), but callers must report that count explicitly rather than
     * let a silent pass stand in for it; see {@link ReleaseArtifactBoundaryGate} and {@link
     * ReleaseArtifactModelAdapterBoundaryIT} for how each surfaces it. */
    static GraphResourceScan combinedGraphResourceScan(List<Path> jarsForGraphScan) throws IOException {
        List<String> violations = new ArrayList<>();
        int entriesScanned = 0;
        for (Path jarPath : jarsForGraphScan) {
            GraphResourceScan scan = graphResourceScan(jarPath);
            violations.addAll(scan.violations());
            entriesScanned += scan.entriesScanned();
        }
        return new GraphResourceScan(violations, entriesScanned);
    }

    /**
     * P7's outcome, including everything needed to tell an empty result apart from an
     * absent one.
     *
     * @param violations       what must fail the build
     * @param bundlesInspected how many bundle candidates were actually opened
     * @param scan             the underlying shared scan, for its own directory-level report
     * @param repositoryRoot   the located repository root, or empty when it could not be located
     */
    record ShippedBundleScan(List<String> violations, int bundlesInspected,
                             GenerativeCapabilityScan.Result scan, Optional<Path> repositoryRoot) {

        /** Always printed by both callers, green or red - the P4 {@code entriesScanned} posture. */
        String report() {
            if (repositoryRoot.isEmpty()) {
                return "P7 located NO repository root from " + Path.of("").toAbsolutePath()
                        + " and no " + SHIPPED_PLUGIN_DIRS_PROPERTY + " override, so it scanned ZERO "
                        + "shipped plugin bundle directories. This is the expected case inside a build "
                        + "context that contains only ravenroot/ (Dockerfile's java-build stage, where "
                        + "bundle staging happens after this phase through PluginBundleBuildCopy); it "
                        + "gives P7 no assurance for this run either way.";
            }
            return "P7 rooted at " + repositoryRoot.get() + ": " + scan.report();
        }
    }

    /**
     * P7: a plugin bundle among the ones this project ships that declares a generative
     * node capability, or that this check cannot conclude about.
     *
     * <h2>This is the determinative condition (the release-artifact boundary)</h2>
     * <p>P7 is determinative because the AI nodes use the bundle route. P3 guards a seam the shipped
     * artefact no longer uses and must still never use; this predicate
     * guards the route the product's own design takes, so it is the one a reader looking for "the
     * control that enforces the release condition" should find first. Both are kept and neither is
     * weakened - they guard two different ways in.</p>
     *
     * <h2>Why this looks at a directory and not at the jar</h2>
     * <p>This condition concerns "a bundle present in the release artifacts", which is today
     * structurally impossible and worth being exact about rather than writing a predicate that can
     * never fire. {@code src/assembly/ravenroot.xml} packages only {@code dependencySets} and {@code
     * src/main/distribution}, so no bundle can reach {@code ravenroot.jar} or {@code
     * ravenroot-bin.zip} at all. Shipped bundles live in directories instead: {@code
     * ci-artifacts/backend/plugins/}, which is what {@code PUBLISHED_PLUGINS} stages and {@code
     * Dockerfile.ci} copies into the official published image. That is what this scans. The local
     * {@code ravenroot-plugins/} directory instead feeds {@code Dockerfile}'s operator-built custom
     * image and is deliberately outside this publication boundary. The jar/zip variant is still
     * checked by {@link #artifactEmbeddedBundleViolations} - it cannot
     * fire today and would the day a plugin module became a Maven dependency of this one.</p>
     *
     * <h2>Absence of the directory is sound; inability to conclude is not</h2>
     * <p>A directory that does not exist ships no bundle, and is reported rather than treated as an
     * error - the same reasoning P4 applies to a build that packaged no {@code ui/examples/**}
     * resource. A directory that exists but cannot be listed, and a bundle whose manifest cannot be
     * read or does not declare {@link PluginManifest#NODE_CAPABILITIES_KEY}, are the opposite case:
     * this check did not conclude, so it fails.</p>
     */
    static ShippedBundleScan shippedPluginBundleScan() {
        return shippedPluginBundleScan(locateRepositoryRoot());
    }

    /** Test seam for the publication boundary's repository-root conventions. */
    static ShippedBundleScan shippedPluginBundleScan(Optional<Path> repositoryRoot) {
        List<Path> directories = shippedPluginBundleDirectories(repositoryRoot);
        GenerativeCapabilityScan.Result scan = GenerativeCapabilityScan.scanDirectories(directories);
        return new ShippedBundleScan(scan.violations(), scan.bundlesInspected(), scan, repositoryRoot);
    }

    /** The directories P7 scans: the {@link #SHIPPED_PLUGIN_DIRS_PROPERTY} override when set,
     * otherwise the convention directories under {@code repositoryRoot}, or none when it is empty. */
    static List<Path> shippedPluginBundleDirectories(Optional<Path> repositoryRoot) {
        String override = System.getProperty(SHIPPED_PLUGIN_DIRS_PROPERTY);
        if (override != null && !override.isBlank()) {
            List<Path> explicit = new ArrayList<>();
            for (String entry : override.split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    explicit.add(Path.of(entry.trim()));
                }
            }
            return List.copyOf(explicit);
        }
        return repositoryRoot
                .map(root -> SHIPPED_PLUGIN_DIRS_RELATIVE_TO_ROOT.stream().map(root::resolve).toList())
                .orElseGet(List::of);
    }

    /**
     * Walks up from the working directory looking for the repository root.
     *
     * <p>Identified by {@code plugin.sh} beside {@code ravenroot/pom.xml} - two files that only
     * coexist at the root - rather than by a fixed number of {@code ..} hops from this module, which
     * would break silently the day the module moved. Returns empty rather than throwing when the root
     * is not there: inside {@code Dockerfile}'s {@code java-build} stage the build context is only
     * {@code ravenroot/}, so there is genuinely no root to find, and failing there would break every
     * image build for a directory that could not have held a shipped bundle anyway. The empty case is
     * reported loudly instead - see {@link ShippedBundleScan#report()}.
     */
    static Optional<Path> locateRepositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("plugin.sh"))
                    && Files.isRegularFile(candidate.resolve("ravenroot").resolve("pom.xml"))) {
                return Optional.of(candidate);
            }
            candidate = candidate.getParent();
        }
        return Optional.empty();
    }

    /**
     * P8: a plugin bundle manifest embedded in a released artifact.
     *
     * <p>This variant cannot fire today - {@code
     * src/assembly/ravenroot.xml} gives no bundle a way in - and it would the day a plugin module
     * became a Maven dependency of {@code ravenroot-distribution} and carried its own manifest as a
     * resource, or the day someone added a bundle directory under {@code src/main/distribution}. Kept
     * as a tripwire in the same spirit as P6, which is equally inert under today's composition.</p>
     *
     * <p><b>Its limit, stated rather than implied:</b> it detects a bundle by its {@code
     * ravenroot-plugin.json}. A plugin module pulled in as a plain Maven dependency contributes its
     * <em>classes</em> to the shaded jar and need not carry a manifest with them; that shape is not
     * caught here. P7 remains the predicate with real coverage today, and this one exists so the
     * artifact channel is not simply unwatched.</p>
     *
     * @param jarPath           the shaded jar, scanned entry by entry
     * @param binZipExtractRoot where {@link BuiltArtifactIndices} already extracted the bin zip
     */
    static List<String> artifactEmbeddedBundleViolations(Path jarPath, Path binZipExtractRoot) throws IOException {
        List<String> violations = new ArrayList<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                // Basename equality, not endsWith: "some-ravenroot-plugin.json" is a different file
                // and reading it as a manifest would manufacture an indeterminate-result violation
                // out of an unrelated resource.
                if (entry.isDirectory() || !isBundleManifestEntry(entry.getName())) {
                    continue;
                }
                String label = jarPath + "!/" + entry.getName();
                try (InputStream input = jar.getInputStream(entry)) {
                    violations.addAll(GenerativeCapabilityScan.inspectManifest(
                            input.readAllBytes(), label, "(embedded in " + jarPath + ")"));
                }
            }
        }
        if (Files.isDirectory(binZipExtractRoot)) {
            try (var walk = Files.walk(binZipExtractRoot)) {
                for (Path manifest : walk.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().equals(BUNDLE_MANIFEST_FILE_NAME))
                        .toList()) {
                    violations.addAll(GenerativeCapabilityScan.inspectManifest(
                            Files.readAllBytes(manifest), manifest.toString(),
                            "(embedded in the bin zip)"));
                }
            }
        }
        return violations;
    }

    private static boolean isBundleManifestEntry(String entryName) {
        return entryName.equals(BUNDLE_MANIFEST_FILE_NAME)
                || entryName.endsWith("/" + BUNDLE_MANIFEST_FILE_NAME);
    }

    /** Every P1/P2/P3/P6 predicate against one artifact index, plus P4 against every jar that makes
     * up that artifact. Does not include {@link #sanityViolations} - callers that want the
     * anti-false-green guard call it separately, since a gate and a reported test may want to treat
     * it differently (the gate must still fail loud; the IT reports it as its own named assertion).
     * Does not report the P4 {@code entriesScanned} count either, for the same reason - see {@link
     * #combinedGraphResourceScan}; callers that need the anti-vacuity report call it directly. */
    static List<String> allViolations(ClassGraphIndex index, String artifactLabel, List<Path> jarsForGraphScan)
            throws IOException {
        List<String> violations = new ArrayList<>();
        violations.addAll(concreteImplementationViolations(index, MODEL_PROVIDER_INTERNAL, MODEL_PROVIDER_FQCN, artifactLabel));
        violations.addAll(concreteImplementationViolations(index, AGENT_RUNTIME_INTERNAL, AGENT_RUNTIME_FQCN, artifactLabel));
        violations.addAll(registrationReachabilityViolations(index, artifactLabel));
        violations.addAll(serviceRegistrationViolations(index, artifactLabel));
        violations.addAll(combinedGraphResourceScan(jarsForGraphScan).violations());
        return violations;
    }
}
