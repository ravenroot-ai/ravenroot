package ai.ravenroot.api.execution;

import java.util.Objects;

/**
 * Immutable snapshot of one node's supervision state.
 *
 * @param node             the reference the engine issued from {@code spawn}
 * @param state            the observable lifecycle state
 * @param reason           why the node terminated; {@code null} unless {@code state} is terminal
 * @param acceptedMessages messages this engine accepted for the node and has not yet settled, so the
 *                         depth a drain still has to work through. This is the same quantity a node
 *                         sees through {@link Mailbox#pendingMessages()}, read from the outside.
 */
public record NodeStatus(NodeRef node, NodeLifecycleState state, NodeTerminationReason reason,
                         int acceptedMessages) {
/**
 * Checks the cross-field invariant between a terminal state and its termination reason.
 */
    public NodeStatus {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(state, "state");
        if (state.terminal() == (reason == null)) {
            throw new IllegalArgumentException(
                    "A termination reason is present exactly when the state is terminal, but state was "
                            + state + " and reason was " + reason);
        }
        if (acceptedMessages < 0) {
            throw new IllegalArgumentException("acceptedMessages cannot be negative: " + acceptedMessages);
        }
    }

/**
 * Returns whether this node still admits new messages.
 * @return whether the node can admit another message
 */
    public boolean accepting() {
        return state.accepting();
    }
}
