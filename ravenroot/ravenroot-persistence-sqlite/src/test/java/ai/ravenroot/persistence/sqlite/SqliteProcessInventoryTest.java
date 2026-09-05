package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.application.ExecutionTerminationReason;
import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionOrigin;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.InventoryDisposition;
import ai.ravenroot.api.persistence.ProcessInventoryEntry;
import ai.ravenroot.api.persistence.ProcessInventoryPage;
import ai.ravenroot.api.persistence.ProcessInventoryQuery;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.HandlerPayloadSchema;
import ai.ravenroot.api.persistence.HandlerRegistration;
import ai.ravenroot.api.persistence.TraversalInventoryEntry;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The durable tenant-scoped process and traversal inventory, against the adapter that can
 * actually lose a process.
 *
 * <p>These assertions live here rather than in the shared conformance suite because the property the
 * issue is about — that an authorized tenant can rediscover its work <em>after a complete restart</em>
 * — is only observable on an adapter that survives one. The suite gets the adapter-neutral half in the
 * next wave; what cannot be delegated is the reopen.</p>
 */
class SqliteProcessInventoryTest {

    private static final Instant EPOCH = Instant.parse("2026-03-01T00:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(1);
    private static final String TENANT = "acme";
    private static final String OTHER_TENANT = "globex";

    @TempDir
    Path databaseDirectory;

    @Test
    void theAdapterDeclaresBothInventoryCapabilitiesAndItsPublishedBounds() {
        var clock = new MutableClock(EPOCH);
        try (var store = open("declares.db", clock)) {
            assertTrue(store.supports(StoreCapability.PROCESS_INVENTORY));
            assertTrue(store.supports(StoreCapability.INVENTORY_RETENTION));
            assertEquals(100, store.maxInventoryPageSize());
            assertEquals(Duration.ofDays(7), store.terminalRetention());
            assertTrue(store.terminalRetention().compareTo(store.journalRetention()) >= 0,
                    "a terminal instance must outlive its own events, or the journal would name an "
                            + "instance the inventory can no longer describe");
        }
    }

    @Test
    void everyRetainedInstanceIsRediscoverableAfterACompleteReopenWithItsTraversals() {
        Path file = databaseDirectory.resolve("reopen.db");
        var clock = new MutableClock(EPOCH);
        var running = new ExecutionKey(TENANT, UUID.randomUUID());
        var waiting = new ExecutionKey(TENANT, UUID.randomUUID());
        var failed = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID runningTraversal = UUID.randomUUID();

        try (var store = open(file, clock)) {
            createRunning(store, running, runningTraversal);
            clock.advance(Duration.ofSeconds(1));
            createWaiting(store, waiting, UUID.randomUUID());
            clock.advance(Duration.ofSeconds(1));
            createFailed(store, failed, UUID.randomUUID());
        }

        // A complete process death: no lease was released, nothing was drained, and the only thing
        // carried across is the file itself.
        try (var reopened = open(file, clock)) {
            ProcessInventoryPage page = await(reopened.listProcessInstances(TENANT,
                    ProcessInventoryQuery.everything(10)));
            assertEquals(3, page.items().size(), "every retained instance must be rediscoverable "
                    + "from durable state alone; the in-memory live listing is gone by definition");
            assertEquals(List.of(failed.processInstanceId(), waiting.processInstanceId(),
                            running.processInstanceId()),
                    page.items().stream().map(item -> item.key().processInstanceId()).toList(),
                    "newest first");
            assertEquals(Optional.empty(), page.nextCursor());
            assertEquals(Instant.MIN, page.retainedFrom(),
                    "nothing has been purged, so the floor has not moved");

            ProcessInventoryEntry entry = only(reopened, running);
            assertEquals(ProcessInstanceStatus.RUNNING, entry.status());
            assertEquals(InventoryDisposition.INTERRUPTED, entry.disposition(),
                    "a lease that nobody is renewing after a restart is exactly the recovery cohort");
            assertEquals(Optional.empty(), entry.ownerWorkerId());
            assertEquals(EPOCH, entry.createdAt());
            assertEquals(1, entry.traversalCount());
            assertEquals(Optional.empty(), entry.retainedUntil(),
                    "retention has not started for a non-terminal instance");

            List<TraversalInventoryEntry> traversals = await(reopened.listTraversals(running));
            assertEquals(1, traversals.size());
            assertEquals(runningTraversal, traversals.get(0).traversalId());
            assertEquals(0, traversals.get(0).position());
            assertEquals("start", traversals.get(0).ingressNodeId());
            assertEquals(TraversalStatus.ACCEPTED, traversals.get(0).status());
            assertEquals(0, traversals.get(0).parkedAttemptCount());
        }
    }

    @Test
    void transientAndDeploymentHostedWorkShareOneIdentityContractWithoutBeingConflated() {
        var clock = new MutableClock(EPOCH);
        var transientKey = new ExecutionKey(TENANT, UUID.randomUUID());
        var hosted = new ExecutionKey(TENANT, UUID.randomUUID());
        try (var store = open("origin.db", clock)) {
            createRunning(store, transientKey, UUID.randomUUID());
            clock.advance(Duration.ofSeconds(1));
            StoredProcessInstance created = await(store.apply(ExecutionBatch
                    .to(hosted)
                    .expecting(RevisionExpectation.notPresent())
                    .apply(new ExecutionTransition.ProcessCreated(
                            Fixtures.acceptedInstance(hosted.processInstanceId(), UUID.randomUUID()),
                            new ai.ravenroot.api.persistence.GraphVersionPin("graph-v1")))
                    .recordOrigin(ExecutionOrigin.of("dep-7", "nightly-batch", "corr-42"))
                    .build()));

            ProcessInventoryEntry deployed = only(store, hosted);
            assertEquals(Optional.of("dep-7"), deployed.deploymentId());
            assertEquals(Optional.of("nightly-batch"), deployed.workloadId());
            assertEquals(Optional.of("corr-42"), deployed.correlationId());
            assertEquals(new ai.ravenroot.api.persistence.GraphVersionPin("graph-v1"),
                    deployed.graphVersionPin(), "the deployment is not the graph version and the "
                            + "inventory must keep the two identities apart");

            ProcessInventoryEntry submitted = only(store, transientKey);
            assertEquals(Optional.empty(), submitted.deploymentId(),
                    "a transient submission has no host, and absence is how that is said");

            // A later write that does not know the deployment must not erase it: annotation semantics,
            // not a transition, so no ordering of partially-informed callers destroys information.
            await(store.apply(ExecutionBatch.to(hosted)
                    .expecting(RevisionExpectation.exactly(created.revision()))
                    .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                    .build()));
            assertEquals(Optional.of("dep-7"), only(store, hosted).deploymentId());

            assertEquals(List.of(hosted.processInstanceId()),
                    await(store.listProcessInstances(TENANT, ProcessInventoryQuery.builder()
                            .hostedBy("dep-7").limit(10).build()))
                            .items().stream().map(item -> item.key().processInstanceId()).toList());
        }
    }

    @Test
    void paginationIsDeterministicWhileNewWorkIsAccepted() {
        var clock = new MutableClock(EPOCH);
        try (var store = open("pagination.db", clock)) {
            var created = new ArrayList<UUID>();
            for (int index = 0; index < 5; index++) {
                var key = new ExecutionKey(TENANT, UUID.randomUUID());
                createRunning(store, key, UUID.randomUUID());
                created.add(key.processInstanceId());
                clock.advance(Duration.ofSeconds(1));
            }

            var seen = new ArrayList<UUID>();
            ProcessInventoryQuery query = ProcessInventoryQuery.outstanding(2);
            ProcessInventoryPage page = await(store.listProcessInstances(TENANT, query));
            page.items().forEach(item -> seen.add(item.key().processInstanceId()));

            // Accepted mid-scan. It sorts BEFORE page one, so this scan must not see it -- and, more
            // importantly, its arrival must not shift a row the scan has not reached yet.
            var late = new ExecutionKey(TENANT, UUID.randomUUID());
            createRunning(store, late, UUID.randomUUID());
            clock.advance(Duration.ofSeconds(1));

            while (page.nextCursor().isPresent()) {
                page = await(store.listProcessInstances(TENANT, query.after(page.nextCursor().get())));
                page.items().forEach(item -> seen.add(item.key().processInstanceId()));
            }

            assertEquals(5, seen.size(), "a scan in flight sees each row exactly once");
            assertEquals(Set.copyOf(created), Set.copyOf(seen), "and never loses one");
            assertEquals(seen.size(), Set.copyOf(seen).size(), "and never repeats one");
            assertFalse(seen.contains(late.processInstanceId()),
                    "work created after the scan started sorts before page one and is simply not seen "
                            + "by that scan; asking for page one again is how a caller picks it up");

            var reversed = new ArrayList<>(created);
            java.util.Collections.reverse(reversed);
            assertEquals(reversed, seen, "newest first, throughout");
        }
    }

    @Test
    void aPageThatExactlyFillsTheLimitDoesNotMintACursorForANextPageThatIsEmpty() {
        var clock = new MutableClock(EPOCH);
        try (var store = open("exact.db", clock)) {
            for (int index = 0; index < 2; index++) {
                createRunning(store, new ExecutionKey(TENANT, UUID.randomUUID()), UUID.randomUUID());
                clock.advance(Duration.ofSeconds(1));
            }
            ProcessInventoryPage page = await(store.listProcessInstances(TENANT,
                    ProcessInventoryQuery.outstanding(2)));
            assertEquals(2, page.items().size());
            assertEquals(Optional.empty(), page.nextCursor(), "a cursor handed back here would cost "
                    + "every caller an empty round trip, and one that reads a present cursor as "
                    + "'there is more' would report work that does not exist");
        }
    }

    @Test
    void anotherTenantsInstanceIsIndistinguishableFromAMissingOneAndNeverADenial() {
        var clock = new MutableClock(EPOCH);
        var mine = new ExecutionKey(TENANT, UUID.randomUUID());
        try (var store = open("isolation.db", clock)) {
            createRunning(store, mine, UUID.randomUUID());

            var borrowed = new ExecutionKey(OTHER_TENANT, mine.processInstanceId());
            var absent = new ExecutionKey(OTHER_TENANT, UUID.randomUUID());
            assertEquals(Optional.empty(), await(store.findProcessInstance(borrowed)));
            assertEquals(await(store.findProcessInstance(absent)),
                    await(store.findProcessInstance(borrowed)),
                    "a real instance under the wrong tenant and an id that never existed must be the "
                            + "same answer, or the store is a cross-tenant existence oracle");

            assertInstanceOf(ExecutionStoreFailure.NotFound.class,
                    failureOf(() -> await(store.listTraversals(borrowed))));
            assertInstanceOf(ExecutionStoreFailure.NotFound.class,
                    failureOf(() -> await(store.listTraversals(absent))));

            assertEquals(List.of(), await(store.listProcessInstances(OTHER_TENANT,
                    ProcessInventoryQuery.everything(10))).items());
        }
    }

    @Test
    void aCursorMintedForOneTenantIsRefusedUnderAnother() {
        var clock = new MutableClock(EPOCH);
        try (var store = open("cursor-tenant.db", clock)) {
            for (int index = 0; index < 3; index++) {
                createRunning(store, new ExecutionKey(TENANT, UUID.randomUUID()), UUID.randomUUID());
                clock.advance(Duration.ofSeconds(1));
            }
            String cursor = await(store.listProcessInstances(TENANT,
                    ProcessInventoryQuery.outstanding(1))).nextCursor().orElseThrow();

            var refused = assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                    failureOf(() -> await(store.listProcessInstances(OTHER_TENANT,
                            ProcessInventoryQuery.outstanding(1).after(cursor)))));
            assertFalse(refused.reason().contains(TENANT),
                    "the rejection must not name the tenant the cursor belongs to; that would confirm "
                            + "the other tenant exists");

            assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                    failureOf(() -> await(store.listProcessInstances(TENANT,
                            ProcessInventoryQuery.outstanding(1).after("not-a-cursor")))));
        }
    }

    @Test
    void aTerminalInstanceIsRemovedOnlyByAnExplicitPurgeAndTheFloorThenSaysWhereCompletenessEnds() {
        var clock = new MutableClock(EPOCH);
        var terminal = new ExecutionKey(TENANT, UUID.randomUUID());
        var live = new ExecutionKey(TENANT, UUID.randomUUID());
        try (var store = open("retention.db", clock)) {
            createFailed(store, terminal, UUID.randomUUID());
            clock.advance(Duration.ofSeconds(1));
            createRunning(store, live, UUID.randomUUID());

            Instant deadline = only(store, terminal).retainedUntil().orElseThrow();
            assertEquals(EPOCH.plus(Duration.ofDays(7)), deadline,
                    "retention starts at the terminal transition and runs for the declared window");
            assertEquals(InventoryDisposition.TERMINAL, only(store, terminal).disposition());

            clock.advance(Duration.ofDays(7).plusSeconds(1));
            assertTrue(await(store.findProcessInstance(terminal)).isPresent(),
                    "nothing is deleted implicitly on a read; two identical listings must return "
                            + "identical pages");
            assertEquals(Instant.MIN, await(store.inventoryRetainedFrom(TENANT)));

            assertEquals(1L, await(store.purgeExpiredProcessInstances(TENANT)));
            assertEquals(Optional.empty(), await(store.findProcessInstance(terminal)));
            assertEquals(deadline, await(store.inventoryRetainedFrom(TENANT)),
                    "the floor is the earliest deadline actually removed, not the purge instant: "
                            + "advancing to now would claim a gap covering rows that are still here");
            assertTrue(await(store.findProcessInstance(live)).isPresent(),
                    "age is not evidence that work has finished; pruning a non-terminal instance would "
                            + "destroy the row an operator needs in order to see that it is stuck");

            assertEquals(0L, await(store.purgeExpiredProcessInstances(TENANT)));
            assertEquals(deadline, await(store.inventoryRetainedFrom(TENANT)),
                    "a purge that forgot nothing must not move the floor, or a periodic job would "
                            + "report a retention gap on every tick");
        }
    }

    /**
     * The case a single-row purge cannot see, and the one that made the floor wrong.
     *
     * <p>Every retention assertion that purges exactly one row is blind to the difference between the
     * earliest and the latest deadline removed, because for one row they are the same instant. Two rows
     * whose deadlines are further apart than the retention window separate them, and separate them in
     * the direction that matters: with the earliest, the later row is gone while sitting <em>after</em>
     * the published floor, so a caller following the documented rule concludes that a genuinely
     * completed execution never existed.</p>
     */
    @Test
    void aPurgeRemovingSeveralRowsPublishesTheLatestDeadlineItCrossedAndNotTheEarliest() {
        var clock = new MutableClock(EPOCH);
        var early = new ExecutionKey(TENANT, UUID.randomUUID());
        var late = new ExecutionKey(TENANT, UUID.randomUUID());
        try (var store = open("floor-multi.db", clock)) {
            createFailed(store, early, UUID.randomUUID());
            Instant earlyDeadline = only(store, early).retainedUntil().orElseThrow();

            // Twenty days later: far enough apart that the two deadlines cannot overlap, which is
            // exactly the spread a single retention window hides.
            clock.advance(Duration.ofDays(20));
            createFailed(store, late, UUID.randomUUID());
            Instant lateDeadline = only(store, late).retainedUntil().orElseThrow();
            assertTrue(lateDeadline.isAfter(earlyDeadline));

            clock.advance(Duration.ofDays(8));
            assertEquals(2L, await(store.purgeExpiredProcessInstances(TENANT)));

            Instant floor = await(store.inventoryRetainedFrom(TENANT));
            assertEquals(lateDeadline, floor,
                    "the floor must sit at the latest boundary the purge actually crossed; the "
                            + "earliest would leave the later row gone and after the floor");
            assertFalse(lateDeadline.isAfter(floor),
                    "no removed row may have a deadline strictly after the floor -- that is the whole "
                            + "of the guarantee, and the earliest deadline breaks it");
            assertEquals(Optional.empty(), await(store.findProcessInstance(late)));
            assertEquals(Optional.empty(), await(store.findProcessInstance(early)));
        }
    }

    @Test
    void aSurvivingTerminalRowSitsStrictlyAfterTheFloorThePurgePublished() {
        var clock = new MutableClock(EPOCH);
        var expired = new ExecutionKey(TENANT, UUID.randomUUID());
        var survivor = new ExecutionKey(TENANT, UUID.randomUUID());
        try (var store = open("floor-survivor.db", clock)) {
            createFailed(store, expired, UUID.randomUUID());
            Instant expiredDeadline = only(store, expired).retainedUntil().orElseThrow();
            clock.advance(Duration.ofDays(20));
            createFailed(store, survivor, UUID.randomUUID());
            Instant survivorDeadline = only(store, survivor).retainedUntil().orElseThrow();

            // Between the two deadlines: only the first is due.
            clock.advance(Duration.ofDays(1));
            assertEquals(1L, await(store.purgeExpiredProcessInstances(TENANT)));

            Instant floor = await(store.inventoryRetainedFrom(TENANT));
            assertEquals(expiredDeadline, floor);
            assertTrue(survivorDeadline.isAfter(floor),
                    "the surviving row must sit strictly after the floor, which is what makes the "
                            + "floor's claim about it true rather than accidental");
            assertTrue(await(store.findProcessInstance(survivor)).isPresent());
        }
    }

    @Test
    void aPurgedInstanceStaysPurgedAcrossAReopenAndSoDoesTheFloor() {
        Path file = databaseDirectory.resolve("retention-durable.db");
        var clock = new MutableClock(EPOCH);
        var terminal = new ExecutionKey(TENANT, UUID.randomUUID());
        Instant deadline;
        try (var store = open(file, clock)) {
            createFailed(store, terminal, UUID.randomUUID());
            deadline = only(store, terminal).retainedUntil().orElseThrow();
            clock.advance(Duration.ofDays(7).plusSeconds(1));
            assertEquals(1L, await(store.purgeExpiredProcessInstances(TENANT)));
        }
        try (var reopened = open(file, clock)) {
            assertEquals(Optional.empty(), await(reopened.findProcessInstance(terminal)));
            assertEquals(deadline, await(reopened.inventoryRetainedFrom(TENANT)),
                    "a floor derived from the surviving rows would reset to 'nothing was forgotten' "
                            + "the moment the last purged row was gone");
            assertEquals(Instant.MIN, await(reopened.inventoryRetainedFrom(OTHER_TENANT)),
                    "and only the purged tenant's floor moves");
        }
    }

    /**
     * Retention expiry treats a cancellation exactly like an ordinary failure -- same
     * {@code terminalRetention} window, same purge call, same permanent removal -- which is the
     * property this test pins rather than assuming. It also completes the picture the shared
     * conformance suite's reopen assertions establish: right up until the deadline, the full
     * aggregate load still distinguishes the cancelled row from the failed one; the purge then
     * erases both without a trace, and neither resurfaces after a reopen.
     *
     * <p>Note what this does <em>not</em> claim: {@link ProcessInventoryEntry} carries no
     * {@code terminationReason} of its own, so a caller reading the inventory listing rather than
     * loading the full aggregate sees only {@code FAILED} for both rows even before either is
     * purged. That is a real limit of the lightweight inventory projection, not of retention -- see
     * the accompanying report.</p>
     */
    @Test
    void aCancelledInstancePurgesOnTheSameScheduleAsAnOrdinaryFailureAndNeitherResurfacesAfterAReopen() {
        Path file = databaseDirectory.resolve("retention-cancelled.db");
        var clock = new MutableClock(EPOCH);
        var cancelled = new ExecutionKey(TENANT, UUID.randomUUID());
        var failed = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID cancelledTraversal = UUID.randomUUID();
        UUID failedTraversal = UUID.randomUUID();

        try (var store = open(file, clock)) {
            createCancelled(store, cancelled, cancelledTraversal);
            createFailed(store, failed, failedTraversal);

            // Right up until the purge, the full aggregate still tells them apart; the inventory
            // entry, read from the same row, does not -- both are FAILED there.
            StoredProcessInstance loadedCancelled = await(store.load(cancelled));
            StoredProcessInstance loadedFailed = await(store.load(failed));
            assertEquals(ExecutionTerminationReason.CANCELLED, loadedCancelled.state().terminationReason());
            assertNull(loadedFailed.state().terminationReason());
            assertEquals(ProcessInstanceStatus.FAILED, only(store, cancelled).status());
            assertEquals(ProcessInstanceStatus.FAILED, only(store, failed).status());

            clock.advance(Duration.ofDays(7).plusSeconds(1));
            assertEquals(2L, await(store.purgeExpiredProcessInstances(TENANT)),
                    "a cancellation must not be exempted from retention, nor purged early: it is "
                            + "removed on the identical schedule an ordinary failure is");
            assertEquals(Optional.empty(), await(store.findProcessInstance(cancelled)));
            assertEquals(Optional.empty(), await(store.findProcessInstance(failed)));
        }

        try (var reopened = open(file, clock)) {
            assertEquals(Optional.empty(), await(reopened.findProcessInstance(cancelled)),
                    "a purged cancellation must not resurface after a reopen");
            assertEquals(Optional.empty(), await(reopened.findProcessInstance(failed)));
        }
    }

    @Test
    void dispositionSeparatesActiveFromInterruptedAndParkedOutranksTerminal() {
        var clock = new MutableClock(EPOCH);
        var leased = new ExecutionKey(TENANT, UUID.randomUUID());
        var waiting = new ExecutionKey(TENANT, UUID.randomUUID());
        var parked = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID parkedTraversal = UUID.randomUUID();
        UUID parkedInvocation = UUID.randomUUID();
        UUID parkedAttempt = UUID.randomUUID();

        try (var store = open("disposition.db", clock)) {
            createRunning(store, leased, UUID.randomUUID());
            await(store.claim(leased, "worker-1", TTL));
            assertEquals(InventoryDisposition.ACTIVE, only(store, leased).disposition());
            assertEquals(Optional.of("worker-1"), only(store, leased).ownerWorkerId());

            createWaiting(store, waiting, UUID.randomUUID());
            assertEquals(InventoryDisposition.WAITING, only(store, waiting).disposition(),
                    "a waiting instance holds no lease by design; classifying it as interrupted would "
                            + "flood recovery with correctly idle work");

            parkThenFail(store, parked, parkedTraversal, parkedInvocation, parkedAttempt);
            ProcessInventoryEntry parkedEntry = only(store, parked);
            assertEquals(ProcessInstanceStatus.FAILED, parkedEntry.status(),
                    "the authoritative status is still reported unchanged");
            assertEquals(InventoryDisposition.PARKED, parkedEntry.disposition(),
                    "the instance is finished but the effect of unknown outcome is not, and a terminal "
                            + "label would hide the only outstanding operator action -- then let "
                            + "retention delete the sole record of it");
            assertEquals(1, await(store.listTraversals(parked)).get(0).parkedAttemptCount());
            assertEquals(InventoryDisposition.PARKED,
                    await(store.listTraversals(parked)).get(0).disposition());

            // The lease lapses without anything being written. That is the whole point: a disposition
            // stored as a column could not have been corrected here, because no transaction happened.
            clock.advance(TTL.plusSeconds(1));
            assertEquals(InventoryDisposition.INTERRUPTED, only(store, leased).disposition());
            assertEquals(Optional.empty(), only(store, leased).ownerWorkerId());
            assertTrue(only(store, leased).fencingToken() > 0,
                    "the token outlives the lease that issued it; a row with a token and no owner is "
                            + "the normal shape of abandoned work");
        }
    }

    @Test
    void theOwnerFilterMatchesOnlyALiveLease() {
        var clock = new MutableClock(EPOCH);
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        try (var store = open("owner.db", clock)) {
            createRunning(store, key, UUID.randomUUID());
            await(store.claim(key, "worker-1", TTL));

            ProcessInventoryQuery byWorker = ProcessInventoryQuery.builder()
                    .ownedBy("worker-1").limit(10).build();
            assertEquals(1, await(store.listProcessInstances(TENANT, byWorker)).items().size());
            assertEquals(0, await(store.listProcessInstances(TENANT, ProcessInventoryQuery.builder()
                    .ownedBy("worker-2").limit(10).build())).items().size());

            clock.advance(TTL.plusSeconds(1));
            assertEquals(0, await(store.listProcessInstances(TENANT, byWorker)).items().size(),
                    "a lapsed lease names the worker that stopped renewing; answering 'owned by w' "
                            + "with work w has abandoned is the opposite of what a drain is asking");
        }
    }

    @Test
    void lifecycleGenerationCountsAppliedTransitionsAndIsNotTheFencingToken() {
        var clock = new MutableClock(EPOCH);
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        try (var store = open("generation.db", clock)) {
            StoredProcessInstance created = await(store.apply(Fixtures.creationBatch(key, UUID.randomUUID())));
            assertEquals(1L, only(store, key).lifecycleGeneration(),
                    "creation is itself the first transition, into the initial status");
            assertEquals(0L, only(store, key).fencingToken());

            StoredProcessInstance moved = await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(created.revision()))
                    .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                    .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.WAITING))
                    .build()));
            assertEquals(3L, only(store, key).lifecycleGeneration(),
                    "two transitions in one batch are two transitions; comparing the endpoints of the "
                            + "batch would have counted none, because it started and ended non-terminal");

            // Three successive claims move the token three times without moving the lifecycle at all,
            // which is the whole reason the two identities are kept distinct.
            long token = await(store.claim(key, "worker-1", TTL)).fencingToken();
            clock.advance(TTL.plusSeconds(1));
            token = await(store.claim(key, "worker-2", TTL)).fencingToken();
            assertTrue(token > 1);
            assertEquals(3L, only(store, key).lifecycleGeneration());
            assertEquals(token, only(store, key).fencingToken());
            assertEquals(moved.revision(), only(store, key).revision());
        }
    }

    @Test
    void aBadRequestIsRejectedRatherThanAnsweredWithAnEmptyPage() {
        var clock = new MutableClock(EPOCH);
        try (var store = open("rejections.db", clock)) {
            assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                    failureOf(() -> await(store.listProcessInstances(TENANT,
                            ProcessInventoryQuery.outstanding(0)))));
            assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                    failureOf(() -> await(store.listProcessInstances(TENANT,
                            ProcessInventoryQuery.outstanding(store.maxInventoryPageSize() + 1)))),
                    "rejected rather than clamped: a silently shortened page is indistinguishable "
                            + "from a last page, and a caller would stop paginating early");
            assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                    failureOf(() -> await(store.listProcessInstances(TENANT,
                            ProcessInventoryQuery.builder().status(ProcessInstanceStatus.COMPLETED)
                                    .status(ProcessInstanceStatus.FAILED).limit(5).build()))),
                    "a filter naming only terminal statuses while excluding terminal rows can never "
                            + "match, and an empty page would read as 'there is none'");
            assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                    failureOf(() -> await(store.listProcessInstances("  ",
                            ProcessInventoryQuery.outstanding(5)))));
        }
    }

    @Test
    void aMixedStatusFilterExcludingTerminalRowsIsMeaningfulRatherThanContradictory() {
        var clock = new MutableClock(EPOCH);
        var running = new ExecutionKey(TENANT, UUID.randomUUID());
        var failed = new ExecutionKey(TENANT, UUID.randomUUID());
        try (var store = open("mixed.db", clock)) {
            createRunning(store, running, UUID.randomUUID());
            clock.advance(Duration.ofSeconds(1));
            createFailed(store, failed, UUID.randomUUID());

            ProcessInventoryPage page = await(store.listProcessInstances(TENANT,
                    ProcessInventoryQuery.builder()
                            .status(ProcessInstanceStatus.RUNNING)
                            .status(ProcessInstanceStatus.FAILED)
                            .limit(10).build()));
            assertEquals(List.of(running.processInstanceId()),
                    page.items().stream().map(item -> item.key().processInstanceId()).toList(),
                    "the two axes compose as a conjunction; naming FAILED does not smuggle terminal "
                            + "rows past includeTerminal");
        }
    }

    /**
     * ADR 0010 section 12.4: an unrecognised status name -- a row written by a future binary, or bit
     * rot -- must surface as {@link ExecutionStoreFailure.Corrupted} and fail the whole page, rather
     * than being silently dropped from a listing or misread as a well-formed row. {@code Corrupted} is
     * adapter-conditional by construction (no conforming in-memory adapter's own operations can
     * produce it, since its aggregate holds a real {@code ProcessInstanceStatus} enum constant and has
     * no serialization step to corrupt), so this can only be exercised against a durable, serialized
     * adapter, and it belongs here rather than in the shared conformance suite for exactly that reason
     * -- see that suite's own section-12.4 discussion.
     */
    @Test
    void anUnrecognisedStatusNameSurfacesAsCorruptedRatherThanBeingSilentlyDroppedFromTheListing()
            throws Exception {
        Path file = databaseDirectory.resolve("corrupt.db");
        var clock = new MutableClock(EPOCH);
        ExecutionKey key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        try (var store = open(file, clock)) {
            createRunning(store, key, traversalId);
        }

        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE process_instance SET status = 'NOT_A_REAL_STATUS' "
                    + "WHERE process_instance_id = '" + key.processInstanceId() + "'");
        }

        try (var reopened = open(file, clock)) {
            ExecutionStoreFailure listingFailure = failureOf(() -> await(
                    reopened.listProcessInstances(TENANT, ProcessInventoryQuery.everything(10))));
            assertInstanceOf(ExecutionStoreFailure.Corrupted.class, listingFailure,
                    "an unrecognised status name must fail the whole page, not silently drop the row "
                            + "and report a listing that looks cleaner than the database actually is");

            ExecutionStoreFailure lookupFailure = failureOf(() -> await(reopened.findProcessInstance(key)));
            assertInstanceOf(ExecutionStoreFailure.Corrupted.class, lookupFailure,
                    "a direct lookup of the same row must fail the same way, not return empty as if "
                            + "the instance had never existed");
        }
    }

    /**
     * The migration-upgrade path: migration 6 leaves {@code retained_until_*} {@code NULL} for a
     * pre-existing row rather than guessing a deadline the migration cannot know (see
     * {@code SqliteSchemaMigrationTest}). This asserts the read-time half of that design: when the row
     * is also terminal, the store must still resolve a deadline for it -- against
     * {@code updatedAt + terminalRetention()} -- rather than reporting the row as unbounded or leaving
     * {@link ProcessInventoryEntry#retainedUntil()} empty.
     *
     * <p>The read and the purge resolve that deadline through one method, {@code retentionDueAt}, so
     * they cannot answer differently about the same row. A read that returned the raw column would
     * report no deadline for a row the purge is about to remove on schedule — and a caller comparing
     * the two would have no way to tell which was right.</p>
     */
    @Test
    void aTerminalRowWithNoStoredRetainedUntilResolvesAgainstUpdatedAtPlusTerminalRetention() throws Exception {
        Path file = databaseDirectory.resolve("legacy-terminal.db");
        var clock = new MutableClock(EPOCH);
        ExecutionKey key = new ExecutionKey(TENANT, UUID.randomUUID());

        try (var store = open(file, clock)) {
            // Opening once is enough to run every migration and establish the real schema on this file.
            assertTrue(store.supports(StoreCapability.PROCESS_INVENTORY));
        }

        Instant updatedAt = EPOCH.plusSeconds(120);
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.prepareStatement("INSERT INTO process_instance (tenant_id, "
                     + "process_instance_id, status, graph_version_pin, revision, fencing_token, "
                     + "updated_at_epoch_second, updated_at_nano, created_at_epoch_second, "
                     + "created_at_nano, lifecycle_generation) VALUES (?, ?, 'FAILED', 'graph-v1', 1, 0, "
                     + "?, 0, ?, 0, 1)")) {
            // retained_until_epoch_second / retained_until_nano are left unspecified -- NULL -- which
            // is exactly the shape migration 6 leaves a pre-existing terminal row in.
            statement.setString(1, TENANT);
            statement.setString(2, key.processInstanceId().toString());
            statement.setLong(3, updatedAt.getEpochSecond());
            statement.setLong(4, updatedAt.getEpochSecond());
            statement.executeUpdate();
        }

        try (var reopened = open(file, clock)) {
            ProcessInventoryEntry entry = only(reopened, key);
            assertEquals(ProcessInstanceStatus.FAILED, entry.status());
            assertEquals(updatedAt.plus(reopened.terminalRetention()), entry.retainedUntil().orElseThrow(),
                    "a terminal row with no stored retained_until must resolve against "
                            + "updatedAt + terminalRetention(), not report as unbounded or absent");
        }
    }

    /**
     * Counts, against an instance whose cardinalities are all greater than one.
     *
     * <p>Every other inventory test in this suite — and every one in the shared contract — builds an
     * instance with a single traversal holding a single invocation and a single attempt. On that shape
     * {@code traversalCount}, {@code invocationCount} and {@code parkedAttemptCount} are
     * indistinguishable from a boolean, and the correlated subqueries and the join that produce them in
     * SQL are indistinguishable from a query that multiplies its rows: one times one is one, whichever
     * way the join is wrong. Two traversals, one of them holding two invocations with a parked attempt
     * apiece, is the smallest shape on which a wrong join and a right one give different answers.</p>
     */
    @Test
    void countsAreCountsAndNotFlags() {
        var clock = new MutableClock(EPOCH);
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID busy = UUID.randomUUID();
        UUID quiet = UUID.randomUUID();
        UUID firstInvocation = UUID.randomUUID();
        UUID secondInvocation = UUID.randomUUID();

        try (var store = open("counts.db", clock)) {
            StoredProcessInstance created = await(store.apply(Fixtures.creationBatch(key, busy)));
            StoredProcessInstance grown = await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(created.revision()))
                    .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                    .apply(new ExecutionTransition.TraversalTransitioned(busy, TraversalStatus.RUNNING))
                    .apply(new ExecutionTransition.TraversalAdded(new ai.ravenroot.api.application.Traversal(
                            quiet, "second-ingress", TraversalStatus.ACCEPTED, java.util.Map.of())))
                    .apply(new ExecutionTransition.InvocationAdded(busy,
                            new NodeInvocation(firstInvocation, "mail.send", null,
                                    NodeInvocationStatus.SCHEDULED)))
                    .apply(new ExecutionTransition.InvocationAdded(busy,
                            new NodeInvocation(secondInvocation, "mail.receipt", null,
                                    NodeInvocationStatus.SCHEDULED)))
                    .build()));

            StoredProcessInstance withAttempts = grown;
            for (UUID invocation : List.of(firstInvocation, secondInvocation)) {
                UUID attempt = UUID.randomUUID();
                withAttempts = await(store.apply(ExecutionBatch.to(key)
                        .expecting(RevisionExpectation.exactly(withAttempts.revision()))
                        .apply(new ExecutionTransition.InvocationTransitioned(busy, invocation,
                                NodeInvocationStatus.RUNNING))
                        .apply(new ExecutionTransition.AttemptAdded(busy, invocation,
                                new NodeAttempt(attempt, 1, NodeAttemptStatus.SCHEDULED)))
                        .build()));
                withAttempts = await(store.apply(ExecutionBatch.to(key)
                        .expecting(RevisionExpectation.exactly(withAttempts.revision()))
                        .apply(new ExecutionTransition.AttemptTransitioned(busy, invocation, attempt,
                                NodeAttemptStatus.RUNNING))
                        .build()));
                withAttempts = await(store.apply(ExecutionBatch.to(key)
                        .expecting(RevisionExpectation.exactly(withAttempts.revision()))
                        .apply(new ExecutionTransition.AttemptParked(busy, invocation, attempt,
                                "dispatched with unknown outcome"))
                        .build()));
            }

            assertEquals(2, only(store, key).traversalCount(),
                    "two traversals must count as two; a subquery that joined through invocations "
                            + "would report four here and one on every other test in this file");

            List<TraversalInventoryEntry> traversals = await(store.listTraversals(key));
            assertEquals(2, traversals.size());
            TraversalInventoryEntry busyRow = traversals.stream()
                    .filter(row -> row.traversalId().equals(busy)).findFirst().orElseThrow();
            TraversalInventoryEntry quietRow = traversals.stream()
                    .filter(row -> row.traversalId().equals(quiet)).findFirst().orElseThrow();

            assertEquals(2, busyRow.invocationCount());
            assertEquals(2, busyRow.parkedAttemptCount());
            assertEquals(InventoryDisposition.PARKED, busyRow.disposition());

            assertEquals(0, quietRow.invocationCount(),
                    "the counts are per traversal, so an empty one stays empty rather than inheriting "
                            + "its sibling's rows");
            assertEquals(0, quietRow.parkedAttemptCount());
            assertEquals(InventoryDisposition.INTERRUPTED, quietRow.disposition(),
                    "a traversal with no parked attempt of its own is not parked because its sibling is");
        }
    }

    /**
     * Retention and durable handlers meet at a foreign key, and neither feature's own tests can see
     * it: the inventory's retention assertions were written before handlers existed, and the handler
     * assertions were written before anything deleted a process instance.
     *
     * <p>{@code purgeExpiredProcessInstances} deletes from {@code process_instance}. With foreign keys
     * on -- which this store enables -- that now cascades into {@code execution_handler}, because a
     * handler row references the instance and is declared to cascade with it and with nothing
     * narrower. This asserts the combination is coherent rather than assuming it: the handlers of a
     * purged terminal instance go with it, and a later lookup reports them absent rather than
     * returning a row whose process no longer exists.</p>
     */
    @Test
    void purgingATerminalInstanceTakesItsDurableHandlersWithIt() {
        var clock = new MutableClock(EPOCH);
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID handlerId = UUID.randomUUID();

        try (var store = open("purge-with-handlers.db", clock)) {
            StoredProcessInstance created = await(store.apply(Fixtures.creationBatch(key, traversalId)));
            StoredProcessInstance waiting = await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(created.revision()))
                    .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                    .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                    .apply(new ExecutionTransition.InvocationAdded(traversalId,
                            new NodeInvocation(invocationId, "await-approval", null,
                                    NodeInvocationStatus.SCHEDULED)))
                    .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.WAITING))
                    .registerHandler(new HandlerRegistration(handlerId, "approval", traversalId,
                            invocationId, "invoice-42", "dedup-1",
                            new HandlerPayloadSchema("application/vnd.ravenroot.test-approval",
                                    "approval/v1", 1024),
                            HandlerAuthorization.ofRoles("APPROVER")))
                    .build()));
            assertTrue(await(store.loadHandler(key, handlerId)).isPresent());
            assertEquals(1, await(store.handlers(key)).size());

            await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(waiting.revision()))
                    .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.FAILED))
                    .build()));

            clock.advance(Duration.ofDays(7).plusSeconds(1));
            assertEquals(1L, await(store.purgeExpiredProcessInstances(TENANT)));

            assertEquals(Optional.empty(), await(store.findProcessInstance(key)));
            assertEquals(Optional.empty(), await(store.loadHandler(key, handlerId)),
                    "a handler cascades with the instance it belongs to; a row surviving here would "
                            + "reference a process that no longer exists");
            assertEquals(Optional.empty(),
                    await(store.findHandler(TENANT, "approval", "invoice-42")),
                    "and the correlation key it held is free again, rather than permanently taken by "
                            + "a handler nobody can reach");
        }
    }

    // ---------------------------------------------------------------- fixtures

    private ProcessInventoryEntry only(SqliteExecutionStore store, ExecutionKey key) {
        return await(store.findProcessInstance(key)).orElseThrow();
    }

    private void createRunning(SqliteExecutionStore store, ExecutionKey key, UUID traversalId) {
        StoredProcessInstance created = await(store.apply(Fixtures.creationBatch(key, traversalId)));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .build()));
    }

    private void createWaiting(SqliteExecutionStore store, ExecutionKey key, UUID traversalId) {
        createRunning(store, key, traversalId);
        StoredProcessInstance running = await(store.load(key));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(running.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.WAITING))
                .build()));
    }

    private void createFailed(SqliteExecutionStore store, ExecutionKey key, UUID traversalId) {
        StoredProcessInstance created = await(store.apply(Fixtures.creationBatch(key, traversalId)));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.FAILED))
                .build()));
    }

    /** The same shape as {@link #createFailed}, qualified as a cancellation rather than a fault. */
    private void createCancelled(SqliteExecutionStore store, ExecutionKey key, UUID traversalId) {
        StoredProcessInstance created = await(store.apply(Fixtures.creationBatch(key, traversalId)));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.FAILED,
                        ExecutionTerminationReason.CANCELLED))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.FAILED,
                        ExecutionTerminationReason.CANCELLED))
                .build()));
    }

    private void parkThenFail(SqliteExecutionStore store, ExecutionKey key, UUID traversalId,
                              UUID invocationId, UUID attemptId) {
        StoredProcessInstance created = await(store.apply(Fixtures.creationBatch(key, traversalId)));
        StoredProcessInstance scheduled = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .apply(new ExecutionTransition.InvocationAdded(traversalId,
                        new NodeInvocation(invocationId, "mail.send", null, NodeInvocationStatus.SCHEDULED)))
                .apply(new ExecutionTransition.InvocationTransitioned(traversalId, invocationId,
                        NodeInvocationStatus.RUNNING))
                .apply(new ExecutionTransition.AttemptAdded(traversalId, invocationId,
                        new NodeAttempt(attemptId, 1, NodeAttemptStatus.SCHEDULED)))
                .build()));
        StoredProcessInstance running = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(scheduled.revision()))
                .apply(new ExecutionTransition.AttemptTransitioned(traversalId, invocationId, attemptId,
                        NodeAttemptStatus.RUNNING))
                .build()));
        StoredProcessInstance parked = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(running.revision()))
                .apply(new ExecutionTransition.AttemptParked(traversalId, invocationId, attemptId,
                        "dispatched with unknown outcome"))
                .build()));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(parked.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.FAILED))
                .build()));
    }

    private SqliteExecutionStore open(String name, MutableClock clock) {
        return open(databaseDirectory.resolve(name), clock);
    }

    private SqliteExecutionStore open(Path file, MutableClock clock) {
        return new SqliteExecutionStore(file, clock);
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private static ExecutionStoreFailure failureOf(Runnable operation) {
        CompletionException thrown = assertThrows(CompletionException.class, operation::run);
        ExecutionStoreException failure = ExecutionStoreException.unwrap(thrown);
        assertNotNull(failure, "adapters must not leak non-store exceptions: " + thrown);
        return failure.failure();
    }
}
