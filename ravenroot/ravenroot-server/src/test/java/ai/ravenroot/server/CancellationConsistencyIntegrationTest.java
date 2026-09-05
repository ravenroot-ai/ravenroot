package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionControlAuditSink;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.core.audit.InMemoryAuditTrail;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.audit.AuditTrailExecutionSink;
import ai.ravenroot.server.audit.StructuredArtifactLifecycleLogger;
import ai.ravenroot.server.audit.StructuredGraphMlRejectionLogger;
import ai.ravenroot.server.error.StructuredPayloadRejectionLogger;
import ai.ravenroot.server.ratelimit.ActiveExecutionRegistry;
import ai.ravenroot.server.ratelimit.RateLimitAuditEvent;
import ai.ravenroot.server.ratelimit.RateLimitAuditSink;
import ai.ravenroot.server.ratelimit.RateLimitConfiguration;
import ai.ravenroot.server.ratelimit.RateLimiter;
import ai.ravenroot.server.ratelimit.TrustedProxyConfiguration;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.AuthenticationException;
import ai.ravenroot.server.security.BrowserOriginPolicy;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import ai.ravenroot.server.security.HttpSecurityConfiguration;
import ai.ravenroot.server.security.RequestAuthenticator;
import ai.ravenroot.server.security.SecurityHeadersPolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The end-to-end proof that a single real cancellation is consistent across every consumer this
 * wave touched inside {@code ravenroot-server}: admission accounting releases the slot it was
 * holding, and the durable audit trail records the cancellation as its own action rather than
 * dropping it or misfiling it as {@code execution.failed}.
 *
 * <h2>Why one test, not three</h2>
 * <p>{@code ActiveExecutionAdmissionHttpTest} and {@code AuditTrailExecutionSinkTest} each already
 * prove their own consumer in isolation, against a hand-built {@code ExecutionEvent} or a stub
 * application. Neither proves the property an operator actually depends on: that ONE real
 * cancellation, through the real {@code RavenrootApplication}/{@code ExecutionMonitor}/event-stream
 * pipeline, reaches every subscriber consistently. That is exactly the shape the three regressions
 * this feature fixed shared -- each consumer independently maintained its own list of "the terminal
 * event types I handle" and independently forgot to add the new one -- and a unit test against a
 * synthetic event cannot catch a producer/consumer wiring gap the way a real traversal can.</p>
 *
 * <h2>What this deliberately does not assert</h2>
 * <p>It does not assert a span was ended: {@code TelemetryBridge} is package-private to
 * {@code ravenroot-observability-otel} and {@code TelemetrySupport}, this module's only public seam
 * onto it, exports real OpenTelemetry SDK wiring (OTLP/HTTP or logging) with no in-memory test
 * hook -- there is no seam here to read span data back through. The span-ended property is proved
 * against the real {@link ai.ravenroot.core.runtime.GraphRunner} pipeline instead, in
 * {@code TelemetryEndToEndTest} (ravenroot-observability-otel), the module that can actually see the
 * bridge.</p>
 *
 * <h2>A correction the wave-2 brief needed</h2>
 * <p>{@code GraphRunner.cancelTraversal} does not preempt a node computation already dispatched: it
 * refuses the traversal's <em>next</em> hop. The hang node below is therefore cancelled between
 * hops, not interrupted mid-computation -- exactly {@code RunawayLoopCancellationTest}'s own model,
 * reproduced here as a single BEHAVIOR node whose one dispatch is already in flight when the cancel
 * lands, so the refusal takes effect on the edge to {@code end} rather than by stopping {@code hang}
 * itself. This is why the fixture must wait past the node's own delay: the cancellation is observed
 * lazily, at the next scheduling point, not eagerly.</p>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CancellationConsistencyIntegrationTest {
    /** {@link DisabledLoopbackAuthenticator}'s own fixed tenant. */
    private static final String TENANT = "local";

    private static BehaviorRegistry hangingBehaviors(CountDownLatch reached) {
        return BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults())
                .register("hang", message -> {
                    reached.countDown();
                    try {
                        Thread.sleep(2_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                });
    }

    private static final String HANG_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="cancellation-consistency-test" edgedefault="directed">
                <node id="start"><data key="kind">START</data></node>
                <node id="hang"><data key="kind">BEHAVIOR</data><data key="behavior">hang</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="hang"><data key="outcome">continue</data></edge>
                <edge id="e2" source="hang" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    @Test
    void aRealCancellationReleasesTheAdmissionSlotAndWritesOneAuditRow() throws Exception {
        var hangReached = new CountDownLatch(1);
        var nanos = new AtomicLong();
        var rateLimitAudit = new RecordingRateLimitAudit();
        var limits = limits(builder -> builder.activeExecutions(4));
        var auditTrail = new InMemoryAuditTrail();

        try (var engine = new PekkoExecutionEngine("cancellation-consistency-test");
             var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                     hangingBehaviors(hangReached));
             var limiter = new RateLimiter(limits, TrustedProxyConfiguration.direct(), rateLimitAudit, nanos::get);
             var trail = auditTrail) {
            var quiet = new PrintStream(java.io.OutputStream.nullOutputStream());
            try (var server = new RavenrootServer(application,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, true,
                    new DisabledLoopbackAuthenticator(), httpSecurity(), Clock.systemUTC(),
                    new ai.ravenroot.api.security.DefaultAuthorizationService(
                            new ai.ravenroot.server.audit.StructuredAuthorizationLogger(quiet)),
                    limiter, new StructuredArtifactLifecycleLogger(quiet),
                    ai.ravenroot.server.readiness.ReadinessGate.engineOnly(() -> application.status().state()),
                    ai.ravenroot.server.readiness.ReadinessConfiguration.defaults().httpStopDelay(),
                    new StructuredGraphMlRejectionLogger(quiet), new StructuredPayloadRejectionLogger(quiet),
                    new AuditTrailExecutionSink(trail),
                    ai.ravenroot.server.readiness.ReadinessConfiguration.defaults().drainGracePeriod(),
                    (ExecutionControlAuditSink) event -> { })) {
                server.start();
                var client = HttpClient.newHttpClient();

                var submitted = client.send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + server.port() + "/v1/executions?mode=run"))
                        .header("Content-Type", "application/xml")
                        .POST(HttpRequest.BodyPublishers.ofString(HANG_GRAPH)).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(202, submitted.statusCode(), submitted.body());
                String executionId = extract(submitted.body(), "executionId");
                assertTrue(hangReached.await(5, TimeUnit.SECONDS), "the hang node never started");

                assertEquals(1, limiter.activeExecutions().activeFor(TENANT),
                        "the admission slot must be held while the hang node is genuinely in flight");

                var cancelled = client.send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + server.port() + "/v1/executions/" + executionId
                                        + "/cancel"))
                        .POST(HttpRequest.BodyPublishers.ofString("")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, cancelled.statusCode(), cancelled.body());

                // Lazy, not eager (see this class's own Javadoc): the cancellation is observed at the
                // next scheduling point, once the in-flight hang node's own delay elapses, not by
                // interrupting it. Polled rather than slept for a fixed bound, so this is a real wait
                // for the effect rather than a guess at its timing.
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (limiter.activeExecutions().activeFor(TENANT) > 0 && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
                assertEquals(0, limiter.activeExecutions().activeFor(TENANT),
                        "regression: a cancelled execution must release its admission slot exactly "
                                + "like a completed or a failed one -- ActiveExecutionRegistry.observe "
                                + "previously matched only EXECUTION_COMPLETED/EXECUTION_FAILED and "
                                + "treated EXECUTION_CANCELLED as an unhandled, no-op event type");

                List<ai.ravenroot.api.audit.AuditRecord> records = auditTrail.read(TENANT, 0, 100);
                assertTrue(records.stream().anyMatch(record ->
                                "execution.cancelled".equals(record.envelope().action())),
                        () -> "regression: the durable audit trail must record a cancellation as its "
                                + "own action -- it previously fell out of AuditTrailExecutionSink's "
                                + "DECISIONAL filter entirely, dropping silently rather than being "
                                + "recorded (even wrongly) as execution.failed: "
                                + records.stream().map(record -> record.envelope().action()).toList());
                assertTrue(records.stream().noneMatch(record ->
                                "execution.failed".equals(record.envelope().action())),
                        "a cancellation must never be filed as an ordinary execution failure");
            }
        }
    }

    private static HttpSecurityConfiguration httpSecurity() {
        return new HttpSecurityConfiguration(new BrowserOriginPolicy(Set.of("http://127.0.0.1:1")),
                new SecurityHeadersPolicy(false), Duration.ofSeconds(30));
    }

    private static String extract(String json, String field) {
        var matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
        assertTrue(matcher.find(), () -> "no " + field + " in " + json);
        return matcher.group(1);
    }

    private static RateLimitConfiguration limits(java.util.function.UnaryOperator<Limits> customise) {
        return customise.apply(new Limits()).build();
    }

    /** Every budget except the one this fixture exercises is raised out of the way. */
    private static final class Limits {
        private int globalActiveExecutions = RateLimitConfiguration.DEFAULTS.globalActiveExecutions();

        Limits activeExecutions(int global) {
            this.globalActiveExecutions = global;
            return this;
        }

        RateLimitConfiguration build() {
            var defaults = RateLimitConfiguration.DEFAULTS;
            return new RateLimitConfiguration(
                    100_000, 100_000,
                    100_000, 100_000,
                    100_000, 100_000,
                    100_000, 100_000,
                    defaults.tenantConcurrentSubmissions(), globalActiveExecutions,
                    defaults.tenantConcurrentStreams(), defaults.principalConcurrentStreams(),
                    defaults.streamQueueCapacity(),
                    defaults.maxQueryBytes(), defaults.maxQueryParameters(),
                    defaults.maxHeaderCount(), defaults.maxHeaderBytes(), defaults.maxHeaderValueBytes(),
                    defaults.maxTrackedClients(), defaults.maxTrackedTenants(),
                    defaults.maxTrackedPrincipals(), defaults.idleEntryTtl(), defaults.executionMaxAge());
        }
    }

    private static final class RecordingRateLimitAudit implements RateLimitAuditSink {
        @Override
        public void record(RateLimitAuditEvent event) {
            // Not asserted on here: ActiveExecutionAdmissionHttpTest already proves the reclamation
            // audit trail in isolation. This sink only needs to exist so RateLimiter has one to call.
        }
    }
}
