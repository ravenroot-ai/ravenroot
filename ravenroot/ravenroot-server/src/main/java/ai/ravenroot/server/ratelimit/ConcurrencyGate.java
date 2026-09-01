package ai.ravenroot.server.ratelimit;

/**
 * Counts a resource that is <em>held</em> rather than consumed: in-flight submissions and open streams.
 *
 * <p>A token bucket is the wrong instrument here. A bucket asks "how often", but an SSE connection or a
 * running submission occupies capacity for as long as it lasts, and a caller that opens one stream per
 * second and never closes them stays comfortably inside any rate limit while exhausting the server. The
 * limit that matters is a count of simultaneous holders, so this is a plain non-blocking semaphore.</p>
 *
 * <p>Acquisition never blocks. Making a caller wait for a permit converts an exhausted limit into
 * exhausted threads, and turns a fast 429 into a slow one that costs the server more than the request
 * it was refusing. Over the limit, the caller is told so immediately.</p>
 *
 * <p>Instances are not thread-safe on their own; {@link BoundedKeyRegistry} owns the lock.</p>
 */
final class ConcurrencyGate implements BoundedKeyRegistry.Evictable {
    private final int permits;
    private int held;
    private long lastAccessNanos;

    ConcurrencyGate(int permits, long nowNanos) {
        this.permits = permits;
        this.lastAccessNanos = nowNanos;
    }

    boolean tryAcquire(long nowNanos) {
        lastAccessNanos = nowNanos;
        if (held >= permits) {
            return false;
        }
        held++;
        return true;
    }

    void release(long nowNanos) {
        lastAccessNanos = nowNanos;
        if (held > 0) {
            held--;
        }
    }

    int held() {
        return held;
    }

    /**
     * Only a gate holding nothing may be forgotten.
     *
     * <p>Evicting a gate with permits outstanding would let the next caller re-acquire capacity that is
     * still in use, and would also lose the releases still to come. The zero-held condition makes
     * eviction safe by construction.</p>
     */
    @Override
    public boolean evictable(long nowNanos, long idleTtlNanos) {
        return held == 0 && nowNanos - lastAccessNanos >= idleTtlNanos;
    }
}
