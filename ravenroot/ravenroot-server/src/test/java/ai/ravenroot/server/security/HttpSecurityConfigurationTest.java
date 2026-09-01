package ai.ravenroot.server.security;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpSecurityConfigurationTest {
    @Test
    void browserOriginsAreExplicitCanonicalAndFailFast() {
        var configuration = HttpSecurityConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_BROWSER_ALLOWED_ORIGINS",
                "https://console.example,http://localhost:5173"), 8080);
        assertEquals(30, configuration.sseAuthenticationRevalidation().toSeconds());

        assertThrows(IllegalArgumentException.class, () -> HttpSecurityConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_BROWSER_ALLOWED_ORIGINS", "*"), 8080));
        assertThrows(IllegalArgumentException.class, () -> HttpSecurityConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_BROWSER_ALLOWED_ORIGINS", "https://console.example/path"), 8080));
        assertThrows(IllegalArgumentException.class, () -> HttpSecurityConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_BROWSER_ALLOWED_ORIGINS", "https://console.example,"), 8080));
    }

    @Test
    void hstsRequiresAnExplicitTrustedTlsTerminatorContract() {
        assertThrows(IllegalArgumentException.class, () -> HttpSecurityConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_PUBLIC_ORIGIN", "https://ravenroot.example"), 8080));
        assertThrows(IllegalArgumentException.class, () -> HttpSecurityConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_TRUSTED_TLS_TERMINATOR", "true",
                "RAVENROOT_PUBLIC_ORIGIN", "http://ravenroot.example"), 8080));

        var configuration = HttpSecurityConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_TRUSTED_TLS_TERMINATOR", "true",
                "RAVENROOT_PUBLIC_ORIGIN", "https://ravenroot.example"), 8080);
        var headers = new com.sun.net.httpserver.Headers();
        configuration.responseHeaders().apply(headers);
        assertEquals("max-age=31536000; includeSubDomains",
                headers.getFirst("Strict-Transport-Security"));
    }

    @Test
    void uiConnectOriginsAreSeparateCanonicalCspDestinations() {
        var configuration = HttpSecurityConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_BROWSER_ALLOWED_ORIGINS", "https://caller.example",
                "RAVENROOT_UI_CONNECT_ORIGINS",
                "https://api.example,http://127.0.0.1:8080,http://localhost:5173,http://[::1]:8080"), 8080);
        var headers = new com.sun.net.httpserver.Headers();
        configuration.responseHeaders().apply(headers);
        String csp = headers.getFirst("Content-Security-Policy");
        org.junit.jupiter.api.Assertions.assertTrue(csp.contains(
                "connect-src 'self' http://127.0.0.1:8080 http://[::1]:8080 "
                        + "http://localhost:5173 https://api.example"));
        assertEquals(1, csp.split(java.util.regex.Pattern.quote("http://[::1]:8080"), -1).length - 1);
        org.junit.jupiter.api.Assertions.assertFalse(csp.contains("https://caller.example"));

        for (String rejected : java.util.List.of("*", "null", "http://api.example",
                "https://api.example/path", "https://user@api.example", "https://api.example?q=1",
                "https://api.example#fragment", "https://api.example:443", "https://API.example")) {
            assertThrows(IllegalArgumentException.class, () -> HttpSecurityConfiguration.fromEnvironment(Map.of(
                    "RAVENROOT_UI_CONNECT_ORIGINS", rejected), 8080), rejected);
        }
    }

    @Test
    void leaseIntervalIsBoundedAndStrictlyParsed() {
        assertThrows(IllegalArgumentException.class, () -> HttpSecurityConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_SSE_AUTH_REVALIDATION_SECONDS", "0"), 8080));
        assertThrows(IllegalArgumentException.class, () -> HttpSecurityConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_SSE_AUTH_REVALIDATION_SECONDS", "301"), 8080));
        assertThrows(IllegalArgumentException.class, () -> HttpSecurityConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_SSE_AUTH_REVALIDATION_SECONDS", "often"), 8080));
    }
}
