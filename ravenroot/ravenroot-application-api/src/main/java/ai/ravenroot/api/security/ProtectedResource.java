package ai.ravenroot.api.security;

import java.util.Objects;
import java.util.Optional;

/**
 * Resource attributes supplied by a trusted repository/facade, never by an untrusted caller.
 * @param type non-blank protected-resource kind
 * @param id non-blank resource identifier
 * @param tenantId known owning tenant, or empty when ownership cannot be established
 */
public record ProtectedResource(String type, String id, Optional<String> tenantId) {
/**
 * Validates text fields and snapshots the optional tenant ownership marker.
 */
    public ProtectedResource {
        type = requireText(type, "type");
        id = requireText(id, "id");
        tenantId = Objects.requireNonNull(tenantId, "tenantId")
                .map(value -> requireText(value, "tenantId"));
    }

/**
 * Describes a tenant-owned collection rather than an individual resource.
 * @param type collection resource kind
 * @param tenantId known owning tenant
 * @return resource attributes whose identifier is the collection type
 */
    public static ProtectedResource collection(String type, String tenantId) {
        return new ProtectedResource(type, type, Optional.of(tenantId));
    }

/**
 * Describes an individual resource with verified tenant ownership.
 * @param type protected resource kind
 * @param id resource identifier
 * @param tenantId verified owning tenant
 * @return tenant-owned resource attributes
 */
    public static ProtectedResource owned(String type, String id, String tenantId) {
        return new ProtectedResource(type, id, Optional.of(tenantId));
    }

/**
 * Describes an individual resource whose tenant ownership is unavailable.
 * @param type protected resource kind
 * @param id resource identifier
 * @return resource attributes with no tenant ownership claim
 */
    public static ProtectedResource unknownOwnership(String type, String id) {
        return new ProtectedResource(type, id, Optional.empty());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
