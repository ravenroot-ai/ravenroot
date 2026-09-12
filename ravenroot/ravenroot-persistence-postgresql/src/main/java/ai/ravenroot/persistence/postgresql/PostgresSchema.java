package ai.ravenroot.persistence.postgresql;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;

/**
 * The versioned PostgreSQL schema for every store in this package.
 *
 * <h2>Why the numbering is this adapter's own</h2>
 * <p>The execution store, journal, inventory, definitions, manifests and results all ship as
 * migration 1. The single-host adapter reached the same shape in twenty-two steps because each of
 * those steps was a real upgrade applied to databases that already held production rows; this adapter
 * had no such database anywhere when it was written, so replaying that history would have created
 * tables only to alter them a moment later, in a transaction nobody can observe. The version sequence
 * is therefore this adapter's own and starts at 1. Aligning the two numbers would be worse than
 * unhelpful: it would imply a correspondence nothing maintains, and the downgrade guard compares
 * integers, so a number meaning two different structures is exactly the silent mismatch the guard
 * exists to prevent.</p>
 *
 * <p>Migration 2 adds the deployment registry, and it is a <em>step</em> rather than an edit for the
 * reason that governs every migration after the first: the text of an applied migration is history,
 * and rewriting it would change what a database that already received it was told it received. A
 * database standing at version 1 is an ordinary state to be found in, so the upgrade path from it is
 * exercised rather than assumed.</p>
 *
 * <h2>Table and column names are the single-host adapter's, deliberately</h2>
 * <p>Every table and column here carries the name {@code SqliteSchema} gives it. The two schemas are
 * different shapes — native {@code uuid}, {@code BYTEA}, {@code BOOLEAN} — but an operator, a reviewer
 * or a migration tool reading them side by side must be able to see that {@code process_instance} is
 * the same concept in both, and a rename would make the correspondence a thing to be reconstructed by
 * hand. The types differ where PostgreSQL has a better one; the vocabulary does not.</p>
 *
 * <h3>What changed in the translation, and why each change</h3>
 * <ul>
 *   <li><strong>Instants stay two integer columns.</strong> {@code timestamptz} is
 *   microsecond-resolution and would truncate the nanosecond component of every instant the store
 *   round-trips, and {@link java.time.Instant#MIN} is outside its range entirely. See
 *   {@link StoredInstant}, which also owns the comparison fragments. Seconds are {@code BIGINT} and
 *   nanos {@code INTEGER}, because a nano is bounded by {@code 999999999} and a second is not.</li>
 *   <li><strong>Identifiers that are UUIDs become native {@code uuid}.</strong> The column then cannot
 *   hold a malformed value at all, and it is half the width of the text form in every index. Ordering
 *   is preserved: PostgreSQL compares {@code uuid} bytewise, and a canonical lowercase UUID string
 *   compares character-by-character in exactly that order, so a cursor minted against the text-keyed
 *   adapter selects the same rows here. That equivalence is load-bearing for the inventory cursor and
 *   is <em>not</em> Java's {@link java.util.UUID#compareTo}, which is signed.</li>
 *   <li><strong>{@code tenant_id}, {@code graph_version_pin}, {@code worker_id}, {@code node_id},
 *   {@code deployment_id}, {@code workload_id}, {@code correlation_id}, {@code content_id},
 *   {@code graph_id} and {@code version_id} stay {@code TEXT}.</strong> None of them is a UUID: they
 *   are caller-supplied opaque identities, and typing them as {@code uuid} would refuse perfectly
 *   legal values the port accepts.</li>
 *   <li><strong>{@code BLOB} becomes {@code BYTEA}</strong>, and a length check over one becomes
 *   {@code octet_length}. A digest stored as text keeps {@code length}.</li>
 *   <li><strong>{@code INTEGER ... CHECK (x IN (0, 1))} becomes {@code BOOLEAN}.</strong> The check
 *   existed to give SQLite the type it lacks; reproducing it here would keep the workaround and throw
 *   away the type.</li>
 *   <li><strong>Partial unique indexes carry over unchanged.</strong> PostgreSQL supports
 *   {@code WHERE} on an index, so the rule that a correlation key is unique only among handlers that
 *   are not terminal stays where it belongs — in the database, decided by the write itself, rather
 *   than in a read-then-write that is a race under concurrency.</li>
 *   <li><strong>Defaults that existed only to backfill pre-existing rows are dropped.</strong> A
 *   default whose entire purpose was to give a value to rows written before its column existed has no
 *   such rows here, and leaving it would invite a writer to omit a column that must always be
 *   written — turning a missing value into a silent historical constant rather than an error.</li>
 * </ul>
 *
 * <h2>The continuation tables shipped ahead of the code that writes them</h2>
 * <p>{@code execution_handler}, {@code tool_approval}, {@code human_task}, {@code execution_pause},
 * {@code agent_authority_budget} and {@code agent_authority_control} were created in this migration
 * before {@link ai.ravenroot.api.persistence.StoreCapability#DURABLE_HANDLERS} and its neighbours
 * were declared and before anything wrote to them. That is what made implementing them
 * <em>additive</em> — new code against an unchanged schema — instead of a migration applied to
 * databases that were by then holding executions. An empty table costs nothing; a schema change to a
 * live multi-host deployment is a rolling-upgrade problem. All six are now written and read, and no
 * migration was needed to start doing so, which is the outcome the decision was made for.</p>
 *
 * <p>The deployment-registry tables were deliberately absent from that migration, because they are a
 * separate aggregate and creating them speculatively would have fixed their shape from the outside
 * before anything here read or wrote one. They arrive in migration 2 together with
 * {@link PostgresDeploymentRegistry}, which is the code that decides what shape they need.</p>
 */
final class PostgresSchema {

    private PostgresSchema() {
    }

    /**
     * Applies every migration this build knows that the database has not already installed.
     *
     * @return the version the database is at when this returns
     */
    static int migrate(Connection connection, Clock clock) throws SQLException {
        return SchemaRunner.migrate(connection, migrations(), clock);
    }

    /** The highest schema version this build can open. */
    static int currentVersion() {
        return migrations().getLast().version();
    }

