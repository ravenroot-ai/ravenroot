package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /v1/executions/inventory} and {@code GET /v1/executions/{id}/traversals} (issue 154):
 * the durable, authoritative process inventory API, CLI, UI, audit and recovery callers are meant to
 * share (acceptance criterion 7), distinct from {@code GET /v1/executions/live}'s process-local
 * runtime view -- see {@code LiveExecutionsHttpTest}, which this class deliberately parallels rather
 * than duplicates: that class proves the live-listing tenant boundary and the "a stalled traversal
 * still appears" property; this class proves the durable inventory answers the same tenant-boundary
 * question over a genuinely different, restart-surviving source, and proves the two id spaces
 * ({@code executionId}/{@code traversalId} versus {@code processInstanceId}) do not get confused at
 * the wire.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ProcessInventoryHttpTest {

    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="process-inventory-http-test" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    /**
     * The positive case, and the wire shape: a completed transient submission is listed by
     * {@code GET /v1/executions/inventory} with an absent {@code deploymentId} (this submission
     * opened no deployment), and {@code GET /v1/executions/{processInstanceId}/traversals} lists its
     * one traversal -- addressed by the process instance id the submission minted, not by
     * {@code executionId}/{@code traversalId}, which is a different id on this fixture's own
     * response.
     */
    @Test
    void completedTransientSubmissionIsListedInTheDurableInventoryWithItsTraversal() throws Exception {
        try (var engine = new PekkoExecutionEngine("process-inventory-http-test");
             var store = new InMemoryExecutionStore()) {
            var application = applicationWith(engine, store);
            try (var server = testServer(application, new HeaderTenantAuthenticator())) {
                server.start();

                var submitResponse = postAs(server, "/v1/executions?mode=run", GRAPH, "tenant-a");
                assertEquals(202, submitResponse.statusCode(), submitResponse.body());
                String processInstanceId = extract(submitResponse.body(), "processInstanceId");
                String executionId = extract(submitResponse.body(), "executionId");

                String inventory = pollUntilNonEmpty(server, "tenant-a");
                assertTrue(inventory.contains("\"processInstanceId\":\"" + processInstanceId + "\""),
                        () -> "the completed instance did not appear in the durable inventory: " + inventory);
                assertTrue(inventory.contains("\"status\":\"COMPLETED\""), inventory);
                assertTrue(inventory.contains("\"deploymentId\":null"),
                        () -> "a transient submission opens no deployment domain: " + inventory);
                assertTrue(inventory.contains("\"retainedFrom\""), inventory);

                String traversals = body(getAs(server,
                        "/v1/executions/" + processInstanceId + "/traversals", "tenant-a"));
                assertTrue(traversals.contains("\"traversalId\":\"" + executionId + "\""),
                        () -> "processInstanceId != executionId on this fixture, and the traversal "
                                + "listing must still name the traversal by its own id: " + traversals);
                assertTrue(traversals.contains("\"status\":\"COMPLETED\""), traversals);
            }
        }
    }

    /**
     * The tenant boundary, over the durable source rather than runtime bookkeeping. Mutation proof:
     * replace {@code delegate.processInventory(context.tenantId(), query)} in
     * {@code AuthorizedRavenrootApplication#processInventory} with an unscoped read and
     * {@code otherInventory}'s assertion reds. Also proves authorization-before-existence-disclosure
     * for the single-instance route: tenant-b's read of tenant-a's own {@code processInstanceId} and
     * tenant-b's read of an id that never existed both answer the identical 404 body, so a caller
     * cannot enumerate another tenant's instances through the difference.
     */
    @Test
    void anotherTenantsInstanceIsNeverVisibleInTheInventoryOrItsTraversals() throws Exception {
        try (var engine = new PekkoExecutionEngine("process-inventory-http-tenant-test");
             var store = new InMemoryExecutionStore()) {
            var application = applicationWith(engine, store);
            try (var server = testServer(application, new HeaderTenantAuthenticator())) {
                server.start();

                var submitResponse = postAs(server, "/v1/executions?mode=run", GRAPH, "tenant-a");
                assertEquals(202, submitResponse.statusCode(), submitResponse.body());
                String processInstanceId = extract(submitResponse.body(), "processInstanceId");

                String ownInventory = pollUntilNonEmpty(server, "tenant-a");
                assertTrue(ownInventory.contains(processInstanceId),
                        () -> "tenant-a's own inventory did not contain its own instance -- the fixture "
                                + "is broken, so the negative assertion below would prove nothing: "
                                + ownInventory);

                String otherInventory = body(getAs(server, "/v1/executions/inventory", "tenant-b"));
                assertTrue(otherInventory.startsWith("{\"items\":[]"),
                        () -> "tenant-b's inventory revealed tenant-a's instance -- the boundary this "
                                + "route exists to preserve is broken: " + otherInventory);

                var crossTenantRead = getAs(server,
                        "/v1/executions/" + processInstanceId + "/traversals", "tenant-b");
                var neverExistedRead = getAs(server,
                        "/v1/executions/" + UUID.randomUUID() + "/traversals", "tenant-b");
                assertEquals(404, crossTenantRead.statusCode(), crossTenantRead.body());
                assertEquals(404, neverExistedRead.statusCode(), neverExistedRead.body());
                // Compared by wire error code, not by the raw body: ErrorEnvelope attaches a fresh,
                // per-request correlationId to every response, by design, so the bodies legitimately
                // differ there. What must be identical -- and is the whole of what "indistinguishable"
                // means here -- is the status and the closed-vocabulary code the server actually
                // chose, which is what a caller could act on to tell the two cases apart if they
                // differed.
                assertEquals(errorCode(neverExistedRead), errorCode(crossTenantRead),
                        () -> "a cross-tenant id and one that never existed must be indistinguishable: "
                                + crossTenantRead.body() + " vs " + neverExistedRead.body());

                var ownRead = getAs(server, "/v1/executions/" + processInstanceId + "/traversals", "tenant-a");
                assertEquals(200, ownRead.statusCode(), ownRead.body());
            }
        }
    }

    /**
     * A deployment with no inventory-capable store composed at all answers 501, a fact about the
     * deployment rather than the request -- distinct from {@code GET /v1/executions/live}, which has
     * no such degraded mode because it reads runtime bookkeeping that always exists.
     */
    @Test
    void withNoDurableStoreComposedTheInventoryRouteAnswers501() throws Exception {
        try (var engine = new PekkoExecutionEngine("process-inventory-http-no-store-test")) {
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor());
            try (var server = testServer(application, new HeaderTenantAuthenticator())) {
                server.start();

                var response = getAs(server, "/v1/executions/inventory", "tenant-a");
                assertEquals(501, response.statusCode(), response.body());
                assertTrue(response.body().contains("PROCESS_INVENTORY_UNAVAILABLE"), response.body());
            }
        }
    }

    private static DefaultRavenrootApplication applicationWith(PekkoExecutionEngine engine,
                                                                InMemoryExecutionStore store) {
        return new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                ExecutionIdentitySource.randomUuids(), store);
    }

    private static RavenrootServer testServer(DefaultRavenrootApplication application,
                                               RequestAuthenticator authenticator) {
        return new RavenrootServer(application,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, authenticator);
    }

    /** The completion is asynchronous relative to the 202 response, so this polls the durable
     * inventory itself -- the same source under test -- until the instance reaches COMPLETED.
     * Deliberately spaced out (well below the test rate limiter's own budget): a tight poll loop
     * hitting a genuinely separate concern (per-tenant request-rate limiting) would fail this test
     * for a reason that has nothing to do with the durable inventory under test. */
    private static String pollUntilNonEmpty(RavenrootServer server, String tenant) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        String listing;
        do {
            // includeTerminal=true: the default page excludes COMPLETED/FAILED rows (the "what is
            // outstanding" default, and correctly so for the ordinary listing), which would make a
            // completed instance disappear from an unfiltered poll the instant it finishes.
            listing = body(getAs(server, "/v1/executions/inventory?includeTerminal=true", tenant));
            if (!listing.contains("\"status\":\"COMPLETED\"")) {
                Thread.sleep(100);
                continue;
            }
            return listing;
        } while (System.nanoTime() < deadline);
        return listing;
    }

    private static String extract(String json, String field) {
        var matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
        assertTrue(matcher.find(), () -> "no " + field + " in " + json);
        return matcher.group(1);
    }

    private static HttpResponse<String> getAs(RavenrootServer server, String path, String tenant)
            throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                        .header("X-Test-Tenant", tenant).GET().build(),
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

    private static String errorCode(HttpResponse<String> response) {
        var matcher = Pattern.compile("\"code\":\"([^\"]+)\"").matcher(response.body());
        assertTrue(matcher.find(), () -> "no error code in " + response.body());
        return matcher.group(1);
    }

    /**
     * Selects a tenant from an {@code X-Test-Tenant} header rather than parsing a bearer token --
     * see {@code LiveExecutionsHttpTest.HeaderTenantAuthenticator}, which this mirrors exactly, for
     * why that is the right substitute when tenant identity, not authentication, is under test.
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
