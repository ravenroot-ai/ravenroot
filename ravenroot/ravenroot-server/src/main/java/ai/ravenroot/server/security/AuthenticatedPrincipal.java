package ai.ravenroot.server.security;

import java.util.Objects;
import java.util.Set;
import java.time.Instant;

/** Immutable identity established at the HTTP authentication boundary. */
public record AuthenticatedPrincipal(String subject, Type type, String issuer, String tenantId,
                                     Set<ai.ravenroot.api.security.Role> roles, Set<String> scopes,
                                     Instant expiresAt) {
    public enum Type { USER, WORKLOAD }

    public AuthenticatedPrincipal(String subject, Type type, String issuer, String tenantId,
                                  Set<ai.ravenroot.api.security.Role> roles, Set<String> scopes) {
        this(subject, type, issuer, tenantId, roles, scopes, Instant.MAX);
    }

    public AuthenticatedPrincipal {
        subject = requireText(subject, "subject");
        type = Objects.requireNonNull(type, "type");
        issuer = requireText(issuer, "issuer");
        tenantId = requireText(tenantId, "tenantId");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        scopes = Set.copyOf(Objects.requireNonNull(scopes, "scopes"));
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
