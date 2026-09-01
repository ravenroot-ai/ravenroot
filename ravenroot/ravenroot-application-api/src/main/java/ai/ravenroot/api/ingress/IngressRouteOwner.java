package ai.ravenroot.api.ingress;

/**
 * The generation-fenced identity of a route lease.
 * @param packageId stable package id for this declaration.
 * @param tenantId stable tenant id for this declaration.
 * @param deploymentId stable deployment id for this declaration.
 * @param nodeId stable node id for this declaration.
 * @param graphGeneration graph generation supplied to this declaration.
 */
public record IngressRouteOwner(String packageId, String tenantId, String deploymentId, String nodeId,
                                long graphGeneration) {
/**
 * Requires bounded ownership identifiers and a positive graph generation for fencing.
 */
    public IngressRouteOwner {
        packageId = require(packageId, "packageId"); tenantId = require(tenantId, "tenantId");
        deploymentId = require(deploymentId, "deploymentId"); nodeId = require(nodeId, "nodeId");
        if (graphGeneration < 1) throw new IllegalArgumentException("graphGeneration must be positive");
    }
    private static String require(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 160) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }
}
