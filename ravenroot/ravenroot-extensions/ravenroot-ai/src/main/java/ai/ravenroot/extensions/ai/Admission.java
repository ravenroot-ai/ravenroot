package ai.ravenroot.extensions.ai;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-key concurrency admission that does not grow without bound.
 *
 * <p>A plain {@code ConcurrentHashMap<String, Semaphore>} keyed by tenant would be simpler and would
 * also be a slow leak: a deployment that sees many tenants keeps one permanently idle semaphore per
 * tenant it has ever served, and nothing ever removes it. So each gate carries a reference count and
 * is dropped from the map when the last lease is released — the same shape
 * {@code OpenApiCallNodeBehavior.AdmissionRegistry} uses, for the same reason, expressed here rather
 * than reached for across a module boundary that does not exist between two bundles.</p>
 */
final class Admission {

    private final ConcurrentHashMap<String, Gate> gates = new ConcurrentHashMap<>();

    /**
     * @return a lease that must be released exactly once, or {@code null} when {@code maximum}
     *     concurrent holders already exist for {@code key}
     */
    Lease tryAcquire(String key, int maximum) {
        Gate gate = gates.compute(key, (ignored, current) ->
                current == null ? new Gate(maximum) : current.retain());
        if (!gate.permits.tryAcquire()) {
            releaseReference(key, gate);
            return null;
        }
        return new Lease(() -> {
            gate.permits.release();
            releaseReference(key, gate);
        });
    }

    /** Test seam: how many keys are currently held. Zero when nothing is in flight. */
    int size() {
        return gates.size();
    }

    private void releaseReference(String key, Gate gate) {
        gates.compute(key, (ignored, current) -> {
            if (current != gate) {
                // Identity is the invariant this map is built on: a different gate under the same key
                // would mean a lease releasing a permit it does not hold.
                throw new IllegalStateException("admission gate identity lost");
            }
            return --gate.references == 0 ? null : gate;
        });
    }

    private static final class Gate {
        private final Semaphore permits;
        /** Guarded by the map's per-key compute lock, which is the only place it is touched. */
        private int references = 1;

        Gate(int maximum) {
            permits = new Semaphore(maximum);
        }

        Gate retain() {
            references++;
            return this;
        }
    }

    /** Idempotent on release, so a failure path and a completion path may both close it. */
    static final class Lease implements AutoCloseable {
        private final Runnable release;
        private final AtomicBoolean released = new AtomicBoolean();

        Lease(Runnable release) {
            this.release = Objects.requireNonNull(release, "release");
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                release.run();
            }
        }
    }
}
