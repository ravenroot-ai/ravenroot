package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The versioned schema and its forward-only migration runner.
 *
 * <h2>Where the version lives</h2>
 * <p>{@code PRAGMA user_version} is the authority. It is an integer in the SQLite file header that
 * exists in every database from the moment it is created, so there is no bootstrap problem: an empty
 * file reports {@code 0} and needs no table to say so. A version table alone cannot do that, because
 * reading it requires a migration to have created it. {@code user_version} is also written inside the
 * enclosing transaction, so the version and the DDL it describes commit or roll back together — a
 * migration can never leave the file structurally changed but labelled with the old version.</p>
 *
 * <p>{@code store_schema_history} is an audit trail rather than a source of truth. It is written in
 * the same transaction, so it cannot disagree with {@code user_version}; it exists because an
 * operator holding a database file needs to see <em>what</em> ran and when, not just a number.</p>
 *
 * <h2>Forward only, one transaction per step</h2>
 * <p>Each migration commits separately. A run interrupted between two steps therefore leaves the file
 * at a real, complete intermediate version rather than half of one, and the next open resumes from
 * exactly that point. Applying all steps in a single transaction would make an interrupted upgrade of
 * a large database restart from the beginning, and would hold a write lock for the whole run.</p>
 *
 * <h2>The downgrade guard</h2>
 * <p>A database whose version exceeds the highest this binary knows is refused rather than opened. An
 * older binary reading a newer file cannot see the columns it does not know about, so it would write
 * rows that the newer binary then reads as incomplete — silent, and discovered later as corruption.
 * Refusing to open is loud and reversible.</p>
 */
final class SqliteSchema {

    private SqliteSchema() {
    }

