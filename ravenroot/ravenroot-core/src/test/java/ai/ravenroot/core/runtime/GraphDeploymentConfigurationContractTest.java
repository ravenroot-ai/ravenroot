package ai.ravenroot.core.runtime;

import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.core.graph.GraphMlLimits;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps deployment carriers aligned with the Java-owned graph limit contract. */
class GraphDeploymentConfigurationContractTest {
    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    @Test
    void everyJavaBindingHasOneBlankDefaultDeploymentMappingWithTheJavaSafetyCeiling() throws Exception {
        List<Setting> settings = settings();
        assertEquals(25, settings.size());
        assertEquals(javaVariables(), settings.stream().map(Setting::environment).collect(Collectors.toSet()));

        String compose = read("compose.yaml");
        String values = read("deploy/helm/ravenroot/values.yaml");
        String schema = read("deploy/helm/ravenroot/values.schema.json");
        String graphSchema = graphSchema(schema);
        String template = read("deploy/helm/ravenroot/templates/deployment.yaml");
        String kubernetes = read("deploy/kubernetes/ravenroot.yaml");
        Map<String, String> helmValues = yamlScalars(values);

        for (Setting setting : settings) {
            assertEquals(setting.defaultValue(), valueOf(GraphExecutionLimits.fromEnvironment(Map.of()), setting),
                    setting.environment());
            assertEquals(setting.defaultValue(),
                    valueOf(GraphExecutionLimits.fromEnvironment(Map.of(setting.environment(), " \t ")), setting),
                    setting.environment());
            assertEquals(1, occurrences(compose,
                    setting.environment() + ": ${" + setting.environment() + ":-}"), setting.environment());
            assertEquals("\"\"", helmValues.get(setting.helmPath()), setting.helmPath());
            assertSchemaLeaf(graphSchema, setting);
            assertEquals(1, occurrences(template,
                    "- name: " + setting.environment() + "\n"
                            + "              value: {{ include \"ravenroot.graphLimitValue\" .Values."
                            + setting.helmPath() + " }}"), setting.environment());
            assertEquals(1, occurrences(kubernetes,
                    "- name: " + setting.environment() + "\n              value: \"\""),
                    setting.environment());
        }

        assertExactGraphEnvironmentNames(schema, javaVariables());
    }

    @Test
    void annotationsOutsideTheGraphSubtreeDoNotAffectTheGraphContract() throws Exception {
        String schema = read("deploy/helm/ravenroot/values.schema.json");
        Set<String> expected = javaVariables();

        assertExactGraphEnvironmentNames(schema, expected);
        assertExactGraphEnvironmentNames(replaceOne(schema,
                annotation("RAVENROOT_ASSISTANT_MAX_OUTPUT_TOKENS"),
                annotation("RAVENROOT_OUTSIDE_GRAPH_ASSISTANT_ALIEN")), expected);
        assertExactGraphEnvironmentNames(replaceOne(schema,
                annotation("RAVENROOT_RATELIMIT_ADDRESS_RPS"),
                annotation("RAVENROOT_OUTSIDE_GRAPH_RATE_ALIEN")), expected);
    }

    @Test
    void missingAlienAndDuplicateGraphAnnotationsAreRejected() throws Exception {
        String schema = read("deploy/helm/ravenroot/values.schema.json");
        List<Setting> settings = settings();
        String first = settings.get(0).environment();
        String second = settings.get(1).environment();
        Set<String> expected = javaVariables();

        String missing = replaceOne(schema, annotation(first) + ",", "");
        assertThrows(AssertionError.class, () -> assertExactGraphEnvironmentNames(missing, expected));

        String alien = replaceOne(schema, annotation(first), annotation("RAVENROOT_GRAPH_ALIEN"));
        assertThrows(AssertionError.class, () -> assertExactGraphEnvironmentNames(alien, expected));

        String duplicate = replaceOne(schema, annotation(second), annotation(first));
        assertThrows(AssertionError.class, () -> assertExactGraphEnvironmentNames(duplicate, expected));
    }

