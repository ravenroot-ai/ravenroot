package ai.ravenroot.core.runtime;

import ai.ravenroot.core.persistence.InMemoryJoinStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two children of one predecessor are two branches, not one (ADR 0024 §4).
 *
 * <h2>What this exists to catch</h2>
 * <p>A branch used to be identified by its predecessor's node id. Under data-parallel fan-out that
 * makes every child of one predecessor present the same identity, and the consequences are silent
 * rather than loud: {@code JoinCoordinator.payloads} is keyed by branch so child 2 overwrites child 1;
 * {@code JoinRecord.branches} is keyed by branch so N children collapse to one entry; and the second
 * child is classified {@code Discarded(DUPLICATE)}, which the codebase documents as "never an error".
 * N-1 real results would vanish with nothing failing anywhere.
 *
 * <p>So the question this answers is the one worth asking of any representation change: <b>what turns
 * red if the identity collides again?</b> Mutating {@link BranchId#value()} to drop the child ordinal
 * — the exact regression — makes {@link #twoChildrenOfOnePredecessorAreNotOneBranch()} fail on the
 * duplicate discard, and {@link #achildBranchIsDistinguishableFromItsPredecessorsWholeOutput()} fail
 * on equality.
 *
 * <h2>Why the join here cannot be satisfied</h2>
 * <p>Three declared branches and a quorum of three, so neither arrival can meet it and both must
 * report {@code Wait}. That is deliberate: it isolates <em>identity</em> from <em>accounting</em>.
 * Quorum arithmetic still subtracts against a static branch count derived from topology, and teaching
 * it about runtime cardinality is a separate change. A test that let the join
 * proceed would be asserting accounting it has no business asserting yet, and would fail for the
 * wrong reason when that accounting changes.
 *
 * <p>Nothing in {@code src/main} mints a child branch yet. These arrivals are constructed directly,
 * which proves that the representation can distinguish them before a producer depends on that
 * capability.
 */
class BranchIdentityCollisionTest {

    private final InMemoryJoinStore store = new InMemoryJoinStore();
    private final ExecutionMonitor monitor = new ExecutionMonitor();
    private final JoinTestEngine engine = new JoinTestEngine();

    @AfterEach
    void close() {
        engine.close();
        store.close();
    }

    /** The identity itself: a child is not its predecessor, and children are not each other. */
    @Test
    void achildBranchIsDistinguishableFromItsPredecessorsWholeOutput() {
        BranchId whole = BranchId.of("a");
        BranchId first = BranchId.child("a", 0);
        BranchId second = BranchId.child("a", 1);

        assertNotEquals(whole, first, "a child of a is not the whole output of a");
        assertNotEquals(first, second, "two children of one predecessor are two branches");
        assertNotEquals(first.value(), second.value(),
                "the stored form must separate them too, because that is what the coordinator and "
                        + "the join record are keyed by. Got: " + first.value() + " and " + second.value());

        // The wire form of a whole-output branch is unchanged, which is what keeps every persisted
        // JoinRecord and every JoinSpec branch list byte-identical while no expansion exists.
        assertEquals("a", whole.value(), "a branch with no child must still serialise as its node id");
        assertTrue(first.value().startsWith("a"), "a child's form stays attributable to its predecessor");
    }

    /**
     * Ordering is numeric on the ordinal, not lexicographic on the serialised form.
     *
     * <p>{@code merge} sorts arrivals by branch to give a fan-in a deterministic result order. Sorting
     * on the string would put child 10 before child 2, so a parallel-map result would silently leave
     * item order for large inputs and only for large inputs — the kind of defect that passes every
     * small test. The ordering promised to authors is undecided; the comparator must not decide it
     * accidentally through string ordering.
     */
    @Test
    void childBranchesOrderNumericallyRatherThanLexicographically() {
        var ordered = new java.util.ArrayList<>(List.of(
                BranchId.child("a", 10), BranchId.child("a", 2), BranchId.child("a", 1)));
        java.util.Collections.sort(ordered);

        assertEquals(List.of(BranchId.child("a", 1), BranchId.child("a", 2), BranchId.child("a", 10)),
                ordered, "child 10 must sort after child 2, which string ordering would not do");
    }

    /**
     * The coordinator treats two children as two branches: two recorded, neither discarded.
     *
     * <p>This is the regression detector. With a colliding identity the second arrival finds the first
     * already recorded, the branch content cannot grow, the verdict stays {@code Wait}, and the
     * coordinator answers {@code Discarded(DUPLICATE)} — losing a real result while reporting nothing.
     *
     * <p>Observed through the store rather than by blocking on the returned stage, and that is not a
     * convenience: {@code arrive} <em>parks</em> its stage on a {@code Wait}, completing it only when
     * the join finally settles. Waiting on it here would hang on the healthy path and pass only on the
     * broken one, which is precisely backwards.
     */
    @Test
    void twoChildrenOfOnePredecessorAreNotOneBranch() throws Exception {
        JoinCoordinator coordinator = coordinator();

        var first = coordinator.arrive("join",
                new JoinArrival(BranchId.child("a", 0), "from-0", Map.of(), Set.of()));
        var second = coordinator.arrive("join",
                new JoinArrival(BranchId.child("a", 1), "from-1", Map.of(), Set.of()));

        assertEquals(2, awaitBranchCount(2),
                "two children of one predecessor are two recorded branches. One means the identities "
                        + "collapsed, which loses a result and raises nothing.");

        assertFalse(isDuplicateDiscard(second),
                "the second child must not be discarded as a duplicate of the first");
        assertFalse(isDuplicateDiscard(first), "nor the first of the second");
    }

    private static boolean isDuplicateDiscard(java.util.concurrent.CompletionStage<JoinDecision> stage) {
        var future = stage.toCompletableFuture();
        // A parked stage is the healthy answer: the branch is recorded and the join still waits.
        return future.isDone() && !future.isCompletedExceptionally()
                && future.join() instanceof JoinDecision.Discarded discarded
                && discarded.reason() == JoinDecision.Discarded.Reason.DUPLICATE;
    }

    /** Bounded wait for the store to hold {@code expected} branches; returns what it actually holds. */
    private int awaitBranchCount(int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int observed = branchCount();
        while (System.nanoTime() < deadline && observed != expected) {
            Thread.sleep(20);
            observed = branchCount();
        }
        return observed;
    }

    private int branchCount() throws Exception {
        return store.load(new ai.ravenroot.api.persistence.JoinKey(
                        TestIdentities.TENANT_A.tenantId(), processInstanceId, traversalId, "join"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS)
                .map(record -> record.branches().size())
                .orElse(0);
    }

    // ------------------------------------------------------------------ fixtures

    private final UUID processInstanceId = UUID.randomUUID();
    private final UUID traversalId = UUID.randomUUID();

    private JoinCoordinator coordinator() {
        var identity = new ExecutionMonitor.ExecutionIdentity(TestIdentities.TENANT_A, "branch-id-test",
                "v1", processInstanceId, traversalId);
        // Three branches, quorum three: neither arrival below can satisfy it, so this isolates
        // identity from the static quorum arithmetic.
        var spec = new JoinSpec("join", List.of("a", "b", "c"), 3, null);
        return new JoinCoordinator(store, engine.scheduler(), monitor, identity, Map.of("join", spec),
                Clock.systemUTC());
    }

    private static JoinDecision arrive(JoinCoordinator coordinator, BranchId branchId) throws Exception {
        return coordinator.arrive("join",
                        new JoinArrival(branchId, "from-" + branchId.value(), Map.of(), Set.of()))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
