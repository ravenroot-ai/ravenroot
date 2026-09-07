package ai.ravenroot.api.execution;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable operational bounds held for one execution-engine lifetime.
 *
 * <p>The Java contract accepts every positive value. Operator-facing configuration may impose
 * narrower deployment ceilings without turning those ceilings into API restrictions.</p>
 *
 * @param maxStashedCommandsPerNode maximum commands one node may retain while its actor is busy
 * @param lifecycleStepBound maximum wait for one engine lifecycle step
 * @param terminalNodeHistoryCapacity maximum terminal node entries retained by one engine
 */
public record ExecutionEnginePolicy(
        int maxStashedCommandsPerNode,
        Duration lifecycleStepBound,
        int terminalNodeHistoryCapacity) {

    /**
     * Values used by every engine before typed policy injection existed.
     *
     * <p>This constant is a compatibility anchor. It must remain frozen even if a future operator
     * default changes.</p>
     */
    public static final ExecutionEnginePolicy FROZEN_LEGACY =
            new ExecutionEnginePolicy(10_000, Duration.ofSeconds(10), 1_024);

    /** Requires positive capacities and a positive lifecycle bound. */
    public ExecutionEnginePolicy {
        if (maxStashedCommandsPerNode < 1) {
            throw new IllegalArgumentException("maxStashedCommandsPerNode must be positive");
        }
        Objects.requireNonNull(lifecycleStepBound, "lifecycleStepBound");
        if (lifecycleStepBound.isZero() || lifecycleStepBound.isNegative()) {
            throw new IllegalArgumentException("lifecycleStepBound must be positive");
        }
        if (terminalNodeHistoryCapacity < 1) {
            throw new IllegalArgumentException("terminalNodeHistoryCapacity must be positive");
        }
    }
}
