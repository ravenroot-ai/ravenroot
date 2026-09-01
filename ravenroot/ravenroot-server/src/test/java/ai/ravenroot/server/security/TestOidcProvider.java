package ai.ravenroot.server.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import ai.ravenroot.server.RavenrootServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;

final class TestOidcProvider implements AutoCloseable {
    static final URI ISSUER = URI.create("https://issuer.test.example");
    static final String AUDIENCE = "ravenroot-test";

    /**
     * Touches {@link RavenrootServer}'s class before this class's own
     * {@link HttpServer#create} below runs, forcing {@code RavenrootServer}'s static initializer --
     * which sets {@code sun.net.httpserver.maxReqHeaderSize} -- to go first. See that class's Javadoc
     * ("Set here, verified at start()") for why letting a foreign {@code HttpServer.create()} run first
     * instead would permanently lock {@code sun.net.httpserver.ServerConfig} onto the JDK's own defaults
     * for the rest of this JVM: Surefire runs every test class in this module in one forked JVM by
     * default, so which test class happens to run before another using this fake OIDC provider would
     * otherwise silently decide whether every {@code RavenrootServer} the rest of the suite constructs
     * actually gets the cap this module intends, or reproduces the raw connection failure unnoticed.
     * {@code JdkHeaderCapOrderingHazardTest} demonstrates the underlying hazard directly, in a fresh JVM
     * where the ordering hazard is isolated; this line keeps this module's own test suite from being an
     * instance of it.
     */
    static {
        // Class.forName(Class#getName()), unlike a plain ".class" literal, is specified (JLS 12.4.1) to
        // initialize the named class -- which is the whole point here: a literal would resolve the
        // Class object without running RavenrootServer's static initializer at all. getName() rather
        // than a string constant so a future rename or repackage of RavenrootServer breaks this line at
        // compile time instead of silently becoming a no-op ClassNotFoundException swallowed below.
        try {
            Class.forName(RavenrootServer.class.getName());
        } catch (ClassNotFoundException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private final HttpServer server;
    private volatile RSAKey key;

    TestOidcProvider() throws Exception {
        key = generate("key-1");
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/jwks", exchange -> {
            byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
    }

    URI jwksUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks");
    }

    RSAKey key() {
        return key;
    }

    RSAKey rotate() throws Exception {
        key = generate("key-2");
        return key;
    }

    String token(String subject, String kind, Instant expiration, String audience) throws Exception {
        return token(key, subject, kind, expiration, audience, ISSUER.toString(),
                Instant.now().minusSeconds(1), Instant.now(), "ravenroot.read");
    }

    static String token(RSAKey signingKey, String subject, String kind, Instant expiration,
                        String audience, String issuer, Instant notBefore, Instant issuedAt,
                        Object scope) throws Exception {
        var builder = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuer)
                .audience(audience)
                .expirationTime(Date.from(expiration))
                .notBeforeTime(Date.from(notBefore))
                .issueTime(Date.from(issuedAt))
                .claim("tenant_id", "tenant-a")
                .claim("roles", List.of("PLATFORM_ADMIN"));
        if (kind != null) builder.claim("token_kind", kind);
        if (scope != null) builder.claim("scope", scope);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(signingKey.getKeyID()).build(), builder.build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    private static RSAKey generate(String keyId) throws Exception {
        return new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256)
                .keyID(keyId).generate();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
