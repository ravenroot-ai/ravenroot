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
    private final ConcurrentHashMap<NodeKey, Gate> runnerGates = new ConcurrentHashMap<>();
    private boolean closed;

    synchronized CompletionStage<Lease> acquire(Key key, int limit, int runnerLimit, int queueLimit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        if (runnerLimit < 1) throw new IllegalArgumentException("runnerLimit must be positive");
        if (queueLimit < 1) throw new IllegalArgumentException("queueLimit must be positive");
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Traversal admission closed"));
        }
        Gate gate = gates.compute(key, (ignored, current) -> {
            if (current == null) return new Gate(limit, queueLimit);
            if (current.limit != limit || current.queueLimit != queueLimit) {
                throw new IllegalStateException("Runtime concurrency changed inside one traversal");
            }
            return current;
        });
        NodeKey nodeKey = NodeKey.from(key);
        Gate runnerGate = runnerGates.compute(nodeKey, (ignored, current) -> {
            if (current == null) return new Gate(runnerLimit, queueLimit);
            if (current.limit != runnerLimit || current.queueLimit != queueLimit) {
                throw new IllegalStateException("Runner admission changed while active");
            }
            return current;
        });
        // The traversal-local gate is always acquired first. No caller acquires these in the reverse
        // order, so holding it while the runner-wide resident/mailbox gate waits cannot deadlock.
        return gate.acquire().thenCompose(local -> runnerGate.acquire()
                .handle((shared, error) -> {
                    if (error != null) {
                        local.close();
                        throw new java.util.concurrent.CompletionException(error);
                    }
                    return new Lease(local, shared);
                }));
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
        snapshot.addAll(runnerGates.values());
        gates.clear();
        runnerGates.clear();
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

    private record NodeKey(String tenantId, String deploymentId, String graphVersion, String nodeId) {
        private static NodeKey from(Key key) {
            return new NodeKey(key.tenantId(), key.deploymentId(), key.graphVersion(), key.nodeId());
        }
    }

    static final class Lease implements AutoCloseable {
        private final GateLease local;
        private final GateLease shared;
        private final AtomicBoolean released = new AtomicBoolean();
        Lease(GateLease local, GateLease shared) {
            this.local = local;
            this.shared = shared;
        }
        @Override public void close() {
            if (released.compareAndSet(false, true)) {
                shared.close();
                local.close();
            }
        }
    }

    private static final class GateLease implements AutoCloseable {
        private final Gate gate;
        private final AtomicBoolean released = new AtomicBoolean();
        private GateLease(Gate gate) { this.gate = gate; }
        @Override public void close() {
            if (released.compareAndSet(false, true)) gate.release();
        }
    }

    private static final class Gate {
        private final int limit;
        private final int queueLimit;
        private final ArrayDeque<CompletableFuture<GateLease>> waiting = new ArrayDeque<>();
        private int active;
        private boolean closed;
        Gate(int limit, int queueLimit) {
            this.limit = limit;
            this.queueLimit = queueLimit;
        }

        synchronized CompletionStage<GateLease> acquire() {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Traversal admission closed"));
            if (active < limit) {
                active++;
                return CompletableFuture.completedFuture(new GateLease(this));
            }
            if (waiting.size() >= queueLimit) {
                return CompletableFuture.failedFuture(new GraphExecutionLimitException(
                        GraphExecutionLimitException.Reason.ADMISSION_QUEUE, waiting.size() + 1L, queueLimit));
            }
            var pending = new CompletableFuture<GateLease>();
            waiting.addLast(pending);
            return pending.thenApply(ignored -> new GateLease(this));
        }

        void release() {
            CompletableFuture<GateLease> next = null;
            synchronized (this) {
                while (!waiting.isEmpty() && next == null) {
                    CompletableFuture<GateLease> candidate = waiting.removeFirst();
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
