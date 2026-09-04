package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.provenance.SyntheticProvenance;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.NodePackageServiceRegistry;
import ai.ravenroot.core.runtime.NodePackages;
import ai.ravenroot.core.security.egress.EgressAddressGuard;
import ai.ravenroot.core.security.egress.ReservedNetworkPolicy;
import ai.ravenroot.core.security.nodepackage.ManagedNodePackageServices;
import ai.ravenroot.core.security.nodepackage.NodePackageEgressPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundle's end-to-end integration case, executed rather than described.
 *
 * <p>A real {@code llm-prompt} node from this bundle, activated through the trust boundary the
 * runtime actually uses ({@code NodePackages}), reaching a real socket through the managed HTTP
 * channel an operator grants ({@code ManagedNodePackageServices}), against an OpenAI-compatible
 * endpoint on loopback. Only the far end is a double. The three operator settings the bundle needs
 * are all present in this composition and each of them is exercised: the package service grant, the
 * origin allowlist inside it, and the reserved-network exception without which loopback is refused.</p>
 *
 * <p><b>What it does not establish:</b> that the shipped image would do this by itself. It would not,
 * because the bundle has to be compiled, installed, named in
 * {@code RAVENROOT_ENABLED_PLUGINS} and baked into an operator's own image. This test composes what
 * the server composes when those four acts have been performed, and no more.</p>
 */
class AiBundleEndToEndTest {

    private static final String PROFILE = "local";

    private ChatCompletionsDouble endpoint;
    private McpDouble.Endpoint mcp;
    private ReservedNetworkPolicy restore;

    @BeforeEach
    void start() throws Exception {
        endpoint = ChatCompletionsDouble.start();
        mcp = McpDouble.Endpoint.start(new McpDouble("corp", "search").returning("42 graphs"));
        restore = EgressAddressGuard.policy();
        // The third of the three operator settings, and the one that is easiest to forget: loopback
        // is denied by default and the shipped exception is keyed on the NAME "localhost", so the
        // literal address needs its own entry. RAVENROOT_EGRESS_RESERVED_EXCEPTIONS is the variable
        // that carries exactly this line in a deployment.
        EgressAddressGuard.configure(ReservedNetworkPolicy.fromCommaSeparatedExceptions(
                endpoint.host() + ":LOOPBACK,localhost:LOOPBACK"));
    }

    @AfterEach
    void stop() {
        EgressAddressGuard.configure(restore);
        endpoint.close();
        mcp.close();
    }

    @Test
    @DisplayName("a bundle llm-prompt node answers from a real local endpoint, over the managed channel")
    void aBundleNodeAnswersFromARealLocalEndpoint() throws Exception {
        endpoint.responds(200, ChatCompletionsDouble.completion("Ravenroot runs graphs."));

        NodeResult result = node(services()).handle(AiTestSupport.message("the product description"))
                .toCompletableFuture().get();

        // The payload rule, in one line: the node's payload is what the model wrote.
        assertEquals("continue", result.outcome());
        assertEquals("Ravenroot runs graphs.", result.payload());
        assertEquals(PROFILE, result.attributes().get("llm.provider"));
        assertEquals("qwen38", result.attributes().get("llm.model"));

        // The node rendered {{payload}} before the adapter ever saw the prompt, and the adapter sent
        // exactly that, as a single user turn.
        var sent = assertInstanceOf(PayloadValue.MapValue.class,
                PayloadJson.read(endpoint.observedBody().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        PayloadLimits.DEFAULTS));
        var turns = assertInstanceOf(PayloadValue.ListValue.class, sent.entries().get("messages"));
        var turn = assertInstanceOf(PayloadValue.MapValue.class, turns.values().get(0));
        assertEquals(PayloadValue.of("Summarise the product description"), turn.entries().get("content"));

        // Nothing was placed on the request: an unauthenticated local endpoint gets no credential,
        // and this bundle has no way to put one there itself.
        assertEquals("", endpoint.observedAuthorization());
    }

