package ai.ravenroot.server.ratelimit;

/**
 * Token bucket with integer-exact refill, used for every rate-shaped limit.
 *
 * <p>A bucket is chosen over a fixed or sliding window for two reasons. It tolerates the bursts a real
 * client produces — one page load is dozens of asset requests — without raising the sustained ceiling.
 * And it makes {@code Retry-After} a computed fact rather than a guess: the deficit divided by the
 * refill rate is exactly how long the caller must wait, so the value the server sends is the value the
 * server will honour.</p>
 *
 * <p>Refill is computed in micro-tokens with integer arithmetic, and {@code lastRefillNanos} advances
 * only by the time actually converted into tokens. The truncated remainder is therefore carried
 * forward instead of discarded, so a client polling faster than one token per call cannot lose refill
 * to rounding and end up throttled below its configured rate.</p>
 *
 * <p>Instances are not thread-safe; {@link BoundedKeyRegistry} owns the lock that guards them.</p>
 */
final class TokenBucket implements BoundedKeyRegistry.Evictable {
    private static final long MICRO = 1_000_000L;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final long capacityMicro;
    private final int refillPerSecond;
    private long tokensMicro;
    private long lastRefillNanos;
    private long lastAccessNanos;

    /** Rate and burst are validated by {@link RateLimitConfiguration}, which bounds both. */
    TokenBucket(int refillPerSecond, int burst, long nowNanos) {
        this.refillPerSecond = refillPerSecond;
        this.capacityMicro = (long) burst * MICRO;
        this.tokensMicro = capacityMicro;
        this.lastRefillNanos = nowNanos;
        this.lastAccessNanos = nowNanos;
    }

    /**
     * Consumes one token.
     *
     * @return {@code 0} when the request is allowed, otherwise the whole seconds the caller must wait,
     *         always at least one so a rejection never invites an immediate retry
     */
    long tryConsume(long nowNanos) {
        lastAccessNanos = nowNanos;
        refill(nowNanos);
        if (tokensMicro >= MICRO) {
            tokensMicro -= MICRO;
            return 0;
        }
        long deficitMicro = MICRO - tokensMicro;
        long waitNanos = Math.ceilDiv(deficitMicro * 1_000L, refillPerSecond);
        return Math.max(1L, Math.ceilDiv(waitNanos, NANOS_PER_SECOND));
    }

    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastRefillNanos;
        if (elapsed <= 0) {
            return;
        }
        if (tokensMicro >= capacityMicro) {
            lastRefillNanos = nowNanos;
            tokensMicro = capacityMicro;
            return;
        }
        long deficitMicro = capacityMicro - tokensMicro;
        // Clamp before multiplying: a bucket idle for hours would otherwise overflow the product.
        long nanosToFull = Math.min(Long.MAX_VALUE / 2, Math.ceilDiv(deficitMicro * 1_000L, refillPerSecond));
        if (elapsed >= nanosToFull) {
            tokensMicro = capacityMicro;
            lastRefillNanos = nowNanos;
            return;
        }
        long grantedMicro = elapsed / 1_000L * refillPerSecond;
        if (grantedMicro <= 0) {
            return;
        }
        tokensMicro = Math.min(capacityMicro, tokensMicro + grantedMicro);
        // Advance only by the time that produced tokens, so the remainder survives to the next call.
        lastRefillNanos += Math.ceilDiv(grantedMicro * 1_000L, refillPerSecond);
    }

    /**
     * A bucket may be forgotten only once it is indistinguishable from a new one.
     *
     * <p>Evicting a partially drained bucket would hand the caller a full one, which is a bypass rather
     * than a cleanup. Requiring a full bucket makes eviction observationally equivalent to retention,
     * so the bound costs no enforcement.</p>
     */
    @Override
    public boolean evictable(long nowNanos, long idleTtlNanos) {
        refill(nowNanos);
        return tokensMicro >= capacityMicro && nowNanos - lastAccessNanos >= idleTtlNanos;
    }
}
