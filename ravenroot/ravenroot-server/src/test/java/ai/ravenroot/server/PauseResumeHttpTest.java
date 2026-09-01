package ai.ravenroot.server;

import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.AuthenticationException;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import ai.ravenroot.server.security.RequestAuthenticator;

import com.sun.net.httpserver.Headers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Over HTTP, {@code POST /v1/executions/{id}/pause} and {@code .../resume} are the surfaces that
 * make the recorded pause semantics reachable rather than merely implemented.
 *
 * <p>Paired with {@code LiveExecutionsHttpTest} by design: the identifier these routes need is the
 * one {@code GET /v1/executions/live} returns, so the sequence asserted here is the one an
 * operator actually performs — list, then act on what was listed, having recorded nothing in
 * advance.</p>
 *
 * <p>The behaviour-level clauses (the in-flight node finishes; resume is its own transition) are
 * asserted in {@code TraversalPauseResumeTest}, which can observe node events. What this class adds
 * is what only the adapter can be asked: the status codes, the outcome vocabulary, and the tenant
 * boundary.</p>
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class PauseResumeHttpTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(20);

    /**
     * {@code hang} signals that it has started and then waits for this test to release it. The pause
     * is issued while it is provably inside the node, so what the traversal holds at is the hop
     * <em>after</em> it — the only placement under which "the in-flight node finishes" is observable.
     *
     * <h2>Released by a latch, not by a sleep, and that is not a style preference</h2>
     * <p>Sleeping two seconds would make the window in which the pause has to land a wall-clock budget:
     * on a loaded machine the node finishes first, the execution completes, and
     * {@code PAUSED} becomes {@code NOT_ACTIVE} — a red that says nothing about pause and everything
     * about the runner it ran on. With a latch there is no window: the node is inside {@code await}
     * until this test decides otherwise, so every assertion below is about ordering rather than about
     * speed.</p>
     *
     * <p>The wait is still bounded, and deliberately: an indefinite block would be released by
     * nothing on the teardown path either, because a {@code CompletableFuture} does not interrupt the
     * thread running its computation (its own documented contract), so a behaviour that depends on
     * interruption to unblock would turn a failed assertion into a hung build. The bound is generous
     * enough that reaching it means the test already failed for a real reason.</p>
     */
    private static BehaviorRegistry hangingBehaviors(CountDownLatch reached, CountDownLatch release) {
        return BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults())
                .register("hang", message -> {
                    reached.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
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
              <graph id="pause-resume-http" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="hang"><data key="kind">BEHAVIOR</data><data key="behavior">hang</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="hang"><data key="outcome">continue</data></edge>
                <edge id="e2" source="hang" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    @Test
    void anExecutionIsPausedResumedAndCompletesThroughTheRoutes() throws Exception {
        var hangReached = new CountDownLatch(1);
        var releaseHang = new CountDownLatch(1);

        try (var engine = new PekkoExecutionEngine("pause-resume-http-test")) {
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                    hangingBehaviors(hangReached, releaseHang));
            try (var server = testServer(application, new DisabledLoopbackAuthenticator())) {
                server.start();

                var submitResponse = post(server, "/v1/executions?mode=run", HANG_GRAPH);
                assertEquals(202, submitResponse.statusCode(), submitResponse.body());
                String executionId = extract(submitResponse.body(), "executionId");
                assertTrue(hangReached.await(10, TimeUnit.SECONDS), "the hang node never started");

                // The pause lands while the node is provably still inside its behaviour -- it is
                // waiting on releaseHang, and nothing has counted that down. There is no window to
                // lose here, so PAUSED is about ordering rather than about how fast this machine is.
                var paused = post(server, "/v1/executions/" + executionId + "/pause", "");
                assertEquals(200, paused.statusCode(), paused.body());
                assertTrue(paused.body().contains("\"outcome\":\"PAUSED\""), paused.body());

                var pausedAgain = post(server, "/v1/executions/" + executionId + "/pause", "");
                assertEquals(200, pausedAgain.statusCode(), pausedAgain.body());
                assertTrue(pausedAgain.body().contains("\"outcome\":\"ALREADY_PAUSED\""), pausedAgain.body());

                // Now let the in-flight node finish. Only one node remains after it, so a pause that
                // did nothing would let this graph run to completion and empty the listing. It stays
                // listed because the hop after `hang` is being held -- which is the whole claim.
                releaseHang.countDown();
                Thread.sleep(3_000);
                String listing = getBody(server, "/v1/executions/live");
                assertTrue(listing.contains("\"traversalId\":\"" + executionId + "\""),
                        () -> "a paused execution must stay live and listed: " + listing);

                var resumed = post(server, "/v1/executions/" + executionId + "/resume", "");
                assertEquals(200, resumed.statusCode(), resumed.body());
                assertTrue(resumed.body().contains("\"outcome\":\"RESUMED\""), resumed.body());

                String afterResume = pollUntilAbsent(server, executionId);
                assertFalse(afterResume.contains(executionId),
                        () -> "a resumed execution must run to completion and leave the live listing: "
                                + afterResume);

                // Resuming what is no longer running is reported, not silently successful.
                var resumedAgain = post(server, "/v1/executions/" + executionId + "/resume", "");
                assertEquals(200, resumedAgain.statusCode(), resumedAgain.body());
                assertTrue(resumedAgain.body().contains("\"outcome\":\"NOT_ACTIVE\""), resumedAgain.body());
            } finally {
                // Idempotent, and here for the path where an assertion above threw before the release
                // in the middle of the test ran: a failing test must end red, not spend the node's own
                // bound in teardown.
                releaseHang.countDown();
            }
        }
    }

    /**
     * A malformed id is a request defect on these routes exactly as it is on cancel, and another
     * tenant's execution is not pausable — holding somebody else's work is a control action over it.
     */
    @Test
    void aMalformedIdIsRefusedAndAnotherTenantsExecutionIsNotPausable() throws Exception {
        var hangReached = new CountDownLatch(1);
        var releaseHang = new CountDownLatch(1);

        try (var engine = new PekkoExecutionEngine("pause-resume-http-boundary-test")) {
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                    hangingBehaviors(hangReached, releaseHang));
            try (var server = testServer(application, new HeaderTenantAuthenticator())) {
                server.start();

                var malformed = postAs(server, "/v1/executions/not-a-uuid/pause", "", "tenant-a");
                assertEquals(400, malformed.statusCode(), malformed.body());

                var submitResponse = postAs(server, "/v1/executions?mode=run", HANG_GRAPH, "tenant-a");
                assertEquals(202, submitResponse.statusCode(), submitResponse.body());
                String executionId = extract(submitResponse.body(), "executionId");
                assertTrue(hangReached.await(10, TimeUnit.SECONDS), "the hang node never started");

                var crossTenant = postAs(server, "/v1/executions/" + executionId + "/pause", "", "tenant-b");
                assertEquals(403, crossTenant.statusCode(), crossTenant.body());

                // Positive control: without this, the 403 above would also be satisfied by a route
                // that refuses everybody, which would prove nothing about the boundary.
                var ownPause = postAs(server, "/v1/executions/" + executionId + "/pause", "", "tenant-a");
                assertEquals(200, ownPause.statusCode(), ownPause.body());
                assertTrue(ownPause.body().contains("\"outcome\":\"PAUSED\""), ownPause.body());

                var ownCancel = postAs(server, "/v1/executions/" + executionId + "/cancel", "", "tenant-a");
                assertEquals(200, ownCancel.statusCode(), ownCancel.body());
            } finally {
                // Released whatever happened above, so a failed assertion ends as a red test rather
                // than as a teardown waiting out the behaviour's own bound.
                releaseHang.countDown();
            }
        }
    }

    private static RavenrootServer testServer(DefaultRavenrootApplication application,
                                              RequestAuthenticator authenticator) {
        return new RavenrootServer(application,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, authenticator);
    }

    private static String pollUntilAbsent(RavenrootServer server, String executionId) throws Exception {
        long deadline = System.nanoTime() + TEST_TIMEOUT.toNanos();
        String listing;
        do {
            listing = getBody(server, "/v1/executions/live");
            if (!listing.contains(executionId)) {
                return listing;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        return listing;
    }

    private static String extract(String json, String field) {
        var matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
        assertTrue(matcher.find(), () -> "no " + field + " in " + json);
        return matcher.group(1);
    }

    private static String getBody(RavenrootServer server, String path) throws Exception {
        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private static HttpResponse<String> post(RavenrootServer server, String path, String requestBody)
            throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postAs(RavenrootServer server, String path, String requestBody,
                                               String tenant) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                        .header("X-Test-Tenant", tenant)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** See {@code LiveExecutionsHttpTest}'s own copy: two tenant ids, every scope, nothing else. */
    private static final class HeaderTenantAuthenticator implements RequestAuthenticator {
        @Override
        public AuthenticatedPrincipal authenticate(Headers headers) throws AuthenticationException {
            String tenantId = headers.getFirst("X-Test-Tenant");
            if (tenantId == null || tenantId.isBlank()) {
                throw new AuthenticationException("missing X-Test-Tenant header");
            }
            return new AuthenticatedPrincipal(tenantId, AuthenticatedPrincipal.Type.USER,
                    "urn:ravenroot:test", tenantId, Set.of(Role.OPERATOR),
                    java.util.Arrays.stream(AuthorizationAction.values())
                            .filter(AuthorizationAction::available)
                            .map(AuthorizationAction::requiredScope)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        }
    }
}
