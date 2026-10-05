package ai.ravenroot.api.persistence;

import java.util.Objects;
import java.util.UUID;

/** Compare-and-set replacement of one saga aggregate inside an {@link ExecutionBatch}.
 * @param mutationId idempotent saga mutation identity
 * @param expectedRevision zero for create or exact current saga revision
 * @param snapshot complete replacement snapshot
 */
public record SagaWrite(UUID mutationId, long expectedRevision, SagaSnapshot snapshot) {
    /** Validates and defensively snapshots the durable value. */
    public SagaWrite {
        Objects.requireNonNull(mutationId, "mutationId");
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision cannot be negative");
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.revision() != expectedRevision + 1) {
            throw new IllegalArgumentException("saga revision must advance by exactly one");
        }
    }
}
