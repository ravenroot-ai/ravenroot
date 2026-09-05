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
 * A held execution is distinguishable from a running one by somebody who did not hold it.
 *
 * <h2>Why the second reader is the whole point</h2>
 * <p>{@code PauseResumeHttpTest} already covers the command: the caller that issues a pause is told
 * {@code PAUSED}, {@code ALREADY_PAUSED} or {@code NOT_ACTIVE}, and that is a complete account of
 * what <em>that</em> caller learns. It is also the entire account anyone learned, which is the defect
 * this file exists for. Every other operator, every reconnecting client and every session that did
 * not issue the pause saw an execution that was listed as running and had stopped publishing events
 * — the same two facts a deadlocked traversal presents, and the two situations call for opposite
 * responses.</p>
 *
 * <p>So the assertions below are deliberately not made by the caller that paused. A second principal
 * in the same tenant, holding no more authority than the read it performs, reads both surfaces and
 * must be able to tell the difference. Asserting through the pausing caller's own return value would
 * re-test what already worked and would prove nothing about observability.</p>
 *
 * <h2>Two principals, one tenant</h2>
 * <p>The authenticator reads {@code tenant:subject} from the bearer token, so {@code alice} and
 * {@code bob} are genuinely different principals with genuinely different request ids while sharing
 * a tenant. The tenant has to be shared: cross-tenant invisibility is already pinned by
 * {@code LiveExecutionsHttpTest}, and a reader who could not see the execution at all would make
 * this test pass for the wrong reason.</p>
 *
 * <h2>No polling around the transitions themselves</h2>
 * <p>Both control routes decide before they answer — a pause has written its hold, and a resume has
 * removed it, by the time the 200 is on the wire — so a read issued after the response is ordered
 * after the transition rather than racing it. The only bounded wait is for the traversal's own
 * completion, which is genuinely asynchronous, and it is a poll with a deadline that reddens rather
 * than a sleep.</p>
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
final class PausedExecutionReadSurfacesHttpTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(20);

    /**
     * A node that parks until this test releases it, so the traversal is reliably mid-flight when
     * the pause arrives. Bounded for the reason {@code PauseResumeHttpTest} states: a
     * {@link CompletableFuture} does not interrupt the thread running its computation, so an
     * unbounded wait would turn a failed assertion into a hung build.
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
              <graph id="paused-read-surfaces" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="hang"><data key="kind">BEHAVIOR</data><data key="behavior">hang</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="hang"><data key="outcome">continue</data></edge>
                <edge id="e2" source="hang" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    /**
     * A second authorized reader tells a held execution from a running one, in the live listing and
     * in the per-execution read, and sees the hold go when it is released.
     *
     * <p>Both surfaces are asserted at three moments — running, held, released — rather than only in
     * the held state. A field that read {@code true} whenever it was present would pass a
     * held-state-only test, and so would one that never went back to {@code false}; neither would be
     * usable by an operator watching a run.</p>
     */
    @Test
    void aSecondAuthorizedReaderTellsAHeldExecutionFromARunningOneInBothReadSurfaces() throws Exception {
        var hangReached = new CountDownLatch(1);
        var releaseHang = new CountDownLatch(1);
        try (var engine = new PekkoExecutionEngine("paused-read-surfaces-test")) {
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                    hangingBehaviors(hangReached, releaseHang));
            try (var server = testServer(application)) {
                server.start();
                try {
                    var submitted = postAs(server, "/v1/executions?mode=run", HANG_GRAPH, "alice");
                    assertEquals(202, submitted.statusCode(), submitted.body());
                    String executionId = extract(submitted.body(), "executionId");
                    assertTrue(hangReached.await(TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                            "the traversal must be inside its node before the pause is issued");

                    // Running, read by the second principal. The control: without it a listing that
                    // always said paused would satisfy the held assertions below.
                    assertTrue(rowFor(getAs(server, "/v1/executions/live", "bob"), executionId)
                                    .contains("\"paused\":false"),
                            "a running execution must be reported as not held to a second reader");
                    assertTrue(getAs(server, "/v1/executions/" + executionId, "bob")
                                    .contains("\"paused\":false"),
                            "and the per-execution read must agree with it");

                    var paused = postAs(server, "/v1/executions/" + executionId + "/pause", "", "alice");
                    assertEquals(200, paused.statusCode(), paused.body());
                    assertTrue(paused.body().contains("\"outcome\":\"PAUSED\""), paused.body());

                    String heldListing = getAs(server, "/v1/executions/live", "bob");
                    assertTrue(rowFor(heldListing, executionId).contains("\"paused\":true"),
                            "a second reader must see the hold in the live listing: " + heldListing);
                    String heldRead = getAs(server, "/v1/executions/" + executionId, "bob");
                    assertTrue(heldRead.contains("\"paused\":true"),
                            "and in the per-execution read: " + heldRead);
                    assertTrue(heldRead.contains("\"status\":\"RUNNING\""),
                            "the durable lifecycle status is unchanged by a hold, which is what keeps "
                                    + "an existing consumer of status working: " + heldRead);

                    var resumed = postAs(server, "/v1/executions/" + executionId + "/resume", "", "alice");
                    assertEquals(200, resumed.statusCode(), resumed.body());
                    assertTrue(resumed.body().contains("\"outcome\":\"RESUMED\""), resumed.body());

                    assertTrue(rowFor(getAs(server, "/v1/executions/live", "bob"), executionId)
                                    .contains("\"paused\":false"),
                            "the hold must be gone from the listing once it is released");
                    assertTrue(getAs(server, "/v1/executions/" + executionId, "bob")
                                    .contains("\"paused\":false"),
                            "and from the per-execution read");

                    releaseHang.countDown();
                    String settledListing = pollUntilAbsent(server, executionId);
                    assertFalse(settledListing.contains(executionId),
                            "a completed execution leaves the live listing: " + settledListing);
                    String terminal = getAs(server, "/v1/executions/" + executionId, "bob");
                    assertTrue(terminal.contains("\"status\":\"COMPLETED\""), terminal);
                    assertTrue(terminal.contains("\"paused\":false"),
                            "a terminal execution is never reported as held: " + terminal);
                } finally {
                    // Released here too, so a failed assertion above reddens instead of waiting out
                    // the behaviour's own bound on the teardown path.
                    releaseHang.countDown();
                }
            }
        }
    }

    /**
     * An execution nobody pauses carries the field and carries it {@code false}, from start to
     * terminal.
     *
     * <p>The acceptance criterion this stands for is the one it is easiest to lose: a clean run's
     * representation must be unchanged apart from an added field that says nothing happened. Asserted
     * on the same two surfaces so that "unchanged" means unchanged in both, and the terminal read is
     * checked for the fields it already carried rather than only for the new one.</p>
     */
    @Test
    void aCleanExecutionReportsNoHoldOnEitherSurfaceAndKeepsItsExistingRepresentation() throws Exception {
        var hangReached = new CountDownLatch(1);
        var releaseHang = new CountDownLatch(1);
        try (var engine = new PekkoExecutionEngine("clean-read-surfaces-test")) {
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                    hangingBehaviors(hangReached, releaseHang));
            try (var server = testServer(application)) {
                server.start();
                try {
                    var submitted = postAs(server, "/v1/executions?mode=run", HANG_GRAPH, "alice");
                    assertEquals(202, submitted.statusCode(), submitted.body());
                    String executionId = extract(submitted.body(), "executionId");
                    assertTrue(hangReached.await(TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                            "the traversal must have reached its node");

                    String running = getAs(server, "/v1/executions/" + executionId, "bob");
                    assertTrue(running.contains("\"status\":\"RUNNING\""), running);
                    assertTrue(running.contains("\"paused\":false"),
                            "the field is present on a run nobody paused, so a client can tell "
                                    + "\"not held\" from \"this server does not report holds\": " + running);

                    releaseHang.countDown();
                    pollUntilAbsent(server, executionId);

                    String terminal = getAs(server, "/v1/executions/" + executionId, "bob");
                    assertTrue(terminal.contains("\"status\":\"COMPLETED\""), terminal);
                    assertTrue(terminal.contains("\"paused\":false"), terminal);
                    assertTrue(terminal.contains("\"degraded\":false"),
                            "the existing terminal representation is unchanged: " + terminal);
                    assertTrue(terminal.contains("\"handledFailure\":false"), terminal);
                    assertTrue(terminal.contains("\"visitedNodes\":"), terminal);
                    assertTrue(terminal.contains("\"untakenEdges\":"), terminal);
                } finally {
                    releaseHang.countDown();
                }
            }
        }
    }

    /**
     * The one listing row for this execution, so an assertion about {@code paused} cannot be
     * satisfied by a different row that happens to carry the value.
     *
     * @param listing the whole {@code GET /v1/executions/live} body
     * @param executionId the execution whose row is wanted
     * @return that row's JSON object, braces excluded
     */
    private static String rowFor(String listing, String executionId) {
        var matcher = Pattern.compile("\\{[^{}]*" + Pattern.quote(executionId) + "[^{}]*\\}").matcher(listing);
        assertTrue(matcher.find(), () -> "no row for " + executionId + " in " + listing);
        return matcher.group();
    }

    private static RavenrootServer testServer(DefaultRavenrootApplication application) {
        return new RavenrootServer(application,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, new TokenAuthenticator());
    }

    private static String pollUntilAbsent(RavenrootServer server, String executionId) throws Exception {
        long deadline = System.nanoTime() + TEST_TIMEOUT.toNanos();
        String listing;
        do {
            listing = getAs(server, "/v1/executions/live", "bob");
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

    private static String getAs(RavenrootServer server, String path, String subject) throws Exception {
        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                        .header("Authorization", "Bearer tenant-a:" + subject).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private static HttpResponse<String> postAs(RavenrootServer server, String path, String requestBody,
                                               String subject) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                        .header("Authorization", "Bearer tenant-a:" + subject)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * {@code Bearer <tenant>:<subject>}, so this file can have two principals inside one tenant.
     *
     * <p>The neighbouring pause and live-listing tests use a tenant-only header, under which the
     * subject <em>is</em> the tenant id — which cannot express the second reader this file is about.
     * The token split is the shape {@code ActiveExecutionAdmissionHttpTest} already uses for the same
     * need. Every available scope is granted to both principals on purpose: the subject of this file
     * is observability, not authorization, and a reader that failed for want of a scope would say
     * nothing about whether a hold is visible.</p>
     */
    private static final class TokenAuthenticator implements RequestAuthenticator {
        @Override
        public AuthenticatedPrincipal authenticate(Headers headers) throws AuthenticationException {
            String value = headers.getFirst("Authorization");
            if (value == null || value.isBlank()) {
                throw new AuthenticationException("missing Authorization header");
            }
            String[] parts = value.replaceFirst("^Bearer ", "").split(":", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new AuthenticationException("expected Bearer <tenant>:<subject>");
            }
            return new AuthenticatedPrincipal(parts[1], AuthenticatedPrincipal.Type.USER,
                    "urn:ravenroot:test", parts[0], Set.of(Role.OPERATOR),
                    java.util.Arrays.stream(AuthorizationAction.values())
                            .filter(AuthorizationAction::available)
                            .map(AuthorizationAction::requiredScope)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        }
    }
}
