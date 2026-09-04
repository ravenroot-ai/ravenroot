package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.ToolCallAuthorization;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.security.ToolCallAuditEvent;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.api.security.ToolInvocation;
import ai.ravenroot.core.security.nodepackage.ManagedNodePackageServices;
import ai.ravenroot.core.security.nodepackage.NodePackageEgressPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MCP-tool behaviors, one test each, against two far ends.
 *
 * <p>Every case here runs a real {@link AgentNodeBehavior} with a real {@link McpSession} and a real
 * {@link McpProtocol}; what is doubled is the model endpoint and the MCP servers, routed by
 * destination so a test can say <em>which</em> server was called rather than only that one was. That
 * distinction is not cosmetic — it is how the collision behavior is verified.</p>
 */
class AgentMcpToolsTest {

    private static final String CHAT = "https://model.example.test/v1/chat/completions";
    private static final String ALPHA = "https://alpha.example.test/mcp";
    private static final String BETA = "https://beta.example.test/mcp";

    @Test
    @DisplayName("a node with two declared servers sees the tools of both, prefixed by server")
    void twoDeclaredServersBothContributeTools() throws Exception {
        var alpha = new McpDouble("alpha", "search");
        var beta = new McpDouble("beta", "lookup");
        var http = new AiTestSupport.RoutedHttp(CHAT)
                .chatting(AiTestSupport.answers("done"))
                .serving(ALPHA, alpha).serving(BETA, beta);

        NodeResult result = resultOf(agent(http, "alpha, beta",
                AiTestSupport.mcpProfile("alpha", ALPHA, "search"),
                AiTestSupport.mcpProfile("beta", BETA, "lookup")));

        assertEquals("done", result.payload());
        // Both servers were opened, and each was opened properly: a client that skipped the
        // initialized notification would work here and fail against a server that is strict about it.
        assertEquals(List.of("initialize", "notifications/initialized", "tools/list"),
                alpha.receivedMethods());
        assertEquals(List.of("initialize", "notifications/initialized", "tools/list"),
                beta.receivedMethods());
        assertEquals(List.of(LoadSkillTool.NAME, "alpha__search", "beta__lookup"),
                toolNamesOf(http.chatBodies().get(0)));
    }

    @Test
    @DisplayName("a tool call reaches the server that owns it, and its result re-enters the loop")
    void aToolCallReachesTheRightServerAndItsResultReEntersTheLoop() throws Exception {
        // BOTH servers expose a tool called "search". This is the case the exposed-name scheme exists
        // for: resolution is by construction, so the call
        // reaches beta because the model named beta -- not because beta happens to be declared second.
        var alpha = new McpDouble("alpha", "search").returning("alpha answered");
        var beta = new McpDouble("beta", "search").returning("beta answered");
        var http = new AiTestSupport.RoutedHttp(CHAT)
                .chatting(AiTestSupport.asksFor("call-1", "beta__search"),
                        AiTestSupport.answers("done"))
                .serving(ALPHA, alpha).serving(BETA, beta);

        NodeResult result = resultOf(agent(http, "alpha,beta",
                AiTestSupport.mcpProfile("alpha", ALPHA, "search"),
                AiTestSupport.mcpProfile("beta", BETA, "search")));

        assertEquals("done", result.payload());
        assertEquals(1, result.attributes().get("agent.toolCalls"));
        assertEquals(List.of("search"), beta.calledTools());
        // The other server was opened and then never called. Declaration order put alpha first, so an
        // implementation that resolved by scanning would have called exactly this one.
        assertEquals(List.of(), alpha.calledTools());
        // The remote name crossed the wire, not the exposed one: a server has never heard of the
        // prefix this bundle invented for the model's benefit.
        assertEquals(List.of("initialize", "notifications/initialized", "tools/list", "tools/call"),
                beta.receivedMethods());

        // And the result came back into the conversation as a tool message the model could read.
        List<PayloadValue> messages = messagesOf(http.chatBodies().get(1));
        assertEquals(5, messages.size());
        assertEquals(PayloadValue.of("tool"), roleOf(messages.get(4)));
        assertEquals(PayloadValue.of("beta answered"), contentOf(messages.get(4)));
    }

