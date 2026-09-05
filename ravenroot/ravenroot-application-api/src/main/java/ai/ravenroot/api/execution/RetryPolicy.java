package ai.ravenroot.api.execution;

import ai.ravenroot.api.persistence.Retryability;

import java.time.Duration;

/**
 * The bounded orchestration retry contract one node instance declares.
 *
 * <h2>What "bounded" means here, and why it is a count of attempts</h2>
 * <p>{@link #maxAttempts()} counts <em>attempts</em>, not retries, so {@code 1} is the no-retry policy
 * and {@code 3} means one initial attempt and up to two retries. Counting retries would make
 * {@code 0} the default and would leave "how many times can this effect happen" one addition away
 * from the number an author wrote — on the one question where an author must not have to do
 * arithmetic. The number is also exactly the ordinal of the last attempt the policy will allow, which
 * is the value that appears in the durable attempt history.</p>
 *
 * <h2>The disposition of each classification is fixed, and is not a second knob</h2>
 * <p>An author declares <em>how many</em> attempts, <em>how long</em> to wait, and <em>which
 * failures</em> are retryable. They do not declare what {@link Retryability#INDETERMINATE} means:
 * it means the effect may have landed, so it stops, always. Making that configurable would let a
 * graph opt into automatically repeating an effect of unknown outcome, which is precisely what
 * ADR 0022 refuses, and it would do so through a property whose name gives no hint that it is the
 * safety decision.</p>
 * <table>
 *   <caption>Fixed disposition per classification</caption>
 *   <tr><th>Classification</th><th>Disposition</th></tr>
 *   <tr><td>{@link Retryability#RETRYABLE_NO_EFFECT}</td><td>retry while attempts remain</td></tr>
 *   <tr><td>{@link Retryability#RETRY_AFTER_REREAD}</td><td>retry while attempts remain</td></tr>
 *   <tr><td>{@link Retryability#DETERMINISTIC_REJECT}</td><td>stop</td></tr>
 *   <tr><td>{@link Retryability#INDETERMINATE}</td><td>stop</td></tr>
 * </table>
 *
 * <h2>This policy never parks</h2>
 * <p>ADR 0022's park is the disposition of an outcome that was never <em>learned</em> — a worker died
 * between the dispatch and the record, so nobody knows what happened. A failure reaching this policy
 * has been learned: it is a {@link Throwable} in hand. So {@link Retryability#INDETERMINATE} here
 * means "do not repeat", and not repeating is what stopping delivers. Parking a live in-process
 * attempt would additionally be inert, because the traversal then ends and the aggregate refuses
 * every further attempt transition on a terminal traversal — the park would be unresolvable by the
 * very operator it exists for.</p>
 *
 * @param maxAttempts the total number of attempts allowed for one invocation, at least one; one is
 *                    the no-retry policy and is the default for every node that declares nothing
 * @param backoff     the wait before each retry; never {@code null}
 * @param classifier  how this node's failures are classified; never {@code null}
 */
public record RetryPolicy(int maxAttempts, RetryBackoff backoff, RetryClassifier classifier) {

    /** The public reason token reported when the attempt budget is used up. */
    public static final String REASON_EXHAUSTED = "retry-exhausted";

    /** The public reason token reported when the failure's classification forbids a retry. */
    public static final String REASON_NOT_RETRYABLE = "not-retryable";

    /**
     * The policy of a node that declared nothing: one attempt, no wait, nothing classified retryable.
     *
     * <p>Behaviourally identical to this runtime before orchestration retries existed, which is the
     * property that makes the whole feature additive: an existing graph gets this and cannot observe
     * that the retry machinery is present.</p>
     */
    public static final RetryPolicy NONE =
            new RetryPolicy(1, RetryBackoff.NONE, RetryClassifier.failClosed());

    /** Requires a positive attempt budget and both collaborators. */
    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least one: " + maxAttempts);
        }
        if (backoff == null) throw new IllegalArgumentException("backoff cannot be null");
        if (classifier == null) throw new IllegalArgumentException("classifier cannot be null");
    }

    /**
     * Whether this policy can ever produce a retry.
     *
     * <p>Read by the orchestrator to skip the classification work entirely for the overwhelmingly
     * common node that declared nothing. It is a property of the budget alone: a classifier that
     * recognises everything still cannot retry when only one attempt is allowed.</p>
     *
     * @return {@code true} when more than one attempt is permitted
     */
    public boolean enabled() {
        return maxAttempts > 1;
    }

    /**
     * Decides what to do after the attempt at {@code failedOrdinal} produced {@code failure}.
     *
     * <p>The budget is checked <em>before</em> the classification is reported, but the classification
     * is computed either way and travels on the {@link RetryDecision.Stop} — so an operator reading a
     * terminal failure can tell "this was retryable and we ran out" from "this was never going to be
     * retried", which are different things to fix.</p>
     *
     * @param failedOrdinal the one-based ordinal of the attempt that just failed; must be positive
     * @param failure       the throwable that attempt produced; must not be {@code null}
     * @return a {@link RetryDecision.Retry} carrying the next ordinal and its delay, or a
     *         {@link RetryDecision.Stop} carrying the reason
     */
    public RetryDecision decide(int failedOrdinal, Throwable failure) {
        if (failedOrdinal < 1) {
            throw new IllegalArgumentException("attempt ordinal must be positive: " + failedOrdinal);
        }
        if (failure == null) throw new IllegalArgumentException("failure cannot be null");
        Retryability classification = classification(failure);
        if (!retryable(classification)) {
            return new RetryDecision.Stop(REASON_NOT_RETRYABLE, classification);
        }
        if (failedOrdinal >= maxAttempts) {
            return new RetryDecision.Stop(REASON_EXHAUSTED, classification);
        }
        int nextOrdinal = failedOrdinal + 1;
        Duration delay = backoff.delayBefore(nextOrdinal);
        return new RetryDecision.Retry(nextOrdinal, delay, classification);
    }

    /**
     * Runs the classifier and converts every way it can misbehave into the fail-closed answer.
     *
     * <p>A classifier that throws, or returns {@code null}, has not classified anything. Letting
     * either escape would abort the failure handling of a node that already failed, leaving the
     * attempt neither retried nor recorded as terminal — the one outcome worse than either, because
     * nothing then records that a decision was owed.</p>
     */
    private Retryability classification(Throwable failure) {
        try {
            Retryability classified = classifier.classify(failure);
            return classified == null ? Retryability.DETERMINISTIC_REJECT : classified;
        } catch (RuntimeException unusable) {
            return Retryability.DETERMINISTIC_REJECT;
        }
    }

    /**
     * The fixed disposition table, as one switch so a new {@link Retryability} member is a compile
     * error here rather than a silent default.
     */
    private static boolean retryable(Retryability classification) {
        return switch (classification) {
            case RETRYABLE_NO_EFFECT, RETRY_AFTER_REREAD -> true;
            case DETERMINISTIC_REJECT, INDETERMINATE -> false;
        };
    }
}
