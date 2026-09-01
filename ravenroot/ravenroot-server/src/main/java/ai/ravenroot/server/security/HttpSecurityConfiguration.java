package ai.ravenroot.server.security;

import java.time.Duration;
import java.util.Map;

/** Fail-fast browser boundary, response headers and long-lived authentication lease settings. */
public record HttpSecurityConfiguration(BrowserOriginPolicy browserOrigins,
                                        SecurityHeadersPolicy responseHeaders,
                                        Duration sseAuthenticationRevalidation) {
    public HttpSecurityConfiguration {
        java.util.Objects.requireNonNull(browserOrigins, "browserOrigins");
        java.util.Objects.requireNonNull(responseHeaders, "responseHeaders");
        if (sseAuthenticationRevalidation == null
                || sseAuthenticationRevalidation.compareTo(Duration.ofSeconds(1)) < 0
                || sseAuthenticationRevalidation.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("SSE authentication revalidation must be between 1 and 300 seconds");
        }
    }

    public static HttpSecurityConfiguration fromEnvironment(Map<String, String> environment, int port) {
        long seconds;
        try {
            seconds = Long.parseLong(environment.getOrDefault(
                    "RAVENROOT_SSE_AUTH_REVALIDATION_SECONDS", "30"));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(
                    "RAVENROOT_SSE_AUTH_REVALIDATION_SECONDS must be an integer", invalid);
        }
        return new HttpSecurityConfiguration(BrowserOriginPolicy.fromEnvironment(environment, port),
                SecurityHeadersPolicy.fromEnvironment(environment), Duration.ofSeconds(seconds));
    }
}
