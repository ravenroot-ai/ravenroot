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
    private static final String ACCEPT_HEADER = "Accept";
    private static final String JSON_MEDIA_TYPE = "application/json";
    private static final String JWK_SET_MEDIA_TYPE = "application/jwk-set+json";
    private final URI uri;
    private final HttpClient client;
    private final Duration ttl;
    private final Duration requestTimeout;
    private final Clock clock;
    private volatile Cached cached;

    public JwkSetProvider(URI uri, Duration ttl) {
        this(uri, ttl, TransportPolicy.defaults(), Clock.systemUTC());
    }

    JwkSetProvider(URI uri, Duration ttl, Clock clock) {
        this(uri, ttl, TransportPolicy.defaults(), clock);
    }

    JwkSetProvider(URI uri, Duration ttl, TransportPolicy transportPolicy) {
        this(uri, ttl, transportPolicy, Clock.systemUTC());
    }

    JwkSetProvider(URI uri, Duration ttl, TransportPolicy transportPolicy, Clock clock) {
        this.uri = validateUri(uri);
        this.ttl = requireRange(ttl, Duration.ofSeconds(30), Duration.ofHours(1), "JWKS cache TTL");
        this.requestTimeout = transportPolicy.requestTimeout();
        this.clock = clock;
        client = HttpClient.newBuilder()
                .connectTimeout(transportPolicy.connectTimeout())
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
            var request = HttpRequest.newBuilder(uri).timeout(requestTimeout)
                    .header(ACCEPT_HEADER, JSON_MEDIA_TYPE).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (var input = response.body()) {
                body = input.readNBytes(MAX_JWKS_BYTES + 1);
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() != 200 || body.length == 0 || body.length > MAX_JWKS_BYTES
                    || !(contentType.startsWith(JSON_MEDIA_TYPE)
                    || contentType.startsWith(JWK_SET_MEDIA_TYPE))) {
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

    record TransportPolicy(Duration connectTimeout, Duration requestTimeout) {
        private static final Duration MINIMUM_CONNECT_TIMEOUT = Duration.ofSeconds(1);
        private static final Duration MAXIMUM_CONNECT_TIMEOUT = Duration.ofMinutes(5);
        private static final Duration MINIMUM_REQUEST_TIMEOUT = Duration.ofSeconds(1);
        private static final Duration MAXIMUM_REQUEST_TIMEOUT = Duration.ofMinutes(5);

        TransportPolicy {
            connectTimeout = requireRange(
                    connectTimeout, MINIMUM_CONNECT_TIMEOUT, MAXIMUM_CONNECT_TIMEOUT, "JWKS connect timeout");
            requestTimeout = requireRange(
                    requestTimeout, MINIMUM_REQUEST_TIMEOUT, MAXIMUM_REQUEST_TIMEOUT, "JWKS request timeout");
        }

        static TransportPolicy defaults() {
            return new TransportPolicy(Duration.ofSeconds(3), Duration.ofSeconds(5));
        }
    }
}
