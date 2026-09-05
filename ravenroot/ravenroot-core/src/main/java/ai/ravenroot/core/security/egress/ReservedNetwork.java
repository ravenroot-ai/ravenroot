package ai.ravenroot.core.security.egress;

import java.net.InetAddress;

/**
 * Classification of an IP address into the networks that graph-driven egress must not reach by
 * default (SEC-10).
 *
 * <p><b>Scope boundary.</b> This classification governs <em>reach</em> — which addresses a connector
 * may connect to. Volume policy governs how much data it may move once connected. Neither is a substitute for
 * the other, and this enum deliberately says nothing about size, rate or duration.
 *
 * <p>The classification is performed on the resolved address, never on the name, because the name
 * is what the attacker controls in a DNS rebinding attack.
 */
public enum ReservedNetwork {
    /** Not reserved: an ordinary routable destination. */
    PUBLIC,
    /** 127.0.0.0/8 and ::1 — the process's own host, where admin and debug surfaces bind. */
    LOOPBACK,
    /** 169.254.0.0/16 and fe80::/10 — cloud instance metadata lives at 169.254.169.254. */
    LINK_LOCAL,
    /** RFC 1918 10/8, 172.16/12, 192.168/16, and IPv6 ULA fc00::/7 plus deprecated fec0::/10. */
    PRIVATE,
    /** 0.0.0.0/8 and :: — "this host", which many stacks route to loopback. */
    ANY_LOCAL,
    /** 224.0.0.0/4 and ff00::/8. */
    MULTICAST,
    /** 255.255.255.255. */
    BROADCAST,
    /** 100.64.0.0/10, RFC 6598 carrier-grade NAT space. */
    CARRIER_GRADE_NAT;

    /** True for every value except {@link #PUBLIC}. */
    public boolean isReserved() {
        return this != PUBLIC;
    }

    /**
     * Classifies an address. IPv4-mapped and IPv4-compatible IPv6 addresses are unwrapped to their
     * embedded IPv4 address first, so {@code ::ffff:169.254.169.254} cannot launder a link-local
     * destination past an IPv4-only check.
     */
    public static ReservedNetwork of(InetAddress address) {
        return valueOf(ai.ravenroot.api.security.egress.ReservedNetwork.of(address).name());
    }
}
