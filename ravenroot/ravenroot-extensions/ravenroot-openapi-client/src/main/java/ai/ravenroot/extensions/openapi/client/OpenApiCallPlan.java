package ai.ravenroot.extensions.openapi.client;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable, deliberately bounded OpenAPI 3.0.3 execution plan. */
final class OpenApiCallPlan {
    private static final PayloadLimits SPEC_LIMITS = new PayloadLimits(OpenApiClientProfile.HARD_MAX_SPEC_BYTES,
            64, 2_048, 50_000, 256 * 1024, 256);
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "options", "head", "patch");
    private static final Set<String> ROOT_KEYS = Set.of("openapi", "info", "paths", "components", "security", "tags");
    private static final Set<String> FORBIDDEN_CREDENTIAL_HEADERS = Set.of("host", "content-length", "connection",
            "upgrade", "transfer-encoding", "te", "trailer", "proxy-authorization", "proxy-connection",
            "sec-websocket-key", "sec-websocket-accept", "sec-websocket-version", "sec-websocket-protocol",
            "sec-websocket-extensions");
    private final Map<String, Operation> operations;

    private OpenApiCallPlan(Map<String, Operation> operations) { this.operations = Map.copyOf(operations); }

    static OpenApiCallPlan compile(OpenApiClientProfile profile) {
        requireDigest(profile.specification(), profile.specificationSha256());
        Object decoded;
        try { decoded = PayloadJson.read(profile.specification(), SPEC_LIMITS).toJava(); }
        catch (RuntimeException invalid) { throw configuration(); }
        Map<String, Object> root = OpenApiValues.object(decoded, "specification");
        rejectExtensions(root);
        OpenApiValues.exactKeys(root, ROOT_KEYS, "specification");
        if (!"3.0.3".equals(root.get("openapi"))) throw configuration();
        OpenApiValues.object(root.get("info"), "info");
        Map<String, Object> components = OpenApiValues.optionalObject(root.get("components"), "components");
        OpenApiValues.exactKeys(components, Set.of("schemas", "securitySchemes"), "components");
        Map<String, Object> schemas = OpenApiValues.optionalObject(components.get("schemas"), "schemas");
        Map<String, SecurityScheme> security = securitySchemes(components.get("securitySchemes"));
        Object rootSecurity = root.get("security");
        Map<String, Object> paths = OpenApiValues.object(root.get("paths"), "paths");
        if (paths.isEmpty() || paths.size() > 256) throw configuration();
        Map<String, Operation> compiled = new LinkedHashMap<>();
        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            String path = pathEntry.getKey();
            validatePathTemplate(path);
            Map<String, Object> item = OpenApiValues.object(pathEntry.getValue(), "path item");
            rejectExtensions(item);
            if (item.containsKey("servers") || item.containsKey("$ref")) throw configuration();
            if (!union(METHODS, Set.of("parameters", "summary", "description")).containsAll(item.keySet())) {
                throw configuration();
            }
            List<Parameter> inherited = parameters(item.get("parameters"), schemas);
            for (String method : METHODS) {
                if (!item.containsKey(method)) continue;
                Map<String, Object> operation = OpenApiValues.object(item.get(method), "operation");
                String operationId = OpenApiValues.string(operation.get("operationId"), "operationId", 128);
                if (!profile.allowedOperations().contains(operationId)) continue;
                Operation value = compileOperation(profile, path, method, operationId, operation, inherited,
                        schemas, security, rootSecurity);
                if (compiled.putIfAbsent(operationId, value) != null) throw configuration();
            }
        }
        if (!compiled.keySet().equals(profile.allowedOperations())) throw configuration();
        Set<CredentialPlacement> placements = compiled.values().stream()
                .map(Operation::credentialPlacement).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if (placements.size() > 1) throw configuration();
        return new OpenApiCallPlan(compiled);
    }

    Operation operation(String id) {
        Operation result = operations.get(id);
        if (result == null) throw new OpenApiClientException(OpenApiClientException.Code.CONFIGURATION);
        return result;
    }

    Set<String> operationIds() { return operations.keySet(); }

    private static Operation compileOperation(OpenApiClientProfile profile, String path, String method,
                                              String operationId, Map<String, Object> raw,
                                              List<Parameter> inherited, Map<String, Object> schemas,
                                              Map<String, SecurityScheme> securitySchemes, Object rootSecurity) {
        rejectExtensions(raw);
        Set<String> allowed = Set.of("operationId", "summary", "description", "tags", "parameters", "requestBody",
                "responses", "security", "deprecated");
        OpenApiValues.exactKeys(raw, allowed, "operation");
        if (Boolean.TRUE.equals(raw.get("deprecated"))) throw configuration();
        List<Parameter> all = mergeParameters(inherited, parameters(raw.get("parameters"), schemas));
        Set<String> pathNames = templateNames(path);
        Set<String> parameterPathNames = new HashSet<>();
        for (Parameter parameter : all) if (parameter.location == Location.PATH) parameterPathNames.add(parameter.name);
        if (!pathNames.equals(parameterPathNames)) throw configuration();
        Schema body = requestBody(raw.get("requestBody"), schemas);
        Map<String, Schema> responses = responses(raw.get("responses"), schemas);
        CredentialPlacement credentialPlacement = authentication(
                raw.containsKey("security") ? raw.get("security") : rootSecurity,
                securitySchemes);
        if (credentialPlacement != null) {
            if (profile.credential().isEmpty()
                    || profile.fixedHeaders().containsKey(credentialPlacement.headerName())
                    || profile.allowedInputHeaders().contains(credentialPlacement.headerName())) throw configuration();
        }
        return new Operation(operationId, method.toUpperCase(Locale.ROOT), path, all, body, responses,
                credentialPlacement);
    }

    private static List<Parameter> parameters(Object raw, Map<String, Object> schemas) {
        if (raw == null) return List.of();
        List<Object> values = OpenApiValues.list(raw, "parameters");
        if (values.size() > 64) throw configuration();
        List<Parameter> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object value : values) {
            Map<String, Object> parameter = OpenApiValues.object(value, "parameter");
            rejectExtensions(parameter);
            OpenApiValues.exactKeys(parameter, Set.of("name", "in", "required", "description", "schema", "style", "explode"),
                    "parameter");
            String name = OpenApiValues.string(parameter.get("name"), "parameter name", 128);
            Location location;
            try { location = Location.valueOf(OpenApiValues.string(parameter.get("in"), "parameter in", 16)
                    .toUpperCase(Locale.ROOT)); }
            catch (RuntimeException invalid) { throw configuration(); }
            if (location == Location.COOKIE) throw configuration();
            boolean required = OpenApiValues.bool(parameter.get("required"), "required", false);
            if (location == Location.PATH && !required) throw configuration();
            String expectedStyle = location == Location.PATH || location == Location.HEADER ? "simple" : "form";
            String style = parameter.get("style") == null ? expectedStyle
                    : OpenApiValues.string(parameter.get("style"), "style", 16);
            if (!expectedStyle.equals(style) || Boolean.TRUE.equals(parameter.get("explode"))) throw configuration();
            Schema schema = Schema.compile(parameter.get("schema"), schemas, new ArrayList<>(), 0);
            if (!schema.scalar()) throw configuration();
            String key = location + "\u0000" + name.toLowerCase(Locale.ROOT);
            if (!seen.add(key)) throw configuration();
            out.add(new Parameter(name, location, required, schema));
        }
        return List.copyOf(out);
    }

    private static List<Parameter> mergeParameters(List<Parameter> inherited, List<Parameter> local) {
        Map<String, Parameter> merged = new LinkedHashMap<>();
        inherited.forEach(value -> merged.put(value.location + "\u0000" + value.name.toLowerCase(Locale.ROOT), value));
        local.forEach(value -> merged.put(value.location + "\u0000" + value.name.toLowerCase(Locale.ROOT), value));
        return List.copyOf(merged.values());
    }

    private static Schema requestBody(Object raw, Map<String, Object> schemas) {
        if (raw == null) return null;
        Map<String, Object> body = OpenApiValues.object(raw, "requestBody");
        rejectExtensions(body);
        OpenApiValues.exactKeys(body, Set.of("description", "required", "content"), "requestBody");
        if (!OpenApiValues.bool(body.get("required"), "requestBody required", false)) throw configuration();
        return jsonContent(body.get("content"), schemas);
    }

    private static Map<String, Schema> responses(Object raw, Map<String, Object> schemas) {
        Map<String, Object> responses = OpenApiValues.object(raw, "responses");
        if (responses.isEmpty() || responses.size() > 64) throw configuration();
        Map<String, Schema> out = new LinkedHashMap<>();
        responses.forEach((status, value) -> {
            if (!(status.equals("default") || status.matches("[1-5][0-9][0-9]"))) throw configuration();
            Map<String, Object> response = OpenApiValues.object(value, "response");
            rejectExtensions(response);
            OpenApiValues.exactKeys(response, Set.of("description", "headers", "content"), "response");
            if (response.containsKey("headers") && !OpenApiValues.object(response.get("headers"), "headers").isEmpty()) {
                // Header schemas are not executed: projection is fixed by the operator profile.
                throw configuration();
            }
            Schema schema = response.containsKey("content") ? jsonContent(response.get("content"), schemas) : null;
            out.put(status, schema);
        });
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(out));
    }

    private static Schema jsonContent(Object raw, Map<String, Object> schemas) {
        Map<String, Object> content = OpenApiValues.object(raw, "content");
        if (!content.keySet().equals(Set.of("application/json"))) throw configuration();
        Map<String, Object> media = OpenApiValues.object(content.get("application/json"), "media");
        rejectExtensions(media);
        OpenApiValues.exactKeys(media, Set.of("schema", "example", "examples", "encoding"), "media");
        if (media.containsKey("encoding")) throw configuration();
        return Schema.compile(media.get("schema"), schemas, new ArrayList<>(), 0);
    }

    private static Map<String, SecurityScheme> securitySchemes(Object raw) {
        Map<String, Object> values = OpenApiValues.optionalObject(raw, "securitySchemes");
        if (values.size() > 16) throw configuration();
        Map<String, SecurityScheme> result = new HashMap<>();
        values.forEach((name, value) -> {
            Map<String, Object> scheme = OpenApiValues.object(value, "security scheme");
            rejectExtensions(scheme);
            String type = OpenApiValues.string(scheme.get("type"), "security type", 16);
            if ("apiKey".equals(type)) {
                OpenApiValues.exactKeys(scheme, Set.of("type", "name", "in", "description"), "security scheme");
                if (!"header".equals(scheme.get("in"))) throw configuration();
                String header = OpenApiClientProfile.header(
                        OpenApiValues.string(scheme.get("name"), "security header", 64));
                if (FORBIDDEN_CREDENTIAL_HEADERS.contains(header)) throw configuration();
                result.put(name, new SecurityScheme(new CredentialPlacement(header, "")));
            } else if ("http".equals(type)) {
                OpenApiValues.exactKeys(scheme, Set.of("type", "scheme", "bearerFormat", "description"), "security scheme");
                if (!"bearer".equalsIgnoreCase(OpenApiValues.string(scheme.get("scheme"), "security scheme", 16))) {
                    throw configuration();
                }
                result.put(name, new SecurityScheme(new CredentialPlacement("authorization", "Bearer ")));
            } else {
                // Query/cookie keys, OAuth acquisition and OpenID are intentionally outside v1.
                throw configuration();
            }
        });
        return Map.copyOf(result);
    }

    private static CredentialPlacement authentication(Object raw, Map<String, SecurityScheme> schemes) {
        if (raw == null) return null;
        List<Object> alternatives = OpenApiValues.list(raw, "security");
        if (alternatives.isEmpty()) return null;
        if (alternatives.size() != 1) throw configuration();
        Map<String, Object> requirement = OpenApiValues.object(alternatives.getFirst(), "security requirement");
        if (requirement.size() != 1) throw configuration();
        SecurityScheme scheme = schemes.get(requirement.keySet().iterator().next());
        if (scheme == null) throw configuration();
        if (!OpenApiValues.list(requirement.values().iterator().next(), "security scopes").isEmpty()) throw configuration();
        return scheme.placement();
    }

    private static void rejectExtensions(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.toLowerCase(Locale.ROOT).startsWith("x-")) {
                    throw configuration();
                }
                rejectExtensions(entry.getValue());
            }
        } else if (value instanceof List<?> list) {
            list.forEach(OpenApiCallPlan::rejectExtensions);
        }
    }

    private static void validatePathTemplate(String path) {
        if (!path.startsWith("/") || path.contains("//") || path.contains("?") || path.contains("#")
                || path.getBytes(StandardCharsets.UTF_8).length > 1_024) throw configuration();
        for (String segment : path.substring(1).split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) throw configuration();
            if (segment.startsWith("{") && segment.endsWith("}")) {
                if (!segment.substring(1, segment.length() - 1).matches("[A-Za-z0-9._-]{1,128}")) throw configuration();
            } else if (segment.contains("{") || segment.contains("}") || !segment.matches("[A-Za-z0-9._~-]+")) {
                throw configuration();
            }
        }
    }

    private static Set<String> templateNames(String path) {
        Set<String> names = new HashSet<>();
        for (String segment : path.substring(1).split("/")) if (segment.startsWith("{")) {
            if (!names.add(segment.substring(1, segment.length() - 1))) throw configuration();
        }
        return Set.copyOf(names);
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> result = new HashSet<>(first); result.addAll(second); return Set.copyOf(result);
    }

    private static void requireDigest(byte[] bytes, String expected) {
        try {
            StringBuilder actual = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) actual.append(String.format("%02x", value));
            if (!MessageDigest.isEqual(actual.toString().getBytes(StandardCharsets.US_ASCII),
                    expected.getBytes(StandardCharsets.US_ASCII))) throw configuration();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static OpenApiClientException configuration() {
        return new OpenApiClientException(OpenApiClientException.Code.CONFIGURATION);
    }

    enum Location { PATH, QUERY, HEADER, COOKIE }
    private record CredentialPlacement(String headerName, String prefix) { }
    private record SecurityScheme(CredentialPlacement placement) { }
    record Parameter(String name, Location location, boolean required, Schema schema) { }

    record Operation(String id, String method, String pathTemplate, List<Parameter> parameters,
                     Schema requestBody, Map<String, Schema> responses, CredentialPlacement credentialPlacement) {
        boolean authenticated() { return credentialPlacement != null; }
        boolean idempotent() { return Set.of("GET", "PUT", "DELETE", "HEAD", "OPTIONS").contains(method); }

        boolean accepts(int status) {
            return responses.containsKey(Integer.toString(status)) || responses.containsKey("default");
        }

        Schema response(int status) {
            Schema exact = responses.get(Integer.toString(status));
            return exact != null || responses.containsKey(Integer.toString(status)) ? exact : responses.get("default");
        }
    }

    /** Bounded JSON Schema subset used identically for request and response. */
    static final class Schema {
        enum Type { OBJECT, ARRAY, STRING, INTEGER, NUMBER, BOOLEAN }
        private static final Set<String> KEYS = Set.of("type", "nullable", "properties", "required",
                "additionalProperties", "items", "enum", "minimum", "maximum", "minLength", "maxLength",
                "minItems", "maxItems", "$ref", "description", "default", "example");
        final Type type; final boolean nullable; final Map<String, Schema> properties; final Set<String> required;
        final boolean additional; final Schema items; final List<Object> enumeration;
        final BigDecimal minimum; final BigDecimal maximum; final int minSize; final int maxSize;

        private Schema(Type type, boolean nullable, Map<String, Schema> properties, Set<String> required,
                       boolean additional, Schema items, List<Object> enumeration, BigDecimal minimum,
                       BigDecimal maximum, int minSize, int maxSize) {
            this.type=type; this.nullable=nullable; this.properties=Map.copyOf(properties); this.required=Set.copyOf(required);
            this.additional=additional; this.items=items; this.enumeration=List.copyOf(enumeration);
            this.minimum=minimum; this.maximum=maximum; this.minSize=minSize; this.maxSize=maxSize;
        }

        static Schema compile(Object raw, Map<String, Object> components, List<String> chain, int depth) {
            if (depth > 16) throw configuration();
            Map<String, Object> schema = OpenApiValues.object(raw, "schema");
            rejectExtensions(schema);
            OpenApiValues.exactKeys(schema, KEYS, "schema");
            if (schema.containsKey("$ref")) {
                if (schema.size() != 1) throw configuration();
                String ref = OpenApiValues.string(schema.get("$ref"), "$ref", 256);
                String prefix = "#/components/schemas/";
                if (!ref.startsWith(prefix)) throw configuration();
                String name = ref.substring(prefix.length());
                if (!name.matches("[A-Za-z0-9._-]{1,128}") || chain.contains(name)) throw configuration();
                Object target = components.get(name); if (target == null) throw configuration();
                List<String> next = new ArrayList<>(chain); next.add(name);
                return compile(target, components, next, depth + 1);
            }
            Type type;
            try { type = Type.valueOf(OpenApiValues.string(schema.get("type"), "schema type", 16)
                    .toUpperCase(Locale.ROOT)); }
            catch (RuntimeException invalid) { throw configuration(); }
            boolean nullable = OpenApiValues.bool(schema.get("nullable"), "nullable", false);
            Map<String, Schema> properties = new LinkedHashMap<>(); Set<String> required = new HashSet<>();
            boolean additional = false; Schema items = null;
            if (type == Type.OBJECT) {
                Map<String, Object> rawProperties = OpenApiValues.optionalObject(schema.get("properties"), "properties");
                if (rawProperties.size() > 256) throw configuration();
                rawProperties.forEach((name, value) -> {
                    if (!name.matches("[A-Za-z0-9._-]{1,128}")) throw configuration();
                    properties.put(name, compile(value, components, chain, depth + 1));
                });
                if (schema.containsKey("required")) for (Object value : OpenApiValues.list(schema.get("required"), "required")) {
                    String name = OpenApiValues.string(value, "required", 128);
                    if (!properties.containsKey(name) || !required.add(name)) throw configuration();
                }
                additional = OpenApiValues.bool(schema.get("additionalProperties"), "additionalProperties", false);
            } else if (type == Type.ARRAY) {
                items = compile(schema.get("items"), components, chain, depth + 1);
            } else if (schema.containsKey("properties") || schema.containsKey("items") || schema.containsKey("required")
                    || schema.containsKey("additionalProperties")) throw configuration();
            List<Object> enumeration = schema.containsKey("enum") ? OpenApiValues.list(schema.get("enum"), "enum") : List.of();
            if (enumeration.size() > 256) throw configuration();
            BigDecimal minimum = decimal(schema.get("minimum")); BigDecimal maximum = decimal(schema.get("maximum"));
            int minSize = size(schema, type == Type.ARRAY ? "minItems" : "minLength", 0);
            int maxSize = size(schema, type == Type.ARRAY ? "maxItems" : "maxLength", 1_000_000);
            if (minSize > maxSize || minimum != null && maximum != null && minimum.compareTo(maximum) > 0) throw configuration();
            return new Schema(type, nullable, properties, required, additional, items, enumeration,
                    minimum, maximum, minSize, maxSize);
        }

        boolean scalar() { return type != Type.OBJECT && type != Type.ARRAY; }

        void validate(Object value) {
            if (value == null) { if (!nullable) invalidInput(); return; }
            boolean typeMatches = switch (type) {
                case OBJECT -> value instanceof Map<?, ?>;
                case ARRAY -> value instanceof List<?>;
                case STRING -> value instanceof String;
                case INTEGER -> value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long;
                case NUMBER -> value instanceof Number;
                case BOOLEAN -> value instanceof Boolean;
            };
            if (!typeMatches || !enumeration.isEmpty() && !enumeration.contains(value)) invalidInput();
            switch (type) {
                case OBJECT -> {
                    Map<String, Object> object;
                    try { object = OpenApiValues.object(value, "value"); } catch (RuntimeException bad) { invalidInput(); return; }
                    if (!object.keySet().containsAll(required) || !additional && !properties.keySet().containsAll(object.keySet())) invalidInput();
                    object.forEach((name, entry) -> { Schema child = properties.get(name); if (child != null) child.validate(entry); });
                }
                case ARRAY -> {
                    List<?> list = (List<?>) value; if (list.size() < minSize || list.size() > maxSize) invalidInput();
                    list.forEach(items::validate);
                }
                case STRING -> { int length = ((String) value).codePointCount(0, ((String) value).length()); if (length < minSize || length > maxSize) invalidInput(); }
                case INTEGER, NUMBER -> {
                    BigDecimal number = new BigDecimal(value.toString());
                    if (minimum != null && number.compareTo(minimum) < 0 || maximum != null && number.compareTo(maximum) > 0) invalidInput();
                }
                default -> { }
            }
        }

        private static int size(Map<String, Object> schema, String name, int fallback) {
            return schema.containsKey(name) ? OpenApiValues.integer(schema.get(name), name, 0, 1_000_000) : fallback;
        }
        private static BigDecimal decimal(Object value) {
            if (value == null) return null; if (!(value instanceof Number number)) throw configuration();
            try { return new BigDecimal(number.toString()); } catch (NumberFormatException invalid) { throw configuration(); }
        }
        private static void invalidInput() { throw new OpenApiClientException(OpenApiClientException.Code.INVALID_INPUT); }
    }
}
