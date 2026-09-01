package ai.ravenroot.observability.otel;

import ai.ravenroot.core.runtime.ExecutionMonitor;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.logging.LoggingMetricExporter;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Objects;
import java.util.Optional;

/**
 * Composition-root facade for PLAT-01: the only entry point {@code RavenrootServerMain} and
 * {@code RavenrootCliMain} call. Neither composition root, nor any other module, constructs a
 * {@link TelemetryBridge}, an OpenTelemetry {@code Tracer} or a {@code Meter} directly.
 *
 * <h2>Disabled by default means absent wiring, not an inert object</h2>
 * <p>{@link #install} on a disabled {@link TelemetryConfiguration} returns immediately, before any
 * OpenTelemetry SDK type is constructed and before {@code ExecutionMonitor.subscribe} is ever
 * called. A caller does not need to branch on {@code configuration.enabled()} itself; calling this
 * method is always correct, and its cost when disabled is the one boolean check inside it.</p>
 */
public final class TelemetrySupport {

    private TelemetrySupport() {
    }

    /**
     * Installs the bridge onto {@code monitor} per {@code configuration}, or installs nothing.
     *
     * @return an {@link AutoCloseable} releasing every resource this call created (the store
     *         subscription, the bridge's own still-open spans, and the SDK's tracer and meter
     *         providers), or {@link Optional#empty()} when {@code configuration} is disabled, in
     *         which case there is nothing to release
     */
    public static Optional<AutoCloseable> install(TelemetryConfiguration configuration, ExecutionMonitor monitor) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(monitor, "monitor");
        if (!configuration.enabled()) {
            return Optional.empty();
        }

        Resource resource = Resource.getDefault().merge(Resource.create(
                Attributes.of(AttributeKey.stringKey("service.name"), configuration.serviceName())));

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter(configuration)).build())
                .build();
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .setResource(resource)
                .registerMetricReader(PeriodicMetricReader.builder(metricExporter(configuration)).build())
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .build();

        TelemetryBridge bridge = new TelemetryBridge(sdk);
        AutoCloseable unsubscribe = monitor.subscribe(bridge);

        return Optional.of(() -> {
            unsubscribe.close();
            bridge.close();
            tracerProvider.shutdown();
            meterProvider.shutdown();
        });
    }

    private static SpanExporter spanExporter(TelemetryConfiguration configuration) {
        return switch (configuration.exporterKind()) {
            case LOGGING -> LoggingSpanExporter.create();
            case OTLP -> OtlpHttpSpanExporter.builder()
                    .setEndpoint(signalEndpoint(configuration.otlpEndpoint(), "v1/traces"))
                    .build();
            case NONE -> throw notReachedWithNoneExporter();
        };
    }

    private static MetricExporter metricExporter(TelemetryConfiguration configuration) {
        return switch (configuration.exporterKind()) {
            case LOGGING -> LoggingMetricExporter.create();
            case OTLP -> OtlpHttpMetricExporter.builder()
                    .setEndpoint(signalEndpoint(configuration.otlpEndpoint(), "v1/metrics"))
                    .build();
            case NONE -> throw notReachedWithNoneExporter();
        };
    }

    /** {@code base} is the collector's root, e.g. {@code http://localhost:4318}; {@code signalPath}
     * is the OTLP/HTTP well-known per-signal path, e.g. {@code v1/traces}. */
    private static String signalEndpoint(String base, String signalPath) {
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/" + signalPath;
    }

    private static IllegalStateException notReachedWithNoneExporter() {
        return new IllegalStateException("install() must not be reached with exporterKind() == NONE on an "
                + "enabled configuration -- TelemetryConfiguration's own constructor forbids that "
                + "combination, so reaching this means the invariant was bypassed, not that NONE is a "
                + "legitimate exporter to build");
    }
}
