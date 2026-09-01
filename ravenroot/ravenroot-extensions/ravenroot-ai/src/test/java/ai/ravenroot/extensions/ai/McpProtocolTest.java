package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JSON-RPC document layer on its own: what is written, and everything that is refused. */
class McpProtocolTest {

    @Test
    @DisplayName("initialize declares the version, an empty capability set and this client")
    void initializeDeclaresWhatTheHandshakeNeeds() {
        var root = read(McpProtocol.initialize(1));

        assertEquals(PayloadValue.of("2.0"), root.entries().get("jsonrpc"));
        assertEquals(PayloadValue.of("initialize"), root.entries().get("method"));
        var params = (PayloadValue.MapValue) root.entries().get("params");
        assertEquals(PayloadValue.of(McpProtocol.PROTOCOL_VERSION),
                params.entries().get("protocolVersion"));
        // Empty and honest: this client consumes tools and offers the server nothing. A capability
        // declared and not implemented is a request nobody answers.
        assertEquals(0, ((PayloadValue.MapValue) params.entries().get("capabilities")).entries().size());
    }

    @Test
    @DisplayName("the initialized notification carries no id, because nothing answers it")
    void theNotificationCarriesNoId() {
        assertFalse(read(McpProtocol.initialized()).entries().containsKey("id"));
    }

    @Test
    @DisplayName("a tool call embeds the model's arguments as a parsed object, never as text")
    void aToolCallEmbedsParsedArguments() {
        var arguments = McpProtocol.readArguments("{\"query\":\"a \\\"quoted\\\" thing\"}");
        var root = read(McpProtocol.callTool(7, "search", arguments));

        var params = (PayloadValue.MapValue) root.entries().get("params");
        assertEquals(PayloadValue.of("search"), params.entries().get("name"));
        var sent = (PayloadValue.MapValue) params.entries().get("arguments");
        // Round-tripped through the audited writer rather than concatenated, which is the property a
        // quote inside model-authored text actually tests.
        assertEquals(PayloadValue.of("a \"quoted\" thing"), sent.entries().get("query"));
    }

    @Test
    @DisplayName("empty arguments are an empty object; a non-object is a refusal the model can correct")
    void argumentsAreReadOrRefused() {
        assertEquals(0, McpProtocol.readArguments("").entries().size());
        assertEquals(0, McpProtocol.readArguments("   ").entries().size());
        assertEquals(0, McpProtocol.readArguments(null).entries().size());

        assertEquals(McpRefusal.Reason.ARGUMENTS_UNREADABLE,
                assertThrows(McpRefusal.class, () -> McpProtocol.readArguments("[1,2]")).reason());
        assertEquals(McpRefusal.Reason.ARGUMENTS_UNREADABLE,
                assertThrows(McpRefusal.class, () -> McpProtocol.readArguments("{\"a\":")).reason());
        assertEquals(McpRefusal.Reason.ARGUMENTS_UNREADABLE,
                assertThrows(McpRefusal.class, () -> McpProtocol.readArguments("\"just text\"")).reason());
    }

    @Test
    @DisplayName("a JSON-RPC error is read before the result, because it arrives as HTTP 200")
    void anErrorIsReadBeforeTheResult() {
        assertEquals(McpRefusal.Reason.SERVER_REFUSED, assertThrows(McpRefusal.class,
                () -> McpProtocol.readResult(
                        bytes("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601},"
                                + "\"result\":{\"tools\":[]}}"),
                        "application/json", 1024)).reason());
    }

    @Test
    @DisplayName("an SSE-framed response is the same response, and is read as one")
    void anEventStreamResponseIsRead() {
        var result = McpProtocol.readResult(
                bytes("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":"
                        + "{\"tools\":[{\"name\":\"search\"}]}}\n\n"),
                "text/event-stream", 4096);

        assertEquals(List.of("search"), McpProtocol.readTools(result).stream()
                .map(McpProtocol.Announced::name).toList());
    }

