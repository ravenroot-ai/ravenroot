package ai.ravenroot.distribution;

import ai.ravenroot.distribution.ClassGraphIndex.MethodRef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast PRE-FILTER for EU AI Act art. 50 role determination (see ADR 0017 in
 * {@code docs/architecture/decisions/0017-article-50-product-decisions.md} and the legal boundary
 * predicate brief). Ravenroot's public-release qualification as an
 * upstream component under recital 89 - rather than a provider of an AI system under art. 3(3),
 * which would apply with no transitional grace period and up to the art. 99(4)(g) ceiling
 * (EUR 15,000,000 or 3% global turnover) - rests on the DEFAULT distribution shipping no concrete,
 * registered model or agent adapter. This class is one of two verifiable forms of that boundary; a
 * prose assertion decays silently, these fail the build.
 *
 * <p><b>This is the pre-filter, not the legally authoritative check - {@link
 * ReleaseArtifactModelAdapterBoundaryIT} is.</b> What is "placed on the market" under arts. 3(9)/(10)
 * of the AI Act is the assembled artifact, not the source tree or the resolved classpath that builds
 * it (the legal boundary analysis). A source/classpath-only check can go silently green on a tree whose
 * shipped artifact would already violate the property, in at least three ways: (1) an adapter
 * arriving only as a transitive dependency's own dependency; (2) {@code maven-shade-plugin}'s {@code
 * ServicesResourceTransformer} merging {@code META-INF/services} entries from dependencies into the
 * fat jar; (3) {@code ravenroot-ui/dist} (npm build output, what actually gets packaged) diverging
 * from {@code ravenroot-ui/public} (source) at release time. This class runs early and cheaply and
 * catches most violations well before {@code package}; {@link ReleaseArtifactModelAdapterBoundaryIT}
 * inspects the actual built {@code ravenroot.jar} and {@code ravenroot-bin.zip} afterwards and is
 * the check whose pass is the actual release condition. Keep both: the legal analysis permits the pre-filter in
 * addition to the artifact check, never as a substitute for it.
 *
 * <p><b>Scope: whatever build actually produces the artifact.</b> {@code ravenroot-distribution}'s
 * {@code akka} Maven profile only changes the <em>contents</em> of the one artifact
 * {@code ravenroot.jar}/{@code ravenroot-bin.zip} always publishes under the same coordinates - a
 * Maven profile does not produce a separate artifact (the packaging-boundary analysis). So "default distribution"
 * means whatever the build that actually assembles the published artifact produces, under whatever
 * profiles that build activates - not "the no-profile build" as a fixed target. This check and {@link
 * ReleaseArtifactModelAdapterBoundaryIT} are both profile-invariant by construction: each inspects
 * the classpath/artifact of whatever invocation runs it, so `-Pakka` (confirmed to add no
 * ModelProvider/AgentRuntime reference - it is an alternate actor runtime) or any future profile is
 * covered automatically, without enumerating profiles here. What they cannot see is a release
 * pipeline that never invokes them at all (e.g. {@code mvn -DskipTests package}, which is what
 * {@code Dockerfile}'s {@code java-build} stage runs) - see {@link ReleaseArtifactModelAdapterBoundaryIT}
 * for why that gap is closed elsewhere, not here. A true separate optional-adapter artifact needs its
 * own Maven coordinates excluded from the release reactor, never a profile of this module.
 *
 * <p>See {@link ClassGraphIndex} for the scan/call-graph mechanism and
 * {@link GraphResourceInspector} for the graph-resource reader shared with the artifact IT.
 *
 * <p><b>Declined: an SDK-dependency denylist (the dependency-denylist proposal, marked [RECOMMENDATION], not legally
 * determinative).</b> A transitively-arriving adapter that actually implements {@code ModelProvider}
 * is already caught structurally by P1/P2 below, direct or transitive makes no difference to a
 * classpath/artifact scan. What P5 would add on top is flagging a vendor SDK dependency that is
 * present but never wired to anything - a weaker, name-based signal that sits awkwardly against this
 * check's own principle of deriving a verdict from the artifact rather than a hardcoded list (a new
 * vendor is invisible to a denylist; a present-but-dead dependency is not a qualification-relevant
 * fact on its own). Not implemented; revisit if a concrete non-brittle formulation turns up.
 */
