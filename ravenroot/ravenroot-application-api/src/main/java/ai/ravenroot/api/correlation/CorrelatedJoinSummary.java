package ai.ravenroot.api.correlation;
import java.time.Instant;
/** Small history projection; paging never loads retained contribution payloads.
 * @param expectation explicit expectation form; unannounced is not an inferred zero-work manifest
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
                                    long retainedPayloadBytes, Instant openedAt, Instant deadlineAt, Instant updatedAt, Expectation expectation) {
    /** Explicit expectation form without pretending an unannounced manifest is empty work. */
    public enum Expectation {
        /** Fixed distinct contribution count. */ COUNT,
        /** Explicit manifest, still provisional until sealed. */ MANIFEST,
        /** No count or manifest has been announced. */ UNANNOUNCED,
        /** An older projection did not retain the expectation form. */ UNKNOWN
    }
    /** Compatibility projection for producers that did not retain expectation form.
     * @param key exact business join key
     * @param revision retained compare-and-set revision
     * @param phase durable collection or release state
     * @param expectedContributions currently declared expectation count
     * @param receivedContributions distinct accepted contribution count
     * @param sealed whether the producer closed expectation announcements
     * @param retainedPayloadBytes retained contribution data bytes
     * @param openedAt original generation creation time
     * @param deadlineAt finite collection deadline
     * @param updatedAt latest retained generation change
     */
    public CorrelatedJoinSummary(CorrelatedJoinKey key, long revision, CorrelatedJoinRecord.Phase phase,
            int expectedContributions, int receivedContributions, boolean sealed, long retainedPayloadBytes,
            Instant openedAt, Instant deadlineAt, Instant updatedAt) {
        this(key, revision, phase, expectedContributions, receivedContributions, sealed, retainedPayloadBytes,
                openedAt, deadlineAt, updatedAt, Expectation.UNKNOWN);
    }
    /** Projects one immutable join checkpoint to bounded scalar observation metadata.
 * @param record durable join checkpoint being projected without exposing its contribution payloads
 * @return bounded immutable observation derived from the original causal record
 */
    public static CorrelatedJoinSummary of(CorrelatedJoinRecord record) {
        return new CorrelatedJoinSummary(record.key(), record.revision(), record.phase(),
                record.expectedCount() == null ? record.expectedIds().size() : record.expectedCount(),
                record.contributions().size(), record.sealed(), record.payloadBytes(), record.openedAt(),
                record.deadlineAt(), record.updatedAt(), record.expectedCount() != null ? Expectation.COUNT
                        : !record.expectedIds().isEmpty() || record.sealed() ? Expectation.MANIFEST : Expectation.UNANNOUNCED);
    }
}
