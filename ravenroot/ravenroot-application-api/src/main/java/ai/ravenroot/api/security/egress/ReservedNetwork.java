package ai.ravenroot.api.security.egress;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Reserved address classes that outbound connector destinations cannot reach by default.
 *
 * <p>This type governs reach only. It does not grant credentials, authorize a connector, or set
 * request volume limits. IPv4-mapped and IPv4-compatible IPv6 addresses are classified by their
 * embedded IPv4 address so an alternate spelling cannot bypass the policy.
 */
public enum ReservedNetwork {
    /** An ordinary routable destination. */
    PUBLIC,
    /** IPv4 127/8 and IPv6 {@code ::1}. */
    LOOPBACK,
    /** IPv4 169.254/16 and IPv6 {@code fe80::/10}. */
    LINK_LOCAL,
    /** RFC 1918, IPv6 ULA, and deprecated IPv6 site-local space. */
    PRIVATE,
    /** IPv4 0/8 and IPv6 {@code ::}. */
    ANY_LOCAL,
    /** IPv4 224/4 and IPv6 {@code ff00::/8}. */
    MULTICAST,
    /** IPv4 {@code 255.255.255.255}. */
    BROADCAST,
    /** RFC 6598 carrier-grade NAT space. */
    CARRIER_GRADE_NAT;

    /**
     * Returns whether this class is refused unless an operator exception permits it.
     *
     * @return whether this address class is reserved
     */
    public boolean isReserved() {
        return this != PUBLIC;
    }

    /**
     * Classifies an address without performing name resolution. A null value fails closed.
     *
     * @param address address to classify
     * @return the address's reserved-network class
     */
    public static ReservedNetwork of(InetAddress address) {
        if (address == null) return ANY_LOCAL;
        InetAddress unwrapped = unwrap(address);
        if (unwrapped.isAnyLocalAddress()) return ANY_LOCAL;
        if (unwrapped.isLoopbackAddress()) return LOOPBACK;
        if (unwrapped.isLinkLocalAddress()) return LINK_LOCAL;
        if (unwrapped.isMulticastAddress()) return MULTICAST;
        byte[] bytes = unwrapped.getAddress();
        if (unwrapped instanceof Inet4Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            if (first == 255 && second == 255 && (bytes[2] & 0xff) == 255 && (bytes[3] & 0xff) == 255)
                return BROADCAST;
            if (first == 0) return ANY_LOCAL;
            if (first == 10) return PRIVATE;
            if (first == 172 && second >= 16 && second <= 31) return PRIVATE;
            if (first == 192 && second == 168) return PRIVATE;
            if (first == 100 && second >= 64 && second <= 127) return CARRIER_GRADE_NAT;
            return PUBLIC;
        }
        if (unwrapped.isSiteLocalAddress()) return PRIVATE;
        if ((bytes[0] & 0xfe) == 0xfc) return PRIVATE;
        return PUBLIC;
    }

    private static InetAddress unwrap(InetAddress address) {
        if (!(address instanceof Inet6Address)) return address;
        byte[] bytes = address.getAddress();
        if (bytes.length != 16) return address;
        for (int i = 0; i < 10; i++) if (bytes[i] != 0) return address;
        boolean mapped = (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
        boolean compatible = bytes[10] == 0 && bytes[11] == 0
                && !(bytes[12] == 0 && bytes[13] == 0 && bytes[14] == 0);
        if (!mapped && !compatible) return address;
        try {
            return InetAddress.getByAddress(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]});
        } catch (UnknownHostException impossible) {
            return address;
        }
    }
}
