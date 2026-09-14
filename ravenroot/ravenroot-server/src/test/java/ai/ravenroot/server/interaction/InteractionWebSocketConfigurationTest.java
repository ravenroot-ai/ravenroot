package ai.ravenroot.server.interaction;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionWebSocketConfigurationTest {
    private record Setting(String name, String environmentName, String propertyValue,
                           String environmentValue,
                           Function<InteractionWebSocketConfiguration, Object> read,
                           Object propertyExpected, Object environmentExpected) { }

    private record Boundary(Map<String, String> values,
                            Function<InteractionWebSocketConfiguration, Object> read,
                            Object expected) { }

    @Test
    void allPublishedDefaultsAreExact() {
        var configuration = InteractionWebSocketConfiguration.from(new Properties(), Map.of());

        assertFalse(configuration.enabled());
        assertEquals("127.0.0.1", configuration.bindAddress().getAddress().getHostAddress());
        assertEquals(8081, configuration.bindAddress().getPort());
        assertEquals(256, configuration.maxConnections());
        assertEquals(32, configuration.maxPendingAuthentication());
        assertEquals(4, configuration.maxPendingAuthenticationPerAddress());
        assertEquals(128, configuration.maxBackendOperations());
        assertEquals(Duration.ofSeconds(5), configuration.authenticationDeadline());
        assertEquals(512 * 1024, configuration.maxMessageBytes());
        assertEquals(16, configuration.maxFragments());
        assertEquals(32, configuration.maxPendingCommands());
        assertEquals(1024 * 1024, configuration.maxQueuedIncomingBytes());
        assertEquals(64 * 1024, configuration.maxOutgoingFrameBytes());
        assertEquals(64, configuration.maxQueuedOutgoingFrames());
        assertEquals(1024 * 1024, configuration.maxQueuedOutgoingBytes());
        assertEquals(64, configuration.maxUnacknowledgedEvents());
        assertEquals(Duration.ofMillis(100), configuration.replayPollInterval());
        assertEquals(Duration.ofSeconds(30), configuration.acknowledgementDeadline());
        assertEquals(Duration.ofSeconds(60), configuration.idleTimeout());
        assertEquals(Duration.ofSeconds(3600), configuration.absoluteLifetime());
        assertEquals(Duration.ofSeconds(5), configuration.shutdownTimeout());
    }

    @Test
    void everyPropertyAndEnvironmentBindingResolvesThroughOneTypedAuthority() {
        for (Setting setting : settings()) {
            var fromEnvironment = InteractionWebSocketConfiguration.from(new Properties(),
                    Map.of(setting.environmentName(), setting.environmentValue()));
            assertEquals(setting.environmentExpected(), setting.read().apply(fromEnvironment),
                    setting.name() + " environment binding");

            var properties = new Properties();
            properties.setProperty("ravenroot.websocket." + setting.name(), setting.propertyValue());
            var fromProperty = InteractionWebSocketConfiguration.from(properties,
                    Map.of(setting.environmentName(), setting.environmentValue()));
            assertEquals(setting.propertyExpected(), setting.read().apply(fromProperty),
                    setting.name() + " property precedence");
        }
    }

    @Test
    void blankAndNonStringPropertiesUseTheDocumentedFallbackChain() {
        var blank = new Properties();
        blank.setProperty("ravenroot.websocket.port", "  ");
        assertEquals(9914, InteractionWebSocketConfiguration.from(blank,
                Map.of("RAVENROOT_WEBSOCKET_PORT", "9914")).bindAddress().getPort());

        var nonString = new Properties();
        nonString.put("ravenroot.websocket.port", new Object());
        assertEquals(9915, InteractionWebSocketConfiguration.from(nonString,
                Map.of("RAVENROOT_WEBSOCKET_PORT", "9915")).bindAddress().getPort());

        assertEquals(8081, InteractionWebSocketConfiguration.from(blank,
                Map.of("RAVENROOT_WEBSOCKET_PORT", "  ")).bindAddress().getPort());

        var trimmedProperty = new Properties();
        trimmedProperty.setProperty("ravenroot.websocket.port", " 9917 ");
        assertEquals(9917, InteractionWebSocketConfiguration.from(trimmedProperty,
                Map.of("RAVENROOT_WEBSOCKET_PORT", "9918")).bindAddress().getPort());
        assertEquals(9918, InteractionWebSocketConfiguration.from(new Properties(),
                Map.of("RAVENROOT_WEBSOCKET_PORT", " 9918 ")).bindAddress().getPort());
    }

    @Test
    void resolvedConfigurationIsAnImmutableSnapshotOfInputs() {
        var properties = new Properties();
        properties.setProperty("ravenroot.websocket.port", "9916");
        var environment = new java.util.HashMap<String, String>();
        environment.put("RAVENROOT_WEBSOCKET_MAX_CONNECTIONS", "300");

        var configuration = InteractionWebSocketConfiguration.from(properties, environment);
        properties.setProperty("ravenroot.websocket.port", "9917");
        environment.put("RAVENROOT_WEBSOCKET_MAX_CONNECTIONS", "400");

        assertEquals(9916, configuration.bindAddress().getPort());
        assertEquals(300, configuration.maxConnections());
    }

    @Test
    void strictScalarParsingAndEverySimpleBoundRefuse() {
        var invalidValues = List.of(
                Map.entry("enabled", "yes"), Map.entry("bind", "127.0.0.999"),
                Map.entry("port", "not-an-integer"), Map.entry("port", "0"), Map.entry("port", "65536"),
                Map.entry("max-connections", "0"), Map.entry("max-connections", "100001"),
                Map.entry("pending-authentication", "0"),
                Map.entry("pending-authentication-per-address", "0"),
                Map.entry("backend-operations", "0"), Map.entry("backend-operations", "100001"),
                Map.entry("authentication-deadline-seconds", "0"),
                Map.entry("authentication-deadline-seconds", "61"),
                Map.entry("max-message-bytes", "1023"), Map.entry("max-message-bytes", "16777217"),
                Map.entry("max-fragments", "0"), Map.entry("max-fragments", "1025"),
                Map.entry("pending-commands", "0"), Map.entry("pending-commands", "1025"),
                Map.entry("queued-incoming-bytes", "524287"),
                Map.entry("queued-incoming-bytes", "67108865"),
                Map.entry("max-outgoing-frame-bytes", "1023"),
                Map.entry("max-outgoing-frame-bytes", "524289"),
                Map.entry("queued-outgoing-frames", "0"), Map.entry("queued-outgoing-frames", "4097"),
                Map.entry("queued-outgoing-bytes", "65535"),
                Map.entry("queued-outgoing-bytes", "67108865"),
                Map.entry("unacknowledged-events", "0"), Map.entry("unacknowledged-events", "4097"),
                Map.entry("replay-poll-millis", "49"), Map.entry("replay-poll-millis", "60001"),
                Map.entry("acknowledgement-deadline-seconds", "0"),
                Map.entry("acknowledgement-deadline-seconds", "301"),
                Map.entry("idle-timeout-seconds", "0"), Map.entry("idle-timeout-seconds", "3601"),
                Map.entry("absolute-lifetime-seconds", "0"),
                Map.entry("absolute-lifetime-seconds", "86401"),
                Map.entry("shutdown-timeout-seconds", "0"),
                Map.entry("shutdown-timeout-seconds", "61"));

        for (var invalid : invalidValues) {
            var properties = new Properties();
            properties.setProperty("ravenroot.websocket." + invalid.getKey(), invalid.getValue());
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> InteractionWebSocketConfiguration.from(properties, Map.of()),
                    invalid.getKey() + "=" + invalid.getValue());
            assertTrue(failure.getMessage().startsWith("Invalid WebSocket setting:"), failure::getMessage);
            assertFalse(failure.getMessage().contains(invalid.getValue()),
                    "refusal must not echo a potentially sensitive configured value");
        }
    }

    @Test
    void everySimpleBoundAcceptsItsExactEndpoints() {
        for (Boundary boundary : List.of(
                boundary(Map.of("port", "1"), value -> value.bindAddress().getPort(), 1),
                boundary(Map.of("port", "65535"), value -> value.bindAddress().getPort(), 65535),
                boundary(Map.of("max-connections", "1", "pending-authentication", "1",
                                "pending-authentication-per-address", "1"),
                        InteractionWebSocketConfiguration::maxConnections, 1),
                boundary(Map.of("max-connections", "100000"),
                        InteractionWebSocketConfiguration::maxConnections, 100000),
                boundary(Map.of("pending-authentication", "1", "pending-authentication-per-address", "1"),
                        InteractionWebSocketConfiguration::maxPendingAuthentication, 1),
                boundary(Map.of("pending-authentication", "256"),
                        InteractionWebSocketConfiguration::maxPendingAuthentication, 256),
                boundary(Map.of("pending-authentication-per-address", "1"),
                        InteractionWebSocketConfiguration::maxPendingAuthenticationPerAddress, 1),
                boundary(Map.of("pending-authentication-per-address", "32"),
                        InteractionWebSocketConfiguration::maxPendingAuthenticationPerAddress, 32),
                boundary(Map.of("backend-operations", "1"),
                        InteractionWebSocketConfiguration::maxBackendOperations, 1),
                boundary(Map.of("backend-operations", "100000"),
                        InteractionWebSocketConfiguration::maxBackendOperations, 100000),
                boundary(Map.of("authentication-deadline-seconds", "1"),
                        InteractionWebSocketConfiguration::authenticationDeadline, Duration.ofSeconds(1)),
                boundary(Map.of("authentication-deadline-seconds", "60"),
                        InteractionWebSocketConfiguration::authenticationDeadline, Duration.ofSeconds(60)),
                boundary(Map.of("max-message-bytes", "1024", "max-outgoing-frame-bytes", "1024"),
                        InteractionWebSocketConfiguration::maxMessageBytes, 1024),
                boundary(Map.of("max-message-bytes", "16777216", "queued-incoming-bytes", "16777216"),
                        InteractionWebSocketConfiguration::maxMessageBytes, 16777216),
                boundary(Map.of("max-fragments", "1"), InteractionWebSocketConfiguration::maxFragments, 1),
                boundary(Map.of("max-fragments", "1024"),
                        InteractionWebSocketConfiguration::maxFragments, 1024),
                boundary(Map.of("pending-commands", "1"),
                        InteractionWebSocketConfiguration::maxPendingCommands, 1),
                boundary(Map.of("pending-commands", "1024"),
                        InteractionWebSocketConfiguration::maxPendingCommands, 1024),
                boundary(Map.of("queued-incoming-bytes", "524288"),
                        InteractionWebSocketConfiguration::maxQueuedIncomingBytes, 524288),
                boundary(Map.of("queued-incoming-bytes", "67108864"),
                        InteractionWebSocketConfiguration::maxQueuedIncomingBytes, 67108864),
                boundary(Map.of("max-outgoing-frame-bytes", "1024"),
                        InteractionWebSocketConfiguration::maxOutgoingFrameBytes, 1024),
                boundary(Map.of("max-outgoing-frame-bytes", "524288"),
                        InteractionWebSocketConfiguration::maxOutgoingFrameBytes, 524288),
                boundary(Map.of("queued-outgoing-frames", "1"),
                        InteractionWebSocketConfiguration::maxQueuedOutgoingFrames, 1),
                boundary(Map.of("queued-outgoing-frames", "4096"),
                        InteractionWebSocketConfiguration::maxQueuedOutgoingFrames, 4096),
                boundary(Map.of("queued-outgoing-bytes", "65536"),
                        InteractionWebSocketConfiguration::maxQueuedOutgoingBytes, 65536),
                boundary(Map.of("queued-outgoing-bytes", "67108864"),
                        InteractionWebSocketConfiguration::maxQueuedOutgoingBytes, 67108864),
                boundary(Map.of("unacknowledged-events", "1"),
                        InteractionWebSocketConfiguration::maxUnacknowledgedEvents, 1),
                boundary(Map.of("unacknowledged-events", "4096"),
                        InteractionWebSocketConfiguration::maxUnacknowledgedEvents, 4096),
                boundary(Map.of("replay-poll-millis", "50"),
                        InteractionWebSocketConfiguration::replayPollInterval, Duration.ofMillis(50)),
                boundary(Map.of("replay-poll-millis", "60000"),
                        InteractionWebSocketConfiguration::replayPollInterval, Duration.ofMillis(60000)),
                boundary(Map.of("acknowledgement-deadline-seconds", "1"),
                        InteractionWebSocketConfiguration::acknowledgementDeadline, Duration.ofSeconds(1)),
                boundary(Map.of("acknowledgement-deadline-seconds", "300"),
                        InteractionWebSocketConfiguration::acknowledgementDeadline, Duration.ofSeconds(300)),
                boundary(Map.of("idle-timeout-seconds", "1"),
                        InteractionWebSocketConfiguration::idleTimeout, Duration.ofSeconds(1)),
                boundary(Map.of("idle-timeout-seconds", "3600"),
                        InteractionWebSocketConfiguration::idleTimeout, Duration.ofSeconds(3600)),
                boundary(Map.of("absolute-lifetime-seconds", "1"),
                        InteractionWebSocketConfiguration::absoluteLifetime, Duration.ofSeconds(1)),
                boundary(Map.of("absolute-lifetime-seconds", "86400"),
                        InteractionWebSocketConfiguration::absoluteLifetime, Duration.ofSeconds(86400)),
                boundary(Map.of("shutdown-timeout-seconds", "1"),
                        InteractionWebSocketConfiguration::shutdownTimeout, Duration.ofSeconds(1)),
                boundary(Map.of("shutdown-timeout-seconds", "60"),
                        InteractionWebSocketConfiguration::shutdownTimeout, Duration.ofSeconds(60)))) {
            assertEquals(boundary.expected(), boundary.read().apply(configuration(boundary.values())),
                    "exact inclusive endpoint must be preserved: " + boundary.values());
        }
    }

    @Test
    void everyCrossFieldBoundRefuses() {
        for (var boundary : List.of(
                Map.entry(Map.of("max-connections", "50", "pending-authentication", "50"),
                        Map.of("max-connections", "50", "pending-authentication", "51")),
                Map.entry(Map.of("pending-authentication", "10",
                                "pending-authentication-per-address", "10"),
                        Map.of("pending-authentication", "10",
                                "pending-authentication-per-address", "11")),
                Map.entry(Map.of("max-message-bytes", "2048", "queued-incoming-bytes", "2048",
                                "max-outgoing-frame-bytes", "1024"),
                        Map.of("max-message-bytes", "2048", "queued-incoming-bytes", "2047",
                                "max-outgoing-frame-bytes", "1024")),
                Map.entry(Map.of("max-message-bytes", "2048", "max-outgoing-frame-bytes", "2048"),
                        Map.of("max-message-bytes", "2048", "max-outgoing-frame-bytes", "2049")),
                Map.entry(Map.of("max-outgoing-frame-bytes", "2048", "queued-outgoing-bytes", "2048"),
                        Map.of("max-outgoing-frame-bytes", "2048", "queued-outgoing-bytes", "2047")))) {
            assertTrue(configuration(boundary.getKey()).maxConnections() > 0,
                    "the exact dynamic boundary must be accepted: " + boundary.getKey());
            assertThrows(IllegalArgumentException.class,
                    () -> configuration(boundary.getValue()), boundary.getValue().toString());
        }
    }

    @Test
    void enabledListenerRefusesDisabledAuthentication() {
        InteractionWebSocketConfiguration.from(new Properties(), Map.of())
                .requireAuthenticatedMode("disabled");

        var properties = new Properties();
        properties.setProperty("ravenroot.websocket.enabled", "true");
        var configuration = InteractionWebSocketConfiguration.from(properties, Map.of());

        assertThrows(IllegalArgumentException.class,
                () -> configuration.requireAuthenticatedMode("disabled"));
        configuration.requireAuthenticatedMode("local-token");
        configuration.requireAuthenticatedMode("oidc");
    }

    private static InteractionWebSocketConfiguration configuration(Map<String, String> values) {
        var properties = new Properties();
        values.forEach((name, value) -> properties.setProperty("ravenroot.websocket." + name, value));
        return InteractionWebSocketConfiguration.from(properties, Map.of());
    }

    private static Boundary boundary(Map<String, String> values,
                                     Function<InteractionWebSocketConfiguration, Object> read,
                                     Object expected) {
        return new Boundary(values, read, expected);
    }

    private static List<Setting> settings() {
        return List.of(
                setting("enabled", "RAVENROOT_WEBSOCKET_ENABLED", "true", "true",
                        InteractionWebSocketConfiguration::enabled, true, true),
                setting("bind", "RAVENROOT_WEBSOCKET_BIND", "0.0.0.0", "localhost",
                        value -> value.bindAddress().getAddress().getHostAddress(), "0.0.0.0", "127.0.0.1"),
                setting("port", "RAVENROOT_WEBSOCKET_PORT", "9091", "9092",
                        value -> value.bindAddress().getPort(), 9091, 9092),
                setting("max-connections", "RAVENROOT_WEBSOCKET_MAX_CONNECTIONS", "300", "301",
                        InteractionWebSocketConfiguration::maxConnections, 300, 301),
                setting("pending-authentication", "RAVENROOT_WEBSOCKET_PENDING_AUTHENTICATION", "40", "41",
                        InteractionWebSocketConfiguration::maxPendingAuthentication, 40, 41),
                setting("pending-authentication-per-address",
                        "RAVENROOT_WEBSOCKET_PENDING_AUTHENTICATION_PER_ADDRESS", "5", "6",
                        InteractionWebSocketConfiguration::maxPendingAuthenticationPerAddress, 5, 6),
                setting("backend-operations", "RAVENROOT_WEBSOCKET_BACKEND_OPERATIONS", "140", "141",
                        InteractionWebSocketConfiguration::maxBackendOperations, 140, 141),
                setting("authentication-deadline-seconds",
                        "RAVENROOT_WEBSOCKET_AUTHENTICATION_DEADLINE_SECONDS", "6", "7",
                        InteractionWebSocketConfiguration::authenticationDeadline,
                        Duration.ofSeconds(6), Duration.ofSeconds(7)),
                setting("max-message-bytes", "RAVENROOT_WEBSOCKET_MAX_MESSAGE_BYTES", "614400", "615424",
                        InteractionWebSocketConfiguration::maxMessageBytes, 614400, 615424),
                setting("max-fragments", "RAVENROOT_WEBSOCKET_MAX_FRAGMENTS", "20", "21",
                        InteractionWebSocketConfiguration::maxFragments, 20, 21),
                setting("pending-commands", "RAVENROOT_WEBSOCKET_PENDING_COMMANDS", "40", "41",
                        InteractionWebSocketConfiguration::maxPendingCommands, 40, 41),
                setting("queued-incoming-bytes", "RAVENROOT_WEBSOCKET_QUEUED_INCOMING_BYTES",
                        "2097152", "2098176", InteractionWebSocketConfiguration::maxQueuedIncomingBytes,
                        2097152, 2098176),
                setting("max-outgoing-frame-bytes", "RAVENROOT_WEBSOCKET_MAX_OUTGOING_FRAME_BYTES",
                        "131072", "132096", InteractionWebSocketConfiguration::maxOutgoingFrameBytes,
                        131072, 132096),
                setting("queued-outgoing-frames", "RAVENROOT_WEBSOCKET_QUEUED_OUTGOING_FRAMES", "65", "66",
                        InteractionWebSocketConfiguration::maxQueuedOutgoingFrames, 65, 66),
                setting("queued-outgoing-bytes", "RAVENROOT_WEBSOCKET_QUEUED_OUTGOING_BYTES",
                        "2097152", "2098176", InteractionWebSocketConfiguration::maxQueuedOutgoingBytes,
                        2097152, 2098176),
                setting("unacknowledged-events", "RAVENROOT_WEBSOCKET_UNACKNOWLEDGED_EVENTS", "65", "66",
                        InteractionWebSocketConfiguration::maxUnacknowledgedEvents, 65, 66),
                setting("replay-poll-millis", "RAVENROOT_WEBSOCKET_REPLAY_POLL_MILLIS", "110", "120",
                        InteractionWebSocketConfiguration::replayPollInterval,
                        Duration.ofMillis(110), Duration.ofMillis(120)),
                setting("acknowledgement-deadline-seconds",
                        "RAVENROOT_WEBSOCKET_ACKNOWLEDGEMENT_DEADLINE_SECONDS", "31", "32",
                        InteractionWebSocketConfiguration::acknowledgementDeadline,
                        Duration.ofSeconds(31), Duration.ofSeconds(32)),
                setting("idle-timeout-seconds", "RAVENROOT_WEBSOCKET_IDLE_TIMEOUT_SECONDS", "61", "62",
                        InteractionWebSocketConfiguration::idleTimeout,
                        Duration.ofSeconds(61), Duration.ofSeconds(62)),
                setting("absolute-lifetime-seconds", "RAVENROOT_WEBSOCKET_ABSOLUTE_LIFETIME_SECONDS",
                        "3601", "3602", InteractionWebSocketConfiguration::absoluteLifetime,
                        Duration.ofSeconds(3601), Duration.ofSeconds(3602)),
                setting("shutdown-timeout-seconds", "RAVENROOT_WEBSOCKET_SHUTDOWN_TIMEOUT_SECONDS", "6", "7",
                        InteractionWebSocketConfiguration::shutdownTimeout,
                        Duration.ofSeconds(6), Duration.ofSeconds(7)));
    }

    private static Setting setting(String name, String environmentName, String propertyValue,
                                   String environmentValue,
                                   Function<InteractionWebSocketConfiguration, Object> read,
                                   Object propertyExpected, Object environmentExpected) {
        return new Setting(name, environmentName, propertyValue, environmentValue,
                read, propertyExpected, environmentExpected);
    }
}
