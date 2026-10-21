package ai.ravenroot.api.correlation;
import java.time.Instant;
/** Small history projection; paging never loads retained contribution payloads. */
public record CorrelatedJoinSummary(CorrelatedJoinKey key, long revision, CorrelatedJoinRecord.Phase phase,
                                    int expectedContributions, int receivedContributions, boolean sealed,
                                    long retainedPayloadBytes, Instant openedAt, Instant deadlineAt, Instant updatedAt) {
    public static CorrelatedJoinSummary of(CorrelatedJoinRecord record) {
        return new CorrelatedJoinSummary(record.key(), record.revision(), record.phase(),
                record.expectedCount() == null ? record.expectedIds().size() : record.expectedCount(),
                record.contributions().size(), record.sealed(), record.payloadBytes(), record.openedAt(),
                record.deadlineAt(), record.updatedAt());
    }
}
