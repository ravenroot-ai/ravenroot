package ai.ravenroot.core.security.egress;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

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
        if (address == null) {
            // A null address is not classifiable, so it is not provably public. Deny-by-default
            // requires the unclassifiable case to be treated as reserved, never as PUBLIC.
            return ANY_LOCAL;
        }
        InetAddress unwrapped = unwrap(address);
        if (unwrapped.isAnyLocalAddress()) return ANY_LOCAL;
        if (unwrapped.isLoopbackAddress()) return LOOPBACK;
        if (unwrapped.isLinkLocalAddress()) return LINK_LOCAL;
        if (unwrapped.isMulticastAddress()) return MULTICAST;
        byte[] bytes = unwrapped.getAddress();
        if (unwrapped instanceof Inet4Address) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            if (first == 255 && second == 255 && (bytes[2] & 0xFF) == 255 && (bytes[3] & 0xFF) == 255) {
                return BROADCAST;
            }
            if (first == 0) return ANY_LOCAL;
            if (first == 10) return PRIVATE;
            if (first == 172 && second >= 16 && second <= 31) return PRIVATE;
            if (first == 192 && second == 168) return PRIVATE;
            if (first == 100 && second >= 64 && second <= 127) return CARRIER_GRADE_NAT;
            return PUBLIC;
        }
        // isSiteLocalAddress covers the deprecated fec0::/10; ULA fc00::/7 is not covered by the
        // JDK predicates and must be tested explicitly.
        if (unwrapped.isSiteLocalAddress()) return PRIVATE;
        if ((bytes[0] & 0xFE) == 0xFC) return PRIVATE;
        return PUBLIC;
    }

    private static InetAddress unwrap(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return address;
        }
        byte[] b = address.getAddress();
        if (b.length != 16) {
            return address;
        }
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) return address;
        }
        boolean mapped = (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF;
        boolean compatible = b[10] == 0 && b[11] == 0 && !(b[12] == 0 && b[13] == 0 && b[14] == 0);
        if (!mapped && !compatible) {
            return address;
        }
        try {
            return InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]});
        } catch (UnknownHostException e) {
            return address;
        }
    }
}
