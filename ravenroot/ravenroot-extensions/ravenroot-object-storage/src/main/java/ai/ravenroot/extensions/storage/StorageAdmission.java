package ai.ravenroot.extensions.storage;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/** Linearizable active-only tenant/profile admission and fixed-window rate limit registry. */
final class StorageAdmission {
    private static final int MAX_GATES = 4096;
    private static final long RATE_WINDOW_NANOS = 1_000_000_000L;
    enum Refusal { CAPACITY, RATE }
    record Result(Lease lease, Refusal refusal) {
        static Result accepted(Lease lease) { return new Result(lease, null); }
        static Result refused(Refusal refusal) { return new Result(null, refusal); }
    }

    private final Map<String, Gate> gates = new HashMap<>();

    synchronized Result acquire(String key, int maximum, int requestsPerSecond, long nowNanos) {
        gates.entrySet().removeIf(entry -> entry.getValue().references == 0
                && nowNanos - entry.getValue().window >= RATE_WINDOW_NANOS);
        Gate gate = gates.get(key);
        if (gate == null) {
            if (gates.size() >= MAX_GATES) return Result.refused(Refusal.CAPACITY);
            gate = new Gate(maximum, requestsPerSecond, nowNanos);
            gates.put(key, gate);
        } else if (gate.maximum != maximum || gate.requestsPerSecond != requestsPerSecond) {
            throw StorageException.of(StorageException.Code.CONFIGURATION);
        }
        gate.references++;
        if (!gate.permits.tryAcquire()) {
            releaseReference(key, gate, false);
            return Result.refused(Refusal.CAPACITY);
        }
        if (!gate.takeRate(nowNanos)) {
            releaseReference(key, gate, true);
            return Result.refused(Refusal.RATE);
        }
        return Result.accepted(new Lease(this, key, gate));
    }

    private synchronized void release(String key, Gate gate) { releaseReference(key, gate, true); }

    private synchronized boolean retry(Gate gate, long nowNanos) {
        return gate.takeRate(nowNanos);
    }

    private void releaseReference(String key, Gate gate, boolean permit) {
        if (permit) gate.permits.release();
        gate.references--;
        // Keep the idle rate window so sequential calls cannot reset the operator rate ceiling.
    }

    synchronized int size() { return gates.size(); }

    static final class Lease implements AutoCloseable {
        private StorageAdmission owner;
        private final String key;
        private final Gate gate;
        Lease(StorageAdmission owner, String key, Gate gate) { this.owner = owner; this.key = key; this.gate = gate; }
        @Override public void close() {
            StorageAdmission current;
            synchronized (this) { current = owner; owner = null; }
            if (current != null) current.release(key, gate);
        }
        boolean retry(long nowNanos) {
            StorageAdmission current;
            synchronized (this) { current = owner; }
            return current != null && current.retry(gate, nowNanos);
        }
    }

    private static final class Gate {
        final int maximum;
        final int requestsPerSecond;
        final Semaphore permits;
        long window;
        int used;
        int references;

        Gate(int maximum, int requestsPerSecond, long now) {
            this.maximum = maximum;
            this.requestsPerSecond = requestsPerSecond;
            permits = new Semaphore(maximum, true);
            window = now;
        }

        boolean takeRate(long now) {
            if (now - window >= RATE_WINDOW_NANOS || now < window) { window = now; used = 0; }
            if (used >= requestsPerSecond) return false;
            used++;
            return true;
        }
    }
}
