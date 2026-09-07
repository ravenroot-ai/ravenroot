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
        String name = "RAVENROOT_SSE_AUTH_REVALIDATION_SECONDS";
        String declared = environment.get(name);
        long seconds = 30;
        try {
            if (declared != null && !declared.isBlank()) {
                seconds = Long.parseLong(declared);
            }
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " must be an integer between 1 and 300");
        }
        if (seconds < 1 || seconds > 300) {
            throw new IllegalArgumentException(name + " must be an integer between 1 and 300");
        }
        return new HttpSecurityConfiguration(BrowserOriginPolicy.fromEnvironment(environment, port),
                SecurityHeadersPolicy.fromEnvironment(environment), Duration.ofSeconds(seconds));
    }
}
