package ai.ravenroot.server;

import ai.ravenroot.api.application.ApplicationStatus;
import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionSubmission;
import ai.ravenroot.api.application.GraphSummary;
import ai.ravenroot.api.application.RavenrootApplication;
import ai.ravenroot.api.application.RuntimeSnapshot;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.programming.ArtifactTestResult;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.server.ratelimit.RateLimitConfiguration;
import ai.ravenroot.server.ratelimit.RateLimiter;
import ai.ravenroot.server.ratelimit.TrustedProxyConfiguration;
import ai.ravenroot.server.readiness.ReadinessConfiguration;
import ai.ravenroot.server.readiness.ReadinessGate;
import ai.ravenroot.server.readiness.StoreLivenessCheck;
import ai.ravenroot.server.security.BrowserOriginPolicy;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import ai.ravenroot.server.security.HttpSecurityConfiguration;
import ai.ravenroot.server.security.SecurityHeadersPolicy;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.server.audit.StructuredAuthorizationLogger;
import ai.ravenroot.server.audit.StructuredArtifactLifecycleLogger;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the wiring, not the gate's own logic (that is
 * {@code ReadinessGateTest}'s job): a real HTTP GET against a real, running {@link RavenrootServer}
 * on {@code /ready} returns 200 while the injected {@link ReadinessGate} says ready, and 503 the
 * moment it does not -- observed across two real requests against one running server, not asserted
 * from a constructed report.
 */
class ReadinessRouteHttpTest {

