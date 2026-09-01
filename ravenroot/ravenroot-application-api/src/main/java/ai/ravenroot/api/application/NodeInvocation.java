package ai.ravenroot.api.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Store-ready state of one visit to a logical node. {@code invocationId} is unique per visit;
 * therefore fan-out, retries and re-entry never overwrite one another merely because nodeId matches.
 * @param invocationId the stable invocation id used to identify the requested resource.
 * @param nodeId the stable node id used to identify the requested resource.
 * @param parentInvocationIds immutable causal parents that routed work to this invocation
 * @param status aggregate invocation lifecycle state
 * @param attempts ordered immutable history of attempts for this invocation
 * @param command processing directive selected when this invocation was routed
 */
public record NodeInvocation(UUID invocationId, String nodeId, Set<UUID> parentInvocationIds,
                             NodeInvocationStatus status, List<NodeAttempt> attempts,
                             ai.ravenroot.api.execution.NodeCommand command) {
/**
 * Validates parentage and freezes the ordered attempt history for an invocation.
 */
    public NodeInvocation {
        if (invocationId == null) throw new IllegalArgumentException("invocationId cannot be null");
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("nodeId cannot be blank");
        if (status == null) throw new IllegalArgumentException("status cannot be null");
        if (parentInvocationIds != null && parentInvocationIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("parentInvocationIds cannot contain null");
        }
        parentInvocationIds = Set.copyOf(parentInvocationIds == null ? Set.of() : parentInvocationIds);
        if (parentInvocationIds.contains(invocationId)) {
            throw new IllegalArgumentException("an invocation cannot be its own parent");
        }
        attempts = List.copyOf(attempts == null ? List.of() : attempts);
        command = command == null ? ai.ravenroot.api.execution.NodeCommand.PROCESS : command;
        validateAttempts(attempts);
        validateCompletedState(status, attempts);
    }

/**
 * Compatibility constructor for the attempt1 single-parent model.
 * @param invocationId the stable invocation id used to identify the requested resource.
 * @param nodeId the stable node id used to identify the requested resource.
 * @param parentInvocationId the stable parent invocation id used to identify the requested resource.
 * @param status aggregate invocation lifecycle state
 */
    public NodeInvocation(UUID invocationId, String nodeId, UUID parentInvocationId,
                          NodeInvocationStatus status) {
        this(invocationId, nodeId, parentInvocationId == null ? Set.of() : Set.of(parentInvocationId), status,
                List.of(), ai.ravenroot.api.execution.NodeCommand.PROCESS);
    }

/**
 * Compatibility constructor preserving the pre-command canonical descriptor.
 * @param invocationId the stable invocation id used to identify the requested resource.
 * @param nodeId the stable node id used to identify the requested resource.
 * @param parentInvocationIds immutable causal parents that routed work to this invocation
 * @param status aggregate invocation lifecycle state
 * @param attempts ordered immutable history of attempts for this invocation
 */
    public NodeInvocation(UUID invocationId, String nodeId, Set<UUID> parentInvocationIds,
                          NodeInvocationStatus status, List<NodeAttempt> attempts) {
        this(invocationId, nodeId, parentInvocationIds, status, attempts,
                ai.ravenroot.api.execution.NodeCommand.PROCESS);
    }

/**
 * Compatibility view; multi-parent invocations expose their first causal parent.
 * @return first causal parent retained for legacy single-parent callers
 */
    public UUID parentInvocationId() {
        return parentInvocationIds.stream().sorted().findFirst().orElse(null);
    }

/**
 * Produces a copy after one legal aggregate invocation transition.
 * @param next target invocation state
 * @return updated invocation retaining its identity, parents and attempt history
 */
    public NodeInvocation transitionTo(NodeInvocationStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Illegal node invocation transition: " + status + " -> " + next);
        }
        if (next == NodeInvocationStatus.COMPLETED && !hasSuccessfulAttempt(attempts)) {
            throw new IllegalStateException(
                    "A completed invocation requires a successfully completed attempt");
        }
        return new NodeInvocation(invocationId, nodeId, parentInvocationIds, next, attempts, command);
    }

/**
 * Appends the next scheduled attempt to this invocation.
 * @param attempt new attempt, which must follow the existing attempt ordering
 * @return updated invocation containing the appended attempt
 */
    public NodeInvocation addAttempt(NodeAttempt attempt) {
        if (status != NodeInvocationStatus.RUNNING) {
            throw new IllegalStateException("Cannot schedule an attempt while invocation is " + status);
        }
        if (attempt == null) throw new IllegalArgumentException("attempt cannot be null");
        if (attempt.ordinal() != attempts.size() + 1) {
            throw new IllegalArgumentException("attempt ordinal must be " + (attempts.size() + 1));
        }
        if (attempts.stream().anyMatch(existing -> existing.attemptId().equals(attempt.attemptId()))) {
            throw new IllegalArgumentException("Duplicate attemptId: " + attempt.attemptId());
        }
        if (!attempts.isEmpty() && attempts.getLast().status() != NodeAttemptStatus.FAILED) {
            throw new IllegalStateException("A new attempt requires the previous attempt to be FAILED");
        }
        var updated = new ArrayList<>(attempts);
        updated.add(attempt);
        return new NodeInvocation(invocationId, nodeId, parentInvocationIds, status, updated, command);
    }

