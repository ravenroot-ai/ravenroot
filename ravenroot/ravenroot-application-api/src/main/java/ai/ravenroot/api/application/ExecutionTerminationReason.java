package ai.ravenroot.api.application;

/**
 * Why an execution ended, when the status alone would misdescribe it.
 *
 * <h2>It qualifies a status, it does not replace one</h2>
 * <p>A cancelled execution is persisted as {@link ProcessInstanceStatus#FAILED} and
 * {@link TraversalStatus#FAILED}, and it keeps that status forever. <b>Read on its own, that status
 * says an execution broke, which is exactly the misreading this type exists to remove — so a status
 * is only a complete answer when this reason is read beside it.</b> Every type that carries a
 * terminal status and can carry this one repeats that sentence, because a reader who consults one
 * field and not the other is not partially informed, they are actively misinformed.</p>
 *
 * <p>The alternative was a new {@code CANCELLED} member of the two status enums, and it was rejected
 * on compatibility rather than on taste. A status is persisted <em>by name</em>, so the first row
 * carrying a new name is unreadable by any binary that predates it: the enlargement is forward-only,
 * and a rollback past that first row fails loudly. That price has been paid deliberately once, for
 * {@link NodeAttemptStatus#PARKED}, where the alternative was silently forging an outcome nobody
 * observed. It is not worth paying again here, because a nullable reason beside an unchanged status
 * is backward <em>and</em> forward compatible: an older reader sees the {@code FAILED} it has always
 * seen and simply does not see the qualification, which is a reader that is behind rather than a
 * reader that is broken.</p>
 *
 * <h2>Why cancellation earns its own value</h2>
 * <p>The same argument {@link NodeAttemptCompletion#OPERATOR_VERIFIED} makes one level down: a
 * distinct value is required when the <em>provenance</em> differs, and reusing an existing one would
 * forge an observation that never happened. Nothing about a cancelled traversal failed. No behavior
 * raised, no dependency broke, no policy was exhausted; somebody with the authority to stop the work
 * stopped it, and every node before the refused hop ran and its effects stand. Counting that as a
 * fault does not merely lose a detail — it fabricates an incident, and an incident is what gets
 * paged on, retried and reported as an availability loss.</p>
 *
 * <h2>Absence is a real answer, and it is deliberately not a third value</h2>
 * <p>The reason is nullable everywhere it appears, and an absent reason means only <em>"nothing
 * distinguishes this termination"</em>. It covers two situations at once on purpose: an ordinary
 * failure, and a row written before a reason could be recorded at all. Conflating them is safe
 * precisely because neither is a cancellation, and the one question a reader asks of this field —
 * <em>was this stopped on request?</em> — is answered correctly for both. A {@code FAULT} member
 * would separate them, and would immediately create the failure mode it appeared to fix: every
 * existing writer of a terminal status would have to be taught to set it, and the one that was
 * forgotten would write an absent reason that a reader had by then been taught to interpret as
 * "written by an older version". Silence that means one thing is safer than silence that means two.
 * </p>
 *
 * <p>The vocabulary grows the same way it started, by adding a member for a termination whose
 * provenance a reader would otherwise get wrong. A reader that switches over it must therefore
 * tolerate a value it does not know, and must treat that value as "not a cancellation" rather than
 * as an error.</p>
 */
public enum ExecutionTerminationReason {

    /**
     * The execution was stopped on request and did not run to a result.
     *
     * <p>Recorded against a {@code FAILED} status, never against a completion: no end node ran and
     * there is no result payload, so a cancelled execution genuinely is not a completion. What it is
     * also not is a fault, and this value is the only thing on the record that says so.</p>
     *
     * <p>It claims nothing about what did or did not happen before the stop took effect. Effects
     * issued before the cancellation was observed are not undone and cannot be; the refused hop is
     * the first that did <em>not</em> run, and every node before it did.</p>
     */
    CANCELLED;

    /**
     * Whether {@code reason} is a cancellation, tolerating an absent one.
     *
     * <p>The read every consumer of a terminal status actually wants, written once here so that no
     * caller has to remember that {@code null} is legal at every site this value appears. A null-safe
     * helper rather than a field default because absence is meaningful — see this type's own
     * documentation — and collapsing it to a value would destroy that meaning at the boundary.</p>
     *
     * @param reason a recorded termination reason, or {@code null} when none was recorded.
     * @return {@code true} only when the execution was stopped on request.
     */
    public static boolean isCancellation(ExecutionTerminationReason reason) {
        return reason == CANCELLED;
    }
}
