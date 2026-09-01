package ai.ravenroot.extensions.jdbc;

import ai.ravenroot.api.node.service.OutboundCall;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class JdbcRuntime {
    static final int CLEANUP_LANES_PER_INVOCATION = 4;
    static final int GLOBAL_CLEANUP_LANES = 128;
    private static final JdbcRuntime PRODUCTION = new JdbcRuntime(System::nanoTime);
    private final java.util.function.LongSupplier ticker;
    private final Semaphore global;
    private final Semaphore cleanupLanes;
    private final ConcurrentHashMap<String, Gate> gates = new ConcurrentHashMap<>();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("ravenroot-jdbc-deadline-", 0).factory());

    JdbcRuntime(java.util.function.LongSupplier ticker) {
        this(ticker, 32, GLOBAL_CLEANUP_LANES);
    }

    JdbcRuntime(java.util.function.LongSupplier ticker, int invocationLimit, int cleanupLaneLimit) {
        this.ticker = java.util.Objects.requireNonNull(ticker);
        if (invocationLimit < 1 || cleanupLaneLimit < CLEANUP_LANES_PER_INVOCATION) {
            throw new IllegalArgumentException("invalid JDBC runtime limits");
        }
        global = new Semaphore(invocationLimit, true);
        cleanupLanes = new Semaphore(cleanupLaneLimit, true);
    }
    static JdbcRuntime production() { return PRODUCTION; }

    CompletableFuture<Object> submit(String key, int limit, Duration deadline, Work work) {
        Gate gate = gates.compute(key, (ignored, current) -> current == null ? new Gate(limit) : current.retain(limit));
        boolean globalHeld = global.tryAcquire();
        boolean gateHeld = globalHeld && gate.slots.tryAcquire();
        boolean cleanupHeld = gateHeld && cleanupLanes.tryAcquire(CLEANUP_LANES_PER_INVOCATION);
        if (!cleanupHeld) {
            if (gateHeld) gate.slots.release();
            if (globalHeld) global.release();
            releaseGate(key, gate);
            return CompletableFuture.failedFuture(new JdbcFailure(JdbcFailure.Code.ADMISSION_REFUSED));
        }
        Invocation invocation = new Invocation(key, gate, deadline, work,
                new CleanupReservation(cleanupLanes, CLEANUP_LANES_PER_INVOCATION));
        invocation.timer = deadlines.schedule(invocation::timeout, deadline.toNanos(), TimeUnit.NANOSECONDS);
        invocation.worker = Thread.ofVirtual().name("ravenroot-jdbc-", 0).start(invocation::run);
        invocation.output.whenComplete((ignored, failure) -> { if (invocation.output.isCancelled()) invocation.cancel(false); });
        return invocation.output;
    }

    private void releaseGate(String key, Gate gate) {
        gates.computeIfPresent(key, (ignored, current) -> current == gate && current.releaseReference() ? null : current);
    }

    interface Work { Object run(Control control) throws Exception; }

    final class Invocation {
        final String key; final Gate gate; final Work work; final CompletableFuture<Object> output;
        final long deadline; final Control control; final AtomicBoolean released = new AtomicBoolean();
        volatile Thread worker; volatile Future<?> timer;
        Invocation(String key, Gate gate, Duration duration, Work work, CleanupReservation cleanup) {
            this.key = key; this.gate = gate; this.work = work;
            this.deadline = ticker.getAsLong() + duration.toNanos();
            this.control = new Control(cleanup);
            this.output = new ContractFuture(this);
        }
        void run() {
            try {
                if (expired()) throw cancellationFailure(true);
                Object value = work.run(control);
                if (expired()) throw cancellationFailure(true);
                control.finishWork();
                output.complete(value);
            } catch (JdbcFailure failure) { output.completeExceptionally(control.resolve(failure)); }
            catch (Throwable failure) {
                output.completeExceptionally(control.resolve(new JdbcFailure(JdbcFailure.Code.EXECUTION_FAILED)));
            }
            finally {
                Future<?> scheduled = timer;
                if (scheduled != null) scheduled.cancel(false);
                control.workerSettled();
                release();
            }
        }
        void timeout() { cancel(true); }
        boolean cancel(boolean deadline) {
            if (output.isDone()) return false;
            CancellationDecision decision = control.requestCancellation(deadline);
            if (decision.finished()) return false;
            boolean won = output.completeExceptionally(new JdbcFailure(decision.code()));
            if (won) {
                Thread running = worker;
                if (running != null) running.interrupt();
            }
            return won;
        }
        boolean expired() { return ticker.getAsLong() - deadline >= 0; }
        JdbcFailure cancellationFailure(boolean deadline) {
            CancellationDecision decision = control.requestCancellation(deadline);
            return new JdbcFailure(decision.code());
        }
        void release() {
            if (released.compareAndSet(false, true)) { gate.slots.release(); global.release(); releaseGate(key, gate); }
        }
    }

    private static final class ContractFuture extends CompletableFuture<Object> {
        private final Invocation owner;

        ContractFuture(Invocation owner) { this.owner = owner; }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            return owner.cancel(false);
        }
    }

    static final class Control {
        private static final int ACTIVE = 0;
        private static final int CANCELLED = 1;
        private static final int TIMED_OUT = 2;
        private static final int COMMITTING = 3;
        private static final int COMMITTED = 4;
        private static final int FINISHED = 5;
        private static final int AMBIGUOUS = 6;

        volatile Connection connection; volatile Statement statement; volatile OutboundCall<?> credentialCall;
        volatile ClassLoader driverContext;
        private final CleanupReservation cleanup;
        private final AtomicInteger phase = new AtomicInteger(ACTIVE);
        final AtomicBoolean cancellationStarted = new AtomicBoolean();
        final AtomicBoolean credentialCancellationRequested = new AtomicBoolean();
        final AtomicBoolean statementCancellationRequested = new AtomicBoolean();
        final AtomicBoolean connectionCleanupRequested = new AtomicBoolean();

        Control(CleanupReservation cleanup) { this.cleanup = cleanup; }

        void connection(Connection value) {
            connection = value;
            if (cancellationStarted.get()) cleanupConnection(value);
        }
        void statement(Statement value) {
            statement = value;
            if (cancellationStarted.get()) cancelStatement(value);
        }
        void credential(OutboundCall<?> value) {
            credentialCall = value;
            if (cancellationStarted.get()) cancelCredential(value);
        }
        void driverContext(ClassLoader value) {
            driverContext = java.util.Objects.requireNonNull(value);
        }

        CancellationDecision requestCancellation(boolean deadline) {
            int cancellationPhase = deadline ? TIMED_OUT : CANCELLED;
            int resolved;
            for (;;) {
                int current = phase.get();
                if (current == ACTIVE) {
                    if (!phase.compareAndSet(ACTIVE, cancellationPhase)) continue;
                    resolved = cancellationPhase;
                    break;
                }
                if (current == COMMITTING || current == COMMITTED) {
                    if (!phase.compareAndSet(current, AMBIGUOUS)) continue;
                    resolved = AMBIGUOUS;
                    break;
                }
                resolved = current;
                break;
            }
            if (resolved == FINISHED) return CancellationDecision.finishedWork();
            cancelResources();
            if (resolved == AMBIGUOUS) {
                return new CancellationDecision(false, JdbcFailure.Code.AMBIGUOUS_COMMIT);
            }
            return new CancellationDecision(false, resolved == TIMED_OUT
                    ? JdbcFailure.Code.DEADLINE_EXCEEDED : JdbcFailure.Code.CANCELLED);
        }

        void beginCommit() {
            if (phase.compareAndSet(ACTIVE, COMMITTING)) return;
            int current = phase.get();
            if (current == TIMED_OUT) throw new JdbcFailure(JdbcFailure.Code.DEADLINE_EXCEEDED);
            if (current == CANCELLED) throw new JdbcFailure(JdbcFailure.Code.CANCELLED);
            throw new JdbcFailure(JdbcFailure.Code.EXECUTION_FAILED);
        }

        void commitCompleted() {
            if (phase.compareAndSet(COMMITTING, COMMITTED) || phase.get() == AMBIGUOUS) return;
            throw new JdbcFailure(JdbcFailure.Code.AMBIGUOUS_COMMIT);
        }

        boolean commitStarted() {
            int current = phase.get();
            return current == COMMITTING || current == COMMITTED || current == AMBIGUOUS;
        }

        JdbcFailure resolve(JdbcFailure proposed) {
            return switch (phase.get()) {
                case CANCELLED -> new JdbcFailure(JdbcFailure.Code.CANCELLED);
                case TIMED_OUT -> new JdbcFailure(JdbcFailure.Code.DEADLINE_EXCEEDED);
                case COMMITTING, COMMITTED, AMBIGUOUS -> new JdbcFailure(JdbcFailure.Code.AMBIGUOUS_COMMIT);
                default -> proposed;
            };
        }

        void finishWork() {
            for (;;) {
                int current = phase.get();
                if (current == ACTIVE) {
                    if (phase.compareAndSet(ACTIVE, FINISHED)) return;
                    continue;
                }
                if (current == COMMITTED) {
                    if (phase.compareAndSet(COMMITTED, FINISHED)) return;
                    continue;
                }
                if (current == FINISHED) return;
                if (current == TIMED_OUT) throw new JdbcFailure(JdbcFailure.Code.DEADLINE_EXCEEDED);
                if (current == CANCELLED) throw new JdbcFailure(JdbcFailure.Code.CANCELLED);
                throw new JdbcFailure(JdbcFailure.Code.AMBIGUOUS_COMMIT);
            }
        }

        void workerSettled() { cleanup.releaseUnused(); }

        private void cancelResources() {
            if (!cancellationStarted.compareAndSet(false, true)) return;
            OutboundCall<?> resolving = credentialCall;
            if (resolving != null) cancelCredential(resolving);
            Statement active = statement;
            if (active != null) cancelStatement(active);
            Connection open = connection;
            if (open != null) cleanupConnection(open);
        }
        private void cancelCredential(OutboundCall<?> resolving) {
            if (credentialCancellationRequested.compareAndSet(false, true)) {
                cleanup.launch("ravenroot-jdbc-credential-cancel-", resolving::cancel);
            }
        }
        private void cancelStatement(Statement active) {
            if (statementCancellationRequested.compareAndSet(false, true)) {
                cleanup.launch("ravenroot-jdbc-statement-cancel-", inDriverContext(() -> cancel(active)));
            }
        }
        private void cleanupConnection(Connection open) {
            if (connectionCleanupRequested.compareAndSet(false, true)) cleanup(open);
        }
        private static void cancel(Statement active) { try { active.cancel(); } catch (Exception ignored) { } }
        private void cleanup(Connection open) {
            // JDBC callbacks are untrusted and may block forever. Abort and close use independent
            // reserved lanes. Each global lane remains accounted until the callback actually exits.
            cleanup.launch("ravenroot-jdbc-connection-abort-",
                    inDriverContext(() -> { try { open.abort(Runnable::run); } catch (Exception ignored) { } }));
            cleanup.launch("ravenroot-jdbc-connection-close-",
                    inDriverContext(() -> { try { open.close(); } catch (Exception ignored) { } }));
        }
        private Runnable inDriverContext(Runnable callback) {
            ClassLoader context = driverContext;
            // Direct package tests may exercise cleanup without publishing JDBC resources through
            // JdbcExecutor. Production connection/statement publication always sets this first.
            if (context == null) return callback;
            return () -> {
                try {
                    JdbcDriverLoader.inContext(context, () -> { callback.run(); return null; });
                } catch (Exception ignored) { }
            };
        }
    }

    private record CancellationDecision(boolean finished, JdbcFailure.Code code) {
        static CancellationDecision finishedWork() {
            return new CancellationDecision(true, JdbcFailure.Code.EXECUTION_FAILED);
        }
    }

    private static final class CleanupReservation {
        private final Semaphore global;
        private final AtomicInteger unused;

        CleanupReservation(Semaphore global, int lanes) {
            this.global = global;
            this.unused = new AtomicInteger(lanes);
        }

        void launch(String name, Runnable callback) {
            for (;;) {
                int available = unused.get();
                if (available == 0) return;
                if (!unused.compareAndSet(available, available - 1)) continue;
                try {
                    Thread.ofVirtual().name(name, 0).start(() -> {
                        try { callback.run(); }
                        finally { global.release(); }
                    });
                } catch (RuntimeException | Error unavailable) {
                    global.release();
                }
                return;
            }
        }

        void releaseUnused() {
            int count = unused.getAndSet(0);
            if (count > 0) global.release(count);
        }
    }

    private static final class Gate {
        final int limit; final Semaphore slots; int references = 1;
        Gate(int limit) { this.limit = limit; slots = new Semaphore(limit, true); }
        synchronized Gate retain(int expected) { if (limit != expected) throw new JdbcFailure(JdbcFailure.Code.PROFILE_UNAVAILABLE); references++; return this; }
        synchronized boolean releaseReference() { return --references == 0; }
    }
}
