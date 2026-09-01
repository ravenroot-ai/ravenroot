package ai.ravenroot.server.embed;

import java.net.URI;
import java.util.Locale;

/** Shared canonical grammar for viewer and registered-parent HTTPS origins. */
final class EmbedHttpsOrigin {
    private EmbedHttpsOrigin() { }

    static String canonical(String value, String role) {
        if (value == null || value.isBlank() || "null".equals(value) || "*".equals(value)
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid(role);
        }
        final URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException malformed) {
            throw invalid(role, malformed);
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty())
                || uri.getPort() == 443 || uri.getPort() < -1 || uri.getPort() > 65_535) {
            throw invalid(role);
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String renderedHost = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        String rendered = "https://" + renderedHost + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        if (!rendered.equals(value)) throw invalid(role);
        return rendered;
    }

    private static IllegalArgumentException invalid(String role) {
        return new IllegalArgumentException(role + " must be one canonical HTTPS origin");
    }

    private static IllegalArgumentException invalid(String role, RuntimeException cause) {
        return new IllegalArgumentException(role + " must be one canonical HTTPS origin", cause);
    }
}
