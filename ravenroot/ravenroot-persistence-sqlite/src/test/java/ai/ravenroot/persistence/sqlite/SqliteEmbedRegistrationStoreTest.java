package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.embed.EmbedGraphProjection;
import ai.ravenroot.api.embed.EmbedProjectionBudget;
import ai.ravenroot.api.embed.EmbedProvisionOutcome;
import ai.ravenroot.api.embed.EmbedRegistrationAggregate;
import ai.ravenroot.api.embed.EmbedRegistrationResolution;
import ai.ravenroot.api.embed.EmbedRegistrationState;
import ai.ravenroot.api.embed.EmbedProjectionResolution;
import ai.ravenroot.api.embed.EmbedRevokeCommand;
import ai.ravenroot.api.embed.EmbedRevokeOutcome;
import ai.ravenroot.api.embed.EmbedTheme;
import ai.ravenroot.api.embed.EmbedProvisionCommand;
import ai.ravenroot.api.embed.EmbedViewerSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The durable adapter must agree with the in-memory reference, and must additionally survive a
 * restart and two writers.
 *
 * <p>Where an assertion here mirrors one in {@code EmbedRegistrationAuthorityTest}, that is
 * deliberate: the shared {@code EmbedRegistrationRules} means the two adapters cannot refuse
 * different commands, but only these tests prove that the durable one applies them at all.</p>
 */
class SqliteEmbedRegistrationStoreTest {

    private static final Clock CLOCK = Clock.fixed(EmbedRegistrationFixtures.AT, ZoneOffset.UTC);

    @TempDir
    Path directory;

    @Test
    void deploymentSourceSurvivesReopenWithoutBecomingASnapshot() {
        var command = EmbedProvisionCommand.deployment(EmbedRegistrationFixtures.REGISTRATION, 0,
                "issuer", "subject", EmbedRegistrationFixtures.TENANT, "https://parent.example",
                Optional.empty(), "orders-live");
        try (var store = open()) {
            assertInstanceOf(EmbedProvisionOutcome.Provisioned.class, store.provision(command));
        }
        try (var reopened = open()) {
            var loaded = reopened.currentForOperator(EmbedRegistrationFixtures.TENANT,
                    EmbedRegistrationFixtures.REGISTRATION).orElseThrow();
            assertEquals(new EmbedViewerSource.Deployment("orders-live"), loaded.source());
            assertInstanceOf(EmbedProjectionResolution.Unavailable.class,
                    reopened.resolveProjection(loaded, EmbedProjectionBudget.DEFAULTS),
                    "a live source must never fall through to the persisted snapshot placeholder");
        }
    }

    @Test
    void populatedV1DatabaseUpgradesAtomicallyAndConcurrentOpenersObserveOneSnapshotMigration()
            throws Exception {
        var expected = EmbedRegistrationFixtures.command(0, "sha256:v1", "start", "next")
                .aggregateAt(CLOCK.instant());
        createPopulatedV1Database(expected);

        var barrier = new CyclicBarrier(2);
        List<Callable<EmbedRegistrationAggregate>> openers = List.of(
                () -> openMigratedAggregate(barrier),
                () -> openMigratedAggregate(barrier));
        List<Future<EmbedRegistrationAggregate>> migrated;
        try (var pool = Executors.newFixedThreadPool(2)) {
            migrated = pool.invokeAll(openers);
        }
        for (Future<EmbedRegistrationAggregate> opened : migrated) {
            assertEquals(expected, opened.get(),
                    "both openers must see the one complete v2 migration, never a half-upgraded row");
            assertInstanceOf(EmbedViewerSource.Snapshot.class, opened.get().source());
        }

        Path database = directory.resolve(SqliteEmbedRegistrationStore.FILE_NAME);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA user_version")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }
            try (var rows = statement.executeQuery("SELECT source_version, source_kind, "
                    + "source_deployment_id FROM embed_registration")) {
                assertTrue(rows.next());
                assertEquals("1", rows.getString("source_version"));
                assertEquals("snapshot", rows.getString("source_kind"));
                assertNull(rows.getString("source_deployment_id"));
                assertFalse(rows.next());
            }
        }

