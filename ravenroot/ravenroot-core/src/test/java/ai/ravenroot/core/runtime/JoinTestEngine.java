package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.EngineState;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.NodeContext;
import ai.ravenroot.api.execution.NodeLifecycleState;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeStatus;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.execution.ScheduledTask;
import ai.ravenroot.api.execution.Scheduler;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Engine for fan-in tests: nodes really run, and they run on a pool so branches of a fan-out are
 * genuinely concurrent.
 *
 * <p>A same-thread engine would make every join race resolve in dispatch order and would turn a
 * concurrency test into an ordering test. Its scheduler is manual, so a join timeout fires when the
 * test says so rather than when the wall clock says so — a timeout test that sleeps is a test whose
 * result depends on the build machine's load.</p>
 */
final class JoinTestEngine implements ExecutionEngine {

    private final Map<NodeRef, RavenNode> nodes = new ConcurrentHashMap<>();
    private final ExecutorService pool;
    private final ManualScheduler scheduler = new ManualScheduler();
    private volatile EngineState state = EngineState.RUNNING;
    private final java.util.concurrent.atomic.AtomicInteger spawned = new java.util.concurrent.atomic.AtomicInteger();

    JoinTestEngine() {
        this(8);
    }

    JoinTestEngine(int threads) {
        this.pool = Executors.newFixedThreadPool(threads);
    }

    ManualScheduler manualScheduler() {
        return scheduler;
    }

    /** Nodes spawned so far. Used to assert that composition rejected a graph before any actor existed. */
    int spawnCount() {
        return spawned.get();
    }

    @Override
    public String id() {
        return "join-test";
    }

    @Override
    public Set<EngineCapability> capabilities() {
        return Set.of();
    }

    @Override
    public Scheduler scheduler() {
        return scheduler;
    }

    @Override
    public NodeRef spawn(String logicalName, RavenNode node) {
        spawned.incrementAndGet();
        var ref = new NodeRef(logicalName + "-" + UUID.randomUUID());
        nodes.put(ref, node);
        return ref;
    }

