package ai.ravenroot.api.persistence;

import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.TraversalStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of the inventory contract that are decided in the port rather than by an adapter: the
 * disposition precedence, the cursor codec, and what a query does and does not validate.
 *
 * <p>These belong here because both adapters share them by construction. A precedence re-derived in
 * each adapter would eventually be two precedences, and a cursor encoded twice would eventually be
 * two encodings — and the failure mode of the second is a page-one restart that looks exactly like an
 * empty result.</p>
 */
class InventoryContractTest {

    private static final String TENANT = "acme";

    @Test
    void parkedOutranksEveryOtherDispositionIncludingTerminal() {
        // The case that makes the rank load-bearing: the instance is finished and an effect of unknown
        // outcome is not. A TERMINAL label would hide the only outstanding operator action -- and then
        // let retention delete the sole record of it.
        assertEquals(InventoryDisposition.PARKED,
                InventoryDisposition.ofProcess(ProcessInstanceStatus.FAILED, false, true));
        assertEquals(InventoryDisposition.PARKED,
                InventoryDisposition.ofProcess(ProcessInstanceStatus.RUNNING, true, true));
        assertEquals(InventoryDisposition.PARKED,
                InventoryDisposition.ofProcess(ProcessInstanceStatus.WAITING, false, true));
    }

    @Test
    void waitingOutranksInterruptedBecauseAWaitingInstanceHoldsNoLeaseByDesign() {
        assertEquals(InventoryDisposition.WAITING,
                InventoryDisposition.ofProcess(ProcessInstanceStatus.WAITING, false, false));
        assertEquals(InventoryDisposition.INTERRUPTED,
                InventoryDisposition.ofProcess(ProcessInstanceStatus.RUNNING, false, false));
        assertEquals(InventoryDisposition.ACTIVE,
                InventoryDisposition.ofProcess(ProcessInstanceStatus.RUNNING, true, false));
        assertEquals(InventoryDisposition.TERMINAL,
                InventoryDisposition.ofProcess(ProcessInstanceStatus.COMPLETED, false, false));
        assertEquals(InventoryDisposition.TERMINAL,
                InventoryDisposition.ofProcess(ProcessInstanceStatus.FAILED, true, false),
                "a terminal instance is terminal whatever a stale lease still says");
    }

    @Test
    void everyProcessStatusIsClassifiedAndTheLeaseIsWhatSeparatesActiveFromInterrupted() {
        for (ProcessInstanceStatus status : ProcessInstanceStatus.values()) {
            assertNotNull(InventoryDisposition.ofProcess(status, true, false));
            assertNotNull(InventoryDisposition.ofProcess(status, false, false));
        }
        // The disposition is derived precisely so that this pair can differ with no write between
        // them: a lease lapses by the passage of time, and a stored classification would have had no
        // transaction in which to correct itself.
        assertNotEquals(InventoryDisposition.ofProcess(ProcessInstanceStatus.RUNNING, true, false),
                InventoryDisposition.ofProcess(ProcessInstanceStatus.RUNNING, false, false));
    }

    @Test
    void aTraversalIsClassifiedAgainstItsOwnStatusAndTheInstancesLease() {
        assertEquals(InventoryDisposition.ACTIVE,
                InventoryDisposition.ofTraversal(TraversalStatus.RUNNING, true, false));
        assertEquals(InventoryDisposition.INTERRUPTED,
                InventoryDisposition.ofTraversal(TraversalStatus.RUNNING, false, false));
        assertEquals(InventoryDisposition.WAITING,
                InventoryDisposition.ofTraversal(TraversalStatus.WAITING, false, false));
        assertEquals(InventoryDisposition.TERMINAL,
                InventoryDisposition.ofTraversal(TraversalStatus.COMPLETED, true, false));
        assertEquals(InventoryDisposition.PARKED,
                InventoryDisposition.ofTraversal(TraversalStatus.COMPLETED, true, true));
    }

    @Test
    void thereIsNoPausedConstantBecauseNoDurablePauseStateExists() {
        // Stated as an assertion rather than only in prose: the day a durable pause lands, this fails
        // and whoever adds the constant is told, in one line, that the disposition being derived is
        // what lets them add it with no schema migration and no contract break.
        assertEquals(5, InventoryDisposition.values().length);
        for (InventoryDisposition value : InventoryDisposition.values()) {
            assertNotEquals("PAUSED", value.name());
        }
    }

