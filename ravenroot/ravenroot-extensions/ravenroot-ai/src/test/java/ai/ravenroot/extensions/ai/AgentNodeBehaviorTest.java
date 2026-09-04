package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.provenance.SyntheticProvenance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The loop, its budgets, and the rule that a refusal never becomes a result. */
class AgentNodeBehaviorTest {

    private static final String ENDPOINT = "https://model.example.test/v1/chat/completions";

    @Test
    @DisplayName("the descriptor declares both capabilities that make the runtime mark this output")
    void theDescriptorDeclaresTheGenerativeCapabilities() {
        var descriptor = new AgentNodeBehavior().descriptor();

        assertTrue(descriptor.capabilities().contains("ai"));
        assertTrue(descriptor.capabilities().contains("agentic"));
        // The runtime's own predicate, not a restatement of it: this is the call GraphRunner makes.
        assertTrue(SyntheticProvenance.isGenerative(descriptor));
        assertTrue(SyntheticProvenance.mint("ask", descriptor, "an answer").isPresent());
        // Unlike its sibling: this node's control style IS agentic, and the editor renders it so.
        assertTrue(descriptor.agentic());
        assertEquals("agent", descriptor.visualType());
        assertEquals(List.of("continue"),
                descriptor.outcomes().stream().map(outcome -> outcome.name()).toList());
    }

    @Test
    @DisplayName("the behavior requires managed HTTP and server-side tool authorization")
    void theBehaviorRequiresManagedBoundaries() {
        assertEquals(java.util.Set.of(NodePackageCapability.OUTBOUND_HTTP,
                        NodePackageCapability.TOOL_AUTHORIZATION,
                        NodePackageCapability.AGENT_RESOURCES),
                new AgentNodeBehavior().requiredServices());
    }

    @Test
    @DisplayName("a blank provider refuses when reached, and does so as a failed future")
    void aBlankProviderRefusesWhenReached() {
        // Not a throw from create(): a node whose adapter is merely unconfigured must still let the
        // graph construct, so the failure is attributable to the node rather than to the submission.
        NodeAction action = new AgentNodeBehavior(name -> Optional.empty())
                .create(configuration(Map.of("provider", "", "instructions", "be terse",
                        "objective", "say hi")), NodePackageServices.unavailable());

        assertEquals(AgentException.Code.PROFILE_UNKNOWN, failureOf(action).code());
    }

    @Test
    @DisplayName("an unknown profile refuses with the profile name and no NodeResult")
    void anUnknownProfileRefuses() {
        NodeAction action = new AgentNodeBehavior(name -> Optional.empty())
                .create(configuration(Map.of("provider", "missing", "instructions", "be terse",
                        "objective", "say hi")), NodePackageServices.unavailable());

        AgentException refusal = failureOf(action);
        assertEquals(AgentException.Code.PROFILE_UNKNOWN, refusal.code());
        assertEquals("missing", refusal.hint());
    }

    @Test
    @DisplayName("a model that answers on the first turn produces one call and the answer")
    void aSingleTurnAnswer() throws Exception {
        var http = new AiTestSupport.ScriptedHttp().then(AiTestSupport.answers("done"));
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        NodeResult result = resultOf(behavior.create(configuration(Map.of(
                "provider", "local", "instructions", "be terse", "objective", "say hi")), http));

        assertEquals(1, http.calls());
        assertEquals("continue", result.outcome());
        assertEquals("done", result.payload());
        assertEquals(1, result.attributes().get("agent.turns"));
        assertEquals(0, result.attributes().get("agent.toolCalls"));
        assertEquals(0, behavior.admissionEntries());
    }

