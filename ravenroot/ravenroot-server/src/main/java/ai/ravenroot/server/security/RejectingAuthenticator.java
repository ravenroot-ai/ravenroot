package ai.ravenroot.server.security;

import com.sun.net.httpserver.Headers;

/** Secure default used when authentication has not been configured. */
public final class RejectingAuthenticator implements RequestAuthenticator {
    @Override
    public AuthenticatedPrincipal authenticate(Headers headers) throws AuthenticationException {
        throw new AuthenticationException("authentication is not configured");
    }
}
