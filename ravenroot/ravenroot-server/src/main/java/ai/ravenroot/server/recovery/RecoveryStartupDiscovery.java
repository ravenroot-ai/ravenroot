package ai.ravenroot.server.recovery;

import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.core.recovery.ExecutionRecoveryCoordinator;
import ai.ravenroot.core.recovery.ExecutionRecoveryService;
import ai.ravenroot.core.recovery.RecoveryCandidate;
import ai.ravenroot.core.recovery.RecoveryClassification;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * The startup pass that decides what this process inherited, and holds readiness closed until it has.
 *
 * <h2>Why readiness waits for it</h2>
 * <p>A process that has not yet looked at the durable state cannot say whether it is able to run the
 * work already accepted into it. Reporting ready before the answer exists is the specific outage this
 * gate prevents: traffic arrives, is admitted, and lands beside inherited work the deployment turns
 * out not to be able to rebuild — a fact that was knowable at startup and was simply not asked for.
 * The store liveness probe does not cover this. It answers whether the store responds, not whether
 * what is in it is runnable here.</p>
 *
 * <h2>One corrupt item does not hide the rest</h2>
 * <p>A pass classifies every discovered instance and reports each refusal individually.
 * {@link ExecutionRecoveryCoordinator#classify} converts a row that cannot be classified into a
 * verdict about that row alone, so the pass completes and the cohort is reported whole. Refusals
 * therefore do <em>not</em> keep readiness closed — they are inherited facts an operator acts on,
 * and a deployment that could never open while one corrupt instance existed would be unable to serve
 * the tenants that instance has nothing to do with.</p>
 *
 * <h2>What does keep readiness closed</h2>
 * <p>Only a pass that could not complete at all — the store unreachable while it was being scanned.
 * That is retried on a bounded cadence rather than failed permanently, because it is the same
 * condition the store probe is already reporting and it resolves on its own. An adapter that does
 * not offer a durable inventory is a different case entirely: there is no scan to run, so the pass
 * completes immediately, saying so, rather than holding a deployment closed forever waiting for an
 * answer that cannot exist.</p>
 */
public final class RecoveryStartupDiscovery implements AutoCloseable {

    private static final System.Logger LOGGER =
            System.getLogger("ai.ravenroot.server.recovery.RecoveryStartupDiscovery");

    /**
     * How many inherited instances one startup pass classifies before it reports and opens readiness.
     *
     * <p>Bounded because readiness waits for this pass, and an unbounded scan makes the wait a
     * function of how much work the deployment inherited. A deployment restarting with a very large
     * interrupted cohort would refuse traffic for the whole scan — turning a check that exists to
     * prevent an outage into one. The instances past the bound are not lost or ignored: the ordinary
     * sweep claims and decides them exactly as it decides the ones inside it, which is what acts on
     * any of them. Only the startup report is truncated, and it says so.</p>
     */
    public static final int DEFAULT_MAX_CLASSIFICATIONS = 500;

    private final ExecutionRecoveryService recovery;
    private final ExecutionRecoveryCoordinator coordinator;
    private final Predicate<StoreCapability> storeSupports;
    private final Duration retryInterval;
    private final int maxClassifications;
    private final ScheduledExecutorService executor;
    private final AtomicReference<Result> result = new AtomicReference<>();

    /**
     * Composes the startup pass over the recovery loop it inspects.
     *
     * @param recovery      the sweep whose durable inventory discovery this pass reuses.
     * @param coordinator   the authority each discovered instance is classified through.
     * @param storeSupports the composed store's capability test, so an adapter without a durable
     *                      inventory completes rather than blocking readiness.
     * @param retryInterval how long to wait before re-running a pass that could not complete.
     */
    public RecoveryStartupDiscovery(ExecutionRecoveryService recovery,
                                    ExecutionRecoveryCoordinator coordinator,
                                    Predicate<StoreCapability> storeSupports,
                                    Duration retryInterval) {
        this(recovery, coordinator, storeSupports, retryInterval, DEFAULT_MAX_CLASSIFICATIONS);
    }

    /**
     * Composes the startup pass with an explicit bound on how much it classifies.
     *
     * @param recovery      the sweep whose durable inventory discovery this pass reuses.
     * @param coordinator   the authority each discovered instance is classified through.
     * @param storeSupports the composed store's capability test.
     * @param retryInterval how long to wait before re-running a pass that could not complete.
     * @param maxClassifications greatest number of instances one pass classifies; must be positive.
     */
    public RecoveryStartupDiscovery(ExecutionRecoveryService recovery,
                                    ExecutionRecoveryCoordinator coordinator,
                                    Predicate<StoreCapability> storeSupports,
                                    Duration retryInterval, int maxClassifications) {
        if (maxClassifications < 1) {
            throw new IllegalArgumentException("maxClassifications must be positive");
        }
        this.maxClassifications = maxClassifications;
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.storeSupports = Objects.requireNonNull(storeSupports, "storeSupports");
        Objects.requireNonNull(retryInterval, "retryInterval");
        if (retryInterval.isNegative() || retryInterval.isZero()) {
            throw new IllegalArgumentException("retryInterval must be positive");
        }
        this.retryInterval = retryInterval;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "ravenroot-recovery-startup-discovery");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Begins the pass, retrying on the configured cadence until one completes. */
    public void start() {
        executor.scheduleWithFixedDelay(this::attempt, 0, retryInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Whether a pass has completed, whatever it found.
     *
     * @return {@code true} once the inherited cohort has been discovered and classified.
     */
    public boolean complete() {
        return result.get() != null;
    }

    /**
     * The completed pass, or {@code null} while none has completed.
     *
     * @return what this process inherited, or {@code null} before the first pass finishes.
     */
    public Result completed() {
        return result.get();
    }

    private void attempt() {
        if (complete()) {
            return;
        }
        try {
            if (!storeSupports.test(StoreCapability.PROCESS_INVENTORY)) {
                // Stated rather than inferred: an operator reading "recovery discovery skipped" must
                // not have to work out from the adapter's documentation why nothing was scanned.
                LOGGER.log(System.Logger.Level.INFO,
                        "ravenroot_recovery_discovery skipped=no-durable-inventory");
                result.compareAndSet(null, new Result(List.of(), false, false));
                return;
            }
            // One more than the bound, so a cohort that exactly fills it is not reported as truncated
            // and one that overflows is — without a second scan to find out which.
            List<ai.ravenroot.api.persistence.ProcessInventoryEntry> discovered =
                    recovery.discoverInterrupted(maxClassifications + 1);
            boolean truncated = discovered.size() > maxClassifications;
            List<RecoveryCandidate> candidates = coordinator.classify(
                    truncated ? discovered.subList(0, maxClassifications) : discovered);
            long refused = candidates.stream().filter(candidate -> !candidate.rehydratable()).count();
            LOGGER.log(System.Logger.Level.INFO,
                    "ravenroot_recovery_discovery interrupted={0} recoverable={1} refused={2} truncated={3}",
                    candidates.size(), candidates.size() - refused, refused, truncated);
            result.compareAndSet(null, new Result(candidates, true, truncated));
        } catch (RuntimeException incomplete) {
            // Deliberately not completed: the durable state could not be read, so nothing is known
            // about it yet, and readiness stays closed until a later pass does know.
            LOGGER.log(System.Logger.Level.WARNING,
                    "ravenroot_recovery_discovery incomplete; readiness stays closed and the pass retries");
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    /**
     * What one completed pass found.
     *
     * @param candidates each interrupted instance with this deployment's verdict about rebuilding it.
     * @param scanned    whether a durable inventory was actually scanned; {@code false} when the
     *                   composed store offers none, in which case {@code candidates} is empty because
     *                   nothing was looked at rather than because nothing was found.
     * @param truncated  whether more interrupted instances exist than this pass classified. The rest
     *                   are decided by the ordinary sweep; only this report stops short of them.
     */
    public record Result(List<RecoveryCandidate> candidates, boolean scanned, boolean truncated) {

        /** Freezes the classified cohort. */
        public Result {
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        }

        /**
         * The instances this deployment refused to rebuild, kept inspectable rather than summarised
         * into a count.
         *
         * @return every refused candidate, in discovery order.
         */
        public List<RecoveryCandidate> refused() {
            return candidates.stream()
                    .filter(candidate -> candidate.classification() instanceof RecoveryClassification.Refused)
                    .toList();
        }
    }
}