    @Test
    @DisplayName("a provider usage breach fails before its generated answer can leave the node")
    void aProviderUsageBreachNeverReturnsTheOverBudgetAnswer() {
        var resources = new AiTestSupport.TrackingAgentResources().failingSettlement(
                new NodePackageServiceException(NodePackageServiceException.Reason.BUDGET_EXHAUSTED));
        var http = new AiTestSupport.ScriptedHttp().then(
                AiTestSupport.answersUsing("must-not-escape", 101, 20));
        http.resources(resources);
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        AgentException refusal = failureOf(behavior.create(configuration(Map.of(
                "provider", "local", "instructions", "be terse", "objective", "say hi")), http));

        assertEquals(AgentException.Code.TOKEN_BUDGET_EXHAUSTED, refusal.code());
        assertEquals(1, http.calls());
        assertEquals(0, resources.completes.get());
        assertEquals(1, resources.failedAttempts.get());
    }

    @Test
    @DisplayName("the exposed result waits for durable resource terminalization")
    void resultCompletionFollowsResourceCompletion() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var resources = new AiTestSupport.TrackingAgentResources().blockingCompletion(entered, release);
        var http = new AiTestSupport.ScriptedHttp().then(AiTestSupport.answers("done"));
        http.resources(resources);
        NodeAction action = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)))
                .create(configuration(Map.of("provider", "local", "instructions", "be terse",
                        "objective", "say hi")), http);

        CompletableFuture<NodeResult> observed = CompletableFuture.supplyAsync(
                        () -> action.handle(AiTestSupport.message("a payload")))
                .thenCompose(java.util.function.Function.identity()).toCompletableFuture();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertFalse(observed.isDone(),
                "the runner must not observe node completion before durable resources terminate");
        release.countDown();
        assertEquals("done", observed.get(5, TimeUnit.SECONDS).payload());
        assertEquals(1, resources.completes.get());
    }

    @Test
    @DisplayName("the durable permit tightens max tokens and HTTP timeout before model egress")
    void durablePermitBoundsTheActualOutboundRequest() throws Exception {
        var resources = new AiTestSupport.TrackingAgentResources(7, Duration.ofMillis(123));
        var http = new AiTestSupport.ScriptedHttp()
                .resources(resources)
                .then(AiTestSupport.answers("done"));
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        resultOf(behavior.create(configuration(Map.of(
                "provider", "local", "instructions", "be terse", "objective", "say hi",
                "maxTokens", 64L, "timeoutMs", 5_000)), http));

        var request = (PayloadValue.MapValue) PayloadJson.read(http.bodies().getFirst(), PayloadLimits.DEFAULTS);
        assertEquals(PayloadValue.of(7L), request.entries().get("max_tokens"));
        assertEquals(Duration.ofMillis(123), http.deadlines().getFirst());
    }

    @Test
    @DisplayName("local request preparation failure releases the held permit without model egress")
    void localPreparationFailureReleasesBeforeDispatch() {
        var resources = new AiTestSupport.TrackingAgentResources(7, Duration.ZERO);
        var http = new AiTestSupport.ScriptedHttp()
                .resources(resources)
                .then(AiTestSupport.answers("must not be sent"));
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        failureOf(behavior.create(configuration(Map.of(
                "provider", "local", "instructions", "be terse", "objective", "say hi")), http));

        assertEquals(0, http.calls());
        assertEquals(0, resources.modelDispatches.get());
        assertEquals(1, resources.modelReleases.get());
        assertEquals(0, resources.modelIndeterminate.get());
    }

    @Test
    @DisplayName("a tool call is executed and its result comes back as a tool message on the next turn")
    void aToolCallRoundTrips() throws Exception {
        var http = new AiTestSupport.ScriptedHttp()
                .then(AiTestSupport.asksFor("call-1", LoadSkillTool.NAME))
                .then(AiTestSupport.answers("done"));
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        NodeResult result = resultOf(behavior.create(configuration(Map.of(
                "provider", "local", "instructions", "be terse", "objective", "say hi")), http));

        assertEquals(2, http.calls());
        assertEquals("done", result.payload());
        assertEquals(2, result.attributes().get("agent.turns"));
        assertEquals(1, result.attributes().get("agent.toolCalls"));

        // The second request carries policy, author instructions, objective, assistant, and tool.
        // loop that dropped the tool result would still answer, and would answer without ever having
        // shown the model what it asked for.
        List<PayloadValue> messages = messagesOf(http.bodies().get(1));
        assertEquals(5, messages.size());
        assertEquals(PayloadValue.of("tool"), roleOf(messages.get(4)));
        assertEquals(PayloadValue.of(LoadSkillTool.NO_SKILLS), contentOf(messages.get(4)));
        assertEquals(0, behavior.admissionEntries());
    }

    @Test
    @DisplayName("a tool the node does not have is answered, not fatal: the model can correct itself")
    void anUnknownToolIsAnsweredRatherThanFatal() throws Exception {
        var http = new AiTestSupport.ScriptedHttp()
                .then(AiTestSupport.asksFor("call-1", "delete_everything"))
                .then(AiTestSupport.answers("sorry, I made that up"));
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        NodeResult result = resultOf(behavior.create(configuration(Map.of(
                "provider", "local", "instructions", "be terse", "objective", "say hi")), http));

        assertEquals("sorry, I made that up", result.payload());
        List<PayloadValue> messages = messagesOf(http.bodies().get(1));
        assertEquals(PayloadValue.of("tool"), roleOf(messages.get(4)));
        // Whatever the refusal says, it must not be empty: an empty tool message reads to a model as
        // a call that succeeded and returned nothing.
        assertFalse(((PayloadValue.TextValue) contentOf(messages.get(4))).value().isEmpty());
    }

    @Test
    @DisplayName("a model that never stops asking for tools exhausts its turns and is refused by name")
    void anEndlessLoopExhaustsTheTurnBudget() {
        var http = new AiTestSupport.ScriptedHttp()
                .thenForever(AiTestSupport.asksFor("call-1", LoadSkillTool.NAME));
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        AgentException refusal = failureOf(behavior.create(configuration(Map.of(
                "provider", "local", "instructions", "be terse", "objective", "say hi",
                "maxTurns", "3")), http));

        // The named code, and not DEADLINE_EXCEEDED: a model looping inside a generous deadline and a
        // model that is merely slow need different answers from an operator.
        assertEquals(AgentException.Code.TURN_BUDGET_EXHAUSTED, refusal.code());
        assertEquals(3, http.calls());
        assertEquals(0, behavior.admissionEntries());
    }

    @Test
    @DisplayName("the default turn bound applies when the author names none")
    void theDefaultTurnBoundApplies() {
        var http = new AiTestSupport.ScriptedHttp()
                .thenForever(AiTestSupport.asksFor("call-1", LoadSkillTool.NAME));
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        failureOf(behavior.create(configuration(Map.of(
                "provider", "local", "instructions", "be terse", "objective", "say hi")), http));

        assertEquals(AgentNodeBehavior.DEFAULT_MAX_TURNS, http.calls());
    }

    @Test
    @DisplayName("an author asking for more turns than the ceiling gets the ceiling")
    void theTurnCeilingCannotBeRaisedByAGraph() {
        var http = new AiTestSupport.ScriptedHttp()
                .thenForever(AiTestSupport.asksFor("call-1", LoadSkillTool.NAME));
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        failureOf(behavior.create(configuration(Map.of(
                "provider", "local", "instructions", "be terse", "objective", "say hi",
                "maxTurns", "100000")), http));

        assertEquals(AgentNodeBehavior.MAX_TURNS_CEILING, http.calls());
    }

    @Test
    @DisplayName("cumulative reported tokens past the ceiling refuse with their own code")
    void theTokenBudgetRefusesWithItsOwnCode() {
        var http = new AiTestSupport.ScriptedHttp()
                .then(AiTestSupport.answersUsing("done", 90, 20));
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        AgentException refusal = failureOf(behavior.create(configuration(Map.of(
                "provider", "local", "instructions", "be terse", "objective", "say hi",
                "maxTotalTokens", "100")), http));

        // 90 + 20 is over 100, and the turn had already answered: the ceiling is checked before the
        // answer is accepted, or a run could pass its budget and still succeed.
        assertEquals(AgentException.Code.TOKEN_BUDGET_EXHAUSTED, refusal.code());
        assertEquals(0, behavior.admissionEntries());
    }

    @Test
    @DisplayName("with no token ceiling a run that reports usage still answers")
    void theTokenBudgetIsUnboundedByDefault() throws Exception {
        var http = new AiTestSupport.ScriptedHttp()
                .then(AiTestSupport.answersUsing("done", 9000, 9000));
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        NodeResult result = resultOf(behavior.create(configuration(Map.of(
                "provider", "local", "instructions", "be terse", "objective", "say hi")), http));

        assertEquals("done", result.payload());
        assertEquals(18000L, result.attributes().get("agent.totalTokens"));
    }

    @Test
    @DisplayName("the deadline covers the whole run, so each turn asks for what is left of it")
    void theDeadlineCoversTheWholeRun() throws Exception {
        var http = new AiTestSupport.ScriptedHttp()
                .then(AiTestSupport.asksFor("call-1", LoadSkillTool.NAME))
                .then(AiTestSupport.answers("done"));
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        resultOf(behavior.create(configuration(Map.of(
                "provider", "local", "instructions", "be terse", "objective", "say hi")), http));

        // A per-turn deadline would hand the same number to both calls, and n turns under a per-turn
        // ceiling have no ceiling at all. The second must ask for strictly less than the first.
        assertTrue(http.deadlines().get(1).compareTo(http.deadlines().get(0)) <= 0);
        assertTrue(http.deadlines().get(0).toMillis() <= 5_000);
    }

    @Test
    @DisplayName("a deadline already spent refuses before any byte leaves")
    void anExpiredDeadlineRefusesBeforeCalling() {
        var http = new AiTestSupport.ScriptedHttp().then(AiTestSupport.answers("done"));
        var profile = new LlmProfile("local", java.net.URI.create(ENDPOINT), "qwen38",
                Optional.empty(), 1, 1024 * 1024, 2);
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(profile));

        // Deterministic, and the mechanism is worth naming because it is not the obvious one. The
        // clock starts inside handle(), not at create(), so no delay before the call would matter.
        // What makes this certain is that remainingMillis() TRUNCATES: a one-millisecond budget minus
        // the work of rendering two templates leaves strictly less than a millisecond, which is zero
        // whole milliseconds, and a run with none left refuses before any byte leaves.
        AgentException refusal = failureOf(behavior.create(configuration(Map.of(
                "provider", "local", "instructions", "be terse", "objective", "say hi")), http));
        assertEquals(AgentException.Code.DEADLINE_EXCEEDED, refusal.code());
        assertEquals(0, http.calls());
        assertEquals(0, behavior.admissionEntries());
    }

    @Test
    @DisplayName("operator policy and author instructions are separate protocol turns")
    void theOperatorPreambleIsStructurallySeparate() throws Exception {
        var http = new AiTestSupport.ScriptedHttp().then(AiTestSupport.answers("done"));
        var profile = new LlmProfile("local", java.net.URI.create(ENDPOINT), "qwen38",
                Optional.empty(), 5_000, 1024 * 1024, 2, "Operator rules.");
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(profile));

        resultOf(behavior.create(configuration(Map.of(
                "provider", "local", "instructions", "I am the author", "objective", "say hi")), http));

        String system = ((PayloadValue.TextValue) contentOf(messagesOf(http.bodies().get(0)).get(0)))
                .value();
        String author = textOf(contentOf(messagesOf(http.bodies().get(0)).get(1)));
        assertTrue(system.startsWith(AgentTurn.BASE_POLICY));
        assertTrue(system.endsWith("Operator rules."));
        assertFalse(system.contains("I am the author"));
        assertEquals("I am the author", author);
    }

    @Test
    @DisplayName("instructions and objective render the incoming payload, and an unknown token refuses")
    void templatesRenderAndRefuseAsThisNodesOwnFailure() throws Exception {
        var http = new AiTestSupport.ScriptedHttp().then(AiTestSupport.answers("done"));
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        resultOf(behavior.create(configuration(Map.of("provider", "local",
                "instructions", "be terse", "objective", "summarise {{payload}}")), http));
        assertTrue(((PayloadValue.TextValue) contentOf(messagesOf(http.bodies().get(0)).get(2)))
                .value().contains("a payload"));

        // PromptTemplate is shared with llm-prompt and speaks that node's vocabulary. A reader of an
        // agent failure must never have to know the other node exists, so it is translated.
        var resources = new AiTestSupport.TrackingAgentResources();
        http.resources(resources);
        AgentException refusal = failureOf(behavior.create(configuration(Map.of("provider", "local",
                "instructions", "be terse", "objective", "summarise {{payload.missing}}")), http));
        assertEquals(AgentException.Code.TEMPLATE_UNRENDERABLE, refusal.code());
        assertEquals(1, resources.admissions.get());
        assertEquals(1, resources.cancels.get(),
                "a constructor-time refusal must cancel its already-admitted durable grant");
    }

    @Test
    @DisplayName("a deny-only services view refuses at invocation rather than answering")
    void aDenyOnlyServicesViewRefuses() {
        // Not the operator-facing case for this bundle: requiredServices() declares outbound-http, so
        // a deployment with no grant is refused at activation. This covers the views that still reach
        // a node -- an SDK /1 construction path, or a composition that granted something else.
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        assertEquals(AgentException.Code.CAPACITY_UNAVAILABLE,
                failureOf(behavior.create(configuration(Map.of("provider", "local",
                        "instructions", "be terse", "objective", "say hi")),
                        NodePackageServices.unavailable())).code());
    }

    @Test
    @DisplayName("a non-2xx status refuses without reading the endpoint's error document")
    void aRejectedStatusDoesNotCarryTheBody() {
        var http = new AiTestSupport.ScriptedHttp().thenStatus(500, "{\"error\":\"say hi leaked\"}");
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        AgentException refusal = failureOf(behavior.create(configuration(Map.of("provider", "local",
                "instructions", "be terse", "objective", "say hi")), http));

        assertEquals(AgentException.Code.ENDPOINT_REJECTED, refusal.code());
        assertEquals("", refusal.hint());
        assertFalse(refusal.getMessage().contains("say hi"));
    }

    @Test
    @DisplayName("the admission entry is released when the run fails between two turns")
    void admissionIsReleasedOnAMidLoopFailure() {
        var http = new AiTestSupport.ScriptedHttp()
                .then(AiTestSupport.asksFor("call-1", LoadSkillTool.NAME))
                .thenStatus(503, "{}");
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        // The lease is held across an unknown number of turns here, unlike its sibling, so a path
        // that forgets to release leaks for the whole run instead of for one call.
        failureOf(behavior.create(configuration(Map.of("provider", "local",
                "instructions", "be terse", "objective", "say hi")), http));

        assertEquals(0, behavior.admissionEntries());
    }

    @Test
    @DisplayName("the admission entry is released when the transport fails between two turns")
    void admissionIsReleasedOnAMidLoopTransportFailure() {
        var http = new AiTestSupport.ScriptedHttp()
                .then(AiTestSupport.asksFor("call-1", LoadSkillTool.NAME))
                .thenTransportFailure();
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        // Distinct from the 5xx case above, and not a duplicate of it: a rejection arrives over a
        // transport that worked and travels a different branch from a connection that did not.
        AgentException refusal = failureOf(behavior.create(configuration(Map.of("provider", "local",
                "instructions", "be terse", "objective", "say hi")), http));

        assertEquals(AgentException.Code.TRANSPORT_UNAVAILABLE, refusal.code());
        assertEquals(0, behavior.admissionEntries());
    }

    @Test
    @DisplayName("a turn whose tool calls are all unusable refuses; it does not answer with the preamble")
    void aTurnOfUnusableToolCallsDoesNotBecomeTheAnswer() {
        // The failure this pins is the worst one available to a loop: a turn that asks for tools and
        // also carries a planning preamble, whose call entries are unusable, would otherwise be
        // classified as answered -- ending the run on turn one and returning the preamble as the
        // agent's result, marked as model-generated, which it is, and presented as the answer, which
        // it is not. Both shapes below are ones real OpenAI-compatible servers emit.
        var http = new AiTestSupport.ScriptedHttp().then(
                "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":"
                        + "{\"content\":\"Let me look that up for you.\",\"tool_calls\":"
                        + "[{\"type\":\"function\",\"function\":{\"name\":\"load_skill\"}}]}}]}");
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        AgentException refusal = failureOf(behavior.create(configuration(Map.of("provider", "local",
                "instructions", "be terse", "objective", "say hi")), http));

        assertEquals(AgentException.Code.TOOL_CALL_UNREADABLE, refusal.code());
        assertEquals(0, behavior.admissionEntries());
    }

    @Test
    @DisplayName("three declared skills reach the model as three names and descriptions, and no body")
    void threeSkillsReachTheModelWithoutTheirBodies() throws Exception {
        var http = new AiTestSupport.ScriptedHttp().then(AiTestSupport.answers("done"));
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        resultOf(behavior.create(configuration(AgentSkillTest.withSkills(3)), http));

        String authorTurn = textOf(contentOf(messagesOf(http.bodies().get(0)).get(1)));
        for (int slot = 1; slot <= 3; slot++) {
            assertTrue(authorTurn.contains("skill-" + slot), "the name of skill " + slot);
            assertTrue(authorTurn.contains("what skill " + slot + " is for"), "the description");
            // The body is NOT there until it is asked for.
            // Asserted against the whole request, not only the author turn, because a body leaking
            // into any other message would cost the same context and be just as wrong.
            assertFalse(new String(http.bodies().get(0), StandardCharsets.UTF_8).contains("body " + slot),
                    "the body of skill " + slot + " must not be sent before it is requested");
        }
    }

    @Test
    @DisplayName("load_skill on a declared name returns the body, and it comes back into the loop")
    void aDeclaredSkillLoads() throws Exception {
        var http = new AiTestSupport.ScriptedHttp()
                .then(AiTestSupport.asksFor("call-1", LoadSkillTool.NAME, "{\"name\":\"skill-2\"}"))
                .then(AiTestSupport.answers("done"));
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        NodeResult result = resultOf(behavior.create(configuration(AgentSkillTest.withSkills(3)), http));

        assertEquals("done", result.payload());
        List<PayloadValue> second = messagesOf(http.bodies().get(1));
        assertEquals(PayloadValue.of("tool"), roleOf(second.get(4)));
        assertEquals("body 2", textOf(contentOf(second.get(4))));
        assertEquals(0, behavior.admissionEntries());
    }

    @Test
    @DisplayName("a name nobody declared is refused into the loop, and the run still answers")
    void anUndeclaredSkillNameDoesNotEndTheLoop() throws Exception {
        var http = new AiTestSupport.ScriptedHttp()
                .then(AiTestSupport.asksFor("call-1", LoadSkillTool.NAME, "{\"name\":\"invented\"}"))
                .then(AiTestSupport.answers("I used what I had"));
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        NodeResult result = resultOf(behavior.create(configuration(AgentSkillTest.withSkills(3)), http));

        // A model that misremembers a name must be able to correct itself: the refusal is a tool
        // message, the loop keeps its turn budget, and the node completes.
        assertEquals("I used what I had", result.payload());
        String refusal = textOf(contentOf(messagesOf(http.bodies().get(1)).get(4)));
        assertTrue(refusal.contains("invented"), "the refusal names what was asked for");
        assertTrue(refusal.contains("skill-1"), "and what could have been asked for instead");
        assertFalse(refusal.contains("body 1"), "naming a skill is not loading it");
    }

    @Test
    @DisplayName("a malformed argument is answered too, rather than failing the node")
    void aMalformedArgumentIsAnswered() throws Exception {
        var http = new AiTestSupport.ScriptedHttp()
                .then(AiTestSupport.asksFor("call-1", LoadSkillTool.NAME, "not json at all"))
                .then(AiTestSupport.answers("fine"));
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        NodeResult result = resultOf(behavior.create(configuration(AgentSkillTest.withSkills(1)), http));

        assertEquals("fine", result.payload());
        // Not merely non-empty: the answer has to be one the model can ACT on, so it names what the
        // node actually has. A bare "that was malformed" would pass an isEmpty check and teach the
        // model nothing about how to succeed on its next turn.
        String answer = textOf(contentOf(messagesOf(http.bodies().get(1)).get(4)));
        assertTrue(answer.contains("skill-1"), "the refusal must name what can be loaded");
        assertFalse(answer.contains("body 1"), "naming a skill is not loading it");
    }

    @Test
    @DisplayName("loading the same skill twice does not put the body in the conversation twice")
    void loadingTheSameSkillTwiceDoesNotRepeatIt() throws Exception {
        var http = new AiTestSupport.ScriptedHttp()
                .then(AiTestSupport.asksFor("call-1", LoadSkillTool.NAME, "{\"name\":\"skill-1\"}"))
                .then(AiTestSupport.asksFor("call-2", LoadSkillTool.NAME, "{\"name\":\"skill-1\"}"))
                .then(AiTestSupport.answers("done"));
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        NodeResult result = resultOf(behavior.create(configuration(AgentSkillTest.withSkills(2)), http));

        assertEquals("done", result.payload());
        // Counted on the LAST request, which carries the whole conversation: exactly one tool message
        // holds the body, and the second call was answered with a pointer to the first.
        List<PayloadValue> last = messagesOf(http.bodies().get(2));
        long bodies = last.stream().filter(entry -> "body 1".equals(textOf(contentOf(entry)))).count();
        assertEquals(1, bodies, "the body must appear exactly once however often it is asked for");
        assertTrue(textOf(contentOf(last.get(6))).contains("already loaded"));
    }

    @Test
    @DisplayName("a skill over the ceiling refuses when the node is built, not when a message arrives")
    void anOversizeSkillRefusesAtConstruction() {
        Map<String, Object> properties = AgentSkillTest.withSkills(1);
        properties.put("skills.1.instructions", "x".repeat(AgentSkill.MAX_INSTRUCTIONS_CHARS + 1));
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        // create() and not handle(): NodeBehavior#create reserves a throw for a node the behavior can
        // never serve, which is what an over-long body is -- no operator action resolves it, only the
        // author's. So the graph is refused rather than admitted and then failed on first arrival.
        AgentSkillException refusal = assertThrows(AgentSkillException.class,
                () -> behavior.create(configuration(properties), new AiTestSupport.ScriptedHttp()));

        assertEquals(AgentSkillException.Code.TOO_LARGE, refusal.code());
        assertEquals("skill-1", refusal.skill());
        // NOT an AgentException: that vocabulary belongs to a RUNNING agent and its rule is that a
        // refusal is a failed future, never a synchronous throw. Keeping this failure out of it is
        // what leaves that invariant true with no exceptions of its own.
        assertFalse(AgentException.class.isInstance(refusal));
    }

    @Test
    @DisplayName("a loaded body is paid for out of the token budget, and exhausting it says so")
    void aLoadedBodyConsumesTheTokenBudget() {
        var http = new AiTestSupport.ScriptedHttp()
                .then(AiTestSupport.asksForUsing("call-1", LoadSkillTool.NAME,
                        "{\"name\":\"skill-1\"}", 100, 10))
                // The turn after the load re-reads the whole conversation, the body now inside it,
                // and is billed for it. That is why no separate accounting is needed here: prompt
                // tokens are summed every turn, so a loaded body keeps costing from then on.
                .then(AiTestSupport.answersUsing("done", 5_000, 10));
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        Map<String, Object> properties = AgentSkillTest.withSkills(1);
        properties.put("maxTotalTokens", "1000");

        AgentException refusal = failureOf(behavior.create(configuration(properties), http));

        assertEquals(AgentException.Code.TOKEN_BUDGET_EXHAUSTED, refusal.code());
        // And the body really was in the turn that cost the budget: without this the test would pass
        // on the scripted numbers alone and prove nothing about skills.
        assertTrue(new String(http.bodies().get(1), StandardCharsets.UTF_8).contains("body 1"));
        assertEquals(0, behavior.admissionEntries());
    }

    @Test
    @DisplayName("with no skills declared, load_skill reports that none are available")
    void aNodeWithoutSkillsIsUnchanged() throws Exception {
        var http = new AiTestSupport.ScriptedHttp()
                .then(AiTestSupport.asksFor("call-1", LoadSkillTool.NAME))
                .then(AiTestSupport.answers("done"));
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        NodeResult result = resultOf(behavior.create(configuration(Map.of(
                "provider", "local", "instructions", "be terse", "objective", "say hi")), http));

        assertEquals("done", result.payload());
        assertEquals(LoadSkillTool.NO_SKILLS, textOf(contentOf(messagesOf(http.bodies().get(1)).get(4))));
    }

    @Test
    @DisplayName("two traversals through one action do not see each other's loaded skills")
    void loadedSkillsAreNotSharedBetweenInvocations() throws Exception {
        // ONE action, two messages through it -- the shape ADR 0024 section 3 describes, and the
        // reason the loaded-name set lives on a per-Run tool rather than on the behavior or the
        // action. If it were shared, the second traversal's first request would be answered "already
        // loaded" and that agent would never receive the body at all.
        var http = new AiTestSupport.ScriptedHttp()
                .then(AiTestSupport.asksFor("call-1", LoadSkillTool.NAME, "{\"name\":\"skill-1\"}"))
                .then(AiTestSupport.answers("first"))
                .then(AiTestSupport.asksFor("call-2", LoadSkillTool.NAME, "{\"name\":\"skill-1\"}"))
                .then(AiTestSupport.answers("second"));
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));
        NodeAction action = behavior.create(configuration(AgentSkillTest.withSkills(1)), http);

        assertEquals("first", resultOf(action).payload());
        assertEquals("second", resultOf(action).payload());

        assertEquals("body 1", textOf(contentOf(messagesOf(http.bodies().get(1)).get(4))));
        assertEquals("body 1", textOf(contentOf(messagesOf(http.bodies().get(3)).get(4))),
                "the second traversal must receive the body itself, not a pointer to another agent's");
        assertEquals(0, behavior.admissionEntries());
    }

    @Test
    @DisplayName("a name over the ceiling, ending mid surrogate pair, still produces a writable request")
    void anOverlongNameIsQuotedBackSafely() throws Exception {
        // The refusal quotes the requested name back, truncated to MAX_NAME_CHARS. Truncation can cut
        // a supplementary character in half, and a lone surrogate is exactly what a hand-written JSON
        // writer emits as broken bytes. This bundle writes through PayloadJson, which escapes an
        // unpaired surrogate rather than emitting it -- an unstated dependency until this test.
        //
        // The single ASCII character in front is what makes this test test anything. Without it the
        // cut at index MAX_NAME_CHARS lands exactly on a pair boundary -- 32 whole pairs of two UTF-16
        // units -- and no lone surrogate is ever produced, so the test would pass whether the hazard
        // were handled or not. Shifting by one puts a HIGH surrogate at the last kept index.
        String astral = "n" + "\uD83D\uDE80".repeat(AgentSkill.MAX_NAME_CHARS);
        var http = new AiTestSupport.ScriptedHttp()
                .then(AiTestSupport.asksFor("call-1", LoadSkillTool.NAME,
                        "{\"name\":\"" + astral + "\"}"))
                .then(AiTestSupport.answers("recovered"));
        var behavior = new AgentNodeBehavior(AiTestSupport.resolving(AiTestSupport.profile(ENDPOINT)));

        NodeResult result = resultOf(behavior.create(configuration(AgentSkillTest.withSkills(1)), http));

        assertEquals("recovered", result.payload());
        // The proof is that the SECOND request was written and read back at all.
        assertEquals(5, messagesOf(http.bodies().get(1)).size());
    }

    private static String textOf(PayloadValue value) {
        return ((PayloadValue.TextValue) value).value();
    }

    private static ai.ravenroot.api.node.NodeConfiguration configuration(Map<String, Object> properties) {
        return AiTestSupport.agentConfiguration(properties);
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
