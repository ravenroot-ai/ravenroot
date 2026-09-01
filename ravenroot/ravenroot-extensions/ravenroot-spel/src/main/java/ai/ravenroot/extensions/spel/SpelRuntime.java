package ai.ravenroot.extensions.spel;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

final class SpelRuntime {
    private static final Semaphore GLOBAL = new Semaphore(SpelBounds.GLOBAL_CONCURRENCY, true);
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(
            SpelBounds.WORKER_THREADS, SpelBounds.WORKER_THREADS, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(SpelBounds.QUEUE_CAPACITY), daemonFactory("ravenroot-spel-worker-"),
            new ThreadPoolExecutor.AbortPolicy());
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();

    private SpelRuntime() {
    }

    static CompletableFuture<Object> evaluate(Semaphore nodeSlots, Supplier<Object> evaluation) {
        if (!GLOBAL.tryAcquire()) {
            return CompletableFuture.failedFuture(
                    new SpelNodeException(SpelNodeException.Code.CAPACITY_UNAVAILABLE));
        }
        if (!nodeSlots.tryAcquire()) {
            GLOBAL.release();
            return CompletableFuture.failedFuture(
                    new SpelNodeException(SpelNodeException.Code.CAPACITY_UNAVAILABLE));
        }

        Invocation invocation = new Invocation(nodeSlots, evaluation);
        try {
            invocation.worker = WORKERS.submit(invocation::run);
        } catch (RejectedExecutionException rejected) {
            invocation.release();
            return CompletableFuture.failedFuture(
                    new SpelNodeException(SpelNodeException.Code.CAPACITY_UNAVAILABLE));
        }
        invocation.timer = DEADLINES.schedule(invocation::timeout,
                SpelBounds.DEADLINE.toNanos(), TimeUnit.NANOSECONDS);
        if (invocation.output.isDone()) {
            invocation.timer.cancel(false);
        }
        return invocation.output;
    }

    private static ScheduledThreadPoolExecutor deadlines() {
        var executor = new ScheduledThreadPoolExecutor(1, daemonFactory("ravenroot-spel-deadline-"));
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static ThreadFactory daemonFactory(String prefix) {
        return new ThreadFactory() {
            private int sequence;

            @Override
            public synchronized Thread newThread(Runnable task) {
                Thread thread = new Thread(task, prefix + ++sequence);
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    private static final class Invocation {
        private final Semaphore nodeSlots;
        private final Supplier<Object> evaluation;
        private final CompletableFuture<Object> output = new CompletableFuture<>();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();
        private final long deadline = System.nanoTime() + SpelBounds.DEADLINE.toNanos();
        private volatile Future<?> worker;
        private volatile ScheduledFuture<?> timer;

        private Invocation(Semaphore nodeSlots, Supplier<Object> evaluation) {
            this.nodeSlots = nodeSlots;
            this.evaluation = evaluation;
        }

        private void run() {
            started.set(true);
            try {
                if (output.isDone()) {
                    return;
                }
                if (System.nanoTime() - deadline >= 0) {
                    output.completeExceptionally(
                            new SpelNodeException(SpelNodeException.Code.DEADLINE_EXCEEDED));
                    return;
                }
                Object value = evaluation.get();
                if (System.nanoTime() - deadline >= 0) {
                    output.completeExceptionally(
                            new SpelNodeException(SpelNodeException.Code.DEADLINE_EXCEEDED));
                } else {
                    output.complete(value);
                }
            } catch (SpelNodeException rejected) {
                output.completeExceptionally(rejected);
            } catch (RuntimeException rejected) {
                output.completeExceptionally(
                        new SpelNodeException(SpelNodeException.Code.EVALUATION_FAILED));
            } finally {
                ScheduledFuture<?> scheduled = timer;
                if (scheduled != null) {
                    scheduled.cancel(false);
                }
                release();
            }
        }

        private void timeout() {
            if (!output.completeExceptionally(
                    new SpelNodeException(SpelNodeException.Code.DEADLINE_EXCEEDED))) {
                return;
            }
            Future<?> running = worker;
            if (running != null && running.cancel(true) && !started.get()) {
                release();
            }
        }

        private void release() {
            if (released.compareAndSet(false, true)) {
                nodeSlots.release();
                GLOBAL.release();
            }
        }
    }
}
