package ai.ravenroot.api.persistence;

import ai.ravenroot.api.security.SecurityContext;
import java.time.Instant;
import java.util.Set;

/**
 * Immutable operator authority from which every agent grant in one process is attenuated.
 *
 * @param runtimeInstanceId stable runtime control-domain identifier
 * @param bootEpoch diagnostic runtime boot generation
 * @param security trusted tenant and principal identity
 * @param policyVersion pinned authority-policy version
 * @param rateCardVersion pinned economic rate-card version
 * @param absoluteDeadline absolute root deadline
 * @param dataScopes bounded operator-defined data scopes
 * @param authorityScopes bounded effect authority scopes
 * @param maxima finite root resource maxima
 * @param currency uppercase three-letter currency for monetary micros
 */
public record AgentAuthorityRootRegistration(String runtimeInstanceId, long bootEpoch,
                                              SecurityContext security, String policyVersion,
                                              String rateCardVersion, Instant absoluteDeadline,
                                              Set<String> dataScopes, Set<String> authorityScopes,
                                              AgentBudgetVector maxima, String currency) {
    /** Validates and snapshots all mutable root inputs. */
    public AgentAuthorityRootRegistration {
        runtimeInstanceId = token(runtimeInstanceId, "runtimeInstanceId", 128);
        if (bootEpoch < 0) throw new IllegalArgumentException("bootEpoch cannot be negative");
        if (security == null) throw new IllegalArgumentException("security is required");
        policyVersion = token(policyVersion, "policyVersion", 128);
        rateCardVersion = token(rateCardVersion, "rateCardVersion", 128);
        if (absoluteDeadline == null) throw new IllegalArgumentException("absoluteDeadline is required");
        dataScopes = scopeTokens(dataScopes, "dataScopes");
        authorityScopes = scopeTokens(authorityScopes, "authorityScopes");
        if (maxima == null) throw new IllegalArgumentException("maxima is required");
        currency = token(currency, "currency", 3);
        if (!currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("currency must be ISO-style uppercase");
    }

    private static Set<String> scopeTokens(Set<String> source, String name) {
        Set<String> copy = Set.copyOf(source == null ? Set.of() : source);
        if (copy.size() > 256 || copy.stream().anyMatch(value -> value == null || value.isBlank()
                || value.length() > 256 || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*"))) {
            throw new IllegalArgumentException(name + " contains an invalid scope token");
        }
        return copy;
    }

    static String token(String value, String name, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException(name + " is not a bounded token");
        }
        return value;
    }
}
