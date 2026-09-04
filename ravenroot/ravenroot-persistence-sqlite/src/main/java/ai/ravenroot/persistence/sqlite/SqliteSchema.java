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
        return List.of(new SchemaMigration(1, "initial execution store", List.of(
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
                new SchemaMigration(2, "event journal, transactional outbox and inbox", List.of(
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
                // ADR 0022. The column is nullable and no existing row is rewritten: every attempt
                // row written before this migration folds exactly as it did before, because a park
                // cause exists iff the status is PARKED and no pre-existing row can carry that status.
                //
                // The *rollback* is the one-way part, and it is a property of the status name rather
                // than of this column: the first row written with status 'PARKED' is unreadable by
                // a binary that predates it, whose NodeAttemptStatus.valueOf throws and surfaces as
                // ExecutionStoreFailure.Corrupted on replay. Downgrade is safe until that first row
                // exists and unsafe after it. That mapping is asserted by the conformance suite
                // (unknownAttemptStatusNamesReplayAsCorruptedRatherThanBeingMisread) rather than left
                // to be discovered in production.
                new SchemaMigration(3, "parked attempts carry an operator-facing cause", List.of(
                        "ALTER TABLE attempt ADD COLUMN park_cause TEXT")),
                // Existing invocations were operational by definition. The non-null default makes
                // upgrade and replay preserve that meaning without rewriting old rows in Java.
                new SchemaMigration(4, "structural incoming node command", List.of(
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
                                + "ON process_instance (tenant_id, graph_version_pin)")),
                // PERS-05. A new table rather than columns on `invocation`, because a handler
                // outlives the invocation's own lifecycle: it is retained after the wait ends, so a
                // duplicate or late trigger can still be refused against it and an operator can still
                // see who resolved a human task. Rows cascade with the process instance and with
                // nothing narrower.
                //
                // The two uniqueness rules are enforced by the database rather than by a read-then-
                // write in Java, because a check performed outside the write's own transaction is a
                // race under concurrency and this one decides which of two concurrent triggers wins.
                //
                // `correlation_key` is unique only among handlers that are NOT terminal, expressed as
                // a partial index: a trigger presenting a business key must resolve to exactly one
                // live handler, while a key whose wait is over becomes reusable. `deduplication_key`
                // is unique across every handler of the tenant, terminal included, which is what
                // makes a retried registration a no-op instead of a second handler.
                //
                // The rollback boundary is the table's existence rather than a status name: a
                // pre-PERS-05 binary opening this file is refused by the user_version downgrade
                // guard, and a file that has never registered a handler downgrades cleanly.
                new SchemaMigration(6, "durable handlers for wait, re-entry and human tasks",
                        List.of(
                """
                CREATE TABLE execution_handler (
                    tenant_id             TEXT    NOT NULL,
                    process_instance_id   TEXT    NOT NULL,
                    handler_id            TEXT    NOT NULL,
                    position              INTEGER NOT NULL,
                    name                  TEXT    NOT NULL,
                    traversal_id          TEXT    NOT NULL,
                    invocation_id         TEXT    NOT NULL,
                    correlation_key       TEXT    NOT NULL,
                    deduplication_key     TEXT    NOT NULL,
                    schema_content_type   TEXT    NOT NULL,
                    schema_ref            TEXT    NOT NULL,
                    schema_max_bytes      INTEGER NOT NULL,
                    required_roles        TEXT    NOT NULL,
                    required_scopes       TEXT    NOT NULL,
                    status                TEXT    NOT NULL,
                    resume_traversal_id   TEXT,
                    actor                 TEXT    NOT NULL,
                    outcome_content_type  TEXT    NOT NULL,
                    outcome_bytes         BLOB    NOT NULL,
                    revision              INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id, handler_id),
                    FOREIGN KEY (tenant_id, process_instance_id)
                        REFERENCES process_instance (tenant_id, process_instance_id) ON DELETE CASCADE
                )
                """,
                """
                CREATE UNIQUE INDEX execution_handler_live_correlation
                    ON execution_handler (tenant_id, name, correlation_key)
                    WHERE status IN ('WAITING', 'ESCALATED')
                """,
                """
                CREATE UNIQUE INDEX execution_handler_deduplication
                    ON execution_handler (tenant_id, deduplication_key)
                """,
                """
                CREATE INDEX execution_handler_claimable
                    ON execution_handler (tenant_id, status)
                """)),
                // Additive in structure: every column is added to an existing table and no
                // table is recreated, so an interrupted run leaves a real intermediate version and the
                // fold of every pre-existing row is unchanged. There is no second copy of the
                // lifecycle here and no projection offset -- the inventory is served by reading these
                // same rows, which is what makes it atomic with the transitions for free and leaves
                // nothing that could be rebuilt into successful work that never happened.
                //
                // ONE ROW REWRITE, and it is the created_at backfill. A pre-existing row has no
                // creation instant recorded anywhere, and created_at is half of the inventory's sort
                // key, so leaving it at the DEFAULT would collapse every old row onto epoch zero and
                // order them by id alone. The backfill copies updated_at, and the CAVEAT IS REAL AND
                // PERMANENT: for a row written before this migration, created_at is the instant of its
                // LAST WRITE, not of its creation. It is a truthful upper bound rather than a
                // fabrication -- the instance certainly existed by then -- and it is stable from this
                // point on, because created_at is never written again. Rows created from version 7
                // onwards carry the real instant.
                //
                // lifecycle_generation defaults to 1 for pre-existing rows and that is a FLOOR, not a
                // count: an instance that exists has had at least the transition that created it, and
                // no record survives of how many followed. From version 7 onwards the counter is
                // incremented in the same transaction as each authoritative status transition, so it
                // is exact for every instance created after the upgrade.
                //
                // retained_until is left NULL, including on rows that are already terminal, because a
                // migration cannot know the configured terminal retention and inventing one would
                // schedule a deletion on the strength of a guess. The store treats a terminal row with
                // a NULL retained_until as due at updated_at + terminalRetention(), which for a
                // terminal row is exactly its terminal transition instant, so the upgrade path needs
                // no backfill and no row is retained forever by accident.
                //
                // DOWNGRADE is safe until the first row carries a non-NULL deployment_id, workload_id,
                // correlation_id or retained_until, or a lifecycle_generation above 1 -- that is, until
                // the first write under version 7. A binary that predates the inventory does not
                // select these columns, so it reads and writes every row correctly; what it loses is
                // the values it cannot see, and its next upsert of that row leaves them untouched
                // because the upsert names only the columns it knows. After the first version-7 write
                // the file is at a version the older binary refuses to open, which is the guard doing
                // its job.
                //
                // The number itself is load-bearing and was not free to choose. Two branches that both
                // stamped PRAGMA user_version = 5 would produce databases the downgrade guard cannot
                // tell apart: it compares integers, so a file advanced to 5 by the other definition
                // opens happily against this binary with the wrong shape -- exactly the silent
                // corruption the guard exists to prevent, and invisible to it. Renumbering to 6 is
                // what keeps the version a name for one structure rather than for two.
                new SchemaMigration(7, "durable tenant-scoped process and traversal inventory",
                        List.of(
                        "ALTER TABLE process_instance ADD COLUMN created_at_epoch_second "
                                + "INTEGER NOT NULL DEFAULT 0",
                        "ALTER TABLE process_instance ADD COLUMN created_at_nano INTEGER NOT NULL DEFAULT 0",
                        "UPDATE process_instance SET created_at_epoch_second = updated_at_epoch_second, "
                                + "created_at_nano = updated_at_nano",
                        "ALTER TABLE process_instance ADD COLUMN lifecycle_generation "
                                + "INTEGER NOT NULL DEFAULT 1",
                        "ALTER TABLE process_instance ADD COLUMN deployment_id TEXT",
                        "ALTER TABLE process_instance ADD COLUMN workload_id TEXT",
                        "ALTER TABLE process_instance ADD COLUMN correlation_id TEXT",
                        "ALTER TABLE process_instance ADD COLUMN retained_until_epoch_second INTEGER",
                        "ALTER TABLE process_instance ADD COLUMN retained_until_nano INTEGER",
                        // The listing walks exactly this axis and nothing else, so it is the index
                        // that matters. tenant_id leads because it leads every key in this schema:
                        // an index that did not would let a scan touch another tenant's pages before
                        // the filter discarded them.
                        "CREATE INDEX idx_process_instance_inventory ON process_instance "
                                + "(tenant_id, created_at_epoch_second DESC, created_at_nano DESC, "
                                + "process_instance_id DESC)",
                        "CREATE INDEX idx_process_instance_status ON process_instance "
                                + "(tenant_id, status)",
                        "CREATE INDEX idx_process_instance_deployment ON process_instance "
                                + "(tenant_id, deployment_id)",
                        // The owner filter resolves a worker to its instances, which is the opposite
                        // direction from the (tenant_id, process_instance_id) primary key.
                        "CREATE INDEX idx_lease_worker ON lease (tenant_id, worker_id)",
                        // Modelled on journal_watermark, and a table rather than a column for the same
                        // reason: the floor must outlive every row it describes. Derived from the
                        // surviving rows it would reset to "nothing was ever forgotten" the moment the
                        // last purged tenant's rows were gone, and a caller reading it would treat an
                        // expired instance as one that never existed.
                        """
                        CREATE TABLE inventory_watermark (
                            tenant_id                   TEXT    NOT NULL PRIMARY KEY,
                            retained_from_epoch_second  INTEGER NOT NULL,
                            retained_from_nano          INTEGER NOT NULL
                        )
                        """)),
                // Tool approvals build on the lifecycle/inventory shape introduced by version 7.
                // Keeping this as a distinct migration makes each user_version name exactly one
                // database structure and preserves the downgrade guard across independently landed
                // features.
                new SchemaMigration(8, "durable scoped tool approvals", List.of(
                """
                CREATE TABLE tool_approval (
                    tenant_id             TEXT    NOT NULL,
                    process_instance_id   TEXT    NOT NULL,
                    approval_id           TEXT    NOT NULL,
                    position              INTEGER NOT NULL,
                    traversal_id          TEXT    NOT NULL,
                    invocation_id         TEXT    NOT NULL,
                    attempt_id            TEXT    NOT NULL,
                    call_id               TEXT    NOT NULL,
                    node_id               TEXT    NOT NULL,
                    tool                  TEXT    NOT NULL,
                    canonical_arguments   BLOB    NOT NULL,
                    arguments_digest      TEXT    NOT NULL,
                    requester_request_id  TEXT    NOT NULL,
                    requester_subject     TEXT    NOT NULL,
                    requester_principal_type TEXT NOT NULL,
                    requester_issuer      TEXT    NOT NULL,
                    graph_version_pin     TEXT    NOT NULL,
                    policy_version        TEXT    NOT NULL,
                    expires_at_epoch_second INTEGER NOT NULL,
                    expires_at_nano         INTEGER NOT NULL,
                    required_roles        TEXT    NOT NULL,
                    required_scopes       TEXT    NOT NULL,
                    requester_may_approve INTEGER NOT NULL CHECK(requester_may_approve IN (0, 1)),
                    continuation_version  INTEGER NOT NULL,
                    continuation          BLOB    NOT NULL,
                    continuation_digest   TEXT    NOT NULL,
                    status                TEXT    NOT NULL,
                    actor                 TEXT    NOT NULL,
                    revision              INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id, approval_id),
                    FOREIGN KEY (tenant_id, process_instance_id)
                        REFERENCES process_instance (tenant_id, process_instance_id) ON DELETE CASCADE
                )
                """,
                "CREATE INDEX tool_approval_pending_expiry ON tool_approval "
                        + "(tenant_id, status, expires_at_epoch_second, expires_at_nano)")),
                new SchemaMigration(9, "first-class durable human tasks", List.of(
                """
                CREATE TABLE human_task (
                    tenant_id               TEXT    NOT NULL,
                    process_instance_id     TEXT    NOT NULL,
                    task_id                 TEXT    NOT NULL,
                    traversal_id            TEXT    NOT NULL,
                    invocation_id           TEXT    NOT NULL,
                    attempt_id              TEXT    NOT NULL,
                    node_id                 TEXT    NOT NULL,
                    correlation_key         TEXT    NOT NULL,
                    deduplication_key       TEXT    NOT NULL,
                    title                   TEXT    NOT NULL,
                    description             TEXT    NOT NULL,
                    response_content_type   TEXT    NOT NULL,
                    response_schema         TEXT    NOT NULL,
                    response_schema_version TEXT    NOT NULL,
                    response_kind           TEXT    NOT NULL,
                    response_max_bytes      INTEGER NOT NULL,
                    required_roles          TEXT    NOT NULL,
                    required_scopes         TEXT    NOT NULL,
                    requester_request_id    TEXT    NOT NULL,
                    requester_subject       TEXT    NOT NULL,
                    requester_principal_type TEXT   NOT NULL,
                    requester_issuer        TEXT    NOT NULL,
                    graph_version_pin       TEXT    NOT NULL,
                    escalate_at_epoch_second INTEGER,
                    escalate_at_nano         INTEGER,
                    expires_at_epoch_second INTEGER NOT NULL,
                    expires_at_nano         INTEGER NOT NULL,
                    resolved_outcome        TEXT    NOT NULL,
                    denied_outcome          TEXT    NOT NULL,
                    expired_outcome         TEXT    NOT NULL,
                    cancelled_outcome       TEXT    NOT NULL,
                    status                  TEXT    NOT NULL,
                    actor                   TEXT    NOT NULL,
                    generation              INTEGER NOT NULL,
                    revision                INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, task_id),
                    UNIQUE (tenant_id, deduplication_key),
                    FOREIGN KEY (tenant_id, process_instance_id)
                        REFERENCES process_instance (tenant_id, process_instance_id) ON DELETE CASCADE
                )
                """,
                "CREATE UNIQUE INDEX human_task_live_correlation ON human_task "
                        + "(tenant_id, correlation_key) WHERE status IN ('WAITING', 'ESCALATED')",
                "CREATE INDEX human_task_inbox ON human_task (tenant_id, task_id)")),
                // A hold is a child of its process instance and dies with it, like every other
                // durable decision record here. It carries its own continuation because a handler
                // by contract carries none, and the continuation is the only reason a held
                // traversal can be continued at all by a process that did not take the hold.
                new SchemaMigration(10, "durable operator holds on traversals", List.of(
                """
                CREATE TABLE execution_pause (
                    tenant_id                TEXT    NOT NULL,
                    process_instance_id      TEXT    NOT NULL,
                    pause_id                 TEXT    NOT NULL,
                    position                 INTEGER NOT NULL,
                    traversal_id             TEXT    NOT NULL,
                    after_invocation_id      TEXT    NOT NULL,
                    node_id                  TEXT    NOT NULL,
                    command_directive        TEXT    NOT NULL,
                    command_name             TEXT    NOT NULL,
                    requester_request_id     TEXT    NOT NULL,
                    requester_subject        TEXT    NOT NULL,
                    requester_principal_type TEXT    NOT NULL,
                    requester_issuer         TEXT    NOT NULL,
                    graph_version_pin        TEXT    NOT NULL,
                    continuation_version     INTEGER NOT NULL,
                    continuation             BLOB    NOT NULL,
                    continuation_digest      TEXT    NOT NULL,
                    status                   TEXT    NOT NULL,
                    actor                    TEXT    NOT NULL,
                    revision                 INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id, pause_id),
                    FOREIGN KEY (tenant_id, process_instance_id)
                        REFERENCES process_instance (tenant_id, process_instance_id) ON DELETE CASCADE
                )
                """,
                // The uniqueness that makes "is this traversal held" a single deterministic answer
                // for a process that has just started and knows only a traversal id. Settled holds
                // are excluded so a traversal resumed and held again resolves to its current hold
                // rather than to its history.
                "CREATE UNIQUE INDEX execution_pause_held_traversal ON execution_pause "
                        + "(tenant_id, traversal_id) WHERE status = 'HELD'",
                "CREATE INDEX execution_pause_by_traversal ON execution_pause "
                        + "(tenant_id, traversal_id)")),
                new SchemaMigration(11, "process-rooted agent authority budgets", List.of(
                """
                CREATE TABLE agent_authority_budget (
                    tenant_id            TEXT NOT NULL,
                    process_instance_id  TEXT NOT NULL,
                    aggregate            BLOB NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id),
                    FOREIGN KEY (tenant_id, process_instance_id)
                        REFERENCES process_instance (tenant_id, process_instance_id) ON DELETE CASCADE
                )
                """)),
                new SchemaMigration(12, "store-global agent authority control epoch", List.of(
                """
                CREATE TABLE agent_authority_control (
                    singleton             INTEGER PRIMARY KEY CHECK(singleton = 1),
                    state                 TEXT    NOT NULL CHECK(state IN ('ACTIVE', 'KILLED')),
                    epoch                 INTEGER NOT NULL CHECK(epoch >= 0),
                    changed_at_epoch_second INTEGER NOT NULL,
                    changed_at_nano         INTEGER NOT NULL
                )
                """,
                "INSERT INTO agent_authority_control "
                        + "(singleton, state, epoch, changed_at_epoch_second, changed_at_nano) "
                        + "VALUES (1, 'ACTIVE', 0, 0, 0)")));
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