    /**
     * The full schema, as a single first migration.
     *
     * <h3>Normalized, not a serialized blob</h3>
     * <p>The aggregate is stored as rows — one per traversal, invocation, causal parent edge and
     * attempt — rather than as a serialized {@code ProcessInstance}. A blob column would make the
     * store's on-disk format an encoding of a Java type, so the aggregate could not change shape
     * without a data migration, and nothing on disk would be queryable: {@code claimPendingWork} has
     * to find {@code SCHEDULED} attempts, and against a blob that means deserializing every instance
     * of the tenant on every poll. Normalized rows also make {@code Corrupted} detectable, because
     * reconstruction runs through the aggregate's own canonical constructors.</p>
     *
     * <h3>Ordering is data</h3>
     * <p>{@code traversal.position} and {@code invocation.position} exist because
     * {@code ProcessInstance} keeps traversals and invocations in {@link java.util.LinkedHashMap}s and
     * its cross-traversal parent rule is stated in terms of an invocation's <em>position</em> within
     * its traversal — the rule is legal only on the invocation at position zero. Insertion order is
     * therefore part of the validated state, and a schema that let SQLite choose a row order would
     * reconstruct an aggregate the domain rejects.</p>
     *
     * <h3>The fencing token is on the instance, not on the lease</h3>
     * <p>ADR 0010 section 13.1: a fencing token must not reset when the store reopens, and it must
     * survive the lease that issued it — {@code ack} and {@code apply} compare a presented token
     * against the <em>current</em> token whether or not a lease is currently held. Keeping the counter
     * on {@code process_instance} means releasing a lease deletes the lease row without touching the
     * token, which is exactly the required behaviour, and reopening the file reads it back unchanged.
     * A token column on {@code lease} would vanish with the lease and restart from zero.</p>
     */
    static List<SchemaMigration> migrations() {
        return List.of(new SchemaMigration(1, "PERS-03 initial execution store", List.of(
                """
                CREATE TABLE process_instance (
                    tenant_id           TEXT    NOT NULL,
                    process_instance_id TEXT    NOT NULL,
                    status              TEXT    NOT NULL,
                    graph_version_pin   TEXT    NOT NULL,
                    revision            INTEGER NOT NULL,
                    fencing_token       INTEGER NOT NULL,
                    updated_at_epoch_second INTEGER NOT NULL,
                    updated_at_nano         INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id)
                )
                """,
                """
                CREATE TABLE traversal (
                    tenant_id           TEXT    NOT NULL,
                    process_instance_id TEXT    NOT NULL,
                    traversal_id        TEXT    NOT NULL,
                    position            INTEGER NOT NULL,
                    ingress_node_id     TEXT    NOT NULL,
                    status              TEXT    NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id, traversal_id),
                    FOREIGN KEY (tenant_id, process_instance_id)
                        REFERENCES process_instance (tenant_id, process_instance_id) ON DELETE CASCADE
                )
                """,
                """
                CREATE TABLE invocation (
                    tenant_id           TEXT    NOT NULL,
                    process_instance_id TEXT    NOT NULL,
                    traversal_id        TEXT    NOT NULL,
                    invocation_id       TEXT    NOT NULL,
                    position            INTEGER NOT NULL,
                    node_id             TEXT    NOT NULL,
                    status              TEXT    NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id, invocation_id),
                    FOREIGN KEY (tenant_id, process_instance_id, traversal_id)
                        REFERENCES traversal (tenant_id, process_instance_id, traversal_id) ON DELETE CASCADE
                )
                """,
                """
                CREATE TABLE invocation_parent (
                    tenant_id            TEXT NOT NULL,
                    process_instance_id  TEXT NOT NULL,
                    invocation_id        TEXT NOT NULL,
                    parent_invocation_id TEXT NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id, invocation_id, parent_invocation_id),
                    FOREIGN KEY (tenant_id, process_instance_id, invocation_id)
                        REFERENCES invocation (tenant_id, process_instance_id, invocation_id) ON DELETE CASCADE
                )
                """,
                """
                CREATE TABLE attempt (
                    tenant_id           TEXT    NOT NULL,
                    process_instance_id TEXT    NOT NULL,
                    invocation_id       TEXT    NOT NULL,
                    attempt_id          TEXT    NOT NULL,
                    ordinal             INTEGER NOT NULL,
                    status              TEXT    NOT NULL,
                    completion          TEXT,
                    PRIMARY KEY (tenant_id, process_instance_id, attempt_id),
                    FOREIGN KEY (tenant_id, process_instance_id, invocation_id)
                        REFERENCES invocation (tenant_id, process_instance_id, invocation_id) ON DELETE CASCADE
                )
                """,
                "CREATE INDEX idx_attempt_scheduled ON attempt (tenant_id, process_instance_id, status)",
                """
                CREATE TABLE timer (
                    tenant_id            TEXT    NOT NULL,
                    process_instance_id  TEXT    NOT NULL,
                    timer_id             TEXT    NOT NULL,
                    traversal_id         TEXT,
                    invocation_id        TEXT,
                    payload_content_type TEXT    NOT NULL,
                    payload_bytes        BLOB    NOT NULL,
                    due_at_epoch_second  INTEGER NOT NULL,
                    due_at_nano          INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id, timer_id),
                    FOREIGN KEY (tenant_id, process_instance_id)
                        REFERENCES process_instance (tenant_id, process_instance_id) ON DELETE CASCADE
                )
                """,
                "CREATE INDEX idx_timer_due ON timer (tenant_id, due_at_epoch_second, due_at_nano)",
                """
                CREATE TABLE lease (
                    tenant_id                TEXT    NOT NULL,
                    process_instance_id      TEXT    NOT NULL,
                    worker_id                TEXT    NOT NULL,
                    claimed_at_epoch_second  INTEGER NOT NULL,
                    claimed_at_nano          INTEGER NOT NULL,
                    expires_at_epoch_second  INTEGER NOT NULL,
                    expires_at_nano          INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id),
                    FOREIGN KEY (tenant_id, process_instance_id)
                        REFERENCES process_instance (tenant_id, process_instance_id) ON DELETE CASCADE
                )
                """,
                "CREATE INDEX idx_lease_expiry ON lease (tenant_id, expires_at_epoch_second, expires_at_nano)",
                """
                CREATE TABLE work_claim (
                    tenant_id           TEXT    NOT NULL,
                    process_instance_id TEXT    NOT NULL,
                    work_item_id        TEXT    NOT NULL,
                    delivery_attempt    INTEGER NOT NULL,
                    visible_again_at_epoch_second INTEGER NOT NULL,
                    visible_again_at_nano         INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id, work_item_id),
                    FOREIGN KEY (tenant_id, process_instance_id)
                        REFERENCES process_instance (tenant_id, process_instance_id) ON DELETE CASCADE
                )
                """,
                """
                CREATE TABLE work_acknowledgement (
                    tenant_id           TEXT NOT NULL,
                    process_instance_id TEXT NOT NULL,
                    work_item_id        TEXT NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id, work_item_id),
                    FOREIGN KEY (tenant_id, process_instance_id)
                        REFERENCES process_instance (tenant_id, process_instance_id) ON DELETE CASCADE
                )
                """,
                """
                CREATE TABLE idempotency_record (
                    tenant_id                        TEXT    NOT NULL,
                    idempotency_key                  TEXT    NOT NULL,
                    request_fingerprint_content_type TEXT    NOT NULL,
                    request_fingerprint_bytes        BLOB    NOT NULL,
                    outcome_ref_content_type         TEXT    NOT NULL,
                    outcome_ref_bytes                BLOB    NOT NULL,
                    recorded_at_revision             INTEGER NOT NULL,
                    expires_at_epoch_second          INTEGER NOT NULL,
                    expires_at_nano                  INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, idempotency_key)
                )
                """,
                "CREATE INDEX idx_idempotency_expiry ON idempotency_record "
                        + "(tenant_id, expires_at_epoch_second, expires_at_nano)",
                """
                CREATE TABLE idempotency_watermark (
                    tenant_id                       TEXT    NOT NULL PRIMARY KEY,
                    forgotten_before_epoch_second   INTEGER NOT NULL,
                    forgotten_before_nano           INTEGER NOT NULL
                )
                """)),
                new SchemaMigration(2, "PERS-07 event journal, transactional outbox and inbox", List.of(
                """
                CREATE TABLE event_journal (
                    tenant_id             TEXT    NOT NULL,
                    journal_offset        INTEGER NOT NULL,
                    stream_sequence       INTEGER NOT NULL,
                    process_instance_id   TEXT    NOT NULL,
                    committed_at_revision INTEGER NOT NULL,
                    envelope_version      INTEGER NOT NULL,
                    event_id              TEXT    NOT NULL,
                    event_type            TEXT    NOT NULL,
                    traversal_id          TEXT    NOT NULL,
                    invocation_id         TEXT,
                    attempt_id            TEXT,
                    causation_id          TEXT,
                    correlation_id        TEXT    NOT NULL,
                    graph_version         TEXT    NOT NULL,
                    occurred_at_epoch_second INTEGER NOT NULL,
                    occurred_at_nano         INTEGER NOT NULL,
                    payload_content_type  TEXT    NOT NULL,
                    payload_bytes         BLOB    NOT NULL,
                    digest                BLOB    NOT NULL,
                    recorded_at_epoch_second INTEGER NOT NULL,
                    recorded_at_nano         INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, journal_offset)
                )
                """,
                // The publisher walks this axis and nothing else, so it is the index that matters.
                "CREATE UNIQUE INDEX idx_journal_stream ON event_journal "
                        + "(tenant_id, process_instance_id, stream_sequence)",
                """
                CREATE TABLE outbox_cursor (
                    tenant_id         TEXT    NOT NULL,
                    destination       TEXT    NOT NULL,
                    delivered_through INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, destination)
                )
                """,
                """
                CREATE TABLE inbox_record (
                    tenant_id               TEXT    NOT NULL,
                    consumer_id             TEXT    NOT NULL,
                    event_id                TEXT    NOT NULL,
                    expires_at_epoch_second INTEGER NOT NULL,
                    expires_at_nano         INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, consumer_id, event_id)
                )
                """,
                // The counters must outlive every record they numbered. A journal compacted to empty
                // that recomputed next_offset from its rows would reissue offsets a destination
                // cursor has already passed, and those events would never be delivered to it —
                // silently, because nothing is missing from the publisher's point of view.
                """
                CREATE TABLE journal_watermark (
                    tenant_id      TEXT    NOT NULL PRIMARY KEY,
                    next_offset    INTEGER NOT NULL,
                    retained_from  INTEGER NOT NULL
                )
                """,
                """
                CREATE TABLE journal_stream_sequence (
                    tenant_id           TEXT    NOT NULL,
                    process_instance_id TEXT    NOT NULL,
                    next_sequence       INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id)
                )
                """)),
                // PERS-04 (ADR 0022). The column is nullable and no existing row is rewritten:
                // every pre-PERS-04 attempt row folds exactly as it did before, because a park cause
                // exists iff the status is PARKED and no pre-existing row can carry that status.
                //
                // The *rollback* is the one-way part, and it is a property of the status name rather
                // than of this column: the first row written with status 'PARKED' is unreadable by a
                // pre-PERS-04 binary, whose NodeAttemptStatus.valueOf throws and surfaces as
                // ExecutionStoreFailure.Corrupted on replay. Downgrade is safe until that first row
                // exists and unsafe after it. That mapping is asserted by the conformance suite
                // (unknownAttemptStatusNamesReplayAsCorruptedRatherThanBeingMisread) rather than left
                // to be discovered in production.
                new SchemaMigration(3, "PERS-04 parked attempts carry an operator-facing cause", List.of(
                        "ALTER TABLE attempt ADD COLUMN park_cause TEXT")),
                // Existing invocations were operational by definition. The non-null default makes
                // upgrade and replay preserve that meaning without rewriting old rows in Java.
                new SchemaMigration(4, "CORE-317 structural incoming node command", List.of(
                        "ALTER TABLE invocation ADD COLUMN node_command TEXT NOT NULL DEFAULT 'process'")),
                // Durable canonical graph definitions, in the same database as the executions that
                // pin them. Co-location is not a convenience: it is what puts a definition and the
                // execution that needs it into one backup snapshot and under one file lock, and it is
                // what lets retention decide reachability from `process_instance` in the same
                // transaction that removes a definition rather than across two stores that can
                // disagree.
                new SchemaMigration(5, "durable canonical graph definitions", List.of(
                        """
                        CREATE TABLE graph_definition (
                            tenant_id         TEXT    NOT NULL,
                            content_id        TEXT    NOT NULL,
                            format_version    INTEGER NOT NULL,
                            definition_bytes  BLOB    NOT NULL,
                            digest            BLOB    NOT NULL CHECK(length(digest) = 32),
                            byte_length       INTEGER NOT NULL,
                            first_graph_id    TEXT    NOT NULL,
                            first_version_id  TEXT    NOT NULL,
                            stored_at_epoch_second INTEGER NOT NULL,
                            stored_at_nano         INTEGER NOT NULL,
                            PRIMARY KEY (tenant_id, content_id)
                        )
                        """,
                        """
                        CREATE TABLE graph_definition_binding (
                            tenant_id  TEXT NOT NULL,
                            graph_id   TEXT NOT NULL,
                            version_id TEXT NOT NULL,
                            content_id TEXT NOT NULL,
                            bound_at_epoch_second INTEGER NOT NULL,
                            bound_at_nano         INTEGER NOT NULL,
                            PRIMARY KEY (tenant_id, graph_id, version_id),
                            FOREIGN KEY (tenant_id, content_id)
                                REFERENCES graph_definition (tenant_id, content_id) ON DELETE CASCADE
                        )
                        """,
                        "CREATE INDEX idx_graph_definition_binding_content "
                                + "ON graph_definition_binding (tenant_id, content_id)",
                        // Retention asks, for every candidate definition, whether any instance of the
                        // tenant still pins it. Without this index that question is a tenant-wide
                        // scan of process_instance per candidate.
                        "CREATE INDEX idx_process_instance_pin "
                                + "ON process_instance (tenant_id, graph_version_pin)")));
    }

