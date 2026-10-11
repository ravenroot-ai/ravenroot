package ai.ravenroot.extensions.systemone;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Per-tenant/profile admission whose idle entries are removed. */
final class SystemOneAdmission {
    private final ConcurrentHashMap<String, Gate> gates = new ConcurrentHashMap<>();

    Lease tryAcquire(String key, int maximum) {
        Gate gate = gates.compute(key, (ignored, current) -> current == null
                ? new Gate(maximum) : current.retain());
        if (!gate.permits.tryAcquire()) {
            releaseReference(key, gate);
            return null;
        }
        return new Lease(() -> {
            gate.permits.release();
            releaseReference(key, gate);
        });
    }

    int size() { return gates.size(); }

    private void releaseReference(String key, Gate gate) {
        gates.compute(key, (ignored, current) -> {
            if (current != gate) throw new IllegalStateException("admission gate identity lost");
            return --gate.references == 0 ? null : gate;
        });
    }

    private static final class Gate {
        private final Semaphore permits;
        private int references = 1;
        Gate(int maximum) { permits = new Semaphore(maximum); }
        Gate retain() { references++; return this; }
    }

    static final class Lease implements AutoCloseable {
        private final Runnable release;
        private final AtomicBoolean released = new AtomicBoolean();
        Lease(Runnable release) { this.release = Objects.requireNonNull(release, "release"); }
        @Override public void close() {
            if (released.compareAndSet(false, true)) release.run();
        }
    }
}
