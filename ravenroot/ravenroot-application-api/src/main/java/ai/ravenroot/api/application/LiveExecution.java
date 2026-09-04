package ai.ravenroot.api.application;

import java.time.Instant;
import java.util.UUID;

/**
 * One live execution's identity: a traversal that is currently accepted and not yet
 * terminal, read directly from the runtime's own active-execution bookkeeping.
 *
 * <p>This is deliberately not derived from {@link ExecutionEvent}. An execution whose behavior has
 * deadlocked or is otherwise stalled stops publishing events entirely — that is precisely the case
 * an operator needs this for — so a listing built from recent events would show every healthy
 * execution and silently omit the one that matters. {@link RavenrootApplication#liveExecutions}
 * instead reads the same bookkeeping {@link RavenrootApplication#cancelTraversal} mutates, so
 * "currently listed" and "currently cancellable" are the same set by construction.</p>
 *
 * <h2>{@code paused} qualifies liveness; it is not a second kind of liveness</h2>
 * <p>A paused traversal is <em>live</em>: it is still listed here, still holds its state, and is
 * still cancellable. What {@link #paused()} adds is the one thing a reader could not previously
 * recover from this listing at all — whether the traversal is holding <em>because somebody asked it
 * to</em>. Without it, a deliberate hold and a traversal whose behavior has deadlocked are the same
 * row: both are listed, both have stopped publishing events, and only the operator who issued the
 * pause knew which was which. The distinction is read live from the runtime's own pause bookkeeping,
 * the same map the pause command mutates, so this field and {@code PauseResult} cannot disagree.</p>
 *
 * <p><strong>This listing is process-local, and so is this field.</strong> Every row here is a
 * traversal <em>this</em> process is running, so a hold reported here is one this process is
 * keeping; a traversal held before a restart is not listed here at all, because nothing in this
 * process is running it. That is a property of the listing rather than of the hold — see
 * {@link RavenrootApplication#liveExecutions(String)}.</p>
 *
 * <p><strong>A hold can be durable, and this field does not say whether it is.</strong> A hold taken
 * at a boundary the runtime can write down survives the process that took it, is reported after a
 * restart by {@link RavenrootApplication#executionPaused(String, java.util.UUID)}, and stays
 * resumable and cancellable there. Do not build reconciliation on the premise that a restart forgets
 * every hold: it forgets only the ones that were never written down, and which those are is stated
 * where the rule lives rather than guessed from this field.</p>
 *
 * <p>It remains a qualifier here rather than a new value of the durable lifecycle status, for a
 * reason that does not depend on durability at all: a hold is orthogonal to what the traversal is
 * doing. A traversal held durably is stored as {@code WAITING}, the vocabulary every durable wait
 * already uses, and the hold record beside it says which wait it is. Collapsing the two would make a
 * deliberate hold indistinguishable from a wait on an external decision.</p>
 * @param processInstanceId the stable process instance id used to identify the requested resource.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param graphVersion the graph version constraint applied while processing the request.
 * @param startedAt instant at which the execution was admitted to the runtime
 * @param paused whether a pause is currently held on this traversal, so that it will not begin
 *               another node until it is resumed. {@code false} for an ordinary running traversal
 *               and for one that has never been paused.
 */
public record LiveExecution(UUID processInstanceId, UUID traversalId, String graphVersion, Instant startedAt,
                            boolean paused) {
/**
 * Validates an active execution snapshot before it is exposed to a tenant-scoped caller.
 */
    public LiveExecution {
        if (processInstanceId == null) throw new IllegalArgumentException("processInstanceId cannot be null");
        if (traversalId == null) throw new IllegalArgumentException("traversalId cannot be null");
        graphVersion = graphVersion == null ? "" : graphVersion;
        if (startedAt == null) throw new IllegalArgumentException("startedAt cannot be null");
    }

/**
 * Compatibility constructor preserving the canonical shape before pauses were observable.
 *
 * <p>Reports {@code paused == false}, which is the honest default rather than a convenient one: a
 * caller assembling this shape has no pause bookkeeping to read, so it cannot be holding
 * anything.</p>
 * @param processInstanceId the stable process instance id used to identify the requested resource.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param graphVersion the graph version constraint applied while processing the request.
 * @param startedAt instant at which the execution was admitted to the runtime
 */
    public LiveExecution(UUID processInstanceId, UUID traversalId, String graphVersion, Instant startedAt) {
        this(processInstanceId, traversalId, graphVersion, startedAt, false);
    }

/**
 * Compatibility alias, matching {@link ExecutionSubmission#executionId()}: one traversal.
 * @return legacy execution identifier, equal to the active traversal ID
 */
    public UUID executionId() {
        return traversalId;
    }
}
