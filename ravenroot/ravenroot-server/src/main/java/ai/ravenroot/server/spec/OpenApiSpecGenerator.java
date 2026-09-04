package ai.ravenroot.server.spec;

import ai.ravenroot.server.audit.JsonStrings;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates an OpenAPI-3.0-shaped document from {@link RouteTable#ALL} (API-05).
 *
 * <h2>What this is not</h2>
 * <p>Request and response bodies are described in prose ({@link RouteDescriptor#summary()}) rather than
 * as exhaustive JSON Schema. Table-driven registration and the drift test verify the generated OpenAPI
 * against the server; that verification, rather than schema depth, makes the document trustworthy.
 * Deepening the schema later is additive to this generator, not a rewrite.</p>
 *
 * <h2>Not a stability promise</h2>
 * <p>The generated document's own {@code info.description} says so: this spec is checked in and
 * versioned with the API, but nothing has committed it as a
 * compatibility guarantee to third parties yet. Declaring one here that nobody agreed to would be the
 * same false-label defect in the opposite direction — a document
 * <em>overclaiming</em> a guarantee instead of a capability understating its own edge.</p>
 *
 * <h2>Escaping</h2>
 * <p>Uses {@link JsonStrings#escape}, the one shared implementation required by SEC-14, rather
 * than an eighth copy. Every string value written here is server-authored (route table content), never
 * caller- or document-derived, so injection is not the risk being guarded against — consistency with
 * the rest of the codebase's JSON-writing discipline is.</p>
 */
public final class OpenApiSpecGenerator {
    private OpenApiSpecGenerator() {
    }

    private static final String NOT_A_STABILITY_PROMISE =
            "Ravenroot's HTTP API. This document is generated from the same route table the server "
                    + "registers its endpoints from (RouteTable, RouteDescriptor) and is checked and "
                    + "versioned alongside the API it describes. It is NOT YET a compatibility promise to "
                    + "third parties: no version-support policy, deprecation window or backward-compatibility "
                    + "guarantee has been committed to for this API. Treat every shape here as subject to "
                    + "change without a deprecation cycle until that guarantee is made explicitly elsewhere.";

    public static String generate(List<RouteDescriptor> routes) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"openapi\": \"3.0.3\",\n");
        json.append("  \"info\": {\n");
        json.append("    \"title\": \"Ravenroot API\",\n");
        json.append("    \"version\": \"1.0.0\",\n");
        json.append("    \"description\": \"").append(JsonStrings.escape(NOT_A_STABILITY_PROMISE)).append("\"\n");
        json.append("  },\n");
        json.append("  \"paths\": {\n");
        json.append(routes.stream().sorted(java.util.Comparator.comparing(RouteDescriptor::path))
                .map(OpenApiSpecGenerator::pathEntry).collect(Collectors.joining(",\n")));
        json.append("\n  },\n");
        json.append("  \"components\": {\n");
        json.append("    \"securitySchemes\": {\n");
        json.append("      \"bearerAuth\": {\"type\": \"http\", \"scheme\": \"bearer\"}\n");
        json.append("    },\n");
        json.append(humanTaskSchemas());
        json.append("  }\n");
        json.append("}\n");
        return json.toString();
    }

    private static String pathEntry(RouteDescriptor route) {
        String operations = route.methods().stream().sorted().map(method -> operationEntry(route, method))
                .collect(Collectors.joining(",\n"));
        return "    \"" + JsonStrings.escape(route.path()) + "\": {\n" + operations + "\n    }";
    }

    private static String operationEntry(RouteDescriptor route, String method) {
        StringBuilder entry = new StringBuilder();
        entry.append("      \"").append(method.toLowerCase(java.util.Locale.ROOT)).append("\": {\n");
        entry.append("        \"summary\": \"").append(JsonStrings.escape(route.summary())).append("\",\n");
        // The assistant posture is emitted into the checked-in spec, which is what gives a
        // posture change a second place it can fail -- RouteTableSpecServerAgreementTest compares the
        // generated document against docs/api/openapi.json byte for byte, so flipping a posture without
        // regenerating reds a test instead of landing as an undocumented capability change.
        entry.append("        \"x-assistant-posture\": \"").append(route.assistantPosture().name())
                .append("\",\n");
        if (route.authenticated()) {
            entry.append("        \"security\": [{\"bearerAuth\": []}],\n");
        }
        String parameters = operationParameters(route, method);
        if (!parameters.isEmpty()) {
            entry.append(parameters);
        }
        if (isHumanTaskDecision(route, method)) {
            entry.append("        \"requestBody\": {\"required\": false, \"description\": "
                    + "\"Required when decision is resolve; ignored for deny and cancel.\", "
                    + "\"content\": {\"application/vnd.ravenroot.payload+json\": {\"schema\": "
                    + "{\"$ref\": \"#/components/schemas/PayloadEnvelope\"}}}},\n");
        }
        entry.append("        \"responses\": {\n");
        var responses = new java.util.ArrayList<String>();
        route.successStatuses().stream().sorted().forEach(status ->
                responses.add(successResponse(route, method, status)));
        route.wireErrorCodes().forEach(code ->
                responses.add("          \"" + statusOrDefault(code) + "\": {\"description\": \""
                        + JsonStrings.escape(code) + "\"}"));
        entry.append(String.join(",\n", dedupeByKey(responses)));
        entry.append("\n        }\n");
        entry.append("      }");
        return entry.toString();
    }

    /**
     * Declares every {@code {name}} in a templated path as a required path parameter.
     *
     * <p>OpenAPI 3.0 requires that a templated path variable have a matching {@code required: true}
     * path parameter; without one the document is not valid and a generated client has no name to
     * bind the segment to. The generator emitted the templated path as a bare key and nothing else,
     * so the six {@code /v1/program-artifacts/&#123;id&#125;/*} entries were invalid for this reason.
     * Deriving the declaration from {@code route.path()} handles all of them uniformly and, more
     * to the point, means a route added later cannot reintroduce the defect by forgetting to declare
     * something by hand — there is nothing to forget.</p>
     */
    private static String operationParameters(RouteDescriptor route, String method) {
        var names = new java.util.LinkedHashSet<String>();
        var matcher = java.util.regex.Pattern.compile("\\{([^/{}]+)}").matcher(route.path());
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        var parameters = new java.util.ArrayList<String>();
        names.forEach(name -> parameters.add("          {\"name\": \""
                + JsonStrings.escape(name) + "\", \"in\": \"path\", "
                + "\"required\": true, \"schema\": {\"type\": \"string\"}}"));
        if ("/v1/human-tasks".equals(route.path()) && "GET".equals(method)) {
            parameters.add("          {\"name\": \"status\", \"in\": \"query\", \"required\": false, "
                    + "\"description\": \"Comma-separated lifecycle statuses.\", \"schema\": "
                    + "{\"type\": \"string\"}}");
            parameters.add("          {\"name\": \"includeTerminal\", \"in\": \"query\", "
                    + "\"required\": false, \"schema\": {\"type\": \"boolean\", \"default\": false}}");
            parameters.add("          {\"name\": \"cursor\", \"in\": \"query\", \"required\": false, "
                    + "\"schema\": {\"type\": \"string\", \"format\": \"uuid\"}}");
            parameters.add("          {\"name\": \"limit\", \"in\": \"query\", \"required\": false, "
                    + "\"schema\": {\"type\": \"integer\", \"minimum\": 1, \"maximum\": 100, "
                    + "\"default\": 50}}");
        }
        if (isHumanTaskDecision(route, method)) {
            parameters.add("          {\"name\": \"generation\", \"in\": \"query\", \"required\": true, "
                    + "\"schema\": {\"type\": \"integer\", \"format\": \"int64\", \"minimum\": 1}}");
        }
        return parameters.isEmpty() ? "" : parameters.stream()
                .collect(Collectors.joining(",\n", "        \"parameters\": [\n", "\n        ],\n"));
    }

    private static boolean isHumanTaskDecision(RouteDescriptor route, String method) {
        return "/v1/human-tasks/{taskId}/{decision}".equals(route.path()) && "POST".equals(method);
    }

    private static String successResponse(RouteDescriptor route, String method, int status) {
        String schema = null;
        if ("/v1/human-tasks".equals(route.path()) && "GET".equals(method)) {
            schema = "HumanTaskInboxPage";
        } else if (isHumanTaskDecision(route, method)) {
            schema = "HumanTaskDecisionResult";
        }
        return "          \"" + status + "\": {\"description\": \"success\""
                + (schema == null ? "}" : ", \"content\": {\"application/json\": "
                        + "{\"schema\": {\"$ref\": \"#/components/schemas/" + schema + "\"}}}}");
    }

    private static String humanTaskSchemas() {
        return "    \"schemas\": {\n"
                + "      \"PayloadEnvelope\": {\"type\": \"object\", \"required\": [\"schema\", "
                + "\"schemaVersion\", \"kind\", \"value\"], \"properties\": {"
                + "\"schema\": {\"type\": \"string\"}, \"schemaVersion\": {\"type\": \"string\"}, "
                + "\"kind\": {\"type\": \"string\"}, \"value\": {}}},\n"
                + "      \"HumanTaskInboxItem\": {\"type\": \"object\", \"required\": [\"taskId\", "
                + "\"processInstanceId\", \"nodeId\", \"title\", \"description\", \"status\", "
                + "\"generation\", \"responseSchema\", \"responseSchemaVersion\", \"responseKind\", "
                + "\"maxResponseBytes\", \"expiresAt\"], \"properties\": {"
                + "\"taskId\": {\"type\": \"string\", \"format\": \"uuid\"}, "
                + "\"processInstanceId\": {\"type\": \"string\", \"format\": \"uuid\"}, "
                + "\"nodeId\": {\"type\": \"string\"}, \"title\": {\"type\": \"string\"}, "
                + "\"description\": {\"type\": \"string\"}, \"status\": {\"type\": \"string\"}, "
                + "\"generation\": {\"type\": \"integer\", \"format\": \"int64\"}, "
                + "\"responseSchema\": {\"type\": \"string\"}, \"responseSchemaVersion\": "
                + "{\"type\": \"string\"}, \"responseKind\": {\"type\": \"string\"}, "
                + "\"maxResponseBytes\": {\"type\": \"integer\"}, \"expiresAt\": "
                + "{\"type\": \"string\", \"format\": \"date-time\"}, \"escalateAt\": "
                + "{\"type\": \"string\", \"format\": \"date-time\"}}},\n"
                + "      \"HumanTaskInboxPage\": {\"type\": \"object\", \"required\": [\"items\", "
                + "\"nextCursor\"], \"properties\": {\"items\": {\"type\": \"array\", \"items\": "
                + "{\"$ref\": \"#/components/schemas/HumanTaskInboxItem\"}}, \"nextCursor\": "
                + "{\"type\": \"string\", \"format\": \"uuid\", \"nullable\": true}}},\n"
                + "      \"HumanTaskDecisionResult\": {\"type\": \"object\", \"required\": [\"outcome\", "
                + "\"taskId\", \"generation\"], \"properties\": {\"outcome\": {\"type\": \"string\"}, "
                + "\"taskId\": {\"type\": \"string\", \"format\": \"uuid\"}, \"generation\": "
                + "{\"type\": \"integer\", \"format\": \"int64\"}, \"resumeTraversalId\": "
                + "{\"type\": \"string\", \"format\": \"uuid\"}}}\n"
                + "    }\n";
    }

    /**
     * Several wire codes share a status (two different 413s, several 400s): OpenAPI keys responses by
     * status, so entries collapse onto the first description for that status rather than producing
     * invalid duplicate keys. The full per-code vocabulary is what {@link WireErrorCodes#all()} is for.
     */
    private static List<String> dedupeByKey(List<String> entries) {
        var seen = new java.util.LinkedHashMap<String, String>();
        for (String entry : entries) {
            String key = entry.substring(0, entry.indexOf(':'));
            seen.putIfAbsent(key, entry);
        }
        return List.copyOf(seen.values());
    }

    /**
     * The rate limiter's own codes carry their status alongside them at every call site
     * ({@code RateLimitDecision.rejected(431, "HEADER_VALUE_TOO_LARGE", ...)}, etc.) rather than through
     * a single enum's {@code status()} the way {@link ai.ravenroot.api.error.ErrorCode} does. Mirrored
     * here for the same reason {@link WireErrorCodes}' six literals are: naming each rather than
     * defaulting them all onto one status, which would misdescribe the 431/414 shape-rejection codes as
     * 429 throttling.
     */
    private static final java.util.Map<String, Integer> SERVER_CODE_STATUSES = java.util.Map.ofEntries(
            java.util.Map.entry(WireErrorCodes.HEADER_VALUE_TOO_LARGE, 431),
            java.util.Map.entry(WireErrorCodes.TOO_MANY_HEADERS, 431),
            java.util.Map.entry(WireErrorCodes.HEADERS_TOO_LARGE, 431),
            java.util.Map.entry(WireErrorCodes.QUERY_TOO_LARGE, 414),
            java.util.Map.entry(WireErrorCodes.TOO_MANY_QUERY_PARAMETERS, 414),
            java.util.Map.entry(WireErrorCodes.LIMITER_CAPACITY_EXHAUSTED, 429),
            java.util.Map.entry(ai.ravenroot.server.ratelimit.ActiveExecutionRegistry.TENANT_LIMIT_CODE, 429),
            java.util.Map.entry(ai.ravenroot.server.ratelimit.ActiveExecutionRegistry.GLOBAL_LIMIT_CODE, 429),
            java.util.Map.entry(WireErrorCodes.EMBED_REQUEST_INVALID, 400),
            java.util.Map.entry(WireErrorCodes.EMBED_METHOD_NOT_ALLOWED, 405),
            java.util.Map.entry(WireErrorCodes.EMBED_SESSION_UNAVAILABLE, 403),
            java.util.Map.entry(WireErrorCodes.EMBED_TEMPORARILY_UNAVAILABLE, 503),
            java.util.Map.entry(WireErrorCodes.EMBED_DATA_TOO_LARGE, 413),
            java.util.Map.entry(WireErrorCodes.EMBED_REQUEST_TOO_LARGE, 413));

    private static int statusOrDefault(String code) {
        try {
            return ai.ravenroot.api.error.ErrorCode.valueOf(code).status();
        } catch (IllegalArgumentException notAnErrorCodeName) {
            Integer status = SERVER_CODE_STATUSES.get(code);
            if (status == null) {
                throw new IllegalStateException("Wire code '" + code + "' is neither an ErrorCode nor a "
                        + "known server code -- RouteTable and this status map have drifted");
            }
            return status;
        }
    }
}
