package ai.ravenroot.server.readiness;

/**
 * The states {@code /ready} can report, in priority order when more than one
 * condition holds at once (checked top to bottom by {@link ReadinessGate#evaluate()}).
 *
 * <p>Deliberately three, not five. The readiness surface distinguishes live process, availability to
 * accept work, degraded store, drain and optional dependencies, but only two of
 * them ("recovery or drain") are required to gate acceptance of new work. "Live process" is
 * {@code /health}'s job, not this route's (see the class Javadoc on {@code /ready} in
 * {@code RavenrootServer} for why conflating the two is the specific outage this design avoids).
 * "Optional dependencies" are reported in {@link ReadinessReport#dependencies()} for observability
 * but never flip {@link ReadinessReport#ready()} to {@code false} — an optional dependency being
 * down is, by definition, not a reason to stop accepting work.</p>
 */
public enum ReadinessState {
    /** Accepting work normally. */
    READY,

    /**
     * The engine is not in its normal running state (draining or already closed). Checked before
     * the store: a process mid-shutdown should report draining even if the store also happens to
     * be unreachable, because draining is the more specific and more actionable fact for an
     * operator reading the response.
     */
    DRAINING,

    /**
     * The store liveness check failed or the process is otherwise unable to durably persist work
     * it accepts. See {@link ReadinessGate}'s own Javadoc for exactly what is and is not checked.
     */
    STORE_DEGRADED
}
