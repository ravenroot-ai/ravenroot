package ai.ravenroot.server.spec;

import ai.ravenroot.core.runner.RunnerJson;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Process lifecycle authority shared by interactive and runner continuation delivery. */
final class ProcessOpenApi {
    private ProcessOpenApi() { }
    static String operation(RouteDescriptor route, String method) {
        var operation = new LinkedHashMap<String, Object>();
        operation.put("summary", route.summary());
        operation.put("description", "PAUSE and STOP retain runner terminal evidence without delivering successors. RESUME and DRAIN "
                + "admit the exact parked invocation through its existing process revision and fence. CANCEL atomically records "
                + "FAILED with terminationReason CANCELLED and sticky runner stop requests; unknown effects retain workspace "
                + "ownership until fenced quiescence. Cancellation does not execute blocked or normal graph successors.");
        operation.put("x-assistant-posture", route.assistantPosture().name());
        operation.put("security", List.of(Map.of("bearerAuth", List.of())));
        operation.put("parameters", List.of(
                parameter("processInstanceId", "path", true, Map.of("type", "string", "format", "uuid")),
                parameter("command", "path", true, Map.of("type", "string", "enum", List.of("pause", "resume", "cancel", "drain", "stop"))),
                parameter("X-Ravenroot-Expected-Generation", "header", true, Map.of("type", "integer", "format", "int64", "minimum", 1)),
                parameter("Idempotency-Key", "header", true, Map.of("type", "string", "minLength", 1, "maxLength", 200)),
                parameter("reason", "query", false, Map.of("type", "string", "maxLength", 500))));
        var schema = Map.of("type", "object", "required", List.of("outcome", "processInstanceId", "generation", "state", "reason", "traversals"),
                "properties", Map.of("outcome", Map.of("type", "string", "enum", Arrays.stream(ai.ravenroot.core.process.ProcessLifecycleService.Code.values()).map(Enum::name).toList()),
                        "processInstanceId", Map.of("type", "string", "format", "uuid"),
                        "generation", Map.of("type", "integer", "format", "int64", "minimum", 0),
                        "state", Map.of("type", "string", "enum", Arrays.stream(ai.ravenroot.core.process.ProcessLifecycleService.State.values()).map(Enum::name).toList()),
                        "reason", Map.of("type", "string", "maxLength", 500),
                        "traversals", Map.of("type", "array", "items", Map.of("type", "object", "required", List.of("traversalId", "outcome"),
                                "properties", Map.of("traversalId", Map.of("type", "string", "format", "uuid"), "outcome", Map.of("type", "string"))))));
        var responses = new LinkedHashMap<String, Object>();
        for (String status : List.of("200", "404", "409")) responses.put(status, Map.of("description",
                status.equals("200") ? "Applied, replayed, terminal or partially settled" : status.equals("404") ? "Process not found" : "Stale generation or idempotency conflict",
                "content", Map.of("application/json", Map.of("schema", status.equals("404")
                        ? Map.of("oneOf", List.of(schema, Map.of("type", "object", "required", List.of("error")))) : schema))));
        for (String status : List.of("400", "401", "403", "500")) responses.put(status, Map.of("description", "Structured request, authorization or server error",
                "content", Map.of("application/json", Map.of("schema", Map.of("type", "object")))));
        operation.put("responses", responses);
        return "      \"" + method.toLowerCase(Locale.ROOT) + "\": " + new String(RunnerJson.write(operation), StandardCharsets.UTF_8);
    }
    private static Map<String, Object> parameter(String name, String location, boolean required, Map<String, Object> schema) {
        return Map.of("name", name, "in", location, "required", required, "schema", schema);
    }
}