    @Test
    void directObjectSelectionIgnoresNestedShadowsAndRejectsAmbiguousMembers() throws Exception {
        String schema = read("deploy/helm/ravenroot/values.schema.json");
        String shadowed = """
                {
                  "shadow": {
                    "properties": {
                      "graph": {"note": "shadow } { \\"quoted\\""}
                    }
                  },
                  "properties": %s
                }
                """.formatted(jsonObject(schema, "properties"));

        assertExactGraphEnvironmentNames(shadowed, javaVariables());
        assertThrows(IllegalStateException.class, () -> jsonObject("{\"other\": {}}", "properties"));
        assertThrows(IllegalStateException.class,
                () -> jsonObject("{\"properties\": {}, \"properties\": {}}", "properties"));
        assertThrows(IllegalStateException.class,
                () -> jsonObject("{\"properties\": []}", "properties"));
    }

    private static void assertSchemaLeaf(String schema, Setting setting) {
        String expected = "\"x-ravenroot-environment\": \"" + setting.environment() + "\",\n"
                + "              \"oneOf\": [{ \"type\": \"integer\", \"minimum\": 1, \"maximum\": "
                + setting.ceiling() + " }, { \"$ref\": \"#/definitions/graphBlank\" }]";
        assertEquals(1, occurrences(schema, expected), setting.environment());
    }

