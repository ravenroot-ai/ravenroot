package ai.ravenroot.api.execution;

import ai.ravenroot.api.persistence.Retryability;

import java.time.Duration;

/**
 * What a {@link RetryPolicy} concluded about one failed attempt.
 *
 * <p>Sealed, and the two members are not symmetrical by accident: {@link Retry} carries everything
 * needed to commit and schedule the next attempt, while {@link Stop} carries the reason it is not
 * retrying. A single record with a boolean and nullable fields would let a caller read a delay off a
 * decision that is not retrying, which is the mistake this shape makes unrepresentable.</p>
 */
public sealed interface RetryDecision {

    /**
     * The classification the policy's classifier produced for the failure.
     *
     * <p>Present on both members, because it is the fact an operator needs in either case: it is what
     * a {@code NODE_RETRY_SCHEDULED} event reports when the policy retried, and it is what explains a
     * {@code NODE_FAILED} that an author expected to be retried and was not.</p>
     *
     * @return the failure classification behind this decision, never {@code null}
     */
    Retryability classification();

    /**
     * Whether this decision schedules a further attempt.
     *
     * @return {@code true} only for {@link Retry}
     */
    default boolean retrying() {
        return this instanceof Retry;
    }

    /**
     * The policy is scheduling another attempt.
     *
     * @param nextOrdinal    the ordinal the next attempt will carry, always the failed ordinal plus
     *                       one; this is the value that makes retries visible as distinct durable
     *                       attempts rather than as a counter on one
     * @param delay          how long to wait before that attempt, from {@link RetryBackoff}; may be
     *                       {@link Duration#ZERO} but never negative
     * @param classification the failure classification that authorised the retry
     */
    record Retry(int nextOrdinal, Duration delay, Retryability classification) implements RetryDecision {
        /** Requires a forward ordinal and a non-negative delay. */
        public Retry {
            if (nextOrdinal < 2) {
                throw new IllegalArgumentException("a retry ordinal is at least two: " + nextOrdinal);
            }
            if (delay == null) throw new IllegalArgumentException("delay cannot be null");
            if (delay.isNegative()) throw new IllegalArgumentException("delay cannot be negative: " + delay);
            if (classification == null) throw new IllegalArgumentException("classification cannot be null");
        }
    }

    /**
     * The policy is not scheduling another attempt.
     *
     * @param reason         why not, as a bounded classifier token suitable for an event's public
     *                       reason; see {@link RetryPolicy} for the tokens it produces
     * @param classification the failure classification behind the refusal
     */
    record Stop(String reason, Retryability classification) implements RetryDecision {
        /** Requires a non-blank reason token and a classification. */
        public Stop {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason cannot be blank");
            }
            if (classification == null) throw new IllegalArgumentException("classification cannot be null");
        }
    }
}
