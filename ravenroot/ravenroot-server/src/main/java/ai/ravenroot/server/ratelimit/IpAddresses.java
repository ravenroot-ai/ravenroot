package ai.ravenroot.server.ratelimit;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Strict, resolver-free parsing of IP address literals.
 *
 * <p>Every value that becomes a rate-limit key or an audit field passes through here first. Two
 * properties matter and both are security properties rather than conveniences:</p>
 *
 * <ul>
 *   <li><strong>No DNS.</strong> {@code X-Forwarded-For} is attacker-influenced text. Handing it to a
 *       resolver would turn a header into an outbound network call, which is both an SSRF primitive
 *       and a trivial way to make every request block on a hostile nameserver. The shape of the value
 *       is therefore proven before {@link InetAddress#getByName(String)} is called, and it is only
 *       called on inputs it can answer from the literal itself.</li>
 *   <li><strong>Canonical output.</strong> A key derived from raw text lets one client occupy many
 *       buckets by spelling the same address differently ({@code ::1} versus {@code 0:0:0:0:0:0:0:1},
 *       or {@code 010.0.0.1}). Normalising through {@link InetAddress#getHostAddress()} collapses
 *       those spellings onto one key, and rejecting non-canonical IPv4 removes the ambiguity entirely.
 *       It also bounds the key alphabet and length, so a key can never be a log-injection payload.</li>
 * </ul>
 */
public final class IpAddresses {
    /** Longest possible textual IPv6 address, including an embedded IPv4 suffix. */
    private static final int MAX_LITERAL_LENGTH = 45;

    private IpAddresses() {
    }

    /**
     * Returns the canonical form of an IP literal, or {@code null} when the value is not one.
     *
     * <p>A {@code null} return is always a rejection and never a fallback: callers must not
     * substitute the raw text.</p>
     */
    public static String canonicalOrNull(String value) {
        if (value == null) {
            return null;
        }
        String candidate = stripZoneAndPort(value.trim());
        if (candidate.isEmpty() || candidate.length() > MAX_LITERAL_LENGTH) {
            return null;
        }
        boolean ipv6 = candidate.indexOf(':') >= 0;
        if (ipv6 ? !looksLikeIpv6(candidate) : !isCanonicalIpv4(candidate)) {
            return null;
        }
        try {
            // Safe: an IPv6 branch always contains ':' and an IPv4 branch is already proven to be four
            // decimal octets, so neither shape can reach the resolver.
            return InetAddress.getByName(candidate).getHostAddress();
        } catch (UnknownHostException notALiteral) {
            return null;
        }
    }

    /** Canonical form of a socket peer, which the JVM has already resolved to a numeric address. */
    public static String canonicalPeer(InetAddress address) {
        return address == null ? null : address.getHostAddress();
    }

    /**
     * The key a per-address limit is charged against: the address for IPv4, the /64 for IPv6.
     *
     * <p>An IPv6 host is routinely handed a whole /64. Keying on the full /128 therefore lets one host
     * mint an unlimited number of empty buckets by sourcing each request from a different address in a
     * prefix it already owns, which does not merely evade the per-address budget — it also fills the
     * bounded address registry, at which point every genuinely new client is refused. Both halves of
     * that come from the same mistake: treating an address a single party fully controls as if it
     * identified a single client. The /64 is the smallest unit an operator is expected to assign, so it
     * is the smallest unit that identifies a distinct party.</p>
     *
     * <p>IPv4-mapped IPv6 addresses are unaffected. {@link #canonicalOrNull} and
     * {@link #canonicalPeer} have already collapsed {@code ::ffff:203.0.113.7} to {@code 203.0.113.7},
     * so by the time a value reaches here it no longer contains a colon and is returned unchanged. It
     * must never be aggregated onto a /64 of the mapped range, which would put unrelated IPv4 clients
     * in one bucket.</p>
     *
     * <p>Only the bucket key is aggregated. The audit record keeps the full address, because that is
     * the field an operator acts on.</p>
     */
    public static String rateLimitKey(String canonicalAddress) {
        if (canonicalAddress == null || canonicalAddress.indexOf(':') < 0
                || !looksLikeIpv6(canonicalAddress)) {
            return canonicalAddress;
        }
        try {
            byte[] bytes = InetAddress.getByName(canonicalAddress).getAddress();
            if (bytes.length != 16) {
                // A mapped address the JDK narrowed to IPv4; it keys on its IPv4 identity, not a prefix.
                return canonicalAddress;
            }
            byte[] prefix = new byte[16];
            System.arraycopy(bytes, 0, prefix, 0, 8);
            return InetAddress.getByAddress(prefix).getHostAddress() + "/64";
        } catch (UnknownHostException notALiteral) {
            return canonicalAddress;
        }
    }

    /**
     * Removes an IPv6 scope id and a transport port, both of which are noise for an identity key.
     *
     * <p>A bare IPv6 literal contains colons of its own, so an unbracketed value is only treated as
     * {@code host:port} when it has exactly one colon. Anything else keeps its colons and is judged by
     * the IPv6 parser.</p>
     */
    private static String stripZoneAndPort(String value) {
        String candidate = value;
        if (candidate.startsWith("[")) {
            int end = candidate.indexOf(']');
            if (end < 0) {
                return "";
            }
            candidate = candidate.substring(1, end);
        } else if (candidate.indexOf(':') >= 0 && candidate.indexOf(':') == candidate.lastIndexOf(':')) {
            candidate = candidate.substring(0, candidate.indexOf(':'));
        }
        int zone = candidate.indexOf('%');
        return zone < 0 ? candidate : candidate.substring(0, zone);
    }

    /** Restricts the alphabet so the value cannot be a hostname, then lets the JDK parse the rest. */
    private static boolean looksLikeIpv6(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean allowed = character == ':' || character == '.'
                    || (character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f')
                    || (character >= 'A' && character <= 'F');
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /**
     * Accepts only four canonical decimal octets.
     *
     * <p>Leading zeros are rejected rather than normalised: {@code 010.0.0.1} is read as octal by some
     * stacks and as decimal by others, and an address whose meaning depends on the reader has no place
     * in an access-control decision.</p>
     */
    private static boolean isCanonicalIpv4(String value) {
        int octets = 0;
        int index = 0;
        while (index < value.length()) {
            int start = index;
            int digits = 0;
            int octet = 0;
            while (index < value.length() && value.charAt(index) >= '0' && value.charAt(index) <= '9') {
                octet = octet * 10 + (value.charAt(index) - '0');
                digits++;
                index++;
                if (digits > 3) {
                    return false;
                }
            }
            if (digits == 0 || octet > 255 || (digits > 1 && value.charAt(start) == '0')) {
                return false;
            }
            octets++;
            if (index == value.length()) {
                break;
            }
            if (value.charAt(index) != '.') {
                return false;
            }
            index++;
            if (index == value.length()) {
                return false;
            }
        }
        return octets == 4;
    }
}