    @Test
    @DisplayName("tool audit distinguishes MCP success from every sanitized failure result")
    void toolAuditUsesTerminalMcpOutcomeInsteadOfNonEmptyModelText() throws Exception {
        var cases = List.of(
                new AuditCase(new McpDouble("alpha", "search").returning("found"),
                        ToolCallAuditEvent.Disposition.SUCCEEDED),
                new AuditCase(new McpDouble("alpha", "search")
                        .failingCallsWith(McpDouble.Mode.UNREACHABLE),
                        ToolCallAuditEvent.Disposition.FAILED),
                new AuditCase(new McpDouble("alpha", "search")
                        .failingCallsWith(McpDouble.Mode.SLOW),
                        ToolCallAuditEvent.Disposition.FAILED),
                new AuditCase(new McpDouble("alpha", "search")
                        .failingCallsWith(McpDouble.Mode.ERRORING),
                        ToolCallAuditEvent.Disposition.FAILED),
                new AuditCase(new McpDouble("alpha", "search").returningError("not found"),
                        ToolCallAuditEvent.Disposition.FAILED));

        for (AuditCase testCase : cases) {
            var events = new ArrayList<ToolCallAuditEvent>();
            var http = new AiTestSupport.RoutedHttp(CHAT)
                    .authorizing(authorizer(events, new AtomicReference<>()))
                    .chatting(AiTestSupport.asksFor("call-1", "alpha__search"),
                            AiTestSupport.answers("done"))
                    .serving(ALPHA, testCase.server());

            assertEquals("done", resultOf(agent(http, "alpha",
                    AiTestSupport.mcpProfile("alpha", ALPHA, "search"))).payload());
            assertAuditPair(events, testCase.terminal());
        }
    }

    @Test
    @DisplayName("blank model arguments are authorized and executed exactly once as canonical empty JSON")
    void blankArgumentsRemainCompatibleAcrossPolicyAuditAndEffect() throws Exception {
        var events = new ArrayList<ToolCallAuditEvent>();
        var evaluated = new AtomicReference<ToolInvocation>();
        var alpha = new McpDouble("alpha", "search").returning("found");
        var http = new AiTestSupport.RoutedHttp(CHAT)
                .authorizing(authorizer(events, evaluated))
                .chatting(AiTestSupport.asksFor("call-1", "alpha__search", "  "),
                        AiTestSupport.answers("done"))
                .serving(ALPHA, alpha);

        assertEquals("done", resultOf(agent(http, "alpha",
                AiTestSupport.mcpProfile("alpha", ALPHA, "search"))).payload());

        assertEquals(Map.of(), evaluated.get().arguments());
        assertThrows(UnsupportedOperationException.class,
                () -> evaluated.get().arguments().put("authority", "model"));
        assertEquals(List.of("search"), alpha.calledTools());
        var call = (PayloadValue.MapValue) PayloadJson.read(
                alpha.calledDocuments().get(0).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                PayloadLimits.DEFAULTS);
        var params = assertInstanceOf(PayloadValue.MapValue.class, call.entries().get("params"));
        var arguments = assertInstanceOf(PayloadValue.MapValue.class,
                params.entries().get("arguments"));
        assertEquals(Map.of(), arguments.entries());
        assertAuditPair(events, ToolCallAuditEvent.Disposition.SUCCEEDED);
    }

    @Test
    @DisplayName("post-effect accounting failure terminates without repeating the provider or tool")
    void postEffectAccountingFailureCannotLookRetryableToTheAgentLoop() {
        var completions = new AtomicInteger();
        var alpha = new McpDouble("alpha", "search").returning("effect-complete");
        var http = new AiTestSupport.RoutedHttp(CHAT)
                .authorizing((message, tool, arguments) -> new ToolCallAuthorization() {
                    private final java.util.UUID callId = java.util.UUID.randomUUID();
                    @Override public java.util.UUID callId() { return callId; }
                    @Override public Disposition disposition() { return Disposition.ALLOW; }
                    @Override public String argumentsDigest() { return "sha256:test"; }
                    @Override public byte[] canonicalArguments() { return "{}".getBytes(
                            java.nio.charset.StandardCharsets.UTF_8); }
                    @Override public void complete(Outcome outcome) {
                        completions.incrementAndGet();
                        throw new NodePackageServiceException(
                                NodePackageServiceException.Reason.EFFECT_OUTCOME_INDETERMINATE);
                    }
                })
                .chatting(AiTestSupport.asksFor("call-1", "alpha__search"),
                        AiTestSupport.answers("must-not-run"))
                .serving(ALPHA, alpha);

        ExecutionException raised = assertThrows(ExecutionException.class,
                () -> agent(http, "alpha", AiTestSupport.mcpProfile("alpha", ALPHA, "search"))
                        .handle(AiTestSupport.message("a payload")).toCompletableFuture().get());
        Throwable cause = raised.getCause();
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        NodePackageServiceException indeterminate = assertInstanceOf(NodePackageServiceException.class, cause);
        assertEquals(NodePackageServiceException.Reason.EFFECT_OUTCOME_INDETERMINATE,
                indeterminate.reason());
        assertEquals(List.of("search"), alpha.calledTools());
        assertEquals(1, http.chatCalls());
        assertEquals(1, completions.get());
    }

