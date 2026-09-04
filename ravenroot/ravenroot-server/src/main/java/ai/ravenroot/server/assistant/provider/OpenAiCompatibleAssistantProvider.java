package ai.ravenroot.server.assistant.provider;

import ai.ravenroot.api.node.service.ExternalIoLimits;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.server.assistant.AssistantCredential;
import ai.ravenroot.server.assistant.AssistantOutcome;
import ai.ravenroot.core.security.egress.BoundedBodyHandlers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The authoring Assistant translator for OpenAI-compatible chat completions.
 *
 * <p>This is deliberately an {@link AssistantProvider}, not the repository's separately packaged
 * executable-node {@code ModelProvider}. It receives the Assistant's system instructions,
 * conversation and read-only tool vocabulary, performs one non-streaming request, and returns one
 * provider-neutral turn. It never consults model profiles or the executable-node registry.</p>
 *
 * <p>The injected client is the SEC-10 client built by the composition root. Redirects are never
 * followed, response bytes are bounded before parsing, and neither an error body nor any part of a
 * request is copied into an exception message.</p>
 */
public final class OpenAiCompatibleAssistantProvider implements AssistantProvider {

    public static final String ID = "openai-compatible";

    static final PayloadLimits RESPONSE_LIMITS =
            new PayloadLimits(8 * 1024 * 1024, 32, 10_000, 200_000, 4 * 1024 * 1024, 256);

    private final HttpClient httpClient;
    private final URI endpoint;
    private final String model;
    private final AssistantCredential credential;
    private final Duration timeout;

    public OpenAiCompatibleAssistantProvider(HttpClient httpClient, URI endpoint, String model,
                                              AssistantCredential credential, Duration timeout,
                                              boolean allowLocalHttp) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.model = Objects.requireNonNull(model, "model");
        this.credential = credential;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (endpoint.getHost() == null || endpoint.getHost().isBlank()
                || endpoint.getUserInfo() != null
                || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("the assistant endpoint has an ineligible URI structure");
        }
        String scheme = String.valueOf(endpoint.getScheme()).toLowerCase(java.util.Locale.ROOT);
        if ("http".equals(scheme)
                && (!allowLocalHttp || credential != null || !isLocalHttpHost(endpoint.getHost()))) {
            throw new IllegalArgumentException("credential-free local HTTP was not explicitly permitted");
        }
        if (!("https".equals(scheme) || "http".equals(scheme))) {
            throw new IllegalArgumentException("the assistant endpoint must use HTTPS");
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public URI endpoint() {
        return endpoint;
    }

    @Override
    public boolean egresses() {
        return true;
    }

    @Override
    public Turn complete(Request request) throws AssistantProviderException {
        byte[] body;
        HttpRequest outbound;
        try {
            body = writeRequest(request).getBytes(StandardCharsets.UTF_8);
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("content-type", "application/json")
                    .header("accept", "application/json");
            if (credential != null) {
                builder.header("authorization", "Bearer " + credential.expose());
            }
            outbound = builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        } catch (IllegalArgumentException malformed) {
            // Do not retain the JDK exception: a rejected header exception can quote its value.
            throw new AssistantProviderException(AssistantOutcome.Reason.PROVIDER_REJECTED,
                    "the configured request could not be constructed", null);
        } catch (RuntimeException defect) {
            // Encoding touches prompts and tool results. Even a product defect must not retain an
            // exception whose message could have copied those values into a later server log.
            throw new AssistantProviderException(AssistantOutcome.Reason.ADAPTER_DEFECT,
                    "the adapter could not encode the request", null);
        }

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(outbound, BoundedBodyHandlers.withLimits(
                    ExternalIoLimits.compressedHttp(Math.max(1, body.length),
                            RESPONSE_LIMITS.maxEncodedBytes(), RESPONSE_LIMITS.maxEncodedBytes(),
                            RESPONSE_LIMITS.maxEncodedBytes(), 100, timeout,
                            java.util.Set.of("application/json"))));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssistantProviderException(AssistantOutcome.Reason.PROVIDER_UNAVAILABLE,
                    "the provider call was interrupted", interrupted);
        } catch (SecurityException refused) {
            throw new AssistantProviderException(AssistantOutcome.Reason.EGRESS_REFUSED,
                    "outbound policy refused the provider destination", refused);
        } catch (IOException unavailable) {
            if (boundedResponseFailure(unavailable)) {
                throw new AssistantProviderException(AssistantOutcome.Reason.PROVIDER_UNREADABLE,
                        "the provider response exceeded its representation limits", null);
            }
            throw new AssistantProviderException(AssistantOutcome.Reason.PROVIDER_UNAVAILABLE,
                    "the provider could not be reached before the configured timeout", unavailable);
        }

        int status = response.statusCode();
        try {
            if (status >= 300 && status < 400) {
                throw new AssistantProviderException(AssistantOutcome.Reason.EGRESS_REFUSED,
                        "the provider redirect was refused", null);
            }
            if (status == 401 || status == 403 || (status >= 400 && status < 500)) {
                throw new AssistantProviderException(AssistantOutcome.Reason.PROVIDER_REJECTED,
                        "the provider rejected the request", null);
            }
            if (status < 200 || status >= 500) {
                throw new AssistantProviderException(AssistantOutcome.Reason.PROVIDER_UNAVAILABLE,
                        "the provider returned an unavailable status", null);
            }
            return readTurn(response.body(), request.model());
        } catch (AssistantProviderException classified) {
            throw classified;
        } catch (RuntimeException defect) {
            // Translation touches provider-controlled content; retain only our fixed classification.
            throw new AssistantProviderException(AssistantOutcome.Reason.ADAPTER_DEFECT,
                    "the adapter could not translate the response", null);
        }
    }

