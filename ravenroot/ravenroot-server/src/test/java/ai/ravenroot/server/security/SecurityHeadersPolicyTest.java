package ai.ravenroot.server.security;

import com.sun.net.httpserver.Headers;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityHeadersPolicyTest {
    @Test
    void workbenchAncestorsDefaultClosedAndOptInWithoutConflictingXFrameOptions() {
        for (Map<String, String> environment : List.<Map<String, String>>of(
                Map.of(), Map.of("RAVENROOT_UI_FRAME_ANCESTORS", " \t "))) {
            var headers = new Headers();
            SecurityHeadersPolicy.fromEnvironment(environment).apply(headers);
            assertTrue(headers.getFirst("Content-Security-Policy")
                    .contains("frame-ancestors 'none'"));
            assertEquals("DENY", headers.getFirst("X-Frame-Options"));
        }

        var headers = new Headers();
        headers.set("X-Frame-Options", "STALE");
        SecurityHeadersPolicy.fromEnvironment(Map.of(
                "RAVENROOT_UI_FRAME_ANCESTORS",
                "https://portal.example,http://localhost:4173,self,https://portal.example"))
                .apply(headers);
        String csp = headers.getFirst("Content-Security-Policy");
        assertTrue(csp.contains(
                "frame-ancestors 'self' http://localhost:4173 https://portal.example"), csp);
        assertFalse(headers.containsKey("X-Frame-Options"));
        assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"));
        assertEquals("no-referrer", headers.getFirst("Referrer-Policy"));
    }

    @Test
    void workbenchAncestorsRejectMalformedUnsafeAndNoncanonicalValuesWithoutEchoingThem() {
        for (String rejected : List.of(
                "*", "'self'", "self,", "https://portal.example/path",
                "https://user@portal.example", "https://portal.example?x=1",
                "https://portal.example#fragment", "https://portal.example:443",
                "https://PORTAL.example", "https://portal.example.",
                "https://portal..", "https://%70ortal.123corp",
                "https://portal.%31%32%33corp", "https://portal.éxample",
                "https://xn--a", "https://xn--0", "https://xn--abc",
                "https://under_score.example", "https://portal..example",
                "https://.portal.example", "https://[::1]:8443",
                "https://[2001:db8::1]:8443",
                "http://127.1", "http://2130706433", "http://017700000001",
                "http://0x", "http://0x7f000001", "http://0X7F000001", "http://0x7f.0.0.1",
                "http://0177.0.0.1", "http://0x7f.01.0x0.1",
                "https://example.1", "https://example.0x1",
                "https://[0:0:0:0:0:0:0:1]",
                "https://portal.example:0", "https://portal.example:65536",
                "https://portal.123corp:0444",
                "ftp://portal.example", "https://portal.example;frame-ancestors *",
                "https://portal.123corp;frame-ancestors*",
                "https://portal.example\r\nX-Frame-Options: ALLOWALL")) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> SecurityHeadersPolicy.fromEnvironment(Map.of(
                            "RAVENROOT_UI_FRAME_ANCESTORS", rejected)), rejected);
            assertTrue(failure.getMessage().startsWith("RAVENROOT_UI_FRAME_ANCESTORS"));
            assertFalse(failure.getMessage().contains(rejected));
            assertEquals(null, failure.getCause());
        }
    }

    @Test
    void workbenchAncestorsAcceptCanonicalDnsIpAndPortForms() {
        for (String accepted : List.of(
                "self", "https://portal.example", "https://1.example",
                "https://0x7f.example", "https://192.168.1.1",
                "http://127.0.0.1:8080",
                "https://portal.123corp", "https://example.123abc",
                "https://example.0xg", "https://example.0x1g",
                "https://123corp", "https://portal.123-corp:444",
                "https://under-score.example", "https://-portal.example",
                "https://portal-.example", "https://xn--bcher-kva.123corp",
                "https://xn--fa-hia.123corp")) {
            assertDoesNotThrow(() -> SecurityHeadersPolicy.fromEnvironment(Map.of(
                    "RAVENROOT_UI_FRAME_ANCESTORS", accepted)), accepted);
        }
    }

    @Test
    void explicitIpv6ExplainsTheCspRepresentationLimit() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> SecurityHeadersPolicy.fromEnvironment(Map.of(
                        "RAVENROOT_UI_FRAME_ANCESTORS", "https://[::1]:8443")));
        assertTrue(failure.getMessage().contains("explicit IPv6"));
        assertTrue(failure.getMessage().contains("use self"));
        assertTrue(failure.getMessage().contains("DNS hostname"));
    }

    @Test
    void frameSourcesAreExactSortedOperatorOriginsAndDefaultClosed() {
        var policy = new SecurityHeadersPolicy(false);
        var closed = new Headers();
        policy.apply(closed);
        assertTrue(closed.getFirst("Content-Security-Policy").endsWith("; frame-src 'none'"));

        var registered = new Headers();
        policy.apply(registered, Set.of(
                URI.create("https://z.example"), URI.create("https://a.example")));
        String csp = registered.getFirst("Content-Security-Policy");
        assertTrue(csp.endsWith("; frame-src https://a.example https://z.example"), csp);
        assertFalse(csp.contains("frame-src *"), csp);
        assertFalse(csp.contains("frame-src 'self'"), csp);
    }

    @Test
    void frameSourcesCannotWidenToArbitraryOrPathBearingValues() {
        var policy = new SecurityHeadersPolicy(false);
        assertThrows(IllegalArgumentException.class,
                () -> policy.apply(new Headers(), Set.of(URI.create("http://provider.example"))));
        assertThrows(IllegalArgumentException.class,
                () -> policy.apply(new Headers(), Set.of(URI.create("https://provider.example/task"))));
        var loopback = new Headers();
        policy.apply(loopback, Set.of(URI.create("http://127.0.0.1:9123")));
        assertEquals("http://127.0.0.1:9123", loopback.getFirst("Content-Security-Policy")
                .substring(loopback.getFirst("Content-Security-Policy").indexOf("frame-src ") + 10));
    }
}
