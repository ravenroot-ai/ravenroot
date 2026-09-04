package ai.ravenroot.server.spec;

import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.RavenrootServer;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * API-05 makes drift non-representable: three independent things must agree —
 * {@link RouteTable#ALL}, the checked-in {@code docs/api/openapi.json}, and what a live
 * {@link RavenrootServer} actually does — and this class is what fails when any two of them disagree.
 *
 * <p>For {@link RouteDescriptor#registersContext()} {@code true} entries, agreement between the table
 * and the server is true <em>by construction</em> ({@code RavenrootServer#apiContext} cannot register a
 * context without a table entry, and {@link RavenrootServer#registeredRoutes()} is exactly what it
 * registered) — the assertion here is a proof that the construction holds, not the only thing standing
 * between the two. For {@code registersContext() == false} entries (the program-artifact operation
 * switch, {@code /health}/{@code /ready}, and the default-off conditional embed surface), there is no
 * such unconditional mechanical link. This class verifies the first two against a running default
 * server; {@code EmbedBrowserHttpIntegrationTest} verifies the configured embed surface and asserts
 * that its five handler constants exactly equal the five hand-declared table paths.</p>
 */
class RouteTableSpecServerAgreementTest {
    @TempDir
    Path uiDirectory;

    @Test
    void theCheckedInSpecMatchesWhatTheTableGeneratesRightNow() throws Exception {
        String generatedNow = OpenApiSpecGenerator.generate(RouteTable.ALL);
        RouteDescriptor buildStatus = RouteTable.ALL.stream()
                .filter(route -> route.path().equals("/v1/program-artifacts/builds/{id}"))
                .findFirst().orElseThrow();
        assertEquals(Set.of(200, 202), buildStatus.successStatuses(),
                "durable polling must declare both terminal and incomplete success responses");
        String buildStatusSpec = pathEntry(generatedNow, buildStatus.path());
        assertTrue(buildStatusSpec.contains("\"200\": {\"description\": \"success\"}"), buildStatusSpec);
        assertTrue(buildStatusSpec.contains("\"202\": {\"description\": \"success\"}"),
                "removing the accepted/incomplete mapping must fail independently of regeneration: "
                        + buildStatusSpec);
        String humanInbox = pathEntry(generatedNow, "/v1/human-tasks");
        assertTrue(humanInbox.contains("\"name\": \"status\""), humanInbox);
        assertTrue(humanInbox.contains("\"name\": \"includeTerminal\""), humanInbox);
        assertTrue(humanInbox.contains("#/components/schemas/HumanTaskInboxPage"), humanInbox);
        assertTrue(generatedNow.contains("\"responseContentType\""), generatedNow);
        String humanDecision = pathEntry(generatedNow, "/v1/human-tasks/{taskId}/{decision}");
        assertTrue(humanDecision.contains("\"name\": \"generation\""), humanDecision);
        assertTrue(humanDecision.contains("\"required\": true"), humanDecision);
        assertTrue(humanDecision.contains("#/components/schemas/PayloadEnvelope"), humanDecision);
        assertTrue(humanDecision.contains("#/components/schemas/HumanTaskDecisionResult"), humanDecision);
        String onDisk = checkedInSpec();
        assertEquals(generatedNow, onDisk, () -> "the checked-in OpenAPI fixture is stale -- regenerate it with "
                + "SpecGeneratorMain (see its own Javadoc) after changing RouteTable");
    }

    /**
     * The mechanical half: every route the table says drives registration is exactly what the server
     * registered. Compared as a set because {@code RouteTable.ALL}'s literal order (grouping
     * {@code /health}/{@code /ready} and the program-artifact sub-operations near their base path for
     * readability) does not match {@code RavenrootServer}'s constructor's registration order, and
     * nothing about "the same routes were registered" should depend on it matching.
     */
    @Test
    void everyRegistersContextTrueRouteIsRegisteredByTheLiveServer() throws Exception {
        var fromTable = RouteTable.ALL.stream().filter(RouteDescriptor::registersContext)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        try (var engine = new PekkoExecutionEngine("route-table-agreement");
             var server = testServer(engine)) {
            var fromServer = Set.copyOf(server.registeredRoutes());
            assertEquals(fromTable, fromServer);
        }
    }

    /**
     * The hand-declared half. {@code /health} and {@code /ready} answer without authentication;
     * asserting only "not 404" rather than a specific status, because {@code /ready}'s own status
     * depends on readiness state this test does not control.
     */
    @Test
    void handDeclaredHealthAndReadyRespondOnTheLiveServer() throws Exception {
        try (var engine = new PekkoExecutionEngine("route-table-health-ready");
             var server = testServer(engine)) {
            server.start();
            var client = HttpClient.newHttpClient();
            for (String path : List.of("/health", "/ready")) {
                var response = client.send(HttpRequest.newBuilder(URI.create(base(server) + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertFalse(response.statusCode() == 404, () -> path + " must be a real endpoint, not 404");
            }
        }
    }

    /**
     * The trap the deployment admission design named, verified directly: the five real program-artifact operations must be
     * reachable through the switch {@code applyArtifactOperation} dispatches on, and a made-up sixth
     * operation must not be -- the positive and the negative together are what prove the table's five
     * hand-declared entries describe the switch's real cases rather than a guess at its shape.
     */
    @Test
    void handDeclaredArtifactOperationsAreReachableAndAnUndeclaredOneIsNot() throws Exception {
        try (var engine = new PekkoExecutionEngine("route-table-artifact-ops");
             var server = testServer(engine)) {
            server.start();
            var client = HttpClient.newHttpClient();
            for (String operation : List.of("validate", "test", "approve", "activate", "retire")) {
                String body = post(client, server, "/v1/program-artifacts/nonexistent-id/" + operation);
                assertFalse(body.contains("UNKNOWN_ARTIFACT_OPERATION"),
                        () -> "'" + operation + "' is declared in RouteTable as a real operation, but the "
                                + "live server answered as if it were not: " + body);
            }
            for (String operation : List.of("build", "approve-batch")) {
                String body = post(client, server, "/v1/program-artifacts/" + operation);
                assertFalse(body.contains("UNKNOWN_ARTIFACT_OPERATION"),
                        () -> "'" + operation + "' is a declared graph-level operation: " + body);
            }
            String undeclared = post(client, server, "/v1/program-artifacts/nonexistent-id/not-a-real-operation");
            assertTrue(undeclared.contains("UNKNOWN_ARTIFACT_OPERATION"),
                    () -> "a made-up operation must fall through the switch's default case: " + undeclared);
        }
    }

    private static String post(HttpClient client, RavenrootServer server, String path) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(base(server) + path))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private static String base(RavenrootServer server) {
        return "http://localhost:" + server.port();
    }

    private static String pathEntry(String spec, String path) {
        int start = spec.indexOf("    \"" + path + "\": {");
        if (start < 0) fail("generated OpenAPI omitted " + path);
        int next = spec.indexOf("\n    \"/", start + path.length());
        return spec.substring(start, next < 0 ? spec.length() : next);
    }

    private RavenrootServer testServer(PekkoExecutionEngine engine) {
        return new RavenrootServer(new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), uiDirectory,
                new DisabledLoopbackAuthenticator());
    }

    private static String checkedInSpec() throws IOException {
        try (var stream = RouteTableSpecServerAgreementTest.class
                .getResourceAsStream("/documentation/openapi.json")) {
            if (stream == null) {
                fail("Expected the checked-in OpenAPI fixture in test resources; regenerate it with "
                        + "SpecGeneratorMain");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
