package ai.ravenroot.core.security.nodepackage;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable operator authority for one package's managed HTTP/WebSocket services. */
public final class NodePackageEgressPolicy {
    private static final Set<String> FORBIDDEN_CREDENTIAL_HEADERS = Set.of(
            "host", "content-length", "connection", "upgrade", "transfer-encoding", "te",
            "trailer", "proxy-authorization", "proxy-connection", "sec-websocket-key",
            "sec-websocket-accept", "sec-websocket-version", "sec-websocket-protocol",
            "sec-websocket-extensions", "x-amz-date", "x-amz-content-sha256",
            "x-amz-security-token");
    public static final long DEFAULT_MAX_REQUEST_BYTES = 1024L * 1024;
    public static final long DEFAULT_MAX_RESPONSE_BYTES = 8L * 1024 * 1024;
    public static final long DEFAULT_MAX_WEBSOCKET_MESSAGE_BYTES = 1024L * 1024;

    private final Set<Origin> origins;
    private final Set<String> requestHeaders;
    private final Set<String> responseHeaders;
    private final Set<String> httpMethods;
    private final Set<String> webSocketSubprotocols;
    private final Map<String, CredentialPlacement> credentialPlacements;
    private final Map<String, AwsSigV4SigningGrant> signingGrants;
    private final long maximumRequestBytes;
    private final long maximumResponseBytes;
    private final long maximumWebSocketMessageBytes;
    private final int maximumWebSocketFragments;
    private final int maximumConcurrentOperations;
    private final int maximumConcurrentPerTenant;
    private final int maximumQueuedWebSocketSends;
    private final Duration maximumDeadline;
    private final Duration maximumWebSocketLifetime;
    private final Duration maximumWebSocketIdle;

    private NodePackageEgressPolicy(Builder builder) {
        origins = Set.copyOf(builder.origins);
        requestHeaders = Set.copyOf(builder.requestHeaders);
        responseHeaders = Set.copyOf(builder.responseHeaders);
        httpMethods = Set.copyOf(builder.httpMethods);
        webSocketSubprotocols = Set.copyOf(builder.webSocketSubprotocols);
        credentialPlacements = Map.copyOf(builder.credentialPlacements);
        signingGrants = Map.copyOf(builder.signingGrants);
        maximumRequestBytes = boundedBytes(builder.maximumRequestBytes, "maximumRequestBytes");
        maximumResponseBytes = boundedBytes(builder.maximumResponseBytes, "maximumResponseBytes");
        maximumWebSocketMessageBytes = boundedBytes(builder.maximumWebSocketMessageBytes,
                "maximumWebSocketMessageBytes");
        maximumWebSocketFragments = positive(builder.maximumWebSocketFragments, "maximumWebSocketFragments");
        maximumConcurrentOperations = positive(builder.maximumConcurrentOperations,
                "maximumConcurrentOperations");
        maximumConcurrentPerTenant = positive(builder.maximumConcurrentPerTenant,
                "maximumConcurrentPerTenant");
        if (maximumConcurrentPerTenant > maximumConcurrentOperations) {
            throw new IllegalArgumentException("maximumConcurrentPerTenant cannot exceed the package maximum");
        }
        maximumQueuedWebSocketSends = positive(builder.maximumQueuedWebSocketSends,
                "maximumQueuedWebSocketSends");
        maximumDeadline = positive(builder.maximumDeadline, "maximumDeadline");
        maximumWebSocketLifetime = positive(builder.maximumWebSocketLifetime, "maximumWebSocketLifetime");
        maximumWebSocketIdle = positive(builder.maximumWebSocketIdle, "maximumWebSocketIdle");
        if (maximumWebSocketIdle.compareTo(maximumWebSocketLifetime) > 0) {
            throw new IllegalArgumentException("maximumWebSocketIdle cannot exceed maximumWebSocketLifetime");
        }
    }

    public static Builder builder() { return new Builder(); }

    public void requireAllowed(URI destination, boolean webSocket) {
        if (destination == null || destination.toASCIIString().length() > 4096) {
            throw new IllegalArgumentException("destination");
        }
        Origin origin = Origin.from(destination);
        if (!origins.contains(origin)) {
            throw new IllegalArgumentException("destination");
        }
        if (webSocket != origin.isWebSocket()) {
            throw new IllegalArgumentException("protocol");
        }
        if (destination.getUserInfo() != null || destination.getFragment() != null) {
            throw new IllegalArgumentException("destination");
        }
    }

    public Duration deadline(Duration requested) {
        Duration safe = positive(requested, "deadline");
        return safe.compareTo(maximumDeadline) > 0 ? maximumDeadline : safe;
    }