        // A later retry/reopen is a no-op at schema v2 and still reconstructs the exact legacy
        // aggregate and projection, including layout and edge ordering.
        try (var reopened = open()) {
            var loaded = reopened.currentForOperator(EmbedRegistrationFixtures.TENANT,
                    EmbedRegistrationFixtures.REGISTRATION).orElseThrow();
            assertEquals(expected, loaded);
            var projection = assertInstanceOf(EmbedProjectionResolution.Available.class,
                    reopened.resolveProjection(loaded, EmbedProjectionBudget.DEFAULTS)).projection();
            assertEquals(expected.projection(), projection);
        }
    }

    @Test
    void aProvisionedRegistrationSurvivesAReopenWithEveryFieldIntact() {
        EmbedRegistrationAggregate written;
        try (var store = open()) {
            written = provisioned(store, EmbedRegistrationFixtures.command(0, "sha256:a", "start", "next"));
        }
        try (var reopened = open()) {
            var loaded = assertInstanceOf(EmbedRegistrationResolution.Available.class,
                    reopened.resolveCurrent(EmbedRegistrationFixtures.workload(),
                            EmbedRegistrationFixtures.REGISTRATION)).aggregate();
            assertEquals(written, loaded, "the whole aggregate round-trips, not merely its identity");
            assertEquals(Optional.of(EmbedTheme.DARK), loaded.sessionGrant().themeOverride());
            assertEquals(List.of("start", "next"), loaded.projection().nodes().stream()
                    .map(EmbedGraphProjection.Node::id).toList());
            assertEquals(new EmbedGraphProjection.Layout(1.5, -2.25, 30, 40),
                    loaded.projection().nodes().getFirst().layout());
            assertTrue(reopened.isCurrent(written),
                    "an aggregate captured before the restart is still the current revision");
        }
    }

    @Test
    void arevocationSurvivesARestartAndTheRegistrationIsNeverReanimated() {
        long revision;
        try (var store = open()) {
            var captured = provisioned(store, EmbedRegistrationFixtures.command(0, "sha256:a", "start"));
            revision = assertInstanceOf(EmbedRevokeOutcome.Revoked.class,
                    store.revoke(new EmbedRevokeCommand(EmbedRegistrationFixtures.REGISTRATION,
                            EmbedRegistrationFixtures.TENANT, captured.revision()))).revision();
            assertFalse(store.isCurrent(captured));
        }
        try (var reopened = open()) {
            assertInstanceOf(EmbedRegistrationResolution.Unavailable.class,
                    reopened.resolveCurrent(EmbedRegistrationFixtures.workload(),
                            EmbedRegistrationFixtures.REGISTRATION),
                    "a revocation that a restart forgets is not a revocation");
            assertEquals(EmbedProvisionOutcome.Reason.REGISTRATION_REVOKED,
                    assertInstanceOf(EmbedProvisionOutcome.Rejected.class, reopened.provision(
                            EmbedRegistrationFixtures.command(revision, "sha256:b", "start"))).reason());
            assertInstanceOf(EmbedRevokeOutcome.AlreadyRevoked.class,
                    reopened.revoke(new EmbedRevokeCommand(EmbedRegistrationFixtures.REGISTRATION,
                            EmbedRegistrationFixtures.TENANT, revision)));
        }
    }

    @Test
    void compareAndSetRejectsAStaleExpectationAndIncrementsMonotonically() {
        try (var store = open()) {
            assertInstanceOf(EmbedProvisionOutcome.Conflict.class,
                    store.provision(EmbedRegistrationFixtures.command(3, "sha256:a", "start")));
            var first = provisioned(store, EmbedRegistrationFixtures.command(0, "sha256:a", "start"));
            assertEquals(1, first.revision());

            var conflict = assertInstanceOf(EmbedProvisionOutcome.Conflict.class,
                    store.provision(EmbedRegistrationFixtures.command(0, "sha256:b", "start")));
            assertEquals(0, conflict.expectedRevision());
            assertEquals(1, conflict.currentRevision());

            assertEquals(2, provisioned(store,
                    EmbedRegistrationFixtures.command(1, "sha256:b", "start")).revision());
        }
    }

    /**
     * Two independent connections to the same file, each with its own transaction, racing the same
     * compare-and-set. {@code BEGIN IMMEDIATE} plus {@code busy_timeout} makes the loser wait and then
     * observe the winner's revision, rather than both reading revision 1 and both writing revision 2.
     */
    @Test
    void twoConcurrentWritersOnOneFileProduceExactlyOneWinner() throws Exception {
        try (var seed = open()) {
            provisioned(seed, EmbedRegistrationFixtures.command(0, "sha256:a", "start"));
        }
        int writers = 4;
        var stores = new ArrayList<SqliteEmbedRegistrationStore>();
        try {
            for (int index = 0; index < writers; index++) stores.add(open());
            var barrier = new CyclicBarrier(writers);
            var tasks = new ArrayList<Callable<EmbedProvisionOutcome>>();
            for (int index = 0; index < writers; index++) {
                var store = stores.get(index);
                String digest = "sha256:w" + index;
                tasks.add(() -> {
                    barrier.await();
                    return store.provision(EmbedRegistrationFixtures.command(1, digest, "start"));
                });
            }
            List<EmbedProvisionOutcome> outcomes = new ArrayList<>();
            try (var pool = Executors.newFixedThreadPool(writers)) {
                for (Future<EmbedProvisionOutcome> future : pool.invokeAll(tasks)) outcomes.add(future.get());
            }
            assertEquals(1, outcomes.stream()
                            .filter(EmbedProvisionOutcome.Provisioned.class::isInstance).count(),
                    "exactly one writer may win: " + outcomes);
            assertEquals(writers - 1, outcomes.stream()
                    .filter(EmbedProvisionOutcome.Conflict.class::isInstance).count());
        } finally {
            stores.forEach(SqliteEmbedRegistrationStore::close);
        }
        try (var reopened = open()) {
            var loaded = assertInstanceOf(EmbedRegistrationResolution.Available.class,
                    reopened.resolveCurrent(EmbedRegistrationFixtures.workload(),
                            EmbedRegistrationFixtures.REGISTRATION)).aggregate();
            assertEquals(2, loaded.revision(), "one increment, not four");
        }
    }

    /** A second writer's replacement makes a captured aggregate stale immediately, across connections. */
    @Test
    void anotherConnectionsReplacementIsVisibleToACapturedAggregate() {
        try (var reader = open(); var writer = open()) {
            var captured = provisioned(reader, EmbedRegistrationFixtures.command(0, "sha256:a", "start"));
            assertTrue(reader.isCurrent(captured));
            assertInstanceOf(EmbedProvisionOutcome.Provisioned.class,
                    writer.provision(EmbedRegistrationFixtures.command(1, "sha256:b", "start")));
            assertFalse(reader.isCurrent(captured));
            assertInstanceOf(EmbedProjectionResolution.Unavailable.class,
                    reader.resolveProjection(captured, EmbedProjectionBudget.DEFAULTS));
        }
    }

    /** The projection served is the captured revision's, never re-derived from the current row. */
    @Test
    void theProjectionServedBelongsToTheCapturedRevision() {
        try (var store = open()) {
            var captured = provisioned(store, EmbedRegistrationFixtures.command(0, "sha256:a", "old"));
            var available = assertInstanceOf(EmbedProjectionResolution.Available.class,
                    store.resolveProjection(captured, EmbedProjectionBudget.DEFAULTS));
            assertEquals("sha256:a", available.projection().canonicalDigest());
            assertEquals(List.of("old"), available.projection().nodes().stream()
                    .map(EmbedGraphProjection.Node::id).toList());
        }
    }

    /**
     * A row edited outside this adapter fails to reconstruct rather than becoming an aggregate whose
     * grant and payload disagree — exactly the pairing this store makes impossible.
     */
    @Test
    void aHandEditedRowIsRefusedRatherThanReconstructedIntoAnIncoherentAggregate() throws Exception {
        Path databaseFile;
        try (var store = open()) {
            provisioned(store, EmbedRegistrationFixtures.command(0, "sha256:a", "start"));
            databaseFile = store.databaseFile();
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE embed_registration SET canonical_digest = 'sha256:TAMPERED'");
        }
        try (var reopened = open()) {
            assertInstanceOf(EmbedRegistrationResolution.Unavailable.class,
                    reopened.resolveCurrent(EmbedRegistrationFixtures.workload(),
                            EmbedRegistrationFixtures.REGISTRATION));
        }
    }

    @Test
    void aTruncatedEligibilityGateSetIsRefused() throws Exception {
        Path databaseFile;
        try (var store = open()) {
            provisioned(store, EmbedRegistrationFixtures.command(0, "sha256:a", "start"));
            databaseFile = store.databaseFile();
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE embed_registration SET eligibility_gates = '111111'");
        }
        try (var reopened = open()) {
            assertInstanceOf(EmbedRegistrationResolution.Unavailable.class,
                    reopened.resolveCurrent(EmbedRegistrationFixtures.workload(),
                            EmbedRegistrationFixtures.REGISTRATION),
                    "six gates cannot be read as seven with the missing one assumed true");
        }
    }

    /**
     * The limit of what this store defends, asserted so the documentation cannot drift from it.
     *
     * <p>Reconstruction validates <b>coherence</b>, not <b>integrity</b>: the aggregate's constructor
     * checks that the declared fields agree with each other, and nothing recomputes anything over the
     * stored {@code projection_json} -- the canonical digest is the hash of the source GraphML, not of
     * the projection, and the two are only ever compared as strings written together. So a row edited
     * by someone with write access to the database loads cleanly, and the edit below defeats both
     * terminality and monotonicity <em>without</em> going through the compare-and-set and without an
     * audit record.
     *
     * <p>This test asserts that this is so. It is not an endorsement: it is the tripwire for the
     * runbook's "Write access to the store directory is complete control of every embed" section. A
     * message authentication code over each row would change this and is deliberately out of scope --
     * it is a separate decision with its own key-management questions. If someone adds one, this test
     * fails, and that section is what they must come back and rewrite.</p>
     */
    @Test
    void writeAccessToTheDatabaseDefeatsRevocationAndThisIsDocumentedRatherThanFixed() throws Exception {
        Path databaseFile;
        long revoked;
        try (var store = open()) {
            var captured = provisioned(store, EmbedRegistrationFixtures.command(0, "sha256:a", "start"));
            revoked = assertInstanceOf(EmbedRevokeOutcome.Revoked.class,
                    store.revoke(new EmbedRevokeCommand(EmbedRegistrationFixtures.REGISTRATION,
                            EmbedRegistrationFixtures.TENANT, captured.revision()))).revision();
            databaseFile = store.databaseFile();
        }
        // Baseline, so this test stands on its own rather than on a sibling establishing the same
        // thing: the registration really does read REVOKED *before* the tampering below. Without it,
        // a store that had somehow never revoked at all would let the assertions further down pass
        // while observing nothing about the tampering.
        try (var beforeTampering = open()) {
            assertEquals(EmbedRegistrationState.REVOKED,
                    beforeTampering.currentForOperator(EmbedRegistrationFixtures.TENANT,
                            EmbedRegistrationFixtures.REGISTRATION).orElseThrow().state());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             Statement statement = connection.createStatement()) {
            // Asserted, not assumed: a test that says "this limitation exists" must not be able to
            // pass because its own edit silently did nothing -- a renamed column would otherwise turn
            // this into a test that observes an untouched row and reports the wrong reassurance.
            assertEquals(1, statement.executeUpdate(
                            "UPDATE embed_registration SET state = 'ACTIVE', revision = 1"),
                    "the tampering edit must have actually changed the row");
        }
        try (var reopened = open()) {
            var resurrected = assertInstanceOf(EmbedRegistrationResolution.Available.class,
                    reopened.resolveCurrent(EmbedRegistrationFixtures.workload(),
                            EmbedRegistrationFixtures.REGISTRATION)).aggregate();
            assertTrue(resurrected.active(),
                    "a terminally revoked registration is editable back to ACTIVE by anyone who can "
                            + "write the file; see the runbook section this test guards");
            assertTrue(resurrected.revision() < revoked,
                    "and the revision goes backwards, which the compare-and-set path forbids");
        }
    }

    @Test
    void aForeignTenantCanNeitherRevokeNorOverwriteARegistration() {
        try (var store = open()) {
            var captured = provisioned(store, EmbedRegistrationFixtures.command(0, "sha256:a", "start"));
            assertInstanceOf(EmbedRevokeOutcome.NotFound.class,
                    store.revoke(new EmbedRevokeCommand(EmbedRegistrationFixtures.REGISTRATION,
                            "tenant-b", captured.revision())));
            assertTrue(store.isCurrent(captured));
        }
    }

    private SqliteEmbedRegistrationStore open() {
        return SqliteEmbedRegistrationStore.openUnder(directory, CLOCK, EmbedProjectionBudget.DEFAULTS);
    }

    private EmbedRegistrationAggregate openMigratedAggregate(CyclicBarrier barrier) throws Exception {
        barrier.await();
        try (var store = open()) {
            return store.currentForOperator(EmbedRegistrationFixtures.TENANT,
                    EmbedRegistrationFixtures.REGISTRATION).orElseThrow();
        }
    }

    /** Builds the exact table shipped as schema v1, including one real legacy snapshot row. */
    private void createPopulatedV1Database(EmbedRegistrationAggregate aggregate) throws Exception {
        Path database = directory.resolve(SqliteEmbedRegistrationStore.FILE_NAME);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("""
                    CREATE TABLE embed_registration (
                        registration_id    TEXT    NOT NULL PRIMARY KEY,
                        revision           INTEGER NOT NULL,
                        state              TEXT    NOT NULL,
                        tenant_id          TEXT    NOT NULL,
                        workload_issuer    TEXT    NOT NULL,
                        workload_subject   TEXT    NOT NULL,
                        parent_origin      TEXT    NOT NULL,
                        capabilities       TEXT    NOT NULL,
                        theme_override     TEXT,
                        resource_id        TEXT    NOT NULL,
                        deployment_id      TEXT    NOT NULL,
                        deployment_version INTEGER NOT NULL,
                        graph_id           TEXT    NOT NULL,
                        graph_version_id   TEXT    NOT NULL,
                        canonical_digest   TEXT    NOT NULL,
                        policy_revision    TEXT    NOT NULL,
                        snapshot_lifecycle TEXT    NOT NULL,
                        eligibility_gates  TEXT    NOT NULL,
                        projection_json    TEXT    NOT NULL,
                        provisioned_at     TEXT    NOT NULL
                    ) WITHOUT ROWID
                    """);
            statement.execute("CREATE INDEX embed_registration_tenant "
                    + "ON embed_registration (tenant_id, registration_id)");
            statement.execute("PRAGMA user_version = 1");
            var grant = aggregate.sessionGrant();
            var graph = aggregate.graphGrant();
            try (var insert = connection.prepareStatement("INSERT INTO embed_registration "
                    + "(registration_id, revision, state, tenant_id, workload_issuer, workload_subject, "
                    + "parent_origin, capabilities, theme_override, resource_id, deployment_id, "
                    + "deployment_version, graph_id, graph_version_id, canonical_digest, policy_revision, "
                    + "snapshot_lifecycle, eligibility_gates, projection_json, provisioned_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, aggregate.registrationId());
                insert.setLong(2, aggregate.revision());
                insert.setString(3, aggregate.state().name());
                insert.setString(4, grant.tenantId());
                insert.setString(5, grant.workloadIssuer());
                insert.setString(6, grant.workloadSubject());
                insert.setString(7, grant.parentOrigin());
                insert.setString(8, grant.capabilities().stream().map(Enum::name).sorted()
                        .collect(java.util.stream.Collectors.joining(",")));
                insert.setString(9, grant.themeOverride().map(EmbedTheme::wireValue).orElse(null));
                insert.setString(10, graph.resourceId());
                insert.setString(11, graph.deploymentId());
                insert.setLong(12, graph.deploymentVersion());
                insert.setString(13, graph.graphId());
                insert.setString(14, graph.graphVersionId());
                insert.setString(15, graph.canonicalDigest());
                insert.setString(16, graph.projectionPolicyRevision());
                insert.setString(17, aggregate.snapshotLifecycle().name());
                insert.setString(18, "1111111");
                insert.setString(19, aggregate.projection().toJson());
                insert.setString(20, aggregate.provisionedAt().toString());
                assertEquals(1, insert.executeUpdate());
            }
        }
    }

    private static EmbedRegistrationAggregate provisioned(SqliteEmbedRegistrationStore store,
                                                          ai.ravenroot.api.embed.EmbedProvisionCommand command) {
        return assertInstanceOf(EmbedProvisionOutcome.Provisioned.class,
                store.provision(command)).aggregate();
    }
}
