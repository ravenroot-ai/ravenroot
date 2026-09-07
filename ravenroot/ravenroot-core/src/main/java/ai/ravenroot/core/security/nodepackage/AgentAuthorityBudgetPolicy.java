package ai.ravenroot.core.security.nodepackage;

import ai.ravenroot.api.persistence.AgentBudgetVector;

import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

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
    private static final Pattern IDENTITY_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]*");
    private static final Pattern SCOPE_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]*");
    private static final int MAX_IDENTITY_LENGTH = 128;
    private static final int MAX_SCOPE_LENGTH = 256;
    private static final int MAX_SCOPES = 256;

    public AgentAuthorityBudgetPolicy {
        identity("runtimeInstanceId", runtimeInstanceId);
        identity("policyVersion", policyVersion);
        identity("rateCardVersion", rateCardVersion);
        if (bootEpoch < 0 || currency == null || !currency.matches("[A-Z]{3}")
                || rootLifetime == null || rootLifetime.isZero() || rootLifetime.isNegative()
                || rootMaxima == null || maximumInputTokensPerTurn <= 0
                || maximumOutputTokensPerTurn <= 0 || inputTokenRateMicros < 0
                || outputTokenRateMicros < 0) {
            throw new IllegalArgumentException("agent authority budget policy is invalid");
        }
        dataScopes = scopes("dataScopes", dataScopes);
        authorityScopes = scopes("authorityScopes", authorityScopes);
    }

    private static void identity(String name, String value) {
        if (value == null || value.length() > MAX_IDENTITY_LENGTH || !IDENTITY_TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be an identity token of 1..128 characters");
        }
    }

    private static Set<String> scopes(String name, Set<String> values) {
        if (values == null) return Set.of();
        if (values.size() > MAX_SCOPES) {
            throw new IllegalArgumentException(name + " must contain at most 256 unique scope tokens");
        }
        for (String value : values) {
            if (value == null || value.length() > MAX_SCOPE_LENGTH || !SCOPE_TOKEN.matcher(value).matches()) {
                throw new IllegalArgumentException(name + " contains an invalid scope token");
            }
        }
        return Set.copyOf(values);
    }
}
