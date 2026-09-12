package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.EngineState;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.Mailbox;
import ai.ravenroot.api.execution.NodeContext;
import ai.ravenroot.api.execution.NodeLifecycleState;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeStatus;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.execution.Scheduler;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test engine that executes real node behavior while making graceful termination stay unsettled.
 *
 * <p>The real delivery matters: composition tests using this probe retain their durable writes,
 * effects, acknowledgements, and terminal assertions. Only teardown is synthetic. A runner that
 * receives the configured short bound must escalate every retiring node from {@link #stop(NodeRef)}
 * to {@link #cancel(NodeRef)}; the probe exposes that transition without sleeping for the legacy
 * ten-second bound.</p>
 */
public final class ShutdownBoundProbeEngine implements ExecutionEngine {
    private final Map<NodeRef, Entry> nodes = new ConcurrentHashMap<>();
    private final AtomicInteger cancellations = new AtomicInteger();
    private final CompletableFuture<Void> firstCancellation = new CompletableFuture<>();
    private volatile EngineState state = EngineState.RUNNING;

    @Override
    public String id() {
        return "shutdown-bound-probe";
    }

    @Override
    public Set<EngineCapability> capabilities() {
        return Set.of();
    }

    @Override
    public Scheduler scheduler() {
        return (delay, task) -> () -> true;
    }

    @Override
    public NodeRef spawn(String logicalName, RavenNode node) {
        if (!state.accepting()) {
            throw new IllegalStateException("probe engine is not accepting nodes");
        }
        NodeRef ref = new NodeRef(logicalName + "-" + UUID.randomUUID());
        Entry entry = new Entry(ref, node);
        nodes.put(ref, entry);
        try {
            node.onStart(entry.context);
        } catch (RuntimeException | Error failure) {
            nodes.remove(ref, entry);
            throw failure;
        }
        return ref;
    }

    @Override
    public CompletionStage<NodeResult> send(NodeRef target, NodeMessage message) {
        Entry entry = nodes.get(target);
        if (entry == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("unknown probe node"));
        }
        return entry.node.onMessage(message, entry.context);
    }

    @Override
    public CompletionStage<Void> stop(NodeRef target) {
        Entry entry = nodes.get(target);
        return entry == null ? CompletableFuture.completedFuture(null) : entry.gracefulStop;
    }

    @Override
    public CompletionStage<Void> cancel(NodeRef target) {
        Entry entry = nodes.remove(target);
        if (entry == null) {
            return CompletableFuture.completedFuture(null);
        }
        entry.cancel();
        cancellations.incrementAndGet();
        firstCancellation.complete(null);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public EngineState state() {
        return state;
    }

    @Override
    public Optional<NodeStatus> status(NodeRef target) {
        Entry entry = nodes.get(target);
        return entry == null
                ? Optional.empty()
                : Optional.of(new NodeStatus(target, NodeLifecycleState.RUNNING, null, 0));
    }

    @Override
    public CompletionStage<Void> drain() {
        state = EngineState.DRAINING;
        return CompletableFuture.allOf(nodes.keySet().stream()
                .map(this::stop)
                .map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new));
    }

    /** Number of nodes whose unsettled graceful stop was escalated. */
    public int cancellationCount() {
        return cancellations.get();
    }

    /** Completes when the first configured-bound escalation reaches the engine. */
    public CompletionStage<Void> firstCancellation() {
        return firstCancellation;
    }

    @Override
    public void close() {
        state = EngineState.CLOSED;
        Set.copyOf(nodes.keySet()).forEach(this::cancel);
    }

    private final class Entry {
        private final NodeRef ref;
        private final RavenNode node;
        private final ProbeCancellation cancellation = new ProbeCancellation();
        private final CompletableFuture<Void> gracefulStop = new CompletableFuture<>();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final NodeContext context;

        private Entry(NodeRef ref, RavenNode node) {
            this.ref = ref;
            this.node = node;
            this.context = new NodeContext() {
                @Override public NodeRef self() { return Entry.this.ref; }
                @Override public Scheduler scheduler() { return ShutdownBoundProbeEngine.this.scheduler(); }
                @Override public Mailbox mailbox() { return () -> 0; }
                @Override public CancellationSignal cancellation() { return cancellation; }
            };
        }

        private void cancel() {
            cancellation.cancel();
            if (stopped.compareAndSet(false, true)) {
                node.onStop(context);
            }
            gracefulStop.complete(null);
        }
    }

    private static final class ProbeCancellation implements CancellationSignal {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CopyOnWriteArrayList<OnceListener> listeners = new CopyOnWriteArrayList<>();

        @Override
        public boolean cancelled() {
            return cancelled.get();
        }

        @Override
        public void onCancel(Runnable listener) {
            java.util.Objects.requireNonNull(listener, "listener");
            OnceListener owned = new OnceListener(listener);
            listeners.add(owned);
            if (cancelled.get() && listeners.remove(owned)) {
                owned.run();
            }
        }

        private void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            for (OnceListener listener : listeners) {
                if (listeners.remove(listener)) listener.run();
            }
        }

        private static final class OnceListener {
            private final Runnable delegate;
            private final AtomicBoolean invoked = new AtomicBoolean();

            private OnceListener(Runnable delegate) {
                this.delegate = delegate;
            }

            private void run() {
                if (!invoked.compareAndSet(false, true)) return;
                try {
                    delegate.run();
                } catch (RuntimeException ignored) {
                    // Cancellation is fault-isolated exactly as the SPI requires.
                }
            }
        }
    }
}
