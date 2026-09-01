package ai.ravenroot.api.execution;

/**
 * The cooperative half of cancellation, handed to a node through {@link NodeContext}.
 *
 * <p>The engine can always release the <em>caller</em> promptly, because it owns the reply. It cannot
 * abort the node's own computation, so a node that may run long is expected to consult this signal:
 * {@link #cancelled()} for work that loops or polls, {@link #onCancel(Runnable)} for work that has
 * already returned a pending {@code CompletionStage} and can only be unwound from a callback.</p>
 *
 * <p>A node that ignores the signal is still correct; it merely wastes the work it goes on to do,
 * because its result is discarded.</p>
 */
public interface CancellationSignal {
/**
 * Returns whether the node has been cancelled. Once true, it never becomes false again.
 * @return whether cancellation has already been requested
 */
    boolean cancelled();

    /**
     * Runs {@code listener} when the node is cancelled, or immediately if it already was.
     *
     * <p>Each registered listener runs at most once. A listener that throws does not prevent the
     * others from running and does not fail the cancellation.</p>
 * @param listener callback run exactly once when cancellation is requested; runs immediately if late
     */
    void onCancel(Runnable listener);
}
