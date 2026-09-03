package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON-RPC 2.0 documents this bundle exchanges with an MCP server, and the reader for what comes
 * back. No transport, no state, no I/O: bytes in, bytes out.
 *
 * <h2>Why a client is written here at all</h2>
 * <p>There is no JSON-RPC elsewhere in this reactor and no other MCP transport of any kind. This is the smallest client
 * that can honestly be called one — initialize, the initialized notification, {@code tools/list},
 * {@code tools/call} — and it stops there on purpose. Resources, prompts, sampling, completion,
 * subscriptions and server-initiated requests are all part of MCP and none of them is part of an
 * agent node calling a tool.</p>
 *
 * <h2>Why this does not provide the server-side assistant client</h2>
 * <p>The inspector's assistant needs a client <em>in the server</em>, with an OAuth
 * authorization-code flow and a callback route that does not exist. Here the consumer is a bundle
 * node, the transport is the managed channel and the credential is an operator binding placed by the
 * runtime. If this document layer later turns out to be reusable there, promoting it is a decision to
 * take then — and promoting it would mean moving it into the core, which the {@code extension}
 * boundary forbids here.</p>
 *
 * <h2>Two response encodings, because Streamable HTTP has two</h2>
 * <p>An MCP server may answer a POST with {@code application/json} or with an SSE stream carrying the
 * same response. This reader accepts both and treats them as one thing, because they are: the second
 * is the first with {@code data:} in front of it. It does not implement streaming — it reads a
 * complete body the managed channel already bounded — and a stream carrying more than one response
 * yields the first one that answers.</p>
 */
final class McpProtocol {

    /**
     * The revision this client implements.
     *
     * <p>Sent in {@code initialize} and, once negotiated, in the {@code MCP-Protocol-Version} header
     * of every later request, which is what the specification asks of an HTTP client. A server that
     * answers with a different version is not argued with: this bundle uses only methods that have
     * been stable across revisions, and refusing to talk to a server over a version string would
     * refuse working servers for a difference this client cannot observe.</p>
     */
    static final String PROTOCOL_VERSION = "2025-06-18";

    /** What this client calls itself in {@code initialize}. Not a credential and not a fingerprint. */
    static final String CLIENT_NAME = "ravenroot-ai";

    private McpProtocol() {
    }

    /** One tool as a server announced it, with only the three members this bundle carries. */
    record Announced(String name, String description, PayloadValue.MapValue schema) {
    }

    static byte[] initialize(long id) {
        var clientInfo = new LinkedHashMap<String, PayloadValue>();
        clientInfo.put("name", PayloadValue.of(CLIENT_NAME));
        clientInfo.put("version", PayloadValue.of("1.0.0"));

        var params = new LinkedHashMap<String, PayloadValue>();
        params.put("protocolVersion", PayloadValue.of(PROTOCOL_VERSION));
        // Empty and honest: this client consumes tools and offers the server nothing. Declaring a
        // capability it does not implement is how a server ends up sending a request nobody answers.
        params.put("capabilities", PayloadValue.map(Map.of()));
        params.put("clientInfo", PayloadValue.map(clientInfo));
        return request(id, "initialize", PayloadValue.map(params));
    }

    /** The notification that completes the handshake. No id, so no answer is expected. */
    static byte[] initialized() {
        var root = new LinkedHashMap<String, PayloadValue>();
        root.put("jsonrpc", PayloadValue.of("2.0"));
        root.put("method", PayloadValue.of("notifications/initialized"));
        return write(root);
    }

    static byte[] listTools(long id) {
        return request(id, "tools/list", null);
    }

    /**
     * @param arguments the model's {@code arguments} string, already parsed into an object by
     *     {@link #readArguments(String)} — never the raw string, so nothing model-authored is
     *     concatenated into a document
     */
    static byte[] callTool(long id, String toolName, PayloadValue.MapValue arguments) {
        var params = new LinkedHashMap<String, PayloadValue>();
        params.put("name", PayloadValue.of(toolName));
        params.put("arguments", arguments);
        return request(id, "tools/call", PayloadValue.map(params));
    }

