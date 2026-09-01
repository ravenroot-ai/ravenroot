package ai.ravenroot.extensions.websocket;

/** Stable, secret-free WebSocket extension outcome. */
public final class WebSocketException extends RuntimeException {
    public enum Code { CONFIGURATION, INVALID_INPUT, CAPACITY_UNAVAILABLE, DESTINATION_REFUSED, CREDENTIAL_UNAVAILABLE, TLS_REFUSED, REQUEST_TOO_LARGE, RESPONSE_TOO_LARGE, DEADLINE_EXCEEDED, TRANSPORT_UNAVAILABLE, AMBIGUOUS }
    private final Code code;
    private WebSocketException(Code code) { super("WebSocket operation failed: " + code.name(), null, false, false); this.code = code; }
    public Code code() { return code; }
    static WebSocketException of(Code code) { return new WebSocketException(code); }
}
