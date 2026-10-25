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
        assertEquals("developer", ((Map<?, ?>) ((List<?>) request.get("input")).getFirst()).get("role"));
        assertEquals("bounded_tool", ((Map<?, ?>) ((List<?>) request.get("tools")).getFirst()).get("name"));
        assertEquals(false, request.get("parallel_tool_calls"));
        assertEquals("answer", OpenAiResponses.text(completed(Map.of("status", "completed", "output", List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", "answer"))))))));
        byte[] tool = OpenAiResponses.toChat(completed(Map.of("status", "completed", "output", List.of(Map.of("type", "function_call", "call_id", "call-1", "name", "bounded_tool", "arguments", "{}")))));
        var choice = (Map<?, ?>) ((List<?>) object(tool).get("choices")).getFirst();
        assertEquals("tool_calls", choice.get("finish_reason"));
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
