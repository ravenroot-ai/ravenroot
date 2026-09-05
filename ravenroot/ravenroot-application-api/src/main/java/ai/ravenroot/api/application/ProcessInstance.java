package ai.ravenroot.api.application;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Framework-neutral, store-ready aggregate for a process and all of its traversals.
 *
 * <p>The aggregate contains no repository, persistence or actor-framework dependency. Every update
 * returns a new value and revalidates identity uniqueness and causal-parent invariants.</p>
 *
 * <p><b>{@link #status()} is not the whole answer for an instance that ended.</b> An instance whose
 * work was stopped on request is stored as {@link ProcessInstanceStatus#FAILED} and carries
 * {@link ExecutionTerminationReason#CANCELLED}; read without the reason, that status reports an
 * incident that did not occur. The pair is the answer, never the status alone — see
 * {@link ExecutionTerminationReason} for why the status vocabulary was deliberately left
 * unchanged.</p>
 *
 * @param processInstanceId durable aggregate identity shared by its traversals
 * @param status aggregate lifecycle state
 * @param traversals immutable traversal map keyed by traversal ID
 * @param terminationReason why a terminal {@code status} was reached when the status alone would
 *                         misdescribe it, or {@code null} when nothing distinguishes this
 *                         termination. Always {@code null} while the instance is not terminal.
 */
public record ProcessInstance(UUID processInstanceId, ProcessInstanceStatus status,
                              Map<UUID, Traversal> traversals,
                              ExecutionTerminationReason terminationReason) {
/**
 * Copies the traversal map and rejects inconsistent map keys, duplicate child identities, and
 * causal-parent relationships that cannot belong to this process instance.
 */
    public ProcessInstance {
        if (processInstanceId == null) throw new IllegalArgumentException("processInstanceId cannot be null");
        if (status == null) throw new IllegalArgumentException("status cannot be null");
        var ordered = new LinkedHashMap<UUID, Traversal>();
        if (traversals != null) {
            traversals.forEach((id, traversal) -> {
                if (id == null || traversal == null || !id.equals(traversal.traversalId())) {
                    throw new IllegalArgumentException("traversals must be keyed by traversalId");
                }
                ordered.put(id, traversal);
            });
        }
        validateIdentitiesAndCausality(processInstanceId, ordered);
        traversals = Collections.unmodifiableMap(ordered);
        validateCompletedState(status, traversals);
        validateTerminationReason(status, terminationReason);
    }

/**
 * Compatibility constructor for the shape that predates a recorded termination reason.
 *
 * <p>Reports an absent reason, which is the correct reading of every instance built without one:
 * nothing distinguishes its termination. It is the shape a row written before the reason column
 * existed reconstructs through, and it must reconstruct as "unstated" rather than as a value
 * somebody inferred from the status.</p>
 *
 * @param processInstanceId durable aggregate identity shared by its traversals
 * @param status aggregate lifecycle state
 * @param traversals immutable traversal map keyed by traversal ID
 */
    public ProcessInstance(UUID processInstanceId, ProcessInstanceStatus status,
                           Map<UUID, Traversal> traversals) {
        this(processInstanceId, status, traversals, null);
    }

/**
 * Transitions the aggregate state after verifying all traversals are consistent.
 * @param next target aggregate lifecycle state
 * @return updated process aggregate
 */
    public ProcessInstance transitionTo(ProcessInstanceStatus next) {
        return transitionTo(next, null);
    }

/**
 * Transitions the aggregate state, recording why it ended the way it did.
 *
 * <p>One call rather than two, for the reason {@link Traversal#transitionTo(TraversalStatus,
 * ExecutionTerminationReason)} states: the status and the reason are one fact, and a separate
 * annotating write would leave a committed window in which the record reports a fault.</p>
 *
 * @param next target aggregate lifecycle state
 * @param reason why this termination is not what its status alone suggests, or {@code null}.
 * @return updated process aggregate
 */
    public ProcessInstance transitionTo(ProcessInstanceStatus next, ExecutionTerminationReason reason) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Illegal process instance transition: " + status + " -> " + next);
        }
        if (next == ProcessInstanceStatus.COMPLETED && hasIncompleteTraversals(traversals)) {
            throw new IllegalStateException(
                    "A completed process instance cannot contain incomplete traversals");
        }
        return new ProcessInstance(processInstanceId, next, traversals, reason);
    }

/**
 * Adds a uniquely identified traversal while the process remains mutable.
 * @param traversal new traversal belonging to this process
 * @return updated aggregate containing the traversal
 */
    public ProcessInstance addTraversal(Traversal traversal) {
        requireNonTerminal();
        if (traversal == null) throw new IllegalArgumentException("traversal cannot be null");
        if (traversals.containsKey(traversal.traversalId())) {
            throw new IllegalArgumentException("Duplicate traversalId: " + traversal.traversalId());
        }
        var updated = new LinkedHashMap<>(traversals);
        updated.put(traversal.traversalId(), traversal);
        return new ProcessInstance(processInstanceId, status, updated, terminationReason);
    }

/**
 * Transitions one contained traversal.
 * @param traversalId identity of the contained traversal
 * @param next target traversal lifecycle state
 * @return updated aggregate containing the transitioned traversal
 */
    public ProcessInstance transitionTraversal(UUID traversalId, TraversalStatus next) {
        return transitionTraversal(traversalId, next, null);
    }

/**
 * Transitions one contained traversal, recording why it ended the way it did.
 *
 * <p>The reason lands on the traversal and not on this aggregate. One instance can carry several
 * traversals, and cancelling one of them says nothing about the others or about the instance, which
 * stays live until its last traversal ends. The instance records its own reason through
 * {@link #transitionTo(ProcessInstanceStatus, ExecutionTerminationReason)} when that moment
 * arrives.</p>
 *
 * @param traversalId identity of the contained traversal
 * @param next target traversal lifecycle state
 * @param reason why this termination is not what its status alone suggests, or {@code null}.
 * @return updated aggregate containing the transitioned traversal
 */
    public ProcessInstance transitionTraversal(UUID traversalId, TraversalStatus next,
                                               ExecutionTerminationReason reason) {
        requireNonTerminal();
        Traversal traversal = traversal(traversalId);
        return replaceTraversal(traversal.transitionTo(next, reason));
    }

/**
 * Adds an invocation to one active traversal.
 * @param traversalId identity of the traversal receiving the invocation
 * @param invocation new graph-node invocation
 * @return updated aggregate containing the invocation
 */
    public ProcessInstance addInvocation(UUID traversalId, NodeInvocation invocation) {
        requireNonTerminal();
        return replaceTraversal(traversal(traversalId).addInvocation(invocation));
    }

/**
 * Transitions one invocation in a contained traversal.
 * @param traversalId identity of the containing traversal
 * @param invocationId identity of the invocation to update
 * @param next target invocation lifecycle state
 * @return updated aggregate containing the transitioned invocation
 */
    public ProcessInstance transitionInvocation(UUID traversalId, UUID invocationId,
                                                NodeInvocationStatus next) {
        requireNonTerminal();
        Traversal traversal = traversal(traversalId);
        NodeInvocation invocation = invocation(traversal, invocationId);
        return replaceTraversal(traversal.replaceInvocation(invocation.transitionTo(next)));
    }

/**
 * Adds a next attempt to one invocation.
 * @param traversalId identity of the containing traversal
 * @param invocationId identity of the invocation receiving the attempt
 * @param attempt scheduled next attempt
 * @return updated aggregate containing the attempt
 */
    public ProcessInstance addAttempt(UUID traversalId, UUID invocationId, NodeAttempt attempt) {
        requireNonTerminal();
        Traversal traversal = traversal(traversalId);
        NodeInvocation invocation = invocation(traversal, invocationId);
        return replaceTraversal(traversal.replaceInvocation(invocation.addAttempt(attempt)));
    }

/**
 * Transitions an invocation's latest attempt.
 * @param traversalId identity of the containing traversal
 * @param invocationId identity of the containing invocation
 * @param attemptId identity of its latest attempt
 * @param next target attempt lifecycle state
 * @return updated aggregate containing the transitioned attempt
 */
    public ProcessInstance transitionAttempt(UUID traversalId, UUID invocationId, UUID attemptId,
                                             NodeAttemptStatus next) {
        requireNonTerminal();
        Traversal traversal = traversal(traversalId);
        NodeInvocation invocation = invocation(traversal, invocationId);
        return replaceTraversal(traversal.replaceInvocation(invocation.transitionAttempt(attemptId, next)));
    }

/**
 * Records the latest attempt as dispatched-with-unknown-outcome (ADR 0022).
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param invocationId the stable invocation id used to identify the requested resource.
 * @param attemptId the stable attempt id used to identify the requested resource.
 * @param cause explanation recorded for the indeterminate attempt.
 * @return a new process snapshot in which the matching attempt is parked with its indeterminate cause.
 */
    public ProcessInstance parkAttempt(UUID traversalId, UUID invocationId, UUID attemptId, String cause) {
        requireNonTerminal();
        Traversal traversal = traversal(traversalId);
        NodeInvocation invocation = invocation(traversal, invocationId);
        return replaceTraversal(traversal.replaceInvocation(invocation.parkAttempt(attemptId, cause)));
    }

    /**
     * Records that recovery withheld one attempt through {@code delivery}.
     *
     * @param traversalId the stable traversal id used to identify the requested resource.
     * @param invocationId the stable invocation id used to identify the requested resource.
     * @param attemptId the stable attempt id used to identify the requested resource.
     * @param delivery the recovery delivery on which the attempt was withheld.
     * @return a new process snapshot whose attempt carries the raised high-water mark.
     */
    public ProcessInstance recordRecoveryWithheld(UUID traversalId, UUID invocationId, UUID attemptId,
                                                  int delivery) {
        requireNonTerminal();
        Traversal traversal = traversal(traversalId);
        NodeInvocation invocation = invocation(traversal, invocationId);
        return replaceTraversal(traversal.replaceInvocation(
                invocation.recordRecoveryWithheld(attemptId, delivery)));
    }

/**
 * Closes a parked attempt as verified-done by a human.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param invocationId the stable invocation id used to identify the requested resource.
 * @param attemptId the stable attempt id used to identify the requested resource.
 * @return a new process snapshot marking the parked attempt operator-verified.
 */
    public ProcessInstance resolveParkedVerified(UUID traversalId, UUID invocationId, UUID attemptId) {
        requireNonTerminal();
        Traversal traversal = traversal(traversalId);
        NodeInvocation invocation = invocation(traversal, invocationId);
        return replaceTraversal(traversal.replaceInvocation(invocation.resolveParkedVerified(attemptId)));
    }

/**
 * Closes a parked attempt as verified-not-done by a human.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param invocationId the stable invocation id used to identify the requested resource.
 * @param attemptId the stable attempt id used to identify the requested resource.
 * @return a new process snapshot marking the parked attempt as failed.
 */
    public ProcessInstance resolveParkedFailed(UUID traversalId, UUID invocationId, UUID attemptId) {
        requireNonTerminal();
        Traversal traversal = traversal(traversalId);
        NodeInvocation invocation = invocation(traversal, invocationId);
        return replaceTraversal(traversal.replaceInvocation(invocation.resolveParkedFailed(attemptId)));
    }

/**
 * Atomically fails a parked attempt and appends its retry as a new ordinal.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param invocationId the stable invocation id used to identify the requested resource.
 * @param attemptId the stable attempt id used to identify the requested resource.
 * @param nextAttempt newly scheduled attempt that follows the closed attempt.
 * @return a new process snapshot that closes the parked attempt and schedules {@code nextAttempt}.
 */
    public ProcessInstance resolveParkedWithRetry(UUID traversalId, UUID invocationId, UUID attemptId,
                                                  NodeAttempt nextAttempt) {
        requireNonTerminal();
        Traversal traversal = traversal(traversalId);
        NodeInvocation invocation = invocation(traversal, invocationId);
        return replaceTraversal(traversal.replaceInvocation(
                invocation.resolveParkedWithRetry(attemptId, nextAttempt)));
    }

/**
 * Resumes a parked attempt only when its prior disposition allows it.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param invocationId the stable invocation id used to identify the requested resource.
 * @param attemptId the stable attempt id used to identify the requested resource.
 * @return a new process snapshot restoring the matching parked attempt to active processing.
 */
    public ProcessInstance resumeAttempt(UUID traversalId, UUID invocationId, UUID attemptId) {
        requireNonTerminal();
        Traversal traversal = traversal(traversalId);
        NodeInvocation invocation = invocation(traversal, invocationId);
        return replaceTraversal(traversal.replaceInvocation(invocation.resumeAttempt(attemptId)));
    }

/**
 * Creates a new retry attempt instead of reusing an ambiguous one.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param invocationId the stable invocation id used to identify the requested resource.
 * @param nextAttempt newly scheduled attempt that follows the closed attempt.
 * @return a new process snapshot that resumes the invocation with the supplied successor attempt.
 */
    public ProcessInstance resumeWithNewAttempt(UUID traversalId, UUID invocationId, NodeAttempt nextAttempt) {
        requireNonTerminal();
        Traversal traversal = traversal(traversalId);
        NodeInvocation invocation = invocation(traversal, invocationId);
        return replaceTraversal(traversal.replaceInvocation(invocation.resumeWithNewAttempt(nextAttempt)));
    }

    private Traversal traversal(UUID traversalId) {
        Traversal traversal = traversals.get(traversalId);
        if (traversal == null) throw new IllegalArgumentException("Unknown traversalId: " + traversalId);
        return traversal;
    }

    private static NodeInvocation invocation(Traversal traversal, UUID invocationId) {
        NodeInvocation invocation = traversal.invocations().get(invocationId);
        if (invocation == null) throw new IllegalArgumentException("Unknown invocationId: " + invocationId);
        return invocation;
    }

    private ProcessInstance replaceTraversal(Traversal traversal) {
        var updated = new LinkedHashMap<>(traversals);
        updated.put(traversal.traversalId(), traversal);
        return new ProcessInstance(processInstanceId, status, updated, terminationReason);
    }

    private void requireNonTerminal() {
        if (status.terminal()) {
            throw new IllegalStateException("Cannot transition child state while process instance is " + status);
        }
    }

    /**
     * Refuses a termination reason that contradicts the status it is meant to qualify.
     *
     * <p>The instance-level twin of {@code Traversal}'s check, and it runs for the same reason: this
     * constructor is what a durable adapter rebuilds stored rows through, so a row claiming an
     * instance was cancelled while still running, or cancelled and completed at once, is classified
     * as corrupt instead of being folded into a legal-looking aggregate.</p>
     */
    private static void validateTerminationReason(ProcessInstanceStatus status,
                                                  ExecutionTerminationReason terminationReason) {
        if (terminationReason == null) {
            return;
        }
        if (!status.terminal()) {
            throw new IllegalArgumentException("A process instance that is " + status
                    + " has not terminated and cannot carry the termination reason " + terminationReason);
        }
        if (terminationReason == ExecutionTerminationReason.CANCELLED
                && status != ProcessInstanceStatus.FAILED) {
            throw new IllegalArgumentException(
                    "A cancelled process instance produces no result and is recorded as FAILED, not "
                            + status);
        }
    }

    private static void validateIdentitiesAndCausality(UUID processInstanceId,
                                                        Map<UUID, Traversal> traversals) {
        var invocationIds = new HashSet<UUID>();
        var attemptIds = new HashSet<UUID>();
        var invocations = new HashMap<UUID, NodeInvocation>();
        var invocationTraversals = new HashMap<UUID, UUID>();
        traversals.values().forEach(traversal -> {
            traversal.invocations().values().forEach(invocation -> {
                unique(invocationIds, invocation.invocationId(), "invocationId");
                invocations.put(invocation.invocationId(), invocation);
                invocationTraversals.put(invocation.invocationId(), traversal.traversalId());
                invocation.attempts().forEach(attempt ->
                        unique(attemptIds, attempt.attemptId(), "attemptId"));
            });
        });
        invocations.values().forEach(invocation -> invocation.parentInvocationIds().forEach(parent -> {
            if (!invocations.containsKey(parent)) {
                throw new IllegalArgumentException("Unknown parentInvocationId: " + parent);
            }
        }));
        traversals.values().forEach(traversal -> {
            int position = 0;
            for (NodeInvocation invocation : traversal.invocations().values()) {
                for (UUID parent : invocation.parentInvocationIds()) {
                    if (!traversal.traversalId().equals(invocationTraversals.get(parent))
                            && (position != 0 || !traversal.ingressNodeId().equals(invocation.nodeId()))) {
                        throw new IllegalArgumentException(
                                "Cross-traversal parent is only legal on the re-entry ingress invocation");
                    }
                }
                position++;
            }
        });
        var visiting = new HashSet<UUID>();
        var visited = new HashSet<UUID>();
        invocations.keySet().forEach(id -> validateAcyclic(id, invocations, visiting, visited));
    }

    private static void unique(Set<UUID> identities, UUID identity, String kind) {
        if (!identities.add(identity)) {
            throw new IllegalArgumentException("Duplicate execution identity for " + kind + ": " + identity);
        }
    }

    private static void validateAcyclic(UUID invocationId, Map<UUID, NodeInvocation> invocations,
                                        Set<UUID> visiting, Set<UUID> visited) {
        if (visited.contains(invocationId)) return;
        if (!visiting.add(invocationId)) {
            throw new IllegalArgumentException("Causal invocation graph contains a cycle at " + invocationId);
        }
        invocations.get(invocationId).parentInvocationIds()
                .forEach(parent -> validateAcyclic(parent, invocations, visiting, visited));
        visiting.remove(invocationId);
        visited.add(invocationId);
    }

    private static void validateCompletedState(ProcessInstanceStatus status, Map<UUID, Traversal> traversals) {
        if (status == ProcessInstanceStatus.COMPLETED && hasIncompleteTraversals(traversals)) {
            throw new IllegalArgumentException(
                    "A completed process instance cannot contain incomplete traversals");
        }
    }

    private static boolean hasIncompleteTraversals(Map<UUID, Traversal> traversals) {
        return traversals.values().stream().anyMatch(traversal ->
                traversal.status() != TraversalStatus.COMPLETED);
    }
}
