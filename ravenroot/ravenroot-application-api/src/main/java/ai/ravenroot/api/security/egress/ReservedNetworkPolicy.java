package ai.ravenroot.api.security.egress;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Immutable operator policy for names and literal connector destinations that reach reserved
 * address space.
 *
 * <p>Exceptions are keyed by normalized destination and network class. A graph, payload, or
 * request cannot add one. Destination parsing is entirely numeric and never invokes DNS. IPv6
 * exception keys use brackets when followed by a network class, for example
 * {@code [::1]:LOOPBACK} or {@code [fe80::1%eth0]:LINK_LOCAL}.
 */
public final class ReservedNetworkPolicy {
    /** Trusted environment key used by Ravenroot composition roots and first-party connectors. */
    public static final String EXCEPTIONS_ENVIRONMENT_VARIABLE = "RAVENROOT_EGRESS_RESERVED_EXCEPTIONS";
    /** Shipped exception: the fixed name {@code localhost} may resolve to loopback. */
    public static final String DEFAULT_EXCEPTIONS = "localhost:LOOPBACK";

    private final Map<String, Set<ReservedNetwork>> exceptions;

    private ReservedNetworkPolicy(Map<String, Set<ReservedNetwork>> exceptions) {
        this.exceptions = Map.copyOf(exceptions);
    }

    /**
     * Returns a policy with no reserved-network exceptions.
     *
     * @return deny-all-reserved policy
     */
    public static ReservedNetworkPolicy denyAllReserved() {
        return new ReservedNetworkPolicy(Map.of());
    }

    /**
     * Returns the shipped policy.
     *
     * @return shipped policy
     */
    public static ReservedNetworkPolicy shippedDefault() {
        return fromCommaSeparatedExceptions(DEFAULT_EXCEPTIONS);
    }

    /**
     * Captures the operator exception policy from an environment snapshot. The resulting object is
     * immutable; callers must not accept the map from graph or payload data.
     *
     * @param environment trusted operator environment
     * @return immutable policy
     */
    public static ReservedNetworkPolicy fromEnvironment(Map<String, String> environment) {
        return fromCommaSeparatedExceptions(environment == null
                ? null : environment.get(EXCEPTIONS_ENVIRONMENT_VARIABLE));
    }

    /**
     * Parses comma-separated {@code name[:NETWORK[|NETWORK...]]} exceptions. Brackets are required
     * around an IPv6 literal when a network suffix is present. Unknown network names and ambiguous
     * unbracketed IPv6 entries fail closed with an {@link IllegalArgumentException}.
     *
     * @param value operator-owned exception text
     * @return immutable parsed policy
     */
    public static ReservedNetworkPolicy fromCommaSeparatedExceptions(String value) {
        String configured = value == null || value.isBlank() ? DEFAULT_EXCEPTIONS : value;
        Map<String, Set<ReservedNetwork>> parsed = new LinkedHashMap<>();
        for (String raw : configured.split(",")) {
            String entry = raw == null ? "" : raw.trim();
            if (entry.isEmpty()) continue;
            ParsedException item = parseException(entry);
            parsed.merge(item.destination(), item.networks(), (left, right) -> {
                EnumSet<ReservedNetwork> merged = EnumSet.noneOf(ReservedNetwork.class);
                merged.addAll(left);
                merged.addAll(right);
                return Set.copyOf(merged);
            });
        }
        return new ReservedNetworkPolicy(parsed);
    }

    /**
     * Returns whether a resolved address is permitted for the exact name used to resolve it.
     * Public addresses are always permitted.
     *
     * @param name requested name
     * @param address resolved address
     * @return whether the address is permitted
     */
    public boolean permits(String name, InetAddress address) {
        ReservedNetwork network = ReservedNetwork.of(address);
        if (!network.isReserved()) return true;
        Set<ReservedNetwork> allowed = exceptions.get(normalizeDestination(name));
        return allowed != null && allowed.contains(network);
    }

    /**
     * Checks a connector host without resolving it. Hostnames pass to the JVM resolver guard;
     * numeric literals are classified here. Malformed numeric-looking values fail closed.
     *
     * @param host destination host, with optional IPv6 brackets and zone identifier
     * @return true for a hostname, public literal, or exact operator exception
     */
    public boolean permitsLiteral(String host) {
        Literal literal = Literal.parse(host);
        if (literal.kind() == LiteralKind.HOSTNAME) return true;
        if (literal.kind() == LiteralKind.MALFORMED) return false;
        ReservedNetwork network = ReservedNetwork.of(literal.address());
        if (!network.isReserved()) return true;
        Set<ReservedNetwork> allowed = exceptions.get(literal.normalized());
        return allowed != null && allowed.contains(network);
    }

