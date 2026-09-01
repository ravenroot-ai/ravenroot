package ai.ravenroot.extensions.openapi.server;

import ai.ravenroot.api.ingress.IngressRequest;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable, bounded OpenAPI 3.0.3 request-validation and route plan. */
final class OpenApiIngressPlan {
    static final int MAX_SCHEMA_COMPILE_WORK = 512;
    private static final PayloadLimits SPEC_LIMITS = new PayloadLimits(OpenApiServerProfile.HARD_MAX_SPEC_BYTES,
            64, 2_048, 50_000, 256 * 1024, 256);
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "options", "head", "patch");
    private static final Set<String> ROOT_KEYS = Set.of("openapi", "info", "paths", "components", "tags");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of("authorization", "proxy-authorization", "cookie",
            "host", "content-length", "connection", "transfer-encoding", "upgrade");
    private static final Set<String> IGNORED_OAS_HEADERS = Set.of("accept", "content-type", "authorization");

    private final List<Operation> operations;
    private final Set<String> methods;
    private final String idempotencyHeader;

    private OpenApiIngressPlan(List<Operation> operations, String idempotencyHeader) {
        this.operations = List.copyOf(operations);
        this.methods = operations.stream().map(Operation::method).collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.idempotencyHeader = idempotencyHeader;
    }

    static OpenApiIngressPlan compile(OpenApiServerProfile profile, Set<String> graphOperations,
                                      Set<String> projectedHeaders) {
        requireDigest(profile.specification(), profile.specificationSha256());
        final Object decoded;
        try { decoded = PayloadJson.read(profile.specification(), SPEC_LIMITS).toJava(); }
        catch (RuntimeException invalid) { throw configuration(); }
        Map<String, Object> root = OpenApiValues.object(decoded, "specification");
        rejectExtensions(root);
        OpenApiValues.exactKeys(root, ROOT_KEYS, "specification");
        if (!"3.0.3".equals(root.get("openapi"))) throw configuration();
        OpenApiValues.object(root.get("info"), "info");
        Map<String, Object> components = OpenApiValues.optionalObject(root.get("components"), "components");
        OpenApiValues.exactKeys(components, Set.of("schemas"), "components");
        Map<String, Object> schemas = OpenApiValues.optionalObject(components.get("schemas"), "schemas");
        Schema.Compiler schemaCompiler = new Schema.Compiler(schemas, MAX_SCHEMA_COMPILE_WORK);
        Map<String, Object> paths = OpenApiValues.object(root.get("paths"), "paths");
        if (paths.isEmpty() || paths.size() > 256) throw configuration();
        Set<String> selected = Set.copyOf(graphOperations);
        if (selected.isEmpty() || !profile.allowedOperations().containsAll(selected)) throw configuration();
        List<Operation> compiled = new ArrayList<>();
        Set<String> found = new HashSet<>();
        Set<String> routeShapes = new HashSet<>();
        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            PathTemplate path = PathTemplate.compile(pathEntry.getKey());
            Map<String, Object> item = OpenApiValues.object(pathEntry.getValue(), "path item");
            rejectExtensions(item);
            if (!union(METHODS, Set.of("parameters", "summary", "description")).containsAll(item.keySet())) {
                throw configuration();
            }
            List<Parameter> inherited = parameters(item.get("parameters"), schemaCompiler, projectedHeaders);
            for (String method : METHODS) {
                if (!item.containsKey(method)) continue;
                Map<String, Object> raw = OpenApiValues.object(item.get(method), "operation");
                String operationId = OpenApiValues.string(raw.get("operationId"), "operationId", 128);
                if (!profile.allowedOperations().contains(operationId)) continue;
                if (!found.add(operationId)) throw configuration();
                if (!selected.contains(operationId)) continue;
                Operation operation = compileOperation(operationId, method, path, raw, inherited, schemaCompiler,
                        projectedHeaders);
                if (!routeShapes.add(operation.method + "\u0000" + path.shape())) throw configuration();
                compiled.add(operation);
            }
        }
        if (!found.containsAll(profile.allowedOperations()) || compiled.size() != selected.size()) throw configuration();
        compiled.sort(Comparator.comparingInt((Operation value) -> -value.path.literalSegments())
                .thenComparingInt(value -> -value.path.segments().size())
                .thenComparing(value -> value.path.raw()).thenComparing(Operation::id));
        return new OpenApiIngressPlan(compiled, profile.idempotencyHeader());
    }

    Match match(IngressRequest request) throws RequestFailure {
        List<Operation> pathMatches = new ArrayList<>();
        for (Operation operation : operations) {
            Map<String, String> captures = operation.path.match(request.relativePath());
            if (captures == null) continue;
            pathMatches.add(operation);
            if (operation.method.equals(request.method())) return operation.validate(request, captures, idempotencyHeader);
        }
        if (!pathMatches.isEmpty()) throw new RequestFailure(405);
        throw new RequestFailure(404);
    }

    Set<String> methods() { return methods; }

    private static Operation compileOperation(String id, String method, PathTemplate path, Map<String, Object> raw,
                                              List<Parameter> inherited, Schema.Compiler schemaCompiler,
                                              Set<String> projectedHeaders) {
        rejectExtensions(raw);
        OpenApiValues.exactKeys(raw, Set.of("operationId", "summary", "description", "tags", "parameters",
                "requestBody", "responses", "deprecated"), "operation");
        if (Boolean.TRUE.equals(raw.get("deprecated"))) throw configuration();
        List<Parameter> parameters = merge(inherited,
                parameters(raw.get("parameters"), schemaCompiler, projectedHeaders));
        Set<String> pathNames = path.parameterNames();
        Set<String> declaredPath = new HashSet<>();
        for (Parameter parameter : parameters) if (parameter.location == Location.PATH) declaredPath.add(parameter.name);
        if (!pathNames.equals(declaredPath)) throw configuration();
        Body body = requestBody(raw.get("requestBody"), schemaCompiler);
        OpenApiValues.object(raw.get("responses"), "responses");
        return new Operation(id, method.toUpperCase(Locale.ROOT), path, parameters, body);
    }

    private static List<Parameter> parameters(Object raw, Schema.Compiler schemaCompiler,
                                              Set<String> projectedHeaders) {
        if (raw == null) return List.of();
        List<Object> values = OpenApiValues.list(raw, "parameters");
        if (values.size() > 64) throw configuration();
        List<Parameter> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object entry : values) {
            Map<String, Object> parameter = OpenApiValues.object(entry, "parameter");
            rejectExtensions(parameter);
            OpenApiValues.exactKeys(parameter, Set.of("name", "in", "required", "description", "schema",
                    "style", "explode"), "parameter");
            String name = OpenApiValues.string(parameter.get("name"), "parameter name", 128);
            Location location;
            try { location = Location.valueOf(OpenApiValues.string(parameter.get("in"), "parameter in", 16)
                    .toUpperCase(Locale.ROOT)); }
            catch (RuntimeException invalid) { throw configuration(); }
            if (location == Location.COOKIE) throw configuration();
            if (location == Location.HEADER) {
                name = OpenApiServerProfile.header(name);
                // OAS 3.0.3 §4.7.12.2: these definitions are ignored; transport/media handling owns them.
                if (IGNORED_OAS_HEADERS.contains(name)) continue;
                if (FORBIDDEN_HEADERS.contains(name) || !projectedHeaders.contains(name)) throw configuration();
            }
            boolean required = OpenApiValues.bool(parameter.get("required"), "required", false);
            if (location == Location.PATH && !required) throw configuration();
            String expectedStyle = location == Location.QUERY ? "form" : "simple";
            String style = parameter.get("style") == null ? expectedStyle
                    : OpenApiValues.string(parameter.get("style"), "style", 16);
            if (!expectedStyle.equals(style) || Boolean.TRUE.equals(parameter.get("explode"))) throw configuration();
            Schema schema = schemaCompiler.compile(parameter.get("schema"));
            if (!schema.scalar()) throw configuration();
            String key = location + "\u0000" + name.toLowerCase(Locale.ROOT);
            if (!seen.add(key)) throw configuration();
            result.add(new Parameter(name, location, required, schema));
        }
        return List.copyOf(result);
    }

    private static List<Parameter> merge(List<Parameter> inherited, List<Parameter> local) {
        Map<String, Parameter> result = new LinkedHashMap<>();
        inherited.forEach(value -> result.put(value.location + "\u0000" + value.name.toLowerCase(Locale.ROOT), value));
        local.forEach(value -> result.put(value.location + "\u0000" + value.name.toLowerCase(Locale.ROOT), value));
        return List.copyOf(result.values());
    }

    private static Body requestBody(Object raw, Schema.Compiler schemaCompiler) {
        if (raw == null) return new Body(false, null);
        Map<String, Object> body = OpenApiValues.object(raw, "requestBody");
        rejectExtensions(body);
        OpenApiValues.exactKeys(body, Set.of("description", "required", "content"), "requestBody");
        boolean required = OpenApiValues.bool(body.get("required"), "required", false);
        Map<String, Object> content = OpenApiValues.object(body.get("content"), "content");
        if (!content.keySet().equals(Set.of("application/json"))) throw configuration();
        Map<String, Object> media = OpenApiValues.object(content.get("application/json"), "media");
        OpenApiValues.exactKeys(media, Set.of("schema"), "media");
        return new Body(required, schemaCompiler.compile(media.get("schema")));
    }

    private static void rejectExtensions(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.toLowerCase(Locale.ROOT).startsWith("x-")) {
                    throw configuration();
                }
                rejectExtensions(entry.getValue());
            }
        } else if (value instanceof List<?> list) list.forEach(OpenApiIngressPlan::rejectExtensions);
    }

    private static void requireDigest(byte[] specification, String expected) {
        try {
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(specification);
            byte[] wanted = java.util.HexFormat.of().parseHex(expected);
            if (!MessageDigest.isEqual(actual, wanted)) throw configuration();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        } catch (IllegalArgumentException invalid) {
            throw configuration();
        }
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> result = new HashSet<>(first); result.addAll(second); return Set.copyOf(result);
    }

    private static OpenApiServerException configuration() { return OpenApiValues.invalid(); }

    static final class RequestFailure extends Exception {
        private final int status;
        RequestFailure(int status) { this.status = status; }
        int status() { return status; }
    }

    record Match(String operationId, Map<String, Object> path, Map<String, Object> query,
                 Map<String, Object> headers, Object body) { }

    private enum Location { PATH, QUERY, HEADER, COOKIE }
    private record Parameter(String name, Location location, boolean required, Schema schema) { }
    private record Body(boolean required, Schema schema) { }

    private record Operation(String id, String method, PathTemplate path, List<Parameter> parameters, Body body) {
        Match validate(IngressRequest request, Map<String, String> captures, String idempotencyHeader)
                throws RequestFailure {
            Map<String, Object> pathValues = new LinkedHashMap<>();
            Map<String, Object> queryValues = new LinkedHashMap<>();
            Map<String, Object> headerValues = new LinkedHashMap<>();
            Set<String> allowedQuery = new HashSet<>();
            Set<String> allowedHeaders = new HashSet<>();
            try {
                for (Parameter parameter : parameters) {
                    switch (parameter.location) {
                        case PATH -> {
                            String raw = captures.get(parameter.name);
                            if (raw == null) throw new RequestFailure(400);
                            pathValues.put(parameter.name, parameter.schema.parseScalar(raw));
                        }
                        case QUERY -> {
                            allowedQuery.add(parameter.name);
                            List<String> raw = request.query().get(parameter.name);
                            if (raw == null) { if (parameter.required) throw new RequestFailure(400); }
                            else {
                                if (raw.size() != 1) throw new RequestFailure(400);
                                queryValues.put(parameter.name, parameter.schema.parseScalar(raw.getFirst()));
                            }
                        }
                        case HEADER -> {
                            allowedHeaders.add(parameter.name);
                            String raw = request.headers().get(parameter.name);
                            if (raw == null) { if (parameter.required) throw new RequestFailure(400); }
                            else headerValues.put(parameter.name, parameter.schema.parseScalar(raw));
                        }
                        default -> throw new RequestFailure(400);
                    }
                }
                if (!allowedQuery.containsAll(request.query().keySet())) throw new RequestFailure(400);
                Set<String> allowedRequestHeaders = new HashSet<>(allowedHeaders);
                allowedRequestHeaders.add(idempotencyHeader);
                allowedRequestHeaders.add("content-type");
                if (!allowedRequestHeaders.containsAll(request.headers().keySet())) throw new RequestFailure(400);
                byte[] bytes = request.body();
                Object bodyValue = null;
                if (body.schema != null) {
                    String contentType = request.headers().get("content-type");
                    if (contentType != null && !applicationJson(contentType)) throw new RequestFailure(400);
                    if (bytes.length == 0) { if (body.required) throw new RequestFailure(400); }
                    else {
                        if (contentType == null) throw new RequestFailure(400);
                        bodyValue = PayloadJson.read(bytes, requestLimits(bytes.length)).toJava();
                        body.schema.validate(bodyValue);
                    }
                } else if (bytes.length != 0) throw new RequestFailure(400);
                return new Match(id, immutable(pathValues), immutable(queryValues), immutable(headerValues), bodyValue);
            } catch (RequestFailure failure) {
                throw failure;
            } catch (RuntimeException invalid) {
                throw new RequestFailure(400);
            }
        }
    }

    private static boolean applicationJson(String value) {
        if (value == null || value.length() > 256
                || value.codePoints().anyMatch(codePoint -> codePoint < 0x20 || codePoint == 0x7f)) return false;
        int index = skipSpaces(value, 0);
        if (!value.regionMatches(true, index, "application/json", 0, "application/json".length())) return false;
        index += "application/json".length();
        if (index < value.length() && value.charAt(index) != ';' && value.charAt(index) != ' ') return false;
        Set<String> names = new HashSet<>();
        while ((index = skipSpaces(value, index)) < value.length()) {
            if (value.charAt(index++) != ';') return false;
            index = skipSpaces(value, index);
            int nameStart = index;
            while (index < value.length() && mediaToken(value.charAt(index))) index++;
            if (index == nameStart) return false;
            String name = value.substring(nameStart, index).toLowerCase(Locale.ROOT);
            index = skipSpaces(value, index);
            if (index >= value.length() || value.charAt(index++) != '=' || !names.add(name)) return false;
            index = skipSpaces(value, index);
            if (index >= value.length()) return false;
            if (value.charAt(index) == '"') {
                index++;
                boolean closed = false;
                while (index < value.length()) {
                    char current = value.charAt(index++);
                    if (current == '\\') {
                        if (index >= value.length()) return false;
                        char escaped = value.charAt(index++);
                        if (escaped < 0x20 || escaped > 0x7e) return false;
                    } else if (current == '"') {
                        closed = true;
                        break;
                    }
                }
                if (!closed) return false;
            } else {
                int valueStart = index;
                while (index < value.length() && mediaToken(value.charAt(index))) index++;
                if (index == valueStart) return false;
            }
        }
        return true;
    }

    private static int skipSpaces(String value, int index) {
        while (index < value.length() && value.charAt(index) == ' ') index++;
        return index;
    }

    private static boolean mediaToken(char value) {
        return value >= '0' && value <= '9' || value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
                || "!#$%&'*+-.^_`|~".indexOf(value) >= 0;
    }

    private static Map<String, Object> immutable(Map<String, Object> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static PayloadLimits requestLimits(int maximum) {
        return new PayloadLimits(Math.max(1, maximum), 32, 512, 10_000, Math.max(1, maximum), 128);
    }

    private record PathTemplate(String raw, List<Segment> segments, int literalSegments,
                                Set<String> parameterNames, String shape) {
        static PathTemplate compile(String raw) {
            if (raw == null || !raw.startsWith("/") || raw.length() > 1024 || raw.contains("//")
                    || raw.contains("?") || raw.contains("#")) throw configuration();
            if (raw.equals("/")) return new PathTemplate(raw, List.of(), 0, Set.of(), "/");
            List<Segment> segments = new ArrayList<>(); Set<String> names = new HashSet<>();
            int literals = 0; StringBuilder shape = new StringBuilder();
            for (String segment : raw.substring(1).split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) throw configuration();
                if (segment.startsWith("{") && segment.endsWith("}")) {
                    String name = segment.substring(1, segment.length() - 1);
                    if (!name.matches("[A-Za-z0-9._-]{1,128}") || !names.add(name)) throw configuration();
                    segments.add(new Segment(null, name)); shape.append("/{}");
                } else {
                    if (segment.contains("{") || segment.contains("}")
                            || !segment.matches("[A-Za-z0-9._~-]+")) throw configuration();
                    segments.add(new Segment(segment, null)); literals++; shape.append('/').append(segment);
                }
            }
            return new PathTemplate(raw, List.copyOf(segments), literals, Set.copyOf(names), shape.toString());
        }

        Map<String, String> match(String value) {
            if (segments.isEmpty()) return value.isEmpty() ? Map.of() : null;
            if (value == null || !value.startsWith("/") || value.endsWith("/")) return null;
            String[] actual = value.substring(1).split("/", -1);
            if (actual.length != segments.size()) return null;
            Map<String, String> captures = new LinkedHashMap<>();
            for (int index = 0; index < actual.length; index++) {
                Segment expected = segments.get(index);
                if (expected.literal != null && !expected.literal.equals(actual[index])) return null;
                if (expected.parameter != null) captures.put(expected.parameter, actual[index]);
            }
            return captures;
        }
    }

    private record Segment(String literal, String parameter) { }

    /** Bounded JSON Schema subset; anything not named here is rejected at source start. */
    private static final class Schema {
        private enum Type { OBJECT, ARRAY, STRING, INTEGER, NUMBER, BOOLEAN }
        private static final Set<String> KEYS = Set.of("type", "nullable", "properties", "required",
                "additionalProperties", "items", "enum", "minimum", "maximum", "minLength", "maxLength",
                "minItems", "maxItems", "$ref", "description", "default", "example");
        private final Type type; private final boolean nullable; private final Map<String, Schema> properties;
        private final Set<String> required; private final boolean additional; private final Schema items;
        private final List<Object> enumeration; private final BigDecimal minimum; private final BigDecimal maximum;
        private final int minSize; private final int maxSize; private final int structuralDepth;

        private Schema(Type type, boolean nullable, Map<String, Schema> properties, Set<String> required,
                       boolean additional, Schema items, List<Object> enumeration, BigDecimal minimum,
                       BigDecimal maximum, int minSize, int maxSize, int structuralDepth) {
            this.type=type; this.nullable=nullable; this.properties=Map.copyOf(properties); this.required=Set.copyOf(required);
            this.additional=additional; this.items=items; this.enumeration=List.copyOf(enumeration);
            this.minimum=minimum; this.maximum=maximum; this.minSize=minSize; this.maxSize=maxSize;
            this.structuralDepth=structuralDepth;
        }

        private static Schema compile(Object raw, Compiler compiler, int depth) {
            if (depth > 16) throw configuration();
            compiler.consume();
            Map<String, Object> schema = OpenApiValues.object(raw, "schema");
            rejectExtensions(schema); OpenApiValues.exactKeys(schema, KEYS, "schema");
            if (schema.containsKey("$ref")) {
                if (schema.size() != 1) throw configuration();
                String ref = OpenApiValues.string(schema.get("$ref"), "$ref", 256);
                String prefix = "#/components/schemas/";
                if (!ref.startsWith(prefix)) throw configuration();
                String name = ref.substring(prefix.length());
                if (!name.matches("[A-Za-z0-9._-]{1,128}")) throw configuration();
                Schema cached = compiler.memo.get(name);
                if (cached != null) {
                    if (depth + 1 + cached.structuralDepth > 16) throw configuration();
                    return cached.viaReference();
                }
                Object target = compiler.components.get(name);
                if (target == null || !compiler.active.add(name)) throw configuration();
                try {
                    Schema compiled = compile(target, compiler, depth + 1);
                    compiler.memo.put(name, compiled);
                    return compiled.viaReference();
                } finally {
                    compiler.active.remove(name);
                }
            }
            Type type;
            try { type = Type.valueOf(OpenApiValues.string(schema.get("type"), "type", 16).toUpperCase(Locale.ROOT)); }
            catch (RuntimeException invalid) { throw configuration(); }
            boolean nullable = OpenApiValues.bool(schema.get("nullable"), "nullable", false);
            Map<String, Schema> properties = new LinkedHashMap<>(); Set<String> required = new HashSet<>();
            boolean additional = false; Schema items = null;
            if (type == Type.OBJECT) {
                Map<String, Object> rawProperties = OpenApiValues.optionalObject(schema.get("properties"), "properties");
                if (rawProperties.size() > 256) throw configuration();
                rawProperties.forEach((name, value) -> {
                    if (!name.matches("[A-Za-z0-9._-]{1,128}")) throw configuration();
                    properties.put(name, compile(value, compiler, depth + 1));
                });
                if (schema.containsKey("required")) for (Object value : OpenApiValues.list(schema.get("required"), "required")) {
                    String name = OpenApiValues.string(value, "required", 128);
                    if (!properties.containsKey(name) || !required.add(name)) throw configuration();
                }
                additional = !schema.containsKey("additionalProperties")
                        || OpenApiValues.bool(schema.get("additionalProperties"), "additionalProperties", true);
            } else if (type == Type.ARRAY) items = compile(schema.get("items"), compiler, depth + 1);
            else if (schema.containsKey("properties") || schema.containsKey("items") || schema.containsKey("required")
                    || schema.containsKey("additionalProperties")) throw configuration();
            List<Object> enumeration = schema.containsKey("enum") ? OpenApiValues.list(schema.get("enum"), "enum") : List.of();
            if (enumeration.size() > 256) throw configuration();
            BigDecimal minimum = decimal(schema.get("minimum")); BigDecimal maximum = decimal(schema.get("maximum"));
            int minSize = size(schema, type == Type.ARRAY ? "minItems" : "minLength", 0);
            int maxSize = size(schema, type == Type.ARRAY ? "maxItems" : "maxLength", 1_000_000);
            if (minSize > maxSize || minimum != null && maximum != null && minimum.compareTo(maximum) > 0) throw configuration();
            int structuralDepth = properties.values().stream().mapToInt(child -> child.structuralDepth + 1).max()
                    .orElse(items == null ? 0 : items.structuralDepth + 1);
            return new Schema(type, nullable, properties, required, additional, items, enumeration,
                    minimum, maximum, minSize, maxSize, structuralDepth);
        }

        private Schema viaReference() {
            return new Schema(type, nullable, properties, required, additional, items, enumeration,
                    minimum, maximum, minSize, maxSize, structuralDepth + 1);
        }

        private static final class Compiler {
            private final Map<String, Object> components;
            private final Map<String, Schema> memo = new HashMap<>();
            private final Set<String> active = new HashSet<>();
            private final int maximum;
            private int work;

            private Compiler(Map<String, Object> components, int maximum) {
                this.components = Map.copyOf(components);
                this.maximum = maximum;
            }

            private Schema compile(Object raw) { return Schema.compile(raw, this, 0); }

            private void consume() {
                if (++work > maximum) throw configuration();
            }
        }

        boolean scalar() { return type != Type.OBJECT && type != Type.ARRAY; }

        Object parseScalar(String raw) {
            Object value;
            try {
                value = switch (type) {
                    case STRING -> raw;
                    case INTEGER -> Long.parseLong(raw);
                    case NUMBER -> Double.parseDouble(raw);
                    case BOOLEAN -> {
                        if (!raw.equals("true") && !raw.equals("false")) throw new IllegalArgumentException();
                        yield Boolean.parseBoolean(raw);
                    }
                    default -> throw new IllegalArgumentException();
                };
            } catch (RuntimeException invalid) { throw new IllegalArgumentException(); }
            validate(value); return value;
        }

        void validate(Object value) {
            if (value == null) { if (!nullable) throw new IllegalArgumentException(); return; }
            boolean matches = switch (type) {
                case OBJECT -> value instanceof Map<?, ?>;
                case ARRAY -> value instanceof List<?>;
                case STRING -> value instanceof String;
                case INTEGER -> value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long;
                case NUMBER -> value instanceof Number;
                case BOOLEAN -> value instanceof Boolean;
            };
            if (!matches || !enumeration.isEmpty() && !enumeration.contains(value)) throw new IllegalArgumentException();
            switch (type) {
                case OBJECT -> {
                    Map<String, Object> object = OpenApiValues.object(value, "body");
                    if (!object.keySet().containsAll(required)
                            || !additional && !properties.keySet().containsAll(object.keySet())) throw new IllegalArgumentException();
                    object.forEach((name, entry) -> { Schema child = properties.get(name); if (child != null) child.validate(entry); });
                }
                case ARRAY -> {
                    List<?> values = (List<?>) value;
                    if (values.size() < minSize || values.size() > maxSize) throw new IllegalArgumentException();
                    values.forEach(items::validate);
                }
                case STRING -> {
                    String text = (String) value; int size = text.codePointCount(0, text.length());
                    if (size < minSize || size > maxSize) throw new IllegalArgumentException();
                }
                case INTEGER, NUMBER -> {
                    BigDecimal number = new BigDecimal(value.toString());
                    if (minimum != null && number.compareTo(minimum) < 0
                            || maximum != null && number.compareTo(maximum) > 0) throw new IllegalArgumentException();
                }
                default -> { }
            }
        }

        private static int size(Map<String, Object> schema, String field, int fallback) {
            return schema.containsKey(field) ? OpenApiValues.integer(schema.get(field), field, 0, 1_000_000) : fallback;
        }

        private static BigDecimal decimal(Object value) {
            if (value == null) return null;
            if (!(value instanceof Number number)) throw configuration();
            try { return new BigDecimal(number.toString()); }
            catch (NumberFormatException invalid) { throw configuration(); }
        }
    }
}
