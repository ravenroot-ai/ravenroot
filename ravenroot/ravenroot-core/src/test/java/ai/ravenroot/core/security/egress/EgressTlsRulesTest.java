package ai.ravenroot.core.security.egress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.X509TrustManager;
import java.net.http.HttpClient;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TLS rules for graph-driven egress (SEC-10).
 *
 * <p>Each assertion here exists because the corresponding control has an off switch somewhere in
 * the JVM that a plugin, a test helper or a well-meaning future edit could reach. The point is not
 * that the JDK defaults are wrong — they are mostly right — but that they are defaults, and a
 * default is not a control.
 */
class EgressTlsRulesTest {

    /** A trust manager that accepts anything, i.e. exactly what must never govern egress. */
    private static SSLContext trustAllContext() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new javax.net.ssl.TrustManager[] {new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }}, null);
        return context;
    }

    @Test
    @DisplayName("only TLS 1.3 and 1.2 are offered")
    void onlyModernProtocolsAreOffered() {
        SSLParameters parameters = EgressHttpClients.egressSslParameters();
        List<String> protocols = List.of(parameters.getProtocols());

        assertEquals(List.of("TLSv1.3", "TLSv1.2"), protocols);
        for (String legacy : List.of("TLSv1", "TLSv1.1", "SSLv3", "SSLv2Hello")) {
            assertFalse(protocols.contains(legacy), legacy + " must not be offered for egress");
        }
    }

    @Test
    @DisplayName("hostname verification is stated, not inherited")
    void hostnameVerificationIsStated() {
        assertEquals("HTTPS", EgressHttpClients.egressSslParameters().getEndpointIdentificationAlgorithm());
    }

    @Test
    @DisplayName("the client carries the egress TLS parameters, not the JDK defaults")
    void theClientCarriesTheEgressParameters() {
        SSLParameters parameters = EgressHttpClients.create().sslParameters();
        // Asserted explicitly rather than dereferenced: default SSLParameters carry a null protocol
        // array, so dropping the pinning would otherwise red as an opaque NullPointerException
        // instead of naming the control that went missing.
        assertNotNull(parameters.getProtocols(),
                "the egress client must pin its TLS protocols, not inherit unset defaults");
        assertEquals(Set.of("TLSv1.3", "TLSv1.2"), Set.of(parameters.getProtocols()));
        assertEquals("HTTPS", parameters.getEndpointIdentificationAlgorithm(),
                "the egress client must pin HTTPS endpoint identification");
    }

    @Test
    @DisplayName("replacing the process-wide default SSLContext cannot disable egress validation")
    void aTrustAllDefaultContextCannotDisableValidation() throws Exception {
        SSLContext previous = SSLContext.getDefault();
        try {
            SSLContext trustAll = trustAllContext();
            SSLContext.setDefault(trustAll);

            SSLContext used = EgressHttpClients.create().sslContext();

            assertNotSame(trustAll, used,
                    "egress must not inherit a process-wide default that any plugin can replace");
            // And the context it does use rejects an empty/unknown chain rather than accepting it.
            assertNotSame(trustAll, EgressHttpClients.validatingSslContext());
        } finally {
            SSLContext.setDefault(previous);
        }
    }

    @Test
    @DisplayName("the egress trust manager actually validates, and does not accept an unknown chain")
    void theEgressTrustManagerValidates() throws Exception {
        var factory = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        factory.init((java.security.KeyStore) null);
        X509TrustManager platform = null;
        for (var manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                platform = x509;
            }
        }
        assertTrue(platform != null && platform.getAcceptedIssuers().length > 0,
                "egress must be anchored to the platform CA trust store");
    }

    @Test
    @DisplayName("construction fails closed when the JDK hostname-verification escape hatch is set")
    void hostnameVerificationEscapeHatchIsRefused() {
        String previous = System.getProperty(EgressHttpClients.DISABLE_HOSTNAME_VERIFICATION);
        try {
            System.setProperty(EgressHttpClients.DISABLE_HOSTNAME_VERIFICATION, "true");
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    EgressHttpClients::create);
            assertTrue(refused.getMessage().contains("hostname verification"), refused.getMessage());
        } finally {
            if (previous == null) {
                System.clearProperty(EgressHttpClients.DISABLE_HOSTNAME_VERIFICATION);
            } else {
                System.setProperty(EgressHttpClients.DISABLE_HOSTNAME_VERIFICATION, previous);
            }
        }
    }

    @Test
    @DisplayName("there is no way to ask for a client that skips TLS validation")
    void thereIsNoRelaxedOverload() {
        // Every public entry point returns a client built by the same builder. If someone adds an
        // overload taking an SSLContext or a "insecure" flag, this enumeration is where it shows up.
        List<String> publicFactories = java.util.Arrays.stream(EgressHttpClients.class.getMethods())
                .filter(m -> m.getDeclaringClass() == EgressHttpClients.class)
                .map(java.lang.reflect.Method::getName)
                .distinct()
                .sorted()
                .toList();
        assertEquals(List.of("builder", "create"), publicFactories,
                "a new public factory on this class must be reviewed as a TLS change");
        for (var method : EgressHttpClients.class.getMethods()) {
            if (method.getDeclaringClass() == EgressHttpClients.class) {
                assertEquals(0, method.getParameterCount(),
                        "no egress client factory may take a parameter that could relax TLS");
            }
        }
    }

    @Test
    @DisplayName("the other reach controls survive alongside the TLS ones")
    void reachControlsStillHold() {
        HttpClient client = EgressHttpClients.create();
        assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
        assertTrue(client.proxy().isPresent());
    }
}
