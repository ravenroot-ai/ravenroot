package ai.ravenroot.api.node.service;

import java.time.Duration;

/**
 * Author-requested bounds which the trusted runtime may only tighten.
 *
 * @param maximumTurns maximum number of model-turn proposals
 * @param maximumTotalTokens combined input-and-output token ceiling
 * @param maximumOutputTokensPerTurn maximum output tokens for one model dispatch
 * @param maximumDuration absolute invocation duration bound relative to admission
 */
public record AgentResourceRequest(long maximumTurns, long maximumTotalTokens,
                                   long maximumOutputTokensPerTurn, Duration maximumDuration) {
    /** Validates the finite requested bounds. */
    public AgentResourceRequest {
        if (maximumTurns <= 0 || maximumTotalTokens < 0 || maximumOutputTokensPerTurn < 0) {
            throw new IllegalArgumentException("agent resource limits are invalid");
        }
        if (maximumDuration == null || maximumDuration.isZero() || maximumDuration.isNegative()) {
            throw new IllegalArgumentException("agent duration must be positive");
        }
    }
}
