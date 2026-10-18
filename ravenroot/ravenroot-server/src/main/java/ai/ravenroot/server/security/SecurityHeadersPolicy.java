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
        if (scheme == null || !("http".equals(scheme) || "https".equals(scheme))
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
            throw invalidFrameAncestor();
        }
        OriginAuthority authority = canonicalOriginAuthority(uri);
        String host = authority.host();
        int port = authority.port();
        if (port == 0 || port > 65535) {
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

    private static OriginAuthority canonicalOriginAuthority(URI uri) {
        if (uri.getHost() != null) {
            return new OriginAuthority(uri.getHost(), uri.getPort());
        }
        // URI#getHost omits browser-valid ASCII names whose final label starts with a digit.
        // Parse only the CSP-safe ASCII hostname grammar shared with the UI-only runtime.
        String rawAuthority = uri.getRawAuthority();
        if (rawAuthority == null || rawAuthority.isEmpty()) {
            throw invalidFrameAncestor();
        }
        int separator = rawAuthority.lastIndexOf(':');
        if (separator != rawAuthority.indexOf(':')) {
            throw invalidFrameAncestor();
        }
        String host = separator == -1 ? rawAuthority : rawAuthority.substring(0, separator);
        String portText = separator == -1 ? null : rawAuthority.substring(separator + 1);
        if (host.isEmpty() || !host.matches("[A-Za-z0-9._-]+")) {
            throw invalidFrameAncestor();
        }
        if (portText == null) {
            return new OriginAuthority(host, -1);
        }
        if (!portText.matches("[0-9]+")) {
            throw invalidFrameAncestor();
        }
        try {
            return new OriginAuthority(host, Integer.parseInt(portText));
        } catch (NumberFormatException invalid) {
            throw invalidFrameAncestor();
        }
    }

    private record OriginAuthority(String host, int port) {
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
        for (String label : normalizedHost.split("\\.", -1)) {
            if (label.startsWith("xn--") && !validPunycodeLabel(label.substring(4))) {
                throw invalidFrameAncestor();
            }
        }
        if (endsInWhatwgIpv4Number(normalizedHost)) {
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

    private static boolean validPunycodeLabel(String encoded) {
        if (encoded.isEmpty()) {
            return false;
        }
        var decoded = new java.util.ArrayList<Integer>();
        int delimiter = encoded.lastIndexOf('-');
        int cursor = 0;
        if (delimiter >= 0) {
            for (; cursor < delimiter; cursor++) {
                char value = encoded.charAt(cursor);
                if (value >= 0x80) {
                    return false;
                }
                decoded.add((int) value);
            }
            cursor++;
        }
        long codePoint = 128;
        long delta = 0;
        long bias = 72;
        while (cursor < encoded.length()) {
            long previousDelta = delta;
            long weight = 1;
            for (long thresholdBase = 36;; thresholdBase += 36) {
                if (cursor >= encoded.length()) {
                    return false;
                }
                int digit = punycodeDigit(encoded.charAt(cursor++));
                if (digit < 0 || digit > (Long.MAX_VALUE - delta) / weight) {
                    return false;
                }
                delta += digit * weight;
                long threshold = thresholdBase <= bias ? 1
                        : Math.min(thresholdBase - bias, 26);
                if (digit < threshold) {
                    break;
                }
                if (weight > Long.MAX_VALUE / (36 - threshold)) {
                    return false;
                }
                weight *= 36 - threshold;
            }
            int outputSize = decoded.size() + 1;
            bias = adaptPunycodeBias(delta - previousDelta, outputSize, previousDelta == 0);
            long increment = delta / outputSize;
            if (increment > Character.MAX_CODE_POINT - codePoint) {
                return false;
            }
            codePoint += increment;
            delta %= outputSize;
            if (!Character.isValidCodePoint((int) codePoint)
                    || codePoint <= 0x9f
                    || (codePoint >= 0xd800 && codePoint <= 0xdfff)
                    || (codePoint >= 0xfdd0 && codePoint <= 0xfdef)
                    || (codePoint & 0xfffe) == 0xfffe) {
                return false;
            }
            decoded.add((int) delta, (int) codePoint);
            delta++;
        }
        return decoded.stream().anyMatch(value -> value >= 0x80);
    }

    private static int punycodeDigit(char value) {
        if (value >= 'a' && value <= 'z') {
            return value - 'a';
        }
        if (value >= '0' && value <= '9') {
            return value - '0' + 26;
        }
        return -1;
    }

    private static long adaptPunycodeBias(long delta, int outputSize, boolean firstCodePoint) {
        delta = firstCodePoint ? delta / 700 : delta / 2;
        delta += delta / outputSize;
        long adjustment = 0;
        while (delta > 455) {
            delta /= 35;
            adjustment += 36;
        }
        return adjustment + 36 * delta / (delta + 38);
    }

    /**
     * WHATWG URL parsing treats a host as IPv4 when its final label is a number. Besides
     * decimal digits, an IPv4 number may use a 0x hexadecimal prefix (including bare 0x).
     * Only canonical dotted-decimal addresses survive the validation above; every alternate
     * spelling would be normalized by browsers and must therefore be rejected at startup.
     */
    private static boolean endsInWhatwgIpv4Number(String host) {
        String finalLabel = host.substring(host.lastIndexOf('.') + 1);
        if (finalLabel.matches("[0-9]+")) {
            return true;
        }
        return finalLabel.startsWith("0x")
                && finalLabel.substring(2).matches("[0-9a-f]*");
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
