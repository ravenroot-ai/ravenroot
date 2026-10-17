package ai.ravenroot.api.flow;

import java.util.Map;

/**
 * Terminal child outcome returned through the internal invocation capability.
 * @param status explicit terminal status
 * @param output completed child output, or {@code null}
 * @param code bounded failure code, or empty for completion
 * @param message bounded failure message, or empty for completion
 */
public record FlowInvocationResult(FlowInvocationStatus status, Object output, String code, String message) {
    /** Validates the terminal result shape. */
    public FlowInvocationResult {
        if (status == null || !status.terminal()) {
            throw new IllegalArgumentException("a flow result requires a terminal status");
        }
        code = code == null ? "" : bounded(code, 64, "code");
        message = message == null ? "" : bounded(message, 256, "message");
        if (status == FlowInvocationStatus.COMPLETED && (!code.isEmpty() || !message.isEmpty())) {
            throw new IllegalArgumentException("a completed flow result cannot carry a failure");
        }
        if (status != FlowInvocationStatus.COMPLETED && output != null) {
            throw new IllegalArgumentException("a failed flow result cannot carry output");
        }
    }

    /**
     * Creates a successful terminal result.
     * @param output bounded child output
     * @return completed invocation result
     */
    public static FlowInvocationResult completed(Object output) {
        return new FlowInvocationResult(FlowInvocationStatus.COMPLETED, output, "", "");
    }

    /**
     * Creates an explicit non-success terminal result.
     * @param status non-success terminal status
     * @param code bounded failure code
     * @param message bounded failure message
     * @return failed, cancelled, expired, orphaned, or ambiguous result
     */
    public static FlowInvocationResult failed(FlowInvocationStatus status, String code, String message) {
        return new FlowInvocationResult(status, null, code, message);
    }

    /**
     * Stable node payload for explicit non-success outcomes.
     * @return map containing status, code, and message
     */
    public Map<String, Object> failurePayload() {
        return Map.of("status", status.name(), "code", code, "message", message);
    }

    private static String bounded(String value, int maximum, String name) {
        String normalized = value.strip();
        if (normalized.length() > maximum || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is not a bounded display-safe value");
        }
        return normalized;
    }
}
