package ai.ravenroot.testkit.persistence;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptCompletion;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.DurableHandler;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.JournalCursor;
import ai.ravenroot.api.persistence.JournalRecord;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.HandlerPayloadSchema;
import ai.ravenroot.api.persistence.HandlerRegistration;
import ai.ravenroot.api.persistence.HandlerStatus;
import ai.ravenroot.api.persistence.HandlerTransition;
import ai.ravenroot.api.persistence.IdempotencyRecord;
import ai.ravenroot.api.persistence.IdempotencyWrite;
import ai.ravenroot.api.persistence.LeaseHandle;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.Retryability;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.persistence.TimerSchedule;
import ai.ravenroot.api.execution.NodeCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reusable conformance suite every {@link ExecutionStore} adapter must extend, per ADR 0010
 * section 1 ("A {@code ravenroot-persistence-testkit} module holds the conformance suite... and
 * adapters consume it at test scope") and the {@code ravenroot-engine-testkit} /
 * {@code ExecutionEngineContract} precedent it explicitly follows.
 *
 * <p>Every assertion here is derived from ADR 0010's text, not from reading any one adapter's
 * source, so that a future adapter that reproduces a bug already present in the reference adapter is
 * still caught. Test methods are {@code final}: a subclass supplies a factory, never a weaker
 * assertion.</p>
 *
 * <h2>Capability-gated assertions</h2>
 * <p>Section 11's enforcement is asymmetric: absence of a declared {@link StoreCapability} skips its
 * assertion (via {@link Assumptions#assumeTrue}, which reports as a visible <em>skip</em>, never a
 * silent pass), presence never skips it. {@link StoreCapability#DURABLE} and
 * {@link StoreCapability#CROSS_PROCESS_LEASE} are structurally impossible for an in-memory adapter to
 * honour, so their assertions are written here and will simply not run against one — and will run,
 * and must pass, against PERS-03's SQLite adapter or any other adapter that declares them.</p>
 *
 * <p>Those two assertions were <strong>PROVISIONAL</strong> under the section 11.1 amendment while
 * no in-tree adapter declared either capability. A gated assertion that no in-tree adapter runs is
 * not merely untested: it is unverified <em>for internal consistency</em>, because it never executes
 * anywhere. That is how
 * {@link #crossProcessLeaseSurvivesAReopenAndItsFencingTokensAreNeverReused} came to contain a step
 * that could only pass if the very property the capability is named for were violated, while never
 * asserting that property at all — mutation testing could not reach it and review had no signal,
 * because in aggregate a skipped assertion looks identical to a passing one.</p>
 *
 * <p><strong>The label is withdrawn.</strong> {@code SqliteExecutionStore} declares both
 * {@link StoreCapability#DURABLE} and {@link StoreCapability#CROSS_PROCESS_LEASE}, both assertions
 * execute against it, and both pass. Note that the label outlived the event that was supposed to
 * clear it: it went on claiming that no in-tree adapter declared the capability long after one did.
 * A statement true only until a named event must name that event, and clearing it belongs to the
 * event's documented requirements — otherwise it decays into a false statement, silently, in exactly the
 * way the gated assertion above did.</p>
 *
 * <h2>Reopen / simulated process death</h2>
 * <p>{@link #createStore(String, Clock)} is invoked with the same {@code storeId} and {@link Clock}
 * both for the first open and for every {@link #reopen()}. A non-durable adapter is free to ignore
 * {@code storeId} and return a fresh, empty instance every time — correct, because the assertions
 * that would observe the difference are gated on {@link StoreCapability#DURABLE} and never run
 * against it. A durable adapter must use {@code storeId} as its backing identity so a reopen
 * reconnects to the same data, exactly as a process restart against the same database file or schema
 * would.</p>
 *
 * <h2>Idempotency expiry (ADR 0010 section 6.1, amended 2026-08-01)</h2>
 * <p>The original per-key "forgotten" mechanism could not be implemented without unbounded state (a
 * purged key is byte-for-byte indistinguishable from one never written), and was replaced by a
 * per-tenant temporal low-water-mark, {@code forgottenBefore}. The port now carries a mandatory
 * {@code Instant keyIssuedAt} on both {@link IdempotencyWrite} and {@code lookupIdempotency}, a new
 * asynchronous {@code forgottenBefore(tenantId)} query and a new synchronous {@code maxClockSkew()}.
 * The suite exercises the watermark, the fail-closed skew guard at both write and lookup time, and —
 * because a purged key that is silently re-executed on the write path is the exact failure this
 * mechanism exists to prevent — the classification of an absent key inside {@code apply()} itself,
 * not merely inside {@code lookupIdempotency}. These assertions run unconditionally against
 * {@link StoreCapability#IDEMPOTENCY_PURGE}: the watermark can only move through
 * {@code purgeExpiredIdempotencyRecords(tenantId)}, so an adapter without that capability cannot
 * exercise this path at all and the assertions gated on it correctly skip for it (never silently
 * omitted).</p>
 *
 * <p>Under the section 6.3 amendment {@code lookupIdempotency} returns
 * {@code CompletionStage<Optional<IdempotencyRecord>>}: an absent record is an <em>empty result</em>,
 * not a failure, because it is the outcome of the first attempt of every idempotent operation and
 * because there is no entity to name in a {@code NotFound} — nothing was looked up by instance key.
 * {@code IdempotencyRecordExpired} remains a failure and remains {@code INDETERMINATE}. Empty means
 * proceed; a failure means stop and resolve.</p>
 *
 * <h2>Failure taxonomy (ADR 0010 section 12, amended 2026-08-01)</h2>
 * <p>{@link Retryability} gained a fourth value, {@code RETRY_AFTER_REREAD}, specifically so
 * {@code ConcurrencyConflict} and {@code FencedOut} are distinguishable on the coarse classification
 * alone and not only by pattern-matching the concrete type. The sealed failure set gained a
 * fifteenth member, {@code LeaseHeldByAnother}, so failing to <em>acquire</em> a contended lease is no
 * longer reported as {@code LeaseLost} (the loss of a lease already <em>held</em>) — see section 12.2.
 * {@code Corrupted}, {@code NotAuthorized}, {@code Unavailable} and {@code OutcomeUnknown} remain
 * adapter-conditional by construction (section 12.4): no conforming in-memory adapter can be driven
 * into them through the port's own operations, and the ADR explicitly endorses asserting their
 * classification by constructing the failure records directly rather than adding a fault-injection
 * point to the port. Section 12.4 also authorizes an optional testkit-only side-interface a durable
 * adapter's test fixture could implement to exercise these live; none is added here because no
 * adapter in this repository yet needs it — see the A2A report.</p>
 *
 * <h2>Tenancy (PERS-10)</h2>
 * <p>Every port operation is now tenant-scoped, and this suite is written against those signatures.
 * The contract uses <em>physical</em> isolation — a database or schema per tenant with its own pool —
 * which makes an un-tenanted operation either meaningless or an implicit fan-out across
 * whichever pools happen to be open. So {@code leases}, {@code idempotencyRecordCount},
 * {@code purgeExpiredIdempotencyRecords} and {@code forgottenBefore} take a tenant by necessity (a
 * watermark, a record count and a purge cutoff have no meaning spanning physically separate stores),
 * and {@code claimPendingWork} / {@code claimDueTimers} take one <em>per call</em>, with cross-tenant
 * fairness left to the runtime rather than smuggled into the store as an unexpressible scheduling
 * policy.</p>
 *
 * <p>With a single in-process adapter, global and tenant-scoped answers coincide for most
 * assertions, which is exactly why
 * {@link #purgingOneTenantLeavesAnotherTenantsWatermarkAtInstantMin} exists: it is the assertion that
 * does <em>not</em> coincide, and it fails against an implementation that advances a watermark for a
 * tenant that lost no record.</p>
 */
public abstract class ExecutionStoreContract {

    /** Arbitrary, fixed epoch so every test starts from a readable, reproducible instant. */
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final String DEFAULT_TENANT = "acme";
    protected static final Duration TTL = Duration.ofSeconds(30);

    private String storeId;
    private MutableClock mutableClock;
    private ExecutionStore store;

    /**
     * Creates (or reopens) an adapter instance backed by {@code clock}, using {@code storeId} as the
     * backing identity for adapters that persist across the call. See the class Javadoc for the
     * reopen contract.
     */
    protected abstract ExecutionStore createStore(String storeId, Clock clock);

    @AfterEach
    final void closeStore() {
        if (store != null) {
            store.close();
        }
    }

    protected final ExecutionStore store() {
        if (store == null) {
            storeId = "contract-" + UUID.randomUUID();
            mutableClock = new MutableClock(EPOCH);
            store = createStore(storeId, mutableClock);
        }
        return store;
    }

    /** Lets a test drive lease expiry and timer due-ness deterministically instead of sleeping. */
    protected final MutableClock clock() {
        store();
        return mutableClock;
    }

    /** Simulates a reopen or a process death against the same backing storage and the same clock. */
    protected final ExecutionStore reopen() {
        store().close();
        store = createStore(storeId, mutableClock);
        return store;
    }

    private void assumeCapability(StoreCapability capability) {
        Assumptions.assumeTrue(store().supports(capability), () -> capability
                + " not declared by this adapter; assertion skipped per ADR 0010 section 11's "
                + "asymmetric enforcement (absence skips, presence never does)");
    }

    // ================================================================== section 2: revisions

    @Test
    final void successfulWriteReturnsAGreaterRevisionAndReplayingTheSameExpectationConflicts() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));

        StoredProcessInstance running = await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .build()));
        assertTrue(running.revision() > created.revision(), "a successful write must return a strictly "
                + "greater revision");

        // The expectation used above is now stale; replaying it is a stale-revision write, not a
        // legal one, even though nothing else about the request changed.
        var failure = failureOf(() -> await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .build())));
        assertInstanceOf(ExecutionStoreFailure.ConcurrencyConflict.class, failure);
    }

    @Test
    final void revisionsAreStrictlyIncreasingPerInstanceButNeedNotBeContiguousGlobally() {
        ExecutionKey a = newKey();
        ExecutionKey b = newKey();
        UUID traversalA = UUID.randomUUID();
        UUID traversalB = UUID.randomUUID();

        StoredProcessInstance a1 = await(store().apply(creationBatch(a, traversalA, "graph-v1")));
        StoredProcessInstance b1 = await(store().apply(creationBatch(b, traversalB, "graph-v1")));
        StoredProcessInstance a2 = await(store().apply(ExecutionBatch.to(a)
                .expecting(RevisionExpectation.exactly(a1.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .build()));
        StoredProcessInstance b2 = await(store().apply(ExecutionBatch.to(b)
                .expecting(RevisionExpectation.exactly(b1.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .build()));

        // Strictly increasing per instance...
        assertTrue(a2.revision() > a1.revision());
        assertTrue(b2.revision() > b1.revision());
        // ...but the suite deliberately never asserts +1, equality across instances, or any other
        // contiguity relationship: a distributed adapter may use a log offset or a hybrid logical
        // clock, and interleaving a second instance's writes between an instance's own writes must
        // not be something a caller could quietly come to depend on.
    }

    @Test
    final void updatedAtIsDiagnosticOnlyNeverAnOrderingPrimitive() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));

        // The clock does not move between the two writes. If anything used wall-clock time, rather
        // than revision, to order or distinguish them, this would be indistinguishable from a no-op.
        StoredProcessInstance advanced = await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .build()));

        assertEquals(created.updatedAt(), advanced.updatedAt());
        assertTrue(advanced.revision() > created.revision());
    }

    @Test
    final void notPresentExpectationMakesInstanceCreationExactlyOnce() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        await(store().apply(creationBatch(key, traversalId, "graph-v1")));

        var failure = failureOf(() -> await(store().apply(creationBatch(key, traversalId, "graph-v1"))));
        assertInstanceOf(ExecutionStoreFailure.AlreadyExists.class, failure);
    }

    @Test
    final void aMismatchedTenantIsNotFoundNeverADenialSoTheStoreIsNotAnExistenceOracle() {
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        ExecutionKey owner = new ExecutionKey("tenant-a", processInstanceId);
        await(store().apply(creationBatch(owner, traversalId, "graph-v1")));

        ExecutionKey impostor = new ExecutionKey("tenant-b", processInstanceId);
        assertInstanceOf(ExecutionStoreFailure.NotFound.class, failureOf(() -> await(store().load(impostor))));
        assertInstanceOf(ExecutionStoreFailure.NotFound.class,
                failureOf(() -> await(store().claim(impostor, "worker-1", TTL))));

        // And the store must not double as a cross-tenant existence oracle: a different tenant may
        // create its own instance reusing the identical processInstanceId, because the two keys are
        // genuinely distinct identities, not a collision.
        StoredProcessInstance created = await(store().apply(creationBatch(impostor, traversalId, "graph-v1")));
        assertEquals(ProcessInstanceStatus.ACCEPTED, created.state().status());
        assertEquals(ProcessInstanceStatus.ACCEPTED, await(store().load(owner)).state().status());
    }

    // ================================================================== sections 3 & 8: batch and pin

    @Test
    final void aRejectedBatchLeavesNoPartialWriteObservable() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));

        UUID timerId = UUID.randomUUID();
        // Four operations in one batch: a legal transition, an illegal one (unknown traversal), a
        // timer schedule and an idempotency write. All-or-nothing means none of the four may be
        // independently observable once the illegal transition rejects the whole batch.
        var failure = failureOf(() -> await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(UUID.randomUUID(), TraversalStatus.RUNNING))
                .scheduleTimer(new TimerSchedule(timerId, clock().instant(), traversalId, null,
                        OpaquePayload.empty("application/octet-stream")))
                .recordIdempotency(new IdempotencyWrite("batch-1", fingerprint("request"),
                        fingerprint("outcome"), Duration.ofMinutes(10), clock().instant()))
                .build())));
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class, failure);

        StoredProcessInstance unchanged = await(store().load(key));
        assertEquals(created.revision(), unchanged.revision());
        assertEquals(ProcessInstanceStatus.ACCEPTED, unchanged.state().status());
        assertTrue(await(store().claimDueTimers(DEFAULT_TENANT, "worker-1", 10, TTL)).isEmpty());
        assertEquals(0L, await(store().idempotencyRecordCount(DEFAULT_TENANT)));
    }

    @Test
    final void aLegalMultiOperationBatchAppliesEveryOperationTogether() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));

        UUID timerId = UUID.randomUUID();
        StoredProcessInstance applied = await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .scheduleTimer(new TimerSchedule(timerId, clock().instant(), traversalId, null,
                        OpaquePayload.empty("application/octet-stream")))
                .recordIdempotency(new IdempotencyWrite("batch-ok", fingerprint("request"),
                        fingerprint("outcome"), Duration.ofMinutes(10), clock().instant()))
                .build()));

        assertEquals(ProcessInstanceStatus.RUNNING, applied.state().status());
        clock().advance(Duration.ofSeconds(1));
        assertEquals(1, await(store().claimDueTimers(DEFAULT_TENANT, "worker-1", 10, TTL)).size());
        assertEquals(fingerprint("outcome"),
                await(store().lookupIdempotency(key.tenantId(), "batch-ok", clock().instant()))
                        .orElseThrow().outcomeRef());
    }

    @Test
    final void theGraphVersionPinIsWriteOnceAndRejectedOnChange() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        await(store().apply(creationBatch(key, traversalId, "graph-v1")));

        // There is no operation that sets the pin except ProcessCreated, and ProcessCreated is legal
        // only against an absent instance (ExecutionTransition's own contract): the aggregate itself,
        // not merely this adapter, refuses to fold a second creation over an existing one. Every
        // compliant adapter must therefore surface this as InvalidRequest.
        var failure = failureOf(() -> await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.any())
                .apply(new ExecutionTransition.ProcessCreated(acceptedInstance(key.processInstanceId(), traversalId),
                        new GraphVersionPin("graph-v2")))
                .build())));
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class, failure);
        assertEquals(new GraphVersionPin("graph-v1"), await(store().load(key)).graphVersionPin());

        // The duplicate-creation route, using the expectation designed for exactly-once creation.
        var duplicate = failureOf(() -> await(store().apply(creationBatch(key, traversalId, "graph-v2"))));
        assertInstanceOf(ExecutionStoreFailure.AlreadyExists.class, duplicate);
        assertEquals(new GraphVersionPin("graph-v1"), await(store().load(key)).graphVersionPin());
    }

    @Test
    final void payloadsBeyondTheDeclaredLimitAreRejectedWithADedicatedFailure() {
        int limit = store().maxPayloadBytes();
        Assumptions.assumeTrue(limit < 64 * 1024 * 1024,
                "declared payload limit is too large to safely exceed in a test");

        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        await(store().apply(creationBatch(key, traversalId, "graph-v1")));

        var failure = failureOf(() -> await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.any())
                .scheduleTimer(new TimerSchedule(UUID.randomUUID(), clock().instant(), traversalId, null,
                        OpaquePayload.of(new byte[limit + 1], "application/octet-stream")))
                .build())));

        var tooLarge = assertInstanceOf(ExecutionStoreFailure.PayloadTooLarge.class, failure);
        assertEquals(limit, tooLarge.limitBytes());
    }

    // ================================================================== sections 4 & 5: lease and fencing

    @Test
    final void leaseTtlAboveTheDeclaredMaximumIsRejectedSoACrashCannotStickForever() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        await(store().apply(creationBatch(key, traversalId, "graph-v1")));

        Duration tooLong = store().maxLeaseTtl().plusSeconds(1);
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                failureOf(() -> await(store().claim(key, "worker-1", tooLong))));
    }

    @Test
    final void claimIsExclusiveUntilItExpiresLazilyOnTheStoreClock() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        await(store().apply(creationBatch(key, traversalId, "graph-v1")));

        LeaseHandle held = await(store().claim(key, "worker-1", TTL));
        assertEquals(clock().instant(), held.claimedAt());
        assertEquals(clock().instant().plus(TTL), held.expiresAt());

        // Failing to ACQUIRE a lease another worker validly holds is ordinary contention, never the
        // loss of a lease the caller held (ADR 0010 section 12.2): LeaseLost stays reserved for a
        // rare, serious event, so an alert on it remains trustworthy instead of drowning in routine
        // contention noise. expiresAt is populated with the actual holder's expiry, not merely
        // present, so a caller can retry when retrying is sensible instead of busy-looping.
        var busy = assertInstanceOf(ExecutionStoreFailure.LeaseHeldByAnother.class,
                failureOf(() -> await(store().claim(key, "worker-2", TTL))));
        assertEquals("worker-1", busy.holderWorkerId());
        assertEquals(held.expiresAt(), busy.expiresAt());
        assertEquals(Retryability.RETRYABLE_NO_EFFECT, busy.retryability());

        // The field is load-bearing, not decorative: advancing the clock to EXACTLY the reported
        // expiry -- not "sometime comfortably later" -- must already be enough to succeed. A caller
        // that trusted a looser reading of expiresAt would still be busy-looping at this instant.
        clock().set(busy.expiresAt());
        LeaseHandle taken = await(store().claim(key, "worker-2", TTL));
        assertTrue(taken.fencingToken() > held.fencingToken(),
                "a new claimant after expiry must receive a strictly greater fencing token");
    }

    @Test
    final void renewExtendsTheWindowWithoutIssuingANewToken() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        LeaseHandle held = await(store().claim(key, "worker-1", TTL));

        clock().advance(Duration.ofSeconds(10));
        LeaseHandle renewed = await(store().renew(held, TTL));

        assertEquals(held.fencingToken(), renewed.fencingToken(), "renewal extends the window; it "
                + "must not rotate the token, or it would fence out the very holder it is renewing");
        assertEquals(clock().instant().plus(TTL), renewed.expiresAt());
    }

    @Test
    final void renewingALostLeaseFails() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        LeaseHandle held = await(store().claim(key, "worker-1", TTL));

        clock().advance(TTL.plusSeconds(1));
        await(store().claim(key, "worker-2", TTL));

        assertInstanceOf(ExecutionStoreFailure.LeaseLost.class,
                failureOf(() -> await(store().renew(held, TTL))));
    }

    @Test
    final void releasingAHeldLeaseAllowsAnImmediateReclaimWithoutWaitingForExpiry() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        LeaseHandle held = await(store().claim(key, "worker-1", TTL));

        await(store().release(held));
        LeaseHandle taken = await(store().claim(key, "worker-2", TTL));
        assertTrue(taken.fencingToken() > held.fencingToken());
    }

    @Test
    final void releasingAnAlreadyLostLeaseIsANoOpNotAFailure() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        LeaseHandle held = await(store().claim(key, "worker-1", TTL));

        clock().advance(TTL.plusSeconds(1));
        await(store().claim(key, "worker-2", TTL));

        assertDoesNotThrow(() -> await(store().release(held)));
    }

    @Test
    final void leaseEnumerationReflectsOnlyCurrentlyLiveLeasesWithoutABackgroundReaper() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        await(store().apply(creationBatch(key, traversalId, "graph-v1")));

        assertTrue(await(store().leases(DEFAULT_TENANT)).isEmpty());
        LeaseHandle held = await(store().claim(key, "worker-1", TTL));

        List<LeaseHandle> active = await(store().leases(DEFAULT_TENANT));
        assertEquals(1, active.size());
        assertEquals(held.fencingToken(), active.get(0).fencingToken());
        assertEquals(held.expiresAt(), active.get(0).expiresAt());

        clock().advance(TTL.plusSeconds(1));
        assertTrue(await(store().leases(DEFAULT_TENANT)).isEmpty(),
                "lazy expiry must be visible through enumeration, otherwise a dead lease in a "
                        + "low-contention deployment is stuck with nothing to alert on");
    }

    @Test
    final void fencingRejectsAnyTokenThatIsNotCurrentIncludingAForgedHigherToken() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        await(store().apply(creationBatch(key, traversalId, "graph-v1")));

        LeaseHandle first = await(store().claim(key, "worker-1", TTL));
        clock().advance(TTL.plusSeconds(1));
        LeaseHandle second = await(store().claim(key, "worker-2", TTL));

        // The superseded holder's now-stale token.
        assertInstanceOf(ExecutionStoreFailure.FencedOut.class, failureOf(() -> await(store().apply(
                ExecutionBatch.to(key).expecting(RevisionExpectation.any()).fencedBy(first)
                        .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                        .build()))));

        // A forged token higher than the current one. Rejecting only lower tokens would let this
        // through and let the impostor fence out the legitimate holder -- the exact hole ADR 0010
        // section 5 names and closes with the "!= current", not "< current", rejection rule.
        assertInstanceOf(ExecutionStoreFailure.FencedOut.class, failureOf(() -> await(store().apply(
                ExecutionBatch.to(key).expecting(RevisionExpectation.any())
                        .fencedBy(second.fencingToken() + 1000)
                        .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                        .build()))));

        // The genuine current holder still writes.
        StoredProcessInstance applied = await(store().apply(
                ExecutionBatch.to(key).expecting(RevisionExpectation.any()).fencedBy(second)
                        .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                        .build()));
        assertEquals(ProcessInstanceStatus.RUNNING, applied.state().status());
    }

    @Test
    final void fencedOutAndConcurrencyConflictAreNeverTheSameFailureType() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        LeaseHandle lease = await(store().claim(key, "worker-1", TTL));

        await(store().apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(created.revision()))
                .fencedBy(lease)
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .build()));

        // A stale revision expectation with NO fencing token presented: the caller never claimed to
        // hold a lease, so this must be ConcurrencyConflict, never FencedOut.
        var staleRevision = failureOf(() -> await(store().apply(
                ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(created.revision()))
                        .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                        .build())));
        assertInstanceOf(ExecutionStoreFailure.ConcurrencyConflict.class, staleRevision);

        // A correct revision expectation but a stale fencing token: the write target is current, only
        // the claimed ownership is not, so this must be FencedOut, never ConcurrencyConflict. The
        // transition used (RUNNING -> WAITING) is legal on its own, so if fencing were not actually
        // enforced the write would silently succeed instead of merely failing for an unrelated reason.
        clock().advance(TTL.plusSeconds(1));
        await(store().claim(key, "worker-2", TTL));
        StoredProcessInstance current = await(store().load(key));
        var staleFence = failureOf(() -> await(store().apply(
                ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(current.revision()))
                        .fencedBy(lease)
                        .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.WAITING))
                        .build())));
        assertInstanceOf(ExecutionStoreFailure.FencedOut.class, staleFence);
        assertEquals(ProcessInstanceStatus.RUNNING, await(store().load(key)).state().status(),
                "the stale-fenced write must not have applied");

        assertNotEquals(staleRevision.getClass(), staleFence.getClass(),
                "FencedOut and ConcurrencyConflict must never collapse into one failure type: the "
                        + "correct caller response is opposite -- abandon versus re-read and retry");

        // ADR 0010 section 12.1 requires the distinction to survive coarsening too: RETRY_AFTER_REREAD
        // obliges the caller to rebuild the request from fresh state before retrying, which a blind
        // identical retry (the obligation DETERMINISTIC_REJECT and RETRYABLE_NO_EFFECT both permit in
        // their own way) can never satisfy for a stale revision expectation.
        assertEquals(Retryability.RETRY_AFTER_REREAD, staleRevision.retryability());
        assertEquals(Retryability.DETERMINISTIC_REJECT, staleFence.retryability());
        assertNotEquals(staleRevision.retryability(), staleFence.retryability(),
                "the two failures must be distinguishable on the coarse classification alone, not "
                        + "merely by pattern-matching the concrete type");
    }

    // ============================================== section 13.2: write-path check ordering

    /**
     * Existence is a <em>precondition</em> of fencing, not a competitor to it (ADR 0010 section 13.2).
     *
     * <p>All three directions are asserted together on purpose. The first two pin the ruling; the
     * third is the regression guard proving the existence precedence did not swallow real fencing,
     * and a future reader who changes one must see the other two immediately.</p>
     */
    @Test
    final void aTokenAgainstANonExistentInstanceIsNotFoundBecauseNoLeaseCouldEverHaveExisted() {
        ExecutionKey neverCreated = newKey();

        // (1) A fencing token against an instance that was never created. FencedOut would have to
        // report a current token of zero -- a fabricated value standing in for the absence of any
        // token, the same defect as the nil-UUID key section 6.3 removed -- and would tell an
        // operator that another worker took over when nobody did, sending them hunting for a
        // competing worker that does not exist.
        var withToken = failureOf(() -> await(store().apply(ExecutionBatch.to(neverCreated)
                .expecting(RevisionExpectation.any())
                .fencedBy(7L)
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .build())));
        assertInstanceOf(ExecutionStoreFailure.NotFound.class, withToken);

        // (2) The same batch with NO token. One rule, not two readings of one rule.
        var withoutToken = failureOf(() -> await(store().apply(ExecutionBatch.to(neverCreated)
                .expecting(RevisionExpectation.any())
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .build())));
        assertInstanceOf(ExecutionStoreFailure.NotFound.class, withoutToken);
        assertEquals(withToken.getClass(), withoutToken.getClass(),
                "presenting a token must not change the classification when the instance is absent: "
                        + "a token is a claim about a lease, and a lease on a nonexistent instance "
                        + "is not stale, it is impossible");

        // (3) The regression guard. Against an instance that DOES exist, a stale token is still
        // FencedOut -- the existence precedence must not have swallowed real fencing.
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        LeaseHandle first = await(store().claim(key, "worker-1", TTL));
        clock().advance(TTL.plusSeconds(1));
        await(store().claim(key, "worker-2", TTL));

        var staleOnExisting = failureOf(() -> await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.any())
                .fencedBy(first)
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .build())));
        assertInstanceOf(ExecutionStoreFailure.FencedOut.class, staleOnExisting);
        assertEquals(Retryability.DETERMINISTIC_REJECT, staleOnExisting.retryability());
        // Both rejections coarsen identically, which is why the ruling costs the fence nothing: a
        // fenced worker stops either way and no successful answer can reach it.
        assertEquals(withToken.retryability(), staleOnExisting.retryability());
    }

    /**
     * Assertion 5 of ADR 0010 section 13.2: asserting {@code NotPresent} while presenting a fencing
     * token is self-contradictory and must be rejected, never accepted with the token ignored.
     *
     * <p>This is the defect the rule replaced. The store used to create the instance and disregard
     * the token in silence: the caller received a success, believed it had written under a fence,
     * and nothing reached a log or an operator. The ADR's earlier justification — that such a batch
     * "presents no fencing token" — was a statement about what callers happen to do, not a rule
     * about what the store does, and nothing prevented one from presenting a token anyway.</p>
     *
     * <p>The classification is decided from the request alone (section 12.3): a token is issued only
     * by a successful claim, and a claim requires the instance to exist, so a caller holding a
     * genuine token holds proof of the very existence {@code NotPresent} denies. The contradiction
     * holds for <em>every</em> possible stored state, which is what separates it from a violated
     * existence expectation — that one is state-dependent, and is {@code AlreadyExists} or
     * {@code NotFound}. {@code FencedOut} would be wrong for the reason section 13.2 already gave:
     * with no instance there is no current token, so the member could only carry a fabricated
     * one.</p>
     *
     * <p>Both sub-cases are asserted because the rule is about the expectation and the token, not
     * about {@code ProcessCreated}: a batch is equally contradictory without a creation
     * transition.</p>
     */
    @Test
    final void assertingNotPresentWhilePresentingAFencingTokenIsAnInvalidRequest() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();

        // (1) The reported case: a creation batch that also carries a token.
        var creationWithToken = failureOf(() -> await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .fencedBy(7L)
                .apply(new ExecutionTransition.ProcessCreated(
                        acceptedInstance(key.processInstanceId(), traversalId),
                        new GraphVersionPin("graph-v1")))
                .build())));
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class, creationWithToken,
                "a batch asserting NotPresent while presenting a fencing token contradicts itself "
                        + "for every possible stored state, so it is a caller bug decidable from the "
                        + "request alone, not a state-dependent existence failure");
        assertEquals(Retryability.DETERMINISTIC_REJECT, creationWithToken.retryability());

        // (2) The rule is about the expectation and the token, not about ProcessCreated.
        var mutationWithToken = failureOf(() -> await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .fencedBy(7L)
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .build())));
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class, mutationWithToken);

        // The rejection applied nothing. A silent creation followed by a loud error would be a worse
        // defect than the silent acceptance being fixed, because the caller would then be wrong
        // about the instance existing AND about the write having failed.
        assertInstanceOf(ExecutionStoreFailure.NotFound.class,
                failureOf(() -> await(store().load(key))),
                "a rejected batch must apply nothing at all");
    }

    /**
     * Assertion 6 of ADR 0010 section 13.2: the regression guard for the narrowing.
     *
     * <p>The rejection above must catch the contradiction specifically, not creation in general. An
     * over-broad guard — one keyed on {@code ProcessCreated}, or on {@code NotPresent} alone —
     * would reject the ordinary creation path that every caller in the tree uses, so this asserts
     * that a tokenless creation batch still succeeds and still creates the instance, exactly as it
     * did before the rule existed.</p>
     */
    @Test
    final void aCreationBatchPresentingNoFencingTokenIsUnaffectedByThatRule() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();

        StoredProcessInstance created = await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(
                        acceptedInstance(key.processInstanceId(), traversalId),
                        new GraphVersionPin("graph-v1")))
                .build()));

        assertEquals(ProcessInstanceStatus.ACCEPTED, created.state().status());
        assertEquals(ProcessInstanceStatus.ACCEPTED, await(store().load(key)).state().status());

        // And NotPresent still means what it meant: a second creation is AlreadyExists, the
        // state-dependent existence failure, which the new request-only rule must not have displaced.
        assertInstanceOf(ExecutionStoreFailure.AlreadyExists.class,
                failureOf(() -> await(store().apply(creationBatch(key, traversalId, "graph-v1")))),
                "the request-only rule must not displace the state-dependent existence failure");
    }

    /** Assertion 4 of section 13.2: a fenced worker must not be answered from its own record. */
    @Test
    final void aFencedOutWorkerIsRejectedBeforeItsIdempotencyRecordIsEvenConsulted() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        LeaseHandle first = await(store().claim(key, "worker-1", TTL));

        var write = new IdempotencyWrite("submit-1", fingerprint("request"), fingerprint("outcome"),
                Duration.ofMinutes(10), clock().instant());
        StoredProcessInstance applied = await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .fencedBy(first)
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .recordIdempotency(write)
                .build()));

        // Ownership moves to worker-2. worker-1 is now fenced out and must stop.
        clock().advance(TTL.plusSeconds(1));
        LeaseHandle second = await(store().claim(key, "worker-2", TTL));

        // worker-1 retries the IDENTICAL batch. Its idempotency record exists and matches, so a
        // store that consulted the record first would answer it successfully -- telling a fenced
        // worker its work landed and letting it briefly believe it still owns the instance, which is
        // exactly the split-brain belief the fence exists to destroy. Fencing is evaluated first,
        // always: the fenced worker does not need this outcome, because it is no longer the owner.
        var failure = failureOf(() -> await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .fencedBy(first)
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .recordIdempotency(write)
                .build())));
        assertInstanceOf(ExecutionStoreFailure.FencedOut.class, failure,
                "fencing governs WHO MAY ACT and is checked before replay, so a fenced worker is "
                        + "rejected rather than answered from the record");

        // The new owner, which holds a current token, does get the replay -- it is the party that
        // actually needs the outcome. Its expectation is stale by construction (the recorded write
        // necessarily bumped the revision), which is precisely why the expectation is checked LAST:
        // checking it first would make every legitimate replay fail with ConcurrencyConflict.
        StoredProcessInstance replayed = await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .fencedBy(second)
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .recordIdempotency(write)
                .build()));
        assertEquals(applied.revision(), replayed.revision());
    }

    // ================================================================== sections 7 & 9: pending work

    @Test
    final void scheduledAttemptsBecomeClaimableProjectionsCarryingIdentitiesAndCounters() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        scheduleRunningAttempt(key, traversalId, invocationId, attemptId);

        List<PendingWork> claimed = await(store().claimPendingWork(DEFAULT_TENANT, "worker-1", 10, TTL));
        assertEquals(1, claimed.size());
        var dispatch = assertInstanceOf(PendingWork.AttemptDispatch.class, claimed.get(0));
        assertEquals(key, dispatch.key());
        assertEquals(traversalId, dispatch.traversalId());
        assertEquals(invocationId, dispatch.invocationId());
        assertEquals(attemptId, dispatch.attemptId());
        assertEquals(1, dispatch.attemptOrdinal());
        assertEquals(1, dispatch.deliveryAttempt());
        assertEquals(NodeCommand.PROCESS, dispatch.command(), "legacy invocations default to PROCESS");
        assertTrue(dispatch.fencingToken() > 0);
        // A projection, never the aggregate: PendingWork.AttemptDispatch has no ProcessInstance
        // component at all, so the caller has no choice but to load() explicitly. That is a property
        // of the sealed type itself (ADR 0010 section 9), not something a runtime assertion adds to.
    }

    @Test
    final void namedCommandsSurviveAggregateReloadAndAttemptRedelivery() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        NodeCommand command = NodeCommand.application("correggi");
        scheduleRunningAttempt(key, traversalId, invocationId, attemptId, command);

        NodeInvocation reloaded = await(store().load(key)).state().traversals().get(traversalId)
                .invocations().get(invocationId);
        assertEquals(command, reloaded.command());

        var first = assertInstanceOf(PendingWork.AttemptDispatch.class,
                await(store().claimPendingWork(DEFAULT_TENANT, "worker-1", 10, TTL)).getFirst());
        assertEquals(command, first.command());
        clock().advance(TTL.plusSeconds(1));
        var redelivered = assertInstanceOf(PendingWork.AttemptDispatch.class,
                await(store().claimPendingWork(DEFAULT_TENANT, "worker-2", 10, TTL)).getFirst());
        assertEquals(command, redelivered.command());
        assertEquals(2, redelivered.deliveryAttempt());
    }

    @Test
    final void pendingWorkIsRedeliveredAtLeastOnceUntilAcknowledged() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        scheduleRunningAttempt(key, traversalId, invocationId, attemptId);

        assertEquals(1, await(store().claimPendingWork(DEFAULT_TENANT, "worker-1", 10, TTL)).size());

        // Invisible while the visibility window holds: two concurrent pollers, or one poller retried
        // after a slow call, must not both dispatch the same attempt.
        assertTrue(await(store().claimPendingWork(DEFAULT_TENANT, "worker-1", 10, TTL)).isEmpty());

        clock().advance(TTL.plusSeconds(1));
        var redelivered = await(store().claimPendingWork(DEFAULT_TENANT, "worker-1", 10, TTL));
        assertEquals(1, redelivered.size());
        assertEquals(2, redelivered.get(0).deliveryAttempt());

        await(store().ack(redelivered.get(0)));
        clock().advance(TTL.plusSeconds(1));
        assertTrue(await(store().claimPendingWork(DEFAULT_TENANT, "worker-1", 10, TTL)).isEmpty(),
                "ack is the only path to permanent removal");
    }

    @Test
    final void contendedInstancesAreNotDoubleClaimedByADifferentWorkerWhileALeaseIsLive() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        scheduleRunningAttempt(key, traversalId, invocationId, attemptId);

        var byFirstWorker = await(store().claimPendingWork(DEFAULT_TENANT, "worker-1", 10, TTL));
        assertEquals(1, byFirstWorker.size());

        var bySecondWorker = await(store().claimPendingWork(DEFAULT_TENANT, "worker-2", 10, TTL));
        assertTrue(bySecondWorker.isEmpty(), "a live lease must not be claimed out from under its holder");

        clock().advance(TTL.plusSeconds(1));
        var afterExpiry = await(store().claimPendingWork(DEFAULT_TENANT, "worker-2", 10, TTL));
        assertEquals(1, afterExpiry.size());
        assertTrue(afterExpiry.get(0).fencingToken() > byFirstWorker.get(0).fencingToken());
    }

    @Test
    final void claimRespectsTheLimitAcrossMixedWorkKindsWithoutLosingEither() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        StoredProcessInstance running = scheduleRunningAttempt(key, traversalId, invocationId, attemptId);

        UUID timerId = UUID.randomUUID();
        await(store().apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(running.revision()))
                .scheduleTimer(new TimerSchedule(timerId, clock().instant(), traversalId, null,
                        OpaquePayload.empty("application/octet-stream")))
                .build()));

        var firstCall = await(store().claimPendingWork(DEFAULT_TENANT, "worker-1", 1, TTL));
        var secondCall = await(store().claimPendingWork(DEFAULT_TENANT, "worker-1", 1, TTL));
        assertEquals(1, firstCall.size());
        assertEquals(1, secondCall.size());

        // No ordering guarantee across kinds: both must eventually surface, in whichever order a
        // limit of one interleaves them.
        Set<Class<?>> kinds = Set.of(firstCall.get(0).getClass(), secondCall.get(0).getClass());
        assertEquals(Set.of(PendingWork.AttemptDispatch.class, PendingWork.TimerDue.class), kinds);
        assertTrue(await(store().claimPendingWork(DEFAULT_TENANT, "worker-1", 10, TTL)).isEmpty());
    }

    @Test
    final void claimAndAckAreSeparateOperationsSoACrashBetweenThemReexposesTheTimer() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        UUID timerId = UUID.randomUUID();
        await(store().apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(created.revision()))
                .scheduleTimer(new TimerSchedule(timerId, clock().instant(), traversalId, null,
                        OpaquePayload.of("wake".getBytes(StandardCharsets.UTF_8), "text/plain")))
                .build()));

        var due = await(store().claimDueTimers(DEFAULT_TENANT, "worker-1", 10, TTL));
        assertEquals(1, due.size());
        // Simulated crash: no ack call happens here, on purpose.

        clock().advance(TTL.plusSeconds(1));
        var reexposed = await(store().claimDueTimers(DEFAULT_TENANT, "worker-1", 10, TTL));
        assertEquals(1, reexposed.size(), "a timer claimed but never acked must come back, never vanish");
        assertEquals(timerId, reexposed.get(0).workItemId());
        assertEquals(2, reexposed.get(0).deliveryAttempt());

        await(store().ack(reexposed.get(0)));
        clock().advance(TTL.plusSeconds(1));
        assertTrue(await(store().claimDueTimers(DEFAULT_TENANT, "worker-1", 10, TTL)).isEmpty());
    }

    @Test
    final void timerDueInstantIsTheOriginalScheduleNotTheClaimInstant() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        Instant dueAt = clock().instant().plusSeconds(60);
        UUID timerId = UUID.randomUUID();
        await(store().apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(created.revision()))
                .scheduleTimer(new TimerSchedule(timerId, dueAt, traversalId, null,
                        OpaquePayload.empty("application/octet-stream")))
                .build()));

        assertTrue(await(store().claimDueTimers(DEFAULT_TENANT, "worker-1", 10, TTL)).isEmpty());

        // A large, uneven lag so a claim instant could never be mistaken for the due instant.
        clock().advance(Duration.ofHours(3).plusSeconds(37));
        var claimed = await(store().claimDueTimers(DEFAULT_TENANT, "worker-1", 10, TTL));
        assertEquals(dueAt, claimed.get(0).dueAt(),
                "lag must be computable as now - dueAt; reporting the claim instant would hide it");
    }

    @Test
    final void cancellingATimerRemovesItBeforeItCanFire() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        UUID timerId = UUID.randomUUID();
        StoredProcessInstance scheduled = await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .scheduleTimer(new TimerSchedule(timerId, clock().instant(), traversalId, null,
                        OpaquePayload.empty("application/octet-stream")))
                .build()));

        await(store().apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(scheduled.revision()))
                .cancelTimer(timerId)
                .build()));

        assertTrue(await(store().claimDueTimers(DEFAULT_TENANT, "worker-1", 10, TTL)).isEmpty());
    }

    // ====================================== PERS-04: ambiguous work is parked (ADR 0022)

    /**
     * The crash case must be <em>reachable</em>, which is a claim-query property and not a runtime one.
     *
     * <p>A worker that claimed a scheduled attempt, persisted {@code RUNNING} and then died leaves the
     * attempt in {@code RUNNING} forever. If the claim query only ever returned {@code SCHEDULED}
     * attempts, that work would never be handed out again — the ambiguity rule could never fire, and
     * the crash would silently lose the work, which this rule prevents. So an
     * adapter must redeliver a {@code RUNNING} attempt once its visibility window elapses.</p>
     */
    @Test
    final void aRunningAttemptIsRedeliveredOnceItsVisibilityWindowElapsesSoTheCrashCaseIsReachable() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        StoredProcessInstance scheduled = scheduleRunningAttempt(key, traversalId, invocationId, attemptId);

        List<PendingWork> first = await(store().claimPendingWork(DEFAULT_TENANT, "worker-1", 10, TTL));
        assertEquals(1, first.size());
        assertEquals(1, first.get(0).deliveryAttempt());

        // The write-ordering invariant: RUNNING is committed under the fence before the engine send.
        await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(scheduled.revision()))
                .fencedBy(first.get(0).fencingToken())
                .apply(new ExecutionTransition.AttemptTransitioned(traversalId, invocationId, attemptId,
                        NodeAttemptStatus.RUNNING))
                .build()));

        clock().advance(TTL.plusSeconds(1));
        List<PendingWork> redelivered = await(store().claimPendingWork(DEFAULT_TENANT, "worker-1", 10, TTL));
        assertEquals(1, redelivered.size(),
                "a RUNNING attempt whose worker died must become claimable again, or the crash case "
                        + "is permanently invisible and the work is lost");
        assertEquals(attemptId, ((PendingWork.AttemptDispatch) redelivered.get(0)).attemptId());
        assertEquals(2, redelivered.get(0).deliveryAttempt(),
                "the rising delivery counter is what a recovering runtime reads as 'we dispatched "
                        + "this and never learned the outcome'");
    }

    /**
     * Parking is where the machine stops deciding. An adapter that redelivered a parked attempt would
     * hand the same unknown effect back to the recovery loop on every poll, and the loop would park it
     * again forever — a livelock whose only visible symptom is an ever-rising delivery counter.
     */
    @Test
    final void aParkedAttemptLeavesTheClaimLoopAndIsNeverRedelivered() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        StoredProcessInstance scheduled = scheduleRunningAttempt(key, traversalId, invocationId, attemptId);

        PendingWork claimed = await(store().claimPendingWork(DEFAULT_TENANT, "worker-1", 10, TTL)).get(0);
        StoredProcessInstance running = await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(scheduled.revision()))
                .fencedBy(claimed.fencingToken())
                .apply(new ExecutionTransition.AttemptTransitioned(traversalId, invocationId, attemptId,
                        NodeAttemptStatus.RUNNING))
                .build()));
        await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(running.revision()))
                .fencedBy(claimed.fencingToken())
                .apply(new ExecutionTransition.AttemptParked(traversalId, invocationId, attemptId,
                        "dispatched with unknown outcome"))
                .build()));

        clock().advance(TTL.multipliedBy(10));
        assertTrue(await(store().claimPendingWork(DEFAULT_TENANT, "worker-1", 10, TTL)).isEmpty(),
                "a parked attempt waits for a human, not for the next poll");
    }

    /** The cause is the only thing the human resolving the park has to go on, so it must be durable. */
    @Test
    final void aParkedAttemptAndItsCauseSurviveAReopen() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        parkTheOnlyAttempt(key, traversalId, invocationId, attemptId, "smtp outcome never acknowledged");

        assumeCapability(StoreCapability.DURABLE);
        reopen();

        NodeAttempt reloaded = onlyAttempt(await(store().load(key)));
        assertEquals(NodeAttemptStatus.PARKED, reloaded.status());
        assertEquals("smtp outcome never acknowledged", reloaded.parkCause());
        assertNull(reloaded.completion(), "a park is not a completion");
    }

    /**
     * Resolution is a decision about the past, and each of the three shapes must be expressible
     * through the port. Completing a park must never be reported as an observed success.
     */
    @Test
    final void aParkResolvesToVerifiedOrFailedOrToARetryThatIsANewEffectIdentity() {
        ExecutionKey verifiedKey = newKey();
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        StoredProcessInstance parked =
                parkTheOnlyAttempt(verifiedKey, traversalId, invocationId, attemptId, "unknown");
        await(store().apply(ExecutionBatch.to(verifiedKey)
                .expecting(RevisionExpectation.exactly(parked.revision()))
                .apply(new ExecutionTransition.ParkResolvedCompleted(traversalId, invocationId, attemptId))
                .build()));
        NodeAttempt verified = onlyAttempt(await(store().load(verifiedKey)));
        assertEquals(NodeAttemptStatus.COMPLETED, verified.status());
        assertEquals(NodeAttemptCompletion.OPERATOR_VERIFIED, verified.completion(),
                "an operator's assertion about the past is a different provenance from an observation");

        ExecutionKey failedKey = newKey();
        UUID failedTraversal = UUID.randomUUID();
        UUID failedInvocation = UUID.randomUUID();
        UUID failedAttempt = UUID.randomUUID();
        StoredProcessInstance parkedFailed =
                parkTheOnlyAttempt(failedKey, failedTraversal, failedInvocation, failedAttempt, "unknown");
        await(store().apply(ExecutionBatch.to(failedKey)
                .expecting(RevisionExpectation.exactly(parkedFailed.revision()))
                .apply(new ExecutionTransition.ParkResolvedFailed(failedTraversal, failedInvocation,
                        failedAttempt))
                .build()));
        assertEquals(NodeAttemptStatus.FAILED, onlyAttempt(await(store().load(failedKey))).status());

        ExecutionKey retryKey = newKey();
        UUID retryTraversal = UUID.randomUUID();
        UUID retryInvocation = UUID.randomUUID();
        UUID firstAttempt = UUID.randomUUID();
        UUID secondAttempt = UUID.randomUUID();
        StoredProcessInstance parkedRetry =
                parkTheOnlyAttempt(retryKey, retryTraversal, retryInvocation, firstAttempt, "unknown");
        await(store().apply(ExecutionBatch.to(retryKey)
                .expecting(RevisionExpectation.exactly(parkedRetry.revision()))
                .apply(new ExecutionTransition.ParkResolvedWithRetry(retryTraversal, retryInvocation,
                        firstAttempt, new NodeAttempt(secondAttempt, 2, NodeAttemptStatus.SCHEDULED)))
                .build()));

        List<NodeAttempt> attempts = attemptsOf(await(store().load(retryKey)), retryTraversal, retryInvocation);
        assertEquals(2, attempts.size(), "resolve-and-append is one step, so neither half can be lost");
        assertEquals(NodeAttemptStatus.FAILED, attempts.get(0).status());
        assertNull(attempts.get(0).parkCause(), "a resolved attempt is no longer parked");
        assertEquals(NodeAttemptStatus.SCHEDULED, attempts.get(1).status());

        // The retry is new work under a new identity, which is what makes it a new effect.
        List<PendingWork> retryWork = await(store().claimPendingWork(retryKey.tenantId(), "worker-1", 10, TTL));
        assertEquals(1, retryWork.size());
        assertEquals(secondAttempt, ((PendingWork.AttemptDispatch) retryWork.get(0)).attemptId());
    }

    /** An illegal resolution applies neither half, so a rejected retry cannot half-land. */
    @Test
    final void aRejectedParkResolutionLeavesTheParkExactlyWhereItWas() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        StoredProcessInstance parked = parkTheOnlyAttempt(key, traversalId, invocationId, attemptId, "unknown");

        ExecutionStoreFailure rejected = failureOf(() -> await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(parked.revision()))
                // A retry whose next attempt is already RUNNING would smuggle an unknown effect back
                // into execution without anybody deciding about the first one.
                .apply(new ExecutionTransition.ParkResolvedWithRetry(traversalId, invocationId, attemptId,
                        new NodeAttempt(UUID.randomUUID(), 2, NodeAttemptStatus.RUNNING)))
                .build())));
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class, rejected);

        List<NodeAttempt> attempts = attemptsOf(await(store().load(key)), traversalId, invocationId);
        assertEquals(1, attempts.size());
        assertEquals(NodeAttemptStatus.PARKED, attempts.get(0).status());
        assertEquals("unknown", attempts.get(0).parkCause());
    }

    /** Drives one attempt from SCHEDULED through RUNNING to PARKED and returns the parked envelope. */
    private StoredProcessInstance parkTheOnlyAttempt(ExecutionKey key, UUID traversalId, UUID invocationId,
                                                     UUID attemptId, String cause) {
        StoredProcessInstance scheduled = scheduleRunningAttempt(key, traversalId, invocationId, attemptId);
        StoredProcessInstance running = await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(scheduled.revision()))
                .apply(new ExecutionTransition.AttemptTransitioned(traversalId, invocationId, attemptId,
                        NodeAttemptStatus.RUNNING))
                .build()));
        return await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(running.revision()))
                .apply(new ExecutionTransition.AttemptParked(traversalId, invocationId, attemptId, cause))
                .build()));
    }

    /**
     * The orchestration retry's write, held to the same standard as the park resolution above.
     *
     * <p>An orchestration retry is not a distinct transition type: it is
     * {@code AttemptTransitioned(FAILED)} followed by {@code AttemptAdded(next)} in one batch, and it
     * relies on three properties of the port that were true before this test existed and were never
     * asserted together for this shape. All three are asserted here, in both adapters, because the
     * runtime's crash-safety argument is built on them.</p>
     * <ol>
     *   <li><b>The pair is atomic.</b> The batch does not partially apply, so there is no instant at
     *       which the invocation has a failed attempt and no successor.</li>
     *   <li><b>The invocation stays {@code RUNNING}.</b> Unlike a terminal failure, a retried
     *       invocation is a visit still in progress, and an aggregate that marked it {@code FAILED}
     *       would refuse the very attempt this batch appends.</li>
     *   <li><b>The successor is immediately claimable, and it is the only claimable item.</b> That is
     *       what makes a crash during the backoff recoverable: the retry is durably {@code SCHEDULED},
     *       which recovery reads as provably effect-free.</li>
     * </ol>
     */
    @Test
    final void anOrchestrationRetryFailsAndAppendsInOneStepAndLeavesTheInvocationRunning() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID firstAttempt = UUID.randomUUID();
        UUID secondAttempt = UUID.randomUUID();
        StoredProcessInstance scheduled = scheduleRunningAttempt(key, traversalId, invocationId, firstAttempt);
        StoredProcessInstance running = await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(scheduled.revision()))
                .apply(new ExecutionTransition.AttemptTransitioned(traversalId, invocationId, firstAttempt,
                        NodeAttemptStatus.RUNNING))
                .build()));

        await(store().apply(retryBatch(key, traversalId, invocationId, firstAttempt, secondAttempt,
                running.revision())));

        StoredProcessInstance afterRetry = await(store().load(key));
        List<NodeAttempt> attempts = attemptsOf(afterRetry, traversalId, invocationId);
        assertEquals(2, attempts.size(), "fail-and-append is one commit, so neither half can be lost");
        assertEquals(NodeAttemptStatus.FAILED, attempts.get(0).status());
        assertEquals(1, attempts.get(0).ordinal());
        assertEquals(NodeAttemptStatus.SCHEDULED, attempts.get(1).status());
        assertEquals(2, attempts.get(1).ordinal(), "a retry is the next ordinal, never a counter");
        assertEquals(NodeInvocationStatus.RUNNING,
                afterRetry.state().traversals().get(traversalId).invocations().get(invocationId).status(),
                "an invocation with a scheduled retry is a visit still in progress");

        List<PendingWork> claimed = await(store().claimPendingWork(key.tenantId(), "worker-1", 10, TTL));
        assertEquals(1, claimed.size(), "the failed attempt has left the claim loop, the retry has entered it");
        var dispatch = assertInstanceOf(PendingWork.AttemptDispatch.class, claimed.get(0));
        assertEquals(secondAttempt, dispatch.attemptId());
        assertEquals(2, dispatch.attemptOrdinal(),
                "the ordinal reaches a recovering worker on the claim, not only in the aggregate");
    }

    /**
     * Replaying the identical retry commit is refused, so a crash between the write and its
     * acknowledgement cannot produce a third attempt.
     *
     * <p>This is the exactly-once property the runtime depends on, and it is asserted through the
     * revision expectation rather than through an idempotency key on purpose: the retry decision is
     * made by a worker that already holds the instance's revision, so the cheapest correct guard is
     * the one it is already carrying. The second assertion is the independent domain guard behind it —
     * even with a revision that matched, the aggregate refuses an ordinal that is not exactly one past
     * its history — so the property does not rest on a single mechanism.</p>
     */
    @Test
    final void replayingARetryCommitCannotProduceASecondAppendedAttempt() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID firstAttempt = UUID.randomUUID();
        UUID secondAttempt = UUID.randomUUID();
        StoredProcessInstance scheduled = scheduleRunningAttempt(key, traversalId, invocationId, firstAttempt);
        StoredProcessInstance running = await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(scheduled.revision()))
                .apply(new ExecutionTransition.AttemptTransitioned(traversalId, invocationId, firstAttempt,
                        NodeAttemptStatus.RUNNING))
                .build()));
        StoredProcessInstance retried = await(store().apply(retryBatch(key, traversalId, invocationId,
                firstAttempt, secondAttempt, running.revision())));

        ExecutionStoreFailure staleReplay = failureOf(() -> await(store().apply(retryBatch(key, traversalId,
                invocationId, firstAttempt, UUID.randomUUID(), running.revision()))));
        assertInstanceOf(ExecutionStoreFailure.ConcurrencyConflict.class, staleReplay,
                "the revision the retry was decided at is gone, so the replay cannot land");

        ExecutionStoreFailure freshReplay = failureOf(() -> await(store().apply(retryBatch(key, traversalId,
                invocationId, firstAttempt, UUID.randomUUID(), retried.revision()))));
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class, freshReplay,
                "even at the current revision, the aggregate refuses to fail an attempt that already "
                        + "failed and to append an ordinal that already exists");

        assertEquals(2, attemptsOf(await(store().load(key)), traversalId, invocationId).size(),
                "neither refusal may leave a third attempt behind");
    }

    /** The retry commit both adapters must apply identically: fail the attempt, append the next. */
    private static ExecutionBatch retryBatch(ExecutionKey key, UUID traversalId, UUID invocationId,
                                             UUID failedAttemptId, UUID nextAttemptId, long revision) {
        return ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(revision))
                .apply(new ExecutionTransition.AttemptTransitioned(traversalId, invocationId, failedAttemptId,
                        NodeAttemptStatus.FAILED))
                .apply(new ExecutionTransition.AttemptAdded(traversalId, invocationId,
                        new NodeAttempt(nextAttemptId, 2, NodeAttemptStatus.SCHEDULED)))
                .build();
    }

    private static NodeAttempt onlyAttempt(StoredProcessInstance stored) {
        return stored.state().traversals().values().iterator().next()
                .invocations().values().iterator().next().attempts().getLast();
    }

    private static List<NodeAttempt> attemptsOf(StoredProcessInstance stored, UUID traversalId,
                                                UUID invocationId) {
        return stored.state().traversals().get(traversalId).invocations().get(invocationId).attempts();
    }

    // ================================================================== section 6: idempotency

    @Test
    final void anUnrecordedKeyIsAnEmptyResultNotAFailureSoTheCallerMayProceed() {
        // ADR 0010 section 6.3: absence is not a failure. This is the outcome of the FIRST ATTEMPT
        // of every idempotent operation -- the overwhelmingly common path -- so modelling it as a
        // thrown failure would allocate an exception on the hot path and force every caller to wrap
        // a catch to discover it may simply proceed. There is also no entity to name: an idempotency
        // lookup has no instance, which is why the earlier NotFound had to fabricate an ExecutionKey
        // with a nil UUID that then surfaced in describe() and the logs.
        assertEquals(Optional.empty(),
                await(store().lookupIdempotency(DEFAULT_TENANT, "never-written", clock().instant())),
                "an unrecorded key must be an empty result, never a failure");
    }

    @Test
    final void retentionWindowIsMandatoryOnTheWrite() {
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyWrite("k",
                fingerprint("request"), fingerprint("outcome"), null, EPOCH));
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyWrite("k",
                fingerprint("request"), fingerprint("outcome"), Duration.ZERO, EPOCH));
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyWrite("k",
                fingerprint("request"), fingerprint("outcome"), Duration.ofSeconds(-1), EPOCH));
    }

    @Test
    final void keyIssuedAtIsMandatoryOnTheWrite() {
        // Mandatory because it is what makes the ABSENCE of a record decidable against the store's
        // forgottenBefore watermark: without it, an adapter would have to invent its own notion of
        // issuance or fall back to a per-key marker, which is exactly the unbounded-growth mechanism
        // section 6.1 replaced.
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyWrite("k",
                fingerprint("request"), fingerprint("outcome"), Duration.ofMinutes(10), null));
    }

    @Test
    final void aMatchingReplayIsAnsweredFromTheRecordAndNeverReapplied() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        var write = new IdempotencyWrite("submit-1", fingerprint("request"), fingerprint("outcome"),
                Duration.ofMinutes(10), clock().instant());

        StoredProcessInstance applied = await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .recordIdempotency(write)
                .build()));

        // The aggregate MOVES between the idempotent write and the replay, by an unrelated write
        // carrying no idempotency key at all. Without this step the two readings of section 6.2 are
        // indistinguishable -- returning the current aggregate and returning the state as of
        // recordedAtRevision give the identical answer -- which is exactly how the ambiguity
        // survived review.
        StoredProcessInstance moved = await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(applied.revision()))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .build()));
        assertTrue(moved.revision() > applied.revision());

        StoredProcessInstance replayed = await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .recordIdempotency(write)
                .build()));

        // A successful replay is a success, and the aggregate did not move a second time. If it had
        // re-executed, the revision would have advanced again despite reusing a now-stale expectation.
        assertEquals(moved.revision(), replayed.revision(),
                "a replay answers with CURRENT state (section 6.2), not the state as of "
                        + "recordedAtRevision: its purpose is to let the caller PROCEED without "
                        + "re-executing, and a caller handed a stale revision derives an expectation "
                        + "from it and loops on ConcurrencyConflict forever");
        assertEquals(TraversalStatus.RUNNING, replayed.state().traversals().get(traversalId).status(),
                "the replay must reflect the mutation that happened after the recorded write");

        // recordedAtRevision remains the correlation handle -- it says WHEN the outcome was recorded
        // without pretending to hand back the state as of then.
        var record = await(store().lookupIdempotency(key.tenantId(), "submit-1", clock().instant()))
                .orElseThrow();
        assertEquals(fingerprint("outcome"), record.outcomeRef());
        assertEquals(applied.revision(), record.recordedAtRevision());
        assertTrue(replayed.revision() > record.recordedAtRevision());
    }

    @Test
    final void reusingAKeyForADifferentRequestFingerprintConflicts() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        await(store().apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .recordIdempotency(new IdempotencyWrite("submit-1", fingerprint("request"),
                        fingerprint("outcome"), Duration.ofMinutes(10), clock().instant()))
                .build()));

        var failure = failureOf(() -> await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.any())
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .recordIdempotency(new IdempotencyWrite("submit-1", fingerprint("a different request"),
                        fingerprint("outcome"), Duration.ofMinutes(10), clock().instant()))
                .build())));

        assertInstanceOf(ExecutionStoreFailure.IdempotencyConflict.class, failure);
    }

    @Test
    final void idempotencyRecordCountIsObservableRegardlessOfPurgeCapability() {
        assertEquals(0L, await(store().idempotencyRecordCount(DEFAULT_TENANT)));

        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        await(store().apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .recordIdempotency(new IdempotencyWrite("submit-1", fingerprint("request"),
                        fingerprint("outcome"), Duration.ofMinutes(10), clock().instant()))
                .build()));

        assertEquals(1L, await(store().idempotencyRecordCount(DEFAULT_TENANT)),
                "record count must be observable whether or not IDEMPOTENCY_PURGE is declared, so "
                        + "unbounded growth is visible before it becomes a disk incident");
    }

    @Test
    final void purgeRemovesOnlyRecordsPastTheirDeclaredRetentionWindowWhenDeclared() {
        assumeCapability(StoreCapability.IDEMPOTENCY_PURGE);

        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        await(store().apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .recordIdempotency(new IdempotencyWrite("submit-1", fingerprint("request"),
                        fingerprint("outcome"), Duration.ofMinutes(10), clock().instant()))
                .build()));

        // Well inside the retention window: a store may forget a key only after its window elapses.
        clock().advance(Duration.ofMinutes(5));
        assertEquals(0L, await(store().purgeExpiredIdempotencyRecords(DEFAULT_TENANT)));
        assertEquals(1L, await(store().idempotencyRecordCount(DEFAULT_TENANT)));

        clock().advance(Duration.ofMinutes(6));
        assertEquals(1L, await(store().purgeExpiredIdempotencyRecords(DEFAULT_TENANT)));
        assertEquals(0L, await(store().idempotencyRecordCount(DEFAULT_TENANT)));

        // What a subsequent lookup or replay of THIS now-purged key answers is asserted separately in
        // aReplayOfAForgottenKeyReportsExpiryRatherThanSilentlyReexecuting, which also drives the
        // forgottenBefore watermark this test does not need to inspect directly.
    }

    @Test
    final void purgeFailsWithCapabilityNotSupportedWhenNotDeclared() {
        Assumptions.assumeFalse(store().supports(StoreCapability.IDEMPOTENCY_PURGE),
                "this adapter declares IDEMPOTENCY_PURGE; the positive behaviour is asserted elsewhere");

        assertInstanceOf(ExecutionStoreFailure.CapabilityNotSupported.class,
                failureOf(() -> await(store().purgeExpiredIdempotencyRecords(DEFAULT_TENANT))));
    }

    @Test
    final void aReplayOfAForgottenKeyReportsExpiryRatherThanSilentlyReexecuting() {
        assumeCapability(StoreCapability.IDEMPOTENCY_PURGE);

        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        Instant issuedAt = clock().instant();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        await(store().apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .recordIdempotency(new IdempotencyWrite("submit-1", fingerprint("request"),
                        fingerprint("outcome"), Duration.ofMinutes(10), issuedAt))
                .build()));

        // A store may forget a key only after its declared window elapses.
        clock().advance(Duration.ofMinutes(11));
        assertEquals(1L, await(store().purgeExpiredIdempotencyRecords(DEFAULT_TENANT)));

        // The critical assertion: a forgotten key is NOT reported as "never recorded" -- that reads
        // as "never applied" and re-executes. The key was issued before the watermark the purge just
        // raised, so the store cannot exclude that a record existed and was purged.
        assertInstanceOf(ExecutionStoreFailure.IdempotencyRecordExpired.class, failureOf(() ->
                await(store().lookupIdempotency(key.tenantId(), "submit-1", issuedAt))));

        // The identical classification must protect the WRITE path too, which is where re-execution
        // would actually happen: apply() answering "proceed" for an unclassified absence is exactly
        // the silent re-execution this whole mechanism exists to prevent.
        var onWrite = failureOf(() -> await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.any())
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .recordIdempotency(new IdempotencyWrite("submit-1", fingerprint("request"),
                        fingerprint("outcome"), Duration.ofMinutes(10), issuedAt))
                .build())));
        var expired = assertInstanceOf(ExecutionStoreFailure.IdempotencyRecordExpired.class, onWrite);
        assertEquals(Retryability.INDETERMINATE, expired.retryability());

        // And it must not have silently applied the transition it claims to have rejected.
        assertEquals(TraversalStatus.ACCEPTED, await(store().load(key)).state().traversals()
                        .get(traversalId).status(),
                "the write claiming a purged idempotency key must not have silently re-executed");
    }

    @Test
    final void applyProceedsForAFreshlyIssuedKeyEvenAfterAnUnrelatedKeyInTheSameTenantIsPurged() {
        assumeCapability(StoreCapability.IDEMPOTENCY_PURGE);

        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        Instant oldIssuedAt = clock().instant();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        await(store().apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .recordIdempotency(new IdempotencyWrite("old-key", fingerprint("request"),
                        fingerprint("outcome"), Duration.ofMinutes(10), oldIssuedAt))
                .build()));
        clock().advance(Duration.ofMinutes(11));
        await(store().purgeExpiredIdempotencyRecords(DEFAULT_TENANT));

        // A DIFFERENT, freshly-issued key, comfortably above the watermark the purge just raised. The
        // write path must tell this apart from a genuinely purged key and proceed normally: a fix
        // that over-corrects by rejecting every absent key once anything in the tenant has ever been
        // purged would wedge all new idempotent writes, which is as real a regression as silently
        // re-executing a purged one.
        clock().advance(Duration.ofMinutes(1));
        Instant freshlyIssued = clock().instant();
        StoredProcessInstance running = await(store().load(key));
        StoredProcessInstance afterFreshWrite = await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(running.revision()))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .recordIdempotency(new IdempotencyWrite("brand-new-key", fingerprint("request"),
                        fingerprint("outcome"), Duration.ofMinutes(10), freshlyIssued))
                .build()));

        assertTrue(afterFreshWrite.revision() > running.revision());
        assertEquals(TraversalStatus.RUNNING,
                afterFreshWrite.state().traversals().get(traversalId).status());
    }

    @Test
    final void theWatermarkStartsAtInstantMinAndAdvancesOnlyThroughPurgePerTenant() {
        assumeCapability(StoreCapability.IDEMPOTENCY_PURGE);

        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        Instant issuedAt = clock().instant();
        assertEquals(Instant.MIN, await(store().forgottenBefore(key.tenantId())));

        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        await(store().apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .recordIdempotency(new IdempotencyWrite("submit-1", fingerprint("request"),
                        fingerprint("outcome"), Duration.ofMinutes(10), issuedAt))
                .build()));

        // Merely writing and letting a record age does not move the watermark -- only a purge does.
        clock().advance(Duration.ofMinutes(11));
        assertEquals(Instant.MIN, await(store().forgottenBefore(key.tenantId())));

        await(store().purgeExpiredIdempotencyRecords(DEFAULT_TENANT));
        Instant advanced = await(store().forgottenBefore(key.tenantId()));
        assertTrue(advanced.isAfter(Instant.MIN));

        // Monotonically non-decreasing: a later purge that forgets nothing cannot retreat it.
        clock().advance(Duration.ofMinutes(5));
        assertEquals(0L, await(store().purgeExpiredIdempotencyRecords(DEFAULT_TENANT)));
        assertEquals(advanced, await(store().forgottenBefore(key.tenantId())));

        // Per tenant: a tenant that has forgotten nothing keeps a provable absence regardless of what
        // happened in another tenant.
        assertEquals(Instant.MIN, await(store().forgottenBefore("untouched-tenant")));
    }

    @Test
    final void anAbsentKeyIssuedAfterTheWatermarkIsProvablyNeverRecorded() {
        assumeCapability(StoreCapability.IDEMPOTENCY_PURGE);

        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        Instant issuedAt = clock().instant();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        await(store().apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .recordIdempotency(new IdempotencyWrite("submit-1", fingerprint("request"),
                        fingerprint("outcome"), Duration.ofMinutes(10), issuedAt))
                .build()));
        clock().advance(Duration.ofMinutes(11));
        await(store().purgeExpiredIdempotencyRecords(DEFAULT_TENANT));

        // Minted comfortably after the watermark just raised: had this key ever been recorded, its
        // expiresAt would still be at or above the watermark, so absence proves it was never written
        // -- the caller may safely proceed, not merely guess.
        clock().advance(Duration.ofMinutes(1));
        Instant freshlyIssued = clock().instant();
        assertEquals(Optional.empty(),
                await(store().lookupIdempotency(key.tenantId(), "brand-new-key", freshlyIssued)),
                "provable absence is an empty result, not a failure");
    }

    @Test
    final void forwardSkewBeyondTheDeclaredBudgetIsRejectedAtWriteAndAtLookup() {
        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        Instant beyondBudget = clock().instant().plus(store().maxClockSkew()).plusSeconds(1);

        // Rejected at WRITE time, so an operator whose clock runs fast is told while the clock can
        // still be fixed, rather than after the damage window has passed.
        var onWrite = failureOf(() -> await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .recordIdempotency(new IdempotencyWrite("submit-1", fingerprint("request"),
                        fingerprint("outcome"), Duration.ofMinutes(10), beyondBudget))
                .build())));
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class, onWrite);

        // Rejected before any mutation: not just the idempotency write, the whole batch.
        StoredProcessInstance unchanged = await(store().load(key));
        assertEquals(created.revision(), unchanged.revision());
        assertEquals(ProcessInstanceStatus.ACCEPTED, unchanged.state().status());

        // And at lookup time, independent of any write ever having happened.
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class, failureOf(() ->
                await(store().lookupIdempotency(key.tenantId(), "submit-1", beyondBudget))));
    }

    @Test
    final void ambiguityWithinTheSkewBudgetResolvesToExpiredRatherThanSafeToApply() {
        assumeCapability(StoreCapability.IDEMPOTENCY_PURGE);
        Assumptions.assumeTrue(store().maxClockSkew().compareTo(Duration.ofSeconds(2)) > 0,
                "this assertion needs a declared skew budget of at least a couple of seconds to probe");

        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        Instant issuedAt = clock().instant();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        await(store().apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .recordIdempotency(new IdempotencyWrite("submit-1", fingerprint("request"),
                        fingerprint("outcome"), Duration.ofMinutes(10), issuedAt))
                .build()));
        clock().advance(Duration.ofMinutes(11));
        await(store().purgeExpiredIdempotencyRecords(DEFAULT_TENANT));
        Instant watermark = await(store().forgottenBefore(key.tenantId()));

        // Issued just ABOVE the watermark, but within its skew budget. A naive comparison against
        // keyIssuedAt alone would call this provably-never-recorded and answer NotFound -- exactly
        // the silent-reexecution hazard the budget exists to close. The contract compares keyIssuedAt
        // MINUS the budget against the watermark, so every case ambiguous within it must resolve to
        // expired rather than to "safe to apply".
        Instant justAboveWatermark = watermark.plusSeconds(1);
        assertInstanceOf(ExecutionStoreFailure.IdempotencyRecordExpired.class, failureOf(() ->
                await(store().lookupIdempotency(key.tenantId(), "ambiguous-key", justAboveWatermark))));

        // Clear of the budget, absence is provable again. The store's own clock must advance too:
        // a keyIssuedAt this far ahead of the watermark but NOT ahead of "now" needs the clock to
        // have actually moved there, or the forward-skew guard -- which compares against the
        // CURRENT clock, independently of the watermark -- would reject it first and this would
        // pass for the wrong reason (an InvalidRequest that only looks like the intended NotFound).
        clock().advance(store().maxClockSkew().plusSeconds(2));
        Instant clearOfBudget = clock().instant();
        assertTrue(clearOfBudget.isAfter(watermark.plus(store().maxClockSkew())),
                "test precondition: clearOfBudget must actually be clear of the ambiguity window");
        assertEquals(Optional.empty(),
                await(store().lookupIdempotency(key.tenantId(), "ambiguous-key", clearOfBudget)));
    }

    // ================================================================== tenant scoping (PERS-10)

    /**
     * The watermark is <strong>per tenant</strong>, and a purge that forgot nothing must not move it.
     *
     * <p>This is the assertion that does not coincide between a global and a tenant-scoped reading,
     * which is why it is worth its own test. Two tenants hold live records; only one tenant's record
     * ages past its window; both tenants are purged. The untouched tenant must still be at
     * {@link Instant#MIN} — it has forgotten nothing, so nothing about it has become unprovable.</p>
     *
     * <p>The consequence, not merely the field value, is what makes this load-bearing: a watermark
     * advanced for a tenant that lost no record destroys provable absence for every key that tenant
     * issued before that instant. Its next fresh idempotent write — and every one after it, since a
     * periodic purge would re-raise the watermark on every tick — would answer
     * {@code IdempotencyRecordExpired} for work that was never recorded, which callers must escalate
     * rather than retry. Under physical isolation the mistake is worse still, because a fan-out purge
     * would move watermarks in stores the operator never asked about.</p>
     */
    @Test
    final void purgingOneTenantLeavesAnotherTenantsWatermarkAtInstantMin() {
        assumeCapability(StoreCapability.IDEMPOTENCY_PURGE);

        String purged = "tenant-purged";
        String untouched = "tenant-untouched";
        // A third tenant is required, not preferred. The two roles this test asserts are mutually
        // exclusive on one tenant: `untouched` must be purged with ZERO effect, to prove a purge
        // that forgot nothing leaves the watermark at Instant.MIN, while a bystander must hold a
        // record expiring at the SAME instant as the purged tenant's, to prove purge does not reach
        // across. One tenant cannot both have and not have an expired record.
        String bystander = "tenant-bystander";
        ExecutionKey purgedKey = keyFor(purged);
        ExecutionKey untouchedKey = keyFor(untouched);
        ExecutionKey bystanderKey = keyFor(bystander);
        UUID purgedTraversal = UUID.randomUUID();
        UUID untouchedTraversal = UUID.randomUUID();
        UUID bystanderTraversal = UUID.randomUUID();
        Instant issuedAt = clock().instant();

        assertEquals(Instant.MIN, await(store().forgottenBefore(purged)));
        assertEquals(Instant.MIN, await(store().forgottenBefore(untouched)));
        assertEquals(Instant.MIN, await(store().forgottenBefore(bystander)));

        // All three hold a live record. The purged tenant's and the bystander's share an identical
        // retention window, so they become collectable at the very same instant -- which is what
        // makes an unscoped purge indistinguishable from a scoped one unless it is asserted.
        recordIdempotentWrite(purgedKey, purgedTraversal, "short-lived", Duration.ofMinutes(10), issuedAt);
        recordIdempotentWrite(untouchedKey, untouchedTraversal, "long-lived", Duration.ofHours(4), issuedAt);
        recordIdempotentWrite(bystanderKey, bystanderTraversal, "also-short", Duration.ofMinutes(10), issuedAt);
        assertEquals(1L, await(store().idempotencyRecordCount(purged)));
        assertEquals(1L, await(store().idempotencyRecordCount(untouched)),
                "a record count is per tenant: counting across physically separate stores would "
                        + "answer for whichever pools happen to be open");
        assertEquals(1L, await(store().idempotencyRecordCount(bystander)));

        clock().advance(Duration.ofMinutes(11));

        // Purging one tenant collects that tenant's expired record and no other, even though the
        // bystander's record is equally expired at this instant.
        assertEquals(1L, await(store().purgeExpiredIdempotencyRecords(purged)),
                "a purge must collect only the requested tenant's expired records; returning 2 "
                        + "would mean it reached into a store it was never asked about");

        // The bystander's record is not merely still counted -- it is still ANSWERABLE. This is the
        // assertion that carries the real hazard: an unscoped purge deletes it while advancing only
        // the purged tenant's watermark, leaving the bystander at Instant.MIN. Its next lookup then
        // returns an empty result, which the contract defines as "provably never recorded, safe to
        // apply", and the caller re-executes completed work. That is the silent exactly-once
        // violation the section 6.1 watermark exists to prevent, and it is invisible to a record
        // count alone.
        Optional<IdempotencyRecord> bystanderRecord =
                await(store().lookupIdempotency(bystander, "also-short", issuedAt));
        assertTrue(bystanderRecord.isPresent(),
                "another tenant's purge turned a recorded outcome into an EMPTY result, which this "
                        + "contract defines as 'provably never recorded, safe to apply' -- the "
                        + "caller will now re-execute work that already happened. A record count "
                        + "cannot see this: an implementation that deletes across tenants while "
                        + "reporting an honest per-tenant count passes every other assertion here");
        assertEquals(fingerprint("outcome"), bystanderRecord.orElseThrow().outcomeRef());
        assertEquals(1L, await(store().idempotencyRecordCount(bystander)));
        assertEquals(Instant.MIN, await(store().forgottenBefore(bystander)));

        assertEquals(0L, await(store().purgeExpiredIdempotencyRecords(untouched)),
                "the second tenant's record is still well inside its declared retention window");

        Instant purgedWatermark = await(store().forgottenBefore(purged));
        assertTrue(purgedWatermark.isAfter(Instant.MIN),
                "the tenant that actually lost a record must have advanced its watermark");

        // The assertion this test exists for. A purge that removed nothing must leave the watermark
        // exactly where it was.
        assertEquals(Instant.MIN, await(store().forgottenBefore(untouched)),
                "a tenant that lost no record must keep its watermark at Instant.MIN; advancing it "
                        + "would destroy provable absence for every key that tenant already issued");

        // ...and its observable consequence, which is what an implementation that got this wrong
        // would actually inflict on callers: a key that tenant issued BEFORE the other tenant's purge
        // must still be provably-never-recorded, so a caller may proceed rather than escalate.
        assertEquals(Optional.empty(),
                await(store().lookupIdempotency(untouched, "never-written", issuedAt)),
                "an advanced watermark would turn this into IdempotencyRecordExpired -- an "
                        + "INDETERMINATE failure the caller must stop and resolve -- for work that "
                        + "was demonstrably never recorded");

        // The untouched tenant's own live record is untouched too, and the purged tenant's is gone.
        assertEquals(fingerprint("outcome"),
                await(store().lookupIdempotency(untouched, "long-lived", issuedAt))
                        .orElseThrow().outcomeRef());
        assertEquals(1L, await(store().idempotencyRecordCount(untouched)));
        assertEquals(0L, await(store().idempotencyRecordCount(purged)));
        assertInstanceOf(ExecutionStoreFailure.IdempotencyRecordExpired.class, failureOf(() ->
                        await(store().lookupIdempotency(purged, "short-lived", issuedAt))),
                "the tenant that did lose a record must still report expiry for it, so this test "
                        + "cannot be satisfied by an implementation that simply never purges");
    }

    @Test
    final void claimingAndLeaseEnumerationAreScopedToTheRequestedTenant() {
        String owner = "tenant-owner";
        String other = "tenant-other";
        ExecutionKey ownerKey = keyFor(owner);
        ExecutionKey otherKey = keyFor(other);
        scheduleRunningAttempt(ownerKey, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        scheduleRunningAttempt(otherKey, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        // Claiming is tenant-scoped PER CALL. A store that fanned one claim across tenants would be
        // deciding which tenant is served first -- a scheduling policy, made behind an interface with
        // no way to express or configure it. Cross-tenant fairness belongs to the runtime.
        List<PendingWork> forOwner = await(store().claimPendingWork(owner, "worker-1", 10, TTL));
        assertEquals(1, forOwner.size());
        assertEquals(ownerKey, forOwner.get(0).key());

        // The other tenant's work is untouched by that claim, and its instance unleased.
        assertEquals(1, await(store().leases(owner)).size());
        assertTrue(await(store().leases(other)).isEmpty(),
                "enumerating one tenant's leases must not report another tenant's");

        List<PendingWork> forOther = await(store().claimPendingWork(other, "worker-1", 10, TTL));
        assertEquals(1, forOther.size());
        assertEquals(otherKey, forOther.get(0).key());
        assertEquals(1, await(store().leases(other)).size());

        // claimDueTimers is a SEPARATE entry point with its own scan loop, so it needs its own
        // assertion: a suite that only exercises claimPendingWork leaves this one unguarded, and
        // every call in the rest of this suite uses a single tenant, so nothing else can see it.
        // It is also where an unscoped claim is worst. Claiming does not merely read another
        // tenant's timers: it LEASES and FENCES them, so one tenant's worker takes ownership of
        // another tenant's work and the rightful owner is fenced out of its own instance. Under
        // physical isolation, that is a cross-tenant data leak, not a scheduling defect.
        ExecutionKey ownerTimerKey = keyFor(owner);
        ExecutionKey otherTimerKey = keyFor(other);
        UUID ownerTimerId = scheduleDueTimer(ownerTimerKey);
        UUID otherTimerId = scheduleDueTimer(otherTimerKey);

        List<PendingWork.TimerDue> ownerDue = await(store().claimDueTimers(owner, "worker-1", 10, TTL));
        assertEquals(1, ownerDue.size(),
                "claimDueTimers must return only the requested tenant's timers; returning both "
                        + "would lease and fence another tenant's work to this tenant's worker");
        assertEquals(ownerTimerId, ownerDue.get(0).workItemId());
        assertEquals(ownerTimerKey, ownerDue.get(0).key());

        // The other tenant's timer was neither claimed nor leased out from under it, so it is still
        // there to be claimed by its own tenant afterwards.
        List<PendingWork.TimerDue> otherDue = await(store().claimDueTimers(other, "worker-2", 10, TTL));
        assertEquals(1, otherDue.size());
        assertEquals(otherTimerId, otherDue.get(0).workItemId());
        assertEquals(otherTimerKey, otherDue.get(0).key());
    }

    // ================================================================== section 11: capability-gated

    /**
     * Formerly PROVISIONAL under ADR 0010 section 11.1. {@code SqliteExecutionStore} declares
     * {@link StoreCapability#DURABLE}, so this assertion now executes and passes against an in-tree
     * adapter, and the label is withdrawn.
     */
    @Test
    final void durableAdaptersSurviveAReopenOrSimulatedProcessDeath() {
        assumeCapability(StoreCapability.DURABLE);

        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        StoredProcessInstance running = await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .build()));
        UUID timerId = UUID.randomUUID();
        Instant dueAt = clock().instant().plusSeconds(60);
        Instant issuedAt = clock().instant();
        StoredProcessInstance scheduled = await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(running.revision()))
                .scheduleTimer(new TimerSchedule(timerId, dueAt, traversalId, null,
                        OpaquePayload.of("wake".getBytes(StandardCharsets.UTF_8), "text/plain")))
                .recordIdempotency(new IdempotencyWrite("durable-submit-1", fingerprint("request"),
                        fingerprint("outcome"), Duration.ofMinutes(10), issuedAt))
                .build()));

        // A batch of a timer plus an idempotency write is still a write, so it still advances the
        // revision: apply() returns "the resulting envelope with a strictly greater revision" with no
        // exemption for a batch whose operations are not transitions, and this batch is accepted
        // rather than rejected. Stating it here is what stops the reopen assertion below from
        // silently reverting to comparing against `running`, which no conforming adapter can satisfy.
        assertTrue(scheduled.revision() > running.revision());

        ExecutionStore reopened = reopen();

        StoredProcessInstance reloaded = await(reopened.load(key));
        assertEquals(scheduled.revision(), reloaded.revision());
        assertEquals(ProcessInstanceStatus.RUNNING, reloaded.state().status());
        assertEquals(new GraphVersionPin("graph-v1"), reloaded.graphVersionPin());

        clock().advance(Duration.ofSeconds(61));
        var due = await(reopened.claimDueTimers(DEFAULT_TENANT, "worker-1", 10, TTL));
        assertEquals(1, due.size(), "a durable timer record must survive the reopen");
        assertEquals(timerId, due.get(0).workItemId());

        // Idempotency records -- and, implicitly, the tenant's forgottenBefore watermark they are
        // interpreted against -- are stored state too, so section 11's DURABLE guarantee covers them
        // exactly as it covers the aggregate and the timer.
        assertEquals(fingerprint("outcome"),
                await(reopened.lookupIdempotency(key.tenantId(), "durable-submit-1", issuedAt)).orElseThrow().outcomeRef());
    }

    /**
     * Formerly PROVISIONAL under ADR 0010 section 11.1. {@code SqliteExecutionStore} declares
     * {@link StoreCapability#CROSS_PROCESS_LEASE}, so this now executes and passes against an
     * in-tree adapter, and the label is withdrawn. {@code SqliteCrossProcessLeaseTest} asserts the
     * same property across two real operating-system processes, where the first exits through a
     * clean {@code close()}.
     *
     * <p>As originally written it was <em>unsatisfiable</em>. It claimed a lease, reopened without
     * advancing the clock, and expected a different worker's immediate claim to succeed with a
     * greater token. But an adapter honouring this capability keeps the lease across the reopen —
     * that is what the capability means — so the second claim must fail with
     * {@link ExecutionStoreFailure.LeaseHeldByAnother}. The step could only pass if the property the
     * capability is named for were violated, and the property itself was never asserted anywhere.
     * The resolution (section 13.1) is both halves: assert that the live lease survives the reopen,
     * then advance the clock past the TTL and assert the token still does not repeat.</p>
     */
    @Test
    final void crossProcessLeaseSurvivesAReopenAndItsFencingTokensAreNeverReused() {
        assumeCapability(StoreCapability.CROSS_PROCESS_LEASE);

        ExecutionKey key = newKey();
        UUID traversalId = UUID.randomUUID();
        await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        LeaseHandle before = await(store().claim(key, "worker-1", TTL));

        ExecutionStore reopened = reopen();

        // The durability-of-lease property the capability is actually named for, and which nothing
        // covered: immediately after the reopen, before the TTL elapses, the lease is still held by
        // worker-1 and a different worker cannot take it. A close() may only accelerate what expiry
        // would do anyway (section 13.1), and expiry would not have released this lease yet.
        var contended = assertInstanceOf(ExecutionStoreFailure.LeaseHeldByAnother.class,
                failureOf(() -> await(reopened.claim(key, "worker-2", TTL))),
                "a live lease must survive a reopen; a store that dropped it would make an orderly "
                        + "restart differ from a crash, which is the divergence section 13.1 forbids");
        assertEquals("worker-1", contended.holderWorkerId());
        assertEquals(before.expiresAt(), contended.expiresAt());

        // Only once the lease has genuinely lapsed on the store's clock may another worker take it.
        clock().advance(TTL.plusSeconds(1));
        LeaseHandle after = await(reopened.claim(key, "worker-2", TTL));

        assertTrue(after.fencingToken() > before.fencingToken(),
                "a fencing token must never be reused after a lease is lost, including across a "
                        + "restart: a session is not a fencing domain, so tokens must not reset on reopen");
    }

    // ================================================================== section 12: failure taxonomy

    @Test
    final void everyFailureMemberExposesItsRetryabilityClassification() {
        ExecutionKey key = newKey();

        // Fifteen sealed members, four adapter-conditional by construction (ADR 0010 section 12.4):
        // Corrupted, NotAuthorized, Unavailable and OutcomeUnknown describe failure modes of durable
        // or remote storage that no conforming in-memory adapter's own operations can produce. The
        // ADR explicitly endorses asserting their classification by constructing the failure records
        // directly rather than adding a fault-injection point to the port, so doing so here is the
        // correct treatment, not a workaround for a gap.
        assertEquals(Retryability.RETRY_AFTER_REREAD,
                new ExecutionStoreFailure.ConcurrencyConflict(key, RevisionExpectation.any(), 1L).retryability());
        assertEquals(Retryability.DETERMINISTIC_REJECT,
                new ExecutionStoreFailure.FencedOut(key, 1L, 2L).retryability());
        assertEquals(Retryability.DETERMINISTIC_REJECT,
                new ExecutionStoreFailure.LeaseLost(key, "worker-1").retryability());
        assertEquals(Retryability.RETRYABLE_NO_EFFECT,
                new ExecutionStoreFailure.LeaseHeldByAnother(key, "worker-1", EPOCH).retryability());
        assertEquals(Retryability.DETERMINISTIC_REJECT, new ExecutionStoreFailure.NotFound(key).retryability());
        assertEquals(Retryability.DETERMINISTIC_REJECT,
                new ExecutionStoreFailure.AlreadyExists(key, 1L).retryability());
        assertEquals(Retryability.DETERMINISTIC_REJECT,
                new ExecutionStoreFailure.IdempotencyConflict("k").retryability());
        assertEquals(Retryability.INDETERMINATE,
                new ExecutionStoreFailure.IdempotencyRecordExpired("k").retryability());
        assertEquals(Retryability.DETERMINISTIC_REJECT, new ExecutionStoreFailure.InvalidRequest("bad")
                .retryability());
        assertEquals(Retryability.DETERMINISTIC_REJECT,
                new ExecutionStoreFailure.PayloadTooLarge(10, 5).retryability());
        assertEquals(Retryability.DETERMINISTIC_REJECT,
                new ExecutionStoreFailure.CapabilityNotSupported(StoreCapability.DURABLE).retryability());
        assertEquals(Retryability.DETERMINISTIC_REJECT,
                new ExecutionStoreFailure.NotAuthorized("bad credentials").retryability());
        assertEquals(Retryability.RETRYABLE_NO_EFFECT,
                new ExecutionStoreFailure.Unavailable("busy").retryability());
        assertEquals(Retryability.INDETERMINATE,
                new ExecutionStoreFailure.OutcomeUnknown(key, "timed out").retryability());
        assertEquals(Retryability.DETERMINISTIC_REJECT,
                new ExecutionStoreFailure.Corrupted(key, "bad state").retryability());

        // The distinction ADR 0010 section 12 says must never collapse now survives coarsening, per
        // the section 12.1 amendment: a caller dispatching purely on retryability() -- not only one
        // that pattern-matches the concrete type -- can tell ConcurrencyConflict from FencedOut apart.
        assertNotEquals(
                new ExecutionStoreFailure.ConcurrencyConflict(key, RevisionExpectation.any(), 1L).retryability(),
                new ExecutionStoreFailure.FencedOut(key, 1L, 2L).retryability());

        // And LeaseLost (losing a lease held) from LeaseHeldByAnother (failing to acquire one), per
        // section 12.2: different frequency, different obligation, so a rare LeaseLost alert is not
        // buried under routine contention.
        assertNotEquals(
                new ExecutionStoreFailure.LeaseLost(key, "worker-1").retryability(),
                new ExecutionStoreFailure.LeaseHeldByAnother(key, "worker-1", EPOCH).retryability());
    }

    // ================================================================== fixtures

    private ExecutionKey newKey() {
        return keyFor(DEFAULT_TENANT);
    }

    private ExecutionKey keyFor(String tenantId) {
        return new ExecutionKey(tenantId, UUID.randomUUID());
    }

    private ExecutionBatch creationBatch(ExecutionKey key, UUID traversalId, String pinReference) {
        return ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(
                        acceptedInstance(key.processInstanceId(), traversalId), new GraphVersionPin(pinReference)))
                .build();
    }

    private static ProcessInstance acceptedInstance(UUID instanceId, UUID traversalId) {
        return new ProcessInstance(instanceId, ProcessInstanceStatus.ACCEPTED,
                Map.of(traversalId, new Traversal(traversalId, "start", TraversalStatus.ACCEPTED, Map.of())));
    }

    /** Creates an instance carrying one timer that is already due on the store's clock. */
    private UUID scheduleDueTimer(ExecutionKey key) {
        UUID traversalId = UUID.randomUUID();
        UUID timerId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .scheduleTimer(new TimerSchedule(timerId, clock().instant(), traversalId, null,
                        OpaquePayload.empty("application/octet-stream")))
                .build()));
        return timerId;
    }

    /** Creates an instance and records one idempotency outcome against it, in that tenant. */
    private void recordIdempotentWrite(ExecutionKey key, UUID traversalId, String idempotencyKey,
                                       Duration retentionWindow, Instant issuedAt) {
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .recordIdempotency(new IdempotencyWrite(idempotencyKey, fingerprint("request"),
                        fingerprint("outcome"), retentionWindow, issuedAt))
                .build()));
    }

    /** Creates a running instance with one SCHEDULED attempt, ready to be claimed as pending work. */
    private StoredProcessInstance scheduleRunningAttempt(ExecutionKey key, UUID traversalId, UUID invocationId,
                                                         UUID attemptId) {
        return scheduleRunningAttempt(key, traversalId, invocationId, attemptId, NodeCommand.PROCESS);
    }

    private StoredProcessInstance scheduleRunningAttempt(ExecutionKey key, UUID traversalId, UUID invocationId,
                                                         UUID attemptId, NodeCommand command) {
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        return await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .apply(new ExecutionTransition.InvocationAdded(traversalId,
                        new NodeInvocation(invocationId, "work", Set.of(), NodeInvocationStatus.SCHEDULED,
                                List.of(), command)))
                .apply(new ExecutionTransition.InvocationTransitioned(traversalId, invocationId,
                        NodeInvocationStatus.RUNNING))
                .apply(new ExecutionTransition.AttemptAdded(traversalId, invocationId,
                        new NodeAttempt(attemptId, 1, NodeAttemptStatus.SCHEDULED)))
                .build()));
    }

    // ============================================== PERS-05: durable handlers, wait and re-entry

    /**
     * The registration and the waiting transition share one commit, and a trigger resolves the
     * handler by the business identity it presents rather than by an identity it could not know.
     */
    @Test
    final void aRegistrationCommitsWithItsWaitingTransitionAndIsFoundByCorrelationKey() {
        assumeCapability(StoreCapability.DURABLE_HANDLERS);
        var fixture = waitingHandler(newKey(), "approval", "invoice-42", "dedup-1");

        DurableHandler found = await(store()
                .findHandler(fixture.key().tenantId(), "approval", "invoice-42")).orElseThrow();
        assertEquals(fixture.handlerId(), found.handlerId());
        assertEquals(HandlerStatus.WAITING, found.status());
        assertNull(found.resumeTraversalId(), "a waiting handler has authorized no re-entry");
        assertEquals(TraversalStatus.WAITING,
                await(store().load(fixture.key())).state().traversals().get(fixture.traversalId()).status(),
                "the wait and the handler that records what it is waiting for commit together");
        assertTrue(await(store().claimPendingWork(fixture.key().tenantId(), "worker-1", 10, TTL)).isEmpty(),
                "a waiting handler is state, not work: nothing is claimable until it settles");
    }

    /**
     * Registration is exactly-once, which is what makes a crash between the waiting transition and
     * the registration recoverable by re-sending the identical batch.
     */
    @Test
    final void aRepeatedRegistrationUnderTheSameDeduplicationKeyIsANoOpNotASecondHandler() {
        assumeCapability(StoreCapability.DURABLE_HANDLERS);
        var fixture = waitingHandler(newKey(), "approval", "invoice-42", "dedup-1");

        StoredProcessInstance before = await(store().load(fixture.key()));
        await(store().apply(ExecutionBatch.to(fixture.key())
                .expecting(RevisionExpectation.exactly(before.revision()))
                .registerHandler(fixture.registration())
                .build()));

        assertEquals(1, await(store().handlers(fixture.key())).size(),
                "a retried wait must not leave a second handler that no trigger will ever resolve");
    }

    /** Two live handlers under one correlation key would make a trigger's target arbitrary. */
    @Test
    final void aLiveCorrelationKeyCannotBeTakenTwiceButBecomesReusableOnceTheWaitIsOver() {
        assumeCapability(StoreCapability.DURABLE_HANDLERS);
        var first = waitingHandler(newKey(), "approval", "invoice-42", "dedup-1");
        ExecutionKey secondKey = newKey();

        ExecutionStoreFailure taken = failureOf(() ->
                waitingHandler(secondKey, "approval", "invoice-42", "dedup-2"));
        var conflict = assertInstanceOf(ExecutionStoreFailure.HandlerCorrelationTaken.class, taken);
        assertEquals("invoice-42", conflict.correlationKey());
        assertEquals(Retryability.DETERMINISTIC_REJECT, conflict.retryability(),
                "re-reading cannot make a taken correlation key free");

        resolve(first, "approved");
        assertDoesNotThrow(() -> waitingHandler(newKey(), "approval", "invoice-42", "dedup-3"),
                "a key whose wait is over is reusable; terminal handlers are retained, not live");
    }

    /**
     * The resolution, the re-entry traversal and the journal event are one commit, and the trigger
     * the store then offers names the <em>new</em> traversal.
     */
    @Test
    final void anAuthorizedResolutionCommitsAReEntryTraversalAndOffersExactlyOneTrigger() {
        assumeCapability(StoreCapability.DURABLE_HANDLERS);
        var fixture = waitingHandler(newKey(), "approval", "invoice-42", "dedup-1");

        UUID resumeTraversalId = resolve(fixture, "approved");

        DurableHandler resolved = await(store()
                .loadHandler(fixture.key(), fixture.handlerId())).orElseThrow();
        assertEquals(HandlerStatus.RESOLVED, resolved.status());
        assertEquals(resumeTraversalId, resolved.resumeTraversalId());
        assertEquals("issuer|USER|approver", resolved.actor());
        assertTrue(await(store().load(fixture.key())).state().traversals().containsKey(resumeTraversalId),
                "the traversal that resumes the process is durable state, not a live continuation");
        assertTrue(await(store().findHandler(fixture.key().tenantId(), "approval", "invoice-42")).isEmpty(),
                "a settled handler is no longer live and no longer answers a trigger");

        List<PendingWork> claimed =
                await(store().claimPendingWork(fixture.key().tenantId(), "worker-1", 10, TTL));
        assertEquals(1, claimed.size());
        var trigger = assertInstanceOf(PendingWork.HandlerTrigger.class, claimed.getFirst());
        assertEquals(fixture.handlerId(), trigger.workItemId());
        assertEquals(resumeTraversalId, trigger.traversalId(),
                "the claimant runs the traversal the resolution authorized, not the one that waited");
        assertEquals("approval", trigger.handlerName());
        assertEquals("approved", new String(trigger.payload().bytes(), StandardCharsets.UTF_8));

        await(store().ack(trigger));
        assertTrue(await(store().claimPendingWork(fixture.key().tenantId(), "worker-1", 10, TTL)).isEmpty(),
                "one handler produces one trigger, and an acknowledged trigger stays acknowledged");
    }

    /**
     * Duplicate and late are the same fact, and both are decided from stored state alone — no clock,
     * no retention window, therefore the same answer on every retry.
     */
    @Test
    final void aSecondResolutionIsRefusedDeterministicallyAndCommitsNothing() {
        assumeCapability(StoreCapability.DURABLE_HANDLERS);
        var fixture = waitingHandler(newKey(), "approval", "invoice-42", "dedup-1");
        UUID firstResume = resolve(fixture, "approved");

        StoredProcessInstance settled = await(store().load(fixture.key()));
        UUID secondResume = UUID.randomUUID();
        ExecutionStoreFailure refused = failureOf(() -> await(store().apply(
                ExecutionBatch.to(fixture.key())
                        .expecting(RevisionExpectation.exactly(settled.revision()))
                        .apply(new ExecutionTransition.TraversalAdded(new Traversal(secondResume, "work",
                                TraversalStatus.ACCEPTED, Map.of())))
                        .applyHandler(new HandlerTransition.Resolved(fixture.handlerId(), "issuer|USER|other",
                                secondResume, OpaquePayload.of("approved".getBytes(StandardCharsets.UTF_8),
                                        "text/plain")))
                        .build())));
        var notResolvable = assertInstanceOf(ExecutionStoreFailure.HandlerNotResolvable.class, refused);
        assertEquals(HandlerStatus.RESOLVED, notResolvable.current());
        assertEquals(Retryability.DETERMINISTIC_REJECT, notResolvable.retryability(),
                "a caller that re-read and retried this would loop forever");

        StoredProcessInstance after = await(store().load(fixture.key()));
        assertEquals(settled.revision(), after.revision(), "a refused batch writes nothing at all");
        assertFalse(after.state().traversals().containsKey(secondResume),
                "the re-entry traversal must not survive the handler transition that was refused");
        DurableHandler unchanged = await(store()
                .loadHandler(fixture.key(), fixture.handlerId())).orElseThrow();
        assertEquals(firstResume, unchanged.resumeTraversalId());
        assertEquals("issuer|USER|approver", unchanged.actor(),
                "the second principal must not overwrite the one that actually resolved it");
    }

    /**
     * The store cannot be used as a cross-tenant existence oracle: another tenant's handler is
     * indistinguishable from one that was never registered.
     */
    @Test
    final void anotherTenantsHandlerIsIndistinguishableFromOneThatNeverExisted() {
        assumeCapability(StoreCapability.DURABLE_HANDLERS);
        var fixture = waitingHandler(newKey(), "approval", "invoice-42", "dedup-1");

        assertEquals(Optional.empty(), await(store().findHandler("intruder", "approval", "invoice-42")),
                "an empty answer, never a denial, or the refusal itself would confirm the key exists");
        assertEquals(Optional.empty(), await(store().loadHandler(
                new ExecutionKey("intruder", fixture.key().processInstanceId()), fixture.handlerId())));
        assertTrue(await(store().handlers(
                new ExecutionKey("intruder", fixture.key().processInstanceId()))).isEmpty());
    }

    /** Escalation raises visibility, leaves the handler resolvable, and tolerates redelivery. */
    @Test
    final void escalationIsRepeatableAndLeavesTheHandlerResolvable() {
        assumeCapability(StoreCapability.DURABLE_HANDLERS);
        var fixture = waitingHandler(newKey(), "approval", "invoice-42", "dedup-1");

        escalate(fixture, "no decision within the declared window");
        DurableHandler escalated = await(store()
                .loadHandler(fixture.key(), fixture.handlerId())).orElseThrow();
        assertEquals(HandlerStatus.ESCALATED, escalated.status());
        assertNull(escalated.resumeTraversalId(), "an escalation resumes nothing and produces no trigger");
        assertTrue(await(store().claimPendingWork(fixture.key().tenantId(), "worker-1", 10, TTL)).isEmpty());

        // At-least-once timer delivery must not be able to turn a retry into an incident.
        assertDoesNotThrow(() -> escalate(fixture, "no decision within the declared window"));
        assertEquals(1, await(store().handlers(fixture.key())).size());
        assertTrue(await(store().findHandler(fixture.key().tenantId(), "approval", "invoice-42")).isPresent(),
                "an escalated handler is still live: escalation unsticks work, it does not close it");

        assertDoesNotThrow(() -> resolve(fixture, "approved"));
    }

    /** Expiry is terminal, resumes the process on its timeout route, and refuses a late trigger. */
    @Test
    final void anExpiredHandlerResumesTheProcessAndRefusesALaterResolution() {
        assumeCapability(StoreCapability.DURABLE_HANDLERS);
        var fixture = waitingHandler(newKey(), "approval", "invoice-42", "dedup-1");

        StoredProcessInstance waiting = await(store().load(fixture.key()));
        UUID timeoutTraversal = UUID.randomUUID();
        await(store().apply(ExecutionBatch.to(fixture.key())
                .expecting(RevisionExpectation.exactly(waiting.revision()))
                .apply(new ExecutionTransition.TraversalAdded(new Traversal(timeoutTraversal, "work",
                        TraversalStatus.ACCEPTED, Map.of())))
                .applyHandler(new HandlerTransition.Expired(fixture.handlerId(), timeoutTraversal))
                .build()));

        DurableHandler expired = await(store()
                .loadHandler(fixture.key(), fixture.handlerId())).orElseThrow();
        assertEquals(HandlerStatus.EXPIRED, expired.status());
        assertEquals("", expired.actor(), "a deadline is not an actor and must not be recorded as one");
        assertEquals(timeoutTraversal, expired.resumeTraversalId());

        List<PendingWork> claimed =
                await(store().claimPendingWork(fixture.key().tenantId(), "worker-1", 10, TTL));
        assertEquals(1, claimed.size());
        assertEquals(timeoutTraversal,
                assertInstanceOf(PendingWork.HandlerTrigger.class, claimed.getFirst()).traversalId());

        assertInstanceOf(ExecutionStoreFailure.HandlerNotResolvable.class,
                failureOf(() -> resolve(fixture, "approved")));
    }

    /** The payload schema is enforced by the store, so it holds for any caller building its own batch. */
    @Test
    final void aResolutionPayloadThatDoesNotMatchTheDeclaredSchemaIsRefusedAndCommitsNothing() {
        assumeCapability(StoreCapability.DURABLE_HANDLERS);
        var fixture = waitingHandler(newKey(), "approval", "invoice-42", "dedup-1");

        StoredProcessInstance waiting = await(store().load(fixture.key()));
        UUID resume = UUID.randomUUID();
        ExecutionStoreFailure refused = failureOf(() -> await(store().apply(
                ExecutionBatch.to(fixture.key())
                        .expecting(RevisionExpectation.exactly(waiting.revision()))
                        .apply(new ExecutionTransition.TraversalAdded(new Traversal(resume, "work",
                                TraversalStatus.ACCEPTED, Map.of())))
                        .applyHandler(new HandlerTransition.Resolved(fixture.handlerId(),
                                "issuer|USER|approver", resume,
                                OpaquePayload.of(new byte[] {1, 2, 3}, "application/octet-stream")))
                        .build())));
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class, refused);
        assertEquals(HandlerStatus.WAITING,
                await(store().loadHandler(fixture.key(), fixture.handlerId())).orElseThrow().status());
        assertEquals(waiting.revision(), await(store().load(fixture.key())).revision());
    }

    /** A handler that closed a process while naming a re-entry point nobody created would strand it. */
    @Test
    final void aTerminalTransitionNamingATraversalTheBatchDidNotCreateIsRefused() {
        assumeCapability(StoreCapability.DURABLE_HANDLERS);
        var fixture = waitingHandler(newKey(), "approval", "invoice-42", "dedup-1");

        StoredProcessInstance waiting = await(store().load(fixture.key()));
        ExecutionStoreFailure refused = failureOf(() -> await(store().apply(
                ExecutionBatch.to(fixture.key())
                        .expecting(RevisionExpectation.exactly(waiting.revision()))
                        .applyHandler(new HandlerTransition.Resolved(fixture.handlerId(),
                                "issuer|USER|approver", UUID.randomUUID(),
                                OpaquePayload.of("approved".getBytes(StandardCharsets.UTF_8), "text/plain")))
                        .build())));
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class, refused);
        assertEquals(HandlerStatus.WAITING,
                await(store().loadHandler(fixture.key(), fixture.handlerId())).orElseThrow().status());
    }

    /**
     * The human-task case the whole mechanism exists for: created before a complete shutdown,
     * authorized and resolved after the restart, by a process that did not exist when the wait began.
     */
    @Test
    final void aHumanTaskRegisteredBeforeAShutdownIsResolvableAfterTheRestart() {
        assumeCapability(StoreCapability.DURABLE_HANDLERS);
        assumeCapability(StoreCapability.DURABLE);
        var fixture = waitingHandler(newKey(), "approval", "invoice-42", "dedup-1");

        reopen();

        DurableHandler survived = await(store()
                .findHandler(fixture.key().tenantId(), "approval", "invoice-42")).orElseThrow();
        assertEquals(fixture.handlerId(), survived.handlerId());
        assertEquals(HandlerStatus.WAITING, survived.status());
        assertEquals(Set.of("APPROVER"), survived.authorization().requiredRoles(),
                "the authorization requirement is what makes the task resolvable by the right person, "
                        + "so it has to survive with it");
        assertEquals("application/vnd.ravenroot.test-approval", survived.payloadSchema().contentType());

        UUID resumeTraversalId = resolve(fixture, "approved");

        reopen();

        assertEquals(HandlerStatus.RESOLVED,
                await(store().loadHandler(fixture.key(), fixture.handlerId())).orElseThrow().status());
        List<PendingWork> claimed =
                await(store().claimPendingWork(fixture.key().tenantId(), "worker-after-restart", 10, TTL));
        assertEquals(1, claimed.size());
        assertEquals(resumeTraversalId,
                assertInstanceOf(PendingWork.HandlerTrigger.class, claimed.getFirst()).traversalId());

        // And the refusal is just as durable as the resolution: a trigger redelivered by a client
        // that never saw the first answer is refused identically after the restart.
        assertInstanceOf(ExecutionStoreFailure.HandlerNotResolvable.class,
                failureOf(() -> resolve(fixture, "approved")));
    }

    /** One handler, one trigger, and the acknowledgement is not lost to a later unrelated write. */
    @Test
    final void anAcknowledgedTriggerIsNotRedeliveredByALaterWriteToTheSameInstance() {
        assumeCapability(StoreCapability.DURABLE_HANDLERS);
        var fixture = waitingHandler(newKey(), "approval", "invoice-42", "dedup-1");
        resolve(fixture, "approved");

        PendingWork trigger =
                await(store().claimPendingWork(fixture.key().tenantId(), "worker-1", 10, TTL)).getFirst();
        await(store().ack(trigger));

        StoredProcessInstance current = await(store().load(fixture.key()));
        await(store().apply(ExecutionBatch.to(fixture.key())
                .expecting(RevisionExpectation.exactly(current.revision()))
                .apply(new ExecutionTransition.TraversalAdded(new Traversal(UUID.randomUUID(), "work",
                        TraversalStatus.ACCEPTED, Map.of())))
                .build()));

        assertTrue(await(store().claimPendingWork(fixture.key().tenantId(), "worker-1", 10, TTL)).isEmpty(),
                "a retained terminal handler must keep its acknowledgement, or its trigger replays forever");
    }

    /**
     * Both uniqueness rules must see the batch's <em>own</em> registrations, not only committed ones.
     *
     * <p>This is the assertion whose absence let the two adapters disagree. A store whose lookups
     * query inside its write transaction sees rows the same transaction inserted and refuses both
     * cases for free; a store that consults committed state instead accepts them, and then holds two
     * live handlers under one correlation key — the state
     * {@link ExecutionStoreFailure.HandlerCorrelationTaken} exists to make impossible — or two
     * handlers under one deduplication key, which is the exactly-once registration guarantee the
     * crash-recovery story rests on. Neither adapter can be trusted to have got it right by
     * construction, so it is asserted for both.</p>
     */
    @Test
    final void bothUniquenessRulesSeeRegistrationsMadeEarlierInTheSameBatch() {
        assumeCapability(StoreCapability.DURABLE_HANDLERS);

        ExecutionKey correlationKey = newKey();
        var correlationFixture = twoWaitingInvocations(correlationKey);
        ExecutionStoreFailure correlationTaken = failureOf(() -> await(store().apply(
                correlationFixture.batch()
                        .registerHandler(handlerRegistration(correlationFixture.firstHandlerId(),
                                "approval", correlationFixture.traversalId(),
                                correlationFixture.firstInvocationId(), "invoice-42", "dedup-a"))
                        .registerHandler(handlerRegistration(correlationFixture.secondHandlerId(),
                                "approval", correlationFixture.traversalId(),
                                correlationFixture.secondInvocationId(), "invoice-42", "dedup-b"))
                        .build())));
        assertInstanceOf(ExecutionStoreFailure.HandlerCorrelationTaken.class, correlationTaken,
                "two live handlers under one correlation key would make findHandler's answer "
                        + "depend on iteration order and strand whichever one it did not return");
        assertTrue(await(store().handlers(correlationKey)).isEmpty(),
                "the refused batch registers neither handler");

        ExecutionKey deduplicationKey = newKey();
        var deduplicationFixture = twoWaitingInvocations(deduplicationKey);
        ExecutionStoreFailure deduplicationRefused = failureOf(() -> await(store().apply(
                deduplicationFixture.batch()
                        .registerHandler(handlerRegistration(deduplicationFixture.firstHandlerId(),
                                "approval-a", deduplicationFixture.traversalId(),
                                deduplicationFixture.firstInvocationId(), "corr-a", "same-dedup"))
                        .registerHandler(handlerRegistration(deduplicationFixture.secondHandlerId(),
                                "approval-b", deduplicationFixture.traversalId(),
                                deduplicationFixture.secondInvocationId(), "corr-b", "same-dedup"))
                        .build())));
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class, deduplicationRefused);
        assertTrue(await(store().handlers(deduplicationKey)).isEmpty(),
                "a deduplication key that admitted two different handlers would break the "
                        + "exactly-once registration a retried wait depends on");
    }

    /** The same registration twice in one batch is the retry case, and collapses rather than failing. */
    @Test
    final void theSameRegistrationRepeatedWithinOneBatchCollapsesToASingleHandler() {
        assumeCapability(StoreCapability.DURABLE_HANDLERS);
        ExecutionKey key = newKey();
        var fixture = twoWaitingInvocations(key);
        HandlerRegistration registration = handlerRegistration(fixture.firstHandlerId(), "approval",
                fixture.traversalId(), fixture.firstInvocationId(), "invoice-42", "dedup-a");

        await(store().apply(fixture.batch()
                .registerHandler(registration)
                .registerHandler(registration)
                .build()));

        assertEquals(1, await(store().handlers(key)).size(),
                "a repeated identical registration is a retry, and a retry must not double-register");
    }

    /**
     * The re-entry traversal must be one the resolving batch created, not merely one that exists.
     *
     * <p>Naming the traversal that was <em>waiting</em> passes an existence check and produces a
     * trigger pointing a claimant at a traversal still in {@code WAITING} that nothing authorized it
     * to resume. The store is where this has to be refused, for the reason it refuses everything else
     * about a handler batch: a caller assembling its own batch is not bound by what the runtime
     * happens to do.</p>
     */
    @Test
    final void aTerminalTransitionMustNameATraversalTheSameBatchCreated() {
        assumeCapability(StoreCapability.DURABLE_HANDLERS);
        var fixture = waitingHandler(newKey(), "approval", "invoice-42", "dedup-1");
        StoredProcessInstance waiting = await(store().load(fixture.key()));

        ExecutionStoreFailure refused = failureOf(() -> await(store().apply(
                ExecutionBatch.to(fixture.key())
                        .expecting(RevisionExpectation.exactly(waiting.revision()))
                        .applyHandler(new HandlerTransition.Resolved(fixture.handlerId(),
                                "issuer|USER|approver", fixture.traversalId(),
                                OpaquePayload.of("approved".getBytes(StandardCharsets.UTF_8),
                                        "application/vnd.ravenroot.test-approval")))
                        .build())));
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class, refused);
        assertEquals(HandlerStatus.WAITING,
                await(store().loadHandler(fixture.key(), fixture.handlerId())).orElseThrow().status(),
                "the handler is untouched, so the wait it records is still the truth");
        assertEquals(waiting.revision(), await(store().load(fixture.key())).revision());
        assertTrue(await(store().claimPendingWork(fixture.key().tenantId(), "worker-1", 10, TTL)).isEmpty(),
                "nothing may become claimable from a re-entry point that was never authorized");
    }

    /** A trigger names the re-entry traversal and no invocation, because the new traversal has none. */
    @Test
    final void aTriggerNamesNoInvocationBecauseTheReEntryTraversalHasNotCreatedOneYet() {
        assumeCapability(StoreCapability.DURABLE_HANDLERS);
        var fixture = waitingHandler(newKey(), "approval", "invoice-42", "dedup-1");
        UUID resumeTraversalId = resolve(fixture, "approved");

        var trigger = assertInstanceOf(PendingWork.HandlerTrigger.class,
                await(store().claimPendingWork(fixture.key().tenantId(), "worker-1", 10, TTL)).getFirst());

        assertEquals(resumeTraversalId, trigger.traversalId());
        assertNull(trigger.invocationId(),
                "naming the waiting invocation would pair a new traversal with an invocation living "
                        + "under the old one, which no lookup resolves");
        assertTrue(await(store().load(fixture.key())).state().traversals().get(resumeTraversalId)
                        .invocations().isEmpty(),
                "and there is genuinely nothing to name: the claimant creates the first invocation");
        assertEquals(fixture.invocationId(),
                await(store().loadHandler(fixture.key(), trigger.workItemId())).orElseThrow()
                        .invocationId(),
                "the waiting invocation stays reachable through the handler the trigger is keyed by");
    }

    /** Identities for a batch that parks two invocations of one traversal, for the same-batch rules. */
    private record TwoWaits(ExecutionKey key, UUID traversalId, UUID firstInvocationId,
                            UUID secondInvocationId, UUID firstHandlerId, UUID secondHandlerId,
                            long revision) {
        /** The parking batch, open so a test can add the registrations it is actually asserting. */
        private ExecutionBatch.Builder batch() {
            return ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(revision))
                    .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                    .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                    .apply(new ExecutionTransition.InvocationAdded(traversalId,
                            new NodeInvocation(firstInvocationId, "await-first", Set.of(),
                                    NodeInvocationStatus.SCHEDULED, List.of(), NodeCommand.PROCESS)))
                    .apply(new ExecutionTransition.InvocationAdded(traversalId,
                            new NodeInvocation(secondInvocationId, "await-second", Set.of(),
                                    NodeInvocationStatus.SCHEDULED, List.of(), NodeCommand.PROCESS)))
                    .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.WAITING));
        }
    }

    private TwoWaits twoWaitingInvocations(ExecutionKey key) {
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        return new TwoWaits(key, traversalId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), created.revision());
    }

    private static HandlerRegistration handlerRegistration(UUID handlerId, String name, UUID traversalId,
                                                           UUID invocationId, String correlationKey,
                                                           String deduplicationKey) {
        return new HandlerRegistration(handlerId, name, traversalId, invocationId, correlationKey,
                deduplicationKey,
                new HandlerPayloadSchema("application/vnd.ravenroot.test-approval", "approval/v1", 1024),
                HandlerAuthorization.ofRoles("APPROVER"));
    }

    /** Identities the whole PERS-05 story is told in, kept together so a test reads as one wait. */
    private record HandlerFixture(ExecutionKey key, UUID traversalId, UUID invocationId, UUID handlerId,
                                  HandlerRegistration registration) {
    }

    /**
     * Creates an instance whose only traversal is {@code WAITING} on one freshly registered handler.
     *
     * <p>The invocation carries no attempt, deliberately: an attempt would be claimable work of its
     * own and every trigger assertion would then have to filter it out, which is exactly how a test
     * comes to pass for the wrong reason.</p>
     */
    private HandlerFixture waitingHandler(ExecutionKey key, String name, String correlationKey,
                                          String deduplicationKey) {
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID handlerId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        var registration = new HandlerRegistration(handlerId, name, traversalId, invocationId,
                correlationKey, deduplicationKey,
                new HandlerPayloadSchema("application/vnd.ravenroot.test-approval", "approval/v1", 1024),
                HandlerAuthorization.ofRoles("APPROVER"));
        await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .apply(new ExecutionTransition.InvocationAdded(traversalId,
                        new NodeInvocation(invocationId, "await-approval", Set.of(),
                                NodeInvocationStatus.SCHEDULED, List.of(), NodeCommand.PROCESS)))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.WAITING))
                .registerHandler(registration)
                .build()));
        return new HandlerFixture(key, traversalId, invocationId, handlerId, registration);
    }

    /** Resolves a fixture's handler and returns the re-entry traversal the resolution committed. */
    private UUID resolve(HandlerFixture fixture, String outcome) {
        StoredProcessInstance current = await(store().load(fixture.key()));
        UUID resumeTraversalId = UUID.randomUUID();
        await(store().apply(ExecutionBatch.to(fixture.key())
                .expecting(RevisionExpectation.exactly(current.revision()))
                .apply(new ExecutionTransition.TraversalAdded(new Traversal(resumeTraversalId, "work",
                        TraversalStatus.ACCEPTED, Map.of())))
                .applyHandler(new HandlerTransition.Resolved(fixture.handlerId(), "issuer|USER|approver",
                        resumeTraversalId, OpaquePayload.of(outcome.getBytes(StandardCharsets.UTF_8),
                                "application/vnd.ravenroot.test-approval")))
                .build()));
        return resumeTraversalId;
    }

    private void escalate(HandlerFixture fixture, String reason) {
        StoredProcessInstance current = await(store().load(fixture.key()));
        await(store().apply(ExecutionBatch.to(fixture.key())
                .expecting(RevisionExpectation.exactly(current.revision()))
                .applyHandler(new HandlerTransition.Escalated(fixture.handlerId(), reason))
                .build()));
    }

    // ============================================== PERS-07: journal, outbox and inbox (ADR 0011)

    @Test
    final void anEventAndItsTransitionShareOneCommitSoNeitherCanBeObservedWithoutTheOther() {
        assumeCapability(StoreCapability.EVENT_JOURNAL);
        var key = new ExecutionKey(DEFAULT_TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));

        EventEnvelope envelope = event(key, traversalId, "process.running");
        StoredProcessInstance applied = await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .publish(envelope)
                .build()));

        List<JournalRecord> journal = await(store().readJournal(DEFAULT_TENANT, 0, 10));
        assertEquals(1, journal.size(), "the event committed with its transition");
        assertEquals(envelope.eventId(), journal.get(0).envelope().eventId());
        assertEquals(applied.revision(), journal.get(0).committedAtRevision(),
                "committedAtRevision is what makes the shared boundary observable rather than merely "
                        + "asserted: it names the exact transition the event was written beside");
        assertEquals(ProcessInstanceStatus.RUNNING, applied.state().status());
    }

    @Test
    final void aRejectedBatchJournalsNothingSoTheTransitionAndTheEventFailTogether() {
        assumeCapability(StoreCapability.EVENT_JOURNAL);
        var key = new ExecutionKey(DEFAULT_TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));

        // A stale expectation, so the batch is rejected after its envelope has been validated.
        failureOf(() -> await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision() + 99))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .publish(event(key, traversalId, "process.running"))
                .build())));

        assertTrue(await(store().readJournal(DEFAULT_TENANT, 0, 10)).isEmpty(),
                "an event surviving a rejected batch would be an event describing a transition that "
                        + "never happened, which is worse than a lost event because it is believed");
    }

    @Test
    final void streamSequenceIsContiguousPerInstanceAndPreservesTheOrderWithinABatch() {
        assumeCapability(StoreCapability.EVENT_JOURNAL);
        var first = new ExecutionKey(DEFAULT_TENANT, UUID.randomUUID());
        var second = new ExecutionKey(DEFAULT_TENANT, UUID.randomUUID());
        UUID firstTraversal = UUID.randomUUID();
        UUID secondTraversal = UUID.randomUUID();
        StoredProcessInstance createdFirst = await(store().apply(creationBatch(first, firstTraversal, "g")));
        StoredProcessInstance createdSecond = await(store().apply(creationBatch(second, secondTraversal, "g")));

        EventEnvelope cause = event(first, firstTraversal, "a");
        EventEnvelope effect = event(first, firstTraversal, "b");
        await(store().apply(ExecutionBatch.to(first)
                .expecting(RevisionExpectation.exactly(createdFirst.revision()))
                .publish(cause).publish(effect).build()));
        await(store().apply(ExecutionBatch.to(second)
                .expecting(RevisionExpectation.exactly(createdSecond.revision()))
                .publish(event(second, secondTraversal, "c")).build()));

        List<JournalRecord> journal = await(store().readJournal(DEFAULT_TENANT, 0, 10));
        assertEquals(3, journal.size());
        assertEquals(cause.eventId(), journal.get(0).envelope().eventId(), "batch order is preserved");
        assertEquals(effect.eventId(), journal.get(1).envelope().eventId());
        assertEquals(1L, journal.get(0).streamSequence());
        assertEquals(2L, journal.get(1).streamSequence());
        assertEquals(1L, journal.get(2).streamSequence(),
                "stream sequence is per process instance, so the second instance starts at one; a "
                        + "counter shared across instances would make it a disguised global clock");
        assertTrue(journal.get(2).journalOffset() > journal.get(1).journalOffset(),
                "the tenant-wide offset, unlike the stream sequence, keeps increasing across instances");
    }

    @Test
    final void anEnvelopeNamingAnotherTenantIsRejectedBeforeItCanReachThisTenantsSubscribers() {
        assumeCapability(StoreCapability.EVENT_JOURNAL);
        var key = new ExecutionKey(DEFAULT_TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));

        EventEnvelope foreign = EventEnvelope.of(UUID.randomUUID(), "other-tenant", "leak",
                key.processInstanceId(), traversalId, null, null, null, "req-1", "graph-v1",
                clock().instant(), OpaquePayload.empty("application/octet-stream"));

        ExecutionStoreFailure failure = failureOf(() -> await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .publish(foreign).build())));
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class, failure,
                "decidable from the request alone, so InvalidRequest per ADR 0010 section 12.3");
        assertTrue(await(store().readJournal(DEFAULT_TENANT, 0, 10)).isEmpty());
    }

    @Test
    final void aFreshDestinationStartsAtZeroAndAdvancingIsACompareAndSet() {
        assumeCapability(StoreCapability.EVENT_JOURNAL);
        var key = new ExecutionKey(DEFAULT_TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .publish(event(key, traversalId, "e")).build()));
        long offset = await(store().readJournal(DEFAULT_TENANT, 0, 10)).get(0).journalOffset();

        JournalCursor start = await(store().outboxCursor(DEFAULT_TENANT, "sse"));
        assertEquals(0L, start.deliveredThrough(), "a destination that never delivered reads as zero");

        await(store().advanceOutboxCursor(start, offset));
        assertEquals(offset, await(store().outboxCursor(DEFAULT_TENANT, "sse")).deliveredThrough());

        // The stale advance is what a second publisher for the same destination would attempt.
        ExecutionStoreFailure failure = failureOf(() -> await(store().advanceOutboxCursor(start, offset)));
        var conflict = assertInstanceOf(ExecutionStoreFailure.OutboxCursorConflict.class, failure);
        assertEquals(Retryability.RETRY_AFTER_REREAD, conflict.retryability(),
                "a blind retry loops forever and a successful stale advance would skip events this "
                        + "publisher never delivered, which is a silent loss rather than a duplicate");
        assertEquals(offset, conflict.actual());
    }

    @Test
    final void aCursorMayNotRetreatBecauseARetreatRedeliversForever() {
        assumeCapability(StoreCapability.EVENT_JOURNAL);
        JournalCursor at = new JournalCursor(DEFAULT_TENANT, "sse", 5L);
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                failureOf(() -> await(store().advanceOutboxCursor(at, 4L))));
    }

    @Test
    final void theInboxAdmitsAnEventOncePerConsumerWhichIsWhatMakesRedeliverySafe() {
        assumeCapability(StoreCapability.EVENT_JOURNAL);
        UUID eventId = UUID.randomUUID();
        Duration retention = Duration.ofMinutes(10);

        assertTrue(await(store().recordInboxDelivery(DEFAULT_TENANT, "sse", eventId, retention)),
                "the first delivery must be admitted, or the effect never happens at all");
        assertFalse(await(store().recordInboxDelivery(DEFAULT_TENANT, "sse", eventId, retention)),
                "a redelivery must be refused, which is what converts at-least-once delivery into an "
                        + "at-most-once effect");
        assertTrue(await(store().recordInboxDelivery(DEFAULT_TENANT, "kafka", eventId, retention)),
                "a different consumer must still receive it once; a shared key would let whichever "
                        + "consumer arrived first suppress the event for every other one");
        assertEquals(2L, await(store().inboxRecordCount(DEFAULT_TENANT)));
    }

    @Test
    final void inboxRecordsAreScopedToTheirTenant() {
        assumeCapability(StoreCapability.EVENT_JOURNAL);
        UUID eventId = UUID.randomUUID();
        Duration retention = Duration.ofMinutes(10);
        assertTrue(await(store().recordInboxDelivery(DEFAULT_TENANT, "sse", eventId, retention)));
        assertTrue(await(store().recordInboxDelivery("other-tenant", "sse", eventId, retention)),
                "the same event id under a different tenant is a different event, and treating it as "
                        + "a duplicate would silently drop one tenant's work because another tenant "
                        + "happened to mint a colliding identifier");
        assertEquals(1L, await(store().inboxRecordCount(DEFAULT_TENANT)));
    }

    @Test
    final void compactionKeepsWhateverAnyDestinationHasNotYetDelivered() {
        assumeCapability(StoreCapability.JOURNAL_COMPACTION);
        var key = new ExecutionKey(DEFAULT_TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .publish(event(key, traversalId, "a")).publish(event(key, traversalId, "b")).build()));
        List<JournalRecord> journal = await(store().readJournal(DEFAULT_TENANT, 0, 10));
        long firstOffset = journal.get(0).journalOffset();

        // Age everything past the retention window, so only delivery decides what may go.
        clock().advance(store().journalRetention().plusMinutes(1));

        assertEquals(0L, await(store().compactJournal(DEFAULT_TENANT)),
                "with no destination at all nothing has been delivered, so nothing is compactable; "
                        + "reading 'nobody is listening' as 'everybody received it' would discard the "
                        + "backlog of a deployment whose projection is not enabled yet");

        JournalCursor cursor = await(store().outboxCursor(DEFAULT_TENANT, "sse"));
        await(store().advanceOutboxCursor(cursor, firstOffset));
        assertEquals(1L, await(store().compactJournal(DEFAULT_TENANT)),
                "only the delivered, expired prefix goes");
        assertEquals(1, await(store().readJournal(DEFAULT_TENANT, firstOffset, 10)).size(),
                "the undelivered event survives");
    }

    @Test
    final void compactionDoesNotDiscardDeliveredRecordsThatAreStillWithinRetention() {
        assumeCapability(StoreCapability.JOURNAL_COMPACTION);
        var key = new ExecutionKey(DEFAULT_TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .publish(event(key, traversalId, "a")).build()));
        long offset = await(store().readJournal(DEFAULT_TENANT, 0, 10)).get(0).journalOffset();
        await(store().advanceOutboxCursor(await(store().outboxCursor(DEFAULT_TENANT, "sse")), offset));

        assertEquals(0L, await(store().compactJournal(DEFAULT_TENANT)),
                "delivery alone is not licence to forget: the declared retention window is the replay "
                        + "guarantee, and a delivered event is exactly what a reconnecting consumer "
                        + "asks to be replayed");
        assertEquals(1, await(store().readJournal(DEFAULT_TENANT, 0, 10)).size());
    }

    @Test
    final void readingBelowTheRetainedFloorFailsLoudlyInsteadOfReturningAStreamWithAHoleInIt() {
        assumeCapability(StoreCapability.JOURNAL_COMPACTION);
        var key = new ExecutionKey(DEFAULT_TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .publish(event(key, traversalId, "a")).publish(event(key, traversalId, "b")).build()));
        List<JournalRecord> journal = await(store().readJournal(DEFAULT_TENANT, 0, 10));
        long firstOffset = journal.get(0).journalOffset();
        long secondOffset = journal.get(1).journalOffset();

        assertEquals(1L, await(store().journalRetainedFrom(DEFAULT_TENANT)),
                "nothing has been forgotten yet, so the floor is the first offset ever issued");

        await(store().advanceOutboxCursor(await(store().outboxCursor(DEFAULT_TENANT, "sse")), secondOffset));
        clock().advance(store().journalRetention().plusMinutes(1));
        assertEquals(2L, await(store().compactJournal(DEFAULT_TENANT)));
        assertEquals(secondOffset + 1, await(store().journalRetainedFrom(DEFAULT_TENANT)));

        ExecutionStoreFailure failure =
                failureOf(() -> await(store().readJournal(DEFAULT_TENANT, firstOffset - 1, 10)));
        var truncated = assertInstanceOf(ExecutionStoreFailure.JournalTruncated.class, failure,
                "returning the surviving suffix would hand back a stream with a hole in it that is "
                        + "indistinguishable from a complete one, and the projection built from it "
                        + "would be wrong permanently and silently");
        assertEquals(secondOffset + 1, truncated.retainedFrom(),
                "the failure names where the caller can resume, rather than making it probe");
        assertEquals(Retryability.DETERMINISTIC_REJECT, truncated.retryability());

        // A consumer already at the head is not truncated: it is simply up to date.
        assertTrue(await(store().readJournal(DEFAULT_TENANT, secondOffset, 10)).isEmpty());
    }

    @Test
    final void offsetsAreNeverReissuedAfterTheJournalHasBeenCompactedToEmpty() {
        assumeCapability(StoreCapability.JOURNAL_COMPACTION);
        var key = new ExecutionKey(DEFAULT_TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        StoredProcessInstance first = await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .publish(event(key, traversalId, "a")).build()));
        long firstOffset = await(store().readJournal(DEFAULT_TENANT, 0, 10)).get(0).journalOffset();

        await(store().advanceOutboxCursor(await(store().outboxCursor(DEFAULT_TENANT, "sse")), firstOffset));
        clock().advance(store().journalRetention().plusMinutes(1));
        assertEquals(1L, await(store().compactJournal(DEFAULT_TENANT)));
        assertTrue(await(store().readJournal(DEFAULT_TENANT, firstOffset, 10)).isEmpty());

        await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(first.revision()))
                .publish(event(key, traversalId, "b")).build()));

        List<JournalRecord> after = await(store().readJournal(DEFAULT_TENANT, firstOffset, 10));
        assertEquals(1, after.size());
        assertTrue(after.get(0).journalOffset() > firstOffset,
                "a reissued offset would sit at or below a destination cursor that has already passed "
                        + "it, so the event would never be delivered to that destination and nothing "
                        + "would report it missing");
        assertEquals(2L, after.get(0).streamSequence(),
                "the per-instance sequence must not restart either, or two different events would "
                        + "share a position within one execution");
    }

    @Test
    final void journalReadsAreScopedToTheirTenant() {
        assumeCapability(StoreCapability.EVENT_JOURNAL);
        var mine = new ExecutionKey(DEFAULT_TENANT, UUID.randomUUID());
        var theirs = new ExecutionKey("other-tenant", UUID.randomUUID());
        UUID mineTraversal = UUID.randomUUID();
        UUID theirsTraversal = UUID.randomUUID();
        StoredProcessInstance createdMine = await(store().apply(creationBatch(mine, mineTraversal, "g")));
        StoredProcessInstance createdTheirs = await(store().apply(creationBatch(theirs, theirsTraversal, "g")));
        await(store().apply(ExecutionBatch.to(mine)
                .expecting(RevisionExpectation.exactly(createdMine.revision()))
                .publish(event(mine, mineTraversal, "a")).build()));
        await(store().apply(ExecutionBatch.to(theirs)
                .expecting(RevisionExpectation.exactly(createdTheirs.revision()))
                .publish(event(theirs, theirsTraversal, "b")).build()));

        List<JournalRecord> mineJournal = await(store().readJournal(DEFAULT_TENANT, 0, 10));
        assertEquals(1, mineJournal.size(), "one tenant's journal never contains another's events");
        assertEquals(DEFAULT_TENANT, mineJournal.get(0).envelope().tenantId());
        assertEquals(1, await(store().readJournal("other-tenant", 0, 10)).size());
        assertEquals(0L, await(store().outboxCursor("other-tenant", "sse")).deliveredThrough());
    }

    @Test
    final void aStoredEnvelopeCarriesTheDigestItWasBuiltWithAndThatDigestDescribesItsContent() {
        assumeCapability(StoreCapability.EVENT_JOURNAL);
        var key = new ExecutionKey(DEFAULT_TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        EventEnvelope envelope = event(key, traversalId, "process.running");
        await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .publish(envelope).build()));

        EventEnvelope stored = await(store().readJournal(DEFAULT_TENANT, 0, 10)).get(0).envelope();
        assertEquals(envelope.digest(), stored.digest(), "the digest survives the round trip unchanged");
        assertTrue(stored.digestMatchesContent(),
                "and it still describes the content it came back with, which is the only property "
                        + "that makes the stored digest evidence about storage rather than about the "
                        + "producer agreeing with itself");
        assertEquals(EventEnvelope.CURRENT_VERSION, stored.envelopeVersion());
        assertEquals(envelope.correlationId(), stored.correlationId(), "causality survives storage");
        assertEquals(envelope.causationId(), stored.causationId());
    }

    @Test
    final void redeliveryAfterAnUnadvancedCursorRepeatsTheEventAndTheInboxAbsorbsIt() {
        assumeCapability(StoreCapability.EVENT_JOURNAL);
        var key = new ExecutionKey(DEFAULT_TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));
        EventEnvelope envelope = event(key, traversalId, "process.running");
        await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .publish(envelope).build()));

        // First pass: read, deliver, then crash before advancing the cursor.
        JournalCursor cursor = await(store().outboxCursor(DEFAULT_TENANT, "sse"));
        List<JournalRecord> firstPass = await(store().readJournal(DEFAULT_TENANT, cursor.deliveredThrough(), 10));
        assertEquals(1, firstPass.size());
        assertTrue(await(store().recordInboxDelivery(DEFAULT_TENANT, "sse",
                firstPass.get(0).envelope().eventId(), Duration.ofHours(1))));

        // Second pass: the cursor never moved, so the same event comes back. Nothing was lost.
        List<JournalRecord> secondPass = await(store().readJournal(DEFAULT_TENANT,
                await(store().outboxCursor(DEFAULT_TENANT, "sse")).deliveredThrough(), 10));
        assertEquals(1, secondPass.size(), "an unadvanced cursor redelivers rather than losing");
        assertEquals(firstPass.get(0).envelope().eventId(), secondPass.get(0).envelope().eventId());
        assertEquals(firstPass.get(0).envelope().digest(), secondPass.get(0).envelope().digest(),
                "a redelivered event digests identically, so a consumer can recognise it as the same "
                        + "event rather than as a new one that merely looks similar");
        assertFalse(await(store().recordInboxDelivery(DEFAULT_TENANT, "sse",
                secondPass.get(0).envelope().eventId(), Duration.ofHours(1))),
                "and the inbox refuses it, so the redelivery costs a duplicate DELIVERY and never a "
                        + "duplicate EFFECT");
    }

    /**
     * Compaction under a clock that has stepped <strong>backwards</strong>.
     *
     * <p>Every other assertion here advances the clock monotonically, under which "expired" and
     * "delivered" each select a prefix of the journal and their intersection is a prefix for free. So
     * an adapter that discarded every expired-and-delivered record wherever it sat, rather than a
     * contiguous prefix, passes all of them — which is not a hypothesis: removing that guard from the
     * first adapter survived this entire suite, and only this assertion caught it.</p>
     *
     * <p>The store's clock is injected and a real one steps backwards under an NTP correction, so a
     * later offset can carry an earlier {@code recordedAt} than an earlier offset. An adapter without
     * the guard then punches a hole in the middle of the journal and advances the retained floor past
     * records it is still holding, so {@code readJournal} reports a truncation that did not happen and
     * a consumer resyncs from a floor that is a lie.</p>
     */
    @Test
    final void aBackwardClockStepCannotMakeCompactionPunchAHoleInTheJournal() {
        assumeCapability(StoreCapability.JOURNAL_COMPACTION);
        var key = new ExecutionKey(DEFAULT_TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        Duration retention = store().journalRetention();
        Instant base = clock().instant();

        StoredProcessInstance created = await(store().apply(creationBatch(key, traversalId, "graph-v1")));

        // Offset one is recorded well ahead of offset two, which is what a correction between the
        // two writes produces.
        clock().set(base.plus(Duration.ofHours(10)));
        StoredProcessInstance first = await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .publish(event(key, traversalId, "late")).build()));

        clock().set(base);
        await(store().apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(first.revision()))
                .publish(event(key, traversalId, "early")).build()));

        List<JournalRecord> journal = await(store().readJournal(DEFAULT_TENANT, 0, 10));
        assertEquals(2, journal.size());
        long firstOffset = journal.get(0).journalOffset();
        long secondOffset = journal.get(1).journalOffset();

        await(store().advanceOutboxCursor(await(store().outboxCursor(DEFAULT_TENANT, "sse")), secondOffset));
        // A cutoff that expires the second record but not the first.
        clock().set(base.plus(Duration.ofHours(5)).plus(retention));

        assertEquals(0L, await(store().compactJournal(DEFAULT_TENANT)),
                "the second record is expired and delivered, but the first is not expired, so "
                        + "discarding the second alone would leave a hole; compaction must take a "
                        + "contiguous prefix or nothing at all");
        assertEquals(firstOffset, await(store().journalRetainedFrom(DEFAULT_TENANT)),
                "and the floor must not move past a record the store is still holding");
        assertEquals(2, await(store().readJournal(DEFAULT_TENANT, 0, 10)).size(),
                "both records stay readable from the very beginning of the journal");
    }

    /** An envelope for {@code key}, carrying causality so the assertions above have something to check. */
    private EventEnvelope event(ExecutionKey key, UUID traversalId, String eventType) {
        return EventEnvelope.of(UUID.randomUUID(), key.tenantId(), eventType, key.processInstanceId(),
                traversalId, null, null, UUID.randomUUID(), "request-1", "graph-v1", clock().instant(),
                OpaquePayload.of(eventType.getBytes(StandardCharsets.UTF_8), "application/json"));
    }

    private static OpaquePayload fingerprint(String value) {
        return OpaquePayload.of(value.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    /** Also asserts, on every call, that adapters never leak a non-{@link ExecutionStoreException}. */
    private static ExecutionStoreFailure failureOf(Runnable operation) {
        CompletionException thrown = assertThrows(CompletionException.class, operation::run);
        ExecutionStoreException failure = ExecutionStoreException.unwrap(thrown);
        assertNotNull(failure, "adapters must not leak non-store exceptions: " + thrown);
        return failure.failure();
    }
}
