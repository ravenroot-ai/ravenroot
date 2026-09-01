package ai.ravenroot.api.ingress;

/** Idempotent generation-fenced lease. Releasing an old lease cannot affect its successor. */
public interface IngressRouteLease extends AutoCloseable {
/**
 * Returns the route whose ownership is protected by this lease.
 * @return stable route identifier used for generation-fenced release.
 */
    String routeId();
/**
 * Returns the authority that acquired this lease.
 * @return owner identity associated with the lease generation.
 */
    IngressRouteOwner owner();
/**
 * Releases this generation only; a stale release cannot remove a successor's lease.
 */
    void release();
    @Override default void close() { release(); }
}
