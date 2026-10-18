package ai.ravenroot.server.security;

import com.sun.net.httpserver.Headers;

import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Central response-header policy shared by UI, API, health and error responses. */
public final class SecurityHeadersPolicy {
    private static final String CSP_PREFIX = "default-src 'self'; base-uri 'none'; object-src 'none'; "
            + "frame-ancestors ";
    private static final String CSP_SUFFIX = "; form-action 'self'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'";
    private final boolean hsts;
    private final Set<String> uiConnectOrigins;
    private final Set<String> uiFrameAncestors;

    public SecurityHeadersPolicy(boolean hsts) {
        this(hsts, Set.of(), Set.of());
    }

    public SecurityHeadersPolicy(boolean hsts, Set<String> uiConnectOrigins) {
        this(hsts, uiConnectOrigins, Set.of());
    }

    private SecurityHeadersPolicy(boolean hsts, Set<String> uiConnectOrigins,
                                  Set<String> uiFrameAncestors) {
        this.hsts = hsts;
        this.uiConnectOrigins = Set.copyOf(java.util.Objects.requireNonNull(
                uiConnectOrigins, "uiConnectOrigins"));
        this.uiFrameAncestors = Set.copyOf(java.util.Objects.requireNonNull(
                uiFrameAncestors, "uiFrameAncestors"));
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
                uiConnectOrigins(environment.getOrDefault("RAVENROOT_UI_CONNECT_ORIGINS", "")),
                uiFrameAncestors(environment.getOrDefault("RAVENROOT_UI_FRAME_ANCESTORS", "")));
    }

    public void apply(Headers headers) {
        apply(headers, Set.of());
    }

    /**
     * Applies the common policy plus the immutable operator-registered frame origins.
     * @param headers response headers to replace with the effective policy
     * @param frameOrigins exact operator-registered presentation origins
     */
    public void apply(Headers headers, Set<URI> frameOrigins) {
        String connectSources = uiConnectOrigins.stream().sorted()
                .collect(java.util.stream.Collectors.joining(" "));
        String frameSources = java.util.Objects.requireNonNull(frameOrigins, "frameOrigins").stream()
                .map(SecurityHeadersPolicy::canonicalFrameOrigin).sorted()
                .collect(java.util.stream.Collectors.joining(" "));
        String ancestorSources = uiFrameAncestors.isEmpty()
                ? "'none'"
                : uiFrameAncestors.stream().sorted().collect(java.util.stream.Collectors.joining(" "));
        headers.set("Content-Security-Policy", CSP_PREFIX + ancestorSources + CSP_SUFFIX
                + (connectSources.isEmpty() ? "" : " " + connectSources)
                + "; frame-src " + (frameSources.isEmpty() ? "'none'" : frameSources));
        if (uiFrameAncestors.isEmpty()) {
            headers.set("X-Frame-Options", "DENY");
        } else {
            // X-Frame-Options supports DENY and SAMEORIGIN but has no origin-list form. Once
            // framing is opted in, keep one CSP authority across self-only and origin-list policies.
            headers.remove("X-Frame-Options");
        }
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy",
                "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
        if (hsts) {
            headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
    }

    private static String canonicalFrameOrigin(URI uri) {
        java.util.Objects.requireNonNull(uri, "frame origin");
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
            throw new IllegalArgumentException("frame origin must contain only scheme and authority");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.startsWith("[") && normalizedHost.endsWith("]")) {
            normalizedHost = normalizedHost.substring(1, normalizedHost.length() - 1);
        }
        boolean loopback = "localhost".equals(normalizedHost) || "127.0.0.1".equals(normalizedHost)
                || "::1".equals(normalizedHost);
        if (!"https".equals(scheme) && !("http".equals(scheme) && loopback)) {
            throw new IllegalArgumentException("frame origins require HTTPS (HTTP is loopback-only)");
        }
        String renderedHost = normalizedHost.indexOf(':') >= 0 ? "[" + normalizedHost + "]" : normalizedHost;
        return scheme + "://" + renderedHost + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
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

    private static Set<String> uiFrameAncestors(String configured) {
        if (configured == null || configured.isBlank()) {
            return Set.of();
        }
        var ancestors = new LinkedHashSet<String>();
        for (String declared : configured.split(",", -1)) {
            String value = declared.trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException(
                        "RAVENROOT_UI_FRAME_ANCESTORS must not contain empty entries");
            }
            ancestors.add("self".equals(value) ? "'self'" : canonicalFrameAncestorOrigin(value));
        }
        return Set.copyOf(ancestors);
    }

    private static String canonicalFrameAncestorOrigin(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            throw invalidFrameAncestor();
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort();
        if (scheme == null || host == null || !("http".equals(scheme) || "https".equals(scheme))
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                || port == 0 || port > 65535) {
            throw invalidFrameAncestor();
        }
        String normalizedHost = canonicalOriginHost(host);
        if (normalizedHost.endsWith(".")) {
            throw invalidFrameAncestor();
        }
        String renderedHost = normalizedHost.indexOf(':') >= 0
                ? "[" + normalizedHost + "]"
                : normalizedHost;
        boolean defaultPort = ("https".equals(scheme) && port == 443)
                || ("http".equals(scheme) && port == 80);
        String canonical = scheme + "://" + renderedHost
                + (port == -1 || defaultPort ? "" : ":" + port);
        if (!canonical.equals(value)) {
            throw invalidFrameAncestor();
        }
        return canonical;
    }

    private static String canonicalOriginHost(String host) {
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.startsWith("[") && normalizedHost.endsWith("]")) {
            normalizedHost = normalizedHost.substring(1, normalizedHost.length() - 1);
        }
        if (normalizedHost.indexOf(':') >= 0) {
            if (normalizedHost.indexOf('%') >= 0) {
                throw invalidFrameAncestor();
            }
            byte[] address;
            try {
                address = java.net.InetAddress.getByName(normalizedHost).getAddress();
            } catch (UnknownHostException invalid) {
                throw invalidFrameAncestor();
            }
            if (address.length != 16) {
                throw invalidFrameAncestor();
            }
            int[] groups = new int[8];
            for (int index = 0; index < groups.length; index++) {
                groups[index] = Byte.toUnsignedInt(address[index * 2]) << 8
                        | Byte.toUnsignedInt(address[index * 2 + 1]);
            }
            int bestStart = -1;
            int bestLength = 0;
            for (int start = 0; start < groups.length;) {
                if (groups[start] != 0) {
                    start++;
                    continue;
                }
                int end = start;
                while (end < groups.length && groups[end] == 0) {
                    end++;
                }
                if (end - start > bestLength && end - start >= 2) {
                    bestStart = start;
                    bestLength = end - start;
                }
                start = end;
            }
            var before = new java.util.ArrayList<String>();
            var after = new java.util.ArrayList<String>();
            for (int index = 0; index < groups.length; index++) {
                if (index < bestStart || bestStart == -1) {
                    before.add(Integer.toHexString(groups[index]));
                } else if (index >= bestStart + bestLength) {
                    after.add(Integer.toHexString(groups[index]));
                }
            }
            return bestStart == -1
                    ? String.join(":", before)
                    : String.join(":", before) + "::" + String.join(":", after);
        }
        if (normalizedHost.matches("[0-9.]+")) {
            String[] components = normalizedHost.split("\\.", -1);
            if (components.length != 4) {
                throw invalidFrameAncestor();
            }
            for (String component : components) {
                if (!component.matches("0|[1-9][0-9]{0,2}")
                        || Integer.parseInt(component) > 255) {
                    throw invalidFrameAncestor();
                }
            }
        }
        return normalizedHost;
    }

    private static IllegalArgumentException invalidFrameAncestor() {
        return new IllegalArgumentException(
                "RAVENROOT_UI_FRAME_ANCESTORS entries must be self or canonical HTTP(S) origins");
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
