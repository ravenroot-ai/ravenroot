package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionTerminationReason;

/**
 * The single place that decides whether a traversal's failure was in fact a cancellation.
 *
 * <h2>One classifier, not one per terminal path</h2>
 * <p>Four terminal handlers in {@link GraphRunner} and one result-recording seam in
 * {@link DefaultRavenrootApplication} all receive the same {@code Throwable} and all have to reach
 * the same conclusion about it. Written five times, that is five opportunities to diverge, and the
 * divergence would be invisible: an execution recorded as cancelled in the aggregate and as an
 * ordinary failure in the result registry looks correct from either side alone.</p>
 *
 * <p>It also settles the visibility question without widening anything. {@link
 * TraversalCancelledException}'s constructor is package-private on purpose — only this package may
 * declare that a traversal was stopped — and every caller of this helper is already in this package,
 * so no accessor, no public factory and no relaxed constructor is needed to classify a cause. The
 * type itself is public, so an out-of-package reader that genuinely needs the distinction can still
 * test for it; what stays package-private is the ability to <em>manufacture</em> one, which is the
 * half that matters.</p>
 *
 * <h2>The cause chain is walked, and it has to be</h2>
 * <p>A cancellation is raised deep inside a dispatch and reaches a terminal handler wrapped: through
 * {@code CompletionException} from the future machinery, and potentially through a failure route or
 * an engine adapter's own wrapper. Testing only the outermost throwable would classify a real
 * cancellation as a fault on every path that wraps, which is most of them. The walk is bounded
 * against a self-referential or cyclic cause chain rather than trusted to terminate, because this
 * runs on a traversal's completion path where a hang has no timeout above it.</p>
 */
final class ExecutionTermination {

    /**
     * Bounded because a cause chain is not guaranteed acyclic. A depth this large is already far past
     * anything a real wrapping produces, so the bound is a safety stop rather than a policy.
     */
    private static final int MAX_CAUSE_DEPTH = 64;

    private ExecutionTermination() {
    }

    /**
     * The reason to record for a traversal that ended with {@code failure}.
     *
     * @param failure the throwable that ended the traversal, or {@code null} when it did not end with
     *                one.
     * @return {@link ExecutionTerminationReason#CANCELLED} when the traversal was stopped on request,
     *         and {@code null} for every other termination — including a completion. {@code null}
     *         means "nothing distinguishes this termination", never "not yet classified".
     */
    static ExecutionTerminationReason reasonOf(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof TraversalCancelledException) {
                return ExecutionTerminationReason.CANCELLED;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                return null;
            }
            current = cause;
        }
        return null;
    }

    /**
     * Whether {@code failure} ended a traversal by cancellation.
     *
     * @param failure the throwable that ended the traversal, or {@code null}.
     * @return whether the termination is a cancellation rather than a fault.
     */
    static boolean isCancellation(Throwable failure) {
        return reasonOf(failure) == ExecutionTerminationReason.CANCELLED;
    }
}
