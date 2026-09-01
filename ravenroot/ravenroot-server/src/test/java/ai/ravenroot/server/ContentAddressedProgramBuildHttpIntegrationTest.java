package ai.ravenroot.server;

import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramAdmission;
import ai.ravenroot.api.programming.ProgramRequest;
import ai.ravenroot.api.programming.ProgramRuntime;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.core.ai.AgentRuntimeRegistry;
import ai.ravenroot.core.ai.ModelProviderRegistry;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.SqliteArtifactRegistry;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.RequestAuthenticator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real HTTP/application/SQLite close-reopen proof for the build authority. */
class ContentAddressedProgramBuildHttpIntegrationTest {
    @TempDir Path storeDirectory;

    @Test
    void twentyProgramsResumeAfterRestartAndOnlyChangedBindingsRequalify() throws Exception {
        Path liveStore = storeDirectory.resolve("live");
        Path recoveryBundle = storeDirectory.resolve("backup/program-qualification");
        Path restoredStore = storeDirectory.resolve("restored");
        String original = batch(20, -1, false);
        String authoredGraph = graph(20);
        var firstRuntime = new CountingRuntime();
        String firstResponse;
        try (var fixture = new Fixture(liveStore, firstRuntime)) {
            var built = fixture.build(original);
            assertEquals(200, built.statusCode(), built.body());
            assertEquals(20, occurrences(built.body(), "\"ready\":true"));
            assertEquals(20, firstRuntime.validations.get());
            assertEquals(20, firstRuntime.smokes.get());
            firstResponse = built.body();
        }

        // Offline recovery drill: the GraphML and the closed qualification database are one
        // coherence set. Copying a live SQLite main file is intentionally not exercised or endorsed.
        Files.createDirectories(recoveryBundle);
        Files.copy(liveStore.resolve(SqliteArtifactRegistry.FILE_NAME),
                recoveryBundle.resolve(SqliteArtifactRegistry.FILE_NAME));
        Files.writeString(recoveryBundle.resolve("graph.graphml"), authoredGraph, StandardCharsets.UTF_8);
        Files.createDirectories(restoredStore);
        Files.copy(recoveryBundle.resolve(SqliteArtifactRegistry.FILE_NAME),
                restoredStore.resolve(SqliteArtifactRegistry.FILE_NAME), StandardCopyOption.COPY_ATTRIBUTES);
        String recoveredGraph = Files.readString(recoveryBundle.resolve("graph.graphml"), StandardCharsets.UTF_8);

        var restartedRuntime = new CountingRuntime();
        try (var fixture = new Fixture(restoredStore, restartedRuntime)) {
            var resumed = fixture.build(original);
            assertEquals(200, resumed.statusCode(), resumed.body());
            assertEquals(20, occurrences(resumed.body(), "\"reused\":true"));
            assertEquals(0, restartedRuntime.validations.get(), "restart must reuse durable qualification");
            assertEquals(0, restartedRuntime.smokes.get(), "unchanged payload evidence must survive restart");

            String execution = fixture.execute(recoveredGraph);
            assertTrue(execution.contains("\"status\":\"COMPLETED\""), execution);
            assertEquals(20, restartedRuntime.executions.get(),
                    "all twenty content-resolved nodes must execute after the store restart");

            var payloadOnly = fixture.build(batch(20, 7, false));
            assertEquals(200, payloadOnly.statusCode(), payloadOnly.body());
            assertEquals(0, restartedRuntime.validations.get(), "payload-only edits do not change source identity");
            assertEquals(1, restartedRuntime.smokes.get(), "only the changed node reruns smoke evidence");

            var oneByteSource = fixture.build(batch(20, 7, true));
            assertEquals(200, oneByteSource.statusCode(), oneByteSource.body());
            assertEquals(1, restartedRuntime.validations.get(), "one changed source creates one new qualification");
            assertEquals(2, restartedRuntime.smokes.get(), "source change adds one smoke to the payload-only rerun");

            String retiredId = artifactIdForNode(firstResponse, "program-0");
            assertEquals(200, fixture.retire(retiredId).statusCode());
            var refused = fixture.build(original);
            assertEquals(200, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("\"phase\":\"RETIRED\""), refused.body());
            assertTrue(refused.body().contains("cannot be rebuilt or resurrected"), refused.body());
        }
    }

