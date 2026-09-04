package ai.ravenroot.api.persistence;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;

import java.util.UUID;

/**
 * The unit of write: a <em>legal aggregate transition</em>, never a field patch and never a
 * whole-aggregate snapshot imposed on the adapter (ADR 0010 section 3).
 *
 * <p>Each member mirrors a mutator {@link ProcessInstance} already publishes, and {@link #applyTo}
 * delegates to that mutator. This resolves the illegal-aggregate hazard <em>by construction</em>:
 * because {@code ProcessInstance} revalidates identity uniqueness, the cross-traversal parent rule
 * and full acyclicity in its canonical constructor, a store that persisted field-level deltas could
 * reconstruct an aggregate the domain considers illegal. Nothing expressible here can do that.</p>
 *
 * <p>The adapter is free to store snapshots keyed by {@code (processInstanceId, revision)} or to
 * append these transitions and fold them through the same mutators; the core cannot observe which.
 * PERS-07 reuses these descriptors for the journal, so it never has to diff two aggregates.</p>
 */
public sealed interface ExecutionTransition {

    /**
     * Folds this transition over {@code current}, which is {@code null} only for
     * {@link ProcessCreated}. Domain rejections surface as {@link IllegalArgumentException} or
     * {@link IllegalStateException} from the aggregate itself; adapters map those to
     * {@link ExecutionStoreFailure.InvalidRequest} when applying a caller's batch and to
     * {@link ExecutionStoreFailure.Corrupted} when replaying their own stored state.
     *
     * <p>The absent-instance case is <em>not</em> among those: a batch against an instance that does
     * not exist is resolved by the port as {@link ExecutionStoreFailure.NotFound} before folding
     * begins, because {@code InvalidRequest} must be decidable from the request alone and existence
     * is stored state whose absence is frequently a legitimate race. The null guard below is a
     * domain invariant for direct use of these transitions, not a port failure classification, and
     * is unreachable through a conforming adapter.</p>
 * @param current aggregate state before the transition.
 * @return aggregate state obtained by applying this transition to the supplied current state.
     */
    ProcessInstance applyTo(ProcessInstance current);

