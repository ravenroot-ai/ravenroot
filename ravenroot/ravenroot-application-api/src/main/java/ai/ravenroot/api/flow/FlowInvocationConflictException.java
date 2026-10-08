package ai.ravenroot.api.flow;

/** Optimistic invocation-relation mutation lost a race and must be re-read. */
public final class FlowInvocationConflictException extends RuntimeException {
    /** Stored revision observed by the failed mutation. */
    private final long actualRevision;

    /**
     * Reports the expected and stored revisions for a lost compare-and-set race.
     * @param expectedRevision revision supplied by the mutation
     * @param actualRevision revision currently stored
     */
    public FlowInvocationConflictException(long expectedRevision, long actualRevision) {
        super("flow invocation revision " + expectedRevision + " is stale; stored revision is "
                + actualRevision);
        this.actualRevision = actualRevision;
    }

    /**
     * Returns the revision observed when the mutation was refused.
     * @return current stored revision
     */
    public long actualRevision() {
        return actualRevision;
    }
}