class DefaultDistributionModelAdapterBoundaryTest {

    private static final String MODEL_PROVIDER_FQCN = "ai.ravenroot.api.ai.ModelProvider";
    private static final String AGENT_RUNTIME_FQCN = "ai.ravenroot.api.ai.AgentRuntime";
    private static final String MODEL_PROVIDER_INTERNAL = "ai/ravenroot/api/ai/ModelProvider";
    private static final String AGENT_RUNTIME_INTERNAL = "ai/ravenroot/api/ai/AgentRuntime";
    private static final Set<String> WATCHED_SPI = Set.of(MODEL_PROVIDER_FQCN, AGENT_RUNTIME_FQCN);

    private static final String MODEL_PROVIDER_REGISTRY_INTERNAL = "ai/ravenroot/core/ai/ModelProviderRegistry";
    private static final String AGENT_RUNTIME_REGISTRY_INTERNAL = "ai/ravenroot/core/ai/AgentRuntimeRegistry";
    private static final String REGISTER_METHOD_NAME = "register";

    /** Node behaviors whose presence in a shipped graph resource would constitute a preconfigured
     * generative/agentic system - the case-(a) trigger ADR 0017 excludes. Adding a
     * future third AI-invoking behavior only requires adding its id here. */
    private static final Set<String> AI_INVOKING_NODE_BEHAVIORS = Set.of("llm-prompt", "agent");

    @Test
    void classpathScannerSeesTheDefaultDistributionDependencyGraph() throws IOException {
        var index = ClassGraphIndex.scanJavaClassPath(WATCHED_SPI);
        // Anti-false-green guard: if either assertion below fails, every other test in this class is
        // vacuous - the scan is not seeing what it thinks it is seeing (a stale java.class.path, a
        // broken path split, a moved package, ...), which is exactly the failure mode that lets a
        // check pass before AND after the property it claims to guard is broken.
        assertTrue(index.containsClass(MODEL_PROVIDER_INTERNAL),
                "Sanity check failed: " + MODEL_PROVIDER_FQCN + " itself was not found on the scanned "
                        + "classpath (java.class.path). This boundary check's classpath scan is not "
                        + "seeing the default distribution's dependency graph, which makes every other "
                        + "assertion in " + getClass().getSimpleName() + " vacuous. Fix the scan mechanism "
                        + "before trusting any pass from this class.");
        assertTrue(index.containsClass(AGENT_RUNTIME_INTERNAL),
                "Sanity check failed: " + AGENT_RUNTIME_FQCN + " itself was not found on the scanned "
                        + "classpath. See the ModelProvider sanity failure above for why this voids every "
                        + "other assertion here.");
        assertTrue(index.hasCallGraphDataFor("ai/ravenroot/server/RavenrootServerMain"),
                "Sanity check failed: no call-graph data was recorded for RavenrootServerMain, the "
                        + "server composition root. The P3 (registration reachability) assertion in this "
                        + "class depends on this and would be vacuous otherwise.");
    }

    @Test
    void defaultDistributionShipsNoConcreteModelProvider() throws IOException {
        var index = ClassGraphIndex.scanJavaClassPath(WATCHED_SPI);
        List<String> concreteImplementations = index.concreteImplementationsOf(MODEL_PROVIDER_INTERNAL);
        assertTrue(concreteImplementations.isEmpty(), () -> "Release condition violated ("
                + "EU AI Act art. 50): the default distribution's resolved classpath contains a "
                + "concrete " + MODEL_PROVIDER_FQCN + " implementation: " + concreteImplementations + ". "
                + "Distributing a concrete model adapter by default makes Ravenroot a provider under "
                + "art. 3(3) with no transitional grace period (art. 99(4)(g): up to EUR 15,000,000 or 3% "
                + "global turnover) instead of an upstream component under recital 89. Move this class to "
                + "an optional, separately-installed adapter artifact with its own Maven coordinates, "
                + "excluded from the release reactor - a Maven profile of ravenroot-distribution is not "
                + "sufficient (the packaging-boundary analysis): it changes this module's own published artifact rather "
                + "than producing a separate one.");
    }