/**
 * Retries after a retryable latest attempt by appending its replacement attempt.
 * @param nextAttempt scheduled retry attempt with the next ordinal
 * @return updated invocation with the retry appended
 */
    public NodeInvocation retryWithNewAttempt(NodeAttempt nextAttempt) {
        return addAttempt(nextAttempt);
    }

/**
 * Changes the latest attempt's state without changing invocation identity.
 * @param attemptId identity of the latest attempt to transition
 * @param next target state for that attempt
 * @return updated invocation containing the transitioned latest attempt
 */
    public NodeInvocation transitionAttempt(UUID attemptId, NodeAttemptStatus next) {
        if (status.terminal()) {
            throw new IllegalStateException("Cannot transition an attempt while invocation is " + status);
        }
        if (attemptId == null) throw new IllegalArgumentException("attemptId cannot be null");
        int index = -1;
        for (int candidate = 0; candidate < attempts.size(); candidate++) {
            if (attempts.get(candidate).attemptId().equals(attemptId)) {
                index = candidate;
                break;
            }
        }
        if (index < 0) throw new IllegalArgumentException("Unknown attemptId: " + attemptId);
        if (index != attempts.size() - 1) {
            throw new IllegalStateException("Only the latest attempt can transition");
        }
        var updated = new ArrayList<>(attempts);
        updated.set(index, attempts.get(index).transitionTo(next));
        return new NodeInvocation(invocationId, nodeId, parentInvocationIds, status, updated, command);
    }

    /**
     * Records that the latest attempt was dispatched and its outcome is unknown (ADR 0022).
     *
     * <p>The invocation itself stays {@code RUNNING}: parking is a fact about one attempt, not a
     * verdict on the visit. Whether the visit can proceed is decided when the park is resolved.</p>
 * @param attemptId the stable attempt id used to identify the requested resource.
 * @param cause bounded operator-facing reason that leaves the attempt waiting
 * @return updated invocation whose latest attempt is parked
     */
    public NodeInvocation parkAttempt(UUID attemptId, String cause) {
        return replaceLatest(attemptId, latest -> latest.park(cause));
    }

/**
 * Closes a parked attempt as verified-done by a human; the invocation may then complete.
 * @param attemptId the stable attempt id used to identify the requested resource.
 * @return updated invocation whose parked attempt is resolved as verified
 */
    public NodeInvocation resolveParkedVerified(UUID attemptId) {
        return replaceLatest(attemptId, NodeAttempt::resolveVerified);
    }

/**
 * Closes a parked attempt as verified-not-done by a human.
 * @param attemptId the stable attempt id used to identify the requested resource.
 * @return updated invocation whose parked attempt is resolved as failed
 */
    public NodeInvocation resolveParkedFailed(UUID attemptId) {
        return replaceLatest(attemptId, NodeAttempt::resolveFailed);
    }

    /**
     * Resolves a parked attempt and appends the retry <em>atomically</em>: the parked attempt fails
     * and the next ordinal is scheduled in one operation.
     *
     * <p>Atomic because the two halves must not be separable. Resolving first and appending second
     * leaves a window in which the invocation has no live attempt and the operator's retry can be
     * lost; appending first is not expressible at all, since {@link #addAttempt(NodeAttempt)}
     * requires the previous attempt to be {@code FAILED}. Mirrors
     * {@link #resumeWithNewAttempt(NodeAttempt)}, which exists for the same reason.</p>
 * @param attemptId the stable attempt id used to identify the requested resource.
 * @param nextAttempt scheduled retry appended after resolving the parked attempt as failed
 * @return updated invocation containing the new retry attempt
     */
    public NodeInvocation resolveParkedWithRetry(UUID attemptId, NodeAttempt nextAttempt) {
        if (nextAttempt == null || nextAttempt.status() != NodeAttemptStatus.SCHEDULED) {
            throw new IllegalArgumentException("retried attempt must be SCHEDULED");
        }
        NodeInvocation failed = replaceLatest(attemptId, NodeAttempt::resolveFailed);
        return failed.addAttempt(nextAttempt);
    }

    private NodeInvocation replaceLatest(UUID attemptId,
                                         java.util.function.UnaryOperator<NodeAttempt> change) {
        if (status.terminal()) {
            throw new IllegalStateException("Cannot transition an attempt while invocation is " + status);
        }
        if (attemptId == null) throw new IllegalArgumentException("attemptId cannot be null");
        if (attempts.isEmpty() || !attempts.getLast().attemptId().equals(attemptId)) {
            throw new IllegalArgumentException("Not the latest attempt: " + attemptId);
        }
        var updated = new ArrayList<>(attempts);
        updated.set(updated.size() - 1, change.apply(updated.getLast()));
        return new NodeInvocation(invocationId, nodeId, parentInvocationIds, status, updated, command);
    }

