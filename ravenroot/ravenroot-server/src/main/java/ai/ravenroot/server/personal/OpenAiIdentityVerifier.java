package ai.ravenroot.server.personal;

import ai.ravenroot.server.security.JwkSetProvider;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.time.*;
import java.util.Map;

/** OIDC ID-token validation, independent of Ravenroot perimeter/access-token authentication. */
public final class OpenAiIdentityVerifier implements PersonalOAuthService.IdentityVerifier {
    private final java.util.function.Supplier<com.nimbusds.jose.jwk.JWKSet> keys;
    private final java.util.function.Supplier<com.nimbusds.jose.jwk.JWKSet> refreshedKeys;
    private final Clock clock;
    public OpenAiIdentityVerifier() {
        this(new JwkSetProvider(URI.create("https://auth.openai.com/.well-known/jwks.json"), Duration.ofMinutes(5)), Clock.systemUTC());
    }
    public OpenAiIdentityVerifier(JwkSetProvider provider, Clock clock) {
        this.keys = () -> { try { return provider.current(); } catch (Exception failure) { throw new PersonalConnectionException("sign-in-unverified"); } };
        this.refreshedKeys = () -> { try { return provider.refresh(); } catch (Exception failure) { throw new PersonalConnectionException("sign-in-unverified"); } };
        this.clock = clock;
    }
    OpenAiIdentityVerifier(java.util.function.Supplier<com.nimbusds.jose.jwk.JWKSet> keys, Clock clock) {
        this.keys = keys; this.refreshedKeys = keys; this.clock = clock;
    }
    public Map<String, Object> verify(String token, String clientId, String nonce) {
        try {
            if (token == null || token.length() > 32_768) throw new IllegalArgumentException();
            var jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm()) || jwt.getHeader().getKeyID() == null
                    || jwt.getHeader().getCriticalParams() != null && !jwt.getHeader().getCriticalParams().isEmpty()) throw new IllegalArgumentException();
            var key = keys.get().getKeyByKeyId(jwt.getHeader().getKeyID());
            if (key == null) key = refreshedKeys.get().getKeyByKeyId(jwt.getHeader().getKeyID());
            if (!(key instanceof RSAKey rsa) || rsa.isPrivate()
                    || rsa.getKeyUse() != null && !com.nimbusds.jose.jwk.KeyUse.SIGNATURE.equals(rsa.getKeyUse())
                    || rsa.getKeyOperations() != null && !rsa.getKeyOperations().contains(com.nimbusds.jose.jwk.KeyOperation.VERIFY)
                    || rsa.getAlgorithm() != null && !JWSAlgorithm.RS256.equals(rsa.getAlgorithm())
                    || !jwt.verify(new RSASSAVerifier(rsa.toRSAPublicKey()))) throw new IllegalArgumentException();
            var claims = jwt.getJWTClaimsSet();
            Instant now = clock.instant();
            if (!"https://auth.openai.com".equals(claims.getIssuer())
                    || !claims.getAudience().contains(clientId)
                    || claims.getAudience().size() > 1 && !clientId.equals(claims.getStringClaim("azp"))
                    || claims.getExpirationTime() == null || !claims.getExpirationTime().toInstant().isAfter(now)
                    || claims.getIssueTime() == null || claims.getIssueTime().toInstant().isAfter(now.plusSeconds(5))
                    || claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().isAfter(now.plusSeconds(5))
                    || claims.getSubject() == null || claims.getSubject().isBlank()
                    || nonce != null && !nonce.equals(claims.getStringClaim("nonce"))) throw new IllegalArgumentException();
            return claims.getClaims();
        } catch (Exception invalid) { throw new PersonalConnectionException("sign-in-unverified"); }
    }
}