    /**
     * Parses the {@code arguments} string a model produced into the object the wire needs.
     *
     * <p>Model-authored text, so every failure mode here is expected traffic rather than an anomaly:
     * an empty string (a tool with no arguments), a JSON scalar, a truncated document. All of them
     * become one refusal the model can correct, and none of them becomes an exception type the loop
     * has to know about.</p>
     */
    static PayloadValue.MapValue readArguments(String argumentsJson) {
        String raw = argumentsJson == null ? "" : argumentsJson.strip();
        if (raw.isEmpty()) {
            // A tool with no required arguments: several endpoints send "" rather than "{}", and
            // refusing that would refuse a correct call.
            return new PayloadValue.MapValue(Map.of());
        }
        try {
            PayloadValue parsed = PayloadJson.read(raw.getBytes(StandardCharsets.UTF_8),
                    new PayloadLimits(64 * 1024, 24, 1_000, 10_000, 32 * 1024, 256));
            if (parsed instanceof PayloadValue.MapValue object) {
                return object;
            }
        } catch (RuntimeException unreadable) {
            throw new McpRefusal(McpRefusal.Reason.ARGUMENTS_UNREADABLE);
        }
        throw new McpRefusal(McpRefusal.Reason.ARGUMENTS_UNREADABLE);
    }

    /**
     * Reads a response body as the {@code result} member of a JSON-RPC response.
     *
     * @param contentType the response's declared type, which decides whether the body is read as JSON
     *     or as an SSE stream carrying JSON
     * @param maxResponseBytes the profile's ceiling, used as the reader's own encoded-byte budget so
     *     the transport bound and the parse bound cannot drift apart — the rule {@link AgentTurn}
     *     states for the model endpoint, applied to the other far end
     */
    static PayloadValue.MapValue readResult(byte[] body, String contentType, int maxResponseBytes) {
        if (body.length > maxResponseBytes) {
            throw new McpRefusal(McpRefusal.Reason.SERVER_RESPONSE_TOO_LARGE);
        }
        PayloadValue parsed = parse(body, contentType, maxResponseBytes);
        if (!(parsed instanceof PayloadValue.MapValue root)) {
            throw new McpRefusal(McpRefusal.Reason.SERVER_RESPONSE_UNREADABLE);
        }
        // The error member is read BEFORE the result, for the reason AgentTurn reads finish_reason
        // first: a JSON-RPC error arrives as a perfectly successful HTTP 200, and a reader that looks
        // for the happy member first reports "unreadable" for a server that said exactly what was
        // wrong.
        if (root.entries().get("error") != null) {
            throw new McpRefusal(McpRefusal.Reason.SERVER_REFUSED);
        }
        if (!(root.entries().get("result") instanceof PayloadValue.MapValue result)) {
            throw new McpRefusal(McpRefusal.Reason.SERVER_RESPONSE_UNREADABLE);
        }
        return result;
    }

    /**
     * The tools a server announced, keeping only the three members this bundle carries.
     *
     * <p>An entry without a usable name is dropped rather than refused, the way {@link AgentTurn}
     * drops one malformed tool call among good ones: one unreadable entry in a server's catalogue is
     * not a reason to deny an agent the rest of it. What is <em>not</em> dropped silently is a tool
     * outside the operator's allow-list — that one is filtered by {@link McpToolset}, which is where
     * the decision belongs, because here there is no profile to consult.</p>
     */
    static List<Announced> readTools(PayloadValue.MapValue result) {
        if (!(result.entries().get("tools") instanceof PayloadValue.ListValue list)) {
            throw new McpRefusal(McpRefusal.Reason.SERVER_RESPONSE_UNREADABLE);
        }
        var announced = new ArrayList<Announced>(list.values().size());
        for (PayloadValue element : list.values()) {
            if (!(element instanceof PayloadValue.MapValue entry)) {
                continue;
            }
            String name = text(entry.entries().get("name"));
            if (name == null || name.isBlank()) {
                continue;
            }
            String description = text(entry.entries().get("description"));
            PayloadValue.MapValue schema =
                    entry.entries().get("inputSchema") instanceof PayloadValue.MapValue value
                            ? value
                            : emptyObjectSchema();
            announced.add(new Announced(name, description == null ? "" : description, schema));
        }
        return List.copyOf(announced);
    }

