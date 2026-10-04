package ai.ravenroot.core.flow;

import java.time.Duration;

/** Runtime-owned bounds for intergraph work. */
public record FlowInvocationPolicy(int maximumUnfinishedPerTenant, Duration maximumDeadline,
                                   Duration retention) {
    public static final FlowInvocationPolicy DEFAULTS =
            new FlowInvocationPolicy(1_000, Duration.ofHours(24), Duration.ofDays(7));

    public FlowInvocationPolicy {
        if (maximumUnfinishedPerTenant < 1 || maximumUnfinishedPerTenant > 100_000
                || maximumDeadline == null || maximumDeadline.isZero() || maximumDeadline.isNegative()
                || maximumDeadline.compareTo(Duration.ofDays(7)) > 0
                || retention == null || retention.isZero() || retention.isNegative()
                || retention.compareTo(Duration.ofDays(90)) > 0) {
            throw new IllegalArgumentException("invalid flow invocation policy");
        }
    }
}
