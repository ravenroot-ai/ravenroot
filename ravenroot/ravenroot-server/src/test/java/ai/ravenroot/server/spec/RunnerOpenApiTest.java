package ai.ravenroot.server.spec;

import ai.ravenroot.core.runner.RunnerJson;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class RunnerOpenApiTest {
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    private static Map<String, Object> operation(String path, String method) {
        var document = RunnerJson.read(OpenApiSpecGenerator.generate(RouteTable.ALL).getBytes(StandardCharsets.UTF_8));
        var paths = map(document.get("paths"));
        assertFalse(paths.containsKey("/v1/runner-plane"));
        assertFalse(paths.containsKey("/v1/runner-plane/workspaces/{processId}/jobs/{jobId}/{operation}"));
        return map(map(paths.get("/v1/runner-plane" + path)).get(method));
    }
    @Test void artifactUploadDeclaresRequiredFencedBinaryContractAndStructuredFailures() {
        var upload = operation("/workspaces/{processId}/jobs/{jobId}/artifacts", "post");
        var parameters = (List<?>) upload.get("parameters");
        for (String header : List.of("X-Runner-Fence", "X-Runner-Artifact-Kind")) {
            var parameter = parameters.stream().map(RunnerOpenApiTest::map).filter(value -> header.equals(value.get("name"))).findFirst().orElseThrow();
            assertEquals("header", parameter.get("in")); assertEquals(true, parameter.get("required"));
        }
        var body = map(upload.get("requestBody")); assertEquals(true, body.get("required"));
        assertEquals(Set.of("application/octet-stream"), map(body.get("content")).keySet());
        var responses = map(upload.get("responses"));
        assertTrue(responses.keySet().containsAll(Set.of("201", "400", "401", "403", "404", "409", "501")));
        for (String error : List.of("400", "404", "409", "501")) {
            assertTrue(map(map(responses.get(error)).get("content")).containsKey("application/json"));
        }
        var download = operation("/workspaces/{processId}/jobs/{jobId}/artifacts/{artifactId}", "get");
        assertEquals(Set.of("application/json", "application/octet-stream"),
                map(map(map(download.get("responses")).get("200")).get("content")).keySet());
    }
    @Test void inventoryPaginationAndExplicitRecoveryAreMachineReadable() {
        for (var entry : Map.of("/health", "cursor", "/assignments", "cursor", "/audit", "afterOffset").entrySet()) {
            var parameters = (List<?>) operation(entry.getKey(), "get").get("parameters");
            assertTrue(parameters.stream().map(RunnerOpenApiTest::map).anyMatch(value -> entry.getValue().equals(value.get("name"))
                    && "query".equals(value.get("in"))));
        }
        var resolution = operation("/workspaces/{processId}/jobs/{jobId}/resolve-continuation", "post");
        var schema = map(map(map(map(resolution.get("requestBody")).get("content")).get("application/json")).get("schema"));
        assertEquals(List.of("expectedRevision", "resolution"), schema.get("required"));
        assertEquals(List.of("RESUME", "ACKNOWLEDGE", "ABANDON"), map(map(schema.get("properties")).get("resolution")).get("enum"));
        assertTrue(resolution.get("description").toString().contains("does not override process lifecycle"));
        assertTrue(operation("/workspaces/{processId}/jobs/{jobId}/complete", "post").get("description").toString().contains("PAUSE and STOP"));
    }
    @Test void processLifecycleHeadersAndDurableCancellationAreExplicit() {
        var document = RunnerJson.read(OpenApiSpecGenerator.generate(RouteTable.ALL).getBytes(StandardCharsets.UTF_8));
        var operation = map(map(map(document.get("paths")).get("/v1/processes/{processInstanceId}/{command}")).get("post"));
        assertTrue(operation.get("description").toString().contains("terminationReason CANCELLED"));
        var parameters = (List<?>) operation.get("parameters");
        for (String header : List.of("Idempotency-Key", "X-Ravenroot-Expected-Generation")) {
            var value = parameters.stream().map(RunnerOpenApiTest::map).filter(parameter -> header.equals(parameter.get("name"))).findFirst().orElseThrow();
            assertEquals("header", value.get("in")); assertEquals(true, value.get("required"));
        }
        var response = map(map(map(map(operation.get("responses")).get("200")).get("content")).get("application/json"));
        assertEquals(List.of("outcome", "processInstanceId", "generation", "state", "reason", "traversals"), map(response.get("schema")).get("required"));
    }
}