    @Override
    public CompletionStage<NodeResult> send(NodeRef target, NodeMessage message) {
        RavenNode node = nodes.get(target);
        if (node == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("unknown node " + target.value()));
        }
        var result = new CompletableFuture<NodeResult>();
        pool.execute(() -> {
            try {
                node.onMessage(message, context(target)).whenComplete((value, error) -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                    } else {
                        result.complete(value);
                    }
                });
            } catch (RuntimeException error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    @Override
    public EngineState state() {
        return state;
    }

    /**
     * A node this engine still holds is running; one it has stopped is gone.
     *
     * <p>Deliberately no richer than that. Like the other hand-written stubs in these tests, this
     * engine exists to make fan-in branches genuinely concurrent, not to model CORE-04 supervision —
     * the lifecycle contract is asserted against the real adapters through
     * {@code ExecutionEngineContract}, and a second plausible-looking implementation here would be a
     * model nothing verifies.</p>
     */
    @Override
    public Optional<NodeStatus> status(NodeRef target) {
        return Optional.ofNullable(nodes.get(target))
                .map(ignored -> new NodeStatus(target, NodeLifecycleState.RUNNING, null, 0));
    }

    @Override
    public CompletionStage<Void> stop(NodeRef target) {
        nodes.remove(target);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Identical to {@link #stop(NodeRef)}: this engine has no accepted-but-unstarted message queue to
     * abandon, so there is nothing cancellation could do that stopping does not already do.
     */
    @Override
    public CompletionStage<Void> cancel(NodeRef target) {
        return stop(target);
    }

    @Override
    public CompletionStage<Void> drain() {
        state = EngineState.DRAINING;
        return CompletableFuture.allOf(List.copyOf(nodes.keySet()).stream()
                .map(this::stop)
                .map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new));
    }

    @Override
    public void close() {
        state = EngineState.CLOSED;
        nodes.clear();
        pool.shutdownNow();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private NodeContext context(NodeRef ref) {
        return new NodeContext() {
            @Override
            public NodeRef self() {
                return ref;
            }

            @Override
            public Scheduler scheduler() {
                return JoinTestEngine.this.scheduler;
            }

            @Override
            public ai.ravenroot.api.execution.Mailbox mailbox() {
                return () -> 0;
            }

            @Override
            public CancellationSignal cancellation() {
                return StubEngineLifecycle.NEVER_CANCELLED;
            }
        };
    }

    /** Records scheduled tasks and fires them on demand. */
    static final class ManualScheduler implements Scheduler {
        private final CopyOnWriteArrayList<Pending> pending = new CopyOnWriteArrayList<>();

        /**
         * Optional block <em>inside</em> {@code schedule}, for the window between a scheduler being
         * asked for a task and the caller receiving the handle it must later cancel. That window is
         * invisible to a scheduler that returns immediately, and it is the whole window in which a
         * timeout can be created that no one is able to cancel.
         */
        private volatile CountDownLatch releaseSchedule;
        private final CountDownLatch enteredSchedule = new CountDownLatch(1);

        /**
         * Optional block <em>inside</em> {@code cancel}, for the other end of the same handoff.
         *
         * <p>Holding a caller inside {@code cancel} is what turns "was the timer cancelled before or
         * after termination completed?" from a race into a state. The thread that arms a timeout
         * inside a termination window is parked here at exactly the instruction between releasing
         * its drain slot and cancelling, so an observation taken while it is parked is ordered by
         * that thread rather than by the clock — and a test that instead sleeps and looks afterwards
         * cannot tell "cancelled first" from "cancelled a moment later", which is precisely how an
         * ordering defect stayed invisible behind a green suite.</p>
         */
        private volatile CountDownLatch releaseCancel;
        private final CountDownLatch enteredCancel = new CountDownLatch(1);

        /**
         * The cancellation mutant: every {@code cancel} reports failure and leaves the task live.
         *
         * <p>It exists so that "no timeout is live after shutdown" can be shown to have teeth. A
         * diagnostic that counts live timeouts is only evidence if the identical read is seen to
         * report a non-zero count when a timeout really is still armed — otherwise "nothing is
         * armed" and "the instrument was reset" produce the same zero, which is exactly how the
         * leak this scheduler now reproduces stayed invisible.</p>
         */
        private volatile boolean refuseCancellation;

        void refuseCancellation() {
            refuseCancellation = true;
        }

        /** Makes the next {@code schedule} call park until {@link #releaseSchedule()}. */
        void blockInsideSchedule() {
            releaseSchedule = new CountDownLatch(1);
        }

        boolean awaitInsideSchedule(long millis) throws InterruptedException {
            return enteredSchedule.await(millis, TimeUnit.MILLISECONDS);
        }

        void releaseSchedule() {
            CountDownLatch latch = releaseSchedule;
            if (latch != null) {
                latch.countDown();
            }
        }

        /** Makes every subsequent {@code cancel} park until {@link #releaseCancel()}. */
        void blockInsideCancel() {
            releaseCancel = new CountDownLatch(1);
        }

        boolean awaitInsideCancel(long millis) throws InterruptedException {
            return enteredCancel.await(millis, TimeUnit.MILLISECONDS);
        }

        void releaseCancel() {
            CountDownLatch latch = releaseCancel;
            if (latch != null) {
                latch.countDown();
            }
        }

        @Override
        public ScheduledTask schedule(Duration delay, Runnable task) {
            CountDownLatch release = releaseSchedule;
            if (release != null) {
                enteredSchedule.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            var entry = new Pending(delay, task);
            pending.add(entry);
            return entry;
        }

        /**
         * The delays this scheduler has been asked for, oldest first, including cancelled and fired
         * ones.
         *
         * <p>Recorded because the budget a deadline is armed with is otherwise invisible: a manual
         * scheduler that only fires on demand makes "re-armed with what was left" and "re-armed with
         * the full timeout" produce identical observable behaviour, and the defect this exists to
         * catch is exactly a resume that resets the budget.</p>
         */
        List<Duration> requestedDelays() {
            return pending.stream().map(entry -> entry.delay).toList();
        }

        /** Runs every task that is neither cancelled nor already fired. Returns how many ran. */
        int fireAll() {
            int fired = 0;
            for (Pending entry : List.copyOf(pending)) {
                if (entry.fire()) {
                    fired++;
                }
            }
            return fired;
        }

        /** Scheduled tasks that have neither fired nor been cancelled. */
        long liveCount() {
            return pending.stream().filter(entry -> !entry.done.get()).count();
        }

        long cancelledCount() {
            return pending.stream().filter(entry -> entry.cancelled.get()).count();
        }

        void clear() {
            pending.clear();
        }

        private final class Pending implements ScheduledTask {
            private final Duration delay;
            private final Runnable task;
            private final AtomicBoolean done = new AtomicBoolean();
            private final AtomicBoolean cancelled = new AtomicBoolean();

            private Pending(Duration delay, Runnable task) {
                this.delay = delay;
                this.task = task;
            }

            @Override
            public boolean cancel() {
                CountDownLatch release = releaseCancel;
                if (release != null) {
                    enteredCancel.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (refuseCancellation) {
                    return false;
                }
                if (done.compareAndSet(false, true)) {
                    cancelled.set(true);
                    return true;
                }
                return false;
            }

            private boolean fire() {
                if (!done.compareAndSet(false, true)) {
                    return false;
                }
                task.run();
                return true;
            }
        }
    }
}
