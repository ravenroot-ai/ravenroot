package ai.ravenroot.api.application;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable state of one process ingress or re-entry traversal.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param ingressNodeId the stable ingress node id used to identify the requested resource.
 * @param status lifecycle state represented by this value.
 * @param invocations invocation map keyed by each invocation's ID.
 */
public record Traversal(UUID traversalId, String ingressNodeId, TraversalStatus status,
                        Map<UUID, NodeInvocation> invocations) {
/**
 * Validates traversal state and immutable invocation history together.
 */
    public Traversal {
        if (traversalId == null) throw new IllegalArgumentException("traversalId cannot be null");
        if (ingressNodeId == null || ingressNodeId.isBlank()) {
            throw new IllegalArgumentException("ingressNodeId cannot be blank");
        }
        if (status == null) throw new IllegalArgumentException("status cannot be null");
        var ordered = new LinkedHashMap<UUID, NodeInvocation>();
        if (invocations != null) {
            invocations.forEach((id, invocation) -> {
                if (id == null || invocation == null || !id.equals(invocation.invocationId())) {
                    throw new IllegalArgumentException("invocations must be keyed by invocationId");
                }
                ordered.put(id, invocation);
            });
        }
        invocations = Collections.unmodifiableMap(ordered);
        validateCompletedState(status, invocations);
    }

/**
 * Moves this traversal to a permitted successor lifecycle state.
 * @param next requested lifecycle state after this transition.
 * @return an immutable traversal snapshot bearing {@code next}; the current snapshot is not modified.
 */
    public Traversal transitionTo(TraversalStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Illegal traversal transition: " + status + " -> " + next);
        }
        if (next == TraversalStatus.COMPLETED && hasIncompleteInvocations(invocations)) {
            throw new IllegalStateException(
                    "A completed traversal cannot contain incomplete invocations");
        }
        if (next == TraversalStatus.COMPLETED && lacksASuccessfulInvocation(invocations)) {
            throw new IllegalStateException(
                    "A completed traversal requires at least one completed invocation");
        }
        return new Traversal(traversalId, ingressNodeId, next, invocations);
    }

/**
 * Returns a traversal copy containing an additional node invocation.
 * @param invocation invocation to add or replace, identified by its invocation ID.
 * @return an immutable running traversal that includes the supplied invocation.
 */
    public Traversal addInvocation(NodeInvocation invocation) {
        if (status != TraversalStatus.RUNNING) {
            throw new IllegalStateException("Cannot schedule an invocation while traversal is " + status);
        }
        if (invocation == null) throw new IllegalArgumentException("invocation cannot be null");
        if (invocations.containsKey(invocation.invocationId())) {
            throw new IllegalArgumentException("Duplicate invocationId: " + invocation.invocationId());
        }
        var updated = new LinkedHashMap<>(invocations);
        updated.put(invocation.invocationId(), invocation);
        return new Traversal(traversalId, ingressNodeId, status, updated);
    }

