package ai.ravenroot.extensions.filesystem;

import ai.ravenroot.api.execution.NodeResult;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Shared bounded invocation controls for both behaviors in one package instance. */
final class FilesystemRuntime {
    private static final int MAX_GATES = 4096;
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("ravenroot-filesystem-deadlines").factory());
    final FilesystemProfileResolver profiles;
    final FilesystemAccess access;
    private final DeadlineScheduler deadlineScheduler;
    private final ConcurrentHashMap<String, Gate> gates = new ConcurrentHashMap<>();

    FilesystemRuntime(FilesystemProfileResolver profiles) { this(profiles, new FilesystemAccess()); }
    FilesystemRuntime(FilesystemProfileResolver profiles, FilesystemAccess access) {
        this(profiles, access, FilesystemRuntime::scheduleDeadline);
    }
    FilesystemRuntime(FilesystemProfileResolver profiles, FilesystemAccess access,
                      DeadlineScheduler deadlineScheduler) {
        this.profiles = Objects.requireNonNull(profiles);
        this.access = Objects.requireNonNull(access);
        this.deadlineScheduler = Objects.requireNonNull(deadlineScheduler);
    }

    CompletableFuture<NodeResult> execute(String tenant, FilesystemProfile profile, Duration timeout,
                                          Supplier<NodeResult> operation,
                                          FilesystemAccess.InvocationState state) {
        Gate gate = gate(tenant, profile);
        if (!gate.permits.tryAcquire()) return CompletableFuture.failedFuture(
                FilesystemNodeException.of(FilesystemNodeException.Reason.SATURATED));
        CompletableFuture<NodeResult> answer = new CompletableFuture<>();
        final Thread worker;
        try {
            worker = Thread.ofVirtual().name("ravenroot-filesystem").start(() -> {
                try {
                    NodeResult result = operation.get();
                    state.finish();
                    answer.complete(result);
                } catch (Error fatal) {
                    answer.completeExceptionally(fatal);
                    throw fatal;
                } catch (RuntimeException failure) {
                    answer.completeExceptionally(classify(failure));
                } finally {
                    gate.permits.release();
                }
            });
        } catch (RuntimeException unavailable) {
            gate.permits.release();
            return CompletableFuture.failedFuture(FilesystemNodeException.of(
                    FilesystemNodeException.Reason.TEMPORARY_IO, unavailable));
        }
        CancellableDeadline deadline = deadlineScheduler.schedule(() -> {
                    if (state.timeout()) {
                        worker.interrupt();
                        answer.completeExceptionally(FilesystemNodeException.of(
                                FilesystemNodeException.Reason.TIMEOUT));
                    } else if (state.moving()) {
                        worker.interrupt();
                        answer.completeExceptionally(FilesystemNodeException.of(
                                FilesystemNodeException.Reason.AMBIGUOUS_FINAL_MOVE));
                    }
                }, timeout);
        answer.whenComplete((ignoredResult, ignoredFailure) -> deadline.cancel());
        return answer;
    }

    private static CancellableDeadline scheduleDeadline(Runnable action, Duration delay) {
        ScheduledFuture<?> scheduled = DEADLINES.schedule(action, delay.toNanos(), TimeUnit.NANOSECONDS);
        return () -> scheduled.cancel(false);
    }

    private Gate gate(String tenant, FilesystemProfile profile) {
        String key = tenant + '\0' + profile.name();
        Gate existing = gates.get(key);
        if (existing != null) return existing;
        if (gates.size() >= MAX_GATES) throw FilesystemNodeException.of(FilesystemNodeException.Reason.SATURATED);
        return gates.computeIfAbsent(key, ignored -> new Gate(profile.maxConcurrency()));
    }

    private static Throwable classify(Throwable failure) {
        if (failure instanceof FilesystemNodeException) return failure;
        return FilesystemNodeException.of(FilesystemNodeException.Reason.TEMPORARY_IO, failure);
    }

    private record Gate(Semaphore permits) {
        Gate(int permits) { this(new Semaphore(permits, true)); }
    }

    @FunctionalInterface
    interface DeadlineScheduler {
        CancellableDeadline schedule(Runnable action, Duration delay);
    }

    @FunctionalInterface
    interface CancellableDeadline {
        void cancel();
    }
}
