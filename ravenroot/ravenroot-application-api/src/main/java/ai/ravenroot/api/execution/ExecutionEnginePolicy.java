package ai.ravenroot.api.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
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
     * default changes. Its history capacity aliases the public terminal-history default that
     * originally supplied that value.</p>
     */
    public static final ExecutionEnginePolicy FROZEN_LEGACY =
            new ExecutionEnginePolicy(
                    10_000, Duration.ofSeconds(10), TerminalNodeHistory.DEFAULT_CAPACITY);

    private static final String FINGERPRINT_DOMAIN = "ravenroot.execution-engine-policy";
    private static final String FINGERPRINT_VERSION = "1";

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

    /**
     * Returns the stable compatibility fingerprint for the policy fields that affect execution.
     *
     * <p>The terminal history capacity is deliberately excluded: it changes only how long a caller
     * can inspect an already terminated node. The historical stash and lifecycle values retain the
     * empty fingerprint even when history capacity differs, preserving existing manifest bytes.</p>
     *
     * @return empty for historical execution semantics, otherwise lowercase SHA-256 hexadecimal
     */
    public String compatibilityFingerprint() {
        if (maxStashedCommandsPerNode == FROZEN_LEGACY.maxStashedCommandsPerNode
                && lifecycleStepBound.equals(FROZEN_LEGACY.lifecycleStepBound)) {
            return "";
        }
        String canonical = String.join("\0",
                FINGERPRINT_DOMAIN,
                FINGERPRINT_VERSION,
                "maxStashedCommandsPerNode", Integer.toString(maxStashedCommandsPerNode),
                "lifecycleStepBoundSeconds", Long.toString(lifecycleStepBound.getSeconds()),
                "lifecycleStepBoundNanos", Integer.toString(lifecycleStepBound.getNano()),
                "");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
