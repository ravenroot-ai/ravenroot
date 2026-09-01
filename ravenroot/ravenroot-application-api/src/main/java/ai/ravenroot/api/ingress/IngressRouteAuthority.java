package ai.ravenroot.api.ingress;

import java.util.Set;

/** A route capability already bound to one trusted source identity. */
@FunctionalInterface
public interface IngressRouteAuthority {
/**
 * Acquires one exact route. Descendants are not delivered to this lease.
 * @param routeId stable route id for this declaration.
 * @param relativePath relative path supplied to this declaration.
 * @param methods HTTP methods admitted for the exact relative path.
 * @param handler bounded request handler to invoke for matching requests.
 * @return generation-fenced lease that owns the route until released.
 */
    IngressRouteLease acquire(String routeId, String relativePath, Set<String> methods, IngressRouteHandler handler);

    /**
     * Acquires a route and every normalized descendant below it.
     *
     * <p>The default fails closed for implementations compiled before prefix dispatch existed. A
     * managed server authority overrides it; callers never get listener or namespace authority.</p>
 * @param routeId stable route id for this declaration.
 * @param relativePath relative path supplied to this declaration.
 * @param methods HTTP methods admitted for the path and descendants.
 * @param handler bounded request handler to invoke for matching requests.
 * @return generation-fenced prefix-route lease when the implementation supports prefix dispatch.
     */
    default IngressRouteLease acquirePrefix(String routeId, String relativePath, Set<String> methods,
                                            IngressRouteHandler handler) {
        throw new UnsupportedOperationException("prefix ingress routes are unavailable");
    }
}
