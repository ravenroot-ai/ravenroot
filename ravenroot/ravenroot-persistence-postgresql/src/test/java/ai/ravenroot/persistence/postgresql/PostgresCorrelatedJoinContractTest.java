package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PostgresCorrelatedJoinContractTest extends ai.ravenroot.testkit.persistence.CorrelatedJoinContract {
    @Override protected ai.ravenroot.api.persistence.ExecutionStore createStore(String id, java.time.Clock clock,
            ai.ravenroot.api.correlation.BusinessHistoryPolicy policy) {
        return new PostgresExecutionStore(PostgresTestDatabase.dataSourceFor(id), clock, PostgresStoreConfig.defaults().withBusinessHistories(policy));
    }
    @Test void successfulCloseReadsJoinsCommittedByTheWriterWhoseHistoryLockItAwaited() throws Exception {
        String id = UUID.randomUUID().toString(), application = "closure-cas-" + UUID.randomUUID();
        var raw = PostgresTestDatabase.dataSourceFor(id);
        var closeSource = PostgresTestDatabase.dataSourceFor(id, application);
        var clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var scope = new BusinessScope("tenant", "deployment", "version");
        var context = new ApplicationCorrelationContext("creation-race", Optional.empty(), Optional.empty(), "round", Optional.empty());
        UUID process = UUID.randomUUID(), traversal = UUID.randomUUID();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var armed = new AtomicBoolean();
        var boundary = new CommitBoundary() {
            @Override public void beforeCommit() {
                if (armed.compareAndSet(true, false)) {
                    entered.countDown();
                    try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                }
            }
        };
        try (var writer = new PostgresExecutionStore(raw, clock, PostgresStoreConfig.defaults(), boundary);
             var closer = new PostgresExecutionStore(closeSource, clock)) {
            writer.recordCorrelatedTraversal(new CorrelatedTraversal(scope, context, process, traversal, "source", CorrelatedTraversal.Cause.SOURCE_EVENT,
                    TraversalStatus.ACCEPTED, null, clock.instant(), clock.instant(), null), CorrelationMapping.Mode.ACCEPT_OR_CREATE).toCompletableFuture().get();
            writer.settleCorrelatedTraversal("tenant", process, traversal, TraversalStatus.COMPLETED, null).toCompletableFuture().get();
            var history = writer.businessHistory(scope, context.applicationCorrelationId()).toCompletableFuture().get().orElseThrow();
            var join = CorrelatedJoinRecord.open(new CorrelatedJoinKey(scope, context.applicationCorrelationId(), "join", "round"), Set.of("part-1"), null, true,
                    new JoinCause(process, traversal, UUID.randomUUID(), UUID.randomUUID(), new SecurityContext("request", "tenant", "owner", PrincipalType.WORKLOAD, "test")),
                    clock.instant(), clock.instant().plusSeconds(60));
            armed.set(true);
            var creation = writer.compareAndSetCorrelatedJoin(join).toCompletableFuture();
            assertTrue(entered.await(10, TimeUnit.SECONDS), "join and its history lock must be held before close starts");
            var close = closer.closeBusinessHistory(scope, context.applicationCorrelationId(), history.revision(), BusinessHistory.Lifecycle.COMPLETED).toCompletableFuture();
            try {
                // Observe this exact competing connection at the real row-lock boundary. This
                // is metadata observation, not an effect retry or a timing-based race guess.
                boolean blocked = false; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                try (var observer = raw.getConnection(); var statement = observer.prepareStatement(
                        "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = ? AND wait_event_type = 'Lock' AND query LIKE 'UPDATE business_history%')")) {
                    statement.setString(1, application);
                    while (!blocked && System.nanoTime() < deadline) {
                        try (var rows = statement.executeQuery()) { rows.next(); blocked = rows.getBoolean(1); }
                    }
                }
                assertTrue(blocked, "the original close must actually await the writer, not start after its commit");
            } finally { release.countDown(); }
            assertEquals(join, creation.get(10, TimeUnit.SECONDS));
            var refused = assertThrows(ExecutionException.class, () -> close.get(10, TimeUnit.SECONDS));
            assertInstanceOf(BusinessHistoryException.class, refused.getCause());
            assertEquals(BusinessHistoryException.Reason.REVISION_CONFLICT, ((BusinessHistoryException) refused.getCause()).reason());
            assertEquals(history, writer.businessHistory(scope, context.applicationCorrelationId()).toCompletableFuture().get().orElseThrow());
            assertEquals(join, writer.correlatedJoin(join.key()).toCompletableFuture().get().orElseThrow());
        } finally { release.countDown(); }
    }
}
