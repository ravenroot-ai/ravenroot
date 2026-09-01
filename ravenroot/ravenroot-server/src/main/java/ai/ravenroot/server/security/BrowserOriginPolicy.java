package ai.ravenroot.server.security;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Exact browser-origin boundary. Requests without Origin remain available to non-browser clients;
 * a browser-supplied Origin must match the configured canonical allowlist exactly.
 */
public final class BrowserOriginPolicy {
    private static final Set<String> REQUEST_HEADERS =
            Set.of("authorization", "content-type", "last-event-id");
    private final Set<String> allowedOrigins;

    public BrowserOriginPolicy(Set<String> allowedOrigins) {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("At least one browser origin must be configured");
        }
        var validated = new LinkedHashSet<String>();
        allowedOrigins.forEach(value -> validated.add(canonicalOrigin(value)));
        this.allowedOrigins = Set.copyOf(validated);
    }

    public static BrowserOriginPolicy fromEnvironment(Map<String, String> environment, int port) {
        String configured = environment.getOrDefault("RAVENROOT_BROWSER_ALLOWED_ORIGINS", "").trim();
        if (!configured.isEmpty()) {
            return new BrowserOriginPolicy(Arrays.stream(configured.split(",", -1))
                    .map(String::trim).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        }
        return new BrowserOriginPolicy(Set.of("http://127.0.0.1:" + port, "http://localhost:" + port));
    }

    public boolean acceptActual(HttpExchange exchange) throws IOException {
        String origin;
        try {
            origin = exactOriginHeader(exchange);
        } catch (OriginRejected handled) {
            return false;
        }
        if (origin == null) {
            return true;
        }
        if (!allowedOrigins.contains(origin)) {
            reject(exchange, 403, "browser origin is not allowed");
            return false;
        }
        applyCorsResponse(exchange, origin);
        return true;
    }

    public boolean handlePreflight(HttpExchange exchange, Set<String> allowedMethods) throws IOException {
        if (!"OPTIONS".equals(exchange.getRequestMethod())) {
            return false;
        }
        String origin;
        try {
            origin = exactOriginHeader(exchange);
        } catch (OriginRejected handled) {
            return true;
        }
        String requestedMethod = exchange.getRequestHeaders().getFirst("Access-Control-Request-Method");
        if (origin == null || !allowedOrigins.contains(origin) || requestedMethod == null
                || !allowedMethods.contains(requestedMethod)) {
            reject(exchange, 403, "CORS preflight is not allowed");
            return true;
        }
        var requestedHeaders = new LinkedHashSet<String>();
        for (String value : exchange.getRequestHeaders().getOrDefault("Access-Control-Request-Headers", java.util.List.of())) {
            for (String name : value.split(",", -1)) {
                String normalized = name.trim().toLowerCase(Locale.ROOT);
                if (normalized.isEmpty() || !REQUEST_HEADERS.contains(normalized)) {
                    reject(exchange, 403, "CORS preflight contains a forbidden request header");
                    return true;
                }
                requestedHeaders.add(normalized);
            }
        }
        applyCorsResponse(exchange, origin);
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods",
                allowedMethods.stream().sorted().collect(java.util.stream.Collectors.joining(", ")));
        if (!requestedHeaders.isEmpty()) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers",
                    requestedHeaders.stream().sorted().collect(java.util.stream.Collectors.joining(", ")));
        }
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "600");
        exchange.getResponseHeaders().set("Allow",
                allowedMethods.stream().sorted().collect(java.util.stream.Collectors.joining(", ")));
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
        return true;
    }

    private static String exactOriginHeader(HttpExchange exchange) throws IOException {
        var values = exchange.getRequestHeaders().get("Origin");
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() != 1) {
            reject(exchange, 400, "Origin header must be unambiguous");
            throw new OriginRejected();
        }
        try {
            return canonicalOrigin(values.getFirst());
        } catch (IllegalArgumentException invalid) {
            reject(exchange, 400, "Origin header is invalid");
            throw new OriginRejected();
        }
    }

    private static String canonicalOrigin(String value) {
        if (value == null || value.isBlank() || "*".equals(value) || "null".equals(value)) {
            throw new IllegalArgumentException("Browser origins must be explicit");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid browser origin", invalid);
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equals(scheme) && !"https".equals(scheme))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getQuery() != null || (uri.getPath() != null && !uri.getPath().isEmpty())) {
            throw new IllegalArgumentException("Browser origin must be canonical HTTP(S) scheme/host/port");
        }
        String canonical = scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        if (!canonical.equals(value)) {
            throw new IllegalArgumentException("Browser origin must use its canonical representation");
        }
        return canonical;
    }

    private static void applyCorsResponse(HttpExchange exchange, String origin) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        exchange.getResponseHeaders().set("Vary", "Origin");
    }

    private static void reject(HttpExchange exchange, int status, String message) throws IOException {
        byte[] bytes = ("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static final class OriginRejected extends RuntimeException {
    }
}
