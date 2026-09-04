package ai.ravenroot.core.security;

import ai.ravenroot.core.security.egress.EgressAddressGuard;

import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deny-by-default SSRF boundary for HTTP action nodes (SEC-10).
 *
 * <h2>Scope boundary: reach versus volume</h2>
 * <b>This policy governs reach</b> — which scheme, host, port and address family a connector may
 * connect to. <b>Volume policy governs</b> how much data it may move, at what rate, for how long. The one crossover
 * here is {@link #maximumResponseBytes()}: it is a hard safety ceiling that stops an unbounded read
 * from an attacker-chosen endpoint, not a quota. Quotas, rates and per-tenant budgets do not belong
 * in this type. This boundary is documented here so it stays visible at the dependency seam.
 *
 * <h2>The host allowlist was never sufficient on its own</h2>
 * Previously this class checked only scheme and host. That left two gaps that were absences, not choices:
 * <ul>
 *   <li><b>No port allowlist.</b> An allowlisted internal host exposed <em>every</em> port on
 *       itself — an allowlist entry intended to permit an API on 443 also permitted its database,
 *       its admin console and its debug agent. {@link #DEFAULT_ALLOWED_PORTS} closes that.</li>
 *   <li><b>No address check.</b> The allowlist matches on the <em>name</em>. An allowlisted host
 *       whose DNS an attacker controls could return a link-local metadata address, and the request
 *       went there. That is DNS rebinding, and it worked. It is closed structurally by
 *       {@link ai.ravenroot.core.security.egress.RavenrootInetAddressResolverProvider}, not here,
 *       because this class cannot pin the address the HTTP client will eventually connect to.</li>
 * </ul>
 *
 * <h2>Literal destinations are checked here, names are checked in the resolver</h2>
 * The JVM-wide resolver cannot see a literal IP address: the JDK parses {@code 127.0.0.1},
 * {@code ::1}, {@code ::ffff:169.254.169.254} and even the bare decimal {@code 2130706433} without
 * ever consulting the resolver SPI. That is deliberate and load-bearing — it is why the container
 * healthcheck and the loopback bind address are untouched by the resolver — but it means a literal
 * destination would otherwise bypass the reserved-network filter entirely. {@link
 * #requireAllowed(URI)} therefore applies the same reserved-network policy to literal hosts itself.
 * The two checks compose: a name is filtered at resolution, a literal is filtered here.
 */
public final class OutboundHttpPolicy {
    /**
     * Ports reachable unless the operator narrows or widens the set. HTTP and HTTPS only: the node
     * speaks HTTP, so every other port on an allowlisted host is reachable only by explicit choice.
     */
    public static final Set<Integer> DEFAULT_ALLOWED_PORTS = Set.of(80, 443);

    /**
     * Hard ceiling on a response body, in bytes, when the operator configures none. This is a gate,
     * not a default that callers may opt out of: there is no value meaning "unlimited", and
     * {@link #maximumResponseBytes()} is always a positive number.
     */
    public static final long DEFAULT_MAX_RESPONSE_BYTES = 8L * 1024 * 1024;

    /**
     * Hard ceiling on a request body, in bytes, when the operator configures none. A gate on the
     * same terms as the response ceiling: no value means unlimited, and
     * {@link #maximumRequestBytes()} is always positive. Smaller than the response ceiling on
     * purpose — a node body is rendered from a template, and a megabyte of it is already an
     * accident rather than a use case.
     */
    public static final long DEFAULT_MAX_REQUEST_BYTES = 1024L * 1024;

    private final Set<String> allowedHosts;
    private final Duration maximumTimeout;
    private final Set<Integer> allowedPorts;
    private final long maximumResponseBytes;
    private final long maximumRequestBytes;

    public OutboundHttpPolicy(Collection<String> allowedHosts, Duration maximumTimeout) {
        this(allowedHosts, maximumTimeout, DEFAULT_ALLOWED_PORTS, DEFAULT_MAX_RESPONSE_BYTES,
                DEFAULT_MAX_REQUEST_BYTES);
    }

    public OutboundHttpPolicy(Collection<String> allowedHosts, Duration maximumTimeout,
                              Collection<Integer> allowedPorts, long maximumResponseBytes) {
        this(allowedHosts, maximumTimeout, allowedPorts, maximumResponseBytes, DEFAULT_MAX_REQUEST_BYTES);
    }

    public OutboundHttpPolicy(Collection<String> allowedHosts, Duration maximumTimeout,
                              Collection<Integer> allowedPorts, long maximumResponseBytes,
                              long maximumRequestBytes) {
        this.allowedHosts = allowedHosts == null ? Set.of() : allowedHosts.stream()
                .map(String::trim).filter(value -> !value.isEmpty()).map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.maximumTimeout = maximumTimeout == null ? Duration.ofSeconds(30) : maximumTimeout;
        this.allowedPorts = allowedPorts == null || allowedPorts.isEmpty()
                ? DEFAULT_ALLOWED_PORTS
                : Set.copyOf(new LinkedHashSet<>(allowedPorts));
        // A non-positive configured ceiling is a misconfiguration, not a request for "unlimited".
        this.maximumResponseBytes = maximumResponseBytes > 0 ? maximumResponseBytes : DEFAULT_MAX_RESPONSE_BYTES;
        this.maximumRequestBytes = maximumRequestBytes > 0 ? maximumRequestBytes : DEFAULT_MAX_REQUEST_BYTES;
    }

    /**
     * Gate for an outbound request body. Called before the request is built, so an oversize body is
     * never handed to the client at all.
     *
     * <p>This is the request half of the body-size safety boundary. It is the same shape as the response ceiling and
     * for the same reason: the body used to be sent with no bound whatsoever, and "nobody writes a
     * template that big" is an assumption about authors, not a control. Volume policy proper — rates,
     * quotas, per-tenant budgets — remains outside this reach policy.
     */
    public void requireRequestWithinLimit(byte[] body) {
        long size = body == null ? 0 : body.length;
        if (size > maximumRequestBytes) {
            throw new SecurityException("Outbound request body of " + size
                    + " bytes exceeds the limit of " + maximumRequestBytes + " bytes");
        }
    }

    public static OutboundHttpPolicy disabled() {
        return new OutboundHttpPolicy(Set.of(), Duration.ofSeconds(30));
    }

    public static OutboundHttpPolicy fromCommaSeparatedHosts(String hosts) {
        return fromCommaSeparated(hosts, null, 0);
    }

    /**
     * Composition-root factory. Same shape as the host allowlist it extends: comma separated,
     * operator-only, read from configuration and unwidenable by any graph, plugin or request.
     */
    public static OutboundHttpPolicy fromCommaSeparated(String hosts, String ports, long maximumResponseBytes) {
        return fromCommaSeparated(hosts, ports, maximumResponseBytes, 0);
    }

    /** Composition-root factory carrying both volume ceilings. */
    public static OutboundHttpPolicy fromCommaSeparated(String hosts, String ports,
                                                        long maximumResponseBytes, long maximumRequestBytes) {
        Set<Integer> parsedPorts = new LinkedHashSet<>();
        if (ports != null && !ports.isBlank()) {
            for (String token : ports.split(",")) {
                String value = token.trim();
                if (value.isEmpty()) {
                    continue;
                }
                int port;
                try {
                    port = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Outbound HTTP port allowlist is not a number: " + value);
                }
                if (port < 1 || port > 65535) {
                    throw new IllegalArgumentException("Outbound HTTP port allowlist is out of range: " + port);
                }
                parsedPorts.add(port);
            }
        }
        return new OutboundHttpPolicy(
                hosts == null ? Set.of() : java.util.Arrays.asList(hosts.split(",")),
                Duration.ofSeconds(30), parsedPorts, maximumResponseBytes, maximumRequestBytes);
    }

    /**
     * Gate for a single destination. Order matters: the address check runs last, so a host that was
     * never allowlisted is refused without any resolution attempt at all.
     */
    public void requireAllowed(URI uri) {
        String scheme = uri == null ? "" : String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT);
        String host = uri == null || uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || !allowedHosts.contains(host)) {
            throw new SecurityException("Outbound HTTP destination is not allowlisted: " + host);
        }
        if (uri.getUserInfo() != null) {
            throw new SecurityException("Credentials are not allowed in node URLs; use credentialRef");
        }
        int port = uri.getPort() == -1 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();
        if (!allowedPorts.contains(port)) {
            throw new SecurityException("Outbound HTTP port is not allowlisted: " + host + ":" + port);
        }
        requireLiteralNotReserved(host);
    }

    /**
     * Applies the reserved-network policy to a destination written as a literal IP address. Names
     * are not checked here — they are filtered inside name resolution, where the address the socket
     * will actually use is decided.
     */
    private void requireLiteralNotReserved(String host) {
        EgressAddressGuard.requireAllowedLiteral(host);
    }

    public Duration timeout(long requestedMillis) {
        Duration requested = Duration.ofMillis(Math.max(1, requestedMillis));
        return requested.compareTo(maximumTimeout) > 0 ? maximumTimeout : requested;
    }

    /** Always positive. See {@link #DEFAULT_MAX_RESPONSE_BYTES} for why there is no unlimited value. */
    public long maximumResponseBytes() {
        return maximumResponseBytes;
    }

    /** Always positive. See {@link #DEFAULT_MAX_REQUEST_BYTES} for why there is no unlimited value. */
    public long maximumRequestBytes() {
        return maximumRequestBytes;
    }

    /** The ports this policy permits. Exposed for diagnostics and tests, never widened at runtime. */
    public Set<Integer> allowedPorts() {
        return allowedPorts;
    }
}