    private static boolean boundedResponseFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof BoundedBodyHandlers.ResponseTooLargeException
                    || current instanceof BoundedBodyHandlers.ResponseMediaTypeException
                    || current instanceof BoundedBodyHandlers.ResponseEncodingException) {
                return true;
            }
        }
        return false;
    }

    String writeRequest(Request request) {
        var messages = new ArrayList<PayloadValue>();
        if (request.system() != null && !request.system().isBlank()) {
            messages.add(message("system", PayloadValue.of(request.system())));
        }
        for (Message message : request.messages()) {
            writeMessage(messages, message);
        }

        var root = new LinkedHashMap<String, PayloadValue>();
        root.put("model", PayloadValue.of(model));
        root.put("stream", PayloadValue.of(false));
        root.put("max_tokens", PayloadValue.of(request.maxTokens()));
        root.put("messages", PayloadValue.list(messages));
        if (!request.tools().isEmpty()) {
            var tools = new ArrayList<PayloadValue>();
            for (ToolSpec tool : request.tools()) {
                PayloadValue schema = PayloadJson.read(
                        tool.inputSchemaJson().getBytes(StandardCharsets.UTF_8), RESPONSE_LIMITS);
                var function = new LinkedHashMap<String, PayloadValue>();
                function.put("name", PayloadValue.of(tool.name()));
                function.put("description", PayloadValue.of(tool.description()));
                function.put("parameters", schema);
                var declared = new LinkedHashMap<String, PayloadValue>();
                declared.put("type", PayloadValue.of("function"));
                declared.put("function", PayloadValue.map(function));
                tools.add(PayloadValue.map(declared));
            }
            root.put("tools", PayloadValue.list(tools));
        }
        return PayloadJson.write(PayloadValue.map(root));
    }

    private static void writeMessage(List<PayloadValue> target, Message source) {
        if (source.role() == Role.AUTHOR) {
            var text = new StringBuilder();
            for (Content block : source.content()) {
                switch (block) {
                    case Content.Text value -> text.append(value.text());
                    case Content.ToolResult result -> {
                        var tool = new LinkedHashMap<String, PayloadValue>();
                        tool.put("role", PayloadValue.of("tool"));
                        tool.put("tool_call_id", PayloadValue.of(result.toolUseId()));
                        tool.put("content", PayloadValue.of(result.content()));
                        target.add(PayloadValue.map(tool));
                    }
                    case Content.ToolUse ignored -> throw new IllegalArgumentException(
                            "an author message cannot contain a provider tool call");
                }
            }
            if (!text.isEmpty()) {
                target.add(message("user", PayloadValue.of(text.toString())));
            }
            return;
        }

        var text = new StringBuilder();
        var calls = new ArrayList<PayloadValue>();
        for (Content block : source.content()) {
            switch (block) {
                case Content.Text value -> text.append(value.text());
                case Content.ToolUse use -> {
                    var function = new LinkedHashMap<String, PayloadValue>();
                    function.put("name", PayloadValue.of(use.name()));
                    function.put("arguments", PayloadValue.of(use.inputJson()));
                    var call = new LinkedHashMap<String, PayloadValue>();
                    call.put("id", PayloadValue.of(use.id()));
                    call.put("type", PayloadValue.of("function"));
                    call.put("function", PayloadValue.map(function));
                    calls.add(PayloadValue.map(call));
                }
                case Content.ToolResult ignored -> throw new IllegalArgumentException(
                        "an assistant message cannot contain a tool result");
            }
        }
        var assistant = new LinkedHashMap<String, PayloadValue>();
        assistant.put("role", PayloadValue.of("assistant"));
        assistant.put("content", text.isEmpty() ? PayloadValue.NULL : PayloadValue.of(text.toString()));
        if (!calls.isEmpty()) {
            assistant.put("tool_calls", PayloadValue.list(calls));
        }
        target.add(PayloadValue.map(assistant));
    }

    private static PayloadValue message(String role, PayloadValue content) {
        var message = new LinkedHashMap<String, PayloadValue>();
        message.put("role", PayloadValue.of(role));
        message.put("content", content);
        return PayloadValue.map(message);
    }

    Turn readTurn(byte[] body, String requestedModel) throws AssistantProviderException {
        PayloadValue parsed;
        try {
            parsed = PayloadJson.read(body, RESPONSE_LIMITS);
        } catch (RuntimeException unreadable) {
            // Parser diagnostics may quote the invalid token. The upstream body is never log data.
            throw new AssistantProviderException(AssistantOutcome.Reason.PROVIDER_UNREADABLE,
                    "the provider response was malformed or exceeded its parse budget", null);
        }
        if (!(parsed instanceof PayloadValue.MapValue root)
                || !(root.entries().get("choices") instanceof PayloadValue.ListValue choices)
                || choices.values().isEmpty()
                || !(choices.values().get(0) instanceof PayloadValue.MapValue choice)) {
            throw new AssistantProviderException(AssistantOutcome.Reason.PROVIDER_UNREADABLE,
                    "the provider response carried no completion choice", null);
        }
        Map<String, PayloadValue> selected = choice.entries();
        String finishReason = text(selected.get("finish_reason"));
        if ("content_filter".equals(finishReason)) {
            return new Turn.Refused("content_filter");
        }
        if (!(selected.get("message") instanceof PayloadValue.MapValue message)) {
            throw new AssistantProviderException(AssistantOutcome.Reason.PROVIDER_UNREADABLE,
                    "the provider response carried no generated message", null);
        }

        var calls = new ArrayList<Content.ToolUse>();
        var assistantContent = new ArrayList<Content>();
        String answer = text(message.entries().get("content"));
        if (answer != null && !answer.isEmpty()) {
            assistantContent.add(new Content.Text(answer));
        }
        if (message.entries().get("tool_calls") instanceof PayloadValue.ListValue toolCalls) {
            for (PayloadValue value : toolCalls.values()) {
                if (!(value instanceof PayloadValue.MapValue call)
                        || !(call.entries().get("function") instanceof PayloadValue.MapValue function)) {
                    throw unreadableToolCall();
                }
                String id = text(call.entries().get("id"));
                String name = text(function.entries().get("name"));
                String arguments = text(function.entries().get("arguments"));
                if (id == null || name == null || arguments == null) {
                    throw unreadableToolCall();
                }
                var use = new Content.ToolUse(id, name, arguments);
                calls.add(use);
                assistantContent.add(use);
            }
        }
        if (!calls.isEmpty()) {
            return new Turn.ToolCalls(calls, requestedModel, assistantContent);
        }
        if (answer == null || answer.isBlank()) {
            throw new AssistantProviderException(AssistantOutcome.Reason.PROVIDER_UNREADABLE,
                    "the provider response carried no generated text", null);
        }
        return new Turn.Answer(answer, requestedModel, "length".equals(finishReason));
    }

    private static AssistantProviderException unreadableToolCall() {
        return new AssistantProviderException(AssistantOutcome.Reason.PROVIDER_UNREADABLE,
                "the provider response carried a malformed tool call", null);
    }

    private static String text(PayloadValue value) {
        return value instanceof PayloadValue.TextValue text ? text.value() : null;
    }

    private static boolean isLocalHttpHost(String host) {
        String value = host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if ("localhost".equals(value) || value.endsWith(".localhost")
                || "host.docker.internal".equals(value) || "::1".equals(value)) {
            return true;
        }
        try {
            return value.matches("[0-9.]+")
                    && java.net.InetAddress.getByName(value).isLoopbackAddress();
        } catch (java.net.UnknownHostException malformedLiteral) {
            return false;
        }
    }
}
