package ai.ravenroot.core.persistence;

import ai.ravenroot.api.persistence.JoinBranchOutcome;
import ai.ravenroot.api.persistence.JoinKey;
import ai.ravenroot.api.persistence.JoinPhase;
import ai.ravenroot.api.persistence.JoinRecord;
import ai.ravenroot.api.persistence.JoinStoreException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The compare-and-set, retention and tenant-scoping behaviour every {@code JoinStore} must have. */
class InMemoryJoinStoreTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final InMemoryJoinStore store = new InMemoryJoinStore();
    private final JoinKey key = new JoinKey("tenant-a", UUID.randomUUID(), UUID.randomUUID(), "join");

    @AfterEach
    void closeStore() {
        store.close();
    }

    @Test
    void declaresItselfNonDurableSoRestartAssertionsAreSkippedRatherThanFaked() {
        assertFalse(store.durable());
    }

    @Test
    void answersEmptyForAJoinThatWasNeverWritten() throws Exception {
        assertTrue(await(store.load(key)).isEmpty());
    }

    @Test
    void acceptsACreationAtTheFirstRevisionAndRejectsASecondOne() throws Exception {
        var created = await(store.compareAndSet(open("b0")));
        assertEquals(1, created.revision());

        var conflict = assertThrows(ExecutionException.class, () -> await(store.compareAndSet(open("b1"))));
        assertEquals(JoinStoreException.Reason.CONCURRENCY_CONFLICT, ((JoinStoreException) conflict.getCause()).reason());
    }

    @Test
    void rejectsAWriteBuiltOnAStaleRevision() throws Exception {
        JoinRecord first = await(store.compareAndSet(open("b0")));
        await(store.compareAndSet(first.next(first.plus("b1", JoinBranchOutcome.ARRIVED), JoinPhase.OPEN, null)));

        var stale = first.next(first.plus("b2", JoinBranchOutcome.ARRIVED), JoinPhase.OPEN, null);
        var conflict = assertThrows(ExecutionException.class, () -> await(store.compareAndSet(stale)));
        assertEquals(JoinStoreException.Reason.CONCURRENCY_CONFLICT,
                ((JoinStoreException) conflict.getCause()).reason());
        assertEquals(JoinStoreException.Reason.CONCURRENCY_CONFLICT.retryability(),
                ai.ravenroot.api.persistence.Retryability.RETRY_AFTER_REREAD);
    }

    /** The isolation property the join depends on: many contenders, exactly one accepted write. */
    @Test
    void admitsExactlyOneOfManyConcurrentWritersAtTheSameRevision() throws Exception {
        int contenders = 16;
        var start = new CountDownLatch(1);
        var accepted = new AtomicInteger();
        var conflicts = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(contenders);
        try {
            var done = new CountDownLatch(contenders);
            for (int index = 0; index < contenders; index++) {
                String branch = "b" + index;
                pool.execute(() -> {
                    try {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        store.compareAndSet(open(branch)).toCompletableFuture().join();
                        accepted.incrementAndGet();
                    } catch (RuntimeException rejected) {
                        conflicts.incrementAndGet();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, accepted.get(), "exactly one writer may win a revision");
        assertEquals(contenders - 1, conflicts.get());
        assertEquals(1, store.totalRecordCount());
    }

    @Test
    void discardsARecordAndReportsWhetherOneWasThere() throws Exception {
        await(store.compareAndSet(open("b0")));
        assertTrue(await(store.discard(key)));
        assertFalse(await(store.discard(key)));
        assertEquals(0, store.totalRecordCount());
    }

    @Test
    void purgesOnlyTerminalRecordsSettledBeforeTheCutoff() throws Exception {
        JoinRecord created = await(store.compareAndSet(open("b0")));
        await(store.compareAndSet(created.next(created.branches(), JoinPhase.SATISFIED, NOW)));

        assertEquals(0, await(store.purgeSettledBefore("tenant-a", NOW)),
                "a record settled exactly at the cutoff is not before it");
        assertEquals(1, await(store.purgeSettledBefore("tenant-a", NOW.plusSeconds(1))));
        assertEquals(0, store.totalRecordCount());
    }

    @Test
    void refusesABlankTenant() {
        assertThrows(JoinStoreException.class, () -> store.recordCount(" "));
    }

    @Test
    void rejectsARecordWhoseSettledInstantContradictsItsPhase() {
        assertThrows(IllegalArgumentException.class,
                () -> JoinRecord.opening(key, NOW).next(Map.of(), JoinPhase.SATISFIED, null));
        assertThrows(IllegalArgumentException.class,
                () -> JoinRecord.opening(key, NOW).next(Map.of(), JoinPhase.OPEN, NOW));
    }

    @Test
    void keepsBranchesInBranchIdOrderWhateverOrderTheyWereWrittenIn() {
        var record = JoinRecord.opening(key, NOW).next(
                new java.util.LinkedHashMap<>(Map.of()) {{
                    put("b2", JoinBranchOutcome.ARRIVED);
                    put("b0", JoinBranchOutcome.FAILED);
                    put("b1", JoinBranchOutcome.ARRIVED);
                }}, JoinPhase.OPEN, null);

        assertEquals(List.of("b0", "b1", "b2"), List.copyOf(record.branches().keySet()));
        assertEquals(2, record.countOf(JoinBranchOutcome.ARRIVED));
        assertEquals(1, record.countOf(JoinBranchOutcome.FAILED));
    }

    private JoinRecord open(String branch) {
        return JoinRecord.opening(key, NOW).next(Map.of(branch, JoinBranchOutcome.ARRIVED), JoinPhase.OPEN, null);
    }

    private static <T> T await(java.util.concurrent.CompletionStage<T> stage) throws Exception {
        return CompletableFuture.completedFuture(stage).thenCompose(inner -> inner)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