    @Test
    @DisplayName("a tool the server announces and the operator did not list is never offered at all")
    void anUnlistedToolIsNeverOffered() throws Exception {
        // The server announces one more tool than the operator permitted, exercising the authority
        // boundary directly rather than assuming it.
        var alpha = new McpDouble("alpha", "search", "delete_everything");
        var http = new AiTestSupport.RoutedHttp(CHAT)
                .chatting(AiTestSupport.answers("done"))
                .serving(ALPHA, alpha);

        resultOf(agent(http, "alpha", AiTestSupport.mcpProfile("alpha", ALPHA, "search")));

        List<String> offered = toolNamesOf(http.chatBodies().get(0));
        assertTrue(offered.contains("alpha__search"));
        // Not merely uncallable: unmentioned. A model cannot ask for what it was never told exists,
        // and the operator's list -- not the server's announcement -- is what decided that.
        assertFalse(offered.contains("alpha__delete_everything"));
    }

    @Test
    @DisplayName("calling an unlisted tool is a named refusal that does not end the loop")
    void callingAnUnlistedToolRefusesWithoutEndingTheLoop() throws Exception {
        var alpha = new McpDouble("alpha", "search", "delete_everything");
        var http = new AiTestSupport.RoutedHttp(CHAT)
                // The model invents the name. It was never offered it, which is precisely why this
                // path has to exist: being unmentioned does not make a name unwritable.
                .chatting(AiTestSupport.asksFor("call-1", "alpha__delete_everything"),
                        AiTestSupport.answers("understood, I will not"))
                .serving(ALPHA, alpha);

        NodeResult result = resultOf(agent(http, "alpha",
                AiTestSupport.mcpProfile("alpha", ALPHA, "search")));

        // The loop survived and the node answered: the refusal does not end the cycle.
        assertEquals("understood, I will not", result.payload());
        assertEquals(2, http.chatCalls());
        // Nothing was sent to the server. The refusal happened here, before any byte left.
        assertEquals(List.of(), alpha.calledTools());
        List<PayloadValue> messages = messagesOf(http.chatBodies().get(1));
        String refusal = ((PayloadValue.TextValue) contentOf(messages.get(4))).value();
        assertFalse(refusal.isEmpty());
        assertTrue(refusal.contains("available to you"), refusal);
    }

    @Test
    @DisplayName("a server that does not answer refuses the node, and never with a result")
    void aServerThatDoesNotAnswerRefusesTheNode() {
        var http = new AiTestSupport.RoutedHttp(CHAT)
                .chatting(AiTestSupport.answers("never reached"))
                .serving(ALPHA, new McpDouble("alpha", "search").in(McpDouble.Mode.UNREACHABLE));

        AgentException failure = failureOf(agent(http, "alpha",
                AiTestSupport.mcpProfile("alpha", ALPHA, "search")));

        assertEquals(AgentException.Code.MCP_SERVER_UNREACHABLE, failure.code());
        // The model was never reached: a node that cannot offer the tools its author declared does
        // not quietly answer without them.
        assertEquals(0, http.chatCalls());
    }

    @Test
    @DisplayName("a server that answers too slowly refuses with its own code, distinct from unreachable")
    void aSlowServerHasItsOwnCode() {
        var http = new AiTestSupport.RoutedHttp(CHAT)
                .chatting(AiTestSupport.answers("never reached"))
                .serving(ALPHA, new McpDouble("alpha", "search").in(McpDouble.Mode.SLOW));

        assertEquals(AgentException.Code.MCP_SERVER_TIMED_OUT,
                failureOf(agent(http, "alpha", AiTestSupport.mcpProfile("alpha", ALPHA, "search")))
                        .code());
    }

    @Test
    @DisplayName("a server that answers too much refuses with its own code, distinct from the other two")
    void anOversizedAnswerHasItsOwnCode() {
        var http = new AiTestSupport.RoutedHttp(CHAT)
                .chatting(AiTestSupport.answers("never reached"))
                .serving(ALPHA, new McpDouble("alpha", "search").in(McpDouble.Mode.TOO_LARGE));

        assertEquals(AgentException.Code.MCP_RESPONSE_TOO_LARGE,
                failureOf(agent(http, "alpha", AiTestSupport.mcpProfile("alpha", ALPHA, "search")))
                        .code());
    }

    @Test
    @DisplayName("the three server refusals are three codes, which is what makes them actionable")
    void theThreeServerRefusalsAreDistinct() {
        var codes = new ArrayList<AgentException.Code>();
        for (McpDouble.Mode mode : List.of(McpDouble.Mode.UNREACHABLE, McpDouble.Mode.SLOW,
                McpDouble.Mode.TOO_LARGE)) {
            var http = new AiTestSupport.RoutedHttp(CHAT)
                    .chatting(AiTestSupport.answers("never reached"))
                    .serving(ALPHA, new McpDouble("alpha", "search").in(mode));
            codes.add(failureOf(agent(http, "alpha",
                    AiTestSupport.mcpProfile("alpha", ALPHA, "search"))).code());
        }
        // Three distinct values, stated as a set rather than as three equalities: the claim is the
        // distinctness, and three equalities would still pass if two of them were the same code.
        assertEquals(3, Set.copyOf(codes).size());
    }

