package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.Retryability;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import static ai.ravenroot.persistence.postgresql.PostgresExecutionStoreFixtures.await;
import static ai.ravenroot.persistence.postgresql.PostgresExecutionStoreFixtures.creationBatch;
import static ai.ravenroot.persistence.postgresql.PostgresExecutionStoreFixtures.failureOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The failure members the conformance suite calls adapter-conditional, driven through the port.
 *
 * <p>The suite asserts {@code Corrupted}, {@code Unavailable}, {@code NotAuthorized} and
 * {@code OutcomeUnknown} by constructing the records directly, because no in-memory adapter's own
 * operations can reach them. A shared database changes that for all four, and the last one is the
 * reason this file exists at all: the single-host adapter records that {@code OutcomeUnknown} is
 * <em>unreachable</em> through its operations, and here it is an ordinary event, so an adapter that
 * mapped it wrongly would be discovered by a caller during a network incident rather than by a
 * test.</p>
 *
 * <p>Each test names the {@code SQLSTATE} it drives, because the classification is on the state and
 * not on the driver's exception type or its message — those change between driver versions and the
 * state does not.</p>
 */
class PostgresExecutionStoreFailureMappingTest {

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    /**
     * {@code 55P03 lock_not_available}: a row lock this writer is not willing to wait out.
     *
     * <p>{@code Unavailable} and not {@code OutcomeUnknown}, and the difference is the whole point of
     * the pair. The statement never ran, the transaction rolled back, and this adapter <em>observed</em>
     * that — so asserting "nothing was applied" is a report rather than a guess. The last assertion is
     * what makes that claim falsifiable: after the blocker lets go, the store is unchanged and the next
     * creation is the first revision of a fresh instance.</p>
     *
     * <p>It is also deliberately not {@code Corrupted} or {@code NotAuthorized}: the holder is alive and
     * within its rights, and the operator action is capacity or a longer bound, not recovery.</p>
     */
    @Test
    void aRowLockThisWriterWillNotWaitOutIsUnavailableAndNothingIsApplied() throws Exception {
        String storeId = "failure-lock-" + UUID.randomUUID();
        DataSource dataSource = PostgresTestDatabase.dataSourceFor(storeId);
        var key = new ExecutionKey("acme", UUID.randomUUID());

        // A quarter of a second, so the contention resolves as a lock timeout rather than as a wait
        // that eventually succeeds and tests nothing.
        var config = new PostgresStoreConfig(Duration.ofMillis(250), Duration.ofSeconds(5), 3,
                Duration.ofMinutes(5), 1024 * 1024, Duration.ofSeconds(5), Duration.ofHours(24), 100,
                Duration.ofDays(7), Duration.ofDays(7));

        try (var store = new PostgresExecutionStore(dataSource, new MutableClock(EPOCH), config);
             Connection blocker = dataSource.getConnection()) {
            await(store.apply(creationBatch(key, UUID.randomUUID())));

            blocker.setAutoCommit(false);
            try (PreparedStatement statement = blocker.prepareStatement(
                    "SELECT 1 FROM process_instance WHERE tenant_id = ? AND process_instance_id = ? "
                            + "FOR UPDATE")) {
                statement.setString(1, key.tenantId());
                statement.setObject(2, key.processInstanceId());
                statement.executeQuery().close();
            }

            var unavailable = assertInstanceOf(ExecutionStoreFailure.Unavailable.class,
                    failureOf(() -> await(store.claim(key, "worker-blocked", Duration.ofSeconds(30)))),
                    "a lock this writer waited out is transient and definitely not applied, which is "
                            + "what separates Unavailable from OutcomeUnknown; reporting the second "
                            + "would assert an absence of effect, and here there provably was none");
            assertEquals(Retryability.RETRYABLE_NO_EFFECT, unavailable.retryability());

            blocker.rollback();

            // The refused claim left nothing behind, and the store still works once the lock is gone.
            var second = new ExecutionKey("acme", UUID.randomUUID());
            assertEquals(1L, await(store.apply(creationBatch(second, UUID.randomUUID()))).revision());
        }
    }