    static int currentVersion() {
        return migrations().stream().mapToInt(SchemaMigration::version).max().orElse(0);
    }

    static int migrate(Connection connection, Clock clock) throws SQLException {
        return migrate(connection, migrations(), clock);
    }

    /**
     * Applies every migration above the file's current version, in ascending order.
     *
     * @return the version the database is at when this returns
     */
    static int migrate(Connection connection, List<SchemaMigration> migrations, Clock clock)
            throws SQLException {
        var ordered = new ArrayList<>(migrations);
        ordered.sort((left, right) -> Integer.compare(left.version(), right.version()));
        for (int index = 0; index < ordered.size(); index++) {
            if (ordered.get(index).version() != index + 1) {
                throw new IllegalStateException("schema migrations must be numbered 1..n with no gaps "
                        + "or duplicates; found " + ordered.get(index).version() + " at position " + (index + 1));
            }
        }

        createHistoryTable(connection);
        int installed = versionOf(connection);
        int highestKnown = ordered.isEmpty() ? 0 : ordered.getLast().version();
        if (installed > highestKnown) {
            throw new IllegalStateException("database schema version " + installed
                    + " is newer than this build understands (" + highestKnown + "); refusing to open, "
                    + "because an older binary writing rows a newer schema expects would corrupt them silently");
        }

        for (SchemaMigration migration : ordered) {
            if (migration.version() <= installed) {
                continue;
            }
            applyOne(connection, migration, clock.instant());
            installed = migration.version();
        }
        return installed;
    }

