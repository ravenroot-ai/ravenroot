package ai.ravenroot.cli;

import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.application.ExecutionSubmission;
import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditEnvelope;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.programming.ArtifactLifecycleAuditSink;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.audit.FileAuditTrail;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.persistence.sqlite.SqliteStoreLocation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The drill. Not a document describing a procedure -- a record of one
 * actually run, with writes genuinely in flight while the backup is taken, against the real
 * {@code require}-then-{@code startAuthorized} path
 * ({@code AuthorizedRavenrootApplication#startGraphMl}), not a simulation of it.
 *
 * <h2>The falsifier, queried first</h2>
 * <p>Per the coherence argument in
 * {@code docs/runbooks/plat-05-backup-restore-and-disaster-recovery.md} Sec.2: because {@code require}
 * (the audit write) runs before {@code startAuthorized} (the store write) inside every {@code
 * startGraphMl} call, the only possible post-restore skew is the audit trail describing an execution
 * the restored store does not contain -- never the reverse. {@link
 * #theFalsifierNeverFiresAndTheConfirmingSkewDoesWhileWritesAreInFlight()} enumerates, for every
 * write the drill issued, whether its store record and its audit record independently survived into
 * the restored backup, and checks the refuting condition (store present, audit absent) <strong>before</strong>
 * asserting anything that would confirm the argument. If that check ever finds an instance, this test
 * fails loudly and Sec.2 of the runbook is wrong, not merely incomplete -- see this class's own
 * {@code main} history for the mutation that proves the check can actually fire.</p>
 */
class BackupRestoreDrillTest {
    private static final String TENANT = "drill-tenant";
    private static final String BEHAVIOR = "drill-behavior";
    private static final int HELD_INDEX = -1;
    // Measured, not assumed, across two earlier approaches, neither reliable: natural timing alone
    // showed the confirming skew in only 1 of 5 runs with 1 writer and 5 of 6 with 6 writers; a
    // fixed per-write delay on every apply() call (tried next, sized from the same VACUUM INTO
    // measurements) still showed zero skew in 5 of 8 runs, because identical delays on identically
    // started threads fall into lockstep -- either all writers are mid-delay when the backup lands
    // or none are, which is a coin flip, not a guarantee (see this file's own history for both).
    // HOLD_ON_APPLY below replaces timing-based luck with an actual synchronization point: one
    // designated write (HELD_INDEX) is paused, via a latch, at the exact instant after its audit
    // record is durably written and before its store row is created, and the backup is only taken
    // once that pause is confirmed entered. That write's audit-ahead-of-store skew is therefore
    // certain by construction on every run, not probable under favorable timing. The concurrent
    // writer pool below still runs the whole time, so the drill also keeps demonstrating genuine
    // unrelated traffic in flight -- it is just no longer what the confirming assertion depends on.
    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="drill" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="work"><data key="kind">BEHAVIOR</data><data key="behavior">drill-behavior</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="work"><data key="outcome">continue</data></edge>
                <edge id="e2" source="work" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    @TempDir
    Path root;

    @Test
    void theFalsifierNeverFiresAndTheConfirmingSkewDoesWhileWritesAreInFlight() throws Exception {
        Path liveAuditDir = root.resolve("live/audit");
        Path liveStoreDir = root.resolve("live/store");
        Path backupDir = root.resolve("backup");
        Path restoredAuditDir = root.resolve("restored/audit");
        Path restoredStoreDir = root.resolve("restored/store");

        var liveConfiguration = new BackupRestoreConfiguration(liveAuditDir,
                SqliteStoreLocation.underDirectory(liveStoreDir));
        var behaviors = new BehaviorRegistry().register(BEHAVIOR,
                message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())));

        Map<Integer, UUID> minted = new ConcurrentHashMap<>();
        AtomicBoolean keepWriting = new AtomicBoolean(true);
        List<Throwable> writerFailures = new ArrayList<>();

        try (var engine = new PekkoExecutionEngine("plat05-drill");
             var trail = new FileAuditTrail(liveAuditDir, Clock.systemUTC(), Duration.ofHours(24))) {
            var store = new SqliteExecutionStore(liveConfiguration.executionStoreLocation(), Clock.systemUTC());
            var enteredHeldApply = new CountDownLatch(1);
            var releaseHeldApply = new CountDownLatch(1);
            var releaseAuditRaceWriter = new CountDownLatch(1);
            var enteredAuditRaceApply = new CountDownLatch(1);
            var releaseAuditRaceApply = new CountDownLatch(1);
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), behaviors,
                    new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                    ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(),
                    holdingStore(store));
            // Equivalent to ravenroot-server's AuditTrailAuthorizationSink (ravenroot-cli does not,
            // and should not, depend on ravenroot-server): same field mapping -- event.requestId()
            // becomes the audit record's correlationId, which is the link this drill's falsifier
            // check depends on -- verified against that class's real source before writing this.
            var authorized = new AuthorizedRavenrootApplication(application,
                    new DefaultAuthorizationService(event -> trail.append(new AuditEnvelope(
                            AuditEnvelope.CURRENT_VERSION, UUID.randomUUID(), event.tenantId(), event.subject(),
                            AuditCategory.DECISION, "authorize:" + event.action(), event.resourceType(),
                            event.resourceId(), event.allowed() ? AuditOutcome.ALLOWED : AuditOutcome.DENIED,
                            event.reason(), event.requestId(), event.occurredAt(),
                            OpaquePayload.empty("text/plain")))),
                    noopArtifactAudit(), true);

            // 8 concurrent writers so the drill demonstrates genuine concurrent overlap (several
            // unrelated requests actually in flight at once, not one request looped serially) --
            // this pool is real traffic surrounding the backup, not the mechanism the confirming
            // assertion depends on; that guarantee now comes from the held write below.
            var nextIndex = new java.util.concurrent.atomic.AtomicInteger();
            List<Thread> writers = new ArrayList<>();
            for (int w = 0; w < 8; w++) {
                var writer = new Thread(() -> {
                    while (keepWriting.get()) {
                        int index = nextIndex.getAndIncrement();
                        try {
                            var context = requestContext(index);
                            var submission = authorized.startGraphMl(context,
                                    new ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8)), Map.of());
                            minted.put(index, submission.processInstanceId());
                        } catch (RuntimeException failure) {
                            synchronized (writerFailures) {
                                writerFailures.add(failure);
                            }
                        }
                    }
                }, "drill-writer-" + w);
                writers.add(writer);
                writer.start();
            }

            // Let the pool establish a real rate before the backup starts, so the backup lands on an
            // already-moving target, not an empty store. Each write is a real Pekko-backed traversal
            // (spawn, message, terminate across three nodes), not a cheap map insert.
            Thread.sleep(1_500);

            // The held write: its own thread, marked so HOLD_ON_APPLY pauses it -- after its audit
            // record is durably written, before its store row is created -- and held there until the
            // backup has actually run. This is what makes "audit captured, store not yet captured"
            // certain for at least this one write, rather than hoped for.
            var heldFailure = new AtomicReference<Throwable>();
            var heldWriter = new Thread(() -> {
                HOLD_ON_APPLY.set(new HeldApply(enteredHeldApply, releaseHeldApply));
                try {
                    var context = requestContext(HELD_INDEX);
                    var submission = authorized.startGraphMl(context,
                            new ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8)), Map.of());
                    minted.put(HELD_INDEX, submission.processInstanceId());
                } catch (RuntimeException failure) {
                    heldFailure.set(failure);
                    enteredHeldApply.countDown();
                } finally {
                    HOLD_ON_APPLY.remove();
                }
            }, "drill-held-writer");
            heldWriter.start();

            boolean heldWriteInPosition = enteredHeldApply.await(10, TimeUnit.SECONDS);
            assertTrue(heldWriteInPosition, "the held write never reached its store apply() call within "
                    + "10s -- cannot demonstrate a guaranteed in-flight backup");
            assertTrue(heldFailure.get() == null, () -> "the held write failed before reaching apply(): "
                    + heldFailure.get());

            // This second held write is released only after the backup has copied the source log.
            // Its real authorization path advances the source head before the observer returns, which
            // makes the former log-then-head capture order deterministically reproduce the invalid
            // stale-log/fresh-head generation reported by CI.
            var auditRaceFailure = new AtomicReference<Throwable>();
            var auditRaceWriter = new Thread(() -> {
                try {
                    if (!releaseAuditRaceWriter.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("audit copy never reached its log capture point");
                    }
                    HOLD_ON_APPLY.set(new HeldApply(enteredAuditRaceApply, releaseAuditRaceApply));
                    var context = requestContext(-2);
                    var submission = authorized.startGraphMl(context,
                            new ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8)), Map.of());
                    minted.put(-2, submission.processInstanceId());
                } catch (Throwable failure) {
                    auditRaceFailure.set(failure);
                    enteredAuditRaceApply.countDown();
                } finally {
                    HOLD_ON_APPLY.remove();
                }
            }, "drill-audit-race-writer");
            auditRaceWriter.start();

            var out = capturing();
            var err = capturing();
            var command = new BackupRestoreCommand(out.stream(), err.stream());
            long backupStartNanos = System.nanoTime();
            int backupExit;
            boolean backupSucceeded = false;
            try {
                backupExit = command.backup(liveConfiguration, backupDir, fileName -> {
                    if (!fileName.endsWith(".audit.jsonl")) {
                        return;
                    }
                    releaseAuditRaceWriter.countDown();
                    boolean reached;
                    try {
                        reached = enteredAuditRaceApply.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException("interrupted while awaiting audit race writer", interrupted);
                    }
                    if (!reached) {
                        throw new java.io.IOException("audit race writer did not reach its held apply");
                    }
                    if (auditRaceFailure.get() != null) {
                        throw new java.io.IOException("audit race writer failed", auditRaceFailure.get());
                    }
                });
                backupSucceeded = backupExit == 0;
            } finally {
                releaseAuditRaceWriter.countDown();
                releaseHeldApply.countDown();
                releaseAuditRaceApply.countDown();
                heldWriter.join(TimeUnit.SECONDS.toMillis(10));
                auditRaceWriter.join(TimeUnit.SECONDS.toMillis(10));
                if (!backupSucceeded) {
                    keepWriting.set(false);
                    for (Thread writer : writers) {
                        writer.join(TimeUnit.SECONDS.toMillis(10));
                    }
                }
            }
            long backupElapsedMillis = (System.nanoTime() - backupStartNanos) / 1_000_000;
            assertEquals(0, backupExit, () -> "backup failed: " + err.text());

            // The original held write was released only after the backup captured its audit record
            // without its store row. The race writer instead overlaps the backup by advancing the
            // live head after the copied log, which is the deterministic audit-snapshot interleaving.
            assertTrue(heldFailure.get() == null, () -> "the held write failed after release: " + heldFailure.get());
            assertTrue(auditRaceFailure.get() == null,
                    () -> "the audit race writer failed after release: " + auditRaceFailure.get());
            assertTrue(minted.containsKey(HELD_INDEX), "the held write never completed and minted no "
                    + "process instance id -- its own presence check below would be meaningless");

            // Keep the pool writing past the backup's own completion too, so the drill covers "in
            // flight when the snapshot was taken" rather than only "in flight when the command started".
            Thread.sleep(1_000);
            keepWriting.set(false);
            for (Thread writer : writers) {
                writer.join(TimeUnit.SECONDS.toMillis(10));
            }
            assertTrue(writerFailures.isEmpty(), () -> "writer thread(s) saw failures: " + writerFailures);

            int totalWrites = minted.size();
            System.out.println("[PLAT-05 drill] writes issued: " + totalWrites + " (including held writes "
                    + "deliberately paused mid-flight across the backup), backup took " + backupElapsedMillis
                    + "ms, writer pool ran concurrently with it: true");
            assertTrue(totalWrites >= 20, () -> "drill produced too few writes (" + totalWrites
                    + ") to say anything about concurrency; this is an evidence failure, not a logic one");

            store.close();

            var restoredConfiguration = new BackupRestoreConfiguration(restoredAuditDir,
                    SqliteStoreLocation.underDirectory(restoredStoreDir));
            assertEquals(0, command.restore(restoredConfiguration, backupDir), () -> "restore failed: " + err.text());

            try (var restoredTrail = new FileAuditTrail(restoredAuditDir, Clock.systemUTC(), Duration.ofHours(24));
                 var restoredStore = new SqliteExecutionStore(restoredConfiguration.executionStoreLocation(),
                         Clock.systemUTC())) {
                var restoredRecords = restoredTrail.read(TENANT, 0, totalWrites + 10);
                var auditedRequestIds = restoredRecords.stream()
                        .map(record -> record.envelope().correlationId())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());

                int storePresent = 0;
                int auditPresent = 0;
                int auditAheadOfStore = 0;
                List<Integer> falsifierHits = new ArrayList<>();

                for (var entry : minted.entrySet()) {
                    int index = entry.getKey();
                    UUID processInstanceId = entry.getValue();
                    boolean hasAudit = auditedRequestIds.contains("drill-" + index);
                    boolean hasStore = isPresent(restoredStore, new ExecutionKey(TENANT, processInstanceId));
                    if (hasStore) {
                        storePresent++;
                    }
                    if (hasAudit) {
                        auditPresent++;
                    }
                    // THE FALSIFIER, CHECKED BEFORE ANYTHING ELSE: store present, audit absent.
                    if (hasStore && !hasAudit) {
                        falsifierHits.add(index);
                    }
                    if (hasAudit && !hasStore) {
                        auditAheadOfStore++;
                    }
                }

                System.out.println("[PLAT-05 drill] of " + totalWrites + " writes issued: " + storePresent
                        + " present in the restored store, " + auditPresent + " present in the restored "
                        + "audit trail, " + auditAheadOfStore + " showed the expected audit-ahead-of-store "
                        + "skew (audited but not yet in the store snapshot), " + falsifierHits.size()
                        + " showed the refuting condition (store present, audit absent).");

                if (!falsifierHits.isEmpty()) {
                    fail("FALSIFIER FIRED for write indices " + falsifierHits + ": the restored store "
                            + "contains an execution with no matching audit record. The coherence "
                            + "argument in docs/runbooks/plat-05-backup-restore-and-disaster-recovery.md "
                            + "Sec.2 is wrong, not merely incomplete, and must be rewritten from this "
                            + "evidence rather than caveated.");
                }

                // The confirming direction, guaranteed by construction: the held write (HELD_INDEX)
                // was paused, by the enteredHeldApply/releaseHeldApply latch pair, at the exact
                // instant after its audit record was durably written and before its store row was
                // created, and released only after the backup returned. Its skew is certain, not
                // probabilistic, and this is checked on that specific write, not on the aggregate.
                boolean heldWriteAuditedButNotStored = auditedRequestIds.contains("drill-" + HELD_INDEX)
                        && !isPresent(restoredStore, new ExecutionKey(TENANT, minted.get(HELD_INDEX)));
                assertTrue(heldWriteAuditedButNotStored, "the held write (deliberately paused mid-flight "
                        + "across the backup) did not show audit-ahead-of-store skew in the restored "
                        + "backup -- either its audit record or the pause mechanism itself is not doing "
                        + "what this drill assumes it does");

                // The aggregate direction, over the whole run: not required to be positive by itself
                // (the held write alone already proves the property), but reported because a positive
                // count here is corroborating evidence that the concurrent pool's ordinary traffic
                // showed the same skew, not just the one write this drill deliberately arranged.
                System.out.println("[PLAT-05 drill] aggregate audit-ahead-of-store skew across all "
                        + totalWrites + " writes (including the held one): " + auditAheadOfStore);

                var verification = restoredTrail.verify(TENANT);
                assertTrue(verification.intact(), () -> "the audit chain must still verify after a "
                        + "restore taken with writes in flight -- anomalies: " + verification.anomalies());
                System.out.println("[PLAT-05 drill] chain verify after restore: intact, checked through "
                        + "sequence " + verification.checkedThroughSequence());
            }
        }
    }

    /** Set on a writer thread that wants its next {@code apply()} call paused; see {@link #holdingStore}. */
    private static final ThreadLocal<HeldApply> HOLD_ON_APPLY = new ThreadLocal<>();

    /**
     * Wraps {@code delegate} so that the <em>first</em> {@link ExecutionStore#apply} call made by a
     * thread with {@link #HOLD_ON_APPLY} set blocks on {@code release} after signalling {@code
     * entered} -- a real synchronization point, not a timing guess. {@code
     * DefaultRavenrootApplication.startGraphMl} calls {@code await(executionStore.apply(...))}
     * synchronously on the caller's own thread (confirmed by reading {@code
     * DefaultRavenrootApplication.java:357}, the {@code ProcessCreated} write, directly), and that
     * call happens after {@code AuthorizedRavenrootApplication}'s {@code require} has already written
     * the audit record for the same request -- so pausing exactly here, on exactly the marked
     * thread's first {@code apply()}, freezes that one write in precisely the "audited, not yet
     * stored" state a backup taken during the pause is certain to capture. Threads without the
     * ThreadLocal set -- the ordinary writer pool -- pass straight through, undelayed.
     */
    private static ExecutionStore holdingStore(ExecutionStore delegate) {
        return (ExecutionStore) Proxy.newProxyInstance(ExecutionStore.class.getClassLoader(),
                new Class<?>[] {ExecutionStore.class}, (proxy, method, args) -> {
                    HeldApply hold = HOLD_ON_APPLY.get();
                    if (hold != null && method.getName().equals("apply")
                            && method.getParameterCount() == 1) {
                        HOLD_ON_APPLY.remove(); // only this thread's first apply() call is held
                        hold.entered().countDown();
                        if (!hold.release().await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("held write was never released -- the "
                                    + "backup did not complete within the wait window");
                        }
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException wrapped) {
                        throw wrapped.getCause();
                    }
                });
    }

    private record HeldApply(CountDownLatch entered, CountDownLatch release) {
    }

    private static boolean isPresent(SqliteExecutionStore store, ExecutionKey key) {
        try {
            store.load(key).toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
            return true;
        } catch (Exception notPresent) {
            return false;
        }
    }

    private static RequestContext requestContext(int index) {
        // RequestContext's field order is (requestId, subject, principalType, issuer, tenantId,
        // roles, scopes) -- subject before tenantId, confirmed by reading the record directly
        // after the first run of this drill put "drill" (the subject) where TENANT ("drill-tenant")
        // belonged, which a base64-decoded audit filename in the backup directory caught immediately.
        return new RequestContext("drill-" + index, "drill", PrincipalType.USER, "urn:ravenroot:test", TENANT,
                Set.of(Role.PLATFORM_ADMIN),
                java.util.Arrays.stream(AuthorizationAction.values()).filter(AuthorizationAction::available)
                        .map(AuthorizationAction::requiredScope).collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    private static ArtifactLifecycleAuditSink noopArtifactAudit() {
        return event -> { };
    }

    private static Capturing capturing() {
        return new Capturing();
    }

    private static final class Capturing {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final PrintStream stream = new PrintStream(buffer, true, StandardCharsets.UTF_8);

        PrintStream stream() {
            return stream;
        }

        String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
