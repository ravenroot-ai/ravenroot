package ai.ravenroot.server.security;

import com.sun.net.httpserver.Headers;

import java.util.Set;

/**
 * Explicit development-only mode. Its owner must verify loopback-effective exposure before
 * constructing it.
 */
public final class DisabledLoopbackAuthenticator implements RequestAuthenticator {
    @Override
    public AuthenticatedPrincipal authenticate(Headers headers) {
        return new AuthenticatedPrincipal("anonymous-loopback", AuthenticatedPrincipal.Type.USER,
                "urn:ravenroot:disabled-loopback", "local",
                Set.of(ai.ravenroot.api.security.Role.PLATFORM_ADMIN),
                java.util.Arrays.stream(ai.ravenroot.api.security.AuthorizationAction.values())
                        .filter(ai.ravenroot.api.security.AuthorizationAction::available)
                        .map(ai.ravenroot.api.security.AuthorizationAction::requiredScope)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }
}