    /**
     * The complete schema.
     *
     * <h3>Normalized, not a serialized blob</h3>
     * <p>The aggregate is stored as rows — one per traversal, invocation, causal parent edge and
     * attempt — for the reason the single-host adapter records: a blob column would make the on-disk
     * format an encoding of a Java type, nothing in it would be queryable, and
     * {@code claimPendingWork} would have to deserialize every instance of the tenant on every poll.
     * Normalized rows are also what makes
     * {@link ai.ravenroot.api.persistence.ExecutionStoreFailure.Corrupted} detectable at all, because
     * reconstruction then runs through the aggregate's own canonical constructors.</p>
     *
     * <h3>Ordering is data</h3>
     * <p>{@code traversal.position} and {@code invocation.position} exist because the aggregate keeps
     * both in insertion order and states its cross-traversal parent rule in terms of an invocation's
     * position within its traversal. A schema that let the planner choose a row order would
     * reconstruct an aggregate the domain rejects.</p>
     *
     * <h3>The fencing token is on the instance, not on the lease</h3>
     * <p>A fencing token must survive the lease that issued it: {@code ack} and {@code apply} compare a
     * presented token against the <em>current</em> token whether or not a lease is held. Keeping the
     * counter on {@code process_instance} means releasing a lease deletes the lease row without
     * touching the token. A token column on {@code lease} would vanish with it and let the counter
     * restart from a value a stale holder could still replay — and here the stale holder is on another
     * host, so nothing local would notice.</p>
     */
    static List<SchemaMigration> migrations() {
        return List.of(new SchemaMigration(1, "shared execution store, journal, inventory and results",
                List.of(
                // ------------------------------------------------------------------ aggregate
                """
                CREATE TABLE process_instance (
                    tenant_id           TEXT    NOT NULL,
                    process_instance_id UUID    NOT NULL,
                    status              TEXT    NOT NULL,
                    termination_reason  TEXT,
                    graph_version_pin   TEXT    NOT NULL,
                    revision            BIGINT  NOT NULL,
                    fencing_token       BIGINT  NOT NULL,
                    lifecycle_generation BIGINT NOT NULL,
                    deployment_id       TEXT,
                    workload_id         TEXT,
                    correlation_id      TEXT,
                    created_at_epoch_second BIGINT  NOT NULL,
                    created_at_nano         INTEGER NOT NULL,
                    updated_at_epoch_second BIGINT  NOT NULL,
                    updated_at_nano         INTEGER NOT NULL,
                    retained_until_epoch_second BIGINT,
                    retained_until_nano         INTEGER,
                    PRIMARY KEY (tenant_id, process_instance_id)
                )
                """,
                """
                CREATE TABLE traversal (
                    tenant_id           TEXT    NOT NULL,
                    process_instance_id UUID    NOT NULL,
                    traversal_id        UUID    NOT NULL,
                    position            INTEGER NOT NULL,
                    ingress_node_id     TEXT    NOT NULL,
                    status              TEXT    NOT NULL,
                    termination_reason  TEXT,
                    PRIMARY KEY (tenant_id, process_instance_id, traversal_id),
                    FOREIGN KEY (tenant_id, process_instance_id)
                        REFERENCES process_instance (tenant_id, process_instance_id) ON DELETE CASCADE
                )
                """,
                """
                CREATE TABLE invocation (
                    tenant_id           TEXT    NOT NULL,
                    process_instance_id UUID    NOT NULL,
                    traversal_id        UUID    NOT NULL,
                    invocation_id       UUID    NOT NULL,
                    position            INTEGER NOT NULL,
                    node_id             TEXT    NOT NULL,
                    status              TEXT    NOT NULL,
                    node_command        TEXT    NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id, invocation_id),
                    FOREIGN KEY (tenant_id, process_instance_id, traversal_id)
                        REFERENCES traversal (tenant_id, process_instance_id, traversal_id) ON DELETE CASCADE
                )
                """,
                """
                CREATE TABLE invocation_parent (
                    tenant_id            TEXT NOT NULL,
                    process_instance_id  UUID NOT NULL,
                    invocation_id        UUID NOT NULL,
                    parent_invocation_id UUID NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id, invocation_id, parent_invocation_id),
                    FOREIGN KEY (tenant_id, process_instance_id, invocation_id)
                        REFERENCES invocation (tenant_id, process_instance_id, invocation_id) ON DELETE CASCADE
                )
                """,
                """
                CREATE TABLE attempt (
                    tenant_id           TEXT    NOT NULL,
                    process_instance_id UUID    NOT NULL,
                    invocation_id       UUID    NOT NULL,
                    attempt_id          UUID    NOT NULL,
                    ordinal             INTEGER NOT NULL,
                    status              TEXT    NOT NULL,
                    completion          TEXT,
                    park_cause          TEXT,
                    withheld_through_delivery INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id, attempt_id),
                    FOREIGN KEY (tenant_id, process_instance_id, invocation_id)
                        REFERENCES invocation (tenant_id, process_instance_id, invocation_id) ON DELETE CASCADE
                )
                """,
                // The claim loop asks one question of this table -- which of this instance's attempts
                // are still dispatchable -- and asks it on every poll of every worker.
                "CREATE INDEX idx_attempt_scheduled ON attempt (tenant_id, process_instance_id, status)",

                // ------------------------------------------------------------------ timers and work
                """
                CREATE TABLE timer (
                    tenant_id            TEXT    NOT NULL,
                    process_instance_id  UUID    NOT NULL,
                    timer_id             UUID    NOT NULL,
                    traversal_id         UUID,
                    invocation_id        UUID,
                    payload_content_type TEXT    NOT NULL,
                    payload_bytes        BYTEA   NOT NULL,
                    due_at_epoch_second  BIGINT  NOT NULL,
                    due_at_nano          INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id, timer_id),
                    FOREIGN KEY (tenant_id, process_instance_id)
                        REFERENCES process_instance (tenant_id, process_instance_id) ON DELETE CASCADE
                )
                """,
                // The composite order matches StoredInstant's row-value comparison exactly, which is
                // the reason that comparison is written as a row constructor rather than as an
                // unrolled disjunction: the planner can satisfy (second, nano) <= (?, ?) from this
                // index and usually cannot satisfy the disjunction from anything.
                "CREATE INDEX idx_timer_due ON timer (tenant_id, due_at_epoch_second, due_at_nano)",
                """
                CREATE TABLE lease (
                    tenant_id                TEXT    NOT NULL,
                    process_instance_id      UUID    NOT NULL,
                    worker_id                TEXT    NOT NULL,
                    claimed_at_epoch_second  BIGINT  NOT NULL,
                    claimed_at_nano          INTEGER NOT NULL,
                    expires_at_epoch_second  BIGINT  NOT NULL,
                    expires_at_nano          INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id),
                    FOREIGN KEY (tenant_id, process_instance_id)
                        REFERENCES process_instance (tenant_id, process_instance_id) ON DELETE CASCADE
                )
                """,
                "CREATE INDEX idx_lease_expiry ON lease (tenant_id, expires_at_epoch_second, expires_at_nano)",
                // The owner filter resolves a worker to its instances, which is the opposite direction
                // from the primary key.
                "CREATE INDEX idx_lease_worker ON lease (tenant_id, worker_id)",
                """
                CREATE TABLE work_claim (
                    tenant_id           TEXT    NOT NULL,
                    process_instance_id UUID    NOT NULL,
                    work_item_id        UUID    NOT NULL,
                    delivery_attempt    INTEGER NOT NULL,
                    visible_again_at_epoch_second BIGINT  NOT NULL,
                    visible_again_at_nano         INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id, work_item_id),
                    FOREIGN KEY (tenant_id, process_instance_id)
                        REFERENCES process_instance (tenant_id, process_instance_id) ON DELETE CASCADE
                )
                """,
                """
                CREATE TABLE work_acknowledgement (
                    tenant_id           TEXT NOT NULL,
                    process_instance_id UUID NOT NULL,
                    work_item_id        UUID NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id, work_item_id),
                    FOREIGN KEY (tenant_id, process_instance_id)
                        REFERENCES process_instance (tenant_id, process_instance_id) ON DELETE CASCADE
                )
                """,

                // ------------------------------------------------------------------ idempotency
                """
                CREATE TABLE idempotency_record (
                    tenant_id                        TEXT    NOT NULL,
                    idempotency_key                  TEXT    NOT NULL,
                    request_fingerprint_content_type TEXT    NOT NULL,
                    request_fingerprint_bytes        BYTEA   NOT NULL,
                    outcome_ref_content_type         TEXT    NOT NULL,
                    outcome_ref_bytes                BYTEA   NOT NULL,
                    recorded_at_revision             BIGINT  NOT NULL,
                    expires_at_epoch_second          BIGINT  NOT NULL,
                    expires_at_nano                  INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, idempotency_key)
                )
                """,
                "CREATE INDEX idx_idempotency_expiry ON idempotency_record "
                        + "(tenant_id, expires_at_epoch_second, expires_at_nano)",
                // The watermark must outlive every record it describes. Derived from the surviving
                // rows it would reset to "nothing was ever forgotten" the moment the last purged
                // record was gone, and a caller reading it would treat a purged key as one that was
                // never recorded -- which is the silent re-execution the whole mechanism exists to
                // prevent.
                """
                CREATE TABLE idempotency_watermark (
                    tenant_id                     TEXT    NOT NULL PRIMARY KEY,
                    forgotten_before_epoch_second BIGINT  NOT NULL,
                    forgotten_before_nano         INTEGER NOT NULL
                )
                """,

                // ------------------------------------------------------------------ journal
                """
                CREATE TABLE event_journal (
                    tenant_id             TEXT    NOT NULL,
                    journal_offset        BIGINT  NOT NULL,
                    stream_sequence       BIGINT  NOT NULL,
                    process_instance_id   UUID    NOT NULL,
                    committed_at_revision BIGINT  NOT NULL,
                    envelope_version      INTEGER NOT NULL,
                    event_id              UUID    NOT NULL,
                    event_type            TEXT    NOT NULL,
                    traversal_id          UUID    NOT NULL,
                    invocation_id         UUID,
                    attempt_id            UUID,
                    causation_id          UUID,
                    correlation_id        TEXT    NOT NULL,
                    graph_version         TEXT    NOT NULL,
                    occurred_at_epoch_second BIGINT  NOT NULL,
                    occurred_at_nano         INTEGER NOT NULL,
                    payload_content_type  TEXT    NOT NULL,
                    payload_bytes         BYTEA   NOT NULL,
                    digest                BYTEA   NOT NULL CHECK (octet_length(digest) = 32),
                    recorded_at_epoch_second BIGINT  NOT NULL,
                    recorded_at_nano         INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, journal_offset)
                )
                """,
                // The publisher walks this axis and nothing else, so it is the index that matters. It
                // is UNIQUE because a repeated stream sequence within one instance would let a
                // consumer that deduplicates on it drop a genuinely distinct event.
                "CREATE UNIQUE INDEX idx_journal_stream ON event_journal "
                        + "(tenant_id, process_instance_id, stream_sequence)",
                """
                CREATE TABLE outbox_cursor (
                    tenant_id         TEXT   NOT NULL,
                    destination       TEXT   NOT NULL,
                    delivered_through BIGINT NOT NULL,
                    PRIMARY KEY (tenant_id, destination)
                )
                """,
                """
                CREATE TABLE inbox_record (
                    tenant_id               TEXT    NOT NULL,
                    consumer_id             TEXT    NOT NULL,
                    event_id                UUID    NOT NULL,
                    expires_at_epoch_second BIGINT  NOT NULL,
                    expires_at_nano         INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, consumer_id, event_id)
                )
                """,
                // The counters must outlive every record they numbered. A journal compacted to empty
                // that recomputed next_offset from its rows would reissue offsets a destination cursor
                // has already passed, and those events would never be delivered to it -- silently,
                // because nothing is missing from the publisher's point of view.
                """
                CREATE TABLE journal_watermark (
                    tenant_id     TEXT   NOT NULL PRIMARY KEY,
                    next_offset   BIGINT NOT NULL,
                    retained_from BIGINT NOT NULL
                )
                """,
                """
                CREATE TABLE journal_stream_sequence (
                    tenant_id           TEXT   NOT NULL,
                    process_instance_id UUID   NOT NULL,
                    next_sequence       BIGINT NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id)
                )
                """,

                // ------------------------------------------------------------------ inventory
                // The listing walks exactly this axis and nothing else. tenant_id leads because it
                // leads every key in this schema: an index that did not would let a scan touch another
                // tenant's pages before the filter discarded them.
                "CREATE INDEX idx_process_instance_inventory ON process_instance "
                        + "(tenant_id, created_at_epoch_second DESC, created_at_nano DESC, "
                        + "process_instance_id DESC)",
                "CREATE INDEX idx_process_instance_status ON process_instance (tenant_id, status)",
                "CREATE INDEX idx_process_instance_deployment ON process_instance "
                        + "(tenant_id, deployment_id)",
                "CREATE INDEX idx_process_instance_workload ON process_instance (tenant_id, workload_id)",
                // Retention asks, for every candidate definition, whether any instance of the tenant
                // still pins it. Without this index that question is a tenant-wide scan per candidate.
                "CREATE INDEX idx_process_instance_pin ON process_instance (tenant_id, graph_version_pin)",
                // Modelled on journal_watermark, and a table rather than a column for the same reason:
                // the floor must outlive every row it describes.
                """
                CREATE TABLE inventory_watermark (
                    tenant_id                  TEXT    NOT NULL PRIMARY KEY,
                    retained_from_epoch_second BIGINT  NOT NULL,
                    retained_from_nano         INTEGER NOT NULL
                )
                """,

                // ------------------------------------------------------------------ graph definitions
                // Co-located with the executions that pin them. That is not a convenience: it is what
                // puts a definition and the execution that needs it under one transaction, and what
                // lets retention decide reachability from process_instance in the same transaction
                // that removes a definition rather than across two stores that can disagree.
                """
                CREATE TABLE graph_definition (
                    tenant_id        TEXT    NOT NULL,
                    content_id       TEXT    NOT NULL,
                    format_version   INTEGER NOT NULL,
                    definition_bytes BYTEA   NOT NULL,
                    digest           BYTEA   NOT NULL CHECK (octet_length(digest) = 32),
                    byte_length      BIGINT  NOT NULL,
                    first_graph_id   TEXT    NOT NULL,
                    first_version_id TEXT    NOT NULL,
                    stored_at_epoch_second BIGINT  NOT NULL,
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
                    bound_at_epoch_second BIGINT  NOT NULL,
                    bound_at_nano         INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, graph_id, version_id),
                    FOREIGN KEY (tenant_id, content_id)
                        REFERENCES graph_definition (tenant_id, content_id) ON DELETE CASCADE
                )
                """,
                "CREATE INDEX idx_graph_definition_binding_content "
                        + "ON graph_definition_binding (tenant_id, content_id)",

                // ------------------------------------------------------------------ manifests
                // There is deliberately no foreign key to process_instance: the manifest is committed
                // BEFORE the acceptance that references it, so the parent row does not exist yet and a
                // constraint would make the required ordering inexpressible. The relationship is
                // enforced at removal instead, by asking whether the instance exists inside the
                // deleting transaction.
                """
                CREATE TABLE execution_manifest (
                    tenant_id           TEXT    NOT NULL,
                    process_instance_id UUID    NOT NULL,
                    format_version      INTEGER NOT NULL,
                    digest              TEXT    NOT NULL CHECK (length(digest) = 64),
                    graph_content_id    TEXT    NOT NULL CHECK (length(graph_content_id) = 64),
                    graph_id            TEXT    NOT NULL,
                    version_id          TEXT    NOT NULL,
                    graph_schema_version      INTEGER NOT NULL,
                    definition_format_version INTEGER NOT NULL,
                    execution_policy       TEXT NOT NULL,
                    unknown_behavior_mode  TEXT NOT NULL,
                    engine_digest          TEXT NOT NULL CHECK (length(engine_digest) = 64),
                    store_digest           TEXT NOT NULL CHECK (length(store_digest) = 64),
                    limits_digest          TEXT NOT NULL CHECK (length(limits_digest) = 64),
                    program_runtime_digest TEXT NOT NULL CHECK (length(program_runtime_digest) = 64),
                    pinned_at_epoch_second    BIGINT  NOT NULL,
                    pinned_at_nano            INTEGER NOT NULL,
                    committed_at_epoch_second BIGINT  NOT NULL,
                    committed_at_nano         INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id)
                )
                """,
                """
                CREATE TABLE execution_manifest_package (
                    tenant_id           TEXT NOT NULL,
                    process_instance_id UUID NOT NULL,
                    package_id          TEXT NOT NULL,
                    identity_digest     TEXT NOT NULL CHECK (length(identity_digest) = 64),
                    PRIMARY KEY (tenant_id, process_instance_id, package_id),
                    FOREIGN KEY (tenant_id, process_instance_id)
                        REFERENCES execution_manifest (tenant_id, process_instance_id) ON DELETE CASCADE
                )
                """,

                // ------------------------------------------------------------------ handlers
                // A separate table rather than columns on `invocation`, because a handler outlives the
                // invocation's own lifecycle: it is retained after the wait ends, so a duplicate or
                // late trigger can still be refused against it and an operator can still see who
                // resolved a human task.
                //
                // Both uniqueness rules are enforced by the database rather than by a read-then-write
                // in Java, because a check performed outside the write's own transaction is a race
                // under concurrency and this one decides which of two concurrent triggers wins. Here
                // the two triggers are usually on different hosts, so there is no process-local lock
                // that could stand in for the constraint.
                """
                CREATE TABLE execution_handler (
                    tenant_id            TEXT    NOT NULL,
                    process_instance_id  UUID    NOT NULL,
                    handler_id           UUID    NOT NULL,
                    position             INTEGER NOT NULL,
                    name                 TEXT    NOT NULL,
                    traversal_id         UUID    NOT NULL,
                    invocation_id        UUID    NOT NULL,
                    correlation_key      TEXT    NOT NULL,
                    deduplication_key    TEXT    NOT NULL,
                    schema_content_type  TEXT    NOT NULL,
                    schema_ref           TEXT    NOT NULL,
                    schema_max_bytes     INTEGER NOT NULL,
                    required_roles       TEXT    NOT NULL,
                    required_scopes      TEXT    NOT NULL,
                    status               TEXT    NOT NULL,
                    resume_traversal_id  UUID,
                    actor                TEXT    NOT NULL,
                    outcome_content_type TEXT    NOT NULL,
                    outcome_bytes        BYTEA   NOT NULL,
                    revision             BIGINT  NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id, handler_id),
                    FOREIGN KEY (tenant_id, process_instance_id)
                        REFERENCES process_instance (tenant_id, process_instance_id) ON DELETE CASCADE
                )
                """,
                // Unique only among handlers that are not terminal: a trigger presenting a business key
                // must resolve to exactly one live handler, while a key whose wait is over becomes
                // reusable.
                """
                CREATE UNIQUE INDEX execution_handler_live_correlation
                    ON execution_handler (tenant_id, name, correlation_key)
                    WHERE status IN ('WAITING', 'ESCALATED')
                """,
                "CREATE UNIQUE INDEX execution_handler_deduplication "
                        + "ON execution_handler (tenant_id, deduplication_key)",
                "CREATE INDEX execution_handler_claimable ON execution_handler (tenant_id, status)",

                // ------------------------------------------------------------------ tool approvals
                """
                CREATE TABLE tool_approval (
                    tenant_id             TEXT    NOT NULL,
                    process_instance_id   UUID    NOT NULL,
                    approval_id           UUID    NOT NULL,
                    position              INTEGER NOT NULL,
                    traversal_id          UUID    NOT NULL,
                    invocation_id         UUID    NOT NULL,
                    attempt_id            UUID    NOT NULL,
                    call_id               UUID    NOT NULL,
                    node_id               TEXT    NOT NULL,
                    tool                  TEXT    NOT NULL,
                    canonical_arguments   BYTEA   NOT NULL,
                    arguments_digest      TEXT    NOT NULL,
                    requester_request_id  TEXT    NOT NULL,
                    requester_subject     TEXT    NOT NULL,
                    requester_principal_type TEXT NOT NULL,
                    requester_issuer      TEXT    NOT NULL,
                    graph_version_pin     TEXT    NOT NULL,
                    policy_version        TEXT    NOT NULL,
                    expires_at_epoch_second BIGINT  NOT NULL,
                    expires_at_nano         INTEGER NOT NULL,
                    required_roles        TEXT    NOT NULL,
                    required_scopes       TEXT    NOT NULL,
                    requester_may_approve BOOLEAN NOT NULL,
                    continuation_version  INTEGER NOT NULL,
                    continuation          BYTEA   NOT NULL,
                    continuation_digest   TEXT    NOT NULL,
                    status                TEXT    NOT NULL,
                    actor                 TEXT    NOT NULL,
                    revision              BIGINT  NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id, approval_id),
                    FOREIGN KEY (tenant_id, process_instance_id)
                        REFERENCES process_instance (tenant_id, process_instance_id) ON DELETE CASCADE
                )
                """,
                "CREATE INDEX tool_approval_pending_expiry ON tool_approval "
                        + "(tenant_id, status, expires_at_epoch_second, expires_at_nano)",

                // ------------------------------------------------------------------ human tasks
                """
                CREATE TABLE human_task (
                    tenant_id               TEXT    NOT NULL,
                    process_instance_id     UUID    NOT NULL,
                    task_id                 UUID    NOT NULL,
                    traversal_id            UUID    NOT NULL,
                    invocation_id           UUID    NOT NULL,
                    attempt_id              UUID    NOT NULL,
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
                    escalate_at_epoch_second BIGINT,
                    escalate_at_nano         INTEGER,
                    expires_at_epoch_second BIGINT  NOT NULL,
                    expires_at_nano         INTEGER NOT NULL,
                    resolved_outcome        TEXT    NOT NULL,
                    denied_outcome          TEXT    NOT NULL,
                    expired_outcome         TEXT    NOT NULL,
                    cancelled_outcome       TEXT    NOT NULL,
                    status                  TEXT    NOT NULL,
                    actor                   TEXT    NOT NULL,
                    generation              BIGINT  NOT NULL,
                    revision                BIGINT  NOT NULL,
                    continuation_version    INTEGER NOT NULL,
                    continuation            BYTEA   NOT NULL,
                    continuation_digest     TEXT    NOT NULL,
                    decision_body_max_bytes     INTEGER NOT NULL,
                    response_max_depth          INTEGER NOT NULL,
                    response_max_collection_size INTEGER NOT NULL,
                    response_max_value_count    INTEGER NOT NULL,
                    response_max_text_length    INTEGER NOT NULL,
                    response_max_key_length     INTEGER NOT NULL,
                    write_attempts              INTEGER NOT NULL,
                    confirmation_version        INTEGER NOT NULL,
                    confirmation_prompt         TEXT    NOT NULL,
                    confirmation_comment_requirement TEXT NOT NULL,
                    confirmation_actions        TEXT    NOT NULL,
                    confirmation_resolve_label  TEXT    NOT NULL,
                    confirmation_deny_label     TEXT    NOT NULL,
                    confirmation_cancel_label   TEXT    NOT NULL,
                    confirmation_max_prompt_bytes       INTEGER NOT NULL,
                    confirmation_max_action_label_bytes INTEGER NOT NULL,
                    confirmation_max_comment_bytes      INTEGER NOT NULL,
                    decision_comment        TEXT    NOT NULL,
                    created_at_epoch_second BIGINT  NOT NULL,
                    created_at_nano         INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, task_id),
                    UNIQUE (tenant_id, deduplication_key),
                    FOREIGN KEY (tenant_id, process_instance_id)
                        REFERENCES process_instance (tenant_id, process_instance_id) ON DELETE CASCADE
                )
                """,
                "CREATE UNIQUE INDEX human_task_live_correlation ON human_task "
                        + "(tenant_id, correlation_key) WHERE status IN ('WAITING', 'ESCALATED')",
                "CREATE INDEX human_task_inbox ON human_task (tenant_id, task_id)",
                "CREATE INDEX human_task_created_order ON human_task "
                        + "(tenant_id, created_at_epoch_second, created_at_nano, task_id)",
                // Attention is derived directly from durable task and process rows. The process-key
                // index lets both process scope and the deployment join reach only the owning tasks;
                // the context-order index serves graph-wide node counts and stable page ordering.
                "CREATE INDEX human_task_process_attention ON human_task "
                        + "(tenant_id, process_instance_id, graph_version_pin, "
                        + "created_at_epoch_second, created_at_nano, task_id, status)",
                "CREATE INDEX human_task_context_attention ON human_task "
                        + "(tenant_id, graph_version_pin, created_at_epoch_second, "
                        + "created_at_nano, task_id, status)",

                // ------------------------------------------------------------------ operator holds
                // A hold is a child of its process instance and dies with it, like every other durable
                // decision record here. It carries its own continuation because a handler by contract
                // carries none, and the continuation is the only reason a held traversal can be
                // continued at all by a process that did not take the hold -- which, here, is the
                // ordinary case rather than the exception.
                """
                CREATE TABLE execution_pause (
                    tenant_id                TEXT    NOT NULL,
                    process_instance_id      UUID    NOT NULL,
                    pause_id                 UUID    NOT NULL,
                    position                 INTEGER NOT NULL,
                    traversal_id             UUID    NOT NULL,
                    after_invocation_id      UUID    NOT NULL,
                    node_id                  TEXT    NOT NULL,
                    command_directive        TEXT    NOT NULL,
                    command_name             TEXT    NOT NULL,
                    requester_request_id     TEXT    NOT NULL,
                    requester_subject        TEXT    NOT NULL,
                    requester_principal_type TEXT    NOT NULL,
                    requester_issuer         TEXT    NOT NULL,
                    graph_version_pin        TEXT    NOT NULL,
                    continuation_version     INTEGER NOT NULL,
                    continuation             BYTEA   NOT NULL,
                    continuation_digest      TEXT    NOT NULL,
                    status                   TEXT    NOT NULL,
                    actor                    TEXT    NOT NULL,
                    revision                 BIGINT  NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id, pause_id),
                    FOREIGN KEY (tenant_id, process_instance_id)
                        REFERENCES process_instance (tenant_id, process_instance_id) ON DELETE CASCADE
                )
                """,
                // The uniqueness that makes "is this traversal held" a single deterministic answer for
                // a process that has just started and knows only a traversal id. Settled holds are
                // excluded so a traversal resumed and held again resolves to its current hold rather
                // than to its history.
                "CREATE UNIQUE INDEX execution_pause_held_traversal ON execution_pause "
                        + "(tenant_id, traversal_id) WHERE status = 'HELD'",
                "CREATE INDEX execution_pause_by_traversal ON execution_pause (tenant_id, traversal_id)",

                // ------------------------------------------------------------------ agent authority
                """
                CREATE TABLE agent_authority_budget (
                    tenant_id           TEXT  NOT NULL,
                    process_instance_id UUID  NOT NULL,
                    aggregate           BYTEA NOT NULL,
                    PRIMARY KEY (tenant_id, process_instance_id),
                    FOREIGN KEY (tenant_id, process_instance_id)
                        REFERENCES process_instance (tenant_id, process_instance_id) ON DELETE CASCADE
                )
                """,
                // One row, enforced by the key rather than by a convention every writer has to keep.
                // The boolean discriminator is the idiom SchemaRunner's own version table already uses:
                // a column that can hold exactly one value, checked, is a shorter way of saying
                // "singleton" than an integer plus an equality check.
                """
                CREATE TABLE agent_authority_control (
                    singleton             BOOLEAN NOT NULL PRIMARY KEY DEFAULT TRUE CHECK (singleton),
                    state                 TEXT    NOT NULL CHECK (state IN ('ACTIVE', 'KILLED')),
                    epoch                 BIGINT  NOT NULL CHECK (epoch >= 0),
                    team_active_released  BIGINT  NOT NULL CHECK (team_active_released >= 0),
                    changed_at_epoch_second BIGINT  NOT NULL,
                    changed_at_nano         INTEGER NOT NULL
                )
                """,
                // Seeded rather than created lazily, so every reader finds a row and none of them has
                // to decide what an absent one would have meant.
                "INSERT INTO agent_authority_control "
                        + "(singleton, state, epoch, team_active_released, changed_at_epoch_second, "
                        + "changed_at_nano) VALUES (TRUE, 'ACTIVE', 0, 0, 0, 0)",

                // ------------------------------------------------------------------ results
                // The canonical result of a terminal execution, so a client can read what a run
                // produced after the process that ran it is gone. Everything above keeps lifecycle
                // state; none of it keeps the answer, which lived in one JVM's memory and died with it.
                //
                // The foreign key is ON DELETE CASCADE and is load-bearing: a result names the instance
                // and the traversal it belongs to, so a result outliving its instance would name a row
                // the inventory can no longer describe. PostgresStoreConfig refuses a result window
                // longer than terminalRetention, so the cascade can never cut a declared window short
                // either.
                """
                CREATE TABLE execution_result (
                    tenant_id           TEXT    NOT NULL,
                    process_instance_id UUID    NOT NULL,
                    traversal_id        UUID    NOT NULL,
                    graph_version_pin   TEXT    NOT NULL,
                    status              TEXT    NOT NULL,
                    termination_reason  TEXT,
                    started_at_epoch_second   BIGINT  NOT NULL,
                    started_at_nano           INTEGER NOT NULL,
                    ended_at_epoch_second     BIGINT  NOT NULL,
                    ended_at_nano             INTEGER NOT NULL,
                    recorded_at_epoch_second  BIGINT  NOT NULL,
                    recorded_at_nano          INTEGER NOT NULL,
                    retained_until_epoch_second BIGINT  NOT NULL,
                    retained_until_nano         INTEGER NOT NULL,
                    payload_state        TEXT    NOT NULL,
                    payload_redacted     BOOLEAN NOT NULL,
                    payload_truncated    BOOLEAN NOT NULL,
                    payload_bytes        INTEGER NOT NULL CHECK (payload_bytes >= 0),
                    payload_content_type TEXT,
                    payload              BYTEA,
                    failure_classifier   TEXT,
                    fingerprint          TEXT    NOT NULL CHECK (length(fingerprint) = 64),
                    PRIMARY KEY (tenant_id, traversal_id),
                    FOREIGN KEY (tenant_id, process_instance_id)
                        REFERENCES process_instance (tenant_id, process_instance_id) ON DELETE CASCADE
                )
                """,
                // Normalized, like every other table here. `position` is stored because the order is
                // part of the validated value -- the result's fingerprint is computed over it -- so a
                // schema that let the planner choose a row order would read back a record whose digest
                // no longer matches what was written.
                """
                CREATE TABLE execution_result_node (
                    tenant_id    TEXT    NOT NULL,
                    traversal_id UUID    NOT NULL,
                    node_set     TEXT    NOT NULL,
                    position     INTEGER NOT NULL,
                    value        TEXT    NOT NULL,
                    PRIMARY KEY (tenant_id, traversal_id, node_set, position),
                    FOREIGN KEY (tenant_id, traversal_id)
                        REFERENCES execution_result (tenant_id, traversal_id) ON DELETE CASCADE
                )
                """,
                """
                CREATE TABLE execution_result_watermark (
                    tenant_id                  TEXT    NOT NULL PRIMARY KEY,
                    retained_from_epoch_second BIGINT  NOT NULL,
                    retained_from_nano         INTEGER NOT NULL
                )
                """,
                // The purge walks exactly this axis: one tenant's rows in deadline order.
                "CREATE INDEX execution_result_retention ON execution_result "
                        + "(tenant_id, retained_until_epoch_second, retained_until_nano)",
                // The primary key leads with traversal_id, so resolving an instance to its results is
                // the opposite direction and would otherwise scan.
                "CREATE INDEX execution_result_instance ON execution_result "
                        + "(tenant_id, process_instance_id)")),

                // ------------------------------------------------------------------ deployments
                // Four tables for the deployment registry, purely additive: nothing migration 1
                // created is touched, which is what makes this the module's first genuine upgrade
                // path rather than a rewrite. `deployment` carries revision, fence and generation on
                // the same row because they are three disjoint monotone axes of one aggregate, and
                // there is no `desired_generation` column because the aggregate's own `generation`
                // always is the generation the current desired state was stamped with; a second
                // column could only ever agree with the first or be a bug. The fencing token lives on
                // `deployment.fence` and not on `deployment_lease`, for the identical reason
                // `process_instance.fencing_token` lives on the instance and not on `lease`: a
                // release deletes the lease row without resetting the counter, and a token column on
                // the lease row would vanish with it and let a fence restart from a value a stale
                // holder could replay -- and here that stale holder is on another host, so nothing
                // local would notice.
                //
                // There is deliberately no foreign key in either direction between these tables and
                // the execution-store tables above. The deployment fence and the execution store's
                // own fencing token govern disjoint concerns, and a constraint would wire together
                // two aggregates the ports keep independent.
                new SchemaMigration(2,
                        "durable deployment registry aggregate, versions, command ledger and lease",
                        List.of(
                """
                CREATE TABLE deployment (
                    tenant_id                   TEXT    NOT NULL,
                    deployment_id               TEXT    NOT NULL,
                    latest_version              BIGINT  NOT NULL,
                    generation                  BIGINT  NOT NULL,
                    revision                    BIGINT  NOT NULL,
                    fence                       BIGINT  NOT NULL,
                    desired_kind                TEXT    NOT NULL,
                    desired_version             BIGINT,
                    update_strategy             TEXT,
                    observed_kind               TEXT    NOT NULL,
                    observed_version            BIGINT,
                    observed_generation         BIGINT  NOT NULL,
                    observed_at_epoch_second    BIGINT  NOT NULL,
                    observed_at_nano            INTEGER NOT NULL,
                    failure_code                TEXT,
                    failure_message             TEXT,
                    failure_at_epoch_second     BIGINT,
                    failure_at_nano             INTEGER,
                    tombstone_reason            TEXT,
                    tombstone_at_epoch_second   BIGINT,
                    tombstone_at_nano           INTEGER,
                    created_at_epoch_second     BIGINT  NOT NULL,
                    created_at_nano             INTEGER NOT NULL,
                    updated_at_epoch_second     BIGINT  NOT NULL,
                    updated_at_nano             INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, deployment_id)
                )
                """,
                // The listing walks one tenant's deployments in identifier order, and the collation is
                // stated rather than inherited. A deployment id is caller-opaque TEXT, so the default
                // collation is whatever locale the database was initialised with; `C` is bytewise and
                // is therefore the same order the single-host adapter produces, which is what lets a
                // cursor minted against one adapter select the same next page against the other. The
                // index carries the same COLLATE as the query so that stating it does not cost a sort
                // of the tenant's whole set on every page.
                "CREATE INDEX idx_deployment_listing ON deployment "
                        + "(tenant_id, deployment_id COLLATE \"C\")",
                // Immutable graph bytes, one row per aggregate version. `digest` is redundant with a
                // SHA-256 recomputed from `canonical_bytes` on every read, on the model of the
                // manifest store's own verification column: it separates "a field changed" from "this
                // row cannot be read back", which matters because GraphVersion.canonicalDigest() is
                // part of the value callers compare against.
                """
                CREATE TABLE deployment_version (
                    tenant_id               TEXT    NOT NULL,
                    deployment_id           TEXT    NOT NULL,
                    version                 BIGINT  NOT NULL,
                    format_version          INTEGER NOT NULL,
                    canonical_bytes         BYTEA   NOT NULL,
                    digest                  BYTEA   NOT NULL CHECK (octet_length(digest) = 32),
                    author                  TEXT    NOT NULL,
                    created_at_epoch_second BIGINT  NOT NULL,
                    created_at_nano         INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, deployment_id, version),
                    FOREIGN KEY (tenant_id, deployment_id)
                        REFERENCES deployment (tenant_id, deployment_id) ON DELETE CASCADE
                )
                """,
                // The idempotency ledger for every mutation, including `create`. A replayed command
                // must return the exact outcome it produced the first time, not the aggregate's
                // current state, because a later unrelated mutation must not change what an earlier
                // replay reports -- so this table stores the whole Record the mutation produced, as
                // columns, rather than a reference into the live `deployment` row.
                """
                CREATE TABLE deployment_command (
                    tenant_id                               TEXT    NOT NULL,
                    deployment_id                           TEXT    NOT NULL,
                    action                                  TEXT    NOT NULL,
                    command_key                             TEXT    NOT NULL,
                    digest                                  TEXT    NOT NULL,
                    recorded_latest_version                 BIGINT  NOT NULL,
                    recorded_generation                     BIGINT  NOT NULL,
                    recorded_revision                       BIGINT  NOT NULL,
                    recorded_desired_kind                   TEXT    NOT NULL,
                    recorded_desired_version                BIGINT,
                    recorded_update_strategy                TEXT,
                    recorded_observed_kind                  TEXT    NOT NULL,
                    recorded_observed_version               BIGINT,
                    recorded_observed_generation            BIGINT  NOT NULL,
                    recorded_observed_at_epoch_second       BIGINT  NOT NULL,
                    recorded_observed_at_nano               INTEGER NOT NULL,
                    recorded_lease_owner                    TEXT,
                    recorded_lease_fence                    BIGINT,
                    recorded_lease_acquired_at_epoch_second BIGINT,
                    recorded_lease_acquired_at_nano         INTEGER,
                    recorded_lease_expires_at_epoch_second  BIGINT,
                    recorded_lease_expires_at_nano          INTEGER,
                    recorded_failure_code                   TEXT,
                    recorded_failure_message                TEXT,
                    recorded_failure_at_epoch_second        BIGINT,
                    recorded_failure_at_nano                INTEGER,
                    recorded_tombstone_reason               TEXT,
                    recorded_tombstone_at_epoch_second      BIGINT,
                    recorded_tombstone_at_nano              INTEGER,
                    recorded_created_at_epoch_second        BIGINT  NOT NULL,
                    recorded_created_at_nano                INTEGER NOT NULL,
                    recorded_updated_at_epoch_second        BIGINT  NOT NULL,
                    recorded_updated_at_nano                INTEGER NOT NULL,
                    recorded_at_epoch_second                BIGINT  NOT NULL,
                    recorded_at_nano                        INTEGER NOT NULL,
                    expires_at_epoch_second                 BIGINT  NOT NULL,
                    expires_at_nano                         INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, deployment_id, action, command_key),
                    FOREIGN KEY (tenant_id, deployment_id)
                        REFERENCES deployment (tenant_id, deployment_id) ON DELETE CASCADE
                )
                """,
                // Resolves a create-replay by (tenant, key) alone, before any deployment id is known:
                // `create` has nothing to key on until one is minted, and the primary key -- which
                // every other action reaches this table by -- therefore cannot serve it.
                //
                // UNIQUE, which the single-host adapter's identically named index is not, and this is
                // the one place where a faithful translation of that index would be wrong. There the
                // whole database's write lock means two concurrent creates carrying one idempotency
                // key cannot interleave, so looking the key up and then inserting is atomic and a
                // plain index suffices. Here the two creates are on different hosts: both would find
                // no row, both would mint an identity, and one caller's key would end up naming a
                // deployment a different caller's key also names -- the exact duplicate-execution
                // that an idempotency ledger exists to prevent, and invisible afterwards because both
                // callers were told they succeeded. Making the index the arbiter moves the decision
                // into the write itself, where a lock this process could take has no reach.
                //
                // Partial, restricted to the one action ever looked up this way, so the uniqueness
                // constrains nothing else: `action` is constant inside the predicate, which makes
                // uniqueness over (tenant_id, action, command_key) exactly uniqueness of a create key
                // within a tenant, while keeping the column list the single-host adapter shipped.
                "CREATE UNIQUE INDEX idx_deployment_command_create_replay ON deployment_command "
                        + "(tenant_id, action, command_key) WHERE action = 'CREATE'",
                // Serves purgeExpiredCommandRecords(tenantId), on the model of idx_idempotency_expiry:
                // without it, bounding the ledger's retention would force a full scan of every
                // tenant's rows on every purge.
                "CREATE INDEX idx_deployment_command_expiry ON deployment_command "
                        + "(tenant_id, expires_at_epoch_second, expires_at_nano)",
                // One row while a lease is held, deleted on release; `fence` here is this lease's own
                // token, copied from `deployment.fence` at acquire time for direct reads. No expiry
                // index: every access to this table is a point lookup by (tenant_id, deployment_id),
                // which the primary key already serves, and unlike idempotency records this table is
                // never scanned by a background purge -- a lease is evaluated lazily against the
                // caller's presented token, never reaped.
                """
                CREATE TABLE deployment_lease (
                    tenant_id                TEXT    NOT NULL,
                    deployment_id            TEXT    NOT NULL,
                    owner                    TEXT    NOT NULL,
                    fence                    BIGINT  NOT NULL,
                    acquired_at_epoch_second BIGINT  NOT NULL,
                    acquired_at_nano         INTEGER NOT NULL,
                    expires_at_epoch_second  BIGINT  NOT NULL,
                    expires_at_nano          INTEGER NOT NULL,
                    PRIMARY KEY (tenant_id, deployment_id),
                    FOREIGN KEY (tenant_id, deployment_id)
                        REFERENCES deployment (tenant_id, deployment_id) ON DELETE CASCADE
                )
                """)),
                new SchemaMigration(3, "execution manifest operational policy v2", List.of(
                        "ALTER TABLE execution_manifest ADD COLUMN operational_policy TEXT")));
    }
}
