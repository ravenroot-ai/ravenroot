package ai.ravenroot.extensions.openapi.server;

import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.JoinSemantics;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.server.OpenApiServerProductionHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Production composition: package -> graph lifecycle -> managed registry -> authenticated HTTP. */
class OpenApiServerProductionIntegrationTest {
    private static final SecurityContext OWNER = new SecurityContext("owner-request", "tenant-a", "operator",
            PrincipalType.WORKLOAD, "runtime");

    @TempDir Path temporaryDirectory;

    @Test void readinessActivationAuthenticationStopRestartAndGenerationInventoryUseRealSeams() throws Exception {
        try (var harness = harness("lifecycle")) {
            assertTrue(harness.ready());
            assertEquals(200, harness.get("/ready").statusCode());
            assertEquals("routes=[]", harness.readinessDetail());
            assertNotEquals(202, harness.post("tenant-a:alice", "application/json", "before").statusCode());

            harness.startGraph();
            assertTrue(harness.readinessDetail().contains(OpenApiServerConfiguration.PACKAGE_ID));
            assertTrue(harness.readinessDetail().contains(":1:ACTIVE"));
            assertEquals(202, harness.post("tenant-a:alice", "application/json; charset=utf-8", "first").statusCode());
            assertEquals(400, harness.post("tenant-a:alice", null, "missing-media").statusCode());
            assertEquals(403, harness.post("tenant-b:mallory", "application/json", "wrong-tenant").statusCode());
            assertEquals(401, harness.post(null, "application/json", "anonymous").statusCode());

            harness.stopGraph();
            assertEquals("routes=[]", harness.readinessDetail());
            assertNotEquals(202, harness.post("tenant-a:alice", "application/json", "stopped").statusCode());
            harness.restartGraph();
            assertTrue(harness.readinessDetail().contains(":2:ACTIVE"));
            assertEquals(202, harness.post("tenant-a:alice", "application/json", "replacement").statusCode());
        }
    }

    @Test void trueTwoGraphCollisionRollsBackOnlyLoserAndLeavesWinnerReachable() throws Exception {
        try (var harness = harness("collision")) {
            harness.startGraph();
            try (var collision = harness.collision("openapi-loser")) {
                assertNotNull(collision.startFailure());
                assertEquals(ai.ravenroot.api.deployment.DeploymentState.FAILED, collision.state());
                assertTrue(harness.readinessDetail().contains(":1:ACTIVE"));
                assertEquals(202, harness.post("tenant-a:alice", "application/json", "winner").statusCode());
            }
        }
    }

    @Test void synchronousRequestReplyTraversesTheDeclaredRespondCommandOverProductionHttp() throws Exception {
        OpenApiServerNodePackage nodePackage = new OpenApiServerNodePackage(
                () -> Optional.of(OpenApiServerTestSupport.configuration()));
        try (var harness = OpenApiServerProductionHarness.start(nodePackage, synchronousGraph(), OWNER,
                temporaryDirectory.resolve("request-reply"))) {
            harness.startGraph();
            var response = harness.post("tenant-a:alice", "application/json", "sync");
            assertEquals(200, response.statusCode());
            assertEquals("production", response.headers().firstValue("result-id").orElseThrow());
            assertEquals("{\"result\":\"accepted\"}", response.body());
        }
    }

    @Test void retiringManagedIngressCancelsAPendingSynchronousExchangeAndReplacementRemainsUsable()
            throws Exception {
        OpenApiServerNodePackage nodePackage = new OpenApiServerNodePackage(
                () -> Optional.of(OpenApiServerTestSupport.configuration()));
        try (var harness = OpenApiServerProductionHarness.start(nodePackage, synchronousGraph(), OWNER,
                temporaryDirectory.resolve("request-reply-retirement"))) {
            harness.startGraph();
            harness.holdResponse();
            CompletableFuture<java.net.http.HttpResponse<String>> pending = CompletableFuture.supplyAsync(() -> {
                try {
                    return harness.post("tenant-a:alice", "application/json", "retiring");
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            });
            CompletableFuture<Void> retirement;
            try {
                harness.awaitHeldResponse();
                retirement = CompletableFuture.runAsync(() -> {
                    try {
                        harness.stopGraph();
                    } catch (Exception failure) {
                        throw new CompletionException(failure);
                    }
                });
                try {
                    assertTrue(pending.get(5, TimeUnit.SECONDS).statusCode() >= 500);
                } catch (ExecutionException disconnected) {
                    assertTrue(disconnected.getCause() instanceof IOException,
                            () -> disconnected.getCause().getClass().getName());
                }
            } finally {
                harness.releaseResponse();
            }
            retirement.get(5, TimeUnit.SECONDS);

            harness.restartGraph();
            assertEquals(200, harness.post("tenant-a:alice", "application/json", "replacement").statusCode());
        }
    }

    @Test void moduleContainerProbeScriptRunsAgainstTheSameRealAdapter() throws Exception {
        try (var harness = harness("probe")) {
            harness.startGraph();
            Path probe = Path.of(System.getProperty("basedir"), "container", "probe.sh")
                    .toAbsolutePath().normalize();
            assertTrue(Files.isRegularFile(probe));
            ProcessBuilder builder = new ProcessBuilder("sh", probe.toString(),
                    harness.uri("").toString().replaceAll("/$", ""));
            builder.environment().put("RAVENROOT_OPENAPI_PROBE_TOKEN", "legacy-value-must-not-be-used");
            Process process = builder.redirectErrorStream(true).start();
            process.getOutputStream().write("tenant-a:probe\n".getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), output);
        }
    }

