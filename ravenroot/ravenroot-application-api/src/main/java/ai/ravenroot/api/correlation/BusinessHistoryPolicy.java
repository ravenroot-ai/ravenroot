package ai.ravenroot.api.correlation;

import java.time.Duration;
import java.util.Objects;

/** Operator-owned storage/cursor budgets; graph content cannot raise them. */
public record BusinessHistoryPolicy(int maxHistoriesPerScope, int maxOpenJoinsPerHistory, int maxRetainedJoinsPerHistory,
                                    int maxContributionsPerJoin, long maxJoinPayloadBytes,
                                    int maxPageSize, Duration historyRetention, Duration joinDeadline,
                                    CorrelationLimits selection) {
    /** Finite shipped policy; compact identity tombstones remain within the total scope cap. */
    public static final BusinessHistoryPolicy DEFAULTS = new BusinessHistoryPolicy(100_000, 64, 1024, 1024,
            16L * 1024 * 1024, 100, Duration.ofDays(30), Duration.ofHours(24), CorrelationLimits.DEFAULTS);
    /** Compatibility policy with an explicit finite ceiling for retained join tombstones. */
    public BusinessHistoryPolicy(int maxHistoriesPerScope, int maxOpenJoinsPerHistory,
            int maxContributionsPerJoin, long maxJoinPayloadBytes, int maxPageSize,
            Duration historyRetention, Duration joinDeadline, CorrelationLimits selection) {
        this(maxHistoriesPerScope, maxOpenJoinsPerHistory, Math.max(maxOpenJoinsPerHistory, 1024),
                maxContributionsPerJoin, maxJoinPayloadBytes, maxPageSize, historyRetention, joinDeadline, selection);
    }
    /** Rejects unlimited retention, groups, pages and payload storage. */
    public BusinessHistoryPolicy {
        if (maxHistoriesPerScope < 1 || maxHistoriesPerScope > 10_000_000 || maxOpenJoinsPerHistory < 1
                || maxOpenJoinsPerHistory > 1024 || maxRetainedJoinsPerHistory < maxOpenJoinsPerHistory
                || maxRetainedJoinsPerHistory > 100_000 || maxContributionsPerJoin < 1 || maxContributionsPerJoin > 10_000
                || maxJoinPayloadBytes < 1 || maxJoinPayloadBytes > 48L * 1024 * 1024
                || maxPageSize < 1 || maxPageSize > 1000) throw new IllegalArgumentException("invalid history resource bounds");
        Objects.requireNonNull(selection, "selection");
        if (historyRetention == null || historyRetention.isNegative() || historyRetention.isZero()
                || historyRetention.compareTo(Duration.ofDays(3650)) > 0 || joinDeadline == null
                || joinDeadline.isNegative() || joinDeadline.isZero()
                || joinDeadline.compareTo(historyRetention) > 0) throw new IllegalArgumentException("invalid history deadlines");
    }
}
