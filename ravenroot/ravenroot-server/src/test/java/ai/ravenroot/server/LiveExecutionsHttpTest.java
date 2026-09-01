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
 * {@code GET /v1/executions/live} exists to close a specific gap -- {@code POST
 * /v1/executions/{id}/cancel} works, but until this route existed nothing returned the identifier
 * it needs once the {@code 202} response that minted it is gone. This class proves the one property
 * that makes the route worth having: <b>a stalled execution -- one whose
 * behavior has not yet returned and has therefore published nothing beyond {@code NODE_STARTED} --
 * must still appear.</b> A listing built by projecting the event stream would reproduce the defect
 * exactly, showing every completed execution and silently omitting the one still running. This
 * class proves the positive instead: while the fixture is genuinely still inside its node (observed
 * through a latch the node itself counts down, not inferred from timing), the live listing already
 * names it, sourced from {@link ai.ravenroot.core.runtime.DefaultRavenrootApplication}'s own
 * active-execution bookkeeping rather than from anything published.
 *
 * <p>The second test proves the tenant boundary: the listing must not become
 * a way to learn that another tenant has work running, not by identifier and not by a non-empty
 * response of any other shape.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class LiveExecutionsHttpTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    /**
     * {@code hang} sleeps for a bounded but generous window after signalling {@code reached}, so the
     * traversal is genuinely still running -- inside the node, having published {@code NODE_STARTED}
     * and nothing since -- for as long as this test needs it to be, while still guaranteed to finish
     * on its own well inside {@code GraphRunner}'s stop-then-cancel shutdown bound (10s + 10s) so
     * {@code engine.close()} in this test's own teardown never has to wait for an unresponsive node.
     * Deliberately not an indefinite block released only by interruption: {@code CompletableFuture}
     * does not interrupt its running thread on {@code cancel()} (its own documented contract), so a
     * behavior that depends on interruption to unblock would never actually be released by this
     * engine's cooperative cancellation and would make every test using it hang at teardown instead
     * of failing on the assertion that matters.
     */
    private static BehaviorRegistry hangingBehaviors(CountDownLatch reached) {
        return BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults())
                .register("hang", message -> {
                    reached.countDown();
                    try {
                        Thread.sleep(3_000);
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
              <graph id="live-executions-test" edgedefault="directed">
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
     * Mutation proof, both directions: comment out the {@code activeExecutions}-backed
     * {@code liveExecutions} override (fall back to the interface default returning
     * {@code List.of()}) and this test reds on the first {@code listing.contains(...)} assertion --
     * a listing that reports nothing live is the exact defect this test detects. Restore it, then
     * instead make {@code cancelTraversal} a no-op and the final polling loop times out with the
     * execution still listed, proving cancel and the listing observe the same underlying state.
     */
    @Test
    void stalledExecutionAppearsInTheLiveListingAndDisappearsOnceCancelled() throws Exception {
        var hangReached = new CountDownLatch(1);

        try (var engine = new PekkoExecutionEngine("live-executions-stall-test")) {
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                    hangingBehaviors(hangReached));
            try (var server = testServer(application, new DisabledLoopbackAuthenticator())) {
                server.start();

                var submitResponse = post(server, "/v1/executions?mode=run", HANG_GRAPH);
                assertEquals(202, submitResponse.statusCode(), submitResponse.body());
                String executionId = extract(submitResponse.body(), "executionId");
                assertTrue(hangReached.await(5, TimeUnit.SECONDS), "the hang node never started");

                // The property the route exists for: the traversal is genuinely still inside its
                // node right now (hangReached fired, and it sleeps for seconds yet), so it has
                // published NODE_STARTED and nothing since. This listing must still name it.
                String listing = getBody(server, "/v1/executions/live");
                assertTrue(listing.contains("\"traversalId\":\"" + executionId + "\""),
                        () -> "stalled execution " + executionId + " did not appear in the live "
                                + "listing, even though it is genuinely still running: " + listing);

                var cancelResponse = post(server, "/v1/executions/" + executionId + "/cancel", "");
                assertEquals(200, cancelResponse.statusCode(), cancelResponse.body());
                assertTrue(cancelResponse.body().contains("\"outcome\":\"CANCELLED\""),
                        cancelResponse.body());

                String afterCancel = pollUntilAbsent(server, executionId);
                assertFalse(afterCancel.contains(executionId),
                        () -> "cancelled execution " + executionId + " is still reported live: "
                                + afterCancel);
            }
        }
    }

    /**
     * Mutation proof: replace {@code delegate.liveExecutions(context.tenantId())} in
     * {@code AuthorizedRavenrootApplication#liveExecutions} with {@code delegate.liveExecutions(""
     * )} or with an unfiltered read of every tenant's executions, and
     * {@code otherListing}'s assertion reds -- tenant-b's response stops being the empty array the
     * boundary requires. The own-tenant assertion just above it is the positive control: if that one
     * ever failed instead, the empty result below would be meaningless (the fixture itself broken,
     * not the boundary).
     */
    @Test
    void anotherTenantsLiveExecutionIsNeverVisibleInTheListingOrCancellable() throws Exception {
        var hangReached = new CountDownLatch(1);

        try (var engine = new PekkoExecutionEngine("live-executions-tenant-test")) {
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                    hangingBehaviors(hangReached));
            try (var server = testServer(application, new HeaderTenantAuthenticator())) {
                server.start();

                var submitResponse = postAs(server, "/v1/executions?mode=run", HANG_GRAPH, "tenant-a");
                assertEquals(202, submitResponse.statusCode(), submitResponse.body());
                String executionId = extract(submitResponse.body(), "executionId");
                assertTrue(hangReached.await(5, TimeUnit.SECONDS), "the hang node never started");

                String ownListing = body(getAs(server, "/v1/executions/live", "tenant-a"));
                assertTrue(ownListing.contains(executionId),
                        () -> "tenant-a's own listing did not contain its own live execution -- the "
                                + "fixture is broken, so the negative assertion below would prove "
                                + "nothing: " + ownListing);

                String otherListing = body(getAs(server, "/v1/executions/live", "tenant-b"));
                assertEquals("{\"executions\":[]}", otherListing,
                        () -> "tenant-b's listing revealed tenant-a's live execution -- the boundary "
                                + "this route exists to preserve is broken: " + otherListing);

                var crossTenantCancel = postAs(server, "/v1/executions/" + executionId + "/cancel", "",
                        "tenant-b");
                assertEquals(403, crossTenantCancel.statusCode(), crossTenantCancel.body());

                var ownCancel = postAs(server, "/v1/executions/" + executionId + "/cancel", "", "tenant-a");
                assertEquals(200, ownCancel.statusCode(), ownCancel.body());
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

    private static HttpResponse<String> getAs(RavenrootServer server, String path, String tenant)
            throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                        .header("X-Test-Tenant", tenant).GET().build(),
                HttpResponse.BodyHandlers.ofString());
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

    private static String body(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    /**
     * Selects a tenant from an {@code X-Test-Tenant} header rather than parsing a bearer token, since
     * this test's only interest is that two different tenant ids produce two differently scoped
     * views -- granted every available scope and {@link Role#OPERATOR}, mirroring
     * {@link DisabledLoopbackAuthenticator}'s own breadth, so the boundary under test is tenant
     * identity and nothing else.
     */
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
