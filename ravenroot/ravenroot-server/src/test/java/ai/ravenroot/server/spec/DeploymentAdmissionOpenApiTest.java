package ai.ravenroot.server.spec;

import ai.ravenroot.core.runner.RunnerJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentAdmissionOpenApiTest {
    @Test void independentAdmissionHasAnInputAndReceiptWithoutLifecycleHeaders() {
        var operation = operation("/v1/deployments/{id}/executions", "post");
        var body = map(operation.get("requestBody"));
        assertEquals(true, body.get("required"));
        assertEquals("#/components/schemas/InitialStartRequest", schemaRef(body));
        var parameters = (List<?>) operation.get("parameters");
        assertFalse(parameters.stream().map(DeploymentAdmissionOpenApiTest::map)
                .anyMatch(parameter -> "header".equals(parameter.get("in"))),
                "execution admission must not require lifecycle generation or idempotency headers");
        var responses = map(operation.get("responses"));
        assertTrue(responses.keySet().containsAll(Set.of("200", "202", "400", "401", "403", "409", "503")));
        for (String status : List.of("200", "202", "503"))
            assertEquals("#/components/schemas/InitialStartResponse", schemaRef(map(responses.get(status))));
        assertTrue(map(responses.get("503")).get("description").toString().contains("same requestId"));
    }

    @Test void submittedSourceIsBoundAndDrawingRebuildIsExplicitlyPresentationOnly() {
        var source = operation("/v1/deployments/{id}/source", "get");
        var parameters = (List<?>) source.get("parameters");
        for (String field : List.of("incarnationId", "graphVersion")) {
            var parameter = parameters.stream().map(DeploymentAdmissionOpenApiTest::map)
                    .filter(value -> field.equals(value.get("name"))).findFirst().orElseThrow();
            assertEquals("query", parameter.get("in"));
            assertEquals(true, parameter.get("required"));
        }
        assertEquals("#/components/schemas/DeploymentSubmittedSource",
                schemaRef(map(map(source.get("responses")).get("200"))));
        assertTrue(map(source.get("responses")).containsKey("409"));
        var view = operation("/v1/deployments/{id}/view", "get");
        assertTrue(map(view.get("responses")).containsKey("503"));
        assertTrue(((List<?>) view.get("parameters")).stream().map(DeploymentAdmissionOpenApiTest::map)
                .anyMatch(parameter -> "layout".equals(parameter.get("name"))
                        && parameter.get("description").toString().contains("source, version and deployment remain unchanged")));
    }

    private static String schemaRef(Map<String, Object> value) {
        return map(map(map(value.get("content")).get("application/json")).get("schema")).get("$ref").toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }

    private static Map<String, Object> operation(String path, String method) {
        var document = RunnerJson.read(OpenApiSpecGenerator.generate(RouteTable.ALL).getBytes(StandardCharsets.UTF_8));
        return map(map(map(document.get("paths")).get(path)).get(method));
    }
}
