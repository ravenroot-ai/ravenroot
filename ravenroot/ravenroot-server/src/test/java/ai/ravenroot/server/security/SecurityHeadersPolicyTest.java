package ai.ravenroot.server.security;

import com.sun.net.httpserver.Headers;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityHeadersPolicyTest {
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
