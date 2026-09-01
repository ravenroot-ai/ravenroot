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

    private static EmbedRegistrationAggregate provisioned(SqliteEmbedRegistrationStore store,
                                                          ai.ravenroot.api.embed.EmbedProvisionCommand command) {
        return assertInstanceOf(EmbedProvisionOutcome.Provisioned.class,
                store.provision(command)).aggregate();
    }
}
