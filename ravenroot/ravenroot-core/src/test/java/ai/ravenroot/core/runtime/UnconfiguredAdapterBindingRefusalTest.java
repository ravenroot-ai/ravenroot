package ai.ravenroot.core.runtime;

import ai.ravenroot.api.ai.ModelProvider;
import ai.ravenroot.api.ai.ModelRequest;
import ai.ravenroot.api.ai.ModelResponse;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.api.security.ToolInvocation;
import ai.ravenroot.api.security.ToolPolicy;
import ai.ravenroot.core.ai.ModelProviderRegistry;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-05 and CORE-07: a node whose adapter is not configured is refused at
 * EXECUTION, not at construction — and the schema exemption that makes a blank adapter id an
 * unconfigured node rather than a defective graph.
 *
 * <h2>The subject is now a test-local behavior, and that is what keeps this file honest</h2>
 * <p>These rules were written for {@code llm-prompt} and {@code agent}, which left the core with the
 * AI nodes (ADR 0029). <b>The rules did not leave with them.</b> They belong to
 * {@link NodePropertyDescriptor#adapterId} and to {@link BehaviorPropertySchema}'s adapter-binding
 * carve-out — public API that a plugin bundle and any embedder will declare — and this file was and
 * still is the only place either is tested. In particular the blank/trim differential below is a
 * security failure mode: the schema grants the exemption using {@code isBlank()} while a
 * factory decides to refuse using its own spelling, and for 15 codepoints the two disagreed, so the
 * graph was admitted and then threw synchronously out of {@code GraphRunner}'s spawn loop. Deleting
 * these cases along with the two node types would have retired that guard at the exact moment its
 * only remaining subjects became code this project does not compile.</p>
 *
 * <p>So {@link BoundAdapterBehaviorFactory} below is registered on top of the standard catalog: a
 * behavior declaring one {@code adapterId} property and one ordinary required property, which is the
 * shape the departed nodes had and the shape a bundle node will have. It carries its own refusal,
 * because {@code UnconfiguredAdapterRefusal} left the core too — and it carries it under the same
 * discipline that class documented, which is stated on the factory rather than assumed.</p>
 *
 * <p>Reading (A), conditional refusal: the registry lookup survives, so composing a
 * {@code ModelProvider} and a factory that reads it still gets the real behavior. Under
 * construction-time refusal several properties held <em>structurally</em>, because no handler object
 * existed at all; a behavioural refusal has to assert them, and that is most of what follows.</p>
 */
class UnconfiguredAdapterBindingRefusalTest {

    /** The behavior name this file registers and exercises. */
    private static final String BOUND_ADAPTER = "bound-adapter";

    /**
     * A behavior with an adapter binding, registered on top of the core catalog.
     *
     * <h2>It carries its own refusal, and the discipline is not optional</h2>
     * <p>{@code UnconfiguredAdapterRefusal} left the core with the two AI nodes. What that
     * class documented is a property of the refusal, not of that class, so it is restated here as the
     * contract this factory is held to — and every rule below is asserted by a case in this file
     * rather than merely written down.</p>
     * <ol>
     *   <li><b>Never a {@link NodeResult}, for any outcome.</b> {@code GraphRunner} calls
     *   {@code markSyntheticProvenance} on every <em>successful</em> node completion and derives the
     *   marker from the registered catalog descriptor rather than from what the node did. A success
     *   returned from a refusal would stamp a machine-readable "a model generated this" onto content
     *   no model produced. Failing is what avoids it, and only failing does: the error branch goes to
     *   {@code nodeFailed} and never reaches the marking step.</li>
     *   <li><b>Exceptionally, but not by throwing.</b> A synchronous throw escapes {@code execute()}
     *   and leaves the traversal non-terminal with no failure event, and whether it does so depends on
     *   which engine adapter is installed. Always a failed future.</li>
     *   <li><b>No SPI is touched on the refusal path.</b> {@link ToolPolicy} is integrator-supplied
     *   and may create a human-approval work item as a side effect, so a graph author who can invoke
     *   nothing must not be able to generate approval load by naming adapters that do not exist. The
     *   provider registry is not re-read either: the lookup happens once, here, so an execution's
     *   capability set is fixed at admission.</li>
     * </ol>
     *
     * <p>The adapter id is read <b>first</b>, before any other required property, and through {@link
     * NodePropertyDescriptor#adapterIdOf} — the same call {@code BehaviorPropertySchema} uses to grant
     * the exemption. Respelling it as {@code isBlank()}, {@code trim()} or {@code strip()} here is the
     * defect: the schema relaxes required-ness on the strength of that exact classification, so
     * any value the two sides judge differently is admitted by the schema and not refused here, and
     * the node then neither validates nor refuses.</p>
     */
    private record BoundAdapterBehaviorFactory(ModelProviderRegistry providers, ToolPolicy toolPolicy)
            implements NodeBehaviorFactory {

        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor(BOUND_ADAPTER, "Bound adapter", "Testing",
                    "A behavior whose adapter is named by the graph and resolved by the deployment.",
                    "actor", false, List.of(
                    NodePropertyDescriptor.adapterId("provider", "Provider", NodePropertyType.STRING,
                            "ID of a composed ModelProvider adapter."),
                    NodePropertyDescriptor.required("prompt", "Prompt", NodePropertyType.TEXT,
                            "Instruction template.")),
                    // Deliberately NOT "ai": this behavior must not be generative, or every refusal
                    // case here would also be exercising the synthetic-provenance marking path and a
                    // failure could not be attributed to one or the other.
                    Set.of("external-provider"))
                    .withOutcomes(NodeOutcomeDescriptor.literal("continue", "The adapter answered."));
        }

        @Override
        public NodeHandler create(GraphNode node) {
            String providerId = NodePropertyDescriptor.adapterIdOf(node.properties().get("provider"));
            if (providerId.isEmpty()) {
                // The "for id" clause is dropped rather than filled with a placeholder: reusing the
                // id-carrying wording with an empty id renders "... configured for id ''", which
                // reads as though the author wrote an empty string on purpose.
                return refuse("Node " + node.id() + " cannot run: no model provider is configured");
            }
            String prompt = requiredProperty(node, "prompt");
            var configured = providers.find(providerId);
            if (configured.isEmpty()) {
                return refuse("Node " + node.id() + " cannot run: no model provider is configured for id '"
                        + providerId + "'");
            }
            var provider = configured.get();
            return message -> provider.generate(new ModelRequest(message.executionId(), node.id(), prompt,
                            message.payload(), "", "", Map.of()))
                    .thenApply(response -> new NodeResult("continue", response.payload(), message.attributes()));
        }

        private static NodeHandler refuse(String failure) {
            return message -> CompletableFuture.failedFuture(new IllegalStateException(failure));
        }

        /** The eager read the departed nodes performed through the core's package-private helper. */
        private static String requiredProperty(GraphNode node, String name) {
            Object value = node.properties().get(name);
            String text = value == null ? "" : value.toString().trim();
            if (text.isEmpty()) {
                throw new IllegalArgumentException("Node " + node.id() + " requires property '" + name + "'");
            }
            return text;
        }
    }

    private static final String LLM_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="k1" for="node" attr.name="kind" attr.type="string"/>
              <key id="k2" for="node" attr.name="behavior" attr.type="string"/>
              <key id="k3" for="node" attr.name="provider" attr.type="string"/>
              <key id="k4" for="node" attr.name="prompt" attr.type="string"/>
              <key id="k5" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="unconfigured-adapter" edgedefault="directed">
                <node id="error"><data key="k1">ERROR</data></node>
                <node id="start"><data key="k1">START</data></node>
                <node id="llm">
                  <data key="k1">BEHAVIOR</data>
                  <data key="k2">bound-adapter</data>
                  <data key="k3">test-model</data>
                  <data key="k4">Summarize {{payload}}</data>
                </node>
                <node id="end"><data key="k1">END</data></node>
                <edge id="e1" source="start" target="llm"><data key="k5">continue</data></edge>
                <edge id="e2" source="llm" target="end"><data key="k5">continue</data></edge>
              </graph>
            </graphml>
            """;

    /**
     * The graph must construct successfully because an unconfigured adapter is refused only when its
     * node is executed.
     */
    @Test
    void aGraphNamingAnUnconfiguredModelProviderConstructs() {
        var registry = registryWith(new ModelProviderRegistry(), ToolPolicy.denyAll());
        var engine = new DirectEngine();
        try (var manager = readLlmGraph();
             var runner = new GraphRunner(manager, engine, registry, new ExecutionMonitor())) {
            assertTrue(runner != null, "the runner must be constructible with no provider registered");
        }
        engine.close();
    }

    /**
     * The refusal contract is asserted on separate, non-interchangeable axes.
     *
     * <p><strong>Asynchronous boundary.</strong> {@code execute(...)} is called outside
     * {@code assertThrows}, so a handler that throws
     * synchronously fails this test instead of satisfying it. {@code GraphRunner.java:313} calls
     * {@code engine.send(...)} uncaught, and the traversal's terminal bookkeeping —
     * {@code state.executionFailed()}, {@code monitor.executionFailed(...)} and the
     * {@code JoinCoordinator} release — all live inside the returned stage. A synchronous throw
     * escapes {@code execute()} entirely and leaves the traversal non-terminal with no
     * {@code EXECUTION_FAILED} event at all. The Pekko adapter would convert such a throw into a
     * failed future and hide the defect; this engine, like the runner itself, does not.</p>
     *
     * <p><strong>No placeholder substitution.</strong> {@code NODE_FAILED} with no
     * {@code NODE_COMPLETED}/{@code NODE_DEFAULTED} is the precise negation of the substitution
     * hazard. A pass-through placeholder is not hypothetical — the
     * runner already contains one at {@code GraphRunner#fallback} for unknown behaviors, returning
     * {@code new NodeResult("continue", message.payload(), Map.of("ravenroot.defaultedNode", ...))}.
     * Written in that shape the refusal would succeed, the traversal would proceed as though the
     * model had run, and {@code markSyntheticProvenance} would stamp the payload.</p>
     *
     * <p><strong>No downstream execution.</strong> "The stage failed" and "nothing downstream ran"
     * are different facts in this runner.
     * {@code absorbIntoJoins} absorbs a branch failure into a fan-in, so under a {@code k of n} join
     * a refused branch can be dropped while the traversal succeeds — correct join semantics, and the
     * reason the two assertions have to be made separately rather than inferred from each other.</p>
     */
    @Test
    void reachingAnUnconfiguredLlmPromptNodeFailsTheStageAndRunsNothingDownstream() {
        var registry = registryWith(new ModelProviderRegistry(), ToolPolicy.denyAll());
        var engine = new DirectEngine();
        var monitor = new ExecutionMonitor();

        try (var manager = readLlmGraph();
             var runner = new GraphRunner(manager, engine, registry, monitor)) {
            // Outside the assertThrows lambda so a synchronous throw fails the test.
            CompletionStage<GraphExecutionResult> stage = runner.execute(TestIdentities.TENANT_A, "payload");
            var failure = assertThrows(ExecutionException.class, () -> stage.toCompletableFuture().get());

            Throwable rootCause = rootCauseOf(failure);
            // The refusal requires its own failure type; a bare IllegalArgumentException check would
            // also accept three unrelated construction failures.
            assertInstanceOf(IllegalStateException.class, rootCause,
                    "the refusal must be its own condition, not a generic argument or security failure");
            assertFalse(rootCause instanceof SecurityException,
                    "a SecurityException here would mean tool policy was consulted and masked the refusal");
            assertTrue(rootCause.getMessage().contains("llm"),
                    "the failure must name the node that refused: " + rootCause.getMessage());
            assertTrue(rootCause.getMessage().contains("test-model"),
                    "the failure must name the provider that is not configured: " + rootCause.getMessage());

            var events = monitor.eventsAfter(0);
            // The refused node fails without completing or defaulting.
            assertTrue(hasEvent(events, ExecutionEventType.NODE_FAILED, "llm"),
                    "the refused node must publish NODE_FAILED");
            assertFalse(hasEvent(events, ExecutionEventType.NODE_COMPLETED, "llm"),
                    "a refused node must never complete: a NodeResult here is stamped with a synthetic "
                            + "provenance marker derived from the catalog descriptor, not from what ran");
            assertFalse(hasEvent(events, ExecutionEventType.NODE_DEFAULTED, "llm"),
                    "the refusal must not degrade into the unknown-behavior pass-through");
            // Refusal stops the path before the downstream node starts.
            assertFalse(hasEvent(events, ExecutionEventType.NODE_STARTED, "end"),
                    "nothing downstream of the refused node may run");
        }
        engine.close();
    }

    /**
     * {@code ToolPolicy.denyAll()} proves only that the refusal is not masked; a recording policy is
     * required to prove that the policy was never consulted.
     *
     * <p>{@code ToolPolicy} is an integrator-supplied SPI, so evaluating it is calling arbitrary
     * third-party code that may reach a network decision point, and a real implementation may return
     * {@code REQUIRE_APPROVAL} with an {@code approvalId} — that is, evaluation can create a
     * human-approval work item as a side effect. A graph author who cannot invoke anything at all
     * would otherwise still be able to generate approval requests and operator noise at will simply
     * by naming providers that do not exist.</p>
     */
    @Test
    void theRefusalConsultsNoToolPolicy() {
        var observed = new ArrayList<ToolInvocation>();
        ToolPolicy recording = invocation -> {
            observed.add(invocation);
            return new ToolDecision(ToolDecision.Disposition.ALLOW, "recorded", "");
        };
        var registry = registryWith(new ModelProviderRegistry(), recording);
        var engine = new DirectEngine();

        try (var manager = readLlmGraph();
             var runner = new GraphRunner(manager, engine, registry, new ExecutionMonitor())) {
            CompletionStage<GraphExecutionResult> stage = runner.execute(TestIdentities.TENANT_A, "payload");
            assertThrows(ExecutionException.class, () -> stage.toCompletableFuture().get());
        }
        engine.close();

        assertTrue(observed.isEmpty(),
                "refusing must not touch the ToolPolicy SPI at all, and it recorded: " + observed);
    }

    /**
     * The registry is a live {@code ConcurrentHashMap} with a public {@code register} and no
     * {@code unregister}, and {@code GraphRunner} is public API an integrator may hold across many
     * traversals. Construction-time refusal froze the capability set at admission for free. A
     * placeholder that re-resolved the registry per invocation would give that away: an execution
     * that was admitted and refused would silently become live mid-flight the moment an operator
     * registered that id.
     *
     * <p>So the lookup happens once, in {@code create(GraphNode)}, and its outcome is captured.</p>
     *
     * <p>The freeze is per <em>execution</em>, not per graph document, and this test asserts only
     * that. {@code DefaultRavenrootApplication#startGraphMl} builds a new {@code GraphRunner} on
     * every submission, so resubmitting the same graph after a registration does resolve the
     * registry again and does get the adapter. That is intended; what must not happen is a running
     * execution changing capability underneath itself.</p>
     */
    @Test
    void theRefusalIsDecidedAtConstructionAndNotReResolvedPerInvocation() {
        var providers = new ModelProviderRegistry();
        var registry = registryWith(providers, ToolPolicy.denyAll());
        var engine = new DirectEngine();
        var invocations = new AtomicInteger();

        try (var manager = readLlmGraph();
             var runner = new GraphRunner(manager, engine, registry, new ExecutionMonitor())) {
            // The graph was admitted with nothing registered. Arming the id afterwards must not
            // retroactively arm the node.
            providers.register(countingProvider("test-model", invocations));

            CompletionStage<GraphExecutionResult> stage = runner.execute(TestIdentities.TENANT_A, "payload");
            assertThrows(ExecutionException.class, () -> stage.toCompletableFuture().get(),
                    "an execution admitted without a provider must stay refused for the whole of "
                            + "that execution");
        }
        engine.close();

        assertEquals(0, invocations.get(),
                "the late-registered provider must never be invoked by an already-admitted graph");
    }

    // ------------------------------------------------------------------ CORE-07

    /**
     * CORE-07 rule 1, and the control that is red under the previous behavior.
     *
     * <p>A node naming no adapter at all is an <em>unconfigured</em> node, not a defective graph.
     * Previously this graph did not construct: {@code BehaviorPropertySchema} rejected the blank
     * required {@code provider} at {@code GraphRunner.java:175}, before a single actor was spawned,
     * so the submission was refused with a schema error. The inconsistency that fixed was that
     * writing a made-up provider id worked <em>better</em> than leaving it blank — the made-up id
     * built and refused at execution, the blank one refused the whole graph.</p>
     */
    @Test
    void aGraphWhoseLlmPromptNamesNoProviderAtAllConstructsAndRefusesWhenReached() {
        var monitor = new ExecutionMonitor();
        Throwable rootCause = refusalOf(graphWith(BOUND_ADAPTER),
                new ModelProviderRegistry(), ToolPolicy.denyAll(), monitor);

        assertInstanceOf(IllegalStateException.class, rootCause,
                "an unconfigured node must refuse with the CORE-05 condition, not a schema or argument "
                        + "failure: " + rootCause);
        assertFalse(rootCause instanceof SecurityException,
                "a SecurityException would mean tool policy was consulted and masked the refusal");
        assertTrue(rootCause.getMessage().contains("ai"),
                "the failure must name the node that refused: " + rootCause.getMessage());

        var events = monitor.eventsAfter(0);
        assertTrue(hasEvent(events, ExecutionEventType.NODE_FAILED, "ai"),
                "the refused node must publish NODE_FAILED");
        assertFalse(hasEvent(events, ExecutionEventType.NODE_COMPLETED, "ai"),
                "a refused node must never complete: a NodeResult here would be stamped with a "
                        + "synthetic provenance marker derived from the catalog descriptor");
        assertFalse(hasEvent(events, ExecutionEventType.NODE_DEFAULTED, "ai"),
                "the refusal must not degrade into the unknown-behavior pass-through");
        assertFalse(hasEvent(events, ExecutionEventType.NODE_STARTED, "end"),
                "nothing downstream of the refused node may run");
    }

    /**
     * The refusal wording for an adapter that was never named must not degrade into the id-carrying
     * form with an empty id. {@code "... is configured for id ''"} reads as though the author wrote
     * an empty string on purpose, and it is the shape a careless reuse of the CORE-05 message
     * produces.
     *
     * <p>"The same reason as CORE-05" is the same refusal path and the same reason code, not the same
     * literal string. The register stays purely technical: a refusal asserting a compliance or legal
     * posture would be an external representation this project has not authorised.</p>
     */
    @Test
    void theUnnamedAdapterRefusalDoesNotRenderAnEmptyQuotedId() {
        Throwable rootCause = refusalOf(graphWith(BOUND_ADAPTER),
                new ModelProviderRegistry(), ToolPolicy.denyAll(), new ExecutionMonitor());

        String message = rootCause.getMessage();
        assertFalse(message.contains("id ''"),
                "the unnamed-adapter refusal must drop the \"for id\" clause entirely: " + message);
        assertFalse(message.contains("''"), message);
        assertTrue(message.contains("no model provider is configured"),
                "the refusal must still say what is missing, in a technical register: " + message);
    }

    /**
     * CORE-07 rule 5 on the new path. {@code ToolPolicy.denyAll()} proves only that the refusal
     * is not masked; a recording policy proves the SPI was never called at all.
     *
     * <p>Evaluating tool policy can create a human-approval work item as a side effect, so a graph
     * author who cannot invoke anything must not be able to generate approval load simply by leaving
     * an adapter blank.</p>
     */
    @Test
    void refusingAnUnnamedAdapterConsultsNoToolPolicy() {
        var observed = new ArrayList<ToolInvocation>();
        ToolPolicy recording = invocation -> {
            observed.add(invocation);
            return new ToolDecision(ToolDecision.Disposition.ALLOW, "recorded", "");
        };

        refusalOf(graphWith(BOUND_ADAPTER), new ModelProviderRegistry(), recording,
                new ExecutionMonitor());

        assertTrue(observed.isEmpty(),
                "refusing an unnamed adapter must not touch the ToolPolicy SPI, and it recorded: " + observed);
    }

    /**
     * An absent adapter id with a prompt present builds and refuses.
     *
     * <p>The node is unconfigured whether or not the author got as far as writing the prompt. Making
     * this case a schema refusal would reintroduce the inconsistency CORE-07 exists to remove, in a
     * narrower form.</p>
     */
    @Test
    void anAbsentAdapterIdWithAPromptPresentStillBuildsAndRefuses() {
        Throwable rootCause = refusalOf(
                graphWith(BOUND_ADAPTER, "<data key=\"kprompt\">Summarize {{payload}}</data>"),
                new ModelProviderRegistry(), ToolPolicy.denyAll(), new ExecutionMonitor());

        assertInstanceOf(IllegalStateException.class, rootCause, rootCause.toString());
    }

    /**
     * An adapter id that is named but resolves to nothing keeps the current behaviour
     * for its remaining required properties — a blank prompt is still a graph defect refused at
     * construction.
     *
     * <p>Naming an adapter is the author asserting the node is meant to be configured. From that
     * point the rest of the node is held to the full schema, exactly as before. This is the boundary
     * that keeps CORE-07 from becoming "AI nodes are never validated".</p>
     */
    @Test
    void anUnresolvableAdapterIdWithABlankPromptStillFailsAtConstruction() {
        var registry = registryWith(new ModelProviderRegistry(), ToolPolicy.denyAll());
        var engine = new DirectEngine();
        var graphMl = graphWith(BOUND_ADAPTER, "<data key=\"kprovider\">no-such-provider</data>");

        try (var manager = readGraph(graphMl)) {
            var failure = assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class,
                    () -> new GraphRunner(manager, engine, registry, new ExecutionMonitor()),
                    "a named adapter puts the node back under the full schema");
            assertEquals("prompt", failure.propertyName());
        }
        engine.close();
    }

    /**
     * CORE-07 constraint 2: when the adapter is configured,
     * {@code prompt} validation is byte-identical to today — a construction-time refusal.
     *
     * <p>{@code StandardBehaviorFactoriesTest} pins the positive side of this and passes unmodified.
     * This pins the negative side, which nothing asserted before.</p>
     */
    @Test
    void aConfiguredProviderStillRequiresThePromptAtConstruction() {
        var providers = new ModelProviderRegistry().register(countingProvider("test-model", new AtomicInteger()));
        var registry = registryWith(providers, ToolPolicy.denyAll());
        var engine = new DirectEngine();
        var graphMl = graphWith(BOUND_ADAPTER, "<data key=\"kprovider\">test-model</data>");

        try (var manager = readGraph(graphMl)) {
            var failure = assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class,
                    () -> new GraphRunner(manager, engine, registry, new ExecutionMonitor()));
            assertEquals("prompt", failure.propertyName());
        }
        engine.close();
    }

    /**
     * CORE-07 rule 1's boundary, asserted rather than argued: for a behavior that declares no
     * adapter binding, a missing required property is still a graph defect refused at construction.
     *
     * <p>{@code cel-transform} declares {@code expression} required and no adapter binding, so the
     * carve-out must not reach it. If this ever goes green, the carve-out has widened and
     * has removed real validation from every SDK-declared node.</p>
     */
    @Test
    void aBehaviorWithoutAnAdapterBindingStillFailsConstructionOnAMissingRequiredProperty() {
        var registry = BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults());
        var engine = new DirectEngine();

        try (var manager = readGraph(graphWith("cel-transform"))) {
            var failure = assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class,
                    () -> new GraphRunner(manager, engine, registry, new ExecutionMonitor()),
                    "a missing required property stays a graph defect for every other node kind");
            assertEquals("expression", failure.propertyName());
        }
        engine.close();
    }

    // --------------------------------------------- CORE-07 normalisation differential

    /**
     * The 15 codepoints for which {@code String.isBlank()} is {@code true} but {@code String.trim()}
     * does NOT empty the value. {@code trim()} strips only codepoints {@code <= U+0020};
     * {@code isBlank()} uses {@code Character.isWhitespace}. All 15 are legal XML 1.0 characters and
     * survive GraphML ingest.
     */
    private static final List<Integer> BLANK_BUT_NOT_TRIMMED = List.of(
            0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006,
            0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x205F, 0x3000);

    /**
     * The mirror set: 23 codepoints that {@code trim()} DOES strip but which {@code isBlank()} calls
     * non-blank, because they are control characters rather than whitespace. Most are illegal in XML
     * 1.0, so they arrive only through a programmatically built {@code GraphDefinition} — a path
     * {@code BehaviorPropertySchema} documents explicitly.
     *
     * <p>These values exercise the non-failing half of the classification and are included deliberately: they are the
     * direction a future "alignment" would break if it unified the two sides on {@code trim()}
     * instead. A differential guard that only covers the currently-failing half invites exactly that.</p>
     */
    private static final List<Integer> TRIMMED_BUT_NOT_BLANK = List.of(
            0x0000, 0x0001, 0x0002, 0x0003, 0x0004, 0x0005, 0x0006, 0x0007, 0x0008, 0x000E, 0x000F,
            0x0010, 0x0011, 0x0012, 0x0013, 0x0014, 0x0015, 0x0016, 0x0017, 0x0018, 0x0019, 0x001A, 0x001B);

    static Stream<Arguments> divergingAdapterIds() {
        // One behavior, not two: the second row was the `agent` node, which left the core.
        // The differential is a property of the SCHEMA and of NodePropertyDescriptor.adapterIdOf, not
        // of any particular behavior, so one behavior declaring an adapter binding exercises it in
        // full -- and BOUND_ADAPTER is registered by this file, so it cannot leave without the cases.
        return Stream.concat(BLANK_BUT_NOT_TRIMMED.stream(), TRIMMED_BUT_NOT_BLANK.stream())
                .map(codepoint -> Arguments.of(codepoint, BOUND_ADAPTER, "provider", "prompt"));
    }

    /**
     * Variant (i). An adapter id on either side of the blank/trim differential, with the behavior's
     * OTHER required property absent, must never throw out of {@code create(GraphNode)}.
     *
     * <p>This is the normalization defect. The validator granted the exemption using
     * {@code isBlank()} while the factories decided to refuse using {@code trim().isEmpty()}, so for
     * the 15 codepoints above the graph was admitted — hashed and recorded — and then
     * {@code NodeProperties.required} threw synchronously from inside {@code GraphRunner}'s spawn
     * loop. That is the exact failure mode CORE-05 and CORE-07 exist to remove, reopened by graph
     * content: a synchronous throw escapes {@code execute()} and leaves the traversal non-terminal
     * with no failure event recorded at all, which is why rule 2 on {@link BoundAdapterBehaviorFactory}
     * requires a failed future.</p>
     *
     * <p>Only two outcomes are acceptable, and the test accepts either, because which one is correct
     * is a consequence of the shared definition rather than a separate decision: the submission is
     * refused by the schema's own exception, or the graph is admitted and the node refuses when
     * reached. What must never happen is a third outcome — any other exception escaping
     * construction.</p>
     */
    @ParameterizedTest(name = "U+{0} {1}")
    @MethodSource("divergingAdapterIds")
    void anAdapterIdAcrossTheBlankDifferentialNeverThrowsOutOfCreate(int codepoint, String behavior,
                                                                     String adapterProperty, String otherRequired) {
        String adapterId = new String(Character.toChars(codepoint));
        var registry = registryWith(new ModelProviderRegistry(), ToolPolicy.denyAll());
        var engine = new DirectEngine();

        try (var manager = programmaticGraph(behavior, Map.of(adapterProperty, adapterId))) {
            GraphRunner constructed = null;
            RuntimeException constructionFailure = null;
            try {
                constructed = new GraphRunner(manager, engine, registry, new ExecutionMonitor());
            } catch (RuntimeException error) {
                constructionFailure = error;
            }

            if (constructionFailure != null) {
                assertInstanceOf(BehaviorPropertySchema.BehaviorPropertyException.class, constructionFailure,
                        "construction may only refuse through the schema. Any other exception here is "
                                + "NodeProperties throwing out of create() inside the spawn loop, which leaves "
                                + "the traversal non-terminal with no recorded failure event: " + constructionFailure);
            } else {
                try (var runner = constructed) {
                    CompletionStage<GraphExecutionResult> stage = runner.execute(TestIdentities.TENANT_A, "payload");
                    var failure = assertThrows(ExecutionException.class, () -> stage.toCompletableFuture().get(),
                            "an admitted node that names no adapter must refuse when reached");
                    assertInstanceOf(IllegalStateException.class, rootCauseOf(failure),
                            "the refusal must be the CORE-05 condition: " + rootCauseOf(failure));
                }
            }
        }
        engine.close();
    }

    /**
     * Variant (ii). The same exotic adapter ids with every required property PRESENT must refuse at
     * execution and must never produce a construction exception. Nothing is registered, so a node
     * that got past the binding check still has no adapter to reach.
     */
    @ParameterizedTest(name = "U+{0} {1}")
    @MethodSource("divergingAdapterIds")
    void anAdapterIdAcrossTheBlankDifferentialRefusesRatherThanFailingConstruction(int codepoint, String behavior,
                                                                                    String adapterProperty,
                                                                                    String otherRequired) {
        String adapterId = new String(Character.toChars(codepoint));
        var registry = registryWith(new ModelProviderRegistry(), ToolPolicy.denyAll());
        var engine = new DirectEngine();

        try (var manager = programmaticGraph(behavior,
                Map.of(adapterProperty, adapterId, otherRequired, "present {{payload}}"))) {
            try (var runner = new GraphRunner(manager, engine, registry, new ExecutionMonitor())) {
                CompletionStage<GraphExecutionResult> stage = runner.execute(TestIdentities.TENANT_A, "payload");
                var failure = assertThrows(ExecutionException.class, () -> stage.toCompletableFuture().get());
                assertInstanceOf(IllegalStateException.class, rootCauseOf(failure),
                        "a complete node whose adapter id names nothing must refuse, not fail construction");
            }
        }
        engine.close();
    }

    /**
     * The reported exploit path end to end, through real GraphML rather than a programmatic graph.
     * U+3000 (ideographic space) is legal XML, survives ingest, and exercises the full path.
     */
    @Test
    void anIdeographicSpaceProviderSurvivesIngestAndRefusesInsteadOfExploding() {
        Throwable rootCause = refusalOf(graphWith(BOUND_ADAPTER, "<data key=\"kprovider\">　</data>"),
                new ModelProviderRegistry(), ToolPolicy.denyAll(), new ExecutionMonitor());

        assertInstanceOf(IllegalStateException.class, rootCause,
                "an ideographic-space provider must take the refusal path, not throw from the spawn loop");
        assertFalse(rootCause.getMessage().contains("requires property"),
                "a NodeProperties required-property throw here means the exemption and the refusal "
                        + "disagree about what counts as naming no adapter: " + rootCause.getMessage());
    }

    private static GraphManager programmaticGraph(String behavior, Map<String, Object> properties) {
        return GraphManager.from(new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("ai", NodeKind.BEHAVIOR, behavior, properties),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "ai"), GraphEdge.to("ai", "end"))));
    }

    /**
     * An unconfigured adapter binding is admitted at graph construction and refused on invocation.
     *
     * <p>A property-less {@code llm-prompt} or {@code agent} node is unconfigured rather than
     * structurally invalid, so the graph must build. The assertion pins that construction produces a
     * handler and invocation refuses.</p>
     *
     * <p>The reads for every OTHER required property stay eager, which is what keeps this narrow:
     * {@code BehaviorRegistry#find} builds a synthetic property-less node that bypasses
     * {@code BehaviorPropertySchema} entirely, so for a behavior with no adapter binding the eager
     * read is still the only guard on that path. That is asserted separately below.</p>
     */
    @Test
    void aNodeNamingNoAdapterConstructsAndRefusesInsteadOfThrowingAtConstruction() throws Exception {
        var registry = registryWith(new ModelProviderRegistry(), ToolPolicy.denyAll());

        for (String behavior : List.of(BOUND_ADAPTER)) {
            NodeHandler handler = registry.create(GraphNode.behavior("n", behavior)).orElseThrow(
                    () -> new AssertionError("a node naming no adapter must still produce a handler: " + behavior));
            var refusal = assertThrows(ExecutionException.class,
                    () -> handler.handle(new ai.ravenroot.api.execution.NodeMessage(TestIdentities.TENANT_A,
                            java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "n", "payload", Map.of()))
                            .toCompletableFuture().get(),
                    "the handler must refuse rather than run: " + behavior);
            assertInstanceOf(IllegalStateException.class, rootCauseOf(refusal), behavior);

            // Same for the synthetic property-less node BehaviorRegistry#find builds.
            assertTrue(registry.find(behavior).isPresent(), behavior);
        }
    }

    /**
     * The other half of C-a, and the boundary that keeps CORE-07 narrow: a behavior that declares no
     * adapter binding still throws eagerly on a property-less node.
     *
     * <p>This is the {@code BehaviorRegistry#find} path, which bypasses
     * {@code BehaviorPropertySchema} entirely, so nothing else guards it. If this ever stops
     * throwing, {@code find} has started handing out handlers for genuinely incomplete nodes.</p>
     */
    @Test
    void aBehaviorWithoutAnAdapterBindingStillThrowsEagerlyOnAPropertyLessNode() {
        var registry = BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults());

        assertThrows(IllegalArgumentException.class,
                () -> registry.create(GraphNode.behavior("n", "cel-transform")),
                "a node with no properties must still be refused while the graph is being built");
        assertThrows(IllegalArgumentException.class, () -> registry.find("cel-transform"));
    }

    /**
     * The standard catalog plus {@link BoundAdapterBehaviorFactory}.
     *
     * <p>Composed exactly the way ADR 0029 says an embedder composes a model-consuming node after
     * {@code BehaviorRegistry.standard(environment)} for the core catalog, then
     * {@code registerFactory} for the behavior that reads the provider registry. The seam is what the
     * core kept; the factory is what it stopped supplying.</p>
     */
    private static BehaviorRegistry registryWith(ModelProviderRegistry providers, ToolPolicy toolPolicy) {
        var environment = new BehaviorEnvironment(providers, null, null, null, null, toolPolicy, null);
        return BehaviorRegistry.standard(environment)
                .registerFactory(new BoundAdapterBehaviorFactory(providers, toolPolicy));
    }

    private static GraphManager readLlmGraph() {
        return GraphManager.readGraphMl(new ByteArrayInputStream(LLM_GRAPH.getBytes(StandardCharsets.UTF_8)));
    }

    private static GraphManager readGraph(String graphMl) {
        return GraphManager.readGraphMl(new ByteArrayInputStream(graphMl.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * One behavior node named {@code ai} between a start and an end, carrying exactly the property
     * lines given. Absent means <em>absent</em>: no key here declares a {@code <default>}, so a
     * property that is not passed does not reach the node at all.
     */
    private static String graphWith(String behavior, String... propertyLines) {
        var properties = new StringBuilder();
        for (String line : propertyLines) {
            properties.append("      ").append(line).append('\n');
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="k1" for="node" attr.name="kind" attr.type="string"/>
                  <key id="k2" for="node" attr.name="behavior" attr.type="string"/>
                  <key id="kprovider" for="node" attr.name="provider" attr.type="string"/>
                  <key id="kprompt" for="node" attr.name="prompt" attr.type="string"/>
                  <key id="kruntime" for="node" attr.name="runtime" attr.type="string"/>
                  <key id="kobjective" for="node" attr.name="objective" attr.type="string"/>
                  <key id="kexpression" for="node" attr.name="expression" attr.type="string"/>
                  <key id="k5" for="edge" attr.name="outcome" attr.type="string"/>
                  <graph id="core-07" edgedefault="directed">
                    <node id="error"><data key="k1">ERROR</data></node>
                    <node id="start"><data key="k1">START</data></node>
                    <node id="ai">
                      <data key="k1">BEHAVIOR</data>
                      <data key="k2">%s</data>
                %s    </node>
                    <node id="end"><data key="k1">END</data></node>
                    <edge id="e1" source="start" target="ai"><data key="k5">continue</data></edge>
                    <edge id="e2" source="ai" target="end"><data key="k5">continue</data></edge>
                  </graph>
                </graphml>
                """.formatted(behavior, properties);
    }

    /** Runs the single behavior node and returns the root cause it refused with. */
    private static Throwable refusalOf(String graphMl, ModelProviderRegistry providers, ToolPolicy toolPolicy,
                                       ExecutionMonitor monitor) {
        var registry = registryWith(providers, toolPolicy);
        var engine = new DirectEngine();
        try (var manager = readGraph(graphMl);
             var runner = new GraphRunner(manager, engine, registry, monitor)) {
            // Outside assertThrows so a synchronous throw fails this test rather than satisfying it.
            CompletionStage<GraphExecutionResult> stage = runner.execute(TestIdentities.TENANT_A, "payload");
            var failure = assertThrows(ExecutionException.class, () -> stage.toCompletableFuture().get());
            return rootCauseOf(failure);
        } finally {
            engine.close();
        }
    }

    private static boolean hasEvent(List<ai.ravenroot.api.application.ExecutionEvent> events,
                                    ExecutionEventType type, String nodeId) {
        return events.stream().anyMatch(event -> event.type() == type && nodeId.equals(event.nodeId()));
    }

    private static Throwable rootCauseOf(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static ModelProvider countingProvider(String id, AtomicInteger invocations) {
        return new ModelProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public CompletionStage<ModelResponse> generate(ModelRequest request) {
                invocations.incrementAndGet();
                return CompletableFuture.completedFuture(
                        new ModelResponse(request.payload(), id(), request.model(), Map.of()));
            }
        };
    }

    /** Synchronous engine: the node genuinely runs, and a synchronous throw is not absorbed. */
    private static final class DirectEngine implements ai.ravenroot.api.execution.ExecutionEngine {
        private final Map<ai.ravenroot.api.execution.NodeRef, ai.ravenroot.api.execution.RavenNode> nodes =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public String id() {
            return "direct";
        }

        @Override
        public java.util.Set<ai.ravenroot.api.execution.EngineCapability> capabilities() {
            return java.util.Set.of();
        }

        @Override
        public ai.ravenroot.api.execution.Scheduler scheduler() {
            return (delay, task) -> () -> true;
        }

        @Override
        public ai.ravenroot.api.execution.NodeRef spawn(String logicalName,
                                                        ai.ravenroot.api.execution.RavenNode node) {
            var ref = new ai.ravenroot.api.execution.NodeRef(logicalName + "-" + java.util.UUID.randomUUID());
            nodes.put(ref, node);
            return ref;
        }

        @Override
        public CompletionStage<ai.ravenroot.api.execution.NodeResult> send(
                ai.ravenroot.api.execution.NodeRef target, ai.ravenroot.api.execution.NodeMessage message) {
            return nodes.get(target).onMessage(message, new ai.ravenroot.api.execution.NodeContext() {
                @Override
                public ai.ravenroot.api.execution.NodeRef self() {
                    return target;
                }

                @Override
                public ai.ravenroot.api.execution.Scheduler scheduler() {
                    return DirectEngine.this.scheduler();
                }

                @Override
                public ai.ravenroot.api.execution.Mailbox mailbox() {
                    return () -> 0;
                }

                @Override
                public ai.ravenroot.api.execution.CancellationSignal cancellation() {
                    return StubEngineLifecycle.NEVER_CANCELLED;
                }
            });
        }

        @Override
        public CompletionStage<Void> stop(ai.ravenroot.api.execution.NodeRef target) {
            nodes.remove(target);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public ai.ravenroot.api.execution.EngineState state() {
            return ai.ravenroot.api.execution.EngineState.RUNNING;
        }

        @Override
        public java.util.Optional<ai.ravenroot.api.execution.NodeStatus> status(
                ai.ravenroot.api.execution.NodeRef target) {
            return java.util.Optional.of(StubEngineLifecycle.running(target));
        }

        @Override
        public CompletionStage<Void> cancel(ai.ravenroot.api.execution.NodeRef target) {
            return stop(target);
        }

        @Override
        public CompletionStage<Void> drain() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            nodes.clear();
        }
    }
}
