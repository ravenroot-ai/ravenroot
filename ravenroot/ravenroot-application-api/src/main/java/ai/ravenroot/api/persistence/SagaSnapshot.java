package ai.ravenroot.api.persistence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Complete bounded saga aggregate persisted under an execution's transaction and fence.
 * @param key owning execution stream
 * @param sagaId tenant-scoped saga identity
 * @param traversalId traversal whose terminal result is gated by this saga
 * @param definition frozen recovery definition
 * @param revision saga compare-and-set revision
 * @param disposition current aggregate disposition
 * @param cancellationRequested whether cancellation was durably requested
 * @param occurrences logical occurrences keyed by identity
 * @param deadline optional saga deadline
 * @param createdAt creation instant
 * @param updatedAt last durable transition instant
 * @param actionableReason redacted operator-facing unresolved reason
 * @param graphCompleted whether the graph has reached its terminal boundary and now waits only for
 *                       participant/outbox completion
 */
public record SagaSnapshot(ExecutionKey key, UUID sagaId, UUID traversalId,
                           SagaDefinition definition, long revision,
                           SagaDisposition disposition, boolean cancellationRequested,
                           Map<UUID, SagaStepSnapshot> occurrences, Instant deadline,
                           Instant createdAt, Instant updatedAt, String actionableReason,
                           boolean graphCompleted) {
    /** Validates and defensively snapshots the durable value. */
    public SagaSnapshot {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(sagaId, "sagaId");
        Objects.requireNonNull(traversalId, "traversalId");
        Objects.requireNonNull(definition, "definition");
        if (revision < 1) throw new IllegalArgumentException("saga revision must be positive");
        Objects.requireNonNull(disposition, "disposition");
        var copy = new LinkedHashMap<UUID, SagaStepSnapshot>();
        if (occurrences != null) occurrences.forEach((id, value) -> {
            if (id == null || value == null || !id.equals(value.occurrenceId()) || copy.putIfAbsent(id, value) != null) {
                throw new IllegalArgumentException("invalid or duplicate saga occurrence");
            }
            if (!definition.steps().containsKey(value.stepId())) {
                throw new IllegalArgumentException("saga occurrence references an unknown step");
            }
        });
        if (copy.size() > 4096) throw new IllegalArgumentException("saga occurrence limit exceeded");
        occurrences = Map.copyOf(copy);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (deadline != null && deadline.isBefore(createdAt)) throw new IllegalArgumentException("deadline precedes creation");
        actionableReason = actionableReason == null ? "" : actionableReason;
        if (actionableReason.length() > 512) throw new IllegalArgumentException("actionable reason is too long");
    }
}
