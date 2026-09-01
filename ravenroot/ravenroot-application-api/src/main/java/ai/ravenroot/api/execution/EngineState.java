package ai.ravenroot.api.execution;

/** Observable lifecycle of an execution engine as a whole. */
public enum EngineState {
    /** Spawning and sending are admitted. */
    RUNNING,
    /** Spawning is refused and every node has been asked to drain; the runtime is still up. */
    DRAINING,
    /** Every node is terminated and the underlying runtime has been shut down. Absorbing. */
    CLOSED;

/**
 * Returns whether an engine in this state may move directly to {@code next}.
 * @param next proposed successor state
 * @return whether the engine's state machine permits the transition
 */
    public boolean canTransitionTo(EngineState next) {
        return switch (this) {
            case RUNNING -> next == DRAINING || next == CLOSED;
            case DRAINING -> next == CLOSED;
            case CLOSED -> false;
        };
    }

/**
 * Returns whether this engine admits new nodes.
 * @return whether spawning nodes is still permitted
 */
    public boolean accepting() {
        return this == RUNNING;
    }
}