    @Test
    @DisplayName("a server failing mid-loop becomes a tool message, not a terminated traversal")
    void aServerFailingMidLoopDoesNotTerminateTheRun() throws Exception {
        // Healthy through discovery, broken at the call. The same condition as the three above, met
        // at the other side of the boundary, and it must produce the opposite outcome.
        var alpha = new McpDouble("alpha", "search")
                .failingCallsWith(McpDouble.Mode.UNREACHABLE);
        var http = new AiTestSupport.RoutedHttp(CHAT)
                .chatting(AiTestSupport.asksFor("call-1", "alpha__search"),
                        AiTestSupport.answers("I answered without it"))
                .serving(ALPHA, alpha);

        NodeResult result = resultOf(agent(http, "alpha",
                AiTestSupport.mcpProfile("alpha", ALPHA, "search")));

        assertEquals("I answered without it", result.payload());
        List<PayloadValue> messages = messagesOf(http.chatBodies().get(1));
        String told = ((PayloadValue.TextValue) contentOf(messages.get(4))).value();
        assertFalse(told.isEmpty());
        assertTrue(told.contains("did not answer"));
    }

    @Test
    @DisplayName("a server the deployment did not declare refuses with its own code and its name")
    void anUndeclaredServerRefusesByName() {
        var http = new AiTestSupport.RoutedHttp(CHAT).chatting(AiTestSupport.answers("never reached"));

        AgentException failure = failureOf(agent(http, "ghost"));

        assertEquals(AgentException.Code.MCP_PROFILE_UNKNOWN, failure.code());
        // The name, so the operator knows which variable to write; never the endpoint, which is not
        // theirs to learn from a graph.
        assertEquals("ghost", failure.hint());
        assertEquals(0, http.chatCalls());
    }

    @Test
    @DisplayName("two servers that would expose one name refuse, rather than one of them silently winning")
    void anExposedNameCollisionRefuses() {
        // Constructed so that "a" + "__" + "b__c" and "a__b" + "__" + "c" are the same string. The
        // outcome must not depend on which was declared first, which is the whole reason it refuses
        // instead of resolving.
        var http = new AiTestSupport.RoutedHttp(CHAT)
                .chatting(AiTestSupport.answers("never reached"))
                .serving(ALPHA, new McpDouble("a", "b__c"))
                .serving(BETA, new McpDouble("a__b", "c"));

        assertEquals(AgentException.Code.MCP_TOOL_NAME_COLLISION,
                failureOf(agent(http, "a,a__b",
                        AiTestSupport.mcpProfile("a", ALPHA, "b__c"),
                        AiTestSupport.mcpProfile("a__b", BETA, "c"))).code());
    }

    @Test
    @DisplayName("a server's tool description reaches the model as data and never as the system turn")
    void aServerDescriptionNeverEntersTheSystemTurn() throws Exception {
        var http = new AiTestSupport.RoutedHttp(CHAT)
                .chatting(AiTestSupport.answers("done"))
                .serving(ALPHA, new McpDouble("alpha", "search"));

        resultOf(agent(http, "alpha", AiTestSupport.mcpProfile("alpha", ALPHA, "search")));

        byte[] sent = http.chatBodies().get(0);
        // Present in the tool list: a tool nobody can describe is a tool nobody can use correctly.
        assertTrue(new String(sent, java.nio.charset.StandardCharsets.UTF_8)
                .contains("What search does."));
        // And absent from the operator-authored system turn: remote text is data, and data does not
        // become standing instructions.
        String system = ((PayloadValue.TextValue) contentOf(messagesOf(sent).get(0))).value();
        assertFalse(system.contains("What search does."));
    }

