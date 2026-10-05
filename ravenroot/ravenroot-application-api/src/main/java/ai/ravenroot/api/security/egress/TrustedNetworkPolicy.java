package ai.ravenroot.api.security.egress;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Immutable administrator-owned exceptions for outbound destination admission and plaintext.
 *
 * <p>The policy is deliberately separate from connector profiles and service grants. A matching
 * rule can relax reserved-address admission or transport encryption for a destination that a
 * connector profile already authorizes; it cannot create that profile, credential binding, or
 * service grant. Rules are captured from the process environment and are never accepted from a
 * graph, workflow, request, or node payload.</p>
 */
public final class TrustedNetworkPolicy {
    /** Strict Base64-encoded JSON policy supplied by the deployment administrator. */
    public static final String ENVIRONMENT_VARIABLE = "RAVENROOT_EGRESS_TRUSTED_NETWORK_POLICY";

    private static final int MAX_ENCODED = 128 * 1024;
    private static final int MAX_RULES = 128;
    private static final Set<String> DOCUMENT_FIELDS = Set.of("version", "rules");
    private static final Set<String> RULE_FIELDS = Set.of(
            "name", "protocols", "ports", "hosts", "addresses", "profiles", "allowPlaintext");
    private static final Set<String> PROTOCOLS = Set.of(
            "http", "websocket", "amqp091", "kafka", "smtp", "imap", "otlp", "git",
            "assistant", "jwks", "runner");
    private static final PayloadLimits LIMITS = new PayloadLimits(
            MAX_ENCODED, 8, 512, 8_192, 4_096, 64);

    private final List<Rule> rules;

    private TrustedNetworkPolicy(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * Returns a policy with no administrator exceptions.
     *
     * @return immutable policy that grants neither reserved-network admission nor plaintext
     */
    public static TrustedNetworkPolicy empty() {
        return new TrustedNetworkPolicy(List.of());
    }

    /**
     * Captures and strictly parses the administrator policy from an environment snapshot.
     *
     * @param environment trusted process environment, or {@code null} for an empty environment
     * @return immutable parsed policy; an absent or blank value produces an empty policy
     * @throws IllegalArgumentException when the configured value violates the strict schema
     */
    public static TrustedNetworkPolicy fromEnvironment(Map<String, String> environment) {
        return fromBase64(environment == null ? null : environment.get(ENVIRONMENT_VARIABLE));
    }

    /**
     * Parses the canonical schema documented for {@link #ENVIRONMENT_VARIABLE}. Blank means no
     * rules. Standard Base64 must be canonical; unknown fields, duplicate rule names, wildcards,
     * unknown protocols, and unbounded rules are rejected.
     *
     * @param encoded canonical padded standard-Base64 JSON, or a blank value for no rules
     * @return immutable parsed policy
     * @throws IllegalArgumentException when the value is not canonical Base64 or violates the
     *         strict versioned schema
     */
    public static TrustedNetworkPolicy fromBase64(String encoded) {
        if (encoded == null || encoded.isBlank()) return empty();
        if (encoded.length() > MAX_ENCODED * 2) throw invalid();
        final byte[] json;
        try {
            json = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
        if (json.length > MAX_ENCODED || !Base64.getEncoder().encodeToString(json).equals(encoded))
            throw invalid();
        final Object raw;
        try {
            raw = PayloadJson.read(json, LIMITS).toJava();
        } catch (RuntimeException malformed) {
            throw invalid();
        }
        if (!(raw instanceof Map<?, ?> document) || !stringKeys(document)
                || !document.keySet().equals(DOCUMENT_FIELDS)
                || number(document.get("version")) != 1
                || !(document.get("rules") instanceof List<?> sourceRules)
                || sourceRules.size() > MAX_RULES) throw invalid();
        List<Rule> parsed = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Object rawRule : sourceRules) {
            if (!(rawRule instanceof Map<?, ?> rule) || !stringKeys(rule)
                    || !rule.keySet().equals(RULE_FIELDS)) throw invalid();
            String name = identifier(rule.get("name"));
            if (!names.add(name)) throw invalid();
            Set<String> protocols = strings(rule.get("protocols"), 16, TrustedNetworkPolicy::protocol);
            Set<Integer> ports = ports(rule.get("ports"));
            Set<String> hosts = strings(rule.get("hosts"), 64, TrustedNetworkPolicy::host);
            List<Cidr> addresses = cidrs(rule.get("addresses"));
            Set<String> profiles = strings(rule.get("profiles"), 64, TrustedNetworkPolicy::profile);
            if (protocols.isEmpty() || ports.isEmpty() || hosts.isEmpty() && addresses.isEmpty())
                throw invalid();
            if (!(rule.get("allowPlaintext") instanceof Boolean allowPlaintext)) throw invalid();
            parsed.add(new Rule(name, protocols, ports, hosts, addresses, profiles, allowPlaintext));
        }
        return new TrustedNetworkPolicy(parsed);
    }

    /**
     * True when one and the same rule admits every resolved address. This prevents two partial
     * rules from being combined into a broader authority and is the DNS-rebinding check callers
     * should use before opening a connection.
     *
     * @param protocol finite protocol name declared by the connector
     * @param profile exact administrator-controlled connector profile or package identifier
     * @param host destination hostname or numeric address
     * @param port destination TCP port
     * @param addresses complete, non-empty current DNS answer set
     * @return {@code true} when one matching rule admits every supplied address
     */
    public boolean permitsAll(String protocol, String profile, String host, int port, List<InetAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) return false;
        return matching(protocol, profile, host, port).stream()
                .anyMatch(rule -> rule.admitsAll(addresses));
    }

