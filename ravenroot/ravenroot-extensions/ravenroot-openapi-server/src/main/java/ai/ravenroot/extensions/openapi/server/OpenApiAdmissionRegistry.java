package ai.ravenroot.extensions.openapi.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Active-source-only tenant/profile admission registry with per-key linearized retention/removal. */
final class OpenApiAdmissionRegistry {
    private final ConcurrentHashMap<String, Gate> gates = new ConcurrentHashMap<>();

    Handle open(String key, int maximum) {
        Gate gate = gates.compute(key, (ignored, current) -> current == null
                ? new Gate(maximum) : current.retain(maximum));
        return new Handle(this, key, gate);
    }

    int size() { return gates.size(); }

    private void retain(String key, Gate gate) {
        gates.compute(key, (ignored, current) -> {
            if (current != gate) throw new IllegalStateException("admission gate identity lost");
            return gate.retain(gate.maximum);
        });
    }

    private void release(String key, Gate gate) {
        gates.compute(key, (ignored, current) -> {
            if (current != gate) throw new IllegalStateException("admission gate identity lost");
            return gate.releaseReference() ? null : gate;
        });
    }

    private static final class Gate {
        private final int maximum;
        private final Semaphore permits;
        private int references = 1;

        private Gate(int maximum) { this.maximum = maximum; this.permits = new Semaphore(maximum, true); }
        private Gate retain(int expected) {
            if (maximum != expected) throw OpenApiValues.invalid();
            references++; return this;
        }
        private boolean releaseReference() { return --references == 0; }
    }

    static final class Handle implements AutoCloseable {
        private final OpenApiAdmissionRegistry registry;
        private final String key;
        private final Gate gate;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Handle(OpenApiAdmissionRegistry registry, String key, Gate gate) {
            this.registry = registry; this.key = key; this.gate = gate;
        }
        synchronized Permit tryAcquire() {
            if (closed.get()) return null;
            registry.retain(key, gate);
            if (!gate.permits.tryAcquire()) { registry.release(key, gate); return null; }
            return new Permit(gate.permits, () -> registry.release(key, gate));
        }
        @Override public synchronized void close() {
            if (closed.compareAndSet(false, true)) registry.release(key, gate);
        }
    }

    static final class Permit implements AutoCloseable {
        private final Semaphore permits;
        private final Runnable releaseReference;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Permit(Semaphore permits, Runnable releaseReference) {
            this.permits = permits; this.releaseReference = releaseReference;
        }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) {
                permits.release(); releaseReference.run();
            }
        }
    }
}
