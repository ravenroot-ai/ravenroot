package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditRecord;
import ai.ravenroot.api.persistence.DurableExecutionResult;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionResultNodes;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.core.audit.InMemoryAuditTrail;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Priority-three coverage: a result purge must leave the same kind of durable evidence
 * {@code AuditTrail.redact} leaves for its own retention operation -- naming the tenant, the count
 * removed and the operator -- so a gap in {@code execution_result} is distinguishable from silent,
 * unaccounted-for loss.
 */
class AuditedExecutionResultPurgeTest {

    private static final String TENANT = "tenant-a";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private static InMemoryExecutionStore store(MutableClock clock) {
        return new InMemoryExecutionStore(clock, Duration.ofMinutes(5), 1024 * 1024,
                Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofHours(2), Duration.ofHours(1));
    }

    private static ExecutionKey createInstance(InMemoryExecutionStore store, UUID traversalId) {
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(
                        new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                                Map.of(traversalId, new Traversal(traversalId, "start",
                                        TraversalStatus.ACCEPTED, Map.of()))),
                        new GraphVersionPin("graph-v1")))
                .build()).toCompletableFuture().join();
        return key;
    }

    @Test
    void aSuccessfulPurgeAppendsAnAdministrationTombstoneNamingTheCount() {
        var clock = new MutableClock(START);
        try (var store = store(clock); var trail = new InMemoryAuditTrail()) {
            UUID traversalId = UUID.randomUUID();
            ExecutionKey key = createInstance(store, traversalId);
            DurableExecutionResult recorded = store.recordExecutionResult(
                    DurableExecutionResult.of(key, traversalId, new GraphVersionPin("graph-v1"),
                            ProcessInstanceStatus.COMPLETED, null, START, START.plusSeconds(1),
                            Map.of("answer", 42L), ExecutionResultNodes.empty(), null,
                            store.maxExecutionResultPayloadBytes()))
                    .toCompletableFuture().join();
            clock.set(recorded.retainedUntil());

            var purge = new AuditedExecutionResultPurge(store, trail);
            long purged = purge.purge(TENANT, "operator-alice");

            assertEquals(1, purged);
            List<AuditRecord> records = trail.read(TENANT, 0, 10);
            assertEquals(1, records.size(), () -> "expected exactly one tombstone: " + records);
            AuditRecord tombstone = records.get(0);
            assertEquals(AuditCategory.ADMINISTRATION, tombstone.envelope().category());
            assertEquals(AuditOutcome.ALLOWED, tombstone.envelope().outcome());
            assertEquals(AuditedExecutionResultPurge.ACTION, tombstone.envelope().action());
            assertEquals(TENANT, tombstone.envelope().tenantId());
            assertEquals("operator-alice", tombstone.envelope().principal());
            assertEquals(TENANT, tombstone.envelope().resourceId(),
                    "a purge is tenant-wide, so the resource id it names is the tenant itself");
            assertTrue(tombstone.envelope().reason().contains("purged=1"), tombstone.envelope().reason());
        }
    }

    @Test
    void aPurgeThatRemovesNothingStillAppendsATombstoneSayingSo() {
        var clock = new MutableClock(START);
        try (var store = store(clock); var trail = new InMemoryAuditTrail()) {
            var purge = new AuditedExecutionResultPurge(store, trail);
            long purged = purge.purge(TENANT, "operator-alice");

            assertEquals(0, purged);
            List<AuditRecord> records = trail.read(TENANT, 0, 10);
            assertEquals(1, records.size());
            assertTrue(records.get(0).envelope().reason().contains("purged=0"));
        }
    }

    /**
     * A store that cannot record results at all refuses the purge with
     * {@link ExecutionStoreFailure.CapabilityNotSupported}; the attempt is still worth a durable
     * record, so the tombstone is appended before the failure is rethrown, not skipped because
     * nothing was removed.
     */
    @Test
    void aRefusedPurgeStillAppendsAFailedTombstoneBeforeThrowing() {
        var clock = new MutableClock(START);
        try (var inner = store(clock);
             var store = new NoExecutionResultsExecutionStore(inner);
             var trail = new InMemoryAuditTrail()) {
            var purge = new AuditedExecutionResultPurge(store, trail);

            // AuditedExecutionResultPurge#await unwraps CompletionException down to the plain
            // ExecutionStoreException, matching this codebase's established convention (see
            // DurableExecutionResults#await) -- so that is the type a caller actually observes.
            assertThrows(ExecutionStoreException.class, () -> purge.purge(TENANT, "operator-alice"));

            List<AuditRecord> records = trail.read(TENANT, 0, 10);
            assertEquals(1, records.size());
            assertEquals(AuditOutcome.FAILED, records.get(0).envelope().outcome());
            assertTrue(records.get(0).envelope().reason().contains("purged=0"),
                    records.get(0).envelope().reason());
        }
    }
}
