package ai.ravenroot.core.runtime;

import ai.ravenroot.api.persistence.JoinStore;
import ai.ravenroot.core.persistence.InMemoryJoinStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-17, and the guard added on top of it by FIX-35.
 *
 * <p><b>The re-entrant termination defect.</b> The idempotence guard used to answer a second caller with
 * {@code CompletableFuture.completedFuture(null)}. That stage was already complete while the first
 * caller was still inside {@code releaseInMemory}, which is the only thing that ever writes
 * {@code abandonedBranch}. {@link JoinCoordinator#abandonedBranchFailure()} documents that it is
 * meaningful only once {@code terminate()}'s stage has completed, and {@code GraphRunner} reads it
 * exactly that way — so the second caller read {@code null} and the traversal reported COMPLETED
 * with no result and its end node never executed.
 *
 * <p><b>Why {@code releaseWaiters} is not the correction point.</b> The first caller runs
 * {@code releaseInMemory} synchronously before its stage
 * exists, so no reordering there can affect it; and the two statements cannot be swapped anyway,
 * because {@code abandonedBranch.compareAndSet} is guarded by the return value of
 * {@code completeWaiters} — hoisting it would either need a restructure or would report an abandoned
 * branch for every terminated join, which
 * {@code JoinRetainedStateTest#reportsNothingAbandonedWhenNoBranchWasEverParked} forbids.
 *
 * <p><b>How the red is constructed rather than sampled.</b> {@code completeWaiters} completes the
 * parked futures outside the {@code waiters} monitor, so a dependent attached to a parked branch
 * runs on the terminating thread — inside {@code releaseWaiters}, after the waiters are completed
 * and before the compare-and-set. Blocking that dependent on a latch holds the first caller in
 * exactly the window the second caller must not be able to see through. No timing, no sampling, no
 * load: the happens-before edge is supplied, so the premature-stage implementation fails every run.
 *
 * <p><b>What the readiness guard adds here.</b> The shared termination stage became accurate; the guard
 * would have caught the defect even if the stage had still been lying, because
 * {@link JoinCoordinator#abandonedBranchFailure()} no longer trusts any caller's belief about
 * completion. {@link #aSecondTerminateDoesNotCompleteBeforeTheAbandonedBranchIsRecorded()} now reads
 * the getter, in this exact window, expecting it to throw rather than to answer {@code null} — the
 * same window the re-entrant caller exploited, this time with a mechanism present that fails loudly in it regardless
 * of which stage a caller is holding.
 * {@link #theRealCallerPatternNeverObservesThePrematureStateEvenWithoutAnExplicitWait()} is the
 * companion: it drives the read the way {@code GraphRunner} actually does — composed onto the
 * second caller's own returned stage, never blocked on with {@code .get()} — and shows that the
 * shared-stage implementation never trips the new guard, because the stage genuinely does not complete until
 * {@code releaseComplete} is set. Reverting FIX-17's sharing of {@code termination} while keeping
 * FIX-35's guard turns that same test red: the naively-already-complete stub stage lets the
 * composed read run before {@code releaseComplete} is set, and the getter throws instead of quietly
 * handing back {@code null}; the mutation is described here rather than committed as a test that
 * permanently changes production code.
 */
class JoinTerminateIdempotenceTest {

    private final JoinTestEngine engine = new JoinTestEngine();
    private final ExecutionMonitor monitor = new ExecutionMonitor();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aSecondTerminateDoesNotCompleteBeforeTheAbandonedBranchIsRecorded() throws Exception {
        var coordinator = coordinator(new InMemoryJoinStore());
        // An all-join of two with a single arrival cannot settle, so b0 parks.
        var parked = coordinator.arrive("join",
                new JoinArrival("b0", "from-b0", Map.of(), java.util.Set.of()));

        var insideRelease = new CountDownLatch(1);
        var holdRelease = new CountDownLatch(1);
        parked.handle((decision, error) -> {
            insideRelease.countDown();
            await(holdRelease);
            return null;
        });

        var first = new Thread(() -> coordinator.terminate(), "first-terminate");
        first.start();
        assertTrue(insideRelease.await(20, TimeUnit.SECONDS),
                "the first terminate() never reached the parked waiter, so the window this test "
                        + "exists to close was never opened and a pass would prove nothing");

        // The first caller is now held inside releaseInMemory, before abandonedBranch is written.
        var second = coordinator.terminate().toCompletableFuture();
        try {
            // isDone(), not getNow(null): a CompletableFuture<Void> yields null whether it is
            // incomplete or completed, so getNow would be an assertion incapable of failing.
            assertFalse(second.isDone(),
                    "the second terminate() returned an already-complete stage while the first caller "
                            + "was still inside releaseInMemory. A caller that reads "
                            + "abandonedBranchFailure() on completion -- which GraphRunner does -- sees "
                            + "null and lets the traversal claim success with no result. This is FIX-17");
            // The question is not yet answerable at this instant -- releaseInMemory
            // has not returned for this coordinator -- and the getter now says so instead of
            // answering the same null a genuine "nothing abandoned" would produce.
            assertThrows(IllegalStateException.class, coordinator::abandonedBranchFailure,
                    "the abandoned branch is genuinely not recorded yet at this instant, so this guard "
                            + "requires this getter to refuse to answer rather than silently claim "
                            + "\"nothing abandoned\"");
        } finally {
            holdRelease.countDown();
        }

        second.get(20, TimeUnit.SECONDS);
        assertNotNull(coordinator.abandonedBranchFailure(),
                "once the second caller's stage completes, the abandoned branch must be visible -- "
                        + "that is what the stage promises");
        first.join(20_000);
    }

    /**
     * The real caller pattern. {@code GraphRunner} never blocks on {@code terminate()}'s
     * stage with {@code .get()} -- it composes {@code abandonedBranchFailure()} onto it with
     * {@code .handle(...)}, exactly like the read below. This drives that exact composition through
     * the same window {@link #aSecondTerminateDoesNotCompleteBeforeTheAbandonedBranchIsRecorded()}
     * constructs, and shows the shared-stage implementation never lets the composed read observe the not-yet-ready
     * state: the second caller's stage IS the shared {@code termination} stage FIX-17 introduced, so
     * a continuation composed onto it cannot run until {@code releaseComplete} is already set.
     *
     * <p>This is the test that would go red if FIX-17's sharing of {@code termination} were ever
     * reverted while FIX-35's guard stayed in place: a naively-already-complete stub stage would let
     * this same composed read run inside the window, and {@code abandonedBranchFailure()} would throw
     * from inside the {@code .handle(...)} continuation instead of quietly handing back {@code null}
     * -- which is the mutation check described here rather than committed as a test that
     * mutates production code.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void theRealCallerPatternNeverObservesThePrematureStateEvenWithoutAnExplicitWait() throws Exception {
        var coordinator = coordinator(new InMemoryJoinStore());
        var parked = coordinator.arrive("join",
                new JoinArrival("b0", "from-b0", Map.of(), java.util.Set.of()));

        var insideRelease = new CountDownLatch(1);
        var holdRelease = new CountDownLatch(1);
        parked.handle((decision, error) -> {
            insideRelease.countDown();
            await(holdRelease);
            return null;
        });

        var first = new Thread(() -> coordinator.terminate(), "first-terminate");
        first.start();
        assertTrue(insideRelease.await(20, TimeUnit.SECONDS),
                "the first terminate() never reached the parked waiter, so the window this test "
                        + "exists to close was never opened and a pass would prove nothing");

        var second = coordinator.terminate().toCompletableFuture();
        // GraphRunner's actual pattern (GraphRunner.java, the .thenCompose(error -> release(...)
        // .handle(...)) chain): composed onto the stage, never blocked on with .get().
        var composedRead = second.handle((done, error) -> coordinator.abandonedBranchFailure());
        assertFalse(composedRead.isDone(),
                "the composed read must not have run yet -- it can only run once the shared stage "
                        + "completes, and running early against a not-yet-ready coordinator is "
                        + "exactly the shape the re-entrant caller exploited");

        holdRelease.countDown();
        JoinFailureException abandoned = composedRead.get(20, TimeUnit.SECONDS);
        assertNotNull(abandoned, "the composed read, which only ran once the shared stage genuinely "
                + "completed, must see the real verdict -- proving the shared stage protects the real caller "
                + "pattern GraphRunner uses, not only a caller careful enough to call .get() first");
        first.join(20_000);
    }

    /**
     * The control. Without it a null from the test above is indistinguishable from a coordinator that
     * never records an abandoned branch at all, and the assertion would pass for the wrong reason.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aSingleTerminateRecordsTheAbandonedBranch() throws Exception {
        var coordinator = coordinator(new InMemoryJoinStore());
        coordinator.arrive("join", new JoinArrival("b0", "from-b0", Map.of(), java.util.Set.of()));

        coordinator.terminate().toCompletableFuture().get(20, TimeUnit.SECONDS);

        assertNotNull(coordinator.abandonedBranchFailure(),
                "a branch parked at termination must be recorded as abandoned -- if this fails, the "
                        + "concurrent test above proves nothing, because it could report null for a "
                        + "reason that has nothing to do with the race");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch was never released");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    /** One {@code all} join over {@code b0} and {@code b1}, so a single arrival cannot satisfy it. */
    private JoinCoordinator coordinator(JoinStore store) {
        var identity = new ExecutionMonitor.ExecutionIdentity(TestIdentities.TENANT_A, "join-test", "v1",
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID());
        var spec = new JoinSpec("join", List.of("b0", "b1"), 2, null);
        return new JoinCoordinator(store, engine.scheduler(), monitor, identity, Map.of("join", spec),
                java.time.Clock.systemUTC());
    }
}
