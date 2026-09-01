package ai.ravenroot.observability.otel;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryConfigurationTest {

    @Test
    void anEmptyEnvironmentIsDisabled() {
        var configuration = TelemetryConfiguration.fromEnvironment(Map.of());
        assertFalse(configuration.enabled());
        assertEquals(TelemetryExporterKind.NONE, configuration.exporterKind());
    }

    @Test
    void anythingOtherThanTrueIgnoringCaseAndSurroundingWhitespaceIsDisabled() {
        for (String value : new String[] {"1", "yes", " enabled", "True!", ""}) {
            var configuration = TelemetryConfiguration.fromEnvironment(
                    Map.of(TelemetryConfiguration.ENABLED_VARIABLE, value));
            assertFalse(configuration.enabled(), () -> "'" + value + "' must not enable telemetry");
        }
    }

    @Test
    void surroundingWhitespaceAroundTrueIsTolerated() {
        // A config-map or .env value commonly carries a trailing newline or space; the same
        // trimming NodePackageLoader.fromCommaSeparated already applies to its own entries.
        var configuration = TelemetryConfiguration.fromEnvironment(
                Map.of(TelemetryConfiguration.ENABLED_VARIABLE, " true \n"));
        assertTrue(configuration.enabled());
    }

    @Test
    void trueCaseInsensitiveEnablesWithTheLoggingExporterByDefault() {
        var configuration = TelemetryConfiguration.fromEnvironment(
                Map.of(TelemetryConfiguration.ENABLED_VARIABLE, "TRUE"));
        assertTrue(configuration.enabled());
        assertEquals(TelemetryExporterKind.LOGGING, configuration.exporterKind());
        assertEquals("ravenroot", configuration.serviceName());
    }

    @Test
    void anExplicitLoggingExporterIsHonoured() {
        var configuration = TelemetryConfiguration.fromEnvironment(Map.of(
                TelemetryConfiguration.ENABLED_VARIABLE, "true",
                TelemetryConfiguration.EXPORTER_VARIABLE, "logging"));
        assertEquals(TelemetryExporterKind.LOGGING, configuration.exporterKind());
    }

    @Test
    void otlpWithAnEndpointIsAccepted() {
        var configuration = TelemetryConfiguration.fromEnvironment(Map.of(
                TelemetryConfiguration.ENABLED_VARIABLE, "true",
                TelemetryConfiguration.EXPORTER_VARIABLE, "otlp",
                TelemetryConfiguration.ENDPOINT_VARIABLE, "http://localhost:4318"));
        assertEquals(TelemetryExporterKind.OTLP, configuration.exporterKind());
        assertEquals("http://localhost:4318", configuration.otlpEndpoint());
    }

    @Test
    void otlpWithoutAnEndpointFailsAtConfigurationTimeRatherThanServingDegraded() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> TelemetryConfiguration.fromEnvironment(
                Map.of(TelemetryConfiguration.ENABLED_VARIABLE, "true",
                        TelemetryConfiguration.EXPORTER_VARIABLE, "otlp")));
        assertTrue(thrown.getMessage().contains(TelemetryConfiguration.ENDPOINT_VARIABLE),
                "the failure must name the missing variable, not just say 'invalid'");
    }

    @Test
    void anUnknownExporterNameFailsRatherThanSilentlyFallingBackToLogging() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> TelemetryConfiguration.fromEnvironment(
                Map.of(TelemetryConfiguration.ENABLED_VARIABLE, "true",
                        TelemetryConfiguration.EXPORTER_VARIABLE, "prometheus")));
        assertTrue(thrown.getMessage().contains("prometheus"));
    }

    @Test
    void aDisabledConfigurationCanNeverReportALiveExporterKind() {
        // The invariant the record's compact constructor enforces directly, exercised through the
        // public constructor rather than only through fromEnvironment -- a future second caller of
        // the constructor (there is only fromEnvironment and disabled() today) must not be able to
        // bypass it either.
        assertThrows(IllegalArgumentException.class,
                () -> new TelemetryConfiguration(false, TelemetryExporterKind.LOGGING, null, "ravenroot"));
    }

    @Test
    void disabledFactoryMatchesWhatFromEnvironmentProducesForAnEmptyEnvironment() {
        assertEquals(TelemetryConfiguration.disabled(), TelemetryConfiguration.fromEnvironment(Map.of()));
    }

    @Test
    void blankServiceNameFallsBackToTheDefaultRatherThanPublishingAnEmptyResourceAttribute() {
        var configuration = TelemetryConfiguration.fromEnvironment(Map.of(
                TelemetryConfiguration.ENABLED_VARIABLE, "true",
                TelemetryConfiguration.SERVICE_NAME_VARIABLE, "   "));
        assertEquals("ravenroot", configuration.serviceName());
    }
}
