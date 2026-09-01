package ai.ravenroot.server.ratelimit;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the client address that per-address limits are keyed on, given a deployment's proxy layout.
 *
 * <p>Every per-address limit is only as trustworthy as this class: if a caller can choose the address
 * it is keyed on, it can mint a fresh, empty bucket per request and the limiter stops existing. The
 * rules below therefore treat {@code X-Forwarded-For} as hostile by default and require an operator to
 * state, exactly, how much of it is produced by infrastructure they control.</p>
 *
 * <h2>Rules</h2>
 * <ol>
 *   <li><strong>Default is zero trust.</strong> {@code trustedHops == 0} means the socket peer is the
 *       client and {@code X-Forwarded-For} is never read. This is the default, so a deployment that
 *       never configures anything cannot be spoofed.</li>
 *   <li><strong>Peer must be a declared proxy.</strong> A hop count alone is not enough: it describes
 *       the expected topology, not the actual sender. If the immediate peer is not in
 *       {@code trustedPeers}, the header is ignored entirely and the peer is the client. An attacker
 *       reaching the origin directly therefore gains nothing by sending the header.</li>
 *   <li><strong>Only the trusted suffix is read.</strong> With {@code N} trusted hops the client is
 *       {@code chain[size - N]} — the entry written by the outermost proxy the operator controls.
 *       Everything to its left was supplied by the caller and is discarded. A client sending
 *       {@code X-Forwarded-For: 1.2.3.4} through one trusted proxy yields the chain
 *       {@code [1.2.3.4, <real client>]}, and the real client is selected.</li>
 *   <li><strong>A short chain is a rejection, not a fallback.</strong> Fewer entries than configured
 *       hops means the request did not traverse the topology the limits assume. Falling back to the
 *       peer would collapse every client behind the proxy onto one key; falling back to the leftmost
 *       entry would trust attacker text. Both are worse than a 400.</li>
 *   <li><strong>Bounded and literal.</strong> The chain is capped and every candidate must be an IP
 *       literal, so a header can neither be unbounded work nor a DNS lookup.</li>
 * </ol>
 */
public record TrustedProxyConfiguration(int trustedHops, Set<String> trustedPeers) {
    /** Header the chain is read from. Only this one; see {@link #forwardedFor}. */
    public static final String FORWARDED_FOR = "X-Forwarded-For";
    /** Maximum entries considered before the chain is rejected as abusive. */
    public static final int MAX_CHAIN_ENTRIES = 32;
    /** Maximum total bytes of forwarding headers considered before rejection. */
    public static final int MAX_CHAIN_BYTES = 4096;

    public TrustedProxyConfiguration {
        if (trustedHops < 0) {
            throw new IllegalArgumentException("RAVENROOT_TRUSTED_PROXY_HOPS must not be negative");
        }
        if (trustedHops > MAX_CHAIN_ENTRIES) {
            throw new IllegalArgumentException(
                    "RAVENROOT_TRUSTED_PROXY_HOPS must not exceed " + MAX_CHAIN_ENTRIES);
        }
        trustedPeers = Set.copyOf(java.util.Objects.requireNonNull(trustedPeers, "trustedPeers"));
        if (trustedHops > 0 && trustedPeers.isEmpty()) {
            throw new IllegalArgumentException(
                    "RAVENROOT_TRUSTED_PROXY_ADDRESSES is required when RAVENROOT_TRUSTED_PROXY_HOPS is set");
        }
        if (trustedHops == 0 && !trustedPeers.isEmpty()) {
            throw new IllegalArgumentException(
                    "RAVENROOT_TRUSTED_PROXY_ADDRESSES requires RAVENROOT_TRUSTED_PROXY_HOPS to be set");
        }
    }

    /** No proxy in front of the server: the socket peer is the client. */
    public static TrustedProxyConfiguration direct() {
        return new TrustedProxyConfiguration(0, Set.of());
    }

    public static TrustedProxyConfiguration fromEnvironment(Map<String, String> environment) {
        int hops;
        String configured = environment.getOrDefault("RAVENROOT_TRUSTED_PROXY_HOPS", "0").trim();
        try {
            hops = Integer.parseInt(configured);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("RAVENROOT_TRUSTED_PROXY_HOPS must be an integer", invalid);
        }
        String peers = environment.getOrDefault("RAVENROOT_TRUSTED_PROXY_ADDRESSES", "").trim();
        var normalized = new LinkedHashSet<String>();
        if (!peers.isEmpty()) {
            for (String entry : Arrays.stream(peers.split(",", -1)).map(String::trim).toList()) {
                String canonical = IpAddresses.canonicalOrNull(entry);
                if (canonical == null) {
                    throw new IllegalArgumentException(
                            "RAVENROOT_TRUSTED_PROXY_ADDRESSES must contain IP literals only");
                }
                normalized.add(canonical);
            }
        }
        return new TrustedProxyConfiguration(hops, normalized);
    }

    /** Outcome of address resolution: either a canonical client address or a reason to reject. */
    public sealed interface Resolution {
        record Client(String address, boolean forwarded) implements Resolution {
        }

        record Rejected(String code, String reason) implements Resolution {
        }
    }

    /**
     * Resolves the client address for one request.
     *
     * @param peer            the socket peer, as reported by the HTTP server
     * @param forwardedValues every {@code X-Forwarded-For} header value, in received order
     */
    public Resolution resolve(InetSocketAddress peer, List<String> forwardedValues) {
        String peerAddress = peer == null || peer.getAddress() == null
                ? null : IpAddresses.canonicalPeer(peer.getAddress());
        if (peerAddress == null) {
            // An exchange with no usable peer cannot be attributed, so it cannot be limited fairly.
            return new Resolution.Rejected("CLIENT_ADDRESS_UNAVAILABLE", "client address is unavailable");
        }
        if (trustedHops == 0 || !trustedPeers.contains(peerAddress)) {
            return new Resolution.Client(peerAddress, false);
        }
        List<String> chain = chain(forwardedValues);
        if (chain == null) {
            return new Resolution.Rejected("FORWARDED_CHAIN_TOO_LONG", "forwarded chain is too long");
        }
        if (chain.size() < trustedHops) {
            return new Resolution.Rejected("FORWARDED_CHAIN_TOO_SHORT", "forwarded chain is shorter than configured");
        }
        String candidate = IpAddresses.canonicalOrNull(chain.get(chain.size() - trustedHops));
        if (candidate == null) {
            return new Resolution.Rejected("FORWARDED_CLIENT_INVALID", "forwarded client address is not an IP literal");
        }
        return new Resolution.Client(candidate, true);
    }

    /** Convenience accessor for the header values of an exchange. */
    public static List<String> forwardedFor(com.sun.net.httpserver.Headers headers) {
        List<String> values = headers.get(FORWARDED_FOR);
        return values == null ? List.of() : values;
    }

    /** Flattens every header instance into one ordered chain, or {@code null} when it is abusive. */
    private static List<String> chain(List<String> forwardedValues) {
        if (forwardedValues == null || forwardedValues.isEmpty()) {
            return List.of();
        }
        int bytes = 0;
        var entries = new ArrayList<String>();
        for (String value : forwardedValues) {
            if (value == null) {
                continue;
            }
            bytes += value.length();
            if (bytes > MAX_CHAIN_BYTES) {
                return null;
            }
            for (String element : value.split(",", -1)) {
                String trimmed = element.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (entries.size() == MAX_CHAIN_ENTRIES) {
                    return null;
                }
                entries.add(trimmed);
            }
        }
        return entries;
    }
}
