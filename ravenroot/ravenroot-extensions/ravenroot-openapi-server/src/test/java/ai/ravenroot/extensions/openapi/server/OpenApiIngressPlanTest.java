package ai.ravenroot.extensions.openapi.server;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenApiIngressPlanTest {
    @Test void validatesPathQueryHeaderAndJsonBody() throws Exception {
        OpenApiIngressPlan plan = OpenApiIngressPlan.compile(OpenApiServerTestSupport.profile(),
                Set.of("createOrder"), Set.of("idempotency-key", "x-trace"));
        OpenApiIngressPlan.Match match = plan.match(OpenApiServerTestSupport.request("/orders/42",
                Map.of("verbose", List.of("true")), Map.of("idempotency-key", "key-1", "x-trace", "trace"),
                "{\"amount\":2}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("createOrder", match.operationId());
        assertEquals("42", match.path().get("id"));
        assertEquals(true, match.query().get("verbose"));
        assertEquals("trace", match.headers().get("x-trace"));
    }

    @Test void rejectsUnknownMethodPathInputAndBody() {
        OpenApiIngressPlan plan = OpenApiIngressPlan.compile(OpenApiServerTestSupport.profile(),
                Set.of("createOrder"), Set.of("idempotency-key", "x-trace"));
        var base = OpenApiServerTestSupport.request("/orders/42", Map.of(),
                Map.of("idempotency-key", "key", "x-trace", "trace"), "{\"amount\":0}".getBytes(StandardCharsets.UTF_8));
        assertEquals(400, assertThrows(OpenApiIngressPlan.RequestFailure.class, () -> plan.match(base)).status());
        assertEquals(404, assertThrows(OpenApiIngressPlan.RequestFailure.class, () -> plan.match(
                OpenApiServerTestSupport.request("/missing", Map.of(), Map.of(), new byte[0]))).status());
        var get = new ai.ravenroot.api.ingress.IngressRequest(base.principal(), "GET", base.relativePath(),
                base.query(), base.headers(), base.body());
        assertEquals(405, assertThrows(OpenApiIngressPlan.RequestFailure.class, () -> plan.match(get)).status());
    }

    @Test void literalRouteWinsAndGraphCannotWidenOperationSet() throws Exception {
        OpenApiIngressPlan plan = OpenApiIngressPlan.compile(OpenApiServerTestSupport.profile(),
                Set.of("createOrder", "specialOrder"), Set.of("idempotency-key", "x-trace"));
        assertEquals("specialOrder", plan.match(OpenApiServerTestSupport.request("/orders/special", Map.of(),
                Map.of("idempotency-key", "key"), new byte[0])).operationId());
        assertThrows(OpenApiServerException.class, () -> OpenApiIngressPlan.compile(OpenApiServerTestSupport.profile(),
                Set.of("not-authorized"), Set.of("idempotency-key", "x-trace")));
    }

    @Test void refusesDigestExternalReferenceAndUnprojectedHeader() {
        OpenApiServerProfile badDigest = new OpenApiServerProfile("orders", OpenApiServerTestSupport.SPEC,
                "0".repeat(64), "/api", Set.of("createOrder"), Set.of("USER"), "idempotency-key", null,
                4096, 128, 1000, 1);
        assertThrows(OpenApiServerException.class, () -> OpenApiIngressPlan.compile(badDigest,
                Set.of("createOrder"), Set.of("idempotency-key", "x-trace")));
        assertThrows(OpenApiServerException.class, () -> OpenApiIngressPlan.compile(OpenApiServerTestSupport.profile(),
                Set.of("createOrder"), Set.of("idempotency-key")));
        byte[] external = OpenApiServerTestSupport.SPEC.clone();
        String changed = new String(external, StandardCharsets.UTF_8).replace("{\"type\":\"integer\",\"minimum\":1}",
                "{\"$ref\":\"https://example.invalid/schema\"}");
        OpenApiServerProfile externalProfile = new OpenApiServerProfile("orders", changed.getBytes(StandardCharsets.UTF_8),
                OpenApiServerTestSupport.sha256(changed.getBytes(StandardCharsets.UTF_8)), "/api",
                Set.of("createOrder"), Set.of("USER"), "idempotency-key", null, 4096, 128, 1000, 1);
        assertThrows(OpenApiServerException.class, () -> OpenApiIngressPlan.compile(externalProfile,
                Set.of("createOrder"), Set.of("idempotency-key", "x-trace")));
    }

    @Test void contentTypeRequiresJsonButAcceptsWellFormedParameters() throws Exception {
        OpenApiIngressPlan plan = OpenApiIngressPlan.compile(OpenApiServerTestSupport.profile(),
                Set.of("createOrder"), Set.of("content-type", "idempotency-key", "x-trace"));
        byte[] body = "{\"amount\":2}".getBytes(StandardCharsets.UTF_8);
        var valid = OpenApiServerTestSupport.request("/orders/42", Map.of(),
                Map.of("idempotency-key", "key", "x-trace", "trace",
                        "content-type", "Application/JSON; charset=utf-8; profile=\"orders;v=1\""), body);
        assertEquals("createOrder", plan.match(valid).operationId());
        for (String invalid : List.of("text/plain", "application/json;", "application/json;charset",
                "application/json;charset=utf-8;charset=ascii", "application/json; charset=\"unterminated")) {
            var request = new ai.ravenroot.api.ingress.IngressRequest(valid.principal(), valid.method(),
                    valid.relativePath(), valid.query(), with(valid.headers(), "content-type", invalid), body);
            assertEquals(400, assertThrows(OpenApiIngressPlan.RequestFailure.class,
                    () -> plan.match(request), invalid).status());
        }
        var missing = new ai.ravenroot.api.ingress.IngressRequest(valid.principal(), valid.method(),
                valid.relativePath(), valid.query(), Map.of("idempotency-key", "key", "x-trace", "trace"), body);
        assertEquals(400, assertThrows(OpenApiIngressPlan.RequestFailure.class, () -> plan.match(missing)).status());
    }

    @Test void additionalPropertiesDefaultsTrueAndExplicitBooleanControlsNestedObjects() throws Exception {
        assertAcceptsExtra("{\"type\":\"object\",\"properties\":{\"known\":{\"type\":\"string\"}}}",
                "{\"known\":\"yes\",\"extra\":1}");
        assertAcceptsExtra("{\"type\":\"object\",\"additionalProperties\":true}", "{\"extra\":1}");
        assertRejectsBody("{\"type\":\"object\",\"additionalProperties\":false}", "{\"extra\":1}");
        assertAcceptsExtra("{\"type\":\"object\",\"properties\":{\"nested\":{\"type\":\"object\"}},"
                + "\"additionalProperties\":false}", "{\"nested\":{\"extra\":1}}");
        assertRejectsBody("{\"type\":\"object\",\"properties\":{\"nested\":{\"type\":\"object\","
                + "\"additionalProperties\":false}},\"additionalProperties\":false}",
                "{\"nested\":{\"extra\":1}}");
    }

    @Test void schemaCompileWorkAcceptsExactLimitRefusesPlusOneAndMemoizesSharedDag() {
        assertCompiles(objectTree(255));
        assertThrows(OpenApiServerException.class, () -> compile(objectTree(256)));

        StringBuilder properties = new StringBuilder();
        for (int index = 0; index < 256; index++) {
            if (index != 0) properties.append(',');
            properties.append("\"p").append(index).append("\":{\"$ref\":\"#/components/schemas/Shared\"}");
        }
        assertCompiles("{\"type\":\"object\",\"properties\":{" + properties + "}}",
                "{\"schemas\":{\"Shared\":{\"type\":\"string\"}}}");
    }

    @Test void memoizedReferenceCannotBypassTheSchemaDepthLimit() {
        String deep = "{\"$ref\":\"#/components/schemas/Shared\"}";
        for (int index = 0; index < 15; index++) deep = "{\"type\":\"array\",\"items\":" + deep + "}";
        String specificationText = "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"T\",\"version\":\"1\"},"
                + "\"components\":{\"schemas\":{\"Shared\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}},"
                + "\"paths\":{\"/shallow\":{\"post\":" + operation("shallow", "{\"$ref\":\"#/components/schemas/Shared\"}") + "},"
                + "\"/deep\":{\"post\":" + operation("deep", deep) + "}}}";
        byte[] specification = specificationText.getBytes(StandardCharsets.UTF_8);
        OpenApiServerProfile profile = new OpenApiServerProfile("bounded", specification,
                OpenApiServerTestSupport.sha256(specification), "/bounded", Set.of("shallow", "deep"),
                Set.of("USER"), "idempotency-key", null, 65536, 128, 1000, 1);
        assertThrows(OpenApiServerException.class, () -> OpenApiIngressPlan.compile(profile,
                Set.of("shallow", "deep"), Set.of("content-type", "idempotency-key")));
    }

    @Test void reservedOasHeaderParametersAreIgnoredBeforeProjectionAndSchemaCompilation() throws Exception {
        String parameters = "[{\"name\":\"aCcEpT\",\"in\":\"header\",\"required\":true,"
                + "\"schema\":{\"type\":\"object\"}},{\"name\":\"CONTENT-TYPE\",\"in\":\"header\","
                + "\"required\":true,\"schema\":{\"type\":\"unsupported\"}},{\"name\":\"Authorization\","
                + "\"in\":\"header\",\"required\":true}]";
        byte[] specification = ("{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"T\",\"version\":\"1\"},"
                + "\"paths\":{\"/item\":{\"post\":{\"operationId\":\"submit\",\"parameters\":" + parameters
                + ",\"requestBody\":{\"required\":true,\"content\":{\"application/json\":{\"schema\":{"
                + "\"type\":\"object\",\"additionalProperties\":true}}}},"
                + "\"responses\":{\"202\":{\"description\":\"accepted\"}}}}}}").getBytes(StandardCharsets.UTF_8);
        OpenApiServerProfile profile = new OpenApiServerProfile("reserved", specification,
                OpenApiServerTestSupport.sha256(specification), "/reserved", Set.of("submit"), Set.of("USER"),
                "idempotency-key", null, 65536, 128, 1000, 1);
        OpenApiIngressPlan plan = OpenApiIngressPlan.compile(profile, Set.of("submit"),
                Set.of("content-type", "idempotency-key"));
        byte[] body = "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8);
        var request = OpenApiServerTestSupport.request("/item", Map.of(), Map.of("idempotency-key", "key"), body);
        assertEquals(Map.of(), plan.match(request).headers());

        var missingMedia = new ai.ravenroot.api.ingress.IngressRequest(request.principal(), request.method(),
                request.relativePath(), request.query(), Map.of("idempotency-key", "key"), body);
        assertEquals(400, assertThrows(OpenApiIngressPlan.RequestFailure.class,
                () -> plan.match(missingMedia)).status());
    }

    private static Map<String, String> with(Map<String, String> source, String key, String value) {
        var result = new java.util.LinkedHashMap<>(source); result.put(key, value); return Map.copyOf(result);
    }

    private static void assertAcceptsExtra(String schema, String body) throws Exception {
        OpenApiIngressPlan plan = compile(schema);
        assertEquals("submit", plan.match(bodyRequest(body)).operationId());
    }

    private static void assertRejectsBody(String schema, String body) {
        OpenApiIngressPlan plan = compile(schema);
        assertEquals(400, assertThrows(OpenApiIngressPlan.RequestFailure.class,
                () -> plan.match(bodyRequest(body))).status());
    }

    private static void assertCompiles(String schema) { assertCompiles(schema, "{}"); }

    private static void assertCompiles(String schema, String components) { compile(schema, components); }

    private static OpenApiIngressPlan compile(String schema) { return compile(schema, "{}"); }

    private static OpenApiIngressPlan compile(String schema, String components) {
        byte[] specification = ("{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"T\",\"version\":\"1\"},"
                + "\"components\":" + components + ",\"paths\":{\"/item\":{\"post\":{\"operationId\":\"submit\","
                + "\"requestBody\":{\"required\":true,\"content\":{\"application/json\":{\"schema\":"
                + schema + "}}},\"responses\":{\"202\":{\"description\":\"accepted\"}}}}}}").getBytes(StandardCharsets.UTF_8);
        OpenApiServerProfile profile = new OpenApiServerProfile("bounded", specification,
                OpenApiServerTestSupport.sha256(specification), "/bounded", Set.of("submit"), Set.of("USER"),
                "idempotency-key", null, 65536, 128, 1000, 1);
        return OpenApiIngressPlan.compile(profile, Set.of("submit"), Set.of("content-type", "idempotency-key"));
    }

    private static ai.ravenroot.api.ingress.IngressRequest bodyRequest(String body) {
        return OpenApiServerTestSupport.request("/item", Map.of(), Map.of("idempotency-key", "key"),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static String objectTree(int nestedProperties) {
        StringBuilder properties = new StringBuilder();
        for (int index = 0; index < 255; index++) {
            if (index != 0) properties.append(',');
            properties.append("\"p").append(index).append("\":{\"type\":\"string\"}");
        }
        properties.append(",\"nested\":{\"type\":\"object\",\"properties\":{");
        for (int index = 0; index < nestedProperties; index++) {
            if (index != 0) properties.append(',');
            properties.append("\"n").append(index).append("\":{\"type\":\"string\"}");
        }
        return "{\"type\":\"object\",\"properties\":{" + properties + "}}}}";
    }

    private static String operation(String id, String schema) {
        return "{\"operationId\":\"" + id + "\",\"requestBody\":{\"required\":true,\"content\":{"
                + "\"application/json\":{\"schema\":" + schema + "}}},"
                + "\"responses\":{\"202\":{\"description\":\"accepted\"}}}";
    }
}