    @Test
    @DisplayName("admission returns to zero after a run that called tools, one that failed and one cancelled")
    void admissionReturnsToZeroOnEveryExit() throws Exception {
        var behavior = new AgentNodeBehavior(
                AiTestSupport.resolving(AiTestSupport.profile(CHAT)),
                AiTestSupport.resolvingMcp(AiTestSupport.mcpProfile("alpha", ALPHA, "search")));

        // 1. A run that called a tool and answered.
        var calling = new AiTestSupport.RoutedHttp(CHAT)
                .chatting(AiTestSupport.asksFor("call-1", "alpha__search"),
                        AiTestSupport.answers("done"))
                .serving(ALPHA, new McpDouble("alpha", "search"));
        assertEquals("done", resultOf(behavior.create(configuration("alpha"), calling)).payload());
        assertEquals(0, behavior.admissionEntries());
        assertEquals(0, behavior.mcpAdmissionEntries());

        // 2. A run that failed -- during discovery, which is where the MCP lease is most exposed:
        // it is taken before this failure and released after it, on a path the successful run does
        // not exercise.
        var failing = new AiTestSupport.RoutedHttp(CHAT)
                .chatting(AiTestSupport.answers("never reached"))
                .serving(ALPHA, new McpDouble("alpha", "search").in(McpDouble.Mode.UNREACHABLE));
        assertEquals(AgentException.Code.MCP_SERVER_UNREACHABLE,
                failureOf(behavior.create(configuration("alpha"), failing)).code());
        assertEquals(0, behavior.admissionEntries());
        assertEquals(0, behavior.mcpAdmissionEntries());

        // 3. A run the caller cancelled WHILE IT WAS IN FLIGHT. The endpoint stalls on purpose: a
        // future that has already completed cannot be cancelled, so cancelling one is a no-op that
        // passes this assertion without testing anything. Cancelling in flight makes the admission
        // leak observable.
        var cancelled = new AiTestSupport.RoutedHttp(CHAT)
                .chattingForever()
                .serving(ALPHA, new McpDouble("alpha", "search"));
        var stage = behavior.create(configuration("alpha"), cancelled)
                .handle(AiTestSupport.message("a payload")).toCompletableFuture();
        assertFalse(stage.isDone());
        assertEquals(1, behavior.mcpAdmissionEntries());
        stage.cancel(true);
        assertTrue(stage.isDone());
        assertEquals(0, behavior.admissionEntries());
        assertEquals(0, behavior.mcpAdmissionEntries());
    }

    @Test
    @DisplayName("a full MCP server admits no further run, and the refusal releases what it took")
    void afullServerRefusesAndReleases() throws Exception {
        var behavior = new AgentNodeBehavior(
                AiTestSupport.resolving(AiTestSupport.profile(CHAT)),
                AiTestSupport.resolvingMcp(new McpProfile("alpha", java.net.URI.create(ALPHA),
                        java.util.Optional.empty(), 5_000, 1024 * 1024, 1, Set.of("search"))));
        var resources = new AiTestSupport.TrackingAgentResources();
        var http = new AiTestSupport.RoutedHttp(CHAT)
                .resources(resources)
                .chattingForever()
                .serving(ALPHA, new McpDouble("alpha", "search"));

        // One run in flight, past discovery and stalled on the model, holding the single permit.
        var holding = behavior.create(configuration("alpha"), http)
                .handle(AiTestSupport.message("a payload")).toCompletableFuture();
        assertFalse(holding.isDone());
        assertEquals(1, behavior.mcpAdmissionEntries());

        AgentException failure = failureOf(behavior.create(configuration("alpha"), http));
        assertEquals(AgentException.Code.CAPACITY_UNAVAILABLE, failure.code());
        assertEquals(2, resources.admissions.get());
        assertEquals(1, resources.cancels.get(),
                "the run refused after admission must cancel its durable grant");

        // And the refusal released the model-profile lease it had already taken before reaching the
        // full server -- the unwind path that only this ordering exercises.
        holding.cancel(true);
        assertEquals(2, resources.cancels.get());
        assertEquals(0, behavior.mcpAdmissionEntries());
        assertEquals(0, behavior.admissionEntries());
    }

    @Test
    @DisplayName("declaring MCP servers does not make this bundle ask to resolve a credential")
    void mcpDoesNotAddACredentialCapability() {
        // The binding is a name the runtime resolves; the bundle never holds a secret and therefore
        // has no path that could return one. Adding CREDENTIAL_RESOLUTION here would replace that
        // property with a promise.
        assertEquals(Set.of(NodePackageCapability.OUTBOUND_HTTP,
                        NodePackageCapability.TOOL_AUTHORIZATION,
                        NodePackageCapability.AGENT_RESOURCES),
                new AgentNodeBehavior().requiredServices());
        assertTrue(new AgentNodeBehavior().descriptor().properties().stream()
                .anyMatch(property -> "mcpServers".equals(property.name())));
    }

    @Test
    @DisplayName("no MCP server declared is the previous behaviour exactly, with no extra exchange")
    void noDeclaredServerChangesNothing() throws Exception {
        var http = new AiTestSupport.RoutedHttp(CHAT).chatting(AiTestSupport.answers("done"));

        NodeResult result = resultOf(agent(http, ""));

        assertEquals("done", result.payload());
        assertEquals(List.of(LoadSkillTool.NAME), toolNamesOf(http.chatBodies().get(0)));
    }

