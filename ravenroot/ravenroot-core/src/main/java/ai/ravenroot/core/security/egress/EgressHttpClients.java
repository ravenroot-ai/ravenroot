package ai.ravenroot.core.security.egress;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.util.List;

/**
 * The one way Ravenroot builds an HTTP client for graph-driven egress (SEC-10).
 *
 * <h2>Why {@code NO_PROXY} is written down instead of left out</h2>
 * Previously there was no proxy bypass here for one reason only: the builder was never told to use
 * a proxy, so {@code HttpClient} defaulted to none and never consulted {@code http.proxyHost},
 * {@code https.proxyHost} or {@code ProxySelector.getDefault()}. That is <b>the absence of a
 * feature, not the presence of a control.</b> Anyone adding {@code .proxy(ProxySelector.getDefault())}
 * later — for a perfectly good reason, such as making the product work behind a corporate proxy —
 * would reintroduce the bypass silently, and nothing in the build would have failed.
 *
 * <p>Setting {@link HttpClient.Builder#NO_PROXY} explicitly turns that absence into an assertion
 * that a test can pin: egress must reach the destination the policy validated, not a proxy that
 * would reach it on the node's behalf and defeat the address filter by resolving the name itself.
 *
 * <p>Redirects are {@code NEVER} followed for the same class of reason: a redirect is a second
 * destination that the policy never saw.
 *
 * <h2>TLS rules</h2>
 * Three, and the reasoning behind each is the same one that deleted this class's own kill switch —
 * a control with an off switch is a control a later edit can disable without scrutiny.
 * <ol>
 *   <li><b>Minimum protocol version.</b> Only TLS 1.3 and TLS 1.2 are offered. Everything older is
 *       broken in ways that matter to a client fetching attacker-influenced URLs.</li>
 *   <li><b>Certificate validation that cannot be disabled.</b> The context is built here from the
 *       platform trust anchors rather than taken from {@link SSLContext#getDefault()}.
 *       {@code SSLContext.setDefault} is process-global and writable by any code in the JVM,
 *       including a plugin; a trust-all context installed there would silently disable certificate
 *       validation for egress. Building our own makes that impossible rather than merely unlikely.
 *       There is deliberately no parameter, property or overload that relaxes this.</li>
 *   <li><b>Hostname verification that cannot be switched off.</b> {@code HTTPS} endpoint
 *       identification is set explicitly, and construction <em>fails</em> when the JDK's
 *       {@code jdk.internal.httpclient.disableHostnameVerification} escape hatch is set. Failing
 *       closed is the point: a certificate that is valid for some other host is exactly what an
 *       SSRF redirect or a rebinding attack would present.</li>
 * </ol>
 *
 * <p><b>Deliberately not configured here:</b> whether to drop TLS 1.2 and require 1.3 only (a
 * compatibility call, not a security one); whether egress should trust a private CA
 * bundle for internal endpoints; whether client certificates (mTLS) are required for egress; and
 * whether to pin certificates for named hosts. Each needs configuration and an operator contract
 * that does not exist yet, and guessing one would be worse than naming it.
 *
 * <p>Scope: this client governs reach. Connection pooling and concurrency belong elsewhere, as does
 * volume policy.
 */
public final class EgressHttpClients {

    /**
     * The JDK property that turns off hostname verification for {@code HttpClient}. Read statically
     * by the JDK at class-initialisation time, so it cannot be neutralised from here — only
     * detected and refused.
     */
    static final String DISABLE_HOSTNAME_VERIFICATION = "jdk.internal.httpclient.disableHostnameVerification";

    /** Newest first. Anything below TLS 1.2 is not offered at all. */
    static final List<String> ALLOWED_PROTOCOLS = List.of("TLSv1.3", "TLSv1.2");

    private EgressHttpClients() {
    }

    /** Builds a client that cannot follow a redirect, cannot be proxied and cannot skip TLS checks. */
    public static HttpClient create() {
        return builder().build();
    }

    /** The builder behind {@link #create()}, so callers add settings without dropping the controls. */
    public static HttpClient.Builder builder() {
        requireHostnameVerification();
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .sslContext(validatingSslContext())
                .sslParameters(egressSslParameters());
    }

    /** Fails closed when the JDK's hostname-verification escape hatch has been set. */
    static void requireHostnameVerification() {
        if (Boolean.parseBoolean(System.getProperty(DISABLE_HOSTNAME_VERIFICATION))) {
            throw new IllegalStateException("Outbound TLS refuses to start with "
                    + DISABLE_HOSTNAME_VERIFICATION + " set: hostname verification is not optional "
                    + "for graph-driven egress");
        }
    }

    /** TLS 1.3/1.2 with HTTPS endpoint identification, stated rather than inherited. */
    static SSLParameters egressSslParameters() {
        SSLParameters parameters = new SSLParameters();
        parameters.setProtocols(ALLOWED_PROTOCOLS.toArray(String[]::new));
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        return parameters;
    }

    /**
     * A context built from the platform trust anchors, independent of {@link SSLContext#getDefault()}
     * so that replacing the process-wide default cannot disable validation for egress.
     */
    static SSLContext validatingSslContext() {
        try {
            TrustManagerFactory trustManagers =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            // A null KeyStore means "the platform's default CA trust store".
            trustManagers.init((java.security.KeyStore) null);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException e) {
            // Failing closed: egress without certificate validation is not a degraded mode worth
            // offering, so there is no fallback to SSLContext.getDefault() here.
            throw new IllegalStateException("Cannot build a validating TLS context for egress", e);
        }
    }
}
