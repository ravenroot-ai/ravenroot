package ai.ravenroot.api.security;

/**
 * Policy decision returned before a tool invocation may proceed.
 * @param disposition allow, deny, or require an external approval
 * @param reason safe-to-disclose decision explanation
 * @param approvalId approval reference when approval is required
 */
public record ToolDecision(Disposition disposition, String reason, String approvalId) {
/**
 * Normalizes omitted disposition to deny, preserving fail-closed authorization.
 */
    public ToolDecision {
        disposition = disposition == null ? Disposition.DENY : disposition;
        reason = reason == null ? "" : reason;
        approvalId = approvalId == null ? "" : approvalId;
    }

/**
 * Possible authorization dispositions for a tool request.
 */
    public enum Disposition {
/** Tool may execute under the evaluated policy. */
        ALLOW,
/** Tool must not execute. */
        DENY,
/** Tool requires a separately recorded approval before execution. */
        REQUIRE_APPROVAL
    }
}
