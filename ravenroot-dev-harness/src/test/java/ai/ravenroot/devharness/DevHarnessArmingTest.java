package ai.ravenroot.devharness;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.ToolPolicy;
import ai.ravenroot.core.ai.AgentRuntimeRegistry;
import ai.ravenroot.core.ai.ModelProviderRegistry;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.security.AllowlistToolPolicy;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bench arms, and the proof is an {@code llm-prompt} node that really returns a model's text.
 *
 * <h2>What this file is for, and what it replaced</h2>
 * <p>Previously the bench declared its providers through {@code ModelProviderService.seed}, so that
 * its own configuration and a save from the editor's Model providers panel reached one store through
 * one arming path — and this file asserted exactly that seam: <em>save a profile, then look in the
 * registry the node actually resolves against</em>. The panel, routes and service are now absent, so
 * the second writer is gone and the seam it guarded
 * no longer exists.</p>
 *
 * <p><b>The assertion did not get weaker, it moved one step further out.</b> The old cases could stop
 * at "the id resolves in the registry", because the core supplied the node that read it. The core
 * now supplies neither the node nor the provider, so "the registry has an entry" would no longer
 * establish that anything works — the bench could arm a registry, fail to register the behavior, and
 * pass. So what is pinned here is the whole path the bench composes, end to end: the environment
 * declaration, {@link DevHarnessMain#armDeclaredProviders}, {@link LlmPromptNodeBehaviorFactory}
 * registered onto the standard catalogue, the adapter, and the socket. Only the far end is a double.</p>
 *
 * <h2>Composed the way {@code DevHarnessMain} composes it</h2>
 * <p>{@link #benchBehaviors} mirrors {@code main}'s composition rather than inventing a convenient
 * one, because the defect this file exists to catch is precisely a bench that arms one thing and
 * serves another. Every assertion below runs against that composition.</p>
 */
class DevHarnessArmingTest {

    private static final String KEY_REFERENCE = "ollama-key";
    private static final String KEY = "sk-local-0123456789";
    private static final SecurityContext TENANT =
            new SecurityContext("request-tenant-a-alice", "tenant-a", "alice", PrincipalType.USER,
                    "urn:ravenroot:test");

    private ChatCompletionsDouble endpoint;

    @BeforeEach
    void start() throws Exception {
        endpoint = ChatCompletionsDouble.start();
    }

    @AfterEach
    void stop() {
        endpoint.close();
    }

    /**
     * <b>The acceptance case: what the bench declares in its environment is what an {@code llm-prompt}
     * node calls.</b>
     *
     * <p>Nothing here names a provider object. The id comes from {@code RAVENROOT_DEV_MODEL_PROVIDERS},
     * the endpoint from {@code RAVENROOT_DEV_MODEL_OLLAMA_LOCAL_ENDPOINT}, and the node names the id
     * an author would type — which is the whole claim the bench makes to its one user.</p>
     */
    @Test
    @DisplayName("a declared provider becomes a node that returns the model's text")
    void aDeclaredProviderBecomesANodeThatReturnsTheModelText() throws Exception {
        endpoint.responds(200, ChatCompletionsDouble.completion("Ravenroot runs graphs."));
        Map<String, String> environment = declaring("ollama-local", KEY_REFERENCE);
        var providers = new ModelProviderRegistry();

        List<String> armed = DevHarnessMain.armDeclaredProviders(environment, providers, resolver());

        assertEquals(List.of("ollama-local"), armed);
        var behaviors = benchBehaviors(providers, environment);
        NodeResult result = run(behaviors, Map.of(
                "provider", "ollama-local",
                "prompt", "Summarise {{payload}}",
                "credentialRef", KEY_REFERENCE), "the product description");

        assertEquals("continue", result.outcome());
        // The contract requirement in one line: the node's payload is what the model wrote.
        assertEquals("Ravenroot runs graphs.", result.payload());
        assertEquals("ollama-local", result.attributes().get("llm.provider"));
        assertEquals("qwen3", result.attributes().get("llm.model"));
        // The node rendered {{payload}} before the adapter ever saw the prompt, and the adapter sent
        // exactly that. Asserted on the body the double received, not on anything this test built.
        assertTrue(endpoint.observedBody().contains("Summarise the product description"),
                endpoint.observedBody());
        assertEquals("Bearer " + KEY, endpoint.observedAuthorization());
    }

    /**
     * <b>The catalogue the bench serves contains {@code llm-prompt}, and the core's does not.</b>
     *
     * <p>Both halves must remain distinct: the bench supplies the node itself, while a future change
     * that quietly restored it to the core would make the first assertion pass while breaking the
     * release condition. Reading the same {@code descriptors()} list twice is what keeps the two
     * comparable.</p>
     */
    @Test
    @DisplayName("the bench supplies llm-prompt and the core catalogue does not")
    void theBenchSuppliesTheNodeAndTheCoreCatalogueDoesNot() {
        var providers = new ModelProviderRegistry();
        var environment = declaring("ollama-local", "");

        assertTrue(behaviorNames(benchBehaviors(providers, environment)).contains("llm-prompt"),
                "the bench must serve the node it exists to exercise");
        assertFalse(behaviorNames(BehaviorRegistry.standard(benchEnvironment(providers, environment)))
                        .contains("llm-prompt"),
                "the core catalogue must not ship it, and a bench that "
                        + "stopped being able to tell the difference would stop being evidence of anything");
    }

    /**
     * <b>The unauthenticated local mode runs end to end with no credential anywhere.</b>
     *
     * <p>A representative target: a {@code qwen3} on a local Ollama, which reads no
     * {@code Authorization} header. The resolver used here <em>throws</em> on any lookup, so this also
     * establishes that the {@code NONE} path resolves nothing rather than resolving and discarding.</p>
     */
    @Test
    @DisplayName("an endpoint declared without a credential reference is called unauthenticated")
    void anEndpointDeclaredWithoutACredentialReferenceIsCalledUnauthenticated() throws Exception {
        endpoint.responds(200, ChatCompletionsDouble.completion("42"));
        Map<String, String> environment = declaring("ollama-local", "");
        var providers = new ModelProviderRegistry();

        DevHarnessMain.armDeclaredProviders(environment, providers, refusingResolver());

        NodeResult result = run(benchBehaviors(providers, environment), Map.of(
                "provider", "ollama-local",
                "prompt", "What is {{payload}}?"), "six times seven");

        assertEquals("42", result.payload());
        assertEquals("", endpoint.observedAuthorization());
    }

    /**
     * <b>A node naming an id the environment never declared refuses when reached, and reaches no
     * socket.</b>
     *
     * <p>The negative half of arming, and it is what makes the positive half mean something: without
     * it every assertion above would pass equally well if the factory called the provider whatever the
     * node said. It also pins the CORE-05/CORE-07 discipline
     * {@link LlmPromptNodeBehaviorFactory} carried over from the core — the graph constructs, the
     * refusal happens at execution, and it is a failed stage rather than a {@link NodeResult}, because
     * a result here would be stamped with a synthetic-provenance marker over content no model
     * produced.</p>
     */
    @Test
    @DisplayName("an undeclared id refuses at execution and opens no socket")
    void anUndeclaredIdRefusesAtExecutionAndOpensNoSocket() {
        Map<String, String> environment = declaring("ollama-local", "");
        var providers = new ModelProviderRegistry();
        DevHarnessMain.armDeclaredProviders(environment, providers, resolver());

        var handler = benchBehaviors(providers, environment).create(node(Map.of(
                "provider", "not-declared-anywhere",
                "prompt", "Summarise {{payload}}"))).orElseThrow(
                () -> new AssertionError("the graph must still construct: the node is unconfigured, "
                        + "not defective"));
        var refused = handler.handle(message("anything")).toCompletableFuture();

        assertTrue(refused.isCompletedExceptionally(),
                "a refusal must be a failed stage, never a NodeResult");
        assertEquals(0, endpoint.calls());
    }

    /**
     * <b>One provider instance serves concurrent nodes, each with its own credential.</b>
     *
     * <p>{@code ModelProvider} and ADR 0024 §3 require implementations to be safe for concurrent use:
     * one instance is shared by every node that resolves it, and those nodes run concurrently.
     * This drives sixteen simultaneous completions through one armed provider and one client, with a
     * distinct credential reference each, and checks that no answer and no credential is crossed.</p>
     */
    @Test
    @DisplayName("one armed provider serves concurrent nodes without crossing a credential")
    void oneArmedProviderServesConcurrentNodesWithoutCrossingACredential() throws Exception {
        endpoint.responds(200, ChatCompletionsDouble.completion("shared answer"));
        Map<String, String> environment = declaring("ollama-local", "reference-0");
        var providers = new ModelProviderRegistry();
        DevHarnessMain.armDeclaredProviders(environment, providers,
                reference -> Optional.of(new SecretValue(("key-for-" + reference).toCharArray())));
        var behaviors = benchBehaviors(providers, environment);

        int concurrency = 16;
        var start = new CountDownLatch(1);
        var failures = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            List<CompletableFuture<NodeResult>> running = new java.util.ArrayList<>();
            for (int index = 0; index < concurrency; index++) {
                String reference = "reference-" + index;
                var handler = behaviors.create(node(Map.of(
                        "provider", "ollama-local",
                        "prompt", "Answer {{payload}}",
                        "credentialRef", reference))).orElseThrow();
                running.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                        return handler.handle(message("payload-" + reference))
                                .toCompletableFuture().get(30, TimeUnit.SECONDS);
                    } catch (Exception failed) {
                        failures.incrementAndGet();
                        throw new IllegalStateException(failed);
                    }
                }, pool));
            }
            start.countDown();
            for (CompletableFuture<NodeResult> pending : running) {
                assertEquals("shared answer", pending.get(60, TimeUnit.SECONDS).payload());
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, failures.get());
        assertEquals(concurrency, endpoint.calls());
        // Every reference produced its own key, and each arrived exactly once: no cache, no crossed
        // credential. Sorted because arrival order under concurrency is not a property worth pinning.
        List<String> expected = java.util.stream.IntStream.range(0, concurrency)
                .mapToObj(index -> "Bearer key-for-reference-" + index).sorted().toList();
        assertEquals(expected, endpoint.observedAuthorizations().stream().sorted().toList());
    }

    /**
     * <b>A declared provider carries a credential REFERENCE and never a value.</b>
     *
     * <p>{@code RAVENROOT_DEV_MODEL_<ID>_CREDENTIAL_REF} names a reference resolved by
     * {@code EnvironmentCredentialResolver}; it is never read by the bench. The shape assertion below
     * is what makes that sentence structural rather than a promise: the record has no component that
     * could carry a value. A bench that put the secret itself into its own configuration record would
     * have reintroduced, one module away, precisely what the server refuses.</p>
     */
    @Test
    @DisplayName("a declared provider carries the reference it was given and nothing else")
    void aDeclaredProviderCarriesTheReferenceItWasGivenAndNothingElse() {
        var declared = DevHarnessMain.declaredProviders(Map.of(
                "RAVENROOT_DEV_MODEL_PROVIDERS", "paid-one",
                "RAVENROOT_DEV_MODEL_PAID_ONE_CREDENTIAL_REF", "vendor-key"));

        assertEquals(1, declared.size());
        assertEquals("vendor-key", declared.get(0).credentialRef());
        for (var component : DevHarnessMain.DeclaredProvider.class.getRecordComponents()) {
            assertFalse(component.getName().toLowerCase(java.util.Locale.ROOT).matches(
                    ".*(apikey|secret|password|token).*"), component.getName());
        }
    }

    /**
     * <b>Declaring the same id twice leaves the second endpoint live.</b>
     *
     * <p>{@code ModelProviderRegistry#register} overwrites by id, which the bench relies on rather
     * than guards against: an operator who corrects an endpoint and restarts must get the corrected
     * one. If the second registration did not take, the bench would go on calling the old endpoint
     * while its own startup line printed the new configuration.</p>
     */
    @Test
    @DisplayName("re-arming an id replaces what the node resolves")
    void reArmingAnIdReplacesWhatTheNodeResolves() {
        var providers = new ModelProviderRegistry();
        DevHarnessMain.armDeclaredProviders(declaring("ollama-local", ""), providers, resolver());
        var first = providers.find("ollama-local").orElseThrow();

        DevHarnessMain.armDeclaredProviders(declaring("ollama-local", ""), providers, resolver());
        var second = providers.find("ollama-local").orElseThrow();

        assertFalse(first == second, "the second declaration must have replaced what the node resolves");
    }

    // ---- fixtures --------------------------------------------------------------------------------

    /** The environment {@code DevHarnessMain} would read, pointed at this test's own endpoint. */
    private Map<String, String> declaring(String id, String credentialRef) {
        String segment = DevHarnessMain.variableSegment(id);
        var environment = new java.util.LinkedHashMap<String, String>();
        environment.put(DevHarnessMain.PROVIDERS_VARIABLE, id);
        environment.put("RAVENROOT_DEV_MODEL_" + segment + "_ENDPOINT", endpoint.endpoint());
        environment.put("RAVENROOT_DEV_MODEL_" + segment + "_MODEL", "qwen3");
        if (!credentialRef.isEmpty()) {
            environment.put("RAVENROOT_DEV_MODEL_" + segment + "_CREDENTIAL_REF", credentialRef);
        }
        // The bench derives no egress settings itself -- dev.sh does, from the endpoints about to be
        // declared. Spelled out here so the outbound policy this composition builds is the one
        // an operator would actually have, rather than a permissive one that would hide an
        // egress-shaped failure.
        environment.put("RAVENROOT_HTTP_ALLOWED_HOSTS", endpoint.host());
        environment.put("RAVENROOT_HTTP_ALLOWED_PORTS", String.valueOf(endpoint.port()));
        return Map.copyOf(environment);
    }

    /**
     * {@code DevHarnessMain}'s composition, and deliberately spelled the same way.
     *
     * <p>Note the tool policy: {@code AllowlistToolPolicy} over the bench's own default of
     * {@code model.generate}, not a blanket allow. The node authorises the tool before it calls the
     * provider, and a blanket allow would let this suite pass even if that authorisation were removed.</p>
     */
    private static BehaviorRegistry benchBehaviors(ModelProviderRegistry providers,
                                                   Map<String, String> environment) {
        ToolPolicy toolPolicy = benchToolPolicy(environment);
        return BehaviorRegistry.standard(benchEnvironment(providers, environment))
                .registerFactory(new LlmPromptNodeBehaviorFactory(providers, toolPolicy));
    }

    private static BehaviorEnvironment benchEnvironment(ModelProviderRegistry providers,
                                                        Map<String, String> environment) {
        return new BehaviorEnvironment(providers, new AgentRuntimeRegistry(),
                new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                ignored -> Optional.empty(), benchToolPolicy(environment),
                OutboundHttpPolicy.fromCommaSeparated(
                        environment.get("RAVENROOT_HTTP_ALLOWED_HOSTS"),
                        environment.get("RAVENROOT_HTTP_ALLOWED_PORTS"), 0, 0));
    }

    private static ToolPolicy benchToolPolicy(Map<String, String> environment) {
        return AllowlistToolPolicy.fromCommaSeparated(
                environment.getOrDefault("RAVENROOT_ALLOWED_TOOLS", "model.generate"));
    }

    private static List<String> behaviorNames(BehaviorRegistry registry) {
        return registry.descriptors().stream()
                .map(ai.ravenroot.api.catalog.NodeTypeDescriptor::behavior).toList();
    }

    private static NodeResult run(BehaviorRegistry behaviors, Map<String, Object> properties, Object payload)
            throws Exception {
        return behaviors.create(node(properties)).orElseThrow()
                .handle(message(payload)).toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private static CredentialResolver resolver() {
        return reference -> KEY_REFERENCE.equals(reference)
                ? Optional.of(new SecretValue(KEY.toCharArray()))
                : Optional.empty();
    }

    /** Proves the NONE path resolves nothing at all: this resolver would fail any lookup. */
    private static CredentialResolver refusingResolver() {
        return reference -> {
            throw new AssertionError("the unauthenticated path must not resolve a credential");
        };
    }

    private static GraphNode node(Map<String, Object> properties) {
        return new GraphNode("llm-prompt-node", NodeKind.BEHAVIOR, "llm-prompt", properties);
    }

    private static NodeMessage message(Object payload) {
        return new NodeMessage(TENANT, UUID.randomUUID(), UUID.randomUUID(), "llm-prompt-node",
                payload, Map.of());
    }
}
