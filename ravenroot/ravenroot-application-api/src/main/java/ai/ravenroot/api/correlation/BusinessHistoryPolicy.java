package ai.ravenroot.api.correlation;

import java.time.Duration;
import java.util.Objects;

/** Operator-owned storage/cursor budgets; graph content cannot raise them.
 * @param maxHistoriesPerScope finite ceiling for retained business identities, including closure tombstones
 * @param maxOpenJoinsPerHistory finite ceiling for simultaneously open join generations in one history
 * @param maxRetainedJoinsPerHistory finite ceiling for all retained join generations, including terminal summaries
 * @param maxContributionsPerJoin finite ceiling for distinct contribution identities in one join generation
 * @param maxJoinPayloadBytes finite byte ceiling for retained contribution data in one join generation
 * @param maxPageSize largest permitted requested metadata page; each store applies it before projection
 * @param historyRetention finite history retention window; expiry preserves observable closure metadata
 * @param joinDeadline finite collection window for a join, no longer than history retention
 * @param selection operator-owned pointer, identifier and manifest extraction bounds
 * @param maxJoinCasAttempts maximum revision-conflict retries within one bounded contribution admission
 * @param readTimeout total wait budget for one SDK history operation
 * @param response operator-owned encoded-size and tree bounds for public response projection
 * @param maxPendingEntriesPerScope finite count ceiling for retained source and derivation input custody
 * @param maxPendingEntryBytesPerScope finite total retained entry-input byte ceiling in one scope
 */
