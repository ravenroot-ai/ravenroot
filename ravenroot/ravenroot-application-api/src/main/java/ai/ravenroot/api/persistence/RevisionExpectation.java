package ai.ravenroot.api.persistence;

/**
 * Optimistic-concurrency expectation carried by every write (ADR 0010 section 2).
 *
 * <p>Magic sentinels are deliberately rejected: an adapter reading {@code -1} as "any" while another
 * reads it as a literal revision is a silent cross-adapter divergence that no conformance suite
 * would catch. {@link NotPresent} is what makes instance creation exactly-once.</p>
 */
public sealed interface RevisionExpectation {

    /** Unconditional write. The caller accepts whatever revision it finds. */
    record Any() implements RevisionExpectation {
        private static final Any INSTANCE = new Any();
    }

    /** The instance must not exist. Violating this fails with {@link ExecutionStoreFailure.AlreadyExists}. */
    record NotPresent() implements RevisionExpectation {
        private static final NotPresent INSTANCE = new NotPresent();
    }

    /**
     * The stored revision must equal {@code revision}. Violating this fails with
     * {@link ExecutionStoreFailure.ConcurrencyConflict}.
 * @param revision revision assigned to the durable join.
     */
    record Exactly(long revision) implements RevisionExpectation {
    }

/**
 * Returns the singleton expectation for an unconditional write.
 * @return shared expectation that accepts any current revision.
 */
    static RevisionExpectation any() {
        return Any.INSTANCE;
    }

/**
 * Returns the singleton expectation used to create an absent aggregate exactly once.
 * @return shared absence expectation.
 */
    static RevisionExpectation notPresent() {
        return NotPresent.INSTANCE;
    }

/**
 * Creates an expectation for one exact stored revision.
 * @param revision revision assigned to the durable join.
 * @return exact revision expectation used for compare-and-set.
 */
    static RevisionExpectation exactly(long revision) {
        return new Exactly(revision);
    }
}