    /**
     * Like {@link #permitsAll(String, String, String, int, List)}, but the same rule must also
     * grant plaintext.
     *
     * @param protocol finite protocol name declared by the connector
     * @param profile exact administrator-controlled connector profile or package identifier
     * @param host destination hostname or numeric address
     * @param port destination TCP port
     * @param addresses complete, non-empty current DNS answer set
     * @return {@code true} when one matching plaintext rule admits every supplied address
     */
    public boolean permitsAllPlaintext(String protocol, String profile, String host, int port,
                                       List<InetAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) return false;
        return matching(protocol, profile, host, port).stream()
                .anyMatch(rule -> rule.allowPlaintext() && rule.admitsAll(addresses));
    }

    /**
     * Reports whether a rule exists for this connector scope, regardless of granted capability.
     *
     * @param protocol finite protocol name declared by the connector
     * @param profile exact administrator-controlled connector profile or package identifier
     * @param host destination hostname or numeric address
     * @param port destination TCP port
     * @return {@code true} when at least one rule matches all supplied scope components
     */
    public boolean hasScope(String protocol, String profile, String host, int port) {
        return !matching(protocol, profile, host, port).isEmpty();
    }

    /** Admission used by the JVM-wide resolver, which cannot carry connector scope. */
    boolean permitsReservedAddress(String host, InetAddress address) {
        String normalized = normalizeHost(host);
        List<Rule> hostRules = rules.stream().filter(rule -> rule.matchesHostForGlobal(normalized)).toList();
        return !hostRules.isEmpty() && hostRules.stream().allMatch(rule -> rule.admits(address));
    }

    /** Whether connection-time DNS for this host is constrained by any trusted rule. */
    boolean constrainsHost(String host) {
        String normalized = normalizeHost(host);
        return rules.stream().anyMatch(rule -> rule.matchesHostForGlobal(normalized));
    }

    /**
     * Returns the number of configured rules for non-sensitive diagnostics and tests.
     *
     * @return configured rule count
     */
    public int ruleCount() {
        return rules.size();
    }

    private List<Rule> matching(String protocol, String profile, String host, int port) {
        String normalizedProtocol = protocol == null ? "" : protocol.trim().toLowerCase(Locale.ROOT);
        String normalizedProfile = profile == null ? "" : profile.trim();
        String normalizedHost = normalizeHost(host);
        if (!PROTOCOLS.contains(normalizedProtocol) || port < 1 || port > 65_535 || normalizedHost.isEmpty())
            return List.of();
        return rules.stream().filter(rule -> rule.protocols().contains(normalizedProtocol)
                && rule.ports().contains(port)
                && rule.matchesProfile(normalizedProfile)
                && rule.matchesHost(normalizedHost)).toList();
    }

    private record Rule(String name, Set<String> protocols, Set<Integer> ports, Set<String> hosts,
                        List<Cidr> addresses, Set<String> profiles, boolean allowPlaintext) {
        private Rule {
            protocols = Set.copyOf(protocols);
            ports = Set.copyOf(ports);
            hosts = Set.copyOf(hosts);
            addresses = List.copyOf(addresses);
            profiles = Set.copyOf(profiles);
        }

        boolean matchesHost(String host) {
            return hosts.isEmpty() || hosts.contains(host);
        }

        boolean matchesHostForGlobal(String host) {
            return !hosts.isEmpty() && hosts.contains(host);
        }

        boolean matchesProfile(String profile) {
            return profiles.isEmpty() || profiles.contains(profile);
        }

        boolean admits(InetAddress address) {
            // A hostname alone never turns into unconditional trust of whatever it resolves to.
            return !addresses.isEmpty() && addresses.stream().anyMatch(cidr -> cidr.contains(address));
        }

        boolean admitsAll(List<InetAddress> resolved) {
            if (addresses.isEmpty())
                return resolved.stream().allMatch(address -> !ReservedNetwork.of(address).isReserved());
            return resolved.stream().allMatch(this::admits);
        }
    }

    private record Cidr(byte[] network, int prefix) {
        private Cidr {
            network = network.clone();
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) return false;
            int whole = prefix / 8;
            int bits = prefix % 8;
            for (int i = 0; i < whole; i++) if (candidate[i] != network[i]) return false;
            if (bits == 0) return true;
            int mask = 0xff << (8 - bits);
            return (candidate[whole] & mask) == (network[whole] & mask);
        }
    }

    private static List<Cidr> cidrs(Object raw) {
        Set<String> values = strings(raw, 64, TrustedNetworkPolicy::cidrText);
        List<Cidr> result = new ArrayList<>();
        for (String value : values) result.add(parseCidr(value));
        return List.copyOf(result);
    }

    private static Cidr parseCidr(String value) {
        int slash = value.indexOf('/');
        if (slash < 1 || slash != value.lastIndexOf('/')) throw invalid();
        String addressText = value.substring(0, slash);
        if (!numericShape(addressText)) throw invalid();
        final InetAddress address;
        final int prefix;
        try {
            address = InetAddress.getByName(addressText);
            prefix = Integer.parseInt(value.substring(slash + 1));
        } catch (UnknownHostException | NumberFormatException malformed) {
            throw invalid();
        }
        byte[] bytes = address.getAddress();
        if (prefix < 0 || prefix > bytes.length * 8) throw invalid();
        byte[] network = bytes.clone();
        int whole = prefix / 8;
        int bits = prefix % 8;
        if (bits != 0) network[whole] &= (byte) (0xff << (8 - bits));
        for (int i = whole + (bits == 0 ? 0 : 1); i < network.length; i++) network[i] = 0;
        if (!java.util.Arrays.equals(bytes, network)) throw invalid();
        return new Cidr(network, prefix);
    }

    private interface Validator { String validate(String value); }

    private static Set<String> strings(Object raw, int maximum, Validator validator) {
        if (!(raw instanceof List<?> values) || values.size() > maximum) throw invalid();
        Set<String> result = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof String text) || !result.add(validator.validate(text))) throw invalid();
        }
        return Set.copyOf(result);
    }

    private static Set<Integer> ports(Object raw) {
        if (!(raw instanceof List<?> values) || values.size() > 128) throw invalid();
        Set<Integer> result = new LinkedHashSet<>();
        for (Object value : values) {
            int port = number(value);
            if (port < 1 || port > 65_535 || !result.add(port)) throw invalid();
        }
        return Set.copyOf(result);
    }

    private static int number(Object value) {
        if (value instanceof Integer integer) return integer;
        if (value instanceof Long number && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE)
            return number.intValue();
        throw invalid();
    }

    private static String identifier(Object value) {
        if (!(value instanceof String text) || !text.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) throw invalid();
        return text;
    }

    private static String protocol(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!PROTOCOLS.contains(normalized)) throw invalid();
        return normalized;
    }

    private static String host(String value) {
        String normalized = normalizeHost(value);
        if (normalized.isEmpty() || normalized.contains("*") || normalized.length() > 253
                || !normalized.matches("[a-z0-9._:%-]+")) throw invalid();
        return normalized;
    }

    private static String profile(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,128}") || value.contains("*"))
            throw invalid();
        return value;
    }

    private static String cidrText(String value) {
        if (value == null || value.isBlank() || value.length() > 80 || value.contains("*")) throw invalid();
        return value;
    }

    private static String normalizeHost(String value) {
        String host = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        return host;
    }

    private static boolean numericShape(String value) {
        return value != null && !value.isBlank()
                && (value.indexOf(':') >= 0 || value.chars().allMatch(c -> c == '.' || c >= '0' && c <= '9'));
    }

    private static boolean stringKeys(Map<?, ?> value) {
        return value.keySet().stream().allMatch(String.class::isInstance);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid trusted network policy");
    }
}
