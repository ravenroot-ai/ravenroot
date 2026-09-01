package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.persistence.InMemoryJoinStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The timeout against a concurrent branch write (CORE-03).
 *
 * <p>A timeout fires once and is never re-armed, so it gets exactly one chance to settle its join.
 * That makes a compare-and-set conflict on the timeout path categorically different from a conflict
 * on an arrival path: a losing arrival simply retries and the join still has a deadline, while a
 * losing timeout that gives up leaves a join that is still open and now has no deadline at all.</p>
 *
 * <p>The conflict the original code assumed — a branch settling the join — is not the common one.
 * The common one is a branch recording its arrival and leaving the phase {@code OPEN}, which is
 * indistinguishable from the assumed case at the point where the conflict is caught and has the
 * opposite consequence.</p>
 */
class JoinTimeoutRaceTest {

    /** Trials, because which of the two writers wins the barrier release is genuinely a race. */
    private static final int TRIALS = 8;

    private final ExecutionMonitor monitor = new ExecutionMonitor();

    private JoinTestEngine engine;

    @AfterEach
    void closeEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void failsWithTimeoutEvenWhenABranchWriteIsInFlightAtTheSameRevision() throws Exception {
        for (int trial = 0; trial < TRIALS; trial++) {
            JoinFailureException failure = raceTimeoutAgainstAnArrival();
            assertEquals(JoinFailureException.Reason.TIMEOUT, failure.reason(),
                    "the join lost its only deadline to a concurrent branch write on trial " + trial);
            // b2 is the only branch that is outstanding on every interleaving: whether b1 appears as
            // arrived or as outstanding depends on which of the two racing writes the timeout read,
            // which is the very thing being raced and therefore not something to assert.
            assertTrue(failure.outstanding().contains("b2"),
                    "b2 never returned and must be reported outstanding, trial " + trial
                            + ", got " + failure.outstanding());
            assertFalse(failure.arrived().contains("b2"), "b2 never arrived, trial " + trial);
        }
    }

    /**
     * One trial. {@code b0} arrives and leaves the join open; {@code b1} is then released at the same
     * moment the deadline fires, so both write against the revision {@code b0} left behind.
     * {@code b2} never returns, so the quorum of three can never be met and the only terminal outcome
     * available is the timeout.
     */
    private JoinFailureException raceTimeoutAgainstAnArrival() throws Exception {
        engine = new JoinTestEngine();
        var backing = new InMemoryJoinStore();
        // Two contenders: the arriving branch and the timeout. Every writer is held until a second
        // one is ready, which is what makes them collide on the same revision rather than by luck.
        var store = new ContendedJoinStore(backing, 2);
        var releasedB1 = new CompletableFuture<NodeResult>();
        var neverB2 = new CompletableFuture<NodeResult>();
        var graph = JoinMiniGraphs.fanIn(3, JoinMiniGraphs.quorumWithTimeout(3, "PT30S"));

        var registry = new BehaviorRegistry();
        registry.register("b0", message -> CompletableFuture.completedFuture(NodeResult.continueWith("from-b0")));
        registry.register("b1", message -> releasedB1);
        registry.register("b2", message -> neverB2);

        try (var manager = GraphManager.from(graph);
             var runner = new GraphRunner(manager, engine, registry, monitor,
                     ExecutionIdentitySource.randomUuids(), store, Clock.systemUTC())) {
            var execution = runner.execute(TestIdentities.TENANT_A, "in").toCompletableFuture();

            // b0's write must have landed first: a timeout that loads an absent record has nothing to
            // settle and would leave the trial measuring the wrong thing.
            awaitRecordCount(backing, 1);

            var releaser = new Thread(() -> releasedB1.complete(NodeResult.continueWith("from-b1")));
            releaser.start();
            assertEquals(1, engine.manualScheduler().fireAll(), "exactly one join timeout was scheduled");
            releaser.join(10_000);

            var error = assertThrows(ExecutionException.class, () -> execution.get(10, TimeUnit.SECONDS));
            neverB2.complete(NodeResult.continueWith("too late"));
            return joinFailure(error);
        }
    }

    private static void awaitRecordCount(InMemoryJoinStore store, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (store.totalRecordCount() >= expected) {
                return;
            }
            Thread.sleep(5);
        }
        assertTrue(false, "expected " + expected + " join records, saw " + store.totalRecordCount());
    }

    private static JoinFailureException joinFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof JoinFailureException failure) {
                return failure;
            }
            current = current.getCause();
        }
        throw new AssertionError("expected a JoinFailureException, got " + error, error);
    }
}
