package ai.ravenroot.core.persistence;

import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryExecutionStoreJournalRetentionTest {
    @Test void unrepresentableCutoffKeepsEarliestEventButStillPurgesExpiredInbox() {
        assertCompaction(Duration.ofSeconds(Long.MAX_VALUE), 0L);
    }

    @Test void representableCutoffRemainsInclusiveAtInstantMin() {
        assertCompaction(Duration.ofNanos(2), 1L);
    }

    private static void assertCompaction(Duration journalRetention, long expectedDiscarded) {
        Duration huge = Duration.ofSeconds(Long.MAX_VALUE);
        var policy = new ExecutionStorePolicy(Duration.ofSeconds(1), 1024, Duration.ZERO,
                journalRetention, 7, huge, huge);
        var clock = new MutableClock(Instant.MIN);
        try (var store = new InMemoryExecutionStore(clock, policy, HumanTaskPolicy.DEFAULTS)) {
            var key = new ExecutionKey("tenant", UUID.randomUUID());
            UUID traversal = UUID.randomUUID();
            var instance = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                    Map.of(traversal, new Traversal(traversal, "start", TraversalStatus.ACCEPTED, Map.of())));
            await(store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                    .apply(new ExecutionTransition.ProcessCreated(instance, new GraphVersionPin("graph-v1")))
                    .publish(EventEnvelope.of(UUID.randomUUID(), "tenant", "started", key.processInstanceId(),
                            traversal, null, null, null, "request", "graph-v1", clock.instant(),
                            OpaquePayload.empty("application/json"))).build()));
            long offset = await(store.readJournal("tenant", 0, 1)).get(0).journalOffset();
            await(store.advanceOutboxCursor(await(store.outboxCursor("tenant", "consumer")), offset));
            await(store.recordInboxDelivery("tenant", "consumer", UUID.randomUUID(), Duration.ofNanos(1)));
            clock.advance(Duration.ofNanos(2));
            assertEquals(expectedDiscarded, await(store.compactJournal("tenant")));
            assertEquals(0L, await(store.inboxRecordCount("tenant")));
            assertEquals(expectedDiscarded == 0 ? 1L : 2L, await(store.journalRetainedFrom("tenant")));
            if (expectedDiscarded == 0) assertEquals(1, await(store.readJournal("tenant", 0, 1)).size());
        }
    }

    private static <T> T await(CompletionStage<T> value) { return value.toCompletableFuture().join(); }
}
