package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.RavenrootApplication;
import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.NodePackages;
import ai.ravenroot.core.runtime.UnknownBehaviorPolicy;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.AuthenticationException;
import ai.ravenroot.server.security.BrowserOriginPolicy;
import ai.ravenroot.server.security.HttpSecurityConfiguration;
import ai.ravenroot.server.security.RequestAuthenticator;
import ai.ravenroot.server.security.SecurityHeadersPolicy;
import ai.ravenroot.server.spec.RouteTable;
import com.sun.net.httpserver.Headers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HTTP/OpenAPI-facing contract for the process-local deployment lifecycle. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class DeploymentLifecycleHttpTest {

    /**
     * Stands in for the per-request correlation handle when two error bodies are compared.
     *
     * <p>The same token replaces both ids, so a body that differs anywhere else still differs. It is
     * not a hex or UUID shape on purpose: it cannot be produced by the server, so a body that
     * contained it already would be a defect rather than a false match.</p>
     */
    private static final String CORRELATION_PLACEHOLDER = "<correlation>";

    @Test
    void durableLifecycleHttpCommandsExposeGenerationAndIdempotentOutcomes() throws Exception {
        try (var fixture = new Fixture(true)) {
            var registered = fixture.request(
                    "POST", "/v1/deployments?id=durable", NO_SOURCE_GRAPH, "tenant-a");
            assertEquals(200, registered.statusCode(), registered.body());
            assertTrue(registered.body().contains("\"deploymentGeneration\":0"), registered.body());
            assertTrue(fixture.request("GET", "/v1/deployments", "", "tenant-a").body()
                    .contains("\"deploymentGeneration\":0"));

            var legacyStop = fixture.request(
                    "POST", "/v1/deployments/durable/stop", "", "tenant-a");
            assertEquals(400, legacyStop.statusCode(),
                    "the former UI request is reproducibly incomplete once persistence is enabled");

            var start = fixture.command("/v1/deployments/durable/start", "tenant-a",
                    "start-1", 0, null);
            assertEquals(200, start.statusCode(), start.body());
            assertTrue(start.body().contains("\"outcome\":\"ACCEPTED\""), start.body());
            assertTrue(start.body().contains("\"generation\":1"), start.body());

            assertTrue(fixture.request("GET", "/v1/deployments/durable", "", "tenant-a").body()
                    .contains("\"deploymentGeneration\":1"));

            var stop = fixture.command("/v1/deployments/durable/stop", "tenant-a",
                    "stop-1", 1, "operator maintenance");
            assertTrue(stop.body().contains("\"generation\":2"), stop.body());
            var replay = fixture.command("/v1/deployments/durable/stop", "tenant-a",
                    "stop-1", 1, "operator maintenance");
            assertTrue(replay.body().contains("\"outcome\":\"REPLAYED\""), replay.body());

            var stale = fixture.command("/v1/deployments/durable/restart", "tenant-a",
                    "restart-stale", 1, null);
            assertTrue(stale.body().contains("\"outcome\":\"STALE_GENERATION\""), stale.body());
            var restartedReady = fixture.command("/v1/deployments/durable/start", "tenant-a",
                    "start-2", 2, null);
            assertTrue(restartedReady.body().contains("\"generation\":3"), restartedReady.body());
            var restart = fixture.command("/v1/deployments/durable/restart", "tenant-a",
                    "restart-1", 3, null);
            assertTrue(restart.body().contains("\"generation\":4"), restart.body());

            var removed = fixture.undeploy("/v1/deployments/durable", "tenant-a",
                    "undeploy-1", 4, "retired", "CANCEL_IN_FLIGHT");
            assertTrue(removed.body().contains("\"outcome\":\"TERMINAL\""), removed.body());
            assertTrue(removed.body().contains("\"generation\":5"), removed.body());
            assertEquals(404, fixture.request("GET", "/v1/deployments/durable", "", "tenant-a")
                    .statusCode(), "terminal durable undeploy removes the process-local runtime");
            assertTrue(fixture.request("GET", "/v1/deployments", "", "tenant-a").body()
                    .contains("\"deployments\":[]"));

            var removalReplay = fixture.undeploy("/v1/deployments/durable", "tenant-a",
                    "undeploy-1", 4, "retired", "CANCEL_IN_FLIGHT");
            assertTrue(removalReplay.body().contains("\"outcome\":\"REPLAYED\""), removalReplay.body());
            assertTrue(removalReplay.body().contains("\"outcome\":\"TERMINAL\""), removalReplay.body());
            assertEquals(409, fixture.request(
                    "POST", "/v1/deployments?id=durable", NO_SOURCE_GRAPH, "tenant-a").statusCode(),
                    "a tombstoned durable identity is never silently reused");
        }
    }

    @Test
    void persistenceRoutesOnlyDurableAliasesAndPreservesLegacySourceSessionCompatibility() throws Exception {
        try (var fixture = new Fixture(true)) {
            assertEquals(202, fixture.request(
                    "POST", "/v1/source-sessions?id=legacy", SOURCE_GRAPH, "tenant-a").statusCode());
            assertState(fixture.request("POST", "/v1/deployments/legacy/stop", "", "tenant-a"), "STOPPED",
                    "a source-session alias must not be forced through durable headers merely because a registry exists");
            assertFalse(fixture.request("GET", "/v1/deployments/legacy", "", "tenant-a").body()
                    .contains("deploymentGeneration"));
        }
    }

    @Test
    void deploymentViewAndStreamAreBoundToTheExactTenantIncarnationAndVersion() throws Exception {
        try (var fixture = new Fixture()) {
            assertEquals(200, fixture.request("POST", "/v1/deployments?id=observed",
                    NO_SOURCE_GRAPH, "tenant-a").statusCode());
            assertEquals(404, fixture.request("GET", "/v1/deployments/observed/view", "", "tenant-a")
                    .statusCode(), "a cold registration has no successfully opened viewer definition");
            assertState(fixture.request("POST", "/v1/deployments/observed/start", "", "tenant-a"), "READY");
            var view = fixture.request("GET", "/v1/deployments/observed/view", "", "tenant-a");
            assertEquals(200, view.statusCode(), view.body());
            assertTrue(view.body().contains("\"viewerSourceVersion\":\"1\""), view.body());
            assertTrue(view.body().contains("\"kind\":\"deployment\""), view.body());
            assertTrue(view.body().contains("\"viewerContractVersion\":\"1.0\""), view.body());
            assertFalse(view.body().toLowerCase().contains("graphml"), view.body());
            String incarnation = field(view.body(), "incarnationId");
            String version = field(view.body(), "graphVersion");

            assertEquals(404, fixture.request("GET", "/v1/deployments/observed/view", "", "tenant-b")
                    .statusCode());
            assertEquals(404, fixture.request("GET", "/v1/deployments/absent/view", "", "tenant-b")
                    .statusCode());
            assertEquals(404, fixture.request("GET",
                    "/v1/deployments/observed/events?graphVersion=" + version, "", "tenant-a").statusCode(),
                    "the first attachment must supply the incarnation captured by GET view");

            var stream = fixture.stream("/v1/deployments/observed/events?graphVersion=" + version,
                    "tenant-a", incarnation);
            assertEquals(200, stream.statusCode());
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream.body()))) {
                String event = reader.readLine();
                String data = reader.readLine();
                assertEquals("event: lifecycle", event);
                assertTrue(data.contains("\"type\":\"lifecycle\""), data);
                assertTrue(data.contains("\"incarnationId\":\"" + incarnation + "\""), data);
                assertTrue(data.contains("\"graphVersion\":\"" + version + "\""), data);
                assertTrue(data.contains("\"lifecycle\":\"READY\""), data);
            }
        }
    }

    @Test
    void attachedStreamDistinguishesUndeployFromIdenticalBytesReplacement() throws Exception {
        try (var fixture = new Fixture()) {
            assertEquals(200, fixture.request("POST", "/v1/deployments?id=observed",
                    NO_SOURCE_GRAPH, "tenant-a").statusCode());
            assertState(fixture.request("POST", "/v1/deployments/observed/start", "", "tenant-a"), "READY");
            var firstView = fixture.request("GET", "/v1/deployments/observed/view", "", "tenant-a");
            String firstIncarnation = field(firstView.body(), "incarnationId");
            String firstVersion = field(firstView.body(), "graphVersion");
            var detached = fixture.stream("/v1/deployments/observed/events?graphVersion=" + firstVersion,
                    "tenant-a", firstIncarnation);
            var detachedReader = new java.io.BufferedReader(new java.io.InputStreamReader(detached.body()));
            assertEquals("event: lifecycle", detachedReader.readLine());
            detachedReader.readLine();
            detachedReader.readLine();
            assertEquals(200, fixture.request("DELETE", "/v1/deployments/observed", "", "tenant-a")
                    .statusCode());
            String undeployed = terminalData(detachedReader);
            assertTrue(undeployed.contains("\"reason\":\"UNDEPLOYED\""), undeployed);
            assertTrue(undeployed.contains("\"incarnationId\":\"" + firstIncarnation + "\""), undeployed);
            detachedReader.close();

            assertEquals(200, fixture.request("POST", "/v1/deployments?id=observed",
                    NO_SOURCE_GRAPH, "tenant-a").statusCode());
            assertState(fixture.request("POST", "/v1/deployments/observed/start", "", "tenant-a"), "READY");
            var replacementView = fixture.request("GET", "/v1/deployments/observed/view", "", "tenant-a");
            String replacementIncarnation = field(replacementView.body(), "incarnationId");
            String replacementVersion = field(replacementView.body(), "graphVersion");
            var replaced = fixture.stream("/v1/deployments/observed/events?graphVersion=" + replacementVersion,
                    "tenant-a", replacementIncarnation);
            var replacedReader = new java.io.BufferedReader(new java.io.InputStreamReader(replaced.body()));
            assertEquals("event: lifecycle", replacedReader.readLine());
            replacedReader.readLine();
            replacedReader.readLine();
            assertEquals(200, fixture.request("DELETE", "/v1/deployments/observed", "", "tenant-a")
                    .statusCode());
            assertEquals(200, fixture.request("POST", "/v1/deployments?id=observed",
                    NO_SOURCE_GRAPH, "tenant-a").statusCode());
            assertState(fixture.request("POST", "/v1/deployments/observed/start", "", "tenant-a"), "READY");
            String mismatch = terminalData(replacedReader);
            assertTrue(mismatch.contains("\"reason\":\"VERSION_MISMATCH\""), mismatch);
            assertTrue(mismatch.contains("\"incarnationId\":\"" + replacementIncarnation + "\""), mismatch);
            assertNotEquals(replacementIncarnation,
                    field(fixture.request("GET", "/v1/deployments/observed/view", "", "tenant-a").body(),
                            "incarnationId"), "identical graph bytes still create a new incarnation");
            replacedReader.close();
        }
    }

    @Test
    void undeployBetweenLeaseCheckAndSourceRefreshStillEmitsATerminalFrame() throws Exception {
        var race = new DeploymentViewRace();
        try (var fixture = new Fixture(race)) {
            assertEquals(200, fixture.request("POST", "/v1/deployments?id=racing",
                    NO_SOURCE_GRAPH, "tenant-a").statusCode());
            assertState(fixture.request("POST", "/v1/deployments/racing/start", "", "tenant-a"), "READY");
            var view = fixture.request("GET", "/v1/deployments/racing/view", "", "tenant-a");
            String incarnation = field(view.body(), "incarnationId");
            String version = field(view.body(), "graphVersion");
            var stream = fixture.stream("/v1/deployments/racing/events?graphVersion=" + version,
                    "tenant-a", incarnation);
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream.body()));
            assertEquals("event: lifecycle", reader.readLine());
            reader.readLine();
            reader.readLine();

            race.arm();
            assertTrue(race.lookupReturned.await(10, TimeUnit.SECONDS),
                    "the stream must reach the post-lease source lookup");
            assertEquals(200, fixture.request("DELETE", "/v1/deployments/racing", "", "tenant-a")
                    .statusCode());
            race.continueLookup.countDown();

            String terminal = terminalData(reader);
            assertTrue(terminal.contains("\"reason\":\"UNDEPLOYED\""), terminal);
            reader.close();
        } finally {
            race.continueLookup.countDown();
        }
    }

    @Test
    void nativeAndEmbedExecutionFramesPreserveFallback() throws Exception {
        var binding = new DeploymentObservationCursorStore.Binding(
                "test", "observed", "incarnation", "version");
        var event = new ai.ravenroot.api.application.ExecutionEvent(7, java.time.Instant.EPOCH,
                "tenant-a", "request", "engine", "version", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(),
                ai.ravenroot.api.application.ExecutionEventType.NODE_DEFAULTED, "worker", 1, true,
                "configured fallback", null, null, null, "engine", null, 1, null, null, null);

        String nativeJson = RavenrootServer.deploymentExecutionJson(binding, event);
        var embedSerializer = ai.ravenroot.server.embed.EmbedBrowserHttpHandler.class
                .getDeclaredMethod("executionJson", DeploymentObservationCursorStore.Binding.class,
                        ai.ravenroot.api.application.ExecutionEvent.class);
        embedSerializer.setAccessible(true);
        String embedJson = (String) embedSerializer.invoke(null, binding, event);

        assertTrue(nativeJson.contains("\"type\":\"NODE_DEFAULTED\""), nativeJson);
        assertTrue(nativeJson.contains("\"fallback\":true"), nativeJson);
        assertTrue(embedJson.contains("\"type\":\"NODE_DEFAULTED\""), embedJson);
        assertTrue(embedJson.contains("\"fallback\":true"), embedJson);
    }

    private static String terminalData(java.io.BufferedReader reader) throws Exception {
        String line;
        boolean terminal = false;
        for (int index = 0; index < 20 && (line = reader.readLine()) != null; index++) {
            if (line.equals("event: source-invalidated")) terminal = true;
            else if (terminal && line.startsWith("data: ")) return line;
        }
        throw new AssertionError("stream did not emit a terminal source-invalidated frame");
    }

    private static String field(String json, String name) {
        var matcher = java.util.regex.Pattern.compile("\\\"" + name + "\\\":\\\"([^\\\"]+)\\\"")
                .matcher(json);
        if (!matcher.find()) throw new AssertionError("missing " + name + " in " + json);
        return matcher.group(1);
    }

    @Test
    void aSourcelessGraphIsRegisteredStartedStoppedRestartedAndUndeployedOverHttp() throws Exception {
        try (var fixture = new Fixture()) {
            assertEquals(401, fixture.request("GET", "/v1/deployments/anything", "", "").statusCode());

            HttpResponse<String> registered = fixture.request(
                    "POST", "/v1/deployments?id=batch-1", NO_SOURCE_GRAPH, "tenant-a");
            assertEquals(200, registered.statusCode(), registered.body());
            assertTrue(registered.body().contains("\"state\":\"REGISTERED\""), registered.body());
            assertTrue(registered.body().contains("\"scope\":\"LOCAL_PROCESS\""), registered.body());
            assertTrue(registered.body().contains("\"sourceCount\":0"), registered.body());
            var graphVersionMatch = java.util.regex.Pattern.compile(
                    "\\\"graphVersion\\\":\\\"([0-9a-f]{64})\\\"").matcher(registered.body());
            assertTrue(graphVersionMatch.find(), registered.body());
            String graphVersion = graphVersionMatch.group(1);

            HttpResponse<String> rejoined = fixture.request(
                    "POST", "/v1/deployments?id=batch-1", NO_SOURCE_GRAPH, "tenant-a");
            assertEquals(200, rejoined.statusCode(), rejoined.body());
            assertEquals(registered.body(), rejoined.body(),
                    "an idempotent re-registration answers with the same status, not a second create");

            HttpResponse<String> conflict = fixture.request("POST", "/v1/deployments?id=batch-1",
                    NO_SOURCE_GRAPH.replace("id=\"g\"", "id=\"g2\""), "tenant-a");
            assertEquals(409, conflict.statusCode(), conflict.body());

            HttpResponse<String> listed = fixture.request("GET", "/v1/deployments", "", "tenant-a");
            assertEquals(200, listed.statusCode(), listed.body());
            assertTrue(listed.body().contains("\"deploymentId\":\"batch-1\""), listed.body());
            assertTrue(listed.body().contains("\"scope\":\"LOCAL_PROCESS\""), listed.body());
            assertTrue(listed.body().contains("\"graphVersion\":\"" + graphVersion + "\""),
                    listed.body());

            HttpResponse<String> started = fixture.request(
                    "POST", "/v1/deployments/batch-1/start", "", "tenant-a");
            assertState(started, "READY");
            assertTrue(started.body().contains("\"graphVersion\":\"" + graphVersion + "\""),
                    started.body());
            assertState(fixture.request("POST", "/v1/deployments/batch-1/start", "", "tenant-a"), "READY");
            assertState(fixture.request("POST", "/v1/deployments/batch-1/stop", "", "tenant-a"), "STOPPED");
            assertState(fixture.request("POST", "/v1/deployments/batch-1/stop", "", "tenant-a"), "STOPPED");
            assertState(fixture.request("GET", "/v1/deployments/batch-1", "", "tenant-a"), "STOPPED");
            assertState(fixture.request("POST", "/v1/deployments/batch-1/restart", "", "tenant-a"), "READY");

            assertState(fixture.request("DELETE", "/v1/deployments/batch-1", "", "tenant-a"), "STOPPED");
            assertEquals(404, fixture.request("GET", "/v1/deployments/batch-1", "", "tenant-a").statusCode());
            assertEquals(404, fixture.request("DELETE", "/v1/deployments/batch-1", "", "tenant-a").statusCode(),
                    "an already-undeployed id is the same 404 an unknown id gets");
            assertTrue(fixture.request("GET", "/v1/deployments", "", "tenant-a").body().contains("\"deployments\":[]"));
        }
    }

    /**
     * <b>A sibling tenant's deployment id and an id nobody ever registered answer the same.</b>
     *
     * <p>The property is that <b>no field distinguishes the two</b>: if any did, a caller could
     * enumerate another tenant's deployment ids by watching which answer came back.</p>
     *
     * <p><b>Why this is not a byte-for-byte comparison.</b> The correlation id is deliberately distinct
     * per request — {@code AuthenticatedPrincipalAttribute} keys its cache by the exchange
     * object rather than by an exchange attribute, because the JDK server backs those attributes with
     * the shared {@code HttpContext} and every later request on the same route was reusing one
     * handle. {@code ErrorEnvelopeHttpTest} now asserts that as a requirement. A per-request handle
     * that is unique by construction discloses nothing about which id exists, so demanding two error
     * bodies be byte-identical would demand the opposite of that requirement.</p>
     *
     * <p>So the comparison follows the shape {@code CredentialRouteTest} established for the same
     * problem: pull each body's correlation id out, assert the two <b>differ</b>, then compare the
     * bodies with each id replaced by one placeholder. That is <b>stronger</b> than the byte-identity
     * it replaces — every other field must still match exactly, and it additionally proves the two
     * requests did not share a handle, which the old assertion never checked. Add the tenant, the
     * deployment id, a distinct code or any "known but forbidden" flavour to either refusal and the
     * placeholder comparison still reds.</p>
     */
    @Test
    void unknownAndSiblingTenantIdentifiersFailClosedWithoutDisclosingExistence() throws Exception {
        try (var fixture = new Fixture()) {
            assertEquals(200, fixture.request(
                    "POST", "/v1/deployments?id=shared", NO_SOURCE_GRAPH, "tenant-a").statusCode());
            assertState(fixture.request("POST", "/v1/deployments/shared/start", "", "tenant-a"), "READY");

            HttpResponse<String> unknownToSibling = fixture.request("GET", "/v1/deployments/shared", "", "tenant-b");
            HttpResponse<String> neverExisted = fixture.request("GET", "/v1/deployments/absent", "", "tenant-b");
            String siblingCorrelation = correlationIn(unknownToSibling.body());
            String absentCorrelation = correlationIn(neverExisted.body());
            assertEquals(404, unknownToSibling.statusCode(), unknownToSibling.body());
            assertEquals(neverExisted.statusCode(), unknownToSibling.statusCode());
            assertNotEquals(siblingCorrelation, absentCorrelation,
                    "separate requests must retain separate correlation ids");
            assertEquals(neverExisted.body().replace(absentCorrelation, CORRELATION_PLACEHOLDER),
                    unknownToSibling.body().replace(siblingCorrelation, CORRELATION_PLACEHOLDER),
                    "a sibling's id and an id nobody registered must differ only by per-request correlation");
            assertFalse(unknownToSibling.body().contains("tenant-a"), unknownToSibling.body());

            for (String command : List.of("start", "stop", "restart")) {
                assertEquals(404, fixture.request(
                        "POST", "/v1/deployments/shared/" + command, "", "tenant-b").statusCode(), command);
            }
            assertEquals(404, fixture.request("DELETE", "/v1/deployments/shared", "", "tenant-b").statusCode());
            assertTrue(fixture.request("GET", "/v1/deployments", "", "tenant-b").body().contains("\"deployments\":[]"));

            assertState(fixture.request("GET", "/v1/deployments/shared", "", "tenant-a"), "READY",
                    "none of the sibling's refused commands may have reached tenant A's deployment");
        }
    }

    /**
     * The scope is refused, never degraded: a caller that asked for a cluster guarantee is told no
     * rather than handed a process-local deployment under the name it requested.
     */
    @Test
    void anExplicitNonLocalScopeIsRefusedRatherThanSilentlyDegraded() throws Exception {
        try (var fixture = new Fixture()) {
            assertEquals(400, fixture.request(
                    "POST", "/v1/deployments?id=clustered&scope=CLUSTER", NO_SOURCE_GRAPH, "tenant-a").statusCode());
            assertEquals(400, fixture.request(
                    "POST", "/v1/deployments?id=clustered&scope=SINGLE_REPLICA", NO_SOURCE_GRAPH, "tenant-a")
                    .statusCode(), "even a synonym is refused: one fact, one vocabulary");
            assertEquals(404, fixture.request("GET", "/v1/deployments/clustered", "", "tenant-a").statusCode(),
                    "a refused scope must not have registered anything");

            assertEquals(200, fixture.request("POST", "/v1/deployments?id=clustered&scope=LOCAL_PROCESS",
                    NO_SOURCE_GRAPH, "tenant-a").statusCode());
        }
    }

    /** Stopping one deployment leaves the sibling and the shared ActorSystem serving. */
    @Test
    void stoppingOneDeploymentLeavesTheSiblingAndTheSharedActorSystemHealthy() throws Exception {
        try (var fixture = new Fixture()) {
            assertEquals(200, fixture.request(
                    "POST", "/v1/deployments?id=first", SOURCE_GRAPH, "tenant-a").statusCode());
            assertEquals(200, fixture.request(
                    "POST", "/v1/deployments?id=second", SOURCE_GRAPH, "tenant-a").statusCode());
            assertState(fixture.request("POST", "/v1/deployments/first/start", "", "tenant-a"), "READY");
            assertState(fixture.request("POST", "/v1/deployments/second/start", "", "tenant-a"), "READY");

            assertState(fixture.request("POST", "/v1/deployments/first/stop", "", "tenant-a"), "STOPPED");
            assertState(fixture.request("GET", "/v1/deployments/second", "", "tenant-a"), "READY");

            // The shared engine is still usable: a fresh deployment registers and reaches readiness
            // after a sibling's domain was released.
            assertEquals(200, fixture.request(
                    "POST", "/v1/deployments?id=third", SOURCE_GRAPH, "tenant-a").statusCode());
            assertState(fixture.request("POST", "/v1/deployments/third/start", "", "tenant-a"), "READY");
        }
    }

    /**
     * Characterisation: {@code POST /v1/executions/{id}/cancel} is still traversal cancellation and
     * {@code POST /v1/drain} is still the server-wide drain. Neither was relabelled as deployment
     * stop, which would leave a source-less
     * graph with no lifecycle at all.
     */
    @Test
    void traversalCancelAndServerDrainKeepTheirOwnMeaningAndDoNotStopADeployment() throws Exception {
        try (var fixture = new Fixture()) {
            assertEquals(200, fixture.request(
                    "POST", "/v1/deployments?id=untouched", NO_SOURCE_GRAPH, "tenant-a").statusCode());
            assertState(fixture.request("POST", "/v1/deployments/untouched/start", "", "tenant-a"), "READY");

            // Cancel addresses traversals, so a deployment id is simply not one of its resources.
            HttpResponse<String> cancelByDeploymentId = fixture.request(
                    "POST", "/v1/executions/untouched/cancel", "", "tenant-a");
            assertTrue(cancelByDeploymentId.statusCode() >= 400, cancelByDeploymentId.body());
            HttpResponse<String> cancelUnknownTraversal = fixture.request(
                    "POST", "/v1/executions/" + UUID.randomUUID() + "/cancel", "", "tenant-a");
            assertTrue(cancelUnknownTraversal.statusCode() >= 400, cancelUnknownTraversal.body());
            assertState(fixture.request("GET", "/v1/deployments/untouched", "", "tenant-a"), "READY",
                    "traversal cancellation must not stop a deployment");

            HttpResponse<String> drained = fixture.request("POST", "/v1/drain", "", "platform");
            assertTrue(drained.statusCode() == 200 || drained.statusCode() == 202, drained.body());
            assertTrue(drained.body().contains("\"outcome\""), drained.body());
            assertState(fixture.request("GET", "/v1/deployments/untouched", "", "tenant-a"), "READY",
                    "a server-wide traversal drain is not a deployment stop");

            // And they remain separately declared routes rather than aliases of each other.
            assertTrue(RouteTable.ALL.stream().anyMatch(route -> route.path().equals("/v1/executions/{id}/cancel")));
            assertTrue(RouteTable.ALL.stream().anyMatch(route -> route.path().equals("/v1/drain")));
            assertTrue(RouteTable.ALL.stream().anyMatch(route -> route.path().equals("/v1/deployments/{id}/stop")));
        }
    }

    /** The wire shape is unchanged even though both routes share one registry underneath. */
    @Test
    void aSourceSessionAndItsDeploymentAreTheSameLocalLifecycle() throws Exception {
        try (var fixture = new Fixture()) {
            HttpResponse<String> session = fixture.request(
                    "POST", "/v1/source-sessions?id=editor-1", SOURCE_GRAPH, "tenant-a");
            assertEquals(202, session.statusCode(), session.body());
            assertTrue(session.body().contains("\"sessionId\":\"editor-1\""), session.body());

            String listed = pollUntil(fixture, "/v1/deployments/editor-1", "tenant-a", "READY");
            assertTrue(listed.contains("\"deploymentId\":\"editor-1\""), listed);
            assertTrue(listed.contains("\"sourceCount\":1"), listed);

            assertState(fixture.request("POST", "/v1/deployments/editor-1/stop", "", "tenant-a"), "STOPPED");
            HttpResponse<String> asSession = fixture.request(
                    "GET", "/v1/source-sessions/editor-1", "", "tenant-a");
            assertEquals(200, asSession.statusCode(), asSession.body());
            assertTrue(asSession.body().contains("\"state\":\"STOPPED\""), asSession.body());

            // The converse: a source-less deployment is not observable as a source session, and says
            // so with the same nondisclosing 404 an unknown id gets.
            assertEquals(200, fixture.request(
                    "POST", "/v1/deployments?id=sourceless", NO_SOURCE_GRAPH, "tenant-a").statusCode());
            assertEquals(404, fixture.request(
                    "GET", "/v1/source-sessions/sourceless", "", "tenant-a").statusCode());
            assertState(fixture.request("GET", "/v1/deployments/sourceless", "", "tenant-a"), "REGISTERED");
        }
    }

    /**
     * The per-request correlation handle carried by a {@code ravenroot.error/1} envelope.
     *
     * <p>Throws rather than returning empty when the field is absent: a body with no correlation id
     * would make the comparison above vacuous, and an envelope without one is itself a regression of
     * the error contract.</p>
     */
    private static String correlationIn(String body) {
        var matcher = java.util.regex.Pattern.compile("\"correlationId\":\"([^\"]+)\"").matcher(body);
        if (!matcher.find()) throw new AssertionError("no correlation id in " + body);
        return matcher.group(1);
    }

    private static void assertState(HttpResponse<String> response, String state) {
        assertState(response, state, "unexpected lifecycle state");
    }

    private static void assertState(HttpResponse<String> response, String state, String because) {
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"state\":\"" + state + "\""), because + ": " + response.body());
        assertTrue(response.body().contains("\"scope\":\"LOCAL_PROCESS\""),
                "every lifecycle response carries its scope: " + response.body());
    }

    private static String pollUntil(Fixture fixture, String path, String tenant, String state) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        HttpResponse<String> latest;
        do {
            latest = fixture.request("GET", path, "", tenant);
            if (latest.statusCode() == 200 && latest.body().contains("\"state\":\"" + state + "\"")) {
                return latest.body();
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("did not reach " + state + "; latest=" + latest.body());
    }

    /** One Pekko engine, one server, closed in the right order regardless of how a test ends. */
    private static final class Fixture implements AutoCloseable {
        private final PekkoExecutionEngine engine;
        private final DefaultRavenrootApplication application;
        private final RavenrootServer server;

        Fixture() throws Exception {
            this(false, null);
        }

        Fixture(boolean durable) throws Exception {
            this(durable, null);
        }

        Fixture(DeploymentViewRace race) throws Exception {
            this(false, race);
        }

        private Fixture(boolean durable, DeploymentViewRace race) throws Exception {
            var behavior = new SourceBehavior();
            NodePackage nodePackage = new NodePackage() {
                @Override public String id() { return "test.deployment.http.package"; }
                @Override public String version() { return "1.0.0"; }
                @Override public String sdkContract() { return NodeSdk.CONTRACT; }
                @Override public List<NodeBehavior> behaviors() { return List.of(behavior); }
            };
            BehaviorRegistry behaviors = NodePackages.register(new BehaviorRegistry(), nodePackage);
            engine = new PekkoExecutionEngine("deployment-lifecycle-http");
            application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), behaviors,
                    new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                    ExecutionIdentitySource.randomUuids(), null, 8, UnknownBehaviorPolicy.passThrough());
            RavenrootApplication servedApplication = race == null ? application : race.wrap(application);
            var httpSecurity = new HttpSecurityConfiguration(
                    new BrowserOriginPolicy(Set.of("https://editor.example")),
                    new SecurityHeadersPolicy(false), java.time.Duration.ofSeconds(30));
            server = new RavenrootServer(servedApplication,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, true,
                    new HeaderTenantAuthenticator(), httpSecurity);
            if (durable) {
                var clock = Clock.systemUTC();
                var registry = new ai.ravenroot.core.deployment.registry.InMemoryDeploymentRegistry(
                        clock, tenant -> ai.ravenroot.api.deployment.DeploymentId.of(UUID.randomUUID().toString()));
                var singleFlight = new ai.ravenroot.core.deployment.DeploymentSingleFlight();
                var coordinator = new ai.ravenroot.core.deployment.DeploymentCoordinator(
                        registry, application.localDeploymentTargets(), singleFlight,
                        ai.ravenroot.core.deployment.ServiceShutdownIntent.RUNNING,
                        "http-owner", Duration.ofMinutes(1), Duration.ofSeconds(1), clock);
                server.installDurableDeploymentControl(
                        new ai.ravenroot.core.deployment.DurableLocalDeploymentControl(
                                application, registry, coordinator, clock));
            }
            server.start();
        }

        HttpResponse<String> command(String path, String tenant, String key, long generation,
                                     String reason) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.port() + path))
                    .header("X-Test-Tenant", tenant)
                    .header("Idempotency-Key", key)
                    .header("X-Ravenroot-Expected-Generation", Long.toString(generation));
            if (reason != null) request.header("X-Ravenroot-Reason", reason);
            return HttpClient.newHttpClient().send(request.POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> undeploy(String path, String tenant, String key, long generation,
                                      String reason, String disposition) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.port() + path))
                    .header("X-Test-Tenant", tenant)
                    .header("Idempotency-Key", key)
                    .header("X-Ravenroot-Expected-Generation", Long.toString(generation))
                    .header("X-Ravenroot-Reason", reason)
                    .header("X-Ravenroot-Undeploy-Disposition", disposition)
                    .DELETE().build();
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> request(String method, String path, String body, String tenant) throws Exception {
            HttpRequest.BodyPublisher publisher = body.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
            HttpRequest.Builder request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.port() + path))
                    .header("Content-Type", "application/graphml+xml; charset=utf-8");
            if (!tenant.isBlank()) request.header("X-Test-Tenant", tenant);
            return HttpClient.newHttpClient().send(request.method(method, publisher).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<java.io.InputStream> stream(String path, String tenant, String incarnation)
                throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                    .header("X-Test-Tenant", tenant)
                    .header("X-Ravenroot-Deployment-Incarnation", incarnation)
                    .GET().build();
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
        }

        @Override
        public void close() {
            server.close();
            engine.close();
        }
    }

    /** Pauses one already-successful source lookup so undeploy can win before the caller refreshes it. */
    private static final class DeploymentViewRace {
        private final AtomicBoolean armed = new AtomicBoolean();
        private final CountDownLatch lookupReturned = new CountDownLatch(1);
        private final CountDownLatch continueLookup = new CountDownLatch(1);

        private void arm() {
            armed.set(true);
        }

        private RavenrootApplication wrap(RavenrootApplication delegate) {
            return (RavenrootApplication) Proxy.newProxyInstance(
                    RavenrootApplication.class.getClassLoader(),
                    new Class<?>[] { RavenrootApplication.class },
                    (proxy, method, arguments) -> {
                        Object result;
                        try {
                            result = method.invoke(delegate, arguments);
                        } catch (InvocationTargetException reflected) {
                            throw reflected.getCause();
                        }
                        if ("localDeploymentView".equals(method.getName())
                                && armed.compareAndSet(true, false)) {
                            lookupReturned.countDown();
                            try {
                                if (!continueLookup.await(20, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("timed out waiting to finish the source lookup");
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException("source lookup interrupted", interrupted);
                            }
                        }
                        return result;
                    });
        }
    }

    private static final class HeaderTenantAuthenticator implements RequestAuthenticator {
        @Override
        public AuthenticatedPrincipal authenticate(Headers headers) throws AuthenticationException {
            String tenant = headers.getFirst("X-Test-Tenant");
            if (tenant == null || tenant.isBlank()) throw new AuthenticationException("missing tenant");
            return new AuthenticatedPrincipal(tenant, AuthenticatedPrincipal.Type.USER,
                    "urn:ravenroot:test", tenant, Set.of(Role.OPERATOR, Role.PLATFORM_ADMIN),
                    java.util.Arrays.stream(AuthorizationAction.values())
                            .filter(AuthorizationAction::available)
                            .map(AuthorizationAction::requiredScope)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        }
    }

    private static final class SourceBehavior implements NodeBehavior, InboundSourceCapable {
        private final CopyOnWriteArrayList<InboundSourceContext> contexts = new CopyOnWriteArrayList<>();

        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.http.source", "HTTP source", "Test", "HTTP source", "actor",
                    false, List.of(), Set.of(), NodeRuntimeNature.SOURCE, Set.of(NodeRuntimeNature.SOURCE));
        }

        @Override
        public NodeAction create(NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(
                    ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()));
        }

        @Override
        public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
            return new InboundSource() {
                @Override public CompletionStage<Void> start(InboundSourceContext started) {
                    contexts.add(started);
                    return CompletableFuture.completedFuture(null);
                }
                @Override public CompletionStage<Void> stop() {
                    return CompletableFuture.completedFuture(null);
                }
            };
        }
    }

    private static final String SOURCE_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="source-http" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="listener"><data key="kind">BEHAVIOR</data><data key="behavior">test.http.source</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge source="start" target="listener"><data key="outcome">continue</data></edge>
                <edge source="listener" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    private static final String NO_SOURCE_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <graph id="g" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge source="start" target="end"/>
              </graph>
            </graphml>
            """;
}
