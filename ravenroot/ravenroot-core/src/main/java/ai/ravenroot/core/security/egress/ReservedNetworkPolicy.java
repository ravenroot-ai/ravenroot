package ai.ravenroot.core.security.egress;

import java.net.InetAddress;
import java.util.Set;

/**
 * Which names are permitted to resolve into reserved address space (SEC-10).
 *
 * <p><b>Deny by default.</b> Every name resolves to public address space only, unless an operator
 * has named an exception. Exceptions are <em>operator-only</em>: they are read at the composition
 * root from configuration and no graph, plugin, payload or request can widen them. This mirrors the
 * shape of the existing host allowlist in {@link ai.ravenroot.core.security.OutboundHttpPolicy}
 * deliberately — the same authority boundary, expressed the same way.
 *
 * <p><b>Exceptions are keyed on (name, network), not on network alone.</b> A network-only exception
 * would be exploitable: exempting LOOPBACK globally so that {@code localhost} keeps working would
 * also let an attacker-controlled name resolve to 127.0.0.1 and reach whatever binds there. Keying
 * on the name means an exception grants reach to exactly the name the operator wrote down.
 *
 * <p><b>Why {@code localhost} is exempt by default.</b> It is the one name whose mapping does not
 * come from DNS — it comes from the host's own resolver configuration — so it is not a rebinding
 * vector, and 30 test files plus several loopback-only code paths depend on it. It is a named
 * exception like any other and can be removed by configuring an explicit exception set.
 *
 * <p>Scope: this type governs <em>reach</em>. It governs no volume, rate or duration; those belong to
 * volume policy and must not be added here.
 */
public final class ReservedNetworkPolicy {
    /** Names allowed to resolve into reserved space out of the box. */
    public static final String DEFAULT_EXCEPTIONS = "localhost:LOOPBACK";

    private final ai.ravenroot.api.security.egress.ReservedNetworkPolicy delegate;

    private ReservedNetworkPolicy(ai.ravenroot.api.security.egress.ReservedNetworkPolicy delegate) {
        this.delegate = delegate;
    }

    /** Denies every name any reserved address. Nothing is exempt, including {@code localhost}. */
    public static ReservedNetworkPolicy denyAllReserved() {
        return new ReservedNetworkPolicy(ai.ravenroot.api.security.egress.ReservedNetworkPolicy.denyAllReserved());
    }

    /** The shipped default: {@code localhost} may reach loopback, nothing else is exempt. */
    public static ReservedNetworkPolicy shippedDefault() {
        return new ReservedNetworkPolicy(ai.ravenroot.api.security.egress.ReservedNetworkPolicy.shippedDefault());
    }

    /**
     * Parses the operator exception list. Entries are comma separated; each is either {@code name},
     * meaning that name may resolve into any reserved network, or {@code name:NETWORK[|NETWORK...]}
     * naming {@link ReservedNetwork} constants. A null or blank value yields the shipped default,
     * because an operator who configured nothing must still get a working {@code localhost} rather
     * than a broken process.
     *
     * <p>An unparseable network name is rejected loudly rather than skipped: a typo that silently
     * narrowed an exception would look like a working configuration and fail only in production.
     */
    public static ReservedNetworkPolicy fromCommaSeparatedExceptions(String value) {
        return new ReservedNetworkPolicy(
                ai.ravenroot.api.security.egress.ReservedNetworkPolicy.fromCommaSeparatedExceptions(value));
    }

    /**
     * True when {@code name} may legitimately resolve to {@code address}. Public addresses are
     * always permitted; reserved ones only when the operator named this exact name for that
     * network.
     */
    public boolean permits(String name, InetAddress address) {
        return delegate.permits(name, address);
    }

    /** Names carrying an exception, for diagnostics. Never used to make a decision. */
    public Set<String> exemptNames() {
        return delegate.exemptNames();
    }

    /** Returns the shared API policy used by connectors and the resolver guard. */
    ai.ravenroot.api.security.egress.ReservedNetworkPolicy delegate() {
        return delegate;
    }
}