    @Test void probeStreamsBearerToCurlWithoutArgumentsOrInheritedEnvironment() throws Exception {
        String sentinel = "tenant-a:sentinel-probe-secret";
        Path bin = Files.createDirectories(temporaryDirectory.resolve("capture-bin"));
        Path arguments = temporaryDirectory.resolve("curl-arguments.txt");
        Path environment = temporaryDirectory.resolve("curl-environment.txt");
        Path standardInput = temporaryDirectory.resolve("curl-stdin.txt");
        Path fakeCurl = bin.resolve("curl");
        String capture = "#!/bin/sh\n"
                + "printf '%s\\n' '---' \"$@\" >> " + shellLiteral(arguments) + "\n"
                + "printf '%s\\n' '---' >> " + shellLiteral(environment) + "\n"
                + "env | LC_ALL=C sort >> " + shellLiteral(environment) + "\n"
                + "headers=false\n"
                + "for argument do [ \"$argument\" = '@-' ] && headers=true; done\n"
                + "if $headers; then cat >> " + shellLiteral(standardInput) + "; printf '202'; fi\n";
        Files.writeString(fakeCurl, capture, StandardCharsets.UTF_8);
        assertTrue(fakeCurl.toFile().setExecutable(true));

        ProcessBuilder builder = new ProcessBuilder("sh", probe().toString(), "http://probe.invalid");
        builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        builder.environment().put("RAVENROOT_OPENAPI_PROBE_TOKEN", sentinel);
        builder.environment().put("UNRELATED_SENTINEL", sentinel);
        Process process = builder.redirectErrorStream(true).start();
        process.getOutputStream().write((sentinel + "\n").getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        assertFalse(Files.readString(arguments).contains(sentinel));
        assertFalse(Files.readString(environment).contains(sentinel));
        assertTrue(Files.readString(standardInput).contains("Authorization: Bearer " + sentinel));
    }

    @Test void probeRejectsEveryMalformedRawRecordBeforeFakeOrRealCurlStarts() throws Exception {
        List<InvalidProbeInput> inputs = invalidProbeInputs();
        Path bin = Files.createDirectories(temporaryDirectory.resolve("refusal-bin"));
        Path invoked = temporaryDirectory.resolve("curl-invoked");
        Path fakeCurl = bin.resolve("curl");
        Files.writeString(fakeCurl, "#!/bin/sh\n: > " + shellLiteral(invoked) + "\nexit 91\n",
                StandardCharsets.UTF_8);
        assertTrue(fakeCurl.toFile().setExecutable(true));

        for (InvalidProbeInput input : inputs) {
            Files.deleteIfExists(invoked);
            ProcessBuilder fakeBuilder = new ProcessBuilder("sh", probe().toString(), "http://probe.invalid");
            fakeBuilder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
            assertProbeRefused(fakeBuilder, input);
            assertFalse(Files.exists(invoked), input.label() + " invoked fake curl");

            // Port 1 makes an actual curl attempt exit 7, as in the independent reproduction. Exit 2
            // therefore proves byte validation refused the record before either readiness or request curl.
            assertProbeRefused(new ProcessBuilder("sh", probe().toString(), "http://127.0.0.1:1"), input);
        }
    }

    private static List<InvalidProbeInput> invalidProbeInputs() {
        List<InvalidProbeInput> inputs = new ArrayList<>();
        inputs.add(new InvalidProbeInput("empty input", new byte[0]));
        inputs.add(new InvalidProbeInput("empty line", new byte[]{'\n'}));
        for (int octet = 0; octet <= 0x20; octet++) {
            inputs.add(new InvalidProbeInput(String.format("control 0x%02x inside token", octet),
                    new byte[]{'a', (byte) octet, 'b', '\n'}));
            inputs.add(new InvalidProbeInput(String.format("control 0x%02x after newline", octet),
                    new byte[]{'o', 'k', '\n', (byte) octet}));
        }
        inputs.add(new InvalidProbeInput("DEL inside token", new byte[]{'a', 0x7f, 'b', '\n'}));
        inputs.add(new InvalidProbeInput("DEL after newline", new byte[]{'o', 'k', '\n', 0x7f}));
        inputs.add(new InvalidProbeInput("UTF-8 non-ASCII", "café\n".getBytes(StandardCharsets.UTF_8)));
        inputs.add(new InvalidProbeInput("visible byte after newline", new byte[]{'o', 'k', '\n', 'x'}));
        inputs.add(new InvalidProbeInput("missing final newline", "token".getBytes(StandardCharsets.US_ASCII)));
        inputs.add(new InvalidProbeInput("second empty line", "token\n\n".getBytes(StandardCharsets.US_ASCII)));
        inputs.add(new InvalidProbeInput("second non-empty line",
                "token\nsecond\n".getBytes(StandardCharsets.US_ASCII)));
        return List.copyOf(inputs);
    }

    private static void assertProbeRefused(ProcessBuilder builder, InvalidProbeInput input) throws Exception {
        Process process = builder.redirectErrorStream(true).start();
        process.getOutputStream().write(input.bytes());
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(2, process.waitFor(), input.label() + ": " + output);
    }

    private record InvalidProbeInput(String label, byte[] bytes) {
        private InvalidProbeInput {
            bytes = bytes.clone();
        }

        @Override public byte[] bytes() {
            return bytes.clone();
        }
    }

    private OpenApiServerProductionHarness harness(String name) throws Exception {
        OpenApiServerNodePackage nodePackage = new OpenApiServerNodePackage(
                () -> Optional.of(OpenApiServerTestSupport.configuration()));
        return OpenApiServerProductionHarness.start(nodePackage, graph(), OWNER, temporaryDirectory.resolve(name));
    }

    private static Path probe() {
        return Path.of(System.getProperty("basedir"), "container", "probe.sh").toAbsolutePath().normalize();
    }

    private static String shellLiteral(Path path) {
        return "'" + path.toString().replace("'", "'\"'\"'") + "'";
    }

    private static byte[] graph() throws Exception {
        GraphNode source = new GraphNode("openapi", NodeKind.BEHAVIOR, OpenApiReceiveNodeBehavior.BEHAVIOR,
                Map.of("apiProfile", "orders", "operations", "createOrder"));
        var definition = new GraphDefinition(List.of(GraphNode.start("start"), source,
                        GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "openapi"), GraphEdge.to("openapi", "end")));
        try (var manager = GraphManager.from(definition); var output = new ByteArrayOutputStream()) {
            manager.writeGraphMl(output);
            return output.toByteArray();
        }
    }

    private static byte[] synchronousGraph() throws Exception {
        GraphNode source = new GraphNode("openapi", NodeKind.BEHAVIOR,
                OpenApiRequestReplyNodeBehavior.BEHAVIOR,
                Map.of("apiProfile", "orders", "operations", "createOrder",
                        JoinSemantics.POLICY_PROPERTY, JoinSemantics.EACH_POLICY));
        GraphNode responder = new GraphNode("responder", NodeKind.BEHAVIOR,
                "test.openapi-response", Map.of());
        var definition = new GraphDefinition(List.of(GraphNode.start("start"), source, responder,
                        GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "openapi"), new GraphEdge("openapi", "responder", "continue",
                                Map.of(GraphManager.COMMAND, "process")),
                        new GraphEdge("responder", "openapi", "continue",
                                Map.of(GraphManager.COMMAND, "respond")),
                        new GraphEdge("openapi", "end", "responded",
                                Map.of(GraphManager.COMMAND, "process"))),
                Map.of(JoinSemantics.MARKER_PROPERTY, JoinSemantics.DECLARED));
        try (var manager = GraphManager.from(definition); var output = new ByteArrayOutputStream()) {
            manager.writeGraphMl(output);
            return output.toByteArray();
        }
    }
}
