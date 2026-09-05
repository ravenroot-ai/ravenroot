package ai.ravenroot.api.persistence;

import java.util.UUID;

/**
 * One compare-and-set lifecycle transition for a durable operator hold.
 *
 * <p>Two transitions and no more, mirroring {@link ExecutionPauseStatus}: a hold is either released
 * to continue or given up. Which one was applied is what the continuation executor reads to decide
 * whether it dispatches a node or settles the traversal, so the distinction is durable rather than
 * inferred from context that a restart would not have.</p>
 */
public sealed interface ExecutionPauseTransition {

    /**
     * Identifies the hold this transition applies to.
     *
     * @return the stable hold identity.
     */
    UUID pauseId();

    /**
     * The state this transition moves the hold into.
     *
     * @return the requested successor state.
     */
    ExecutionPauseStatus next();

    /**
     * Audit-stable identity of the principal that applied this transition.
     *
     * @return the acting principal, never {@code null}.
     */
    String actor();

    /**
     * An authorized principal released the hold and the traversal continues.
     *
     * @param pauseId the hold being released.
     * @param actor audit-stable identity of the principal that released it.
     */
    record Resumed(UUID pauseId, String actor) implements ExecutionPauseTransition {
        /** Rejects a release that names no hold or no principal. */
        public Resumed {
            requireId(pauseId);
            actor = HandlerRegistration.requireBoundedKey(actor, "actor");
        }

        @Override
        public ExecutionPauseStatus next() {
            return ExecutionPauseStatus.RESUMED;
        }
    }

    /**
     * The hold ended without continuing the traversal.
     *
     * @param pauseId the hold being given up.
     * @param actor audit-stable identity of the principal that gave it up.
     */
    record Cancelled(UUID pauseId, String actor) implements ExecutionPauseTransition {
        /** Rejects a cancellation that names no hold or no principal. */
        public Cancelled {
            requireId(pauseId);
            actor = HandlerRegistration.requireBoundedKey(actor, "actor");
        }

        @Override
        public ExecutionPauseStatus next() {
            return ExecutionPauseStatus.CANCELLED;
        }
    }

    private static void requireId(UUID id) {
        if (id == null) throw new IllegalArgumentException("pauseId cannot be null");
    }
}
