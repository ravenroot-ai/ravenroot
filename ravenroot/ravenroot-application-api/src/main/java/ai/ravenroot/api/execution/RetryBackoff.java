package ai.ravenroot.api.execution;

import java.time.Duration;

/**
 * The bounded, deterministic delay a node waits before its next orchestration attempt.
 *
 * <h2>Deterministic, and no jitter</h2>
 * <p>{@link #delayBefore(int)} is a pure function of the ordinal, so a test that asserts "the second
 * attempt waited 200ms" asserts something reproducible, and an operator reading a
 * {@code NODE_RETRY_SCHEDULED} event can predict the next one. Jitter is the usual answer to
 * synchronised retry storms and is deliberately absent here rather than defaulted on: it would make
 * every backoff assertion in the suite a range check, and the storm it protects against needs a
 * seeded, injectable randomness source to stay testable. Adding it later is additive — a component
 * with a zero default — and does not change any value this type produces today.</p>
 *
 * <h2>The bound is on the delay, not on the total</h2>
 * <p>{@link #maxDelay()} caps each individual wait. The total time a retrying node can occupy is
 * bounded by {@code maxAttempts} on {@link RetryPolicy}, which is the component that owns "how many",
 * and by cancellation, which interrupts a wait in progress. A separate total-elapsed budget was
 * considered and left out: two independent bounds on one loop produce a policy where the effective
 * attempt count depends on how slow the failures happened to be, which is not something an author can
 * reason about when they write the number.</p>
 *
 * @param initialDelay the wait before the second attempt, that is before the first retry; never
 *                     negative
 * @param multiplier   growth factor applied once per further retry; {@code 1.0} is a fixed delay and
 *                     values below {@code 1.0} are refused, because a backoff that shrinks is a
 *                     retry storm written as a policy
 * @param maxDelay     ceiling on any single wait; never negative and never below
 *                     {@code initialDelay}
 */
public record RetryBackoff(Duration initialDelay, double multiplier, Duration maxDelay) {

    /** The default first wait, short enough that a transient blip costs little and long enough to matter. */
    public static final Duration DEFAULT_INITIAL_DELAY = Duration.ofMillis(200);

    /** The default growth factor: doubling, the ordinary exponential shape. */
    public static final double DEFAULT_MULTIPLIER = 2.0;

    /** The default ceiling on one wait. */
    public static final Duration DEFAULT_MAX_DELAY = Duration.ofSeconds(30);

    /**
     * The backoff a node gets when it declares a retry count and nothing else.
     *
     * <p>A constant rather than a factory call at each use site, so "the default backoff" is one
     * object every reader can identify by reference.</p>
     */
    public static final RetryBackoff DEFAULT =
            new RetryBackoff(DEFAULT_INITIAL_DELAY, DEFAULT_MULTIPLIER, DEFAULT_MAX_DELAY);

    /**
     * A backoff that never waits, for a node whose failures are known to be instantaneous to retest.
     *
     * <p>Kept as a named constant because {@code new RetryBackoff(Duration.ZERO, 1.0, Duration.ZERO)}
     * at a call site reads as an oversight rather than a choice.</p>
     */
    public static final RetryBackoff NONE = new RetryBackoff(Duration.ZERO, 1.0, Duration.ZERO);

    /** Refuses a shape whose waits would shrink, run backwards, or exceed their own ceiling. */
    public RetryBackoff {
        if (initialDelay == null) throw new IllegalArgumentException("initialDelay cannot be null");
        if (maxDelay == null) throw new IllegalArgumentException("maxDelay cannot be null");
        if (initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay cannot be negative: " + initialDelay);
        }
        if (maxDelay.isNegative()) {
            throw new IllegalArgumentException("maxDelay cannot be negative: " + maxDelay);
        }
        if (maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException(
                    "maxDelay " + maxDelay + " is below initialDelay " + initialDelay);
        }
        if (!(multiplier >= 1.0) || Double.isInfinite(multiplier)) {
            // Written as !(x >= 1.0) rather than x < 1.0 so NaN is refused here rather than passing
            // every ordinary comparison below and producing a NaN delay nobody can schedule.
            throw new IllegalArgumentException("multiplier must be a finite value of at least 1.0: "
                    + multiplier);
        }
    }

    /**
     * The wait preceding the attempt at {@code ordinal}.
     *
     * <p>Ordinal one is the initial attempt and is never preceded by a wait, so it answers
     * {@link Duration#ZERO}. Ordinal two answers {@link #initialDelay()}, and each further ordinal
     * multiplies by {@link #multiplier()} up to {@link #maxDelay()}.</p>
     *
     * <p>Computed by iteration rather than by {@code Math.pow}, and that is not stylistic: a large
     * ordinal with a multiplier above one overflows a {@code double} exponent into infinity, and
     * {@code Duration.ofMillis((long) Double.POSITIVE_INFINITY)} is {@link Long#MAX_VALUE}
     * milliseconds — a wait of nearly three hundred million years, produced silently. The loop
     * saturates at {@link #maxDelay()} on the first iteration that reaches it and stops, so no
     * ordinal, however large, can leave this method with a value above the declared ceiling.</p>
     *
     * @param ordinal the one-based attempt ordinal the wait precedes; must be positive
     * @return the delay before that attempt, never negative and never above {@link #maxDelay()}
     */
    public Duration delayBefore(int ordinal) {
        if (ordinal < 1) {
            throw new IllegalArgumentException("attempt ordinal must be positive: " + ordinal);
        }
        if (ordinal == 1 || initialDelay.isZero()) {
            return Duration.ZERO;
        }
        Duration delay = initialDelay;
        for (int step = 2; step < ordinal; step++) {
            if (delay.compareTo(maxDelay) >= 0) {
                return maxDelay;
            }
            delay = scale(delay);
        }
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }

    /**
     * One growth step, in nanoseconds, saturating at the ceiling rather than overflowing.
     *
     * <p>{@link Duration#multipliedBy(long)} takes a {@code long} and would truncate a fractional
     * multiplier to zero or one, so the arithmetic is done on the nanosecond count. The saturation
     * check is on the {@code double} before it is narrowed, because narrowing a value above
     * {@link Long#MAX_VALUE} clamps silently and the clamped result is indistinguishable from a real
     * one.
     */
    private Duration scale(Duration delay) {
        double grown = delay.toNanos() * multiplier;
        if (!(grown < (double) Long.MAX_VALUE)) {
            return maxDelay;
        }
        Duration next = Duration.ofNanos((long) grown);
        return next.compareTo(maxDelay) > 0 ? maxDelay : next;
    }
}
