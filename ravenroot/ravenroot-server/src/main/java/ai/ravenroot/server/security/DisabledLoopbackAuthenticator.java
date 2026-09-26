package ai.ravenroot.server.security;

import com.sun.net.httpserver.Headers;

import java.util.Set;

/**
 * Explicit development-only mode. Its owner must verify loopback-effective exposure before
 * constructing it.
 */
public final class DisabledLoopbackAuthenticator implements RequestAuthenticator {
    /** Local-only embed host opts into the workload side of the disabled-mode identity. */
    public static final String WORKLOAD_HEADER = "X-Ravenroot-Local-Workload";
    @Override
    public AuthenticatedPrincipal authenticate(Headers headers) {
        boolean workload = "embed".equals(headers.getFirst(WORKLOAD_HEADER));
        return new AuthenticatedPrincipal(workload ? "local-embed-host" : "anonymous-loopback",
                workload ? AuthenticatedPrincipal.Type.WORKLOAD : AuthenticatedPrincipal.Type.USER,
                "urn:ravenroot:disabled-loopback", "local",
                Set.of(ai.ravenroot.api.security.Role.PLATFORM_ADMIN),
                java.util.Arrays.stream(ai.ravenroot.api.security.AuthorizationAction.values())
                        .filter(ai.ravenroot.api.security.AuthorizationAction::available)
                        .map(ai.ravenroot.api.security.AuthorizationAction::requiredScope)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }
}
