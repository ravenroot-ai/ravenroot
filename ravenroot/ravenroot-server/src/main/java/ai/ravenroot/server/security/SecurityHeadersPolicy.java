package ai.ravenroot.server.security;

import com.sun.net.httpserver.Headers;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Central response-header policy shared by UI, API, health and error responses. */
public final class SecurityHeadersPolicy {
    private static final String CSP_PREFIX = "default-src 'self'; base-uri 'none'; object-src 'none'; "
            + "frame-ancestors 'none'; form-action 'self'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'";
    private final boolean hsts;
    private final Set<String> uiConnectOrigins;

    public SecurityHeadersPolicy(boolean hsts) {
        this(hsts, Set.of());
    }

    public SecurityHeadersPolicy(boolean hsts, Set<String> uiConnectOrigins) {
        this.hsts = hsts;
        this.uiConnectOrigins = Set.copyOf(java.util.Objects.requireNonNull(
                uiConnectOrigins, "uiConnectOrigins"));
    }

    public static SecurityHeadersPolicy fromEnvironment(Map<String, String> environment) {
        boolean trustedTlsTerminator = strictBoolean(environment, "RAVENROOT_TRUSTED_TLS_TERMINATOR", false);
        String publicOrigin = environment.getOrDefault("RAVENROOT_PUBLIC_ORIGIN", "").trim();
        if (trustedTlsTerminator) {
            URI uri = URI.create(publicOrigin);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty())) {
                throw new IllegalArgumentException(
                        "RAVENROOT_PUBLIC_ORIGIN must be an HTTPS origin when the TLS terminator is trusted");
            }
        } else if (!publicOrigin.isEmpty()) {
            throw new IllegalArgumentException(
                    "RAVENROOT_PUBLIC_ORIGIN requires RAVENROOT_TRUSTED_TLS_TERMINATOR=true");
        }
        return new SecurityHeadersPolicy(trustedTlsTerminator,
                uiConnectOrigins(environment.getOrDefault("RAVENROOT_UI_CONNECT_ORIGINS", "")));
    }

    public void apply(Headers headers) {
        String connectSources = uiConnectOrigins.stream().sorted()
                .collect(java.util.stream.Collectors.joining(" "));
        headers.set("Content-Security-Policy", CSP_PREFIX
                + (connectSources.isEmpty() ? "" : " " + connectSources));
        headers.set("X-Frame-Options", "DENY");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy",
                "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
        if (hsts) {
            headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
    }

    private static Set<String> uiConnectOrigins(String configured) {
        if (configured == null || configured.isBlank()) {
            return Set.of();
        }
        var origins = new LinkedHashSet<String>();
        Arrays.stream(configured.split(",", -1)).map(String::trim)
                .map(SecurityHeadersPolicy::canonicalConnectOrigin).forEach(origins::add);
        return Set.copyOf(origins);
    }

    private static String canonicalConnectOrigin(String value) {
        if (value.isEmpty() || "*".equals(value) || "null".equals(value)) {
            throw new IllegalArgumentException("UI connect origins must be explicit");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid UI connect origin", invalid);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty())) {
            throw new IllegalArgumentException("UI connect origin must be an origin without path or credentials");
        }
        int port = uri.getPort();
        if (("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)) {
            throw new IllegalArgumentException("UI connect origin must omit its default port");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.startsWith("[") && normalizedHost.endsWith("]")) {
            normalizedHost = normalizedHost.substring(1, normalizedHost.length() - 1);
        }
        boolean loopback = "localhost".equals(normalizedHost) || "127.0.0.1".equals(normalizedHost)
                || "::1".equals(normalizedHost);
        if (!"https".equals(scheme) && !("http".equals(scheme) && loopback)) {
            throw new IllegalArgumentException("UI connect origins require HTTPS (HTTP is loopback-only)");
        }
        String renderedHost = normalizedHost.indexOf(':') >= 0 ? "[" + normalizedHost + "]" : normalizedHost;
        String canonical = scheme + "://" + renderedHost + (port == -1 ? "" : ":" + port);
        if (!canonical.equals(value)) {
            throw new IllegalArgumentException("UI connect origin must use its canonical representation");
        }
        return canonical;
    }

    private static boolean strictBoolean(Map<String, String> environment, String name, boolean defaultValue) {
        String value = environment.get(name);
        if (value == null) {
            return defaultValue;
        }
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(name + " must be true or false");
        };
    }
}
