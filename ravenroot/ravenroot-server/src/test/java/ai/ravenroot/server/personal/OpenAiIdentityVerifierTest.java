package ai.ravenroot.server.personal;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiIdentityVerifierTest {
    final Instant now = Instant.parse("2026-10-25T12:00:00Z");
    @Test void signatureIssuerAudienceNonceExpiryAndSubjectAreAllRequired() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("key").generate();
        var verifier = new OpenAiIdentityVerifier(() -> new JWKSet(key.toPublicJWK()), Clock.fixed(now, ZoneOffset.UTC));
        assertEquals("subject-A", verifier.verify(token(key, "https://auth.openai.com", "client-A", "nonce", now.plusSeconds(60)), "client-A", "nonce").get("sub"));
        for (String[] values : List.of(new String[]{"https://attacker.example", "client-A", "nonce"}, new String[]{"https://auth.openai.com", "client-B", "nonce"}, new String[]{"https://auth.openai.com", "client-A", "other"})) {
            String token = token(key, values[0], values[1], values[2], now.plusSeconds(60));
            assertThrows(PersonalConnectionException.class, () -> verifier.verify(token, "client-A", "nonce"));
        }
        assertThrows(PersonalConnectionException.class, () -> verifier.verify(token(key, "https://auth.openai.com", "client-A", "nonce", now.minusSeconds(1)), "client-A", "nonce"));
        var encryptionKey = new RSAKey.Builder(key.toPublicJWK()).keyUse(KeyUse.ENCRYPTION).build();
        var wrongUse = new OpenAiIdentityVerifier(() -> new JWKSet(encryptionKey), Clock.fixed(now, ZoneOffset.UTC));
        assertThrows(PersonalConnectionException.class, () -> wrongUse.verify(token(key, "https://auth.openai.com", "client-A", "nonce", now.plusSeconds(60)), "client-A", "nonce"));
        RSAKey other = new RSAKeyGenerator(2048).keyID("key").generate();
        assertThrows(PersonalConnectionException.class, () -> verifier.verify(token(other, "https://auth.openai.com", "client-A", "nonce", now.plusSeconds(60)), "client-A", "nonce"));
    }
    private String token(RSAKey key, String issuer, String audience, String nonce, Instant expiry) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject("subject-A")
                .issueTime(Date.from(now.minusSeconds(1))).expirationTime(Date.from(expiry)).claim("nonce", nonce).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("key").build(), claims);
        jwt.sign(new RSASSASigner(key)); return jwt.serialize();
    }
}
