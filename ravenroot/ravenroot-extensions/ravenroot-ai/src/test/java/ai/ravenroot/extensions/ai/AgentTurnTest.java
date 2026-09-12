package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the {@code agent} node puts on the wire, and what it is willing to read back. */
class AgentTurnTest {

    private static final OpenAiCompatibleChat.Tuning NO_TUNING = new OpenAiCompatibleChat.Tuning(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    @Test
    @DisplayName("stream is stated false, so a server defaulting to SSE cannot decide it for us")
    void streamIsStated() {
        Map<String, PayloadValue> request = parse(AgentTurn.writeRequest(
                "qwen38", List.of(AgentTurn.userMessage("hi")), List.of(), NO_TUNING));

        assertEquals(new PayloadValue.BooleanValue(false), request.get("stream"));
    }

    @Test
    @DisplayName("a node with no tools writes neither tools nor tool_choice")
    void noToolsWritesNoToolMembers() {
        Map<String, PayloadValue> request = parse(AgentTurn.writeRequest(
                "qwen38", List.of(AgentTurn.userMessage("hi")), List.of(), NO_TUNING));

        // An empty tools array is not universally accepted, and a request that declares no tools is
        // the honest document for a node that has none.
        assertFalse(request.containsKey("tools"));
        assertFalse(request.containsKey("tool_choice"));
    }

    @Test
    @DisplayName("a declared tool travels as a function with its schema, and tool_choice stays auto")
    void aDeclaredToolTravelsWithItsSchema() {
        Map<String, PayloadValue> request = parse(AgentTurn.writeRequest(
                "qwen38", List.of(AgentTurn.userMessage("hi")), List.of(new LoadSkillTool(List.of())), NO_TUNING));

        var tools = (PayloadValue.ListValue) request.get("tools");
        var first = (PayloadValue.MapValue) tools.values().get(0);
        assertEquals(PayloadValue.of("function"), first.entries().get("type"));
        var function = (PayloadValue.MapValue) first.entries().get("function");
        assertEquals(PayloadValue.of(LoadSkillTool.NAME), function.entries().get("name"));
        assertTrue(function.entries().containsKey("parameters"));
        // "required" and not "auto" would make termination unreachable: the loop ends when the model
        // answers, and a forced tool call means it never does.
        assertEquals(PayloadValue.of("auto"), request.get("tool_choice"));
    }

    @Test
    @DisplayName("with no operator preamble the system turn is exactly Ravenroot policy")
    void withoutAPreambleTheSystemTurnIsOnlyPolicy() {
        var system = (PayloadValue.MapValue) AgentTurn.systemMessage("");

        assertEquals(PayloadValue.of("system"), system.entries().get("role"));
        assertEquals(PayloadValue.of(AgentTurn.BASE_POLICY), system.entries().get("content"));
    }

    @Test
    @DisplayName("operator policy is structurally separate from author instructions")
    void operatorAndAuthorInstructionsUseDifferentRoles() {
        var system = (PayloadValue.MapValue) AgentTurn.systemMessage("Operator rules.");
        var author = (PayloadValue.MapValue) AgentTurn.authorInstructionsMessage(
                "Author rules.", List.of());

        String content = ((PayloadValue.TextValue) system.entries().get("content")).value();
        assertTrue(content.startsWith(AgentTurn.BASE_POLICY));
        assertTrue(content.endsWith("Operator rules."));
        assertFalse(content.contains("Author rules."));
        assertEquals(PayloadValue.of("user"), author.entries().get("role"));
        assertEquals(PayloadValue.of("Author rules."), author.entries().get("content"));
    }

    @Test
    @DisplayName("an answered turn reads as an answer with no tool calls")
    void anAnsweredTurnReads() {
        AgentTurn.Turn turn = AgentTurn.read(bytes(
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"done\"}}],"
                        + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3}}"), 65_536);

        assertTrue(turn.answered());
        assertEquals("done", turn.answer());
        assertEquals("stop", turn.finishReason());
        assertEquals(Optional.of(11L), turn.promptTokens());
        assertEquals(Optional.of(3L), turn.completionTokens());
        assertFalse(turn.truncated());
    }

    @Test
    @DisplayName("a tool-call turn reads as a request, including the shape llm-prompt calls empty")
    void aToolCallTurnReads() {
        // "content": null with tool_calls is exactly the document OpenAiCompatibleChat refuses as
        // COMPLETION_EMPTY, and refusing it there is correct: that node cannot answer a tool call.
        // This node can, which is the whole reason the two readers are separate.
        AgentTurn.Turn turn = AgentTurn.read(bytes(
                "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"content\":null,"
                        + "\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":"
                        + "{\"name\":\"load_skill\",\"arguments\":\"{\\\"name\\\":\\\"x\\\"}\"}}]}}]}"),
                65_536);

        assertFalse(turn.answered());
        assertEquals(1, turn.toolCalls().size());
        assertEquals("call-1", turn.toolCalls().get(0).id());
        assertEquals("load_skill", turn.toolCalls().get(0).name());
        assertEquals("{\"name\":\"x\"}", turn.toolCalls().get(0).arguments());
    }

    @Test
    @DisplayName("one bad tool call among good ones is dropped and the turn still asks for the rest")
    void oneBadToolCallAmongGoodOnesIsDropped() {
        // The model still gets a result for the call that was usable and can ask again for the one it
        // did not; refusing the whole response for a single formatting quirk would turn it into a
        // terminated traversal.
        AgentTurn.Turn turn = AgentTurn.read(bytes(
                "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":"
                        + "[{\"type\":\"function\",\"function\":{\"name\":\"load_skill\"}},"
                        + "{\"id\":\"call-2\",\"type\":\"function\",\"function\":"
                        + "{\"name\":\"load_skill\",\"arguments\":\"{}\"}}]}}]}"), 65_536);

        assertFalse(turn.answered());
        assertEquals(1, turn.toolCalls().size());
        assertEquals("call-2", turn.toolCalls().get(0).id());
    }

    @Test
    @DisplayName("a turn whose tool calls are ALL unusable refuses, and does not become an answer")
    void aTurnOfUnusableToolCallsRefuses() {
        // The dangerous half, and the one a "content is null" test cannot reach: with a planning
        // preamble present, "empty" is false and answered() is true, so without this refusal the loop
        // would end on turn one and hand back the preamble as the agent's result.
        AgentException refusal = assertThrows(AgentException.class, () -> AgentTurn.read(bytes(
                "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":"
                        + "{\"content\":\"Let me look that up for you.\",\"tool_calls\":"
                        + "[{\"type\":\"function\",\"function\":{\"name\":\"load_skill\"}}]}}]}"),
                65_536));

        assertEquals(AgentException.Code.TOOL_CALL_UNREADABLE, refusal.code());
    }

    @Test
    @DisplayName("no tool_calls member and no content is still the empty turn, with its own code")
    void neitherAnAnswerNorARequestIsEmpty() {
        // The two conditions must stay distinguishable: this one means the endpoint said nothing,
        // the one above means it asked for something unusable.
        AgentException refusal = assertThrows(AgentException.class, () -> AgentTurn.read(bytes(
                "{\"choices\":[{\"message\":{\"content\":null}}]}"), 65_536));

        assertEquals(AgentException.Code.COMPLETION_EMPTY, refusal.code());
    }

    @Test
    @DisplayName("a content filter is read before the content, and refuses")
    void aContentFilterRefusesBeforeTheContentIsTouched() {
        AgentException refusal = assertThrows(AgentException.class, () -> AgentTurn.read(bytes(
                "{\"choices\":[{\"finish_reason\":\"content_filter\","
                        + "\"message\":{\"content\":\"partial\"}}]}"), 65_536));

        assertEquals(AgentException.Code.COMPLETION_REFUSED, refusal.code());
    }

    @Test
    @DisplayName("a response larger than the profile's ceiling refuses without being parsed")
    void anOversizedResponseRefuses() {
        AgentException refusal = assertThrows(AgentException.class,
                () -> AgentTurn.read(bytes("{\"choices\":[{\"message\":{\"content\":\"x\"}}]}"), 4));

        assertEquals(AgentException.Code.RESPONSE_TOO_LARGE, refusal.code());
    }

    @Test
    @DisplayName("an event-stream body is unreadable rather than empty")
    void anEventStreamBodyIsUnreadable() {
        AgentException refusal = assertThrows(AgentException.class,
                () -> AgentTurn.read(bytes("data: {\"choices\":[]}\n\n"), 65_536));

        assertEquals(AgentException.Code.RESPONSE_UNREADABLE, refusal.code());
    }

    @Test
    @DisplayName("the assistant turn is rebuilt from what was read, not echoed back")
    void theAssistantTurnIsRebuilt() {
        var rebuilt = (PayloadValue.MapValue) AgentTurn.assistantToolCallMessage("",
                List.of(new AgentTurn.ToolCall("call-1", "load_skill", "{}")));

        assertEquals(PayloadValue.of("assistant"), rebuilt.entries().get("role"));
        // Sent even when empty: several endpoints reject an assistant turn with no content member.
        assertEquals(PayloadValue.of(""), rebuilt.entries().get("content"));
        var calls = (PayloadValue.ListValue) rebuilt.entries().get("tool_calls");
        var call = (PayloadValue.MapValue) calls.values().get(0);
        // Four fields and nothing else: whatever else the far end included does not travel back.
        assertEquals(java.util.Set.of("id", "type", "function"), call.entries().keySet());
    }

    @Test
    @DisplayName("a tool result is addressed to the call it answers")
    void aToolResultIsAddressed() {
        var result = (PayloadValue.MapValue) AgentTurn.toolResultMessage("call-1", "the body");

        assertEquals(PayloadValue.of("tool"), result.entries().get("role"));
        assertEquals(PayloadValue.of("call-1"), result.entries().get("tool_call_id"));
        assertEquals(PayloadValue.of("the body"), result.entries().get("content"));
    }

    @Test
    @DisplayName("instructions with a quote and a backslash survive as one value")
    void hostileInstructionsSurvive() {
        String hostile = "say \"hi\" \\ then stop";
        Map<String, PayloadValue> request = parse(AgentTurn.writeRequest("qwen38",
                List.of(AgentTurn.systemMessage(""),
                        AgentTurn.authorInstructionsMessage(hostile, List.of())), List.of(), NO_TUNING));

        var messages = (PayloadValue.ListValue) request.get("messages");
        var system = (PayloadValue.MapValue) messages.values().get(0);
        var author = (PayloadValue.MapValue) messages.values().get(1);
        assertFalse(((PayloadValue.TextValue) system.entries().get("content")).value().contains(hostile));
        assertEquals(PayloadValue.of(hostile), author.entries().get("content"));
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, PayloadValue> parse(byte[] request) {
        return ((PayloadValue.MapValue) PayloadJson.read(request, PayloadLimits.DEFAULTS)).entries();
    }

    @Test
    @DisplayName("the skills listing is in the untrusted author turn")
    void theSkillsListingIsInTheAuthorTurn() {
        var skills = java.util.List.of(
                new AgentSkill("research", "Finds sources.", "the body"),
                new AgentSkill("summarise", "Condenses them.", "another body"));

        String turn = ((PayloadValue.TextValue) ((PayloadValue.MapValue)
                AgentTurn.authorInstructionsMessage("author instructions", skills))
                .entries().get("content")).value();

        assertTrue(turn.contains("research"), "a skill name is author content");
        assertTrue(turn.contains("Finds sources."), "and so is its description");
        assertFalse(turn.contains("OPERATOR PREAMBLE"));
        assertFalse(turn.contains("the body"));
        assertFalse(turn.contains("another body"));
    }
}
