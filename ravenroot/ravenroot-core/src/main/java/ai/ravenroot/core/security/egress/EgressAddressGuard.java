package ai.ravenroot.core.security.egress;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolver.LookupPolicy;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The single mutable seam behind the one JVM-wide resolver Ravenroot installs (SEC-10).
 *
 * <p><b>Why a mutable seam rather than a swappable provider.</b> {@link
 * java.net.spi.InetAddressResolverProvider} is discovered through {@link java.util.ServiceLoader}
 * and installed by the JDK <em>once per process</em>, on first use of {@link InetAddress}. It can
 * never be replaced, so a test cannot install its own. Making the provider a thin, permanent shell
 * over a replaceable policy and a replaceable upstream is what makes this control testable in
 * process; the alternative — forking a JVM per case, as {@code SqliteCrossProcessLeaseTest} does —
 * was rejected here because the rebinding suite needs many small cases and each would pay a process
 * launch.
 *
 * <p><b>Blast radius, stated rather than discovered.</b> The provider filters every name resolution
 * in the process: the JWKS fetch, the persistence store, plugins and anything a connector does.
 * The shipped policy therefore identifies precisely which <em>names</em> may reach reserved space
 * instead of blanket-denying a
 * network. A literal IP address never reaches this guard at all — the JDK resolves literals without
 * consulting the resolver SPI — which is why the container healthcheck ({@code http://127.0.0.1})
 * and the loopback bind address are unaffected by construction, and why
 * {@link ai.ravenroot.core.security.OutboundHttpPolicy} must check literal destinations itself.
 *
 * <p>Scope: this guard governs reach. Nothing about response size belongs here; that is volume policy.
 */
public final class EgressAddressGuard {

    /** The upstream this guard filters. Replaceable so a test can drive a hostile resolution. */
    @FunctionalInterface
    public interface NameSource {
        List<InetAddress> lookup(String name, LookupPolicy lookupPolicy) throws UnknownHostException;
    }

    private static final AtomicLong REFUSALS = new AtomicLong();

    private static volatile ReservedNetworkPolicy policy = ReservedNetworkPolicy.shippedDefault();
    private static volatile NameSource builtinSource;
    private static volatile NameSource upstream;

    private EgressAddressGuard() {
    }

    /**
     * Installs the operator's policy. Called from the composition root exactly once, before the
     * server accepts work. Passing null restores the shipped default rather than disabling the
     * filter, because a null policy must never be the fail-open path.
     */
    public static void configure(ReservedNetworkPolicy configured) {
        policy = configured == null ? ReservedNetworkPolicy.shippedDefault() : configured;
    }

    public static ReservedNetworkPolicy policy() {
        return policy;
    }

    /** Number of resolutions refused since JVM start. Diagnostics only, never a decision input. */
    public static long refusals() {
        return REFUSALS.get();
    }

    /** Binds the guard to the JDK's built-in resolver. Called once by the installed provider. */
    static void bindBuiltin(InetAddressResolver builtin) {
        if (builtinSource == null) {
            builtinSource = (name, lookupPolicy) -> builtin.lookupByName(name, lookupPolicy).toList();
            if (upstream == null) {
                upstream = builtinSource;
            }
        }
    }

    /** Resolves through the current upstream and applies the filter. The provider's whole body. */
    static List<InetAddress> resolve(String name, LookupPolicy lookupPolicy) throws UnknownHostException {
        NameSource source = upstream;
        if (source == null) {
            throw new UnknownHostException(name);
        }
        return filter(name, source.lookup(name, lookupPolicy));
    }

    /**
     * Filters a resolution. Every address returned for {@code name} must be permitted by the
     * policy; a name that resolves only to refused addresses fails to resolve at all, so no socket
     * can be opened to it by any protocol.
     *
     * <p>Refused addresses are dropped individually rather than failing the whole lookup on the
     * first bad entry: a dual-stack name that legitimately returns a public A record and a reserved
     * AAAA record must remain usable through its public address, and dropping is what prevents the
     * client from silently falling back to the reserved one.
     */
    static List<InetAddress> filter(String name, List<InetAddress> resolved) throws UnknownHostException {
        ReservedNetworkPolicy current = policy;
        List<InetAddress> permitted = resolved.stream()
                .filter(address -> current.permits(name, address))
                .toList();
        if (permitted.size() != resolved.size()) {
            REFUSALS.incrementAndGet();
        }
        if (permitted.isEmpty() && !resolved.isEmpty()) {
            throw new UnknownHostException(name
                    + ": refused, the name resolves only into reserved address space ("
                    + ReservedNetwork.of(resolved.get(0)) + "); see RAVENROOT_EGRESS_RESERVED_EXCEPTIONS");
        }
        return permitted;
    }

    // ---------------------------------------------------------------------
    // Test seam. Package-private on purpose: substituting the upstream is available to
    // same-package tests in this module and to nothing else. There is deliberately NO switch that
    // turns the filter off -- not even a package-private one. A control with an unused off switch
    // is a control that can be disabled by a future edit nobody reviews as a security change.
    // ---------------------------------------------------------------------

    static NameSource upstream() {
        return upstream;
    }

    static void replaceUpstream(NameSource replacement) {
        upstream = replacement;
    }
}