    @Test
    @DisplayName("the activated package's registered descriptor is the one the runtime marks on")
    void theRegisteredDescriptorIsGenerative() {
        BehaviorRegistry registry = NodePackages.registerAll(BehaviorRegistry.standard(),
                List.of(new AiNodePackage()),
                NodePackageServiceRegistry.builder().grant(AiNodePackage.ID, services()).build());

        NodeTypeDescriptor registered = registry.descriptor(LlmPromptNodeBehavior.BEHAVIOR).orElseThrow();
        // Registered through registerFactory with the author's real descriptor, which is the same
        // object GraphRunner reads when it decides whether to mark a completion.
        assertTrue(SyntheticProvenance.isGenerative(registered));
        Map<String, Object> marker = SyntheticProvenance.mint("ask", registered, "an answer").orElseThrow();
        assertEquals(SyntheticProvenance.CONTRACT, marker.get(SyntheticProvenance.CONTRACT_FIELD));
        assertEquals(LlmPromptNodeBehavior.BEHAVIOR, marker.get(SyntheticProvenance.BEHAVIOR_FIELD));
        assertEquals(List.of("ai"), marker.get(SyntheticProvenance.CAPABILITIES_FIELD));
    }

    @Test
    @DisplayName("a bundle agent node loops through a tool call and answers, over the managed channel")
    void aBundleAgentNodeLoopsOverTheManagedChannel() throws Exception {
        // Two turns from the same socket: the model asks for the built-in tool, reads what it
        // answered, and only then answers itself. One canned response could not tell those apart.
        endpoint.respondsInSequence(
                AiTestSupport.asksFor("call-1", LoadSkillTool.NAME),
                AiTestSupport.answers("Ravenroot runs graphs."));

        NodeResult result = agent(services()).handle(AiTestSupport.message("the product description"))
                .toCompletableFuture().get();

        assertEquals("continue", result.outcome());
        assertEquals("Ravenroot runs graphs.", result.payload());
        assertEquals(2, endpoint.calls());
        assertEquals(2, result.attributes().get("agent.turns"));
        assertEquals(1, result.attributes().get("agent.toolCalls"));
        assertEquals(PROFILE, result.attributes().get("agent.provider"));

        // The first request declared the tool; the second carried its result back. Both statements
        // are about what left the process, not about what the node believes it sent.
        var first = messagesOf(endpoint.observedBodies().get(0));
        assertEquals(3, first.size());
        assertEquals(PayloadValue.of("system"), roleOf(first.get(0)));
        var second = messagesOf(endpoint.observedBodies().get(1));
        assertEquals(5, second.size());
        assertEquals(PayloadValue.of("tool"), roleOf(second.get(4)));

        // Nothing was placed on either request: an unauthenticated local endpoint gets no credential,
        // and a loop does not become a way to acquire one on a later turn.
        assertEquals(List.of("", ""), endpoint.observedAuthorizations());
    }

    @Test
    @DisplayName("the activated package registers both node types, and the agent is generative too")
    void theActivatedPackageRegistersBothNodeTypes() {
        BehaviorRegistry registry = NodePackages.registerAll(BehaviorRegistry.standard(),
                List.of(new AiNodePackage()),
                NodePackageServiceRegistry.builder().grant(AiNodePackage.ID, services()).build());

        NodeTypeDescriptor registered = registry.descriptor(AgentNodeBehavior.BEHAVIOR).orElseThrow();
        assertTrue(SyntheticProvenance.isGenerative(registered));
        Map<String, Object> marker = SyntheticProvenance.mint("ask", registered, "an answer").orElseThrow();
        assertEquals(AgentNodeBehavior.BEHAVIOR, marker.get(SyntheticProvenance.BEHAVIOR_FIELD));
        // Both capabilities are generative, so both appear: removing either would unmark the node.
        assertEquals(List.of("agentic", "ai"), marker.get(SyntheticProvenance.CAPABILITIES_FIELD));
    }

    @Test
    @DisplayName("without the reserved-network exception the same call is refused, not silently retried")
    void withoutTheReservedExceptionTheCallIsRefused() {
        EgressAddressGuard.configure(ReservedNetworkPolicy.shippedDefault());
        endpoint.responds(200, ChatCompletionsDouble.completion("never reached"));

        LlmPromptException failure = failureOf(node(services()));

        assertEquals(LlmPromptException.Code.DESTINATION_REFUSED, failure.code());
        assertEquals(0, endpoint.calls());
    }