public record BusinessHistoryPolicy(int maxHistoriesPerScope, int maxOpenJoinsPerHistory, int maxRetainedJoinsPerHistory,
                                    int maxContributionsPerJoin, long maxJoinPayloadBytes,
                                    int maxPageSize, Duration historyRetention, Duration joinDeadline,
                                    CorrelationLimits selection, int maxJoinCasAttempts, Duration readTimeout, ai.ravenroot.api.payload.PayloadLimits response,
                                    int maxPendingEntriesPerScope, long maxPendingEntryBytesPerScope) {
    /** Compatibility constructor retaining finite input-custody quotas.
     * @param maxHistoriesPerScope retained business identity ceiling
     * @param maxOpenJoinsPerHistory open correlated-join ceiling per business history
     * @param maxRetainedJoinsPerHistory retained join-summary ceiling per history
     * @param maxContributionsPerJoin distinct contribution ceiling per correlated join
     * @param maxJoinPayloadBytes retained contribution byte ceiling per join
     * @param maxPageSize maximum requested metadata page size
     * @param historyRetention finite business history retention window
     * @param joinDeadline finite correlated collection deadline
     * @param selection operator-owned pointer, identity and input bounds
     * @param maxJoinCasAttempts finite conflict retry ceiling
     * @param readTimeout finite metadata read wait budget
     * @param response finite metadata response encoding/tree bounds
     */
    public BusinessHistoryPolicy(int maxHistoriesPerScope, int maxOpenJoinsPerHistory, int maxRetainedJoinsPerHistory,
            int maxContributionsPerJoin, long maxJoinPayloadBytes, int maxPageSize, Duration historyRetention,
            Duration joinDeadline, CorrelationLimits selection, int maxJoinCasAttempts, Duration readTimeout,
            ai.ravenroot.api.payload.PayloadLimits response) {
        this(maxHistoriesPerScope, maxOpenJoinsPerHistory, maxRetainedJoinsPerHistory, maxContributionsPerJoin,
                maxJoinPayloadBytes, maxPageSize, historyRetention, joinDeadline, selection, maxJoinCasAttempts,
                readTimeout, response, 1024, 16L * 1024 * 1024);
    }
    /** Default response tree/encoding ceiling, independent from workflow input payload budgets. */
    public static final ai.ravenroot.api.payload.PayloadLimits DEFAULT_RESPONSE_LIMITS =
            new ai.ravenroot.api.payload.PayloadLimits(4 * 1024 * 1024, 64, 2000, 100_000, 262144, 256);
    /** Compatibility constructor retaining a finite metadata response projection.
 * @param maxHistoriesPerScope finite ceiling for retained business identities, including closure tombstones
 * @param maxOpenJoinsPerHistory finite ceiling for simultaneously open join generations in one history
 * @param maxRetainedJoinsPerHistory finite ceiling for all retained join generations, including terminal summaries
 * @param maxContributionsPerJoin finite ceiling for distinct contribution identities in one join generation
 * @param maxJoinPayloadBytes finite byte ceiling for retained contribution data in one join generation
 * @param maxPageSize largest permitted requested metadata page; each store applies it before projection
 * @param historyRetention finite history retention window; expiry preserves observable closure metadata
 * @param joinDeadline finite collection window for a join, no longer than history retention
 * @param selection operator-owned pointer, identifier and manifest extraction bounds
 * @param maxJoinCasAttempts maximum revision-conflict retries within one bounded contribution admission
 * @param readTimeout total wait budget for one SDK history operation
 */
    public BusinessHistoryPolicy(int maxHistoriesPerScope, int maxOpenJoinsPerHistory, int maxRetainedJoinsPerHistory,
            int maxContributionsPerJoin, long maxJoinPayloadBytes, int maxPageSize,
            Duration historyRetention, Duration joinDeadline, CorrelationLimits selection, int maxJoinCasAttempts, Duration readTimeout) {
        this(maxHistoriesPerScope, maxOpenJoinsPerHistory, maxRetainedJoinsPerHistory, maxContributionsPerJoin,
                maxJoinPayloadBytes, maxPageSize, historyRetention, joinDeadline, selection, maxJoinCasAttempts, readTimeout, DEFAULT_RESPONSE_LIMITS);
    }
    /** Finite shipped policy; compact identity tombstones remain within the total scope cap. */
    public static final BusinessHistoryPolicy DEFAULTS = new BusinessHistoryPolicy(100_000, 64, 1024, 1024,
            16L * 1024 * 1024, 100, Duration.ofDays(30), Duration.ofHours(24), CorrelationLimits.DEFAULTS, 128);
    /** Compatibility constructor with a finite, operator-owned metadata read timeout.
 * @param maxHistoriesPerScope finite ceiling for retained business identities, including closure tombstones
 * @param maxOpenJoinsPerHistory finite ceiling for simultaneously open join generations in one history
 * @param maxRetainedJoinsPerHistory finite ceiling for all retained join generations, including terminal summaries
 * @param maxContributionsPerJoin finite ceiling for distinct contribution identities in one join generation
 * @param maxJoinPayloadBytes finite byte ceiling for retained contribution data in one join generation
 * @param maxPageSize largest permitted requested metadata page; each store applies it before projection
 * @param historyRetention finite history retention window; expiry preserves observable closure metadata
 * @param joinDeadline finite collection window for a join, no longer than history retention
 * @param selection operator-owned pointer, identifier and manifest extraction bounds
 * @param maxJoinCasAttempts maximum revision-conflict retries within one bounded contribution admission
 */
    public BusinessHistoryPolicy(int maxHistoriesPerScope, int maxOpenJoinsPerHistory, int maxRetainedJoinsPerHistory,
            int maxContributionsPerJoin, long maxJoinPayloadBytes, int maxPageSize,
            Duration historyRetention, Duration joinDeadline, CorrelationLimits selection, int maxJoinCasAttempts) {
        this(maxHistoriesPerScope, maxOpenJoinsPerHistory, maxRetainedJoinsPerHistory, maxContributionsPerJoin,
                maxJoinPayloadBytes, maxPageSize, historyRetention, joinDeadline, selection, maxJoinCasAttempts, Duration.ofSeconds(10));
    }
    /** Compatibility constructor retaining the finite conflict-retry budget.
 * @param maxHistoriesPerScope finite ceiling for retained business identities, including closure tombstones
 * @param maxOpenJoinsPerHistory finite ceiling for simultaneously open join generations in one history
 * @param maxRetainedJoinsPerHistory finite ceiling for all retained join generations, including terminal summaries
 * @param maxContributionsPerJoin finite ceiling for distinct contribution identities in one join generation
 * @param maxJoinPayloadBytes finite byte ceiling for retained contribution data in one join generation
 * @param maxPageSize largest permitted requested metadata page; each store applies it before projection
 * @param historyRetention finite history retention window; expiry preserves observable closure metadata
 * @param joinDeadline finite collection window for a join, no longer than history retention
 * @param selection operator-owned pointer, identifier and manifest extraction bounds
 */
    public BusinessHistoryPolicy(int maxHistoriesPerScope, int maxOpenJoinsPerHistory, int maxRetainedJoinsPerHistory,
            int maxContributionsPerJoin, long maxJoinPayloadBytes, int maxPageSize,
            Duration historyRetention, Duration joinDeadline, CorrelationLimits selection) {
        this(maxHistoriesPerScope, maxOpenJoinsPerHistory, maxRetainedJoinsPerHistory, maxContributionsPerJoin,
                maxJoinPayloadBytes, maxPageSize, historyRetention, joinDeadline, selection, 128);
    }
    /** Compatibility policy with an explicit finite ceiling for retained join tombstones.
 * @param maxHistoriesPerScope finite ceiling for retained business identities, including closure tombstones
 * @param maxOpenJoinsPerHistory finite ceiling for simultaneously open join generations in one history
 * @param maxContributionsPerJoin finite ceiling for distinct contribution identities in one join generation
 * @param maxJoinPayloadBytes finite byte ceiling for retained contribution data in one join generation
 * @param maxPageSize largest permitted requested metadata page; each store applies it before projection
 * @param historyRetention finite history retention window; expiry preserves observable closure metadata
 * @param joinDeadline finite collection window for a join, no longer than history retention
 * @param selection operator-owned pointer, identifier and manifest extraction bounds
 */
    public BusinessHistoryPolicy(int maxHistoriesPerScope, int maxOpenJoinsPerHistory,
            int maxContributionsPerJoin, long maxJoinPayloadBytes, int maxPageSize,
            Duration historyRetention, Duration joinDeadline, CorrelationLimits selection) {
        this(maxHistoriesPerScope, maxOpenJoinsPerHistory, Math.max(maxOpenJoinsPerHistory, 1024),
                maxContributionsPerJoin, maxJoinPayloadBytes, maxPageSize, historyRetention, joinDeadline, selection);
    }
    /** Rejects unlimited retention, groups, pages and payload storage. */
    public BusinessHistoryPolicy {
        if (maxPendingEntriesPerScope < 1 || maxPendingEntriesPerScope > 100_000
                || maxPendingEntryBytesPerScope < 1 || maxPendingEntryBytesPerScope > 64L * 1024 * 1024)
            throw new IllegalArgumentException("invalid deployment entry custody quotas");
        if (maxJoinCasAttempts < 1 || maxJoinCasAttempts > 10000 || maxHistoriesPerScope < 1 || maxHistoriesPerScope > 10_000_000 || maxOpenJoinsPerHistory < 1
                || maxOpenJoinsPerHistory > 1024 || maxRetainedJoinsPerHistory < maxOpenJoinsPerHistory
                || maxRetainedJoinsPerHistory > 100_000 || maxContributionsPerJoin < 1 || maxContributionsPerJoin > 10_000
                || maxJoinPayloadBytes < 1 || maxJoinPayloadBytes > 48L * 1024 * 1024
                || maxPageSize < 1 || maxPageSize > 1000) throw new IllegalArgumentException("invalid history resource bounds");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(response, "response");
        if (response.maxEncodedBytes() > 16 * 1024 * 1024 || response.maxValueCount() > 1_000_000
                || response.maxValueCount() < maxPageSize * 32 + 64 || response.maxCollectionSize() < maxPageSize)
            throw new IllegalArgumentException("invalid history response projection bounds");
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()
                || readTimeout.compareTo(Duration.ofMinutes(1)) > 0)
            throw new IllegalArgumentException("invalid history read timeout");
        if (historyRetention == null || historyRetention.isNegative() || historyRetention.isZero()
                || historyRetention.compareTo(Duration.ofDays(3650)) > 0 || joinDeadline == null
                || joinDeadline.isNegative() || joinDeadline.isZero()
                || joinDeadline.compareTo(historyRetention) > 0) throw new IllegalArgumentException("invalid history deadlines");
    }
}
