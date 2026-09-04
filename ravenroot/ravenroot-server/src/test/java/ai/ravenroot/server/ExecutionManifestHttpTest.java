package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionManifest;
import ai.ravenroot.api.persistence.PinnedNodePackage;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.persistence.InMemoryExecutionManifestStore;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.persistence.InMemoryGraphDefinitionStore;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.GraphExecutionLimits;
import ai.ravenroot.core.runtime.UnknownBehaviorPolicy;
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
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /v1/executions/{id}/manifest}: what an authenticated tenant may learn about the
 * dependency set its own execution was accepted against, and — the part these tests exist for — what
 * it may not learn about the deployment underneath it.
 *
 * <p>The disclosure assertions are the reason this class is not folded into a broader wire test.
 * Every value a compatibility difference carries describes the deployment: an installed node
 * package's identity, a digest of the operator's execution limits, which engine is composed. The
 * verdict a tenant needs is which dimension stopped matching. A projection that rendered the values
 * would answer a question the caller never asked and has no claim on, and it would do so to anyone
 * who had submitted one graph.</p>
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ExecutionManifestHttpTest {

    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="execution-manifest-http-test" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    /** A package id and version an operator would not want handed to a tenant. */
    private static final String PRIVATE_PACKAGE = "acme.internal.billing-nodes";
    private static final String PRIVATE_VERSION = "9.9.9-internal-preview";

    @Test
    void anAcceptedExecutionReportsItsOwnManifestIdentityAndACompatibleVerdict() throws Exception {
        try (var engine = new PekkoExecutionEngine("execution-manifest-http-ok");
             var store = new InMemoryExecutionStore();
             var manifests = new InMemoryExecutionManifestStore(Clock.systemUTC())) {
            var application = applicationWith(engine, store, manifests);
            try (var server = testServer(application, manifests)) {
                server.start();

                var submitted = postAs(server, "/v1/executions?mode=run", GRAPH, "tenant-a");
                assertEquals(202, submitted.statusCode(), submitted.body());
                String processInstanceId = extract(submitted.body(), "processInstanceId");
                String graphVersion = extract(submitted.body(), "graphVersion");

                var response = getAs(server, "/v1/executions/" + processInstanceId + "/manifest",
                        "tenant-a");
                assertEquals(200, response.statusCode(), response.body());
                String body = response.body();
                assertTrue(body.contains("\"compatible\":true"), body);
                assertTrue(body.contains("\"incompatibleDimensions\":[]"), body);
                assertTrue(body.contains("\"graphVersion\":\"" + graphVersion + "\""),
                        () -> "the caller's own submitted graph address must be echoed back: " + body);
                assertTrue(body.contains("\"manifestFormatVersion\":1"), body);
                assertTrue(Pattern.compile("\"manifestDigest\":\"[0-9a-f]{64}\"").matcher(body).find(),
                        () -> "the manifest's own address identifies it to its owner: " + body);
                assertTrue(body.contains("\"dimensionsTruncated\":false"), body);
            }
        }
    }

    /**
     * The disclosure boundary, driven by a manifest that pins a package this deployment does not have.
     *
     * <p>The verdict must be reported. The package must not be. Both halves are asserted, because a
     * projection that reported nothing would satisfy the second alone and be useless.</p>
     */
    @Test
    void anIncompatibleVerdictNamesTheDimensionAndDisclosesNoPartOfTheDeploymentInventory()
            throws Exception {
        try (var engine = new PekkoExecutionEngine("execution-manifest-http-disclosure");
             var store = new InMemoryExecutionStore();
             var manifests = new InMemoryExecutionManifestStore(Clock.systemUTC())) {
            var application = applicationWith(engine, store, manifests);
            try (var server = testServer(application, manifests)) {
                server.start();

                // A real submission first, so the manifest copied below carries this runtime's own
                // profile in every dimension. Fabricating one would have made every dimension differ
                // and the assertion would no longer be about node packages at all.
                var submitted = postAs(server, "/v1/executions?mode=run", GRAPH, "tenant-a");
                assertEquals(202, submitted.statusCode(), submitted.body());
                var accepted = new ExecutionKey("tenant-a",
                        UUID.fromString(extract(submitted.body(), "processInstanceId")));
                ExecutionManifest matching =
                        manifests.load(accepted).toCompletableFuture().join().manifest();

                // The same runtime, under a second execution, accepted when a package this deployment
                // no longer has was installed.
                var key = new ExecutionKey("tenant-a", UUID.randomUUID());
                var withPrivatePackage = new ExecutionManifest(matching.formatVersion(), key,
                        matching.graphContentId(), matching.graphIdentity(), matching.runtime(),
                        List.of(PinnedNodePackage.of(PRIVATE_PACKAGE, PRIVATE_VERSION, "node-sdk-1")),
                        matching.pinnedAt());
                manifests.pin(withPrivatePackage).toCompletableFuture().join();

                var response = getAs(server,
                        "/v1/executions/" + key.processInstanceId() + "/manifest", "tenant-a");
                assertEquals(200, response.statusCode(), response.body());
                String body = response.body();

                assertTrue(body.contains("\"compatible\":false"), body);
                assertTrue(body.contains("\"incompatibleDimensions\":[\"NODE_PACKAGE_MISSING\"]"),
                        () -> "the caller must still learn along which axis its execution stopped "
                                + "being reproducible: " + body);
                assertFalse(body.contains(PRIVATE_PACKAGE),
                        () -> "an installed-package identity must never cross the tenant boundary: " + body);
                assertFalse(body.contains(PRIVATE_VERSION), () -> "nor its version: " + body);
                assertFalse(body.contains("node-sdk-1"), () -> "nor its SDK contract: " + body);
                assertFalse(body.contains("\"pinned\"") || body.contains("\"observed\""),
                        () -> "the comparison's own values have no field on this route at all: " + body);
                assertFalse(body.contains("nodePackageCount"),
                        () -> "how many packages a deployment installs is an inventory fact too, and "
                                + "a number is still an answer: " + body);
            }
        }
    }

    @Test
    void anotherTenantsManifestIsAbsentRatherThanDenied() throws Exception {
        try (var engine = new PekkoExecutionEngine("execution-manifest-http-tenant");
             var store = new InMemoryExecutionStore();
             var manifests = new InMemoryExecutionManifestStore(Clock.systemUTC())) {
            var application = applicationWith(engine, store, manifests);
            try (var server = testServer(application, manifests)) {
                server.start();
                var submitted = postAs(server, "/v1/executions?mode=run", GRAPH, "tenant-a");
                assertEquals(202, submitted.statusCode(), submitted.body());
                String processInstanceId = extract(submitted.body(), "processInstanceId");

                var response = getAs(server, "/v1/executions/" + processInstanceId + "/manifest",
                        "tenant-b");
                assertEquals(404, response.statusCode(), response.body());
                assertEquals("UNKNOWN_PROCESS_INSTANCE", errorCode(response),
                        "a cross-tenant read reports absence; a denial would confirm the instance exists");
            }
        }
    }

    @Test
    void anInstanceWithNoPinnedManifestIsNotDistinguishableFromOneThatNeverExisted() throws Exception {
        try (var engine = new PekkoExecutionEngine("execution-manifest-http-absent");
             var store = new InMemoryExecutionStore();
             var manifests = new InMemoryExecutionManifestStore(Clock.systemUTC())) {
            var application = applicationWith(engine, store, manifests);
            try (var server = testServer(application, manifests)) {
                server.start();
                var response = getAs(server,
                        "/v1/executions/" + UUID.randomUUID() + "/manifest", "tenant-a");
                assertEquals(404, response.statusCode(), response.body());
                assertEquals("UNKNOWN_PROCESS_INSTANCE", errorCode(response));
            }
        }
    }

    @Test
    void aMalformedInstanceIdIsRefusedAsAnInvalidRequestRatherThanReportedAbsent() throws Exception {
        try (var engine = new PekkoExecutionEngine("execution-manifest-http-malformed");
             var store = new InMemoryExecutionStore();
             var manifests = new InMemoryExecutionManifestStore(Clock.systemUTC())) {
            var application = applicationWith(engine, store, manifests);
            try (var server = testServer(application, manifests)) {
                server.start();
                var response = getAs(server, "/v1/executions/not-a-uuid/manifest", "tenant-a");
                assertEquals(400, response.statusCode(), response.body());
                assertEquals("INVALID_REQUEST", errorCode(response));
            }
        }
    }

    /**
     * A deployment that records no manifests answers 501, never 404.
     *
     * <p>The distinction matters operationally: 404 says this execution has no manifest, 501 says
     * this deployment has no manifests at all. Collapsing them would let an operator conclude one
     * execution was mis-recorded when in fact nothing is recorded.</p>
     */
    @Test
    void aDeploymentThatRecordsNoManifestsSaysSoRatherThanReportingAnAbsentOne() throws Exception {
        try (var engine = new PekkoExecutionEngine("execution-manifest-http-unavailable");
             var store = new InMemoryExecutionStore()) {
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                    BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                    new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                    ExecutionIdentitySource.randomUuids(), store);
            try (var server = testServer(application, null)) {
                server.start();
                var response = getAs(server,
                        "/v1/executions/" + UUID.randomUUID() + "/manifest", "tenant-a");
                assertEquals(501, response.statusCode(), response.body());
                assertEquals("PROCESS_INVENTORY_UNAVAILABLE", errorCode(response));
            }
        }
    }

    private static DefaultRavenrootApplication applicationWith(PekkoExecutionEngine engine,
                                                               InMemoryExecutionStore store,
                                                               InMemoryExecutionManifestStore manifests) {
        return new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                ExecutionIdentitySource.randomUuids(), store, 0,
                UnknownBehaviorPolicy.passThrough(),
                new InMemoryGraphDefinitionStore(Clock.systemUTC()), null, null,
                GraphExecutionLimits.DEFAULTS, null, manifests);
    }

    private static RavenrootServer testServer(DefaultRavenrootApplication application,
                                              InMemoryExecutionManifestStore manifests) {
        var server = new RavenrootServer(application,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
                new HeaderTenantAuthenticator());
        if (manifests != null) {
            server.installExecutionManifests(application.executionManifests());
        }
        return server;
    }

    private static String extract(String json, String field) {
        var matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
        assertTrue(matcher.find(), () -> "no " + field + " in " + json);
        return matcher.group(1);
    }

    private static String errorCode(HttpResponse<String> response) {
        var matcher = Pattern.compile("\"code\":\"([^\"]+)\"").matcher(response.body());
        assertTrue(matcher.find(), () -> "no error code in " + response.body());
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

    /** Selects a tenant from a header; tenant identity, not authentication, is what these test. */
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
