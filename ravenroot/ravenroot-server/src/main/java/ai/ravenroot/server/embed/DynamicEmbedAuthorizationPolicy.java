package ai.ravenroot.server.embed;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Default-off origin policy for dynamic read-only embedding. */
public record DynamicEmbedAuthorizationPolicy(Mode mode, Set<String> allowedOrigins, String revision) {
    public static final String MODE_VARIABLE = "RAVENROOT_EMBED_DYNAMIC_ORIGIN_POLICY";
    public static final String ORIGINS_VARIABLE = "RAVENROOT_EMBED_DYNAMIC_ALLOWED_ORIGINS";

    public enum Mode { DISABLED, RESTRICTED, AUTHENTICATED }

    public DynamicEmbedAuthorizationPolicy {
        Objects.requireNonNull(mode, "mode");
        allowedOrigins = Set.copyOf(Objects.requireNonNull(allowedOrigins, "allowedOrigins"));
        if (revision == null || revision.isBlank()) throw new IllegalArgumentException("policy revision is blank");
        if (mode == Mode.DISABLED && !allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("disabled dynamic policy cannot carry origins");
        }
        if (mode == Mode.RESTRICTED && allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("restricted dynamic policy requires allowed origins");
        }
    }

    public static DynamicEmbedAuthorizationPolicy fromEnvironment(Map<String, String> environment,
                                                                   EmbedViewerOrigin viewer) {
        Objects.requireNonNull(environment, "environment");
        String raw = environment.getOrDefault(MODE_VARIABLE, "disabled").trim();
        Mode mode = switch (raw) {
            case "disabled" -> Mode.DISABLED;
            case "restricted" -> Mode.RESTRICTED;
            case "authenticated" -> Mode.AUTHENTICATED;
            default -> throw new IllegalArgumentException(MODE_VARIABLE
                    + " must be disabled, restricted, or authenticated");
        };
        var origins = new LinkedHashSet<String>();
        String configured = environment.get(ORIGINS_VARIABLE);
        if (configured != null && !configured.isBlank()) {
            for (String value : configured.split(",", -1)) {
                String canonical = canonicalParent(value.trim());
                if (viewer != null && viewer.value().equals(canonical)) {
                    throw new IllegalArgumentException("dynamic parent and viewer origins must be distinct");
                }
                origins.add(canonical);
            }
        }
        if (mode != Mode.RESTRICTED && !origins.isEmpty()) {
            throw new IllegalArgumentException(ORIGINS_VARIABLE + " applies only to restricted policy");
        }
        String canonicalPolicy = mode.name() + "\n" + origins.stream().sorted()
                .collect(java.util.stream.Collectors.joining("\n"));
        String revision = policyDigest(canonicalPolicy);
        return new DynamicEmbedAuthorizationPolicy(mode, origins, "dynamic-origin-v1:" + revision);
    }

    public boolean enabled() { return mode != Mode.DISABLED; }

    /** Returns the exact canonical origin captured by a grant, or throws to deny it. */
    public String requireAllowed(String supplied, EmbedViewerOrigin viewer) {
        if (!enabled()) throw new IllegalArgumentException("dynamic embed policy is disabled");
        String canonical = canonicalParent(supplied);
        if (viewer.value().equals(canonical)) {
            throw new IllegalArgumentException("dynamic parent and viewer origins must be distinct");
        }
        if (mode == Mode.RESTRICTED && !allowedOrigins.contains(canonical)) {
            throw new IllegalArgumentException("dynamic parent origin is not allowed");
        }
        return canonical;
    }

    static String canonicalParent(String value) {
        if (value == null || value.isBlank() || "null".equals(value) || "*".equals(value)
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("embed parent origin is invalid");
        }
        final URI uri;
        try { uri = URI.create(value); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("embed parent origin is invalid", invalid); }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPort() < -1 || uri.getPort() > 65_535
                || (uri.getPath() != null && !uri.getPath().isEmpty())) {
            throw new IllegalArgumentException("embed parent origin is invalid");
        }
        host = host.toLowerCase(Locale.ROOT);
        boolean secure = "https".equals(scheme);
        boolean loopbackHttp = "http".equals(scheme) && loopback(host);
        if (!secure && !loopbackHttp) throw new IllegalArgumentException("embed parent origin is not secure");
        if ((secure && uri.getPort() == 443) || (loopbackHttp && uri.getPort() == 80)) {
            throw new IllegalArgumentException("embed parent origin must omit its default port");
        }
        String renderedHost = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        String rendered = scheme + "://" + renderedHost + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        if (!rendered.equals(value)) throw new IllegalArgumentException("embed parent origin is not canonical");
        return rendered;
    }

    private static boolean loopback(String host) {
        String address = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        return "localhost".equals(address) || "127.0.0.1".equals(address) || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address);
    }

    private static String policyDigest(String canonicalPolicy) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(
                            canonicalPolicy.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
