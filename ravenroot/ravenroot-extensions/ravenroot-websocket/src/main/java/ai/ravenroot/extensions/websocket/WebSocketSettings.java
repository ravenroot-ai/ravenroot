package ai.ravenroot.extensions.websocket;

import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodeExternalIoCapacity;
import java.util.Objects;

record WebSocketSettings(WebSocketProfile profile, int maximumMessageBytes, int maximumFragments,
                         int timeoutMs, int maximumConcurrency) {
    WebSocketSettings(WebSocketProfile profile, int maximumMessageBytes, int maximumFragments,
                      int timeoutMs) {
        this(profile, maximumMessageBytes, maximumFragments, timeoutMs,
                Objects.requireNonNull(profile, "profile").maxConcurrency());
    }

    static WebSocketSettings compile(NodeConfiguration configuration, WebSocketProfileResolver profiles) {
        String name = configuration.requiredProperty("websocketProfile");
        WebSocketProfile profile = profiles.resolve(name).orElseThrow(() -> WebSocketException.of(WebSocketException.Code.CONFIGURATION));
        int bytes = tightened(configuration.property("maxMessageBytes", ""), profile.maximumMessageBytes());
        int fragments = tightened(configuration.property("maxFragments", ""), profile.maximumFragments());
        int timeout = tightened(configuration.property("timeoutMs", ""), profile.timeoutMs());
        return new WebSocketSettings(profile, bytes, fragments, timeout, profile.maxConcurrency());
    }

    static WebSocketSettings compilePinned(NodeConfiguration configuration, WebSocketProfileResolver profiles,
                                           NodeExternalIoCapacity capacity) {
        Objects.requireNonNull(capacity, "capacity");
        String name = configuration.requiredProperty("websocketProfile");
        WebSocketProfile profile = profiles.resolve(name).orElseThrow(() ->
                WebSocketException.of(WebSocketException.Code.CONFIGURATION));
        // Numeric graph/profile values were resolved exactly once at admission. Only the current
        // destination, protocol and credential authorization is restored from the live profile.
        return new WebSocketSettings(profile, capacity.maximumMessageBytes(), capacity.maximumFragments(),
                Math.toIntExact(capacity.maximumTimeout().toMillis()), capacity.maximumConcurrency());
    }
    private static int tightened(String raw, int upper) {
        if (raw == null || raw.isBlank()) return upper;
        try { int value = Integer.parseInt(raw); if (value < 1 || value > upper) throw new NumberFormatException(); return value; }
        catch (RuntimeException invalid) { throw WebSocketException.of(WebSocketException.Code.CONFIGURATION); }
    }
}
