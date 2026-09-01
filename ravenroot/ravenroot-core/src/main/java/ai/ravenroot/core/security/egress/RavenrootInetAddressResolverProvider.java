package ai.ravenroot.core.security.egress;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.stream.Stream;

/**
 * Installs Ravenroot's reserved-network filter as the JVM's name resolver (SEC-10).
 *
 * <p><b>Why the resolver and not a check before connecting.</b> The platform HTTP client cannot be
 * pinned to an address that has already been validated: it exposes no socket factory and no
 * resolver hook, so the obvious shape — resolve, validate, connect — leaves the client free to
 * resolve the name a second time when it actually opens the socket. That window <em>is</em> the DNS
 * rebinding attack, and a test written against that shape passes while the attack succeeds. Filtering
 * inside resolution closes the window structurally: the socket can only ever be handed addresses
 * that the policy permitted, for every protocol and every client in the process.
 *
 * <p><b>It installs always and filters always.</b> Installing only when egress is configured would
 * be fail-open, which is the posture this provider prevents. The cost is that the filter is
 * process-global and cannot know which caller asked: the resolver sees names, not call sites. Reach
 * is therefore governed by what the operator declared, never by who is asking.
 *
 * <p><b>Do not try to scope this to "threads doing graph egress". It was measured and it does not
 * work.</b> The obvious refinement — mark the calling thread, filter only for marked threads — is
 * the first thing a reader proposes, so here is the measurement rather than an invitation to repeat
 * it. Under JDK 21, {@code HttpClient.send} resolves on the caller's thread, but
 * {@code HttpClient.sendAsync} resolves on an internal client worker ({@code HttpClient-N-Worker-M})
 * that is <em>not</em> created from the caller and does <em>not</em> inherit an
 * {@link InheritableThreadLocal}: a marker set on the calling thread reads back as {@code null} at
 * the point of resolution. The HTTP node uses {@code sendAsync}. Any thread-scoped or
 * {@code ScopedValue}-style egress marker is therefore silently ineffective exactly where it is
 * needed, which is worse than having none — it would look like a control and filter nothing.
 *
 * <p>Registered through {@code META-INF/services/java.net.spi.InetAddressResolverProvider}. The JDK
 * instantiates it once, on first use of {@link InetAddress}, and it can never be replaced; all
 * mutability lives in {@link EgressAddressGuard}.
 */
public final class RavenrootInetAddressResolverProvider extends InetAddressResolverProvider {

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        EgressAddressGuard.bindBuiltin(builtin);
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
                    throws UnknownHostException {
                return EgressAddressGuard.resolve(host, lookupPolicy).stream();
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                // Reverse lookups open no socket, so they are outside this control's scope.
                return builtin.lookupByAddress(addr);
            }
        };
    }

    @Override
    public String name() {
        return "ravenroot-egress";
    }
}
