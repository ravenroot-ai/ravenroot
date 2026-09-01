package ai.ravenroot.core.security.egress;

import java.net.InetAddress;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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

    private final Map<String, Set<ReservedNetwork>> exceptions;

    private ReservedNetworkPolicy(Map<String, Set<ReservedNetwork>> exceptions) {
        this.exceptions = Map.copyOf(exceptions);
    }

    /** Denies every name any reserved address. Nothing is exempt, including {@code localhost}. */
    public static ReservedNetworkPolicy denyAllReserved() {
        return new ReservedNetworkPolicy(Map.of());
    }

    /** The shipped default: {@code localhost} may reach loopback, nothing else is exempt. */
    public static ReservedNetworkPolicy shippedDefault() {
        return fromCommaSeparatedExceptions(DEFAULT_EXCEPTIONS);
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
        if (value == null || value.isBlank()) {
            return fromEntries(DEFAULT_EXCEPTIONS.split(","));
        }
        return fromEntries(value.split(","));
    }

    private static ReservedNetworkPolicy fromEntries(String[] entries) {
        Map<String, Set<ReservedNetwork>> parsed = new LinkedHashMap<>();
        for (String raw : entries) {
            String entry = raw == null ? "" : raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int colon = entry.indexOf(':');
            String name = (colon < 0 ? entry : entry.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            Set<ReservedNetwork> networks;
            if (colon < 0) {
                networks = EnumSet.complementOf(EnumSet.of(ReservedNetwork.PUBLIC));
            } else {
                networks = EnumSet.noneOf(ReservedNetwork.class);
                for (String token : entry.substring(colon + 1).split("\\|")) {
                    String network = token.trim().toUpperCase(Locale.ROOT);
                    if (network.isEmpty()) {
                        continue;
                    }
                    try {
                        networks.add(ReservedNetwork.valueOf(network));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException(
                                "Unknown reserved network in egress exception '" + entry + "': " + network);
                    }
                }
            }
            parsed.merge(name, Set.copyOf(networks), (a, b) -> {
                var merged = EnumSet.noneOf(ReservedNetwork.class);
                merged.addAll(a);
                merged.addAll(b);
                return Set.copyOf(merged);
            });
        }
        return new ReservedNetworkPolicy(parsed);
    }

    /**
     * True when {@code name} may legitimately resolve to {@code address}. Public addresses are
     * always permitted; reserved ones only when the operator named this exact name for that
     * network.
     */
    public boolean permits(String name, InetAddress address) {
        ReservedNetwork network = ReservedNetwork.of(address);
        if (!network.isReserved()) {
            return true;
        }
        String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        Set<ReservedNetwork> allowed = exceptions.get(key);
        return allowed != null && allowed.contains(network);
    }

    /** Names carrying an exception, for diagnostics. Never used to make a decision. */
    public Set<String> exemptNames() {
        return exceptions.keySet();
    }
}
