package ai.ravenroot.observability.otel;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime configuration for the OpenTelemetry bridge (PLAT-01), read from environment
 * variables at a composition root, exactly the pattern {@code AuthenticationConfiguration},
 * {@code RateLimitConfiguration}, {@code ArtifactLifecycleConfiguration} and
 * {@code NodePackageLoader} already use in {@code ravenroot-server} and {@code ravenroot-core}.
 *
 * <p>This deliberately does not answer "where does platform configuration live": it is a bare
 * environment-variable read, with no config file and no new configuration subsystem. If the
 * platform adopts a different mechanism, this class is the one place that needs to change.</p>
 *
 * <h2>Disabled by default, and what "disabled" means here</h2>
 * <p>{@link #enabled()} is {@code false} unless {@value #ENABLED_VARIABLE} is exactly {@code "true"}
 * (case-insensitive). A disabled configuration's {@link #exporterKind()} is always {@link
 * TelemetryExporterKind#NONE}, whatever {@value #EXPORTER_VARIABLE} says: this is what makes
 * "disabled" mean <em>absent wiring</em> rather than <em>a no-op object that still allocates</em>.
 * A composition root reading {@link #enabled()} {@code == false} does not construct an OpenTelemetry
 * {@code SdkTracerProvider}, does not construct a {@code Meter}, and does not call
 * {@code ExecutionMonitor.subscribe} at all &mdash; see {@link TelemetrySupport#install}.</p>
 */
public record TelemetryConfiguration(boolean enabled, TelemetryExporterKind exporterKind, String otlpEndpoint,
                                     String serviceName) {

    /** {@code "true"} (case-insensitive) enables the bridge. Any other value, or absence, disables it. */
    public static final String ENABLED_VARIABLE = "RAVENROOT_OTEL_ENABLED";

    /** {@code logging} or {@code otlp}. Ignored, and not required, when disabled. Defaults to {@code logging}. */
    public static final String EXPORTER_VARIABLE = "RAVENROOT_OTEL_EXPORTER";

    /** Required when {@value #EXPORTER_VARIABLE} is {@code otlp}; ignored otherwise. */
    public static final String ENDPOINT_VARIABLE = "RAVENROOT_OTEL_ENDPOINT";

    /** OpenTelemetry resource {@code service.name}. Defaults to {@code ravenroot}. */
    public static final String SERVICE_NAME_VARIABLE = "RAVENROOT_OTEL_SERVICE_NAME";

    public TelemetryConfiguration {
        Objects.requireNonNull(exporterKind, "exporterKind");
        if (!enabled && exporterKind != TelemetryExporterKind.NONE) {
            throw new IllegalArgumentException(
                    "a disabled TelemetryConfiguration must report exporterKind() == NONE, got "
                            + exporterKind + " -- a caller that only checks enabled() must never be able to "
                            + "observe a live exporter kind on a configuration that told it telemetry was off");
        }
        if (enabled && exporterKind == TelemetryExporterKind.OTLP
                && (otlpEndpoint == null || otlpEndpoint.isBlank())) {
            throw new IllegalArgumentException(ENDPOINT_VARIABLE + " is required when " + EXPORTER_VARIABLE
                    + "=otlp");
        }
        serviceName = (serviceName == null || serviceName.isBlank()) ? "ravenroot" : serviceName;
    }

    /** The disabled configuration: {@link #enabled()} {@code false}, {@link #exporterKind()} {@code NONE}. */
    public static TelemetryConfiguration disabled() {
        return new TelemetryConfiguration(false, TelemetryExporterKind.NONE, null, "ravenroot");
    }

    /**
     * Reads {@code environment} the same way every other {@code fromEnvironment} composition-root
     * helper in this codebase does: no defaulting to a live process's actual environment inside this
     * method, so a caller (and a test) always supplies the map explicitly.
     *
     * @throws IllegalArgumentException {@value #EXPORTER_VARIABLE} names an unknown exporter, or
     *                                  names {@code otlp} with no {@value #ENDPOINT_VARIABLE} set --
     *                                  fails at startup, the same posture {@code NodePackageLoader}
     *                                  takes for a misconfigured node package: told once, loudly,
     *                                  rather than served silently degraded
     */
    public static TelemetryConfiguration fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        boolean enabled = "true".equalsIgnoreCase(trimmed(environment.get(ENABLED_VARIABLE)));
        if (!enabled) {
            return disabled();
        }
        String serviceName = trimmed(environment.get(SERVICE_NAME_VARIABLE));
        String exporterValue = trimmed(environment.get(EXPORTER_VARIABLE));
        TelemetryExporterKind kind = exporterValue == null
                ? TelemetryExporterKind.LOGGING
                : parseExporterKind(exporterValue);
        String endpoint = trimmed(environment.get(ENDPOINT_VARIABLE));
        return new TelemetryConfiguration(true, kind, endpoint, serviceName);
    }

    private static TelemetryExporterKind parseExporterKind(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "logging" -> TelemetryExporterKind.LOGGING;
            case "otlp" -> TelemetryExporterKind.OTLP;
            default -> throw new IllegalArgumentException(EXPORTER_VARIABLE + "='" + value
                    + "' is not a recognised exporter. Use 'logging' or 'otlp', or unset "
                    + ENABLED_VARIABLE + " to disable telemetry entirely.");
        };
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
