package ai.ravenroot.server.security;

import com.nimbusds.jose.jwk.JWKSet;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Bounded, no-redirect JWKS retrieval with a short last-known-good cache. */
public final class JwkSetProvider {
    private static final int MAX_JWKS_BYTES = 64 * 1024;
    private final URI uri;
    private final HttpClient client;
    private final Duration ttl;
    private final Clock clock;
    private volatile Cached cached;

    public JwkSetProvider(URI uri, Duration ttl) {
        this(uri, ttl, Clock.systemUTC());
    }

    JwkSetProvider(URI uri, Duration ttl, Clock clock) {
        this.uri = validateUri(uri);
        this.ttl = requireRange(ttl, Duration.ofSeconds(30), Duration.ofHours(1), "JWKS cache TTL");
        this.clock = clock;
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public JWKSet current() throws AuthenticationException {
        var snapshot = cached;
        Instant now = clock.instant();
        if (snapshot != null && now.isBefore(snapshot.expiresAt())) {
            return snapshot.keys();
        }
        return refresh();
    }

    public synchronized JWKSet refresh() throws AuthenticationException {
        try {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json").GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (var input = response.body()) {
                body = input.readNBytes(MAX_JWKS_BYTES + 1);
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() != 200 || body.length == 0 || body.length > MAX_JWKS_BYTES
                    || !(contentType.startsWith("application/json")
                    || contentType.startsWith("application/jwk-set+json"))) {
                throw new AuthenticationException("JWKS endpoint returned an unusable response");
            }
            var keys = JWKSet.parse(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            if (keys.getKeys().isEmpty()) {
                throw new AuthenticationException("JWKS contains no keys");
            }
            cached = new Cached(keys, clock.instant().plus(ttl));
            return keys;
        } catch (AuthenticationException error) {
            throw error;
        } catch (IOException error) {
            throw new AuthenticationException("JWKS retrieval failed", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AuthenticationException("JWKS retrieval interrupted", error);
        } catch (java.text.ParseException error) {
            throw new AuthenticationException("JWKS is malformed", error);
        }
    }

    private static URI validateUri(URI uri) {
        if (uri == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getHost() == null) {
            throw new IllegalArgumentException("JWKS URI must be an absolute URI without credentials or fragment");
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return uri;
        }
        if ("http".equalsIgnoreCase(uri.getScheme()) && isLoopbackLiteral(uri.getHost())) {
            return uri;
        }
        throw new IllegalArgumentException("JWKS URI must use HTTPS (HTTP is restricted to loopback literals)");
    }

    private static boolean isLoopbackLiteral(String host) {
        return "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
    }

    private static Duration requireRange(Duration value, Duration min, Duration max, String name) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
        return value;
    }

    private record Cached(JWKSet keys, Instant expiresAt) {
    }
}
