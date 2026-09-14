package ai.ravenroot.server.spec;

import ai.ravenroot.core.runner.RunnerJson;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Concrete runner protocol contracts, including bounded binary transfer and operator recovery. */
final class RunnerOpenApi {
    private RunnerOpenApi() { }
    static String operation(RouteDescriptor route, String method) {
        var operation = new LinkedHashMap<String, Object>();
        operation.put("summary", route.summary());
        if (route.path().endsWith("/complete") || route.path().endsWith("/resolve-continuation")) {
            operation.put("description", "Terminal evidence is durable before graph delivery. Process PAUSE and STOP park delivery; "
                    + "only process RESUME or DRAIN admits it. Runner continuation RESUME does not override process lifecycle. "
                    + "Process CANCEL prevents normal successors while preserving fenced quiescence evidence.");
        }
        operation.put("x-assistant-posture", route.assistantPosture().name());
        operation.put("security", List.of(Map.of("bearerAuth", List.of())));
        var parameters = new ArrayList<Map<String, Object>>();
        var matcher = java.util.regex.Pattern.compile("\\{([^/{}]+)}").matcher(route.path());
        while (matcher.find()) parameters.add(parameter(matcher.group(1), "path", true,
                matcher.group(1).equals("key") ? string() : Map.of("type", "string", "format", "uuid")));
        String path = route.path();
        if (path.endsWith("/health") || path.endsWith("/assignments")) parameters.add(parameter("cursor", "query", false, string()));
        if (path.endsWith("/audit")) parameters.add(parameter("afterOffset", "query", false,
                Map.of("type", "integer", "format", "int64", "minimum", 0, "default", 0)));
        boolean upload = method.equals("POST") && path.endsWith("/artifacts");
        boolean complete = path.endsWith("/complete");
        if (upload || complete) parameters.add(parameter("X-Runner-Fence", "header", true, positiveLong()));
        if (upload) parameters.add(parameter("X-Runner-Artifact-Kind", "header", true,
                Map.of("type", "string", "enum", Arrays.stream(ai.ravenroot.api.runner.RunnerArtifact.Kind.values()).map(Enum::name).toList())));
        if (path.endsWith("/{artifactId}")) parameters.add(parameter("Accept", "header", false,
                Map.of("type", "string", "enum", List.of("application/json", "application/octet-stream"))));
        if (!parameters.isEmpty()) operation.put("parameters", parameters);
        if (upload || complete) operation.put("requestBody", body(upload ? "application/octet-stream"
                : "application/vnd.ravenroot.runner-result.v1", Map.of("type", "string", "format", "binary")));
        else if (path.endsWith("/resolve-continuation")) operation.put("requestBody", body("application/json", object(
                List.of("expectedRevision", "resolution"), Map.of("expectedRevision", positiveLong(), "resolution",
                        Map.of("type", "string", "enum", List.of("RESUME", "ACKNOWLEDGE", "ABANDON"))))));
        else if (path.endsWith("/claim") || path.endsWith("/heartbeat") || path.endsWith("/reconcile-report")) {
            var properties = new LinkedHashMap<String, Object>();
            properties.put("ttlSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 300));
            var required = new ArrayList<>(List.of("ttlSeconds"));
            if (path.endsWith("/heartbeat")) { properties.put("fence", positiveLong()); required.add("fence"); }
            operation.put("requestBody", body("application/json", object(required, properties)));
        } else if (method.equals("PUT") || path.endsWith("/register")) operation.put("requestBody", body("application/json",
                Map.of("type", "object", "description", "Bounded protocol-v1 definition/registration. Catalog PUT requires expectedRevision and explicit approved.")));
        var responses = new LinkedHashMap<String, Object>();
        Map<String, Object> schema = Map.of("type", "object");
        String type = "application/json";
        if (path.endsWith("/{jobId}") || complete || path.endsWith("/claim") || path.endsWith("/heartbeat") || path.endsWith("/reconcile-report")) {
            type = "application/vnd.ravenroot.runner-assignment.v1"; schema = Map.of("type", "string", "format", "binary");
        } else if (upload) schema = object(List.of("artifactId", "kind", "sha256", "sizeBytes"), Map.of(
                "artifactId", Map.of("type", "string", "format", "uuid"), "kind", string(),
                "sha256", Map.of("type", "string", "pattern", "^[0-9a-f]{64}$"),
                "sizeBytes", Map.of("type", "integer", "format", "int64", "minimum", 0)));
        else if (path.endsWith("/health") || path.endsWith("/assignments")) schema = object(
                path.endsWith("/health") ? List.of("items", "nextCursor", "pageMetrics", "observedAt") : List.of("items", "nextCursor"), Map.of(
                "items", Map.of("type", "array", "maxItems", 4096, "items", Map.of("type", "object")),
                "nextCursor", Map.of("type", "string", "nullable", true),
                "observedAt", string(),
                "pageMetrics", Map.of("type", "object", "additionalProperties", Map.of("type", "integer", "format", "int64"))));
        else if (path.endsWith("/audit")) schema = object(List.of("items", "nextOffset"), Map.of(
                "items", Map.of("type", "array", "maxItems", 64, "items", Map.of("type", "object")), "nextOffset", Map.of("type", "integer", "format", "int64", "minimum", 0)));
        var content = new LinkedHashMap<String, Object>(); content.put(type, Map.of("schema", schema));
        if (path.endsWith("/{artifactId}")) {
            content.put("application/octet-stream", Map.of("schema", Map.of("type", "string", "format", "binary")));
            content.put("application/json", Map.of("schema", object(List.of("content", "truncated"), Map.of(
                    "content", Map.of("type", "string", "maxLength", 65536), "truncated", Map.of("type", "boolean")))));
        }
        for (int status : route.successStatuses()) {
            var response = new LinkedHashMap<String, Object>();
            response.put("description", "Accepted"); response.put("content", content);
            if (path.endsWith("/{artifactId}")) response.put("headers", Map.of(
                    "Content-Disposition", Map.of("description", "Binary responses are attachments", "schema", Map.of("type", "string", "enum", List.of("attachment"))),
                    "X-Content-Type-Options", Map.of("description", "Binary responses disable content sniffing", "schema", Map.of("type", "string", "enum", List.of("nosniff")))));
            responses.put(Integer.toString(status), response);
        }
        var errors = Map.of("400", "INVALID_RUNNER_REQUEST", "401", "Authentication required", "403", "RUNNER_ACCESS_DENIED",
                "404", "RUNNER_RESOURCE_NOT_FOUND", "409", "RUNNER_STATE_CONFLICT or RUNNER_STORE_CONFLICT", "501", "RUNNER_PLANE_UNAVAILABLE");
        errors.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(error -> responses.put(error.getKey(),
                Map.of("description", error.getValue(), "content", Map.of("application/json", Map.of("schema",
                        Map.of("type", "object", "required", List.of("error"), "properties", Map.of("error", string(), "requestId", string())))))));
        operation.put("responses", responses);
        return "      \"" + method.toLowerCase(Locale.ROOT) + "\": " + new String(RunnerJson.write(operation), StandardCharsets.UTF_8);
    }
    private static Map<String, Object> string() { return Map.of("type", "string"); }
    private static Map<String, Object> positiveLong() { return Map.of("type", "integer", "format", "int64", "minimum", 1); }
    private static Map<String, Object> parameter(String name, String location, boolean required, Map<String, Object> schema) {
        return Map.of("name", name, "in", location, "required", required, "schema", schema);
    }
    private static Map<String, Object> object(List<String> required, Map<String, Object> properties) {
        return Map.of("type", "object", "required", required, "properties", properties);
    }
    private static Map<String, Object> body(String type, Map<String, Object> schema) {
        return Map.of("required", true, "content", Map.of(type, Map.of("schema", schema)));
    }
}