    @Test
    @DisplayName("a stateful server's session handle is carried on every request, tools/call included")
    void theSessionHandleSurvivesTheHandshake() throws Exception {
        // The regression this pins: the handshake used to be performed on one object and the
        // catalogue returned on a NEW one, which reset the session handle and the id counter. Against
        // a stateless server nothing showed; against a stateful one every tools/call arrived without
        // a session and was refused, and only inside the loop, where it degrades into a tool message
        // telling the model not to retry.
        var alpha = new McpDouble("alpha", "search").stateful().returning("found it");
        var http = new AiTestSupport.RoutedHttp(CHAT)
                .chatting(AiTestSupport.asksFor("call-1", "alpha__search"),
                        AiTestSupport.answers("done"))
                .serving(ALPHA, alpha);

        NodeResult result = resultOf(agent(http, "alpha",
                AiTestSupport.mcpProfile("alpha", ALPHA, "search")));

        assertEquals("done", result.payload());
        // initialize carries none because none has been issued yet; every later request carries it,
        // and the last of the four IS the tool call.
        assertEquals(List.of("", McpDouble.SESSION_ID, McpDouble.SESSION_ID, McpDouble.SESSION_ID),
                alpha.observedSessionIds());
        assertEquals(List.of("search"), alpha.calledTools());
        // And the server never had to answer 404, which is the observation that does not depend on
        // reading headers at all.
        List<PayloadValue> messages = messagesOf(http.chatBodies().get(1));
        assertEquals(PayloadValue.of("found it"), contentOf(messages.get(4)));
    }

    @Test
    @DisplayName("a cancelled run stops: no further tool call, no further turn, even once the endpoint answers")
    void aCancelledRunStopsDoingWork() throws Exception {
        var alpha = new McpDouble("alpha", "search");
        var http = new AiTestSupport.RoutedHttp(CHAT)
                .chattingForever()
                .serving(ALPHA, alpha);
        var behavior = new AgentNodeBehavior(
                AiTestSupport.resolving(AiTestSupport.profile(CHAT)),
                AiTestSupport.resolvingMcp(AiTestSupport.mcpProfile("alpha", ALPHA, "search")));

        var stage = behavior.create(configuration("alpha"), http)
                .handle(AiTestSupport.message("a payload")).toCompletableFuture();
        assertFalse(stage.isDone());

        // A cancellation that worked must say so. Reporting false on a successful cancel tells a
        // caller to keep waiting for a run that has stopped.
        assertTrue(stage.cancel(true));
        assertEquals(1, http.chatCalls());
        assertEquals(List.of(Boolean.TRUE), http.cancelled());
        assertEquals(0, behavior.admissionEntries());
        assertEquals(0, behavior.mcpAdmissionEntries());

        // Now let the endpoint answer, as a slow endpoint eventually does, and with a turn that asks
        // for a tool -- the worst case, because acting on it would be a remote side effect after the
        // caller said stop.
        http.releaseChat(AiTestSupport.asksFor("call-1", "alpha__search"));

        assertEquals(1, http.chatCalls());
        assertEquals(List.of(), alpha.calledTools());
        assertEquals(0, behavior.admissionEntries());
        assertEquals(0, behavior.mcpAdmissionEntries());
    }

    @Test
    @DisplayName("a cancelled run stops even when the call cannot be cancelled and the answer arrives")
    void aCancelledRunStopsEvenWhenTheCallCannotBeCancelled() {
        // Cancelling the in-flight call is the easy half and is not always available: a response
        // already on its way is delivered whatever anyone asks. What must hold then is that the
        // answer does not restart the loop -- because the next thing the loop would do is call a
        // tool, which is a remote side effect after the caller said stop.
        var alpha = new McpDouble("alpha", "search");
        var http = new AiTestSupport.RoutedHttp(CHAT)
                .chattingForeverAndRefusingCancellation()
                .serving(ALPHA, alpha);
        var behavior = new AgentNodeBehavior(
                AiTestSupport.resolving(AiTestSupport.profile(CHAT)),
                AiTestSupport.resolvingMcp(AiTestSupport.mcpProfile("alpha", ALPHA, "search")));

        var stage = behavior.create(configuration("alpha"), http)
                .handle(AiTestSupport.message("a payload")).toCompletableFuture();
        stage.cancel(true);
        http.releaseChat(AiTestSupport.asksFor("call-1", "alpha__search"));

        assertEquals(List.of(), alpha.calledTools());
        assertEquals(1, http.chatCalls());
        assertEquals(0, behavior.admissionEntries());
        assertEquals(0, behavior.mcpAdmissionEntries());
    }

