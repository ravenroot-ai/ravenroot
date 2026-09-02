package ai.ravenroot.extensions.ocr;

import ai.ravenroot.api.execution.NodeResult;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Fail-fast admission and cancellable virtual-thread execution shared across OCR node instances. */
final class OcrRuntimeControls {
    static final OcrRuntimeControls PRODUCTION = new OcrRuntimeControls(OcrProfile.ABSOLUTE_MAX_CONCURRENCY);

    private final Gate global;
    private final ConcurrentHashMap<Key, Gate> profileGates = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("ravenroot-ocr-invocation-", 0).factory());

    OcrRuntimeControls(int globalLimit) { this.global = new Gate(globalLimit); }

    Admission acquire(String tenantId, String profile, int limit) {
        Gate scoped = profileGates.computeIfAbsent(new Key(tenantId, profile), ignored -> new Gate());
        if (!scoped.tryAcquire(limit)) return Admission.refused();
        if (!global.tryAcquire(global.limit)) {
            scoped.release();
            return Admission.refused();
        }
        return new Admission(global, scoped);
    }

    CompletableFuture<NodeResult> submit(Admission admission, Callable<NodeResult> action) {
        Objects.requireNonNull(admission, "admission");
        if (!admission.acquired()) throw new IllegalArgumentException("admission was refused");
        var future = new TaskFuture();
        try {
            executor.execute(() -> {
                Thread current = Thread.currentThread();
                future.runner.set(current);
                NodeResult result = null;
                Throwable failure = null;
                boolean invoked = false;
                try {
                    if (!future.isCancelled()) {
                        invoked = true;
                        result = action.call();
                    }
                } catch (Throwable caught) {
                    failure = caught;
                } finally {
                    future.runner.compareAndSet(current, null);
                    admission.release();
                }
                if (invoked && !future.isCancelled()) {
                    if (failure == null) future.complete(result);
                    else future.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException rejected) {
            admission.release();
            throw rejected;
        }
        return future;
    }

    static final class Admission {
        private final Gate global;
        private final Gate scoped;
        private final AtomicBoolean released = new AtomicBoolean();

        private Admission(Gate global, Gate scoped) { this.global = global; this.scoped = scoped; }
        static Admission refused() { return new Admission(null, null); }
        boolean acquired() { return global != null; }
        void release() {
            if (!acquired() || !released.compareAndSet(false, true)) return;
            scoped.release();
            global.release();
        }
    }

    private static final class Gate {
        private final int limit;
        private final AtomicInteger inUse = new AtomicInteger();

        private Gate() { this(Integer.MAX_VALUE); }
        private Gate(int limit) { this.limit = limit; }
        boolean tryAcquire(int requestedLimit) {
            int effective = Math.min(limit, requestedLimit);
            for (;;) {
                int current = inUse.get();
                if (current >= effective) return false;
                if (inUse.compareAndSet(current, current + 1)) return true;
            }
        }
        void release() {
            int remaining = inUse.decrementAndGet();
            if (remaining < 0) throw new IllegalStateException("OCR admission released more than once");
        }
    }

    private static final class TaskFuture extends CompletableFuture<NodeResult> {
        private final AtomicReference<Thread> runner = new AtomicReference<>();
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            Thread active = runner.get();
            if (cancelled && mayInterruptIfRunning && active != null) active.interrupt();
            return cancelled;
        }
    }

    private record Key(String tenantId, String profile) {
        private Key { Objects.requireNonNull(tenantId); Objects.requireNonNull(profile); }
    }
}
