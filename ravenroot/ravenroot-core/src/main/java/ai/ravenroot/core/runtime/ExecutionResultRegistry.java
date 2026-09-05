package ai.ravenroot.core.runtime;

import ai.ravenroot.api.persistence.DurableExecutionResult;
import ai.ravenroot.api.persistence.ExecutionResultNodes;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.application.ExecutionLookup;
import ai.ravenroot.api.application.ExecutionOutcome;
import ai.ravenroot.api.application.ExecutionTerminationReason;
import ai.ravenroot.api.application.ProcessInstanceStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Retains what {@code DefaultRavenrootApplication} used to throw away.
 *
 * <p>The engine's {@code activeExecutions} map is keyed by traversal id and its entries are
 * <em>removed on completion</em>, so a lookup against it cannot tell "completed" from "never
 * existed" — it answers absent for both. This registry is the second, deliberately different map
 * that can: an entry is created when a traversal starts, updated in place when it finishes, and
 * demoted to a tombstone rather than deleted when it ages out.</p>
 *
 * <h2>Two bounds, both by count</h2>
 * <p>{@code maxResults} full entries are retained in insertion order; the eldest is demoted to a
 * tombstone when a new one arrives. {@code maxTombstones} tombstones are retained the same way and
 * the eldest is dropped. Both are counts rather than durations because a count is what actually
 * bounds memory, and because a count-based horizon is deterministic — a test can drive an entry
 * across it exactly, which a wall-clock TTL cannot. What a caller sees on each side of both bounds
 * is stated on {@link ExecutionLookup}.</p>
 *
 * <p>The eviction order is insertion, not access. A read is not a reason to keep a result alive
 * longer: the caller that just read it is the caller least likely to need it again, and access
 * ordering would let one polling client hold the whole window against everyone else.</p>
 *
 * <h2>Tenant scoping is the key, not a filter</h2>
 * <p>{@link Key} is {@code (tenantId, executionId)}. There is no lookup that takes an id alone, so a
 * cross-tenant read is not something this class forgets to reject — it is something the signature
 * cannot express. A tenant asking for another tenant's id misses, and a miss is
 * {@link ExecutionLookup.Unknown}, indistinguishable from a nonexistent id.</p>
 *
 * <h2>A cache in front of the durable record, not the authority</h2>
 * <p>When a {@link Durable} record is composed, this registry answers first and the store answers
 * when it misses. That ordering is the whole point: the in-memory entry is the freshest and cheapest
 * answer for a traversal this process ran, and the durable record is the only answer for one it did
 * not — a traversal that ran before a restart, or on another instance sharing the store. Reversing
 * the order would put a disk read in front of every poll of a running execution for no gain, since a
 * running execution has no durable result yet.</p>
 *
 * <p><strong>A tombstone is a miss, not an answer.</strong> Only a retained full result short-circuits
 * the store; the two bounds below evict results from this process long before any retention horizon
 * elapses, so a tombstone means "this process no longer holds it" and never "nobody holds it any
 * more". {@link #lookup} states what that costs when the tombstone is wrongly treated as the latter,
 * and what is answered when the store has nothing either.</p>
 *
 * <p>With no durable record composed, every path below behaves exactly as it did before one existed:
 * the fallback is reached only after an in-memory miss, and it is skipped entirely. That degradation
 * is a deployment choice — a result readable before a restart reads as
 * {@link ExecutionLookup.Unknown} after one — and it is stated on {@link ExecutionLookup} rather than
 * softened here.</p>
 *
 * <p>All in-memory state is guarded by {@code this}. Contention is bounded by the admission ceiling
 * on concurrent executions, and every operation is a constant-time map mutation. <strong>The durable
 * fallback is deliberately performed outside that monitor</strong>: it is the only operation here
 * that can block on a disk or a network, and holding the registry lock across it would let one slow
 * store stall every read and write of every other execution in the process.</p>
 */
public final class ExecutionResultRegistry {

    /** Full results retained. Bounds memory; the payload of a completed run is the large part. */
    public static final int DEFAULT_MAX_RESULTS = 256;