    @Test
    void validationAndSmokeFailuresStayGatedAndDualControlUsesOneBatchApproval() throws Exception {
        var runtime = new CountingRuntime();
        try (var fixture = new Fixture(storeDirectory.resolve("failures"), runtime)) {
            var invalid = fixture.build(one("invalid", "javascript", "invalid-source", "test payload"));
            assertEquals(200, invalid.statusCode(), invalid.body());
            assertTrue(invalid.body().contains("\"phase\":\"FAILED\""), invalid.body());
            assertTrue(invalid.body().contains("\"artifactId\":\""), invalid.body());

            var smoke = fixture.build(one("bad-smoke", "javascript", "valid-source", "fail smoke"));
            assertEquals(200, smoke.statusCode(), smoke.body());
            assertTrue(smoke.body().contains("\"phase\":\"FAILED\""), smoke.body());
            assertTrue(smoke.body().contains("\"artifactId\":\""), smoke.body());
        }

        try (var fixture = new Fixture(storeDirectory.resolve("dual-control"), new CountingRuntime(), true)) {
            var waiting = fixture.build(one("reviewed", "javascript", "reviewed-source", "test payload"));
            assertEquals(202, waiting.statusCode(), waiting.body());
            assertTrue(waiting.body().contains("\"phase\":\"APPROVAL_REQUIRED\""), waiting.body());
            String artifactId = artifactIdForNode(waiting.body(), "reviewed");

            var creatorDenied = fixture.approve(artifactId, "creator");
            assertEquals(403, creatorDenied.statusCode(), creatorDenied.body());
            var approved = fixture.approve(artifactId, "reviewer");
            assertEquals(200, approved.statusCode(), approved.body());
            assertTrue(approved.body().contains("\"state\":\"ACTIVE\""), approved.body());

            var ready = fixture.build(one("reviewed", "javascript", "reviewed-source", "test payload"));
            assertEquals(200, ready.statusCode(), ready.body());
            assertTrue(ready.body().contains("\"ready\":true"), ready.body());
        }
    }

    @Test
    void changedRuntimeCompatibilityFailsClosedUntilRevalidationSucceeds() throws Exception {
        Path compatibilityStore = storeDirectory.resolve("compatibility");
        try (var fixture = new Fixture(compatibilityStore, new CountingRuntime("runtime/v1", false))) {
            assertEquals(200, fixture.build(one(
                    "program-0", "javascript", "source-0", "test payload")).statusCode());
        }

        var incompatible = new CountingRuntime("runtime/v2", true);
        try (var fixture = new Fixture(compatibilityStore, incompatible)) {
            var refused = fixture.build(one("program-0", "javascript", "source-0", "test payload"));
            assertEquals(200, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("\"phase\":\"FAILED\""), refused.body());
            String execution = fixture.execute(graph(1));
            assertFalse(execution.contains("\"status\":\"COMPLETED\""), execution);
            assertEquals(0, incompatible.executions.get(),
                    "source must not reach a changed runtime after compatibility validation failed");
        }
    }

    private static String one(String nodeId, String language, String source, String payload) {
        return "{\"programs\":[{\"nodeId\":\"" + nodeId + "\",\"language\":\"" + language
                + "\",\"source\":\"" + source + "\",\"testPayload\":\"" + payload + "\"}]}";
    }

