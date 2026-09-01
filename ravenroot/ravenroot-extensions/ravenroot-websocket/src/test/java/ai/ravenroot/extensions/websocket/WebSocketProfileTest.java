package ai.ravenroot.extensions.websocket;

import ai.ravenroot.api.node.NodeConfiguration;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketProfileTest {
    @Test
    void environmentProfileIsStrictCanonicalBase64Json() {
        String json = """
                {"destination":"wss://socket.example.test/events","headers":{"X-Client":["ravenroot"]},
                "subprotocols":["events.v1"],"credentialBindingId":"handshake","credentialReference":"secret",
                "maximumMessageBytes":1024,"maximumFragments":4,"timeoutMs":2000,"reconnectBackoffMs":10,
                "maxConcurrency":2,"maxBufferedEvents":8}
                """.replace("\n", "");
        String key = EnvironmentWebSocketProfileResolver.variable("events");
        String encoded = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        var profile = new EnvironmentWebSocketProfileResolver(Map.of(key, encoded)).resolve("events");

        assertTrue(profile.isPresent());
        assertEquals(URI.create("wss://socket.example.test/events"), profile.orElseThrow().destination());
        assertFalse(new EnvironmentWebSocketProfileResolver(Map.of(key, encoded + "=")).resolve("events").isPresent());
        assertFalse(new EnvironmentWebSocketProfileResolver(Map.of(key,
                Base64.getEncoder().encodeToString((json.substring(0, json.length() - 1)
                        + ",\"extra\":true}").getBytes(StandardCharsets.UTF_8)))).resolve("events").isPresent());
    }

    @Test
    void profileRejectsUnsafeAuthorityHeadersAndSubprotocols() {
        assertThrows(IllegalArgumentException.class, () -> profile("ws://socket.example.test/events",
                Map.of(), List.of("events.v1")));
        assertThrows(IllegalArgumentException.class, () -> profile("wss://user@socket.example.test/events",
                Map.of(), List.of("events.v1")));
        for (String forbidden : List.of("Authorization", "COOKIE", "Host", "Connection",
                "Sec-WebSocket-Key", "Sec-WebSocket-Protocol")) {
            assertThrows(IllegalArgumentException.class, () -> profile("wss://socket.example.test/events",
                    Map.of(forbidden, List.of("not-operator-safe")), List.of("events.v1")), forbidden);
        }
        assertThrows(IllegalArgumentException.class, () -> profile("wss://socket.example.test/events",
                Map.of("X-Client", List.of("line\nbreak")), List.of("events.v1")));
        assertThrows(IllegalArgumentException.class, () -> profile("wss://socket.example.test/events",
                Map.of(), List.of("events.v1", "events.v1")));
    }

    @Test
    void graphMayOnlyTightenOperatorLimits() {
        WebSocketProfile profile = WebSocketTestSupport.profile(2, 8);
        NodeConfiguration tighter = new NodeConfiguration("socket", WebSocketSendNodeBehavior.BEHAVIOR,
                Map.of("websocketProfile", "events", "maxMessageBytes", "8", "maxFragments", "1",
                        "timeoutMs", "100"));
        WebSocketSettings settings = WebSocketSettings.compile(tighter, WebSocketTestSupport.resolver(profile));
        assertEquals(8, settings.maximumMessageBytes());
        assertEquals(1, settings.maximumFragments());
        assertEquals(100, settings.timeoutMs());

        for (Map<String, Object> widening : List.<Map<String, Object>>of(
                Map.of("websocketProfile", "events", "maxMessageBytes", "17"),
                Map.of("websocketProfile", "events", "maxFragments", "3"),
                Map.of("websocketProfile", "events", "timeoutMs", "2001"))) {
            assertThrows(WebSocketException.class, () -> WebSocketSettings.compile(
                    new NodeConfiguration("socket", WebSocketSendNodeBehavior.BEHAVIOR, widening),
                    WebSocketTestSupport.resolver(profile)));
        }
    }

    private static WebSocketProfile profile(String destination, Map<String, List<String>> headers,
                                            List<String> protocols) {
        return new WebSocketProfile("events", URI.create(destination), headers, protocols,
                null, null, 1024, 4, 2_000, 10, 2, 8);
    }
}
