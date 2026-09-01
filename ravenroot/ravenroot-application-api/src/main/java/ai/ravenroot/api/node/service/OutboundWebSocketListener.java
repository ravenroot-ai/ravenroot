package ai.ravenroot.api.node.service;

/** Complete-message callbacks from a managed WebSocket session. */
public interface OutboundWebSocketListener {
    /**
     * Receives one complete text message after transport framing and size policy have been applied.
     *
     * @param text decoded message text; implementations should return promptly
     */
    default void onText(String text) { }
    /**
     * Receives one complete binary message after transport framing and size policy have been applied.
     *
     * @param bytes message bytes; listeners must not retain mutable assumptions about transport buffers
     */
    default void onBinary(byte[] bytes) { }
    /**
     * Observes a terminal close handshake.
     *
     * @param statusCode peer close status
     * @param reason peer-provided close reason, possibly empty
     */
    default void onClosed(int statusCode, String reason) { }
    /**
     * Observes a sanitized terminal failure rather than transport-specific diagnostics.
     *
     * @param failure non-null policy, deadline, or transport classification
     */
    default void onFailure(NodePackageServiceException failure) { }
}
