package ai.ravenroot.api.persistence;

import java.time.Instant;
import java.util.Objects;

/** Durable delivery and lease state for one saga application command.
 * @param key owning execution stream
 * @param intent immutable application command
 * @param status current delivery stage or ownership state
 * @param attempts delivery claims consumed
 * @param owner current claim owner, or null
 * @param fencingToken monotonic claim fence
 * @param leaseExpiresAt current claim expiry, or null
 * @param nextAttemptAt earliest next claim instant
 * @param lastFailure redacted failure reason
 * @param createdAt intent creation instant
 * @param brokerAcceptedAt broker confirmation instant, or null
 * @param businessCompletedAt participant completion instant, or null
 */
public record SagaOutboxRecord(ExecutionKey key, SagaCommandIntent intent, SagaOutboxStatus status,
                               int attempts, String owner, long fencingToken, Instant leaseExpiresAt,
                               Instant nextAttemptAt, String lastFailure, Instant createdAt,
                               Instant brokerAcceptedAt, Instant businessCompletedAt) {
    /** Validates and defensively snapshots the durable value. */
    public SagaOutboxRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(status, "status");
        if (attempts < 0 || attempts > intent.maxAttempts()) throw new IllegalArgumentException("invalid attempts");
        if ((status == SagaOutboxStatus.CLAIMED) != (owner != null && !owner.isBlank())) {
            throw new IllegalArgumentException("only a claimed outbox record has an owner");
        }
        if (fencingToken < 0) throw new IllegalArgumentException("negative outbox fence");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        Objects.requireNonNull(createdAt, "createdAt");
        lastFailure = lastFailure == null ? "" : lastFailure;
        if (lastFailure.length() > 512) throw new IllegalArgumentException("outbox failure detail is too long");
    }
}
