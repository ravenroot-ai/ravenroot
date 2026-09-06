package ai.ravenroot.server.interaction;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** Typed, fail-fast authority for the optional interaction WebSocket listener. */
public record InteractionWebSocketConfiguration(
        boolean enabled,
        InetSocketAddress bindAddress,
        int maxConnections,
        int maxPendingAuthentication,
        int maxPendingAuthenticationPerAddress,
        int maxBackendOperations,
        Duration authenticationDeadline,
        int maxMessageBytes,
        int maxFragments,
        int maxPendingCommands,
        int maxQueuedIncomingBytes,
        int maxOutgoingFrameBytes,
        int maxQueuedOutgoingFrames,
        int maxQueuedOutgoingBytes,
        int maxUnacknowledgedEvents,
        Duration replayPollInterval,
        Duration acknowledgementDeadline,
        Duration idleTimeout,
        Duration absoluteLifetime,
        Duration shutdownTimeout) {

    public static final String PATH = "/v1/interactions";
    public static final String SUBPROTOCOL = "ravenroot.interactions.v1";

    public InteractionWebSocketConfiguration {
        Objects.requireNonNull(bindAddress, "bindAddress");
        if (bindAddress.getPort() < 1 || bindAddress.getPort() > 65_535) throw invalid("port");
        requireBetween("maxConnections", maxConnections, 1, 100_000);
        requireBetween("maxPendingAuthentication", maxPendingAuthentication, 1, maxConnections);
        requireBetween("maxPendingAuthenticationPerAddress", maxPendingAuthenticationPerAddress,
                1, maxPendingAuthentication);
        requireBetween("maxBackendOperations", maxBackendOperations, 1, 100_000);
        requireDuration("authenticationDeadline", authenticationDeadline, 1, 60);
        requireBetween("maxMessageBytes", maxMessageBytes, 1_024, 16 * 1_024 * 1_024);
        requireBetween("maxFragments", maxFragments, 1, 1_024);
        requireBetween("maxPendingCommands", maxPendingCommands, 1, 1_024);
        requireBetween("maxQueuedIncomingBytes", maxQueuedIncomingBytes, maxMessageBytes,
                64 * 1_024 * 1_024);
        requireBetween("maxOutgoingFrameBytes", maxOutgoingFrameBytes, 1_024, maxMessageBytes);
        requireBetween("maxQueuedOutgoingFrames", maxQueuedOutgoingFrames, 1, 4_096);
        requireBetween("maxQueuedOutgoingBytes", maxQueuedOutgoingBytes, maxOutgoingFrameBytes,
                64 * 1_024 * 1_024);
        requireBetween("maxUnacknowledgedEvents", maxUnacknowledgedEvents, 1, 4_096);
        requireDurationMillis("replayPollInterval", replayPollInterval, 50, 60_000);
        requireDuration("acknowledgementDeadline", acknowledgementDeadline, 1, 300);
        requireDuration("idleTimeout", idleTimeout, 1, 3_600);
        requireDuration("absoluteLifetime", absoluteLifetime, 1, 86_400);
        requireDuration("shutdownTimeout", shutdownTimeout, 1, 60);
    }

    public static InteractionWebSocketConfiguration from(Properties properties,
                                                          Map<String, String> environment) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(environment, "environment");
        boolean enabled = bool(properties, environment, "enabled", false);
        String host = value(properties, environment, "bind", "127.0.0.1");
        int port = integer(properties, environment, "port", 8081);
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException invalid) {
            throw invalid("bind");
        }
        if (!address.getHostAddress().equals(host) && !"localhost".equalsIgnoreCase(host)) throw invalid("bind");
        try {
            return new InteractionWebSocketConfiguration(enabled, new InetSocketAddress(address, port),
                    integer(properties, environment, "max-connections", 256),
                    integer(properties, environment, "pending-authentication", 32),
                    integer(properties, environment, "pending-authentication-per-address", 4),
                    integer(properties, environment, "backend-operations", 128),
                    seconds(properties, environment, "authentication-deadline-seconds", 5),
                    integer(properties, environment, "max-message-bytes", 512 * 1_024),
                    integer(properties, environment, "max-fragments", 16),
                    integer(properties, environment, "pending-commands", 32),
                    integer(properties, environment, "queued-incoming-bytes", 1024 * 1024),
                    integer(properties, environment, "max-outgoing-frame-bytes", 64 * 1_024),
                    integer(properties, environment, "queued-outgoing-frames", 64),
                    integer(properties, environment, "queued-outgoing-bytes", 1024 * 1024),
                    integer(properties, environment, "unacknowledged-events", 64),
                    millis(properties, environment, "replay-poll-millis", 100),
                    seconds(properties, environment, "acknowledgement-deadline-seconds", 30),
                    seconds(properties, environment, "idle-timeout-seconds", 60),
                    seconds(properties, environment, "absolute-lifetime-seconds", 3_600),
                    seconds(properties, environment, "shutdown-timeout-seconds", 5));
        } catch (IllegalArgumentException invalid) {
            if (invalid.getMessage() != null && invalid.getMessage().startsWith("Invalid WebSocket setting:")) {
                throw invalid;
            }
            throw new IllegalArgumentException("Invalid WebSocket setting: bounds", invalid);
        }
    }

    /** Refuses to label an enabled interaction endpoint authenticated when HTTP auth is disabled. */
    public void requireAuthenticatedMode(String mode) {
        if (enabled && "disabled".equals(mode)) {
            throw new IllegalArgumentException(
                    "RAVENROOT_WEBSOCKET_ENABLED requires RAVENROOT_AUTH_MODE=local-token or oidc");
        }
    }

    private static String value(Properties properties, Map<String, String> environment,
                                String name, String fallback) {
        String property = properties.getProperty("ravenroot.websocket." + name);
        if (property != null && !property.isBlank()) return property.trim();
        String env = environment.get("RAVENROOT_WEBSOCKET_" + name.toUpperCase(java.util.Locale.ROOT)
                .replace('-', '_'));
        return env == null || env.isBlank() ? fallback : env.trim();
    }

    private static int integer(Properties properties, Map<String, String> environment,
                               String name, int fallback) {
        try {
            return Integer.parseInt(value(properties, environment, name, Integer.toString(fallback)));
        } catch (NumberFormatException invalid) {
            throw invalid(name);
        }
    }

    private static boolean bool(Properties properties, Map<String, String> environment,
                                String name, boolean fallback) {
        String text = value(properties, environment, name, Boolean.toString(fallback));
        if ("true".equalsIgnoreCase(text)) return true;
        if ("false".equalsIgnoreCase(text)) return false;
        throw invalid(name);
    }

    private static Duration seconds(Properties properties, Map<String, String> environment,
                                    String name, int fallback) {
        return Duration.ofSeconds(integer(properties, environment, name, fallback));
    }

    private static Duration millis(Properties properties, Map<String, String> environment,
                                   String name, int fallback) {
        return Duration.ofMillis(integer(properties, environment, name, fallback));
    }

    private static void requireBetween(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) throw invalid(name);
    }

    private static void requireDuration(String name, Duration value, int minimum, int maximum) {
        if (value == null || value.compareTo(Duration.ofSeconds(minimum)) < 0
                || value.compareTo(Duration.ofSeconds(maximum)) > 0) throw invalid(name);
    }

    private static void requireDurationMillis(String name, Duration value, int minimum, int maximum) {
        if (value == null || value.compareTo(Duration.ofMillis(minimum)) < 0
                || value.compareTo(Duration.ofMillis(maximum)) > 0) throw invalid(name);
    }

    private static IllegalArgumentException invalid(String name) {
        return new IllegalArgumentException("Invalid WebSocket setting: " + name);
    }
}
