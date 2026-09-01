package ai.ravenroot.extensions.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for an MCP server over Streamable HTTP, in two transports over one responder.
 *
 * <p>The responder is the useful part and is deliberately separate from the socket: the unit tests
 * drive it in memory through {@link AiTestSupport.RoutedHttp}, and {@link AiBundleEndToEndTest}
 * drives the same responder through a real loopback listener. One implementation of the protocol, so
 * a fixture that is wrong is wrong in both places rather than in only the one nobody reads.</p>
 *
 * <p>It answers the four methods this bundle sends and nothing else: an MCP feature that is not
 * initialize, the initialized notification, {@code tools/list} or {@code tools/call} is not exercised
 * by the client under test, and a double that implemented it would be asserting about itself.</p>
 */
final class McpDouble {

    /** One HTTP answer, as the responder decided it. */
    record Answer(int status, String contentType, byte[] body, Map<String, List<String>> headers) {
        Answer(int status, String contentType, byte[] body) {
            this(status, contentType, body, Map.of());
        }
    }

    /** The handle a stateful server issues and then insists on seeing again. */
    static final String SESSION_ID = "sess-1";

    /** How this server misbehaves, when it does. Exactly one is in force at a time. */
    enum Mode {
        /** Answers correctly. */
        HEALTHY,
        /** Answers 503, which is what an operator sees from a server that is down behind a proxy. */
        UNREACHABLE,
        /** Does not answer inside the caller's deadline. */
        SLOW,
        /** Answers with a well-formed document far larger than any profile ceiling. */
        TOO_LARGE,
        /** Answers 200 with something that is not a JSON-RPC response. */
        UNREADABLE,
        /** Answers a JSON-RPC error rather than a result. */
        ERRORING,
        /** Answers correctly, framed as a single SSE event. */
        EVENT_STREAM
    }

    private final String name;
    private final List<String> announced = new CopyOnWriteArrayList<>();
    private final List<String> calledTools = new CopyOnWriteArrayList<>();
    private final List<String> receivedMethods = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Mode mode = Mode.HEALTHY;
    /** Issues a session handle on initialize and refuses any later request that arrives without it. */
    private volatile boolean stateful;
    private final List<String> observedSessionIds = new CopyOnWriteArrayList<>();
    /** Turns on only after {@code tools/list}, so discovery succeeds and the call does not. */
    private volatile Mode modeFromCallOnwards;
    private volatile String result = "ok";
    private volatile long slowMillis = 5_000;

    McpDouble(String name, String... tools) {
        this.name = name;
        announced.addAll(List.of(tools));
    }

    McpDouble announcing(String... tools) {
        announced.clear();
        announced.addAll(List.of(tools));
        return this;
    }

    McpDouble in(Mode value) {
        mode = value;
        return this;
    }

    /**
     * Behaves like a stateful Streamable HTTP server: issues {@code Mcp-Session-Id} on
     * {@code initialize} and answers 404 to any later request that does not carry it back.
     *
     * <p>The majority behaviour of real MCP servers, and the one a double that only ever answers
     * cannot express — which is how a client that loses the handle between the handshake and the
     * first tool call passes every test and fails against every stateful server.</p>
     */
    McpDouble stateful() {
        stateful = true;
        return this;
    }

    /** The session handle carried by each request, in order; {@code ""} where none was sent. */
    List<String> observedSessionIds() {
        return List.copyOf(observedSessionIds);
    }

    /** Healthy through discovery, then {@code value} for every {@code tools/call}. */
    McpDouble failingCallsWith(Mode value) {
        modeFromCallOnwards = value;
        return this;
    }

    McpDouble returning(String text) {
        result = text;
        return this;
    }

    McpDouble slowBy(long millis) {
        slowMillis = millis;
        return this;
    }

    /** The tools this server was asked to run, in order. Empty means it was never called. */
    List<String> calledTools() {
        return List.copyOf(calledTools);
    }

    /** The JSON-RPC methods it received, in order. */
    List<String> receivedMethods() {
        return List.copyOf(receivedMethods);
    }

    int calls() {
        return calls.get();
    }

    /**
     * Answers one JSON-RPC request.
     *
     * <p>Parsed with a regular expression rather than the payload reader on purpose: a double that
     * used the same parser as the code under test would agree with it about a malformed document,
     * which is the one thing a double must not do.</p>
     */
    Answer respond(byte[] request) {
        return respond(request, Map.of());
    }

