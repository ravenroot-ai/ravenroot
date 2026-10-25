package ai.ravenroot.api.ai;

import ai.ravenroot.api.payload.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiResponsesTest {
    @Test void completedTextAndToolCallsAdaptWithoutExpandingTools() {
        String input = "{\"model\":\"account-model\",\"messages\":[{\"role\":\"system\",\"content\":\"operator policy\"},{\"role\":\"user\",\"content\":\"question\"}],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"bounded_tool\",\"parameters\":{\"type\":\"object\"}}}]}";
        Map<?, ?> request = object(OpenAiResponses.fromChat(bytes(input)));
        assertEquals(true, request.get("stream")); assertEquals(false, request.get("store"));
        var declaration = (Map<?, ?>) ((List<?>) request.get("input")).getFirst();
        assertEquals("developer", declaration.get("role")); assertEquals("additional_tools", declaration.get("type"));
        assertEquals("bounded_tool", ((Map<?, ?>) ((List<?>) declaration.get("tools")).getFirst()).get("name"));
        assertFalse(request.containsKey("tools"));
        assertEquals(false, request.get("parallel_tool_calls"));
        assertEquals("answer", OpenAiResponses.text(completed(Map.of("status", "completed", "output", List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", "answer"))))))));
        byte[] tool = OpenAiResponses.toChat(completed(Map.of("status", "completed", "output", List.of(Map.of("type", "function_call", "call_id", "call-1", "name", "bounded_tool", "arguments", "{}")))));
        var choice = (Map<?, ?>) ((List<?>) object(tool).get("choices")).getFirst();
        assertEquals("tool_calls", choice.get("finish_reason"));
    }
    @Test void personalWireOmitsUnsupportedControlsAndPreservesFunctionHistoryAndAuthority() {
        var definition = Map.of("type", "function", "function", Map.of("name", "approved_tool", "parameters", Map.of("type", "object")));
        var source = new LinkedHashMap<String, Object>();
        source.put("model", "account-model"); source.put("tools", List.of(definition)); source.put("max_tokens", 12);
        source.put("temperature", 0.5); source.put("top_p", 0.8); source.put("seed", 42);
        source.put("messages", List.of(Map.of("role", "user", "content", "hello"),
                Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", "call-1", "function", Map.of("name", "approved_tool", "arguments", "{}")))),
                Map.of("role", "tool", "tool_call_id", "call-1", "content", "approved-result")));
        var request = object(OpenAiResponses.fromChat(bytes(PayloadJson.write(PayloadValue.fromJava(source, PayloadLimits.DEFAULTS)))));
        assertEquals(Set.of("model", "input", "stream", "store", "parallel_tool_calls"), request.keySet());
        var input = (List<?>) request.get("input"); var declarations = (Map<?, ?>) input.getFirst();
        assertEquals("additional_tools", declarations.get("type")); assertEquals("developer", declarations.get("role"));
        var tool = (Map<?, ?>) ((List<?>) declarations.get("tools")).getFirst();
        assertEquals(Set.of("name", "parameters", "type", "strict"), tool.keySet()); assertEquals("approved_tool", tool.get("name"));
        assertEquals("function_call", ((Map<?, ?>) input.get(2)).get("type")); assertEquals("approved_tool", ((Map<?, ?>) input.get(2)).get("name"));
        assertEquals("call-1", ((Map<?, ?>) input.get(3)).get("call_id")); assertEquals("approved-result", ((Map<?, ?>) input.get(3)).get("output"));
    }
    @Test void acceptedOutputBudgetRequiresRealUsageBeforeReturningTextOrTools() {
        var result = new LinkedHashMap<String, Object>(); result.put("status", "completed");
        result.put("output", List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", "answer")))));
        assertEquals("provider-usage-missing", assertThrows(IllegalArgumentException.class, () -> OpenAiResponses.text(completed(result), 4)).getMessage());
        result.put("usage", Map.of("output_tokens", 5));
        assertEquals("provider-output-limit-exceeded", assertThrows(IllegalArgumentException.class, () -> OpenAiResponses.text(completed(result), 4)).getMessage());
        result.put("usage", Map.of("output_tokens", 4)); assertEquals("answer", OpenAiResponses.text(completed(result), 4));
        result.put("output", List.of(Map.of("type", "function_call", "call_id", "call-1", "name", "approved_tool", "arguments", "{}")));
        result.put("usage", Map.of("output_tokens", 5));
        assertEquals("provider-output-limit-exceeded", assertThrows(IllegalArgumentException.class, () -> OpenAiResponses.toChat(completed(result), 4)).getMessage());
    }
    @Test void terminalEventAndFailureClassificationAreMandatory() {
        assertEquals("provider-stream-interrupted", assertThrows(IllegalArgumentException.class, () -> OpenAiResponses.text(bytes("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n"))).getMessage());
        assertEquals("provider-response-incomplete", assertThrows(IllegalArgumentException.class, () -> OpenAiResponses.text(bytes("data: {\"type\":\"response.incomplete\"}\n\n"))).getMessage());
        assertEquals("provider-quota-exceeded", assertThrows(IllegalArgumentException.class, () -> OpenAiResponses.text(bytes("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\ndata: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n"))).getMessage());
        assertEquals("provider-quota-exceeded", assertThrows(IllegalArgumentException.class, () -> OpenAiResponses.text(bytes("data: {\"type\":\"error\",\"code\":\"subscription_sharing_usage_limit_exceeded\"}\n\n"))).getMessage());
        assertEquals("provider-response-invalid", assertThrows(IllegalArgumentException.class, () -> OpenAiResponses.text(bytes("data: not-json-private-provider-message\n\n"))).getMessage());
    }
    @Test void missingUsageStaysAbsentAndInvalidUsageIsRefused() {
        var response = Map.of("status", "completed", "output", List.of());
        assertTrue(((Map<?, ?>) object(OpenAiResponses.toChat(completed(response))).get("usage")).isEmpty());
        for (Object count : List.of(-1, 1.5, "2", Long.MAX_VALUE)) {
            var value = new LinkedHashMap<String, Object>(response); value.put("usage", Map.of("input_tokens", count));
            assertEquals("provider-usage-invalid", assertThrows(IllegalArgumentException.class, () -> OpenAiResponses.toChat(completed(value))).getMessage());
        }
        var value = new LinkedHashMap<String, Object>(response); value.put("usage", Map.of("input_tokens", 4, "output_tokens", 7));
        assertEquals(4L, ((Map<?, ?>) object(OpenAiResponses.toChat(completed(value))).get("usage")).get("prompt_tokens"));
    }
    private static byte[] completed(Map<String, ?> response) { return bytes("data: " + PayloadJson.write(PayloadValue.fromJava(Map.of("type", "response.completed", "response", response), PayloadLimits.DEFAULTS)) + "\n\n"); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static Map<?, ?> object(byte[] body) { return (Map<?, ?>) PayloadJson.read(body, PayloadLimits.DEFAULTS).toJava(); }
}