    @Test
    void defaultDistributionShipsNoConcreteAgentRuntime() throws IOException {
        var index = ClassGraphIndex.scanJavaClassPath(WATCHED_SPI);
        List<String> concreteImplementations = index.concreteImplementationsOf(AGENT_RUNTIME_INTERNAL);
        assertTrue(concreteImplementations.isEmpty(), () -> "Release condition violated ("
                + "EU AI Act art. 50): the default distribution's resolved classpath contains a "
                + "concrete " + AGENT_RUNTIME_FQCN + " implementation: " + concreteImplementations + ". "
                + "Same rule as ModelProvider (see that assertion in this class): the default "
                + "distribution ships no concrete agent adapter, so Ravenroot stays an upstream component "
                + "under recital 89 instead of becoming a provider under art. 3(3) from day one of public "
                + "release. Move this class to an optional, separately-installed adapter artifact with its "
                + "own Maven coordinates, excluded from the release reactor.");
    }

    /**
     * P3 (the artifact-boundary analysis): the determinative condition, not merely a class being present. A
     * concrete {@code ModelProvider}/{@code AgentRuntime} that is never registered arms nothing - the
     * factory looks the provider up by id while the graph is built and, when it is absent, refuses to
     * execute the node, failing that stage and invoking nothing
     * ({@code LlmPromptNodeBehaviorFactory}, {@code AgentNodeBehaviorFactory}; CORE-05 moved this
     * refusal from construction time to execution time and the lookup outcome is captured once per
     * execution, so an execution admitted with no adapter stays refused for the whole of that
     * execution). What actually makes
     * the distribution capable of inferring is a reachable call to {@code ModelProviderRegistry
     * #register} or {@code AgentRuntimeRegistry#register} from the composition root - {@code
     * RavenrootServerMain} for the server, {@code RavenrootCliMain} for the CLI (both launched
     * directly by {@code bin/ravenroot-server} and {@code bin/ravenroot} - see
     * {@code ravenroot-distribution/src/main/distribution/bin/}). Today neither composition root
     * constructs anything but empty registries and zero production code calls {@code register(...)}
     * on either (verified directly: {@code grep -rn "register(" ravenroot} outside {@code /test/}
     * finds only the two method declarations themselves and an unrelated {@code BehaviorRegistry
     * #register(String, NodeHandler)} in test-scope {@code ravenroot-engine-testkit}).
     */
    @Test
    void defaultDistributionCompositionRootNeverRegistersAnAdapter() throws IOException {
        var index = ClassGraphIndex.scanJavaClassPath(WATCHED_SPI);
        Map<String, String> registerTargets = Map.of(
                MODEL_PROVIDER_REGISTRY_INTERNAL, REGISTER_METHOD_NAME,
                AGENT_RUNTIME_REGISTRY_INTERNAL, REGISTER_METHOD_NAME);

        var serverEntry = new MethodRef("ai/ravenroot/server/RavenrootServerMain", "main", "([Ljava/lang/String;)V");
        var cliEntry = new MethodRef("ai/ravenroot/cli/RavenrootCliMain", "main", "([Ljava/lang/String;)V");

        for (MethodRef entry : List.of(serverEntry, cliEntry)) {
            var path = index.registerCallPathFrom(entry, registerTargets);
            assertTrue(path.isEmpty(), () -> "Release condition violated (EU AI Act art. "
                    + "50): a call to ModelProviderRegistry#register or AgentRuntimeRegistry#register is "
                    + "reachable from the composition root " + entry.display() + ": "
                    + String.join(" -> ", path.orElse(List.of())) + ". This is the act that actually arms "
                    + "an llm-prompt/agent node - a concrete adapter class alone does not (see P1/P2 in "
                    + "this class). Do not add this call to the default distribution's composition root; "
                    + "an optional adapter artifact may register itself once the user installs and wires "
                    + "it as their own act (the getting-started boundary in ADR 0017, "
                    + "docs/architecture/decisions/0017-article-50-product-decisions.md, brief section C).");
        }
    }

