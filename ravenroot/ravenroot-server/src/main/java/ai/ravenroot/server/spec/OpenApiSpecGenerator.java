package ai.ravenroot.server.spec;

import ai.ravenroot.api.persistence.HumanTaskPolicy;
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
        String existingSchemas = humanTaskSchemas();
        json.append(existingSchemas, 0, existingSchemas.lastIndexOf("\n    }"));
        json.append(",\n").append(executionEventSchemas()).append("    }\n");
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
        if (isHumanTaskConfirmation(route, method)) {
            entry.append("        \"requestBody\": {\"required\": true, \"content\": "
                    + "{\"application/json\": {\"schema\": {\"$ref\": "
                    + "\"#/components/schemas/HumanTaskConfirmationRequest\"}}}},\n");
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
        if (isHumanTaskAttention(route, method)) {
            parameters.add(queryParameter("graphVersion", "string",
                    "Required for aggregate mode; exact durable graph version."));
            parameters.add(queryParameter("deploymentId", "string",
                    "Aggregate mode requires exactly one of deploymentId and processInstanceId."));
            parameters.add(queryParameter("processInstanceId", "string",
                    "Aggregate mode requires exactly one of processInstanceId and deploymentId."));
            parameters.add(queryParameter("traversalId", "string", "Optional aggregate traversal filter."));
            parameters.add(queryParameter("nodeId", "string", "Optional aggregate node filter."));
            parameters.add(queryParameter("taskId", "string",
                    "With generation and no graph context, selects the exact recovery locator."));
            parameters.add(queryParameter("generation", "integer",
                    "Required with taskId in exact-locator mode."));
            parameters.add(queryParameter("cursor", "string", "Opaque aggregate page cursor."));
            parameters.add("          {\"name\": \"limit\", \"in\": \"query\", \"required\": false, "
                    + "\"schema\": {\"type\": \"integer\", \"minimum\": 1, \"maximum\": "
                    + HumanTaskPolicy.Confirmation.HARD_MAX_ATTENTION_PAGE_SIZE + ", \"default\": "
                    + HumanTaskPolicy.Confirmation.DEFAULTS.attentionDefaultPageSize() + "}}");
        }
        if (isHumanTaskDecision(route, method) || isHumanTaskConfirmation(route, method)) {
            parameters.add("          {\"name\": \"generation\", \"in\": \"query\", \"required\": true, "
                    + "\"schema\": {\"type\": \"integer\", \"format\": \"int64\", \"minimum\": 1}}");
        }
        return parameters.isEmpty() ? "" : parameters.stream()
                .collect(Collectors.joining(",\n", "        \"parameters\": [\n", "\n        ],\n"));
    }

    private static boolean isHumanTaskDecision(RouteDescriptor route, String method) {
        return "/v1/human-tasks/{taskId}/{decision}".equals(route.path()) && "POST".equals(method);
    }

    private static boolean isHumanTaskAttention(RouteDescriptor route, String method) {
        return "/v1/human-tasks/attention".equals(route.path()) && "GET".equals(method);
    }

    private static boolean isHumanTaskConfirmation(RouteDescriptor route, String method) {
        return "/v1/human-tasks/{taskId}/confirmation/{action}".equals(route.path())
                && "POST".equals(method);
    }

    private static String queryParameter(String name, String type, String description) {
        return "          {\"name\": \"" + name + "\", \"in\": \"query\", \"required\": false, "
                + "\"description\": \"" + JsonStrings.escape(description) + "\", \"schema\": {\"type\": \""
                + type + "\"}}";
    }

    private static String successResponse(RouteDescriptor route, String method, int status) {
        if ("/v1/events".equals(route.path()) && "GET".equals(method) && status == 200) {
            return "          \"200\": {\"description\":\"UTF-8 Server-Sent Events text, not a JSON document or JSON array. Parse blank"
                     + "-line-delimited frames first; only event: execution has ExecutionStreamEvent JSON data. Keepalive comments dis"
                     + "patch no event. Named stream-truncated and stream-overrun controls terminate observation and require reconcili"
                     + "ation. The x-ravenroot-sse-events extension maps event names to decoded data schemas; ordinary OpenAPI tools m"
                     + "ay ignore it.\",\"headers\":{\"X-Ravenroot-Event-Source\":{\"description\":\"Must equal every execution payload source"
                     + ".\",\"schema\":{\"type\":\"string\",\"enum\":[\"RING\",\"DURABLE\"]}},\"X-Ravenroot-Event-Continuity\":{\"description\":\"RING i"
                     + "s PROCESS_LOCAL and loses continuity on restart; DURABLE is DURABLE within the authenticated tenant journal.\","
                     + "\"schema\":{\"type\":\"string\",\"enum\":[\"PROCESS_LOCAL\",\"DURABLE\"]}},\"X-Ravenroot-Event-Schema-Version\":{\"descriptio"
                     + "n\":\"Execution data schema version; named controls and comments are separate shapes.\",\"schema\":{\"type\":\"integer"
                     + "\",\"enum\":[1]}}},\"content\":{\"text/event-stream\":{\"schema\":{\"type\":\"string\"},\"x-ravenroot-sse-events\":{\"executio"
                     + "n\":{\"dataSchema\":{\"$ref\":\"#/components/schemas/ExecutionStreamEvent\"}},\"stream-truncated\":{\"dataSchema\":{\"$ref"
                     + "\":\"#/components/schemas/EventStreamTruncated\"}},\"stream-overrun\":{\"dataSchema\":{\"$ref\":\"#/components/schemas/E"
                     + "ventStreamOverrun\"}}},\"examples\":{\"ring\":{\"summary\":\"RING execution frame followed by a keepalive comment\",\"va"
                     + "lue\":\"id: 7\\nevent: execution\\ndata: {\\\"schemaVersion\\\":1,\\\"occurredAt\\\":\\\"2026-01-01T00:00:00Z\\\",\\\"graphVersi"
                     + "on\\\":\\\"graph-v1\\\",\\\"processInstanceId\\\":\\\"10000000-0000-0000-0000-000000000001\\\",\\\"traversalId\\\":\\\"20000000-00"
                     + "00-0000-0000-000000000002\\\",\\\"invocationId\\\":null,\\\"attemptId\\\":null,\\\"nodeId\\\":null,\\\"edgeId\\\":null,\\\"source\\"
                     + "\":\\\"RING\\\",\\\"id\\\":\\\"7\\\",\\\"eventType\\\":\\\"EXECUTION_STARTED\\\",\\\"sequence\\\":7,\\\"engineId\\\":\\\"pekko\\\",\\\"executionI"
                     + "d\\\":\\\"20000000-0000-0000-0000-000000000002\\\",\\\"type\\\":\\\"EXECUTION_STARTED\\\",\\\"activeInstances\\\":0,\\\"inFlightAr"
                     + "rivals\\\":0,\\\"fallback\\\":false,\\\"description\\\":\\\"Execution started.\\\",\\\"publicReason\\\":null,\\\"message\\\":null,\\\""
                     + "messageRedacted\\\":false,\\\"messageTruncated\\\":false,\\\"processingDuration\\\":null}\\n\\n: keepalive\\n\\n\"},\"durable\""
                     + ":{\"summary\":\"DURABLE execution frame\",\"value\":\"id: 12\\nevent: execution\\ndata: {\\\"schemaVersion\\\":1,\\\"occurred"
                     + "At\\\":\\\"2026-01-01T00:00:00Z\\\",\\\"graphVersion\\\":\\\"graph-v1\\\",\\\"processInstanceId\\\":\\\"10000000-0000-0000-0000-00"
                     + "0000000001\\\",\\\"traversalId\\\":\\\"20000000-0000-0000-0000-000000000002\\\",\\\"invocationId\\\":null,\\\"attemptId\\\":null"
                     + ",\\\"nodeId\\\":null,\\\"edgeId\\\":null,\\\"source\\\":\\\"DURABLE\\\",\\\"id\\\":\\\"12\\\",\\\"eventId\\\":\\\"30000000-0000-0000-0000-00"
                     + "0000000003\\\",\\\"journalOffset\\\":12,\\\"streamSequence\\\":1,\\\"eventType\\\":\\\"EXECUTION_STARTED\\\",\\\"description\\\":\\\"E"
                     + "xecution started.\\\",\\\"causationId\\\":null,\\\"handlerId\\\":null}\\n\\n\"},\"truncated\":{\"summary\":\"Terminal retained-h"
                     + "istory gap; reconcile before resuming\",\"value\":\"event: stream-truncated\\ndata: {\\\"code\\\":\\\"STREAM_RETENTION_EX"
                     + "CEEDED\\\",\\\"retainedFrom\\\":10,\\\"resumeFrom\\\":9}\\n\\n\"},\"overrun\":{\"summary\":\"Terminal slow-consumer control\",\"va"
                     + "lue\":\"id: 7\\nevent: stream-overrun\\ndata: {\\\"code\\\":\\\"STREAM_CONSUMER_TOO_SLOW\\\",\\\"resumeAfter\\\":7}\\n\\n\"}}}}}";
        }
        String schema = null;
        if ("/v1/human-tasks".equals(route.path()) && "GET".equals(method)) {
            schema = "HumanTaskInboxPage";
        } else if (isHumanTaskDecision(route, method)) {
            schema = "HumanTaskDecisionResult";
        } else if (isHumanTaskAttention(route, method)) {
            schema = "HumanTaskAttentionPage";
        } else if (isHumanTaskConfirmation(route, method)) {
            schema = "HumanTaskConfirmationResult";
        }
        return "          \"" + status + "\": {\"description\": \"success\""
                + (schema == null ? "}" : ", \"content\": {\"application/json\": "
                        + "{\"schema\": {\"$ref\": \"#/components/schemas/" + schema + "\"}}}}");
    }

    /** JSON data schemas for named SSE frames; unknown future members remain permitted. */
    private static String executionEventSchemas() {
        return "      \"ExecutionStreamEventBase\": {\"type\":\"object\",\"description\":\"Version 1 JSON data of an execution SSE fram"
                 + "e, not the whole SSE response. Compare id only within the authenticated tenant and source; RING also requires "
                 + "the same process-local continuity domain. Unknown fields and event classifiers are allowed. A conflicting type"
                 + " alias is invalid. IDs and aliases require semantic equality checks outside OpenAPI 3.0.3.\",\"required\":[\"schem"
                 + "aVersion\",\"source\",\"id\",\"eventType\",\"occurredAt\",\"processInstanceId\",\"traversalId\"],\"additionalProperties\":tru"
                 + "e,\"properties\":{\"schemaVersion\":{\"type\":\"integer\",\"enum\":[1]},\"source\":{\"type\":\"string\",\"enum\":[\"RING\",\"DURABL"
                 + "E\"]},\"id\":{\"type\":\"string\",\"pattern\":\"^(0|-?[1-9][0-9]*)$\",\"maxLength\":20,\"description\":\"Canonical signed deci"
                 + "mal long string equal to the SSE id and native cursor. Not a globally unique event UUID. Use exact integer ari"
                 + "thmetic; never compare against a rounded floating-point cursor.\"},\"eventType\":{\"type\":\"string\",\"minLength\":1,\""
                 + "description\":\"Canonical classifier. Unknown values remain readable.\"},\"occurredAt\":{\"type\":\"string\",\"descripti"
                 + "on\":\"Producer occurrence time in Java Instant text form, including supported extended years. Diagnostic time, "
                 + "not an ordering axis.\"},\"processInstanceId\":{\"type\":\"string\",\"format\":\"uuid\"},\"traversalId\":{\"type\":\"string\",\""
                 + "format\":\"uuid\"},\"graphVersion\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"},\"invocationId\":{\"type\":\"strin"
                 + "g\",\"format\":\"uuid\",\"nullable\":true},\"attemptId\":{\"type\":\"string\",\"format\":\"uuid\",\"nullable\":true},\"nodeId\":{\"t"
                 + "ype\":\"string\",\"nullable\":true},\"edgeId\":{\"type\":\"string\",\"description\":\"Stable edge identity; unchanged 8192 U"
                 + "TF-8 byte limit and shared traversal wire budget.\",\"nullable\":true},\"type\":{\"type\":\"string\",\"deprecated\":true,"
                 + "\"description\":\"Compatibility alias retained on live writers. If present it must equal eventType. No removal da"
                 + "te is scheduled.\"}}},\n      \"ExecutionStreamEvent\": {\"oneOf\":[{\"$ref\":\"#/components/schemas/RingExecutionStrea"
                 + "mEvent\"},{\"$ref\":\"#/components/schemas/DurableExecutionStreamEvent\"}],\"discriminator\":{\"propertyName\":\"source\""
                 + ",\"mapping\":{\"RING\":\"#/components/schemas/RingExecutionStreamEvent\",\"DURABLE\":\"#/components/schemas/DurableExec"
                 + "utionStreamEvent\"}}},\n      \"RingExecutionStreamEvent\": {\"allOf\":[{\"$ref\":\"#/components/schemas/ExecutionStrea"
                 + "mEventBase\"},{\"type\":\"object\",\"required\":[\"sequence\",\"engineId\",\"executionId\",\"type\",\"activeInstances\",\"inFlig"
                 + "htArrivals\",\"fallback\",\"publicReason\",\"message\",\"messageRedacted\",\"messageTruncated\",\"processingDuration\"],\"ad"
                 + "ditionalProperties\":true,\"properties\":{\"source\":{\"type\":\"string\",\"enum\":[\"RING\"]},\"sequence\":{\"type\":\"integer\""
                 + ",\"format\":\"int64\"},\"engineId\":{\"type\":\"string\"},\"executionId\":{\"type\":\"string\",\"format\":\"uuid\",\"description\":\""
                 + "Legacy traversalId alias; values must agree.\"},\"activeInstances\":{\"type\":\"integer\",\"minimum\":0},\"inFlightArriv"
                 + "als\":{\"type\":\"integer\",\"minimum\":0},\"fallback\":{\"type\":\"boolean\"},\"publicReason\":{\"type\":\"string\",\"maxLength\":"
                 + "64,\"pattern\":\"^[A-Za-z0-9._:-]+$\",\"nullable\":true},\"message\":{\"type\":\"string\",\"description\":\"Already-safe boun"
                 + "ded author message, not raw exception detail.\",\"nullable\":true},\"messageRedacted\":{\"type\":\"boolean\"},\"messageT"
                 + "runcated\":{\"type\":\"boolean\"},\"output\":{\"description\":\"Optional bounded, targeted-redacted built-in log output."
                 + " Absent is not an empty output.\"},\"outputRedacted\":{\"type\":\"boolean\"},\"outputTruncated\":{\"type\":\"boolean\"},\"pr"
                 + "ocessingDuration\":{\"type\":\"number\",\"minimum\":0,\"description\":\"Measured seconds; null means not measured, never"
                 + " zero by substitution.\",\"nullable\":true}},\"not\":{\"anyOf\":[{\"required\":[\"journalOffset\"]},{\"required\":[\"streamS"
                 + "equence\"]},{\"required\":[\"eventId\"]},{\"required\":[\"causationId\"]},{\"required\":[\"handlerId\"]}]}}],\"example\":{\"sc"
                 + "hemaVersion\":1,\"occurredAt\":\"2026-01-01T00:00:00Z\",\"graphVersion\":\"graph-v1\",\"processInstanceId\":\"10000000-000"
                 + "0-0000-0000-000000000001\",\"traversalId\":\"20000000-0000-0000-0000-000000000002\",\"invocationId\":null,\"attemptId\""
                 + ":null,\"nodeId\":null,\"edgeId\":null,\"source\":\"RING\",\"id\":\"7\",\"eventType\":\"EXECUTION_STARTED\",\"sequence\":7,\"engin"
                 + "eId\":\"pekko\",\"executionId\":\"20000000-0000-0000-0000-000000000002\",\"type\":\"EXECUTION_STARTED\",\"activeInstances\""
                 + ":0,\"inFlightArrivals\":0,\"fallback\":false,\"description\":\"Execution started.\",\"publicReason\":null,\"message\":null"
                 + ",\"messageRedacted\":false,\"messageTruncated\":false,\"processingDuration\":null}},\n      \"DurableExecutionStreamEv"
                 + "ent\": {\"allOf\":[{\"$ref\":\"#/components/schemas/ExecutionStreamEventBase\"},{\"type\":\"object\",\"required\":[\"eventId"
                 + "\",\"journalOffset\",\"streamSequence\",\"causationId\",\"handlerId\"],\"additionalProperties\":true,\"properties\":{\"sourc"
                 + "e\":{\"type\":\"string\",\"enum\":[\"DURABLE\"]},\"id\":{\"type\":\"string\",\"pattern\":\"^[1-9][0-9]*$\",\"maxLength\":19},\"event"
                 + "Id\":{\"type\":\"string\",\"format\":\"uuid\",\"description\":\"Persisted journal deduplication identity, distinct from cu"
                 + "rsor id; causationId refers to this UUID space.\"},\"journalOffset\":{\"type\":\"integer\",\"format\":\"int64\",\"minimum\""
                 + ":1,\"description\":\"Tenant-local durable order and SSE resume cursor.\"},\"streamSequence\":{\"type\":\"integer\",\"form"
                 + "at\":\"int64\",\"minimum\":1,\"description\":\"Order within the process instance, not the tenant resume cursor.\"},\"cau"
                 + "sationId\":{\"type\":\"string\",\"format\":\"uuid\",\"description\":\"Journal event that caused this one; null when the ca"
                 + "use lies outside the journal.\",\"nullable\":true},\"handlerId\":{\"type\":\"string\",\"format\":\"uuid\",\"nullable\":true}}"
                 + ",\"not\":{\"anyOf\":[{\"required\":[\"sequence\"]},{\"required\":[\"engineId\"]},{\"required\":[\"executionId\"]},{\"required\":"
                 + "[\"activeInstances\"]},{\"required\":[\"inFlightArrivals\"]},{\"required\":[\"fallback\"]},{\"required\":[\"publicReason\"]}"
                 + ",{\"required\":[\"message\"]},{\"required\":[\"messageRedacted\"]},{\"required\":[\"messageTruncated\"]},{\"required\":[\"out"
                 + "put\"]},{\"required\":[\"outputRedacted\"]},{\"required\":[\"outputTruncated\"]},{\"required\":[\"processingDuration\"]}]}}"
                 + "],\"example\":{\"schemaVersion\":1,\"occurredAt\":\"2026-01-01T00:00:00Z\",\"graphVersion\":\"graph-v1\",\"processInstanceI"
                 + "d\":\"10000000-0000-0000-0000-000000000001\",\"traversalId\":\"20000000-0000-0000-0000-000000000002\",\"invocationId\":"
                 + "null,\"attemptId\":null,\"nodeId\":null,\"edgeId\":null,\"source\":\"DURABLE\",\"id\":\"12\",\"eventId\":\"30000000-0000-0000-0"
                 + "000-000000000003\",\"journalOffset\":12,\"streamSequence\":1,\"eventType\":\"EXECUTION_STARTED\",\"description\":\"Executi"
                 + "on started.\",\"causationId\":null,\"handlerId\":null}},\n      \"EventStreamTruncated\": {\"type\":\"object\",\"required\":"
                 + "[\"code\",\"retainedFrom\",\"resumeFrom\"],\"additionalProperties\":true,\"properties\":{\"code\":{\"type\":\"string\",\"enum\":"
                 + "[\"STREAM_RETENTION_EXCEEDED\"]},\"retainedFrom\":{\"type\":\"integer\",\"format\":\"int64\",\"minimum\":1},\"resumeFrom\":{\"t"
                 + "ype\":\"integer\",\"format\":\"int64\",\"minimum\":0}}},\n      \"EventStreamOverrun\": {\"type\":\"object\",\"required\":[\"code"
                 + "\",\"resumeAfter\"],\"additionalProperties\":true,\"properties\":{\"code\":{\"type\":\"string\",\"enum\":[\"STREAM_CONSUMER_TO"
                 + "O_SLOW\"]},\"resumeAfter\":{\"type\":\"integer\",\"format\":\"int64\"}}}\n";
    }

    private static String humanTaskSchemas() {
        return "    \"schemas\": {\n"
                + "      \"PayloadEnvelope\": {\"type\": \"object\", \"required\": [\"schema\", "
                + "\"schemaVersion\", \"kind\", \"value\"], \"properties\": {"
                + "\"schema\": {\"type\": \"string\"}, \"schemaVersion\": {\"type\": \"string\"}, "
                + "\"kind\": {\"type\": \"string\"}, \"value\": {}}},\n"
                + "      \"HumanTaskInboxItem\": {\"type\": \"object\", \"required\": [\"taskId\", "
                + "\"processInstanceId\", \"nodeId\", \"title\", \"description\", \"status\", "
                + "\"generation\", \"responseContentType\", \"responseSchema\", "
                + "\"responseSchemaVersion\", \"responseKind\", "
                + "\"maxResponseBytes\", \"expiresAt\"], \"properties\": {"
                + "\"taskId\": {\"type\": \"string\", \"format\": \"uuid\"}, "
                + "\"processInstanceId\": {\"type\": \"string\", \"format\": \"uuid\"}, "
                + "\"nodeId\": {\"type\": \"string\"}, \"title\": {\"type\": \"string\"}, "
                + "\"description\": {\"type\": \"string\"}, \"status\": {\"type\": \"string\"}, "
                + "\"generation\": {\"type\": \"integer\", \"format\": \"int64\"}, "
                + "\"responseContentType\": {\"type\": \"string\"}, "
                + "\"responseSchema\": {\"type\": \"string\"}, \"responseSchemaVersion\": "
                + "{\"type\": \"string\"}, \"responseKind\": {\"type\": \"string\"}, "
                + "\"maxResponseBytes\": {\"type\": \"integer\"}, \"expiresAt\": "
                + "{\"type\": \"string\", \"format\": \"date-time\"}, \"escalateAt\": "
                + "{\"type\": \"string\", \"format\": \"date-time\"}}},\n"
                + "      \"HumanTaskInboxPage\": {\"type\": \"object\", \"required\": [\"items\", "
                + "\"nextCursor\"], \"properties\": {\"items\": {\"type\": \"array\", \"items\": "
                + "{\"$ref\": \"#/components/schemas/HumanTaskInboxItem\"}}, \"nextCursor\": "
                + "{\"type\": \"string\", \"format\": \"uuid\", \"nullable\": true}}},\n"
                + "      \"HumanTaskConfirmationRequest\": {\"type\": \"object\", "
                + "\"additionalProperties\": false, \"required\": [\"schemaVersion\", \"comment\"], "
                + "\"properties\": {\"schemaVersion\": {\"type\": \"integer\", \"enum\": [1]}, "
                + "\"comment\": {\"type\": \"string\"}}},\n"
                + "      \"HumanTaskConfirmationPresentation\": {\"type\": \"object\", "
                + "\"required\": [\"version\", \"prompt\", \"commentRequirement\", \"actions\", "
                + "\"resolveLabel\", \"denyLabel\", \"cancelLabel\"], \"properties\": {"
                + "\"version\": {\"type\": \"integer\", \"enum\": [1]}, \"prompt\": {\"type\": \"string\"}, "
                + "\"commentRequirement\": {\"type\": \"string\", \"enum\": [\"DISALLOWED\", \"OPTIONAL\", \"REQUIRED\"]}, "
                + "\"actions\": {\"type\": \"array\", \"items\": {\"type\": \"string\", "
                + "\"enum\": [\"RESOLVE\", \"DENY\", \"CANCEL\"]}}, \"resolveLabel\": {\"type\": \"string\"}, "
                + "\"denyLabel\": {\"type\": \"string\"}, \"cancelLabel\": {\"type\": \"string\"}}},\n"
                + "      \"HumanTaskAttentionItem\": {\"type\": \"object\", \"required\": [\"taskId\", "
                + "\"generation\", \"status\", \"graphVersion\", \"deploymentId\", \"processInstanceId\", "
                + "\"traversalId\", \"nodeId\", \"createdAt\", \"expiresAt\", \"escalateAt\", "
                + "\"promptMaxUtf8Bytes\", \"actionLabelMaxUtf8Bytes\", \"commentMaxUtf8Bytes\", "
                + "\"presentation\", \"availableActions\"], \"properties\": {"
                + "\"taskId\": {\"type\": \"string\", \"format\": \"uuid\"}, \"generation\": {\"type\": \"integer\", \"format\": \"int64\"}, "
                + "\"status\": {\"type\": \"string\"}, \"graphVersion\": {\"type\": \"string\"}, "
                + "\"deploymentId\": {\"type\": \"string\", \"nullable\": true}, \"processInstanceId\": {\"type\": \"string\", \"format\": \"uuid\"}, "
                + "\"traversalId\": {\"type\": \"string\", \"format\": \"uuid\"}, \"nodeId\": {\"type\": \"string\"}, "
                + "\"createdAt\": {\"type\": \"string\", \"format\": \"date-time\"}, \"expiresAt\": {\"type\": \"string\", \"format\": \"date-time\"}, "
                + "\"escalateAt\": {\"type\": \"string\", \"format\": \"date-time\", \"nullable\": true}, "
                + "\"promptMaxUtf8Bytes\": {\"type\": \"integer\"}, \"actionLabelMaxUtf8Bytes\": {\"type\": \"integer\"}, "
                + "\"commentMaxUtf8Bytes\": {\"type\": \"integer\"}, \"presentation\": {\"$ref\": \"#/components/schemas/HumanTaskConfirmationPresentation\"}, "
                + "\"availableActions\": {\"type\": \"array\", \"items\": {\"type\": \"string\", \"enum\": [\"RESOLVE\", \"DENY\", \"CANCEL\"]}}}},\n"
                + "      \"HumanTaskAttentionCounts\": {\"type\": \"object\", \"required\": [\"pending\", \"escalated\"], "
                + "\"properties\": {\"pending\": {\"type\": \"integer\"}, \"escalated\": {\"type\": \"integer\"}}},\n"
                + "      \"HumanTaskNodeCount\": {\"allOf\": [{\"$ref\": \"#/components/schemas/HumanTaskAttentionCounts\"}, "
                + "{\"type\": \"object\", \"required\": [\"nodeId\"], \"properties\": {\"nodeId\": {\"type\": \"string\"}}}]},\n"
                + "      \"HumanTaskAttentionPage\": {\"type\": \"object\", \"required\": [\"schemaVersion\", \"items\", \"nextCursor\", \"counts\", \"nodeCounts\"], "
                + "\"properties\": {\"schemaVersion\": {\"type\": \"integer\", \"enum\": [1]}, \"items\": {\"type\": \"array\", \"items\": {\"$ref\": \"#/components/schemas/HumanTaskAttentionItem\"}}, "
                + "\"nextCursor\": {\"type\": \"string\", \"nullable\": true}, \"counts\": {\"$ref\": \"#/components/schemas/HumanTaskAttentionCounts\"}, "
                + "\"nodeCounts\": {\"type\": \"array\", \"items\": {\"$ref\": \"#/components/schemas/HumanTaskNodeCount\"}}}},\n"
                + "      \"HumanTaskConfirmationResult\": {\"type\": \"object\", \"required\": [\"schemaVersion\", \"outcome\", \"task\"], "
                + "\"properties\": {\"schemaVersion\": {\"type\": \"integer\", \"enum\": [1]}, "
                + "\"outcome\": {\"type\": \"string\", \"enum\": [\"APPLIED\", \"ALREADY_APPLIED\"]}, "
                + "\"task\": {\"$ref\": \"#/components/schemas/HumanTaskAttentionItem\"}}},\n"
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
