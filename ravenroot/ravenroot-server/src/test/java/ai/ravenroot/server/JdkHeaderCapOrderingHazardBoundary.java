package ai.ravenroot.server;

import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.audit.StructuredAuthorizationLogger;
import ai.ravenroot.server.ratelimit.RateLimitAuditSink;
import ai.ravenroot.server.ratelimit.RateLimitConfiguration;
import ai.ravenroot.server.ratelimit.RateLimiter;
import ai.ravenroot.server.ratelimit.TrustedProxyConfiguration;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.BrowserOriginPolicy;
import ai.ravenroot.server.security.HttpSecurityConfiguration;
import ai.ravenroot.server.security.RequestAuthenticator;
import ai.ravenroot.server.security.SecurityHeadersPolicy;
import com.sun.net.httpserver.HttpServer;

import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Run out of process by {@link JdkHeaderCapOrderingHazardTest}, this boundary reproduces or refutes
 * two claims about {@link RavenrootServer}'s static initializer -- see that class's "Set here,
 * verified at {@code start()}" Javadoc.
 *
 * <p>Deliberately a separate, freshly launched JVM rather than an in-process JUnit fixture: both claims
 * are about which code touches {@code com.sun.net.httpserver} <em>first</em> in a process, which this
 * module's own Surefire run cannot settle by construction -- it runs every test class in one shared,
 * already-forked JVM, so "first" would mean "whichever test class Surefire happens to schedule first",
 * not the property under test. A fresh {@code java} invocation is the only way to control that ordering
 * on purpose instead of assuming it, mirroring the QA-03 kill-cell boundary processes already in this
 * module ({@code qa03/DeploymentIngressKillBoundary}).
 *
 * <h2>Markers</h2>
 * <p>{@link #HAZARD_DETECTED} / {@link #HAZARD_NOT_DETECTED}: for {@code args[0] = "foreign-first"} --
 * a plain {@code com.sun.net.httpserver.HttpServer} is created before anything touches
 * {@link RavenrootServer}, reproducing what {@code security/TestOidcProvider.java} would do to this
 * module's own test suite if it created the foreign server first. The boundary expects
 * {@link RavenrootServer#start()} to fail fast with the diagnostic {@link IllegalStateException}, and
 * says so with {@link #HAZARD_DETECTED}; if it does not throw, {@link #HAZARD_NOT_DETECTED} says the
 * safety net itself is broken.</p>
 * <p>{@link #PROPERTY_AFTER_STATIC_INIT_PREFIX}: for {@code args[0] = "operator-override"} -- launched with
 * {@code -Dsun.net.httpserver.maxReqHeaderSize=<value>} already set on this JVM's own command line (the
 * one place setting it is guaranteed to apply before any class loads), with no foreign server. Prints
 * the property's value exactly as {@link RavenrootServer} leaves it, so the test can assert the static
 * initializer left an operator's own value untouched.</p>
 */
final class JdkHeaderCapOrderingHazardBoundary {
    static final String FOREIGN_SERVER_UP = "FOREIGN_SERVER_UP";
    static final String HAZARD_DETECTED = "HAZARD_DETECTED";
    static final String HAZARD_NOT_DETECTED = "HAZARD_NOT_DETECTED";
    /**
     * Named for what it literally reads -- {@code System.getProperty}, i.e. the static property, not the
     * JDK's own live cap. The two can diverge, so a name like "EFFECTIVE_PROPERTY" would be inaccurate.
     * {@link JdkHeaderCapOrderingHazardTest#anOperatorsExplicitPropertyIsReadNotOverwritten} still pins
     * both halves despite the modest name here: it asserts this value equals the operator's override
     * <em>and</em>, separately, that the boundary process exited {@code 0} -- which in the
     * {@code operator-override} scenario only happens if {@link RavenrootServer#start()} did not throw,
     * which only happens if {@code verifyRequestHeaderCapTookEffect()}'s own self-addressed probe
     * confirmed the live cap, not just the property, matches. The property alone is what this constant
     * names; the pair of assertions is what the test actually establishes.
     */
    static final String PROPERTY_AFTER_STATIC_INIT_PREFIX = "PROPERTY_AFTER_STATIC_INIT=";
    /**
     * {@code args[0] = "overflow-override"} sets {@code sun.net.httpserver.maxReqHeaderSize} to a
     * value this class parses fine as a {@code long} but that overflows {@code int} -- exactly what
     * {@code ServerConfig} itself reads with {@code Integer.getInteger}, silently falling back to its own
     * 380 KiB default. These two markers are printed only after {@link RavenrootServer#start()} returned
     * normally, from probes sent with {@link RavenrootServer#probeHeaderCap} directly against the real,
     * running server (not a throwaway one) -- proof, not assumption, that the live cap really is the JDK
     * default: a header comfortably under it must still be accepted, and one comfortably over it must
     * still be rejected, exactly as if the property had never been set.
     */
    static final String OVERFLOW_SMALL_PROBE_OK = "OVERFLOW_SMALL_PROBE_OK";
    static final String OVERFLOW_LARGE_PROBE_REJECTED = "OVERFLOW_LARGE_PROBE_REJECTED";
    /** Printed instead of the marker above when a probe's outcome was not the expected one. */
    static final String OVERFLOW_PROBE_UNEXPECTED_PREFIX = "OVERFLOW_PROBE_UNEXPECTED=";

    private JdkHeaderCapOrderingHazardBoundary() {
    }

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "";
        switch (scenario) {
            case "foreign-first" -> foreignFirst();
            case "operator-override" -> operatorOverride();
            case "overflow-override" -> overflowOverride();
            default -> throw new IllegalArgumentException("unknown scenario: " + scenario);
        }
    }

    /** Reproduces the hazard directly: a foreign HttpServer, created before RavenrootServer exists. */
    private static void foreignFirst() throws Exception {
        HttpServer foreign = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        foreign.start();
        System.out.println(FOREIGN_SERVER_UP);
        System.out.flush();

        var engine = new PekkoExecutionEngine("hazard-boundary-" + System.nanoTime());
        var quiet = new PrintStream(java.io.OutputStream.nullOutputStream());
        var limiter = new RateLimiter(RateLimitConfiguration.DEFAULTS, TrustedProxyConfiguration.direct(),
                RateLimitAuditSink.discarding());
        var server = new RavenrootServer(
                new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, true,
                tenantAuthenticator(), httpSecurity(), Clock.systemUTC(),
                new DefaultAuthorizationService(new StructuredAuthorizationLogger(quiet)), limiter);
        try {
            server.start();
            System.out.println(HAZARD_NOT_DETECTED);
            System.out.flush();
            System.exit(2);
        } catch (IllegalStateException hazardCaught) {
            System.out.println(HAZARD_DETECTED + ": " + hazardCaught.getMessage());
            System.out.flush();
        } finally {
            try {
                server.close();
            } catch (RuntimeException ignoredCleanupFailureAfterAlreadyReportingTheOutcome) {
                // The outcome is already on stdout; a cleanup failure on a server that may never have
                // fully started must not overwrite the exit code the test is reading.
            }
            engine.close();
            foreign.stop(0);
        }
        System.exit(0);
    }

    /** No foreign server: relies on -Dsun.net.httpserver.maxReqHeaderSize=... already set on argv. */
    private static void operatorOverride() throws Exception {
        var engine = new PekkoExecutionEngine("override-boundary-" + System.nanoTime());
        var quiet = new PrintStream(java.io.OutputStream.nullOutputStream());
        var limiter = new RateLimiter(RateLimitConfiguration.DEFAULTS, TrustedProxyConfiguration.direct(),
                RateLimitAuditSink.discarding());
        var server = new RavenrootServer(
                new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, true,
                tenantAuthenticator(), httpSecurity(), Clock.systemUTC(),
                new DefaultAuthorizationService(new StructuredAuthorizationLogger(quiet)), limiter);
        try {
            server.start();
            System.out.println(PROPERTY_AFTER_STATIC_INIT_PREFIX + System.getProperty("sun.net.httpserver.maxReqHeaderSize"));
            System.out.flush();
        } finally {
            server.close();
            engine.close();
        }
        System.exit(0);
    }

    /** No foreign server: relies on -Dsun.net.httpserver.maxReqHeaderSize=<int-overflowing value>. */
    private static void overflowOverride() throws Exception {
        var engine = new PekkoExecutionEngine("overflow-boundary-" + System.nanoTime());
        var quiet = new PrintStream(java.io.OutputStream.nullOutputStream());
        var limiter = new RateLimiter(RateLimitConfiguration.DEFAULTS, TrustedProxyConfiguration.direct(),
                RateLimitAuditSink.discarding());
        var server = new RavenrootServer(
                new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, true,
                tenantAuthenticator(), httpSecurity(), Clock.systemUTC(),
                new DefaultAuthorizationService(new StructuredAuthorizationLogger(quiet)), limiter);
        try {
            server.start();
            InetSocketAddress liveServer = new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port());

            // Comfortably under the JDK's own 380 KiB (389120-byte) default: must still be answered.
            RavenrootServer.HeaderCapProbeResult small = RavenrootServer.probeHeaderCap(liveServer, 380_928);
            if (small.outcome() == RavenrootServer.HeaderCapProbeOutcome.CAP_CONFIRMED_OK) {
                System.out.println(OVERFLOW_SMALL_PROBE_OK);
            } else {
                System.out.println(OVERFLOW_PROBE_UNEXPECTED_PREFIX + "small=" + small.outcome());
            }

            // Comfortably over the JDK's own 380 KiB default: must still be rejected -- if the operator's
            // out-of-range override had somehow taken effect instead, this would be accepted.
            RavenrootServer.HeaderCapProbeResult large = RavenrootServer.probeHeaderCap(liveServer, 397_312);
            if (large.outcome() == RavenrootServer.HeaderCapProbeOutcome.CAP_CONFIRMED_WRONG) {
                System.out.println(OVERFLOW_LARGE_PROBE_REJECTED);
            } else {
                System.out.println(OVERFLOW_PROBE_UNEXPECTED_PREFIX + "large=" + large.outcome());
            }
            System.out.flush();
        } finally {
            server.close();
            engine.close();
        }
        System.exit(0);
    }

    private static RequestAuthenticator tenantAuthenticator() {
        return headers -> {
            String value = headers.getFirst("Authorization");
            String token = value == null ? "tenant-a:alice" : value.replaceFirst("^Bearer ", "");
            String[] parts = token.split(":", 2);
            String tenant = parts[0].isBlank() ? "tenant-a" : parts[0];
            String subject = parts.length > 1 && !parts[1].isBlank() ? parts[1] : "alice";
            return new AuthenticatedPrincipal(subject, AuthenticatedPrincipal.Type.USER,
                    "https://issuer.example", tenant, Set.of(Role.PLATFORM_ADMIN),
                    Arrays.stream(AuthorizationAction.values())
                            .filter(AuthorizationAction::available)
                            .map(AuthorizationAction::requiredScope)
                            .collect(Collectors.toUnmodifiableSet()),
                    Instant.now().plus(Duration.ofHours(1)));
        };
    }

    private static HttpSecurityConfiguration httpSecurity() {
        return new HttpSecurityConfiguration(
                new BrowserOriginPolicy(Set.of("http://127.0.0.1:1")),
                new SecurityHeadersPolicy(false), Duration.ofSeconds(30));
    }
}
