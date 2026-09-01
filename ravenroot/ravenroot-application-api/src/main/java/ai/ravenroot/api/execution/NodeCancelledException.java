package ai.ravenroot.api.execution;

/**
 * A message accepted by the engine was abandoned because its node was cancelled.
 *
 * <p>Receiving this says the <em>caller</em> was released, not that the node's computation stopped.
 * Unless the engine declares {@link EngineCapability#PREEMPTIVE_CANCELLATION}, an
 * {@code onMessage} already in flight runs to completion on its own schedule and its outcome is
 * discarded. That is not an implementation shortcut: the JVM offers no safe way to abort arbitrary
 * user code, so a contract promising preemption would be a contract no adapter could keep.</p>
 *
 * <p>It is <strong>not</strong> a {@link java.util.concurrent.CancellationException}, even though the
 * name invites it. {@code CompletableFuture.get} and {@code join} special-case that type and rethrow
 * it bare instead of wrapping it in {@code ExecutionException} / {@code CompletionException}, so a
 * cancelled message would surface through a different shape than every other engine failure and every
 * caller would need two code paths for one concept.</p>
 */
public class NodeCancelledException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private final transient NodeRef node;

/**
 * Creates the delivery failure reported when cancellation wins its race with completion.
 * @param node the node whose lifecycle abandoned the caller's message
 */
    public NodeCancelledException(NodeRef node) {
        super("Node " + node.value() + " was cancelled before this message was completed");
        this.node = node;
    }

/**
 * The node whose cancellation abandoned this message.
 * @return the node whose cancellation caused this failure
 */
    public NodeRef node() {
        return node;
    }
}