    /**
     * Creates the instance and sets its write-once graph version pin. Legal only against an absent
     * instance, which is what a {@link RevisionExpectation.NotPresent} expectation asserts.
 * @param initial initial aggregate state.
 * @param graphVersionPin graph version pinned for this state transition.
     */
    record ProcessCreated(ProcessInstance initial, GraphVersionPin graphVersionPin)
            implements ExecutionTransition {
        /** Requires both the initial aggregate and its pinned graph version. */
        public ProcessCreated {
            if (initial == null) throw new IllegalArgumentException("initial cannot be null");
            if (graphVersionPin == null) throw new IllegalArgumentException("graphVersionPin cannot be null");
        }

        @Override
        public ProcessInstance applyTo(ProcessInstance current) {
            if (current != null) {
                throw new IllegalStateException("ProcessCreated applied to an existing process instance");
            }
            return initial;
        }
    }

/**
 * Defines the process transitioned contract exposed to Ravenroot integrators.
 * @param next aggregate state after the transition.
 */
    record ProcessTransitioned(ProcessInstanceStatus next) implements ExecutionTransition {
        @Override
        public ProcessInstance applyTo(ProcessInstance current) {
            return required(current).transitionTo(next);
        }
    }

/**
 * Defines the traversal added contract exposed to Ravenroot integrators.
 * @param traversal traversal whose state changes.
 */
    record TraversalAdded(Traversal traversal) implements ExecutionTransition {
        @Override
        public ProcessInstance applyTo(ProcessInstance current) {
            return required(current).addTraversal(traversal);
        }
    }

/**
 * Defines the traversal transitioned contract exposed to Ravenroot integrators.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param next aggregate state after the transition.
 */
    record TraversalTransitioned(UUID traversalId, TraversalStatus next) implements ExecutionTransition {
        @Override
        public ProcessInstance applyTo(ProcessInstance current) {
            return required(current).transitionTraversal(traversalId, next);
        }
    }

/**
 * Defines the invocation added contract exposed to Ravenroot integrators.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param invocation node invocation recorded by the transition.
 */
    record InvocationAdded(UUID traversalId, NodeInvocation invocation) implements ExecutionTransition {
        @Override
        public ProcessInstance applyTo(ProcessInstance current) {
            return required(current).addInvocation(traversalId, invocation);
        }
    }

/**
 * Defines the invocation transitioned contract exposed to Ravenroot integrators.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param invocationId the stable invocation id used to identify the requested resource.
 * @param next aggregate state after the transition.
 */
    record InvocationTransitioned(UUID traversalId, UUID invocationId, NodeInvocationStatus next)
            implements ExecutionTransition {
        @Override
        public ProcessInstance applyTo(ProcessInstance current) {
            return required(current).transitionInvocation(traversalId, invocationId, next);
        }
    }

/**
 * Defines the attempt added contract exposed to Ravenroot integrators.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param invocationId the stable invocation id used to identify the requested resource.
 * @param attempt node attempt recorded by the transition.
 */
    record AttemptAdded(UUID traversalId, UUID invocationId, NodeAttempt attempt)
            implements ExecutionTransition {
        @Override
        public ProcessInstance applyTo(ProcessInstance current) {
            return required(current).addAttempt(traversalId, invocationId, attempt);
        }
    }

/**
 * Defines the attempt transitioned contract exposed to Ravenroot integrators.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param invocationId the stable invocation id used to identify the requested resource.
 * @param attemptId the stable attempt id used to identify the requested resource.
 * @param next aggregate state after the transition.
 */
    record AttemptTransitioned(UUID traversalId, UUID invocationId, UUID attemptId, NodeAttemptStatus next)
            implements ExecutionTransition {
        @Override
        public ProcessInstance applyTo(ProcessInstance current) {
            return required(current).transitionAttempt(traversalId, invocationId, attemptId, next);
        }
    }

    /**
     * Records that recovery declined to act on an attempt on one delivery, because this deployment
     * could not yet classify the execution and waiting might still change that.
     *
     * <p>It moves no lifecycle state: the attempt keeps its status, and the only thing that changes
     * is the high-water mark the recovery delivery limit is evaluated against. Written so that a
     * dependency outage does not silently spend an attempt's delivery budget and park it the moment
     * the outage ends -- see {@link ai.ravenroot.api.application.NodeAttempt#withheldThrough(int)}.</p>
     *
     * @param traversalId the stable traversal id used to identify the requested resource.
     * @param invocationId the stable invocation id used to identify the requested resource.
     * @param attemptId the stable attempt id used to identify the requested resource.
     * @param throughDelivery the recovery delivery on which the attempt was withheld.
     */
    record RecoveryWithheld(UUID traversalId, UUID invocationId, UUID attemptId, int throughDelivery)
            implements ExecutionTransition {
        /** Rejects a mark that names no delivery. */
        public RecoveryWithheld {
            if (throughDelivery < 1) {
                throw new IllegalArgumentException("throughDelivery must be positive");
            }
        }

        @Override
        public ProcessInstance applyTo(ProcessInstance current) {
            return required(current).recordRecoveryWithheld(traversalId, invocationId, attemptId,
                    throughDelivery);
        }
    }

    /**
     * Records an attempt as dispatched-with-unknown-outcome (ADR 0022, PERS-04).
     *
     * <p>The runtime writes this in the same fenced batch that acknowledges the work item, so a
     * parked attempt leaves the claim loop rather than being redelivered forever. {@code cause} is an
     * already operator-safe summary, bounded and control-character-stripped by
     * {@link NodeAttempt}.</p>
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param invocationId the stable invocation id used to identify the requested resource.
 * @param attemptId the stable attempt id used to identify the requested resource.
 * @param cause sanitized reason that explains the change.
     */
    record AttemptParked(UUID traversalId, UUID invocationId, UUID attemptId, String cause)
            implements ExecutionTransition {
        /** Validates the operator-safe cause recorded for a parked attempt. */
        public AttemptParked {
            if (cause == null || cause.isBlank()) {
                throw new IllegalArgumentException("cause cannot be blank");
            }
        }

        @Override
        public ProcessInstance applyTo(ProcessInstance current) {
            return required(current).parkAttempt(traversalId, invocationId, attemptId, cause);
        }
    }

