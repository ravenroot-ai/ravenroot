package ai.ravenroot.core.runtime;

import ai.ravenroot.api.persistence.JoinStore;
import ai.ravenroot.core.persistence.InMemoryJoinStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The verdict {@code releaseWaiters} builds for an abandoned branch must carry the
 * causes of the branches that failed in <em>this</em> process.
 *
 * <p><b>The defect.</b> {@code releaseWaiters} named the failed branches — {@code branchFailures}'
 * key set is the right argument for the constructor's {@code failed} list — and then stopped there.
 * The {@link Throwable}s held against those same keys were never attached, so the exception the
 * runner reports for the whole traversal said <em>which</em> branch broke and never <em>what</em>
 * broke it. This is the third instance of the shape FIX-16 corrected at
 * {@code recordedFailure}, and the correction here is deliberately the same one: build the verdict
 * from the branch-id lists, then attach the causes this process still holds with
 * {@code addSuppressed}, filtering nulls.
 *
 * <p><b>Why this path qualifies for the causes at all.</b> The rule is that a verdict is thin
 * exactly when the causes are gone, and they are gone only after a cross-process restart.
 * {@code releaseWaiters} runs from {@code releaseInMemory} on a live coordinator whose traversal is
 * ending in-process, so the causes are still in memory by construction. The restart case is
 * unaffected and is pinned by {@link #aVerdictBuiltWithNoCauseInMemoryStaysAsThinAsItWas()}: a
 * coordinator that never saw a branch fail has nothing to attach, and the loop is a no-op.
 *
 * <p><b>Why the delivered verdict is inspected at completion time and not only afterwards.</b>
 * {@code LocalJoin#completeWaiters} completes the parked futures <em>outside</em> the {@code waiters}
 * monitor, so a dependent attached to a parked branch runs on the terminating thread, inside
 * {@code releaseWaiters}. That is what lets
 * {@link #theParkedBranchIsReleasedWithTheCausesAlreadyAttached()} observe the exception at the
 * instant it is handed over, and so distinguish a correction that attaches the causes <em>before</em>
 * delivery from one that attaches them after — which would leave every consumer that reads the
 * exception on completion, as {@code GraphRunner} does, seeing the same empty verdict as before.
 */
class JoinAbandonedBranchCauseTest {

    private final JoinTestEngine engine = new JoinTestEngine();
    private final ExecutionMonitor monitor = new ExecutionMonitor();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    /**
     * The control. Red before the correction: the verdict names {@code b1} as failed and carries no
     * suppressed exception at all.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void theAbandonedBranchVerdictCarriesTheCauseOfTheBranchThatFailed() throws Exception {
        var coordinator = coordinator(new InMemoryJoinStore());
        var cause = new IllegalStateException("b1 exploded");

        // b0 arrives and parks: one arrival cannot meet a quorum of two.
        coordinator.arrive("join", arrival("b0"));
        // b1 fails. Two of three branches are now accounted for and one is still outstanding, so the
        // quorum stays reachable and the join stays OPEN -- which is what leaves b0 parked with a
        // recorded branch failure alongside it.
        coordinator.fail("join", "b1", cause).toCompletableFuture().get(20, TimeUnit.SECONDS);

        coordinator.terminate().toCompletableFuture().get(20, TimeUnit.SECONDS);

        JoinFailureException abandoned = coordinator.abandonedBranchFailure();
        assertNotNull(abandoned, "precondition: a branch was parked when the traversal ended, so a "
                + "verdict must have been recorded. If this fails the test below proves nothing");
        assertEquals(List.of("b1"), abandoned.failed(),
                "precondition: the verdict already names the failed "
                        + "branch. What it did not carry is why");
        assertEquals(1, abandoned.getSuppressed().length,
                "the traversal's own failure must carry the cause of the branch that failed. The "
                        + "branch id alone tells an operator where to look and nothing about what to "
                        + "look for -- this is FIX-34, the third instance of the shape corrected "
                        + "at recordedFailure()");
        assertSame(cause, abandoned.getSuppressed()[0],
                "the cause attached must be the Throwable the branch actually reported, not a "
                        + "reconstruction of it");
    }

    /**
     * The causes must be attached <em>before</em> the verdict is handed to the parked branch, not
     * after. Attaching them afterwards would satisfy a test that only inspects
     * {@code abandonedBranchFailure()} at the end, while every consumer reading the exception on
     * completion still saw it empty.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void theParkedBranchIsReleasedWithTheCausesAlreadyAttached() throws Exception {
        var coordinator = coordinator(new InMemoryJoinStore());
        var cause = new IllegalStateException("b1 exploded");

        var parked = coordinator.arrive("join", arrival("b0")).toCompletableFuture();
        coordinator.fail("join", "b1", cause).toCompletableFuture().get(20, TimeUnit.SECONDS);

        var suppressedAtDelivery = new AtomicInteger(-1);
        var deliveredCause = new AtomicReference<Throwable>();
        parked.whenComplete((decision, error) -> {
            Throwable delivered = unwrap(error);
            deliveredCause.set(delivered);
            suppressedAtDelivery.set(delivered == null ? -1 : delivered.getSuppressed().length);
        });

        coordinator.terminate().toCompletableFuture().get(20, TimeUnit.SECONDS);

        var thrown = assertThrows(ExecutionException.class, () -> parked.get(20, TimeUnit.SECONDS),
                "the parked branch is released with the join's verdict rather than left pending");
        assertTrue(thrown.getCause() instanceof JoinFailureException,
                "the branch is released with the join's own verdict, got " + thrown.getCause());
        assertEquals(1, suppressedAtDelivery.get(),
                "the cause must already be attached at the instant the verdict is delivered. A "
                        + "correction that calls addSuppressed after completeWaiters leaves every "
                        + "consumer that reads the exception on completion -- GraphRunner does -- with "
                        + "an empty verdict at delivery time");
        assertSame(cause, deliveredCause.get().getSuppressed()[0],
                "and it must be the branch's own Throwable");
    }

    /**
     * The cross-process distinction, demonstrably untouched. A coordinator that holds no branch
     * causes — the shape a process has after a restart, where the causes died with the process that
     * observed them — still produces the deliberately thinner verdict. The correction adds causes
     * where they exist; it does not manufacture them where they do not.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aVerdictBuiltWithNoCauseInMemoryStaysAsThinAsItWas() throws Exception {
        var coordinator = coordinator(new InMemoryJoinStore());

        coordinator.arrive("join", arrival("b0"));

        coordinator.terminate().toCompletableFuture().get(20, TimeUnit.SECONDS);

        JoinFailureException abandoned = coordinator.abandonedBranchFailure();
        assertNotNull(abandoned, "a branch was parked when the traversal ended and must be reported");
        assertEquals(List.of(), abandoned.failed(), "no branch failed here");
        assertEquals(0, abandoned.getSuppressed().length,
                "with no cause in memory the verdict stays thin: a verdict is poorer "
                        + "exactly when the causes are "
                        + "genuinely gone, never as a matter of preference");
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException wrapper && wrapper.getCause() != null
                ? wrapper.getCause()
                : error;
    }

    private static JoinArrival arrival(String branchId) {
        return new JoinArrival(branchId, "from-" + branchId, Map.of(), Set.of());
    }

    /**
     * One {@code 2 of 3} join. A {@code k of n} rather than an {@code all} join on purpose: an
     * {@code all} join fails outright on its first branch failure, which settles it and completes its
     * waiters, so no branch is ever left parked for termination to abandon. The quorum has to survive
     * the failure for {@code releaseWaiters} to be the code that builds the verdict.
     */
    private JoinCoordinator coordinator(JoinStore store) {
        var identity = new ExecutionMonitor.ExecutionIdentity(TestIdentities.TENANT_A, "join-test", "v1",
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID());
        var spec = new JoinSpec("join", List.of("b0", "b1", "b2"), 2, null);
        return new JoinCoordinator(store, engine.scheduler(), monitor, identity, Map.of("join", spec),
                java.time.Clock.systemUTC());
    }
}
