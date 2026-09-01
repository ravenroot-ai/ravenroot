package ai.ravenroot.api.security;

/**
 * Auditable result of one authorization decision.
 * @param allowed whether policy permitted the requested action
 * @param reason safe-to-disclose decision reason; normalized to an empty string when absent
 */
public record AuthorizationDecision(boolean allowed, String reason) {
/**
 * Normalizes a missing reason without changing the permit/deny result.
 */
    public AuthorizationDecision {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