    public CredentialPlacement requireCredentialPlacement(String id, URI destination) {
        CredentialPlacement placement = credentialPlacements.get(id);
        if (placement == null || !placement.origin().equals(Origin.from(destination))) {
            throw new IllegalArgumentException("credential binding");
        }
        return placement;
    }

    public AwsSigV4SigningGrant requireAwsSigV4SigningGrant(String id, URI destination) {
        AwsSigV4SigningGrant grant = signingGrants.get(id);
        if (grant == null || !grant.origin().equals(Origin.from(destination))) {
            throw new IllegalArgumentException("signing binding");
        }
        return grant;
    }

    public Set<String> requestHeaders() { return requestHeaders; }
    public Set<String> responseHeaders() { return responseHeaders; }
    public Set<String> httpMethods() { return httpMethods; }
    public Set<String> webSocketSubprotocols() { return webSocketSubprotocols; }
    public long maximumRequestBytes() { return maximumRequestBytes; }
    public long maximumResponseBytes() { return maximumResponseBytes; }
    public long maximumWebSocketMessageBytes() { return maximumWebSocketMessageBytes; }
    public int maximumWebSocketFragments() { return maximumWebSocketFragments; }
    public int maximumConcurrentOperations() { return maximumConcurrentOperations; }
    public int maximumConcurrentPerTenant() { return maximumConcurrentPerTenant; }
    public int maximumQueuedWebSocketSends() { return maximumQueuedWebSocketSends; }
    public Duration maximumDeadline() { return maximumDeadline; }
    public Duration maximumWebSocketLifetime() { return maximumWebSocketLifetime; }
    public Duration maximumWebSocketIdle() { return maximumWebSocketIdle; }

    public record Origin(String scheme, String host, int port) {
        public Origin {
            scheme = safeLower(scheme, "scheme");
            host = safeLower(host, "host");
            if (!Set.of("http", "https", "ws", "wss").contains(scheme)) {
                throw new IllegalArgumentException("Unsupported egress scheme");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Egress port is out of range");
            }
        }

        static Origin from(URI uri) {
            Objects.requireNonNull(uri, "destination");
            String scheme = safeLower(uri.getScheme(), "scheme");
            String host = safeLower(uri.getHost(), "host");
            int port = uri.getPort();
            if (port == -1) {
                port = switch (scheme) {
                    case "https", "wss" -> 443;
                    case "http", "ws" -> 80;
                    default -> -1;
                };
            }
            return new Origin(scheme, host, port);
        }

        boolean isWebSocket() { return "ws".equals(scheme) || "wss".equals(scheme); }
    }

    public record CredentialPlacement(String bindingId, Origin origin, String headerName, String prefix) {
        public CredentialPlacement {
            bindingId = safeToken(bindingId, "bindingId", 256);
            Objects.requireNonNull(origin, "origin");
            headerName = safeHeaderName(headerName);
            if (FORBIDDEN_CREDENTIAL_HEADERS.contains(headerName.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("credential header cannot alter transport authority");
            }
            prefix = prefix == null ? "" : prefix;
            requireNoCrLf(prefix, "credential prefix");
            if (prefix.length() > 256) {
                throw new IllegalArgumentException("credential prefix is too long");
            }
        }
    }

    /** Operator authority for one destination-bound AWS Signature Version 4 credential. */
    public record AwsSigV4SigningGrant(String bindingId, Origin origin, String credentialReference,
                                       String region, String service) {
        public AwsSigV4SigningGrant {
            bindingId = safeToken(bindingId, "bindingId", 256);
            Objects.requireNonNull(origin, "origin");
            if (!"https".equals(origin.scheme())) {
                throw new IllegalArgumentException("SigV4 signing requires HTTPS");
            }
            credentialReference = safeToken(credentialReference, "credentialReference", 256);
            region = safeAwsComponent(region, "region");
            service = safeAwsComponent(service, "service");
            if (!"s3".equals(service)) {
                throw new IllegalArgumentException("Only the s3 SigV4 service profile is supported");
            }
        }
    }

    public static final class Builder {
        private final Set<Origin> origins = new LinkedHashSet<>();
        private final Set<String> requestHeaders = new LinkedHashSet<>();
        private final Set<String> responseHeaders = new LinkedHashSet<>();
        private final Set<String> httpMethods = new LinkedHashSet<>();
        private final Set<String> webSocketSubprotocols = new LinkedHashSet<>();
        private final Map<String, CredentialPlacement> credentialPlacements = new LinkedHashMap<>();
        private final Map<String, AwsSigV4SigningGrant> signingGrants = new LinkedHashMap<>();
        private long maximumRequestBytes = DEFAULT_MAX_REQUEST_BYTES;
        private long maximumResponseBytes = DEFAULT_MAX_RESPONSE_BYTES;
        private long maximumWebSocketMessageBytes = DEFAULT_MAX_WEBSOCKET_MESSAGE_BYTES;
        private int maximumWebSocketFragments = 64;
        private int maximumConcurrentOperations = 32;
        private int maximumConcurrentPerTenant = 8;
        private int maximumQueuedWebSocketSends = 16;
        private Duration maximumDeadline = Duration.ofSeconds(30);
        private Duration maximumWebSocketLifetime = Duration.ofHours(1);
        private Duration maximumWebSocketIdle = Duration.ofMinutes(5);