    @Test
    void defaultDistributionRegistersNoModelAdapterServiceProvider() throws IOException {
        var index = ClassGraphIndex.scanJavaClassPath(WATCHED_SPI);
        for (String spi : WATCHED_SPI) {
            var registrations = index.serviceRegistrationsFor(spi);
            // Prospective sentinel, not the primary guarantee (the release-boundary analysis): neither registry
            // is ServiceLoader-based today (both use explicit register(...) composition - see P3
            // above), so a META-INF/services file for either SPI would be inert right now. This stays
            // as a tripwire for the day someone converts either registry to ServiceLoader, at which
            // point maven-shade-plugin's ServicesResourceTransformer would merge such a file in from
            // any dependency, including one that never appears in this repository.
            assertTrue(registrations.isEmpty(), () -> "Prospective sentinel tripped (EU AI "
                    + "Act art. 50): the default distribution's resolved classpath contains a "
                    + "META-INF/services/" + spi + " registration: " + registrations + ". This is inert "
                    + "under today's explicit register(...) composition, but if either registry has been "
                    + "converted to ServiceLoader-based discovery since this check was written, it is no "
                    + "longer inert and is exactly the case-(a) trigger ADR 0017 "
                    + "(docs/architecture/decisions/0017-article-50-product-decisions.md) excludes. Remove the registration from whatever module the build depends on; an "
                    + "optional adapter artifact may still register itself, because installing it is the "
                    + "user's own act.");
        }
    }

    /**
     * Anchored on {@code ravenroot-ui/public/examples} - the checked-in source, always present on any
     * checkout - not on {@code target/classes}. The Java verification runs {@code mvn clean verify}
     * deliberately without building {@code ravenroot-ui/dist} first, so a check that
     * required {@code target/classes/ui/examples} to be non-empty would fail that job outright, not
     * just be weaker evidence. This is exactly why this class is the pre-filter and not the
     * authoritative check (see the class Javadoc): {@link ReleaseArtifactModelAdapterBoundaryIT} reads
     * what the build actually packaged, with no such requirement to find anything there, because a
     * ui-less build legitimately packages nothing under {@code ui/examples/}.
     */
    @Test
    void shippedExampleGraphsDeclareNoAiInvokingNode() throws IOException {
        List<Path> examples = new ArrayList<>();
        collectFiles(Path.of("..", "ravenroot-ui", "public", "examples"), ".graphml", examples);
        List<Path> jsonResources = new ArrayList<>();
        collectFiles(Path.of("..", "ravenroot-ui", "public", "examples"), ".json", jsonResources);
        // Opportunistic, not required: whatever this module actually packaged, if ravenroot-ui/dist
        // happened to be built before this test ran.
        collectFiles(Path.of("target", "classes"), ".graphml", examples);
        collectFiles(Path.of("target", "classes"), ".json", jsonResources);

        assertFalse(examples.isEmpty(), "Sanity check failed: no .graphml file was found under "
                + "ravenroot-ui/public/examples. Either the shipped-examples source moved and this check "
                + "needs to follow it, or the checkout is unexpectedly incomplete - either way this "
                + "assertion would otherwise be silently vacuous, so treat this failure as blocking too.");

        List<String> violations = new ArrayList<>();
        for (Path graphmlFile : examples) {
            try (InputStream input = Files.newInputStream(graphmlFile)) {
                violations.addAll(GraphResourceInspector.aiInvokingNodeBehaviors(
                        input, graphmlFile.toString(), AI_INVOKING_NODE_BEHAVIORS));
            }
        }
        for (Path jsonFile : jsonResources) {
            try (InputStream input = Files.newInputStream(jsonFile)) {
                violations.addAll(GraphResourceInspector.suspiciousJsonKeys(input, jsonFile.toString()));
            }
        }
        assertTrue(violations.isEmpty(), () -> "Release condition violated (EU AI Act art. "
                + "50): a graph resource that ships with the default distribution declares a node with "
                + "behavior 'llm-prompt' or 'agent' (or a previously schema-incompatible JSON resource now "
                + "has a node-behavior-shaped key): " + violations + ". ADR 0017 "
                + "(docs/architecture/decisions/0017-article-50-product-decisions.md) "
                + "requires shipped examples to live in documentation, not as executable graphs that "
                + "invoke a model the moment a provider is registered - that reconstitutes the case-(a) "
                + "trigger regardless of where the artifact is published. Move the example out of "
                + "ravenroot-ui/public/examples (or wherever it was added) and into documentation "
                + "instead.");
    }

    private static void collectFiles(Path directory, String suffix, List<Path> out) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .forEach(out::add);
        }
    }
}