    /**
     * The text of a {@code tools/call} result, which is what becomes the {@code tool} message.
     *
     * <p>Only {@code text} parts are carried. MCP also allows image, audio and embedded-resource
     * parts, and this bundle names them rather than dropping them: a model that asked for a diagram
     * and received silence would conclude the tool is broken and retry, which costs turns to arrive
     * at the same place. A named absence lets it choose differently on the first try.</p>
     *
     * <p>{@code isError} is <b>not</b> turned into a refusal. It is the server saying the tool ran
     * and failed — a compile error, a missing record — which is a result the model is supposed to
     * read and act on. The refusal path is for a server that would not or could not run it at all.</p>
     */
    static String readToolContent(PayloadValue.MapValue result) {
        if (!(result.entries().get("content") instanceof PayloadValue.ListValue parts)) {
            throw new McpRefusal(McpRefusal.Reason.SERVER_RESPONSE_UNREADABLE);
        }
        var text = new StringBuilder();
        int skipped = 0;
        for (PayloadValue element : parts.values()) {
            if (!(element instanceof PayloadValue.MapValue part)) {
                skipped++;
                continue;
            }
            String type = text(part.entries().get("type"));
            String value = text(part.entries().get("text"));
            if ("text".equals(type) && value != null) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(value);
            } else {
                skipped++;
            }
        }
        if (skipped > 0) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append("[").append(skipped)
                    .append(" part(s) of this result were not text and were not included.]");
        }
        // Never empty: AgentTool's contract forbids it, because an empty tool message reads to a
        // model as a call that succeeded and returned nothing.
        return text.isEmpty() ? "The tool returned no content." : text.toString();
    }

    /** Whether a syntactically valid tool result says the remote effect failed. */
    static boolean isToolError(PayloadValue.MapValue result) {
        return result.entries().get("isError") instanceof PayloadValue.BooleanValue error
                && error.value();
    }

    private static PayloadValue.MapValue emptyObjectSchema() {
        var schema = new LinkedHashMap<String, PayloadValue>();
        schema.put("type", PayloadValue.of("object"));
        schema.put("properties", PayloadValue.map(Map.of()));
        return new PayloadValue.MapValue(Map.copyOf(schema));
    }

    private static PayloadValue parse(byte[] body, String contentType, int maxResponseBytes) {
        byte[] document = contentType != null && contentType.toLowerCase(java.util.Locale.ROOT)
                .contains("text/event-stream")
                ? eventStreamPayload(body)
                : body;
        try {
            return PayloadJson.read(document, new PayloadLimits(Math.max(1, maxResponseBytes), 48,
                    10_000, 200_000, Math.max(1, maxResponseBytes / 2), 256));
        } catch (RuntimeException unreadable) {
            throw new McpRefusal(McpRefusal.Reason.SERVER_RESPONSE_UNREADABLE);
        }
    }

    /**
     * The first SSE event's data, joined across its {@code data:} lines.
     *
     * <p>Not a streaming reader: the managed channel already delivered a complete, bounded body, so
     * this only has to undo the framing. A stream carrying several events yields the first, which for
     * a request/response exchange is the response.</p>
     */
    private static byte[] eventStreamPayload(byte[] body) {
        var data = new StringBuilder();
        for (String line : new String(body, StandardCharsets.UTF_8).split("\n", -1)) {
            String stripped = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (stripped.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(stripped.substring("data:".length()).stripLeading());
            } else if (stripped.isEmpty() && !data.isEmpty()) {
                break;
            }
        }
        if (data.isEmpty()) {
            throw new McpRefusal(McpRefusal.Reason.SERVER_RESPONSE_UNREADABLE);
        }
        return data.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] request(long id, String method, PayloadValue params) {
        var root = new LinkedHashMap<String, PayloadValue>();
        root.put("jsonrpc", PayloadValue.of("2.0"));
        root.put("id", PayloadValue.of(id));
        root.put("method", PayloadValue.of(method));
        if (params != null) {
            root.put("params", params);
        }
        return write(root);
    }

    private static byte[] write(Map<String, PayloadValue> root) {
        // The audited writer, and not string concatenation, for the reason AgentTurn gives: a tool
        // argument is model-authored text and a hand-written writer is exactly what a quote, a
        // backslash or a lone surrogate would put to the test.
        return PayloadJson.write(PayloadValue.map(root)).getBytes(StandardCharsets.UTF_8);
    }

    private static String text(PayloadValue value) {
        return value instanceof PayloadValue.TextValue textValue ? textValue.value() : null;
    }
}
