package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.programming.ProgramAdmission;
import ai.ravenroot.api.programming.ProgramRequest;
import ai.ravenroot.api.programming.ProgramRuntime;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.core.ai.AgentRuntimeRegistry;
import ai.ravenroot.core.ai.ModelProviderRegistry;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import org.junit.jupiter.api.Test;
import ai.ravenroot.api.catalog.NodeCatalogSource;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandardBehaviorFactoriesTest {
    private static final UUID EXECUTION_ID = UUID.randomUUID();

    @Test
    void exposesTheCanonicalCatalogAndRetainsUnknownFallbackDetection() {
        var registry = BehaviorRegistry.standard();

        assertTrue(registry.descriptors().stream().anyMatch(type -> type.behavior().equals("cel-transform")));
        assertTrue(registry.descriptors().stream().anyMatch(type -> type.behavior().equals("delay")));
        assertEquals(NodeCatalogSource.Origin.CORE, registry.catalogSources().get("delay").origin());
        assertEquals("", registry.catalogSources().get("delay").bundleId());
        assertFalse(registry.create(GraphNode.behavior("future", "not-implemented")).isPresent());
    }

    @Test
    void programDescriptorPublishesOnlyAuthorableContentAndManagedArtifactReference() {
        var descriptor = BehaviorRegistry.standard().descriptor("program").orElseThrow();
        var properties = descriptor.properties().stream().collect(java.util.stream.Collectors.toMap(
                ai.ravenroot.api.catalog.NodePropertyDescriptor::name, java.util.function.Function.identity()));

        assertTrue(properties.get("language").required());
        assertTrue(properties.get("source").required());
        assertFalse(properties.get("testPayload").required());
        assertEquals("test payload", properties.get("testPayload").defaultValue());
        assertFalse(properties.get("artifactId").required());
        assertFalse(properties.containsKey("sha256"));
        assertFalse(properties.containsKey("lifecycle"));
        assertFalse(properties.containsKey("state"));
    }

    @Test
    void delayPassesPayloadAndAttributesThrough() throws Exception {
        NodeMessage message = new NodeMessage(TestIdentities.TENANT_A, EXECUTION_ID, UUID.randomUUID(), "pause",
                Map.of("value", 42), Map.of("correlation", "abc"));

        NodeResult result = BehaviorRegistry.standard().create(node("delay", Map.of("durationMs", 0))).orElseThrow()
                .handle(message).toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertEquals(message.payload(), result.payload());
        assertEquals(message.attributes(), result.attributes());
        assertEquals("continue", result.outcome());
    }

    @Test
    void executesTemplateCelTransformAndCelDecisionFromNodeProperties() throws Exception {
        var registry = BehaviorRegistry.standard();

        NodeResult templated = invoke(registry, node("template", Map.of("template", "Hello {{payload}}")), "Ravenroot");
        NodeResult transformed = invoke(registry, node("cel-transform", Map.of("expression", "payload + '-CEL'")),
                templated.payload());
        NodeResult decision = invoke(registry, node("cel-decision", Map.of("expression", "payload == 'Hello Ravenroot-CEL'",
                "trueOutcome", "accepted", "falseOutcome", "rejected")), transformed.payload());

        assertEquals("Hello Ravenroot-CEL", transformed.payload());
        assertEquals("accepted", decision.outcome());
    }

    /**
     * The catalog the core ships names no model-consuming node (ADR 0029).
     *
     * <p>This replaces {@code llmAndAgentNodesDelegateThroughExplicitProviderBoundaries}, which
     * executed an {@code llm-prompt} and an {@code agent} node through registered stubs. It is
     * asserted as an absence, and an absence is the assertion that goes vacuous most easily, so it is
     * written against the same {@code descriptors()} list the positive cases above read and paired
     * with a roster check: if this registry ever went empty, or its behaviour names changed shape,
     * the second assertion fails rather than letting the first one pass by finding nothing.</p>
     *
     * <p>What the core kept is the <em>seam</em>, not the node: {@code BehaviorEnvironment} still
     * carries both registries and {@code BehaviorRegistry.registerFactory} is still public, so an
     * embedder composes such a node itself. That is the compatibility boundary, and
     * {@code BehaviorEnvironment} is deliberately untouched.</p>
     */
    @Test
    void theCoreCatalogShipsNoModelConsumingNode() {
        var registry = BehaviorRegistry.standard();
        var behaviors = registry.descriptors().stream()
                .map(ai.ravenroot.api.catalog.NodeTypeDescriptor::behavior)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));

        assertFalse(behaviors.contains("llm-prompt"),
                "llm-prompt left the core with the AI nodes; a distribution must not offer a node "
                        + "nobody installing it can arm");
        assertFalse(behaviors.contains("agent"), "same, for the agentic node");
        assertTrue(registry.descriptors().stream().noneMatch(
                        ai.ravenroot.api.catalog.NodeTypeDescriptor::agentic),
                "no shipped node type declares itself agentic any more");

        // ANTI-VACUITY: the two assertions above are absences, and an absence over an empty catalog
        // is not an assertion. This pins what the catalog does contain, so an accidental emptying
        // fails here instead of turning the three lines above green for the wrong reason.
        assertEquals(new java.util.TreeSet<>(java.util.Set.of("boundary-guard", "cel-decision", "cel-transform", "delay",
                "http-request", "human-task", "json-parse", "json-path", "log", "program", "template")), behaviors,
                "the core catalog changed shape; update this roster deliberately");
    }

    @Test
    void humanTaskCatalogIsBoundedAccessibleAndFailsClosedWithoutDurability() throws Exception {
        var registry = BehaviorRegistry.standard();
        var descriptor = registry.descriptor("human-task").orElseThrow();
        var properties = descriptor.properties().stream().collect(java.util.stream.Collectors.toMap(
                ai.ravenroot.api.catalog.NodePropertyDescriptor::name, java.util.function.Function.identity()));

        assertEquals("Human task", descriptor.displayName());
        assertTrue(descriptor.capabilities().contains("restart-safe"));
        assertTrue(properties.get("title").required());
        assertEquals(ai.ravenroot.api.catalog.NodePropertyType.TEXT,
                properties.get("description").type());
        assertEquals(java.util.List.of("SCALAR", "LIST", "MAP"),
                properties.get("responseKind").allowedValues());
        assertEquals(java.util.Set.of("resolved", "denied", "expired", "cancelled"),
                descriptor.resolveOutcomes(name -> properties.get(name).defaultValue()));

        var node = node("human-task", Map.of("title", "Review release"));
        var failure = org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                () -> registry.create(node).orElseThrow().handle(new NodeMessage(TestIdentities.TENANT_A,
                        EXECUTION_ID, UUID.randomUUID(), node.id(), null, Map.of()))
                        .toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().contains("requires durable human-task"));
    }

    @Test
    void programNodeOnlyExecutesAnArtifactAfterTheGovernedLifecycle() throws Exception {
        // Provenance verification is stated, not defaulted -- the registry refuses to
        // release any source without a verifier. This test is about the governed lifecycle, so it says
        // so explicitly rather than inheriting a permissive default that would not exist in production.
        var artifacts = new InMemoryArtifactRegistry(artifact -> { });
        // An artifact must record its owning tenant, or it is executable by nobody.
        // This is not the test being relaxed to fit a new gate -- it is the fixture supplying
        // ownership it never had, which is precisely the metadata AuthorizedRavenrootApplication
        // stamps on every artifact created through the reference monitor.
        GeneratedArtifact artifact = artifacts.create("javascript", "payload + '-program'",
                Map.of(AuthorizedRavenrootApplication.OWNER_TENANT_METADATA, TestIdentities.TENANT_A.tenantId()));
        artifact = artifacts.transition(artifact.id(), ArtifactState.GENERATED, ArtifactState.VALIDATED);
        artifact = artifacts.transition(artifact.id(), ArtifactState.VALIDATED, ArtifactState.TESTED);
        artifact = artifacts.transition(artifact.id(), ArtifactState.TESTED, ArtifactState.APPROVED);
        artifact = artifacts.transition(artifact.id(), ArtifactState.APPROVED, ArtifactState.ACTIVE);
        var runtime = new ProgramRuntime() {
            @Override
            public String id() {
                return "isolated-test-runtime";
            }

            @Override
            public java.util.concurrent.CompletionStage<Object> execute(ProgramAdmission admission,
                                                                         ProgramRequest request) {
                // Redeeming is what a conforming runtime does, and it is now the only way to reach the
                // artifact at all -- so this assertion is strictly stronger than it was: it proves the
                // admission redeems to an ACTIVE artifact, not merely that a snapshot said so.
                assertEquals(ArtifactState.ACTIVE, admission.redeem().state());
                return CompletableFuture.completedFuture(request.payload() + "-program");
            }
        };
        var registry = BehaviorRegistry.standard(environment(new ModelProviderRegistry(), new AgentRuntimeRegistry(),
                artifacts, runtime));

        NodeResult result = invoke(registry, node("program", Map.of(
                "language", artifact.language(), "source", artifact.source(), "artifactId", artifact.id())), "input");

        assertEquals("input-program", result.payload());
        assertEquals(artifact.sha256(), result.attributes().get("program.sha256"));
    }

    private static BehaviorEnvironment environment(ModelProviderRegistry models, AgentRuntimeRegistry agents,
                                                   InMemoryArtifactRegistry artifacts, ProgramRuntime runtime) {
        return new BehaviorEnvironment(models, agents, artifacts, runtime, ignored -> Optional.empty(),
                invocation -> new ToolDecision(ToolDecision.Disposition.ALLOW, "test", ""),
                OutboundHttpPolicy.disabled());
    }

    private static GraphNode node(String behavior, Map<String, Object> properties) {
        return new GraphNode(behavior + "-node", NodeKind.BEHAVIOR, behavior, properties);
    }

    private static NodeResult invoke(BehaviorRegistry registry, GraphNode node, Object payload) throws Exception {
        return registry.create(node).orElseThrow().handle(new NodeMessage(TestIdentities.TENANT_A, EXECUTION_ID, UUID.randomUUID(), node.id(), payload, Map.of()))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

}
