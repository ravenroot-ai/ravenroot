package ai.ravenroot.core.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Non-blocking per-node/per-traversal admission, with deterministic queue cleanup. */
final class TraversalAdmissionRegistry implements AutoCloseable {
    private final ConcurrentHashMap<Key, Gate> gates = new ConcurrentHashMap<>();
    private boolean closed;

    synchronized CompletionStage<Lease> acquire(Key key, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Traversal admission closed"));
        }
        Gate gate = gates.compute(key, (ignored, current) -> {
            if (current == null) return new Gate(limit);
            if (current.limit != limit) {
                throw new IllegalStateException("Runtime concurrency changed inside one traversal");
            }
            return current;
        });
        return gate.acquire();
    }

    synchronized void release(UUID traversalId) {
        gates.forEach((key, gate) -> {
            if (traversalId.equals(key.traversalId()) && gates.remove(key, gate)) gate.close();
        });
    }

    synchronized int active(UUID traversalId) {
        return gates.entrySet().stream().filter(entry -> traversalId.equals(entry.getKey().traversalId()))
                .mapToInt(entry -> entry.getValue().active()).sum();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        List<Gate> snapshot = new ArrayList<>(gates.values());
        gates.clear();
        snapshot.forEach(Gate::close);
    }

    record Key(String tenantId, String deploymentId, String graphVersion, UUID traversalId, String nodeId) {
        Key {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(graphVersion, "graphVersion");
            Objects.requireNonNull(traversalId, "traversalId");
            Objects.requireNonNull(nodeId, "nodeId");
        }
    }

    static final class Lease implements AutoCloseable {
        private final Gate gate;
        private final AtomicBoolean released = new AtomicBoolean();
        Lease(Gate gate) { this.gate = gate; }
        @Override public void close() {
            if (released.compareAndSet(false, true)) {
                gate.release();
            }
        }
    }

    private static final class Gate {
        private final int limit;
        private final ArrayDeque<CompletableFuture<Lease>> waiting = new ArrayDeque<>();
        private int active;
        private boolean closed;
        Gate(int limit) { this.limit = limit; }

        synchronized CompletionStage<Lease> acquire() {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Traversal admission closed"));
            if (active < limit) {
                active++;
                return CompletableFuture.completedFuture(new Lease(this));
            }
            var pending = new CompletableFuture<Lease>();
            waiting.addLast(pending);
            return pending.thenApply(ignored -> new Lease(this));
        }

        void release() {
            CompletableFuture<Lease> next = null;
            synchronized (this) {
                while (!waiting.isEmpty() && next == null) {
                    CompletableFuture<Lease> candidate = waiting.removeFirst();
                    if (!candidate.isDone()) next = candidate;
                }
                if (next == null) active = Math.max(0, active - 1);
            }
            // Completing outside the monitor is load-bearing: a same-thread engine may execute the
            // admitted node synchronously from this continuation.
            if (next != null) next.complete(null);
        }

        synchronized int active() { return active; }
        synchronized void close() {
            closed = true;
            waiting.forEach(waiter -> waiter.completeExceptionally(
                    new IllegalStateException("Traversal admission closed")));
            waiting.clear();
        }
    }
}
