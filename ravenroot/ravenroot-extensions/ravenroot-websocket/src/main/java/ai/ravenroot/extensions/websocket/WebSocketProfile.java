package ai.ravenroot.extensions.websocket;

import ai.ravenroot.api.node.service.OutboundCredentialBinding;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable operator-owned authority for one WebSocket profile. */
public record WebSocketProfile(String name, URI destination, Map<String, List<String>> headers, List<String> subprotocols, String credentialBindingId, String credentialReference, int maximumMessageBytes, int maximumFragments, int timeoutMs, int reconnectBackoffMs, int maxConcurrency, int maxBufferedEvents) {
    private static final java.util.Set<String> FORBIDDEN_HEADERS = java.util.Set.of(
            "authorization", "cookie", "proxy-authorization", "host", "content-length", "connection",
            "upgrade", "transfer-encoding", "te", "trailer", "sec-websocket-key", "sec-websocket-accept",
            "sec-websocket-version", "sec-websocket-protocol", "sec-websocket-extensions");

    public WebSocketProfile {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) throw new IllegalArgumentException("name");
        if (destination == null || !"wss".equals(destination.getScheme()) || destination.getHost() == null || destination.getUserInfo() != null || destination.getFragment() != null || destination.getQuery() != null || destination.toASCIIString().length() > 2048) throw new IllegalArgumentException("destination");
        Map<String, List<String>> copiedHeaders = new java.util.LinkedHashMap<>();
        if (headers != null) headers.forEach((key, values) -> {
            if (key == null || values == null || values.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("headers");
            }
            copiedHeaders.put(key, List.copyOf(values));
        });
        headers = Map.copyOf(copiedHeaders);
        subprotocols = subprotocols == null ? List.of() : List.copyOf(subprotocols);
        if (headers.size() > 32 || headers.entrySet().stream().anyMatch(e -> e.getKey() == null
                || !e.getKey().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}")
                || FORBIDDEN_HEADERS.contains(e.getKey().toLowerCase(java.util.Locale.ROOT))
                || e.getValue() == null || e.getValue().isEmpty() || e.getValue().size() > 8
                || e.getValue().stream().anyMatch(v -> v == null || v.isEmpty() || v.length() > 512
                || v.codePoints().anyMatch(c -> c < 0x20 || c > 0x7e)))) throw new IllegalArgumentException("headers");
        if (subprotocols.size() > 16 || subprotocols.stream().distinct().count() != subprotocols.size() || subprotocols.stream().anyMatch(v -> v == null || !v.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}"))) throw new IllegalArgumentException("subprotocols");
        if ((credentialBindingId == null) != (credentialReference == null) || (credentialBindingId != null && (!token(credentialBindingId) || !token(credentialReference)))) throw new IllegalArgumentException("credential");
        if (maximumMessageBytes < 1 || maximumMessageBytes > 16 * 1024 * 1024 || maximumFragments < 1 || maximumFragments > 1024 || timeoutMs < 1 || timeoutMs > 300_000 || reconnectBackoffMs < 1 || reconnectBackoffMs > 300_000 || maxConcurrency < 1 || maxConcurrency > 256 || maxBufferedEvents < 1 || maxBufferedEvents > 65_536) throw new IllegalArgumentException("limits");
    }
    Optional<OutboundCredentialBinding> credential() { return credentialBindingId == null ? Optional.empty() : Optional.of(new OutboundCredentialBinding(credentialBindingId, credentialReference)); }
    Duration timeout() { return Duration.ofMillis(timeoutMs); }
    private static boolean token(String v) { return v != null && v.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}"); }
}