    /**
     * Applies {@link #permitsLiteral(String)} and raises a fixed, non-sensitive refusal.
     *
     * @param host destination host
     * @throws SecurityException when the numeric literal is reserved, malformed, or not exempt
     */
    public void requireAllowedLiteral(String host) {
        if (!permitsLiteral(host))
            throw new SecurityException("Connector destination is a reserved address prohibited by policy");
    }

    /**
     * Returns normalized exception destination keys for diagnostics, never for authorization.
     *
     * @return immutable normalized exception keys
     */
    public Set<String> exemptNames() {
        return exceptions.keySet();
    }

    private static ParsedException parseException(String entry) {
        String destination;
        String networks = null;
        if (entry.startsWith("[")) {
            int close = entry.indexOf(']');
            if (close < 0) throw new IllegalArgumentException("Malformed bracketed egress exception");
            destination = entry.substring(0, close + 1);
            if (close + 1 < entry.length()) {
                if (entry.charAt(close + 1) != ':')
                    throw new IllegalArgumentException("Malformed bracketed egress exception");
                networks = entry.substring(close + 2);
            }
        } else {
            int first = entry.indexOf(':');
            int last = entry.lastIndexOf(':');
            if (first >= 0 && first != last)
                throw new IllegalArgumentException("IPv6 egress exception must be bracketed");
            if (first >= 0) {
                destination = entry.substring(0, first);
                networks = entry.substring(first + 1);
            } else destination = entry;
        }
        Literal destinationLiteral = Literal.parse(destination);
        if (destinationLiteral.kind() == LiteralKind.MALFORMED)
            throw new IllegalArgumentException("Malformed literal egress exception destination");
        String normalized = normalizeDestination(destination);
        if (normalized.isEmpty()) throw new IllegalArgumentException("Egress exception destination is blank");
        Set<ReservedNetwork> allowed;
        if (networks == null) {
            allowed = EnumSet.complementOf(EnumSet.of(ReservedNetwork.PUBLIC));
        } else {
            allowed = EnumSet.noneOf(ReservedNetwork.class);
            for (String raw : networks.split("\\|", -1)) {
                String token = raw.trim().toUpperCase(Locale.ROOT);
                if (token.isEmpty()) throw new IllegalArgumentException("Egress exception network is blank");
                try {
                    ReservedNetwork network = ReservedNetwork.valueOf(token);
                    if (network == ReservedNetwork.PUBLIC)
                        throw new IllegalArgumentException("PUBLIC is not a reserved-network exception");
                    allowed.add(network);
                } catch (IllegalArgumentException unknown) {
                    throw new IllegalArgumentException("Unknown reserved network in egress exception", unknown);
                }
            }
        }
        return new ParsedException(normalized, Set.copyOf(allowed));
    }