    @Test
    @DisplayName("an origin the grant does not name is refused even when the profile asks for it")
    void anUngrantedOriginIsRefused() {
        NodePackageServices narrowed = ManagedNodePackageServices.builder(AiNodePackage.ID,
                        NodePackageEgressPolicy.builder()
                                .allowOrigin("http", endpoint.host(), endpoint.port() + 1)
                                .allowHttpMethod("POST").allowRequestHeader("content-type")
                                .byteLimits(1024 * 1024, 1024 * 1024, 1024)
                                .concurrencyLimits(4, 2).maximumDeadline(Duration.ofSeconds(10)).build(),
                        (packageId, tenant, reference) -> Optional.empty())
                .grant(NodePackageCapability.OUTBOUND_HTTP)
                .grant(NodePackageCapability.AGENT_RESOURCES)
                .agentResources(AiTestSupport.unlimitedTestResources()).build();

        assertEquals(LlmPromptException.Code.DESTINATION_REFUSED, failureOf(node(narrowed)).code());
        assertEquals(0, endpoint.calls());
    }

    @Test
    @DisplayName("a bundle agent node calls a real MCP server's tool over the managed channel")
    void aBundleAgentNodeCallsARealMcpServer() throws Exception {
        // The model asks for the prefixed name; the loop resolves it to the server that owns it and
        // to that server's own name for the tool. Everything between the property and the two sockets
        // is the code an operator's image runs.
        endpoint.respondsInSequence(
                AiTestSupport.asksFor("call-1", "corp__search"),
                AiTestSupport.answers("There are 42."));

        NodeResult result = agentWithMcp(mcpServices()).handle(
                AiTestSupport.message("the product description")).toCompletableFuture().get();

        assertEquals("continue", result.outcome());
        assertEquals("There are 42.", result.payload());
        assertEquals(1, result.attributes().get("agent.toolCalls"));

        // The full handshake reached a real listener, in order, and the remote name -- not the
        // prefixed one -- crossed the wire.
        assertEquals(List.of("initialize", "notifications/initialized", "tools/list", "tools/call"),
                mcp.responder().receivedMethods());
        assertEquals(List.of("search"), mcp.responder().calledTools());

        // And what the server returned came back into the conversation as a tool message.
        var second = messagesOf(endpoint.observedBodies().get(1));
        assertEquals(PayloadValue.of("tool"), roleOf(second.get(4)));
        assertEquals(PayloadValue.of("42 graphs"),
                assertInstanceOf(PayloadValue.MapValue.class, second.get(4)).entries().get("content"));

        // Nothing was placed on any request: neither far end is authenticated here, and reaching a
        // second one does not become a way to acquire a credential.
        assertEquals(List.of("", ""), endpoint.observedAuthorizations());
    }

    @Test
    @DisplayName("a grant naming only content-type refuses the MCP call, and says so as a destination")
    void aGrantWithoutTheMcpHeadersRefuses() {
        // The operational fact this bundle's README has to carry: MCP needs `accept`,
        // `mcp-protocol-version` and `mcp-session-id` named in the package grant, and a grant written
        // for llm-prompt does not have them. The failure must point at the grant -- so
        // DESTINATION_REFUSED, not "the server is unreachable", which would send an operator to
        // inspect a server that is running perfectly.
        NodePackageServices narrow = ManagedNodePackageServices.builder(AiNodePackage.ID,
                        NodePackageEgressPolicy.builder()
                                .allowOrigin("http", endpoint.host(), endpoint.port())
                                .allowOrigin("http", mcp.host(), mcp.port())
                                .allowHttpMethod("POST").allowRequestHeader("content-type")
                                .byteLimits(1024 * 1024, 1024 * 1024, 1024)
                                .concurrencyLimits(4, 2).maximumDeadline(Duration.ofSeconds(10)).build(),
                        (packageId, tenant, reference) -> Optional.empty())
                .grant(NodePackageCapability.OUTBOUND_HTTP)
                .grant(NodePackageCapability.AGENT_RESOURCES)
                .agentResources(AiTestSupport.unlimitedTestResources()).build();

        ExecutionException raised = assertThrows(ExecutionException.class,
                () -> agentWithMcp(narrow).handle(AiTestSupport.message("x"))
                        .toCompletableFuture().get());
        Throwable cause = raised.getCause();
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertEquals(AgentException.Code.DESTINATION_REFUSED,
                assertInstanceOf(AgentException.class, cause).code());
        // The model was never reached: discovery failed before the first turn.
        assertEquals(0, endpoint.calls());
    }

