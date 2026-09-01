package ai.ravenroot.api.security;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable identity and tenancy context established by a trusted adapter.
 * @param requestId non-blank request correlation identifier
 * @param subject authenticated subject name
 * @param principalType kind of authenticated actor
 * @param issuer identity-provider issuer that qualifies the subject
 * @param tenantId tenant selected by the trusted ingress adapter
 * @param roles immutable application roles asserted for the subject
 * @param scopes immutable granted scope strings
 */
public record RequestContext(String requestId, String subject, PrincipalType principalType, String issuer,
                             String tenantId, Set<Role> roles, Set<String> scopes) {
/**
 * Rejects missing identity fields and snapshots the role and scope sets.
 */
    public RequestContext {
        requestId = requireText(requestId, "requestId");
        subject = requireText(subject, "subject");
        principalType = Objects.requireNonNull(principalType, "principalType");
        issuer = requireText(issuer, "issuer");
        tenantId = requireText(tenantId, "tenantId");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        scopes = Set.copyOf(Objects.requireNonNull(scopes, "scopes"));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
