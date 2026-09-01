package ai.ravenroot.server.security;

import com.sun.net.httpserver.Headers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/** Explicit loopback-only development authentication with a caller-supplied high-entropy token. */
public final class LocalTokenAuthenticator implements RequestAuthenticator {
    private final byte[] expectedDigest;

    public LocalTokenAuthenticator(String token) {
        if (token == null || token.length() < 32) {
            throw new IllegalArgumentException("Local authentication token must contain at least 32 characters");
        }
        expectedDigest = digest(token);
    }

    @Override
    public AuthenticatedPrincipal authenticate(Headers headers) throws AuthenticationException {
        String presented = BearerToken.extract(headers);
        if (!MessageDigest.isEqual(expectedDigest, digest(presented))) {
            throw new AuthenticationException("local bearer token mismatch");
        }
        return new AuthenticatedPrincipal("local-workload", AuthenticatedPrincipal.Type.WORKLOAD,
                "urn:ravenroot:local", "local", Set.of(ai.ravenroot.api.security.Role.PLATFORM_ADMIN),
                java.util.Arrays.stream(ai.ravenroot.api.security.AuthorizationAction.values())
                        .filter(ai.ravenroot.api.security.AuthorizationAction::available)
                        .map(ai.ravenroot.api.security.AuthorizationAction::requiredScope)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
