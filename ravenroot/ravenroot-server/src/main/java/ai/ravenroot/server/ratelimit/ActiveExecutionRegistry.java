package ai.ravenroot.server.ratelimit;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Per-tenant accounting of executions that are currently running.
 *
 * <h2>Why this exists rather than a single global counter</h2>
 *
 * <p>A global ceiling alone answers the wrong question. It bounds how much work the engine carries, and
 * it does so correctly, but it says nothing about <em>whose</em> work that is. A tenant holding
 * executions up to a global ceiling makes the server answer 429 to every other tenant, which turns a
 * capacity control into a cross-tenant denial vector: one tenant's traffic decides another tenant's
 * availability. The per-tenant cap in {@link RateLimitConfiguration#tenantActiveExecutions()} is derived
 * from the ceiling and is structurally below it, so a single tenant can never be the reason another
 * tenant is refused. The global ceiling stays, as the backstop it always was.</p>
 *
 * <h2>Why a leaked entry is bounded here and was not before</h2>
 *
 * <p>Accounting for running work means an execution that never publishes a terminal event leaves an
 * entry behind. That is real, and it is exactly as real for a single global counter — with a strictly
 * worse blast radius, because a stuck global count refuses <em>every</em> tenant, permanently, until the
 * process restarts. So the leak is bounded rather than avoided:</p>
 *
 * <ul>
 *   <li><strong>Age-out.</strong> An entry older than {@link RateLimitConfiguration#executionMaxAge()}
 *       is reclaimed on the next admission check. A stuck execution therefore costs one tenant one slot
 *       for a bounded window instead of costing everyone every slot forever.</li>
 *   <li><strong>Hard ceiling.</strong> The map cannot exceed
 *       {@link RateLimitConfiguration#maxTrackedExecutions()}. Over it, the oldest entry is displaced,
 *       which can only relax a limit and never tighten one.</li>
 *   <li><strong>Observability.</strong> Both reclamations emit a record on the same audit sink as every
 *       other limiter decision, carrying the {@code requestId} of the request that started the execution
 *       that leaked. The condition is a log line an operator can alert on, not a number that silently
 *       stops matching reality.</li>
 * </ul>
 *
 * <p>The counter that feeds {@code /v1/runtime} is deliberately not the admission authority: it only
 * ever moves on terminal events, so it cannot recover from an execution that has none.</p>
 */
public final class ActiveExecutionRegistry implements AutoCloseable {
    /** Refusal because this tenant already holds its share of the ceiling. */
    public static final String TENANT_LIMIT_CODE = "TENANT_ACTIVE_EXECUTION_LIMIT_REACHED";
    /** Refusal because the process as a whole is at the ceiling. Preserved code and contract. */
    public static final String GLOBAL_LIMIT_CODE = "ACTIVE_EXECUTION_CEILING_REACHED";
    /** Audit code for an entry reclaimed because its execution never reached a terminal event. */
    public static final String EXPIRED_CODE = "ACTIVE_EXECUTION_ENTRY_EXPIRED";
    /** Audit code for an entry displaced because the registry reached its hard ceiling. */
    public static final String DISPLACED_CODE = "ACTIVE_EXECUTION_ENTRY_DISPLACED";

    private final RateLimitConfiguration configuration;
    private final RateLimitAuditSink audit;
    private final LongSupplier nanoTime;
    private final Clock clock;

    /** Insertion-ordered so the oldest entry is the first one, which is the one displaced on overflow. */
    private final LinkedHashMap<UUID, Entry> active = new LinkedHashMap<>();
    private final Map<String, Integer> perTenant = new HashMap<>();
    private long expiredEntries;
    private long displacedEntries;

    public ActiveExecutionRegistry(RateLimitConfiguration configuration, RateLimitAuditSink audit,
                                   LongSupplier nanoTime) {
        this(configuration, audit, nanoTime, Clock.systemUTC());
    }

    public ActiveExecutionRegistry(RateLimitConfiguration configuration, RateLimitAuditSink audit,
                                   LongSupplier nanoTime, Clock clock) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Feeds the registry from the execution event stream.
     *
     * <p>This is the whole coupling to the engine: the events already carry a tenant and already have
     * terminal types, so no new contract is needed and nothing has to be threaded through the
     * submission path.</p>
     *
     * <p><b>Node- and join-level event types are ignored here, deliberately and by name -- this is
     * not the same rule as "ignores unknown terminal types".</b> A traversal-terminal event releases
     * this method's one resource (the admission slot); every other event type carries no fact this
     * method needs, known or not. That distinction used to be blurred: a hardcoded {@code case
     * EXECUTION_COMPLETED, EXECUTION_FAILED ->} list was the only definition of "terminal" this
     * method had, so {@link ExecutionEventType#EXECUTION_CANCELLED} silently fell into the same
     * {@code default} arm as {@code NODE_STARTED} when it was added -- a cancelled execution's
     * admission slot was never released, and nothing here failed to compile to say so. Terminal-ness
     * is now delegated entirely to {@link ExecutionEventType#isTraversalTerminal()}, an exhaustive
     * switch expression with no default: a future terminal type cannot silently rejoin the "ignored"
     * cohort here the way {@code EXECUTION_CANCELLED} once did, because failing to classify it in
     * that one method is a compile error, not a silent admission-accounting gap in this one.</p>
     */
    public void observe(ExecutionEvent event) {
        if (event == null) {
            return;
        }
        if (event.type() == ExecutionEventType.EXECUTION_STARTED) {
            emit(started(event));
        } else if (event.type().isTraversalTerminal()) {
            finished(event.executionId());
        }
        // Every other event type is node- or join-level and says nothing about how many executions
        // are running -- ignored here because it is irrelevant, not because it is unrecognised.
    }

    /**
     * Decides whether one more execution may start for {@code tenantId}.
     *
     * <p>The tenant cap is checked before the global ceiling so a tenant at its own share is told which
     * limit it hit, and a tenant below its share is never told "the tenant limit" for capacity somebody
     * else is holding.</p>
     */
    public RateLimitDecision checkAdmission(String tenantId) {
        int total;
        int tenantActive;
        List<RateLimitAuditEvent> reclaimed;
        synchronized (this) {
            reclaimed = expire(nanoTime.getAsLong());
            total = active.size();
            tenantActive = perTenant.getOrDefault(tenantId, 0);
        }
        emit(reclaimed);
        if (tenantActive >= configuration.tenantActiveExecutions()) {
            return RateLimitDecision.throttled(TENANT_LIMIT_CODE, "tenant", 1);
        }
        if (total >= configuration.globalActiveExecutions()) {
            return RateLimitDecision.throttled(GLOBAL_LIMIT_CODE, "global", 1);
        }
        return RateLimitDecision.allowed();
    }

    /** Executions currently counted for one tenant. */
    public synchronized int activeFor(String tenantId) {
        return perTenant.getOrDefault(tenantId, 0);
    }

    /** Executions currently counted across every tenant. */
    public synchronized int total() {
        return active.size();
    }

    /** Entries reclaimed because their execution never reached a terminal event. */
    public synchronized long expiredEntries() {
        return expiredEntries;
    }

    /** Entries displaced because the registry reached its hard ceiling. */
    public synchronized long displacedEntries() {
        return displacedEntries;
    }

    /** Runs the age-out now, so an operator probe or a test does not have to wait for a submission. */
    public void sweep() {
        List<RateLimitAuditEvent> reclaimed;
        synchronized (this) {
            reclaimed = expire(nanoTime.getAsLong());
        }
        emit(reclaimed);
    }

    /** Releases every entry. The registry must not outlive the server that owns it. */
    @Override
    public synchronized void close() {
        active.clear();
        perTenant.clear();
    }

    private synchronized List<RateLimitAuditEvent> started(ExecutionEvent event) {
        UUID key = event.executionId();
        if (key == null || active.containsKey(key)) {
            return List.of();
        }
        long now = nanoTime.getAsLong();
        List<RateLimitAuditEvent> reclaimed = expire(now);
        while (active.size() >= configuration.maxTrackedExecutions()) {
            RateLimitAuditEvent displaced = displaceOldest();
            if (displaced == null) {
                break;
            }
            reclaimed = append(reclaimed, displaced);
        }
        active.put(key, new Entry(event.tenantId(), event.requestId(), now));
        perTenant.merge(event.tenantId(), 1, Integer::sum);
        return reclaimed;
    }

    private synchronized void finished(UUID executionId) {
        if (executionId == null) {
            return;
        }
        release(active.remove(executionId));
    }

    /** Caller holds the monitor. Returns the audit records for entries that were reclaimed. */
    private List<RateLimitAuditEvent> expire(long nowNanos) {
        long maxAgeNanos = configuration.executionMaxAge().toNanos();
        List<RateLimitAuditEvent> reclaimed = List.of();
        Iterator<Map.Entry<UUID, Entry>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (nowNanos - entry.startedNanos() < maxAgeNanos) {
                // Insertion order is start order, so the first young entry ends the scan.
                break;
            }
            iterator.remove();
            release(entry);
            expiredEntries++;
            reclaimed = append(reclaimed, record(entry, EXPIRED_CODE));
        }
        return reclaimed;
    }

    /** Caller holds the monitor. */
    private RateLimitAuditEvent displaceOldest() {
        Iterator<Map.Entry<UUID, Entry>> iterator = active.entrySet().iterator();
        if (!iterator.hasNext()) {
            return null;
        }
        Entry entry = iterator.next().getValue();
        iterator.remove();
        release(entry);
        displacedEntries++;
        return record(entry, DISPLACED_CODE);
    }

    /** Caller holds the monitor. */
    private void release(Entry entry) {
        if (entry == null) {
            return;
        }
        perTenant.computeIfPresent(entry.tenantId(), (ignored, count) -> count <= 1 ? null : count - 1);
    }

    private RateLimitAuditEvent record(Entry entry, String code) {
        return new RateLimitAuditEvent(clock.instant(), entry.requestId(), null, false,
                entry.tenantId(), RateLimitAuditEvent.UNKNOWN, "POST", "/v1/executions", code, "tenant",
                0, 0);
    }

    /** Audits are emitted outside the monitor so a sink can never deadlock or block admission. */
    private void emit(List<RateLimitAuditEvent> events) {
        for (RateLimitAuditEvent event : events) {
            audit.record(event);
        }
    }

    private static List<RateLimitAuditEvent> append(List<RateLimitAuditEvent> events,
                                                    RateLimitAuditEvent event) {
        List<RateLimitAuditEvent> mutable = events instanceof ArrayList<RateLimitAuditEvent> existing
                ? existing : new ArrayList<>(events);
        mutable.add(event);
        return mutable;
    }

    /**
     * One running execution.
     *
     * <p>{@code requestId} is the correlation id of the submission that started it, so the record
     * written when an entry is reclaimed names the request that leaked rather than merely reporting
     * that something did.</p>
     */
    private record Entry(String tenantId, String requestId, long startedNanos) {
    }
}
