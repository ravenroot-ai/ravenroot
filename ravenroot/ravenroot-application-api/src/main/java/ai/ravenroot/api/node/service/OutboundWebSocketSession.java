package ai.ravenroot.api.node.service;

import java.util.concurrent.CompletionStage;

/** Constrained session; the JDK WebSocket/client and its builders are never exposed. */
public interface OutboundWebSocketSession extends AutoCloseable {
    /**
     * Sends one complete text message through the managed session.
     *
     * @param text text payload subject to the session's framing and size policy
     * @return a stage completing when the managed transport accepts or rejects the send
     */
    CompletionStage<Void> sendText(String text);
    /**
     * Sends one complete binary message through the managed session.
     *
     * @param bytes binary payload subject to the session's framing and size policy
     * @return a stage completing when the managed transport accepts or rejects the send
     */
    CompletionStage<Void> sendBinary(byte[] bytes);
    /**
     * Starts a close handshake with a caller-selected status and reason.
     *
     * @param statusCode WebSocket close code
     * @param reason close reason sent to the peer
     * @return a stage completing after the managed transport closes or refuses the handshake
     */
    CompletionStage<Void> close(int statusCode, String reason);
    /**
     * Requests immediate cancellation rather than an orderly close handshake.
     *
     * @return whether this call changed the session to cancelled
     */
    boolean cancel();

    @Override
    default void close() {
        close(1000, "closed");
    }
}