/**
 * Returns a traversal copy with one invocation state replaced.
 * @param invocation invocation to add or replace, identified by its invocation ID.
 * @return an immutable traversal whose invocation with the same identifier has been replaced.
 */
    public Traversal replaceInvocation(NodeInvocation invocation) {
        if (status.terminal()) {
            throw new IllegalStateException("Cannot transition an invocation while traversal is " + status);
        }
        if (invocation == null || !invocations.containsKey(invocation.invocationId())) {
            throw new IllegalArgumentException("Unknown invocationId: "
                    + (invocation == null ? null : invocation.invocationId()));
        }
        var updated = new LinkedHashMap<>(invocations);
        updated.put(invocation.invocationId(), invocation);
        return new Traversal(traversalId, ingressNodeId, status, updated);
    }

    private static void validateCompletedState(TraversalStatus status, Map<UUID, NodeInvocation> invocations) {
        if (status == TraversalStatus.COMPLETED && hasIncompleteInvocations(invocations)) {
            throw new IllegalArgumentException(
                    "A completed traversal cannot contain incomplete invocations: " + incomplete(invocations));
        }
        if (status == TraversalStatus.COMPLETED && lacksASuccessfulInvocation(invocations)) {
            throw new IllegalArgumentException(
                    "A completed traversal requires at least one completed invocation, got "
                            + statuses(invocations));
        }
    }

    /**
     * Whether a traversal claims success without a single invocation having succeeded.
     *
     * <p>The pair to {@link #hasIncompleteInvocations}, and the reason the CORE-03 relaxation is two
     * rules rather than one. Loosening "every invocation COMPLETED" to "every invocation terminal"
     * admits exactly one thing too many: a COMPLETED traversal in which every invocation FAILED. A
     * quorum needs a traversal to succeed over branches that failed, not over <em>nothing but</em>
     * branches that failed — no work succeeded there, so there is no result and no sense in which
     * the traversal completed.</p>
     *
     * <p>This is the same shape as the rule one level down: {@link NodeInvocation} permits failed
     * attempts but refuses to be COMPLETED unless its last attempt actually succeeded. Stating it
     * here too matters because {@code Traversal} is a store-ready aggregate whose constructor is
     * what classifies a persisted row set as {@code Corrupted}; every row set the constructor
     * accepts is a row set that loads silently, so widening it is a decision about what a reader is
     * allowed to trust, not only about what the runtime happens to emit.</p>
     *
     * <p>A traversal with no invocations at all is not caught by this: nothing has run, so nothing
     * has failed, and that is the ordinary state of a freshly accepted traversal rather than a
     * contradiction.</p>
     */
    private static boolean lacksASuccessfulInvocation(Map<UUID, NodeInvocation> invocations) {
        return !invocations.isEmpty() && invocations.values().stream()
                .noneMatch(invocation -> invocation.status() == NodeInvocationStatus.COMPLETED);
    }

    private static Map<UUID, NodeInvocationStatus> statuses(Map<UUID, NodeInvocation> invocations) {
        var all = new LinkedHashMap<UUID, NodeInvocationStatus>();
        invocations.forEach((id, invocation) -> all.put(id, invocation.status()));
        return all;
    }

    /**
     * Whether any invocation is still <em>in flight</em>.
     *
     * <p>The test is {@link NodeInvocationStatus#terminal()} rather than equality with
     * {@link NodeInvocationStatus#COMPLETED}, and CORE-03 is what forced the distinction. A
     * {@code k of n} fan-in exists so that a branch may fail without the traversal failing, so a
     * traversal that legitimately succeeds now contains {@code FAILED} invocations — the branches
     * the quorum did not need. Requiring every invocation to be {@code COMPLETED} made "quorum" and
     * "a branch may fail" mutually exclusive, which are the two things CORE-03 has to deliver
     * together.</p>
     *
     * <p>What the invariant still forbids is the thing it was written to forbid: a traversal
     * declaring itself finished while work is genuinely outstanding. {@code SCHEDULED},
     * {@code RUNNING} and {@code WAITING} remain violations, so a traversal cannot complete out from
     * under a node that is still running — it can only complete over one that has already stopped.
     * A failed invocation is not unfinished business; it is finished business with a bad outcome,
     * and whether that outcome is fatal is the <em>graph's</em> decision, expressed by its join
     * configuration, not this record's.</p>
     */
    private static boolean hasIncompleteInvocations(Map<UUID, NodeInvocation> invocations) {
        return invocations.values().stream().anyMatch(invocation -> !invocation.status().terminal());
    }

    private static Map<UUID, NodeInvocationStatus> incomplete(Map<UUID, NodeInvocation> invocations) {
        var offenders = new LinkedHashMap<UUID, NodeInvocationStatus>();
        invocations.forEach((id, invocation) -> {
            if (!invocation.status().terminal()) {
                offenders.put(id, invocation.status());
            }
        });
        return offenders;
    }
}
