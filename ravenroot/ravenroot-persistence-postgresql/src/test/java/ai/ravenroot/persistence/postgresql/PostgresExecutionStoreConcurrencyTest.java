package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.persistence.TimerSchedule;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static ai.ravenroot.persistence.postgresql.PostgresExecutionStoreFixtures.await;
import static ai.ravenroot.persistence.postgresql.PostgresExecutionStoreFixtures.creationBatch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lost updates this adapter exists to make impossible, driven concurrently.
 *
 * <p>Every assertion here passes trivially against a store whose read-then-write sequences were copied
 * from the single-host adapter and run one caller at a time, which is exactly why the conformance suite
 * cannot see them: it is single-threaded, so a revision computed from a stale read is always computed
 * from a current one. These tests put several callers on the same row at the same instant, where a
 * decision derived from a value that has since moved produces an answer nothing else in the suite
 * would notice.</p>
 *
 * <p>Each of them is stated as a count rather than as a probability. "The final revision is exactly
 * one per writer" fails deterministically when two writers derive the same next revision, whereas
 * "no exception was thrown" would pass while silently discarding one of them.</p>
 */
class PostgresExecutionStoreConcurrencyTest {

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final int WRITERS = 8;

    /**
     * A loaded aggregate's revision describes the state beside it, even while a writer is advancing it.
     *
     * <p>This is the read half of the same problem, and it is the one that looks safe. A fold reads the
     * instance's revision and then its traversals, invocations, causal edges and attempts. Under
     * {@code READ COMMITTED} each of those statements takes its own snapshot <em>whether or not a
     * transaction is open</em>, so the natural implementation returns a
     * {@link StoredProcessInstance} pairing a revision with a state that has since moved past it —
     * roughly one load in seven under this test's contention, measured before the fix. Nothing throws;
     * the caller simply receives a snapshot that never existed.</p>
     *
     * <p>The invariant is arithmetic rather than probabilistic: this instance gains exactly one
     * traversal per write, so a load reporting revision {@code r} must show exactly {@code r}
     * traversals. A torn read shows more, because the meta row is read first and the rows after it.</p>
     */
    @Test
    void aLoadedAggregateAgreesWithItsOwnRevisionWhileAWriterAdvancesIt() throws Exception {
        String storeId = "concurrency-load-" + UUID.randomUUID();
        var key = new ExecutionKey("acme", UUID.randomUUID());
        var clock = new MutableClock(EPOCH);

        try (var store = new PostgresExecutionStore(PostgresTestDatabase.dataSourceFor(storeId), clock)) {
            await(store.apply(creationBatch(key, UUID.randomUUID())));

            var stop = new java.util.concurrent.atomic.AtomicBoolean();
            var writer = CompletableFuture.runAsync(() -> {
                while (!stop.get()) {
                    UUID added = UUID.randomUUID();
                    store.apply(ExecutionBatch.to(key)
                            .expecting(RevisionExpectation.any())
                            .apply(new ExecutionTransition.TraversalAdded(new Traversal(added,
                                    "ingress", TraversalStatus.ACCEPTED, java.util.Map.of())))
                            .build()).toCompletableFuture().join();
                }
            });

            var torn = new ArrayList<String>();
            int reads = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                StoredProcessInstance loaded = await(store.load(key));
                reads++;
                int traversals = loaded.state().traversals().size();
                if (traversals != loaded.revision()) {
                    torn.add("revision=" + loaded.revision() + " traversals=" + traversals);
                }
            }
            stop.set(true);
            writer.get(1, TimeUnit.MINUTES);

            // A floor rather than "at least one", because this test can only find a torn read by
            // performing enough of them. On a loaded runner a single read would satisfy the weaker
            // assertion and the test would pass having proved nothing, which is the failure mode a
            // concurrency probe is most likely to have and least likely to show.
            assertTrue(reads >= 50, "only " + reads + " reads completed in the window, which is too "
                    + "few for their agreement to mean anything");
            assertTrue(torn.isEmpty(), torn.size() + " of " + reads
                    + " loads returned an aggregate whose revision does not describe its state, so the "
                    + "fold saw more than one committed snapshot: " + torn.subList(0, Math.min(5, torn.size())));
        }
    }

    /**
     * Concurrent writers to one instance each advance the revision by one, and none is overwritten.
     *
     * <p>This is the row lock, stated as arithmetic. {@code apply} reads the instance's revision and
     * writes {@code revision + 1}; without {@code SELECT ... FOR UPDATE} two writers read the same
     * value, both write the same successor, and one batch's rows are silently replaced by the other's —
     * both callers are told they succeeded, and the instance carries one write instead of two. The
     * final revision is the only place that shows.</p>
     *
     * <p>Each writer schedules its own timer rather than transitioning the status, so the count of
     * surviving side effects is a second, independent witness: a lost update loses a timer row too, and
     * a store that somehow kept the revisions but dropped a write would fail on the timers instead.</p>
     */
    @Test
    void concurrentWritersToOneInstanceEachAdvanceTheRevisionByExactlyOne() throws Exception {
        String storeId = "concurrency-revision-" + UUID.randomUUID();
        var key = new ExecutionKey("acme", UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        var clock = new MutableClock(EPOCH);

        try (var store = new PostgresExecutionStore(PostgresTestDatabase.dataSourceFor(storeId), clock)) {
            StoredProcessInstance created = await(store.apply(creationBatch(key, traversalId)));
            assertEquals(1L, created.revision());

            var start = new CountDownLatch(1);
            var timers = new ArrayList<UUID>();
            var writes = new ArrayList<CompletableFuture<StoredProcessInstance>>();
            for (int writer = 0; writer < WRITERS; writer++) {
                UUID timerId = UUID.randomUUID();
                timers.add(timerId);
                writes.add(CompletableFuture.supplyAsync(() -> {
                    awaitLatch(start);
                    // RevisionExpectation.any(), deliberately. An `exactly` expectation would make the
                    // losers fail with ConcurrencyConflict, and the store would be excused from
                    // serializing anything: the point is that a batch which does NOT name a revision
                    // still cannot have one derived from stale state.
                    return store.apply(ExecutionBatch.to(key)
                            .expecting(RevisionExpectation.any())
                            .scheduleTimer(new TimerSchedule(timerId, EPOCH, traversalId, null,
                                    OpaquePayload.of("tick".getBytes(StandardCharsets.UTF_8),
                                            "text/plain")))
                            .build()).toCompletableFuture().join();
                }));
            }
            start.countDown();
            CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).get(2, TimeUnit.MINUTES);

            Set<Long> revisions = new HashSet<>();
            for (CompletableFuture<StoredProcessInstance> write : writes) {
                revisions.add(write.join().revision());
            }
            assertEquals(WRITERS, revisions.size(),
                    "two writers that were handed the same revision both believe they wrote it, and one "
                            + "of the two batches is gone: " + revisions);
            assertEquals(1L + WRITERS, await(store.load(key)).revision());

            List<PendingWork.TimerDue> due = await(store.claimDueTimers(key.tenantId(), "collector",
                    WRITERS * 2, java.time.Duration.ofSeconds(30)));
            assertEquals(Set.copyOf(timers),
                    due.stream().map(PendingWork::workItemId).collect(java.util.stream.Collectors.toSet()),
                    "every writer's own side effect survived, which a lost update would not allow");
        }
    }

    /**
     * Concurrent creations of one instance produce exactly one instance and one winner.
     *
     * <p>An upsert would let all of them succeed and the last one written would be the instance, which
     * is a lost creation wearing the shape of a successful one — every caller told it created the
     * process, and one process existing. The insert refuses instead, and the losers are told
     * {@code AlreadyExists} with the revision that actually stands, which is a fact they can act on.</p>
     */
    @Test
    void concurrentCreationsOfOneInstanceProduceOneWinnerAndTheRestAreRefused() throws Exception {
        String storeId = "concurrency-create-" + UUID.randomUUID();
        var key = new ExecutionKey("acme", UUID.randomUUID());
        var clock = new MutableClock(EPOCH);

        try (var store = new PostgresExecutionStore(PostgresTestDatabase.dataSourceFor(storeId), clock)) {
            var start = new CountDownLatch(1);
            var attempts = new ArrayList<CompletableFuture<Object>>();
            for (int writer = 0; writer < WRITERS; writer++) {
                attempts.add(CompletableFuture.supplyAsync(() -> {
                    awaitLatch(start);
                    try {
                        return outcome(store.apply(creationBatch(key, UUID.randomUUID())));
                    } catch (CompletionException thrown) {
                        ExecutionStoreException failure = ExecutionStoreException.unwrap(thrown);
                        assertNotNull(failure, "adapters must not leak non-store exceptions: " + thrown);
                        return failure.failure();
                    }
                }));
            }
            start.countDown();
            CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).get(2, TimeUnit.MINUTES);

            int created = 0;
            for (CompletableFuture<Object> attempt : attempts) {
                Object result = attempt.join();
                if (result instanceof StoredProcessInstance instance) {
                    created++;
                    assertEquals(1L, instance.revision());
                } else {
                    var refused = assertInstanceOf(ExecutionStoreFailure.AlreadyExists.class, result,
                            "a creation that lost the race must be told the instance exists, not handed "
                                    + "a success for a creation somebody else performed");
                    assertEquals(1L, refused.revision());
                }
            }
            assertEquals(1, created, "exactly one creation may win");
            assertEquals(1L, await(store.load(key)).revision());
        }
    }

    /**
     * Two workers polling the same tenant at the same instant take disjoint work.
     *
     * <p>The claim loop is the operation the single-host adapter performs by enumerating every instance
     * of the tenant, which is affordable only because one process holds the whole database's write lock
     * anyway. Here the candidate rows are selected and locked in one statement with
     * {@code FOR UPDATE ... SKIP LOCKED}, so a second poller steps over what the first is holding rather
     * than waiting for it — and, more to the point, rather than reading the same rows and issuing a
     * second lease against a token the first poller is about to rotate.</p>
     *
     * <p>Delivery is at-least-once by contract, so a duplicate is not a violation in general. It is one
     * <em>here</em>, because both polls are inside the visibility window of the other: an item handed to
     * both means the exclusion did not hold at all.</p>
     */
    @Test
    void twoWorkersPollingAtOnceTakeDisjointWork() throws Exception {
        String storeId = "concurrency-claim-" + UUID.randomUUID();
        var clock = new MutableClock(EPOCH);
        String tenantId = "acme";
        int instances = 12;

        try (var store = new PostgresExecutionStore(PostgresTestDatabase.dataSourceFor(storeId), clock)) {
            var expected = new HashSet<UUID>();
            for (int index = 0; index < instances; index++) {
                var key = new ExecutionKey(tenantId, UUID.randomUUID());
                UUID traversalId = UUID.randomUUID();
                UUID timerId = UUID.randomUUID();
                StoredProcessInstance created = await(store.apply(creationBatch(key, traversalId)));
                await(store.apply(ExecutionBatch.to(key)
                        .expecting(RevisionExpectation.exactly(created.revision()))
                        .scheduleTimer(new TimerSchedule(timerId, EPOCH, traversalId, null,
                                OpaquePayload.of("tick".getBytes(StandardCharsets.UTF_8), "text/plain")))
                        .build()));
                expected.add(timerId);
            }

            var start = new CountDownLatch(1);
            var first = CompletableFuture.supplyAsync(() -> {
                awaitLatch(start);
                return await(store.claimDueTimers(tenantId, "worker-one", instances,
                        java.time.Duration.ofSeconds(30)));
            });
            var second = CompletableFuture.supplyAsync(() -> {
                awaitLatch(start);
                return await(store.claimDueTimers(tenantId, "worker-two", instances,
                        java.time.Duration.ofSeconds(30)));
            });
            start.countDown();
            CompletableFuture.allOf(first, second).get(2, TimeUnit.MINUTES);

            Set<UUID> byFirst = idsOf(first.join());
            Set<UUID> bySecond = idsOf(second.join());
            var overlap = new HashSet<>(byFirst);
            overlap.retainAll(bySecond);
            assertTrue(overlap.isEmpty(),
                    "the same timer was handed to two workers inside one visibility window, so neither "
                            + "the row lock nor the lease excluded the other: " + overlap);

            var togetherWith = new HashSet<>(byFirst);
            togetherWith.addAll(bySecond);
            assertTrue(expected.containsAll(togetherWith), "no work was invented");
            assertTrue(!togetherWith.isEmpty(), "at least one of the two polls must have taken work");
        }
    }

    private static Set<UUID> idsOf(List<PendingWork.TimerDue> claimed) {
        return claimed.stream().map(PendingWork::workItemId)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static Object outcome(CompletionStage<StoredProcessInstance> stage) {
        return stage.toCompletableFuture().join();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            // The starting gun. Without it the writers are staggered by however long it takes to submit
            // them, and a store that serializes nothing would still pass most of the time.
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
