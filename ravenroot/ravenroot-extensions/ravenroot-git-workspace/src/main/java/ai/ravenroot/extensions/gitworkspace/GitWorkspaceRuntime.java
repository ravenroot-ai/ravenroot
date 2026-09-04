package ai.ravenroot.extensions.gitworkspace;

import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.service.OutboundCall;

import java.time.Duration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        private static final String GROUP_SUPERVISOR = """
                control=$1
                shift
                leader=
                cleanup() {
                  trap - EXIT HUP INT TERM
                  if [ -n "$leader" ]; then kill -KILL -"$leader" 2>/dev/null || :; fi
                }
                trap cleanup EXIT HUP INT TERM
                set -m || exit 125
                exec 3>&1 4>&2
                exec 1>/dev/null 2>/dev/null
                while :; do :; done &
                leader=$!
                kill -0 -"$leader" 2>/dev/null || exit 125
                kill -KILL -"$leader" 2>/dev/null || exit 125
                wait "$leader" 2>/dev/null || :
                leader=
                "$@" 1>&3 2>&4 3>&- 4>&- &
                leader=$!
                printf '%s\\n' "$leader" > "$control" || exit 125
                wait "$leader"
                result=$?
                exit "$result"
                """;
        private static final String GROUP_SIGNAL = "kill -\"$1\" -\"$2\" 2>/dev/null";
        private enum Terminal { ACTIVE, CANCELLED, TIMED_OUT }
        private final long deadlineNanos;
        private final java.util.function.LongSupplier ticker;
        private final AtomicReference<Terminal> terminal = new AtomicReference<>(Terminal.ACTIVE);
        private final Set<ProcessHandle> owned = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final Map<Long, Boundary> boundaries = new java.util.concurrent.ConcurrentHashMap<>();
        private final Object boundaryRegistration = new Object();
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

        Process startGrouped(ProcessBuilder builder, Path shell, Object shellIdentity, Path privateHome)
                throws IOException {
            synchronized (boundaryRegistration) {
                check();
                validateExecutable(shell, shellIdentity);
                Path groupFile = Files.createTempFile(privateHome, ".process-group-", ".pid");
                Object groupFileIdentity = fileKey(groupFile);
                try {
                    Files.setPosixFilePermissions(groupFile, Set.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE));
                    List<String> child = List.copyOf(builder.command());
                    List<String> supervised = new java.util.ArrayList<>(child.size() + 5);
                    supervised.add(shell.toString());
                    supervised.add("-c");
                    supervised.add(GROUP_SUPERVISOR);
                    supervised.add("ravenroot-git-process-group");
                    supervised.add(groupFile.toString());
                    supervised.addAll(child);
                    builder.command(supervised);
                    Process process = builder.start();
                    owned.add(process.toHandle());
                    long group;
                    try {
                        group = awaitGroup(process, groupFile, groupFileIdentity);
                    } catch (IOException | RuntimeException registrationFailed) {
                        process.destroy();
                        try {
                            if (!process.waitFor(200, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                            process.waitFor(1, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            process.destroyForcibly();
                            Thread.currentThread().interrupt();
                        }
                        owned.remove(process.toHandle());
                        throw registrationFailed;
                    }
                    Boundary boundary = new Boundary(process.toHandle(), group, groupFile, groupFileIdentity,
                            shell, shellIdentity, privateHome);
                    boundaries.put(process.pid(), boundary);
                    if (terminal.get() != Terminal.ACTIVE) {
                        reapOwned();
                        check();
                    }
                    return process;
                } catch (IOException | RuntimeException failed) {
                    try { Files.deleteIfExists(groupFile); } catch (IOException ignored) { }
                    throw failed;
                }
            }
        }

        boolean settled(Process value) {
            synchronized (boundaryRegistration) {
                Boundary boundary = boundaries.get(value.pid());
                if (boundary == null) return !value.isAlive();
                boolean gone = settleBoundary(boundary);
                boolean removed = gone && removeControl(boundary);
                if (removed) {
                    boundaries.remove(value.pid(), boundary);
                    owned.remove(value.toHandle());
                }
                return removed && !value.isAlive();
            }
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
            synchronized (boundaryRegistration) {
                boolean complete = true;
                for (Boundary boundary : List.copyOf(boundaries.values())) {
                    boolean gone = settleBoundary(boundary);
                    boolean removed = gone && removeControl(boundary);
                    complete &= removed;
                    if (removed) {
                        boundaries.remove(boundary.root().pid(), boundary);
                        owned.remove(boundary.root());
                    }
                }
                for (ProcessHandle handle : List.copyOf(owned)) {
                    if (handle.isAlive()) handle.destroy();
                }
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (owned.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() - until < 0) {
                    owned.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                    pause();
                }
                return complete && boundaries.isEmpty() && owned.stream().noneMatch(ProcessHandle::isAlive);
            }
        }

        private long awaitGroup(Process process, Path file, Object identity) throws IOException {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() - until < 0) {
                validateFile(file, identity);
                String raw = Files.readString(file, StandardCharsets.US_ASCII);
                if (raw.matches("[0-9]+\\n")) {
                    long group = Long.parseLong(raw.trim());
                    if (group <= 1) throw new IOException("invalid process group");
                    return group;
                }
                if (!raw.isEmpty() && raw.endsWith("\n")) throw new IOException("invalid process group");
                if (!process.isAlive()) throw new IOException("process group supervisor exited");
                pause();
            }
            throw new IOException("process group registration timed out");
        }

        private boolean settleBoundary(Boundary boundary) {
            boolean restoreInterrupt = Thread.interrupted();
            try {
                if (!signal(boundary, "0")) {
                    if (boundary.root().isAlive()) boundary.root().destroyForcibly();
                    return true;
                }
                signal(boundary, "TERM");
                long gentle = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
                while (signal(boundary, "0") && System.nanoTime() - gentle < 0) pause();
                signal(boundary, "KILL");
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (signal(boundary, "0") && System.nanoTime() - until < 0) pause();
                if (boundary.root().isAlive()) boundary.root().destroyForcibly();
                return !signal(boundary, "0");
            } finally {
                if (restoreInterrupt) Thread.currentThread().interrupt();
            }
        }

        private boolean signal(Boundary boundary, String signal) {
            try {
                validateExecutable(boundary.shell(), boundary.shellIdentity());
                ProcessBuilder builder = new ProcessBuilder(boundary.shell().toString(), "-c", GROUP_SIGNAL,
                        "ravenroot-git-process-group-signal", signal, Long.toString(boundary.group()));
                builder.directory(boundary.privateHome().toFile());
                builder.environment().clear();
                Process process = builder.start();
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(1, TimeUnit.SECONDS);
                    return true;
                }
                return process.exitValue() == 0;
            } catch (IOException | InterruptedException | RuntimeException failed) {
                if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
                return true;
            }
        }

        private static boolean removeControl(Boundary boundary) {
            try {
                validateFile(boundary.groupFile(), boundary.groupFileIdentity());
                Files.delete(boundary.groupFile());
                return true;
            } catch (IOException replaced) {
                return false;
            }
        }

        private static void validateExecutable(Path path, Object identity) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || Files.isSymbolicLink(path)
                    || !java.util.Objects.equals(identity, attributes.fileKey())) throw new IOException();
        }

        private static void validateFile(Path path, Object identity) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || Files.isSymbolicLink(path)
                    || !java.util.Objects.equals(identity, attributes.fileKey()) || attributes.size() > 32) {
                throw new IOException();
            }
        }

        private static Object fileKey(Path path) throws IOException {
            Object value = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).fileKey();
            if (value == null) throw new IOException();
            return value;
        }

        private static void pause() {
            try { Thread.sleep(10); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }

        private record Boundary(ProcessHandle root, long group, Path groupFile, Object groupFileIdentity,
                                Path shell, Object shellIdentity, Path privateHome) { }
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
