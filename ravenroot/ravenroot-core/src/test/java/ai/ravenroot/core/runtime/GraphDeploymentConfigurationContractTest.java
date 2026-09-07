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
            assertSchemaLeaf(schema, setting);
            assertEquals(1, occurrences(template,
                    "- name: " + setting.environment() + "\n"
                            + "              value: {{ include \"ravenroot.graphLimitValue\" .Values."
                            + setting.helmPath() + " }}"), setting.environment());
            assertEquals(1, occurrences(kubernetes,
                    "- name: " + setting.environment() + "\n              value: \"\""),
                    setting.environment());
        }

        assertEquals(javaVariables(), schemaEnvironmentNames(schema));
    }

    private static void assertSchemaLeaf(String schema, Setting setting) {
        String expected = "\"x-ravenroot-environment\": \"" + setting.environment() + "\",\n"
                + "              \"oneOf\": [{ \"type\": \"integer\", \"minimum\": 1, \"maximum\": "
                + setting.ceiling() + " }, { \"$ref\": \"#/definitions/blank\" }]";
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

    private static Set<String> schemaEnvironmentNames(String schema) {
        Matcher matcher = Pattern.compile("\\\"x-ravenroot-environment\\\": \\\"([^\\\"]+)\\\"").matcher(schema);
        var names = new java.util.HashSet<String>();
        while (matcher.find()) names.add(matcher.group(1));
        return Set.copyOf(names);
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