    @Test
    @DisplayName("a response past the profile ceiling is refused by size, not by parse failure")
    void anOversizedResponseIsRefusedBySize() {
        assertEquals(McpRefusal.Reason.SERVER_RESPONSE_TOO_LARGE, assertThrows(McpRefusal.class,
                () -> McpProtocol.readResult(bytes("{\"jsonrpc\":\"2.0\",\"result\":{}}"),
                        "application/json", 4)).reason());
    }

    @Test
    @DisplayName("a body that is not a JSON-RPC response is one refusal, whatever shape it took")
    void anUnreadableBodyIsOneRefusal() {
        for (String body : List.of("<html/>", "[1,2,3]", "{\"jsonrpc\":\"2.0\"}", "")) {
            assertEquals(McpRefusal.Reason.SERVER_RESPONSE_UNREADABLE, assertThrows(McpRefusal.class,
                    () -> McpProtocol.readResult(bytes(body), "application/json", 4096)).reason(),
                    body);
        }
    }

    @Test
    @DisplayName("an entry without a usable name is dropped; the rest of the catalogue survives")
    void oneBadEntryDoesNotDenyTheRest() {
        var result = McpProtocol.readResult(
                bytes("{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":["
                        + "{\"description\":\"nameless\"},"
                        + "\"not an object\","
                        + "{\"name\":\"search\",\"description\":\"finds things\"}]}}"),
                "application/json", 4096);

        List<McpProtocol.Announced> tools = McpProtocol.readTools(result);
        assertEquals(1, tools.size());
        assertEquals("search", tools.get(0).name());
        assertEquals("finds things", tools.get(0).description());
        // A tool announced without a schema still gets one, or the request document would be invalid.
        assertEquals(PayloadValue.of("object"), tools.get(0).schema().entries().get("type"));
    }

    @Test
    @DisplayName("tool content is the text parts, and non-text parts are named rather than dropped")
    void toolContentNamesWhatItCouldNotCarry() {
        String text = McpProtocol.readToolContent((PayloadValue.MapValue) McpProtocol.readResult(
                bytes("{\"jsonrpc\":\"2.0\",\"result\":{\"content\":["
                        + "{\"type\":\"text\",\"text\":\"first\"},"
                        + "{\"type\":\"image\",\"data\":\"...\"},"
                        + "{\"type\":\"text\",\"text\":\"second\"}]}}"),
                "application/json", 4096));

        assertTrue(text.contains("first"));
        assertTrue(text.contains("second"));
        // Named, because a model that asked for a diagram and got silence concludes the tool is
        // broken and retries, which costs turns to arrive at the same place.
        assertTrue(text.contains("1 part(s)"));
    }

    @Test
    @DisplayName("an empty result is never an empty tool message")
    void anEmptyResultIsNeverAnEmptyMessage() {
        String text = McpProtocol.readToolContent((PayloadValue.MapValue) McpProtocol.readResult(
                bytes("{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[]}}"), "application/json", 4096));

        // An empty tool message reads to a model as a call that succeeded and returned nothing, which
        // is a different fact from the one the server stated.
        assertFalse(text.isEmpty());
    }

    @Test
    @DisplayName("isError is content the model must read, not a refusal that hides it")
    void isErrorIsContentAndNotARefusal() {
        String text = McpProtocol.readToolContent((PayloadValue.MapValue) McpProtocol.readResult(
                bytes("{\"jsonrpc\":\"2.0\",\"result\":{\"isError\":true,\"content\":["
                        + "{\"type\":\"text\",\"text\":\"file not found\"}]}}"),
                "application/json", 4096));

        // The tool ran and failed -- a compile error, a missing record. That is a result the model is
        // supposed to act on; turning it into a refusal would hide the one sentence it needed.
        assertEquals("file not found", text);
    }

    private static PayloadValue.MapValue read(byte[] document) {
        return (PayloadValue.MapValue) PayloadJson.read(document, PayloadLimits.DEFAULTS);
    }

    private static byte[] bytes(String document) {
        return document.getBytes(StandardCharsets.UTF_8);
    }
}