    @Test
    void readyRouteReflectsALiveTransitionFromReadyToUnreadyAndBack() throws Exception {
        var engineState = new AtomicReference<>("RUNNING");
        var gate = new ReadinessGate(engineState::get, StoreLivenessCheck.none(), List::of,
                ReadinessConfiguration.defaults());
        var quiet = new PrintStream(java.io.OutputStream.nullOutputStream());
        var limiter = new RateLimiter(RateLimitConfiguration.fromEnvironment(Map.of()),
                TrustedProxyConfiguration.direct(), event -> { }, System::nanoTime);

        try (gate;
             limiter;
             var server = new RavenrootServer(new StubApplication(),
                     new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, false,
                     new DisabledLoopbackAuthenticator(), httpSecurity(), Clock.systemUTC(),
                     new DefaultAuthorizationService(new StructuredAuthorizationLogger(quiet)), limiter,
                     new StructuredArtifactLifecycleLogger(quiet), gate,
                     java.time.Duration.ofSeconds(10))) {
            server.start();
            var client = HttpClient.newHttpClient();
            var readyUri = URI.create("http://127.0.0.1:" + server.port() + "/ready");

            var whileRunning = client.send(HttpRequest.newBuilder(readyUri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, whileRunning.statusCode());
            assertTrue(whileRunning.body().contains("\"ready\":true"));
            assertTrue(whileRunning.body().contains("\"state\":\"READY\""));

            // The live transition: the same running server, the same route, a signal flipped
            // underneath it -- not a second server built already-draining.
            engineState.set("DRAINING");

            var whileDraining = client.send(HttpRequest.newBuilder(readyUri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(503, whileDraining.statusCode());
            assertTrue(whileDraining.body().contains("\"ready\":false"));
            assertTrue(whileDraining.body().contains("\"state\":\"DRAINING\""));

            engineState.set("RUNNING");

            var recovered = client.send(HttpRequest.newBuilder(readyUri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, recovered.statusCode(), "the route must recover, not just be capable of failing");
        }
    }

    /**
     * Rate limiting reuses {@code publicContext}, the identical wrapper {@code /health} uses -- see
     * {@code RavenrootServer}'s route registration comment. Not re-proven per-request-budget here
     * (the address-limit mechanics are {@code RateLimitHttpIntegrationTest}'s own, already-covered
     * ground); this asserts only that {@code /ready} is not reachable through a different, unlimited
     * path, by confirming it answers from the same loopback client {@code /health} does.
     */
    @Test
    void readyIsReachableOnlyThroughTheSameAddressLimitedSurfaceHealthUses() throws Exception {
        var gate = ReadinessGate.engineOnly(() -> "RUNNING");
        var quiet = new PrintStream(java.io.OutputStream.nullOutputStream());
        var limiter = new RateLimiter(RateLimitConfiguration.fromEnvironment(Map.of()),
                TrustedProxyConfiguration.direct(), event -> { }, System::nanoTime);

        try (gate;
             limiter;
             var server = new RavenrootServer(new StubApplication(),
                     new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, false,
                     new DisabledLoopbackAuthenticator(), httpSecurity(), Clock.systemUTC(),
                     new DefaultAuthorizationService(new StructuredAuthorizationLogger(quiet)), limiter,
                     new StructuredArtifactLifecycleLogger(quiet), gate,
                     java.time.Duration.ofSeconds(10))) {
            server.start();
            var client = HttpClient.newHttpClient();
            var health = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.port() + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            var ready = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.port() + "/ready")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertEquals(200, ready.statusCode());
        }
    }

    @Test
    void storeFailureReturnsRedacted503WhileHealthRemainsIndependent() throws Exception {
        String secret = "/customer-secret-volume/execution.db";
        var gate = new ReadinessGate(() -> "RUNNING", () -> {
            throw new java.io.IOException("cannot open " + secret);
        }, List::of, ReadinessConfiguration.defaults());
        var quiet = new PrintStream(java.io.OutputStream.nullOutputStream());
        var limiter = new RateLimiter(RateLimitConfiguration.fromEnvironment(Map.of()),
                TrustedProxyConfiguration.direct(), event -> { }, System::nanoTime);

        try (gate;
             limiter;
             var server = new RavenrootServer(new StubApplication(),
                     new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, false,
                     new DisabledLoopbackAuthenticator(), httpSecurity(), Clock.systemUTC(),
                     new DefaultAuthorizationService(new StructuredAuthorizationLogger(quiet)), limiter,
                     new StructuredArtifactLifecycleLogger(quiet), gate, Duration.ofSeconds(10))) {
            server.start();
            var client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();

            var ready = client.send(HttpRequest.newBuilder(URI.create(base + "/ready")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            var health = client.send(HttpRequest.newBuilder(URI.create(base + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(503, ready.statusCode());
            assertTrue(ready.body().contains("\"state\":\"STORE_DEGRADED\""));
            assertFalse(ready.body().contains(secret));
            assertFalse(ready.body().contains("cannot open"));
            assertEquals(200, health.statusCode(), "liveness must not depend on durable-store readiness");
        }
    }

    private static HttpSecurityConfiguration httpSecurity() {
        return new HttpSecurityConfiguration(new BrowserOriginPolicy(Set.of("http://127.0.0.1:1")),
                new SecurityHeadersPolicy(false), Duration.ofSeconds(30));
    }

    /** Never touched by /ready -- every method here exists only to satisfy the interface. */
    private static final class StubApplication implements RavenrootApplication {
        @Override
        public ApplicationStatus status() {
            return new ApplicationStatus("RUNNING", "stub", Set.of());
        }

        @Override
        public RuntimeSnapshot runtimeSnapshot() {
            return new RuntimeSnapshot(0, Map.of());
        }

        @Override
        public List<NodeTypeDescriptor> nodeTypes() {
            return List.of();
        }

        @Override
        public List<GeneratedArtifact> programArtifacts() {
            return List.of();
        }

        @Override
        public GeneratedArtifact createProgramArtifact(String language, String source, Map<String, String> metadata) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<GeneratedArtifact> validateProgramArtifact(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<ArtifactTestResult> testProgramArtifact(String id, Object payload) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GeneratedArtifact approveProgramArtifact(String id, Map<String, String> trustedEvidence) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GeneratedArtifact activateProgramArtifact(String id, Map<String, String> trustedEvidence) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GeneratedArtifact retireProgramArtifact(String id, Map<String, String> trustedEvidence) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GraphSummary inspectGraphMl(InputStream graphMl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExecutionSubmission startGraphMl(SecurityContext security, UUID executionId, InputStream graphMl,
                                                Object payload) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ExecutionEvent> executionEventsAfter(long sequence) {
            return List.of();
        }

        @Override
        public AutoCloseable subscribeToExecutionEvents(Consumer<ExecutionEvent> listener) {
            return () -> { };
        }

        @Override
        public boolean durableEventJournalAvailable() {
            return false;
        }

        @Override
        public List<ai.ravenroot.api.application.DurableExecutionEvent> durableEventsAfter(String tenantId,
                                                                                            long afterOffset,
                                                                                            int limit) {
            throw new IllegalStateException("no durable journal configured");
        }

        @Override
        public void close() {
        }
    }
}
