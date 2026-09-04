package ai.ravenroot.api.node.service;

import java.time.Duration;

/** Author-requested bounds which the trusted runtime may only tighten. */
public record AgentResourceRequest(long maximumTurns, long maximumTotalTokens,
                                   long maximumOutputTokensPerTurn, Duration maximumDuration) {
    public AgentResourceRequest {
        if (maximumTurns <= 0 || maximumTotalTokens < 0 || maximumOutputTokensPerTurn < 0) {
            throw new IllegalArgumentException("agent resource limits are invalid");
        }
        if (maximumDuration == null || maximumDuration.isZero() || maximumDuration.isNegative()) {
            throw new IllegalArgumentException("agent duration must be positive");
        }
    }
}
