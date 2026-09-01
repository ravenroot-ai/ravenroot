package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
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

import java.io.PrintStream;
import java.io.ByteArrayInputStream;
import java.net.ConnectException;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * PLAT-02 shutdown verification: drive a real shutdown and observe the sequence from outside, not
 * assert it from inside a comment.
 *
 * <p>What "in-flight work must not be lost" means on this server, stated precisely because the
 * architecture makes the naive reading wrong: {@code POST /v1/executions} is fire-and-forget --
 * confirmed by reading {@code startExecution}, which returns as soon as the graph is accepted, not
 * when it finishes. So the thing that must survive a shutdown started while a graph is running is
 * not an open HTTP connection; it is the <strong>node processing itself</strong>, which {@link
 * ai.ravenroot.api.execution.ExecutionEngine#drain()} is specifically documented not to cut off
 * ("completes when every node has terminated"). This test proves exactly that: a slow node started
 * before shutdown begins is allowed to run to completion, observed through a side effect the node's
 * own handler sets, not inferred from the engine's contract text.</p>
 */
class DrainSequencingTest {

    @Test
    void drainingIsObservableWhileStillServingAndInFlightNodeWorkSurvivesTheSequence() throws Exception {
        var slowNodeReached = new CountDownLatch(1);
        var slowNodeCompleted = new CountDownLatch(1);
        var behaviors = BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults())
                .register("slow-behavior", message -> {
                    slowNodeReached.countDown();
                    try {
                        Thread.sleep(700);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    slowNodeCompleted.countDown();
                    return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                });

        try (var engine = new PekkoExecutionEngine("drain-sequencing-test")) {
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), behaviors);
            var quiet = new PrintStream(java.io.OutputStream.nullOutputStream());
            var limiter = new RateLimiter(RateLimitConfiguration.fromEnvironment(Map.of()),
                    TrustedProxyConfiguration.direct(), event -> { }, System::nanoTime);
            var gate = new ReadinessGate(() -> application.status().state(), StoreLivenessCheck.none(), List::of,
                    ReadinessConfiguration.defaults());
            // Short grace period so the test is fast; still long enough for at least one /ready
            // poll to land inside it, which is the property under test, not the wall-clock value.
            var drainGracePeriod = Duration.ofMillis(300);

            RavenrootServer server;
            try (limiter; gate) {
                server = new RavenrootServer(application,
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, false,
                        new DisabledLoopbackAuthenticator(), httpSecurity(), Clock.systemUTC(),
                        new DefaultAuthorizationService(new StructuredAuthorizationLogger(quiet)), limiter,
                        new StructuredArtifactLifecycleLogger(quiet), gate, Duration.ofSeconds(5));
                server.start();
                var client = HttpClient.newHttpClient();
                int port = server.port();

                // Start genuine operational work through the application boundary. The public inline
                // HTTP route deliberately selects TEST_PASSTHROUGH and therefore cannot be used as a
                // fixture for production work surviving engine drain.
                application.startGraphMl(new SecurityContext("drain-request", "drain-tenant", "fixture",
                                PrincipalType.WORKLOAD, "urn:ravenroot:test"), UUID.randomUUID(),
                        new ByteArrayInputStream(SLOW_GRAPH.getBytes(StandardCharsets.UTF_8)), "payload",
                        ExecutionPolicy.STANDARD);
                assertTrue(slowNodeReached.await(5, TimeUnit.SECONDS), "the slow node never started");

                // The real sequence under test, on its own thread, exactly as the shutdown hook
                // drives it.
                var shutdownThread = new Thread(() -> GracefulShutdown.run(engine, server, drainGracePeriod));
                shutdownThread.start();

                // While the grace period is running, the listener must still answer -- and must
                // already report unready, because engine.drain() ran before the sleep, not after.
                boolean observedDrainingWhileStillServing = false;
                long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while (System.nanoTime() < deadline) {
                    HttpResponse<String> readyResponse;
                    try {
                        readyResponse = client.send(HttpRequest.newBuilder(
                                        URI.create("http://127.0.0.1:" + port + "/ready")).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                    } catch (ConnectException stillStarting) {
                        continue;
                    }
                    if (readyResponse.statusCode() == 503 && readyResponse.body().contains("DRAINING")) {
                        observedDrainingWhileStillServing = true;
                        break;
                    }
                }
                assertTrue(observedDrainingWhileStillServing,
                        "never observed /ready reporting 503 DRAINING while the server was still reachable -- "
                                + "either the ordering regressed or the grace period is too short for this poll loop");

                shutdownThread.join(TimeUnit.SECONDS.toMillis(15));
                assertTrue(slowNodeCompleted.await(5, TimeUnit.SECONDS),
                        "the slow node was cut off by shutdown instead of being allowed to finish -- "
                                + "in-flight work was lost");

                // Final state: the listener should be gone now. This asserts against the same
                // released-ephemeral-port premise avoided in JdkHeaderCapConnectFailureClassificationTest,
                // and with the identical failure mode: `port` is the ephemeral port RavenrootServer itself
                // picked and just released by stopping, and nothing stops another process on the same host
                // from being handed it before this check runs. A bare assertThrows(ConnectException) would
                // fail if that ever happened -- accusing the drain sequence of a regression it did not
                // commit, against a completely unrelated occupant of the recycled port. Unlike the
                // probeHeaderCap case, though, this one does not need an independent premise check for the
                // regression it actually catches: a response, if one comes back over a completed HTTP round
                // trip, is directly distinguishable by content. Only a response that is recognizably
                // Ravenroot's own /health ({@code 200} with body {@code {"status":"UP"}}, see
                // RavenrootServer's route registration) proves the listener is still up -- that is the
                // actual drain regression this assertion catches. A ConnectException (nobody home) is the
                // other side it distinguishes cleanly: the listener is confirmed gone. (The {@code catch}
                // below is narrow on purpose, but narrow cuts both ways. A reset *during* the handshake --
                // no SYN-ACK, just a reset -- is what "nobody is listening" looks like at the TCP level, and
                // is exactly the {@code ConnectException} case above: this test's own normal green path when
                // the port really is free (confirmed directly: connecting to a closed loopback port throws
                // exactly that, immediately). What the catch does not cover is an occupant that has already
                // completed the TCP handshake -- {@code connect()} itself cannot fail after that point, by
                // the JDK's own contract for what {@code ConnectException} means -- and only then resets the
                // connection or closes it without a well-formed HTTP response: that surfaces instead as an
                // {@code IOException} this catch does not absorb, and would fail this test with an unrelated
                // exception instead of being handled the way a mid-handshake refusal or a well-formed HTTP
                // reply both are. (Not a paper hazard: {@code IOException: HTTP/1.1 header parser received no
                // bytes} is genuine JDK-internal wording, confirmed present in this JDK's own module image,
                // and it was measured firing for a related single-fault mutation -- skipping
                // only {@code server.stop(...)} inside {@link RavenrootServer#close()} while the rest of that
                // method still ran.) The same false accusation a bare assertThrows(ConnectException) risks,
                // for a narrower and rarer occupant behaviour: one that accepts the connection, not one that
                // refuses it.)
                //
                // What this does NOT distinguish, and is not a false claim to paper over: a Ravenroot
                // listener that survived the drain regression but answers /health with anything other than
                // {@code 200}/{@code UP} is indistinguishable, by this assertion, from some unrelated
                // process answering on the recycled port -- both fall through to "not a proven regression"
                // below. This is not closable by making /health itself drain-aware: /health is deliberately
                // NOT coupled to readiness/drain state (see the route registration comment above -- a
                // liveness probe that fails on a readiness condition restarts the pod instead of letting the
                // load balancer drain it, the exact outage this design exists to prevent). What is actually
                // true of this server's real, unmutated production code is narrower than "unconditional":
                // the /health *handler* itself is unconditionally {@code 200 UP} -- but {@code publicContext},
                // which wraps every route including this one, can still answer before ever reaching that
                // handler, from three checks that run first: a rejected forwarded-address chain, an
                // exhausted per-address rate budget, or a malformed request shape (see publicContext's own
                // early returns). {@code RateLimitHttpIntegrationTest#theUnauthenticatedHealthEndpointIsRateLimited}
                // proves the address-budget one fires for real on unmutated /health: 429 on the third
                // request from one address, live code, no mutation. None of those three checks, though,
                // read drain or readiness state -- they run off {@code rateLimiter} and the request's own
                // headers, never off {@code application.status()} or the readiness gate -- which is what
                // makes the second fault needed to reach the gap above unrelated to draining by
                // construction, not merely by the luck of no test having hit it yet. That second fault
                // could be this exact request coincidentally hitting the address rate limiter -- answered
                // in Ravenroot's own error envelope, not {@code {"status":"UP"}}, so even that case is not
                // truly silent, just not what this assertion checks. Every single-fault drain regression
                // this test can provoke is still caught here; the gap is an accepted limit of a
                // content-based check, confirmed by reproducing it directly (skip closing the listener and
                // make /health degrade to {@code 503 {"status":"DOWN"}}: the assertion below still passes),
                // not a claim this assertion no longer makes.
                HttpResponse<String> responseAfterShutdown;
                try {
                    responseAfterShutdown = client.send(HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + port + "/health")).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                } catch (ConnectException listenerActuallyGone) {
                    responseAfterShutdown = null;
                }
                if (responseAfterShutdown != null) {
                    boolean stillRavenrootsOwnListener = responseAfterShutdown.statusCode() == 200
                            && "{\"status\":\"UP\"}".equals(responseAfterShutdown.body());
                    assertFalse(stillRavenrootsOwnListener,
                            "the /health endpoint answered as Ravenroot's own liveness probe (200, "
                                    + "{\"status\":\"UP\"}) after shutdown completed -- the listener is still "
                                    + "up, which is an actual drain regression. (A different response here "
                                    + "is not proof of the opposite: it could be an unrelated process on the "
                                    + "recycled ephemeral port, but it could also be this server's own "
                                    + "listener having survived in a degraded state -- see this assertion's "
                                    + "own comment above for why that gap is accepted rather than closed.)");
                }
            }
        }
    }

    private static final String SLOW_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="drain-sequencing-test" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="slow"><data key="kind">BEHAVIOR</data><data key="behavior">slow-behavior</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="slow"><data key="outcome">continue</data></edge>
                <edge id="e2" source="slow" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    private static HttpSecurityConfiguration httpSecurity() {
        return new HttpSecurityConfiguration(new BrowserOriginPolicy(Set.of("http://127.0.0.1:1")),
                new SecurityHeadersPolicy(false), Duration.ofSeconds(30));
    }
}
