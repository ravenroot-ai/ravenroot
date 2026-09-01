package ai.ravenroot.api.embed;

import java.util.Objects;

/**
 * Server-verified, concretely pinned authority for one embedded graph read.
 *
 * <p>An HTTP adapter may create this value only from verified grant claims. Path, query and body
 * values are never coordinates for this record. The application authority still compares every
 * field with its authoritative record, so a verified but stale or incoherent grant fails closed.</p>
 * @param tenantId tenant that owns the granted resource
 * @param resourceId authorization resource identifier verified in the original grant
 * @param deploymentId deployment that published the graph snapshot
 * @param deploymentVersion positive immutable deployment revision
 * @param graphId immutable graph identifier selected by the grant
 * @param graphVersionId exact graph snapshot version selected by the grant
 * @param canonicalDigest canonical content digest of that snapshot
 * @param projectionPolicyRevision policy revision used to authorize its projection
 */
public record VerifiedEmbedGraphGrant(String tenantId, String resourceId, String deploymentId,
                                      long deploymentVersion, String graphId, String graphVersionId,
                                      String canonicalDigest, String projectionPolicyRevision) {
/**
 * Rejects blank identities and non-positive deployment versions before a grant reaches a browser path.
 */
    public VerifiedEmbedGraphGrant {
        tenantId = requireText(tenantId, "tenantId");
        resourceId = requireText(resourceId, "resourceId");
        deploymentId = requireText(deploymentId, "deploymentId");
        if (deploymentVersion < 1) throw new IllegalArgumentException("deploymentVersion must be positive");
        graphId = requireText(graphId, "graphId");
        graphVersionId = requireText(graphVersionId, "graphVersionId");
        canonicalDigest = requireText(canonicalDigest, "canonicalDigest");
        projectionPolicyRevision = requireText(projectionPolicyRevision, "projectionPolicyRevision");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
