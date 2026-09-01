package ai.ravenroot.api.execution;

/**
 * Defines the node context contract exposed to Ravenroot integrators.
 */
public interface NodeContext {
/**
 * Identifies the node receiving the current message.
 *
 * @return the stable engine-issued reference for this node
 */
    NodeRef self();

/**
 * Exposes scheduling services scoped to this node's execution engine.
 *
 * @return the scheduler that owns delayed work for this execution
 */
    Scheduler scheduler();

/**
 * Exposes this node's mailbox for ordered self-directed work.
 *
 * @return the mailbox through which the node can enqueue its own messages
 */
    Mailbox mailbox();

    /**
     * The cooperative cancellation signal for this node.
     *
     * <p>Abstract on purpose. A default returning "never cancelled" would let an adapter omit the
     * signal and still compile, and the omission would only surface as a node that keeps burning work
     * after its callers were released — precisely the divergence between adapters this contract
     * exists to prevent.</p>
 * @return a signal that becomes cancelled when the engine starts cancellation
     */
    CancellationSignal cancellation();
}
