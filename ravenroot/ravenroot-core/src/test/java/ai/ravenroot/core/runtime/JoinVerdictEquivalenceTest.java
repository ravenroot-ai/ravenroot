package ai.ravenroot.core.runtime;

import ai.ravenroot.api.persistence.JoinStore;
import ai.ravenroot.core.persistence.InMemoryJoinStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One join failure must produce <b>one verdict</b>, whichever branch observes it.
 *
 * <p><b>What was wrong.</b> Two paths in {@link JoinCoordinator} construct the verdict for the same
 * failure and they were not equivalent. {@code unreachable()} attaches the branch causes from
 * {@code LocalJoin.branchFailures}; {@code recordedFailure()} rebuilt the verdict from the persisted
 * record and attached nothing. The second is reached by the in-process redelivery path — a sibling
 * branch reporting into an already-{@code FAILED} join — so whichever verdict reached the traversal
 * first decided whether the failing branch's own error survived as a suppressed cause. That made
 * {@code JoinSemanticsTest#failsTheJoinAsSoonAsTheQuorumBecomesUnreachable} unstable at roughly 1% of
 * traversals, and the losing path was measured still holding the cause in memory when it discarded
 * it.
 *
 * <p><b>Why this test drives the coordinator directly.</b> The defect is a race between two
 * verdicts, and reproducing it through the public traversal would mean winning that race on purpose
 * — which cannot be done without instrumenting the product, and a test that has to be lucky is not a
 * regression test. Sequencing the two reports here supplies the happens-before edge explicitly:
 * {@code fail(b1)} settles the join, then {@code arrive(b0)} is the redelivery that rebuilds the
 * verdict. No timing, no sampling, no load — the vulnerable ordering loses the cause and the
 * protected ordering retains it on every run.
 *
 * <p>This test proves verdict equivalence. {@code JoinSemanticsTest} exercises the public path and
 * proves the race no longer changes the result. Neither alone does both, which is why both are kept.
 *
 * <p>Nothing here is stubbed. The coordinator, the store and the verdicts are the real ones; the
 * only thing supplied is the order in which two branches report.
 */
class JoinVerdictEquivalenceTest {

    private final JoinTestEngine engine = new JoinTestEngine();
    private final ExecutionMonitor monitor = new ExecutionMonitor();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    @Test
    void aRedeliveredBranchSeesTheSameVerdictAsTheBranchThatFailedTheJoin() throws Exception {
        var coordinator = coordinator(new InMemoryJoinStore());
        var cause = new IllegalStateException("branch exploded");

        // b1 fails: this settles the join and produces the verdict first-hand.
        var firstHand = assertInstanceOf(JoinDecision.Failed.class,
                coordinator.fail("join", "b1", cause).toCompletableFuture().get(5, TimeUnit.SECONDS));

        // b0 now reports into an already-FAILED join. It must be told the same thing, not a thinner
        // version of it -- this is the in-process redelivery path through recordedFailure().
        var redelivered = assertInstanceOf(JoinDecision.Failed.class,
                coordinator.arrive("join", new JoinArrival("b0", "from-b0", Map.of(), java.util.Set.of()))
                        .toCompletableFuture().get(5, TimeUnit.SECONDS));

        assertEquals(JoinFailureException.Reason.QUORUM_UNREACHABLE, firstHand.failure().reason());
        assertEquals(JoinFailureException.Reason.QUORUM_UNREACHABLE, redelivered.failure().reason());
        assertEquals(List.of("b1"), firstHand.failure().failed());
        assertEquals(List.of("b1"), redelivered.failure().failed());

        assertTrue(carries(firstHand, cause),
                "the branch that failed the join must carry its own cause -- if this fails the defect "
                        + "is not the suppressed-cause defect");
        assertTrue(carries(redelivered, cause),
                "the redelivered branch was handed a verdict that dropped the branch's cause. That is "
                        + "FIX-16: recordedFailure() discarded a Throwable this process still held in "
                        + "LocalJoin.branchFailures, so the traversal's error depended on which branch "
                        + "reported first");
    }

    /**
     * The restart case the omission actually describes, kept to reject an incorrect rule of
     * "always attach something". A coordinator that never saw the failure in this process has no
     * cause to attach, and the rebuilt verdict is legitimately thinner.
     */
    @Test
    void aVerdictRebuiltWithoutTheCausesInMemoryStaysThin() throws Exception {
        var store = new InMemoryJoinStore();
        // The same execution identity on both sides, so the restarted coordinator addresses the very
        // record the dead one wrote. A fresh identity would mint a different JoinKey and the lookup
        // would simply miss, which would prove nothing.
        var identity = identity();
        var died = coordinator(store, identity);
        died.fail("join", "b1", new IllegalStateException("branch exploded"))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        // A fresh coordinator over the same store: the record survived, the Throwables did not.
        var restarted = coordinator(store, identity);
        var rebuilt = assertInstanceOf(JoinDecision.Failed.class,
                restarted.arrive("join", new JoinArrival("b0", "from-b0", Map.of(), java.util.Set.of()))
                        .toCompletableFuture().get(5, TimeUnit.SECONDS));

        assertEquals(JoinFailureException.Reason.QUORUM_UNREACHABLE, rebuilt.failure().reason());
        assertEquals(List.of("b1"), rebuilt.failure().failed());
        assertEquals(0, rebuilt.failure().getSuppressed().length,
                "after a restart the causes are genuinely unrecoverable, so the rebuilt verdict is "
                        + "thin by necessity -- the coordinator must not manufacture one");
    }

    private static boolean carries(JoinDecision.Failed decision, Throwable cause) {
        return List.of(decision.failure().getSuppressed()).stream().anyMatch(s -> s == cause);
    }

    private static ExecutionMonitor.ExecutionIdentity identity() {
        return new ExecutionMonitor.ExecutionIdentity(TestIdentities.TENANT_A, "join-test", "v1",
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID());
    }

    private JoinCoordinator coordinator(JoinStore store) {
        return coordinator(store, identity());
    }

    /** One {@code all} join over {@code b0} and {@code b1}, so a single failure makes it unreachable. */
    private JoinCoordinator coordinator(JoinStore store, ExecutionMonitor.ExecutionIdentity identity) {
        var spec = new JoinSpec("join", List.of("b0", "b1"), 2, null);
        return new JoinCoordinator(store, engine.scheduler(), monitor, identity, Map.of("join", spec),
                java.time.Clock.systemUTC());
    }
}
