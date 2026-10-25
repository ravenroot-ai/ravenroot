package ai.ravenroot.api.ai;

import ai.ravenroot.api.payload.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded SIWC Responses wire codec. Success requires an explicit completed terminal event. */
public final class OpenAiResponses {
    private static final PayloadLimits LIMITS = new PayloadLimits(4 * 1024 * 1024, 32, 100_000, 100_000, 2 * 1024 * 1024, 4096);
    private static final String REPLAY = "ravenroot.responses.output";
    private OpenAiResponses() { }

    /**
     * Adapts an existing bounded Chat conversation without granting additional tools.
     * @param chat UTF-8 Chat request bytes with messages and optional function declarations
     * @return a non-stored streaming Responses request using the same model and tools
     * @throws Failure if the bounded wire document cannot be represented safely
     */
    public static byte[] fromChat(byte[] chat) {
        Map<String, Object> source = object(chat);
        List<Object> input = new ArrayList<>();
        for (Object value : list(source.get("messages"))) {
            Map<String, Object> message = map(value);
            String role = string(message, "role");
            if (message.containsKey(REPLAY)) {
                if (!role.equals("assistant")) throw error("provider-history-invalid");
                for (Object retained : list(message.get(REPLAY))) {
                    var item = map(retained); requireReplayable(item); input.add(item);
                }
                continue;
            }
            if (role.equals("tool")) {
                input.add(Map.of("type", "function_call_output", "call_id", string(message, "tool_call_id"), "output", string(message, "content")));
                continue;
            }
            if (message.get("content") instanceof String content && !content.isEmpty())
                input.add(Map.of("role", role.equals("system") ? "developer" : role, "content", content));
            for (Object tool : list(message.get("tool_calls"))) {
                var call = map(tool); var function = map(call.get("function"));
                input.add(Map.of("type", "function_call", "call_id", string(call, "id"), "name", string(function, "name"), "arguments", string(function, "arguments")));
            }
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", source.get("model")); request.put("input", input); request.put("stream", true); request.put("store", false);
        List<Object> tools = new ArrayList<>();
        for (Object value : list(source.get("tools"))) {
            var tool = map(value); if (!"function".equals(tool.get("type"))) throw error("unsupported-tool");
            Map<String, Object> definition = new LinkedHashMap<>(map(tool.get("function")));
            definition.put("type", "function");
            // Preserve the existing tool schema semantics; runtime authorization validates each call.
            definition.put("strict", false); tools.add(definition);
        }
        if (!tools.isEmpty()) {
            input.addFirst(Map.of("type", "additional_tools", "role", "developer", "tools", tools));
            request.put("parallel_tool_calls", false);
        }
        // SIWC HTTP rejects max_output_tokens and sampling controls. Callers enforce accepted
        // usage locally; an API-key Chat request retains its separate provider-side tuning.
        return json(request);
    }

    /**
     * Reads through completion and preserves bounded function calls for an existing policy loop.
     * @param stream bounded UTF-8 Responses server-sent events through the completed terminal event
     * @return Chat-compatible completion bytes with text, tool calls and only reported usage fields
     * @throws Failure if the response fails, is interrupted, is incomplete or has invalid usage
     */
    public static byte[] toChat(byte[] stream) {
        Map<String, Object> completed = terminal(stream);
        StringBuilder text = new StringBuilder(); List<Object> calls = new ArrayList<>();
        for (Object value : list(completed.get("output"))) {
            var output = map(value);
            requireReplayable(output);
            if ("message".equals(output.get("type"))) {
                for (Object content : list(output.get("content"))) {
                    var part = map(content);
                    if ("refusal".equals(part.get("type"))) throw error("provider-permission-denied");
                    if ("output_text".equals(part.get("type"))) text.append(string(part, "text"));
                }
            } else if ("function_call".equals(output.get("type"))) {
                if (calls.size() >= 32) throw error("tool-limit-exceeded");
                calls.add(Map.of("id", string(output, "call_id"), "type", "function", "function", Map.of("name", string(output, "name"), "arguments", string(output, "arguments"))));
            }
        }
        Map<String, Object> message = new LinkedHashMap<>(); message.put("role", "assistant"); message.put("content", text.toString());
        if (!calls.isEmpty()) {
            message.put("tool_calls", calls);
            message.put(REPLAY, list(completed.get("output")));
        }
        Map<String, Object> usage = map(completed.get("usage"));
        Map<String, Object> accounting = new LinkedHashMap<>();
        for (String field : List.of("input_tokens", "output_tokens")) {
            if (!usage.containsKey(field)) continue;
            Object value = usage.get(field);
            if (!(value instanceof Number count) || count.longValue() < 0 || count.longValue() > Integer.MAX_VALUE
                    || count.doubleValue() != (double) count.longValue() || !Double.isFinite(count.doubleValue()))
                throw error("provider-usage-invalid");
            accounting.put(field.equals("input_tokens") ? "prompt_tokens" : "completion_tokens", value);
        }
        return json(Map.of("model", string(completed, "model"), "choices", List.of(Map.of("index", 0, "message", message,
                "finish_reason", calls.isEmpty() ? "stop" : "tool_calls")), "usage", accounting));
    }
    /**
     * Retains validated stateless provider output for a subsequent authorized tool turn.
     * @param completedChat the bounded Chat-compatible result already checked against local budgets
     * @return an internal assistant history message preserving reasoning, phase and call identities
     * @throws Failure if a replayable assistant tool result is absent or has unsupported output
     */
    public static PayloadValue assistantHistory(byte[] completedChat) {
        var choices = list(object(completedChat).get("choices"));
        if (choices.size() != 1) throw error("provider-history-invalid");
        var message = map(map(choices.getFirst()).get("message"));
        var replay = list(message.get(REPLAY));
        if (!"assistant".equals(message.get("role")) || replay.isEmpty()) throw error("provider-history-invalid");
        for (Object item : replay) requireReplayable(map(item));
        return PayloadValue.fromJava(Map.of("role", "assistant", REPLAY, replay), LIMITS);
    }
    private static void requireReplayable(Map<String, Object> item) {
        String type = string(item, "type");
        if (type.equals("reasoning") && string(item, "encrypted_content").isBlank()) throw error("provider-history-invalid");
        if (!Set.of("reasoning", "message", "function_call").contains(type)
                || type.equals("message") && item.containsKey("role") && !"assistant".equals(item.get("role")))
            throw error("unsupported-output");
    }
    /**
     * Enforces a local accepted-result output budget using reported, validated usage.
     * @param stream bounded Responses server-sent events
     * @param maximumOutputTokens maximum reported output accepted by the caller
     * @return Chat-compatible bytes only after the output usage satisfies the local budget
     * @throws Failure if output usage is missing, invalid or exceeds the local budget
     */
    public static byte[] toChat(byte[] stream, long maximumOutputTokens) {
        byte[] chat = toChat(stream);
        Object reported = map(object(chat).get("usage")).get("completion_tokens");
        if (!(reported instanceof Number count)) throw error("provider-usage-missing");
        if (maximumOutputTokens < 1 || count.longValue() > maximumOutputTokens) throw error("provider-output-limit-exceeded");
        return chat;
    }
    /**
     * Accepts completed text only within a locally checked reported-output budget.
     * @param stream bounded Responses server-sent events
     * @param maximumOutputTokens maximum reported output accepted by the caller
     * @return completed text after validating usage and refusing tool calls
     * @throws Failure if completion or its accepted output budget is invalid
     */
    public static String text(byte[] stream, long maximumOutputTokens) {
        return textFromChat(toChat(stream, maximumOutputTokens));
    }
    /**
     * Reads one completed text response without permitting tool execution.
     * @param stream bounded UTF-8 Responses server-sent events
     * @return nonblank completed output text
     * @throws Failure if completion is absent, invalid, empty or requests a tool
     */
    public static String text(byte[] stream) {
        return textFromChat(toChat(stream));
    }
    private static String textFromChat(byte[] chat) {
        var choices = list(object(chat).get("choices"));
        var message = map(map(choices.getFirst()).get("message"));
        if (!list(message.get("tool_calls")).isEmpty()) throw error("unsupported-tool");
        String text = string(message, "content"); if (text.isBlank()) throw error("provider-empty-response"); return text;
    }
    private static Map<String, Object> terminal(byte[] stream) {
        if (stream.length > LIMITS.maxEncodedBytes()) throw error("provider-response-too-large");
        String source = new String(stream, StandardCharsets.UTF_8).replace("\r\n", "\n");
        Map<String, Object> completed = null;
        for (String event : source.split("\n\n")) {
            StringBuilder data = new StringBuilder();
            for (String line : event.split("\n")) if (line.startsWith("data:")) { if (!data.isEmpty()) data.append('\n'); data.append(line.substring(5).stripLeading()); }
            if (data.isEmpty() || data.toString().equals("[DONE]")) continue;
            var item = object(data.toString().getBytes(StandardCharsets.UTF_8));
            String type = string(item, "type");
            if (completed != null) throw error("provider-stream-invalid");
            if (type.equals("response.failed") || type.equals("error")) {
                var detail = type.equals("error")
                        ? item.get("error") instanceof Map<?, ?> ? map(item.get("error")) : item
                        : map(map(item.get("response")).get("error"));
                String code = string(detail, "code");
                throw error(switch (code) {
                    case "subscription_sharing_usage_limit_exceeded" -> "provider-quota-exceeded";
                    case "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> "provider-unavailable";
                    case "subscription_sharing_invalid_user" -> "reconnect-required";
                    case "subscription_sharing_user_not_eligible", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" -> "provider-permission-denied";
                    default -> "provider-request-failed";
                });
            }
            if (type.equals("response.incomplete")) throw error("provider-response-incomplete");
            if (type.equals("response.completed")) {
                completed = map(item.get("response"));
                if (!"completed".equals(completed.get("status"))) throw error("provider-stream-invalid");
            }
        }
        if (completed == null) throw error("provider-stream-interrupted");
        return completed;
    }
    /** Sanitized wire failure whose message contains only a fixed classification code. */
    public static final class Failure extends IllegalArgumentException {
        private Failure(String code) { super(code); }
    }
    /**
     * Classifies a provider HTTP response before accepting any stream output.
     * @param status HTTP status returned by the provider
     * @throws Failure when the status is outside the successful 2xx range
     */
    public static void requireStatus(int status) {
        if (status >= 200 && status < 300) return;
        throw error(switch (status) { case 401 -> "reconnect-required"; case 403 -> "provider-permission-denied";
            case 429 -> "provider-quota-exceeded"; default -> "provider-unavailable"; });
    }
    private static Failure error(String code) { return new Failure(code); }
    private static byte[] json(Object value) { return PayloadJson.write(PayloadValue.fromJava(value, LIMITS)).getBytes(StandardCharsets.UTF_8); }
    private static Map<String, Object> object(byte[] value) {
        try { return map(PayloadJson.read(value, LIMITS).toJava()); }
        catch (RuntimeException invalid) { throw error("provider-response-invalid"); }
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(); }
    private static List<?> list(Object value) { return value instanceof List<?> list ? list : List.of(); }
    private static String string(Map<String, Object> map, String key) { return map.get(key) instanceof String text ? text : ""; }
}
