package ai.ravenroot.api.persistence;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * One immutable, invocation-bound attenuation of process-root authority.
 *
 * @param grantId stable identifier for this grant
 * @param parentGrantId primary parent grant, or {@code null} for a root-derived grant
 * @param contributingParentGrantIds every parent whose authority contributes to this grant
 * @param depth immutable delegation depth
 * @param dataScopes bounded operator-defined data-scope tokens
 * @param authorityScopes bounded authority-scope tokens
 * @param ceilings finite per-dimension resource ceilings
 * @param maximumTotalTokens combined input-and-output token ceiling
 * @param absoluteDeadline absolute deadline inherited or tightened from the parent authority
 */
public record AgentAuthorityGrantRegistration(UUID grantId, UUID parentGrantId,
                                               Set<UUID> contributingParentGrantIds,
                                               long depth, Set<String> dataScopes,
                                               Set<String> authorityScopes,
                                               AgentBudgetVector ceilings,
                                               long maximumTotalTokens,
                                               Instant absoluteDeadline) {
    /**
     * Creates a grant whose combined-token ceiling is the saturated sum of its input and output ceilings.
     *
     * @param grantId stable identifier for this grant
     * @param parentGrantId primary parent grant, or {@code null}
     * @param contributingParentGrantIds every contributing parent grant
     * @param depth immutable delegation depth
     * @param dataScopes bounded data scopes
     * @param authorityScopes bounded authority scopes
     * @param ceilings finite resource ceilings
     * @param absoluteDeadline absolute grant deadline
     */
    public AgentAuthorityGrantRegistration(UUID grantId, UUID parentGrantId,
            Set<UUID> contributingParentGrantIds, long depth, Set<String> dataScopes,
            Set<String> authorityScopes, AgentBudgetVector ceilings, Instant absoluteDeadline) {
        this(grantId, parentGrantId, contributingParentGrantIds, depth, dataScopes, authorityScopes,
                ceilings, combinedTokenCeiling(ceilings), absoluteDeadline);
    }

    /** Validates and snapshots all mutable grant inputs. */
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
        if (maximumTotalTokens < 0 || maximumTotalTokens > combinedTokenCeiling(ceilings)) {
            throw new IllegalArgumentException("maximumTotalTokens is outside the token ceilings");
        }
        if (absoluteDeadline == null) throw new IllegalArgumentException("absoluteDeadline is required");
    }

    private static long combinedTokenCeiling(AgentBudgetVector ceilings) {
        if (ceilings == null) throw new IllegalArgumentException("ceilings are required");
        return ceilings.inputTokens() > Long.MAX_VALUE - ceilings.outputTokens()
                ? Long.MAX_VALUE : ceilings.inputTokens() + ceilings.outputTokens();
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
