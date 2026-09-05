package ai.ravenroot.server.readiness;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Computes a {@link ReadinessReport} on demand. Concurrent requests share at most
 * one outstanding store probe; its result is discarded on completion, so this is bounded
 * backpressure rather than cached or background-refreshed readiness state.
 *
 * <h2>Priority, checked in {@link #evaluate()}</h2>
 * <ol>
 *   <li>{@code engineState.get()} not {@code "RUNNING"} &rarr; {@link ReadinessState#DRAINING}</li>
 *   <li>{@code recoveryClassified.getAsBoolean()} is {@code false} &rarr;
 *       {@link ReadinessState#RECOVERING}</li>
 *   <li>{@link #storeCheck}'s active probe throws or exceeds {@link ReadinessConfiguration#storeCheckTimeout()}
 *       &rarr; {@link ReadinessState#STORE_DEGRADED}</li>
 *   <li>otherwise &rarr; {@link ReadinessState#READY}</li>
 * </ol>
 * <p>{@code dependencies} is evaluated and attached regardless of which of the three states above
 * is reached; it never changes {@code ready()} (see {@link DependencyStatus}).</p>
 *
 * <h2>This gate can report unready, provably</h2>
 * <p>{@code engineState} and {@code storeCheck} are both externally supplied functions, not fields
 * this class computes internally from a fixed source -- {@code ReadinessGateTest} constructs a gate
 * whose engine-state supplier and whose store check are both under the test's direct control and
 * drives each away from healthy independently. A gate that could only ever be constructed against
 * the real engine and the real store would make "does this report unready" untestable except
 * through failure injection against live infrastructure, which is exactly the kind of control this
 * run has repeatedly found to be decoration because nothing exercises its false path.</p>
 */
public final class ReadinessGate implements AutoCloseable {
    private static final String RUNNING = "RUNNING";

    private final Supplier<String> engineState;
    private final StoreLivenessCheck storeCheck;
    private final Supplier<List<DependencyStatus>> dependencies;
    /**
     * Whether the startup pass over inherited durable work has finished. Supplied rather than
     * computed here for the same reason the engine state and the store check are: a gate that could
     * only be built against the real discovery pass would make "does this report unready" testable
     * only by failure injection against live infrastructure.
     */
    private final BooleanSupplier recoveryClassified;
    private final ReadinessConfiguration configuration;
    private final ExecutorService probeExecutor;
    private final Object probeMonitor = new Object();
    private Future<Boolean> outstandingProbe;

    /**
     * A gate for a deployment that runs no startup recovery classification, which therefore never
     * reports {@link ReadinessState#RECOVERING}. Retained as the additive default so an existing
     * composition keeps exactly the behaviour it had.
     *
     * @param engineState   supplier of the engine's own lifecycle state.
     * @param storeCheck    the required durable dependency probe.
     * @param dependencies  optional dependencies, reported but never gating.
     * @param configuration probe timeout and drain settings.
     */
    public ReadinessGate(Supplier<String> engineState, StoreLivenessCheck storeCheck,
                         Supplier<List<DependencyStatus>> dependencies,
                         ReadinessConfiguration configuration) {
        this(engineState, storeCheck, dependencies, () -> true, configuration);
    }

    /**
     * A gate that stays closed until the startup pass over inherited durable work has completed.
     *
     * @param engineState        supplier of the engine's own lifecycle state.
     * @param storeCheck         the required durable dependency probe.
     * @param dependencies       optional dependencies, reported but never gating.
     * @param recoveryClassified whether startup recovery classification has finished.
     * @param configuration      probe timeout and drain settings.
     */
    public ReadinessGate(Supplier<String> engineState, StoreLivenessCheck storeCheck,
                         Supplier<List<DependencyStatus>> dependencies,
                         BooleanSupplier recoveryClassified, ReadinessConfiguration configuration) {
        this.recoveryClassified = Objects.requireNonNull(recoveryClassified, "recoveryClassified");
        this.engineState = Objects.requireNonNull(engineState, "engineState");
        this.storeCheck = Objects.requireNonNull(storeCheck, "storeCheck");
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.probeExecutor = Executors.newSingleThreadExecutor(runnable -> {
            var thread = new Thread(runnable, "ravenroot-readiness-probe");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** No store composed into this deployment, no optional dependencies tracked -- only the engine's
     * own state can degrade readiness. See {@link StoreLivenessCheck}'s Javadoc for why this is the
     * honest default rather than a stand-in for a check that does not exist yet. */
    public static ReadinessGate engineOnly(Supplier<String> engineState) {
        return new ReadinessGate(engineState, StoreLivenessCheck.none(), List::of, ReadinessConfiguration.defaults());
    }

    public ReadinessReport evaluate() {
        List<DependencyStatus> currentDependencies = dependencies.get();
        if (!RUNNING.equals(engineState.get())) {
            return new ReadinessReport(false, ReadinessState.DRAINING, currentDependencies);
        }
        if (!recoveryClassified.getAsBoolean()) {
            return new ReadinessReport(false, ReadinessState.RECOVERING, currentDependencies);
        }
        if (!storeIsHealthy()) {
            return new ReadinessReport(false, ReadinessState.STORE_DEGRADED, currentDependencies);
        }
        return new ReadinessReport(true, ReadinessState.READY, currentDependencies);
    }

    private boolean storeIsHealthy() {
        Future<Boolean> future;
        synchronized (probeMonitor) {
            if (outstandingProbe == null || outstandingProbe.isDone()) {
                outstandingProbe = probeExecutor.submit(() -> {
                    try {
                        storeCheck.check();
                        return true;
                    } catch (Exception failed) {
                        return false;
                    }
                });
            }
            future = outstandingProbe;
        }
        try {
            return future.get(configuration.storeCheckTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception failed) {
            return false;
        }
    }

    @Override
    public void close() {
        synchronized (probeMonitor) {
            if (outstandingProbe != null) {
                outstandingProbe.cancel(true);
            }
        }
        probeExecutor.shutdownNow();
    }
}
