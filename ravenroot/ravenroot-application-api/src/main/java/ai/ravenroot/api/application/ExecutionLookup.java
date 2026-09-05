package ai.ravenroot.api.application;

import java.util.Objects;
import java.util.UUID;

/**
 * The four answers a read by execution id can give, and there is no fifth.
 *
 * <p>Sealed rather than {@code Optional<ExecutionOutcome>} because the absences are not the same
 * absence and a caller must not be able to conflate them. {@link Unknown} says "this process has no
 * record of that id"; {@link Expired} says "it ran, here is how it ended, and its result is past the
 * retention horizon"; {@link Redacted} says "it ran, here is how it ended, and its result was never
 * retainable". Collapsing them into an empty optional is precisely the silently-short answer this
 * project has spent weeks removing — an adapter would render the same empty body for a typo, for a
 * real execution whose payload it no longer holds, and for one whose payload it refused to keep.</p>
 *
 * <h2>{@link Redacted} is a fourth member of a sealed hierarchy, and that is a breaking change</h2>
 * <p>Stated rather than softened, and it is the identical cost {@code ExecutionTransition} paid when
 * it gained a member. An exhaustive {@code switch} written against the previous three arms outside
 * this repository stops compiling, because the compiler can no longer prove it covers every case.
 * The compensation is narrow and deliberate: the new arm is only ever produced for an execution
 * whose payload was not retainable, so a consumer that adds a {@code default} arm mapping it beside
 * {@link Expired} — status and reason present, payload absent — is correct without reading any of
 * the detail below. What it must not do is map it to {@link Unknown}, which would report a real
 * execution as one that never happened.</p>
 *
 * <h2>Where a result lives, and what a caller observes past that horizon</h2>
 * <p>Results are retained in two places at once, and the order matters. A bounded, count-limited
 * cache in the process that ran the execution answers first, and a durable record in the execution
 * store answers when the cache misses. The cache is the same one that has always been here; what
 * changed is that it is no longer the authority, so a result readable before a restart is still
 * readable after one, and readable from a second instance that never ran it.</p>
 *
 * <p>The durable record carries a payload-retention state of its own, and the four answers below are
 * exactly its projection. Concretely, a caller observes:</p>
 * <ul>
 *   <li>{@link Found} with {@code status == RUNNING} while the traversal is in flight;</li>
 *   <li>{@link Found} with a terminal status once it completes — with a payload when one was
 *       produced and retained, and without one when the execution produced none;</li>
 *   <li>{@link Redacted} when the execution produced a payload that was not retained: it exceeded
 *       the store's cap, or it did not project onto the closed payload model at all. The terminal
 *       status and its termination reason are reported in full;</li>
 *   <li>{@link Expired} once the retention window has elapsed, reported from the durable record,
 *       which owns that judgement because only the store's clock can make it. The terminal status
 *       and its termination reason are still reported, the payload is not. <b>Evicting a result from
 *       the process-local cache is not an expiry and never produces this answer while a durable
 *       record exists</b>: the cache is bounded by a count of executions, not by time, so an
 *       instance that has since run a few hundred more traversals still reads a durably held result
 *       as {@link Found}, exactly as an instance that never ran it does. The process-local record of
 *       a terminal execution answers {@code Expired} on its own only where no durable record backs
 *       it — where none was ever written, or where a purge has since removed it;</li>
 *   <li>{@link Unknown} once the durable record has been purged and even the tombstone is gone, when
 *       no durable store is composed and the process has restarted, or when the execution belongs to
 *       another tenant.</li>
 * </ul>
 *
 * <p>The restart case is no longer the sharp edge it was, and the remaining one is stated rather
 * than softened: without a durable, result-capable store composed, a result readable before a
 * restart still reads as {@link Unknown} after one, indistinguishable from an id that never existed.
 * That degradation is a deployment choice, not a surprise.</p>
 *
 * <h2>Another tenant's execution is {@link Unknown}, never a denial</h2>
 * <p>Same choice {@code ExecutionStore.load} makes for the same reason: a distinct "exists but not
 * yours" answer is an existence oracle, and a caller could enumerate another tenant's execution ids
 * through it. Both the cache and the durable record are keyed by tenant <em>and</em> id together, so
 * a cross-tenant hit is not excluded by a check that could be forgotten — it is not a lookup that
 * can be expressed.</p>
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
     * <h2>And the termination reason, for the same reason the status is here</h2>
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
     * The execution ran and reached {@code status}, and its result was never retainable.
     *
     * <p>The answer this hierarchy previously could not give. A payload the store refused — because
     * its encoding exceeded the published cap, or because the value does not project onto the closed
     * payload model at all — used to arrive as a {@link Found} carrying a null payload, which is the
     * same shape as a run that legitimately produced nothing. Those are different facts and they call
     * for different actions: one is a limit an operator can raise or a defect in a node, and the other
     * is a normal completion.</p>
     *
     * <p>Deliberately carries the terminal status and the termination reason, exactly as
     * {@link Expired} does and for exactly the same reason: the status alone reports a cancelled
     * execution as a failure, and a caller reading a result whose payload it cannot see has nothing
     * to check that against. {@link #payloadState()} says which refusal applies, and it is never
     * {@link ai.ravenroot.api.persistence.ResultPayloadState#RETAINED} or
     * {@link ai.ravenroot.api.persistence.ResultPayloadState#NONE} — those two are
     * {@link Found}.</p>
     *
     * @param executionId  the stable execution id used to identify the requested resource.
     * @param status       terminal lifecycle state the execution reached.
     * @param terminationReason why that status was reached, or {@code null} when nothing
     *                     distinguishes it. See {@link ExecutionTerminationReason}.
     * @param payloadState why the payload is not being returned.
     */
    record Redacted(UUID executionId, ProcessInstanceStatus status,
                    ExecutionTerminationReason terminationReason,
                    ai.ravenroot.api.persistence.ResultPayloadState payloadState)
            implements ExecutionLookup {
        /** Rejects a withheld answer that cannot say what was withheld or how the run ended. */
        public Redacted {
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(payloadState, "payloadState");
            if (payloadState == ai.ravenroot.api.persistence.ResultPayloadState.RETAINED
                    || payloadState == ai.ravenroot.api.persistence.ResultPayloadState.NONE) {
                throw new IllegalArgumentException(payloadState
                        + " describes a payload a caller can be given, so it is a Found answer");
            }
        }

        /**
         * Whether the execution this answer describes was stopped on request rather than having
         * broken.
         *
         * @return whether the recorded termination is a cancellation.
         */
        public boolean cancelled() {
            return ExecutionTerminationReason.isCancellation(terminationReason);
        }
    }

    /**
     * This process has no record of the id for the asking tenant.
     *
     * <p>Covers several distinct situations on purpose — never submitted, submitted by another
     * tenant, purged past the retention horizon, evicted past the process-local tombstone horizon,
     * and, where no durable result store is composed, submitted before a restart — because
     * distinguishing them for the caller would mean disclosing the first two.</p>
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
