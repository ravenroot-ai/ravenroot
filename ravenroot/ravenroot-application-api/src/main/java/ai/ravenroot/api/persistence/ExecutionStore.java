package ai.ravenroot.api.persistence;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * The engine-neutral persistence port for execution state (ADR 0010, PERS-02).
 *
 * <p>No Pekko, Akka or other framework type appears anywhere in this contract, and none may be
 * added. The port operates on the PERS-01 aggregate through {@link ExecutionTransition} and returns
 * the store-side envelope {@link StoredProcessInstance}.</p>
 *
 * <h2>Asynchrony</h2>
 * <p>The rule is: <strong>{@link CompletionStage} governs operations that touch stored state; static
 * self-description is synchronous.</strong> The dividing line is stored state, not the shape of the
 * method — which is why {@link #forgottenBefore(String)} is asynchronous despite reading like an
 * accessor, while {@link #capabilities()}, {@link #maxLeaseTtl()}, {@link #maxPayloadBytes()} and
 * {@link #maxClockSkew()} are not.</p>
 *
 * <p>A blocking port would be invoked from actor dispatch once PERS-04 lands, and a remote adapter
 * (PERS-08) is inherently asynchronous; making the adapter own its execution context is correct
 * because only the adapter knows its blocking profile. The self-descriptions are synchronous because
 * the core reads them at composition time to fail fast, which a stage would force it to do by
 * blocking inside a constructor.</p>
 *
 * <p>The port contains <strong>no callbacks</strong>. No {@code Runnable}, listener or continuation
 * appears in any signature. A remote adapter cannot persist a callback, and that is the structural
 * reason timer <em>firing</em> cannot belong to the store.</p>
 *
 * <h2>Failure</h2>
 * <p>Every failure is an {@link ExecutionStoreException} carrying a sealed
 * {@link ExecutionStoreFailure}, whether thrown directly or delivered through a failed stage.
 * Adapters must not leak adapter-specific exception types.</p>
 *
 * <h2>Tenancy</h2>
 * <p><strong>Every operation is tenant-scoped.</strong> There is no global operation on this port.
 * The port uses <em>physical</em> isolation — a distinct database or schema per tenant,
 * with its own connection pool — so an operation without a tenant is not "global", it is either
 * meaningless or an implicit fan-out across every open pool. Two consequences, per the amended
 * tenant-isolation contract:</p>
 * <ul>
 *   <li>The observability and maintenance operations — {@link #leases(String)},
 *   {@link #idempotencyRecordCount(String)}, {@link #purgeExpiredIdempotencyRecords(String)} and
 *   {@link #forgottenBefore(String)} — are tenant-scoped by necessity: a watermark, a record count
 *   and a purge cutoff have no meaning spanning physically separate stores.</li>
 *   <li>The claiming operations — {@link #claimPendingWork(String, String, int, Duration)} and
 *   {@link #claimDueTimers(String, String, int, Duration)} — are tenant-scoped <em>per call</em>,
 *   with cross-tenant fairness owned by the runtime. A store that fanned a claim across tenant pools
 *   would be making a scheduling policy decision behind an interface that cannot express it.</li>
 * </ul>
 * <p>{@code tenantId} therefore doubles as the routing key an adapter uses to select a pool, and it
 * is the first parameter of every operation that does not already carry an {@link ExecutionKey}.
 * The port still carries the tenant <em>opaquely</em> and does not define the isolation mechanism:
 * physical isolation is a deployment and schema property adapters realise.</p>
 *
 * <h2>What this port does not do</h2>
 * <p>It does not store graph bytes, does not define graph identity or deduplication, does not fire
 * timers, does not model fan-in, and does not make the core durable. Those belong to ARC-02's
 * successor, the runtime, CORE-03 and PERS-04 respectively.</p>
 */
public interface ExecutionStore extends AutoCloseable {

    /**
     * Facilities this adapter honours. Static self-description, therefore synchronous;
     * {@link ai.ravenroot.api.execution.ExecutionEngine} declares its capabilities the same way.
 * @return immutable persistence features guaranteed by this adapter.
     */
    Set<StoreCapability> capabilities();

/**
 * Tests whether this adapter advertises a requested persistence capability.
 * @param capability capability unavailable from the adapter.
 * @return whether the capability is present in {@link #capabilities()}.
 */
    default boolean supports(StoreCapability capability) {
        return capabilities().contains(capability);
    }

    /**
     * The longest lease TTL this adapter accepts. An effectively unbounded TTL would convert a
     * single worker crash into a permanently stuck instance with no recourse, so the bound is part
     * of the contract rather than adapter configuration trivia.
 * @return largest lease duration this adapter accepts.
     */
    Duration maxLeaseTtl();

    /**
     * The largest {@link OpaquePayload} this adapter accepts, in bytes. Exceeding it fails with
     * {@link ExecutionStoreFailure.PayloadTooLarge}. SQLite BLOB limits and remote message limits
     * differ; without a declared bound the core could not classify an adapter-specific rejection.
 * @return largest opaque payload this adapter accepts in one write.
     */
    int maxPayloadBytes();

    /**
     * Applies an all-or-nothing batch and returns the resulting envelope with a strictly greater
     * revision.
     *
     * <p>Fails with {@link ExecutionStoreFailure.ConcurrencyConflict} when the revision expectation
     * is unmet, {@link ExecutionStoreFailure.AlreadyExists} when {@link RevisionExpectation.NotPresent}
     * is made against an existing instance, {@link ExecutionStoreFailure.FencedOut} when a presented
     * token is not the current one, and {@link ExecutionStoreFailure.InvalidRequest} when a
     * transition is not legal for the aggregate.</p>
     *
     * <h4>Check order: fencing, then replay, then expectation, then fold</h4>
     * <p>ADR 0010 section 13.2 fixes this order, and it is not arbitrary. <strong>Fencing precedes
     * every check that could yield a success</strong>: answering a fenced worker's batch successfully
     * from an idempotency record would tell it its work landed and let it briefly believe it still
     * owns the instance — the split-brain belief the fence exists to destroy. It does no lasting
     * damage, because its next mutating call is rejected, but it costs a round trip and a false
     * belief for nothing. The fenced worker does not need the outcome; the <em>new</em> owner does,
     * and the new owner holds a current token. <strong>The expectation is last</strong>: the opposite
     * order would break replay entirely, because a write that already happened necessarily bumped the
     * revision, so a retrying caller's expectation is stale by construction and every legitimate
     * replay would fail with {@link ExecutionStoreFailure.ConcurrencyConflict}.</p>
     *
     * <p>That is deliberately <em>not</em> a priority ordering among rejections. <strong>When the
     * instance does not exist, {@link ExecutionStoreFailure.NotFound} prevails, whether or not a
     * fencing token was presented.</strong> Existence is a <em>precondition</em> of fencing rather
     * than a competitor to it: a token is a claim about a lease, and a lease on a nonexistent
     * instance is not stale, it is impossible. Reporting {@code FencedOut} with a current token of
     * zero would fabricate a value to stand in for the absence of any token — the same defect as the
     * nil-UUID key that section 6.3 removed — and section 12 defines {@code FencedOut} as a token
     * mismatch, which an operator reads as <em>another worker took over</em>. When the instance was
     * never created or was archived, nobody took over, and that reading sends someone hunting for a
     * competing worker that does not exist. {@code NotFound} is truthful in every sub-case. Nothing
     * escapes, because both are {@link Retryability#DETERMINISTIC_REJECT} and the worker stops
     * either way. Creation is unaffected: a batch carrying a {@code ProcessCreated} transition under
     * a {@link RevisionExpectation.NotPresent} expectation presents no fencing token, since nothing
     * exists to hold a lease on.</p>
     *
     * <p>The generalising rule for operations added later: fencing governs <em>who may act</em>,
     * replay establishes <em>whether this act already happened</em>, and the expectation governs
     * <em>whether state matches what the act assumed</em> — which is meaningful only for an act
     * actually about to be applied.</p>
     *
     * <p>A batch whose idempotency key is recorded but whose process instance is gone fails with
     * {@link ExecutionStoreFailure.NotFound} carrying the batch's own key (section 13.3), reachable
     * through a tokenless and a token-bearing batch alike. Retention windows and instance lifetime
     * are independent, so a record can outlive its instance;
     * {@link ExecutionStoreFailure.IdempotencyRecordExpired} would be wrong, because the store
     * <em>can</em> answer about the record and it is the instance that is gone.</p>
 * @param batch atomic set of persistence changes.
 * @return updated durable aggregate snapshot after the atomic batch commits.
     */
    CompletionStage<StoredProcessInstance> apply(ExecutionBatch batch);

    /**
     * Loads an already-validated aggregate.
     *
     * <p>Fails with {@link ExecutionStoreFailure.NotFound} when the instance is absent <em>or</em>
     * not visible to the key's tenant — the two are indistinguishable by design — and with
     * {@link ExecutionStoreFailure.Corrupted} when stored state does not reconstruct into a legal
     * aggregate.</p>
 * @param key the stable key used to identify the requested resource.
 * @return current aggregate snapshot, or empty when the key is absent.
     */
    CompletionStage<StoredProcessInstance> load(ExecutionKey key);

    /**
     * Claims ownership of one process instance for {@code ttl}, issuing a new fencing token.
     *
     * <p>TTL is requested by the caller but evaluated against the <em>store's</em> clock. Expiry is
     * lazy: an adapter may evaluate it when a contender arrives rather than running a reaper. A TTL
     * above {@link #maxLeaseTtl()} fails with {@link ExecutionStoreFailure.InvalidRequest}.</p>
 * @param key the stable key used to identify the requested resource.
 * @param workerId the stable worker id used to identify the requested resource.
 * @param ttl period for which a lease remains valid.
 * @return acquired lease, or empty when another worker already owns it.
     */
    CompletionStage<LeaseHandle> claim(ExecutionKey key, String workerId, Duration ttl);

/**
 * Extends a held lease. Fails with {@link ExecutionStoreFailure.LeaseLost} if it was already lost.
 * @param lease lease that authorizes the worker.
 * @param ttl period for which a lease remains valid.
 * @return renewed lease when its fence is current, otherwise empty.
 */
    CompletionStage<LeaseHandle> renew(LeaseHandle lease, Duration ttl);

    /**
     * Releases a held lease early. Releasing a lease already lost is a no-op, not a failure.
     *
     * <p>With expiry, this is one of the only two ways a lease ends (ADR 0010 section 4). It is also
     * the <em>sole</em> mechanism for handing work back faster than the TTL: {@link #close()}
     * deliberately does not release anything, so a draining worker that wants its instances
     * reclaimed immediately must release them explicitly while the store is still open.</p>
     *
     * <p>It is an optimisation and never a correctness dependency. A {@code release} that fails, or
     * a drain cut short before it completes, leaves the lease to expire on the store's clock — the
     * ordinary crash path. A shutdown sequence must therefore never block on {@code release}
     * succeeding, because that would make shutdown depend on store health, which the crash path
     * never does.</p>
 * @param lease lease that authorizes the worker.
 * @return stage that completes after the release attempt; releasing an already-lost lease completes
 *         normally as a no-op, while adapter failure completes exceptionally.
     */
    CompletionStage<Void> release(LeaseHandle lease);

    /**
     * Enumerates the leases currently held <em>within {@code tenantId}</em>, with their claim
     * instant, expiry instant and fencing token.
     *
     * <p>Required because lazy expiry makes staleness visible only when a contender arrives: in a
     * low-contention deployment a dead lease would otherwise be silently stuck with nothing to alert
     * on. Enumeration keeps the port reaper-free without keeping it blind.</p>
     *
     * <p>Tenant-scoped because leases live in the tenant's own store: enumerating "all" leases would
     * mean fanning out over whichever pools happen to be open, which is a different answer on every
     * pod and at every moment.</p>
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @return active leases in the tenant scope.
     */
    CompletionStage<List<LeaseHandle>> leases(String tenantId);

    /**
     * Claims up to {@code limit} outstanding work items for {@code tenantId}, leased and fenced.
     *
     * <p>Delivery is at-least-once, with no ordering guarantee across kinds. Items must be
     * acknowledged through {@link #ack(PendingWork)}; an unacknowledged item becomes claimable again
     * after its visibility window elapses.</p>
     *
     * <p>Tenant-scoped <strong>per call</strong>, with cross-tenant fairness owned by the runtime.
     * A store fanning one claim across tenant pools would be choosing which tenant gets served
     * first — a scheduling policy decision, made behind an interface that has no way to express or
     * configure it. If a single global claim loop is ever needed, that is a PERS-08 question to be
     * reopened deliberately, not discovered.</p>
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @param workerId the stable worker id used to identify the requested resource.
 * @param limit the limit constraint applied while processing the request.
 * @param leaseTtl duration for which the new lease remains valid.
 * @return claimed outstanding work available before the requested bound.
     */
    CompletionStage<List<PendingWork>> claimPendingWork(String tenantId, String workerId, int limit,
                                                        Duration leaseTtl);

    /**
     * Claims up to {@code limit} timers of {@code tenantId} that are due on the <em>store's</em> clock.
     *
     * <p>There is deliberately no {@code dueTimers(now, limit)}: a caller-supplied clock would
     * reintroduce skew and let a fast-clocked worker fire early. Claiming carries a visibility
     * timeout reusing the lease and fencing mechanism, so two concurrent pollers — or one poller
     * retried after a slow call — do not both fire the same timer. Completion is an explicit ack,
     * distinct from the claim, so a crash between claim and fire re-exposes the timer rather than
     * losing it; a lost timer is worse than a duplicate because nothing signals it.</p>
     *
     * <p>Deduplication of <em>effects</em> is the runtime's job, through the idempotency operations.
     * Tenant scoping is per call for the same reason as
     * {@link #claimPendingWork(String, String, int, Duration)}.</p>
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @param workerId the stable worker id used to identify the requested resource.
 * @param limit the limit constraint applied while processing the request.
 * @param leaseTtl duration for which the new lease remains valid.
 * @return claimed timer work due at or before the requested instant.
     */
    CompletionStage<List<PendingWork.TimerDue>> claimDueTimers(String tenantId, String workerId, int limit,
                                                               Duration leaseTtl);

    /**
     * Acknowledges a claimed work item so it is not redelivered. This is the write-back half of the
     * claim, and is deliberately a separate call.
 * @param item pending work returned by the store.
 * @return stage that completes after the acknowledgement is applied; a stale or invalid fence
 *         completes exceptionally with the store's classified fencing failure.
     */
    CompletionStage<Void> ack(PendingWork item);

    /**
     * The largest clock disagreement this adapter tolerates on a caller-supplied {@code keyIssuedAt}.
     *
     * <p>Backward skew is harmless — it biases toward {@link ExecutionStoreFailure.IdempotencyRecordExpired},
     * the safe answer. Forward skew is the hazard: a caller claiming a later issuance than the truth
     * could lift an already-purged record above the watermark and earn a silent re-execution. This
     * budget bounds that, and skew beyond it is named as an operator failure rather than absorbed.</p>
 * @return maximum clock skew tolerated by lease and timer semantics.
     */
    Duration maxClockSkew();

    /**
     * The per-tenant low-water-mark: every idempotency record whose {@code expiresAt} is at or after
     * this instant is present, and one before it may have been forgotten. Monotonically
     * non-decreasing, never retreating, and {@link java.time.Instant#MIN} until something is purged.
     *
     * <p>Asynchronous because it reads stored state. Exposed because without it the retention
     * guarantee is unverifiable by the conformance suite and undiagnosable by an operator.</p>
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @return earliest safely forgotten aggregate revision for the tenant.
     */
    CompletionStage<java.time.Instant> forgottenBefore(String tenantId);

    /**
     * Looks up a previously recorded idempotency outcome.
     *
     * <p>{@code keyIssuedAt} is what makes <em>absence</em> decidable. A record, had it been written,
     * would carry an {@code expiresAt} at or after its issuance, because {@code expiresAt} is the
     * write instant plus a strictly positive window and a write cannot precede issuance. So when
     * {@code keyIssuedAt} is at or after {@link #forgottenBefore(String)}, any such record would
     * still be present and its absence <em>proves</em> it was never recorded.</p>
     *
     * <p>An <strong>empty</strong> result means absence is proven and the caller may safely apply.
     * Absence is not a failure (ADR 0010 section 6.3): it is the outcome of the first attempt of
     * every idempotent operation, so modelling the commonest path as a thrown exception would make
     * every caller wrap a catch to learn that it may simply proceed, and would allocate an exception
     * on the hot path. There is also no entity to name in a {@code NotFound} here — nothing was
     * looked up by instance key — which is why the first adapter had to fabricate an
     * {@link ExecutionKey} with a nil UUID that then reached its own diagnostics.</p>
     *
     * <p>{@link ExecutionStoreFailure.IdempotencyRecordExpired} remains a <em>failure</em>, and
     * remains {@link Retryability#INDETERMINATE}, because it genuinely is one: the store cannot
     * answer and the caller must stop and resolve. The two outcomes travel on different channels
     * precisely because they demand different actions — an empty value means proceed, a failure
     * means stop. Collapsing them in either direction silently re-executes. A {@code keyIssuedAt}
     * beyond the store's clock plus {@link #maxClockSkew()} fails with
     * {@link ExecutionStoreFailure.InvalidRequest}.</p>
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @param key the stable key used to identify the requested resource.
 * @param keyIssuedAt time at which the signing key was issued.
 * @return existing inbound-delivery record when its signed key is recognized.
     */
    CompletionStage<Optional<IdempotencyRecord>> lookupIdempotency(String tenantId, String key,
                                                                   java.time.Instant keyIssuedAt);

    /**
     * Number of idempotency records retained for {@code tenantId}, observable whether or not
     * {@link StoreCapability#IDEMPOTENCY_PURGE} is declared, so unbounded growth is visible before it
     * becomes a disk incident. Tenant-scoped because a count spanning physically separate stores
     * would answer for whichever pools happen to be open rather than for anything an operator asked
     * about.
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @return number of retained inbound-delivery idempotency records.
     */
    CompletionStage<Long> idempotencyRecordCount(String tenantId);

    /**
     * Purges the records of {@code tenantId} whose declared retention window has elapsed, returning
     * how many were removed, and advances that tenant's {@link #forgottenBefore(String)} watermark —
     * and <strong>only</strong> that tenant's. Fails with
     * {@link ExecutionStoreFailure.CapabilityNotSupported} unless
     * {@link StoreCapability#IDEMPOTENCY_PURGE} is declared — its absence is declared, never silent.
     *
     * <p>A tenant that lost no record must keep its watermark exactly where it was, at
     * {@link java.time.Instant#MIN} if it has never forgotten anything. Advancing a watermark for a
     * tenant that forgot nothing destroys provable absence for every key that tenant issued before
     * that instant, turning safe {@code apply} calls into
     * {@link ExecutionStoreFailure.IdempotencyRecordExpired} for work that was never recorded.</p>
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @return number of expired idempotency records removed.
     */
    CompletionStage<Long> purgeExpiredIdempotencyRecords(String tenantId);

    // ---------------------------------------------------------------- durable handlers

    /**
     * Reads one registered handler of this instance, or empty when the instance holds no handler
     * with that identity (PERS-05).
     *
     * <p>Empty is an <em>answer</em>, not a failure, for the reason
     * {@link #lookupIdempotency(String, String, java.time.Instant)} already gives: absence is the
     * ordinary outcome of asking about a handler that has not been registered, and modelling the
     * common path as a thrown exception would make every caller wrap a catch to learn it may
     * proceed. A handler belonging to another tenant is indistinguishable from an absent one, so the
     * store cannot be used as a cross-tenant existence oracle.</p>
     *
     * <p>A terminal handler is still returned. It is the evidence a duplicate or late trigger is
     * refused against and the record an operator reads to see who resolved a human task.</p>
     * @param key the stable key used to identify the requested resource.
     * @param handlerId the stable handler id used to identify the requested resource.
     * @return stored handler, or empty when this instance has none with that identity.
     */
    default CompletionStage<Optional<DurableHandler>> loadHandler(ExecutionKey key, UUID handlerId) {
        return handlersUnsupported();
    }

    /**
     * Resolves the single <em>live</em> handler of {@code tenantId} registered under
     * {@code handlerName} for {@code correlationKey}, or empty when there is none (PERS-05).
     *
     * <p>This is the lookup an inbound trigger performs, and it is the reason a correlation key is
     * unique per {@code (tenantId, name, correlationKey)} among handlers that are not terminal: a
     * trigger carries a business identity and must resolve to exactly one handler, deterministically.
     * Terminal handlers are excluded so that a correlation key becomes reusable once the wait it
     * named is over.</p>
     *
     * <p>Tenant-scoped as its first parameter, like every other operation that does not already carry
     * an {@link ExecutionKey}: the trigger arrives knowing a tenant and a business key and does not
     * yet know which process instance it belongs to — finding that out is precisely what this call is
     * for. A cross-tenant trigger therefore reads as empty rather than as a denial, which is
     * {@link ExecutionKey}'s own rule and is what keeps a probe for another tenant's correlation keys
     * from succeeding as a side channel.</p>
     * @param tenantId the stable tenant id used to identify the requested resource.
     * @param handlerName opaque handler name presented by the trigger.
     * @param correlationKey business identity presented by the trigger.
     * @return the single live handler for that key, or empty when none is waiting.
     */
    default CompletionStage<Optional<DurableHandler>> findHandler(String tenantId, String handlerName,
                                                                  String correlationKey) {
        return handlersUnsupported();
    }

    /**
     * Lists every handler registered against one instance, live and terminal, in registration order
     * (PERS-05).
     *
     * <p>Exposed because without it the retention of terminal handlers is unverifiable by the
     * conformance suite and undiagnosable by an operator, who would otherwise have to guess a handler
     * identity in order to ask about it — the same reason {@link #leases(String)} and
     * {@link #journalRetainedFrom(String)} exist.</p>
     * @param key the stable key used to identify the requested resource.
     * @return handlers of this instance in registration order, empty when it has none.
     */
    default CompletionStage<List<DurableHandler>> handlers(ExecutionKey key) {
        return handlersUnsupported();
    }

    /**
     * The failed stage every handler operation returns when this adapter does not declare
     * {@link StoreCapability#DURABLE_HANDLERS}.
     *
     * <p>These four operations are {@code default} rather than abstract deliberately. PERS-05 is
     * additive to a port that adapters — including out-of-tree ones — have already implemented, and
     * abstract methods would turn a new capability into a breaking change to every existing adapter.
     * The default is not silence: it is a declared, classified refusal, which is the same discipline
     * {@link #purgeExpiredIdempotencyRecords(String)} applies to
     * {@link StoreCapability#IDEMPOTENCY_PURGE}. An adapter that overrides these must declare the
     * capability, and one that declares it must override all of them.</p>
     * @param <T> result type of the operation being refused.
     * @return stage already failed with {@link ExecutionStoreFailure.CapabilityNotSupported}.
     */
    private static <T> CompletionStage<T> handlersUnsupported() {
        var refused = new java.util.concurrent.CompletableFuture<T>();
        refused.completeExceptionally(new ExecutionStoreException(
                new ExecutionStoreFailure.CapabilityNotSupported(StoreCapability.DURABLE_HANDLERS)));
        return refused;
    }

    // ---------------------------------------------------------------- durable tool approvals

    /** Reads one tool approval without exposing another tenant's existence. */
    default CompletionStage<Optional<DurableToolApproval>> loadToolApproval(ExecutionKey key,
                                                                            UUID approvalId) {
        return toolApprovalsUnsupported();
    }

    /** Lists tool approvals retained for one process in registration order. */
    default CompletionStage<List<DurableToolApproval>> toolApprovals(ExecutionKey key) {
        return toolApprovalsUnsupported();
    }

    /** Additive fail-closed default for adapters that have not implemented tool approvals. */
    private static <T> CompletionStage<T> toolApprovalsUnsupported() {
        var refused = new java.util.concurrent.CompletableFuture<T>();
        refused.completeExceptionally(new ExecutionStoreException(
                new ExecutionStoreFailure.CapabilityNotSupported(StoreCapability.TOOL_APPROVALS)));
        return refused;
    }

    // ---------------------------------------------------------------- durable human tasks

    /**
     * Largest page the tenant-scoped human-task inbox will return.
     *
     * @return positive implementation limit.
     */
    default int maxHumanTaskPageSize() {
        return 100;
    }

    /**
     * Reads a task by opaque identity without exposing another tenant's task.
     *
     * @param tenantId authenticated tenant boundary.
     * @param taskId opaque task identity.
     * @return stage yielding the tenant-owned task, or empty when absent.
     */
    default CompletionStage<Optional<DurableHumanTask>> loadHumanTask(String tenantId, UUID taskId) {
        return humanTasksUnsupported();
    }

    /**
     * Lists one deterministic, bounded page from a tenant's human-task inbox.
     *
     * @param tenantId authenticated tenant boundary.
     * @param query bounded inbox query.
     * @return stage yielding one deterministic page.
     */
    default CompletionStage<HumanTaskPage> listHumanTasks(String tenantId, HumanTaskQuery query) {
        return humanTasksUnsupported();
    }

    /** Additive fail-closed default for adapters that do not implement human tasks. */
    private static <T> CompletionStage<T> humanTasksUnsupported() {
        var refused = new java.util.concurrent.CompletableFuture<T>();
        refused.completeExceptionally(new ExecutionStoreException(
                new ExecutionStoreFailure.CapabilityNotSupported(StoreCapability.HUMAN_TASKS)));
        return refused;
    }

    // ---------------------------------------------------------------- event journal and outbox

    /**
     * How long this adapter retains journal records before {@link #compactJournal(String)} may
     * discard them. Static self-description, therefore synchronous.
     *
     * <p>Declared rather than implicit because it <em>is</em> the replay guarantee: a consumer that
     * has been disconnected for longer than this cannot resume, and it needs to be able to read that
     * bound rather than discover it as a {@link ExecutionStoreFailure.JournalTruncated} in
     * production. Adapters without {@link StoreCapability#JOURNAL_COMPACTION} still answer, because a
     * journal that is never pruned retains everything and its bound is honestly unbounded.</p>
 * @return retention policy that bounds the readable journal window.
     */
    Duration journalRetention();

    /**
     * Reads this tenant's journal in {@link JournalRecord#journalOffset()} order, returning at most
     * {@code limit} records strictly after {@code afterOffset}. Pass zero to start from the
     * beginning.
     *
     * <p>Fails with {@link ExecutionStoreFailure.JournalTruncated} when {@code afterOffset} is below
     * {@link #journalRetainedFrom(String)} — that is, when records the caller is asking to continue
     * from have been compacted away. This is deliberately a failure and not a short answer.
     * <strong>Returning whatever survives would hand back a stream with a hole in it that is
     * indistinguishable from a complete one</strong>, and a projection built from it is wrong
     * permanently and silently. The caller's recourse is to resync from current state and resume at
     * the retained floor.</p>
     *
     * <p>Fails with {@link ExecutionStoreFailure.Corrupted} when a stored envelope's digest does not
     * match its stored content, and with
     * {@link ExecutionStoreFailure.CapabilityNotSupported} unless {@link StoreCapability#EVENT_JOURNAL}
     * is declared.</p>
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @param afterOffset exclusive journal offset after which records are read.
 * @param limit the limit constraint applied while processing the request.
 * @return journal records after the cursor, bounded by the requested limit.
     */
    CompletionStage<List<JournalRecord>> readJournal(String tenantId, long afterOffset, int limit);

    /**
     * The lowest {@link JournalRecord#journalOffset()} this tenant's journal still holds, or the next
     * offset it will issue when the journal is empty.
     *
     * <p>The counterpart of {@link #forgottenBefore(String)} for events, and it exists for the same
     * reason: without it the retention guarantee is unverifiable by the conformance suite and
     * undiagnosable by an operator, who would have to infer the floor by probing for the offset at
     * which reads stop failing.</p>
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @return earliest journal offset still retained for the tenant.
     */
    CompletionStage<Long> journalRetainedFrom(String tenantId);

    /**
     * Reads a publisher destination's durable position. A destination that has never delivered
     * anything reads as {@link JournalCursor#start(String, String)} rather than as an absence,
     * because "has delivered nothing" and "does not exist" call for the identical action — start at
     * the beginning — and modelling them separately would only add a branch that both arms of resolve
     * the same way.
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @param destination journal destination whose cursor is updated.
 * @return durable publisher cursor for the named destination.
     */
    CompletionStage<JournalCursor> outboxCursor(String tenantId, String destination);

    /**
     * Advances a destination's cursor to {@code throughOffset}, but only if the stored position still
     * matches {@code expected}.
     *
     * <p>This is a compare-and-set and fails with {@link ExecutionStoreFailure.ConcurrencyConflict}
     * when another publisher for the same destination has moved it — {@link Retryability#RETRY_AFTER_REREAD},
     * so the loser re-reads rather than blindly retrying an advance derived from a stale position.
     * Without the expectation, two publishers could advance past events only one of them delivered,
     * which is a silent loss rather than a duplicate.</p>
     *
     * <p><strong>The order is load-bearing, and it is the whole of the delivery guarantee: deliver
     * first, advance second.</strong> A crash between the two re-delivers, which the inbox
     * deduplicates. A crash in the other order loses, and nothing signals it. Delivery is therefore
     * <strong>at-least-once</strong> — stated plainly, because a publisher author who assumed
     * otherwise would omit the inbox and be correct almost always.</p>
     *
     * <p>Fails with {@link ExecutionStoreFailure.InvalidRequest} when {@code throughOffset} would move
     * the cursor backwards: a cursor that retreats re-delivers events already acknowledged as
     * delivered, indefinitely.</p>
 * @param expected revision required for an optimistic update.
 * @param throughOffset inclusive journal offset acknowledged as delivered.
 * @return advanced cursor when the expected position matches, otherwise conflict failure.
     */
    CompletionStage<JournalCursor> advanceOutboxCursor(JournalCursor expected, long throughOffset);

    /**
     * Records that {@code consumerId} has handled {@code eventId}, returning {@code true} if this is
     * the first time and {@code false} if it was already recorded.
     *
     * <p>This is the <strong>inbox</strong>. A consumer calls it inside its own transaction before
     * applying an effect, and applies the effect only on {@code true}. That converts the outbox's
     * at-least-once delivery into at-most-once <em>effect</em>, which together give the exactly-once
     * logical outcome required by the documented contract — without ever claiming exactly-once
     * <em>delivery</em>, which no publisher can honestly promise.</p>
     *
     * <p>The key is {@code (tenantId, consumerId, eventId)}. Per consumer, because two independent
     * consumers must each receive the event once and a shared key would let the first to arrive
     * suppress it for the rest. Keyed on {@link EventEnvelope#eventId()} rather than on
     * {@link EventEnvelope#digest()}, because a digest is a function of content: two genuinely
     * distinct occurrences that happen to carry identical content would share one and the second
     * would be discarded as a duplicate it is not. The event id is minted once per event and is
     * stable across every redelivery of it, which is exactly the identity a dedup key needs.</p>
     *
     * <p>{@code retention} is mandatory and strictly positive for the reason
     * {@link IdempotencyWrite#retentionWindow()} already gives: only the caller knows how long
     * redelivery remains possible for it, and an adapter-invented policy that forgets early
     * reintroduces the duplicate the record exists to suppress. It must be at least as long as the
     * window in which the outbox could still redeliver.</p>
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @param consumerId the stable consumer id used to identify the requested resource.
 * @param eventId the stable event id used to identify the requested resource.
 * @param retention minimum idempotency retention period.
 * @return first-delivery or duplicate result after durable inbox recording.
     */
    CompletionStage<Boolean> recordInboxDelivery(String tenantId, String consumerId, java.util.UUID eventId,
                                                 Duration retention);

/**
 * Number of inbox records retained for {@code tenantId}, so unbounded growth is visible.
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @return count of retained inbox-delivery records for the tenant.
 */
    CompletionStage<Long> inboxRecordCount(String tenantId);

    /**
     * Discards journal records that are past {@link #journalRetention()} <em>and</em> already
     * delivered by every known destination, returning how many were discarded, and advances
     * {@link #journalRetainedFrom(String)} accordingly.
     *
     * <p><strong>Both conditions are required, and the second is the one that is easy to omit.</strong>
     * Compacting a record no destination has delivered destroys it before anybody received it, which
     * is the exact loss the transactional outbox exists to prevent — and it would be invisible,
     * because a publisher that never saw the event has nothing to notice missing. Age alone is not
     * evidence of delivery.</p>
     *
     * <p>Records are discarded whole rather than reduced to headers. A header-only record would be
     * delivered to a consumer as an event with no body, which is a corrupted event wearing the shape
     * of a valid one; an honest floor plus {@link ExecutionStoreFailure.JournalTruncated} tells the
     * caller the truth instead.</p>
     *
     * <p>Offsets are <strong>never</strong> reissued after compaction, including after the journal is
     * emptied entirely. A restarted offset counter would let a destination whose cursor sits at fifty
     * silently skip the whole of a freshly reissued one-to-fifty.</p>
     *
     * <p>A tenant with no destination cursor at all has, by this rule, delivered nothing, so nothing
     * is compactable and the journal grows until a publisher exists. That is the conservative
     * direction, and it is chosen deliberately: the alternative — treating "nobody is listening" as
     * "everybody has received it" — would discard the entire backlog of a deployment whose projection
     * had not been enabled yet.</p>
     *
     * <p>Fails with {@link ExecutionStoreFailure.CapabilityNotSupported} unless
     * {@link StoreCapability#JOURNAL_COMPACTION} is declared.</p>
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @return number of journal records removed by compaction.
     */
    CompletionStage<Long> compactJournal(String tenantId);

    // ---------------------------------------------------------------- durable execution inventory

    /**
     * The largest page {@link #listProcessInstances(String, ProcessInventoryQuery)} will return. Static
     * self-description, therefore synchronous.
     *
     * <p>Declared rather than enforced silently, because the alternative is clamping: a store that
     * quietly reduced a caller's limit would return a short page that is indistinguishable from the
     * last page, and a caller paginating on "fewer rows than I asked for means I am done" would stop
     * early and never learn it. An over-limit request is rejected with
     * {@link ExecutionStoreFailure.InvalidRequest} instead, and this is the number that says what the
     * limit is before the rejection happens.</p>
     * @return the maximum page size this adapter accepts.
     */
    int maxInventoryPageSize();

    /**
     * How long a terminal process instance is retained before
     * {@link #purgeExpiredProcessInstances(String)} may remove it. Static self-description, therefore
     * synchronous.
     *
     * <p>The counterpart of {@link #journalRetention()} for instances, and declared for the same
     * reason: it is the window inside which a completed or failed execution is still discoverable, and
     * an operator or an audit caller needs to read that bound rather than discover it as a missing row
     * during an investigation. Adapters that never prune still answer, because an inventory that is
     * never pruned retains everything and its bound is honestly unbounded.</p>
     * @return the retention window applied to terminal instances.
     */
    Duration terminalRetention();

    /**
     * Lists one page of {@code tenantId}'s durable process instances, ordered by
     * {@code (createdAt descending, processInstanceId descending)}.
     *
     * <h4>Determinism, and what a scan deliberately does not see</h4>
     * <p>Both components of the sort key are immutable for the life of a row, so a row cannot move
     * between pages while a scan is under way. Work created after the scan started sorts <em>before</em>
     * page one and is therefore not returned by that scan; that is the cost of a stable cursor and it
     * is chosen deliberately over an ordering on {@code updatedAt}, which would let an updated row jump
     * the cursor and be returned twice, or fall behind it and be skipped — silently, in both
     * directions. Terminal work expiring mid-scan removes rows the scan has not reached yet, which is
     * a short page rather than a hole, and {@link ProcessInventoryPage#retainedFrom()} says so.</p>
     *
     * <h4>Rejections</h4>
     * <p>Fails with {@link ExecutionStoreFailure.InvalidRequest} when the limit is not positive, when
     * it exceeds {@link #maxInventoryPageSize()}, when the cursor does not decode or was minted for
     * another tenant, and when the query is self-contradictory — a status filter naming only terminal
     * statuses while excluding terminal rows. Each of those returns nothing under a lenient reading,
     * and an empty page that means "your request was wrong" is indistinguishable from one that means
     * "there is none". Fails with {@link ExecutionStoreFailure.CapabilityNotSupported} unless
     * {@link StoreCapability#PROCESS_INVENTORY} is declared.</p>
     * @param tenantId the stable tenant id used to identify the requested resource.
     * @param query the page to return: filters, cursor and limit.
     * @return one deterministic page of the tenant's process instances.
     */
    CompletionStage<ProcessInventoryPage> listProcessInstances(String tenantId, ProcessInventoryQuery query);

    /**
     * Reads one instance's inventory row directly, without a scan.
     *
     * <p><strong>Empty is the answer for an instance that does not exist and for one that belongs to
     * another tenant, and the two are indistinguishable by design</strong> — the same rule
     * {@link #load(ExecutionKey)} follows, for the same reason: a distinguishable denial would make the
     * store a cross-tenant existence oracle, and a caller could enumerate another tenant's instance ids
     * through the difference. It is also empty for an instance whose retention window elapsed; a caller
     * that needs to tell that case apart compares against
     * {@link #inventoryRetainedFrom(String)} rather than against a second failure channel.</p>
     *
     * <p>Absence is an empty value and not a failure, because it is the ordinary outcome of a lookup
     * and modelling the ordinary outcome as a thrown exception would put a catch block on the common
     * path. A stored row that no longer reconstructs into a legal aggregate is a different matter and
     * still fails with {@link ExecutionStoreFailure.Corrupted}, so a malformed row is never silently
     * dropped from a listing or misread as a well-formed one.</p>
     * @param key the stable key used to identify the requested resource.
     * @return the instance's inventory row, or empty when it is absent or not visible to the key's tenant.
     */
    CompletionStage<Optional<ProcessInventoryEntry>> findProcessInstance(ExecutionKey key);

    /**
     * Lists every traversal of one instance, in {@code position} order.
     *
     * <p>Fails with {@link ExecutionStoreFailure.NotFound} when the instance is absent <em>or</em> not
     * visible to the key's tenant — indistinguishable, exactly as {@link #load(ExecutionKey)} is. An
     * instance with no traversals yet is a different answer and is an empty list, because it exists and
     * the honest report of its contents is that there are none.</p>
     * @param key the stable key used to identify the requested resource.
     * @return the instance's traversals, in insertion order.
     */
    CompletionStage<List<TraversalInventoryEntry>> listTraversals(ExecutionKey key);

    /**
     * The per-tenant inventory retention floor: the <strong>latest retention deadline this tenant has
     * actually crossed</strong>. Every terminal instance whose deadline is strictly after this instant
     * is still present; one whose deadline is at or before it may have been purged. Monotonically
     * non-decreasing, never retreating, and {@link java.time.Instant#MIN} until something is purged.
     *
     * <h4>The floor is measured in deadlines, not in end instants</h4>
     * <p>It lives in the same space the purge decides in — the {@code retainedUntil} that
     * {@link #findProcessInstance(ExecutionKey)} publishes for every terminal row — and that is the
     * point: a floor expressed in a different space than the predicate needs a conversion, and the
     * conversion is exactly where an off-by-one or an inverted bound hides. It also matches
     * {@link #forgottenBefore(String)}, which is stated in its records' {@code expiresAt} rather than
     * in when they were written. A caller holding an execution it once read compares that row's own
     * {@code retainedUntil}; one that never read the row derives the deadline from the instant the
     * execution ended plus {@link #terminalRetention()}, both of which are published.</p>
     *
     * <h4>Latest, not earliest, and the boundary is exclusive</h4>
     * <p>The guarantee runs in the direction "everything past this is still here", so the floor must
     * sit at or beyond every boundary a purge crossed. Publishing the <em>earliest</em> deadline
     * removed breaks it as soon as one run removes two rows whose deadlines are further apart than the
     * retention window: the later row is gone and sits after the published floor, so a caller following
     * this rule concludes that a genuinely completed execution never existed — the ambiguity inverted
     * into the unsafe direction, which is the opposite of what this method is for. A run that removes
     * exactly one row is the degenerate case where earliest and latest coincide, so no single-row test
     * can tell the two apart.</p>
     *
     * <p>The boundary is exclusive because collection is inclusive: a row is purged when its deadline
     * is at or before the store's now, so the row sitting exactly on the floor is precisely one that
     * was removed. This is one of the store's non-uniform boundary conventions and is stated here on
     * its own merits rather than assumed to match a neighbour's.</p>
     *
     * <p>The counterpart of {@link #forgottenBefore(String)} and {@link #journalRetainedFrom(String)},
     * and it exists for the identical reason: it is what turns an absent row from an ambiguity into an
     * answer. Without it a caller cannot distinguish an instance that never existed from one that
     * expired by policy, and those call for opposite actions — investigate a bad identifier, or accept
     * a completed execution that aged out.</p>
     *
     * <p>Asynchronous because it reads stored state.</p>
     * @param tenantId the stable tenant id used to identify the requested resource.
     * @return the earliest instant from which this tenant's terminal inventory is complete.
     */
    CompletionStage<java.time.Instant> inventoryRetainedFrom(String tenantId);

    /**
     * Removes {@code tenantId}'s terminal instances whose {@link #terminalRetention()} window has
     * elapsed, returning how many were removed, and advances that tenant's
     * {@link #inventoryRetainedFrom(String)} floor — and <strong>only</strong> that tenant's.
     *
     * <p><strong>Nothing is ever deleted implicitly on a read.</strong> Retention is an explicit
     * operator or scheduler action, exactly like {@link #purgeExpiredIdempotencyRecords(String)} and
     * {@link #compactJournal(String)}, so a listing has no side effect and two identical listings
     * return identical pages. A read that pruned would make the inventory's own contents depend on who
     * happened to look at it.</p>
     *
     * <p>Only <em>terminal</em> rows are eligible. A non-terminal instance is never removed however old
     * it is, because age is not evidence that work is finished: pruning a long-running or interrupted
     * instance would destroy the very row an operator needs in order to discover that it is stuck.</p>
     *
     * <p>A tenant that lost nothing must keep its floor exactly where it was, at
     * {@link java.time.Instant#MIN} if it has never purged. Advancing a floor for a tenant that forgot
     * nothing would report a retention gap that does not exist, and a periodic purge job would report
     * one on every tick.</p>
     *
     * <p>Fails with {@link ExecutionStoreFailure.CapabilityNotSupported} unless
     * {@link StoreCapability#INVENTORY_RETENTION} is declared — its absence is declared, never
     * silent.</p>
     * @param tenantId the stable tenant id used to identify the requested resource.
     * @return the number of expired terminal instances removed.
     */
    CompletionStage<Long> purgeExpiredProcessInstances(String tenantId);

    /**
     * Releases what the <em>process</em> owns and <strong>no lease</strong>.
     *
     * <p>The governing invariant (ADR 0010 section 13.1) is that a clean shutdown and a
     * {@code kill -9} are semantically identical and differ only in latency. If {@code close()}
     * could do anything expiry would not eventually do by itself, crash recovery and orderly
     * recovery would follow different paths and every recovery test would be evidence about the path
     * nobody experiences in production.</p>
     *
     * <p>A {@code kill -9} releases no leases, so {@code close()} releases none. It follows
     * that:</p>
     * <ul>
     *   <li>{@code close()} releases connections, worker threads and buffers, and no state the store
     *   owns. A durable adapter must still flush, checkpoint and close its connection on the way
     *   out; that is durability, not lease policy.</li>
     *   <li>No session identifier is minted, and none is recorded on a lease. That mechanism existed
     *   only to scope a release that no longer happens, and it would have forced an adapter with no
     *   meaningful session — a stateless remote store — to fabricate one.</li>
     *   <li>A lease ends only by expiry on the store's clock or by an explicit
     *   {@link #release(LeaseHandle)}. Handing work back faster than the TTL is {@code release}'s
     *   job, not this method's.</li>
     *   <li>A crash performs neither {@code close()} nor {@code release}, so its leases persist and
     *   expire lazily. Nothing special-cases crash recovery, because there is no second path to
     *   special-case.</li>
     *   <li>Fencing tokens do not reset when a store reopens. A session is not a fencing domain.</li>
     * </ul>
     *
     * <p>A <strong>non-durable</strong> adapter's {@code close()} discards everything, and that is
     * correct rather than a violation: retaining state across close would falsely simulate
     * durability, which section 11 forbids. It discards that state because it has no durable state
     * to keep, not because {@code close()} released anything. The capability differs, so the
     * behaviour differs, and neither may be read as the other's contract.</p>
     */
    @Override
    void close();
}
