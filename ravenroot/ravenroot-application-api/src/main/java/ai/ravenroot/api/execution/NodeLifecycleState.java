package ai.ravenroot.api.execution;

/**
 * Observable lifecycle of one spawned node (CORE-04).
 *
 * <p>Stop, cancel and drain are <strong>not</strong> three peer states. Draining is the phase a node
 * enters when it stops accepting work but must still honour what it already accepted; cancelling is
 * the phase it enters when it stops accepting work and abandons what it accepted. Both end in the
 * single absorbing {@link #TERMINATED} state, whose {@link NodeTerminationReason} says which path was
 * taken.</p>
 *
 * <p>Failure is deliberately absent from this enum. An invocation that fails does not move the node:
 * the mandatory Ravenroot supervision decision is <em>resume</em>, so the node keeps its state and
 * keeps processing. Modelling failure as a node state would silently redefine that contract, which
 * the conformance suite has asserted since the engine SPI was introduced.</p>
 *
 * <p>The transition table is encoded here rather than in each adapter, for the same reason ADR 0007
 * encodes the process, traversal, invocation and attempt tables in the public model: two adapters
 * that each carry their own copy of a state machine are two adapters that will eventually disagree.</p>
 */
public enum NodeLifecycleState {
    /** Accepting new messages. */
    RUNNING,
    /** Refusing new messages; messages already accepted will still complete normally. */
    DRAINING,
    /** Refusing new messages; messages already accepted have been abandoned. */
    CANCELLING,
    /** Absorbing. {@code onStop} has run exactly once and no message is outstanding. */
    TERMINATED;

/**
 * Returns whether a node in this state may move directly to {@code next}.
 * @param next the proposed successor state
 * @return {@code true} when the lifecycle transition is legal
 */
    public boolean canTransitionTo(NodeLifecycleState next) {
        return switch (this) {
            // A drain may be escalated to a cancellation; the reverse is not offered, because
            // un-cancelling would have to explain what happens to callers already told their message
            // was cancelled.
            case RUNNING -> next == DRAINING || next == CANCELLING;
            case DRAINING -> next == CANCELLING || next == TERMINATED;
            case CANCELLING -> next == TERMINATED;
            case TERMINATED -> false;
        };
    }

/**
 * Returns whether this state is absorbing.
 * @return {@code true} for the absorbing {@link #TERMINATED} state
 */
    public boolean terminal() {
        return this == TERMINATED;
    }

/**
 * Returns whether a node in this state admits new messages.
 * @return whether the node may admit another message in this state
 */
    public boolean accepting() {
        return this == RUNNING;
    }
}
