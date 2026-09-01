package ai.ravenroot.api.persistence;

/**
 * Why a join reached {@link JoinPhase#FAILED}, as the durable record states it (CORE-03, FIX-15).
 *
 * <h2>Why the phase alone was not enough</h2>
 * <p>{@link JoinPhase#FAILED} says a verdict of failure was reached; it does not say what the
 * verdict <em>was</em>. That is a distinction with a consequence rather than a nicety: a verdict
 * that outlives the process which produced it — a timeout written just before a crash, say — is
 * re-delivered to a branch that was not there when it was decided, and a re-delivered failure that
 * cannot name its cause is a failure an operator cannot act on. So the reason is persisted at the
 * same instant as the phase, in the same conditional write, and travels with it.</p>
 *
 * <h2>Deliberately not the runtime's own enum</h2>
 * <p>This mirrors {@code JoinFailureException.Reason}, which lives in the core runtime. It is
 * restated here rather than referenced because this package is the engine-neutral persistence port:
 * an adapter must be able to store a join's outcome without compiling against the runtime, and the
 * dependency runs core → api, never the other way. The runtime owns the single mapping between the
 * two, and both directions are exhaustive switches, so a reason added on either side fails to
 * compile rather than silently degrading to a default.</p>
 *
 * <h2>Absence</h2>
 * <p>A record may carry no reason at all — see {@link JoinRecord#failureReason()}. Absence is not a
 * member of this enum, because "the reason was not recorded" is a statement about the record, not a
 * way for a join to fail.</p>
 */
public enum JoinFailureReason {

    /** Enough branches failed that the ones remaining can no longer reach the quorum. */
    QUORUM_UNREACHABLE,

    /** The quorum is out of reach only because branches were never taken — nothing failed. */
    BRANCH_NOT_TAKEN,

    /** The configured join timeout elapsed with the quorum neither met nor disproven. */
    TIMEOUT
}
