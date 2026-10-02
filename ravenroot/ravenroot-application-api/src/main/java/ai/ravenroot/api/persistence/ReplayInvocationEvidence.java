package ai.ravenroot.api.persistence;

import ai.ravenroot.api.execution.NodeCommand;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Retained, bounded evidence of one completed invocation's routed value.
 *
 * <p>This is deliberately separate from the event journal. Node payloads may contain application
 * data and therefore never become diagnostic event bodies merely to make replay possible. Stores
 * retain this value under the source process's tenant and retention boundary.</p>
 * @param source exact source execution
 * @param traversalId source traversal
 * @param invocationId completed source invocation
 * @param attemptId completed source attempt
 * @param nodeId source node
 * @param parentInvocationIds exact causal parents
 * @param command completed command
 * @param outcome selected route outcome
 * @param iteration iteration identity, empty for supported acyclic evidence
 * @param output retained routed output
 * @param attributes retained routed attributes
 * @param recordedAt capture time
 * @param retainedUntil store-authoritative expiry
 */
public record ReplayInvocationEvidence(
        ExecutionKey source,
        UUID traversalId,
        UUID invocationId,
        UUID attemptId,
        String nodeId,
        Set<UUID> parentInvocationIds,
        NodeCommand command,
        String outcome,
        Map<String, Integer> iteration,
        OpaquePayload output,
        OpaquePayload attributes,
        Instant recordedAt,
        Instant retainedUntil) {

    /** Validates and defensively copies the retained evidence. */
    public ReplayInvocationEvidence {
        if (source == null || traversalId == null || invocationId == null || attemptId == null) {
            throw new IllegalArgumentException("replay evidence identities cannot be null");
        }
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("nodeId cannot be blank");
        if (outcome == null || outcome.isBlank()) throw new IllegalArgumentException("outcome cannot be blank");
        parentInvocationIds = Set.copyOf(parentInvocationIds == null ? Set.of() : parentInvocationIds);
        if (parentInvocationIds.contains(invocationId)) {
            throw new IllegalArgumentException("an invocation cannot be its own parent");
        }
        command = command == null ? NodeCommand.PROCESS : command;
        iteration = Map.copyOf(iteration == null ? Map.of() : iteration);
        if (iteration.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue() < 0)) {
            throw new IllegalArgumentException("iteration identities must be non-blank with non-negative laps");
        }
        if (output == null || attributes == null || recordedAt == null || retainedUntil == null) {
            throw new IllegalArgumentException("replay evidence payloads and time cannot be null");
        }
        if (!retainedUntil.isAfter(recordedAt)) {
            throw new IllegalArgumentException("replay evidence retention must follow capture");
        }
    }
}