    /**
     * A human resolved a park by asserting the effect did happen: COMPLETED with completion
     * {@link ai.ravenroot.api.application.NodeAttemptCompletion#OPERATOR_VERIFIED}, never a forged
     * {@code SUCCEEDED}.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param invocationId the stable invocation id used to identify the requested resource.
 * @param attemptId the stable attempt id used to identify the requested resource.
     */
    record ParkResolvedCompleted(UUID traversalId, UUID invocationId, UUID attemptId)
            implements ExecutionTransition {
        @Override
        public ProcessInstance applyTo(ProcessInstance current) {
            return required(current).resolveParkedVerified(traversalId, invocationId, attemptId);
        }
    }

/**
 * A human resolved a park by asserting the effect did not happen.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param invocationId the stable invocation id used to identify the requested resource.
 * @param attemptId the stable attempt id used to identify the requested resource.
 */
    record ParkResolvedFailed(UUID traversalId, UUID invocationId, UUID attemptId)
            implements ExecutionTransition {
        @Override
        public ProcessInstance applyTo(ProcessInstance current) {
            return required(current).resolveParkedFailed(traversalId, invocationId, attemptId);
        }
    }

    /**
     * A human resolved a park by retrying: atomically fails the parked attempt and appends the next
     * ordinal, mirroring {@link ResumedWithNewAttempt} so the retry cannot race its own resolution.
     *
     * <p>This is the only route out of a park that produces new work, and it produces it as a
     * <em>new attempt</em> — new ordinal, new attemptId, therefore a new effect identity under the
     * attempt-scoped idempotency key. {@code PARKED -> RUNNING} does not exist precisely so that this
     * is the only route.</p>
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param invocationId the stable invocation id used to identify the requested resource.
 * @param attemptId the stable attempt id used to identify the requested resource.
 * @param nextAttempt delivery-attempt number assigned after the retry.
     */
    record ParkResolvedWithRetry(UUID traversalId, UUID invocationId, UUID attemptId,
                                 NodeAttempt nextAttempt) implements ExecutionTransition {
        @Override
        public ProcessInstance applyTo(ProcessInstance current) {
            return required(current)
                    .resolveParkedWithRetry(traversalId, invocationId, attemptId, nextAttempt);
        }
    }

/**
 * Ordinary resume: the same waiting invocation and attempt move back to running.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param invocationId the stable invocation id used to identify the requested resource.
 * @param attemptId the stable attempt id used to identify the requested resource.
 */
    record AttemptResumed(UUID traversalId, UUID invocationId, UUID attemptId) implements ExecutionTransition {
        @Override
        public ProcessInstance applyTo(ProcessInstance current) {
            return required(current).resumeAttempt(traversalId, invocationId, attemptId);
        }
    }

    /**
     * Externalized-continuation resume: atomically completes the waiting attempt with completion
     * {@code WAIT} and appends the next ordinal under the same invocation.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param invocationId the stable invocation id used to identify the requested resource.
 * @param nextAttempt delivery-attempt number assigned after the retry.
     */
    record ResumedWithNewAttempt(UUID traversalId, UUID invocationId, NodeAttempt nextAttempt)
            implements ExecutionTransition {
        @Override
        public ProcessInstance applyTo(ProcessInstance current) {
            return required(current).resumeWithNewAttempt(traversalId, invocationId, nextAttempt);
        }
    }

    private static ProcessInstance required(ProcessInstance current) {
        if (current == null) {
            throw new IllegalStateException("transition applied to an absent process instance");
        }
        return current;
    }
}