    private static void applyOne(Connection connection, SchemaMigration migration, Instant appliedAt)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                for (String ddl : migration.statements()) {
                    statement.execute(ddl);
                }
                try (var history = connection.prepareStatement("INSERT INTO store_schema_history "
                        + "(version, description, applied_at_epoch_second, applied_at_nano) VALUES (?, ?, ?, ?)")) {
                    history.setInt(1, migration.version());
                    history.setString(2, migration.description());
                    StoredInstant.bindValue(history, 3, appliedAt);
                    history.executeUpdate();
                }
                // Transactional: it lives in the file header and commits with the DDL above, so the
                // recorded version and the structure it names can never disagree.
                statement.execute("PRAGMA user_version = " + migration.version());
                statement.execute("COMMIT");
            } catch (SQLException | RuntimeException failed) {
                safeRollback(connection);
                throw failed;
            }
        }
    }

    private static void createHistoryTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS store_schema_history (
                        version                 INTEGER NOT NULL PRIMARY KEY,
                        description             TEXT    NOT NULL,
                        applied_at_epoch_second INTEGER NOT NULL,
                        applied_at_nano         INTEGER NOT NULL
                    )
                    """);
        }
    }

    static int versionOf(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA user_version")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    /** The highest schema version this build can open. */
    static int highestKnownVersion() {
        return migrations().getLast().version();
    }

    /**
     * Refuses a file that is not a restorable Ravenroot execution store, <em>before</em> a restore
     * deletes anything.
     *
     * <p>Three questions, in the order that makes a wrong answer cheapest. Is it a SQLite database at
     * all — a mistyped path naming a text file must not reach the point where sidecars are removed. Is
     * it structurally intact — restoring a corrupt file over a working one turns an operator error into
     * data loss. Is its schema version one this build understands — the same downgrade guard
     * {@link #migrate} applies, applied early, because discovering it on the reopen after the restore
     * means discovering it when the original is already gone.</p>
     *
     * <p>{@code integrity_check} is bounded to a handful of errors rather than left to enumerate every
     * fault in a large damaged file: the answer needed here is whether it is sound, and the first
     * failure settles that.</p>
     */
    static void requireRestorableStore(Path candidate) {
        try (Connection probe = DriverManager.getConnection("jdbc:sqlite:" + candidate)) {
            String integrity;
            try (Statement statement = probe.createStatement();
                 ResultSet rows = statement.executeQuery("PRAGMA integrity_check(4)")) {
                integrity = rows.next() ? rows.getString(1) : null;
            }
            if (!"ok".equalsIgnoreCase(integrity)) {
                // InvalidRequest rather than Corrupted: Corrupted names this store's own state failing
                // to reconstruct and carries the ExecutionKey that failed. A rejected backup file is an
                // argument the caller got wrong, there is no key to name, and the operator's next step
                // is to supply a different file rather than to investigate this store.
                throw reject("the backup at " + candidate + " does not pass an integrity check: " + integrity);
            }
            int version = versionOf(probe);
            if (version < 1) {
                throw reject("the file at " + candidate
                        + " carries no execution store schema; it is not a Ravenroot backup");
            }
            if (version > highestKnownVersion()) {
                throw reject("the backup at " + candidate + " is at schema version " + version
                        + ", newer than this build understands (" + highestKnownVersion()
                        + "); restoring it would corrupt it silently");
            }
        } catch (SQLException failed) {
            throw new ExecutionStoreException(ExecutionStoreFailure.invalid("the file at " + candidate
                    + " cannot be read as a SQLite database: " + failed.getMessage()), failed);
        }
    }

    private static ExecutionStoreException reject(String reason) {
        return new ExecutionStoreException(ExecutionStoreFailure.invalid(reason));
    }

    private static void safeRollback(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ROLLBACK");
        } catch (SQLException ignored) {
            // Already rolled back, or the connection is gone; the original failure is what matters.
        }
    }
}
