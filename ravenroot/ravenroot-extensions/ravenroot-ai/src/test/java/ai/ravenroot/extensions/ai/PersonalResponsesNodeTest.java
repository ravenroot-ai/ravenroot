package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.node.service.*;
import ai.ravenroot.api.payload.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PersonalResponsesNodeTest {
    private LlmProfile profile() { return new LlmProfile("openai", URI.create("https://api.openai.com/v1/chat/completions"),
            "account-model", Optional.of(new OutboundCredentialBinding("openai", "system-key")), 5000, 1024 * 1024, 4); }
    @Test void systemSelectionCannotUseAReservedPersonalReferenceEvenWhenAnotherNodeIsPersonal() {
        var reserved = new LlmProfile("openai", URI.create("https://api.openai.com/v1/chat/completions"), "account-model",
                Optional.of(new OutboundCredentialBinding("openai", "personal:openai")), 5000, 1048576, 4);
        var system = AiTestSupport.configuration(Map.of("provider", "openai", "prompt", "hello", "credentialMode", "system"));
        assertThrows(IllegalArgumentException.class, () -> new LlmPromptNodeBehavior(AiTestSupport.resolving(reserved))
                .create(system, new AiTestSupport.HttpDouble()));
        assertThrows(IllegalArgumentException.class, () -> reserved.forNode(system));
        assertTrue(reserved.forNode(AiTestSupport.configuration(Map.of("credentialMode", "personal"))).personal());
    }
    @Test void promptUsesLogicalPersonalBindingAndCompletedResponsesButSystemModeStaysChat() {
        var services = new AiTestSupport.HttpDouble();
        services.response = CompletableFuture.completedFuture(new OutboundHttpResponse(200, Map.of("content-type", List.of("text/event-stream")), streamText("personal-answer").getBytes(StandardCharsets.UTF_8)));
        var node = new LlmPromptNodeBehavior(AiTestSupport.resolving(profile()));
        var action = node.create(AiTestSupport.configuration(Map.of("provider", "openai", "prompt", "{{payload}}", "credentialMode", "personal", "maxTokens", 4)), services);
        var result = action.handle(AiTestSupport.message("hello")).toCompletableFuture().join();
        assertEquals("personal-answer", result.payload());
        var request = services.request.get();
        assertEquals("https://api.openai.com/v1/responses", request.destination().toString());
        assertEquals("personal:openai", request.credential().orElseThrow().reference());
        Map<?, ?> body = object(request.body()); assertEquals(true, body.get("stream")); assertEquals(false, body.get("store"));
        assertFalse(body.containsKey("max_output_tokens"));
        assertFalse(new String(request.body(), StandardCharsets.UTF_8).contains("system-key"));
        var system = new AiTestSupport.HttpDouble();
        node.create(AiTestSupport.configuration(Map.of("provider", "openai", "prompt", "hello", "maxTokens", 4)), system)
                .handle(AiTestSupport.message("hello")).toCompletableFuture().join();
        assertEquals("https://api.openai.com/v1/chat/completions", system.request.get().destination().toString());
        assertEquals("system-key", system.request.get().credential().orElseThrow().reference());
        assertEquals(4L, object(system.request.get().body()).get("max_tokens"));
    }
    @Test void agentUsesTheSameBoundedToolLoopWithResponsesFunctionOutput() {
        var services = new AiTestSupport.ScriptedHttp().then(streamCall()).then(streamText("agent-answer"));
        var node = new AgentNodeBehavior(AiTestSupport.resolving(profile()));
        var action = node.create(AiTestSupport.agentConfiguration(Map.of("provider", "openai", "instructions", "be concise", "objective", "{{payload}}", "credentialMode", "personal", "maxTurns", 3)), services);
        var result = action.handle(AiTestSupport.message("hello")).toCompletableFuture().join();
        assertEquals("agent-answer", result.payload()); assertEquals(2, services.bodies().size());
        assertFalse(result.attributes().toString().contains("opaque-provider-context"));
        assertTrue(new String(services.bodies().get(1), StandardCharsets.UTF_8).contains("function_call_output"));
        Map<?, ?> first = object(services.bodies().getFirst());
        assertEquals(false, first.get("parallel_tool_calls"));
        var continuation = (List<?>) object(services.bodies().get(1)).get("input");
        var types = continuation.stream().filter(v -> v instanceof Map<?, ?>).map(v -> ((Map<?, ?>) v).get("type")).toList();
        int reasoning = types.indexOf("reasoning"), call = types.indexOf("function_call"), output = types.indexOf("function_call_output");
        assertTrue(reasoning >= 0 && reasoning < call && call < output);
        assertEquals(1, Collections.frequency(types, "function_call"));
        assertEquals("opaque-provider-context", ((Map<?, ?>) continuation.get(reasoning)).get("encrypted_content"));
        assertEquals("commentary", ((Map<?, ?>) continuation.get(reasoning + 1)).get("phase"));
        assertFalse(new String(services.bodies().get(1), StandardCharsets.UTF_8).contains("ravenroot.responses.output"));
        assertFalse(first.containsKey("tools")); assertFalse(first.containsKey("max_output_tokens"));
        var declaration = (Map<?, ?>) ((List<?>) first.get("input")).getFirst();
        assertEquals("additional_tools", declaration.get("type"));
        assertTrue(((List<?>) declaration.get("tools")).stream().allMatch(t -> Boolean.FALSE.equals(((Map<?, ?>) t).get("strict"))));
    }
    @Test void personalPromptAndAgentRefuseMissingOrExcessUsageBeforeAcceptingResultsOrTools() {
        for (var usage : List.of(Map.<String, Object>of(), Map.<String, Object>of("input_tokens", 1, "output_tokens", 5),
                Map.<String, Object>of("input_tokens", 1, "output_tokens", -1))) {
            var text = stream(Map.of("status", "completed", "usage", usage, "output", List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", "unaccepted"))))));
            var promptService = new AiTestSupport.ScriptedHttp().then(text);
            var prompt = new LlmPromptNodeBehavior(AiTestSupport.resolving(profile())).create(AiTestSupport.configuration(
                    Map.of("provider", "openai", "prompt", "hello", "credentialMode", "personal", "maxTokens", 4)), promptService);
            assertThrows(CompletionException.class, () -> prompt.handle(AiTestSupport.message("hello")).toCompletableFuture().join());
            var call = stream(Map.of("status", "completed", "usage", usage, "output", List.of(Map.of("type", "function_call", "call_id", "call-1", "name", "load_skill", "arguments", "{}"))));
            var agentService = new AiTestSupport.ScriptedHttp().then(call).then(streamText("must-not-run"));
            var agent = new AgentNodeBehavior(AiTestSupport.resolving(profile())).create(AiTestSupport.agentConfiguration(
                    Map.of("provider", "openai", "instructions", "be concise", "objective", "hello", "credentialMode", "personal", "maxTokens", 4)), agentService);
            assertThrows(CompletionException.class, () -> agent.handle(AiTestSupport.message("hello")).toCompletableFuture().join());
            assertEquals(1, agentService.calls()); assertEquals(0, agentService.toolAuthorizations.get());
        }
    }
    @Test void unsupportedOrUnreplayableProviderContextRefusesBeforeAnyTool() {
        for (var context : List.of(Map.<String, Object>of("type", "web_search_call", "id", "unapproved"),
                Map.<String, Object>of("type", "reasoning", "id", "missing-encrypted-context", "summary", List.of()),
                Map.<String, Object>of("type", "message", "role", "developer", "content", List.of()))) {
            var response = stream(Map.of("status", "completed", "usage", Map.of("input_tokens", 1, "output_tokens", 1),
                    "output", List.of(context, Map.of("type", "function_call", "call_id", "call-1", "name", "load_skill", "arguments", "{}"))));
            var services = new AiTestSupport.ScriptedHttp().then(response);
            var agent = new AgentNodeBehavior(AiTestSupport.resolving(profile())).create(AiTestSupport.agentConfiguration(
                    Map.of("provider", "openai", "instructions", "be concise", "objective", "hello", "credentialMode", "personal")), services);
            assertThrows(CompletionException.class, () -> agent.handle(AiTestSupport.message("hello")).toCompletableFuture().join());
            assertEquals(1, services.calls()); assertEquals(0, services.toolAuthorizations.get());
        }
    }
    @Test void quotaAndInterruptedStreamsDoNotProduceSuccessfulNodeResults() {
        for (String response : List.of("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n", "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n")) {
            var services = new AiTestSupport.ScriptedHttp().then(response);
            var action = new AgentNodeBehavior(AiTestSupport.resolving(profile())).create(AiTestSupport.agentConfiguration(Map.of("provider", "openai", "instructions", "be concise", "objective", "hello", "credentialMode", "personal")), services);
            var failure = assertThrows(CompletionException.class, () -> action.handle(AiTestSupport.message("hello")).toCompletableFuture().join());
            assertInstanceOf(AgentException.class, failure.getCause());
            assertEquals(response.contains("usage_limit") ? AgentException.Code.PROVIDER_QUOTA_EXCEEDED : AgentException.Code.RESPONSE_INCOMPLETE, ((AgentException) failure.getCause()).code());
        }
    }
    static String streamText(String text) { return stream(Map.of("status", "completed", "usage", Map.of("input_tokens", 5, "output_tokens", 4), "output", List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", text)))))); }
    static String streamCall() { return stream(Map.of("status", "completed", "usage", Map.of("input_tokens", 5, "output_tokens", 4), "output", List.of(
            Map.of("type", "reasoning", "id", "reasoning-1", "encrypted_content", "opaque-provider-context", "summary", List.of()),
            Map.of("type", "message", "role", "assistant", "phase", "commentary", "content", List.of(Map.of("type", "output_text", "text", "Checking the approved tool"))),
            Map.of("type", "function_call", "call_id", "tool-1", "name", "load_skill", "arguments", "{}")))); }
    static String stream(Map<String, Object> response) { return "data: " + PayloadJson.write(PayloadValue.fromJava(Map.of("type", "response.completed", "response", response), PayloadLimits.DEFAULTS)) + "\n\n"; }
    static Map<?, ?> object(byte[] data) { return (Map<?, ?>) PayloadJson.read(data, PayloadLimits.DEFAULTS).toJava(); }
}
