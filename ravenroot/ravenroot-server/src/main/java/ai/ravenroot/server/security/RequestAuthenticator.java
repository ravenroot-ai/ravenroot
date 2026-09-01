package ai.ravenroot.server.security;

import com.sun.net.httpserver.Headers;

@FunctionalInterface
public interface RequestAuthenticator {
    AuthenticatedPrincipal authenticate(Headers headers) throws AuthenticationException;

    /**
     * Revalidates a credential held by a long-lived request. Stateful or key-backed implementations
     * may bypass normal caches so revocation becomes visible at the lease interval.
     */
    default AuthenticatedPrincipal revalidate(Headers headers) throws AuthenticationException {
        return authenticate(headers);
    }
}
