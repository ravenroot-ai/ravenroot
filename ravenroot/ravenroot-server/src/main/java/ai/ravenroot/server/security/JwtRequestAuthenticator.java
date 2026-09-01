package ai.ravenroot.server.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyOperation;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.Headers;

import java.net.URI;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** RS256 OAuth2/OIDC access-token validation against an explicitly trusted JWKS endpoint. */
public final class JwtRequestAuthenticator implements RequestAuthenticator {
    private static final int MAX_JWT_LENGTH = 16_384;
    private final String issuer;
    private final String audience;
    private final String principalTypeClaim;
    private final Duration clockSkew;
    private final JwkSetProvider keys;
    private final Clock clock;

    public JwtRequestAuthenticator(URI issuer, String audience, String principalTypeClaim,
                                   Duration clockSkew, JwkSetProvider keys) {
        this(issuer, audience, principalTypeClaim, clockSkew, keys, Clock.systemUTC());
    }

    JwtRequestAuthenticator(URI issuer, String audience, String principalTypeClaim,
                            Duration clockSkew, JwkSetProvider keys, Clock clock) {
        this.issuer = canonicalIssuer(issuer);
        this.audience = requireText(audience, "audience");
        this.principalTypeClaim = requireText(principalTypeClaim, "principal type claim");
        if (clockSkew == null || clockSkew.isNegative() || clockSkew.compareTo(Duration.ofSeconds(120)) > 0) {
            throw new IllegalArgumentException("Clock skew must be between 0 and 120 seconds");
        }
        this.clockSkew = clockSkew;
        this.keys = java.util.Objects.requireNonNull(keys, "keys");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AuthenticatedPrincipal authenticate(Headers headers) throws AuthenticationException {
        return authenticate(headers, false);
    }

    @Override
    public AuthenticatedPrincipal revalidate(Headers headers) throws AuthenticationException {
        return authenticate(headers, true);
    }

    private AuthenticatedPrincipal authenticate(Headers headers, boolean forceKeyRefresh)
            throws AuthenticationException {
        String token = BearerToken.extract(headers);
        if (token.length() > MAX_JWT_LENGTH || token.chars().filter(character -> character == '.').count() != 2) {
            throw new AuthenticationException("JWT compact serialization is invalid");
        }
        try {
            var jwt = SignedJWT.parse(token);
            var header = jwt.getHeader();
            if (!JWSAlgorithm.RS256.equals(header.getAlgorithm()) || header.getKeyID() == null
                    || header.getKeyID().isBlank() || (header.getCriticalParams() != null
                    && !header.getCriticalParams().isEmpty())) {
                throw new AuthenticationException("JWT header is not accepted");
            }
            RSAKey key = selectKey(forceKeyRefresh ? keys.refresh() : keys.current(), header.getKeyID());
            if (key == null && !forceKeyRefresh) {
                key = selectKey(keys.refresh(), header.getKeyID());
            }
            if (key == null || !jwt.verify(new RSASSAVerifier(key.toRSAPublicKey()))) {
                throw new AuthenticationException("JWT signature is invalid");
            }
            return principal(jwt.getJWTClaimsSet());
        } catch (AuthenticationException error) {
            throw error;
        } catch (Exception error) {
            throw new AuthenticationException("JWT validation failed", error);
        }
    }

    private AuthenticatedPrincipal principal(JWTClaimsSet claims) throws ParseException, AuthenticationException {
        Instant now = clock.instant();
        if (!issuer.equals(claims.getIssuer()) || claims.getAudience() == null
                || !claims.getAudience().contains(audience)) {
            throw new AuthenticationException("JWT trust claims do not match");
        }
        if (claims.getExpirationTime() == null
                || !now.minus(clockSkew).isBefore(claims.getExpirationTime().toInstant())) {
            throw new AuthenticationException("JWT is expired or has no expiry");
        }
        if (claims.getNotBeforeTime() != null
                && now.plus(clockSkew).isBefore(claims.getNotBeforeTime().toInstant())) {
            throw new AuthenticationException("JWT is not active");
        }
        if (claims.getIssueTime() != null && now.plus(clockSkew).isBefore(claims.getIssueTime().toInstant())) {
            throw new AuthenticationException("JWT issue time is in the future");
        }
        String subject = requireText(claims.getSubject(), "subject");
        Object rawType = claims.getClaim(principalTypeClaim);
        AuthenticatedPrincipal.Type type = switch (rawType instanceof String value ? value : "") {
            case "user" -> AuthenticatedPrincipal.Type.USER;
            case "workload" -> AuthenticatedPrincipal.Type.WORKLOAD;
            default -> throw new AuthenticationException("JWT principal type is absent or unknown");
        };
        Set<String> scopes = scopes(claims);
        if (scopes.isEmpty()) {
            throw new AuthenticationException("JWT scope is absent");
        }
        String tenantId = requireText(claims.getStringClaim("tenant_id"), "tenant_id");
        Set<ai.ravenroot.api.security.Role> roles = roles(claims);
        if (roles.isEmpty()) {
            throw new AuthenticationException("JWT roles are absent");
        }
        return new AuthenticatedPrincipal(subject, type, issuer, tenantId, roles, scopes,
                claims.getExpirationTime().toInstant());
    }

    private static Set<ai.ravenroot.api.security.Role> roles(JWTClaimsSet claims)
            throws ParseException, AuthenticationException {
        Object raw = claims.getClaim("roles");
        List<String> values;
        if (raw instanceof String text) {
            values = Arrays.stream(text.trim().split("\\s+")).filter(value -> !value.isBlank()).toList();
        } else if (raw instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            values = list.stream().map(String.class::cast).toList();
        } else {
            throw new AuthenticationException("roles claim type is invalid");
        }
        var result = EnumSet.noneOf(ai.ravenroot.api.security.Role.class);
        try {
            values.stream().map(value -> value.trim().toUpperCase(java.util.Locale.ROOT))
                    .map(ai.ravenroot.api.security.Role::valueOf).forEach(result::add);
        } catch (IllegalArgumentException unknown) {
            throw new AuthenticationException("JWT contains an unknown role");
        }
        return Set.copyOf(result);
    }

    private static Set<String> scopes(JWTClaimsSet claims) throws ParseException, AuthenticationException {
        var result = new HashSet<String>();
        Object scope = claims.getClaim("scope");
        if (scope instanceof String text) {
            Arrays.stream(text.trim().split("\\s+")).filter(value -> !value.isBlank()).forEach(result::add);
        } else if (scope != null) {
            throw new AuthenticationException("scope claim type is invalid");
        }
        return Set.copyOf(result);
    }

    private static RSAKey selectKey(com.nimbusds.jose.jwk.JWKSet set, String keyId) {
        var matches = set.getKeys().stream()
                .filter(key -> keyId.equals(key.getKeyID()))
                .filter(key -> KeyType.RSA.equals(key.getKeyType()))
                .filter(key -> key.getAlgorithm() == null || JWSAlgorithm.RS256.equals(key.getAlgorithm()))
                .filter(key -> key.getKeyUse() == null || com.nimbusds.jose.jwk.KeyUse.SIGNATURE.equals(key.getKeyUse()))
                .filter(key -> key.getKeyOperations() == null || key.getKeyOperations().contains(KeyOperation.VERIFY))
                .map(RSAKey.class::cast)
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static String canonicalIssuer(URI issuer) {
        if (issuer == null || !issuer.isAbsolute() || issuer.getFragment() != null || issuer.getUserInfo() != null) {
            throw new IllegalArgumentException("Issuer must be an absolute URI without credentials or fragment");
        }
        String value = issuer.normalize().toString();
        if (!value.equals(issuer.toString())) {
            throw new IllegalArgumentException("Issuer URI must be canonical");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