    /** The grant an operator writes when the bundle's agent node declares MCP servers. */
    private NodePackageServices mcpServices() {
        var policy = NodePackageEgressPolicy.builder()
                .allowOrigin("http", endpoint.host(), endpoint.port())
                .allowOrigin("http", mcp.host(), mcp.port())
                .allowHttpMethod("POST")
                .byteLimits(1024 * 1024, 1024 * 1024, 1024)
                .concurrencyLimits(4, 2)
                .maximumDeadline(Duration.ofSeconds(10));
        McpDouble.REQUIRED_GRANT_HEADERS.forEach(policy::allowRequestHeader);
        return ManagedNodePackageServices.builder(AiNodePackage.ID, policy.build(),
                        (packageId, tenant, reference) -> Optional.empty())
                .grant(NodePackageCapability.OUTBOUND_HTTP)
                .grant(NodePackageCapability.TOOL_AUTHORIZATION)
                .grant(NodePackageCapability.AGENT_RESOURCES)
                .agentResources(AiTestSupport.unlimitedTestResources())
                .toolAuthorization(invocation -> new ToolDecision(
                        ToolDecision.Disposition.ALLOW, "test", ""), event -> { })
                .build();
    }

    private NodeAction agentWithMcp(NodePackageServices services) {
        var profile = new LlmProfile(PROFILE, java.net.URI.create(endpoint.endpoint()), "qwen38",
                Optional.empty(), 5_000, 1024 * 1024, 2);
        var server = new McpProfile("corp", java.net.URI.create(mcp.url()), Optional.empty(),
                5_000, 1024 * 1024, 2, java.util.Set.of("search"));
        return new AgentNodeBehavior(AiTestSupport.resolving(profile),
                AiTestSupport.resolvingMcp(server))
                .create(AiTestSupport.agentConfiguration(Map.of(
                        "provider", PROFILE, "instructions", "You are terse.",
                        "objective", "Summarise {{payload}}", "mcpServers", "corp")), services);
    }

    @Test
    @DisplayName("an oversize skill refuses through the registry the runtime itself builds nodes with")
    void anOversizeSkillRefusesThroughTheRealRegistry() {
        BehaviorRegistry registry = NodePackages.registerAll(BehaviorRegistry.standard(),
                List.of(new AiNodePackage()),
                NodePackageServiceRegistry.builder().grant(AiNodePackage.ID, services()).build());

        var properties = new java.util.HashMap<String, Object>(Map.of(
                "provider", PROFILE, "instructions", "be terse", "objective", "say hi",
                "skills.1.name", "runbook",
                "skills.1.description", "the operational runbook",
                "skills.1.instructions", "x".repeat(AgentSkill.MAX_INSTRUCTIONS_CHARS + 1)));

        // BehaviorRegistry#create is the call the runtime makes when it composes a graph, so this
        // exercises the same path a submission takes rather than a direct call to the behavior.
        RuntimeException refusal = assertThrows(RuntimeException.class,
                () -> registry.create(new GraphNode("ask", NodeKind.BEHAVIOR,
                        AgentNodeBehavior.BEHAVIOR, properties)));

        // The type, and not merely that it threw. The server's submission handler answers
        // IllegalArgumentException with INVALID_REQUEST; anything else matches no catch clause there
        // and the request is never answered through the product's error contract. A refusal nobody
        // can read is worse than the late failure it replaced, so this assertion is the one that
        // makes construction-time refusal correct rather than merely early.
        assertInstanceOf(IllegalArgumentException.class, refusal);
    }

