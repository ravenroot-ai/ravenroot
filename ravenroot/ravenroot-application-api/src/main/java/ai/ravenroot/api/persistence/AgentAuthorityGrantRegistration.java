package ai.ravenroot.api.persistence;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** One immutable, invocation-bound attenuation of process-root authority. */
public record AgentAuthorityGrantRegistration(UUID grantId, UUID parentGrantId,
                                               Set<UUID> contributingParentGrantIds,
                                               long depth, Set<String> dataScopes,
                                               Set<String> authorityScopes,
                                               AgentBudgetVector ceilings,
                                               Instant absoluteDeadline) {
    public AgentAuthorityGrantRegistration {
        if (grantId == null) throw new IllegalArgumentException("grantId is required");
        contributingParentGrantIds = Set.copyOf(contributingParentGrantIds == null
                ? (parentGrantId == null ? Set.of() : Set.of(parentGrantId))
                : contributingParentGrantIds);
        if (parentGrantId != null && !contributingParentGrantIds.contains(parentGrantId)) {
            throw new IllegalArgumentException("primary parent must contribute to the grant");
        }
        if (contributingParentGrantIds.contains(grantId)) {
            throw new IllegalArgumentException("a grant cannot parent itself");
        }
        if (depth < 1) throw new IllegalArgumentException("grant depth must be positive");
        dataScopes = scopes(dataScopes, "dataScopes");
        authorityScopes = scopes(authorityScopes, "authorityScopes");
        if (ceilings == null) throw new IllegalArgumentException("ceilings are required");
        if (absoluteDeadline == null) throw new IllegalArgumentException("absoluteDeadline is required");
    }

    private static Set<String> scopes(Set<String> source, String name) {
        Set<String> copy = Set.copyOf(source == null ? Set.of() : source);
        if (copy.size() > 256 || copy.stream().anyMatch(value -> value == null || value.isBlank()
                || value.length() > 256 || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*"))) {
            throw new IllegalArgumentException(name + " contains an invalid scope token");
        }
        return copy;
    }
}