    Answer respond(byte[] request, Map<String, List<String>> headers) {
        calls.incrementAndGet();
        String document = new String(request, StandardCharsets.UTF_8);
        String method = between(document, "\"method\":\"", "\"");
        receivedMethods.add(method);
        String carried = header(headers, "mcp-session-id");
        observedSessionIds.add(carried);
        if (stateful && !"initialize".equals(method) && !SESSION_ID.equals(carried)) {
            // What a real stateful server does, and the reason this double can express it: a client
            // that forgot the handle looks identical to one that never handshook.
            return new Answer(404, "text/plain", "no session".getBytes(StandardCharsets.UTF_8));
        }
        Mode effective = "tools/call".equals(method) && modeFromCallOnwards != null
                ? modeFromCallOnwards
                : mode;
        switch (effective) {
            case UNREACHABLE -> {
                return new Answer(503, "text/plain", "unavailable".getBytes(StandardCharsets.UTF_8));
            }
            case SLOW -> {
                // Status -1 means "this exchange outlives the caller's deadline". The two transports
                // express that differently and neither may fake it: in memory the channel's own
                // DEADLINE_EXCEEDED is raised, and over a socket the server really does sleep so the
                // real deadline in the real channel is the thing that fires.
                return new Answer(-1, "", new byte[0]);
            }
            case UNREADABLE -> {
                return json("<html>not json at all</html>");
            }
            case ERRORING -> {
                return json("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,"
                        + "\"message\":\"no\"}}");
            }
            default -> {
                // falls through to the healthy answers below
            }
        }
        String body = switch (method) {
            case "initialize" -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\""
                    + McpProtocol.PROTOCOL_VERSION + "\",\"capabilities\":{\"tools\":{}},"
                    + "\"serverInfo\":{\"name\":\"" + name + "\",\"version\":\"1.0\"}}}";
            case "notifications/initialized" -> "";
            case "tools/list" -> toolsList(effective == Mode.TOO_LARGE);
            case "tools/call" -> {
                calledTools.add(between(document, "\"name\":\"", "\""));
                yield effective == Mode.TOO_LARGE
                        ? toolResult("x".repeat(3 * 1024 * 1024))
                        : toolResult(result);
            }
            default -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"no\"}}";
        };
        if (body.isEmpty()) {
            // What a server answers a notification with: accepted, nothing to read.
            return new Answer(202, "text/plain", new byte[0]);
        }
        if (effective == Mode.EVENT_STREAM) {
            return new Answer(200, "text/event-stream",
                    ("event: message\ndata: " + body + "\n\n").getBytes(StandardCharsets.UTF_8),
                    issued(method));
        }
        return new Answer(200, "application/json", body.getBytes(StandardCharsets.UTF_8),
                issued(method));
    }

    private Map<String, List<String>> issued(String method) {
        return stateful && "initialize".equals(method)
                ? Map.of("mcp-session-id", List.of(SESSION_ID))
                : Map.of();
    }

    private static String header(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null
                    && entry.getKey().toLowerCase(java.util.Locale.ROOT).equals(name)
                    && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return "";
    }

    private String toolsList(boolean oversized) {
        var tools = new StringBuilder();
        for (String tool : announced) {
            if (!tools.isEmpty()) {
                tools.append(',');
            }
            tools.append("{\"name\":\"").append(tool).append("\",\"description\":\"")
                    .append(oversized ? "d".repeat(3 * 1024 * 1024) : "What " + tool + " does.")
                    .append("\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}");
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[" + tools + "]}}";
    }

    private static String toolResult(String text) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\""
                + text + "\"}],\"isError\":false}}";
    }

    private static Answer json(String body) {
        return new Answer(200, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private static String between(String document, String open, String close) {
        int start = document.indexOf(open);
        if (start < 0) {
            return "";
        }
        start += open.length();
        int end = document.indexOf(close, start);
        return end < 0 ? "" : document.substring(start, end);
    }

    /**
     * The same responder behind a real loopback socket, for the end-to-end case.
     *
     * <p>Bound to {@link InetAddress#getLoopbackAddress()} with an ephemeral port, so a test run opens
     * no reachable listener — the rule {@link ChatCompletionsDouble} states and this one keeps.</p>
     */
    static final class Endpoint implements AutoCloseable {
        private final HttpServer server;
        private final McpDouble responder;

        private Endpoint(HttpServer server, McpDouble responder) {
            this.server = server;
            this.responder = responder;
        }

        static Endpoint start(McpDouble responder) throws IOException {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            var endpoint = new Endpoint(server, responder);
            server.createContext("/mcp", endpoint::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return endpoint;
        }

        String url() {
            return "http://" + host() + ":" + port() + "/mcp";
        }

        String host() {
            return server.getAddress().getAddress().getHostAddress();
        }

        int port() {
            return server.getAddress().getPort();
        }

        McpDouble responder() {
            return responder;
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] request;
            try (InputStream input = exchange.getRequestBody()) {
                request = input.readAllBytes();
            }
            var incoming = new java.util.LinkedHashMap<String, List<String>>();
            exchange.getRequestHeaders().forEach(incoming::put);
            Answer answer = responder.respond(request, incoming);
            if (answer.status() < 0) {
                try {
                    Thread.sleep(responder.slowMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                answer = json("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}");
            }
            exchange.getResponseHeaders().set("Content-Type", answer.contentType());
            answer.headers().forEach((name, values) ->
                    exchange.getResponseHeaders().set(name, values.get(0)));
            exchange.sendResponseHeaders(answer.status(),
                    answer.body().length == 0 ? -1 : answer.body().length);
            try (var output = exchange.getResponseBody()) {
                output.write(answer.body());
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** The headers an operator's grant must name for this bundle to talk MCP at all. */
    static final List<String> REQUIRED_GRANT_HEADERS =
            List.of("content-type", "accept", "mcp-protocol-version", "mcp-session-id");

    static Map<String, List<String>> jsonContentType() {
        return Map.of("content-type", List.of("application/json"));
    }
}
