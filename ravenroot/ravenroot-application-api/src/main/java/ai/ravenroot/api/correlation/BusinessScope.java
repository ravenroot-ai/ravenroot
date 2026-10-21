package ai.ravenroot.api.correlation;

/** Explicit tenant/logical-deployment/exact-version authority scope; incarnation is a separate fence.
 * @param tenantId authenticated tenant owning this history scope
 * @param deploymentId logical deployment identity; a new incarnation does not change it
 * @param graphVersion exact immutable graph version admitted for this history
 */
public record BusinessScope(String tenantId, String deploymentId, String graphVersion) {
    /** Validates finite opaque coordinates without deriving authority from an application ID. */
    public BusinessScope {
        tenantId = require(tenantId);
        deploymentId = require(deploymentId);
        graphVersion = require(graphVersion);
    }
    private static String require(String value) {
        if (value == null || value.isBlank() || value.length() > 4096
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid business history scope");
        return value;
    }
}
