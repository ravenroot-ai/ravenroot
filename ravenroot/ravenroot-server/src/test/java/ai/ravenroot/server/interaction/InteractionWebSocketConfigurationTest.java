package ai.ravenroot.server.interaction;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionWebSocketConfigurationTest {
    @Test
    void absentAndBlankValuesUseSafeDefaults() {
        var configuration = InteractionWebSocketConfiguration.from(new Properties(),
                Map.of("RAVENROOT_WEBSOCKET_ENABLED", " "));

        assertFalse(configuration.enabled());
        assertTrue(configuration.bindAddress().getAddress().isLoopbackAddress());
        assertEquals(8081, configuration.bindAddress().getPort());
        assertEquals(512 * 1024, configuration.maxMessageBytes());
        assertEquals(1024 * 1024, configuration.maxQueuedIncomingBytes());
        assertEquals(128, configuration.maxBackendOperations());
        assertEquals(java.time.Duration.ofMillis(100), configuration.replayPollInterval());
        assertEquals(64, configuration.maxUnacknowledgedEvents());
    }

    @Test
    void systemPropertyOverridesEnvironment() {
        var properties = new Properties();
        properties.setProperty("ravenroot.websocket.port", "9912");
        properties.setProperty("ravenroot.websocket.enabled", "true");

        var configuration = InteractionWebSocketConfiguration.from(properties,
                Map.of("RAVENROOT_WEBSOCKET_PORT", "9913"));

        assertTrue(configuration.enabled());
        assertEquals(9912, configuration.bindAddress().getPort());
    }

    @Test
    void blankSystemPropertyFallsThroughToEnvironment() {
        var properties = new Properties();
        properties.setProperty("ravenroot.websocket.port", "  ");

        var configuration = InteractionWebSocketConfiguration.from(properties,
                Map.of("RAVENROOT_WEBSOCKET_PORT", "9914"));

        assertEquals(9914, configuration.bindAddress().getPort());
    }

    @Test
    void invalidNonblankValueNamesSettingWithoutEchoingValue() {
        var properties = new Properties();
        properties.setProperty("ravenroot.websocket.max-message-bytes", "secret-value");

        var failure = assertThrows(IllegalArgumentException.class,
                () -> InteractionWebSocketConfiguration.from(properties, Map.of()));

        assertEquals("Invalid WebSocket setting: max-message-bytes", failure.getMessage());
    }

    @Test
    void enabledListenerRefusesDisabledAuthentication() {
        var properties = new Properties();
        properties.setProperty("ravenroot.websocket.enabled", "true");
        var configuration = InteractionWebSocketConfiguration.from(properties, Map.of());

        assertThrows(IllegalArgumentException.class,
                () -> configuration.requireAuthenticatedMode("disabled"));
        configuration.requireAuthenticatedMode("local-token");
    }
}
