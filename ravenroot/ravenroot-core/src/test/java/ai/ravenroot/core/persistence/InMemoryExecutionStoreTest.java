package ai.ravenroot.core.persistence;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.IdempotencyWrite;
import ai.ravenroot.api.persistence.LeaseHandle;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.Retryability;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.persistence.TimerSchedule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural contract of the reference adapter. The reusable API conformance suite lives in
 * {@code ravenroot-persistence-testkit}; these tests are the implementation-side checks that the
 * adapter honours each ADR 0010 ruling.
 */
class InMemoryExecutionStoreTest {

    private static final String TENANT = "acme";
    private static final Duration TTL = Duration.ofSeconds(30);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-01T10:00:00Z"));
    private final InMemoryExecutionStore store = new InMemoryExecutionStore(clock);

    private final UUID processInstanceId = UUID.randomUUID();
    private final UUID traversalId = UUID.randomUUID();
    private final ExecutionKey key = new ExecutionKey(TENANT, processInstanceId);

    @AfterEach
    void tearDown() {
        store.close();
    }

    // ------------------------------------------------------------------ capabilities

    @Test
    void declaresOnlyWhatItCanHonour() {
        // Declaring either of these to skip a conformance assertion is the failure mode the
        // asymmetric enforcement rule exists to prevent.
        assertFalse(store.supports(StoreCapability.DURABLE));
        assertFalse(store.supports(StoreCapability.CROSS_PROCESS_LEASE));
        assertTrue(store.supports(StoreCapability.TRANSACTIONAL_BATCH));
        assertTrue(store.supports(StoreCapability.IDEMPOTENCY_PURGE));
    }

    // ------------------------------------------------------------------ revisions and expectations

    @Test
    void createsExactlyOnceAndRejectsTheSecondCreation() {
        StoredProcessInstance created = await(store.apply(creation()));

        assertEquals(ProcessInstanceStatus.ACCEPTED, created.state().status());
        assertEquals(new GraphVersionPin("graph-v1"), created.graphVersionPin());

        var conflict = failureOf(() -> await(store.apply(creation())));
        assertInstanceOf(ExecutionStoreFailure.AlreadyExists.class, conflict);
    }

    @Test
    void revisionsAreStrictlyIncreasingButNotContiguous() {
        StoredProcessInstance first = await(store.apply(creation()));

        // A second instance takes the next store-wide sequence value, so the first instance's own
        // revisions skip. Nothing may depend on +1.
        var other = new ExecutionKey(TENANT, UUID.randomUUID());
        await(store.apply(creationFor(other, UUID.randomUUID())));

        StoredProcessInstance advanced = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(first.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .build()));

