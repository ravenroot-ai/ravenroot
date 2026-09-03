package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.DeploymentState;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.JournalCursor;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERS-INGRESS: {@code TrustedIngress#offerDurably} composed directly against
 * {@link ExecutionStore} -- no private persistence path. Uses {@link SqliteExecutionStore} rather than
 * {@link InMemoryExecutionStore} wherever durability itself is under test, because
 * {@code InMemoryExecutionStore} does not declare {@code StoreCapability#DURABLE} and must not: a
 * "crash and restart" scenario against it would prove nothing about what survives a real process
 * death.
 *
 * <h2>What this class does not test, and why</h2>
 * <p>Failover and fencing across replicas are not exercised here because this boundary does not claim
 * them -- {@link IngressReceipt}'s own Javadoc states plainly that {@code DurablyCommitted} is
 * single-pod, and cross-replica ownership arbitration belongs to PERS-08 and PLAT-03, neither of
 * which this single-process guarantee depends on. Poison-event quarantine is
 * not tested either: {@link ExecutionStore#recordInboxDelivery} has no retry-count concept to build one
 * on, and none is added here.</p>
 *
 * <p><b>Crash is proved at one transition, by a clean close, and that is a stated boundary rather than
 * an inferred one.</b> {@code duplicateAfterSimulatedRestartIsRecognizedWithoutReprocessing} simulates a
 * crash by closing the first {@link SqliteExecutionStore} in an orderly way and reopening the same file
 * -- it does not hard-kill the process at the write itself. This class therefore proves the
 * <em>wiring</em> above the store: commit-before-dispatch ordering, refusal isolation, and the
 * ambiguous/duplicate/durable split -- and rests the store write's own survival of a true hard kill on
 * {@code SqliteExecutionStore}'s own WAL-durability suite ({@code SqliteKillAtCommitBoundaryTest} and
 * neighbours in {@code ravenroot-persistence-sqlite}), which forks a real process and kills it at the
 * commit boundary. That layering -- this class proves the boundary's logic, the store's own suite
 * proves the boundary's disk write -- is deliberate, not an oversight this class's own scope note
 * should leave silent.</p>
 */
class DefaultGraphDeploymentDurableIngressTest {
    private static final SecurityContext IDENTITY = new SecurityContext("durable-ingress-request",
            "durable-ingress-tenant", "durable-ingress-subject", PrincipalType.WORKLOAD,
            "urn:ravenroot:durable-ingress");
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="wiring" edgedefault="directed">
                <node id="error"><data key="node-kind">ERROR</data></node>
                <node id="start"><data key="node-kind">START</data></node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="start-end" source="start" target="end">
                  <data key="edge-outcome">continue</data>
                </edge>
              </graph>
            </graphml>
            """;

    /** One node whose handler never completes on its own, to hold an ingress permit indefinitely. */
    private static final String BLOCKING_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="node-behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="blocking-wiring" edgedefault="directed">
                <node id="error"><data key="node-kind">ERROR</data></node>
                <node id="start"><data key="node-kind">START</data></node>
                <node id="blocker">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">blocker</data>
                </node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="e1" source="start" target="blocker"><data key="edge-outcome">continue</data></edge>
                <edge id="e2" source="blocker" target="end"><data key="edge-outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    @TempDir
    Path databaseDirectory;

    // ------------------------------------------------------------------------- construction safety

    @Test
    void constructionFailsFastWhenTheStoreDoesNotDeclareDurable() {
        try (var engine = new JoinTestEngine(); var store = new InMemoryExecutionStore()) {
            var failure = assertThrows(IllegalArgumentException.class, () -> new DefaultGraphDeployment(
                    DeploymentId.of("no-durable"), engine, BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                    new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(), graphBytes(),
                    DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY, store,
                    DefaultGraphDeployment.DEFAULT_INBOX_RETENTION));
            assertTrue(failure.getMessage().contains("DURABLE"),
                    () -> "must name the missing capability: " + failure.getMessage());
        }
    }

    // --------------------------------------------------------------------------- volatile fallback

    @Test
    void withoutAStoreOfferDurablyDegradesToVolatileCustodyAndStillDispatches() throws Exception {
        try (var engine = new JoinTestEngine()) {
            var monitor = new ExecutionMonitor();
            var completed = new CountDownLatch(1);
            try (var subscription = monitor.subscribe(event -> {
                if (event.type() == ExecutionEventType.EXECUTION_COMPLETED) {
                    completed.countDown();
                }
            })) {
                var deployment = new DefaultGraphDeployment(DeploymentId.of("volatile"), engine,
                        BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()), monitor,
                        ExecutionIdentitySource.randomUuids(), graphBytes(),
                        DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY);
                deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

                var receipt = deployment.ingress().offerDurably(IDENTITY, IngressTarget.start(), "payload",
                        "poller-1", "key-1");

                assertInstanceOf(IngressReceipt.VolatileCustody.class, receipt);
                assertFalse(receipt.acknowledgeable(), "volatile custody must never license an external ack");
                assertTrue(completed.await(10, TimeUnit.SECONDS), "the traversal must still run");
                deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    // ------------------------------------------------------------------------------- durable commit

    /**
     * Issue 154's write path, at the deployment-hosted admission point: {@code offerDurably} opens
     * this deployment's own domain and dispatches under this traversal's own identity, so both
     * halves of ADR 0021 D5's pair are knowable at admission and must be recorded on the durable
     * inventory row exactly as {@code GraphRunner.execute} is stamped with them -- the deployment's
     * own id as {@code deploymentId} and the traversal's own id as {@code workloadId} -- alongside
     * the caller's correlation identity. This is the counterpart of
     * {@code DefaultRavenrootApplicationExecutionStoreTest
     * .recordsTransientOriginWithAbsentDeploymentAndWorkloadButPresentCorrelationId}: a
     * deployment-hosted execution and a transient one are both discoverable through the identical
     * {@link ai.ravenroot.api.persistence.ExecutionKey} identity contract, without conflating either
     * with a deployment or a graph version (acceptance criterion 2).
     */
    @Test
    void deploymentHostedOfferRecordsItsOwnDeploymentAndTraversalAsOrigin() throws Exception {
        Path file = databaseDirectory.resolve("origin.db");
        var deploymentId = DeploymentId.of("origin-" + java.util.UUID.randomUUID());
        try (var engine = new JoinTestEngine(); var store = new SqliteExecutionStore(file, systemClock())) {
            var completions = countCompletions();
            var deployment = deployment(deploymentId, engine, store, completions.monitor());
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            var receipt = deployment.ingress().offerDurably(IDENTITY, IngressTarget.start(), "payload",
                    "poller-1", "key-origin");
            assertInstanceOf(IngressReceipt.DurablyCommitted.class, receipt);
            assertTrue(completions.latch().await(10, TimeUnit.SECONDS));

            var page = store.listProcessInstances(IDENTITY.tenantId(),
                            ai.ravenroot.api.persistence.ProcessInventoryQuery.everything(10))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(1, page.items().size(), "exactly one instance was admitted by this offer");
            var entry = page.items().get(0);
            assertEquals(deploymentId.value(), entry.deploymentId().orElseThrow(),
                    "a deployment-hosted execution must record its own deployment id");
            var traversals = store.listTraversals(entry.key()).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(1, traversals.size(), "exactly one traversal was dispatched by this offer");
            assertEquals(traversals.get(0).traversalId().toString(), entry.workloadId().orElseThrow(),
                    "ADR 0021 D5's own pair: workloadId is this traversal's own id");
            assertEquals(IDENTITY.requestId(), entry.correlationId().orElseThrow(),
                    "the caller's own ingress correlation identity must still be recorded");

            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void firstOfferIsDurablyCommittedAndDispatchesExactlyOnce() throws Exception {
        Path file = databaseDirectory.resolve("commit.db");
        try (var engine = new JoinTestEngine(); var store = new SqliteExecutionStore(file, systemClock())) {
            var completions = countCompletions();
            var deployment = deployment(engine, store, completions.monitor());
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            var receipt = deployment.ingress().offerDurably(IDENTITY, IngressTarget.start(), "payload",
                    "poller-1", "key-A");

            assertInstanceOf(IngressReceipt.DurablyCommitted.class, receipt);
            assertTrue(receipt.acknowledgeable());
            assertTrue(completions.latch().await(10, TimeUnit.SECONDS), "the first offer must dispatch");
            assertEquals(1, completions.count().get());
            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * A source offers, the commit lands durably, and the process "dies" before
     * anything else observes it -- simulated by never dispatching a second event against the first
     * {@link DefaultGraphDeployment} and instead opening a second one against the same SQLite file, the
     * same durable backing a real restart would reopen. Redelivery of the same key must be recognised
     * as {@link IngressReceipt.Duplicate}, and the original traversal must not run a second time.
     */
    @Test
    void duplicateAfterSimulatedRestartIsRecognizedWithoutReprocessing() throws Exception {
        Path file = databaseDirectory.resolve("restart.db");
        Clock clock = systemClock();
        // A real restart reconstructs the same logical deployment under the same id -- that identity,
        // not object identity, is what the destination/eventId key is derived from, so the fixture
        // must hold it fixed across both instances rather than letting the shared deployment() helper
        // mint a fresh random one for each.
        var deploymentId = DeploymentId.of("restart-" + java.util.UUID.randomUUID());
        try (var firstEngine = new JoinTestEngine()) {
            var firstCompletions = countCompletions();
            try (var firstStore = new SqliteExecutionStore(file, clock)) {
                var first = deployment(deploymentId, firstEngine, firstStore, firstCompletions.monitor());
                first.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

                var firstReceipt = first.ingress().offerDurably(IDENTITY, IngressTarget.start(), "payload",
                        "poller-1", "key-restart");
                assertInstanceOf(IngressReceipt.DurablyCommitted.class, firstReceipt);
                assertTrue(firstCompletions.latch().await(10, TimeUnit.SECONDS));
                // No stop(), no ack -- the "crash" is exactly this: nothing more happens on this instance.
            }
        }

        try (var secondEngine = new JoinTestEngine(); var secondStore = new SqliteExecutionStore(file, clock)) {
            var secondCompletions = countCompletions();
            var second = deployment(deploymentId, secondEngine, secondStore, secondCompletions.monitor());
            second.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            var secondReceipt = second.ingress().offerDurably(IDENTITY, IngressTarget.start(), "payload",
                    "poller-1", "key-restart");

            assertInstanceOf(IngressReceipt.Duplicate.class, secondReceipt);
            assertTrue(secondReceipt.acknowledgeable(), "a duplicate is still safe to ack -- it was already committed");
            // Give any wrongly-dispatched traversal time to complete before asserting its absence.
            Thread.sleep(200);
            assertEquals(0, secondCompletions.count().get(), "redelivery of an already-committed key must not "
                    + "start a second traversal");
            second.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    // ------------------------------------------------------------------------------------ refusal

    @Test
    void aRefusalNeverReachesTheStore() throws Exception {
        Path file = databaseDirectory.resolve("refused.db");
        try (var engine = new JoinTestEngine(); var store = new SqliteExecutionStore(file, systemClock())) {
            var deployment = deployment(engine, store, new ExecutionMonitor());
            // Never started: COLD refuses exactly as offer() does, before any store call is attempted.
            var receipt = deployment.ingress().offerDurably(IDENTITY, IngressTarget.start(), "payload",
                    "poller-1", "key-refused");

            assertInstanceOf(IngressReceipt.Refused.class, receipt);
            assertFalse(receipt.acknowledgeable());
            assertEquals(0L, store.inboxRecordCount(IDENTITY.tenantId()).toCompletableFuture().get(10, TimeUnit.SECONDS),
                    "a refusal must not have written anything durable");
        }
    }

    // ---------------------------------------------------------------------------------- checkpoint

    @Test
    void checkpointStartsAtZeroAndAdvancesOnlyWithTheCorrectExpectation() throws Exception {
        Path file = databaseDirectory.resolve("checkpoint.db");
        try (var engine = new JoinTestEngine(); var store = new SqliteExecutionStore(file, systemClock())) {
            var deployment = deployment(engine, store, new ExecutionMonitor());
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            JournalCursor start = deployment.ingress().sourceCheckpoint(IDENTITY, "poller-1")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(0L, start.deliveredThrough());

            JournalCursor advanced = deployment.ingress().advanceSourceCheckpoint(start, 5L)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(5L, advanced.deliveredThrough());

            // Advancing again against the now-stale "start" cursor must fail rather than silently
            // overwrite the position a concurrent-looking writer already moved.
            var staleAdvance = deployment.ingress().advanceSourceCheckpoint(start, 10L).toCompletableFuture();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> staleAdvance.get(10, TimeUnit.SECONDS));

            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void checkpointWithoutAStoreIsUnsupportedRatherThanFabricated() throws Exception {
        try (var engine = new JoinTestEngine()) {
            var deployment = new DefaultGraphDeployment(DeploymentId.of("no-store-checkpoint"), engine,
                    BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()), new ExecutionMonitor(),
                    ExecutionIdentitySource.randomUuids(), graphBytes(),
                    DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY);
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            var checkpoint = deployment.ingress().sourceCheckpoint(IDENTITY, "poller-1").toCompletableFuture();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> checkpoint.get(10, TimeUnit.SECONDS));
            assertInstanceOf(UnsupportedOperationException.class, failure.getCause());

            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    // ---------------------------------------------------------------------------------- ambiguous

    /**
     * Covers {@link IngressReceipt.Ambiguous} and rejects a mutation that leaks the buffer permit and
     * dispatches the traversal anyway. All three consequences of the type's contract are asserted
     * directly: not acknowledgeable, no dispatch, and -- the one a leaked permit would
     * hide -- released, proved by a second call on the same capacity-1, still-failing store reaching
     * the store again rather than being refused as buffer-full.
     */
    @Test
    void ambiguousReceiptReleasesItsPermitDispatchesNothingAndIsReconcilableByReoffering() throws Exception {
        Path file = databaseDirectory.resolve("ambiguous.db");
        try (var engine = new JoinTestEngine(); var realStore = new SqliteExecutionStore(file, systemClock())) {
            var shouldFail = new AtomicBoolean(true);
            var store = new FailingInboxStore(realStore, shouldFail::get);
            var completions = countCompletions();
            var deployment = new DefaultGraphDeployment(DeploymentId.of("ambiguous-" + java.util.UUID.randomUUID()),
                    engine, BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()), completions.monitor(),
                    ExecutionIdentitySource.randomUuids(), graphBytes(), 1, store,
                    DefaultGraphDeployment.DEFAULT_INBOX_RETENTION);
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            var first = deployment.ingress().offerDurably(IDENTITY, IngressTarget.start(), "payload",
                    "poller-1", "key-ambiguous-1");
            assertInstanceOf(IngressReceipt.Ambiguous.class, first);
            assertFalse(first.acknowledgeable(), "an ambiguous outcome must never license an external ack");

            // Permit released: on a capacity-1 buffer, a second call against the still-failing store
            // must reach the store again (and also come back Ambiguous) rather than being refused as
            // buffer-full -- which is only possible if the first call gave back what it acquired.
            var second = deployment.ingress().offerDurably(IDENTITY, IngressTarget.start(), "payload",
                    "poller-1", "key-ambiguous-2");
            assertInstanceOf(IngressReceipt.Ambiguous.class, second,
                    "a buffer-full receipt here would mean the first Ambiguous leaked its permit");

            // No dispatch: give a wrongly-started traversal time to complete, then assert none did.
            Thread.sleep(200);
            assertEquals(0, completions.count().get(), "an ambiguous outcome must not have dispatched a traversal");

            // Reconciliable: the failed call never actually wrote anything, so once the store recovers,
            // re-offering the SAME key that was ambiguous commits fresh rather than reading as a
            // duplicate of a write that never happened.
            shouldFail.set(false);
            var reconciled = deployment.ingress().offerDurably(IDENTITY, IngressTarget.start(), "payload",
                    "poller-1", "key-ambiguous-1");
            assertInstanceOf(IngressReceipt.DurablyCommitted.class, reconciled,
                    "reconciliation: the ambiguous attempt never actually recorded, so a retry commits fresh");

            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    // --------------------------------------------------------------------------------- buffer full

    /**
     * The other half of the gap: a mutation that made buffer-full return {@code DurablyCommitted} --
     * an acknowledgement license with nothing behind it, reachable under ordinary load -- also left the
     * suite green, because nothing exercised buffer-full on the durable path specifically. A full
     * buffer is refused before the store is ever touched, asserted by an unchanged
     * {@code inboxRecordCount} across the refusal, the same evidence
     * {@code aRefusalNeverReachesTheStore} uses for a not-ready refusal.
     */
    @Test
    void bufferFullOnTheDurablePathIsRefusedWithoutTouchingTheStore() throws Exception {
        Path file = databaseDirectory.resolve("buffer-full.db");
        var gate = new CompletableFuture<NodeResult>();
        var registry = BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults())
                .register("blocker", message -> gate);
        try (var engine = new JoinTestEngine(); var store = new SqliteExecutionStore(file, systemClock())) {
            var deployment = new DefaultGraphDeployment(
                    DeploymentId.of("buffer-full-" + java.util.UUID.randomUUID()), engine, registry,
                    new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(),
                    BLOCKING_GRAPH.getBytes(StandardCharsets.UTF_8), 1, store,
                    DefaultGraphDeployment.DEFAULT_INBOX_RETENTION);
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            var holding = deployment.ingress().offerDurably(IDENTITY, IngressTarget.start(), "payload",
                    "poller-1", "key-holding");
            assertInstanceOf(IngressReceipt.DurablyCommitted.class, holding,
                    "the first offer must commit and hold the deployment's only permit in the blocker node");

            long recordedBefore = store.inboxRecordCount(IDENTITY.tenantId()).toCompletableFuture().get(10, TimeUnit.SECONDS);

            var refused = deployment.ingress().offerDurably(IDENTITY, IngressTarget.start(), "payload",
                    "poller-1", "key-refused-buffer-full");

            assertInstanceOf(IngressReceipt.Refused.class, refused);
            assertFalse(refused.acknowledgeable());
            long recordedAfter = store.inboxRecordCount(IDENTITY.tenantId()).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(recordedBefore, recordedAfter, "a buffer-full refusal must never reach the store");

            gate.complete(NodeResult.continueWith("released"));
            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    // ------------------------------------------------------------------------- eventId invariance

    /**
     * {@code offerDurably}'s ingress-key derivation used to separate {@code tenantId},
     * {@code destination} and {@code idempotentKey} with two raw NUL bytes written directly into
     * character literals instead of the {@code '\0'} escape -- semantically identical, but a raw NUL
     * byte anywhere in the file makes {@code grep} classify the whole file as binary and skip
     * it silently, which is what let a call-site search return zero hits in a file that holds two of
     * them (see the class list in the deployment loop above). Other control
     * bytes were measured not to reproduce this -- see
     * {@code docs/qa/grep-and-git-binary-file-detection.md}.
     *
     * <p>The separator's escaped representation is byte-for-byte identical to the raw NUL character,
     * so the {@link UUID} this derivation produces must also be identical. This test proves that
     * rather than asserting it by inspection: {@code expectedEventId} below was captured by running
     * this exact scenario with raw NUL bytes in the source and is pinned
     * here as a literal -- if the escape sequence ever diverges from the byte it replaces, or someone
     * later "tidies" the separator to something else, this is the test that reds.</p>
     */
    @Test
    void ingressEventIdDerivationIsUnchangedByTheNulByteFix() throws Exception {
        Path file = databaseDirectory.resolve("event-id-invariance.db");
        var capturedEventIds = new CopyOnWriteArrayList<UUID>();
        try (var engine = new JoinTestEngine(); var realStore = new SqliteExecutionStore(file, systemClock())) {
            var store = new EventIdCapturingStore(realStore, capturedEventIds::add);
            var deployment = new DefaultGraphDeployment(DeploymentId.of("event-id-fixed"), engine,
                    BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()), new ExecutionMonitor(),
                    ExecutionIdentitySource.randomUuids(), graphBytes(),
                    DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY, store,
                    DefaultGraphDeployment.DEFAULT_INBOX_RETENTION);
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            var receipt = deployment.ingress().offerDurably(IDENTITY, IngressTarget.start(), "payload",
                    "poller-1", "key-nul-fix");
            assertInstanceOf(IngressReceipt.DurablyCommitted.class, receipt);

            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        assertEquals(1, capturedEventIds.size(), "exactly one event was offered");
        // Captured from the source form with raw NUL separators for this exact
        // tenantId="durable-ingress-tenant", destination="event-id-fixed/poller-1",
        // idempotentKey="key-nul-fix" -- see the method Javadoc for what pinning this literal proves.
        UUID expectedEventId = UUID.fromString("d5cc7fa2-ed63-3e5f-9e5c-b9822b1ba87c");
        assertEquals(expectedEventId, capturedEventIds.get(0),
                "replacing the raw NUL separator bytes with '\\0' escapes must not change the "
                        + "derived eventId -- they are the same character, so this UUID must be identical "
                        + "to the one the raw-NUL source form produced for the same inputs");
    }

    // -------------------------------------------------------------------------------------- fixtures

    private static DefaultGraphDeployment deployment(ai.ravenroot.api.execution.ExecutionEngine engine,
            ExecutionStore store, ExecutionMonitor monitor) {
        return deployment(DeploymentId.of("durable-" + java.util.UUID.randomUUID()), engine, store, monitor);
    }

    private static DefaultGraphDeployment deployment(DeploymentId deploymentId,
            ai.ravenroot.api.execution.ExecutionEngine engine, ExecutionStore store, ExecutionMonitor monitor) {
        return new DefaultGraphDeployment(deploymentId, engine,
                BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()), monitor,
                ExecutionIdentitySource.randomUuids(), graphBytes(),
                DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY, store,
                DefaultGraphDeployment.DEFAULT_INBOX_RETENTION);
    }

    private static Completions countCompletions() {
        var monitor = new ExecutionMonitor();
        var count = new AtomicInteger();
        var latch = new CountDownLatch(1);
        monitor.subscribe(event -> {
            if (event.type() == ExecutionEventType.EXECUTION_COMPLETED) {
                count.incrementAndGet();
                latch.countDown();
            }
        });
        return new Completions(monitor, count, latch);
    }

    private record Completions(ExecutionMonitor monitor, AtomicInteger count, CountDownLatch latch) {
    }

    private static Clock systemClock() {
        return Clock.fixed(EPOCH, ZoneOffset.UTC);
    }

    private static byte[] graphBytes() {
        return GRAPH.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Delegates every {@link ExecutionStore} operation to a real store except
     * {@link #recordInboxDelivery}, which fails on demand -- modelling a store call that never
     * actually wrote anything (a network failure before the write reached disk, for instance), which is
     * what makes the reconciliation assertion in
     * {@code ambiguousReceiptReleasesItsPermitDispatchesNothingAndIsReconcilableByReoffering} correct: a
     * retry after the failure is a fresh commit, not a duplicate of a write that never happened.
     */
    private static final class FailingInboxStore implements ExecutionStore {
        // Durable inventory (issue 154): pure delegation, so this double differs from the store it
        // wraps only in the one operation it exists to perturb.

        @Override
        public int maxInventoryPageSize() {
            return delegate.maxInventoryPageSize();
        }

        @Override
        public Duration terminalRetention() {
            return delegate.terminalRetention();
        }

        @Override
        public java.util.concurrent.CompletionStage<ai.ravenroot.api.persistence.ProcessInventoryPage>
                listProcessInstances(String tenantId,
                        ai.ravenroot.api.persistence.ProcessInventoryQuery query) {
            return delegate.listProcessInstances(tenantId, query);
        }

        @Override
        public java.util.concurrent.CompletionStage<
                java.util.Optional<ai.ravenroot.api.persistence.ProcessInventoryEntry>>
                findProcessInstance(ai.ravenroot.api.persistence.ExecutionKey key) {
            return delegate.findProcessInstance(key);
        }

        @Override
        public java.util.concurrent.CompletionStage<
                java.util.List<ai.ravenroot.api.persistence.TraversalInventoryEntry>>
                listTraversals(ai.ravenroot.api.persistence.ExecutionKey key) {
            return delegate.listTraversals(key);
        }

        @Override
        public java.util.concurrent.CompletionStage<java.time.Instant> inventoryRetainedFrom(String tenantId) {
            return delegate.inventoryRetainedFrom(tenantId);
        }

        @Override
        public java.util.concurrent.CompletionStage<Long> purgeExpiredProcessInstances(String tenantId) {
            return delegate.purgeExpiredProcessInstances(tenantId);
        }

        private final ExecutionStore delegate;
        private final java.util.function.BooleanSupplier shouldFail;

        FailingInboxStore(ExecutionStore delegate, java.util.function.BooleanSupplier shouldFail) {
            this.delegate = delegate;
            this.shouldFail = shouldFail;
        }

        @Override
        public java.util.concurrent.CompletionStage<Boolean> recordInboxDelivery(String tenantId, String consumerId,
                java.util.UUID eventId, java.time.Duration retention) {
            if (shouldFail.getAsBoolean()) {
                return CompletableFuture.failedFuture(new java.io.UncheckedIOException(
                        "simulated store failure", new java.io.IOException("no connection")));
            }
            return delegate.recordInboxDelivery(tenantId, consumerId, eventId, retention);
        }

        @Override
        public java.util.Set<ai.ravenroot.api.persistence.StoreCapability> capabilities() {
            return delegate.capabilities();
        }

        @Override
        public Duration maxLeaseTtl() {
            return delegate.maxLeaseTtl();
        }

        @Override
        public int maxPayloadBytes() {
            return delegate.maxPayloadBytes();
        }

        @Override
        public java.util.concurrent.CompletionStage<ai.ravenroot.api.persistence.StoredProcessInstance> apply(
                ai.ravenroot.api.persistence.ExecutionBatch batch) {
            return delegate.apply(batch);
        }

        @Override
        public java.util.concurrent.CompletionStage<ai.ravenroot.api.persistence.StoredProcessInstance> load(
                ai.ravenroot.api.persistence.ExecutionKey key) {
            return delegate.load(key);
        }

        @Override
        public java.util.concurrent.CompletionStage<ai.ravenroot.api.persistence.LeaseHandle> claim(
                ai.ravenroot.api.persistence.ExecutionKey key, String workerId, Duration ttl) {
            return delegate.claim(key, workerId, ttl);
        }

        @Override
        public java.util.concurrent.CompletionStage<ai.ravenroot.api.persistence.LeaseHandle> renew(
                ai.ravenroot.api.persistence.LeaseHandle lease, Duration ttl) {
            return delegate.renew(lease, ttl);
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> release(ai.ravenroot.api.persistence.LeaseHandle lease) {
            return delegate.release(lease);
        }

        @Override
        public java.util.concurrent.CompletionStage<List<ai.ravenroot.api.persistence.LeaseHandle>> leases(
                String tenantId) {
            return delegate.leases(tenantId);
        }

        @Override
        public java.util.concurrent.CompletionStage<List<ai.ravenroot.api.persistence.PendingWork>> claimPendingWork(
                String tenantId, String workerId, int limit, Duration leaseTtl) {
            return delegate.claimPendingWork(tenantId, workerId, limit, leaseTtl);
        }

        @Override
        public java.util.concurrent.CompletionStage<List<ai.ravenroot.api.persistence.PendingWork.TimerDue>> claimDueTimers(
                String tenantId, String workerId, int limit, Duration leaseTtl) {
            return delegate.claimDueTimers(tenantId, workerId, limit, leaseTtl);
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> ack(ai.ravenroot.api.persistence.PendingWork item) {
            return delegate.ack(item);
        }

        @Override
        public Duration maxClockSkew() {
            return delegate.maxClockSkew();
        }

        @Override
        public java.util.concurrent.CompletionStage<Instant> forgottenBefore(String tenantId) {
            return delegate.forgottenBefore(tenantId);
        }

        @Override
        public java.util.concurrent.CompletionStage<java.util.Optional<ai.ravenroot.api.persistence.IdempotencyRecord>> lookupIdempotency(
                String tenantId, String key, Instant keyIssuedAt) {
            return delegate.lookupIdempotency(tenantId, key, keyIssuedAt);
        }

        @Override
        public java.util.concurrent.CompletionStage<Long> idempotencyRecordCount(String tenantId) {
            return delegate.idempotencyRecordCount(tenantId);
        }

        @Override
        public java.util.concurrent.CompletionStage<Long> purgeExpiredIdempotencyRecords(String tenantId) {
            return delegate.purgeExpiredIdempotencyRecords(tenantId);
        }

        @Override
        public Duration journalRetention() {
            return delegate.journalRetention();
        }

        @Override
        public java.util.concurrent.CompletionStage<List<ai.ravenroot.api.persistence.JournalRecord>> readJournal(
                String tenantId, long afterOffset, int limit) {
            return delegate.readJournal(tenantId, afterOffset, limit);
        }

        @Override
        public java.util.concurrent.CompletionStage<Long> journalRetainedFrom(String tenantId) {
            return delegate.journalRetainedFrom(tenantId);
        }

        @Override
        public java.util.concurrent.CompletionStage<JournalCursor> outboxCursor(String tenantId, String destination) {
            return delegate.outboxCursor(tenantId, destination);
        }

        @Override
        public java.util.concurrent.CompletionStage<JournalCursor> advanceOutboxCursor(JournalCursor expected,
                long throughOffset) {
            return delegate.advanceOutboxCursor(expected, throughOffset);
        }

        @Override
        public java.util.concurrent.CompletionStage<Long> inboxRecordCount(String tenantId) {
            return delegate.inboxRecordCount(tenantId);
        }

        @Override
        public java.util.concurrent.CompletionStage<Long> compactJournal(String tenantId) {
            return delegate.compactJournal(tenantId);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /**
     * Delegates every {@link ExecutionStore} operation to a real store, capturing the {@code eventId}
     * argument {@link #recordInboxDelivery} is called with -- the derived value {@code offerDurably}
     * never otherwise exposes past its own private scope, and the value
     * {@code ingressEventIdDerivationIsUnchangedByTheNulByteFix} exists to pin.
     */
    private static final class EventIdCapturingStore implements ExecutionStore {
        // Durable inventory (issue 154): pure delegation, so this double differs from the store it
        // wraps only in the one operation it exists to perturb.

        @Override
        public int maxInventoryPageSize() {
            return delegate.maxInventoryPageSize();
        }

        @Override
        public Duration terminalRetention() {
            return delegate.terminalRetention();
        }

        @Override
        public java.util.concurrent.CompletionStage<ai.ravenroot.api.persistence.ProcessInventoryPage>
                listProcessInstances(String tenantId,
                        ai.ravenroot.api.persistence.ProcessInventoryQuery query) {
            return delegate.listProcessInstances(tenantId, query);
        }

        @Override
        public java.util.concurrent.CompletionStage<
                java.util.Optional<ai.ravenroot.api.persistence.ProcessInventoryEntry>>
                findProcessInstance(ai.ravenroot.api.persistence.ExecutionKey key) {
            return delegate.findProcessInstance(key);
        }

        @Override
        public java.util.concurrent.CompletionStage<
                java.util.List<ai.ravenroot.api.persistence.TraversalInventoryEntry>>
                listTraversals(ai.ravenroot.api.persistence.ExecutionKey key) {
            return delegate.listTraversals(key);
        }

        @Override
        public java.util.concurrent.CompletionStage<java.time.Instant> inventoryRetainedFrom(String tenantId) {
            return delegate.inventoryRetainedFrom(tenantId);
        }

        @Override
        public java.util.concurrent.CompletionStage<Long> purgeExpiredProcessInstances(String tenantId) {
            return delegate.purgeExpiredProcessInstances(tenantId);
        }

        private final ExecutionStore delegate;
        private final java.util.function.Consumer<UUID> onEventId;

        EventIdCapturingStore(ExecutionStore delegate, java.util.function.Consumer<UUID> onEventId) {
            this.delegate = delegate;
            this.onEventId = onEventId;
        }

        @Override
        public java.util.concurrent.CompletionStage<Boolean> recordInboxDelivery(String tenantId, String consumerId,
                UUID eventId, Duration retention) {
            onEventId.accept(eventId);
            return delegate.recordInboxDelivery(tenantId, consumerId, eventId, retention);
        }

        @Override
        public java.util.Set<ai.ravenroot.api.persistence.StoreCapability> capabilities() {
            return delegate.capabilities();
        }

        @Override
        public Duration maxLeaseTtl() {
            return delegate.maxLeaseTtl();
        }

        @Override
        public int maxPayloadBytes() {
            return delegate.maxPayloadBytes();
        }

        @Override
        public java.util.concurrent.CompletionStage<ai.ravenroot.api.persistence.StoredProcessInstance> apply(
                ai.ravenroot.api.persistence.ExecutionBatch batch) {
            return delegate.apply(batch);
        }

        @Override
        public java.util.concurrent.CompletionStage<ai.ravenroot.api.persistence.StoredProcessInstance> load(
                ai.ravenroot.api.persistence.ExecutionKey key) {
            return delegate.load(key);
        }

        @Override
        public java.util.concurrent.CompletionStage<ai.ravenroot.api.persistence.LeaseHandle> claim(
                ai.ravenroot.api.persistence.ExecutionKey key, String workerId, Duration ttl) {
            return delegate.claim(key, workerId, ttl);
        }

        @Override
        public java.util.concurrent.CompletionStage<ai.ravenroot.api.persistence.LeaseHandle> renew(
                ai.ravenroot.api.persistence.LeaseHandle lease, Duration ttl) {
            return delegate.renew(lease, ttl);
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> release(ai.ravenroot.api.persistence.LeaseHandle lease) {
            return delegate.release(lease);
        }

        @Override
        public java.util.concurrent.CompletionStage<List<ai.ravenroot.api.persistence.LeaseHandle>> leases(
                String tenantId) {
            return delegate.leases(tenantId);
        }

        @Override
        public java.util.concurrent.CompletionStage<List<ai.ravenroot.api.persistence.PendingWork>> claimPendingWork(
                String tenantId, String workerId, int limit, Duration leaseTtl) {
            return delegate.claimPendingWork(tenantId, workerId, limit, leaseTtl);
        }

        @Override
        public java.util.concurrent.CompletionStage<List<ai.ravenroot.api.persistence.PendingWork.TimerDue>> claimDueTimers(
                String tenantId, String workerId, int limit, Duration leaseTtl) {
            return delegate.claimDueTimers(tenantId, workerId, limit, leaseTtl);
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> ack(ai.ravenroot.api.persistence.PendingWork item) {
            return delegate.ack(item);
        }

        @Override
        public Duration maxClockSkew() {
            return delegate.maxClockSkew();
        }

        @Override
        public java.util.concurrent.CompletionStage<Instant> forgottenBefore(String tenantId) {
            return delegate.forgottenBefore(tenantId);
        }

        @Override
        public java.util.concurrent.CompletionStage<java.util.Optional<ai.ravenroot.api.persistence.IdempotencyRecord>> lookupIdempotency(
                String tenantId, String key, Instant keyIssuedAt) {
            return delegate.lookupIdempotency(tenantId, key, keyIssuedAt);
        }

        @Override
        public java.util.concurrent.CompletionStage<Long> idempotencyRecordCount(String tenantId) {
            return delegate.idempotencyRecordCount(tenantId);
        }

        @Override
        public java.util.concurrent.CompletionStage<Long> purgeExpiredIdempotencyRecords(String tenantId) {
            return delegate.purgeExpiredIdempotencyRecords(tenantId);
        }

        @Override
        public Duration journalRetention() {
            return delegate.journalRetention();
        }

        @Override
        public java.util.concurrent.CompletionStage<List<ai.ravenroot.api.persistence.JournalRecord>> readJournal(
                String tenantId, long afterOffset, int limit) {
            return delegate.readJournal(tenantId, afterOffset, limit);
        }

        @Override
        public java.util.concurrent.CompletionStage<Long> journalRetainedFrom(String tenantId) {
            return delegate.journalRetainedFrom(tenantId);
        }

        @Override
        public java.util.concurrent.CompletionStage<JournalCursor> outboxCursor(String tenantId, String destination) {
            return delegate.outboxCursor(tenantId, destination);
        }

        @Override
        public java.util.concurrent.CompletionStage<JournalCursor> advanceOutboxCursor(JournalCursor expected,
                long throughOffset) {
            return delegate.advanceOutboxCursor(expected, throughOffset);
        }

        @Override
        public java.util.concurrent.CompletionStage<Long> inboxRecordCount(String tenantId) {
            return delegate.inboxRecordCount(tenantId);
        }

        @Override
        public java.util.concurrent.CompletionStage<Long> compactJournal(String tenantId) {
            return delegate.compactJournal(tenantId);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
