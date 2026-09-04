package ai.ravenroot.core.security.nodepackage;

import ai.ravenroot.api.persistence.AgentBudgetVector;

import java.time.Duration;
import java.util.Set;

/** Finite operator policy and pinned model rate card for one packaged runtime instance. */
public record AgentAuthorityBudgetPolicy(String runtimeInstanceId, long bootEpoch,
                                         String policyVersion, String rateCardVersion,
                                         String currency, Duration rootLifetime,
                                         AgentBudgetVector rootMaxima,
                                         long maximumInputTokensPerTurn,
                                         long maximumOutputTokensPerTurn,
                                         long inputTokenRateMicros,
                                         long outputTokenRateMicros,
                                         Set<String> dataScopes,
                                         Set<String> authorityScopes) {
    public AgentAuthorityBudgetPolicy {
        if (runtimeInstanceId == null || runtimeInstanceId.isBlank() || bootEpoch < 0
                || policyVersion == null || policyVersion.isBlank()
                || rateCardVersion == null || rateCardVersion.isBlank()
                || currency == null || !currency.matches("[A-Z]{3}")
                || rootLifetime == null || rootLifetime.isZero() || rootLifetime.isNegative()
                || rootMaxima == null || maximumInputTokensPerTurn <= 0
                || maximumOutputTokensPerTurn <= 0 || inputTokenRateMicros < 0
                || outputTokenRateMicros < 0) {
            throw new IllegalArgumentException("agent authority budget policy is invalid");
        }
        dataScopes = Set.copyOf(dataScopes == null ? Set.of() : dataScopes);
        authorityScopes = Set.copyOf(authorityScopes == null ? Set.of() : authorityScopes);
    }
}