        assertTrue(advanced.revision() > first.revision());
        assertNotEquals(first.revision() + 1, advanced.revision());
    }

    @Test
    void staleRevisionExpectationConflictsAndIsNotCollapsedWithFencing() {
        StoredProcessInstance created = await(store.apply(creation()));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .build()));

        var failure = failureOf(() -> await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .build())));

        var conflict = assertInstanceOf(ExecutionStoreFailure.ConcurrencyConflict.class, failure);
        // Not DETERMINISTIC_REJECT and not RETRYABLE_NO_EFFECT: an identical retry would carry the
        // same stale expectation and loop forever, so the caller is obliged to re-read first.
        assertEquals(Retryability.RETRY_AFTER_REREAD, conflict.retryability());
        assertNotEquals(conflict.retryability(),
                new ExecutionStoreFailure.FencedOut(key, 1L, 2L).retryability());
    }

    @Test
    void unconditionalWriteAgainstAnAbsentInstanceIsNotFound() {
        var failure = failureOf(() -> await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.any())
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .build())));

        assertInstanceOf(ExecutionStoreFailure.NotFound.class, failure);
    }

    @Test
    void anotherTenantSeesNotFoundRatherThanADenialSoTheStoreIsNoExistenceOracle() {
        await(store.apply(creation()));

        var failure = failureOf(() -> await(store.load(new ExecutionKey("other-tenant", processInstanceId))));

        assertInstanceOf(ExecutionStoreFailure.NotFound.class, failure);
        assertFalse(failure instanceof ExecutionStoreFailure.NotAuthorized);
    }

    @Test
    void graphVersionPinIsWriteOnce() {
        await(store.apply(creation()));

        var failure = failureOf(() -> await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.any())
                .apply(new ExecutionTransition.ProcessCreated(acceptedInstance(processInstanceId, traversalId),
                        new GraphVersionPin("graph-v2")))
                .build())));

        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class, failure);
        assertEquals(new GraphVersionPin("graph-v1"), await(store.load(key)).graphVersionPin());
    }

    // ------------------------------------------------------------------ atomicity

    @Test
    void aRejectedBatchLeavesNoPartialWrite() {
        StoredProcessInstance created = await(store.apply(creation()));

        // The first transition is legal, the second is not. All-or-nothing means neither lands.
        var failure = failureOf(() -> await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .scheduleTimer(new TimerSchedule(UUID.randomUUID(), clock.instant(), traversalId, null,
                        OpaquePayload.empty("application/octet-stream")))
                .build())));

        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class, failure);

        StoredProcessInstance unchanged = await(store.load(key));
        assertEquals(created.revision(), unchanged.revision());
        assertEquals(ProcessInstanceStatus.ACCEPTED, unchanged.state().status());
        assertTrue(await(store.claimDueTimers(TENANT, "worker-1", 10, TTL)).isEmpty());
    }

    @Test
    void payloadsBeyondTheDeclaredLimitAreRejectedWithTheirOwnFailure() {
        var small = new InMemoryExecutionStore(clock, Duration.ofMinutes(5), 8);
        try {
            await(small.apply(creation()));
            var failure = failureOf(() -> await(small.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.any())
                    .scheduleTimer(new TimerSchedule(UUID.randomUUID(), clock.instant(), traversalId, null,
                            OpaquePayload.of(new byte[64], "application/octet-stream")))
                    .build())));

            var tooLarge = assertInstanceOf(ExecutionStoreFailure.PayloadTooLarge.class, failure);
            assertEquals(8, tooLarge.limitBytes());
        } finally {
            small.close();
        }
    }

    // ------------------------------------------------------------------ leases and fencing

    @Test
    void leaseIsExclusiveUntilItExpiresOnTheStoreClock() {
        await(store.apply(creation()));

        LeaseHandle held = await(store.claim(key, "worker-1", TTL));
        assertEquals(1, await(store.leases(TENANT)).size());

        var contended = failureOf(() -> await(store.claim(key, "worker-2", TTL)));
        // Failing to acquire is ordinary contention, not the loss of a held lease.
        var busy = assertInstanceOf(ExecutionStoreFailure.LeaseHeldByAnother.class, contended);
        assertEquals("worker-1", busy.holderWorkerId());
        assertEquals(held.expiresAt(), busy.expiresAt());
        assertEquals(Retryability.RETRYABLE_NO_EFFECT, busy.retryability());

        // Expiry is evaluated lazily against the store's clock, never the caller's.
        clock.advance(Duration.ofSeconds(31));
        assertTrue(await(store.leases(TENANT)).isEmpty());

        LeaseHandle taken = await(store.claim(key, "worker-2", TTL));
        assertTrue(taken.fencingToken() > held.fencingToken());
    }

    @Test
    void renewingALostLeaseFails() {
        await(store.apply(creation()));
        LeaseHandle held = await(store.claim(key, "worker-1", TTL));

        clock.advance(Duration.ofSeconds(31));
        await(store.claim(key, "worker-2", TTL));

        assertInstanceOf(ExecutionStoreFailure.LeaseLost.class,
                failureOf(() -> await(store.renew(held, TTL))));
    }

    @Test
    void ttlAboveTheDeclaredMaximumIsRejectedSoACrashCannotStickForever() {
        await(store.apply(creation()));

        var failure = failureOf(() -> await(store.claim(key, "worker-1", store.maxLeaseTtl().plusSeconds(1))));

        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class, failure);
    }

    @Test
    void fencingRejectsAnyTokenThatIsNotTheCurrentOneIncludingAHigherUnissuedToken() {
        await(store.apply(creation()));
        LeaseHandle first = await(store.claim(key, "worker-1", TTL));
        clock.advance(Duration.ofSeconds(31));
        LeaseHandle second = await(store.claim(key, "worker-2", TTL));

        // The superseded owner is fenced out.
        var stale = failureOf(() -> await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.any())
                .fencedBy(first)
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .build())));
        assertInstanceOf(ExecutionStoreFailure.FencedOut.class, stale);

        // And so is a caller inventing a higher token, which a "reject lower" rule would have let
        // through and used to fence out the legitimate owner.
        var forged = failureOf(() -> await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.any())
                .fencedBy(second.fencingToken() + 5)
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .build())));
        assertInstanceOf(ExecutionStoreFailure.FencedOut.class, forged);

        // The current holder still writes.
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.any())
                .fencedBy(second)
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .build()));
        assertEquals(ProcessInstanceStatus.RUNNING, await(store.load(key)).state().status());
    }

    // ------------------------------------------------------------------ pending work and timers

    @Test
    void scheduledAttemptsBecomeClaimableWorkAndAreRedeliveredUntilAcknowledged() {
        var attemptId = UUID.randomUUID();
        awaitRunningWithScheduledAttempt(attemptId);

        List<PendingWork> claimed = await(store.claimPendingWork(TENANT, "worker-1", 10, TTL));
        var dispatch = assertInstanceOf(PendingWork.AttemptDispatch.class, claimed.get(0));
        assertEquals(attemptId, dispatch.attemptId());
        assertEquals(1, dispatch.deliveryAttempt());

        // Invisible while the visibility window holds.
        assertTrue(await(store.claimPendingWork(TENANT, "worker-1", 10, TTL)).isEmpty());

        // Redelivered after it elapses: at-least-once, so a crash before the ack loses nothing.
        clock.advance(Duration.ofSeconds(31));
        var redelivered = assertInstanceOf(PendingWork.AttemptDispatch.class,
                await(store.claimPendingWork(TENANT, "worker-1", 10, TTL)).get(0));
        assertEquals(2, redelivered.deliveryAttempt());

        await(store.ack(redelivered));
        clock.advance(Duration.ofSeconds(31));
        assertTrue(await(store.claimPendingWork(TENANT, "worker-1", 10, TTL)).isEmpty());
    }

    @Test
    void timersAreDueOnTheStoreClockAndSurviveACrashBetweenClaimAndFire() {
        StoredProcessInstance created = await(store.apply(creation()));
        var timerId = UUID.randomUUID();
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .scheduleTimer(new TimerSchedule(timerId, clock.instant().plusSeconds(60), traversalId, null,
                        OpaquePayload.of("wake".getBytes(StandardCharsets.UTF_8), "text/plain")))
                .build()));

        assertTrue(await(store.claimDueTimers(TENANT, "worker-1", 10, TTL)).isEmpty());

        clock.advance(Duration.ofSeconds(61));
        PendingWork.TimerDue due = await(store.claimDueTimers(TENANT, "worker-1", 10, TTL)).get(0);
        assertEquals(timerId, due.workItemId());
        // The original due instant is exposed, not an opaque handle, so scheduling lag is computable.
        assertEquals(Instant.parse("2026-08-01T10:01:00Z"), due.dueAt());

        // Claimed but never acknowledged: the timer must come back rather than vanish.
        clock.advance(Duration.ofSeconds(31));
        PendingWork.TimerDue again = await(store.claimDueTimers(TENANT, "worker-1", 10, TTL)).get(0);
        assertEquals(2, again.deliveryAttempt());

        await(store.ack(again));
        clock.advance(Duration.ofSeconds(31));
        assertTrue(await(store.claimDueTimers(TENANT, "worker-1", 10, TTL)).isEmpty());
    }

    @Test
    void cancellingATimerRemovesItBeforeItCanFire() {
        StoredProcessInstance created = await(store.apply(creation()));
        var timerId = UUID.randomUUID();
        StoredProcessInstance scheduled = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .scheduleTimer(new TimerSchedule(timerId, clock.instant(), traversalId, null,
                        OpaquePayload.empty("application/octet-stream")))
                .build()));

        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(scheduled.revision()))
                .cancelTimer(timerId)
                .build()));

        assertTrue(await(store.claimDueTimers(TENANT, "worker-1", 10, TTL)).isEmpty());
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    void aMatchingReplayIsAnsweredFromTheRecordAndNeverReapplied() {
        StoredProcessInstance created = await(store.apply(creation()));
        var write = new IdempotencyWrite("submit-1", fingerprint("request"), fingerprint("outcome"),
                Duration.ofMinutes(10), clock.instant());

        StoredProcessInstance applied = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .recordIdempotency(write)
                .build()));

        StoredProcessInstance replayed = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .recordIdempotency(write)
                .build()));

        // A successful replay is a success, and the aggregate did not move a second time.
        assertEquals(applied.revision(), replayed.revision());
        assertEquals(1L, await(store.idempotencyRecordCount(TENANT)));
        assertEquals(fingerprint("outcome"),
                await(store.lookupIdempotency(TENANT, "submit-1", clock.instant())).orElseThrow().outcomeRef());
    }

    @Test
    void reusingAKeyForADifferentRequestConflicts() {
        StoredProcessInstance created = await(store.apply(creation()));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .recordIdempotency(new IdempotencyWrite("submit-1", fingerprint("request"),
                        fingerprint("outcome"), Duration.ofMinutes(10), clock.instant()))
                .build()));

        var failure = failureOf(() -> await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.any())
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .recordIdempotency(new IdempotencyWrite("submit-1", fingerprint("different"),
                        fingerprint("outcome"), Duration.ofMinutes(10), clock.instant()))
                .build())));

        assertInstanceOf(ExecutionStoreFailure.IdempotencyConflict.class, failure);
    }

    @Test
    void aForgottenKeyReportsExpiryRatherThanSilentlyReexecuting() {
        Instant issuedAt = clock.instant();
        StoredProcessInstance created = await(store.apply(creation()));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .recordIdempotency(new IdempotencyWrite("submit-1", fingerprint("request"),
                        fingerprint("outcome"), Duration.ofMinutes(10), issuedAt))
                .build()));

        // A store may forget a key only after its declared window elapses.
        assertEquals(0L, await(store.purgeExpiredIdempotencyRecords(TENANT)));
        clock.advance(Duration.ofMinutes(11));
        assertEquals(1L, await(store.purgeExpiredIdempotencyRecords(TENANT)));

        // The critical assertion: a forgotten key is NOT reported as "never recorded", because that
        // reads as "never applied" and re-executes. The key was issued below the watermark, so the
        // store cannot exclude that a record existed and was purged.
        assertInstanceOf(ExecutionStoreFailure.IdempotencyRecordExpired.class,
                failureOf(() -> await(store.lookupIdempotency(TENANT, "submit-1", issuedAt))));

        // And the same classification protects the write path, which is where re-execution would
        // actually happen.
        var onWrite = failureOf(() -> await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.any())
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .recordIdempotency(new IdempotencyWrite("submit-1", fingerprint("request"),
                        fingerprint("outcome"), Duration.ofMinutes(10), issuedAt))
                .build())));
        var expired = assertInstanceOf(ExecutionStoreFailure.IdempotencyRecordExpired.class, onWrite);
        assertEquals(Retryability.INDETERMINATE, expired.retryability());
    }

    @Test
    void theWatermarkStartsAtInstantMinAndAdvancesOnlyThroughPurge() {
        Instant issuedAt = clock.instant();
        assertEquals(Instant.MIN, await(store.forgottenBefore(TENANT)));

        StoredProcessInstance created = await(store.apply(creation()));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .recordIdempotency(new IdempotencyWrite("submit-1", fingerprint("request"),
                        fingerprint("outcome"), Duration.ofMinutes(10), issuedAt))
                .build()));

        // Merely writing and letting a record age does not move the watermark.
        clock.advance(Duration.ofMinutes(11));
        assertEquals(Instant.MIN, await(store.forgottenBefore(TENANT)));

        await(store.purgeExpiredIdempotencyRecords(TENANT));
        Instant advanced = await(store.forgottenBefore(TENANT));
        assertEquals(clock.instant(), advanced);

        // Monotonically non-decreasing: a later purge that forgets nothing cannot retreat it.
        clock.advance(Duration.ofMinutes(5));
        assertEquals(0L, await(store.purgeExpiredIdempotencyRecords(TENANT)));
        assertEquals(advanced, await(store.forgottenBefore(TENANT)));

        // A tenant that has forgotten nothing keeps a provable absence.
        assertEquals(Instant.MIN, await(store.forgottenBefore("untouched-tenant")));
    }

    @Test
    void anAbsentKeyIssuedAboveTheWatermarkIsProvablyNeverRecorded() {
        Instant issuedAt = clock.instant();
        StoredProcessInstance created = await(store.apply(creation()));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .recordIdempotency(new IdempotencyWrite("submit-1", fingerprint("request"),
                        fingerprint("outcome"), Duration.ofMinutes(10), issuedAt))
                .build()));
        clock.advance(Duration.ofMinutes(11));
        await(store.purgeExpiredIdempotencyRecords(TENANT));

        // A key minted comfortably after the watermark: had it been recorded, its expiresAt would
        // still be above the watermark, so its absence proves it was never written.
        clock.advance(Duration.ofMinutes(1));
        Instant freshlyIssued = clock.instant();
        assertEquals(Optional.empty(),
                await(store.lookupIdempotency(TENANT, "brand-new-key", freshlyIssued)),
                "provable absence is an empty result, not a failure (ADR 0010 section 6.3)");
    }

    @Test
    void forwardSkewBeyondTheDeclaredBudgetIsRejectedAtWriteAndAtLookup() {
        StoredProcessInstance created = await(store.apply(creation()));
        Instant beyondBudget = clock.instant().plus(store.maxClockSkew()).plusSeconds(1);

        // Rejected at write time, so an operator whose clock runs fast is told while the clock can
        // still be fixed rather than after the damage window has passed.
        var onWrite = failureOf(() -> await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .recordIdempotency(new IdempotencyWrite("submit-1", fingerprint("request"),
                        fingerprint("outcome"), Duration.ofMinutes(10), beyondBudget))
                .build())));
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class, onWrite);

        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                failureOf(() -> await(store.lookupIdempotency(TENANT, "submit-1", beyondBudget))));

        // The rejected write applied nothing.
        assertEquals(created.revision(), await(store.load(key)).revision());
    }

    @Test
    void ambiguityWithinTheSkewBudgetResolvesToExpiredRatherThanSafeToApply() {
        Instant issuedAt = clock.instant();
        StoredProcessInstance created = await(store.apply(creation()));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .recordIdempotency(new IdempotencyWrite("submit-1", fingerprint("request"),
                        fingerprint("outcome"), Duration.ofMinutes(10), issuedAt))
                .build()));
        clock.advance(Duration.ofMinutes(11));
        await(store.purgeExpiredIdempotencyRecords(TENANT));
        Instant watermark = await(store.forgottenBefore(TENANT));

        // Issued just ABOVE the watermark but within the skew budget of it. A naive comparison would
        // answer NotFound and re-execute; the contract compares keyIssuedAt minus the budget, so
        // every ambiguous case inside the budget falls to expired.
        clock.advance(Duration.ofMinutes(1));
        Instant justAbove = watermark.plusSeconds(1);
        assertTrue(store.maxClockSkew().toSeconds() > 1);
        assertInstanceOf(ExecutionStoreFailure.IdempotencyRecordExpired.class,
                failureOf(() -> await(store.lookupIdempotency(TENANT, "ambiguous", justAbove))));

        // Clear of the budget, absence is provable again.
        Instant clearOfBudget = watermark.plus(store.maxClockSkew()).plusSeconds(1);
        assertEquals(Optional.empty(),
                await(store.lookupIdempotency(TENANT, "ambiguous", clearOfBudget)));
    }

    @Test
    void anUnrecordedKeyIsAnEmptyResultNotAFailureSoTheCallerMayProceed() {
        // ADR 0010 section 6.3: the commonest path -- the first attempt of every idempotent
        // operation -- must not allocate an exception, and there is no ExecutionKey to report
        // because nothing was looked up by instance key. The nil-UUID key the earlier NotFound had
        // to fabricate reached describe() and the logs, showing an operator a real-looking key for
        // an entity that never existed.
        assertEquals(Optional.empty(),
                await(store.lookupIdempotency(TENANT, "never-written", clock.instant())));
    }

    @Test
    void retentionWindowIsMandatoryOnTheWrite() {
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyWrite("k",
                fingerprint("request"), fingerprint("outcome"), null, clock.instant()));
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyWrite("k",
                fingerprint("request"), fingerprint("outcome"), Duration.ZERO, clock.instant()));
    }

    @Test
    void keyIssuedAtIsMandatoryOnTheWrite() {
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyWrite("k",
                fingerprint("request"), fingerprint("outcome"), Duration.ofMinutes(10), null));
    }

    // ------------------------------------------------------------------ helpers

    private void awaitRunningWithScheduledAttempt(UUID attemptId) {
        var invocationId = UUID.randomUUID();
        StoredProcessInstance created = await(store.apply(creation()));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .apply(new ExecutionTransition.InvocationAdded(traversalId,
                        new NodeInvocation(invocationId, "work", null, NodeInvocationStatus.SCHEDULED)))
                .apply(new ExecutionTransition.InvocationTransitioned(traversalId, invocationId,
                        NodeInvocationStatus.RUNNING))
                .apply(new ExecutionTransition.AttemptAdded(traversalId, invocationId,
                        new NodeAttempt(attemptId, 1, NodeAttemptStatus.SCHEDULED)))
                .build()));
    }

    private ExecutionBatch creation() {
        return creationFor(key, traversalId);
    }

    private ExecutionBatch creationFor(ExecutionKey target, UUID traversal) {
        return ExecutionBatch.to(target)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(
                        acceptedInstance(target.processInstanceId(), traversal), new GraphVersionPin("graph-v1")))
                .build();
    }

    private static ProcessInstance acceptedInstance(UUID instanceId, UUID traversal) {
        return new ProcessInstance(instanceId, ProcessInstanceStatus.ACCEPTED,
                Map.of(traversal, new Traversal(traversal, "start", TraversalStatus.ACCEPTED, Map.of())));
    }

    private static OpaquePayload fingerprint(String value) {
        return OpaquePayload.of(value.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private static ExecutionStoreFailure failureOf(Runnable operation) {
        CompletionException thrown = assertThrows(CompletionException.class, operation::run);
        ExecutionStoreException failure = ExecutionStoreException.unwrap(thrown);
        assertTrue(failure != null, "adapters must not leak non-store exceptions: " + thrown);
        return failure.failure();
    }

    /** Lets lease expiry and timer due-ness be driven deterministically instead of by sleeping. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
