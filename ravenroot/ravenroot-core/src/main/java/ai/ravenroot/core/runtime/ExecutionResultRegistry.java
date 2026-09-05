package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionLookup;
import ai.ravenroot.api.application.ExecutionOutcome;
import ai.ravenroot.api.application.ExecutionTerminationReason;
import ai.ravenroot.api.application.ProcessInstanceStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
 * <p>All state is guarded by {@code this}. Contention is bounded by the admission ceiling on
 * concurrent executions, and every operation is a constant-time map mutation.</p>
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

    /** The three-way answer defined by {@link ExecutionLookup}; never null, never an empty body. */
    public synchronized ExecutionLookup lookup(Key key) {
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
