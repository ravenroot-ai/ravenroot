package ai.ravenroot.api.correlation;
import java.time.Instant;
/** Small history projection; paging never loads retained contribution payloads.
 * @param key exact scoped business join identity, including its node and round
 * @param revision monotone compare-and-set revision of the retained record
 * @param phase durable join collection or continuation-release state
 * @param expectedContributions number of currently declared distinct expected contributions
 * @param receivedContributions number of distinct accepted contributions; duplicate delivery does not increment it
 * @param sealed whether the producer has explicitly closed its expectation announcement
 * @param retainedPayloadBytes bytes of retained contribution content, excluding metadata
 * @param openedAt time this join generation was first created
 * @param deadlineAt deadline after which incomplete collection cannot release work
 * @param updatedAt time of the latest recorded change; pagination does not depend on this value
 */
public record CorrelatedJoinSummary(CorrelatedJoinKey key, long revision, CorrelatedJoinRecord.Phase phase,
                                    int expectedContributions, int receivedContributions, boolean sealed,
                                    long retainedPayloadBytes, Instant openedAt, Instant deadlineAt, Instant updatedAt) {
    /** Projects one immutable join checkpoint to bounded scalar observation metadata.
 * @param record durable join checkpoint being projected without exposing its contribution payloads
 * @return bounded immutable observation derived from the original causal record
 */
    public static CorrelatedJoinSummary of(CorrelatedJoinRecord record) {
        return new CorrelatedJoinSummary(record.key(), record.revision(), record.phase(),
                record.expectedCount() == null ? record.expectedIds().size() : record.expectedCount(),
                record.contributions().size(), record.sealed(), record.payloadBytes(), record.openedAt(),
                record.deadlineAt(), record.updatedAt());
    }
}