    @Test
    @DisplayName("a well-formed skill builds a node through that same registry")
    void aWellFormedSkillBuildsThroughTheRealRegistry() {
        // The counterpart, so the test above proves the SKILL was refused rather than that this path
        // refuses every agent node with skill properties on it.
        BehaviorRegistry registry = NodePackages.registerAll(BehaviorRegistry.standard(),
                List.of(new AiNodePackage()),
                NodePackageServiceRegistry.builder().grant(AiNodePackage.ID, services()).build());

        var properties = new java.util.HashMap<String, Object>(Map.of(
                "provider", PROFILE, "instructions", "be terse", "objective", "say hi",
                "skills.1.name", "runbook",
                "skills.1.description", "the operational runbook",
                "skills.1.instructions", "step one, step two"));

        assertTrue(registry.create(new GraphNode("ask", NodeKind.BEHAVIOR,
                AgentNodeBehavior.BEHAVIOR, properties)).isPresent());
    }

    /** Exactly what an operator's grant variable composes: one origin, one method, one header. */
    private NodePackageServices services() {
        NodePackageEgressPolicy policy = NodePackageEgressPolicy.builder()
                .allowOrigin("http", endpoint.host(), endpoint.port())
                .allowHttpMethod("POST")
                .allowRequestHeader("content-type")
                .byteLimits(1024 * 1024, 1024 * 1024, 1024)
                .concurrencyLimits(4, 2)
                .maximumDeadline(Duration.ofSeconds(10))
                .build();
        return ManagedNodePackageServices.builder(AiNodePackage.ID, policy,
                        // No credential is resolvable: this deployment grants none, and the bundle
                        // never asks. The local test case is deliberately unauthenticated.
                        (packageId, tenant, reference) -> Optional.empty())
                .grant(NodePackageCapability.OUTBOUND_HTTP)
                .grant(NodePackageCapability.TOOL_AUTHORIZATION)
                .grant(NodePackageCapability.AGENT_RESOURCES)
                .agentResources(AiTestSupport.unlimitedTestResources())
                .toolAuthorization(invocation -> new ToolDecision(
                        ToolDecision.Disposition.ALLOW, "test", ""), event -> { })
                .build();
    }

    private NodeAction agent(NodePackageServices services) {
        var profile = new LlmProfile(PROFILE, java.net.URI.create(endpoint.endpoint()), "qwen38",
                Optional.empty(), 5_000, 1024 * 1024, 2);
        return new AgentNodeBehavior(AiTestSupport.resolving(profile))
                .create(AiTestSupport.agentConfiguration(Map.of(
                        "provider", PROFILE, "instructions", "You are terse.",
                        "objective", "Summarise {{payload}}")), services);
    }

    private static List<PayloadValue> messagesOf(String body) {
        var root = assertInstanceOf(PayloadValue.MapValue.class, PayloadJson.read(
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8), PayloadLimits.DEFAULTS));
        return assertInstanceOf(PayloadValue.ListValue.class, root.entries().get("messages")).values();
    }

    private static PayloadValue roleOf(PayloadValue message) {
        return assertInstanceOf(PayloadValue.MapValue.class, message).entries().get("role");
    }

    private NodeAction node(NodePackageServices services) {
        var profile = new LlmProfile(PROFILE, java.net.URI.create(endpoint.endpoint()), "qwen38",
                Optional.empty(), 5_000, 1024 * 1024, 2);
        return new LlmPromptNodeBehavior(AiTestSupport.resolving(profile))
                .create(AiTestSupport.configuration(Map.of(
                        "provider", PROFILE, "prompt", "Summarise {{payload}}")), services);
    }

    private static LlmPromptException failureOf(NodeAction action) {
        ExecutionException raised = assertThrows(ExecutionException.class,
                () -> action.handle(AiTestSupport.message("the product description"))
                        .toCompletableFuture().get());
        Throwable cause = raised.getCause();
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return assertInstanceOf(LlmPromptException.class, cause);
    }
}
