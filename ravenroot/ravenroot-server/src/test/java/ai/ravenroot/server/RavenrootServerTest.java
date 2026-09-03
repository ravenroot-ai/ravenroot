package ai.ravenroot.server;

import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.GraphExecutionLimitException;
import ai.ravenroot.core.ai.AgentRuntimeRegistry;
import ai.ravenroot.core.ai.ModelProviderRegistry;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramAdmission;
import ai.ravenroot.api.programming.ProgramLanguageDescriptor;
import ai.ravenroot.api.programming.ProgramRequest;
import ai.ravenroot.api.programming.ProgramRuntime;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.BrowserOriginPolicy;
import ai.ravenroot.server.security.HttpSecurityConfiguration;
import ai.ravenroot.server.security.SecurityHeadersPolicy;
import ai.ravenroot.server.security.RequestAuthenticator;
import ai.ravenroot.server.support.ForwardingRavenrootApplication;
import ai.ravenroot.api.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RavenrootServerTest {
    @TempDir
    Path uiDirectory;

    private static final String EXECUTABLE_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="server-test" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="future"><data key="kind">BEHAVIOR</data><data key="behavior">future-behavior</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="future"><data key="outcome">continue</data></edge>
                <edge id="e2" source="future" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    /**
     * A handled-failure topology as a caller would submit it: {@code boom} either completes or fails,
     * and either way its branch reaches the single END through a quorum-of-one rejoin, so the branch
     * {@code boom} did not take does not stall the traversal. {@code boom -> handler} carries the
     * author-declared {@code failure.route} property and deliberately no {@code outcome} -- an edge is
     * a failure route or an outcome edge, never both, and the loader refuses a graph that claims it is
     * both.
     */
    private static final String HANDLED_FAILURE_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="joinQuorum" for="node" attr.name="joinQuorum" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <key id="failureRoute" for="edge" attr.name="failure.route" attr.type="string"/>
              <graph id="handled-failure" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="boom"><data key="kind">BEHAVIOR</data><data key="behavior">boom-behavior</data></node>
                <node id="normalNext"><data key="kind">BEHAVIOR</data><data key="behavior">next-behavior</data></node>
                <node id="handler"><data key="kind">BEHAVIOR</data><data key="behavior">handler-behavior</data></node>
                <node id="rejoin"><data key="kind">PASSTHROUGH</data><data key="joinQuorum">1</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="boom"><data key="outcome">continue</data></edge>
                <edge id="e2" source="boom" target="normalNext"><data key="outcome">continue</data></edge>
                <edge id="e3" source="boom" target="handler"><data key="failureRoute">true</data></edge>
                <edge id="e4" source="normalNext" target="rejoin"><data key="outcome">continue</data></edge>
                <edge id="e5" source="handler" target="rejoin"><data key="outcome">continue</data></edge>
                <edge id="e6" source="rejoin" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    /** A real failed attempt whose failure route terminates at ERROR, preserving its failure payload. */
    private static final String TERMINAL_FAILURE_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <key id="failureRoute" for="edge" attr.name="failure.route" attr.type="string"/>
              <graph id="terminal-failure" edgedefault="directed">
                <node id="start"><data key="kind">START</data></node>
                <node id="producer"><data key="kind">BEHAVIOR</data><data key="behavior">producer-behavior</data></node>
                <node id="boom"><data key="kind">BEHAVIOR</data><data key="behavior">terminal-boom-behavior</data></node>
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="producer"><data key="outcome">continue</data></edge>
                <edge id="e2" source="producer" target="boom"><data key="outcome">continue</data></edge>
                <edge id="e3" source="boom" target="end"><data key="outcome">continue</data></edge>
                <edge id="e4" source="boom" target="error"><data key="failureRoute">true</data></edge>
              </graph>
            </graphml>
            """;

    /**
     * A two-way branch submitted over HTTP: {@code gate}'s two edges carry only custom outcomes
     * ({@code approved}/{@code rejected}), neither named {@code continue}. Under the default
     * {@code mode=test} submission (no {@code mode} parameter, same as {@link #EXECUTABLE_GRAPH}
     * above) every node is bypassed and {@code gate}'s own outcome is hardcoded to {@code
     * "continue"}, so neither edge is ever a routing candidate -- the engine-level proof is {@code
     * NodeCommandRoutingTest#untakenEdgesUnderBypassAreNamedInTheResult}; this is the same shape
     * carried across the wire.
     */
    private static final String BRANCH_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="joinQuorum" for="node" attr.name="joinQuorum" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="branch-test" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="gate"><data key="kind">BEHAVIOR</data><data key="behavior">gate-behavior</data></node>
                <node id="approvedNode"><data key="kind">BEHAVIOR</data><data key="behavior">approved-behavior</data></node>
                <node id="rejectedNode"><data key="kind">BEHAVIOR</data><data key="behavior">rejected-behavior</data></node>
                <node id="end"><data key="kind">END</data><data key="joinQuorum">1</data></node>
                <edge id="e1" source="start" target="gate"><data key="outcome">continue</data></edge>
                <edge id="e2" source="gate" target="approvedNode"><data key="outcome">approved</data></edge>
                <edge id="e3" source="gate" target="rejectedNode"><data key="outcome">rejected</data></edge>
                <edge id="e4" source="approvedNode" target="end"><data key="outcome">continue</data></edge>
                <edge id="e5" source="rejectedNode" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    @Test
    void exposesHealthStatusAndRuntimeWithoutSpring() throws Exception {
        try (var engine = new PekkoExecutionEngine("ravenroot-server-test");
             var server = testServer(new DefaultRavenrootApplication(engine, new ExecutionMonitor()), null)) {
            server.start();
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/v1/status")).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("apache-pekko"));

            var catalog = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + server.port() + "/v1/node-types")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, catalog.statusCode());
            assertTrue(catalog.body().contains("\"behavior\":\"cel-transform\""));
            // Asserted on the wire and not on the registry: this is the route an installation actually
            // reads, and the defect is that a
            // distribution offered two node types nobody installing it could arm. Both halves are
            // needed -- the absence of the names, and the absence of any agentic declaration -- since
            // a node type could return under a different name and the same posture.
            assertFalse(catalog.body().contains("\"behavior\":\"llm-prompt\""), catalog.body());
            assertFalse(catalog.body().contains("\"behavior\":\"agent\""), catalog.body());
            assertFalse(catalog.body().contains("\"agentic\":true"), catalog.body());
            // ANTI-VACUITY for the three lines above: they are absences, and an absence over an empty
            // or unparsed body is not an assertion. The cel-transform line above and this one pin
            // that the catalog was really served and really read.
            assertTrue(catalog.body().contains("\"agentic\":false"), catalog.body());
            // The nature contract the Inspector renders from. Effective values, so an editor
            // never has to know what "absent" means; every built-in says nothing about nature and
            // therefore publishes the fail-closed default, which is what makes the editor offer no
            // escalation the server would then refuse.
            assertTrue(catalog.body().contains("\"defaultNature\":\"WORKER\""), catalog.body());
            assertTrue(catalog.body().contains("\"allowedNatures\":[\"WORKER\"]"), catalog.body());
            assertTrue(catalog.body().contains("\"natureProperty\":\"runtime.nature\""), catalog.body());
            assertTrue(catalog.body().contains("\"allowedNatures\":[\"WORKER\",\"TRAVERSAL\"]"),
                    "Programmer must publish the declared lifecycle choices: " + catalog.body());
            assertTrue(catalog.body().contains("\"maxConcurrencyProperty\":\"runtime.maxConcurrency\""),
                    catalog.body());
            assertTrue(catalog.body().contains("\"defaultMaxConcurrency\":64"), catalog.body());
            assertTrue(catalog.body().contains("\"maxConcurrencyCeiling\":256"), catalog.body());
            assertFalse(catalog.body().contains("\"allowedNatures\":[]"),
                    "an empty allowed set would read as 'anything' to a permissive consumer");
            // The outcome declaration the edge inspector suggests from. The DECLARATION reaches
            // the wire, not a resolved set -- cel-decision's two outcomes are named by node properties,
            // so the editor resolves them per node and the catalog carries the property references.
            assertTrue(catalog.body().contains("\"fromProperty\":\"trueOutcome\""), catalog.body());
            assertTrue(catalog.body().contains("\"fromProperty\":\"falseOutcome\""), catalog.body());
            assertTrue(catalog.body().contains("\"fromProperty\":\"successOutcome\""), catalog.body());
            assertTrue(catalog.body().contains("\"name\":\"continue\",\"fromProperty\":\"\""),
                    "a behavior that hard-wires its outcome publishes it as a fixed name: " + catalog.body());
            assertFalse(catalog.body().contains("\"outcomes\":[]"),
                    "every built-in declares at least one outcome, so an empty array means one was "
                            + "dropped between the descriptor and the wire: " + catalog.body());

            var deniedArtifact = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/v1/program-artifacts"))
                    .POST(HttpRequest.BodyPublishers.ofString("({ payload }) => payload")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, deniedArtifact.statusCode());
        }
    }

    /**
     * {@code GET /v1/program-languages} reads whatever the composed {@code ProgramRuntime}
     * declares -- proven here with a fake runtime declaring three languages, one of which
     * ("ruby") ships with neither {@code GraalVmProgramRuntime} nor the editor. Nothing about the
     * route, {@code DefaultRavenrootApplication} or {@code AuthorizedRavenrootApplication} names a
     * language: if this test only passed for javascript and python, that would mean the list was
     * hard-coded somewhere on this path after all.
     */
    @Test
    void exposesProgramLanguagesFromTheComposedRuntimeWithoutNamingAnyOfThem() throws Exception {
        var runtime = new ThreeLanguageRuntime();
        try (var server = testServer(new DefaultRavenrootApplication(null, new ExecutionMonitor(),
                new BehaviorRegistry(), new InMemoryArtifactRegistry(), runtime), null)) {
            server.start();
            var client = HttpClient.newHttpClient();
            var response = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/v1/program-languages")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"id\":\"javascript\""), response.body());
            assertTrue(response.body().contains("\"id\":\"python\""), response.body());
            assertTrue(response.body().contains("\"id\":\"ruby\""), response.body());
            assertTrue(response.body().contains("\"displayName\":\"Ruby\""), response.body());
            // A multi-line starter travels through the same JSON string escaping every other field
            // on this response already uses -- \n, not a raw newline that would break the envelope.
            assertTrue(response.body().contains("def call(payload)\\n  payload\\nend"), response.body());
            assertFalse(response.body().contains("\r"), "no raw control byte reaches the wire");
        }
    }

    private static final class ThreeLanguageRuntime implements ProgramRuntime {
        @Override public String id() { return "three-language-test-runtime"; }
        @Override public java.util.List<ProgramLanguageDescriptor> supportedLanguages() {
            return java.util.List.of(
                    new ProgramLanguageDescriptor("javascript", "JavaScript",
                            "({ payload }) => ({ value: payload })"),
                    new ProgramLanguageDescriptor("python", "Python",
                            "def handler(request):\n    return {'value': request.payload}\nhandler"),
                    new ProgramLanguageDescriptor("ruby", "Ruby", "def call(payload)\n  payload\nend"));
        }
        @Override public java.util.concurrent.CompletionStage<Object> execute(ProgramAdmission admission,
                                                                               ProgramRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("not exercised"));
        }
    }

    @Test
    void servesUiFromTheSameOriginAndKeepsUnknownApiRoutesOutOfTheSpaFallback() throws Exception {
        Files.writeString(uiDirectory.resolve("index.html"), "<html><body>Ravenroot UI</body></html>");
        Files.createDirectory(uiDirectory.resolve("assets"));
        Files.writeString(uiDirectory.resolve("assets/app.js"), "export const ready = true;");
        Files.writeString(uiDirectory.resolve("embed-viewer.js"), "export const viewer = true;");
        Files.writeString(uiDirectory.resolve("embed-viewer.css"), ".embed-viewer{}");

        try (var engine = new PekkoExecutionEngine("ravenroot-server-ui-test");
             var server = testServer(
                     new DefaultRavenrootApplication(engine, new ExecutionMonitor()), uiDirectory)) {
            server.start();
            var client = HttpClient.newHttpClient();

            var page = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + server.port() + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("Ravenroot UI"));
            assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
            assertEquals("no-referrer", page.headers().firstValue("Referrer-Policy").orElseThrow());
            assertEquals("DENY", page.headers().firstValue("X-Frame-Options").orElseThrow());

            var asset = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + server.port() + "/assets/app.js")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, asset.statusCode());
            assertTrue(asset.headers().firstValue("Cache-Control").orElse("").contains("immutable"));

            for (String stableEmbedAsset : java.util.List.of("embed-viewer.js", "embed-viewer.css")) {
                var embedAsset = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.port() + "/" + stableEmbedAsset)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, embedAsset.statusCode());
                assertEquals("no-cache", embedAsset.headers().firstValue("Cache-Control").orElseThrow());
            }

            var missingApi = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + server.port() + "/v1/not-a-route")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missingApi.statusCode());
            assertEquals("DENY", missingApi.headers().firstValue("X-Frame-Options").orElseThrow());
            assertEquals("nosniff", missingApi.headers().firstValue("X-Content-Type-Options").orElseThrow());
            assertTrue(missingApi.headers().firstValue("Content-Security-Policy").orElseThrow()
                    .contains("frame-ancestors 'none'"));
            assertTrue(RavenrootHealthcheck.isHealthy(server.port()));
        }
    }

    @Test
    void graphExecutionLimitRefusalUsesAClosedPayloadFreeHttpCode() throws Exception {
        try (var engine = new PekkoExecutionEngine("ravenroot-server-graph-limit-test")) {
            var delegate = new DefaultRavenrootApplication(engine, new ExecutionMonitor());
            var application = new ForwardingRavenrootApplication(delegate) {
                @Override
                public ai.ravenroot.api.application.ExecutionSubmission startGraphMl(
                        ai.ravenroot.api.security.SecurityContext security, UUID executionId,
                        java.io.InputStream graphMl, Object payload,
                        ai.ravenroot.api.application.ExecutionPolicy policy) {
                    throw new GraphExecutionLimitException(
                            GraphExecutionLimitException.Reason.FAN_OUT, 99, 64);
                }
            };
            try (var server = testServer(application, null)) {
                server.start();
                HttpResponse<String> response = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/v1/executions"))
                                .POST(HttpRequest.BodyPublishers.ofString(EXECUTABLE_GRAPH)).build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(413, response.statusCode());
                assertTrue(response.body().contains("\"code\":\"GRAPH_LIMIT_FAN_OUT_EXCEEDED\""),
                        response.body());
                assertFalse(response.body().contains("99"), response.body());
                assertFalse(response.body().contains("64"), response.body());
            }
        }
    }

    @Test
    void enforcesExactBrowserOriginsAndStrictPreflightBeforeAuthentication() throws Exception {
        var authenticationCalls = new AtomicInteger();
        var authenticator = (ai.ravenroot.server.security.RequestAuthenticator) headers -> {
            authenticationCalls.incrementAndGet();
            return principal(Instant.MAX);
        };
        var httpSecurity = new HttpSecurityConfiguration(
                new BrowserOriginPolicy(Set.of("https://console.example")),
                new SecurityHeadersPolicy(false), Duration.ofSeconds(1));
        try (var engine = new PekkoExecutionEngine("ravenroot-server-origin-test");
             var server = new RavenrootServer(new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                     new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, false,
                     authenticator, httpSecurity)) {
            server.start();
            var client = HttpClient.newHttpClient();
            String status = "http://localhost:" + server.port() + "/v1/status";

            var rejected = client.send(HttpRequest.newBuilder(URI.create(status))
                            .header("Origin", "https://evil.example").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, rejected.statusCode());
            assertEquals(0, authenticationCalls.get());

            var preflight = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/v1/executions"))
                            .header("Origin", "https://console.example")
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "Authorization, Content-Type")
                            .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(204, preflight.statusCode());
            assertEquals("https://console.example",
                    preflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
            assertEquals(0, authenticationCalls.get());

            var forbiddenHeader = client.send(HttpRequest.newBuilder(URI.create(status))
                            .header("Origin", "https://console.example")
                            .header("Access-Control-Request-Method", "GET")
                            .header("Access-Control-Request-Headers", "X-Unsafe")
                            .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, forbiddenHeader.statusCode());
            assertEquals(0, authenticationCalls.get());

            var accepted = client.send(HttpRequest.newBuilder(URI.create(status))
                            .header("Origin", "https://console.example").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, accepted.statusCode());
            assertEquals("https://console.example",
                    accepted.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
            assertEquals(1, authenticationCalls.get());
        }
    }

    @Test
    void terminatesAnEventStreamWhenItsAuthenticationLeaseExpires() throws Exception {
        var authenticationCalls = new AtomicInteger();
        Instant authenticatedAt = Instant.parse("2030-01-01T00:00:00Z");
        Instant expiresAt = authenticatedAt.plusMillis(25);
        var authenticator = (ai.ravenroot.server.security.RequestAuthenticator) headers -> {
            authenticationCalls.incrementAndGet();
            return principal(expiresAt);
        };
        var httpSecurity = new HttpSecurityConfiguration(
                new BrowserOriginPolicy(Set.of("http://localhost:8080")),
                new SecurityHeadersPolicy(false), Duration.ofSeconds(1));
        try (var engine = new PekkoExecutionEngine("ravenroot-server-sse-lease-test");
             var server = new RavenrootServer(new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                     new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, false,
                     authenticator, httpSecurity,
                     new ExpiringLeaseClock(authenticatedAt, expiresAt));
             var readerExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.start();
            var client = HttpClient.newHttpClient();
            var stream = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/v1/events"))
                            .header("Authorization", "Bearer opaque").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, stream.statusCode());
            var ended = readerExecutor.submit(() -> {
                try (var input = stream.body()) {
                    return input.readAllBytes().length;
                }
            });
            ended.get(3, TimeUnit.SECONDS);
            assertEquals(1, authenticationCalls.get(),
                    "hard expiry must close before authenticator/JWKS revalidation");
        }
    }

    /**
     * Built here with {@code new DefaultRavenrootApplication(engine, new ExecutionMonitor())} - no
     * {@code ExecutionStore} - so {@code durableEventJournalAvailable()} is false and this exercises
     * {@code RavenrootServer.streamInMemoryExecutionEvents}, the pre-API-03 in-memory path, which does
     * push {@code EXECUTION_COMPLETED}. A server composed with a durable store (the default for
     * {@code ravenroot.jar}, and every real deployment) dispatches instead to {@code
     * streamDurableExecutionEvents}, whose journal carries no traversal-terminal frame by design;
     * see that method's Javadoc). Do not generalise this test's expectation to the durable path - see
     * {@code RavenrootExecutionLifecycleIT} in {@code ravenroot-distribution} for the pinned contrast.
     */
    @Test
    void acceptsGraphExecutionAndPushesCorrelatedSseEvents() throws Exception {
        try (var engine = new PekkoExecutionEngine("ravenroot-server-events-test");
             var server = testServer(new DefaultRavenrootApplication(engine, new ExecutionMonitor()), null);
             var readerExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.start();
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            var streamRequest = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + server.port() + "/v1/events")).GET().build();
            var streamResponse = client.send(streamRequest, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, streamResponse.statusCode());

            var eventText = readerExecutor.submit(() -> {
                var collected = new StringBuilder();
                try (var reader = new BufferedReader(new InputStreamReader(streamResponse.body()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        collected.append(line).append('\n');
                        if (collected.toString().contains("EXECUTION_COMPLETED")) {
                            return collected.toString();
                        }
                    }
                }
                return collected.toString();
            });

            var executeRequest = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/v1/executions?payload=hello"))
                    .header("Content-Type", "application/graphml+xml")
                    .POST(HttpRequest.BodyPublishers.ofString(EXECUTABLE_GRAPH))
                    .build();
            var executeResponse = client.send(executeRequest, HttpResponse.BodyHandlers.ofString());

            assertEquals(202, executeResponse.statusCode());
            assertTrue(executeResponse.body().contains("processInstanceId"));
            assertTrue(executeResponse.body().contains("traversalId"));
            assertEquals(jsonString(executeResponse.body(), "traversalId"),
                    jsonString(executeResponse.body(), "executionId"));
            String events = eventText.get(5, TimeUnit.SECONDS);
            assertTrue(events.contains("NODE_STARTED"));
            assertTrue(events.contains("NODE_BYPASSED"));
            assertTrue(events.contains("EXECUTION_COMPLETED"));
            assertTrue(events.contains("graphVersion"));
            assertTrue(events.contains("\"processInstanceId\""));
            assertTrue(events.contains("\"traversalId\""));
            assertTrue(events.contains("\"attemptId\""));
            assertTrue(events.contains("\"processingDuration\":"));
            assertTrue(events.matches("(?s).*\"processingDuration\":(?!null)[0-9.Ee+\\-]+.*"), events);
            assertTrue(events.contains("\"description\":\"Execution started.\""), events);
            // The sentence says which bypass occurred. The graph here is executed in play/test mode,
            // so the classifier is the command one
            // and the sentence is the traversal-level explanation. The bare sentence is still what
            // PublicExecutionDescription.forType(type) alone produces -- durable replay, which never
            // captured a classifier, still gets it -- but a live SSE frame now carries the classifier
            // and therefore the fuller text. The next assertion still holds and is the reason this one
            // could be strengthened at all: the classifier is a restricted token, not the diagnostic.
            assertTrue(events.contains(
                    "\"description\":\"Node was bypassed: the traversal was not executing node behaviours.\""),
                    events);
            assertFalse(events.contains("execution accepted"),
                    "the internal diagnostic must not cross the SSE boundary: " + events);
            assertFalse(events.contains("incoming command=passthrough"),
                    "graph/runtime diagnostic text must not cross the SSE boundary: " + events);
            // The event carries tenant and request identity internally, but the browser-facing
            // SSE frame discloses neither. Tenant naming is server-side knowledge, and the correlation
            // id belongs in the audit log rather than in a payload a client could echo back.
            assertFalse(events.contains("\"tenantId\""),
                    "the SSE frame must not disclose tenant naming to a browser client: " + events);
            assertFalse(events.contains("\"requestId\""),
                    "the SSE frame must not disclose the audit correlation id: " + events);
        }
    }

    @Test
    void playEnforcesTestPassthroughWithoutProductionEffectsOrDeploymentMutation() throws Exception {
        var productionCalls = new AtomicInteger();
        var registry = new ai.ravenroot.core.runtime.BehaviorRegistry().register("production-effect", message -> {
            productionCalls.incrementAndGet();
            return CompletableFuture.completedFuture(
                    ai.ravenroot.api.execution.NodeResult.continueWith("production-output"));
        });
        var monitor = new ExecutionMonitor();
        var graph = EXECUTABLE_GRAPH.replace("future-behavior", "production-effect");
        try (var engine = new PekkoExecutionEngine("ravenroot-server-test-passthrough");
             var application = new DefaultRavenrootApplication(engine, monitor, registry);
             var server = testServer(application, null)) {
            server.start();
            var client = HttpClient.newHttpClient();

            var first = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/v1/executions?payload=original"))
                    .header("Content-Type", "application/graphml+xml")
                    .POST(HttpRequest.BodyPublishers.ofString(graph)).build(),
                    HttpResponse.BodyHandlers.ofString());
            var second = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/v1/executions?payload=original"))
                    .header("Content-Type", "application/graphml+xml")
                    .POST(HttpRequest.BodyPublishers.ofString(graph)).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(202, first.statusCode());
            assertEquals(202, second.statusCode());
            assertFalse(jsonString(first.body(), "executionId").equals(jsonString(second.body(), "executionId")),
                    "each Play submission needs its own execution identity");
            assertEquals(jsonString(first.body(), "graphVersion"), jsonString(second.body(), "graphVersion"),
                    "the same immutable snapshot needs the same content identity");
            awaitTerminal(monitor, jsonString(first.body(), "executionId"));
            awaitTerminal(monitor, jsonString(second.body(), "executionId"));
            assertEquals(0, productionCalls.get(), "Play invoked the registered production behavior");
            assertTrue(application.deployment(ai.ravenroot.api.deployment.DeploymentId.of("play")).isEmpty(),
                    "Play must not create deployment inventory or desired state");
        }
    }

    @Test
    void runModeExecutesRegisteredProductionBehaviorWithoutPretendingToBeADeployment() throws Exception {
        var productionCalls = new AtomicInteger();
        var registry = new ai.ravenroot.core.runtime.BehaviorRegistry().register("production-effect", message -> {
            productionCalls.incrementAndGet();
            return CompletableFuture.completedFuture(
                    ai.ravenroot.api.execution.NodeResult.continueWith("production-output"));
        });
        var monitor = new ExecutionMonitor();
        var graph = EXECUTABLE_GRAPH.replace("future-behavior", "production-effect");
        try (var engine = new PekkoExecutionEngine("ravenroot-server-standard-run");
             var application = new DefaultRavenrootApplication(engine, monitor, registry);
             var server = testServer(application, null)) {
            server.start();
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port()
                                    + "/v1/executions?mode=run&payload=original"))
                    .header("Content-Type", "application/graphml+xml")
                    .POST(HttpRequest.BodyPublishers.ofString(graph)).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(202, response.statusCode(), response.body());
            assertTrue(response.body().contains("\"executionPolicy\":\"STANDARD\""), response.body());
            awaitTerminal(monitor, jsonString(response.body(), "executionId"));
            assertEquals(1, productionCalls.get(), "Run did not invoke the registered production behavior");
            assertTrue(application.deployment(ai.ravenroot.api.deployment.DeploymentId.of("run")).isEmpty(),
                    "transient Run must not claim durable deployment lifecycle");
        }
    }

    private static void awaitTerminal(ExecutionMonitor monitor, String executionId) throws Exception {
        UUID id = UUID.fromString(executionId);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (monitor.eventsAfter(0).stream().anyMatch(event -> id.equals(event.executionId())
                    && (event.type() == ai.ravenroot.api.application.ExecutionEventType.EXECUTION_COMPLETED
                    || event.type() == ai.ravenroot.api.application.ExecutionEventType.EXECUTION_FAILED))) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("execution did not finish: " + executionId);
    }

    @Test
    void rejectsUnsafeGraphMlConsistentlyForInspectAndExecution() throws Exception {
        String unsafe = """
                <?xml version="1.0"?>
                <!DOCTYPE graphml [<!ENTITY xxe SYSTEM "https://invalid.example/secret">]>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <graph id="unsafe" edgedefault="directed"><node id="n">&xxe;</node></graph>
                </graphml>
                """;
        var monitor = new ExecutionMonitor();
        try (var engine = new PekkoExecutionEngine("ravenroot-server-parser-security-test");
             var server = testServer(new DefaultRavenrootApplication(engine, monitor), null)) {
            server.start();
            var client = HttpClient.newHttpClient();
            for (String path : new String[]{"/v1/graphs/inspect", "/v1/executions"}) {
                var response = client.send(HttpRequest.newBuilder(
                                URI.create("http://localhost:" + server.port() + path))
                        .POST(HttpRequest.BodyPublishers.ofString(unsafe)).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(400, response.statusCode());
                assertTrue(response.body().contains("\"code\":\"GRAPHML_UNSAFE_XML\""));
                assertTrue(!response.body().contains("invalid.example"));
            }
            assertTrue(monitor.eventsAfter(0).isEmpty());
        }
    }

    /**
     * {@code POST /v1/graphs/inspect} must distinguish a sound graph from an invalid one rather than
     * returning the same four counts for both. This is the positive case:
     * a document {@code startGraphMl} would also accept reports {@code "valid":true} and an empty
     * violation array alongside its counts.
     */
    @Test
    void inspectGraphReportsValidityInTheHttpResponse() throws Exception {
        try (var engine = new PekkoExecutionEngine("ravenroot-server-inspect-validity-test");
             var server = testServer(new DefaultRavenrootApplication(engine, new ExecutionMonitor()), null)) {
            server.start();
            var client = HttpClient.newHttpClient();
            var response = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/v1/graphs/inspect"))
                    .POST(HttpRequest.BodyPublishers.ofString(EXECUTABLE_GRAPH)).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.body().contains("\"nodes\":4"), response.body());
            assertTrue(response.body().contains("\"startNodes\":1"), response.body());
            assertTrue(response.body().contains("\"valid\":true"), response.body());
            assertTrue(response.body().contains("\"violations\":[]"), response.body());
        }
    }

    /** The negative case remains a 200 because inspecting is not refusing. */
    @Test
    void inspectGraphNamesASemanticViolationInTheHttpResponse() throws Exception {
        String unknownKind = EXECUTABLE_GRAPH.replace(">ERROR<", ">SUBGRAPH<");
        try (var engine = new PekkoExecutionEngine("ravenroot-server-inspect-invalidity-test");
             var server = testServer(new DefaultRavenrootApplication(engine, new ExecutionMonitor()), null)) {
            server.start();
            var client = HttpClient.newHttpClient();
            var response = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/v1/graphs/inspect"))
                    .POST(HttpRequest.BodyPublishers.ofString(unknownKind)).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode(), response.body());
            // Still inspectable: the counts are unchanged from the valid case.
            assertTrue(response.body().contains("\"nodes\":4"), response.body());
            assertTrue(response.body().contains("\"valid\":false"), response.body());
            assertTrue(response.body().contains(
                    "Node 'error' declares an unknown kind 'SUBGRAPH'"), response.body());
        }
    }

    /**
     * The compatibility layer is answered like the security layer, and returns nothing of the
     * document (FIX-03).
     *
     * <p>Both halves matter. Letting a property-graph refusal fall through to the generic
     * {@code IllegalArgumentException} branch would omit the {@code code} and echo a message containing
     * submitted content. Both security and compatibility refusals must expose the same bounded shape.</p>
     */
    @Test
    void refusesAHostileCompatibilityDocumentWithoutReturningAnyOfIt() throws Exception {
        String marker = "pwned-7f3a91c2";
        String hostile = """
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="k0" for="node" attr.name="p" attr.type="%s"/>
                  <graph id="g" edgedefault="directed"><node id="n0"/></graph>
                </graphml>
                """.formatted(marker);

        var audit = new java.io.ByteArrayOutputStream();
        PrintStream original = System.out;
        // The rejection sink is bound to System.out when the server is constructed, as its siblings
        // are, so the capture has to be installed first.
        System.setOut(new PrintStream(audit, true, StandardCharsets.UTF_8));
        try (var engine = new PekkoExecutionEngine("ravenroot-server-graphml-sanitisation-test");
             var server = testServer(new DefaultRavenrootApplication(engine, new ExecutionMonitor()), null)) {
            server.start();
            var client = HttpClient.newHttpClient();
            for (String path : new String[]{"/v1/graphs/inspect", "/v1/executions"}) {
                var response = client.send(HttpRequest.newBuilder(
                                URI.create("http://localhost:" + server.port() + path))
                        .POST(HttpRequest.BodyPublishers.ofString(hostile)).build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(400, response.statusCode());
                assertFalse(response.body().contains(marker),
                        "the response returned submitted content: " + response.body());
                assertTrue(response.body().contains("\"code\":\"GRAPHML_INVALID_GRAPH\""),
                        response.body());
                assertTrue(response.body().contains("\"incidentId\":\""), response.body());

                // The detail is diagnosable server-side, joined to the response by the
                // incident handle the caller was given.
                String incidentId = response.body().replaceAll(
                        "(?s).*\"incidentId\":\"([0-9a-f]+)\".*", "$1");
                String recorded = audit.toString(StandardCharsets.UTF_8);
                assertTrue(recorded.contains(incidentId), "no audit record for " + incidentId);
                assertTrue(recorded.contains(marker),
                        "the detail did not reach the server-side record, so it was lost rather than moved");
            }
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void enforcesTheSameByteBudgetOnInspectAndExecution() throws Exception {
        String oversized = " ".repeat(10 * 1024 * 1024 + 1);
        try (var engine = new PekkoExecutionEngine("ravenroot-server-parser-limit-test");
             var server = testServer(new DefaultRavenrootApplication(engine, new ExecutionMonitor()), null)) {
            server.start();
            var client = HttpClient.newHttpClient();
            for (String path : new String[]{"/v1/graphs/inspect", "/v1/executions"}) {
                var response = client.send(HttpRequest.newBuilder(
                                URI.create("http://localhost:" + server.port() + path))
                        .POST(HttpRequest.BodyPublishers.ofString(oversized)).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(413, response.statusCode());
                assertTrue(response.body().contains("\"code\":\"GRAPHML_DOCUMENT_TOO_LARGE\""));
            }
        }
    }

    @Test
    void exposesTheExplicitDevelopmentArtifactLifecycleWithoutReturningSource() throws Exception {
        var runtime = new ProgramRuntime() {
            @Override
            public String id() {
                return "test-program-runtime";
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> validate(GeneratedArtifact artifact) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public java.util.concurrent.CompletionStage<Object> test(GeneratedArtifact artifact,
                                                                      ProgramRequest request) {
                return CompletableFuture.completedFuture(Map.of("echo", request.payload()));
            }

            @Override
            public java.util.concurrent.CompletionStage<Object> execute(
                    ai.ravenroot.api.programming.ProgramAdmission admission, ProgramRequest request) {
                return CompletableFuture.completedFuture(request.payload());
            }
        };
        var artifacts = new InMemoryArtifactRegistry();
        var environment = new BehaviorEnvironment(new ModelProviderRegistry(), new AgentRuntimeRegistry(), artifacts,
                runtime, ignored -> java.util.Optional.empty(),
                ignored -> new ToolDecision(ToolDecision.Disposition.ALLOW, "test", ""),
                OutboundHttpPolicy.disabled());
        try (var engine = new PekkoExecutionEngine("ravenroot-artifact-api-test");
             var server = testServer(
                     new DefaultRavenrootApplication(engine, new ExecutionMonitor(), environment), null,
                     lifecycleAuthenticator())) {
            server.start();
            var client = HttpClient.newHttpClient();
            String base = "http://localhost:" + server.port() + "/v1/program-artifacts";
            String source = "({ payload }) => payload";

            var created = client.send(HttpRequest.newBuilder(URI.create(base + "?language=javascript&name=echo"))
                    .header("Authorization", "Bearer creator")
                    .POST(HttpRequest.BodyPublishers.ofString(source)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, created.statusCode());
            assertTrue(!created.body().contains(source));
            String id = created.body().split("\"id\":\"")[1].split("\"")[0];

            assertEquals(200, post(client, base + "/" + id + "/validate", "creator").statusCode());
            var tested = post(client, base + "/" + id + "/test?payload=hello", "creator");
            assertEquals(200, tested.statusCode());
            assertTrue(tested.body().contains("\"echo\":\"hello\""));
            assertEquals(403, post(client, base + "/" + id + "/approve?reason=self", "creator").statusCode());
            assertEquals(200, post(client, base + "/" + id + "/approve?reason=reviewed", "approver").statusCode());
            assertEquals(200, post(client, base + "/" + id + "/activate", "approver").statusCode());
            assertEquals(200, post(client, base + "/" + id + "/retire?reason=superseded", "approver").statusCode());

            var listed = client.send(HttpRequest.newBuilder(URI.create(base)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, listed.statusCode());
            assertTrue(listed.body().contains("\"state\":\"RETIRED\""));
            assertTrue(!listed.body().contains(source));
        }
    }

    /**
     * Proof that the product produces an observable result.
     *
     * <p>Submit a graph, wait, read the result by execution id, and assert a non-empty payload. Until
     * this test existed a graph could run to completion and no caller could observe that it had
     * produced anything: {@code POST /v1/executions} answered 202 with identifiers, the result was
     * computed inside the process and discarded, and there was no read by execution id at all.</p>
     *
     * <p>Submitted without a {@code mode} parameter, so the route's default --
     * {@code ExecutionPolicy.TEST_PASSTHROUGH} -- delivers {@code NodeCommand.PASSTHROUGH} to
     * every node, including {@code EXECUTABLE_GRAPH}'s middle one. That node is therefore reported as
     * <em>bypassed</em>, not defaulted: {@code defaultedNodes} comes back empty and {@code degraded}
     * is {@code false} below, on purpose: intentional bypass is reported separately from
     * unknown-behavior defaulting. The assertions below prove that separation directly. This test
     * proves the read-by-id plumbing and the result's field
     * shape, not that {@code defaultedNodes} reveals degradation; {@link
     * DefaultedNodeDurableCountHttpTest} submits with {@code mode=run} against an unregistered
     * behavior and is the one that actually exercises that half.</p>
     */
    @Test
    void readsAnExecutionResultByIdAndReportsBothItsPayloadAndItsDefaultedNodes() throws Exception {
        try (var engine = new PekkoExecutionEngine("ravenroot-server-result-test");
             var server = testServer(new DefaultRavenrootApplication(engine, new ExecutionMonitor()), null)) {
            server.start();
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            var submitted = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/v1/executions?payload=hello"))
                    .header("Content-Type", "application/graphml+xml")
                    .POST(HttpRequest.BodyPublishers.ofString(EXECUTABLE_GRAPH)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, submitted.statusCode(), "submission must stay 202 and asynchronous");
            String executionId = jsonString(submitted.body(), "executionId");

            // Polled slowly and a bounded number of times on purpose. A tight loop is not merely
            // wasteful here: /v1/executions/{id} is rate limited like every other API route, so a
            // fast poll turns "the result never arrived" into a 429 and the test would then fail for
            // a reason that has nothing to do with the result. Twelve requests over six seconds stays
            // well inside the budget, so a non-200 in this loop is a real defect and nothing else.
            String resultUri = "http://localhost:" + server.port() + "/v1/executions/" + executionId;
            HttpResponse<String> result = null;
            for (int attempt = 0; attempt < 12; attempt++) {
                result = client.send(HttpRequest.newBuilder(URI.create(resultUri)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                HttpResponse<String> observed = result;
                assertEquals(200, observed.statusCode(),
                        () -> "an accepted execution must be readable for the whole life of the run, "
                                + "not only after it ends: " + observed.body());
                if (!result.body().contains("\"status\":\"RUNNING\"")) {
                    break;
                }
                Thread.sleep(500);
            }
            HttpResponse<String> settled = result;
            assertFalse(settled.body().contains("\"status\":\"RUNNING\""),
                    () -> "the execution never left RUNNING, so no result was ever recorded: " + settled.body());

            String body = result.body();
            assertEquals("COMPLETED", jsonString(body, "status"), () -> body);
            // The result must expose the produced payload.
            assertTrue(body.contains("\"payload\":"), () -> "the result carries no payload at all: " + body);
            assertEquals("hello", jsonString(body, "payload"), () -> body);
            assertTrue(body.contains("\"visitedNodes\":[\"end\",\"future\",\"start\"]"), () -> body);
            assertTrue(body.contains("\"defaultedNodes\":[]"), () -> body);
            assertTrue(body.contains("\"bypassedNodes\":[\"end\",\"future\",\"start\"]"),
                    () -> "Play/Test must report intentional bypass separately from unknown-behavior "
                            + "defaulting: " + body);
            assertTrue(body.contains("\"degraded\":false"), () -> body);
            assertTrue(body.contains("\"handledFailure\":false"),
                    () -> "handled-failure fields are present on every result, not only on failing ones -- an "
                            + "absent field is indistinguishable from a server that cannot report "
                            + "handled failures: " + body);
            assertTrue(body.contains("\"handledFailureNodes\":[]"), () -> body);
            // The fifth field follows the same always-present rule, for the same reason -- an
            // absent field would be indistinguishable from a server that does not report it at all.
            assertTrue(body.contains("\"untakenEdges\":[]"),
                    () -> "EXECUTABLE_GRAPH has no branch behind a bypassed node, so this must be "
                            + "empty, not absent: " + body);
            assertEquals(executionId, jsonString(body, "executionId"));
        }
    }

    /**
     * At the HTTP boundary, {@code NodeCommandRoutingTest} already proves {@code untakenEdges}
     * populates at the engine level, and {@code readsAnExecutionResultByIdAndReportsBothItsPayloadAnd
     * ItsDefaultedNodes} above proves the field is present and correctly empty over the wire. Neither
     * proves the non-empty case survives {@code ExecutionResultRegistry}, {@code ExecutionOutcome} and
     * JSON serialisation intact -- this is that proof, submitted exactly as an author would from the
     * editor: a GraphML document over {@code POST /v1/executions} with no {@code mode} parameter,
     * which is the {@code TEST_PASSTHROUGH} default both this test and {@code EXECUTABLE_GRAPH}'s own
     * test rely on.
     */
    @Test
    void reportsUntakenEdgesInTheResultJsonForABranchBehindOnlyCustomOutcomes() throws Exception {
        try (var engine = new PekkoExecutionEngine("ravenroot-server-untaken-edges-test");
             var server = testServer(new DefaultRavenrootApplication(engine, new ExecutionMonitor()), null)) {
            server.start();
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            var submitted = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/v1/executions?payload=hello"))
                    .header("Content-Type", "application/graphml+xml")
                    .POST(HttpRequest.BodyPublishers.ofString(BRANCH_GRAPH)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, submitted.statusCode(), "submission must stay 202 and asynchronous");
            String executionId = jsonString(submitted.body(), "executionId");

            String resultUri = "http://localhost:" + server.port() + "/v1/executions/" + executionId;
            HttpResponse<String> result = null;
            for (int attempt = 0; attempt < 12; attempt++) {
                result = client.send(HttpRequest.newBuilder(URI.create(resultUri)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                HttpResponse<String> observed = result;
                assertEquals(200, observed.statusCode(), () -> observed.body());
                if (!result.body().contains("\"status\":\"RUNNING\"")) {
                    break;
                }
                Thread.sleep(500);
            }
            String body = result.body();
            assertEquals("COMPLETED", jsonString(body, "status"), () -> body);
            // gate's own hardcoded "continue" outcome dead-ends before either custom-outcome edge is
            // a candidate, so only the dorsale (start, gate) is ever bypassed -- approvedNode,
            // rejectedNode and end are never reached, exactly like the engine-level proof.
            assertTrue(body.contains("\"bypassedNodes\":[\"gate\",\"start\"]"), () -> body);
            // stringArrayJson sorts, and "gate->approvedNode..." sorts before "gate->rejectedNode..."
            assertTrue(body.contains("\"untakenEdges\":[\"gate->approvedNode [outcome=approved]\","
                    + "\"gate->rejectedNode [outcome=rejected]\"]"),
                    () -> "both untaken edges must be named in the response JSON, exactly: " + body);
        }
    }

    /**
     * The wire boundary must expose handled failures. The engine records them on
     * {@code GraphExecutionResult}, but a caller outside the JVM reads this
     * JSON and nothing else, so a set that stops at {@code ExecutionOutcome} leaves a run that
     * suffered a real fault indistinguishable from a clean one for every consumer that matters.
     *
     * <p>The two runs below are the <em>same graph</em>, submitted to the <em>same server</em>, in
     * {@code mode=run} so the registered behaviours actually execute; only whether {@code boom} throws
     * differs. Both answer {@code COMPLETED}, so status cannot carry the difference — which is the
     * whole premise — and the assertions are therefore made against the two handled-failure fields
     * rather than against "the bodies are unequal", which {@code visitedNodes} alone would satisfy
     * even if the annotation were dropped.</p>
     */
    @Test
    void reportsAHandledFailureInTheResultJsonAndNamesTheNodeThatFailed() throws Exception {
        var failNext = new AtomicBoolean(true);
        var registry = new ai.ravenroot.core.runtime.BehaviorRegistry()
                .register("boom-behavior", message -> failNext.get()
                        ? CompletableFuture.failedFuture(new IllegalStateException("boom exploded"))
                        : CompletableFuture.completedFuture(
                                ai.ravenroot.api.execution.NodeResult.continueWith("from-boom")))
                .register("next-behavior", message -> CompletableFuture.completedFuture(
                        ai.ravenroot.api.execution.NodeResult.continueWith("from-normalNext")))
                .register("handler-behavior", message -> CompletableFuture.completedFuture(
                        ai.ravenroot.api.execution.NodeResult.continueWith("handled")));
        var monitor = new ExecutionMonitor();

        try (var engine = new PekkoExecutionEngine("ravenroot-server-handled-failure");
             var application = new DefaultRavenrootApplication(engine, monitor, registry);
             var server = testServer(application, null)) {
            server.start();
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

            String handled = submitRunAndAwait(client, server, monitor, HANDLED_FAILURE_GRAPH);
            failNext.set(false);
            String clean = submitRunAndAwait(client, server, monitor, HANDLED_FAILURE_GRAPH);

            String handledBody = readResult(client, server, handled);
            String cleanBody = readResult(client, server, clean);

            assertEquals("COMPLETED", jsonString(handledBody, "status"), () -> handledBody);
            assertEquals(jsonString(cleanBody, "status"), jsonString(handledBody, "status"),
                    "both runs must report the same status -- if these ever diverge the annotation is "
                            + "no longer what carries the difference and this test asserts the wrong thing");

            assertTrue(handledBody.contains("\"handledFailure\":true"),
                    () -> "the JSON of a run that survived a node failure must say so: " + handledBody);
            assertTrue(handledBody.contains("\"handledFailureNodes\":[\"boom\"]"),
                    () -> "and must name the node that failed -- boom, never the handler that recovered "
                            + "from it: " + handledBody);

            assertTrue(cleanBody.contains("\"handledFailure\":false"), () -> cleanBody);
            assertTrue(cleanBody.contains("\"handledFailureNodes\":[]"), () -> cleanBody);
            assertFalse(cleanBody.contains("\"handledFailureNodes\":[\"boom\"]"), () -> cleanBody);
        }
    }

    @Test
    void terminalFailurePayloadIsExplicitBoundedJsonAndUnsupportedInputIsAClassifiedResponse() throws Exception {
        var producedInput = new java.util.concurrent.atomic.AtomicReference<Object>("hello");
        var failBoom = new AtomicBoolean(true);
        final class UnsupportedInput {
            @Override
            public String toString() {
                return "secret-input-must-not-leak";
            }
        }
        var registry = new BehaviorRegistry()
                .register("producer-behavior", message -> CompletableFuture.completedFuture(
                        ai.ravenroot.api.execution.NodeResult.continueWith(
                                producedInput.get())))
                .register("terminal-boom-behavior", message -> failBoom.get()
                        ? CompletableFuture.failedFuture(new IllegalStateException("boom exploded"))
                        : CompletableFuture.completedFuture(
                                ai.ravenroot.api.execution.NodeResult.continueWith("clean-result")));
        var monitor = new ExecutionMonitor();

        try (var engine = new PekkoExecutionEngine("ravenroot-server-terminal-failure-payload");
             var application = new DefaultRavenrootApplication(engine, monitor, registry);
             var server = testServer(application, null)) {
            server.start();
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

            String failedId = submitRunAndAwait(client, server, monitor, TERMINAL_FAILURE_GRAPH);
            var failed = readSettledResponse(client, server, failedId);
            assertEquals(200, failed.statusCode(), failed.body());
            assertEquals("COMPLETED", jsonString(failed.body(), "status"), failed.body());
            assertTrue(failed.body().contains("\"payload\":{\"errorClass\":\"java.lang.IllegalStateException\","
                    + "\"input\":\"hello\",\"message\":\"boom exploded\",\"nodeId\":\"boom\"}"),
                    () -> "the terminal payload must use the explicit four-field contract: " + failed.body());
            assertTrue(failed.body().contains("\"handledFailureNodes\":[\"boom\"]"), failed.body());
            assertFalse(failed.body().contains("\"cause\":"), failed.body());
            assertFalse(failed.body().contains("stackTrace"), failed.body());
            assertFalse(failed.body().contains("suppressed"), failed.body());

            producedInput.set(new UnsupportedInput());
            String unsupportedId = submitRunAndAwait(client, server, monitor, TERMINAL_FAILURE_GRAPH);
            var unsupported = readSettledResponse(client, server, unsupportedId);
            assertEquals(400, unsupported.statusCode(), unsupported.body());
            assertTrue(unsupported.headers().firstValue("Content-Type").orElse("")
                    .startsWith("application/json"), unsupported.headers().toString());
            assertTrue(unsupported.body().contains("\"code\":\"PAYLOAD_UNSUPPORTED_TYPE\""), unsupported.body());
            assertFalse(unsupported.body().contains("secret-input-must-not-leak"), unsupported.body());
            ai.ravenroot.api.payload.PayloadJson.read(unsupported.body().getBytes(StandardCharsets.UTF_8),
                    ai.ravenroot.api.payload.PayloadLimits.DEFAULTS);

            var overLimitValues = java.util.Collections.nCopies(
                    ai.ravenroot.api.payload.PayloadLimits.DEFAULTS.maxCollectionSize() + 1,
                    ai.ravenroot.api.payload.PayloadValue.NULL);
            producedInput.set(ai.ravenroot.api.payload.PayloadValue.list(overLimitValues));
            assertEquals(1_001, overLimitValues.size(),
                    "the in-memory PayloadValue must exceed the default collection bound by exactly one");
            String overLimitId = submitRunAndAwait(client, server, monitor, TERMINAL_FAILURE_GRAPH);
            var overLimit = readSettledResponse(client, server, overLimitId);
            assertEquals(413, overLimit.statusCode(), overLimit.body());
            assertTrue(overLimit.headers().firstValue("Content-Type").orElse("")
                    .startsWith("application/json"), overLimit.headers().toString());
            assertTrue(overLimit.body().contains("\"code\":\"PAYLOAD_COLLECTION_LIMIT_EXCEEDED\""),
                    overLimit.body());
            ai.ravenroot.api.payload.PayloadJson.read(overLimit.body().getBytes(StandardCharsets.UTF_8),
                    ai.ravenroot.api.payload.PayloadLimits.DEFAULTS);

            producedInput.set("hello");
            failBoom.set(false);
            String cleanId = submitRunAndAwait(client, server, monitor, TERMINAL_FAILURE_GRAPH);
            var clean = readSettledResponse(client, server, cleanId);
            assertEquals(200, clean.statusCode(), clean.body());
            assertEquals("COMPLETED", jsonString(clean.body(), "status"), clean.body());
            assertTrue(clean.body().contains("\"payload\":\"clean-result\""), clean.body());
            assertTrue(clean.body().contains("\"handledFailure\":false"), clean.body());

            var unknown = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port()
                            + "/v1/executions/" + UUID.randomUUID())).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, unknown.statusCode(), unknown.body());
            assertTrue(unknown.body().contains("\"code\":\"UNKNOWN_EXECUTION\""), unknown.body());
        }
    }

    /** Submits in {@code mode=run} so registered behaviours execute, and waits for the traversal to end. */
    private static String submitRunAndAwait(HttpClient client, RavenrootServer server, ExecutionMonitor monitor,
                                            String graphMl) throws Exception {
        var submitted = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.port() + "/v1/executions?mode=run&payload=hello"))
                .header("Content-Type", "application/graphml+xml")
                .POST(HttpRequest.BodyPublishers.ofString(graphMl)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, submitted.statusCode(), submitted.body());
        String executionId = jsonString(submitted.body(), "executionId");
        awaitTerminal(monitor, executionId);
        return executionId;
    }

    /**
     * Normally one GET, because the caller already waited on the monitor. The bounded retry covers the
     * one ordering the monitor cannot: {@code EXECUTION_COMPLETED} is published by the runner before
     * the returned stage settles, and the registry is written from that stage's completion, so a read
     * arriving between the two legitimately still answers {@code RUNNING}. Retried slowly and a
     * bounded number of times rather than tightly: this route is rate limited like every other, and a
     * fast poll would turn a missing annotation into a 429 that looks like an unrelated defect.
     */
    private static String readResult(HttpClient client, RavenrootServer server, String executionId)
            throws Exception {
        HttpResponse<String> response = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port()
                    + "/v1/executions/" + executionId)).GET().build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> observed = response;
            assertEquals(200, observed.statusCode(), () -> observed.body());
            if (!response.body().contains("\"status\":\"RUNNING\"")) {
                return response.body();
            }
            Thread.sleep(250);
        }
        throw new AssertionError("the execution never left RUNNING: " + response.body());
    }

    /** Returns the first terminal outcome, including a classified payload-rejection response. */
    private static HttpResponse<String> readSettledResponse(
            HttpClient client, RavenrootServer server, String executionId) throws Exception {
        HttpResponse<String> response = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port()
                    + "/v1/executions/" + executionId)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || !response.body().contains("\"status\":\"RUNNING\"")) {
                return response;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("the execution never produced a terminal response: " + response.body());
    }

    /**
     * An unknown id must be an explicit, classified refusal — never an empty body and never a 200
     * with nothing in it, which is the failure mode this endpoint was written to avoid.
     */
    @Test
    void readingAnUnknownOrMalformedExecutionIdIsAnExplicitClassifiedRefusal() throws Exception {
        try (var engine = new PekkoExecutionEngine("ravenroot-server-unknown-result-test");
             var server = testServer(new DefaultRavenrootApplication(engine, new ExecutionMonitor()), null)) {
            server.start();
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

            var unknown = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port()
                    + "/v1/executions/" + java.util.UUID.randomUUID())).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, unknown.statusCode());
            assertTrue(unknown.body().contains("UNKNOWN_EXECUTION"), () -> unknown.body());

            // Not 404: the request is malformed, and a 404 would claim the server looked and missed.
            var malformed = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port()
                    + "/v1/executions/not-a-uuid")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, malformed.statusCode());
            assertTrue(malformed.body().contains("INVALID_REQUEST"), () -> malformed.body());

            // Submission must not have been turned into a GET-able synchronous call by the dispatch.
            var wrongMethod = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port()
                    + "/v1/executions")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, wrongMethod.statusCode());
        }
    }

    /**
     * The static-asset fallback ({@link StaticUiHandler}, registered on {@code "/"}) must judge
     * the path before the method. A request under {@code /v1} that reaches this fallback means the
     * route table has no context for it -- typically a base URL that puts the caller outside
     * {@code /v1} entirely, for example a passthrough test hitting an instance that serves the UI
     * without the API routes. The right answer is 404 "no such route" for any method, never a 405
     * that asserts the wrong thing (the method) was the problem when the place was.
     */
    @Test
    void staticFallbackJudgesPathBeforeMethod() throws Exception {
        try (var engine = new PekkoExecutionEngine("ravenroot-server-static-fallback-test");
             var server = testServer(new DefaultRavenrootApplication(engine, new ExecutionMonitor()), uiDirectory)) {
            server.start();
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

            // A path under /v1 with no registered context falls through to the static handler. POST
            // must not be told "method not allowed" here -- the method was never evaluated.
            var apiShaped = post(client, "http://localhost:" + server.port() + "/v1/does-not-exist");
            assertEquals(404, apiShaped.statusCode(), apiShaped.body());
            assertTrue(apiShaped.body().contains("\"error\":\"not found\""), apiShaped.body());
            assertTrue(apiShaped.headers().firstValue("Allow").isEmpty(),
                    "an Allow header on a 404 for a nonexistent route is noise: " + apiShaped.headers());

            // Preservation control: a genuinely static path keeps its 405 for an unsupported method.
            // The 405 applies only to paths actually served here; narrowing it must not remove it.
            var staticRoot = post(client, "http://localhost:" + server.port() + "/");
            assertEquals(405, staticRoot.statusCode(), staticRoot.body());
            assertEquals(Optional.of("GET, HEAD, OPTIONS"), staticRoot.headers().firstValue("Allow"));
        }
    }

    private static HttpResponse<String> post(HttpClient client, String uri) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String jsonString(String body, String field) {
        String marker = "\"" + field + "\":\"";
        int start = body.indexOf(marker);
        if (start < 0) throw new AssertionError("Missing JSON field " + field + " in " + body);
        int valueStart = start + marker.length();
        int end = body.indexOf('"', valueStart);
        if (end < 0) throw new AssertionError("Unterminated JSON field " + field + " in " + body);
        return body.substring(valueStart, end);
    }

    private static HttpResponse<String> post(HttpClient client, String uri, String token) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri))
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static RavenrootServer testServer(ai.ravenroot.api.application.RavenrootApplication application,
                                               Path ui) {
        return testServer(application, ui, new DisabledLoopbackAuthenticator());
    }

    private static RavenrootServer testServer(ai.ravenroot.api.application.RavenrootApplication application,
                                               Path ui, RequestAuthenticator authenticator) {
        return new RavenrootServer(application,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), ui,
                authenticator);
    }

    private static RequestAuthenticator lifecycleAuthenticator() {
        return headers -> {
            boolean approver = "Bearer approver".equals(headers.getFirst("Authorization"));
            return new AuthenticatedPrincipal(approver ? "bob" : "alice", AuthenticatedPrincipal.Type.USER,
                    "issuer", "tenant-a",
                    Set.of(approver ? Role.APPROVER : Role.DEVELOPER),
                    approver
                            ? Set.of("ravenroot.artifact.read", "ravenroot.artifact.approve",
                            "ravenroot.artifact.activate", "ravenroot.artifact.retire")
                            : Set.of("ravenroot.artifact.read", "ravenroot.artifact.manage"));
        };
    }

    private static AuthenticatedPrincipal principal(Instant expiresAt) {
        return new AuthenticatedPrincipal("browser-user", AuthenticatedPrincipal.Type.USER,
                "https://issuer.example", "tenant-a",
                Set.of(ai.ravenroot.api.security.Role.PLATFORM_ADMIN),
                java.util.Arrays.stream(ai.ravenroot.api.security.AuthorizationAction.values())
                        .filter(ai.ravenroot.api.security.AuthorizationAction::available)
                        .map(ai.ravenroot.api.security.AuthorizationAction::requiredScope)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                expiresAt);
    }

    /**
     * Holds time before expiry while the lease calculates its bounded poll, then reaches exp exactly.
     * This makes the hard-expiry regression independent from scheduler and wall-clock timing.
     */
    private static final class ExpiringLeaseClock extends Clock {
        private final Instant active;
        private final Instant expired;
        private final AtomicInteger reads = new AtomicInteger();

        private ExpiringLeaseClock(Instant active, Instant expired) {
            this.active = active;
            this.expired = expired;
        }

        @Override
        public ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!java.time.ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock is fixed to UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return reads.incrementAndGet() <= 3 ? active : expired;
        }
    }
}
