package ai.ravenroot.extensions.gitworkspace;

import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.service.OutboundCall;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded invocation, deadline, cancellation, and subprocess ownership runtime. */
final class GitWorkspaceRuntime {
    private static final GitWorkspaceRuntime PRODUCTION = new GitWorkspaceRuntime(System::nanoTime);
    private final java.util.function.LongSupplier ticker;
    private final Semaphore global = new Semaphore(64, true);
    private final ConcurrentHashMap<String, Gate> gates = new ConcurrentHashMap<>();
    private final ScheduledExecutorService deadlines = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("ravenroot-git-workspace-deadline-", 0).factory());

    GitWorkspaceRuntime(java.util.function.LongSupplier ticker) {
        this.ticker = java.util.Objects.requireNonNull(ticker);
    }

    static GitWorkspaceRuntime production() {
        return PRODUCTION;
    }

    CompletableFuture<NodeResult> submit(GitWorkspaceProfile profile, CancellationSignal cancellation, Work work) {
        String key = profile.tenant() + '\0' + profile.name();
        Gate gate = gates.compute(key, (ignored, current) -> current == null
                ? new Gate(profile.maxConcurrency()) : current.retain(profile.maxConcurrency()));
        boolean globalHeld = global.tryAcquire();
        boolean localHeld = globalHeld && gate.permits.tryAcquire();
        if (!localHeld) {
            if (globalHeld) global.release();
            releaseGate(key, gate);
            return CompletableFuture.failedFuture(GitWorkspaceFailure.of(GitWorkspaceFailure.Code.SATURATED));
        }
        Invocation invocation = new Invocation(key, gate, profile.deadline(), work);
        try {
            cancellation.onCancel(() -> invocation.cancel(false));
            invocation.timer = deadlines.schedule(() -> invocation.cancel(true), profile.deadline().toNanos(),
                    TimeUnit.NANOSECONDS);
            invocation.worker = Thread.ofVirtual().name("ravenroot-git-workspace").start(invocation::run);
            if (cancellation.cancelled()) invocation.cancel(false);
            return invocation.output;
        } catch (RuntimeException unavailable) {
            invocation.release();
            return CompletableFuture.failedFuture(GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_UNAVAILABLE));
        }
    }

    private void releaseGate(String key, Gate gate) {
        gates.computeIfPresent(key, (ignored, current) -> current == gate && current.release() ? null : current);
    }

    @FunctionalInterface
    interface Work {
        NodeResult run(Control control);
    }

    final class Invocation {
        final String key;
        final Gate gate;
        final Work work;
        final long deadlineNanos;
        final Control control;
        final CompletableFuture<NodeResult> output;
        final AtomicBoolean released = new AtomicBoolean();
        volatile Thread worker;
        volatile Future<?> timer;

        Invocation(String key, Gate gate, Duration deadline, Work work) {
            this.key = key;
            this.gate = gate;
            this.work = work;
            this.deadlineNanos = ticker.getAsLong() + deadline.toNanos();
            this.control = new Control(deadlineNanos, ticker);
            this.output = new ContractFuture(this);
        }

        void run() {
            NodeResult result = null;
            GitWorkspaceFailure terminalFailure = null;
            try {
                control.check();
                result = work.run(control);
                control.check();
            } catch (GitWorkspaceFailure failure) {
                terminalFailure = control.resolve(failure);
            } catch (RuntimeException failure) {
                terminalFailure = control.resolve(GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_FAILED));
            } finally {
                Future<?> scheduled = timer;
                if (scheduled != null) scheduled.cancel(false);
                control.cancelCredential();
                if (!control.reapOwned()) {
                    terminalFailure = GitWorkspaceFailure.of(GitWorkspaceFailure.Code.GIT_FAILED);
                }
                release();
            }
            if (terminalFailure == null) output.complete(result);
            else output.completeExceptionally(terminalFailure);
        }

        boolean cancel(boolean timeout) {
            boolean changed = control.cancel(timeout);
            if (changed) {
                Thread running = worker;
                if (running != null) running.interrupt();
            }
            return changed;
        }

        void release() {
            if (released.compareAndSet(false, true)) {
                gate.permits.release();
                global.release();
                releaseGate(key, gate);
            }
        }
    }

    private static final class ContractFuture extends CompletableFuture<NodeResult> {
        private final Invocation owner;
        ContractFuture(Invocation owner) { this.owner = owner; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) { return owner.cancel(false); }
    }

    static final class Control {
        private enum Terminal { ACTIVE, CANCELLED, TIMED_OUT }
        private final long deadlineNanos;
        private final java.util.function.LongSupplier ticker;
        private final AtomicReference<Terminal> terminal = new AtomicReference<>(Terminal.ACTIVE);
        private final java.util.Set<ProcessHandle> owned = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final AtomicReference<OutboundCall<?>> credential = new AtomicReference<>();

        Control(long deadlineNanos, java.util.function.LongSupplier ticker) {
            this.deadlineNanos = deadlineNanos;
            this.ticker = ticker;
        }

        void check() {
            if (ticker.getAsLong() - deadlineNanos >= 0) cancel(true);
            Terminal current = terminal.get();
            if (current == Terminal.CANCELLED) throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.CANCELLED);
            if (current == Terminal.TIMED_OUT) {
                throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.DEADLINE_EXCEEDED);
            }
        }

        long remainingMillis() {
            check();
            long remaining = deadlineNanos - ticker.getAsLong();
            return Math.max(1L, Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(remaining)));
        }

        boolean cancel(boolean timeout) {
            Terminal next = timeout ? Terminal.TIMED_OUT : Terminal.CANCELLED;
            if (!terminal.compareAndSet(Terminal.ACTIVE, next)) return false;
            cancelCredential();
            terminateProcess();
            return true;
        }

        void own(Process value) {
            remember(value.toHandle());
            if (terminal.get() != Terminal.ACTIVE) terminateProcess();
        }

        void settled(Process value) {
            remember(value.toHandle());
        }

        void credential(OutboundCall<?> value) {
            credential.set(value);
            if (terminal.get() != Terminal.ACTIVE) cancelCredential();
        }

        void cancelCredential() {
            OutboundCall<?> call = credential.getAndSet(null);
            if (call != null) {
                try { call.cancel(); } catch (RuntimeException ignored) { }
            }
        }

        void terminateProcess() {
            reapOwned();
        }

        GitWorkspaceFailure resolve(GitWorkspaceFailure proposed) {
            return switch (terminal.get()) {
                case CANCELLED -> GitWorkspaceFailure.of(GitWorkspaceFailure.Code.CANCELLED);
                case TIMED_OUT -> GitWorkspaceFailure.of(GitWorkspaceFailure.Code.DEADLINE_EXCEEDED);
                case ACTIVE -> proposed;
            };
        }

        boolean reapOwned() {
            boolean restoreInterrupt = Thread.interrupted();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean force = false;
            try {
                while (System.nanoTime() - until < 0) {
                    owned.stream().filter(ProcessHandle::isAlive).forEach(this::remember);
                    java.util.List<ProcessHandle> live = owned.stream().filter(ProcessHandle::isAlive).toList();
                    if (live.isEmpty()) return true;
                    for (ProcessHandle handle : live.reversed()) {
                        if (force) handle.destroyForcibly(); else handle.destroy();
                    }
                    force = true;
                    try { Thread.sleep(10); }
                    catch (InterruptedException interrupted) { restoreInterrupt = true; }
                }
                owned.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                long forcedUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (System.nanoTime() - forcedUntil < 0) {
                    owned.stream().filter(ProcessHandle::isAlive).forEach(this::remember);
                    if (owned.stream().noneMatch(ProcessHandle::isAlive)) return true;
                    try { Thread.sleep(10); }
                    catch (InterruptedException interrupted) { restoreInterrupt = true; }
                }
                return owned.stream().noneMatch(ProcessHandle::isAlive);
            } finally {
                if (restoreInterrupt) Thread.currentThread().interrupt();
            }
        }

        private void remember(ProcessHandle root) {
            owned.add(root);
            root.descendants().forEach(owned::add);
        }
    }

    private static final class Gate {
        final int limit;
        final Semaphore permits;
        int references = 1;
        Gate(int limit) { this.limit = limit; permits = new Semaphore(limit, true); }
        synchronized Gate retain(int expected) {
            if (limit != expected) throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.PROFILE_UNAVAILABLE);
            references++;
            return this;
        }
        synchronized boolean release() { return --references == 0; }
    }
}
