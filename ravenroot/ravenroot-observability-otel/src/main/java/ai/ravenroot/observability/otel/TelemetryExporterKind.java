package ai.ravenroot.observability.otel;

/**
 * The substitutable exporter selected by {@code RAVENROOT_OTEL_EXPORTER} (PLAT-01).
 *
 * <p>{@link #NONE} is not a real exporter and never appears on a {@link TelemetryConfiguration}
 * that is {@link TelemetryConfiguration#enabled()}: it exists only as the value {@code kind()}
 * would hold on a disabled configuration, so callers never need a separate null check.</p>
 */
public enum TelemetryExporterKind {
    /** No exporter: telemetry is disabled. */
    NONE,
    /** Spans and metrics as structured log lines, via {@code opentelemetry-exporter-logging}. */
    LOGGING,
    /** OTLP/HTTP, via {@code opentelemetry-exporter-otlp}. */
    OTLP
}
