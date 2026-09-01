package ai.ravenroot.server.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.Headers;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Time in this class is never read from the system clock. {@link JwtRequestAuthenticator} is
 * constructed with a fixed {@link Clock} (via its package-private test constructor) pinned at
 * {@link #NOW}, and every token minted here has its {@code nbf}/{@code iat}/{@code exp} computed
 * as an explicit offset from that same instant. There is no window between "mint the token" and
 * "validate the token" during which wall-clock scheduling (GC pause, CI contention, a slow
 * shared runner) can flip an assertion: {@code NOW} does not advance while the test runs.
 *
 * <p>The validator's clock tolerance ({@link #CLOCK_SKEW}, 30 seconds here) is a real, bounded
 * constructor parameter of {@link JwtRequestAuthenticator} (0-120s, see its constructor), not an
 * incidental byproduct of test timing. {@link #acceptsTokensAtTheExactEdgeOfTheConfiguredClockSkew()}
 * pins both sides of that boundary: it fails if the tolerance is ever removed from the production
 * comparisons, not just if it is narrowed.
 *
 * <p>Boundary note (asymmetry in production, observed not changed here): the expiry check uses a
 * strict {@code now - skew < exp}, so a token exactly {@code skew} seconds past its expiry is
 * already rejected (age {@code == skew} does not satisfy strict {@code <}). The nbf/iat checks use
 * {@code now + skew < claim}, so a claim exactly {@code skew} seconds in the future is still
 * accepted (offset {@code == skew} does not satisfy strict {@code <}). Both edges are exercised
 * below rather than assumed.
 */
class JwtRequestAuthenticatorTest {
    private static final Instant NOW = Instant.parse("2024-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    @Test
    void validatesUserAndWorkloadTokensFromRotatingTestProvider() throws Exception {
        try (var provider = new TestOidcProvider()) {
            var authenticator = authenticator(provider);
            var user = authenticator.authenticate(headers(userToken(provider, "alice", NOW.plusSeconds(60))));
            assertEquals(AuthenticatedPrincipal.Type.USER, user.type());
            assertEquals("alice", user.subject());

            var rotated = provider.rotate();
            String workloadToken = TestOidcProvider.token(rotated, "job-7", "workload",
                    NOW.plusSeconds(60), TestOidcProvider.AUDIENCE, TestOidcProvider.ISSUER.toString(),
                    NOW.minusSeconds(1), NOW, "ravenroot.execute");
            var workload = authenticator.authenticate(headers(workloadToken));
            assertEquals(AuthenticatedPrincipal.Type.WORKLOAD, workload.type());
            assertEquals("job-7", workload.subject());
        }
    }

    @Test
    void longLivedRevalidationRefreshesKeysAndObservesKeyRevocation() throws Exception {
        try (var provider = new TestOidcProvider()) {
            var authenticator = authenticator(provider);
            var token = headers(userToken(provider, "alice", NOW.plusSeconds(60)));
            authenticator.authenticate(token);
            provider.rotate();
            assertThrows(AuthenticationException.class, () -> authenticator.revalidate(token));
        }
    }

    @Test
    void rejectsMissingMalformedRepeatedAndNonAsymmetricTokens() throws Exception {
        try (var provider = new TestOidcProvider()) {
            var authenticator = authenticator(provider);
            assertThrows(AuthenticationException.class, () -> authenticator.authenticate(new Headers()));
            assertThrows(AuthenticationException.class,
                    () -> authenticator.authenticate(headers("not-a-jwt")));
            var repeated = headers(userToken(provider, "alice", NOW.plusSeconds(60)));
            repeated.add("Authorization", "Bearer another");
            assertThrows(AuthenticationException.class, () -> authenticator.authenticate(repeated));

            var claims = new JWTClaimsSet.Builder().subject("alice").issuer(TestOidcProvider.ISSUER.toString())
                    .audience(TestOidcProvider.AUDIENCE).expirationTime(Date.from(NOW.plusSeconds(60)))
                    .claim("token_kind", "user").claim("scope", "read").build();
            var hs = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            hs.sign(new MACSigner("01234567890123456789012345678901"));
            assertThrows(AuthenticationException.class, () -> authenticator.authenticate(headers(hs.serialize())));
        }
    }

    @Test
    void rejectsInvalidTrustAndTimeClaims() throws Exception {
        try (var provider = new TestOidcProvider()) {
            var authenticator = authenticator(provider);
            // Expired beyond tolerance: age from NOW is exactly CLOCK_SKEW, which the strict "<"
            // expiry comparison treats as already expired (see class javadoc boundary note).
            assertRejected(authenticator, TestOidcProvider.token(provider.key(), "alice", "user",
                    NOW.minusSeconds(30), TestOidcProvider.AUDIENCE, TestOidcProvider.ISSUER.toString(),
                    NOW.minusSeconds(60), NOW.minusSeconds(60), "read"));
            assertRejected(authenticator, TestOidcProvider.token(provider.key(), "alice", "user",
                    NOW.plusSeconds(60), "wrong-audience", TestOidcProvider.ISSUER.toString(),
                    NOW.minusSeconds(1), NOW, "read"));
            assertRejected(authenticator, TestOidcProvider.token(provider.key(), "alice", "user",
                    NOW.plusSeconds(60), TestOidcProvider.AUDIENCE, "https://wrong.example",
                    NOW.minusSeconds(1), NOW, "read"));
            // nbf strictly beyond tolerance (skew + 1s): must be rejected.
            assertRejected(authenticator, TestOidcProvider.token(provider.key(), "alice", "user",
                    NOW.plusSeconds(60), TestOidcProvider.AUDIENCE, TestOidcProvider.ISSUER.toString(),
                    NOW.plusSeconds(31), NOW, "read"));
            // iat strictly beyond tolerance (skew + 1s): must be rejected.
            assertRejected(authenticator, TestOidcProvider.token(provider.key(), "alice", "user",
                    NOW.plusSeconds(60), TestOidcProvider.AUDIENCE, TestOidcProvider.ISSUER.toString(),
                    NOW.minusSeconds(1), NOW.plusSeconds(31), "read"));
        }
    }

    /**
     * Pins both sides of {@link #CLOCK_SKEW} for every time-based claim. These are positive
     * (accept) cases, chosen so that if the production tolerance were ever deleted or zeroed —
     * i.e. the validator started comparing claims directly against {@code NOW} instead of against
     * {@code NOW +/- CLOCK_SKEW} — each of these would start throwing {@link AuthenticationException}
     * and this test would fail. The contract requires a test that fails if the clock
     * tolerance is removed, not one that is merely unaffected by widening it.
     */
    @Test
    void acceptsTokensAtTheExactEdgeOfTheConfiguredClockSkew() throws Exception {
        try (var provider = new TestOidcProvider()) {
            var authenticator = authenticator(provider);

            // Expired one second inside tolerance (age = skew - 1s): accepted only because of skew.
            var expiredWithinTolerance = assertDoesNotThrow(() -> authenticator.authenticate(headers(
                    TestOidcProvider.token(provider.key(), "alice", "user", NOW.minusSeconds(29),
                            TestOidcProvider.AUDIENCE, TestOidcProvider.ISSUER.toString(),
                            NOW.minusSeconds(60), NOW.minusSeconds(60), "read"))));
            assertEquals("alice", expiredWithinTolerance.subject());

            // nbf exactly at the boundary (offset == skew): the "<" comparison does not reject it.
            var nbfAtBoundary = assertDoesNotThrow(() -> authenticator.authenticate(headers(
                    TestOidcProvider.token(provider.key(), "alice", "user", NOW.plusSeconds(90),
                            TestOidcProvider.AUDIENCE, TestOidcProvider.ISSUER.toString(),
                            NOW.plusSeconds(30), NOW, "read"))));
            assertEquals("alice", nbfAtBoundary.subject());

            // iat exactly at the boundary (offset == skew): the "<" comparison does not reject it.
            var iatAtBoundary = assertDoesNotThrow(() -> authenticator.authenticate(headers(
                    TestOidcProvider.token(provider.key(), "alice", "user", NOW.plusSeconds(90),
                            TestOidcProvider.AUDIENCE, TestOidcProvider.ISSUER.toString(),
                            NOW.minusSeconds(1), NOW.plusSeconds(30), "read"))));
            assertEquals("alice", iatAtBoundary.subject());
        }
    }

    @Test
    void clockSkewMustStayWithinItsDocumentedZeroToOneHundredTwentySecondRange() {
        var keys = new JwkSetProvider(URI.create("https://issuer.test.example/jwks"), Duration.ofSeconds(30));
        assertThrows(IllegalArgumentException.class, () -> new JwtRequestAuthenticator(TestOidcProvider.ISSUER,
                TestOidcProvider.AUDIENCE, "token_kind", Duration.ofSeconds(-1), keys));
        assertThrows(IllegalArgumentException.class, () -> new JwtRequestAuthenticator(TestOidcProvider.ISSUER,
                TestOidcProvider.AUDIENCE, "token_kind", Duration.ofSeconds(121), keys));
        assertThrows(IllegalArgumentException.class, () -> new JwtRequestAuthenticator(TestOidcProvider.ISSUER,
                TestOidcProvider.AUDIENCE, "token_kind", null, keys));
        assertDoesNotThrow(() -> new JwtRequestAuthenticator(TestOidcProvider.ISSUER,
                TestOidcProvider.AUDIENCE, "token_kind", Duration.ZERO, keys));
        assertDoesNotThrow(() -> new JwtRequestAuthenticator(TestOidcProvider.ISSUER,
                TestOidcProvider.AUDIENCE, "token_kind", Duration.ofSeconds(120), keys));
    }

    @Test
    void rejectsUnknownKeySignatureMissingTypeAndMissingOrInvalidScope() throws Exception {
        try (var provider = new TestOidcProvider(); var other = new TestOidcProvider()) {
            var authenticator = authenticator(provider);
            assertRejected(authenticator, TestOidcProvider.token(other.key(), "alice", "user",
                    NOW.plusSeconds(60), TestOidcProvider.AUDIENCE, TestOidcProvider.ISSUER.toString(),
                    NOW.minusSeconds(1), NOW, "read"));
            assertRejected(authenticator, TestOidcProvider.token(provider.key(), "alice", null,
                    NOW.plusSeconds(60), TestOidcProvider.AUDIENCE, TestOidcProvider.ISSUER.toString(),
                    NOW.minusSeconds(1), NOW, "read"));
            assertRejected(authenticator, TestOidcProvider.token(provider.key(), "alice", "operator",
                    NOW.plusSeconds(60), TestOidcProvider.AUDIENCE, TestOidcProvider.ISSUER.toString(),
                    NOW.minusSeconds(1), NOW, "read"));
            assertRejected(authenticator, TestOidcProvider.token(provider.key(), "alice", "user",
                    NOW.plusSeconds(60), TestOidcProvider.AUDIENCE, TestOidcProvider.ISSUER.toString(),
                    NOW.minusSeconds(1), NOW, null));
            assertRejected(authenticator, TestOidcProvider.token(provider.key(), "alice", "user",
                    NOW.plusSeconds(60), TestOidcProvider.AUDIENCE, TestOidcProvider.ISSUER.toString(),
                    NOW.minusSeconds(1), NOW, 42));
        }
    }

    private static JwtRequestAuthenticator authenticator(TestOidcProvider provider) {
        return new JwtRequestAuthenticator(TestOidcProvider.ISSUER, TestOidcProvider.AUDIENCE, "token_kind",
                CLOCK_SKEW, new JwkSetProvider(provider.jwksUri(), Duration.ofSeconds(30)), CLOCK);
    }

    /** Mints a well-formed, currently-valid "user" token against {@link #NOW}, bypassing
     * {@link TestOidcProvider#token(String, String, Instant, String)}, which reads the system
     * clock for nbf/iat and is intentionally left untouched for the integration tests that still
     * rely on it against a real running server. */
    private static String userToken(TestOidcProvider provider, String subject, Instant expiration) throws Exception {
        return TestOidcProvider.token(provider.key(), subject, "user", expiration, TestOidcProvider.AUDIENCE,
                TestOidcProvider.ISSUER.toString(), NOW.minusSeconds(1), NOW, "ravenroot.read");
    }

    private static Headers headers(String token) {
        var headers = new Headers();
        headers.set("Authorization", "Bearer " + token);
        return headers;
    }

    private static void assertRejected(JwtRequestAuthenticator authenticator, String token) {
        assertThrows(AuthenticationException.class, () -> authenticator.authenticate(headers(token)));
    }
}
