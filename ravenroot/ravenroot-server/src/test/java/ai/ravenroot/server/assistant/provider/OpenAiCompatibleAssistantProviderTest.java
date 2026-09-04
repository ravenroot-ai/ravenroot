package ai.ravenroot.server.assistant.provider;

import ai.ravenroot.server.assistant.AssistantCredential;
import ai.ravenroot.server.assistant.AssistantGraphProposal;
import ai.ravenroot.server.assistant.AssistantOutcome;
import ai.ravenroot.server.assistant.oauth.ScriptedHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wire and failure contract for the dedicated OpenAI-compatible authoring-Assistant provider. */
class OpenAiCompatibleAssistantProviderTest {

    private Fixture server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void aCredentialFreeLocalHttpFixtureReturnsGeneratedTextNonStreaming() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = fixture(200,
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"from llama.cpp\"}}]}",
                Duration.ZERO, requestBody);

        AssistantProvider.Turn turn = localProvider(Duration.ofSeconds(2)).complete(oneTurn());

        var answer = assertInstanceOf(AssistantProvider.Turn.Answer.class, turn);
        assertEquals("from llama.cpp", answer.text());
        assertEquals("local-model", answer.model());
        assertFalse(answer.truncated());
        assertTrue(requestBody.get().contains("\"stream\":false"), requestBody::get);
        assertTrue(requestBody.get().contains("\"model\":\"local-model\""), requestBody::get);
        assertTrue(requestBody.get().contains("\"role\":\"system\""), requestBody::get);
    }

    @Test
    void toolCallsRoundTripThroughTheExistingAssistantPort() throws Exception {
        String body = """
                {"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"read_graph","arguments":"{\\"id\\":7}"}}
                ]}}]}""";
        server = fixture(200, body, Duration.ZERO, new AtomicReference<>());
        var request = new AssistantProvider.Request("local-model", "read only",
                List.of(AssistantProvider.Message.author("inspect it")),
                List.of(new AssistantProvider.ToolSpec("read_graph", "read graph",
                        "{\"type\":\"object\"}")), 128);

        var calls = assertInstanceOf(AssistantProvider.Turn.ToolCalls.class,
                localProvider(Duration.ofSeconds(2)).complete(request));

        assertEquals(1, calls.calls().size());
        assertEquals("read_graph", calls.calls().get(0).name());
        assertEquals("{\"id\":7}", calls.calls().get(0).inputJson());
    }

    @Test
    void discriminatedProposalSchemaAndSafeCorrectionResultReachTheOpenAiWire() {
        var proposalUse = new AssistantProvider.Content.ToolUse("proposal-1",
                AssistantGraphProposal.TOOL_NAME,
                "{\"summary\":\"Add a node\",\"operations\":[{\"op\":\"create-node\"}]}");
        var feedback = new AssistantProvider.Content.ToolResult("proposal-1",
                "{\"code\":\"INVALID_GRAPH_PROPOSAL\",\"problem\":\"MISSING_REQUIRED_FIELDS\","
                        + "\"fields\":[\"ref\"]}", true);
        var request = new AssistantProvider.Request("local-model", "authoring",
                List.of(AssistantProvider.Message.author("add a log node"),
                        new AssistantProvider.Message(AssistantProvider.Role.ASSISTANT,
                                List.of(proposalUse)),
                        new AssistantProvider.Message(AssistantProvider.Role.AUTHOR,
                                List.of(feedback))),
                List.of(AssistantGraphProposal.toolSpec()), 128);

        String wire = provider(URI.create("https://models.example/v1/chat/completions"),
                AssistantCredential.ofNullable("wire-secret"), false, Duration.ofSeconds(1))
                .writeRequest(request);

        assertTrue(wire.contains("\"oneOf\""), wire);
        assertTrue(wire.contains("\"const\":\"create-node\""), wire);
        assertTrue(wire.contains("MISSING_REQUIRED_FIELDS"), wire);
        assertTrue(wire.contains("\"role\":\"tool\""), wire);
        assertTrue(wire.contains("proposal-1"), wire);
        assertFalse(wire.contains("wire-secret"), "credentials belong only in the HTTP header");
    }

    @Test
    void redirectsAndProviderHttpFailuresAreClassifiedWithoutReadingTheirBodies() throws Exception {
        String plantedBody = "upstream-secret-body-and-prompt";
        for (Map.Entry<Integer, AssistantOutcome.Reason> example : Map.of(
                302, AssistantOutcome.Reason.EGRESS_REFUSED,
                401, AssistantOutcome.Reason.PROVIDER_REJECTED,
                429, AssistantOutcome.Reason.PROVIDER_REJECTED,
                503, AssistantOutcome.Reason.PROVIDER_UNAVAILABLE).entrySet()) {
            server = fixture(example.getKey(), plantedBody, Duration.ZERO, new AtomicReference<>());
            AssistantProviderException failure = assertThrows(AssistantProviderException.class,
                    () -> localProvider(Duration.ofSeconds(2)).complete(oneTurn()));
            assertEquals(example.getValue(), failure.reason());
            assertFalse(failure.getMessage().contains(plantedBody));
            server.close();
            server = null;
        }
    }

    @Test
    void nonJsonErrorBodyStillReachesAssistantStatusClassification() throws Exception {
        String plantedBody = "plain upstream secret";
        server = new Fixture(503, plantedBody.getBytes(StandardCharsets.UTF_8), Duration.ZERO,
                new AtomicReference<>(), "text/plain");
        AssistantProviderException failure = assertThrows(AssistantProviderException.class,
                () -> localProvider(Duration.ofSeconds(2)).complete(oneTurn()));
        assertEquals(AssistantOutcome.Reason.PROVIDER_UNAVAILABLE, failure.reason());
        assertFalse(failure.getMessage().contains(plantedBody));
    }

    @Test
    void assistantProjectionCeilingHasAnExactAndOneByteOverBoundary() throws Exception {
        var turn = new AssistantProvider.Turn.Answer("answer", "m", false);
        long exact = 64L + "answer".getBytes(StandardCharsets.UTF_8).length + 1L;
        var accepted = new ai.ravenroot.api.node.service.ExternalIoLimits(1, 1, 1, exact, 1,
                Duration.ofSeconds(1), Duration.ofSeconds(1), Set.of(), Set.of("identity"));
        assertSame(turn, AssistantOutputLimit.requireWithin(turn, accepted));
        var refused = new ai.ravenroot.api.node.service.ExternalIoLimits(1, 1, 1, exact - 1, 1,
                Duration.ofSeconds(1), Duration.ofSeconds(1), Set.of(), Set.of("identity"));
        AssistantProviderException failure = assertThrows(AssistantProviderException.class,
                () -> AssistantOutputLimit.requireWithin(turn, refused));
        assertEquals(AssistantOutcome.Reason.PROVIDER_UNREADABLE, failure.reason());
    }

    @Test
    void timeoutMalformedResponseAndMissingGeneratedTextAreNamedFailures() throws Exception {
        server = fixture(200, "{\"choices\":[]}", Duration.ofMillis(300), new AtomicReference<>());
        AssistantProviderException timeout = assertThrows(AssistantProviderException.class,
                () -> localProvider(Duration.ofMillis(30)).complete(oneTurn()));
        assertEquals(AssistantOutcome.Reason.PROVIDER_UNAVAILABLE, timeout.reason());
        server.close();

        String providerBodyCanary = "provider-body-canary-not-json";
        server = fixture(200, providerBodyCanary, Duration.ZERO, new AtomicReference<>());
        AssistantProviderException malformed = assertThrows(AssistantProviderException.class,
                () -> localProvider(Duration.ofSeconds(2)).complete(oneTurn()));
        assertEquals(AssistantOutcome.Reason.PROVIDER_UNREADABLE, malformed.reason());
        assertFalse(malformed.getMessage().contains(providerBodyCanary));
        assertEquals(null, malformed.getCause(),
                "a parser cause can retain provider-controlled body fragments and must not escape");
        server.close();

        server = fixture(200,
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"\"}}]}",
                Duration.ZERO, new AtomicReference<>());
        AssistantProviderException empty = assertThrows(AssistantProviderException.class,
                () -> localProvider(Duration.ofSeconds(2)).complete(oneTurn()));
        assertEquals(AssistantOutcome.Reason.PROVIDER_UNREADABLE, empty.reason());
    }

    @Test
    void responseOverTheTransportBudgetIsRefused() throws Exception {
        String oversized = "x".repeat(OpenAiCompatibleAssistantProvider.RESPONSE_LIMITS.maxEncodedBytes() + 1);
        server = fixture(200, oversized, Duration.ZERO, new AtomicReference<>());

        AssistantProviderException failure = assertThrows(AssistantProviderException.class,
                () -> localProvider(Duration.ofSeconds(2)).complete(oneTurn()));

        assertEquals(AssistantOutcome.Reason.PROVIDER_UNREADABLE, failure.reason());
    }

    @Test
    void theProviderItselfRefusesCredentialBearingOrNonLocalHttp() {
        URI local = URI.create("http://localhost:8081/v1/chat/completions");
        URI remote = URI.create("http://models.example/v1/chat/completions");

        assertThrows(IllegalArgumentException.class, () -> provider(local,
                AssistantCredential.ofNullable("operator-secret"), true, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> provider(local, null, false, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> provider(remote, null, true, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> provider(
                URI.create("http://secret@localhost:8081/v1/chat/completions"), null, true,
                Duration.ofSeconds(1)));

        new OpenAiCompatibleAssistantProvider(HttpClient.newHttpClient(),
                URI.create("http://[::1]:8081/v1/chat/completions"), "local-model", null,
                Duration.ofSeconds(1), true);
    }

    @Test
    void anHttpsOperatorCredentialIsBearerAndNeverPartOfTheDocument() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        String secret = "operator-secret-canary";
        var provider = new OpenAiCompatibleAssistantProvider(
                new ScriptedHttpClient(requests, bodies, new ArrayDeque<>(List.of(
                        "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"ok\"}}]}"))),
                URI.create("https://models.example/v1/chat/completions"), "hosted-model",
                AssistantCredential.ofNullable(secret), Duration.ofSeconds(1), false);

        assertInstanceOf(AssistantProvider.Turn.Answer.class, provider.complete(oneTurn()));

        assertEquals("Bearer " + secret, requests.get(0).headers()
                .firstValue("authorization").orElseThrow());
        assertFalse(bodies.get(0).contains(secret), "the credential belongs only in the header");
    }

    private OpenAiCompatibleAssistantProvider localProvider(Duration timeout) {
        return provider(URI.create("http://127.0.0.1:" + server.port()
                + "/v1/chat/completions"), null, true, timeout);
    }

    private static OpenAiCompatibleAssistantProvider provider(URI endpoint, AssistantCredential credential,
                                                               boolean allowLocalHttp, Duration timeout) {
        return new OpenAiCompatibleAssistantProvider(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                endpoint, "local-model", credential, timeout, allowLocalHttp);
    }

    private static AssistantProvider.Request oneTurn() {
        return new AssistantProvider.Request("local-model", "read only",
                List.of(AssistantProvider.Message.author("author prompt")), List.of(), 128);
    }

    private Fixture fixture(int status, String response, Duration delay,
                            AtomicReference<String> requestBody) throws IOException {
        return new Fixture(status, response.getBytes(StandardCharsets.UTF_8), delay, requestBody);
    }

    /** A one-request HTTP/1.1 fixture that does not initialize JDK HttpServer's process-global state. */
    private static final class Fixture implements AutoCloseable {
        private final ServerSocket listener;
        private final Thread thread;

        private Fixture(int status, byte[] response, Duration delay,
                        AtomicReference<String> requestBody) throws IOException {
            this(status, response, delay, requestBody, "application/json");
        }

        private Fixture(int status, byte[] response, Duration delay,
                        AtomicReference<String> requestBody, String mediaType) throws IOException {
            listener = new ServerSocket();
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            thread = Thread.ofPlatform().name("openai-compatible-fixture").start(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(5_000);
                    InputStream input = socket.getInputStream();
                    byte[] head = readHead(input);
                    String headers = new String(head, StandardCharsets.ISO_8859_1);
                    int contentLength = headers.lines()
                            .filter(line -> line.regionMatches(true, 0, "content-length:", 0, 15))
                            .map(line -> line.substring(15).trim())
                            .mapToInt(Integer::parseInt)
                            .findFirst().orElse(0);
                    requestBody.set(new String(input.readNBytes(contentLength), StandardCharsets.UTF_8));
                    if (!delay.isZero()) {
                        Thread.sleep(delay);
                    }
                    String reason = status >= 500 ? "Unavailable"
                            : status >= 400 ? "Rejected"
                            : status >= 300 ? "Redirect" : "OK";
                    byte[] responseHead = ("HTTP/1.1 " + status + " " + reason + "\r\n"
                            + "Content-Type: " + mediaType + "\r\n"
                            + "Content-Length: " + response.length + "\r\n"
                            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
                    try {
                        OutputStream output = socket.getOutputStream();
                        output.write(responseHead);
                        output.write(response);
                        output.flush();
                    } catch (IOException clientLeft) {
                        // Expected in the timeout case.
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // Closing the listener ends a fixture whose client already left.
                }
            });
        }

        private int port() {
            return listener.getLocalPort();
        }

        private static byte[] readHead(InputStream input) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int matched = 0;
            while (head.size() < 64 * 1024) {
                int value = input.read();
                if (value < 0) {
                    break;
                }
                head.write(value);
                matched = switch (matched) {
                    case 0 -> value == '\r' ? 1 : 0;
                    case 1 -> value == '\n' ? 2 : 0;
                    case 2 -> value == '\r' ? 3 : 0;
                    case 3 -> value == '\n' ? 4 : 0;
                    default -> 4;
                };
                if (matched == 4) {
                    return head.toByteArray();
                }
            }
            throw new IOException("fixture received no complete HTTP header");
        }

        @Override
        public void close() {
            try {
                listener.close();
                thread.join(1_000);
            } catch (IOException ignored) {
                // Already closed.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