    @Test
    @DisplayName("a cancel that throws still gives the admission back, in both registries")
    void aThrowingCancelStillReleasesTheAdmission() {
        // The failure this pins is not a lost instant, it is a lost key. Admission.Gate is
        // reference-counted and leaves the map only at zero, so a release skipped once means this
        // (tenant, profile) pair -- and this (tenant, server) pair -- carry a permanent phantom
        // holder for the life of the process, and the operator's maxConcurrency is one lower forever.
        var http = new AiTestSupport.RoutedHttp(CHAT)
                .chattingForeverAndFailingCancellation()
                .serving(ALPHA, new McpDouble("alpha", "search"));
        var resources = new AiTestSupport.TrackingAgentResources().failingCancellation(
                new IllegalStateException("resource cleanup failed"));
        http.resources(resources);
        var behavior = new AgentNodeBehavior(
                AiTestSupport.resolving(AiTestSupport.profile(CHAT)),
                AiTestSupport.resolvingMcp(AiTestSupport.mcpProfile("alpha", ALPHA, "search")));

        var stage = behavior.create(configuration("alpha"), http)
                .handle(AiTestSupport.message("a payload")).toCompletableFuture();
        assertEquals(1, behavior.mcpAdmissionEntries());

        stage.cancel(true);

        // Both registries are separate objects, so covering only the one the loop touches would leave
        // the other holding.
        assertEquals(0, behavior.admissionEntries());
        assertEquals(0, behavior.mcpAdmissionEntries());
        assertEquals(1, resources.cancels.get(),
                "a throwing transport cancellation must not skip durable attempt cleanup");

        // And a second run is admitted, which is the property an operator actually has: the first
        // assertion says the counter reads zero, this one says the capacity is really back.
        var second = behavior.create(configuration("alpha"), http)
                .handle(AiTestSupport.message("a payload")).toCompletableFuture();
        second.cancel(true);
        assertEquals(0, behavior.mcpAdmissionEntries());
    }

    @Test
    @DisplayName("skills and MCP servers on one node: the skill memory survives tool discovery")
    void skillsAndMcpServersCoexistOnOneNode() throws Exception {
        // This combination exists only when both capabilities are present: skills reach the system
        // turn, MCP tools reach the tool list, and the loop
        // has to serve both from one rebuilt tool list.
        //
        // What this test does NOT pin, said plainly because a green suite here is easy to over-read:
        // it does not prove that discovery re-uses the constructor's LoadSkillTool rather than
        // building a fresh one. Measured -- building a fresh one keeps this test green, because
        // discovery runs before the first model turn and the memory being discarded is always empty.
        // See the comment at that line for why the instance is re-used anyway.
        var alpha = new McpDouble("alpha", "search").returning("found it");
        var http = new AiTestSupport.RoutedHttp(CHAT)
                .chatting(AiTestSupport.asksForWith("call-1", LoadSkillTool.NAME,
                                "{\"name\":\"runbook\"}"),
                        AiTestSupport.asksForWith("call-2", LoadSkillTool.NAME,
                                "{\"name\":\"runbook\"}"),
                        AiTestSupport.answers("done"))
                .serving(ALPHA, alpha);

        var properties = new java.util.HashMap<String, Object>(Map.of(
                "provider", "local", "instructions", "be terse", "objective", "say hi",
                "mcpServers", "alpha",
                "skills.1.name", "runbook",
                "skills.1.description", "the operational runbook",
                "skills.1.instructions", "step one, step two"));
        var behavior = new AgentNodeBehavior(
                AiTestSupport.resolving(AiTestSupport.profile(CHAT)),
                AiTestSupport.resolvingMcp(AiTestSupport.mcpProfile("alpha", ALPHA, "search")));

        NodeResult result = resultOf(behavior.create(
                AiTestSupport.agentConfiguration(Map.copyOf(properties)), http));

        assertEquals("done", result.payload());
        // Both worlds are offered, and the built-in still comes first.
        assertEquals(List.of(LoadSkillTool.NAME, "alpha__search"),
                toolNamesOf(http.chatBodies().get(0)));
        // The skill's name and description reached the untrusted author turn; its body did not.
        String author = ((PayloadValue.TextValue) contentOf(messagesOf(http.chatBodies().get(0))
                .get(1))).value();
        assertTrue(author.contains("runbook"), author);
        assertFalse(author.contains("step one, step two"), author);

        // The first load hands the body over.
        String first = ((PayloadValue.TextValue) contentOf(messagesOf(http.chatBodies().get(1))
                .get(4))).value();
        assertEquals("step one, step two", first);
        // The second says "already loaded" instead of repeating it -- the duplicate-load rule holds on a node
        // that also declares an MCP server, which is the interaction worth pinning here.
        String second = ((PayloadValue.TextValue) contentOf(messagesOf(http.chatBodies().get(2))
                .get(6))).value();
        assertTrue(second.contains("already loaded"), second);
        assertFalse(second.contains("step one, step two"), second);
    }

    @Test
    @DisplayName("more servers than the node allows is refused, and not quietly trimmed")
    void tooManyDeclaredServersIsRefused() {
        var http = new AiTestSupport.RoutedHttp(CHAT).chatting(AiTestSupport.answers("never reached"));
        var declared = new ArrayList<String>();
        var profiles = new ArrayList<McpProfile>();
        for (int index = 0; index <= AgentNodeBehavior.MAX_MCP_SERVERS; index++) {
            String name = "srv" + index;
            declared.add(name);
            profiles.add(AiTestSupport.mcpProfile(name, ALPHA + index, "search"));
        }

        // The descriptor tells an author "at most eight" and the guide repeats it. Before this check
        // both were false: the ninth server was opened by an author who had been told it would not be.
        assertEquals(AgentException.Code.MCP_TOO_MANY_SERVERS,
                failureOf(agent(http, String.join(",", declared),
                        profiles.toArray(new McpProfile[0]))).code());
        assertEquals(0, http.chatCalls());
    }