    @Test
    void aCursorRoundTripsAndIsRefusedUnderAnotherTenant() {
        Instant createdAt = Instant.parse("2026-03-01T10:15:30.000000123Z");
        UUID id = UUID.fromString("9f1c1d2e-3a4b-4c5d-8e6f-0a1b2c3d4e5f");
        String cursor = InventoryCursor.encode(TENANT, createdAt, id);

        InventoryCursor.Position decoded = InventoryCursor.decode(TENANT, cursor);
        assertEquals(createdAt, decoded.createdAt(),
                "the nanosecond component survives, or a tie in a page boundary would resolve wrongly");
        assertEquals(id, decoded.processInstanceId());

        ExecutionStoreFailure refused = failureOf(() -> InventoryCursor.decode("globex", cursor));
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class, refused);
        assertFalse(refused.toString().contains(TENANT),
                "the rejection must not name the tenant the cursor was minted for");
    }

    @Test
    void aMalformedOrForeignVersionCursorIsRefusedRatherThanGuessedAt() {
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                failureOf(() -> InventoryCursor.decode(TENANT, "!!!not base64!!!")));
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                failureOf(() -> InventoryCursor.decode(TENANT, "")));
        String foreignVersion = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("rri0\0" + TENANT + "\0" + "1\0" + "0\0" + UUID.randomUUID())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                failureOf(() -> InventoryCursor.decode(TENANT, foreignVersion)),
                "a cursor from another ordering must not decode into a position in this one, which "
                        + "would skip or repeat rows with nothing to signal it");
    }

    @Test
    void aTenantIdContainingTheSeparatorStillRoundTrips() {
        // The delimiter is NUL for exactly this reason: a printable separator could occur inside an
        // opaque tenant id and split one field into two.
        String awkward = "tenant with spaces/and:punctuation";
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-03-01T00:00:00Z");
        assertEquals(id, InventoryCursor.decode(awkward,
                InventoryCursor.encode(awkward, createdAt, id)).processInstanceId());
    }

    @Test
    void theCursorPositionOrdersByCreatedAtThenIdBothDescending() {
        Instant later = Instant.parse("2026-03-01T00:00:02Z");
        Instant earlier = Instant.parse("2026-03-01T00:00:01Z");
        UUID middle = UUID.fromString("50000000-0000-4000-8000-000000000000");
        var position = new InventoryCursor.Position(later, middle);

        assertTrue(position.precedes(earlier, UUID.randomUUID()), "older rows come after");
        assertFalse(position.precedes(Instant.parse("2026-03-01T00:00:03Z"), UUID.randomUUID()),
                "newer rows sort before page one and are not reachable through a cursor");
        assertTrue(position.precedes(later, UUID.fromString("40000000-0000-4000-8000-000000000000")),
                "a tie is broken by the id, descending");
        assertFalse(position.precedes(later, UUID.fromString("60000000-0000-4000-8000-000000000000")));
        assertFalse(position.precedes(later, middle), "strictly after: the cursor row is not repeated");
    }

    @Test
    void aQueryNormalisesNullsAndLeavesEveryBoundToTheStore() {
        var query = new ProcessInventoryQuery(null, null, null, false, null, 0);
        assertEquals(java.util.Set.of(), query.statuses());
        assertEquals(Optional.empty(), query.cursor());
        assertEquals(0, query.limit(),
                "the limit is not validated here: the bound belongs to the store, so a conformance "
                        + "suite must be able to build the offending query in order to assert that "
                        + "every adapter rejects it");
    }

    @Test
    void theTerminalAxisAndTheStatusFilterComposeAsAConjunction() {
        var outstanding = ProcessInventoryQuery.outstanding(10);
        assertTrue(outstanding.admits(ProcessInstanceStatus.RUNNING));
        assertFalse(outstanding.admits(ProcessInstanceStatus.COMPLETED));
        assertFalse(outstanding.isSelfContradictory());

        var mixed = ProcessInventoryQuery.builder()
                .status(ProcessInstanceStatus.RUNNING)
                .status(ProcessInstanceStatus.COMPLETED)
                .limit(10).build();
        assertTrue(mixed.admits(ProcessInstanceStatus.RUNNING));
        assertFalse(mixed.admits(ProcessInstanceStatus.COMPLETED));
        assertFalse(mixed.isSelfContradictory(),
                "a mixed filter is meaningful and returns the non-terminal half");

        var contradiction = ProcessInventoryQuery.builder()
                .status(ProcessInstanceStatus.COMPLETED)
                .status(ProcessInstanceStatus.FAILED)
                .limit(10).build();
        assertTrue(contradiction.isSelfContradictory());

        assertTrue(ProcessInventoryQuery.everything(10).admits(ProcessInstanceStatus.COMPLETED));
    }

    @Test
    void anOriginIsMergedComponentWiseSoAPartiallyInformedCallerCannotEraseWhatIsRecorded() {
        var full = ExecutionOrigin.of("dep-1", "workload-1", "corr-1");
        assertEquals(full, full.mergedWith(ExecutionOrigin.none()));
        assertEquals(Optional.of("dep-1"),
                full.mergedWith(ExecutionOrigin.of(null, "workload-2", null)).deploymentId());
        assertEquals(Optional.of("workload-2"),
                full.mergedWith(ExecutionOrigin.of(null, "workload-2", null)).workloadId());
        assertEquals(Optional.of("dep-2"),
                full.mergedWith(ExecutionOrigin.of("dep-2", null, null)).deploymentId(),
                "a redeployment genuinely moves hosting, so a later value is an update rather than a "
                        + "contradiction; this is deliberately not the write-once graph version pin");
        assertTrue(ExecutionOrigin.none().isEmpty());
    }

    @Test
    void anOriginComponentIsBoundedAndNeverBlank() {
        assertThrows(IllegalArgumentException.class, () -> ExecutionOrigin.of("  ", null, null));
        assertThrows(IllegalArgumentException.class, () -> ExecutionOrigin.of(null,
                "x".repeat(ExecutionOrigin.MAX_COMPONENT_LENGTH + 1), null));
        assertEquals(Optional.of("x".repeat(ExecutionOrigin.MAX_COMPONENT_LENGTH)),
                ExecutionOrigin.of(null, null, "x".repeat(ExecutionOrigin.MAX_COMPONENT_LENGTH))
                        .correlationId());
    }

    @Test
    void anOriginOnlyBatchIsAnOperationInItsOwnRight() {
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        // Recording a relationship changes stored state, so it does not need a no-op transition
        // invented to carry it. That is the seam a caller that learns the deployment after creation
        // writes through.
        ExecutionBatch batch = ExecutionBatch.to(key)
                .recordOrigin(ExecutionOrigin.of("dep-1", null, null))
                .build();
        assertEquals(Optional.of("dep-1"), batch.origin().deploymentId());
        assertThrows(IllegalArgumentException.class, () -> ExecutionBatch.to(key).build());
    }

    @Test
    void aBatchAccumulatesItsOriginAcrossSeveralCallsWithoutTheCallerKnowingTheOrder() {
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        ExecutionBatch batch = ExecutionBatch.to(key)
                .recordOrigin(ExecutionOrigin.of("dep-1", null, null))
                .recordOrigin(ExecutionOrigin.of(null, "workload-1", null))
                .build();
        assertEquals(Optional.of("dep-1"), batch.origin().deploymentId());
        assertEquals(Optional.of("workload-1"), batch.origin().workloadId());
    }

    @Test
    void aPageWithoutARetentionFloorIsRejectedBecauseAnAbsentRowWouldBeUnreadable() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessInventoryPage(java.util.List.of(), Optional.empty(), null));
        var empty = new ProcessInventoryPage(null, null, Instant.MIN);
        assertEquals(java.util.List.of(), empty.items());
        assertEquals(Optional.empty(), empty.nextCursor());
    }

    private static ExecutionStoreFailure failureOf(Runnable operation) {
        ExecutionStoreException thrown = assertThrows(ExecutionStoreException.class, operation::run);
        return thrown.failure();
    }
}
