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

    synchronized CompletionStage<Lease> acquire(Key key, int limit) {
        return acquire(key, limit, limit, Integer.MAX_VALUE, true);
    }

    synchronized CompletionStage<Lease> acquire(Key key, int limit, int runnerLimit, int queueLimit) {
        return acquire(key, limit, runnerLimit, queueLimit, true);
    }

    /**
     * Admission for a <em>re-entry</em> to a node this traversal has already admitted, which never
     * creates a gate.
     *
     * <h4>Why an arrival that cannot create a gate is the right shape for a retry</h4>
     * <p>{@link #release(UUID)} drops a traversal's gates when the traversal ends, and then the
     * caller releases the pause gate on the same thread — so a retry parked there asks for admission
     * <em>after</em> its gates are gone. {@link #acquire(Key, int)} would create a fresh one, the
     * retry would then be refused by its {@code RUNNING} commit and close its lease, and the gate
     * would sit in this map until the whole runner closed. One entry per retrying node per traversal,
     * on a server whose runners outlive their traversals.</p>
     *
     * <p>Refusing to create is the fix at the source rather than at one ordering that happens to
     * trigger it. Reordering the two lines in the caller closes the deterministic case and leaves the
     * racing one: a retry whose backoff completed just as the traversal ended reaches this method on
     * its own thread, at any moment, including after the release. Here the absence check and the
     * decision not to create are the same atomic {@code compute}, so there is no window at all.</p>
     *
     * <p>It costs no new state, which a "traversals that have ended" set would not: that set would
     * grow exactly as fast as the gates it replaced, moving the leak rather than closing it.</p>
     *
     * <p>And it is <em>true</em>, which is what makes it safe rather than merely effective. A retry
     * is by construction a re-entry: its first attempt went through the ordinary dispatch and created
     * this key's gate. So an absent gate here means precisely "this traversal's admission is gone",
     * which means the traversal has ended — making this registry a second, independent refusal of a
     * retry into an ended traversal, layered under the {@code RUNNING} commit's refusal rather than
     * duplicating it.</p>
     *
     * @param key   the node and traversal being re-entered
     * @param limit the node's declared concurrency, which must match the gate already present
     * @return a lease, or a stage failed with {@link TraversalCancelledException} when this
     *         traversal holds no gate for the node any more
     */
    synchronized CompletionStage<Lease> reacquire(Key key, int limit, int runnerLimit, int queueLimit) {
        return acquire(key, limit, runnerLimit, queueLimit, false);
    }

    synchronized CompletionStage<Lease> reacquire(Key key, int limit) {
        return acquire(key, limit, limit, Integer.MAX_VALUE, false);
    }

    private synchronized CompletionStage<Lease> acquire(Key key, int limit, int runnerLimit, int queueLimit,
                                                        boolean admitFirstArrival) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        if (runnerLimit < 1) throw new IllegalArgumentException("runnerLimit must be positive");
        if (queueLimit < 1) throw new IllegalArgumentException("queueLimit must be positive");
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Traversal admission closed"));
        }
        Gate gate = gates.compute(key, (ignored, current) -> {
            if (current == null) return admitFirstArrival ? new Gate(limit, queueLimit) : null;
            if (current.limit != limit || current.queueLimit != queueLimit) {
                throw new IllegalStateException("Runtime concurrency changed inside one traversal");
            }
            return current;
        });
        if (gate == null) {
            return CompletableFuture.failedFuture(
                    new TraversalCancelledException(key.traversalId(), key.nodeId()));
        }
        NodeKey nodeKey = NodeKey.from(key);
        Gate runnerGate = runnerGates.compute(nodeKey, (ignored, current) -> {
            if (current == null) return admitFirstArrival ? new Gate(runnerLimit, queueLimit) : null;
            if (current.limit != runnerLimit || current.queueLimit != queueLimit) {
                throw new IllegalStateException("Runner admission changed while active");
            }
            return current;
        });
        if (runnerGate == null) {
            return CompletableFuture.failedFuture(
                    new TraversalCancelledException(key.traversalId(), key.nodeId()));
        }
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

    /**
     * How many gate entries this registry holds, across every traversal. Diagnostics.
     *
     * <p>Counts <em>entries</em>, not permits, which is the quantity {@link #active(UUID)} cannot
     * answer: a gate whose lease has been closed reports zero active and is still an entry in the
     * map. That difference is exactly what a leak looks like from the outside.</p>
     *
     * @return the number of live {@link Key} to {@link Gate} mappings
     */
    synchronized int gateCount() {
        return gates.size();
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
