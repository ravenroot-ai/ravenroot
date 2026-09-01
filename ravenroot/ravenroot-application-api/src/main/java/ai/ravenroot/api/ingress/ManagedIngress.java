package ai.ravenroot.api.ingress;

/** Attenuated post-readiness route authority supplied by the composition root. */
public interface ManagedIngress {
/**
 * Internal lifecycle seam: callers give it a trusted source identity, never route data identity.
 * @param trustedOwner runtime-validated identity permitted to own routes.
 * @return authority attenuated to that owner and its admitted route namespace.
 */
    IngressRouteAuthority authorityFor(IngressRouteOwner trustedOwner);
/**
 * Retires every route currently owned by a trusted lifecycle identity.
 * @param trustedOwner runtime-validated identity whose routes must be removed.
 */
    void retire(IngressRouteOwner trustedOwner);
}
