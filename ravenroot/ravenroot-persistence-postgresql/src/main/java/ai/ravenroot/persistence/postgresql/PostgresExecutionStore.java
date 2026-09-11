package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.application.ExecutionTerminationReason;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.execution.NodeCommand;
import ai.ravenroot.api.payload.PayloadKind;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.persistence.AgentAuthorityBudgetFold;
import ai.ravenroot.api.persistence.AgentAuthorityControl;
import ai.ravenroot.api.persistence.AgentAuthorityControlState;
import ai.ravenroot.api.persistence.AgentAuthorityState;
import ai.ravenroot.api.persistence.AgentBudgetOperation;
import ai.ravenroot.api.persistence.DurableAgentAuthorityBudget;
import ai.ravenroot.api.persistence.DurableExecutionPause;
import ai.ravenroot.api.persistence.DurableExecutionResult;
import ai.ravenroot.api.persistence.DurableHandler;
import ai.ravenroot.api.persistence.DurableHumanTask;
import ai.ravenroot.api.persistence.DurableToolApproval;
import ai.ravenroot.api.persistence.EventDigest;
import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionOrigin;
import ai.ravenroot.api.persistence.ExecutionPauseRegistration;
import ai.ravenroot.api.persistence.ExecutionPauseStatus;
import ai.ravenroot.api.persistence.ExecutionPauseTransition;
import ai.ravenroot.api.persistence.ExecutionResultNodes;
import ai.ravenroot.api.persistence.ExecutionResultPayload;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.HandlerPayloadSchema;
import ai.ravenroot.api.persistence.HandlerRegistration;
import ai.ravenroot.api.persistence.HandlerStatus;
import ai.ravenroot.api.persistence.HandlerTransition;
import ai.ravenroot.api.persistence.HumanTaskAttentionAuthorization;
import ai.ravenroot.api.persistence.HumanTaskAttentionCounts;
import ai.ravenroot.api.persistence.HumanTaskAttentionCursor;
import ai.ravenroot.api.persistence.HumanTaskAttentionItem;
import ai.ravenroot.api.persistence.HumanTaskAttentionLocator;
import ai.ravenroot.api.persistence.HumanTaskAttentionPage;
import ai.ravenroot.api.persistence.HumanTaskAttentionQuery;
import ai.ravenroot.api.persistence.HumanTaskCommentRequirement;
import ai.ravenroot.api.persistence.HumanTaskConfirmationAction;
import ai.ravenroot.api.persistence.HumanTaskConfirmationLimits;
import ai.ravenroot.api.persistence.HumanTaskConfirmationPresentation;
import ai.ravenroot.api.persistence.HumanTaskExecutionLimits;
import ai.ravenroot.api.persistence.HumanTaskMetadata;
import ai.ravenroot.api.persistence.HumanTaskNodeAttentionCounts;
import ai.ravenroot.api.persistence.HumanTaskPage;
import ai.ravenroot.api.persistence.HumanTaskPolicy;
import ai.ravenroot.api.persistence.HumanTaskQuery;
import ai.ravenroot.api.persistence.HumanTaskReentryMapping;
import ai.ravenroot.api.persistence.HumanTaskRegistration;
import ai.ravenroot.api.persistence.HumanTaskResponseSchema;
import ai.ravenroot.api.persistence.HumanTaskStatus;
import ai.ravenroot.api.persistence.HumanTaskTransition;
import ai.ravenroot.api.persistence.IdempotencyRecord;
import ai.ravenroot.api.persistence.IdempotencyWrite;
import ai.ravenroot.api.persistence.InventoryCursor;
import ai.ravenroot.api.persistence.InventoryDisposition;
import ai.ravenroot.api.persistence.JournalCursor;
import ai.ravenroot.api.persistence.JournalRecord;
import ai.ravenroot.api.persistence.LeaseHandle;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.ProcessInventoryEntry;
import ai.ravenroot.api.persistence.ProcessInventoryPage;
import ai.ravenroot.api.persistence.ProcessInventoryQuery;
import ai.ravenroot.api.persistence.ResultPayloadState;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.persistence.TimerSchedule;
import ai.ravenroot.api.persistence.ToolApprovalRegistration;
import ai.ravenroot.api.persistence.ToolApprovalStatus;
import ai.ravenroot.api.persistence.ToolApprovalTransition;
import ai.ravenroot.api.persistence.TraversalInventoryEntry;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The durable shared {@link ExecutionStore}, backed by one PostgreSQL database.
 *
 * <h2>Why this is not a port of the single-host adapter</h2>
 * <p>{@code SqliteExecutionStore} takes the whole database's write lock with {@code BEGIN IMMEDIATE},
 * so it can read state and then write a decision derived from that state with nothing able to
 * intervene. Every such read-then-write sequence is a <strong>lost update</strong> here, because the
 * second writer is a different JVM, usually on a different host, and no lock this process could take
 * would be visible to it. Reproducing those sequences would compile, pass a single-threaded
 * conformance run, and lose updates in production under contention — the one failure this adapter
 * exists to make impossible.</p>
 *
 * <p>Each of them is therefore one of two shapes, and never a third:</p>
 * <ul>
 *   <li>a {@code SELECT ... FOR UPDATE} on the {@code process_instance} row taken inside the same
 *   transaction that writes the decision, which is how {@code apply}, {@code claim}, {@code renew},
 *   {@code ack} and the claim loop are made safe. That row is the serialization point for an
 *   instance's whole lifecycle: revision, fencing token and lease all move under it;</li>
 *   <li>a conditional {@code UPDATE ... WHERE <the value the decision was made on>} or an
 *   {@code INSERT ... ON CONFLICT} whose {@code SET} is computed from the stored row, which is how the
 *   delivery-attempt counter, the journal offset and stream-sequence allocators, the outbox cursor,
 *   the inbox record, {@code release} and every watermark are made safe without a lock at all.</li>
 * </ul>
 *
 * <p>There is no process-local lock anywhere in this class, and there could not be one that helped.</p>
 *
 * <h2>What it declares, and on what evidence</h2>
 * <ul>
 *   <li>{@link StoreCapability#DURABLE} — every batch is one committed PostgreSQL transaction, and
 *   state is read back from the database on reopen.</li>
 *   <li>{@link StoreCapability#TRANSACTIONAL_BATCH} — one transaction per batch, one {@code COMMIT},
 *   every rejection raised before it. {@link Transactions} owns that boundary and is the only one.</li>
 *   <li>{@link StoreCapability#CROSS_PROCESS_LEASE} — leases and fencing tokens live in the database
 *   and are decided by row locks the server holds, so the exclusion extends past the machine, which is
 *   the half the single-host adapter has to disclaim.</li>
 *   <li>{@link StoreCapability#IDEMPOTENCY_PURGE}, {@link StoreCapability#EVENT_JOURNAL},
 *   {@link StoreCapability#JOURNAL_COMPACTION}, {@link StoreCapability#PROCESS_INVENTORY},
 *   {@link StoreCapability#INVENTORY_RETENTION} and {@link StoreCapability#EXECUTION_RESULTS} — all
 *   implemented here against the same rows the lifecycle writes.</li>
 *   <li>{@link StoreCapability#DURABLE_HANDLERS}, {@link StoreCapability#TOOL_APPROVALS},
 *   {@link StoreCapability#HUMAN_TASKS}, {@link StoreCapability#HUMAN_TASK_CONFIRMATIONS},
 *   {@link StoreCapability#EXECUTION_PAUSES} and {@link StoreCapability#AGENT_AUTHORITY_BUDGETS} —
 *   the six continuation facilities, each written inside the <em>same</em> transaction as the
 *   aggregate transitions beside it. That atomicity is the whole content of these capabilities: a
 *   store that committed a wait without the handler recording what it is waiting for, or a
 *   resolution without the traversal it authorizes, would leave a process that no host can
 *   continue.</li>
 * </ul>
 *
 * <h2>Uniqueness is decided by the database, not by looking first</h2>
 * <p>Four of those facilities carry a uniqueness rule that spans a whole tenant rather than one
 * instance: a handler's correlation and deduplication keys, a human task's, and a traversal's live
 * hold. Those are the only rules here that two <em>different</em> instances can contend for, so the
 * {@code process_instance} row lock that serializes everything else does not cover them. Checking and
 * then inserting would be the lost update this adapter exists to prevent, with the specific outcome
 * that two live handlers end up under one correlation key and a trigger's target becomes whichever
 * one the planner returned.</p>
 *
 * <p>So the partial unique indexes decide the winner, and the insert is issued inside a savepoint: a
 * collision rolls back to it, the row that won is then read, and the caller is told which rule it hit
 * — an exact repeat is the no-op a retried wait depends on, and anything else is the classified
 * refusal. A bare insert would be correct about who wins and useless about why, because the
 * transaction is aborted at the point the truthful answer would have to be read; and
 * {@code ON CONFLICT DO NOTHING} would not be reliable here, since inferring a <em>partial</em> index
 * as an arbiter is not something a statement without a conflict target can be relied on to do.</p>
 *
 * <h2>The store is its own clock authority</h2>
 * <p>Every temporal predicate is evaluated against the injected {@link Clock}, read once per operation
 * in Java and bound as a parameter. No SQL in this adapter contains {@code now()},
 * {@code CURRENT_TIMESTAMP} or any other database-side clock. A predicate reading the server's clock
 * would ignore the injected one entirely, so every lease and timer assertion would either never fire
 * or fire for the wrong reason — and with several hosts there is a second reason: the database's clock
 * and the deployment's clocks are different clocks, and only one of them is the one the caller reasons
 * about.</p>
 *
 * <h2>Four boundary conventions, and they are not uniform</h2>
 * <p>Each is inclusive or exclusive on its own merits, matching the other adapters exactly. Writing
 * one from intuition gets at least one of them wrong.</p>
 * <ol>
 *   <li><strong>A lease is live while {@code now < expiresAt}</strong>, strictly.</li>
 *   <li><strong>A timer is due when {@code dueAt <= now}</strong>, inclusive.</li>
 *   <li><strong>A claimed work item is visible again when {@code visibleAgainAt <= now}</strong>,
 *   inclusive.</li>
 *   <li><strong>An idempotency record is collectable when {@code expiresAt < now}</strong>,
 *   <em>strictly</em> — the odd one out, because {@code expiresAt} is the earliest instant at which the
 *   store is <em>permitted</em> to forget, and forgetting exactly then would forget at the first
 *   instant of the last moment it promised to retain.</li>
 * </ol>
 *
 * <h2>The connection is never named here</h2>
 * <p>This adapter is handed a {@link DataSource} and never sees a URL, a credential or a pool setting.
 * Everything operational — TLS, pool size, failover, the schema the search path resolves to — is the
 * deployment's, and the adapter's surface is deliberately too narrow to disagree with it. It follows
 * that {@link #close()} does not close the {@code DataSource}: this store did not open it and may not
 * be the only thing using it.</p>
 */
public final class PostgresExecutionStore implements ExecutionStore {

    private static final Set<StoreCapability> CAPABILITIES = Set.of(
            StoreCapability.DURABLE,
            StoreCapability.TRANSACTIONAL_BATCH,
            StoreCapability.CROSS_PROCESS_LEASE,
            StoreCapability.IDEMPOTENCY_PURGE,
            StoreCapability.EVENT_JOURNAL,
            StoreCapability.JOURNAL_COMPACTION,
            // The inventory reads the same process_instance, traversal, invocation, attempt and lease
            // rows that apply() writes inside one transaction, so it is atomic with the lifecycle by
            // construction rather than by a projection that has to be kept in step. There is no offset
            // to repair and no rebuild that could invent work.
            StoreCapability.PROCESS_INVENTORY,
            StoreCapability.INVENTORY_RETENTION,
            StoreCapability.EXECUTION_RESULTS,
            // The six continuation facilities. They are declared together because they are one
            // mechanism seen from six angles -- a durable decision record, written with the
            // transitions it belongs to -- and because the batch that carries them carries them
            // together: a human task's resolution is a handler transition, a human task transition
            // and a re-entry traversal in one commit, so declaring a subset would let a caller
            // assemble a batch that is half accepted and half refused.
            StoreCapability.DURABLE_HANDLERS,
            StoreCapability.TOOL_APPROVALS,
            StoreCapability.HUMAN_TASKS,
            StoreCapability.HUMAN_TASK_CONFIRMATIONS,
            StoreCapability.EXECUTION_PAUSES,
            StoreCapability.AGENT_AUTHORITY_BUDGETS);

    /**
     * The one projection every handler read uses, aliased so a correlated subquery cannot silently
     * bind an unqualified column to its own table instead of to this one.
     */
    private static final String HANDLER_COLUMNS = "SELECT h.* FROM execution_handler h";

    private static final String TOOL_APPROVAL_COLUMNS = "SELECT a.* FROM tool_approval a";

    private static final String EXECUTION_PAUSE_COLUMNS = "SELECT p.* FROM execution_pause p";

    private static final String HUMAN_TASK_COLUMNS = "SELECT t.* FROM human_task t";

    /**
     * The bounded attention projection, and deliberately not {@code t.*}.
     *
     * <p>An attention row is shown to a caller who has been authorized for one <em>action</em>, not
     * for the task. Selecting every column and then choosing what to return would put the response
     * schema, the continuation bytes, the requester's identity and the decision comment in this
     * process's memory one field access away from a projection that must not carry them. Naming the
     * columns makes the omission the query's, where a reviewer can see it.</p>
     *
     * <p>The join to {@code process_instance} is on the graph pin as well as the identity, because a
     * task is only actionable in the deployment context it was pinned to; a task whose instance has
     * been re-pinned is not an attention row for the old context and must not be counted as one.</p>
     */
    private static final String HUMAN_TASK_ATTENTION_COLUMNS =
            "SELECT t.process_instance_id, t.task_id, t.traversal_id, t.node_id, t.generation, "
                    + "t.status, t.graph_version_pin, t.created_at_epoch_second, t.created_at_nano, "
                    + "t.expires_at_epoch_second, t.expires_at_nano, t.escalate_at_epoch_second, "
                    + "t.escalate_at_nano, t.required_roles, t.required_scopes, "
                    + "t.requester_request_id, t.requester_subject, t.requester_principal_type, "
                    + "t.requester_issuer, t.confirmation_version, t.confirmation_prompt, "
                    + "t.confirmation_comment_requirement, t.confirmation_actions, "
                    + "t.confirmation_resolve_label, t.confirmation_deny_label, "
                    + "t.confirmation_cancel_label, t.confirmation_max_prompt_bytes, "
                    + "t.confirmation_max_action_label_bytes, t.confirmation_max_comment_bytes, "
                    + "p.deployment_id AS attention_deployment_id "
                    + "FROM human_task t JOIN process_instance p ON p.tenant_id = t.tenant_id "
                    + "AND p.process_instance_id = t.process_instance_id "
                    + "AND p.graph_version_pin = t.graph_version_pin ";

    /**
     * {@code ('WAITING', 'ESCALATED')} and {@code ('RESOLVED', 'DENIED', 'EXPIRED')}, derived from
     * {@link HandlerStatus#terminal()} rather than written out as SQL text.
     *
     * <p>Restating the split as a literal in every query is how two adapters come to disagree without
     * anything failing: a sixth, non-terminal status would be enforced by the port's own
     * {@code terminal()} check and quietly ignored by a hand-written {@code IN} list here, so
     * correlation-key uniqueness would hold on one store and not the other. Deriving it means adding
     * a status changes every query at once.</p>
     *
     * <p>{@link PostgresSchema}'s migration cannot use these — a migration's text is history and must
     * never be rewritten — so the partial index there spells the literal out, and this adapter's
     * schema test pins the shipped literal against these derived lists, so a new status fails the
     * build rather than silently making the shipped index wrong.</p>
     */
    static final String LIVE_HANDLER_STATUSES = handlerStatusList(false);

    /** The terminal counterpart of {@link #LIVE_HANDLER_STATUSES}. */
    static final String TERMINAL_HANDLER_STATUSES = handlerStatusList(true);

    /**
     * The live human-task statuses, derived for exactly the reason the handler lists are derived.
     *
     * <p>{@link HumanTaskStatus#terminal()} partitions the enum the same way
     * {@link HandlerStatus#terminal()} does, so this is derived from it rather than written out. A
     * hand-written list is not a shortcut here, it is a second definition of liveness that nothing
     * keeps in step: a new non-terminal status would be honoured by the port's own {@code terminal()}
     * and silently ignored by the list, so correlation-key uniqueness would stop covering it, and the
     * attention query and the live lookups would stop returning it — all without failing anything.</p>
     *
     * <p>{@link PostgresSchema}'s migration cannot use this — a migration's text is history and must
     * never be rewritten — so the partial index there spells the literal out, and the schema test pins
     * the shipped literal against this derived list, so a new status fails the build rather than
     * quietly making the shipped index wrong.</p>
     */
    static final String LIVE_HUMAN_TASK_STATUSES = humanTaskStatusList(false);

    /**
     * The live execution-pause status, derived for the same reason.
     *
     * <p>{@link ExecutionPauseStatus#terminal()} leaves exactly one live status today, so this list
     * has one member and looks like a constant. It is derived anyway: a single-valued list is the
     * easiest one to write out by hand and the easiest to leave behind when a second live status
     * arrives.</p>
     */
    static final String LIVE_EXECUTION_PAUSE_STATUSES = executionPauseStatusList(false);

    private static String handlerStatusList(boolean terminal) {
        return statusList(Arrays.stream(HandlerStatus.values())
                .filter(status -> status.terminal() == terminal)
                .map(Enum::name));
    }

    private static String humanTaskStatusList(boolean terminal) {
        return statusList(Arrays.stream(HumanTaskStatus.values())
                .filter(status -> status.terminal() == terminal)
                .map(Enum::name));
    }

    private static String executionPauseStatusList(boolean terminal) {
        return statusList(Arrays.stream(ExecutionPauseStatus.values())
                .filter(status -> status.terminal() == terminal)
                .map(Enum::name));
    }

    private static String statusList(java.util.stream.Stream<String> names) {
        return names.map(name -> "'" + name + "'")
                .collect(java.util.stream.Collectors.joining(", ", "(", ")"));
    }

    /**
     * The columns every inventory row needs, plus the two aggregate counts and the lease, in one
     * statement.
     *
     * <p>The counts are correlated subqueries rather than a {@code GROUP BY} over two joins, because
     * joining traversals and attempts in the same query multiplies the rows and every aggregate then
     * has to be de-duplicated — which is both slower and, more to the point, the kind of query that is
     * subtly wrong in a way no assertion notices until a row has two traversals and three attempts.</p>
     *
     * <p>The lease is a {@code LEFT JOIN}: an instance with no lease is the normal shape of interrupted
     * work and must appear in the listing, so an inner join would hide precisely the cohort the
     * inventory exists to surface.</p>
     */
    private static final String INVENTORY_COLUMNS =
            "SELECT p.process_instance_id, p.status, p.termination_reason, p.graph_version_pin, "
                    + "p.revision, p.fencing_token, p.lifecycle_generation, p.deployment_id, "
                    + "p.workload_id, p.correlation_id, p.created_at_epoch_second, p.created_at_nano, "
                    + "p.updated_at_epoch_second, p.updated_at_nano, p.retained_until_epoch_second, "
                    + "p.retained_until_nano, l.worker_id AS lease_worker_id, "
                    + "l.expires_at_epoch_second AS lease_expires_at_epoch_second, "
                    + "l.expires_at_nano AS lease_expires_at_nano, "
                    + "(SELECT COUNT(*) FROM traversal t WHERE t.tenant_id = p.tenant_id "
                    + "AND t.process_instance_id = p.process_instance_id) AS traversal_count, "
                    + "(SELECT COUNT(*) FROM attempt a WHERE a.tenant_id = p.tenant_id "
                    + "AND a.process_instance_id = p.process_instance_id AND a.status = 'PARKED') "
                    + "AS parked_count "
                    + "FROM process_instance p LEFT JOIN lease l ON l.tenant_id = p.tenant_id "
                    + "AND l.process_instance_id = p.process_instance_id ";

    private static final String META_COLUMNS =
            "SELECT revision, fencing_token, graph_version_pin, status, termination_reason, "
                    + "updated_at_epoch_second, updated_at_nano, created_at_epoch_second, "
                    + "created_at_nano, lifecycle_generation, deployment_id, workload_id, "
                    + "correlation_id, retained_until_epoch_second, retained_until_nano "
                    + "FROM process_instance WHERE tenant_id = ? AND process_instance_id = ?";

    private final DataSource dataSource;
    private final Clock clock;
    private final PostgresStoreConfig config;
    private final HumanTaskPolicy humanTaskPolicy;
    private final Transactions transactions;
    private final ExecutorService worker;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Creates a store over {@code dataSource}, migrating the schema if it is not current. */
    public PostgresExecutionStore(DataSource dataSource, Clock clock) {
        this(dataSource, clock, PostgresStoreConfig.defaults(), HumanTaskPolicy.DEFAULTS,
                CommitBoundary.NONE);
    }

    /** Creates a store with explicit contention and limit settings. */
    public PostgresExecutionStore(DataSource dataSource, Clock clock, PostgresStoreConfig config) {
        this(dataSource, clock, config, HumanTaskPolicy.DEFAULTS, CommitBoundary.NONE);
    }

    /** Creates a store that additionally publishes {@code humanTaskPolicy}'s bounds. */
    public PostgresExecutionStore(DataSource dataSource, Clock clock, PostgresStoreConfig config,
                                  HumanTaskPolicy humanTaskPolicy) {
        this(dataSource, clock, config, humanTaskPolicy, CommitBoundary.NONE);
    }

    PostgresExecutionStore(DataSource dataSource, Clock clock, PostgresStoreConfig config,
                           CommitBoundary commitBoundary) {
        this(dataSource, clock, config, HumanTaskPolicy.DEFAULTS, commitBoundary);
    }

    PostgresExecutionStore(DataSource dataSource, Clock clock, PostgresStoreConfig config,
                           HumanTaskPolicy humanTaskPolicy, CommitBoundary commitBoundary) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.config = Objects.requireNonNull(config, "config");
        this.humanTaskPolicy = Objects.requireNonNull(humanTaskPolicy, "humanTaskPolicy");
        this.transactions = new Transactions(dataSource, config,
                Objects.requireNonNull(commitBoundary, "commitBoundary"));
        // Named and daemon so a thread dump says which store is blocked and a forgotten close cannot
        // hold the JVM open. Unbounded because the DataSource is the real bound: a task that cannot get
        // a connection blocks there, which is where the deployment configured the limit.
        var sequence = new AtomicLong();
        this.worker = Executors.newCachedThreadPool(runnable -> {
            var thread = new Thread(runnable, "ravenroot-postgres-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        try (Connection connection = dataSource.getConnection()) {
            PostgresSchema.migrate(connection, clock);
        } catch (SQLException failed) {
            worker.shutdownNow();
            throw mapped(failed, null);
        } catch (RuntimeException failed) {
            worker.shutdownNow();
            throw failed;
        }
    }

    // ---------------------------------------------------------------- static self-description

    @Override
    public Set<StoreCapability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public Duration maxLeaseTtl() {
        return config.maxLeaseTtl();
    }

    @Override
    public int maxPayloadBytes() {
        return config.maxPayloadBytes();
    }

    @Override
    public Duration maxClockSkew() {
        return config.maxClockSkew();
    }

    @Override
    public int maxInventoryPageSize() {
        return config.maxInventoryPageSize();
    }

    @Override
    public Duration terminalRetention() {
        return config.terminalRetention();
    }

    @Override
    public Duration journalRetention() {
        return config.journalRetention();
    }

    @Override
    public Duration executionResultRetention() {
        return config.executionResultRetention();
    }

    @Override
    public int maxExecutionResultPayloadBytes() {
        return config.maxPayloadBytes();
    }

    @Override
    public int maxHumanTaskPageSize() {
        return humanTaskPolicy.inboxMaxPageSize();
    }

    @Override
    public int maxHumanTaskAttentionPageSize() {
        return humanTaskPolicy.confirmation().attentionMaxPageSize();
    }

    @Override
    public int maxHumanTaskAttentionNodeCounts() {
        return HumanTaskPolicy.Confirmation.HARD_MAX_ATTENTION_NODE_COUNTS;
    }

    @Override
    public int maxHumanTaskResponsePayloadBytes() {
        // The stable structured-payload ceiling rather than today's general execution-payload setting:
        // a task accepted under one configuration must remain resolvable after a restart under
        // another, and this number is the one that cannot move underneath a stored row.
        return PayloadLimits.HARD_MAX_ENCODED_BYTES;
    }

    // ---------------------------------------------------------------- write path

    @Override
    public CompletionStage<StoredProcessInstance> apply(ExecutionBatch batch) {
        return applyInternal(batch, null);
    }

    @Override
    public CompletionStage<StoredProcessInstance> applyManaged(
            ExecutionBatch batch, ai.ravenroot.api.persistence.ExecutionPersistenceAuthority authority) {
        Objects.requireNonNull(authority, "authority");
        return applyInternal(batch, authority);
    }

    private CompletionStage<StoredProcessInstance> applyInternal(
            ExecutionBatch batch, ai.ravenroot.api.persistence.ExecutionPersistenceAuthority authority) {
        return async(() -> {
            Objects.requireNonNull(batch, "batch");
            // All decidable from the request alone, so they happen before a connection is taken from
            // the pool, let alone a transaction opened. A rejection that needed a round trip would make
            // a caller bug cost the same as a write.
            requireNoFencingTokenUnderNotPresent(batch);
            if (authority == null) requireBatchPayloads(batch);
            // A handler outcome is checked here only when it is NOT a human task's resolution. A
            // human task pins its own response capacity when it is registered, and that capacity can
            // legitimately exceed this build's general execution-payload setting -- so the check that
            // applies to it needs the stored task, which needs a connection. It is made inside the
            // transaction instead, by requireHandlerOutcomeWithinLimit.
            requireEnvelopesMatchBatch(batch);
            return write(batch.key(), connection -> applyLocked(connection, batch, authority));
        });
    }

    private void requireBatchPayloads(ExecutionBatch batch) {
        batch.timersToSchedule().forEach(timer -> requireWithinPayloadLimit(timer.payload()));
        batch.idempotency().ifPresent(write -> {
            requireWithinPayloadLimit(write.requestFingerprint());
            requireWithinPayloadLimit(write.outcomeRef());
        });
        batch.handlerTransitions().forEach(transition -> {
            if (!isHumanTaskResolution(batch, transition)) requireWithinPayloadLimit(transition.outcomePayload());
        });
        batch.toolApprovalsToRegister().forEach(registration -> {
            requireWithinPayloadLimit(OpaquePayload.of(registration.canonicalArguments(), "application/json"));
            requireWithinPayloadLimit(OpaquePayload.of(registration.continuation(),
                    "application/vnd.ravenroot.tool-continuation"));
        });
    }

    /**
     * The whole of a batch, inside one transaction, under the instance's own row lock.
     *
     * <p>The {@code FOR UPDATE} on the first read is what makes every later decision in this method
     * sound. Fencing, replay, the revision expectation and the fold are all derived from state read
     * here, and each of them is written back before the transaction ends; without the lock a second
     * host could move the row in between and this method would write a decision about a state that no
     * longer exists.</p>
     */
    private StoredProcessInstance applyLocked(Connection connection, ExecutionBatch batch,
            ai.ravenroot.api.persistence.ExecutionPersistenceAuthority authority)
            throws SQLException {
        ExecutionKey key = batch.key();
        InstanceMeta existing = readMeta(connection, key, true);

        // Fencing -> replay -> expectation -> fold.
        //
        // Fencing precedes every check that could yield a SUCCESS. Answering a fenced worker from its
        // own idempotency record would tell it its work landed and let it briefly believe it still owns
        // the instance, which is the split-brain belief the fence exists to destroy; the new owner is
        // the party that needs the outcome, and it holds a current token.
        //
        // Existence is a PRECONDITION of fencing, not a competitor to it, which is why this is guarded
        // on `existing != null`. A lease on an instance that does not exist is not stale, it is
        // impossible, and reporting FencedOut with a current token of zero would fabricate a value to
        // stand in for the absence of any token.
        if (existing != null) {
            requireFencingTokenCurrent(key, batch, existing);
        }

        StoredProcessInstance replay = replayOf(connection, batch, existing);
        if (replay != null) {
            return replay;
        }

        if (authority != null) {
            requireManagedAuthority(connection, key, authority);
            requireBatchPayloads(batch);
        }

        // Expectation LAST. A write that already happened necessarily bumped the revision, so a
        // retrying caller's expectation is stale by construction; checking it first would make every
        // legitimate replay fail with ConcurrencyConflict.
        requireExpectationMet(key, batch.expectation(), existing, batch);

        ProcessInstance current = existing == null ? null : readAggregate(connection, key, existing);
        ProcessInstance folded = fold(batch, current);
        GraphVersionPin pin = pinFor(key, batch, existing);

        Instant now = clock.instant();
        long revision = existing == null ? 1L : existing.revision() + 1L;
        long fencingToken = existing == null ? 0L : existing.fencingToken();
        // created_at is written once and never rewritten, which is what makes the inventory's sort key
        // immutable and therefore its pagination stable while writes continue.
        Instant createdAt = existing == null ? now : existing.createdAt();
        // One increment per authoritative status transition ACTUALLY APPLIED, counted from the batch
        // rather than by comparing the status before and after: a batch that moves an instance
        // RUNNING -> WAITING -> RUNNING applied two transitions and an endpoint comparison would see
        // none. It is NOT the fencing token and must never be conflated with one: a generation counts
        // how far the lifecycle has moved, a token names who is allowed to move it.
        long generation = (existing == null ? 1L : existing.lifecycleGeneration())
                + processTransitionCount(batch);
        ExecutionOrigin origin = (existing == null ? ExecutionOrigin.none() : existing.origin())
                .mergedWith(batch.origin());
        // Retention starts when the instance becomes terminal and never restarts, because a terminal
        // instance cannot transition again. A non-terminal row carries no deadline at all rather than a
        // far-future one: NULL means "retention has not started", and a sentinel date would be readable
        // as a real deadline by anyone looking at the row.
        Instant retainedUntil = folded.status().terminal()
                ? (existing != null && existing.retainedUntil() != null
                        ? existing.retainedUntil() : plusClamped(now, config.terminalRetention()))
                : null;

        if (existing == null) {
            insertInstanceRow(connection, key, folded, pin, revision, now, createdAt, generation, origin,
                    retainedUntil);
        } else {
            updateInstanceRow(connection, key, folded, pin, revision, now, generation, origin,
                    retainedUntil, existing.revision());
        }
        AggregateStorage.write(connection, key, folded);
        writeTimers(connection, key, batch);
        // After the aggregate, because a registration may name an invocation this batch created and a
        // terminal transition must name a traversal this batch added; both are validated against the
        // post-fold aggregate that is already in `folded`. A rejection here rolls the whole
        // transaction back, which is what makes a wait -- and a re-entry -- atomic with the
        // transitions beside it.
        //
        // The order among the five is the single-host adapter's, and one dependency is real rather
        // than stylistic: a handler transition that resolves a human task reads that task's pinned
        // response capacity, so handlers are folded before the task rows are rewritten and read the
        // capacity the task was registered with rather than one this batch is in the middle of
        // changing.
        writeHandlers(connection, key, batch, folded, revision);
        writeToolApprovals(connection, key, batch, folded, pin, revision, now);
        writeAgentAuthorityBudget(connection, key, batch, folded, now);
        writeExecutionPauses(connection, key, batch, folded, pin, revision);
        writeHumanTasks(connection, key, batch, folded, pin, revision, now);
        IdempotencyWrite idempotency = batch.idempotency().orElse(null);
        if (idempotency != null) {
            writeIdempotencyRecord(connection, key, idempotency, revision, now);
        }
        // Inside the same transaction as the transition above, which is the entirety of the shared
        // transactional boundary the event journal promises. There is no publish step to crash between,
        // because there is no publish step: delivery reads the committed journal afterwards.
        writeJournal(connection, key, batch, revision, now);
        dropAcknowledgementsForRescheduledWork(connection, key);

        // fencingToken is read but never written here: apply does not rotate the fence, and assigning
        // it in the UPDATE would silently undo a claim that committed between this transaction's read
        // and its write if the row lock above were ever removed.
        assert fencingToken >= 0;
        return new StoredProcessInstance(folded, revision, pin, key.tenantId(), now);
    }

    private void requireManagedAuthority(Connection connection, ExecutionKey key,
            ai.ravenroot.api.persistence.ExecutionPersistenceAuthority authority) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT digest, format_version, operational_policy FROM execution_manifest "
                        + "WHERE tenant_id = ? AND process_instance_id = ? FOR KEY SHARE")) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw failure(ExecutionStoreFailure.invalid(
                        "managed execution has no pinned persistence authority"));
                if (!authority.manifestDigest().value().equals(rows.getString(1))
                        || (rows.getInt(2) != ai.ravenroot.api.persistence.ExecutionManifest.FORMAT_VERSION_3
                        && rows.getInt(2) != ai.ravenroot.api.persistence.ExecutionManifest.FORMAT_VERSION_4)) {
                    throw failure(ExecutionStoreFailure.invalid(
                            "managed execution persistence authority does not match its manifest"));
                }
                try {
                    var policy = ai.ravenroot.api.persistence.ResolvedOperationalPolicy
                            .decodeForManifest(rows.getString(3), rows.getInt(2));
                    int pinned = policy.persistence().orElseThrow().maximumPayloadBytes();
                    if (pinned != authority.maximumPayloadBytes() || pinned != config.maxPayloadBytes()) {
                        throw failure(ExecutionStoreFailure.invalid(
                                "managed execution persistence capacity is incompatible"));
                    }
                } catch (IllegalArgumentException | NullPointerException malformed) {
                    throw failure(ExecutionStoreFailure.invalid(
                            "managed execution persistence authority is unavailable"));
                }
            }
        }
    }

    @Override
    public CompletionStage<StoredProcessInstance> load(ExecutionKey key) {
        return async(() -> {
            Objects.requireNonNull(key, "key");
            return readFolded(key, connection -> {
                InstanceMeta meta = readMeta(connection, key, false);
                if (meta == null) {
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                ProcessInstance state = readAggregate(connection, key, meta);
                return new StoredProcessInstance(state, meta.revision(), meta.graphVersionPin(),
                        key.tenantId(), meta.updatedAt());
            });
        });
    }

    // ---------------------------------------------------------------- leases

    @Override
    public CompletionStage<LeaseHandle> claim(ExecutionKey key, String workerId, Duration ttl) {
        return async(() -> {
            Objects.requireNonNull(key, "key");
            requireLeaseTtl(ttl);
            requireWorkerId(workerId);
            return write(key, connection -> {
                // FOR UPDATE, because the next statement writes a fencing token derived from the one
                // read here. Two hosts claiming at once without it both read token N and both write
                // N+1, and the fence -- whose entire purpose is that two owners cannot exist -- would
                // hand the same number to both of them.
                InstanceMeta meta = readMeta(connection, key, true);
                if (meta == null) {
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                Instant now = clock.instant();
                LeaseHandle held = readLease(connection, key, meta.fencingToken());
                if (held != null && !held.workerId().equals(workerId) && now.isBefore(held.expiresAt())) {
                    // Failing to ACQUIRE, not losing one that was held: ordinary contention, nothing
                    // started and nothing at risk. Reporting it as LeaseLost would bury the rare
                    // critical signal under routine noise.
                    throw failure(new ExecutionStoreFailure.LeaseHeldByAnother(key, held.workerId(),
                            held.expiresAt()));
                }
                return issueLease(connection, key, meta.fencingToken(), held, workerId, ttl, now);
            });
        });
    }

    @Override
    public CompletionStage<LeaseHandle> claimManaged(ExecutionKey key, String workerId, Duration ttl,
            ai.ravenroot.api.persistence.ExecutionPersistenceAuthority authority) {
        return async(() -> {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(authority, "authority");
            requireLeaseTtl(ttl);
            requireWorkerId(workerId);
            return write(key, connection -> {
                requireManagedAuthority(connection, key, authority);
                InstanceMeta meta = readMeta(connection, key, true);
                if (meta == null) throw failure(new ExecutionStoreFailure.NotFound(key));
                Instant now = clock.instant();
                LeaseHandle held = readLease(connection, key, meta.fencingToken());
                if (held != null && !held.workerId().equals(workerId) && now.isBefore(held.expiresAt())) {
                    throw failure(new ExecutionStoreFailure.LeaseHeldByAnother(key, held.workerId(),
                            held.expiresAt()));
                }
                return issueLease(connection, key, meta.fencingToken(), held, workerId, ttl, now);
            });
        });
    }

    @Override
    public CompletionStage<LeaseHandle> renew(LeaseHandle lease, Duration ttl) {
        return async(() -> {
            Objects.requireNonNull(lease, "lease");
            requireLeaseTtl(ttl);
            ExecutionKey key = lease.key();
            return write(key, connection -> {
                // FOR UPDATE even though no token is written: the decision "this lease is still mine"
                // is read from the instance's token, and a claim committing between that read and the
                // lease upsert would let this call extend a lease it no longer owns.
                InstanceMeta meta = readMeta(connection, key, true);
                if (meta == null) {
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                LeaseHandle held = readLease(connection, key, meta.fencingToken());
                if (held == null || !held.workerId().equals(lease.workerId())
                        || meta.fencingToken() != lease.fencingToken()) {
                    throw failure(new ExecutionStoreFailure.LeaseLost(key, lease.workerId()));
                }
                Instant now = clock.instant();
                if (!now.isBefore(held.expiresAt())) {
                    throw failure(new ExecutionStoreFailure.LeaseLost(key, lease.workerId()));
                }
                // Renewal extends the window without rotating the token: rotating it would fence out
                // the very holder being renewed.
                var renewed = new LeaseHandle(key, lease.workerId(), meta.fencingToken(),
                        held.claimedAt(), now.plus(ttl));
                upsertLease(connection, renewed);
                return renewed;
            });
        });
    }

    /**
     * Releases a held lease with one conditional statement and no row lock.
     *
     * <p>This is the shape a lost update is impossible in rather than merely unlikely: the fencing
     * token the caller's authority rests on is in the {@code WHERE} clause, so the delete either
     * happens against the state the caller reasoned about or does not happen at all. Reading the token
     * first and deleting second would be the same sequence with a window in it, and the window is where
     * a concurrent claim's lease gets deleted by the worker it just replaced.</p>
     *
     * <p>Zero rows deleted is a no-op and not a failure, because releasing a lease already lost is
     * defined as one.</p>
     */
    @Override
    public CompletionStage<Void> release(LeaseHandle lease) {
        return async(() -> {
            Objects.requireNonNull(lease, "lease");
            ExecutionKey key = lease.key();
            return write(key, connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM lease l USING process_instance p "
                                + "WHERE l.tenant_id = ? AND l.process_instance_id = ? "
                                + "AND l.worker_id = ? AND p.tenant_id = l.tenant_id "
                                + "AND p.process_instance_id = l.process_instance_id "
                                + "AND p.fencing_token = ?")) {
                    statement.setString(1, key.tenantId());
                    StoredUuid.bind(statement, 2, key.processInstanceId());
                    statement.setString(3, lease.workerId());
                    statement.setLong(4, lease.fencingToken());
                    statement.executeUpdate();
                }
                // The fencing token is deliberately left where it is. It is not the lease's property:
                // the next claimant must receive a strictly greater one whether the previous holder
                // released early or simply lapsed.
                return null;
            });
        });
    }

    @Override
    public CompletionStage<List<LeaseHandle>> leases(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            return read(null, connection -> {
                Instant now = clock.instant();
                var active = new ArrayList<LeaseHandle>();
                // A stable order has to come from data here: PostgreSQL has no rowid, and an
                // unordered listing would return the same leases in a different order on every call,
                // which an operator diffing two listings would read as churn.
                String sql = "SELECT l.process_instance_id, "
                        + "p.process_instance_id AS joined_process_id, l.worker_id, "
                        + "l.claimed_at_epoch_second, l.claimed_at_nano, l.expires_at_epoch_second, "
                        + "l.expires_at_nano, p.fencing_token "
                        + "FROM lease l LEFT JOIN process_instance p ON p.tenant_id = l.tenant_id "
                        + "AND p.process_instance_id = l.process_instance_id "
                        + "WHERE l.tenant_id = ? AND " + StoredInstant.strictlyAfter("l.expires_at")
                        + " ORDER BY l.process_instance_id";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, tenantId);
                    StoredInstant.bindComparison(statement, 2, now);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            var key = new ExecutionKey(tenantId, StoredUuid.required(rows, "lease",
                                    "process_instance_id", tenantId));
                            StoredUuid.requiredMatching(rows, "process_instance", "joined_process_id",
                                    key, key.processInstanceId());
                            active.add(new LeaseHandle(key, rows.getString("worker_id"),
                                    rows.getLong("fencing_token"),
                                    StoredInstant.read(rows, "claimed_at"),
                                    StoredInstant.read(rows, "expires_at")));
                        }
                    }
                }
                return List.copyOf(active);
            });
        });
    }

    // ---------------------------------------------------------------- pending work

    @Override
    public CompletionStage<List<PendingWork>> claimPendingWork(String tenantId, String workerId, int limit,
                                                               Duration leaseTtl) {
        return async(() -> {
            requireTenantId(tenantId);
            requireWorkerId(workerId);
            requireLimit(limit);
            requireLeaseTtl(leaseTtl);
            return write(null, connection -> {
                Instant now = clock.instant();
                var claimed = new ArrayList<PendingWork>();
                for (Candidate candidate : lockClaimable(connection, tenantId, workerId, now, limit, true)) {
                    if (claimed.size() >= limit) {
                        break;
                    }
                    List<ScheduledAttempt> attempts = claimableAttempts(connection, candidate.key(), now);
                    List<TimerSchedule> timers = claimableTimers(connection, candidate.key(), now);
                    List<DurableHandler> triggers = claimableTriggers(connection, candidate.key(), now);
                    if (attempts.isEmpty() && timers.isEmpty() && triggers.isEmpty()) {
                        continue;
                    }
                    LeaseHandle lease = issueLease(connection, candidate.key(), candidate.fencingToken(),
                            readLease(connection, candidate.key(), candidate.fencingToken()), workerId,
                            leaseTtl, now);
                    for (ScheduledAttempt attempt : attempts) {
                        if (claimed.size() >= limit) {
                            break;
                        }
                        claimed.add(claimAttempt(connection, candidate.key(), attempt, lease, now, leaseTtl));
                    }
                    for (TimerSchedule timer : timers) {
                        if (claimed.size() >= limit) {
                            break;
                        }
                        claimed.add(claimTimer(connection, candidate.key(), timer, lease, now, leaseTtl));
                    }
                    for (DurableHandler handler : triggers) {
                        if (claimed.size() >= limit) {
                            break;
                        }
                        claimed.add(claimTrigger(connection, candidate.key(), handler, lease, now,
                                leaseTtl));
                    }
                }
                return List.copyOf(claimed);
            });
        });
    }

    @Override
    public CompletionStage<List<PendingWork.TimerDue>> claimDueTimers(String tenantId, String workerId,
                                                                      int limit, Duration leaseTtl) {
        return async(() -> {
            requireTenantId(tenantId);
            requireWorkerId(workerId);
            requireLimit(limit);
            requireLeaseTtl(leaseTtl);
            return write(null, connection -> {
                Instant now = clock.instant();
                var claimed = new ArrayList<PendingWork.TimerDue>();
                for (Candidate candidate : lockClaimable(connection, tenantId, workerId, now, limit, false)) {
                    if (claimed.size() >= limit) {
                        break;
                    }
                    List<TimerSchedule> timers = claimableTimers(connection, candidate.key(), now);
                    if (timers.isEmpty()) {
                        continue;
                    }
                    LeaseHandle lease = issueLease(connection, candidate.key(), candidate.fencingToken(),
                            readLease(connection, candidate.key(), candidate.fencingToken()), workerId,
                            leaseTtl, now);
                    for (TimerSchedule timer : timers) {
                        if (claimed.size() >= limit) {
                            break;
                        }
                        claimed.add(claimTimer(connection, candidate.key(), timer, lease, now, leaseTtl));
                    }
                }
                return List.copyOf(claimed);
            });
        });
    }

    @Override
    public CompletionStage<List<PendingWork>> claimPendingWorkAmong(
            String tenantId, String workerId, int limit, Duration leaseTtl,
            java.util.Map<ExecutionKey, ai.ravenroot.api.persistence.ExecutionPersistenceAuthority> verified) {
        return claimAmong(tenantId, workerId, limit, leaseTtl, verified, false)
                .thenApply(items -> items.stream().map(PendingWork.class::cast).toList());
    }

    @Override
    public CompletionStage<List<PendingWork.TimerDue>> claimDueTimersAmong(
            String tenantId, String workerId, int limit, Duration leaseTtl,
            java.util.Map<ExecutionKey, ai.ravenroot.api.persistence.ExecutionPersistenceAuthority> verified) {
        return claimAmong(tenantId, workerId, limit, leaseTtl, verified, true)
                .thenApply(items -> items.stream().map(PendingWork.TimerDue.class::cast).toList());
    }

    private CompletionStage<List<? extends PendingWork>> claimAmong(
            String tenantId, String workerId, int limit, Duration leaseTtl,
            java.util.Map<ExecutionKey, ai.ravenroot.api.persistence.ExecutionPersistenceAuthority> verified,
            boolean timersOnly) {
        return async(() -> {
            requireTenantId(tenantId);
            requireWorkerId(workerId);
            requireLimit(limit);
            requireLeaseTtl(leaseTtl);
            var authorities = requireVerifiedAuthorities(tenantId, verified);
            if (authorities.isEmpty()) return List.of();
            return write(null, connection -> {
                Instant now = clock.instant();
                var claimed = new ArrayList<PendingWork>();
                for (var entry : authorities.entrySet()) {
                    if (claimed.size() >= limit) break;
                    ExecutionKey key = entry.getKey();
                    requireManagedAuthority(connection, key, entry.getValue());
                    InstanceMeta meta = readMeta(connection, key, true);
                    if (meta == null || leaseLive(connection, key, now)
                            && !workerId.equals(readLease(connection, key, meta.fencingToken()).workerId())) {
                        continue;
                    }
                    List<TimerSchedule> timers = claimableTimers(connection, key, now);
                    List<ScheduledAttempt> attempts = timersOnly ? List.of()
                            : claimableAttempts(connection, key, now);
                    List<DurableHandler> triggers = timersOnly ? List.of()
                            : claimableTriggers(connection, key, now);
                    if (attempts.isEmpty() && timers.isEmpty() && triggers.isEmpty()) continue;
                    LeaseHandle lease = issueLease(connection, key, meta.fencingToken(),
                            readLease(connection, key, meta.fencingToken()), workerId, leaseTtl, now);
                    if (!timersOnly) {
                        for (ScheduledAttempt attempt : attempts) {
                            if (claimed.size() >= limit) break;
                            claimed.add(claimAttempt(connection, key, attempt, lease, now, leaseTtl));
                        }
                    }
                    for (TimerSchedule timer : timers) {
                        if (claimed.size() >= limit) break;
                        claimed.add(claimTimer(connection, key, timer, lease, now, leaseTtl));
                    }
                    if (!timersOnly) {
                        for (DurableHandler trigger : triggers) {
                            if (claimed.size() >= limit) break;
                            claimed.add(claimTrigger(connection, key, trigger, lease, now, leaseTtl));
                        }
                    }
                }
                return List.copyOf(claimed);
            });
        });
    }

    @Override
    public CompletionStage<ai.ravenroot.api.persistence.ManagedClaimCandidatePage> managedClaimCandidates(
            String tenantId, String workerId, int limit, Duration leaseTtl, boolean timersOnly,
            java.util.Optional<UUID> after) {
        return async(() -> {
            requireTenantId(tenantId);
            requireWorkerId(workerId);
            requireLimit(limit);
            requireLeaseTtl(leaseTtl);
            Objects.requireNonNull(after, "after");
            return read(null, connection -> {
                String sql = "SELECT process_instance_id FROM process_instance WHERE tenant_id = ? "
                        + (after.isPresent() ? "AND process_instance_id > ? " : "")
                        + "ORDER BY process_instance_id LIMIT ?";
                var keys = new ArrayList<ExecutionKey>();
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    int index = 1;
                    statement.setString(index++, tenantId);
                    if (after.isPresent()) StoredUuid.bind(statement, index++, after.orElseThrow());
                    statement.setInt(index, limit);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) keys.add(new ExecutionKey(tenantId,
                                StoredUuid.required(rows, "process_instance", "process_instance_id", tenantId)));
                    }
                }
                java.util.Optional<UUID> next = keys.size() == limit
                        ? java.util.Optional.of(keys.get(keys.size() - 1).processInstanceId())
                        : java.util.Optional.empty();
                return new ai.ravenroot.api.persistence.ManagedClaimCandidatePage(keys, next);
            });
        });
    }

    private static java.util.NavigableMap<ExecutionKey,
            ai.ravenroot.api.persistence.ExecutionPersistenceAuthority> requireVerifiedAuthorities(
                    String tenantId,
                    java.util.Map<ExecutionKey,
                            ai.ravenroot.api.persistence.ExecutionPersistenceAuthority> verified) {
        Objects.requireNonNull(verified, "verified");
        var ordered = new java.util.TreeMap<ExecutionKey,
                ai.ravenroot.api.persistence.ExecutionPersistenceAuthority>(
                        java.util.Comparator.comparing(key -> key.processInstanceId().toString()));
        verified.forEach((key, authority) -> {
            Objects.requireNonNull(key, "verified key");
            Objects.requireNonNull(authority, "verified authority");
            if (!tenantId.equals(key.tenantId())) throw failure(ExecutionStoreFailure.invalid(
                    "managed claim authority belongs to another tenant"));
            ordered.put(key, authority);
        });
        return ordered;
    }

    @Override
    public CompletionStage<Void> ack(PendingWork item) {
        return async(() -> {
            Objects.requireNonNull(item, "item");
            ExecutionKey key = item.key();
            return write(key, connection -> {
                // FOR UPDATE: the fence check below and the deletion under it are one decision, and a
                // claim committing between them would let a worker that has just been fenced out
                // acknowledge -- and so suppress -- work its replacement is about to be handed.
                InstanceMeta meta = readMeta(connection, key, true);
                if (meta == null) {
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                if (meta.fencingToken() != item.fencingToken()) {
                    throw failure(new ExecutionStoreFailure.FencedOut(key, item.fencingToken(),
                            meta.fencingToken()));
                }
                int removed;
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM work_claim WHERE tenant_id = ? AND process_instance_id = ? "
                                + "AND work_item_id = ?")) {
                    bindItem(statement, key, item.workItemId());
                    removed = statement.executeUpdate();
                }
                if (removed == 0) {
                    throw failure(ExecutionStoreFailure.unknownWorkItem(item.workItemId()));
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO work_acknowledgement (tenant_id, process_instance_id, work_item_id) "
                                + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
                    bindItem(statement, key, item.workItemId());
                    statement.executeUpdate();
                }
                if (item instanceof PendingWork.TimerDue) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "DELETE FROM timer WHERE tenant_id = ? AND process_instance_id = ? "
                                    + "AND timer_id = ?")) {
                        bindItem(statement, key, item.workItemId());
                        statement.executeUpdate();
                    }
                }
                return null;
            });
        });
    }

    // ---------------------------------------------------------------- idempotency

    @Override
    public CompletionStage<Optional<IdempotencyRecord>> lookupIdempotency(String tenantId, String key,
                                                                          Instant keyIssuedAt) {
        return async(() -> {
            requireTenantId(tenantId);
            if (key == null || key.isBlank()) {
                throw failure(ExecutionStoreFailure.invalid("idempotency key cannot be blank"));
            }
            return read(null, connection -> {
                requireIssuanceWithinSkewBudget(keyIssuedAt);
                // The record is read BEFORE the watermark, and the order is load-bearing rather than
                // incidental. Under READ COMMITTED each statement sees its own snapshot, so a purge can
                // land between the two; reading the record first means the watermark this call compares
                // against is at least as advanced as the one in force when the record was looked for,
                // and every ambiguity therefore falls towards "expired" rather than towards "safe to
                // apply". The reverse order fails open, which is a silent re-execution.
                IdempotencyRecord record = readIdempotencyRecord(connection, tenantId, key);
                if (record != null) {
                    return Optional.of(record);
                }
                if (provablyNeverRecorded(connection, tenantId, keyIssuedAt)) {
                    // Any record for this key would still carry an expiresAt at or above the tenant's
                    // watermark, so its absence PROVES it was never written and the caller may safely
                    // apply. That is an absent answer, not a missing entity.
                    return Optional.empty();
                }
                // Expiry stays a failure. Empty means proceed, a failure means stop and resolve;
                // collapsing them in either direction silently re-executes completed work.
                throw failure(new ExecutionStoreFailure.IdempotencyRecordExpired(key));
            });
        });
    }

    @Override
    public CompletionStage<Long> idempotencyRecordCount(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            return read(null, connection -> countOf(connection,
                    "SELECT COUNT(*) FROM idempotency_record WHERE tenant_id = ?", tenantId));
        });
    }

    /**
     * Purges in <strong>two</strong> transactions: the watermark advances in the first, the records are
     * deleted in the second.
     *
     * <p>The order is load-bearing — advance, then delete — and one transaction would make it
     * unobservable, because a crash would undo both and the ordering would never have been tested
     * against anything. Split, the interrupted state is real and reachable, and it is the conservative
     * one: the watermark is ahead of the deletions, and a record still present is still answered
     * <em>from the record</em>, so an early watermark costs nothing. The reverse — deleting first and
     * dying before the watermark moved — would leave absent records below a stale watermark, which
     * reads as "never recorded" and silently re-executes.</p>
     *
     * <p>The zero guard is equally load-bearing in the other direction. A purge that forgot nothing must
     * not move the watermark at all: doing so would destroy provable absence for every key the tenant
     * issued before that instant, and a periodic purge job would inflict it on every tick.</p>
     */
    @Override
    public CompletionStage<Long> purgeExpiredIdempotencyRecords(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            Instant now = clock.instant();

            boolean anythingToForget = write(null, connection -> {
                String sql = "SELECT COUNT(*) FROM idempotency_record WHERE tenant_id = ? AND "
                        + StoredInstant.strictlyBefore("expires_at");
                long expired;
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, tenantId);
                    StoredInstant.bindComparison(statement, 2, now);
                    try (ResultSet rows = statement.executeQuery()) {
                        expired = rows.next() ? rows.getLong(1) : 0L;
                    }
                }
                if (expired == 0L) {
                    return false;
                }
                advanceWatermark(connection, "idempotency_watermark", "forgotten_before", tenantId, now);
                return true;
            });
            if (!anythingToForget) {
                return 0L;
            }
            return write(null, connection -> {
                String sql = "DELETE FROM idempotency_record WHERE tenant_id = ? AND "
                        + StoredInstant.strictlyBefore("expires_at");
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, tenantId);
                    StoredInstant.bindComparison(statement, 2, now);
                    return (long) statement.executeUpdate();
                }
            });
        });
    }

    @Override
    public CompletionStage<Instant> forgottenBefore(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            return read(null, connection -> watermarkOf(connection, "idempotency_watermark",
                    "forgotten_before", tenantId));
        });
    }

    // ---------------------------------------------------------------- durable execution inventory

    @Override
    public CompletionStage<ProcessInventoryPage> listProcessInstances(String tenantId,
                                                                      ProcessInventoryQuery query) {
        return async(() -> {
            requireTenantId(tenantId);
            requireInventoryQuery(query);
            InventoryCursor.Position after = query.cursor()
                    .map(cursor -> InventoryCursor.decode(tenantId, cursor))
                    .orElse(null);
            // read, not readFolded, and the statement order is what makes that safe. The page is
            // read before the retention floor, so a purge committing between them can only raise the
            // floor above a row the page already returned - which the page's contract permits, since a
            // row present below the floor is a row that outlived the guarantee rather than one that
            // was invented. Reading the floor first would allow the opposite and inadmissible pairing:
            // a floor that promises completeness over rows the page has already lost.
            return read(null, connection -> {
                Instant now = clock.instant();
                var sql = new StringBuilder(INVENTORY_COLUMNS).append("WHERE p.tenant_id = ?");
                var binds = new ArrayList<Object>();
                binds.add(tenantId);

                if (!query.statuses().isEmpty()) {
                    sql.append(" AND p.status IN (")
                            .append("?, ".repeat(query.statuses().size() - 1)).append("?)");
                    query.statuses().stream().map(Enum::name).forEach(binds::add);
                }
                if (!query.includeTerminal()) {
                    // Built from the enum rather than written out, so a status added later is
                    // classified by the domain's own terminal() rather than by a literal here that
                    // nobody would remember to revisit.
                    var terminal = terminalStatusNames();
                    sql.append(" AND p.status NOT IN (")
                            .append("?, ".repeat(terminal.size() - 1)).append("?)");
                    binds.addAll(terminal);
                }
                query.deploymentId().ifPresent(deployment -> {
                    sql.append(" AND p.deployment_id = ?");
                    binds.add(deployment);
                });
                query.ownerWorkerId().ifPresent(owner -> {
                    // A live lease only. A lapsed lease names the worker that has stopped renewing, and
                    // answering "owned by w" with work w has abandoned is the opposite of what an
                    // operator draining a worker is asking for.
                    sql.append(" AND l.worker_id = ? AND ")
                            .append(StoredInstant.strictlyAfter("l.expires_at"));
                    binds.add(owner);
                    binds.add(now);
                });
                if (after != null) {
                    // Strictly after the cursor under (created_at DESC, process_instance_id DESC), as a
                    // single row comparison rather than the unrolled disjunction the text-keyed adapter
                    // has to write. The third component is a native uuid, and that is sound only
                    // because PostgreSQL compares uuid bytewise while the other adapters compare the
                    // canonical lowercase string -- which is the same order, character for character.
                    // It is deliberately NOT java.util.UUID.compareTo, which is signed and would
                    // disagree for half of all identifiers.
                    sql.append(" AND (p.created_at_epoch_second, p.created_at_nano, "
                            + "p.process_instance_id) < (?, ?, ?)");
                    binds.add(after.createdAt().getEpochSecond());
                    binds.add(after.createdAt().getNano());
                    binds.add(after.processInstanceId());
                }
                sql.append(" ORDER BY p.created_at_epoch_second DESC, p.created_at_nano DESC, "
                        + "p.process_instance_id DESC LIMIT ?");

                var page = new ArrayList<ProcessInventoryEntry>(query.limit());
                boolean more = false;
                try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                    int index = 1;
                    for (Object bind : binds) {
                        index = bindInventoryArgument(statement, index, bind);
                    }
                    // One row past the page. A next cursor minted merely because the page filled would
                    // cost every caller an empty round trip, and a caller that reads a present cursor as
                    // "there is more" would report outstanding work that does not exist.
                    statement.setInt(index, query.limit() + 1);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            if (page.size() == query.limit()) {
                                more = true;
                                break;
                            }
                            page.add(readInventoryRow(tenantId, rows, now, null));
                        }
                    }
                }
                Optional<String> next = more
                        ? Optional.of(InventoryCursor.encode(tenantId, page.getLast().createdAt(),
                                page.getLast().key().processInstanceId()))
                        : Optional.empty();
                return new ProcessInventoryPage(List.copyOf(page), next,
                        watermarkOf(connection, "inventory_watermark", "retained_from", tenantId));
            });
        });
    }

    @Override
    public CompletionStage<Optional<ProcessInventoryEntry>> findProcessInstance(ExecutionKey key) {
        return async(() -> {
            Objects.requireNonNull(key, "key");
            return read(key, connection -> {
                Instant now = clock.instant();
                try (PreparedStatement statement = connection.prepareStatement(INVENTORY_COLUMNS
                        + "WHERE p.tenant_id = ? AND p.process_instance_id = ?")) {
                    // Both halves of the key are in the predicate, so a row belonging to another tenant
                    // is not excluded by a check that could be forgotten -- it is not selected at all.
                    // Absent and not-yours are therefore the same empty answer by construction, and the
                    // store cannot be used as a cross-tenant existence oracle.
                    statement.setString(1, key.tenantId());
                    StoredUuid.bind(statement, 2, key.processInstanceId());
                    try (ResultSet rows = statement.executeQuery()) {
                        return rows.next()
                                ? Optional.of(readInventoryRow(key.tenantId(), rows, now, key))
                                : Optional.<ProcessInventoryEntry>empty();
                    }
                }
            });
        });
    }

    @Override
    public CompletionStage<List<TraversalInventoryEntry>> listTraversals(ExecutionKey key) {
        return async(() -> {
            Objects.requireNonNull(key, "key");
            return readFolded(key, connection -> {
                InstanceMeta meta = readMeta(connection, key, false);
                if (meta == null) {
                    // NotFound rather than an empty list: an instance that exists with no traversals
                    // honestly reports none, and collapsing the two would make "you asked about
                    // nothing" indistinguishable from "it has nothing".
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                boolean leaseLive = leaseLive(connection, key, clock.instant());
                String sql = "SELECT t.traversal_id, t.position, t.ingress_node_id, t.status, "
                        + "t.termination_reason, "
                        + "(SELECT COUNT(*) FROM invocation i WHERE i.tenant_id = t.tenant_id "
                        + "AND i.process_instance_id = t.process_instance_id "
                        + "AND i.traversal_id = t.traversal_id) AS invocation_count, "
                        + "(SELECT COUNT(*) FROM attempt a JOIN invocation i2 "
                        + "ON i2.tenant_id = a.tenant_id "
                        + "AND i2.process_instance_id = a.process_instance_id "
                        + "AND i2.invocation_id = a.invocation_id "
                        + "WHERE a.tenant_id = t.tenant_id "
                        + "AND a.process_instance_id = t.process_instance_id "
                        + "AND i2.traversal_id = t.traversal_id AND a.status = 'PARKED') AS parked_count "
                        + "FROM traversal t WHERE t.tenant_id = ? AND t.process_instance_id = ? "
                        + "ORDER BY t.position";
                var rowsOut = new ArrayList<TraversalInventoryEntry>();
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, key.tenantId());
                    StoredUuid.bind(statement, 2, key.processInstanceId());
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            TraversalStatus status = traversalStatusOf(key, rows.getString("status"));
                            int parked = rows.getInt("parked_count");
                            rowsOut.add(new TraversalInventoryEntry(key,
                                    StoredUuid.required(rows, "traversal", "traversal_id", key),
                                    rows.getInt("position"), rows.getString("ingress_node_id"), status,
                                    InventoryDisposition.ofTraversal(status, leaseLive, parked > 0),
                                    rows.getInt("invocation_count"), parked,
                                    terminationReasonOf(key, rows.getString("termination_reason"))));
                        }
                    }
                }
                return List.copyOf(rowsOut);
            });
        });
    }

    @Override
    public CompletionStage<Instant> inventoryRetainedFrom(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            return read(null, connection -> watermarkOf(connection, "inventory_watermark",
                    "retained_from", tenantId));
        });
    }

    /**
     * Purges in <strong>two</strong> transactions, floor first and deletions second, for the reason
     * {@link #purgeExpiredIdempotencyRecords(String)} records: the interrupted state has to be the
     * conservative one. A floor ahead of the deletions says "rows before this instant may be gone" while
     * they are in fact still present — a false alarm the surviving row itself answers. The reverse
     * leaves rows genuinely gone under a floor that claims completeness, and every absent instance then
     * reads as one that never existed.
     */
    @Override
    public CompletionStage<Long> purgeExpiredProcessInstances(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            Instant now = clock.instant();
            // updated_at + retention <= now, rearranged so the comparison is against a stored column
            // rather than against an expression the planner would have to evaluate per row.
            Instant lapsed = minusClamped(now, config.terminalRetention());

            Instant floor = write(null, connection -> latestExpiredDeadline(connection, tenantId, now, lapsed));
            if (floor == null) {
                return 0L;
            }
            write(null, connection -> {
                advanceWatermark(connection, "inventory_watermark", "retained_from", tenantId, floor);
                return null;
            });
            return write(null, connection -> {
                // ON DELETE CASCADE clears the traversals, invocations, causal edges, attempts, timers,
                // leases, work bookkeeping and results of every removed instance. The journal is
                // deliberately NOT cascaded: its offsets must never be reissued, and
                // journal_stream_sequence keeps the per-instance counter of a removed instance for
                // exactly that reason.
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM process_instance WHERE tenant_id = ? AND process_instance_id IN ("
                                + expiredInstanceIdQuery() + ")")) {
                    bindExpiredInstanceQuery(statement, tenantId, now, lapsed);
                    return (long) statement.executeUpdate();
                }
            });
        });
    }

    // ---------------------------------------------------------------- durable execution results

    /**
     * Writes the result, or recognises that it is already written, inside one transaction.
     *
     * <p>The insert is {@code ON CONFLICT DO NOTHING} rather than a bare insert, and that is what makes
     * two hosts recording the same terminal event concurrently safe. Both read no row and both attempt
     * the insert; the loser writes nothing, re-reads the winner's row and answers from its fingerprint —
     * which is the same answer it would have given a moment later. A bare insert would abort the loser's
     * whole transaction on a unique violation, so the idempotent success would have to be recovered by
     * retrying an operation that had already succeeded.</p>
     *
     * <p>The deadline is computed from {@code endedAt} rather than from now, so the window a caller is
     * promised starts when the execution ended and not when the write happened to land. A retry after an
     * ambiguous write therefore reproduces the identical deadline, which is what lets the fingerprint
     * comparison stay a comparison of what the producer decided.</p>
     */
    @Override
    public CompletionStage<DurableExecutionResult> recordExecutionResult(DurableExecutionResult result) {
        return recordExecutionResult(result, config.maxPayloadBytes());
    }

    @Override
    public CompletionStage<DurableExecutionResult> recordExecutionResult(
            DurableExecutionResult result, int resolvedMaximumPayloadBytes) {
        return async(() -> {
            Objects.requireNonNull(result, "result");
            ExecutionKey key = result.key();
            requireResultPayloadWithinLimit(result, resolvedMaximumPayloadBytes);
            DurableExecutionResult candidate = result.withRetainedUntil(
                    plusClamped(result.endedAt(), config.executionResultRetention()));
            Instant now = clock.instant();
            return write(key, connection -> {
                DurableExecutionResult stored = readExecutionResult(connection, key.tenantId(),
                        result.traversalId());
                if (stored != null) {
                    return reconcile(stored, candidate);
                }
                requireInstanceExists(connection, key);
                boolean written;
                try {
                    written = insertExecutionResult(connection, candidate, now);
                } catch (SQLException failed) {
                    if (SqlStates.isForeignKeyViolation(failed)) {
                        // The instance was there a statement ago and is not there now: a purge or a
                        // cascade committed in between. The foreign key is the authority rather than
                        // the check above, which is why this is reported as the same NotFound and not
                        // as a generic collision -- a result whose instance is gone is a dangling row,
                        // and refusing it is the whole reason the key exists.
                        throw failure(new ExecutionStoreFailure.NotFound(key));
                    }
                    throw failed;
                }
                if (written) {
                    return candidate;
                }
                DurableExecutionResult raced = readExecutionResult(connection, key.tenantId(),
                        result.traversalId());
                if (raced == null) {
                    // The insert was refused by the primary key and the row is not there: the only way
                    // that happens is a delete committing between the two statements, which means the
                    // instance itself has gone.
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                return reconcile(raced, candidate);
            });
        });
    }

    private static DurableExecutionResult reconcile(DurableExecutionResult stored,
                                                    DurableExecutionResult candidate) {
        if (stored.fingerprint().equals(candidate.fingerprint())) {
            return stored;
        }
        throw failure(new ExecutionStoreFailure.ExecutionResultNotRecordable(candidate.traversalId(),
                stored.status(), candidate.status(), stored.fingerprint(), candidate.fingerprint()));
    }

    @Override
    public CompletionStage<Optional<DurableExecutionResult>> loadExecutionResult(String tenantId,
                                                                                 UUID traversalId) {
        return async(() -> {
            requireTenantId(tenantId);
            Objects.requireNonNull(traversalId, "traversalId");
            Instant now = clock.instant();
            return readFolded(null, connection -> {
                DurableExecutionResult stored = readExecutionResult(connection, tenantId, traversalId);
                if (stored == null) {
                    return Optional.<DurableExecutionResult>empty();
                }
                // Retained while now < retainedUntil, strictly, so the boundary matches the purge's
                // exactly: a row eligible for collection is a row whose payload this read no longer
                // offers. Any other pairing would produce an instant at which a result is purgeable and
                // still readable in full, or readable as expired and not yet purgeable.
                return Optional.of(now.isBefore(stored.retainedUntil()) ? stored : stored.expired());
            });
        });
    }

    @Override
    public CompletionStage<Instant> executionResultsRetainedFrom(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            return read(null, connection -> watermarkOf(connection, "execution_result_watermark",
                    "retained_from", tenantId));
        });
    }

    @Override
    public CompletionStage<Long> purgeExpiredExecutionResults(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            Instant now = clock.instant();
            Instant floor = write(null, connection -> latestExpiredResultDeadline(connection, tenantId, now));
            if (floor == null) {
                // A purge that removed nothing must leave the floor where it is. Advancing it would
                // report a retention gap that does not exist, on every tick of a periodic job.
                return 0L;
            }
            write(null, connection -> {
                advanceWatermark(connection, "execution_result_watermark", "retained_from", tenantId, floor);
                return null;
            });
            return write(null, connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM execution_result WHERE tenant_id = ? AND "
                                + StoredInstant.atOrBefore("retained_until"))) {
                    statement.setString(1, tenantId);
                    StoredInstant.bindComparison(statement, 2, now);
                    return (long) statement.executeUpdate();
                }
            });
        });
    }

    // ---------------------------------------------------------------- event journal and outbox

    @Override
    public CompletionStage<List<JournalRecord>> readJournal(String tenantId, long afterOffset, int limit) {
        return async(() -> {
            requireTenantId(tenantId);
            if (afterOffset < 0) {
                throw failure(ExecutionStoreFailure.invalid("afterOffset cannot be negative"));
            }
            requireLimit(limit);
            return readFolded(null, connection -> {
                long retainedFrom = readWatermarkColumn(connection, tenantId, "retained_from");
                // Strictly below: a caller resuming from the last offset it saw is asking for what comes
                // after a record it already holds, and that record being the oldest survivor is the
                // healthy steady state rather than a truncation.
                if (afterOffset + 1 < retainedFrom) {
                    throw failure(new ExecutionStoreFailure.JournalTruncated(tenantId, afterOffset,
                            retainedFrom));
                }
                var page = new ArrayList<JournalRecord>();
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT * FROM event_journal WHERE tenant_id = ? AND journal_offset > ? "
                                + "ORDER BY journal_offset LIMIT ?")) {
                    statement.setString(1, tenantId);
                    statement.setLong(2, afterOffset);
                    statement.setInt(3, limit);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            page.add(readJournalRecord(tenantId, rows));
                        }
                    }
                }
                return List.copyOf(page);
            });
        });
    }

    @Override
    public CompletionStage<Long> journalRetainedFrom(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            return read(null, connection -> readWatermarkColumn(connection, tenantId, "retained_from"));
        });
    }

    @Override
    public CompletionStage<JournalCursor> outboxCursor(String tenantId, String destination) {
        return async(() -> {
            requireTenantId(tenantId);
            requireDestination(destination);
            return read(null, connection -> new JournalCursor(tenantId, destination,
                    readCursor(connection, tenantId, destination)));
        });
    }

    /**
     * Advances a destination's cursor, as a compare-and-set the database performs rather than one this
     * process performs on its behalf.
     *
     * <p>Both arms carry the expectation. The update's {@code WHERE} names the position the caller
     * decided from, and the insert runs only when that position is the start — because an absent row
     * <em>is</em> position zero, and an insert accepted under any other expectation would silently
     * fabricate agreement with a cursor that never existed. Reading the stored value first and updating
     * second is the same logic with a window in it, and the window is where two publishers both advance
     * past events only one of them delivered — a silent loss rather than a duplicate.</p>
     */
    @Override
    public CompletionStage<JournalCursor> advanceOutboxCursor(JournalCursor expected, long throughOffset) {
        return async(() -> {
            Objects.requireNonNull(expected, "expected");
            if (throughOffset < expected.deliveredThrough()) {
                throw failure(ExecutionStoreFailure.invalid("a cursor cannot retreat: destination "
                        + expected.destination() + " is at " + expected.deliveredThrough()
                        + " and was asked to move to " + throughOffset));
            }
            return write(null, connection -> {
                int moved;
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE outbox_cursor SET delivered_through = ? WHERE tenant_id = ? "
                                + "AND destination = ? AND delivered_through = ?")) {
                    statement.setLong(1, throughOffset);
                    statement.setString(2, expected.tenantId());
                    statement.setString(3, expected.destination());
                    statement.setLong(4, expected.deliveredThrough());
                    moved = statement.executeUpdate();
                }
                if (moved == 0 && expected.deliveredThrough() == 0L) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO outbox_cursor (tenant_id, destination, delivered_through) "
                                    + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
                        statement.setString(1, expected.tenantId());
                        statement.setString(2, expected.destination());
                        statement.setLong(3, throughOffset);
                        moved = statement.executeUpdate();
                    }
                }
                if (moved == 0) {
                    throw failure(new ExecutionStoreFailure.OutboxCursorConflict(expected.tenantId(),
                            expected.destination(), expected.deliveredThrough(),
                            readCursor(connection, expected.tenantId(), expected.destination())));
                }
                return new JournalCursor(expected.tenantId(), expected.destination(), throughOffset);
            });
        });
    }

    @Override
    public CompletionStage<Boolean> recordInboxDelivery(String tenantId, String consumerId, UUID eventId,
                                                        Duration retention) {
        return async(() -> {
            requireTenantId(tenantId);
            if (consumerId == null || consumerId.isBlank()) {
                throw failure(ExecutionStoreFailure.invalid("consumerId cannot be blank"));
            }
            Objects.requireNonNull(eventId, "eventId");
            if (retention == null || retention.isZero() || retention.isNegative()) {
                throw failure(ExecutionStoreFailure.invalid(
                        "inbox retention must be positive and is mandatory"));
            }
            return write(null, connection -> {
                Instant expiresAt = plusClamped(clock.instant(), retention);
                // The insert IS the decision. Two consumers racing on the same event both attempt it and
                // exactly one row appears, so exactly one of them is told this is a first delivery -- a
                // probe followed by an insert would tell both of them, and both would apply the effect.
                int inserted;
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO inbox_record (tenant_id, consumer_id, event_id, "
                                + "expires_at_epoch_second, expires_at_nano) VALUES (?, ?, ?, ?, ?) "
                                + "ON CONFLICT DO NOTHING")) {
                    statement.setString(1, tenantId);
                    statement.setString(2, consumerId);
                    StoredUuid.bind(statement, 3, eventId);
                    StoredInstant.bindValue(statement, 4, expiresAt);
                    inserted = statement.executeUpdate();
                }
                if (inserted > 0) {
                    return Boolean.TRUE;
                }
                // Already recorded, so the caller must not apply the effect. The expiry is extended
                // rather than left alone: a redelivery is evidence the sender still believes this event
                // is in flight, so forgetting it on the original schedule would let the next redelivery
                // be treated as a first delivery. The extension is conditional on the stored value being
                // the earlier one, so two concurrent redeliveries cannot move it backwards.
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE inbox_record SET expires_at_epoch_second = ?, expires_at_nano = ? "
                                + "WHERE tenant_id = ? AND consumer_id = ? AND event_id = ? AND "
                                + StoredInstant.strictlyBefore("expires_at"))) {
                    int index = StoredInstant.bindValue(statement, 1, expiresAt);
                    statement.setString(index++, tenantId);
                    statement.setString(index++, consumerId);
                    StoredUuid.bind(statement, index++, eventId);
                    StoredInstant.bindComparison(statement, index, expiresAt);
                    statement.executeUpdate();
                }
                return Boolean.FALSE;
            });
        });
    }

    @Override
    public CompletionStage<Long> inboxRecordCount(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            return read(null, connection -> countOf(connection,
                    "SELECT COUNT(*) FROM inbox_record WHERE tenant_id = ?", tenantId));
        });
    }

    @Override
    public CompletionStage<Long> compactJournal(String tenantId) {
        return async(() -> {
            requireTenantId(tenantId);
            return write(null, connection -> {
                // "Delivered by every known destination" is the minimum over all cursors, and with no
                // destination at all it is zero, so nothing is compactable. That is the conservative
                // direction and it is deliberate: reading "nobody is listening" as "everybody has
                // received it" would discard the whole backlog of a deployment whose projection has not
                // been enabled yet, and no publisher would ever notice, because a publisher that never
                // saw an event has nothing to miss.
                long deliveredEverywhere = minimumCursor(connection, tenantId);
                Instant cutoff = minusClamped(clock.instant(), config.journalRetention());

                // Only a contiguous prefix goes. Punching a hole in the middle would leave surviving
                // offsets that no single retained_from could honestly describe.
                //
                // The allocation ceiling is read FIRST, and the order is load-bearing. Every statement
                // in this transaction takes its own snapshot at READ COMMITTED, so reading it after the
                // survivor query would let an apply that commits in between raise next_offset, and the
                // fallback below would then compute a ceiling covering an event that was allocated
                // after the decision to compact - deleting a brand-new, undelivered, in-retention
                // record and recording a floor as though it had been legitimately compacted. Read
                // before, and the ceiling can only ever be lower than the truth, which discards
                // nothing and merely leaves a record for the next compaction.
                long allocatedThrough = readWatermarkColumn(connection, tenantId, "next_offset") - 1;
                long ceiling;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT MIN(journal_offset) FROM event_journal WHERE tenant_id = ? "
                                + "AND (journal_offset > ? OR "
                                + StoredInstant.strictlyAfter("recorded_at") + ")")) {
                    statement.setString(1, tenantId);
                    statement.setLong(2, deliveredEverywhere);
                    StoredInstant.bindComparison(statement, 3, cutoff);
                    try (ResultSet rows = statement.executeQuery()) {
                        long survivor = rows.next() ? rows.getLong(1) : 0L;
                        ceiling = rows.wasNull() || survivor == 0L ? Long.MAX_VALUE : survivor - 1;
                    }
                }
                if (ceiling == Long.MAX_VALUE) {
                    // Nothing survives the filter, so everything allocated when this transaction began
                    // is compactable - and nothing beyond it, which is what the earlier read buys.
                    ceiling = allocatedThrough;
                }

                long discarded;
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM event_journal WHERE tenant_id = ? AND journal_offset <= ?")) {
                    statement.setString(1, tenantId);
                    statement.setLong(2, ceiling);
                    discarded = statement.executeUpdate();
                }
                if (discarded > 0) {
                    writeRetainedFrom(connection, tenantId, ceiling + 1);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM inbox_record WHERE tenant_id = ? AND "
                                + StoredInstant.strictlyBefore("expires_at"))) {
                    statement.setString(1, tenantId);
                    StoredInstant.bindComparison(statement, 2, clock.instant());
                    statement.executeUpdate();
                }
                return discarded;
            });
        });
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Releases what the <em>process</em> owns and no lease, and in particular not the
     * {@link DataSource}.
     *
     * <p>A {@code kill -9} releases no leases, so this does not either: a clean shutdown and a crash
     * must differ only in latency, or crash recovery and orderly recovery would follow different paths
     * and every recovery test would be evidence about the path nobody experiences in production.</p>
     *
     * <p>The {@code DataSource} was handed to this store and may be shared with the graph-definition and
     * manifest stores of the same deployment; closing it here would take those down as a side effect of
     * closing this one.</p>
     */
    @Override
    public void close() {
        if (closed.getAndSet(true)) {
            return;
        }
        worker.shutdown();
    }

    // ---------------------------------------------------------------- batch helpers

    private StoredProcessInstance replayOf(Connection connection, ExecutionBatch batch,
                                           InstanceMeta existing) throws SQLException {
        IdempotencyWrite write = batch.idempotency().orElse(null);
        if (write == null) {
            return null;
        }
        // Rejected at WRITE time as well as at lookup, so an operator whose clock runs fast is told
        // while the clock can still be fixed rather than after the damage window has passed.
        requireIssuanceWithinSkewBudget(write.keyIssuedAt());
        ExecutionKey key = batch.key();
        IdempotencyRecord recorded = readIdempotencyRecord(connection, key.tenantId(), write.key());
        if (recorded == null) {
            if (provablyNeverRecorded(connection, key.tenantId(), write.keyIssuedAt())) {
                return null;
            }
            // Applying a batch whose key may have been purged is exactly the silent re-execution this
            // mechanism exists to prevent, so absence is classified on the write path too.
            throw failure(new ExecutionStoreFailure.IdempotencyRecordExpired(write.key()));
        }
        if (!recorded.requestFingerprint().equals(write.requestFingerprint())) {
            throw failure(new ExecutionStoreFailure.IdempotencyConflict(write.key()));
        }
        if (existing == null) {
            // A record can outlive its instance, because retention windows and instance lifetime are
            // independent. IdempotencyRecordExpired would be wrong here, because the store CAN answer
            // about the record; it is the instance that is gone.
            throw failure(new ExecutionStoreFailure.NotFound(key));
        }
        // A replay answers with CURRENT state, not the state as of recordedAtRevision: its purpose is to
        // let the caller proceed without re-executing, and a caller handed a stale revision derives an
        // expectation from it and loops on ConcurrencyConflict forever.
        return new StoredProcessInstance(readAggregate(connection, key, existing), existing.revision(),
                existing.graphVersionPin(), key.tenantId(), existing.updatedAt());
    }

    private static void requireFencingTokenCurrent(ExecutionKey key, ExecutionBatch batch,
                                                   InstanceMeta existing) {
        if (batch.fencingToken().isEmpty()) {
            return;
        }
        long presented = batch.fencingToken().getAsLong();
        // Inequality, not "lower than": a caller presenting an unissued higher token must not be able to
        // fence out the legitimate owner.
        if (presented != existing.fencingToken()) {
            throw failure(new ExecutionStoreFailure.FencedOut(key, presented, existing.fencingToken()));
        }
    }

    private static void requireExpectationMet(ExecutionKey key, RevisionExpectation expectation,
                                              InstanceMeta existing, ExecutionBatch batch) {
        switch (expectation) {
            case RevisionExpectation.NotPresent ignored -> {
                if (existing != null) {
                    throw failure(new ExecutionStoreFailure.AlreadyExists(key, existing.revision()));
                }
            }
            case RevisionExpectation.Exactly exactly -> {
                if (existing == null) {
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                if (existing.revision() != exactly.revision()) {
                    throw failure(new ExecutionStoreFailure.ConcurrencyConflict(key, expectation,
                            existing.revision()));
                }
            }
            case RevisionExpectation.Any ignored -> {
                if (existing == null && !createsInstance(batch)) {
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
            }
        }
    }

    private static ProcessInstance fold(ExecutionBatch batch, ProcessInstance current) {
        ProcessInstance folded = current;
        for (ExecutionTransition transition : batch.transitions()) {
            try {
                folded = transition.applyTo(folded);
            } catch (IllegalArgumentException | IllegalStateException rejected) {
                // A caller's illegal transition is a caller bug, not stored corruption. The two are told
                // apart by WHERE the rejection came from, not by its type: reconstruction of stored rows
                // is Corrupted, folding a caller's batch is InvalidRequest.
                throw new ExecutionStoreException(
                        ExecutionStoreFailure.invalid(rejected.getMessage()), rejected);
            }
        }
        if (folded == null) {
            throw failure(ExecutionStoreFailure.invalid("batch produced no aggregate state"));
        }
        return folded;
    }

    private static GraphVersionPin pinFor(ExecutionKey key, ExecutionBatch batch, InstanceMeta existing) {
        GraphVersionPin created = null;
        for (ExecutionTransition transition : batch.transitions()) {
            if (transition instanceof ExecutionTransition.ProcessCreated process) {
                if (existing != null) {
                    throw failure(ExecutionStoreFailure.invalid("graph version pin is write-once and "
                            + "cannot be reset on " + key.processInstanceId()));
                }
                created = process.graphVersionPin();
            }
        }
        if (created != null) {
            return created;
        }
        if (existing == null) {
            throw failure(ExecutionStoreFailure.invalid(
                    "a new process instance requires a graph version pin"));
        }
        return existing.graphVersionPin();
    }

    private static boolean createsInstance(ExecutionBatch batch) {
        return batch.transitions().stream().anyMatch(ExecutionTransition.ProcessCreated.class::isInstance);
    }

    private static long processTransitionCount(ExecutionBatch batch) {
        return batch.transitions().stream()
                .filter(ExecutionTransition.ProcessTransitioned.class::isInstance)
                .count();
    }

    // ---------------------------------------------------------------- row access

    /**
     * @param createdAt           write-once, and half of the inventory's sort key
     * @param lifecycleGeneration count of authoritative status transitions
     * @param retainedUntil       the <em>raw</em> stored column and not the answer a caller is given:
     *                            null while non-terminal. Every path that reports or acts on a deadline
     *                            resolves it through {@link #retentionDueAt} instead
     */
    private record InstanceMeta(long revision, long fencingToken, GraphVersionPin graphVersionPin,
                                ProcessInstanceStatus status,
                                ExecutionTerminationReason terminationReason, Instant updatedAt,
                                Instant createdAt, long lifecycleGeneration, ExecutionOrigin origin,
                                Instant retainedUntil) {
    }

    /** One instance the claim loop has locked, with the token its lease will be issued against. */
    private record Candidate(ExecutionKey key, long fencingToken) {
    }

    private record ScheduledAttempt(UUID traversalId, UUID invocationId, UUID attemptId, int ordinal,
                                    NodeCommand command) {
    }

    /**
     * Reads the instance's own row, optionally taking its lock for the rest of the transaction.
     *
     * <p>{@code forUpdate} is not an optimisation switch. Every caller that will write a value derived
     * from this row passes {@code true}, and the row is the serialization point for the instance's whole
     * lifecycle; a reader that only reports passes {@code false}, because taking a write lock to answer
     * a query would make a listing block a claim.</p>
     */
    private InstanceMeta readMeta(Connection connection, ExecutionKey key, boolean forUpdate)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                META_COLUMNS + (forUpdate ? " FOR UPDATE" : ""))) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new InstanceMeta(rows.getLong("revision"), rows.getLong("fencing_token"),
                        new GraphVersionPin(rows.getString("graph_version_pin")),
                        processStatusOf(key, rows.getString("status")),
                        terminationReasonOf(key, rows.getString("termination_reason")),
                        StoredInstant.read(rows, "updated_at"),
                        StoredInstant.read(rows, "created_at"),
                        rows.getLong("lifecycle_generation"),
                        ExecutionOrigin.of(rows.getString("deployment_id"), rows.getString("workload_id"),
                                rows.getString("correlation_id")),
                        nullableInstant(rows, "retained_until"));
            }
        }
    }

    /**
     * Reads a two-column instant that may be NULL, which the plain reader cannot express: it would
     * report a missing value as the epoch, and an epoch retention deadline is one that has already
     * passed.
     */
    private static Instant nullableInstant(ResultSet rows, String column) throws SQLException {
        long second = rows.getLong(column + "_epoch_second");
        if (rows.wasNull()) {
            return null;
        }
        return Instant.ofEpochSecond(second, rows.getInt(column + "_nano"));
    }

    /**
     * Maps an unrecognised stored status name to {@link ExecutionStoreFailure.Corrupted}.
     *
     * <p>Statuses are persisted by name, which is what lets a member be added without a data migration,
     * and the cost of that is a one-way rollback gate: a row written by a newer binary is unreadable by
     * an older one. It has to surface as corruption rather than as a raw exception escaping the port,
     * and above all it must never be skipped — an inventory that silently dropped the rows it could not
     * parse would report a shorter, cleaner world than the one in the database, and nothing would say
     * so.</p>
     */
    private static ProcessInstanceStatus processStatusOf(ExecutionKey key, String name) {
        try {
            return ProcessInstanceStatus.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new ExecutionStoreException(new ExecutionStoreFailure.Corrupted(key,
                    "process instance status '" + name + "' is not a status this build understands"),
                    unknown);
        }
    }

    /**
     * Maps a stored termination reason, distinguishing an absent one from an unreadable one.
     *
     * <p>NULL is the ordinary case and a meaningful one: nothing distinguishes this termination. A
     * <em>name</em> this build does not know is the rollback case, and it takes the route an unknown
     * status name takes — {@link ExecutionStoreFailure.Corrupted}, loudly. Reading it as an absent
     * reason would report a run that was cancelled as one that failed, which is the misreading the
     * reason exists to prevent, restored by the very code meant to carry it.
     */
    private static ExecutionTerminationReason terminationReasonOf(ExecutionKey key, String name) {
        if (name == null) {
            return null;
        }
        try {
            return ExecutionTerminationReason.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            throw new ExecutionStoreException(new ExecutionStoreFailure.Corrupted(key,
                    "termination reason '" + name + "' is not a reason this build understands"), unknown);
        }
    }

    private static TraversalStatus traversalStatusOf(ExecutionKey key, String name) {
        try {
            return TraversalStatus.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new ExecutionStoreException(new ExecutionStoreFailure.Corrupted(key,
                    "traversal status '" + name + "' is not a status this build understands"), unknown);
        }
    }

    private ProcessInstance readAggregate(Connection connection, ExecutionKey key, InstanceMeta meta)
            throws SQLException {
        try {
            return AggregateStorage.read(connection, key, meta.status(), meta.terminationReason());
        } catch (IllegalArgumentException | IllegalStateException corrupted) {
            // Rows that no longer reconstruct into a legal aggregate must never escape into the runtime.
            throw new ExecutionStoreException(
                    new ExecutionStoreFailure.Corrupted(key, corrupted.getMessage()), corrupted);
        }
    }

    /**
     * Creates the instance's row, refusing rather than overwriting when another host got there first.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than an upsert, and rather than a bare insert. An upsert
     * would let two hosts that both read no row both write one, and the second would silently replace
     * the first's creation — the lost update this adapter exists to make impossible. A bare insert would
     * be correct but would abort the transaction on the unique violation, so the truthful revision could
     * not then be read to report it.</p>
     */
    private void insertInstanceRow(Connection connection, ExecutionKey key, ProcessInstance folded,
                                   GraphVersionPin pin, long revision, Instant now, Instant createdAt,
                                   long generation, ExecutionOrigin origin, Instant retainedUntil)
            throws SQLException {
        int inserted;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO process_instance (tenant_id, process_instance_id, status, "
                        + "termination_reason, graph_version_pin, revision, fencing_token, "
                        + "lifecycle_generation, deployment_id, workload_id, correlation_id, "
                        + "created_at_epoch_second, created_at_nano, updated_at_epoch_second, "
                        + "updated_at_nano, retained_until_epoch_second, retained_until_nano) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT DO NOTHING")) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            statement.setString(3, folded.status().name());
            statement.setString(4, folded.terminationReason() == null
                    ? null : folded.terminationReason().name());
            statement.setString(5, pin.reference());
            statement.setLong(6, revision);
            statement.setLong(7, generation);
            statement.setString(8, origin.deploymentId().orElse(null));
            statement.setString(9, origin.workloadId().orElse(null));
            statement.setString(10, origin.correlationId().orElse(null));
            int index = StoredInstant.bindValue(statement, 11, createdAt);
            index = StoredInstant.bindValue(statement, index, now);
            bindNullableInstant(statement, index, retainedUntil);
            inserted = statement.executeUpdate();
        }
        if (inserted == 0) {
            InstanceMeta raced = readMeta(connection, key, false);
            throw failure(new ExecutionStoreFailure.AlreadyExists(key,
                    raced == null ? revision : raced.revision()));
        }
    }

    /**
     * Advances the instance's row, with the revision the decision was made on in the {@code WHERE}.
     *
     * <p>The guard is redundant while the caller holds this row's {@code FOR UPDATE} lock, and it is
     * kept precisely because that is an argument about a caller rather than about this statement. A
     * conditional update is wrong only if the condition is wrong; an unconditional one is wrong the
     * moment any future caller reaches it without the lock, and nothing would say so.</p>
     *
     * <p>{@code created_at} and {@code fencing_token} are absent from the {@code SET}. The first is
     * write-once, which is what makes the inventory's ordering stable while writes continue; the second
     * belongs to the lease mechanism, and assigning it here would let a batch undo a claim.</p>
     */
    private void updateInstanceRow(Connection connection, ExecutionKey key, ProcessInstance folded,
                                   GraphVersionPin pin, long revision, Instant now, long generation,
                                   ExecutionOrigin origin, Instant retainedUntil, long expectedRevision)
            throws SQLException {
        int updated;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE process_instance SET status = ?, termination_reason = ?, "
                        + "graph_version_pin = ?, revision = ?, lifecycle_generation = ?, "
                        + "deployment_id = ?, workload_id = ?, correlation_id = ?, "
                        + "updated_at_epoch_second = ?, updated_at_nano = ?, "
                        + "retained_until_epoch_second = ?, retained_until_nano = ? "
                        + "WHERE tenant_id = ? AND process_instance_id = ? AND revision = ?")) {
            statement.setString(1, folded.status().name());
            // Assigned beside the status it qualifies and never apart from it: the pair is one fact, so
            // a row must never carry a new status with the previous reason still attached to it.
            statement.setString(2, folded.terminationReason() == null
                    ? null : folded.terminationReason().name());
            statement.setString(3, pin.reference());
            statement.setLong(4, revision);
            statement.setLong(5, generation);
            statement.setString(6, origin.deploymentId().orElse(null));
            statement.setString(7, origin.workloadId().orElse(null));
            statement.setString(8, origin.correlationId().orElse(null));
            int index = StoredInstant.bindValue(statement, 9, now);
            index = bindNullableInstant(statement, index, retainedUntil);
            statement.setString(index++, key.tenantId());
            StoredUuid.bind(statement, index++, key.processInstanceId());
            statement.setLong(index, expectedRevision);
            updated = statement.executeUpdate();
        }
        if (updated == 0) {
            InstanceMeta raced = readMeta(connection, key, false);
            throw failure(raced == null
                    ? new ExecutionStoreFailure.NotFound(key)
                    : new ExecutionStoreFailure.ConcurrencyConflict(key,
                            RevisionExpectation.exactly(expectedRevision), raced.revision()));
        }
    }

    private static int bindNullableInstant(PreparedStatement statement, int index, Instant value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
            statement.setNull(index + 1, Types.INTEGER);
            return index + 2;
        }
        return StoredInstant.bindValue(statement, index, value);
    }

    private void writeTimers(Connection connection, ExecutionKey key, ExecutionBatch batch)
            throws SQLException {
        for (UUID timerId : batch.timersToCancel()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM timer WHERE tenant_id = ? AND process_instance_id = ? AND timer_id = ?")) {
                bindItem(statement, key, timerId);
                statement.executeUpdate();
            }
        }
        // Cancellations first, then schedules: a batch that cancels and reschedules the same id must end
        // with the new timer, not with nothing.
        for (TimerSchedule timer : batch.timersToSchedule()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO timer (tenant_id, process_instance_id, timer_id, traversal_id, "
                            + "invocation_id, payload_content_type, payload_bytes, due_at_epoch_second, "
                            + "due_at_nano) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT (tenant_id, process_instance_id, timer_id) DO UPDATE SET "
                            + "traversal_id = EXCLUDED.traversal_id, "
                            + "invocation_id = EXCLUDED.invocation_id, "
                            + "payload_content_type = EXCLUDED.payload_content_type, "
                            + "payload_bytes = EXCLUDED.payload_bytes, "
                            + "due_at_epoch_second = EXCLUDED.due_at_epoch_second, "
                            + "due_at_nano = EXCLUDED.due_at_nano")) {
                bindItem(statement, key, timer.timerId());
                bindNullableUuid(statement, 4, timer.traversalId());
                bindNullableUuid(statement, 5, timer.invocationId());
                statement.setString(6, timer.payload().contentType());
                statement.setBytes(7, timer.payload().bytes());
                StoredInstant.bindValue(statement, 8, timer.dueAt());
                statement.executeUpdate();
            }
        }
    }

    private void writeIdempotencyRecord(Connection connection, ExecutionKey key, IdempotencyWrite write,
                                        long revision, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO idempotency_record (tenant_id, idempotency_key, "
                        + "request_fingerprint_content_type, request_fingerprint_bytes, "
                        + "outcome_ref_content_type, outcome_ref_bytes, recorded_at_revision, "
                        + "expires_at_epoch_second, expires_at_nano) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, idempotency_key) DO UPDATE SET "
                        + "outcome_ref_content_type = EXCLUDED.outcome_ref_content_type, "
                        + "outcome_ref_bytes = EXCLUDED.outcome_ref_bytes, "
                        + "recorded_at_revision = EXCLUDED.recorded_at_revision, "
                        + "expires_at_epoch_second = EXCLUDED.expires_at_epoch_second, "
                        + "expires_at_nano = EXCLUDED.expires_at_nano")) {
            statement.setString(1, key.tenantId());
            statement.setString(2, write.key());
            statement.setString(3, write.requestFingerprint().contentType());
            statement.setBytes(4, write.requestFingerprint().bytes());
            statement.setString(5, write.outcomeRef().contentType());
            statement.setBytes(6, write.outcomeRef().bytes());
            statement.setLong(7, revision);
            StoredInstant.bindValue(statement, 8, plusClamped(now, write.retentionWindow()));
            statement.executeUpdate();
        }
    }

    private IdempotencyRecord readIdempotencyRecord(Connection connection, String tenantId, String key)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT request_fingerprint_content_type, request_fingerprint_bytes, "
                        + "outcome_ref_content_type, outcome_ref_bytes, recorded_at_revision, "
                        + "expires_at_epoch_second, expires_at_nano FROM idempotency_record "
                        + "WHERE tenant_id = ? AND idempotency_key = ?")) {
            statement.setString(1, tenantId);
            statement.setString(2, key);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new IdempotencyRecord(key,
                        OpaquePayload.of(rows.getBytes("request_fingerprint_bytes"),
                                rows.getString("request_fingerprint_content_type")),
                        OpaquePayload.of(rows.getBytes("outcome_ref_bytes"),
                                rows.getString("outcome_ref_content_type")),
                        rows.getLong("recorded_at_revision"),
                        StoredInstant.read(rows, "expires_at"));
            }
        }
    }

    /**
     * Advances one of the three per-tenant floors, monotonically, in a single statement.
     *
     * <p>The {@code WHERE} on the conflict arm is what makes it monotone. Reading the stored floor and
     * writing a greater one is the read-then-write this adapter cannot have: two purges committing at
     * once would both read the old value and the later one could install the lower of the two new ones,
     * retracting a floor that a caller has already been shown. Expressed as a condition on the stored
     * row, a floor that would retreat simply does not update.</p>
     */
    private static void advanceWatermark(Connection connection, String table, String column,
                                         String tenantId, Instant floor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + table + " (tenant_id, " + column + "_epoch_second, " + column
                        + "_nano) VALUES (?, ?, ?) ON CONFLICT (tenant_id) DO UPDATE SET "
                        + column + "_epoch_second = EXCLUDED." + column + "_epoch_second, "
                        + column + "_nano = EXCLUDED." + column + "_nano "
                        + "WHERE (" + table + "." + column + "_epoch_second, " + table + "." + column
                        + "_nano) < (EXCLUDED." + column + "_epoch_second, EXCLUDED." + column
                        + "_nano)")) {
            statement.setString(1, tenantId);
            StoredInstant.bindValue(statement, 2, floor);
            statement.executeUpdate();
        }
    }

    private static Instant watermarkOf(Connection connection, String table, String column,
                                       String tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + column + "_epoch_second, " + column + "_nano FROM " + table
                        + " WHERE tenant_id = ?")) {
            statement.setString(1, tenantId);
            try (ResultSet rows = statement.executeQuery()) {
                // An absent row IS Instant.MIN. Writing one when a tenant first appears would record a
                // forgetting that never happened, in a table whose only purpose is to record one.
                return rows.next() ? StoredInstant.read(rows, column) : Instant.MIN;
            }
        }
    }

    // ---------------------------------------------------------------- leases and work

    private LeaseHandle readLease(Connection connection, ExecutionKey key, long fencingToken)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT worker_id, claimed_at_epoch_second, claimed_at_nano, expires_at_epoch_second, "
                        + "expires_at_nano FROM lease WHERE tenant_id = ? AND process_instance_id = ?")) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new LeaseHandle(key, rows.getString("worker_id"), fencingToken,
                        StoredInstant.read(rows, "claimed_at"), StoredInstant.read(rows, "expires_at"));
            }
        }
    }

    /**
     * Issues or extends a lease. Every caller holds the instance's {@code FOR UPDATE} lock.
     *
     * <p>The token increment is written as {@code fencing_token = fencing_token + 1} rather than as the
     * value this process computed, so the statement is correct even where the lock is not held. That is
     * belt and braces on top of the lock, and it is the cheap half of the pair: the lock is what makes
     * the surrounding <em>decisions</em> sound, and this makes the arithmetic sound on its own.</p>
     */
    private LeaseHandle issueLease(Connection connection, ExecutionKey key, long currentToken,
                                   LeaseHandle held, String workerId, Duration ttl, Instant now)
            throws SQLException {
        if (held != null && held.workerId().equals(workerId) && now.isBefore(held.expiresAt())) {
            // The same worker re-claiming keeps its token; reissuing would fence out its own writes.
            var extended = new LeaseHandle(key, workerId, currentToken, held.claimedAt(), now.plus(ttl));
            upsertLease(connection, extended);
            return extended;
        }
        long nextToken;
        // Durable, and never reset on reopen: the counter is a column on process_instance, so a restart
        // reads it back rather than starting again from zero. A session is not a fencing domain, and a
        // token that repeated across a restart would let a worker resurrected from an old handle write
        // as though it still owned the instance.
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE process_instance SET fencing_token = fencing_token + 1 "
                        + "WHERE tenant_id = ? AND process_instance_id = ? RETURNING fencing_token")) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
                nextToken = rows.getLong(1);
            }
        }
        var lease = new LeaseHandle(key, workerId, nextToken, now, now.plus(ttl));
        upsertLease(connection, lease);
        return lease;
    }

    private void upsertLease(Connection connection, LeaseHandle lease) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO lease (tenant_id, process_instance_id, worker_id, claimed_at_epoch_second, "
                        + "claimed_at_nano, expires_at_epoch_second, expires_at_nano) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, process_instance_id) DO UPDATE SET "
                        + "worker_id = EXCLUDED.worker_id, "
                        + "claimed_at_epoch_second = EXCLUDED.claimed_at_epoch_second, "
                        + "claimed_at_nano = EXCLUDED.claimed_at_nano, "
                        + "expires_at_epoch_second = EXCLUDED.expires_at_epoch_second, "
                        + "expires_at_nano = EXCLUDED.expires_at_nano")) {
            statement.setString(1, lease.key().tenantId());
            StoredUuid.bind(statement, 2, lease.key().processInstanceId());
            statement.setString(3, lease.workerId());
            int index = StoredInstant.bindValue(statement, 4, lease.claimedAt());
            StoredInstant.bindValue(statement, index, lease.expiresAt());
            statement.executeUpdate();
        }
    }

    private boolean leaseLive(Connection connection, ExecutionKey key, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT expires_at_epoch_second, expires_at_nano FROM lease "
                        + "WHERE tenant_id = ? AND process_instance_id = ?")) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            try (ResultSet rows = statement.executeQuery()) {
                // Strictly before the expiry, matching the lease boundary the rest of this adapter uses:
                // at exactly expiresAt the lease is already gone.
                return rows.next() && now.isBefore(StoredInstant.read(rows, "expires_at"));
            }
        }
    }

    /**
     * Locks the instances of {@code tenantId} that have work this worker may take, and no others.
     *
     * <p><strong>This is the query that decides whether the claim loop scales.</strong> The single-host
     * adapter enumerates every instance of the tenant and asks each one in turn, which is affordable
     * only because one process holds the whole database's write lock anyway. Here every worker in the
     * fleet runs this loop; enumerating the tenant would make them all touch every row, and they would
     * serialize on the tenant rather than on the work they are competing for.</p>
     *
     * <p>So the eligibility predicate is pushed into the database and the rows come back already locked:
     * {@code FOR UPDATE OF p SKIP LOCKED} takes the instance-row lock the fencing token is written under
     * and steps over rows another worker is already holding, so two workers polling at the same time
     * take disjoint sets instead of one waiting for the other. {@code SKIP LOCKED} may return fewer rows
     * than {@code LIMIT}; that is correct rather than tolerated, because delivery is at-least-once and a
     * poll that returns nothing is a poll, not a failure.</p>
     *
     * <p>The ordering is by identifier so the set is deterministic and so two workers acquire locks in
     * the same order — which, with {@code SKIP LOCKED}, means they cannot build a cycle between them.</p>
     *
     * <p>{@code includeNonTimerWork} distinguishes the general sweep from
     * {@link #claimDueTimers(String, String, int, Duration)}, which promises timers and only timers. It
     * is one flag rather than two because attempts and handler triggers are excluded together and for
     * the same reason: a caller that asked for due timers and was handed an attempt dispatch or a
     * handler trigger would have to pattern-match the answer to the question it did not ask.</p>
     */
    private List<Candidate> lockClaimable(Connection connection, String tenantId, String workerId,
                                          Instant now, int limit, boolean includeNonTimerWork)
            throws SQLException {
        String attemptArm = "EXISTS (SELECT 1 FROM attempt a "
                + "WHERE a.tenant_id = p.tenant_id AND a.process_instance_id = p.process_instance_id "
                + "AND a.status IN ('SCHEDULED', 'RUNNING') "
                + "AND NOT EXISTS (SELECT 1 FROM work_acknowledgement k WHERE k.tenant_id = a.tenant_id "
                + "AND k.process_instance_id = a.process_instance_id AND k.work_item_id = a.attempt_id) "
                + "AND NOT EXISTS (SELECT 1 FROM work_claim c WHERE c.tenant_id = a.tenant_id "
                + "AND c.process_instance_id = a.process_instance_id AND c.work_item_id = a.attempt_id "
                + "AND " + StoredInstant.strictlyAfter("c.visible_again_at") + "))";
        String timerArm = "EXISTS (SELECT 1 FROM timer t "
                + "WHERE t.tenant_id = p.tenant_id AND t.process_instance_id = p.process_instance_id "
                + "AND " + StoredInstant.atOrBefore("t.due_at") + " "
                + "AND NOT EXISTS (SELECT 1 FROM work_acknowledgement k WHERE k.tenant_id = t.tenant_id "
                + "AND k.process_instance_id = t.process_instance_id AND k.work_item_id = t.timer_id) "
                + "AND NOT EXISTS (SELECT 1 FROM work_claim c WHERE c.tenant_id = t.tenant_id "
                + "AND c.process_instance_id = t.process_instance_id AND c.work_item_id = t.timer_id "
                + "AND " + StoredInstant.strictlyAfter("c.visible_again_at") + "))";
        // A handler becomes work when it settles, never before: a waiting handler is state, and an
        // instance that offers a trigger for it would hand a claimant a traversal nothing has
        // authorized it to resume. There is no temporal predicate at all here -- the trigger is due
        // because a decision was recorded, not because a deadline passed -- which is why this arm
        // takes one instant bind where the timer arm takes two.
        String handlerArm = "EXISTS (SELECT 1 FROM execution_handler h "
                + "WHERE h.tenant_id = p.tenant_id AND h.process_instance_id = p.process_instance_id "
                + "AND h.status IN " + TERMINAL_HANDLER_STATUSES + " "
                + "AND NOT EXISTS (SELECT 1 FROM work_acknowledgement k WHERE k.tenant_id = h.tenant_id "
                + "AND k.process_instance_id = h.process_instance_id AND k.work_item_id = h.handler_id) "
                + "AND NOT EXISTS (SELECT 1 FROM work_claim c WHERE c.tenant_id = h.tenant_id "
                + "AND c.process_instance_id = h.process_instance_id AND c.work_item_id = h.handler_id "
                + "AND " + StoredInstant.strictlyAfter("c.visible_again_at") + "))";

        String sql = "SELECT p.process_instance_id, p.fencing_token FROM process_instance p "
                + "WHERE p.tenant_id = ? "
                // Held by somebody else and still live: skipped, exactly as the single-host adapter
                // skips it. A lapsed lease is not an obstacle, which is what makes a dead worker's work
                // recoverable without a reaper.
                + "AND NOT EXISTS (SELECT 1 FROM lease l WHERE l.tenant_id = p.tenant_id "
                + "AND l.process_instance_id = p.process_instance_id AND l.worker_id <> ? "
                + "AND " + StoredInstant.strictlyAfter("l.expires_at") + ") AND ("
                + (includeNonTimerWork ? attemptArm + " OR " : "") + timerArm
                + (includeNonTimerWork ? " OR " + handlerArm : "") + ") "
                + "ORDER BY p.process_instance_id LIMIT ? FOR UPDATE OF p SKIP LOCKED";

        var candidates = new ArrayList<Candidate>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, tenantId);
            statement.setString(index++, workerId);
            index = StoredInstant.bindComparison(statement, index, now);
            if (includeNonTimerWork) {
                index = StoredInstant.bindComparison(statement, index, now);
            }
            index = StoredInstant.bindComparison(statement, index, now);
            index = StoredInstant.bindComparison(statement, index, now);
            if (includeNonTimerWork) {
                index = StoredInstant.bindComparison(statement, index, now);
            }
            statement.setInt(index, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    var key = new ExecutionKey(tenantId, StoredUuid.required(rows, "process_instance",
                            "process_instance_id", tenantId));
                    candidates.add(new Candidate(key, rows.getLong("fencing_token")));
                }
            }
        }
        return candidates;
    }

    /**
     * Outstanding attempts that are neither acknowledged nor inside a live visibility window.
     *
     * <p>The visibility predicate is the negation of "still invisible", which is why it reads as
     * {@code NOT EXISTS (... AND visible_again_at > now)} rather than {@code visible_again_at <= now}:
     * an item with no claim row at all has never been delivered and must be claimable, and a plain
     * comparison against a missing row is never true.</p>
     *
     * <p>{@code RUNNING} is claimable and {@code PARKED} is not. An attempt stuck in {@code RUNNING} is
     * exactly the crash case parking exists for — dispatched, outcome never learned — and restricting
     * this to {@code SCHEDULED} would make it permanently invisible. A healthy long-running attempt is
     * not redelivered, because the claim loop skips any instance whose lease is held by another worker
     * and has not expired: a live runtime renews while its node runs, and only one that has stopped
     * renewing lets its instance become claimable again. {@code PARKED} is excluded because parking is a
     * terminal disposition for the claim loop; it waits for a human rather than being re-decided by a
     * machine on every poll.</p>
     */
    private List<ScheduledAttempt> claimableAttempts(Connection connection, ExecutionKey key, Instant now)
            throws SQLException {
        String sql = "SELECT i.traversal_id, tr.traversal_id AS joined_traversal_id, "
                + "a.invocation_id, i.invocation_id AS joined_invocation_id, "
                + "a.attempt_id, a.ordinal, i.node_command "
                + "FROM attempt a LEFT JOIN invocation i ON i.tenant_id = a.tenant_id "
                + "AND i.process_instance_id = a.process_instance_id AND i.invocation_id = a.invocation_id "
                + "LEFT JOIN traversal tr ON tr.tenant_id = i.tenant_id "
                + "AND tr.process_instance_id = i.process_instance_id AND tr.traversal_id = i.traversal_id "
                + "WHERE a.tenant_id = ? AND a.process_instance_id = ? "
                + "AND a.status IN ('SCHEDULED', 'RUNNING') "
                + "AND NOT EXISTS (SELECT 1 FROM work_acknowledgement k WHERE k.tenant_id = a.tenant_id "
                + "AND k.process_instance_id = a.process_instance_id AND k.work_item_id = a.attempt_id) "
                + "AND NOT EXISTS (SELECT 1 FROM work_claim c WHERE c.tenant_id = a.tenant_id "
                + "AND c.process_instance_id = a.process_instance_id AND c.work_item_id = a.attempt_id "
                + "AND " + StoredInstant.strictlyAfter("c.visible_again_at") + ") "
                + "ORDER BY tr.position, i.position, a.ordinal";
        var ready = new ArrayList<ScheduledAttempt>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            StoredInstant.bindComparison(statement, 3, now);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID invocationId = StoredUuid.required(rows, "attempt", "invocation_id", key);
                    StoredUuid.requiredMatching(rows, "invocation", "joined_invocation_id", key,
                            invocationId);
                    UUID traversalId = StoredUuid.required(rows, "invocation", "traversal_id", key);
                    StoredUuid.requiredMatching(rows, "traversal", "joined_traversal_id", key, traversalId);
                    ready.add(new ScheduledAttempt(traversalId, invocationId,
                            StoredUuid.required(rows, "attempt", "attempt_id", key), rows.getInt("ordinal"),
                            NodeCommand.parse(rows.getString("node_command"))));
                }
            }
        }
        return ready;
    }

    private List<TimerSchedule> claimableTimers(Connection connection, ExecutionKey key, Instant now)
            throws SQLException {
        String sql = "SELECT t.timer_id, t.traversal_id, t.invocation_id, t.payload_content_type, "
                + "t.payload_bytes, t.due_at_epoch_second, t.due_at_nano FROM timer t "
                + "WHERE t.tenant_id = ? AND t.process_instance_id = ? "
                + "AND " + StoredInstant.atOrBefore("t.due_at") + " "
                + "AND NOT EXISTS (SELECT 1 FROM work_acknowledgement k WHERE k.tenant_id = t.tenant_id "
                + "AND k.process_instance_id = t.process_instance_id AND k.work_item_id = t.timer_id) "
                + "AND NOT EXISTS (SELECT 1 FROM work_claim c WHERE c.tenant_id = t.tenant_id "
                + "AND c.process_instance_id = t.process_instance_id AND c.work_item_id = t.timer_id "
                + "AND " + StoredInstant.strictlyAfter("c.visible_again_at") + ") "
                // The identifier is the tie-break because there is no rowid to fall back on, and an
                // order that varies between two identical polls would redeliver timers in a different
                // sequence for no reason a caller could see.
                + "ORDER BY t.due_at_epoch_second, t.due_at_nano, t.timer_id";
        var due = new ArrayList<TimerSchedule>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            int index = StoredInstant.bindComparison(statement, 3, now);
            StoredInstant.bindComparison(statement, index, now);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    due.add(new TimerSchedule(StoredUuid.required(rows, "timer", "timer_id", key),
                            StoredInstant.read(rows, "due_at"),
                            StoredUuid.optional(rows, "timer", "traversal_id"),
                            StoredUuid.optional(rows, "timer", "invocation_id"),
                            OpaquePayload.of(rows.getBytes("payload_bytes"),
                                    rows.getString("payload_content_type"))));
                }
            }
        }
        return due;
    }

    private PendingWork claimAttempt(Connection connection, ExecutionKey key, ScheduledAttempt attempt,
                                     LeaseHandle lease, Instant now, Duration leaseTtl) throws SQLException {
        int delivery = registerClaim(connection, key, attempt.attemptId(), now, leaseTtl);
        return new PendingWork.AttemptDispatch(key, attempt.attemptId(), attempt.traversalId(),
                attempt.invocationId(), attempt.attemptId(), attempt.ordinal(), lease.fencingToken(),
                lease.expiresAt(), delivery, attempt.command());
    }

    private PendingWork.TimerDue claimTimer(Connection connection, ExecutionKey key, TimerSchedule timer,
                                            LeaseHandle lease, Instant now, Duration leaseTtl)
            throws SQLException {
        int delivery = registerClaim(connection, key, timer.timerId(), now, leaseTtl);
        return new PendingWork.TimerDue(key, timer.timerId(), timer.traversalId(), timer.invocationId(),
                timer.dueAt(), timer.payload(), lease.fencingToken(), lease.expiresAt(), delivery);
    }

    /**
     * Records this delivery and returns which delivery it is, in one statement.
     *
     * <p>The increment is computed by the database from the stored row rather than by this process from
     * a value it read a moment earlier. Read-then-write here is the classic lost update: two workers
     * that both read attempt 3 both write 4, and the delivery count — which is the evidence the
     * ambiguity rule uses to decide that a {@code RUNNING} attempt was dispatched and never heard from —
     * silently understates how many times the work has actually gone out.</p>
     */
    private int registerClaim(Connection connection, ExecutionKey key, UUID workItemId, Instant now,
                              Duration leaseTtl) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO work_claim (tenant_id, process_instance_id, work_item_id, delivery_attempt, "
                        + "visible_again_at_epoch_second, visible_again_at_nano) VALUES (?, ?, ?, 1, ?, ?) "
                        + "ON CONFLICT (tenant_id, process_instance_id, work_item_id) DO UPDATE SET "
                        + "delivery_attempt = work_claim.delivery_attempt + 1, "
                        + "visible_again_at_epoch_second = EXCLUDED.visible_again_at_epoch_second, "
                        + "visible_again_at_nano = EXCLUDED.visible_again_at_nano "
                        + "RETURNING delivery_attempt")) {
            bindItem(statement, key, workItemId);
            StoredInstant.bindValue(statement, 4, now.plus(leaseTtl));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw failure(ExecutionStoreFailure.unknownWorkItem(workItemId));
                }
                return rows.getInt(1);
            }
        }
    }

    /**
     * Clears acknowledgements and claims whose work no longer exists.
     *
     * <p>A retry appends a new attempt id rather than reviving the old one, so this only removes
     * bookkeeping for work that has genuinely gone — a cancelled timer, or an attempt the fold replaced.
     * Leaving it would keep a permanently acknowledged ghost in the way of nothing, but would also let
     * the table grow without bound across a long-lived instance.</p>
     *
     * <p>The handler arm is load-bearing rather than symmetric. A handler identity <em>is</em> a
     * work-item identity — a trigger is claimed and acknowledged under it — and a terminal handler is
     * retained rather than deleted, so its acknowledgement has to be retained with it. Without the arm
     * the very next unrelated write to the instance would delete the acknowledgement of a trigger that
     * was already handled, and the claim loop would offer it again, forever.</p>
     */
    private void dropAcknowledgementsForRescheduledWork(Connection connection, ExecutionKey key)
            throws SQLException {
        String liveWork = "SELECT attempt_id FROM attempt WHERE tenant_id = ? AND process_instance_id = ? "
                + "UNION ALL SELECT timer_id FROM timer WHERE tenant_id = ? AND process_instance_id = ? "
                + "UNION ALL SELECT handler_id FROM execution_handler "
                + "WHERE tenant_id = ? AND process_instance_id = ?";
        for (String table : List.of("work_acknowledgement", "work_claim")) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE tenant_id = ? AND process_instance_id = ? "
                            + "AND work_item_id NOT IN (" + liveWork + ")")) {
                for (int pair = 0; pair < 4; pair++) {
                    statement.setString(pair * 2 + 1, key.tenantId());
                    StoredUuid.bind(statement, pair * 2 + 2, key.processInstanceId());
                }
                statement.executeUpdate();
            }
        }
    }

    private static void bindItem(PreparedStatement statement, ExecutionKey key, UUID workItemId)
            throws SQLException {
        statement.setString(1, key.tenantId());
        StoredUuid.bind(statement, 2, key.processInstanceId());
        StoredUuid.bind(statement, 3, workItemId);
    }

    private static void bindNullableUuid(PreparedStatement statement, int index, UUID value)
            throws SQLException {
        if (value == null) {
            // The type has to be named: an untyped NULL against a uuid column is ambiguous to the
            // server, which refuses it rather than guessing.
            statement.setNull(index, Types.OTHER);
        } else {
            StoredUuid.bind(statement, index, value);
        }
    }

    // ---------------------------------------------------------------- inventory helpers

    private static List<String> terminalStatusNames() {
        return Arrays.stream(ProcessInstanceStatus.values())
                .filter(ProcessInstanceStatus::terminal).map(Enum::name).toList();
    }

    /**
     * The eligibility predicate, in one place because the floor query and the delete must not be able to
     * disagree about what "expired" means.
     *
     * <p>Only terminal rows are eligible however old a non-terminal one is: age is not evidence that
     * work has finished, and pruning a stuck instance would destroy the row an operator needs in order
     * to discover that it is stuck. The second arm of the disjunction covers a terminal row that carries
     * no stored deadline, whose {@code updated_at} <em>is</em> its terminal transition instant, because
     * a terminal instance is never written again.</p>
     */
    private static String expiredInstanceIdQuery() {
        String terminal = String.join(", ", terminalStatusNames().stream()
                .map(name -> "'" + name + "'").toList());
        return "SELECT process_instance_id FROM process_instance WHERE tenant_id = ? AND status IN ("
                + terminal + ") AND ("
                + "(retained_until_epoch_second IS NOT NULL AND "
                + StoredInstant.atOrBefore("retained_until") + ") OR "
                + "(retained_until_epoch_second IS NULL AND "
                + StoredInstant.atOrBefore("updated_at") + "))";
    }

    /**
     * Binds a statement of the form {@code ... WHERE tenant_id = ? AND process_instance_id IN
     * (}{@link #expiredInstanceIdQuery()}{@code )}. The tenant appears twice because the subquery must
     * carry it too — an instance id is unique only within a tenant, so a delete whose subquery were
     * unscoped would match a colliding id belonging to somebody else.
     */
    private static void bindExpiredInstanceQuery(PreparedStatement statement, String tenantId, Instant now,
                                                 Instant lapsed) throws SQLException {
        statement.setString(1, tenantId);
        statement.setString(2, tenantId);
        int index = StoredInstant.bindComparison(statement, 3, now);
        StoredInstant.bindComparison(statement, index, lapsed);
    }

    /**
     * The <strong>latest</strong> retention deadline among the rows this purge will remove, or
     * {@code null} when none is eligible. This is the floor.
     *
     * <p>It has to be the latest. The floor's guarantee runs in the direction "everything past it is
     * still here", so it must sit at or beyond every boundary the purge actually crossed. With the
     * earliest, a run that removes two rows whose deadlines are further apart than the retention window
     * publishes a floor the later row sits <em>after</em> — and a caller following the documented rule
     * then concludes that a genuinely completed execution never existed, which is the ambiguity inverted
     * into the unsafe direction. One row is the degenerate case where earliest and latest coincide,
     * which is why the mistake survives any test that purges only one.</p>
     *
     * <p>It is deliberately not {@code now} either: advancing to now would claim a gap covering instants
     * at which rows are still present, which is safe but uselessly pessimistic.</p>
     */
    private Instant latestExpiredDeadline(Connection connection, String tenantId, Instant now,
                                          Instant lapsed) throws SQLException {
        String sql = "SELECT retained_until_epoch_second, retained_until_nano, "
                + "updated_at_epoch_second, updated_at_nano FROM process_instance "
                + "WHERE tenant_id = ? AND process_instance_id IN (" + expiredInstanceIdQuery() + ")";
        Instant latest = null;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindExpiredInstanceQuery(statement, tenantId, now, lapsed);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    // The same resolution the read publishes, so the floor is expressed in deadlines a
                    // caller has actually been shown rather than in a quantity only this method knows.
                    Instant deadline = retentionDueAt(nullableInstant(rows, "retained_until"),
                            StoredInstant.read(rows, "updated_at"));
                    if (latest == null || deadline.isAfter(latest)) {
                        latest = deadline;
                    }
                }
            }
        }
        return latest;
    }

    private Instant retentionDueAt(Instant retainedUntil, Instant updatedAt) {
        return retainedUntil != null ? retainedUntil : plusClamped(updatedAt, config.terminalRetention());
    }

    private ProcessInventoryEntry readInventoryRow(String tenantId, ResultSet rows, Instant now,
                                                   ExecutionKey expectedKey) throws SQLException {
        UUID processInstanceId = expectedKey == null
                ? StoredUuid.required(rows, "process_instance", "process_instance_id", tenantId)
                : StoredUuid.requiredMatching(rows, "process_instance", "process_instance_id", expectedKey,
                        expectedKey.processInstanceId());
        var key = new ExecutionKey(tenantId, processInstanceId);
        ProcessInstanceStatus status = processStatusOf(key, rows.getString("status"));
        String worker = rows.getString("lease_worker_id");
        Instant leaseExpiresAt = worker == null ? null : nullableInstant(rows, "lease_expires_at");
        boolean leaseLive = leaseExpiresAt != null && now.isBefore(leaseExpiresAt);
        return new ProcessInventoryEntry(key, status,
                InventoryDisposition.ofProcess(status, leaseLive, rows.getInt("parked_count") > 0),
                rows.getLong("revision"), rows.getLong("lifecycle_generation"),
                new GraphVersionPin(rows.getString("graph_version_pin")),
                Optional.ofNullable(rows.getString("deployment_id")),
                Optional.ofNullable(rows.getString("workload_id")),
                Optional.ofNullable(rows.getString("correlation_id")),
                leaseLive ? Optional.of(worker) : Optional.empty(),
                rows.getLong("fencing_token"),
                leaseLive ? Optional.of(leaseExpiresAt) : Optional.empty(),
                rows.getInt("traversal_count"), StoredInstant.read(rows, "created_at"),
                StoredInstant.read(rows, "updated_at"),
                retainedUntilOf(status, nullableInstant(rows, "retained_until"),
                        StoredInstant.read(rows, "updated_at")),
                terminationReasonOf(key, rows.getString("termination_reason")));
    }

    /**
     * What a reader is told about retention, resolved the same way the purge decides it.
     *
     * <p>Two paths disagreeing about the same fact is how a caller comes to trust the wrong one; routing
     * both through {@link #retentionDueAt} makes them incapable of it. The terminal test is the gate,
     * and it is what stops the fallback from inventing a deadline for a running instance: retention has
     * not started for a non-terminal row, and absent is how that is said.</p>
     */
    private Optional<Instant> retainedUntilOf(ProcessInstanceStatus status, Instant storedDeadline,
                                              Instant updatedAt) {
        return status.terminal()
                ? Optional.of(retentionDueAt(storedDeadline, updatedAt))
                : Optional.empty();
    }

    /** Binds one accumulated filter argument; an instant occupies the two slots of a comparison. */
    private static int bindInventoryArgument(PreparedStatement statement, int index, Object value)
            throws SQLException {
        if (value instanceof Instant instant) {
            return StoredInstant.bindComparison(statement, index, instant);
        }
        if (value instanceof UUID uuid) {
            StoredUuid.bind(statement, index, uuid);
            return index + 1;
        }
        if (value instanceof Long epochSecond) {
            statement.setLong(index, epochSecond);
            return index + 1;
        }
        if (value instanceof Integer nano) {
            statement.setInt(index, nano);
            return index + 1;
        }
        statement.setString(index, (String) value);
        return index + 1;
    }

    private void requireInventoryQuery(ProcessInventoryQuery query) {
        if (query == null) {
            throw failure(ExecutionStoreFailure.invalid("query is mandatory"));
        }
        if (query.limit() < 1) {
            throw failure(ExecutionStoreFailure.invalid("inventory limit must be positive"));
        }
        // Rejected rather than clamped. A silently reduced page is indistinguishable from a last page,
        // and a caller paginating on "fewer rows than I asked for means I am done" would stop early and
        // never learn that it had.
        if (query.limit() > config.maxInventoryPageSize()) {
            throw failure(ExecutionStoreFailure.invalid("inventory limit " + query.limit()
                    + " exceeds the declared maximum " + config.maxInventoryPageSize()));
        }
        if (query.isSelfContradictory()) {
            throw failure(ExecutionStoreFailure.invalid("a query that filters only for terminal "
                    + "statuses while excluding terminal rows can never match; an empty page would be "
                    + "indistinguishable from there being none"));
        }
    }

    // ---------------------------------------------------------------- execution result helpers

    private void requireResultPayloadWithinLimit(DurableExecutionResult result, int maximumPayloadBytes) {
        if (maximumPayloadBytes < 1) throw new IllegalArgumentException("maximumPayloadBytes must be positive");
        ExecutionResultPayload payload = result.payload();
        if (payload.state() == ResultPayloadState.RETAINED
                && payload.bytes() > maximumPayloadBytes) {
            // Refused rather than silently relabelled WITHHELD. The projection decides what to keep, and
            // a store that rewrote that decision would report a payload as refused for size by an
            // adapter the caller never asked about the size of.
            throw failure(new ExecutionStoreFailure.PayloadTooLarge(payload.bytes(),
                    maximumPayloadBytes));
        }
        if (payload.state() == ResultPayloadState.EXPIRED) {
            throw failure(ExecutionStoreFailure.invalid(
                    "EXPIRED describes a record's age and is produced by a read; it cannot be stored"));
        }
    }

    private void requireInstanceExists(Connection connection, ExecutionKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM process_instance WHERE tenant_id = ? AND process_instance_id = ?")) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    // Indistinguishable from another tenant's instance, which is the point: the key
                    // carries the tenant, so a cross-tenant record is refused by the same miss.
                    throw failure(new ExecutionStoreFailure.NotFound(key));
                }
            }
        }
    }

    /** @return whether this call wrote the row, as opposed to finding a concurrent writer's. */
    private boolean insertExecutionResult(Connection connection, DurableExecutionResult result,
                                          Instant recordedAt) throws SQLException {
        ExecutionResultPayload payload = result.payload();
        int inserted;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO execution_result (tenant_id, process_instance_id, traversal_id, "
                        + "graph_version_pin, status, termination_reason, started_at_epoch_second, "
                        + "started_at_nano, ended_at_epoch_second, ended_at_nano, "
                        + "recorded_at_epoch_second, recorded_at_nano, retained_until_epoch_second, "
                        + "retained_until_nano, payload_state, payload_redacted, payload_truncated, "
                        + "payload_bytes, payload_content_type, payload, failure_classifier, "
                        + "fingerprint) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
            int index = 1;
            statement.setString(index++, result.key().tenantId());
            StoredUuid.bind(statement, index++, result.key().processInstanceId());
            StoredUuid.bind(statement, index++, result.traversalId());
            statement.setString(index++, result.graphVersionPin().reference());
            statement.setString(index++, result.status().name());
            statement.setString(index++, result.terminationReason() == null ? null
                    : result.terminationReason().name());
            index = StoredInstant.bindValue(statement, index, result.startedAt());
            index = StoredInstant.bindValue(statement, index, result.endedAt());
            index = StoredInstant.bindValue(statement, index, recordedAt);
            index = StoredInstant.bindValue(statement, index, result.retainedUntil());
            statement.setString(index++, payload.state().name());
            statement.setBoolean(index++, payload.redacted());
            statement.setBoolean(index++, payload.truncated());
            statement.setInt(index++, payload.bytes());
            statement.setString(index++, payload.contentType());
            statement.setBytes(index++, payload.retained() == null ? null : payload.retained().bytes());
            statement.setString(index++, result.failureClassifier());
            statement.setString(index, result.fingerprint());
            inserted = statement.executeUpdate();
        }
        if (inserted == 0) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO execution_result_node (tenant_id, traversal_id, node_set, position, value) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            for (ExecutionResultNodes.Kind kind : ExecutionResultNodes.Kind.values()) {
                List<String> entries = result.nodes().entries(kind);
                for (int position = 0; position < entries.size(); position++) {
                    statement.setString(1, result.key().tenantId());
                    StoredUuid.bind(statement, 2, result.traversalId());
                    statement.setString(3, kind.name());
                    statement.setInt(4, position);
                    statement.setString(5, entries.get(position));
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }
        return true;
    }

    private DurableExecutionResult readExecutionResult(Connection connection, String tenantId,
                                                       UUID traversalId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM execution_result WHERE tenant_id = ? AND traversal_id = ?")) {
            statement.setString(1, tenantId);
            StoredUuid.bind(statement, 2, traversalId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                var key = new ExecutionKey(tenantId,
                        StoredUuid.required(rows, "execution_result", "process_instance_id", tenantId));
                return new DurableExecutionResult(key, traversalId,
                        new GraphVersionPin(rows.getString("graph_version_pin")),
                        processStatusOf(key, rows.getString("status")),
                        terminationReasonOf(key, rows.getString("termination_reason")),
                        StoredInstant.read(rows, "started_at"), StoredInstant.read(rows, "ended_at"),
                        StoredInstant.read(rows, "retained_until"), readResultPayload(key, rows),
                        rows.getString("failure_classifier"),
                        readResultNodes(connection, tenantId, traversalId));
            }
        }
    }

    private static ExecutionResultPayload readResultPayload(ExecutionKey key, ResultSet rows)
            throws SQLException {
        String name = rows.getString("payload_state");
        ResultPayloadState state;
        try {
            state = ResultPayloadState.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            // The route an unknown status name takes, and never a fallback to "no payload": a state
            // this build cannot read is a rollback, and reporting it as an absent payload would tell a
            // caller the run produced nothing when it produced something unreadable.
            throw new ExecutionStoreException(new ExecutionStoreFailure.Corrupted(key,
                    "payload state '" + name + "' is not a state this build understands"), unknown);
        }
        return new ExecutionResultPayload(state, rows.getBoolean("payload_redacted"),
                rows.getBoolean("payload_truncated"), rows.getInt("payload_bytes"),
                rows.getString("payload_content_type"),
                state == ResultPayloadState.RETAINED
                        ? OpaquePayload.of(rows.getBytes("payload"), rows.getString("payload_content_type"))
                        : null);
    }

    private ExecutionResultNodes readResultNodes(Connection connection, String tenantId, UUID traversalId)
            throws SQLException {
        var byKind = new EnumMap<ExecutionResultNodes.Kind, List<String>>(ExecutionResultNodes.Kind.class);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT node_set, value FROM execution_result_node WHERE tenant_id = ? AND "
                        + "traversal_id = ? ORDER BY node_set, position")) {
            statement.setString(1, tenantId);
            StoredUuid.bind(statement, 2, traversalId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String setName = rows.getString("node_set");
                    ExecutionResultNodes.Kind kind;
                    try {
                        kind = ExecutionResultNodes.Kind.valueOf(setName);
                    } catch (IllegalArgumentException unknown) {
                        throw new ExecutionStoreException(new ExecutionStoreFailure.Corrupted(
                                new ExecutionKey(tenantId, traversalId),
                                "node set '" + setName + "' is not a set this build understands"), unknown);
                    }
                    byKind.computeIfAbsent(kind, ignored -> new ArrayList<>())
                            .add(rows.getString("value"));
                }
            }
        }
        return new ExecutionResultNodes(byKind.getOrDefault(ExecutionResultNodes.Kind.VISITED, List.of()),
                byKind.getOrDefault(ExecutionResultNodes.Kind.DEFAULTED, List.of()),
                byKind.getOrDefault(ExecutionResultNodes.Kind.BYPASSED, List.of()),
                byKind.getOrDefault(ExecutionResultNodes.Kind.HANDLED_FAILURE, List.of()),
                byKind.getOrDefault(ExecutionResultNodes.Kind.UNTAKEN_EDGE, List.of()));
    }

    private Instant latestExpiredResultDeadline(Connection connection, String tenantId, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT retained_until_epoch_second, retained_until_nano FROM execution_result "
                        + "WHERE tenant_id = ? AND " + StoredInstant.atOrBefore("retained_until")
                        + " ORDER BY retained_until_epoch_second DESC, retained_until_nano DESC LIMIT 1")) {
            statement.setString(1, tenantId);
            StoredInstant.bindComparison(statement, 2, now);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? StoredInstant.read(rows, "retained_until") : null;
            }
        }
    }

    // ---------------------------------------------------------------- journal helpers

    /**
     * Reconstructs one journal row and verifies its digest.
     *
     * <p>The check is on the <strong>read</strong> path deliberately. Verifying only at write time would
     * prove the digest was computed correctly and nothing whatever about whether the bytes survived,
     * which is the entire question a digest exists to answer.</p>
     */
    private JournalRecord readJournalRecord(String tenantId, ResultSet rows) throws SQLException {
        UUID instanceId = StoredUuid.required(rows, "event_journal", "process_instance_id", tenantId);
        var key = new ExecutionKey(tenantId, instanceId);
        var envelope = new EventEnvelope(
                rows.getInt("envelope_version"),
                StoredUuid.required(rows, "event_journal", "event_id", key),
                tenantId,
                rows.getString("event_type"),
                instanceId,
                StoredUuid.required(rows, "event_journal", "traversal_id", key),
                StoredUuid.optional(rows, "event_journal", "invocation_id"),
                StoredUuid.optional(rows, "event_journal", "attempt_id"),
                StoredUuid.optional(rows, "event_journal", "causation_id"),
                rows.getString("correlation_id"),
                rows.getString("graph_version"),
                StoredInstant.read(rows, "occurred_at"),
                OpaquePayload.of(rows.getBytes("payload_bytes"), rows.getString("payload_content_type")),
                EventDigest.of(rows.getBytes("digest")));
        if (!envelope.digestMatchesContent()) {
            throw failure(new ExecutionStoreFailure.Corrupted(key,
                    "journal offset " + rows.getLong("journal_offset") + " carries digest "
                            + envelope.digest().hex() + ", which does not match its stored content"));
        }
        return new JournalRecord(envelope, rows.getLong("stream_sequence"), rows.getLong("journal_offset"),
                rows.getLong("committed_at_revision"), StoredInstant.read(rows, "recorded_at"));
    }

    /**
     * Writes this batch's envelopes inside the batch's own transaction.
     *
     * <p>Called from {@code applyLocked}, between the aggregate write and the return, so the event rows
     * and the transition rows are inside one {@code COMMIT}. That single fact is the whole of the event
     * journal's atomicity requirement: there is no window between committing the transition and
     * recording the event, because there is no second write to perform.</p>
     */
    private void writeJournal(Connection connection, ExecutionKey key, ExecutionBatch batch, long revision,
                              Instant now) throws SQLException {
        List<EventEnvelope> events = batch.events();
        if (events.isEmpty()) {
            return;
        }
        long count = events.size();
        long offset = allocate(connection,
                "INSERT INTO journal_watermark (tenant_id, next_offset, retained_from) "
                        + "VALUES (?, 1 + ?, 1) ON CONFLICT (tenant_id) DO UPDATE SET "
                        + "next_offset = journal_watermark.next_offset + ? RETURNING next_offset",
                statement -> statement.setString(1, key.tenantId()), 1, count) - count;
        long stream = allocate(connection,
                "INSERT INTO journal_stream_sequence (tenant_id, process_instance_id, next_sequence) "
                        + "VALUES (?, ?, 1 + ?) ON CONFLICT (tenant_id, process_instance_id) DO UPDATE "
                        + "SET next_sequence = journal_stream_sequence.next_sequence + ? "
                        + "RETURNING next_sequence",
                statement -> {
                    statement.setString(1, key.tenantId());
                    StoredUuid.bind(statement, 2, key.processInstanceId());
                }, 2, count) - count;

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO event_journal (tenant_id, journal_offset, stream_sequence, "
                        + "process_instance_id, committed_at_revision, envelope_version, event_id, "
                        + "event_type, traversal_id, invocation_id, attempt_id, causation_id, "
                        + "correlation_id, graph_version, occurred_at_epoch_second, occurred_at_nano, "
                        + "payload_content_type, payload_bytes, digest, recorded_at_epoch_second, "
                        + "recorded_at_nano) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "?, ?, ?, ?)")) {
            for (EventEnvelope envelope : events) {
                statement.setString(1, key.tenantId());
                statement.setLong(2, offset++);
                statement.setLong(3, stream++);
                StoredUuid.bind(statement, 4, key.processInstanceId());
                statement.setLong(5, revision);
                statement.setInt(6, envelope.envelopeVersion());
                StoredUuid.bind(statement, 7, envelope.eventId());
                statement.setString(8, envelope.eventType());
                StoredUuid.bind(statement, 9, envelope.traversalId());
                bindNullableUuid(statement, 10, envelope.invocationId());
                bindNullableUuid(statement, 11, envelope.attemptId());
                bindNullableUuid(statement, 12, envelope.causationId());
                statement.setString(13, envelope.correlationId());
                statement.setString(14, envelope.graphVersion());
                StoredInstant.bindValue(statement, 15, envelope.occurredAt());
                statement.setString(17, envelope.payload().contentType());
                statement.setBytes(18, envelope.payload().bytes());
                statement.setBytes(19, envelope.digest().value());
                StoredInstant.bindValue(statement, 20, now);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /**
     * Reserves {@code count} consecutive numbers and returns the first one past the reservation.
     *
     * <p>The single-host adapter reads the counter and writes {@code read + count}. Two hosts doing that
     * at once would both read the same number and both write the same one, so two different events
     * would be given the same journal offset — and a consumer resuming from an offset would then skip
     * whichever of them it did not receive, silently. Computing the new value from the stored row inside
     * one statement makes that arithmetic the database's, where concurrent callers serialize on the row
     * rather than on nothing.</p>
     */
    private static long allocate(Connection connection, String sql, Binder identity, int identityWidth,
                                 long count) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            identity.bind(statement);
            statement.setLong(identityWidth + 1, count);
            statement.setLong(identityWidth + 2, count);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("the counter statement returned no row");
                }
                return rows.getLong(1);
            }
        }
    }

    /** Binds the identity columns of an allocator statement. */
    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private static long readWatermarkColumn(Connection connection, String tenantId, String column)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + column + " FROM journal_watermark WHERE tenant_id = ?")) {
            statement.setString(1, tenantId);
            try (ResultSet rows = statement.executeQuery()) {
                // Offsets start at one, so an absent watermark and an empty journal agree.
                return rows.next() ? rows.getLong(1) : 1L;
            }
        }
    }

    /**
     * Advances the retained floor, and only the retained floor.
     *
     * <p>The single-host adapter reads both counters and rewrites the pair. Here that would let a
     * compaction clobber a {@code next_offset} a concurrent {@code apply} had just advanced, reissuing
     * offsets that a destination cursor has already passed — the one thing the journal promises never to
     * do. Naming a single column in the {@code SET} makes the two counters independent, and the
     * comparison makes the floor monotone.</p>
     */
    private static void writeRetainedFrom(Connection connection, String tenantId, long retainedFrom)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO journal_watermark (tenant_id, next_offset, retained_from) VALUES (?, 1, ?) "
                        + "ON CONFLICT (tenant_id) DO UPDATE SET retained_from = EXCLUDED.retained_from "
                        + "WHERE journal_watermark.retained_from < EXCLUDED.retained_from")) {
            statement.setString(1, tenantId);
            statement.setLong(2, retainedFrom);
            statement.executeUpdate();
        }
    }

    private static long readCursor(Connection connection, String tenantId, String destination)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT delivered_through FROM outbox_cursor WHERE tenant_id = ? AND destination = ?")) {
            statement.setString(1, tenantId);
            statement.setString(2, destination);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    private static long minimumCursor(Connection connection, String tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT MIN(delivered_through), COUNT(*) FROM outbox_cursor WHERE tenant_id = ?")) {
            statement.setString(1, tenantId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getLong(2) == 0L) {
                    return 0L;
                }
                return rows.getLong(1);
            }
        }
    }

    private static long countOf(Connection connection, String sql, String tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    /**
     * Every envelope must name this batch's own tenant and instance, decided before any stored state is
     * read.
     *
     * <p>The tenant half is a security guard, not a consistency one. An envelope naming another tenant,
     * accepted into this tenant's journal, is delivered to <em>this</em> journal's subscribers — a
     * cross-tenant disclosure produced by a caller bug.</p>
     */
    private void requireEnvelopesMatchBatch(ExecutionBatch batch) {
        for (EventEnvelope envelope : batch.events()) {
            requireWithinPayloadLimit(envelope.payload());
            if (!envelope.tenantId().equals(batch.key().tenantId())) {
                throw failure(ExecutionStoreFailure.invalid("event " + envelope.eventId() + " names tenant "
                        + envelope.tenantId() + " but the batch writes to " + batch.key().tenantId()));
            }
            if (!envelope.processInstanceId().equals(batch.key().processInstanceId())) {
                throw failure(ExecutionStoreFailure.invalid("event " + envelope.eventId()
                        + " names instance " + envelope.processInstanceId()
                        + " but the batch writes to " + batch.key().processInstanceId()));
            }
        }
    }

    // ---------------------------------------------------------------- durable handlers

    @Override
    public CompletionStage<Optional<DurableHandler>> loadHandler(ExecutionKey key, UUID handlerId) {
        return async(() -> {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(handlerId, "handlerId");
            // One statement, so readOnly rather than readConsistent: there is no second read whose
            // snapshot could disagree with this one.
            return read(key, connection -> {
                // An absent instance and an absent handler answer the same way. Distinguishing them
                // would let a probe learn that a process instance exists in a tenant it cannot read.
                DurableHandler stored = readHandler(connection, key, handlerId);
                return Optional.ofNullable(stored);
            });
        });
    }

    @Override
    public CompletionStage<Optional<DurableHandler>> findHandler(String tenantId, String handlerName,
                                                                 String correlationKey) {
        return async(() -> {
            requireTenantId(tenantId);
            HandlerRegistration.requireBoundedKey(handlerName, "handlerName");
            HandlerRegistration.requireBoundedKey(correlationKey, "correlationKey");
            return read(null, connection ->
                    Optional.ofNullable(liveHandler(connection, tenantId, handlerName, correlationKey)));
        });
    }

    @Override
    public CompletionStage<List<DurableHandler>> handlers(ExecutionKey key) {
        return async(() -> {
            Objects.requireNonNull(key, "key");
            return read(key, connection -> {
                var found = new ArrayList<DurableHandler>();
                try (PreparedStatement statement = connection.prepareStatement(
                        HANDLER_COLUMNS + " WHERE h.tenant_id = ? AND h.process_instance_id = ? "
                                + "ORDER BY h.position, h.handler_id")) {
                    statement.setString(1, key.tenantId());
                    StoredUuid.bind(statement, 2, key.processInstanceId());
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            found.add(readHandler(rows, key, null));
                        }
                    }
                }
                return List.copyOf(found);
            });
        });
    }

    /** Folds this batch's registrations and handler transitions, inside the enclosing transaction. */
    private void writeHandlers(Connection connection, ExecutionKey key, ExecutionBatch batch,
                               ProcessInstance folded, long revision) throws SQLException {
        for (HandlerRegistration registration : batch.handlersToRegister()) {
            registerHandler(connection, key, folded, registration, revision);
        }
        for (HandlerTransition transition : batch.handlerTransitions()) {
            transitionHandler(connection, key, batch, folded, transition, revision);
        }
    }

    /**
     * Registers one handler, letting the partial unique indexes decide who owns a contested key.
     *
     * <p>The lookups run first and answer the ordinary cases exactly: a retried wait re-sends the
     * identical registration and must be a no-op, a <em>different</em> handler under the same
     * deduplication key is a caller bug, and a live correlation key held by somebody else is
     * {@link ExecutionStoreFailure.HandlerCorrelationTaken}. They also see registrations made earlier
     * in this same batch, because a transaction reads its own writes — which is the property that
     * makes two registrations sharing a key inside one batch refuse each other rather than both
     * commit.</p>
     *
     * <p>What the lookups cannot see is a competing transaction that has not committed yet, and the
     * correlation and deduplication keys are tenant-wide, so the competitor is very often a different
     * process instance whose row lock this transaction does not hold. The insert therefore stands as
     * the decision: it waits for the competitor, and if the competitor committed first the index
     * raises the violation and this rolls back to the savepoint and asks the lookups again — which now
     * see the winner and produce the same answer they would have produced had it been there all
     * along.</p>
     */
    private void registerHandler(Connection connection, ExecutionKey key, ProcessInstance folded,
                                 HandlerRegistration registration, long revision) throws SQLException {
        requireInvocationExists(folded, registration.traversalId(), registration.invocationId(),
                "handler " + registration.handlerId());
        if (handlerAlreadyRegistered(connection, key, registration)) {
            return;
        }
        DurableHandler handler = DurableHandler.waiting(key, registration, revision);
        if (insertApplied(connection,
                () -> insertHandler(connection, handler, nextHandlerPosition(connection, key)))) {
            return;
        }
        if (handlerAlreadyRegistered(connection, key, registration)) {
            return;
        }
        // Unreachable in principle and stated rather than assumed: a unique violation is raised only
        // against a COMMITTED row, because an uncommitted conflict makes the insert wait instead. A
        // row that is not there after the violation would mean the index and the table disagree.
        throw failure(ExecutionStoreFailure.invalid("handler " + registration.handlerId()
                + " collided with a uniqueness rule whose winning row cannot be read back"));
    }

    /**
     * Whether this exact registration is already stored, refusing every collision that is not it.
     *
     * <p>Called twice per registration — once before the insert and once after a lost race — and the
     * second call is why it is a method rather than inline: the classification a caller is owed must
     * not depend on whether the collision was seen by a read or by an index.</p>
     */
    private boolean handlerAlreadyRegistered(Connection connection, ExecutionKey key,
                                             HandlerRegistration registration) throws SQLException {
        DurableHandler byDeduplication = handlerByDeduplicationKey(connection, key.tenantId(),
                registration.deduplicationKey());
        if (byDeduplication != null) {
            // A retried wait re-sends the identical batch and must be a no-op. A DIFFERENT
            // registration under the same key is a caller bug, and answering it as a success would
            // silently discard a handler somebody asked for.
            if (!byDeduplication.matches(registration)) {
                throw failure(ExecutionStoreFailure.invalid("deduplication key "
                        + registration.deduplicationKey() + " already registers handler "
                        + byDeduplication.handlerId() + ", which is not the handler being registered"));
            }
            return true;
        }
        DurableHandler contender = liveHandler(connection, key.tenantId(), registration.name(),
                registration.correlationKey());
        if (contender != null && !contender.handlerId().equals(registration.handlerId())) {
            throw failure(new ExecutionStoreFailure.HandlerCorrelationTaken(registration.name(),
                    registration.correlationKey()));
        }
        DurableHandler existing = readHandler(connection, key, registration.handlerId());
        if (existing != null) {
            throw failure(ExecutionStoreFailure.invalid("handler " + registration.handlerId()
                    + " is already registered under a different deduplication key"));
        }
        return false;
    }

    private void transitionHandler(Connection connection, ExecutionKey key, ExecutionBatch batch,
                                   ProcessInstance folded, HandlerTransition transition, long revision)
            throws SQLException {
        DurableHandler current = readHandler(connection, key, transition.handlerId());
        if (current == null) {
            // InvalidRequest rather than NotFound: NotFound names a process instance, and the
            // instance is present -- it is the handler inside it that this batch invented.
            throw failure(ExecutionStoreFailure.invalid("unknown handler " + transition.handlerId()));
        }
        // A redelivered escalation timer must not be able to turn an escalation into a failure.
        // Every other repeat is a duplicate and is refused.
        if (transition.next() == HandlerStatus.ESCALATED && current.status() == HandlerStatus.ESCALATED) {
            return;
        }
        if (!current.status().canTransitionTo(transition.next())) {
            throw failure(new ExecutionStoreFailure.HandlerNotResolvable(current.handlerId(),
                    current.status(), transition.next()));
        }
        requireHandlerOutcomeWithinLimit(connection, key, batch, transition);
        if (transition.next().resumesProcess()) {
            requireBatchCreatedTraversal(batch, transition.resumeTraversalId(),
                    "handler " + current.handlerId() + " resume");
            requireTraversalExists(folded, transition.resumeTraversalId(),
                    "handler " + current.handlerId() + " resume");
        }
        if (transition.next() == HandlerStatus.RESOLVED) {
            // Only a resolution supplies the body the handler was declared to be waiting for. A
            // denial carries a refusal reason, which is a different shape by nature.
            Optional<String> refusal = current.payloadSchema().rejectionOf(transition.outcomePayload());
            if (refusal.isPresent()) {
                throw failure(ExecutionStoreFailure.invalid("handler " + current.handlerId()
                        + " payload was refused: " + refusal.get()));
            }
        }
        updateHandler(connection, current.apply(transition, revision), current.status());
    }

    private void insertHandler(Connection connection, DurableHandler handler, int position)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO execution_handler (tenant_id, process_instance_id, handler_id, position, "
                        + "name, traversal_id, invocation_id, correlation_key, deduplication_key, "
                        + "schema_content_type, schema_ref, schema_max_bytes, required_roles, "
                        + "required_scopes, status, resume_traversal_id, actor, outcome_content_type, "
                        + "outcome_bytes, revision) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, handler.key().tenantId());
            StoredUuid.bind(statement, 2, handler.key().processInstanceId());
            StoredUuid.bind(statement, 3, handler.handlerId());
            statement.setInt(4, position);
            statement.setString(5, handler.name());
            StoredUuid.bind(statement, 6, handler.traversalId());
            StoredUuid.bind(statement, 7, handler.invocationId());
            statement.setString(8, handler.correlationKey());
            statement.setString(9, handler.deduplicationKey());
            statement.setString(10, handler.payloadSchema().contentType());
            statement.setString(11, handler.payloadSchema().schemaRef());
            statement.setInt(12, handler.payloadSchema().maxBytes());
            statement.setString(13, joinTokens(handler.authorization().requiredRoles()));
            statement.setString(14, joinTokens(handler.authorization().requiredScopes()));
            statement.setString(15, handler.status().name());
            bindNullableUuid(statement, 16, handler.resumeTraversalId());
            statement.setString(17, handler.actor());
            statement.setString(18, handler.outcomePayload().contentType());
            statement.setBytes(19, handler.outcomePayload().bytes());
            statement.setLong(20, handler.revision());
            statement.executeUpdate();
        }
    }

    /**
     * Applies a settled handler, with the status the decision was made on in the {@code WHERE}.
     *
     * <p>The predicate is not decoration. Everything a batch writes about one instance is serialized
     * by that instance's own row lock, so today no concurrent writer can move this row between the
     * read above and this update — but the guard costs one comparison and makes the statement true on
     * its own terms, so a future change that narrowed the lock would fail the transition rather than
     * silently overwrite whichever decision lost.</p>
     */
    private void updateHandler(Connection connection, DurableHandler handler, HandlerStatus expected)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE execution_handler SET status = ?, resume_traversal_id = ?, actor = ?, "
                        + "outcome_content_type = ?, outcome_bytes = ?, revision = ? "
                        + "WHERE tenant_id = ? AND process_instance_id = ? AND handler_id = ? "
                        + "AND status = ?")) {
            statement.setString(1, handler.status().name());
            bindNullableUuid(statement, 2, handler.resumeTraversalId());
            statement.setString(3, handler.actor());
            statement.setString(4, handler.outcomePayload().contentType());
            statement.setBytes(5, handler.outcomePayload().bytes());
            statement.setLong(6, handler.revision());
            statement.setString(7, handler.key().tenantId());
            StoredUuid.bind(statement, 8, handler.key().processInstanceId());
            StoredUuid.bind(statement, 9, handler.handlerId());
            statement.setString(10, expected.name());
            if (statement.executeUpdate() != 1) {
                throw failure(new ExecutionStoreFailure.HandlerNotResolvable(handler.handlerId(),
                        expected, handler.status()));
            }
        }
    }

    private int nextHandlerPosition(Connection connection, ExecutionKey key) throws SQLException {
        // Safe to derive from a read because every handler of one instance is written under that
        // instance's row lock, so no second writer can allocate the same position. It is a display
        // order rather than an identity in any case -- handlers() breaks ties on the identifier, so a
        // duplicate would cost determinism rather than correctness.
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(position), -1) + 1 FROM execution_handler "
                        + "WHERE tenant_id = ? AND process_instance_id = ?")) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private DurableHandler readHandler(Connection connection, ExecutionKey key, UUID handlerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(HANDLER_COLUMNS
                + " WHERE h.tenant_id = ? AND h.process_instance_id = ? AND h.handler_id = ?")) {
            bindItem(statement, key, handlerId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? readHandler(rows, key, handlerId) : null;
            }
        }
    }

    private DurableHandler liveHandler(Connection connection, String tenantId, String handlerName,
                                       String correlationKey) throws SQLException {
        // The partial unique index makes this at most one row, so the answer does not depend on
        // ordering. A LIMIT here would have hidden a violated invariant behind an arbitrary winner.
        try (PreparedStatement statement = connection.prepareStatement(HANDLER_COLUMNS
                + " WHERE h.tenant_id = ? AND h.name = ? AND h.correlation_key = ? "
                + "AND h.status IN " + LIVE_HANDLER_STATUSES)) {
            statement.setString(1, tenantId);
            statement.setString(2, handlerName);
            statement.setString(3, correlationKey);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? readHandler(rows, null, null) : null;
            }
        }
    }

    private DurableHandler handlerByDeduplicationKey(Connection connection, String tenantId,
                                                     String deduplicationKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(HANDLER_COLUMNS
                + " WHERE h.tenant_id = ? AND h.deduplication_key = ?")) {
            statement.setString(1, tenantId);
            statement.setString(2, deduplicationKey);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? readHandler(rows, null, null) : null;
            }
        }
    }

    /**
     * Reconstructs a stored handler through its canonical constructor.
     *
     * <p>A row that no longer satisfies the record's invariants — a resuming status with no resume
     * traversal, an unknown status name written by a newer binary — surfaces as
     * {@link ExecutionStoreFailure.Corrupted} rather than escaping into the runtime, matching how the
     * aggregate itself is revalidated on the way out.</p>
     */
    private static DurableHandler readHandler(ResultSet rows, ExecutionKey expectedKey,
                                              UUID expectedHandlerId) throws SQLException {
        String tenantId = rows.getString("tenant_id");
        UUID processInstanceId = expectedKey == null
                ? StoredUuid.required(rows, "execution_handler", "process_instance_id", tenantId)
                : StoredUuid.requiredMatching(rows, "execution_handler", "process_instance_id",
                        expectedKey, expectedKey.processInstanceId());
        var key = new ExecutionKey(tenantId, processInstanceId);
        try {
            UUID handlerId = expectedHandlerId == null
                    ? StoredUuid.required(rows, "execution_handler", "handler_id", key)
                    : StoredUuid.requiredMatching(rows, "execution_handler", "handler_id", key,
                            expectedHandlerId);
            return new DurableHandler(handlerId, key, rows.getString("name"),
                    StoredUuid.required(rows, "execution_handler", "traversal_id", key),
                    StoredUuid.required(rows, "execution_handler", "invocation_id", key),
                    rows.getString("correlation_key"), rows.getString("deduplication_key"),
                    new HandlerPayloadSchema(rows.getString("schema_content_type"),
                            rows.getString("schema_ref"), rows.getInt("schema_max_bytes")),
                    new HandlerAuthorization(splitTokens(rows.getString("required_roles")),
                            splitTokens(rows.getString("required_scopes"))),
                    HandlerStatus.valueOf(rows.getString("status")),
                    StoredUuid.optional(rows, "execution_handler", "resume_traversal_id"),
                    rows.getString("actor"),
                    OpaquePayload.of(rows.getBytes("outcome_bytes"),
                            rows.getString("outcome_content_type")),
                    rows.getLong("revision"));
        } catch (IllegalArgumentException | IllegalStateException corrupted) {
            throw failure(new ExecutionStoreFailure.Corrupted(key, corrupted.getMessage()));
        }
    }

    /**
     * The settled handlers of one instance that have a trigger nobody has taken or acknowledged.
     *
     * <p>Terminal only, and that is the whole eligibility rule: a handler becomes claimable work at
     * the moment a decision is recorded against it, and a waiting one offers nothing. There is no
     * temporal predicate, so unlike a timer this is not "due" — it is outstanding.</p>
     */
    private List<DurableHandler> claimableTriggers(Connection connection, ExecutionKey key, Instant now)
            throws SQLException {
        String sql = HANDLER_COLUMNS + " WHERE h.tenant_id = ? AND h.process_instance_id = ? "
                + "AND h.status IN " + TERMINAL_HANDLER_STATUSES + " "
                + "AND NOT EXISTS (SELECT 1 FROM work_acknowledgement k WHERE k.tenant_id = h.tenant_id "
                + "AND k.process_instance_id = h.process_instance_id AND k.work_item_id = h.handler_id) "
                + "AND NOT EXISTS (SELECT 1 FROM work_claim c WHERE c.tenant_id = h.tenant_id "
                + "AND c.process_instance_id = h.process_instance_id AND c.work_item_id = h.handler_id "
                + "AND " + StoredInstant.strictlyAfter("c.visible_again_at") + ") "
                + "ORDER BY h.position, h.handler_id";
        var ready = new ArrayList<DurableHandler>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            StoredInstant.bindComparison(statement, 3, now);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ready.add(readHandler(rows, key, null));
                }
            }
        }
        return ready;
    }

    private PendingWork.HandlerTrigger claimTrigger(Connection connection, ExecutionKey key,
                                                    DurableHandler handler, LeaseHandle lease,
                                                    Instant now, Duration leaseTtl) throws SQLException {
        int delivery = registerClaim(connection, key, handler.handlerId(), now, leaseTtl);
        // The RE-ENTRY traversal, never the one that was waiting: the claimant runs the traversal the
        // resolution authorized, and the waiting traversal's own history stays closed.
        //
        // The invocation is ABSENT, not the waiting one. Pairing a new traversal with an invocation
        // that lives under the old one produces a pair no lookup resolves -- a claimant asking the
        // re-entry traversal for that invocation gets null -- and it is the invocation the wait is
        // over for, so naming it would also read as work still to do. The claimant creates the
        // re-entry invocation itself; the waiting one stays reachable through the handler, whose id
        // is this item's own workItemId.
        return new PendingWork.HandlerTrigger(key, handler.handlerId(), handler.resumeTraversalId(),
                null, handler.name(), handler.outcomePayload(), lease.fencingToken(),
                lease.expiresAt(), delivery);
    }

    // ---------------------------------------------------------------- durable tool approvals

    @Override
    public CompletionStage<Optional<DurableToolApproval>> loadToolApproval(ExecutionKey key,
                                                                           UUID approvalId) {
        return async(() -> {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(approvalId, "approvalId");
            return read(key, connection ->
                    Optional.ofNullable(readToolApproval(connection, key, approvalId)));
        });
    }

    @Override
    public CompletionStage<List<DurableToolApproval>> toolApprovals(ExecutionKey key) {
        return async(() -> {
            Objects.requireNonNull(key, "key");
            return read(key, connection -> {
                var approvals = new ArrayList<DurableToolApproval>();
                try (PreparedStatement statement = connection.prepareStatement(TOOL_APPROVAL_COLUMNS
                        + " WHERE a.tenant_id = ? AND a.process_instance_id = ? "
                        + "ORDER BY a.position, a.approval_id")) {
                    statement.setString(1, key.tenantId());
                    StoredUuid.bind(statement, 2, key.processInstanceId());
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            approvals.add(readToolApproval(rows, key, null));
                        }
                    }
                }
                return List.copyOf(approvals);
            });
        });
    }

    /**
     * Folds this batch's approval registrations and decisions.
     *
     * <p>An approval is identified by an id the caller chose, and its uniqueness is the primary key of
     * one instance, so there is no tenant-wide rule here and no savepoint: the instance's own row lock
     * already serializes every writer that could collide.</p>
     */
    private void writeToolApprovals(Connection connection, ExecutionKey key, ExecutionBatch batch,
                                    ProcessInstance folded, GraphVersionPin pin, long revision,
                                    Instant now) throws SQLException {
        for (ToolApprovalRegistration registration : batch.toolApprovalsToRegister()) {
            requireInvocationExists(folded, registration.traversalId(), registration.invocationId(),
                    "tool approval " + registration.approvalId());
            if (!key.tenantId().equals(registration.requester().tenantId())
                    || !pin.equals(registration.graphVersionPin())) {
                throw failure(ExecutionStoreFailure.invalid(
                        "tool approval identity or graph pin does not match its execution"));
            }
            if (!now.isBefore(registration.expiresAt())) {
                throw failure(ExecutionStoreFailure.invalid(
                        "tool approval expiry must be after store time"));
            }
            DurableToolApproval existing = readToolApproval(connection, key, registration.approvalId());
            if (existing != null) {
                if (!existing.request().sameRequest(registration)) {
                    throw failure(ExecutionStoreFailure.invalid("tool approval "
                            + registration.approvalId()
                            + " is already registered with a different request"));
                }
                continue;
            }
            insertToolApproval(connection, DurableToolApproval.pending(key, registration, revision),
                    nextToolApprovalPosition(connection, key));
        }
        for (ToolApprovalTransition transition : batch.toolApprovalTransitions()) {
            DurableToolApproval current = readToolApproval(connection, key, transition.approvalId());
            if (current == null) {
                throw failure(ExecutionStoreFailure.invalid("unknown tool approval "
                        + transition.approvalId()));
            }
            if (current.alreadyApplied(transition)) {
                continue;
            }
            if (!current.status().canTransitionTo(transition.next())) {
                throw failure(new ExecutionStoreFailure.ToolApprovalNotResolvable(
                        current.request().approvalId(), current.status(), transition.next()));
            }
            // The store is the only authority that may expire an approval, and the only authority
            // that may refuse one as late. Both comparisons are against the injected clock read once
            // for this batch: a caller that decided a moment ago and a caller replaying an hour later
            // must get the same answer as the instant the store is reasoning about.
            if (transition.next() == ToolApprovalStatus.EXPIRED
                    && now.isBefore(current.request().expiresAt())) {
                throw failure(new ExecutionStoreFailure.ToolApprovalNotResolvable(
                        current.request().approvalId(), current.status(), ToolApprovalStatus.EXPIRED));
            }
            if ((transition.next() == ToolApprovalStatus.APPROVED
                    || transition.next() == ToolApprovalStatus.DENIED
                    || transition.next() == ToolApprovalStatus.CONSUMED)
                    && !now.isBefore(current.request().expiresAt())) {
                throw failure(new ExecutionStoreFailure.ToolApprovalNotResolvable(
                        current.request().approvalId(), current.status(), ToolApprovalStatus.EXPIRED));
            }
            updateToolApproval(connection, current.apply(transition, revision), current.status());
        }
    }

    private void insertToolApproval(Connection connection, DurableToolApproval approval, int position)
            throws SQLException {
        ToolApprovalRegistration request = approval.request();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO tool_approval (tenant_id, process_instance_id, approval_id, position, "
                        + "traversal_id, invocation_id, attempt_id, call_id, node_id, tool, "
                        + "canonical_arguments, arguments_digest, requester_request_id, "
                        + "requester_subject, requester_principal_type, requester_issuer, "
                        + "graph_version_pin, policy_version, expires_at_epoch_second, "
                        + "expires_at_nano, required_roles, required_scopes, requester_may_approve, "
                        + "continuation_version, continuation, continuation_digest, status, actor, "
                        + "revision) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, approval.key().tenantId());
            StoredUuid.bind(statement, 2, approval.key().processInstanceId());
            StoredUuid.bind(statement, 3, request.approvalId());
            statement.setInt(4, position);
            StoredUuid.bind(statement, 5, request.traversalId());
            StoredUuid.bind(statement, 6, request.invocationId());
            StoredUuid.bind(statement, 7, request.attemptId());
            StoredUuid.bind(statement, 8, request.callId());
            statement.setString(9, request.nodeId());
            statement.setString(10, request.tool());
            statement.setBytes(11, request.canonicalArguments());
            statement.setString(12, request.argumentsDigest());
            statement.setString(13, request.requester().requestId());
            statement.setString(14, request.requester().subject());
            statement.setString(15, request.requester().principalType().name());
            statement.setString(16, request.requester().issuer());
            statement.setString(17, request.graphVersionPin().reference());
            statement.setString(18, request.policyVersion());
            StoredInstant.bindValue(statement, 19, request.expiresAt());
            statement.setString(21, joinTokens(request.approverRequirements().requiredRoles()));
            statement.setString(22, joinTokens(request.approverRequirements().requiredScopes()));
            // A real BOOLEAN column rather than the single-host adapter's integer, because
            // PostgreSQL has the type and a column that can only hold what it means cannot be
            // written with a two as a third state.
            statement.setBoolean(23, request.requesterMayApprove());
            statement.setInt(24, request.continuationVersion());
            statement.setBytes(25, request.continuation());
            statement.setString(26, request.continuationDigest());
            statement.setString(27, approval.status().name());
            statement.setString(28, approval.actor());
            statement.setLong(29, approval.revision());
            statement.executeUpdate();
        }
    }

    private void updateToolApproval(Connection connection, DurableToolApproval approval,
                                    ToolApprovalStatus expected) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE tool_approval SET status = ?, actor = ?, revision = ? "
                        + "WHERE tenant_id = ? AND process_instance_id = ? AND approval_id = ? "
                        + "AND status = ?")) {
            statement.setString(1, approval.status().name());
            statement.setString(2, approval.actor());
            statement.setLong(3, approval.revision());
            statement.setString(4, approval.key().tenantId());
            StoredUuid.bind(statement, 5, approval.key().processInstanceId());
            StoredUuid.bind(statement, 6, approval.request().approvalId());
            statement.setString(7, expected.name());
            if (statement.executeUpdate() != 1) {
                throw failure(new ExecutionStoreFailure.ToolApprovalNotResolvable(
                        approval.request().approvalId(), expected, approval.status()));
            }
        }
    }

    private int nextToolApprovalPosition(Connection connection, ExecutionKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(position), -1) + 1 FROM tool_approval "
                        + "WHERE tenant_id = ? AND process_instance_id = ?")) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private DurableToolApproval readToolApproval(Connection connection, ExecutionKey key, UUID approvalId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TOOL_APPROVAL_COLUMNS
                + " WHERE a.tenant_id = ? AND a.process_instance_id = ? AND a.approval_id = ?")) {
            bindItem(statement, key, approvalId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? readToolApproval(rows, key, approvalId) : null;
            }
        }
    }

    private static DurableToolApproval readToolApproval(ResultSet rows, ExecutionKey expectedKey,
                                                        UUID expectedApprovalId) throws SQLException {
        String tenantId = rows.getString("tenant_id");
        UUID processInstanceId = expectedKey == null
                ? StoredUuid.required(rows, "tool_approval", "process_instance_id", tenantId)
                : StoredUuid.requiredMatching(rows, "tool_approval", "process_instance_id", expectedKey,
                        expectedKey.processInstanceId());
        var key = new ExecutionKey(tenantId, processInstanceId);
        try {
            UUID approvalId = expectedApprovalId == null
                    ? StoredUuid.required(rows, "tool_approval", "approval_id", key)
                    : StoredUuid.requiredMatching(rows, "tool_approval", "approval_id", key,
                            expectedApprovalId);
            var request = new ToolApprovalRegistration(approvalId,
                    StoredUuid.required(rows, "tool_approval", "traversal_id", key),
                    StoredUuid.required(rows, "tool_approval", "invocation_id", key),
                    StoredUuid.required(rows, "tool_approval", "attempt_id", key),
                    StoredUuid.required(rows, "tool_approval", "call_id", key),
                    rows.getString("node_id"), rows.getString("tool"),
                    rows.getBytes("canonical_arguments"), rows.getString("arguments_digest"),
                    new SecurityContext(rows.getString("requester_request_id"), key.tenantId(),
                            rows.getString("requester_subject"),
                            PrincipalType.valueOf(rows.getString("requester_principal_type")),
                            rows.getString("requester_issuer")),
                    new GraphVersionPin(rows.getString("graph_version_pin")),
                    rows.getString("policy_version"), StoredInstant.read(rows, "expires_at"),
                    new HandlerAuthorization(splitTokens(rows.getString("required_roles")),
                            splitTokens(rows.getString("required_scopes"))),
                    rows.getBoolean("requester_may_approve"), rows.getInt("continuation_version"),
                    rows.getBytes("continuation"), rows.getString("continuation_digest"));
            return new DurableToolApproval(key, request,
                    ToolApprovalStatus.valueOf(rows.getString("status")), rows.getString("actor"),
                    rows.getLong("revision"));
        } catch (IllegalArgumentException | IllegalStateException corrupted) {
            throw failure(new ExecutionStoreFailure.Corrupted(key, corrupted.getMessage()));
        }
    }

    // ---------------------------------------------------------------- agent authority and budgets

    @Override
    public CompletionStage<Optional<DurableAgentAuthorityBudget>> loadAgentAuthorityBudget(
            ExecutionKey key) {
        return async(() -> {
            Objects.requireNonNull(key, "key");
            return read(key, connection -> readAgentAuthorityBudget(connection, key, false));
        });
    }

    @Override
    public CompletionStage<AgentAuthorityControl> loadAgentAuthorityControl() {
        return async(() -> read(null, connection -> readAgentAuthorityControl(connection, null)));
    }

    /**
     * Advances the store-global control state from the exact snapshot the caller decided on.
     *
     * <h2>The lock order, which is the whole of why this is safe</h2>
     * <p>Two writers touch both the control row and the budget rows: this transition, which kills
     * every root of the epoch it is leaving, and {@code apply}, which validates a budget operation
     * against the epoch in force. Taken in opposite orders they deadlock, and worse, taken with no
     * lock at all they interleave — an {@code apply} that read {@code ACTIVE} at epoch 7 could commit
     * a hold after this transition had already swept epoch 7's roots, and the hold would survive a
     * kill that was supposed to have revoked it.</p>
     *
     * <p>So the order is fixed here and matched in {@link #writeAgentAuthorityBudget}: <strong>the
     * control row first, the budget rows second</strong>. This transition takes it {@code FOR UPDATE};
     * a batch takes it {@code FOR SHARE}, which lets any number of concurrent batches validate against
     * the same epoch and blocks only against a transition that is changing it. A batch therefore
     * either completes before the sweep begins or waits for it and then reads the new epoch, and
     * neither can hold a budget row while waiting for the control row.</p>
     *
     * <p>The {@code UPDATE} still carries the expected state and epoch in its {@code WHERE} even
     * though the row is already locked. The lock makes the check redundant against another database
     * client; the predicate makes the statement true on its own, which is what a reader has to be able
     * to verify without reconstructing the locking argument above.</p>
     */
    @Override
    public CompletionStage<AgentAuthorityControl> transitionAgentAuthorityControl(
            AgentAuthorityControlState expectedState, long expectedEpoch,
            AgentAuthorityControlState targetState) {
        return async(() -> {
            Objects.requireNonNull(expectedState, "expectedState");
            Objects.requireNonNull(targetState, "targetState");
            return write(null, connection -> {
                AgentAuthorityControl current = readAgentAuthorityControl(connection, "FOR UPDATE");
                if (current.state() != expectedState || current.epoch() != expectedEpoch) {
                    throw failure(ExecutionStoreFailure.invalid(
                            "agent authority control expectation is stale"));
                }
                long nextEpoch;
                try {
                    nextEpoch = Math.addExact(expectedEpoch, 1);
                } catch (ArithmeticException overflow) {
                    throw failure(ExecutionStoreFailure.invalid(
                            "agent authority control epoch is exhausted"));
                }
                // One instant for the whole transition, threaded rather than read twice. Two reads
                // would stamp the killed budgets and the control row with different instants, and
                // although the skew runs in the harmless direction, every other path in this adapter
                // carries one clock reading through a batch and a lone exception is the kind of
                // inconsistency that later gets copied rather than questioned.
                Instant now = clock.instant();
                long releasedTeamActive = targetState == AgentAuthorityControlState.KILLED
                        ? killAgentAuthorityBudgets(connection, expectedEpoch, now) : 0L;
                AgentAuthorityControl next;
                try {
                    next = new AgentAuthorityControl(targetState, nextEpoch, now,
                            Math.addExact(current.teamActiveReleased(), releasedTeamActive));
                } catch (ArithmeticException overflow) {
                    throw failure(ExecutionStoreFailure.invalid(
                            "agent authority release aggregate is exhausted"));
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE agent_authority_control SET state = ?, epoch = ?, "
                                + "changed_at_epoch_second = ?, changed_at_nano = ?, "
                                + "team_active_released = ? "
                                + "WHERE singleton AND state = ? AND epoch = ?")) {
                    statement.setString(1, next.state().name());
                    statement.setLong(2, next.epoch());
                    StoredInstant.bindValue(statement, 3, next.changedAt());
                    statement.setLong(5, next.teamActiveReleased());
                    statement.setString(6, expectedState.name());
                    statement.setLong(7, expectedEpoch);
                    if (statement.executeUpdate() != 1) {
                        throw failure(ExecutionStoreFailure.invalid(
                                "agent authority control expectation is stale"));
                    }
                }
                return next;
            });
        });
    }

    /**
     * Reads the singleton control row, optionally locking it for the rest of the transaction.
     *
     * <p>{@code lockClause} is {@code null} for a plain read, {@code "FOR SHARE"} for a batch that
     * will decide something against this epoch, and {@code "FOR UPDATE"} for the transition that
     * changes it. It is a string rather than a boolean because there are genuinely three answers and a
     * boolean would have forced the third caller to pass the wrong one of two.</p>
     */
    private AgentAuthorityControl readAgentAuthorityControl(Connection connection, String lockClause)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state, epoch, changed_at_epoch_second, changed_at_nano, team_active_released "
                        + "FROM agent_authority_control WHERE singleton"
                        + (lockClause == null ? "" : " " + lockClause));
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                // Seeded by the migration, so an absent row is a schema that is not this build's
                // rather than a state a caller could have produced. Unavailable rather than
                // Corrupted, because there is no ExecutionKey to name and nothing tenant-scoped is
                // damaged.
                throw failure(new ExecutionStoreFailure.Unavailable(
                        "agent authority control is unavailable"));
            }
            try {
                return new AgentAuthorityControl(
                        AgentAuthorityControlState.valueOf(rows.getString("state")),
                        rows.getLong("epoch"), StoredInstant.read(rows, "changed_at"),
                        rows.getLong("team_active_released"));
            } catch (IllegalArgumentException | IllegalStateException invalid) {
                throw failure(new ExecutionStoreFailure.Unavailable(
                        "agent authority control is invalid"));
            }
        }
    }

    private Optional<DurableAgentAuthorityBudget> readAgentAuthorityBudget(Connection connection,
                                                                           ExecutionKey key,
                                                                           boolean forUpdate)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT aggregate FROM agent_authority_budget "
                        + "WHERE tenant_id = ? AND process_instance_id = ?"
                        + (forUpdate ? " FOR UPDATE" : ""))) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(AgentAuthorityBudgetCodec.read(key, rows.getBytes("aggregate")));
                } catch (RuntimeException corrupted) {
                    throw failure(new ExecutionStoreFailure.Corrupted(key,
                            "agent authority aggregate is invalid"));
                }
            }
        }
    }

    /**
     * Revokes every root still active at {@code expectedEpoch}, and reports the team slots released.
     *
     * <p>{@code FOR UPDATE} on the scan, ordered by identity, is what makes this a sweep rather than a
     * race. Each row is read, folded and rewritten, so without the lock a batch committing between
     * the read and the write would have its own update replaced by this one — the lost update, with
     * the specific consequence that a hold taken during the kill survives it. The ordering matters
     * because two sweeps of the same set must acquire in the same sequence; they cannot both be
     * running today, since both hold the control row exclusively first, and the order costs nothing
     * to keep true.</p>
     */
    private long killAgentAuthorityBudgets(Connection connection, long expectedEpoch, Instant now)
            throws SQLException {
        var replacements = new ArrayList<BudgetReplacement>();
        long releasedTeamActive = 0L;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT tenant_id, process_instance_id, aggregate FROM agent_authority_budget "
                        + "ORDER BY tenant_id, process_instance_id FOR UPDATE");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String tenantId = rows.getString("tenant_id");
                var key = new ExecutionKey(tenantId, StoredUuid.required(rows,
                        "agent_authority_budget", "process_instance_id", tenantId));
                DurableAgentAuthorityBudget budget;
                try {
                    budget = AgentAuthorityBudgetCodec.read(key, rows.getBytes("aggregate"));
                } catch (RuntimeException invalid) {
                    throw failure(new ExecutionStoreFailure.Corrupted(key,
                            "agent authority aggregate is invalid"));
                }
                if (budget.state() != AgentAuthorityState.ACTIVE
                        || budget.controlEpoch() != expectedEpoch) {
                    continue;
                }
                DurableAgentAuthorityBudget killed;
                try {
                    killed = AgentAuthorityBudgetFold.apply(key, budget,
                            new AgentBudgetOperation.KillRoot(expectedEpoch), now);
                } catch (IllegalArgumentException | IllegalStateException invalid) {
                    // Wrapped for the same reason the batch path wraps it, and not because this call
                    // is expected to reject: only budgets already filtered to ACTIVE at the expected
                    // epoch reach here, and a kill accepts those. What the wrap buys is that a fold
                    // rule this sweep has not anticipated arrives as a classified store failure rather
                    // than as a raw argument exception escaping the port, which is the one outcome the
                    // port forbids regardless of how it was reached.
                    throw failure(ExecutionStoreFailure.invalid(invalid.getMessage()));
                }
                try {
                    releasedTeamActive = Math.addExact(releasedTeamActive,
                            budget.reserved().teamActive() - killed.reserved().teamActive());
                } catch (ArithmeticException overflow) {
                    throw failure(ExecutionStoreFailure.invalid(
                            "agent authority release aggregate is exhausted"));
                }
                replacements.add(new BudgetReplacement(key, AgentAuthorityBudgetCodec.write(killed)));
            }
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE agent_authority_budget SET aggregate = ? "
                        + "WHERE tenant_id = ? AND process_instance_id = ?")) {
            for (BudgetReplacement replacement : replacements) {
                update.setBytes(1, replacement.aggregate());
                update.setString(2, replacement.key().tenantId());
                StoredUuid.bind(update, 3, replacement.key().processInstanceId());
                update.addBatch();
            }
            update.executeBatch();
        }
        return releasedTeamActive;
    }

    /** One killed ledger, held until the scan's cursor is closed and the rows can be rewritten. */
    private record BudgetReplacement(ExecutionKey key, byte[] aggregate) {
    }

    /**
     * Folds this batch's budget operations into the instance's ledger.
     *
     * <p>The read is {@code FOR UPDATE} and the control row is taken {@code FOR SHARE} first, in that
     * order — see {@link #transitionAgentAuthorityControl} for why the order is the one that cannot
     * deadlock. Neither lock is taken at all when the batch carries no budget operation, which is the
     * overwhelming majority of batches: a facility nobody in this batch used must not put a lock in
     * the way of one that did.</p>
     */
    private void writeAgentAuthorityBudget(Connection connection, ExecutionKey key,
                                           ExecutionBatch batch, ProcessInstance folded, Instant now)
            throws SQLException {
        if (batch.agentBudgetOperations().isEmpty()) {
            return;
        }
        AgentAuthorityControl control = readAgentAuthorityControl(connection, "FOR SHARE");
        DurableAgentAuthorityBudget budget = readAgentAuthorityBudget(connection, key, true)
                .orElse(null);
        for (AgentBudgetOperation operation : batch.agentBudgetOperations()) {
            requireAgentAuthorityControl(operation, control);
            if (operation instanceof AgentBudgetOperation.RegisterGrant register) {
                requireGrantBindingMatchesFold(folded, register);
            }
            try {
                budget = AgentAuthorityBudgetFold.apply(key, budget, operation, now);
            } catch (IllegalArgumentException | IllegalStateException invalid) {
                throw failure(ExecutionStoreFailure.invalid(invalid.getMessage()));
            }
        }
        byte[] encoded = AgentAuthorityBudgetCodec.write(budget);
        if (encoded.length > config.maxPayloadBytes()) {
            throw failure(new ExecutionStoreFailure.PayloadTooLarge(encoded.length,
                    config.maxPayloadBytes()));
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO agent_authority_budget (tenant_id, process_instance_id, aggregate) "
                        + "VALUES (?, ?, ?) ON CONFLICT (tenant_id, process_instance_id) "
                        + "DO UPDATE SET aggregate = EXCLUDED.aggregate")) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            statement.setBytes(3, encoded);
            statement.executeUpdate();
        }
    }

    /**
     * Requires a new grant to name the invocation the post-fold aggregate actually has.
     *
     * <p>A grant's authority is bounded by where it sits in the causal graph, so a binding that names
     * a different node or a different set of causal parents than the invocation carries would be
     * authority derived from a shape the execution does not have.</p>
     */
    private static void requireGrantBindingMatchesFold(ProcessInstance folded,
                                                       AgentBudgetOperation.RegisterGrant register) {
        var invocation = folded == null ? null : folded.traversals().values().stream()
                .flatMap(traversal -> traversal.invocations().values().stream())
                .filter(candidate -> candidate.invocationId().equals(register.binding().invocationId()))
                .findFirst().orElse(null);
        if (invocation == null || !invocation.nodeId().equals(register.binding().nodeId())
                || !invocation.parentInvocationIds()
                        .equals(register.binding().causalParentInvocationIds())) {
            throw failure(ExecutionStoreFailure.invalid(
                    "agent grant binding does not name the post-fold invocation"));
        }
    }

    /**
     * Refuses an operation issued under an epoch that is no longer in force.
     *
     * <p>Only the four operations that <em>extend</em> authority carry an epoch. Cancelling,
     * exhausting, settling and reporting a breach do not, because they only ever remove authority and
     * refusing one because the epoch moved would leave authority outstanding that a caller was trying
     * to give back.</p>
     */
    private static void requireAgentAuthorityControl(AgentBudgetOperation operation,
                                                     AgentAuthorityControl control) {
        Long expected = switch (operation) {
            case AgentBudgetOperation.RegisterRoot register -> register.controlEpoch();
            case AgentBudgetOperation.RegisterGrant register -> register.controlEpoch();
            case AgentBudgetOperation.Hold hold -> hold.controlEpoch();
            case AgentBudgetOperation.Dispatch dispatch -> dispatch.controlEpoch();
            default -> null;
        };
        if (expected != null && (control.state() != AgentAuthorityControlState.ACTIVE
                || control.epoch() != expected)) {
            throw failure(ExecutionStoreFailure.invalid(
                    "agent authority control is not active for this epoch"));
        }
    }

    // ---------------------------------------------------------------- durable execution pauses

    @Override
    public CompletionStage<Optional<DurableExecutionPause>> loadExecutionPause(ExecutionKey key,
                                                                               UUID pauseId) {
        return async(() -> {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(pauseId, "pauseId");
            return read(key, connection ->
                    Optional.ofNullable(readExecutionPause(connection, key, pauseId)));
        });
    }

    @Override
    public CompletionStage<List<DurableExecutionPause>> executionPauses(ExecutionKey key) {
        return async(() -> {
            Objects.requireNonNull(key, "key");
            return read(key, connection -> {
                var pauses = new ArrayList<DurableExecutionPause>();
                try (PreparedStatement statement = connection.prepareStatement(EXECUTION_PAUSE_COLUMNS
                        + " WHERE p.tenant_id = ? AND p.process_instance_id = ? "
                        + "ORDER BY p.position, p.pause_id")) {
                    statement.setString(1, key.tenantId());
                    StoredUuid.bind(statement, 2, key.processInstanceId());
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            pauses.add(readExecutionPause(rows, key, null));
                        }
                    }
                }
                return List.copyOf(pauses);
            });
        });
    }

    @Override
    public CompletionStage<Optional<DurableExecutionPause>> findHeldExecutionPause(String tenantId,
                                                                                    UUID traversalId) {
        return async(() -> {
            requireTenantId(tenantId);
            Objects.requireNonNull(traversalId, "traversalId");
            return read(null, connection ->
                    Optional.ofNullable(readHeldExecutionPause(connection, tenantId, traversalId)));
        });
    }

    private void writeExecutionPauses(Connection connection, ExecutionKey key, ExecutionBatch batch,
                                      ProcessInstance folded, GraphVersionPin pin, long revision)
            throws SQLException {
        for (ExecutionPauseRegistration registration : batch.executionPausesToRegister()) {
            requireInvocationExists(folded, registration.traversalId(), registration.afterInvocationId(),
                    "execution pause " + registration.pauseId());
            if (!key.tenantId().equals(registration.requester().tenantId())
                    || !pin.equals(registration.graphVersionPin())) {
                throw failure(ExecutionStoreFailure.invalid(
                        "execution pause identity or graph pin does not match its execution"));
            }
            if (pauseAlreadyRegistered(connection, key, registration)) {
                continue;
            }
            DurableExecutionPause pause = DurableExecutionPause.held(key, registration, revision);
            if (insertApplied(connection, () -> insertExecutionPause(connection, pause,
                    nextExecutionPausePosition(connection, key)))) {
                continue;
            }
            if (pauseAlreadyRegistered(connection, key, registration)) {
                continue;
            }
            throw failure(ExecutionStoreFailure.invalid("execution pause " + registration.pauseId()
                    + " collided with a uniqueness rule whose winning row cannot be read back"));
        }
        for (ExecutionPauseTransition transition : batch.executionPauseTransitions()) {
            DurableExecutionPause current = readExecutionPause(connection, key, transition.pauseId());
            if (current == null) {
                throw failure(ExecutionStoreFailure.invalid("unknown execution pause "
                        + transition.pauseId()));
            }
            if (current.alreadyApplied(transition)) {
                continue;
            }
            if (!current.status().canTransitionTo(transition.next())) {
                throw failure(new ExecutionStoreFailure.ExecutionPauseNotResolvable(
                        current.request().pauseId(), current.status(), transition.next()));
            }
            updateExecutionPause(connection, current.apply(transition, revision), current.status());
        }
    }

    /**
     * Whether this exact hold is already committed, refusing every other collision.
     *
     * <p>The traversal's live hold is checked here rather than left to the partial unique index, so
     * the caller is told which hold already owns the traversal instead of reading a constraint name.
     * The index still decides the contested case — see {@link #registerHandler} for why the lookup
     * cannot be the decision — and this is then asked again to name the winner.</p>
     */
    private boolean pauseAlreadyRegistered(Connection connection, ExecutionKey key,
                                           ExecutionPauseRegistration registration) throws SQLException {
        DurableExecutionPause existing = readExecutionPause(connection, key, registration.pauseId());
        if (existing != null) {
            if (!existing.request().equals(registration)) {
                throw failure(ExecutionStoreFailure.invalid("execution pause " + registration.pauseId()
                        + " is already committed with a different hold"));
            }
            return true;
        }
        DurableExecutionPause held = readHeldExecutionPause(connection, key.tenantId(),
                registration.traversalId());
        if (held != null) {
            throw failure(ExecutionStoreFailure.invalid("traversal " + registration.traversalId()
                    + " is already held by " + held.request().pauseId()));
        }
        return false;
    }

    private void insertExecutionPause(Connection connection, DurableExecutionPause pause, int position)
            throws SQLException {
        ExecutionPauseRegistration request = pause.request();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO execution_pause (tenant_id, process_instance_id, pause_id, position, "
                        + "traversal_id, after_invocation_id, node_id, command_directive, "
                        + "command_name, requester_request_id, requester_subject, "
                        + "requester_principal_type, requester_issuer, graph_version_pin, "
                        + "continuation_version, continuation, continuation_digest, status, actor, "
                        + "revision) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "?)")) {
            statement.setString(1, pause.key().tenantId());
            StoredUuid.bind(statement, 2, pause.key().processInstanceId());
            StoredUuid.bind(statement, 3, request.pauseId());
            statement.setInt(4, position);
            StoredUuid.bind(statement, 5, request.traversalId());
            StoredUuid.bind(statement, 6, request.afterInvocationId());
            statement.setString(7, request.nodeId());
            statement.setString(8, request.commandDirective());
            statement.setString(9, request.commandName());
            statement.setString(10, request.requester().requestId());
            statement.setString(11, request.requester().subject());
            statement.setString(12, request.requester().principalType().name());
            statement.setString(13, request.requester().issuer());
            statement.setString(14, request.graphVersionPin().reference());
            statement.setInt(15, request.continuationVersion());
            statement.setBytes(16, request.continuation());
            statement.setString(17, request.continuationDigest());
            statement.setString(18, pause.status().name());
            statement.setString(19, pause.actor());
            statement.setLong(20, pause.revision());
            statement.executeUpdate();
        }
    }

    private void updateExecutionPause(Connection connection, DurableExecutionPause pause,
                                      ExecutionPauseStatus expected) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE execution_pause SET status = ?, actor = ?, revision = ? "
                        + "WHERE tenant_id = ? AND process_instance_id = ? AND pause_id = ? "
                        + "AND status = ?")) {
            statement.setString(1, pause.status().name());
            statement.setString(2, pause.actor());
            statement.setLong(3, pause.revision());
            statement.setString(4, pause.key().tenantId());
            StoredUuid.bind(statement, 5, pause.key().processInstanceId());
            StoredUuid.bind(statement, 6, pause.request().pauseId());
            statement.setString(7, expected.name());
            if (statement.executeUpdate() != 1) {
                throw failure(new ExecutionStoreFailure.ExecutionPauseNotResolvable(
                        pause.request().pauseId(), expected, pause.status()));
            }
        }
    }

    private int nextExecutionPausePosition(Connection connection, ExecutionKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(position), -1) + 1 FROM execution_pause "
                        + "WHERE tenant_id = ? AND process_instance_id = ?")) {
            statement.setString(1, key.tenantId());
            StoredUuid.bind(statement, 2, key.processInstanceId());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private DurableExecutionPause readExecutionPause(Connection connection, ExecutionKey key,
                                                     UUID pauseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(EXECUTION_PAUSE_COLUMNS
                + " WHERE p.tenant_id = ? AND p.process_instance_id = ? AND p.pause_id = ?")) {
            bindItem(statement, key, pauseId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? readExecutionPause(rows, key, pauseId) : null;
            }
        }
    }

    private DurableExecutionPause readHeldExecutionPause(Connection connection, String tenantId,
                                                         UUID traversalId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(EXECUTION_PAUSE_COLUMNS
                + " WHERE p.tenant_id = ? AND p.traversal_id = ? AND p.status IN "
                + LIVE_EXECUTION_PAUSE_STATUSES)) {
            statement.setString(1, tenantId);
            StoredUuid.bind(statement, 2, traversalId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? readExecutionPause(rows, null, null) : null;
            }
        }
    }

    private static DurableExecutionPause readExecutionPause(ResultSet rows, ExecutionKey expectedKey,
                                                            UUID expectedPauseId) throws SQLException {
        String tenantId = rows.getString("tenant_id");
        UUID processInstanceId = expectedKey == null
                ? StoredUuid.required(rows, "execution_pause", "process_instance_id", tenantId)
                : StoredUuid.requiredMatching(rows, "execution_pause", "process_instance_id",
                        expectedKey, expectedKey.processInstanceId());
        var key = new ExecutionKey(tenantId, processInstanceId);
        try {
            UUID pauseId = expectedPauseId == null
                    ? StoredUuid.required(rows, "execution_pause", "pause_id", key)
                    : StoredUuid.requiredMatching(rows, "execution_pause", "pause_id", key,
                            expectedPauseId);
            var request = new ExecutionPauseRegistration(pauseId,
                    StoredUuid.required(rows, "execution_pause", "traversal_id", key),
                    StoredUuid.required(rows, "execution_pause", "after_invocation_id", key),
                    rows.getString("node_id"), rows.getString("command_directive"),
                    rows.getString("command_name"),
                    new SecurityContext(rows.getString("requester_request_id"), key.tenantId(),
                            rows.getString("requester_subject"),
                            PrincipalType.valueOf(rows.getString("requester_principal_type")),
                            rows.getString("requester_issuer")),
                    new GraphVersionPin(rows.getString("graph_version_pin")),
                    rows.getInt("continuation_version"), rows.getBytes("continuation"),
                    rows.getString("continuation_digest"));
            return new DurableExecutionPause(key, request,
                    ExecutionPauseStatus.valueOf(rows.getString("status")), rows.getString("actor"),
                    rows.getLong("revision"));
        } catch (IllegalArgumentException | IllegalStateException corrupted) {
            throw failure(new ExecutionStoreFailure.Corrupted(key, corrupted.getMessage()));
        }
    }

    // ---------------------------------------------------------------- durable human tasks

    @Override
    public CompletionStage<Optional<DurableHumanTask>> loadHumanTask(String tenantId, UUID taskId) {
        return async(() -> {
            requireTenantId(tenantId);
            Objects.requireNonNull(taskId, "taskId");
            return read(null, connection ->
                    Optional.ofNullable(readHumanTask(connection, tenantId, taskId)));
        });
    }

    /**
     * One bounded, deterministic page of a tenant's inbox.
     *
     * <p>{@code readFolded} rather than {@code read}, because this is two statements — the cursor is
     * validated against the tenant before the page is read — and under {@code READ COMMITTED} each of
     * them would otherwise take its own snapshot. A cursor validated against one snapshot and paged
     * against a later one can skip a row that was inserted between them, which is precisely the
     * failure a stable cursor exists to rule out.</p>
     *
     * <p>The page is read one row wider than the caller asked for. That extra row is the entire
     * evidence for whether a next cursor exists, and computing it any other way — a second
     * {@code COUNT}, or issuing a cursor unconditionally — either costs another scan or hands the
     * caller a cursor that resolves to nothing.</p>
     */
    @Override
    public CompletionStage<HumanTaskPage> listHumanTasks(String tenantId, HumanTaskQuery query) {
        return async(() -> {
            requireTenantId(tenantId);
            Objects.requireNonNull(query, "query");
            if (query.limit() < 1 || query.limit() > maxHumanTaskPageSize()) {
                throw failure(ExecutionStoreFailure.invalid(
                        "human-task page limit must be between 1 and " + maxHumanTaskPageSize()));
            }
            return readFolded(null, connection -> {
                if (query.cursor().isPresent()
                        && readHumanTask(connection, tenantId, query.cursor().orElseThrow()) == null) {
                    throw failure(ExecutionStoreFailure.invalid(
                            "human-task cursor does not belong to this tenant"));
                }
                List<HumanTaskStatus> admitted = Arrays.stream(HumanTaskStatus.values())
                        .filter(query::admits).toList();
                if (admitted.isEmpty()) {
                    return new HumanTaskPage(List.of(), Optional.empty());
                }
                var matching = new ArrayList<DurableHumanTask>();
                // The status filter is in the WHERE rather than applied after the fact, so a page of
                // outstanding tasks is a page of outstanding tasks: filtering in Java would let
                // terminal rows consume the limit and return a short page that looks like the end of
                // the inbox.
                String sql = HUMAN_TASK_COLUMNS + " WHERE t.tenant_id = ?"
                        + (query.cursor().isPresent() ? " AND t.task_id > ?" : "")
                        + " AND t.status IN (" + admitted.stream().map(ignored -> "?")
                                .collect(java.util.stream.Collectors.joining(",")) + ")"
                        // Ordered by the native uuid, which PostgreSQL compares as unsigned bytes --
                        // the same order as the canonical text form, so a cursor issued by this
                        // adapter means the same boundary as one issued by any other.
                        + " ORDER BY t.task_id LIMIT ?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    int parameter = 1;
                    statement.setString(parameter++, tenantId);
                    if (query.cursor().isPresent()) {
                        StoredUuid.bind(statement, parameter++, query.cursor().orElseThrow());
                    }
                    for (HumanTaskStatus status : admitted) {
                        statement.setString(parameter++, status.name());
                    }
                    statement.setInt(parameter, query.limit() + 1);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            matching.add(readHumanTask(rows, tenantId, null));
                        }
                    }
                }
                int end = Math.min(query.limit(), matching.size());
                List<DurableHumanTask> page = List.copyOf(matching.subList(0, end));
                Optional<UUID> next = matching.size() > end
                        ? Optional.of(page.getLast().request().taskId()) : Optional.empty();
                return new HumanTaskPage(page, next);
            });
        });
    }

    /**
     * One authorized attention page, with counts that are authoritative for the caller who asked.
     *
     * <p><b>Every row is authorized before it is counted.</b> The projection is built first and a
     * caller with no available action on a row gets {@code null} back, which removes the row from the
     * page <em>and</em> from every count including the per-node breakdown. Counting first and
     * filtering afterwards would turn the counts into an existence oracle: a caller who may not see a
     * task would still learn how many there are, which is most of what the task's existence
     * discloses.</p>
     *
     * <p>Ordering, counting and paging are one pass over one statement rather than a count query plus
     * a page query. Two statements would each take their own snapshot under {@code READ COMMITTED},
     * so the counts could describe a set the page is not drawn from — and no isolation level makes
     * two statements agree with an authorization decision that happens in this process between
     * them.</p>
     */
    @Override
    public CompletionStage<HumanTaskAttentionPage> listHumanTaskAttention(
            String tenantId, HumanTaskAttentionQuery query,
            HumanTaskAttentionAuthorization authorization) {
        return async(() -> {
            requireTenantId(tenantId);
            Objects.requireNonNull(query, "query");
            Objects.requireNonNull(authorization, "authorization");
            if (query.limit() > maxHumanTaskAttentionPageSize()) {
                throw failure(ExecutionStoreFailure.invalid(
                        "human-task page limit must be between 1 and "
                                + maxHumanTaskAttentionPageSize()));
            }
            HumanTaskAttentionCursor.Boundary boundary;
            try {
                // The cursor is resolved from itself and from the scope it was issued for, never by
                // looking up the task that produced it: that task may have settled, and a boundary
                // that stopped existing would silently restart the paging.
                boundary = query.cursor()
                        .map(cursor -> cursor.boundary(tenantId, query, authorization)).orElse(null);
            } catch (IllegalArgumentException invalid) {
                throw failure(ExecutionStoreFailure.invalid(invalid.getMessage()));
            }
            return read(null, connection -> {
                StringBuilder sql = new StringBuilder(HUMAN_TASK_ATTENTION_COLUMNS
                        + "WHERE t.tenant_id = ? AND t.graph_version_pin = ? "
                        + "AND t.status IN " + LIVE_HUMAN_TASK_STATUSES + " "
                        + "AND t.confirmation_version > 0");
                query.deploymentId().ifPresent(ignored -> sql.append(" AND p.deployment_id = ?"));
                query.processInstanceId().ifPresent(ignored ->
                        sql.append(" AND t.process_instance_id = ?"));
                query.traversalId().ifPresent(ignored -> sql.append(" AND t.traversal_id = ?"));
                query.nodeId().ifPresent(ignored -> sql.append(" AND t.node_id = ?"));
                query.taskId().ifPresent(ignored -> sql.append(" AND t.task_id = ?"));
                query.generation().ifPresent(ignored -> sql.append(" AND t.generation = ?"));
                sql.append(" ORDER BY t.created_at_epoch_second, t.created_at_nano, t.task_id");

                long pending = 0;
                long escalated = 0;
                var nodeCounts = new TreeMap<String, long[]>();
                var pageRows = new ArrayList<HumanTaskAttentionItem>(query.limit() + 1);
                try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                    int parameter = 1;
                    statement.setString(parameter++, tenantId);
                    statement.setString(parameter++, query.graphVersion());
                    if (query.deploymentId().isPresent()) {
                        statement.setString(parameter++, query.deploymentId().orElseThrow());
                    }
                    if (query.processInstanceId().isPresent()) {
                        StoredUuid.bind(statement, parameter++, query.processInstanceId().orElseThrow());
                    }
                    if (query.traversalId().isPresent()) {
                        StoredUuid.bind(statement, parameter++, query.traversalId().orElseThrow());
                    }
                    if (query.nodeId().isPresent()) {
                        statement.setString(parameter++, query.nodeId().orElseThrow());
                    }
                    if (query.taskId().isPresent()) {
                        StoredUuid.bind(statement, parameter++, query.taskId().orElseThrow());
                    }
                    if (query.generation().isPresent()) {
                        statement.setLong(parameter, query.generation().orElseThrow());
                    }
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            HumanTaskAttentionItem item = readHumanTaskAttentionItem(rows, tenantId,
                                    authorization);
                            if (item == null) {
                                continue;
                            }
                            pending++;
                            if (item.status() == HumanTaskStatus.ESCALATED) {
                                escalated++;
                            }
                            // A query that selected one node already IS the breakdown, so producing
                            // a single-entry map would repeat the page's own answer as if it were an
                            // aggregate over the graph.
                            if (query.nodeId().isEmpty()) {
                                long[] node = nodeCounts.get(item.nodeId());
                                if (node == null) {
                                    if (nodeCounts.size() == maxHumanTaskAttentionNodeCounts()) {
                                        // Refused rather than truncated: a breakdown missing nodes
                                        // nobody named is a wrong answer that reads as a complete
                                        // one, and the caller cannot tell which nodes are absent.
                                        throw failure(
                                                new ExecutionStoreFailure.HumanTaskAttentionTooLarge(
                                                        nodeCounts.size() + 1L,
                                                        maxHumanTaskAttentionNodeCounts()));
                                    }
                                    node = new long[2];
                                    nodeCounts.put(item.nodeId(), node);
                                }
                                node[0]++;
                                if (item.status() == HumanTaskStatus.ESCALATED) {
                                    node[1]++;
                                }
                            }
                            if ((boundary == null || after(item, boundary))
                                    && pageRows.size() <= query.limit()) {
                                pageRows.add(item);
                            }
                        }
                    }
                }
                int end = Math.min(query.limit(), pageRows.size());
                List<HumanTaskAttentionItem> page = List.copyOf(pageRows.subList(0, end));
                Optional<HumanTaskAttentionCursor> next = pageRows.size() > end
                        ? Optional.of(HumanTaskAttentionCursor.issue(tenantId, query, authorization,
                                page.getLast().createdAt(), page.getLast().taskId()))
                        : Optional.empty();
                List<HumanTaskNodeAttentionCounts> perNode = nodeCounts.entrySet().stream()
                        .map(entry -> new HumanTaskNodeAttentionCounts(entry.getKey(),
                                entry.getValue()[0], entry.getValue()[1]))
                        .toList();
                return new HumanTaskAttentionPage(page, next,
                        new HumanTaskAttentionCounts(pending, escalated), perNode);
            });
        });
    }

    @Override
    public CompletionStage<Optional<HumanTaskAttentionItem>> findHumanTaskAttention(
            String tenantId, HumanTaskAttentionLocator locator,
            HumanTaskAttentionAuthorization authorization) {
        return async(() -> {
            requireTenantId(tenantId);
            Objects.requireNonNull(locator, "locator");
            Objects.requireNonNull(authorization, "authorization");
            return read(null, connection -> {
                // Absent, terminal, stale-generation and unauthorized all answer empty. Every one of
                // them is a reason the caller has no action, and telling them apart would make this
                // recovery path a task-existence oracle for anyone holding a guessed identity.
                String sql = HUMAN_TASK_ATTENTION_COLUMNS
                        + "WHERE t.tenant_id = ? AND t.task_id = ? AND t.generation = ? "
                        + "AND t.status IN " + LIVE_HUMAN_TASK_STATUSES + " "
                        + "AND t.confirmation_version > 0";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, tenantId);
                    StoredUuid.bind(statement, 2, locator.taskId());
                    statement.setLong(3, locator.generation());
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            return Optional.empty();
                        }
                        return Optional.ofNullable(
                                readHumanTaskAttentionItem(rows, tenantId, authorization));
                    }
                }
            });
        });
    }

    /**
     * Whether this row is strictly past the cursor's boundary in the page's own order.
     *
     * <p>The identifier is compared as text rather than through {@link UUID#compareTo}, which orders
     * by two <em>signed</em> longs and therefore disagrees with both the canonical string form and
     * PostgreSQL's own unsigned-byte {@code uuid} ordering. Using it here would make the tie-break
     * disagree with the {@code ORDER BY} that produced the rows, and a page would skip or repeat
     * whichever identifiers straddle the sign boundary.</p>
     */
    private static boolean after(HumanTaskAttentionItem item,
                                 HumanTaskAttentionCursor.Boundary boundary) {
        int time = item.createdAt().compareTo(boundary.createdAt());
        return time > 0
                || time == 0 && item.taskId().toString().compareTo(boundary.taskId().toString()) > 0;
    }

    /**
     * Reads only the bounded columns needed to authorize and construct an attention row.
     *
     * <p>A {@code null} result means the caller has no currently available action and must learn
     * neither the row nor its count.</p>
     */
    private static HumanTaskAttentionItem readHumanTaskAttentionItem(
            ResultSet rows, String tenantId, HumanTaskAttentionAuthorization authorization)
            throws SQLException {
        UUID processInstanceId = StoredUuid.required(rows, "human_task", "process_instance_id",
                tenantId);
        var key = new ExecutionKey(tenantId, processInstanceId);
        try {
            var requirements = new HandlerAuthorization(splitTokens(rows.getString("required_roles")),
                    splitTokens(rows.getString("required_scopes")));
            String requesterActor = new SecurityContext(rows.getString("requester_request_id"),
                    tenantId, rows.getString("requester_subject"),
                    PrincipalType.valueOf(rows.getString("requester_principal_type")),
                    rows.getString("requester_issuer")).qualifiedIdentity();
            List<HumanTaskConfirmationAction> pinnedActions =
                    splitConfirmationActions(rows.getString("confirmation_actions"));
            List<HumanTaskConfirmationAction> actions = authorization.permittedActions(requirements,
                    requesterActor, pinnedActions);
            if (actions.isEmpty()) {
                return null;
            }
            var presentation = new HumanTaskConfirmationPresentation(
                    rows.getInt("confirmation_version"), rows.getString("confirmation_prompt"),
                    HumanTaskCommentRequirement.valueOf(
                            rows.getString("confirmation_comment_requirement")),
                    pinnedActions, rows.getString("confirmation_resolve_label"),
                    rows.getString("confirmation_deny_label"),
                    rows.getString("confirmation_cancel_label"));
            Instant escalation = nullableInstant(rows, "escalate_at");
            return new HumanTaskAttentionItem(
                    StoredUuid.required(rows, "human_task", "task_id", key), rows.getLong("generation"),
                    HumanTaskStatus.valueOf(rows.getString("status")),
                    rows.getString("graph_version_pin"),
                    Optional.ofNullable(rows.getString("attention_deployment_id")), processInstanceId,
                    StoredUuid.required(rows, "human_task", "traversal_id", key),
                    rows.getString("node_id"), StoredInstant.read(rows, "created_at"),
                    StoredInstant.read(rows, "expires_at"), Optional.ofNullable(escalation),
                    presentation, rows.getInt("confirmation_max_prompt_bytes"),
                    rows.getInt("confirmation_max_action_label_bytes"),
                    rows.getInt("confirmation_max_comment_bytes"), actions);
        } catch (IllegalArgumentException | IllegalStateException corrupted) {
            throw failure(new ExecutionStoreFailure.Corrupted(key, corrupted.getMessage()));
        }
    }

    private void writeHumanTasks(Connection connection, ExecutionKey key, ExecutionBatch batch,
                                 ProcessInstance folded, GraphVersionPin pin, long revision,
                                 Instant now) throws SQLException {
        for (HumanTaskRegistration registration : batch.humanTasksToRegister()) {
            requireInvocationExists(folded, registration.traversalId(), registration.invocationId(),
                    "human task " + registration.taskId());
            requireAttemptExists(folded, registration.traversalId(), registration.invocationId(),
                    registration.attemptId(), "human task " + registration.taskId());
            if (!key.tenantId().equals(registration.requester().tenantId())
                    || !pin.equals(registration.graphVersionPin())) {
                throw failure(ExecutionStoreFailure.invalid(
                        "human task identity or graph pin does not match its execution"));
            }
            // The deduplication answer comes BEFORE the admission rules, and the order is
            // load-bearing: a task admitted under one policy must stay replayable after the policy
            // tightens, or a retried registration would start failing for a task that is already
            // stored and perfectly valid.
            if (humanTaskDeduplicated(connection, key, registration)) {
                continue;
            }
            requireAdmissibleHumanTask(registration, now);
            requireHumanTaskIdentityFree(connection, key, registration);
            DurableHumanTask task = DurableHumanTask.waiting(key, registration, revision, now);
            if (insertApplied(connection, () -> insertHumanTask(connection, task))) {
                continue;
            }
            if (humanTaskDeduplicated(connection, key, registration)) {
                continue;
            }
            requireHumanTaskIdentityFree(connection, key, registration);
            throw failure(ExecutionStoreFailure.invalid("human task " + registration.taskId()
                    + " collided with a uniqueness rule whose winning row cannot be read back"));
        }
        for (HumanTaskTransition transition : batch.humanTaskTransitions()) {
            transitionHumanTask(connection, key, transition, revision, now);
        }
    }

    /** Whether this exact task is already stored under its deduplication key. */
    private boolean humanTaskDeduplicated(Connection connection, ExecutionKey key,
                                          HumanTaskRegistration registration) throws SQLException {
        DurableHumanTask deduplicated = readHumanTaskBy(connection, key.tenantId(),
                "deduplication_key", registration.deduplicationKey(), false);
        if (deduplicated == null) {
            return false;
        }
        if (!deduplicated.request().sameRequest(registration)) {
            throw failure(ExecutionStoreFailure.invalid("deduplication key "
                    + registration.deduplicationKey()
                    + " already registers a different human task"));
        }
        return true;
    }

    /**
     * The admission rules a <em>new</em> task must satisfy, all decided against the store's clock.
     *
     * <p>The clock matters more here than anywhere else on this port: a task's expiry and escalation
     * are the two instants a person is racing, and a caller that computed them against its own clock
     * would register a task that is already expired on the store that has to honour it.</p>
     */
    private void requireAdmissibleHumanTask(HumanTaskRegistration registration, Instant now) {
        try {
            humanTaskPolicy.requireNewRegistration(registration, now);
        } catch (IllegalArgumentException refused) {
            throw failure(ExecutionStoreFailure.invalid(refused.getMessage()));
        }
        if (registration.responseSchema().maxBytes() > maxHumanTaskResponsePayloadBytes()) {
            throw failure(new ExecutionStoreFailure.PayloadTooLarge(
                    registration.responseSchema().maxBytes(), maxHumanTaskResponsePayloadBytes()));
        }
        if (!now.isBefore(registration.expiresAt())) {
            throw failure(ExecutionStoreFailure.invalid("human task expiry must be after store time"));
        }
        if (registration.escalateAt().isPresent()
                && !now.isBefore(registration.escalateAt().orElseThrow())) {
            throw failure(ExecutionStoreFailure.invalid(
                    "human task escalation must be after store time"));
        }
    }

    /** Refuses a task identity or a live correlation key that something else already owns. */
    private void requireHumanTaskIdentityFree(Connection connection, ExecutionKey key,
                                              HumanTaskRegistration registration) throws SQLException {
        if (readHumanTask(connection, key.tenantId(), registration.taskId()) != null) {
            throw failure(ExecutionStoreFailure.invalid("human task " + registration.taskId()
                    + " is already registered under a different deduplication key"));
        }
        if (readHumanTaskBy(connection, key.tenantId(), "correlation_key",
                registration.correlationKey(), true) != null) {
            throw failure(ExecutionStoreFailure.invalid("correlation key "
                    + registration.correlationKey() + " already identifies a live human task"));
        }
    }

    private void transitionHumanTask(Connection connection, ExecutionKey key,
                                     HumanTaskTransition transition, long revision, Instant now)
            throws SQLException {
        DurableHumanTask current = readHumanTask(connection, key.tenantId(), transition.taskId());
        if (current == null || !current.key().equals(key)) {
            throw failure(ExecutionStoreFailure.invalid("unknown human task " + transition.taskId()));
        }
        if (current.alreadyApplied(transition)) {
            return;
        }
        if (transition.expectedGeneration() != current.generation()
                || !current.status().canTransitionTo(transition.next())) {
            throw humanTaskConflict(current, transition);
        }
        if (transition.next() == HumanTaskStatus.EXPIRED
                && now.isBefore(current.request().expiresAt())) {
            throw humanTaskConflict(current, transition);
        }
        if (transition.next() == HumanTaskStatus.ESCALATED
                && (current.request().escalateAt().isEmpty()
                || now.isBefore(current.request().escalateAt().orElseThrow())
                || !now.isBefore(current.request().expiresAt()))) {
            throw humanTaskConflict(current, transition);
        }
        // Past the deadline, expiry is the ONLY transition left. Reporting the requested one as
        // unresolvable while naming EXPIRED tells the caller both that its decision was refused and
        // what the task has actually become, which is what a client has to know to stop retrying.
        if (transition.next() != HumanTaskStatus.EXPIRED
                && !now.isBefore(current.request().expiresAt())) {
            throw failure(new ExecutionStoreFailure.HumanTaskNotResolvable(current.request().taskId(),
                    current.status(), HumanTaskStatus.EXPIRED, transition.expectedGeneration(),
                    current.generation()));
        }
        updateHumanTask(connection, current.apply(transition, revision), current.status(),
                current.generation());
    }

    private ExecutionStoreException humanTaskConflict(DurableHumanTask current,
                                                      HumanTaskTransition transition) {
        return failure(new ExecutionStoreFailure.HumanTaskNotResolvable(current.request().taskId(),
                current.status(), transition.next(), transition.expectedGeneration(),
                current.generation()));
    }

    private void insertHumanTask(Connection connection, DurableHumanTask task) throws SQLException {
        HumanTaskRegistration request = task.request();
        String columns = "tenant_id, process_instance_id, task_id, traversal_id, invocation_id, "
                + "attempt_id, node_id, correlation_key, deduplication_key, title, description, "
                + "response_content_type, response_schema, response_schema_version, response_kind, "
                + "response_max_bytes, required_roles, required_scopes, requester_request_id, "
                + "requester_subject, requester_principal_type, requester_issuer, graph_version_pin, "
                + "escalate_at_epoch_second, escalate_at_nano, expires_at_epoch_second, "
                + "expires_at_nano, resolved_outcome, denied_outcome, expired_outcome, "
                + "cancelled_outcome, decision_body_max_bytes, response_max_depth, "
                + "response_max_collection_size, response_max_value_count, response_max_text_length, "
                + "response_max_key_length, write_attempts, continuation_version, continuation, "
                + "continuation_digest, confirmation_version, confirmation_prompt, "
                + "confirmation_comment_requirement, confirmation_actions, "
                + "confirmation_resolve_label, confirmation_deny_label, confirmation_cancel_label, "
                + "confirmation_max_prompt_bytes, confirmation_max_action_label_bytes, "
                + "confirmation_max_comment_bytes, created_at_epoch_second, created_at_nano, status, "
                + "actor, decision_comment, generation, revision";
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO human_task (" + columns + ") VALUES (" + "?, ".repeat(57) + "?)")) {
            int index = 1;
            statement.setString(index++, task.key().tenantId());
            StoredUuid.bind(statement, index++, task.key().processInstanceId());
            StoredUuid.bind(statement, index++, request.taskId());
            StoredUuid.bind(statement, index++, request.traversalId());
            StoredUuid.bind(statement, index++, request.invocationId());
            StoredUuid.bind(statement, index++, request.attemptId());
            statement.setString(index++, request.nodeId());
            statement.setString(index++, request.correlationKey());
            statement.setString(index++, request.deduplicationKey());
            statement.setString(index++, request.metadata().title());
            statement.setString(index++, request.metadata().description());
            statement.setString(index++, request.responseSchema().contentType());
            statement.setString(index++, request.responseSchema().schema());
            statement.setString(index++, request.responseSchema().schemaVersion());
            statement.setString(index++, request.responseSchema().kind().name());
            statement.setInt(index++, request.responseSchema().maxBytes());
            statement.setString(index++, joinTokens(request.responderRequirements().requiredRoles()));
            statement.setString(index++, joinTokens(request.responderRequirements().requiredScopes()));
            statement.setString(index++, request.requester().requestId());
            statement.setString(index++, request.requester().subject());
            statement.setString(index++, request.requester().principalType().name());
            statement.setString(index++, request.requester().issuer());
            statement.setString(index++, request.graphVersionPin().reference());
            if (request.escalateAt().isPresent()) {
                index = StoredInstant.bindValue(statement, index, request.escalateAt().orElseThrow());
            } else {
                // Both halves NULL together, and the type is named because the column pair is read
                // back through wasNull() on the seconds: a zero written for "no escalation" would
                // read as an escalation the store passed long ago.
                statement.setNull(index++, Types.BIGINT);
                statement.setNull(index++, Types.INTEGER);
            }
            index = StoredInstant.bindValue(statement, index, request.expiresAt());
            statement.setString(index++, request.reentryMapping().resolvedOutcome());
            statement.setString(index++, request.reentryMapping().deniedOutcome());
            statement.setString(index++, request.reentryMapping().expiredOutcome());
            statement.setString(index++, request.reentryMapping().cancelledOutcome());
            statement.setInt(index++, request.executionLimits().decisionBodyMaxBytes());
            statement.setInt(index++, request.executionLimits().responsePayload().maxDepth());
            statement.setInt(index++, request.executionLimits().responsePayload().maxCollectionSize());
            statement.setInt(index++, request.executionLimits().responsePayload().maxValueCount());
            statement.setInt(index++, request.executionLimits().responsePayload().maxTextLength());
            statement.setInt(index++, request.executionLimits().responsePayload().maxKeyLength());
            statement.setInt(index++, request.executionLimits().writeAttempts());
            statement.setInt(index++, request.continuationVersion());
            statement.setBytes(index++, request.continuation());
            statement.setString(index++, request.continuationDigest());
            HumanTaskConfirmationPresentation presentation = request.confirmationPresentation();
            statement.setInt(index++, presentation.version());
            statement.setString(index++, presentation.prompt());
            statement.setString(index++, presentation.commentRequirement().name());
            statement.setString(index++, joinConfirmationActions(presentation.actions()));
            statement.setString(index++, presentation.resolveLabel());
            statement.setString(index++, presentation.denyLabel());
            statement.setString(index++, presentation.cancelLabel());
            HumanTaskConfirmationLimits confirmationLimits = request.confirmationLimits();
            statement.setInt(index++, confirmationLimits.maxPromptUtf8Bytes());
            statement.setInt(index++, confirmationLimits.maxActionLabelUtf8Bytes());
            statement.setInt(index++, confirmationLimits.maxCommentUtf8Bytes());
            index = StoredInstant.bindValue(statement, index, task.createdAt());
            statement.setString(index++, task.status().name());
            statement.setString(index++, task.actor());
            statement.setString(index++, task.decisionComment());
            statement.setLong(index++, task.generation());
            statement.setLong(index, task.revision());
            statement.executeUpdate();
        }
    }

    /**
     * Applies a decision, with the status <em>and</em> the generation it was made on in the
     * {@code WHERE}.
     *
     * <p>The generation is the fence a human decision is made against — it is what a client holds
     * between rendering a task and submitting an answer — so putting it in the predicate makes the
     * statement itself refuse a decision taken against a version of the task that has since
     * moved.</p>
     */
    private void updateHumanTask(Connection connection, DurableHumanTask task,
                                 HumanTaskStatus expectedStatus, long expectedGeneration)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE human_task SET status = ?, actor = ?, decision_comment = ?, generation = ?, "
                        + "revision = ? WHERE tenant_id = ? AND task_id = ? AND status = ? "
                        + "AND generation = ?")) {
            statement.setString(1, task.status().name());
            statement.setString(2, task.actor());
            statement.setString(3, task.decisionComment());
            statement.setLong(4, task.generation());
            statement.setLong(5, task.revision());
            statement.setString(6, task.key().tenantId());
            StoredUuid.bind(statement, 7, task.request().taskId());
            statement.setString(8, expectedStatus.name());
            statement.setLong(9, expectedGeneration);
            if (statement.executeUpdate() != 1) {
                throw failure(new ExecutionStoreFailure.HumanTaskNotResolvable(
                        task.request().taskId(), expectedStatus, task.status(), expectedGeneration,
                        task.generation()));
            }
        }
    }

    private DurableHumanTask readHumanTask(Connection connection, String tenantId, UUID taskId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(HUMAN_TASK_COLUMNS
                + " WHERE t.tenant_id = ? AND t.task_id = ?")) {
            statement.setString(1, tenantId);
            StoredUuid.bind(statement, 2, taskId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? readHumanTask(rows, tenantId, taskId) : null;
            }
        }
    }

    private DurableHumanTask readHumanTaskBy(Connection connection, String tenantId, String column,
                                             String value, boolean liveOnly) throws SQLException {
        // The column name is interpolated and the VALUE is bound. That is safe here and only here:
        // both callers pass a literal from this file, and no value a caller controls reaches the
        // statement text. Binding a column name is not possible in SQL, and building the predicate
        // any other way would mean two near-identical methods.
        String sql = HUMAN_TASK_COLUMNS + " WHERE t.tenant_id = ? AND t." + column + " = ?"
                + (liveOnly ? " AND t.status IN " + LIVE_HUMAN_TASK_STATUSES : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, value);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? readHumanTask(rows, tenantId, null) : null;
            }
        }
    }

    private static DurableHumanTask readHumanTask(ResultSet rows, String expectedTenantId,
                                                  UUID expectedTaskId) throws SQLException {
        String tenantId = expectedTenantId == null ? rows.getString("tenant_id") : expectedTenantId;
        var key = new ExecutionKey(tenantId,
                StoredUuid.required(rows, "human_task", "process_instance_id", tenantId));
        try {
            Instant escalateAt = nullableInstant(rows, "escalate_at");
            UUID taskId = expectedTaskId == null
                    ? StoredUuid.required(rows, "human_task", "task_id", key)
                    : StoredUuid.requiredMatching(rows, "human_task", "task_id", key, expectedTaskId);
            var request = new HumanTaskRegistration(taskId,
                    StoredUuid.required(rows, "human_task", "traversal_id", key),
                    StoredUuid.required(rows, "human_task", "invocation_id", key),
                    StoredUuid.required(rows, "human_task", "attempt_id", key),
                    rows.getString("node_id"), rows.getString("correlation_key"),
                    rows.getString("deduplication_key"),
                    new HumanTaskMetadata(rows.getString("title"), rows.getString("description")),
                    new HumanTaskResponseSchema(rows.getString("response_content_type"),
                            rows.getString("response_schema"),
                            rows.getString("response_schema_version"),
                            PayloadKind.valueOf(rows.getString("response_kind")),
                            rows.getInt("response_max_bytes")),
                    new HandlerAuthorization(splitTokens(rows.getString("required_roles")),
                            splitTokens(rows.getString("required_scopes"))),
                    new SecurityContext(rows.getString("requester_request_id"), key.tenantId(),
                            rows.getString("requester_subject"),
                            PrincipalType.valueOf(rows.getString("requester_principal_type")),
                            rows.getString("requester_issuer")),
                    new GraphVersionPin(rows.getString("graph_version_pin")),
                    Optional.ofNullable(escalateAt), StoredInstant.read(rows, "expires_at"),
                    new HumanTaskReentryMapping(rows.getString("resolved_outcome"),
                            rows.getString("denied_outcome"), rows.getString("expired_outcome"),
                            rows.getString("cancelled_outcome")),
                    new HumanTaskExecutionLimits(new PayloadLimits(rows.getInt("response_max_bytes"),
                            rows.getInt("response_max_depth"),
                            rows.getInt("response_max_collection_size"),
                            rows.getInt("response_max_value_count"),
                            rows.getInt("response_max_text_length"),
                            rows.getInt("response_max_key_length")),
                            rows.getInt("decision_body_max_bytes"), rows.getInt("write_attempts")),
                    rows.getInt("continuation_version"), rows.getBytes("continuation"),
                    rows.getString("continuation_digest"),
                    new HumanTaskConfirmationPresentation(rows.getInt("confirmation_version"),
                            rows.getString("confirmation_prompt"),
                            HumanTaskCommentRequirement.valueOf(
                                    rows.getString("confirmation_comment_requirement")),
                            splitConfirmationActions(rows.getString("confirmation_actions")),
                            rows.getString("confirmation_resolve_label"),
                            rows.getString("confirmation_deny_label"),
                            rows.getString("confirmation_cancel_label")),
                    new HumanTaskConfirmationLimits(rows.getInt("confirmation_max_prompt_bytes"),
                            rows.getInt("confirmation_max_action_label_bytes"),
                            rows.getInt("confirmation_max_comment_bytes")));
            return new DurableHumanTask(key, request,
                    HumanTaskStatus.valueOf(rows.getString("status")), rows.getString("actor"),
                    rows.getString("decision_comment"), rows.getLong("generation"),
                    rows.getLong("revision"), StoredInstant.read(rows, "created_at"));
        } catch (IllegalArgumentException | IllegalStateException corrupted) {
            throw failure(new ExecutionStoreFailure.Corrupted(key, corrupted.getMessage()));
        }
    }

    // ---------------------------------------------------------------- continuation helpers

    /**
     * Runs one insert that a uniqueness rule may refuse, and reports whether it was applied.
     *
     * <p>The savepoint is what makes the refusal survivable. PostgreSQL aborts the whole transaction
     * on a constraint violation, so a bare insert would take the batch down at exactly the point
     * where the store still has to read the winning row in order to say <em>which</em> rule was hit
     * and whether the collision was an exact repeat. Rolling back to a savepoint discards the failed
     * statement and nothing else, leaving every write this batch has already made intact.</p>
     *
     * <p>A unique violation always names a <strong>committed</strong> conflicting row: an uncommitted
     * one makes the insert wait instead, and it then either succeeds because the competitor rolled
     * back, or fails because the competitor committed. That is the property the callers rely on when
     * they re-read after a {@code false} — the row they are about to describe is certainly there.</p>
     *
     * <p>Every other {@link SQLException} propagates untouched, including a serialization failure,
     * which {@link Transactions} has to see in order to retry the transaction.</p>
     */
    private static boolean insertApplied(Connection connection, Insert insert) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try {
            insert.run();
            connection.releaseSavepoint(savepoint);
            return true;
        } catch (SQLException failed) {
            if (!SqlStates.isUniqueViolation(failed)) {
                throw failed;
            }
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
            return false;
        }
    }

    /** One insert, run inside a savepoint by {@link #insertApplied}. */
    @FunctionalInterface
    private interface Insert {
        void run() throws SQLException;
    }

    /**
     * Enforces the handler outcome's size against the right ceiling, which is not always this
     * adapter's.
     *
     * <p>A human task pins its own response capacity when it is registered, and that capacity may
     * legitimately exceed the deployment's general execution-payload setting — the pinned value is
     * the one the task must remain resolvable under, whatever the configuration has become since. So
     * a resolution that is <em>this batch's</em> resolution of a human task is measured against the
     * stored task, and everything else against the adapter's own limit.</p>
     */
    private void requireHandlerOutcomeWithinLimit(Connection connection, ExecutionKey key,
                                                  ExecutionBatch batch, HandlerTransition transition)
            throws SQLException {
        OpaquePayload payload = transition.outcomePayload();
        if (payload.size() <= config.maxPayloadBytes()) {
            return;
        }
        if (!isHumanTaskResolution(batch, transition)) {
            requireWithinPayloadLimit(payload);
            return;
        }
        DurableHumanTask task = readHumanTask(connection, key.tenantId(), transition.handlerId());
        if (task == null || !task.key().equals(key)) {
            requireWithinPayloadLimit(payload);
            return;
        }
        int pinned = task.request().executionLimits().responsePayload().maxEncodedBytes();
        if (payload.size() > pinned) {
            throw failure(new ExecutionStoreFailure.PayloadTooLarge(payload.size(), pinned));
        }
    }

    /**
     * Whether this handler transition is the handler half of a human task's resolution in this batch.
     *
     * <p>Decided from the batch rather than from storage, and matched on the identifier the two halves
     * share: a human task is resolved by one commit that carries both a task transition and the
     * handler transition that re-enters the process, and only that pairing earns the task's pinned
     * response capacity.</p>
     */
    private static boolean isHumanTaskResolution(ExecutionBatch batch, HandlerTransition transition) {
        if (!(transition instanceof HandlerTransition.Resolved)) {
            return false;
        }
        return batch.humanTaskTransitions().stream()
                .anyMatch(candidate -> candidate instanceof HumanTaskTransition.Resolved
                        && candidate.taskId().equals(transition.handlerId()));
    }

    private static void requireTraversalExists(ProcessInstance folded, UUID traversalId, String what) {
        if (folded == null || !folded.traversals().containsKey(traversalId)) {
            throw failure(ExecutionStoreFailure.invalid(what + " names traversal " + traversalId
                    + ", which this batch neither found nor created"));
        }
    }

    private static void requireInvocationExists(ProcessInstance folded, UUID traversalId,
                                                UUID invocationId, String what) {
        requireTraversalExists(folded, traversalId, what);
        if (!folded.traversals().get(traversalId).invocations().containsKey(invocationId)) {
            throw failure(ExecutionStoreFailure.invalid(what + " names invocation " + invocationId
                    + ", which traversal " + traversalId + " does not contain"));
        }
    }

    private static void requireAttemptExists(ProcessInstance folded, UUID traversalId,
                                             UUID invocationId, UUID attemptId, String what) {
        var traversal = folded == null ? null : folded.traversals().get(traversalId);
        var invocation = traversal == null ? null : traversal.invocations().get(invocationId);
        if (invocation == null || invocation.attempts().stream()
                .noneMatch(attempt -> attempt.attemptId().equals(attemptId))) {
            throw failure(ExecutionStoreFailure.invalid(what + " names attempt " + attemptId
                    + ", which this batch neither found nor created"));
        }
    }

    /**
     * Requires that {@code traversalId} is a traversal <em>this batch created</em>.
     *
     * <p>Existence in the post-fold aggregate is not enough. A terminal handler transition naming a
     * traversal that was already there — the very traversal that was waiting, for instance — would
     * commit, and the trigger the store then offers would point a claimant at a traversal still in
     * {@code WAITING} that nothing authorized it to resume. The re-entry point has to be created by
     * the same batch that authorizes it, which is the whole of "the resolution and the traversal it
     * authorizes commit together or neither does".</p>
     */
    private static void requireBatchCreatedTraversal(ExecutionBatch batch, UUID traversalId,
                                                     String what) {
        boolean created = batch.transitions().stream()
                .anyMatch(transition -> transition instanceof ExecutionTransition.TraversalAdded added
                        && added.traversal().traversalId().equals(traversalId));
        if (!created) {
            throw failure(ExecutionStoreFailure.invalid(what + " names traversal " + traversalId
                    + ", which this batch did not create"));
        }
    }

    /**
     * Newline-delimited, which is unambiguous because {@link HandlerAuthorization} rejects a token
     * carrying a control character. An escaping scheme invented here would be one every other adapter
     * would have to reproduce exactly.
     */
    private static String joinTokens(Set<String> tokens) {
        return String.join("\n", tokens);
    }

    private static Set<String> splitTokens(String stored) {
        if (stored == null || stored.isEmpty()) {
            return Set.of();
        }
        // -1 keeps trailing empty fields, so a round trip is exact rather than quietly shortened. The
        // insertion order is kept because the authorization record compares as a set but reads better
        // in a diagnosis in the order it was written.
        return new LinkedHashSet<>(List.of(stored.split("\n", -1)));
    }

    /**
     * Comma-delimited, and order-preserving because the order <em>is</em> content: the actions are
     * shown to a person in the sequence the graph author chose, and a set would lose it.
     */
    private static String joinConfirmationActions(List<HumanTaskConfirmationAction> actions) {
        return actions.stream().map(Enum::name).collect(java.util.stream.Collectors.joining(","));
    }

    private static List<HumanTaskConfirmationAction> splitConfirmationActions(String stored) {
        if (stored == null || stored.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(stored.split(",")).map(HumanTaskConfirmationAction::valueOf).toList();
    }

    // ---------------------------------------------------------------- request guards

    /**
     * Fail-closed classification of an absent key. Comparing {@code keyIssuedAt - maxClockSkew} rather
     * than {@code keyIssuedAt} means every case ambiguous within the declared budget resolves to expired
     * rather than to "safe to apply".
     */
    private boolean provablyNeverRecorded(Connection connection, String tenantId, Instant keyIssuedAt)
            throws SQLException {
        return !minusClamped(keyIssuedAt, config.maxClockSkew())
                .isBefore(watermarkOf(connection, "idempotency_watermark", "forgotten_before", tenantId));
    }

    private void requireIssuanceWithinSkewBudget(Instant keyIssuedAt) {
        if (keyIssuedAt == null) {
            throw failure(ExecutionStoreFailure.invalid("keyIssuedAt is mandatory"));
        }
        if (keyIssuedAt.isAfter(plusClamped(clock.instant(), config.maxClockSkew()))) {
            throw failure(ExecutionStoreFailure.invalid("keyIssuedAt " + keyIssuedAt
                    + " is later than the store clock plus the declared " + config.maxClockSkew()
                    + " skew budget; the caller's clock is wrong"));
        }
    }

    /** {@code Instant.MIN}/{@code MAX} arithmetic overflows rather than saturating, so clamp it. */
    private static Instant minusClamped(Instant instant, Duration amount) {
        try {
            return instant.minus(amount);
        } catch (ArithmeticException | DateTimeException overflow) {
            return Instant.MIN;
        }
    }

    private static Instant plusClamped(Instant instant, Duration amount) {
        try {
            return instant.plus(amount);
        } catch (ArithmeticException | DateTimeException overflow) {
            return Instant.MAX;
        }
    }

    /**
     * A {@code NotPresent} expectation and a fencing token contradict each other within a single
     * request.
     *
     * <p>A token is issued only by a successful claim, and a claim requires the instance to exist, so a
     * caller holding a genuine token holds proof of the very existence {@code NotPresent} denies. The
     * contradiction holds for every possible stored state, which is what makes it
     * {@link ExecutionStoreFailure.InvalidRequest} rather than a state-dependent rejection, and why it
     * is decided before any row is read.</p>
     */
    private static void requireNoFencingTokenUnderNotPresent(ExecutionBatch batch) {
        if (batch.expectation() instanceof RevisionExpectation.NotPresent
                && batch.fencingToken().isPresent()) {
            throw failure(ExecutionStoreFailure.invalid(
                    "a batch asserting NotPresent cannot present a fencing token (presented "
                            + batch.fencingToken().getAsLong() + "): a token is issued only by a "
                            + "claim on an existing instance, so the request contradicts itself"));
        }
    }

    private void requireWithinPayloadLimit(OpaquePayload payload) {
        if (payload.size() > config.maxPayloadBytes()) {
            throw failure(new ExecutionStoreFailure.PayloadTooLarge(payload.size(),
                    config.maxPayloadBytes()));
        }
    }

    private void requireLeaseTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw failure(ExecutionStoreFailure.invalid("lease ttl must be positive"));
        }
        if (ttl.compareTo(config.maxLeaseTtl()) > 0) {
            throw failure(ExecutionStoreFailure.invalid(
                    "lease ttl " + ttl + " exceeds the declared maximum " + config.maxLeaseTtl()));
        }
    }

    private static void requireTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw failure(ExecutionStoreFailure.invalid("tenantId cannot be blank"));
        }
    }

    private static void requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw failure(ExecutionStoreFailure.invalid("workerId cannot be blank"));
        }
    }

    private static void requireLimit(int limit) {
        if (limit < 1) {
            throw failure(ExecutionStoreFailure.invalid("limit must be positive"));
        }
    }

    private static void requireDestination(String destination) {
        if (destination == null || destination.isBlank()) {
            throw failure(ExecutionStoreFailure.invalid("destination cannot be blank"));
        }
    }

    private static ExecutionStoreException failure(ExecutionStoreFailure failure) {
        return new ExecutionStoreException(failure);
    }

    // ---------------------------------------------------------------- plumbing

    private <T> T write(ExecutionKey key, Transactions.Work<T> work) {
        try {
            return transactions.inTransaction(work);
        } catch (OutcomeUnknownException unknown) {
            // The state the single-host adapter records as unreachable, and that is ordinary here: the
            // network can drop between the client sending COMMIT and the acknowledgement arriving, and
            // in that window the transaction may have been applied. Retrying is not available -- a retry
            // of a committed transaction is a duplicate -- so the honest answer is to say the outcome is
            // unknown and let the caller resolve it against the store.
            throw key == null
                    ? new ExecutionStoreException(new ExecutionStoreFailure.Unavailable(
                            "the outcome of a tenant-scoped write could not be confirmed: "
                                    + unknown.getMessage()), unknown)
                    : new ExecutionStoreException(new ExecutionStoreFailure.OutcomeUnknown(key,
                            unknown.getMessage()), unknown);
        } catch (SQLException failed) {
            throw mapped(failed, key);
        }
    }

    private <T> T read(ExecutionKey key, Transactions.Work<T> work) {
        try {
            return transactions.readOnly(work);
        } catch (SQLException failed) {
            throw mapped(failed, key);
        }
    }

    /**
     * A read whose answer is assembled from more than one statement.
     *
     * <p>Separate from {@link #read} because the two are not interchangeable and the difference is
     * invisible at the call site. Under {@code READ COMMITTED} each statement takes its own snapshot,
     * so a fold reading an instance's revision and then its rows can pair a revision with a state that
     * is already ahead of it, and can observe a child row whose parent the next statement no longer
     * returns - which this adapter would report as {@code Corrupted}, its loudest signal, for a
     * database that is merely busy. {@link Transactions#readConsistent} holds one snapshot for the
     * whole fold.</p>
     */
    private <T> T readFolded(ExecutionKey key, Transactions.Work<T> work) {
        try {
            return transactions.readConsistent(work);
        } catch (SQLException failed) {
            throw mapped(failed, key);
        }
    }

    /**
     * Classifies a database failure the adapter did not anticipate at its own call site.
     *
     * <p>Every arm here is reached only after a rollback, so "nothing was applied" is an observation
     * rather than an assumption — which is what separates every one of these from
     * {@link ExecutionStoreFailure.OutcomeUnknown}, raised only by {@link Transactions} and only for a
     * commit that neither succeeded nor demonstrably failed.</p>
     *
     * <p>Two arms are worth stating rather than reading off the table. A unique violation that reaches
     * here is an identity collision the call site did not anticipate; it is
     * {@link ExecutionStoreFailure.InvalidRequest} because running it again collides again, and
     * reporting it as unavailability would invite a caller to retry forever. And the rest of class 42 —
     * syntax and access-rule violations other than insufficient privilege — is a fault in this adapter
     * or a schema that does not match this binary, so it takes the same deterministic-reject route
     * rather than being laundered into a transient condition.</p>
     *
     * <p><strong>An unrecognised code is rejected, not retried.</strong> Every genuinely transient
     * condition PostgreSQL reports has a code this adapter already names: connection loss, admin
     * shutdown, too many connections, lock and statement timeouts, serialization failure and deadlock.
     * What is left over is therefore far more likely to be deterministic than transient - a check or
     * not-null constraint, a value that does not fit its column, a fault in this adapter - and
     * reporting one of those as unavailability tells a caller to retry something that will fail
     * identically every time, forever. Rejecting says less than the truth and costs one failed
     * operation; retrying claims something untrue and costs a loop. The classifier deliberately has no
     * arm that turns an unrecognised code into something retryable.</p>
     */
    private ExecutionStoreException mapped(SQLException failed, ExecutionKey key) {
        if (SqlStates.isNotAuthorized(failed)) {
            return new ExecutionStoreException(
                    new ExecutionStoreFailure.NotAuthorized(String.valueOf(failed.getMessage())), failed);
        }
        if (SqlStates.isCorrupted(failed)) {
            return key == null
                    ? new ExecutionStoreException(new ExecutionStoreFailure.Unavailable(
                            "the database reports damage to its own storage: " + failed.getMessage()), failed)
                    : new ExecutionStoreException(
                            new ExecutionStoreFailure.Corrupted(key, failed.getMessage()), failed);
        }
        if (SqlStates.isTimedOut(failed)) {
            return new ExecutionStoreException(new ExecutionStoreFailure.Unavailable(
                    "the statement exceeded its lock or statement timeout: " + failed.getMessage()), failed);
        }
        if (SqlStates.isUnavailable(failed)) {
            return new ExecutionStoreException(
                    new ExecutionStoreFailure.Unavailable(String.valueOf(failed.getMessage())), failed);
        }
        if (SqlStates.isUniqueViolation(failed) || SqlStates.isForeignKeyViolation(failed)) {
            return new ExecutionStoreException(ExecutionStoreFailure.invalid(
                    "the write collided with stored identity: " + failed.getMessage()), failed);
        }
        if (isProgrammingFault(failed)) {
            return new ExecutionStoreException(ExecutionStoreFailure.invalid(
                    "the adapter issued a statement this database rejected, which is a defect in the "
                            + "adapter or a schema that does not match this build: "
                            + failed.getMessage()), failed);
        }
        if (isDataOrIntegrityFault(failed)) {
            return new ExecutionStoreException(ExecutionStoreFailure.invalid(
                    "the write did not satisfy a constraint or a column's domain: "
                            + failed.getMessage()), failed);
        }
        return new ExecutionStoreException(ExecutionStoreFailure.invalid(
                "the database refused the operation with a condition this adapter does not classify, "
                        + "and every transient condition it does classify has been ruled out: "
                        + failed.getMessage()), failed);
    }

    /** Class 42: syntax or access-rule violation other than insufficient privilege. */
    private static boolean isProgrammingFault(SQLException failed) {
        return inClass(failed, "42");
    }

    /**
     * Class 22 data exception and class 23 integrity-constraint violation.
     *
     * <p>Unique and foreign-key violations are already answered above, more precisely. What reaches
     * here is a check constraint, a not-null, or a value outside its column's domain - each of which
     * fails identically on every retry, and each of which the schema does declare, so none is
     * unreachable in principle.</p>
     */
    private static boolean isDataOrIntegrityFault(SQLException failed) {
        return inClass(failed, "22") || inClass(failed, "23");
    }

    private static boolean inClass(SQLException failed, String stateClass) {
        for (SQLException current = failed; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (state != null && state.startsWith(stateClass)) {
                return true;
            }
        }
        return false;
    }

    private <T> CompletionStage<T> async(java.util.function.Supplier<T> operation) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new ExecutionStoreException(
                    new ExecutionStoreFailure.Unavailable("this execution store is closed")));
        }
        return CompletableFuture.supplyAsync(operation, worker);
    }
}