    private static String batch(int count, int changedPayload, boolean changedSource) {
        var out = new StringBuilder("{\"programs\":[");
        for (int index = 0; index < count; index++) {
            if (index > 0) out.append(',');
            String source = "source-" + index + (changedSource && index == 3 ? "x" : "");
            String payload = index == changedPayload ? "{\\\"changed\\\":true}" : "test payload";
            out.append("{\"nodeId\":\"program-").append(index)
                    .append("\",\"language\":\"javascript\",\"source\":\"").append(source)
                    .append("\",\"testPayload\":\"").append(payload).append("\"}");
        }
        return out.append("]}").toString();
    }

    private static String graph(int count) {
        var out = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
                  <key id="language" for="node" attr.name="language" attr.type="string"/>
                  <key id="source" for="node" attr.name="source" attr.type="string"/>
                  <graph id="g" edgedefault="directed">
                    <node id="error"><data key="kind">ERROR</data></node>
                    <node id="start"><data key="kind">START</data></node>
                """);
        for (int index = 0; index < count; index++) {
            out.append("    <node id=\"program-").append(index).append("\">"
                    + "<data key=\"kind\">BEHAVIOR</data><data key=\"behavior\">program</data>"
                    + "<data key=\"language\">javascript</data><data key=\"source\">source-")
                    .append(index).append("</data></node>\n");
        }
        out.append("    <node id=\"end\"><data key=\"kind\">END</data></node>\n");
        String previous = "start";
        for (int index = 0; index < count; index++) {
            out.append("    <edge id=\"e").append(index).append("\" source=\"").append(previous)
                    .append("\" target=\"program-").append(index).append("\"/>\n");
            previous = "program-" + index;
        }
        return out.append("    <edge id=\"e-end\" source=\"").append(previous)
                .append("\" target=\"end\"/>\n  </graph>\n</graphml>\n").toString();
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        for (int offset = 0; (offset = text.indexOf(token, offset)) >= 0; offset += token.length()) count++;
        return count;
    }

    private static String artifactIdForNode(String body, String nodeId) {
        int node = body.indexOf("\"nodeId\":\"" + nodeId + "\"");
        int id = body.indexOf("\"artifactId\":\"", node) + "\"artifactId\":\"".length();
        return body.substring(id, body.indexOf('"', id));
    }

    private static final class CountingRuntime implements ProgramRuntime {
        private final AtomicInteger validations = new AtomicInteger();
        private final AtomicInteger smokes = new AtomicInteger();
        private final AtomicInteger executions = new AtomicInteger();
        private final String fingerprint;
        private final boolean rejectValidation;

        private CountingRuntime() {
            this("jdk21-test-runtime/v1", false);
        }

        private CountingRuntime(String fingerprint, boolean rejectValidation) {
            this.fingerprint = fingerprint;
            this.rejectValidation = rejectValidation;
        }

        @Override public String id() { return "jdk21-test-runtime"; }
        @Override public String compatibilityFingerprint() { return fingerprint; }
        @Override public CompletionStage<Void> validate(GeneratedArtifact artifact) {
            validations.incrementAndGet();
            if (rejectValidation || artifact.source().startsWith("invalid-")) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("validation refused source"));
            }
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Object> test(GeneratedArtifact artifact, ProgramRequest request) {
            smokes.incrementAndGet();
            if ("fail smoke".equals(request.payload())) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("smoke refused payload"));
            }
            return CompletableFuture.completedFuture(Map.of("bounded", true, "payload", request.payload()));
        }
        @Override public CompletionStage<Object> execute(ProgramAdmission admission, ProgramRequest request) {
            admission.redeem();
            executions.incrementAndGet();
            return CompletableFuture.completedFuture(request.payload());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final PekkoExecutionEngine engine;
        private final RavenrootServer server;
        private final HttpClient client = HttpClient.newHttpClient();
        private final String base;

        private Fixture(Path directory, ProgramRuntime runtime) {
            this(directory, runtime, false);
        }

        private Fixture(Path directory, ProgramRuntime runtime, boolean dualControl) {
            engine = new PekkoExecutionEngine("program-build-http-test");
            var artifacts = SqliteArtifactRegistry.openUnder(directory, artifact -> { });
            var environment = new BehaviorEnvironment(new ModelProviderRegistry(), new AgentRuntimeRegistry(),
                    artifacts, runtime, ignored -> Optional.empty(),
                    ignored -> new ToolDecision(ToolDecision.Disposition.ALLOW, "test", ""),
                    OutboundHttpPolicy.disabled());
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), environment);
            application.configureArtifactDualControl(dualControl);
            server = new RavenrootServer(application,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, authenticator(), dualControl);
            server.start();
            base = "http://localhost:" + server.port() + "/v1/program-artifacts";
        }

        private HttpResponse<String> build(String body) throws Exception {
            HttpResponse<String> started = client.send(HttpRequest.newBuilder(URI.create(base + "/build"))
                            .header("Authorization", "Bearer creator")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            String buildId = started.body().split("\"buildId\":\"")[1].split("\"")[0];
            HttpResponse<String> status = started;
            for (int attempt = 0; attempt < 200; attempt++) {
                status = client.send(HttpRequest.newBuilder(URI.create(base + "/builds/" + buildId))
                                .header("Authorization", "Bearer creator").GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                int buildTerminal = status.body().indexOf("\"terminal\":true");
                if ((buildTerminal >= 0 && buildTerminal < status.body().indexOf("\"programs\""))
                        || status.body().contains("\"phase\":\"APPROVAL_REQUIRED\"")) return status;
                Thread.sleep(10);
            }
            return status;
        }

        private HttpResponse<String> retire(String id) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create(base + "/" + id + "/retire?reason=recovery-test"))
                            .header("Authorization", "Bearer creator")
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> approve(String id, String actor) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create(base + "/approve-batch"))
                            .header("Authorization", "Bearer " + actor)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"artifactIds\":[\"" + id
                                    + "\"],\"reason\":\"peer approval\"}"))
                            .build(), HttpResponse.BodyHandlers.ofString());
        }

        private String execute(String graphMl) throws Exception {
            var submitted = client.send(HttpRequest.newBuilder(URI.create(
                            "http://localhost:" + server.port() + "/v1/executions?mode=run&payload=hello"))
                            .header("Authorization", "Bearer creator")
                            .header("Content-Type", "application/graphml+xml")
                            .POST(HttpRequest.BodyPublishers.ofString(graphMl)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, submitted.statusCode(), submitted.body());
            String executionId = submitted.body().split("\"executionId\":\"")[1].split("\"")[0];
            String resultUri = "http://localhost:" + server.port() + "/v1/executions/" + executionId;
            HttpResponse<String> result = null;
            for (int attempt = 0; attempt < 40; attempt++) {
                result = client.send(HttpRequest.newBuilder(URI.create(resultUri))
                                .header("Authorization", "Bearer creator").GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, result.statusCode(), result.body());
                if (!result.body().contains("\"status\":\"RUNNING\"")) return result.body();
                Thread.sleep(50);
            }
            return result == null ? "no execution result" : result.body();
        }

        @Override public void close() {
            server.close();
            engine.close();
        }
    }

    private static RequestAuthenticator authenticator() {
        return headers -> {
            boolean reviewer = "Bearer reviewer".equals(headers.getFirst("Authorization"));
            return new AuthenticatedPrincipal(reviewer ? "bob" : "alice", AuthenticatedPrincipal.Type.USER,
                    "issuer", "tenant-a", reviewer ? Set.of(Role.APPROVER)
                    : Set.of(Role.DEVELOPER, Role.APPROVER, Role.OPERATOR),
                    reviewer ? Set.of("ravenroot.artifact.approve", "ravenroot.artifact.activate")
                            : Set.of("ravenroot.artifact.read", "ravenroot.artifact.manage",
                            "ravenroot.artifact.retire", "ravenroot.artifact.approve",
                            "ravenroot.artifact.activate", "ravenroot.execute", "ravenroot.observe"));
        };
    }
}
