package ai.ravenroot.core.security.egress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the absence of a proxy bypass as a control rather than an accident (SEC-10).
 *
 * <p>Previously nothing here could fail. The HTTP node had no proxy bypass only because the builder
 * was never told to use a proxy — an absent feature, not a control. Adding
 * {@code .proxy(ProxySelector.getDefault())} later would reintroduce the bypass in silence and no
 * test would have noticed. These assertions red exactly then.
 *
 * <p>Why it matters for SSRF specifically: a proxy resolves the destination name itself, on the far
 * side of this process. Every address filter installed here would be bypassed, because the socket
 * this process opens goes to the proxy and the proxy opens the one that counts.
 */
class EgressProxyAbsenceTest {

    @Test
    @DisplayName("the egress client declares NO_PROXY explicitly")
    void egressClientDeclaresNoProxy() {
        HttpClient client = EgressHttpClients.create();
        Optional<ProxySelector> selector = client.proxy();

        assertTrue(selector.isPresent(),
                "an absent selector means the client fell back to the default, which is the bypass");
        assertSame(HttpClient.Builder.NO_PROXY, selector.get(),
                "egress must be pinned to NO_PROXY, not to whatever ProxySelector.getDefault() returns");
    }

    @Test
    @DisplayName("NO_PROXY selects a direct connection even when system properties name a proxy")
    void systemProxyPropertiesCannotRouteEgress() {
        String previousHost = System.getProperty("http.proxyHost");
        String previousPort = System.getProperty("http.proxyPort");
        try {
            System.setProperty("http.proxyHost", "attacker.proxy.invalid");
            System.setProperty("http.proxyPort", "3128");

            ProxySelector selector = EgressHttpClients.create().proxy().orElseThrow();
            List<Proxy> chosen = selector.select(URI.create("http://example.invalid/resource"));

            assertEquals(1, chosen.size());
            assertSame(Proxy.NO_PROXY, chosen.get(0),
                    "a system-property proxy must not be able to intercept graph-driven egress");
        } finally {
            restore("http.proxyHost", previousHost);
            restore("http.proxyPort", previousPort);
        }
    }

    @Test
    @DisplayName("a default ProxySelector installed in the JVM cannot capture egress")
    void aDefaultProxySelectorCannotCaptureEgress() {
        ProxySelector previous = ProxySelector.getDefault();
        try {
            ProxySelector.setDefault(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    return List.of(new Proxy(Proxy.Type.HTTP,
                            new InetSocketAddress("attacker.proxy.invalid", 3128)));
                }

                @Override
                public void connectFailed(URI uri, SocketAddress sa, java.io.IOException ioe) {
                }
            });

            ProxySelector selector = EgressHttpClients.create().proxy().orElseThrow();
            assertSame(Proxy.NO_PROXY, selector.select(URI.create("https://example.invalid/")).get(0));
        } finally {
            ProxySelector.setDefault(previous);
        }
    }

    @Test
    @DisplayName("the egress client never follows a redirect, which is a second unvalidated destination")
    void egressClientNeverFollowsRedirects() {
        assertEquals(HttpClient.Redirect.NEVER, EgressHttpClients.create().followRedirects());
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