    private static String normalizeDestination(String value) {
        Literal literal = Literal.parse(value);
        if (literal.kind() == LiteralKind.LITERAL) return literal.normalized();
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]"))
            normalized = normalized.substring(1, normalized.length() - 1);
        return normalized;
    }

    private record ParsedException(String destination, Set<ReservedNetwork> networks) {}

    private enum LiteralKind { HOSTNAME, LITERAL, MALFORMED }

    private record Literal(LiteralKind kind, String normalized, InetAddress address) {
        private static Literal parse(String raw) {
            String value = raw == null ? "" : raw.trim();
            if (value.startsWith("[") && value.endsWith("]"))
                value = value.substring(1, value.length() - 1);
            boolean ipv6Shape = value.indexOf(':') >= 0;
            boolean ipv4Shape = !value.isEmpty() && value.chars().allMatch(c -> c == '.' || c >= '0' && c <= '9');
            if (!ipv6Shape && !ipv4Shape) return new Literal(LiteralKind.HOSTNAME, "", null);
            String addressText = value;
            String zone = "";
            if (ipv6Shape) {
                int percent = addressText.indexOf('%');
                if (percent >= 0) {
                    int separatorLength = addressText.regionMatches(true, percent, "%25", 0, 3) ? 3 : 1;
                    zone = addressText.substring(percent + separatorLength);
                    addressText = addressText.substring(0, percent);
                    if (zone.isEmpty() || !zone.chars().allMatch(ReservedNetworkPolicy::zoneCharacter))
                        return new Literal(LiteralKind.MALFORMED, "", null);
                }
            }
            byte[] bytes = ipv6Shape ? parseIpv6(addressText) : parseIpv4(addressText);
            if (bytes == null) return new Literal(LiteralKind.MALFORMED, "", null);
            try {
                InetAddress address = InetAddress.getByAddress(bytes);
                String normalized = ipv6Shape
                        ? "ipv6|" + address.getHostAddress().toLowerCase(Locale.ROOT)
                        : "ipv4|" + normalizeIpv4Lexical(addressText);
                if (!zone.isEmpty()) normalized += "%" + zone;
                return new Literal(LiteralKind.LITERAL, normalized, address);
            } catch (UnknownHostException impossible) {
                return new Literal(LiteralKind.MALFORMED, "", null);
            }
        }
    }

    private static boolean zoneCharacter(int character) {
        return character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9' || character == '_' || character == '-'
                || character == '.' || character == '~';
    }

    private static byte[] parseIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length < 1 || parts.length > 4) return null;
        long[] numbers = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 10
                    || parts[i].length() > 1 && parts[i].charAt(0) == '0') return null;
            long number = 0;
            for (int j = 0; j < parts[i].length(); j++) {
                number = number * 10 + parts[i].charAt(j) - '0';
                if (number > 0xffff_ffffL) return null;
            }
            numbers[i] = number;
        }
        long packed;
        switch (numbers.length) {
            case 1 -> packed = numbers[0];
            case 2 -> {
                if (numbers[0] > 0xff || numbers[1] > 0xff_ffff) return null;
                packed = numbers[0] << 24 | numbers[1];
            }
            case 3 -> {
                if (numbers[0] > 0xff || numbers[1] > 0xff || numbers[2] > 0xffff) return null;
                packed = numbers[0] << 24 | numbers[1] << 16 | numbers[2];
            }
            case 4 -> {
                for (long number : numbers) if (number > 0xff) return null;
                packed = numbers[0] << 24 | numbers[1] << 16 | numbers[2] << 8 | numbers[3];
            }
            default -> { return null; }
        }
        return new byte[] {(byte) (packed >>> 24), (byte) (packed >>> 16),
                (byte) (packed >>> 8), (byte) packed};
    }

    private static String normalizeIpv4Lexical(String value) {
        String[] parts = value.split("\\.", -1);
        for (int i = 0; i < parts.length; i++) parts[i] = Long.toString(Long.parseLong(parts[i]));
        return String.join(".", parts);
    }

    private static byte[] parseIpv6(String value) {
        int compression = value.indexOf("::");
        if (compression != value.lastIndexOf("::")) return null;
        String left = compression < 0 ? value : value.substring(0, compression);
        String right = compression < 0 ? "" : value.substring(compression + 2);
        java.util.List<Integer> head = groups(left, right.isEmpty() && compression < 0);
        java.util.List<Integer> tail = groups(right, true);
        if (head == null || tail == null) return null;
        int total = head.size() + tail.size();
        if (compression < 0 && total != 8 || compression >= 0 && total >= 8) return null;
        int zeros = 8 - total;
        byte[] bytes = new byte[16];
        int index = 0;
        for (int group : head) { bytes[index++] = (byte) (group >>> 8); bytes[index++] = (byte) group; }
        index += zeros * 2;
        for (int group : tail) { bytes[index++] = (byte) (group >>> 8); bytes[index++] = (byte) group; }
        return bytes;
    }

    private static java.util.List<Integer> groups(String value, boolean allowIpv4) {
        if (value.isEmpty()) return java.util.List.of();
        String[] tokens = value.split(":", -1);
        java.util.ArrayList<Integer> groups = new java.util.ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.isEmpty()) return null;
            if (token.indexOf('.') >= 0) {
                if (!allowIpv4 || i != tokens.length - 1) return null;
                byte[] ipv4 = parseIpv4(token);
                if (ipv4 == null) return null;
                groups.add((ipv4[0] & 0xff) << 8 | ipv4[1] & 0xff);
                groups.add((ipv4[2] & 0xff) << 8 | ipv4[3] & 0xff);
            } else {
                if (token.length() > 4) return null;
                int group;
                try { group = Integer.parseInt(token, 16); }
                catch (NumberFormatException malformed) { return null; }
                groups.add(group);
            }
        }
        return groups;
    }
}