    @Test
    @DisplayName("mid-loop, all three server failures become tool messages and none ends the run")
    void everyMidLoopServerFailureBecomesAToolMessage() throws Exception {
        // The discovery side has three distinct codes; the loop side has to be shown for all three
        // too, or "the loop survives" is a claim about one of them.
        for (McpDouble.Mode mode : List.of(McpDouble.Mode.UNREACHABLE, McpDouble.Mode.SLOW,
                McpDouble.Mode.TOO_LARGE)) {
            var alpha = new McpDouble("alpha", "search").failingCallsWith(mode);
            var http = new AiTestSupport.RoutedHttp(CHAT)
                    .chatting(AiTestSupport.asksFor("call-1", "alpha__search"),
                            AiTestSupport.answers("answered anyway"))
                    .serving(ALPHA, alpha);

            NodeResult result = resultOf(agent(http, "alpha",
                    AiTestSupport.mcpProfile("alpha", ALPHA, "search")));

            assertEquals("answered anyway", result.payload(), mode.name());
            List<PayloadValue> messages = messagesOf(http.chatBodies().get(1));
            assertEquals(PayloadValue.of("tool"), roleOf(messages.get(4)), mode.name());
            assertFalse(((PayloadValue.TextValue) contentOf(messages.get(4))).value().isEmpty(),
                    mode.name());
        }
    }

    private NodeAction agent(AiTestSupport.RoutedHttp http, String declared, McpProfile... servers) {
        return new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(CHAT)),
                AiTestSupport.resolvingMcp(servers))
                .create(configuration(declared), http);
    }

    private static ai.ravenroot.api.node.service.ToolCallAuthorizationService authorizer(
            List<ToolCallAuditEvent> events, AtomicReference<ToolInvocation> evaluated) {
        return ManagedNodePackageServices.builder("ai.ravenroot.extension.ai",
                        NodePackageEgressPolicy.builder().build(),
                        (packageId, tenant, reference) -> java.util.Optional.empty())
                .grant(NodePackageCapability.TOOL_AUTHORIZATION)
                .toolAuthorization(invocation -> {
                    evaluated.set(invocation);
                    return new ToolDecision(ToolDecision.Disposition.ALLOW, "allowed", "");
                }, events::add)
                .build().toolAuthorization();
    }

    private static void assertAuditPair(List<ToolCallAuditEvent> events,
                                        ToolCallAuditEvent.Disposition terminal) {
        assertEquals(List.of(ToolCallAuditEvent.Disposition.ATTEMPT, terminal),
                events.stream().map(ToolCallAuditEvent::disposition).toList());
        assertEquals(events.get(0).callId(), events.get(1).callId());
    }

    private record AuditCase(McpDouble server, ToolCallAuditEvent.Disposition terminal) {
    }

    private static ai.ravenroot.api.node.NodeConfiguration configuration(String mcpServers) {
        return AiTestSupport.agentConfiguration(Map.of(
                "provider", "local", "instructions", "be terse", "objective", "say hi",
                "mcpServers", mcpServers));
    }

    private static NodeResult resultOf(NodeAction action) throws Exception {
        return action.handle(AiTestSupport.message("a payload")).toCompletableFuture().get();
    }

    private static AgentException failureOf(NodeAction action) {
        ExecutionException raised = assertThrows(ExecutionException.class,
                () -> action.handle(AiTestSupport.message("a payload")).toCompletableFuture().get());
        Throwable cause = raised.getCause();
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return assertInstanceOf(AgentException.class, cause);
    }

    private static List<String> toolNamesOf(byte[] body) {
        var root = (PayloadValue.MapValue) PayloadJson.read(body, PayloadLimits.DEFAULTS);
        var declared = assertInstanceOf(PayloadValue.ListValue.class, root.entries().get("tools"));
        var names = new ArrayList<String>();
        for (PayloadValue entry : declared.values()) {
            var function = (PayloadValue.MapValue) ((PayloadValue.MapValue) entry)
                    .entries().get("function");
            names.add(((PayloadValue.TextValue) function.entries().get("name")).value());
        }
        return List.copyOf(names);
    }

    private static List<PayloadValue> messagesOf(byte[] body) {
        var root = (PayloadValue.MapValue) PayloadJson.read(body, PayloadLimits.DEFAULTS);
        return ((PayloadValue.ListValue) root.entries().get("messages")).values();
    }

    private static PayloadValue roleOf(PayloadValue message) {
        return ((PayloadValue.MapValue) message).entries().get("role");
    }

    private static PayloadValue contentOf(PayloadValue message) {
        return ((PayloadValue.MapValue) message).entries().get("content");
    }
}
