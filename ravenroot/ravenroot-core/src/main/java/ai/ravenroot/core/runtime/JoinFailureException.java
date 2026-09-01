package ai.ravenroot.core.runtime;

import java.util.List;
import java.util.Objects;

/**
 * A fan-in join could not be satisfied (CORE-03).
 *
 * <p>The message names the branches that arrived, the branches that failed and the branches still
 * outstanding, because the operator question after a stuck join is always "which branch did not
 * come back", and a bare timeout answers it with nothing.</p>
 */
public final class JoinFailureException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Why the join failed.
     *
     * <p>A join has exactly three ways of not being satisfied — {@link #QUORUM_UNREACHABLE},
     * {@link #BRANCH_NOT_TAKEN} and {@link #TIMEOUT} — and one way of not being able to say which
     * of them applied, {@link #UNRECORDED}. The fourth constant is deliberately not a fourth way to
     * fail; see its own documentation.</p>
     */
    public enum Reason {
        /**
         * Enough branches failed that the remaining ones cannot reach the quorum.
         *
         * <p>Detected as soon as it becomes true rather than at the timeout. Waiting out a timeout
         * for an outcome already proven impossible converts a determinate failure into a latency
         * cost, and it is the configured timeout — not the actual defect — that then appears in
         * every incident report.</p>
         */
        QUORUM_UNREACHABLE,

        /**
         * The quorum is out of reach only because branches were never taken — nothing failed.
         *
         * <p>Separated from {@link #QUORUM_UNREACHABLE} because the two need opposite responses.
         * A quorum lost to failures is a runtime incident: something broke and the logs will say
         * what. A quorum lost to branches that were never dispatched is a <em>modelling</em> defect
         * that is present on every execution of that graph — most often an {@code all} join placed
         * over the outcomes of one decision, where the branches are mutually exclusive by
         * construction and the author wanted {@code any}. Reporting it as QUORUM_UNREACHABLE would
         * send an operator hunting for a failure that never happened.</p>
         */
        BRANCH_NOT_TAKEN,

        /** The configured {@code joinTimeout} elapsed with the quorum neither met nor disproven. */
        TIMEOUT,

        /**
         * The join is known to have failed, but the durable record does not say why.
         *
         * <p>Not a way for a join to fail — a statement about the evidence. It is reachable from
         * exactly one place: a {@code FAILED} join record written by a runtime that predates
         * {@link ai.ravenroot.api.persistence.JoinRecord#failureReason()}, re-read after a restart.
         * No current writer can produce it, and it is never persisted.</p>
         *
         * <p>It exists so that such a verdict can be delivered <em>without being dressed up as one
         * of the other three</em>. Mapping it onto {@link #TIMEOUT} or {@link #QUORUM_UNREACHABLE}
         * would put a cause in an incident report that nothing in the system ever observed, which
         * is worse than admitting the cause was lost: an operator can act on "the reason was not
         * recorded" and cannot act on a fabricated one. Dropping the verdict instead is not an
         * option either — that is the silent success this reason exists to prevent.</p>
         */
        UNRECORDED
    }

    private final Reason reason;
    private final String nodeId;
    private final transient List<String> arrived;
    private final transient List<String> failed;
    private final transient List<String> outstanding;
    private final transient List<String> notTaken;

    public JoinFailureException(Reason reason, String nodeId, int quorum, List<String> arrived,
                                List<String> failed, List<String> outstanding) {
        this(reason, nodeId, quorum, arrived, failed, outstanding, List.of());
    }

    public JoinFailureException(Reason reason, String nodeId, int quorum, List<String> arrived,
                                List<String> failed, List<String> outstanding, List<String> notTaken) {
        super("Join '" + nodeId + "' " + describe(reason) + ": quorum " + quorum
                + " of " + (arrived.size() + failed.size() + outstanding.size() + notTaken.size())
                + " branches, arrived=" + arrived + " failed=" + failed + " outstanding=" + outstanding
                + " notTaken=" + notTaken);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.nodeId = nodeId;
        this.arrived = List.copyOf(arrived);
        this.failed = List.copyOf(failed);
        this.outstanding = List.copyOf(outstanding);
        this.notTaken = List.copyOf(notTaken);
    }

    private static String describe(Reason reason) {
        return switch (reason) {
            case TIMEOUT -> "timed out";
            case BRANCH_NOT_TAKEN -> "can never reach its quorum, because branches it needs are never taken";
            case QUORUM_UNREACHABLE -> "can no longer reach its quorum";
            case UNRECORDED -> "had already failed before this runtime started, for a reason its "
                    + "record does not carry";
        };
    }

    public Reason reason() {
        return reason;
    }

    public String nodeId() {
        return nodeId;
    }

    /** Branches whose result reached the join. */
    public List<String> arrived() {
        return arrived;
    }

    /** Branches proven unable to deliver. */
    public List<String> failed() {
        return failed;
    }

    /** Branches neither arrived nor failed when the join gave up. */
    public List<String> outstanding() {
        return outstanding;
    }

    /**
     * Branches that were never dispatched, so nothing was ever going to arrive on them.
     *
     * <p>Deliberately not merged into {@link #failed()}: nothing on these branches ran, let alone
     * failed, and an operator reading {@code failed=[y]} for a branch that never started has been
     * told something untrue.</p>
     */
    public List<String> notTaken() {
        return notTaken;
    }
}