        public Builder allowOrigin(String scheme, String host, int port) {
            origins.add(new Origin(scheme, host, port));
            return this;
        }

        public Builder allowRequestHeader(String name) {
            requestHeaders.add(safeHeaderName(name).toLowerCase(Locale.ROOT));
            return this;
        }

        public Builder allowResponseHeader(String name) {
            responseHeaders.add(safeHeaderName(name).toLowerCase(Locale.ROOT));
            return this;
        }

        public Builder allowHttpMethod(String method) {
            String safe = safeToken(method, "HTTP method", 32).toUpperCase(Locale.ROOT);
            if (!safe.matches("[A-Z]+") || Set.of("CONNECT", "TRACE").contains(safe)) {
                throw new IllegalArgumentException("HTTP method is not permitted");
            }
            httpMethods.add(safe);
            return this;
        }

        public Builder allowWebSocketSubprotocol(String subprotocol) {
            String safe = safeToken(subprotocol, "WebSocket subprotocol", 128);
            if (!safe.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
                throw new IllegalArgumentException("WebSocket subprotocol is invalid");
            }
            webSocketSubprotocols.add(safe);
            return this;
        }

        public Builder bindCredential(String bindingId, Origin origin, String headerName, String prefix) {
            CredentialPlacement placement = new CredentialPlacement(bindingId, origin, headerName, prefix);
            if (credentialPlacements.putIfAbsent(placement.bindingId(), placement) != null) {
                throw new IllegalArgumentException("Duplicate credential binding");
            }
            return this;
        }

        public Builder bindAwsSigV4(String bindingId, Origin origin, String credentialReference,
                                    String region, String service) {
            AwsSigV4SigningGrant grant = new AwsSigV4SigningGrant(bindingId, origin, credentialReference,
                    region, service);
            if (signingGrants.putIfAbsent(grant.bindingId(), grant) != null) {
                throw new IllegalArgumentException("Duplicate signing binding");
            }
            return this;
        }

        public Builder byteLimits(long request, long response, long webSocketMessage) {
            maximumRequestBytes = request;
            maximumResponseBytes = response;
            maximumWebSocketMessageBytes = webSocketMessage;
            return this;
        }

        public Builder concurrencyLimits(int packageMaximum, int tenantMaximum) {
            maximumConcurrentOperations = packageMaximum;
            maximumConcurrentPerTenant = tenantMaximum;
            return this;
        }

        public Builder webSocketLimits(int maximumFragments, int maximumQueuedSends,
                                       Duration maximumLifetime, Duration maximumIdle) {
            maximumWebSocketFragments = maximumFragments;
            maximumQueuedWebSocketSends = maximumQueuedSends;
            maximumWebSocketLifetime = maximumLifetime;
            maximumWebSocketIdle = maximumIdle;
            return this;
        }

        public Builder maximumDeadline(Duration maximum) {
            maximumDeadline = maximum;
            return this;
        }

        public NodePackageEgressPolicy build() { return new NodePackageEgressPolicy(this); }
    }

    private static String safeLower(String value, String name) {
        return safeToken(value, name, 253).toLowerCase(Locale.ROOT);
    }

    private static String safeToken(String value, String name, int maximum) {
        String safe = value == null ? "" : value.strip();
        if (safe.isEmpty() || safe.length() > maximum) {
            throw new IllegalArgumentException(name + " is absent or too long");
        }
        requireNoCrLf(safe, name);
        return safe;
    }

    private static String safeHeaderName(String value) {
        String safe = safeToken(value, "header name", 128);
        if (!safe.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
            throw new IllegalArgumentException("header name is invalid");
        }
        return safe;
    }

    private static String safeAwsComponent(String value, String name) {
        String safe = safeToken(value, name, 64).toLowerCase(Locale.ROOT);
        if (!safe.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return safe;
    }

    static void requireNoCrLf(String value, String name) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " contains a line break");
        }
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static long boundedBytes(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " exceeds the maximum representable buffer");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        try {
            value.toNanos();
        } catch (ArithmeticException tooLarge) {
            throw new IllegalArgumentException(name + " is too large", tooLarge);
        }
        return value;
    }
}
