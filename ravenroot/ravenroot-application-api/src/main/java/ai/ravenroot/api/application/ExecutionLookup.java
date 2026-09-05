package ai.ravenroot.api.application;

import java.util.Objects;
import java.util.UUID;

/**
 * The three answers a read by execution id can give, and there is no fourth.
 *
 * <p>Sealed rather than {@code Optional<ExecutionOutcome>} because the two absences are not the same
 * absence and a caller must not be able to conflate them. {@link Unknown} says "this process has no
 * record of that id"; {@link Expired} says "it ran, here is how it ended, and its result is past the
 * retention horizon". Collapsing both into an empty optional is precisely the silently-short answer
 * this project has spent weeks removing — an adapter would render the same empty body for a typo and
 * for a real execution whose payload it simply no longer holds.</p>
 *
 * <h2>Where a result lives, and what a caller observes past that horizon</h2>
 * <p>Results are retained <strong>in memory, in the process that ran them, bounded by count</strong>.
 * That is a deliberate choice against durable storage, and the reason is that there is nowhere
 * durable to put one: {@code ProcessInstance} has no result field, the SQLite execution store has no
 * result column, and {@code GraphRunner} journals its node-level events with
 * {@code OpaquePayload.empty()} by an explicit decision recorded in its own Javadoc. Persisting a
 * result would mean a new {@code ExecutionStore} port method, a schema migration and a canonical
 * encoding for an arbitrary {@code Object} payload — an architectural change to a shared,
 * engine-neutral port, not a read endpoint.</p>
 *
 * <p>The horizon is therefore a <em>count</em>, not a duration, which is the honest way to state it:
 * a bounded number of full results is retained, and beyond that a much larger bounded number of
 * {@link Expired} tombstones, so an execution stops being readable in a stated order rather than at
 * an unpredictable wall-clock moment. Concretely, a caller observes:</p>
 * <ul>
 *   <li>{@link Found} with {@code status == RUNNING} while the traversal is in flight;</li>
 *   <li>{@link Found} with a terminal status and a payload once it completes;</li>
 *   <li>{@link Expired} once the full result has been evicted but the tombstone survives — the
 *       terminal status and its termination reason are still reported, the payload is not;</li>
 *   <li>{@link Unknown} once even the tombstone is evicted, <strong>after a process restart</strong>,
 *       or when the execution belongs to another tenant.</li>
 * </ul>
 *
 * <p>The restart case is the sharp edge and is stated rather than softened: a result that was
 * readable before a restart reads as {@link Unknown} after one, indistinguishable from an id that
 * never existed. Durable results are the fix, and they are a separate piece of work.</p>
 *
 * <h2>Another tenant's execution is {@link Unknown}, never a denial</h2>
 * <p>Same choice {@code ExecutionStore.load} makes for the same reason: a distinct "exists but not
 * yours" answer is an existence oracle, and a caller could enumerate another tenant's execution ids
 * through it. The registry is keyed by tenant <em>and</em> id together, so a cross-tenant hit is not
 * excluded by a check that could be forgotten — it is not a lookup that can be expressed.</p>
 */
public sealed interface ExecutionLookup {

/**
 * The execution id this lookup asked about, present on every answer.
 * @return the identifier queried by the caller, including when the lookup reports absence or denial.
 */
    UUID executionId();

/**
 * The execution is known to this process and its state is reported in full.
 * @param outcome classification indicating whether the requested execution was found or unavailable
 */
    record Found(ExecutionOutcome outcome) implements ExecutionLookup {
/**
 * Rejects incomplete execution lookup alternatives so callers cannot confuse absence with success.
 */
        public Found {
            Objects.requireNonNull(outcome, "outcome");
        }

        @Override
        public UUID executionId() {
            return outcome.executionId();
        }
    }

    /**
     * The execution ran and reached {@code status}, but its result is past the retention horizon.
     *
     * <p>Deliberately still carries the terminal status: knowing an execution completed is useful
     * even when its payload is gone, and it is the difference between "your run finished, ask
     * earlier next time" and "we have never heard of you".</p>
     *
     * <h4>And the termination reason, for the same reason the status is here</h4>
     * <p>{@code status} alone past the retention horizon says a cancelled execution failed, which is
     * worse than saying nothing: a caller reading an aged-out result would learn that its deliberate
     * stop was an incident, and would learn it from the one answer it has no way to check against a
     * live record. Carrying the reason into the tombstone costs one reference and keeps the
     * distinction true for exactly as long as the status it qualifies is true. <b>Read the two
     * together; the status on its own is a wrong answer, not a partial one.</b></p>
 * @param executionId the stable execution id used to identify the requested resource.
 * @param status lifecycle state represented by this value.
 * @param terminationReason why this terminal {@code status} was reached, or {@code null} when
 *                         nothing distinguishes it. See {@link ExecutionTerminationReason}.
     */
    record Expired(UUID executionId, ProcessInstanceStatus status,
                   ExecutionTerminationReason terminationReason) implements ExecutionLookup {
/**
 * Rejects incomplete execution lookup alternatives so callers cannot confuse absence with success.
 */
        public Expired {
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(status, "status");
        }

/**
 * Compatibility constructor for the shape that predates a retained termination reason.
 *
 * <p>Reports an absent reason. Correct for a tombstone written by a producer that could not observe
 * one, and wrong for a cancellation, which is why the retaining side records the reason at the same
 * moment it records the status rather than reconstructing it later from the status alone.</p>
 * @param executionId the stable execution id used to identify the requested resource.
 * @param status lifecycle state represented by this value.
 */
        public Expired(UUID executionId, ProcessInstanceStatus status) {
            this(executionId, status, null);
        }

/**
 * Whether the execution this tombstone remembers was stopped on request rather than having broken.
 * @return whether the retained termination is recorded as a cancellation.
 */
        public boolean cancelled() {
            return ExecutionTerminationReason.isCancellation(terminationReason);
        }
    }

    /**
     * This process has no record of the id for the asking tenant.
     *
     * <p>Covers four distinct situations on purpose — never submitted, submitted by another tenant,
     * evicted past the tombstone horizon, and submitted before a restart — because distinguishing
     * them for the caller would mean disclosing the first two.</p>
 * @param executionId the stable execution id used to identify the requested resource.
     */
    record Unknown(UUID executionId) implements ExecutionLookup {
/**
 * Rejects incomplete execution lookup alternatives so callers cannot confuse absence with success.
 */
        public Unknown {
            Objects.requireNonNull(executionId, "executionId");
        }
    }
}