/**
 * Resumes the waiting invocation and its latest attempt without changing either identity.
 * @param attemptId the stable attempt id used to identify the requested resource.
 * @return updated invocation with its waiting latest attempt running again
 */
    public NodeInvocation resumeAttempt(UUID attemptId) {
        if (status != NodeInvocationStatus.WAITING) {
            throw new IllegalStateException("Cannot resume an invocation while it is " + status);
        }
        if (attempts.isEmpty() || !attempts.getLast().attemptId().equals(attemptId)
                || attempts.getLast().status() != NodeAttemptStatus.WAITING) {
            throw new IllegalStateException("The latest attempt is not waiting: " + attemptId);
        }
        var updated = new ArrayList<>(attempts);
        updated.set(updated.size() - 1, updated.getLast().transitionTo(NodeAttemptStatus.RUNNING));
        return new NodeInvocation(invocationId, nodeId, parentInvocationIds, NodeInvocationStatus.RUNNING, updated,
                command);
    }

    /**
     * Resumes an externalized continuation by completing the waiting attempt with WAIT and appending
     * a new, ordered attempt under the same invocation.
 * @param nextAttempt scheduled continuation attempt appended after completing the wait
 * @return updated invocation containing the resumed continuation attempt
     */
    public NodeInvocation resumeWithNewAttempt(NodeAttempt nextAttempt) {
        if (status != NodeInvocationStatus.WAITING) {
            throw new IllegalStateException("Cannot resume an invocation while it is " + status);
        }
        if (attempts.isEmpty() || attempts.getLast().status() != NodeAttemptStatus.WAITING) {
            throw new IllegalStateException("The latest attempt is not waiting");
        }
        if (nextAttempt == null || nextAttempt.status() != NodeAttemptStatus.SCHEDULED) {
            throw new IllegalArgumentException("resumed attempt must be SCHEDULED");
        }
        if (nextAttempt.ordinal() != attempts.size() + 1) {
            throw new IllegalArgumentException("attempt ordinal must be " + (attempts.size() + 1));
        }
        if (attempts.stream().anyMatch(existing -> existing.attemptId().equals(nextAttempt.attemptId()))) {
            throw new IllegalArgumentException("Duplicate attemptId: " + nextAttempt.attemptId());
        }
        var updated = new ArrayList<>(attempts);
        updated.set(updated.size() - 1, updated.getLast().completeWait());
        updated.add(nextAttempt);
        return new NodeInvocation(invocationId, nodeId, parentInvocationIds, NodeInvocationStatus.RUNNING, updated,
                command);
    }

    private static void validateAttempts(List<NodeAttempt> attempts) {
        var ids = new HashSet<UUID>();
        for (int index = 0; index < attempts.size(); index++) {
            NodeAttempt attempt = attempts.get(index);
            if (attempt == null) throw new IllegalArgumentException("attempts cannot contain null");
            if (attempt.ordinal() != index + 1) {
                throw new IllegalArgumentException("attempt ordinals must be contiguous and start at one");
            }
            if (!ids.add(attempt.attemptId())) {
                throw new IllegalArgumentException("attempt IDs must be distinct");
            }
            if (index < attempts.size() - 1 && attempt.status() != NodeAttemptStatus.FAILED
                    && attempt.completion() != NodeAttemptCompletion.WAIT) {
                throw new IllegalArgumentException(
                        "Only failed or WAIT-completed attempts may precede another attempt");
            }
        }
    }

    private static void validateCompletedState(NodeInvocationStatus status, List<NodeAttempt> attempts) {
        if (status == NodeInvocationStatus.COMPLETED && !hasSuccessfulAttempt(attempts)) {
            throw new IllegalArgumentException(
                    "A completed invocation requires a successfully completed attempt");
        }
    }

    /**
     * A completed invocation needs a last attempt that actually did the node's work.
     *
     * <p>{@link NodeAttemptCompletion#OPERATOR_VERIFIED} qualifies alongside
     * {@link NodeAttemptCompletion#SUCCEEDED}: an operator who verified the effect landed has
     * established the same fact by a different route, and refusing it would leave a resolved park
     * unable to complete its invocation — which would make the resolution decision unusable.
     * {@link NodeAttemptCompletion#WAIT} does not qualify, because it defers the work rather than
     * doing it.</p>
     */
    private static boolean hasSuccessfulAttempt(List<NodeAttempt> attempts) {
        return !attempts.isEmpty()
                && attempts.getLast().status() == NodeAttemptStatus.COMPLETED
                && attempts.getLast().completion().successful();
    }
}