    private static Set<String> javaVariables() throws IllegalAccessException {
        return Arrays.stream(GraphExecutionLimits.class.getFields())
                .filter(field -> field.getType() == String.class)
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> field.getName().endsWith("_VARIABLE"))
                .map(field -> {
                    try {
                        return (String) field.get(null);
                    } catch (IllegalAccessException failure) {
                        throw new IllegalStateException(failure);
                    }
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void assertExactGraphEnvironmentNames(String schema, Set<String> expected) {
        List<String> actual = schemaEnvironmentNames(graphSchema(schema));
        assertEquals(expected.size(), actual.size());
        assertEquals(expected, actual.stream().collect(Collectors.toSet()));
    }

    private static List<String> schemaEnvironmentNames(String schema) {
        Matcher matcher = Pattern.compile("\\\"x-ravenroot-environment\\\": \\\"([^\\\"]+)\\\"").matcher(schema);
        var names = new java.util.ArrayList<String>();
        while (matcher.find()) names.add(matcher.group(1));
        return List.copyOf(names);
    }

    private static String graphSchema(String schema) {
        return jsonObject(jsonObject(schema, "properties"), "graph");
    }

    private static String jsonObject(String json, String property) {
        int cursor = skipWhitespace(json, 0);
        if (cursor >= json.length() || json.charAt(cursor) != '{') {
            throw new IllegalStateException("expected JSON object");
        }
        cursor++;
        String found = null;
        int matches = 0;
        while (true) {
            cursor = skipWhitespace(json, cursor);
            if (cursor >= json.length()) throw new IllegalStateException("unterminated JSON object");
            if (json.charAt(cursor) == '}') {
                cursor++;
                break;
            }
            if (json.charAt(cursor) != '"') throw new IllegalStateException("expected JSON member name");
            int keyEnd = jsonStringEnd(json, cursor);
            String key = json.substring(cursor + 1, keyEnd - 1);
            cursor = skipWhitespace(json, keyEnd);
            if (cursor >= json.length() || json.charAt(cursor) != ':') {
                throw new IllegalStateException("expected JSON member separator");
            }
            int valueStart = skipWhitespace(json, cursor + 1);
            int valueEnd = jsonValueEnd(json, valueStart);
            if (key.equals(property)) {
                matches++;
                if (valueStart >= json.length() || json.charAt(valueStart) != '{') {
                    throw new IllegalStateException("JSON member " + property + " is not an object");
                }
                found = json.substring(valueStart, valueEnd);
            }
            cursor = skipWhitespace(json, valueEnd);
            if (cursor >= json.length()) throw new IllegalStateException("unterminated JSON object");
            if (json.charAt(cursor) == ',') {
                cursor++;
            } else if (json.charAt(cursor) == '}') {
                cursor++;
                break;
            } else {
                throw new IllegalStateException("expected JSON member delimiter");
            }
        }
        if (skipWhitespace(json, cursor) != json.length()) {
            throw new IllegalStateException("trailing JSON content");
        }
        if (matches != 1) throw new IllegalStateException("expected one JSON object " + property);
        return found;
    }

    private static int jsonValueEnd(String json, int start) {
        if (start >= json.length()) throw new IllegalStateException("missing JSON value");
        char first = json.charAt(start);
        if (first == '"') return jsonStringEnd(json, start);
        if (first == '{' || first == '[') {
            var closing = new ArrayDeque<Character>();
            closing.addLast(first == '{' ? '}' : ']');
            for (int cursor = start + 1; cursor < json.length(); cursor++) {
                char current = json.charAt(cursor);
                if (current == '"') {
                    cursor = jsonStringEnd(json, cursor) - 1;
                } else if (current == '{') {
                    closing.addLast('}');
                } else if (current == '[') {
                    closing.addLast(']');
                } else if (current == '}' || current == ']') {
                    if (closing.isEmpty() || closing.removeLast() != current) {
                        throw new IllegalStateException("mismatched JSON container");
                    }
                    if (closing.isEmpty()) return cursor + 1;
                }
            }
            throw new IllegalStateException("unterminated JSON value");
        }
        int cursor = start;
        while (cursor < json.length() && json.charAt(cursor) != ',' && json.charAt(cursor) != '}') cursor++;
        if (start == skipWhitespace(json, cursor)) throw new IllegalStateException("missing JSON value");
        return cursor;
    }

    private static int jsonStringEnd(String json, int opening) {
        for (int cursor = opening + 1; cursor < json.length(); cursor++) {
            char current = json.charAt(cursor);
            if (current == '\\') {
                cursor++;
                if (cursor >= json.length()) throw new IllegalStateException("unterminated JSON escape");
            } else if (current == '"') {
                return cursor + 1;
            }
        }
        throw new IllegalStateException("unterminated JSON string");
    }

    private static int skipWhitespace(String text, int start) {
        int cursor = start;
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) cursor++;
        return cursor;
    }

    private static String annotation(String environment) {
        return "\"x-ravenroot-environment\": \"" + environment + "\"";
    }

    private static String replaceOne(String source, String target, String replacement) {
        assertEquals(1, occurrences(source, target), target);
        return source.replace(target, replacement);
    }

    private static Map<String, String> yamlScalars(String yaml) {
        Pattern entry = Pattern.compile("^( *)([A-Za-z][A-Za-z0-9]*):(?: (.*))?$");
        var parents = new ArrayDeque<String>();
        var values = new LinkedHashMap<String, String>();
        for (String line : yaml.lines().toList()) {
            Matcher matcher = entry.matcher(line);
            if (!matcher.matches()) continue;
            int depth = matcher.group(1).length() / 2;
            while (parents.size() > depth) parents.removeLast();
            String key = matcher.group(2);
            String value = matcher.group(3);
            if (value == null || value.isEmpty()) {
                parents.addLast(key);
            } else {
                String path = String.join(".", parents) + (parents.isEmpty() ? "" : ".") + key;
                values.put(path, value);
            }
        }
        return Map.copyOf(values);
    }

    private static int occurrences(String text, String fragment) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(fragment, offset)) >= 0) {
            count++;
            offset += fragment.length();
        }
        return count;
    }

    private static String read(String relative) throws IOException {
        return Files.readString(REPO_ROOT.resolve(relative));
    }

    private static long valueOf(GraphExecutionLimits limits, Setting setting) {
        return switch (setting.helmPath()) {
            case "graph.graphMl.maxBytes" -> limits.graphMl().maxBytes();
            case "graph.graphMl.maxNodes" -> limits.graphMl().maxNodes();
            case "graph.graphMl.maxEdges" -> limits.graphMl().maxEdges();
            case "graph.graphMl.maxProperties" -> limits.graphMl().maxProperties();
            case "graph.graphMl.maxDepth" -> limits.graphMl().maxDepth();
            case "graph.graphMl.maxStringLength" -> limits.graphMl().maxStringLength();
            case "graph.graphMl.maxKeys" -> limits.graphMl().maxKeys();
            case "graph.graphMl.maxElements" -> limits.graphMl().maxElements();
            case "graph.graphMl.maxAttributes" -> limits.graphMl().maxAttributes();
            case "graph.graphMl.maxNamespaceDeclarations" -> limits.graphMl().maxNamespaceDeclarations();
            case "graph.payload.maxEncodedBytes" -> limits.payload().maxEncodedBytes();
            case "graph.payload.maxDepth" -> limits.payload().maxDepth();
            case "graph.payload.maxCollectionSize" -> limits.payload().maxCollectionSize();
            case "graph.payload.maxValueCount" -> limits.payload().maxValueCount();
            case "graph.payload.maxTextLength" -> limits.payload().maxTextLength();
            case "graph.payload.maxKeyLength" -> limits.payload().maxKeyLength();
            case "graph.execution.maxFanOut" -> limits.maxFanOut();
            case "graph.execution.maxResidentActors" -> limits.maxResidentActors();
            case "graph.execution.maxLiveActorsPerTraversal" -> limits.maxLiveActorsPerTraversal();
            case "graph.execution.maxInFlightHopsPerTraversal" -> limits.maxInFlightHopsPerTraversal();
            case "graph.execution.maxQueuedAdmissionsPerNode" -> limits.maxQueuedAdmissionsPerNode();
            case "graph.execution.maxTraversalSteps" -> limits.maxTraversalSteps();
            case "graph.execution.maxAmplifiedDeliveries" -> limits.maxAmplifiedDeliveries();
            case "graph.execution.maxCumulativePayloadBytes" -> limits.maxCumulativePayloadBytes();
            case "graph.execution.maxRecoveryDeliveriesPerAttempt" -> limits.maxRecoveryDeliveriesPerAttempt();
            default -> throw new IllegalStateException("unmapped graph deployment setting");
        };
    }

    private static List<Setting> settings() {
        GraphExecutionLimits defaults = GraphExecutionLimits.DEFAULTS;
        return List.of(
                new Setting(GraphExecutionLimits.MAX_GRAPHML_BYTES_VARIABLE, "graph.graphMl.maxBytes",
                        defaults.graphMl().maxBytes(), GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES),
                new Setting(GraphExecutionLimits.MAX_NODES_VARIABLE, "graph.graphMl.maxNodes",
                        defaults.graphMl().maxNodes(), GraphMlLimits.HARD_MAX_NODES),
                new Setting(GraphExecutionLimits.MAX_EDGES_VARIABLE, "graph.graphMl.maxEdges",
                        defaults.graphMl().maxEdges(), GraphMlLimits.HARD_MAX_EDGES),
                new Setting(GraphExecutionLimits.MAX_PROPERTIES_VARIABLE, "graph.graphMl.maxProperties",
                        defaults.graphMl().maxProperties(), GraphMlLimits.HARD_MAX_PROPERTIES),
                new Setting(GraphExecutionLimits.MAX_GRAPHML_DEPTH_VARIABLE, "graph.graphMl.maxDepth",
                        defaults.graphMl().maxDepth(), GraphMlLimits.HARD_MAX_DEPTH),
                new Setting(GraphExecutionLimits.MAX_GRAPHML_STRING_LENGTH_VARIABLE,
                        "graph.graphMl.maxStringLength", defaults.graphMl().maxStringLength(),
                        GraphMlLimits.HARD_MAX_STRING_LENGTH),
                new Setting(GraphExecutionLimits.MAX_GRAPHML_KEYS_VARIABLE, "graph.graphMl.maxKeys",
                        defaults.graphMl().maxKeys(), GraphMlLimits.HARD_MAX_KEYS),
                new Setting(GraphExecutionLimits.MAX_GRAPHML_ELEMENTS_VARIABLE, "graph.graphMl.maxElements",
                        defaults.graphMl().maxElements(), GraphMlLimits.HARD_MAX_ELEMENTS),
                new Setting(GraphExecutionLimits.MAX_GRAPHML_ATTRIBUTES_VARIABLE, "graph.graphMl.maxAttributes",
                        defaults.graphMl().maxAttributes(), GraphMlLimits.HARD_MAX_ATTRIBUTES),
                new Setting(GraphExecutionLimits.MAX_GRAPHML_NAMESPACE_DECLARATIONS_VARIABLE,
                        "graph.graphMl.maxNamespaceDeclarations", defaults.graphMl().maxNamespaceDeclarations(),
                        GraphMlLimits.HARD_MAX_NAMESPACE_DECLARATIONS),
                new Setting(GraphExecutionLimits.MAX_PAYLOAD_BYTES_VARIABLE, "graph.payload.maxEncodedBytes",
                        defaults.payload().maxEncodedBytes(), PayloadLimits.HARD_MAX_ENCODED_BYTES),
                new Setting(GraphExecutionLimits.MAX_PAYLOAD_DEPTH_VARIABLE, "graph.payload.maxDepth",
                        defaults.payload().maxDepth(), PayloadLimits.HARD_MAX_DEPTH),
                new Setting(GraphExecutionLimits.MAX_PAYLOAD_COLLECTION_SIZE_VARIABLE,
                        "graph.payload.maxCollectionSize", defaults.payload().maxCollectionSize(),
                        PayloadLimits.HARD_MAX_COLLECTION_SIZE),
                new Setting(GraphExecutionLimits.MAX_PAYLOAD_VALUE_COUNT_VARIABLE, "graph.payload.maxValueCount",
                        defaults.payload().maxValueCount(), PayloadLimits.HARD_MAX_VALUE_COUNT),
                new Setting(GraphExecutionLimits.MAX_PAYLOAD_TEXT_LENGTH_VARIABLE, "graph.payload.maxTextLength",
                        defaults.payload().maxTextLength(), PayloadLimits.HARD_MAX_TEXT_LENGTH),
                new Setting(GraphExecutionLimits.MAX_PAYLOAD_KEY_LENGTH_VARIABLE, "graph.payload.maxKeyLength",
                        defaults.payload().maxKeyLength(), PayloadLimits.HARD_MAX_KEY_LENGTH),
                new Setting(GraphExecutionLimits.MAX_FAN_OUT_VARIABLE, "graph.execution.maxFanOut",
                        defaults.maxFanOut(), GraphExecutionLimits.HARD_MAX_FAN_OUT),
                new Setting(GraphExecutionLimits.MAX_RESIDENT_ACTORS_VARIABLE, "graph.execution.maxResidentActors",
                        defaults.maxResidentActors(), GraphExecutionLimits.HARD_MAX_RESIDENT_ACTORS),
                new Setting(GraphExecutionLimits.MAX_LIVE_ACTORS_VARIABLE,
                        "graph.execution.maxLiveActorsPerTraversal", defaults.maxLiveActorsPerTraversal(),
                        GraphExecutionLimits.HARD_MAX_LIVE_ACTORS),
                new Setting(GraphExecutionLimits.MAX_IN_FLIGHT_HOPS_VARIABLE,
                        "graph.execution.maxInFlightHopsPerTraversal", defaults.maxInFlightHopsPerTraversal(),
                        GraphExecutionLimits.HARD_MAX_IN_FLIGHT_HOPS),
                new Setting(GraphExecutionLimits.MAX_QUEUED_ADMISSIONS_VARIABLE,
                        "graph.execution.maxQueuedAdmissionsPerNode", defaults.maxQueuedAdmissionsPerNode(),
                        GraphExecutionLimits.HARD_MAX_QUEUED_ADMISSIONS),
                new Setting(GraphExecutionLimits.MAX_TRAVERSAL_STEPS_VARIABLE,
                        "graph.execution.maxTraversalSteps", defaults.maxTraversalSteps(),
                        GraphExecutionLimits.HARD_MAX_TRAVERSAL_STEPS),
                new Setting(GraphExecutionLimits.MAX_AMPLIFIED_DELIVERIES_VARIABLE,
                        "graph.execution.maxAmplifiedDeliveries", defaults.maxAmplifiedDeliveries(),
                        GraphExecutionLimits.HARD_MAX_AMPLIFIED_DELIVERIES),
                new Setting(GraphExecutionLimits.MAX_CUMULATIVE_PAYLOAD_BYTES_VARIABLE,
                        "graph.execution.maxCumulativePayloadBytes", defaults.maxCumulativePayloadBytes(),
                        GraphExecutionLimits.HARD_MAX_CUMULATIVE_PAYLOAD_BYTES),
                new Setting(GraphExecutionLimits.MAX_RECOVERY_DELIVERIES_VARIABLE,
                        "graph.execution.maxRecoveryDeliveriesPerAttempt",
                        defaults.maxRecoveryDeliveriesPerAttempt(), GraphExecutionLimits.HARD_MAX_RECOVERY_DELIVERIES));
    }

    private record Setting(String environment, String helmPath, long defaultValue, long ceiling) {
    }
}
