package ai.ravenroot.api.persistence;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.execution.NodeDirective;
import java.util.*;

/** Original runtime-owned SOURCE visit, committed with its lifecycle and journal events.
 * @param invocationId original SOURCE visit
 * @param attemptId original SOURCE attempt
 * @param startedEventId original recorded start used as the completion cause
 * @param completedEventId original completion used as the outgoing dispatch cause, absent before completion
 * @param runtimeState monotone original execution budget and source reservation
 */
public record DeploymentEntryCheckpoint(UUID invocationId, UUID attemptId, UUID startedEventId,
        UUID completedEventId, DeploymentEntryRuntimeState runtimeState) {
    /** Requires complete original visit and start coordinates. */
    public DeploymentEntryCheckpoint {
        Objects.requireNonNull(runtimeState); Objects.requireNonNull(invocationId); Objects.requireNonNull(attemptId); Objects.requireNonNull(startedEventId);
    }
    /** Rejects replacing a committed original visit or causal event.
     * @param previous previous checkpoint, absent before SOURCE start
     */
    public void requireSuccessorOf(DeploymentEntryCheckpoint previous) {
        runtimeState.requireSuccessorOf(previous == null ? null : previous.runtimeState());
        if (previous != null && (!invocationId.equals(previous.invocationId()) || !attemptId.equals(previous.attemptId())
                || !startedEventId.equals(previous.startedEventId()) || previous.completedEventId() != null
                && !previous.completedEventId().equals(completedEventId)))
            throw new IllegalArgumentException("entry checkpoint cannot replace original causal identity");
    }
    /** Projects only the retained entry's original root SOURCE events in the same atomic batch.
     * @param entry retained original input owner
     * @param batch fenced lifecycle and event write
     * @param folded post-fold aggregate used to verify original visit ownership
     * @return original or monotonically advanced checkpoint; absent before SOURCE start
     */
    public static DeploymentEntryCheckpoint project(DeploymentEntryIntent entry, ExecutionBatch batch, ProcessInstance folded) {
        var checkpoint = entry.checkpoint();
        var traversal = folded.traversals().get(entry.traversalId());
        if (traversal == null || !traversal.ingressNodeId().equals(entry.entryNodeId()))
            throw new IllegalArgumentException("entry traversal ownership mismatch");
        for (var event : batch.events()) {
            if (!entry.traversalId().equals(event.traversalId()) || event.invocationId() == null) continue;
            var invocation = traversal.invocations().get(event.invocationId());
            if (invocation == null || !invocation.nodeId().equals(entry.entryNodeId()) || !invocation.parentInvocationIds().isEmpty()
                    || invocation.command().directive() == NodeDirective.APPLICATION) continue;
            if (event.eventType().equals("NODE_STARTED")) {
                var next = new DeploymentEntryCheckpoint(event.invocationId(), event.attemptId(), event.eventId(), null, batch.deploymentEntryRuntimeState().orElseThrow());
                next.requireSuccessorOf(checkpoint); checkpoint = next;
            } else if (event.eventType().equals("NODE_COMPLETED")) {
                if (checkpoint == null || !checkpoint.invocationId().equals(event.invocationId())
                        || !checkpoint.attemptId().equals(event.attemptId()) || !checkpoint.startedEventId().equals(event.causationId()))
                    throw new IllegalArgumentException("entry completion lacks its original start");
                var next = new DeploymentEntryCheckpoint(checkpoint.invocationId(), checkpoint.attemptId(), checkpoint.startedEventId(), event.eventId(), batch.deploymentEntryRuntimeState().orElseThrow());
                next.requireSuccessorOf(checkpoint); checkpoint = next;
            }
        }
        if (checkpoint != null && batch.deploymentEntryRuntimeState().isPresent()) {
            var updated = new DeploymentEntryCheckpoint(checkpoint.invocationId(), checkpoint.attemptId(), checkpoint.startedEventId(),
                    checkpoint.completedEventId(), batch.deploymentEntryRuntimeState().orElseThrow());
            updated.requireSuccessorOf(checkpoint); checkpoint = updated;
        }
        return checkpoint;
    }
}