    /**
     * {@code 42501 insufficient_privilege}: a credential that may read the schema and not write it.
     *
     * <p>{@code NotAuthorized} rather than {@code Unavailable}, because the two carry opposite advice.
     * {@code Unavailable} is {@link Retryability#RETRYABLE_NO_EFFECT}, and a caller retrying a
     * permission failure retries it until somebody changes a grant — a busy loop that looks, from the
     * outside, exactly like an outage.</p>
     *
     * <p>It is also not tenant scoping. A row another tenant owns is {@code NotFound} by design; this is
     * the store's own connection being refused, which no amount of correct tenancy would fix.</p>
     */
    @Test
    void aCredentialThatMayNotWriteIsNotAuthorizedRatherThanUnavailable() throws Exception {
        String storeId = "failure-grant-" + UUID.randomUUID();
        String schema = PostgresTestDatabase.schemaNameFor(storeId);
        // The schema is created and migrated by the container's own owner, so the restricted role meets
        // a database that is already at the current version and the refusal happens on a write rather
        // than on the migration -- which would prove only that DDL needs privileges.
        try (var owner = new PostgresExecutionStore(PostgresTestDatabase.dataSourceFor(storeId),
                new MutableClock(EPOCH))) {
            await(owner.apply(creationBatch(new ExecutionKey("acme", UUID.randomUUID()),
                    UUID.randomUUID())));
        }

        String role = "reader_" + UUID.randomUUID().toString().replace("-", "");
        // Minted here and never written down: it lives as long as this container and this method.
        String secret = UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = PostgresTestDatabase.dataSource(PostgresTestDatabase.jdbcUrl(),
                PostgresTestDatabase.username(), PostgresTestDatabase.password(), null).getConnection();
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE ROLE \"" + role + "\" LOGIN PASSWORD '" + secret + "'");
            // USAGE and CREATE so the migration runner gets as far as reading its own version table and
            // finds nothing to do; SELECT and nothing else on the tables, so the first refusal is a
            // write through the port rather than DDL at construction. Granting less would prove only
            // that creating tables needs privileges, which is not the mapping under test.
            statement.execute("GRANT USAGE, CREATE ON SCHEMA \"" + schema + "\" TO \"" + role + "\"");
            statement.execute("GRANT SELECT ON ALL TABLES IN SCHEMA \"" + schema + "\" TO \"" + role + "\"");
        }

        DataSource restricted = PostgresTestDatabase.dataSource(PostgresTestDatabase.jdbcUrl(), role,
                secret, schema);
        try (var store = new PostgresExecutionStore(restricted, new MutableClock(EPOCH))) {
            var failure = assertInstanceOf(ExecutionStoreFailure.NotAuthorized.class,
                    failureOf(() -> await(store.apply(creationBatch(
                            new ExecutionKey("acme", UUID.randomUUID()), UUID.randomUUID())))),
                    "an operator condition, not a caller error and not an outage");
            assertEquals(Retryability.DETERMINISTIC_REJECT, failure.retryability());
        }
    }

    /**
     * {@code 57P01 admin_shutdown} during {@code COMMIT}: the outcome is genuinely unknown.
     *
     * <p>This is the state the single-host adapter documents as unreachable, and it is the reason this
     * adapter cannot be a port of that one. The writer and the database are separate processes, so the
     * connection can end after {@code COMMIT} was sent and before its acknowledgement arrived — and in
     * that window the transaction may or may not have been applied. The store cannot tell, so it must
     * not claim to.</p>
     *
     * <p>The kill is placed at {@link CommitBoundary#beforeCommit()} rather than "somewhere during the
     * batch" on purpose. A kill that is not precisely placed almost always lands in the long tail of
     * statement execution and demonstrates that an un-started transaction leaves no trace, which is not
     * in doubt, while never once exercising the boundary where atomicity is actually decided.</p>
     *
     * <p>The assertion is on the <em>classification</em>, not on whether the row landed. Reporting
     * {@code Unavailable} here would assert an absence of effect the adapter cannot observe, and a
     * caller that believed it would skip the reconciliation this failure exists to demand. Note that
     * the transaction did in fact not commit — the honest answer is still "unknown", because the
     * adapter has no way to know that.</p>
     */
    @Test
    void aCommitWhoseConnectionDiesUnderneathItIsOutcomeUnknownRatherThanUnavailable() throws Exception {
        String storeId = "failure-commit-" + UUID.randomUUID();
        String application = "ravenroot-outcome-unknown-"
                + UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        DataSource dataSource = PostgresTestDatabase.dataSourceFor(storeId, application);
        var key = new ExecutionKey("acme", UUID.randomUUID());

        CommitBoundary killTheBackend = new CommitBoundary() {
            @Override
            public void beforeCommit() {
                try (Connection assassin = PostgresTestDatabase.dataSource(
                        PostgresTestDatabase.jdbcUrl(), PostgresTestDatabase.username(),
                        PostgresTestDatabase.password(), null).getConnection();
                     PreparedStatement statement = assassin.prepareStatement(
                             "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                                     + "WHERE application_name = ? AND pid <> pg_backend_pid()")) {
                    statement.setString(1, application);
                    statement.executeQuery().close();
                } catch (SQLException failed) {
                    throw new IllegalStateException("could not end the writer's backend", failed);
                }
            }
        };

        try (var store = new PostgresExecutionStore(dataSource, new MutableClock(EPOCH),
                PostgresStoreConfig.defaults(), killTheBackend)) {
            var unknown = assertInstanceOf(ExecutionStoreFailure.OutcomeUnknown.class,
                    failureOf(() -> await(store.apply(creationBatch(key, UUID.randomUUID())))),
                    "a commit that neither succeeded nor demonstrably failed is the one case where the "
                            + "adapter cannot say whether the write applied, and the port has a member "
                            + "for exactly that rather than for a guess in either direction");
            assertEquals(Retryability.INDETERMINATE, unknown.retryability());
            assertEquals(key, unknown.key());
            assertTrue(unknown.describe().contains(key.processInstanceId().toString()),
                    "the caller has to reconcile against this instance, so its identity has to be in "
                            + "the failure: " + unknown.describe());
        }

        // And the store is usable again on a new connection, which is what makes the diagnosis
        // "that connection died" rather than "this store is broken".
        try (var reopened = new PostgresExecutionStore(PostgresTestDatabase.dataSourceFor(storeId),
                new MutableClock(EPOCH))) {
            assertEquals(1L, await(reopened.apply(creationBatch(
                    new ExecutionKey("acme", UUID.randomUUID()), UUID.randomUUID()))).revision());
        }
    }

    /**
     * Rows edited out of band that no longer reconstruct into a legal aggregate.
     *
     * <p>Not a {@code SQLSTATE} at all, and that is the point of including it here: reconstruction
     * through the aggregate's own constructors is the only place a durable store can detect this. The
     * database is perfectly happy — every row is individually well formed — and the set of them is
     * collectively illegal, because the aggregate forbids a {@code COMPLETED} process holding a
     * traversal that is not.</p>
     */
    @Test
    void rowsThatNoLongerReconstructIntoALegalAggregateSurfaceAsCorrupted() throws Exception {
        String storeId = "failure-corrupt-" + UUID.randomUUID();
        DataSource dataSource = PostgresTestDatabase.dataSourceFor(storeId);
        var key = new ExecutionKey("acme", UUID.randomUUID());

        try (var store = new PostgresExecutionStore(dataSource, new MutableClock(EPOCH))) {
            await(store.apply(creationBatch(key, UUID.randomUUID())));

            // Exactly as a hand-run UPDATE during an incident would leave it.
            try (Connection direct = dataSource.getConnection();
                 Statement statement = direct.createStatement()) {
                statement.executeUpdate("UPDATE process_instance SET status = 'COMPLETED'");
            }

            var corrupted = assertInstanceOf(ExecutionStoreFailure.Corrupted.class,
                    failureOf(() -> await(store.load(key))),
                    "without reconstruction the illegal aggregate escapes into the runtime and fails "
                            + "somewhere with no connection to its cause");
            assertEquals(key, corrupted.key());
            assertEquals(Retryability.DETERMINISTIC_REJECT, corrupted.retryability());
            assertTrue(corrupted.describe().contains(key.processInstanceId().toString()));
        }
    }

    /**
     * A schema written by a newer build is refused rather than opened.
     *
     * <p>The guard is the reason a rolling upgrade is survivable. An older binary cannot see the
     * columns it does not know about, so it would write rows the newer binary later reads as
     * incomplete — silent, and discovered as corruption long afterwards. Refusing to start is loud, and
     * during a rolling upgrade it is the correct answer: the old pods should fail, not quietly write a
     * shape the new schema no longer means.</p>
     */
    @Test
    void aSchemaNewerThanThisBuildIsRefusedRatherThanOpened() throws Exception {
        String storeId = "failure-downgrade-" + UUID.randomUUID();
        DataSource dataSource = PostgresTestDatabase.dataSourceFor(storeId);
        try (var store = new PostgresExecutionStore(dataSource, new MutableClock(EPOCH))) {
            await(store.apply(creationBatch(new ExecutionKey("acme", UUID.randomUUID()),
                    UUID.randomUUID())));
        }
        try (Connection direct = dataSource.getConnection();
             Statement statement = direct.createStatement()) {
            statement.executeUpdate("UPDATE store_schema_version SET version = "
                    + (PostgresSchema.currentVersion() + 1));
        }

        var refused = assertThrows(IllegalStateException.class,
                () -> new PostgresExecutionStore(dataSource, new MutableClock(EPOCH)));
        assertTrue(refused.getMessage().contains("newer than this build understands"), refused.getMessage());
    }
}
