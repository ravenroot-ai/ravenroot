package ai.ravenroot.extensions.github;

import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.payload.PayloadJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class GithubBoundaryTest {
    @TempDir Path directory;

    @Test void packageExposesExactlyTheFiveIssueBehaviors() {
        GithubNodePackage nodePackage = GithubTestSupport.nodePackage(directory.resolve("operations.db"));
        assertEquals(GithubConfiguration.PACKAGE_ID, nodePackage.id());
        assertEquals(NodeSdk.CONTRACT, nodePackage.sdkContract());
        assertEquals(Set.of("github-events-source", "project-transition", "github-app-review",
                        "github-workflow-watch", "release-prepare"), nodePackage.behaviors().stream()
                .map(value -> value.descriptor().behavior()).collect(Collectors.toSet()));
        assertEquals(Set.of(NodePackageCapability.CREDENTIAL_RESOLUTION),
                GithubTestSupport.behavior(nodePackage, "github-events-source").requiredServices());
        nodePackage.behaviors().stream().filter(value -> !value.descriptor().behavior().equals("github-events-source"))
                .forEach(value -> assertEquals(Set.of(NodePackageCapability.OUTBOUND_HTTP), value.requiredServices()));
    }

    @Test void productionUsesOnlyTheSdkManagedServicesAndOneDiscoverablePackage() throws Exception {
        try (var files = Files.walk(Path.of("src/main/java"))) {
            var sources = files.filter(path -> path.toString().endsWith(".java")).toList();
            assertEquals(1, sources.stream().filter(path -> path.getFileName().toString().endsWith("NodePackage.java")).count());
            for (Path source : sources) {
                String text = Files.readString(source);
                assertFalse(text.contains("import ai.ravenroot.core"), source.toString());
                assertFalse(text.contains("import ai.ravenroot.server"), source.toString());
                assertFalse(text.contains("java.net.http.HttpClient"), source.toString());
                assertFalse(text.contains("ServiceLoader"), source.toString());
            }
        }
        String distribution = Files.readString(Path.of("../../ravenroot-distribution/pom.xml"));
        assertFalse(distribution.contains("ravenroot-github"));
    }

    @Test void releasePreparationHasNoMutatingWireMethod() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ai/ravenroot/extensions/github/ReleasePrepareBehavior.java"));
        assertFalse(source.contains("api.post("));
        assertFalse(source.contains("api.delete("));
        assertFalse(source.contains("api.graphql("));
        assertTrue(source.contains("api.get("));
    }

    @Test void versionedSchemasAreDiscoverableForExactlyTheFiveBehaviors() throws Exception {
        String indexPath = "META-INF/ravenroot/github/schema-index.json";
        ClassLoader loader = GithubNodePackage.class.getClassLoader();
        Map<String, Object> index;
        try (var stream = loader.getResourceAsStream(indexPath)) {
            assertNotNull(stream);
            index = GithubValues.object(PayloadJson.read(stream.readAllBytes(), GithubValues.LIMITS).toJava());
        }
        assertEquals("ravenroot.github.schemas.v1", index.get("version"));
        Map<String, Object> schemas = GithubValues.object(index.get("schemas"));
        assertEquals(Set.of("github-events-source", "project-transition", "github-app-review",
                "github-workflow-watch", "release-prepare"), schemas.keySet());
        ObjectMapper mapper = new ObjectMapper();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        Map<String, Map<String, Object>> valid = validSchemaPayloads();
        for (Map.Entry<String, Object> entry : schemas.entrySet()) {
            Map<String, Object> contracts = GithubValues.object(entry.getValue());
            assertEquals(valid.get(entry.getKey()).keySet(), contracts.keySet());
            for (Map.Entry<String, Object> contract : contracts.entrySet()) {
                String uri = GithubValues.string(contract.getValue(), 300);
                String[] parts = uri.split("#", 2);
                assertEquals("/$defs/" + contract.getKey(), parts[1]);
                try (var stream = loader.getResourceAsStream(parts[0])) {
                    assertNotNull(stream, entry.getKey() + ":" + contract.getKey());
                    var document = mapper.readTree(stream);
                    ObjectNode operative = mapper.createObjectNode();
                    operative.put("$schema", "https://json-schema.org/draft/2020-12/schema");
                    operative.set("$defs", document.get("$defs"));
                    operative.put("$ref", "#/$defs/" + contract.getKey());
                    var schema = factory.getSchema(operative);
                    assertTrue(schema.validate(mapper.valueToTree(valid.get(entry.getKey()).get(contract.getKey())))
                            .isEmpty(), entry.getKey() + ":" + contract.getKey());
                    assertFalse(schema.validate(mapper.valueToTree(Map.of("unexpected", true))).isEmpty(),
                            entry.getKey() + ":" + contract.getKey());
                }
            }
        }
    }

    private static Map<String, Map<String, Object>> validSchemaPayloads() {
        String sha = GithubTestSupport.SHA;
        Map<String, Object> retry = Map.of("version", "github.operation.retry.v1", "status", "waiting",
                "reason", "RATE_LIMITED", "retryAtEpochMs", 1L, "generation", 0L, "attempts", 0L,
                "remoteId", "");
        return Map.of(
                "github-events-source", Map.of(
                        "event", Map.of("version", "github.event.v1", "event", "workflow_run", "action", "completed",
                                "deliveryId", "delivery-1", "repositoryId", 1L, "installationId", 2L,
                                "body", Map.of()),
                        "receipt", Map.of("version", "github.event.receipt.v1", "deliveryId", "delivery-1",
                                "receipt", "committed")),
                "project-transition", Map.of(
                        "input", Map.of("version", "github.project-transition.v1", "itemId", "ITEM_1",
                                "fromStatus", "Todo", "toStatus", "Doing", "expectedGeneration", 1L,
                                "expectedAttempts", 0L, "correlationId", "correlation"), "output", retry),
                "github-app-review", Map.of(
                        "input", Map.of("version", "github.app-review.v1", "pullNumber", 1L, "commit", sha,
                                "verdict", "APPROVE", "body", "Approved", "correlationId", "correlation"),
                        "output", retry),
                "github-workflow-watch", Map.of(
                        "input", Map.of("version", "github.workflow-watch.v1", "commit", sha,
                                "deadlineEpochMs", 1L, "correlationId", "correlation"), "output", retry),
                "release-prepare", Map.of(
                        "input", Map.of("version", "github.release-prepare.v1", "commit", sha,
                                "releaseKind", "minor", "correlationId", "correlation"), "output", retry));
    }
}