    /**
     * Tombstones retained. Far larger than {@link #DEFAULT_MAX_RESULTS} on purpose and cheaply so:
     * a tombstone is a reference and two enums, so remembering that an execution existed, and how it
     * ended, costs a tiny fraction of remembering what it produced. Widening this bound is what buys
     * the {@code Expired} answer instead of {@code Unknown} for a caller that read too late.
     */
    public static final int DEFAULT_MAX_TOMBSTONES = 8_192;

    /** A retained execution, addressable only with the tenant that owns it. */
    public record Key(String tenantId, UUID executionId) {
        public Key {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(executionId, "executionId");
            if (tenantId.isBlank()) {
                throw new IllegalArgumentException("tenantId cannot be blank");
            }
        }
    }

    /**
     * The durable half of the answer, as this registry needs it.
     *
     * <p>Narrower than {@code ExecutionStore} on purpose. This registry needs to record one result
     * and read one back; expressing that as two methods rather than as a dependency on the whole
     * persistence port keeps the runtime testable without a store and keeps the composition honest
     * about what it actually uses. It is also synchronous, because the registry's own contract is,
     * and the adapter that bridges to the asynchronous port is where the waiting belongs.</p>
     */
    public interface Durable {

        /**
         * Reads one traversal's recorded result.
         *
         * @param tenantId    the tenant that owns the execution.
         * @param traversalId the caller-facing execution id.
         * @return the recorded result, or empty when there is none this tenant may see.
         */
        Optional<DurableExecutionResult> load(String tenantId, UUID traversalId);

        /**
         * Records one terminal result, or accepts that an identical one is already recorded.
         *
         * @param result the record to store.
         */
        void record(DurableExecutionResult result);
    }

    private final Durable durable;
    private final int maxResults;
    private final int maxTombstones;
    private final Map<Key, ExecutionOutcome> results = new LinkedHashMap<>();
    private final Map<Key, ai.ravenroot.api.payload.PayloadException> payloadFailures = new LinkedHashMap<>();
    /**
     * What survives eviction of a full result: the terminal status <em>and</em> why it was reached.
     *
     * <p>The reason is retained rather than recomputed because it cannot be recomputed — the status
     * is {@code FAILED} for a cancellation and for a fault alike, so a tombstone holding only the
     * status answers "this run broke" for a run that was deliberately stopped, and answers it with
     * the confidence of a record. That is worse than the {@code Unknown} it replaced: a caller can
     * act on a wrong answer, and past the retention horizon there is nothing left to check it
     * against. Carrying one more enum reference is the whole cost of not lying.</p>
     *
     * @param status the terminal status the execution reached.
     * @param terminationReason why it reached that status, or {@code null} when nothing
     *                          distinguishes it.
     */
    private record Tombstone(ProcessInstanceStatus status, ExecutionTerminationReason terminationReason) {
    }

    private final Map<Key, Tombstone> tombstones = new LinkedHashMap<>();

    public ExecutionResultRegistry() {
        this(DEFAULT_MAX_RESULTS, DEFAULT_MAX_TOMBSTONES);
    }

    public ExecutionResultRegistry(int maxResults, int maxTombstones) {
        this(maxResults, maxTombstones, null);
    }

    /**
     * Composes the registry in front of a durable record.
     *
     * <p>{@code null} keeps the process-local behaviour this class has always had, unchanged and
     * untouched by anything below — the fallback is the last branch of a lookup that already missed,
     * and the recording path is a no-op.</p>
     *
     * @param maxResults    full results retained in memory.
     * @param maxTombstones tombstones retained in memory.
     * @param durable       the durable record, or {@code null} for none.
     */
    public ExecutionResultRegistry(int maxResults, int maxTombstones, Durable durable) {
        this.durable = durable;
        if (maxResults < 1) {
            throw new IllegalArgumentException("maxResults must be at least 1");
        }
        if (maxTombstones < 1) {
            throw new IllegalArgumentException("maxTombstones must be at least 1");
        }
        this.maxResults = maxResults;
        this.maxTombstones = maxTombstones;
    }

    /**
     * Records an accepted traversal as {@code RUNNING}, before it starts.
     *
     * <p>Registered at submission rather than at completion so that a read arriving while the graph
     * is still running answers {@code RUNNING} instead of {@code Unknown}. A 202 whose id reads back
     * as unknown for the whole duration of the run would make the read useless for exactly the
     * asynchronous case the 202 exists to express.</p>
     */
    public synchronized void started(Key key, UUID processInstanceId) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(processInstanceId, "processInstanceId");
        payloadFailures.remove(key);
        put(key, new ExecutionOutcome(processInstanceId, key.executionId(), ProcessInstanceStatus.RUNNING,
                null, Set.of(), Set.of()));
    }

    /**
     * Replaces a running entry with its terminal result.
     *
     * <p>Re-inserts rather than mutating in place, so a completed execution is freshly ranked for
     * eviction: the retention window then holds the most recently <em>finished</em> executions,
     * which is what a caller reading a result is asking about, rather than the most recently
     * started.</p>
     *
     * <p>Every set the engine computed is projected, including {@code handledFailureNodes} and {@code
     * untakenEdges}. A set dropped here is a fact that exists inside the JVM and nowhere a caller can
     * read it, which is the shape {@code defaultedNodes} previously had —
     * the engine recorded it and an execution that suffered a real fault still reported plain
     * success. {@code untakenEdges} is not a node set like the other three: it names edges the
     * engine skipped for a bypassed node, not nodes the traversal reached, but the same argument
     * applies to it unchanged -- computed and discarded is still discarded.</p>
     */
    public synchronized void completed(Key key, GraphExecutionResult result) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(result, "result");
        results.remove(key);
        payloadFailures.remove(key);
        put(key, new ExecutionOutcome(result.processInstanceId(), key.executionId(),
                ProcessInstanceStatus.COMPLETED, result.payload(), result.visitedNodes(),
                result.defaultedNodes(), result.bypassedNodes(), result.handledFailureNodes(),
                result.untakenEdges()));
    }

    /**
     * Records a terminal failure.
     *
     * <p>{@code visitedNodes} and {@code defaultedNodes} are empty because a failed traversal never
     * produced a {@code GraphExecutionResult} to read them from — the engine surfaces the failure as
     * an exception instead. Reporting empty sets here is not a claim that no node ran; the node-level
     * detail of a failed run lives in the event journal, which does carry it with causation.</p>
     */
    public synchronized void failed(Key key, UUID processInstanceId) {
        terminated(key, processInstanceId, null);
    }

    /**
     * Records a terminal cancellation: the execution was stopped on request and produced no result.
     *
     * <p>The status written is {@code FAILED}, the same status an ordinary failure writes, and that
     * is the design rather than an oversight — see
     * {@link ExecutionTerminationReason}. What separates the two is the reason recorded beside it, so
     * a caller reading {@link #lookup} must read {@link ExecutionOutcome#cancelled()} as well;
     * branching on the status alone reports every operator stop as an incident, which is exactly the
     * defect this method exists to close.</p>
     *
     * @param key the tenant-scoped execution being recorded.
     * @param processInstanceId the durable process that contained the cancelled traversal.
     */
    public synchronized void cancelled(Key key, UUID processInstanceId) {
        terminated(key, processInstanceId, ExecutionTerminationReason.CANCELLED);
    }

    private synchronized void terminated(Key key, UUID processInstanceId,
                                         ExecutionTerminationReason reason) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(processInstanceId, "processInstanceId");
        results.remove(key);
        payloadFailures.remove(key);
        put(key, new ExecutionOutcome(processInstanceId, key.executionId(), ProcessInstanceStatus.FAILED,
                null, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), false, reason));
    }

    /** Retains only the typed bounded payload refusal, never the rejected object or its text. */
    public synchronized void payloadFailed(Key key, UUID processInstanceId,
                                           ai.ravenroot.api.payload.PayloadException failure) {
        failed(key, processInstanceId);
        payloadFailures.put(key, Objects.requireNonNull(failure, "failure"));
    }

    /**
     * The four-way answer defined by {@link ExecutionLookup}; never null, never an empty body.
     *
     * <p>In-memory first, durable second, and the durable read happens outside this object's monitor
     * so a slow store cannot stall every other execution in the process. Only a process-local
     * {@link ExecutionLookup.Found} is returned without consulting the store: it is the freshest
     * answer that exists for a traversal this process ran, and nothing on disk can improve on it.
     * Both absences — {@link ExecutionLookup.Unknown} and {@link ExecutionLookup.Expired} — fall
     * through.</p>
     *
     * <h2>An eviction is not an expiry, and a tombstone must not claim it is</h2>
     * <p>{@code Expired} used to be returned outright, on the reasoning that a tombstone this process
     * wrote already answers for a result this process evicted. That reasoning conflated two facts
     * that were the same one before a durable record existed and are not the same one now: a
     * tombstone records that <em>this process dropped the result from a bounded cache</em>, which
     * {@link #put} does by ordinary eviction as soon as {@link #DEFAULT_MAX_RESULTS} further
     * executions complete, while {@code Expired} claims that <em>the result is past its retention
     * horizon</em>, which only the store's own clock can establish. Returning the first as the second
     * made the instance that ran an execution answer "no longer retained" for a result the store was
     * still holding well inside its window, while every sibling instance sharing that store answered
     * with the full payload for the same id — the identical identifier giving two different answers
     * depending on nothing but which instance was asked, which is the disagreement this whole
     * composition exists to remove.</p>
     *
     * <p>So where a durable record exists it is the only thing that may pronounce a result expired,
     * and the tombstone yields to it: {@link #project} derives {@code Expired} from the record's own
     * retention state, and derives {@code Found} or {@code Redacted} when the record is still
     * offered. <b>When the store has no record either, the tombstone is kept and returned
     * unchanged</b> — it still carries a true and useful fact, that this execution ran and reached
     * this terminal status for this reason, and discarding it in favour of the store's silence would
     * downgrade a known terminal execution to {@link ExecutionLookup.Unknown}: an execution that
     * never happened. That is the case a result purged from the store, or an execution recorded
     * before a result-capable store was composed, lands in.</p>
     *
     * @param key the tenant-scoped execution to read.
     * @return what is known about that execution, which is never an unqualified absence.
     */
    public ExecutionLookup lookup(Key key) {
        ExecutionLookup local = lookupLocal(key);
        boolean deferrable = local instanceof ExecutionLookup.Unknown
                || local instanceof ExecutionLookup.Expired;
        if (durable == null || !deferrable) {
            return local;
        }
        return durable.load(key.tenantId(), key.executionId())
                .<ExecutionLookup>map(ExecutionResultRegistry::project)
                .orElse(local);
    }

    private synchronized ExecutionLookup lookupLocal(Key key) {
        Objects.requireNonNull(key, "key");
        ai.ravenroot.api.payload.PayloadException payloadFailure = payloadFailures.get(key);
        if (payloadFailure != null) throw payloadFailure;
        ExecutionOutcome outcome = results.get(key);
        if (outcome != null) {
            return new ExecutionLookup.Found(outcome);
        }
        Tombstone tombstone = tombstones.get(key);
        if (tombstone != null) {
            return new ExecutionLookup.Expired(key.executionId(), tombstone.status(),
                    tombstone.terminationReason());
        }
        return new ExecutionLookup.Unknown(key.executionId());
    }

    /**
     * Writes one terminal result through to the durable record, and does nothing when none is
     * composed.
     *
     * <p>Separate from {@link #completed}, {@link #failed} and {@link #cancelled} rather than folded
     * into them, because the durable record needs facts none of those three carry — the graph version
     * the execution was pinned to, and when it started and ended — and inventing them here would mean
     * inventing them wrongly. The caller that has those facts builds the record.</p>
     *
     * @param result the record to store.
     */
    public void recordDurably(DurableExecutionResult result) {
        Objects.requireNonNull(result, "result");
        if (durable != null) {
            durable.record(result);
        }
    }

    /**
     * Erases {@code key}'s entry from this process's local memory entirely -- the full result and any
     * tombstone alike -- so the next {@link #lookup} for it falls straight through to the durable
     * record, exactly as it would for a key this process never wrote to at all.
     *
     * <p>The one caller of this is a durable write that {@link #recordDurably} just refused because a
     * conflicting outcome already occupies that key durably: the local entry this process wrote a
     * moment earlier and the durable authority now disagree, and this class's own claim to be "a cache
     * in front of the durable record, not the authority" is false for exactly as long as that
     * disagreement stands. Local writes are not undone by any other path here -- a completed, failed
     * or cancelled result stands until it is evicted on its own -- because in every other case nothing
     * durable contradicts it. This is the one case something does, and it is why this method exists
     * beside {@link #recordDurably} rather than as a general-purpose eviction a caller could reach for
     * any other reason.</p>
     *
     * @param key the tenant-scoped execution to forget locally.
     */
    public synchronized void forgetLocally(Key key) {
        Objects.requireNonNull(key, "key");
        results.remove(key);
        payloadFailures.remove(key);
        tombstones.remove(key);
    }

    /**
     * Projects a durable record onto the answer a caller reads, which is the one place the four read
     * states are derived from the one stored payload state.
     *
     * <p>The mapping is total and deliberately not defaultable: a payload that is present or was
     * never produced is {@link ExecutionLookup.Found}, one that was refused is
     * {@link ExecutionLookup.Redacted}, and one that aged out is {@link ExecutionLookup.Expired}.
     * Nothing maps to {@link ExecutionLookup.Unknown} — a record that exists is never reported as an
     * execution that never happened.</p>
     *
     * @param result the recorded result.
     * @return what a caller reading that execution observes.
     */
    public static ExecutionLookup project(DurableExecutionResult result) {
        Objects.requireNonNull(result, "result");
        return switch (result.payload().state()) {
            case NONE, RETAINED -> found(result);
            case WITHHELD, UNCONVERTIBLE -> new ExecutionLookup.Redacted(result.traversalId(),
                    result.status(), result.terminationReason(), result.payload().state());
            case EXPIRED -> new ExecutionLookup.Expired(result.traversalId(), result.status(),
                    result.terminationReason());
        };
    }

    private static ExecutionLookup found(DurableExecutionResult result) {
        Object payload;
        try {
            payload = result.payload().retained() == null ? null
                    : PayloadJson.read(result.payload().retained().bytes(), PayloadLimits.DEFAULTS)
                            .toJava();
        } catch (RuntimeException undecodable) {
            // Reported as a refusal rather than as an absent payload. A caller told the run produced
            // nothing would act on that; one told the output is not returnable knows to look
            // elsewhere, which is the difference this hierarchy's fourth member exists to keep.
            return new ExecutionLookup.Redacted(result.traversalId(), result.status(),
                    result.terminationReason(),
                    ai.ravenroot.api.persistence.ResultPayloadState.UNCONVERTIBLE);
        }
        ExecutionResultNodes nodes = result.nodes();
        return new ExecutionLookup.Found(new ExecutionOutcome(result.key().processInstanceId(),
                result.traversalId(), result.status(), payload, Set.copyOf(nodes.visitedNodes()),
                Set.copyOf(nodes.defaultedNodes()), Set.copyOf(nodes.bypassedNodes()),
                Set.copyOf(nodes.handledFailureNodes()), Set.copyOf(nodes.untakenEdges()), false,
                result.terminationReason()));
    }

    /** Retained full results. Exists so a test can assert the bound rather than infer it. */
    public synchronized int retainedResults() {
        return results.size();
    }

    /** Retained tombstones. Exists so a test can assert the bound rather than infer it. */
    public synchronized int retainedTombstones() {
        return tombstones.size();
    }

    private void put(Key key, ExecutionOutcome outcome) {
        tombstones.remove(key);
        results.put(key, outcome);
        while (results.size() > maxResults) {
            var eldest = results.entrySet().iterator().next();
            results.remove(eldest.getKey());
            payloadFailures.remove(eldest.getKey());
            entomb(eldest.getKey(), eldest.getValue());
        }
    }

    private void entomb(Key key, ExecutionOutcome evicted) {
        tombstones.put(key, new Tombstone(evicted.status(), evicted.terminationReason()));
        while (tombstones.size() > maxTombstones) {
            tombstones.remove(tombstones.entrySet().iterator().next().getKey());
        }
    }
}
