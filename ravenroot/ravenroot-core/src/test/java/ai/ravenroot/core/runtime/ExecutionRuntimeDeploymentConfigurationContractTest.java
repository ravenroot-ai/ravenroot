package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.ExecutionEnginePolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps the deployment schema aligned with the single Java execution-runtime authority. */
class ExecutionRuntimeDeploymentConfigurationContractTest {
    private static final Path SCHEMA = Path.of("..", "..", "deploy", "helm", "ravenroot",
            "values.schema.json").toAbsolutePath().normalize();

    @Test
    void exactFourSchemaLeavesMatchJavaNamesDefaultsAndCeilings() throws Exception {
        List<Setting> settings = settings();
        assertEquals(4, settings.size());
        assertEquals(4, settings.stream().map(Setting::environment).collect(Collectors.toSet()).size());
        assertEquals(4, settings.stream().map(Setting::component).collect(Collectors.toSet()).size());
        assertEquals(4, settings.stream().map(Setting::helmLeaf).collect(Collectors.toSet()).size());

        ExecutionRuntimeConfiguration defaults = ExecutionRuntimeConfiguration.DEFAULTS;
        assertEquals(ExecutionEnginePolicy.FROZEN_LEGACY, defaults.enginePolicy());
        assertSame(defaults.runnerShutdownStepBound(), GraphRunner.DEFAULT_SHUTDOWN_BOUND);
        for (Setting setting : settings) {
            assertEquals(setting.maximum(), setting.value().applyAsLong(defaults), setting.environment());
            assertEquals(setting.maximum(), setting.value().applyAsLong(
                    ExecutionRuntimeConfiguration.fromEnvironment(Map.of())), setting.environment());
            for (String blank : List.of("", " \t ", "\u2003", "\u001c\u3000")) {
                assertEquals(setting.maximum(), setting.value().applyAsLong(
                        ExecutionRuntimeConfiguration.fromEnvironment(Map.of(setting.environment(), blank))),
                        setting.environment());
            }
            assertEquals(1, setting.value().applyAsLong(ExecutionRuntimeConfiguration.fromEnvironment(
                    Map.of(setting.environment(), " \u200301\u2003 "))), setting.environment());
        }

        assertRuntimeSchema(Files.readString(SCHEMA), settings);
    }

    @Test
    void everyFieldAcceptsItsIndependentBoundsAndRefusesMalformedOrWidenedValues() {
        for (Setting setting : settings()) {
            assertEquals(1, setting.value().applyAsLong(ExecutionRuntimeConfiguration.fromEnvironment(
                    Map.of(setting.environment(), "1"))), setting.environment());
            assertEquals(setting.maximum(), setting.value().applyAsLong(
                    ExecutionRuntimeConfiguration.fromEnvironment(
                            Map.of(setting.environment(), Long.toString(setting.maximum())))),
                    setting.environment());
            for (String invalid : List.of("0", "-1", "+1", "1.0", "not-a-number", "١",
                    "\u00a0", "\u2007", "\u202f", Long.toString(setting.maximum() + 1))) {
                IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                        () -> ExecutionRuntimeConfiguration.fromEnvironment(
                                Map.of(setting.environment(), invalid)), setting.environment());
                assertTrue(failure.getMessage().contains(setting.environment()));
                assertEquals(null, failure.getCause());
            }
        }
    }

    @Test
    void missingExtraDuplicateAndSwappedSchemaLeavesAreDetected() throws Exception {
        String schema = Files.readString(SCHEMA);
        List<Setting> settings = settings();
        assertRuntimeSchema(schema, settings);

        String runtime = runtimeSchema(schema);
        assertThrows(AssertionError.class, () -> assertRuntimeObject(
                replaceOne(runtime, "\"type\": \"object\"", "\"type\": \"array\""), settings));
        assertThrows(AssertionError.class, () -> assertRuntimeObject(
                replaceOne(runtime, "      \"type\": \"object\",\n", ""), settings));
        assertThrows(AssertionError.class, () -> assertRuntimeObject(
                replaceOne(runtime, "      \"additionalProperties\": false,\n", ""), settings));
        assertThrows(AssertionError.class, () -> assertRuntimeObject(
                replaceOne(runtime, "\"additionalProperties\": false",
                        "\"additionalProperties\": true"), settings));

        String duplicateRequired = replaceOne(runtime,
                "\"maxStashedCommandsPerNode\", \"lifecycleStepSeconds\"",
                "\"maxStashedCommandsPerNode\", \"maxStashedCommandsPerNode\", "
                        + "\"lifecycleStepSeconds\"");
        assertThrows(AssertionError.class, () -> assertRuntimeObject(duplicateRequired, settings));
        String missingRequired = replaceOne(runtime,
                "\"maxStashedCommandsPerNode\", \"lifecycleStepSeconds\"",
                "\"lifecycleStepSeconds\"");
        assertThrows(AssertionError.class, () -> assertRuntimeObject(missingRequired, settings));
        String extraRequired = replaceOne(runtime,
                "\"terminalHistoryCapacity\", \"runnerShutdownStepSeconds\"",
                "\"terminalHistoryCapacity\", \"runnerShutdownStepSeconds\", \"unexpected\"");
        assertThrows(AssertionError.class, () -> assertRuntimeObject(extraRequired, settings));

        String missing = runtime.replaceFirst(
                "(?s)\\s*\"runnerShutdownStepSeconds\": \\{.*?\\n        \\}", "");
        assertThrows(AssertionError.class, () -> assertRuntimeObject(missing, settings));

        String extra = runtime.replaceFirst("\"properties\": \\{",
                "\"properties\": {\n        \"unexpected\": {},");
        assertThrows(AssertionError.class, () -> assertRuntimeObject(extra, settings));

        String duplicated = runtime.replaceFirst("\"properties\": \\{",
                "\"properties\": {\n        \"maxStashedCommandsPerNode\": {},");
        assertThrows(IllegalStateException.class, () -> assertRuntimeObject(duplicated, settings));

        String first = settings.get(0).environment();
        String second = settings.get(1).environment();
        String swapped = runtime.replace(first, "SWAP_SENTINEL")
                .replace(second, first).replace("SWAP_SENTINEL", second);
        assertThrows(AssertionError.class, () -> assertRuntimeObject(swapped, settings));

        String extraLeafConstraint = replaceOne(runtime,
                "\"x-ravenroot-environment\": \"" + first + "\",",
                "\"x-ravenroot-environment\": \"" + first + "\",\n"
                        + "          \"description\": \"unexpected\",");
        assertThrows(AssertionError.class, () -> assertRuntimeObject(extraLeafConstraint, settings));
        String firstOneOf = "\"oneOf\": [{ \"type\": \"integer\", \"minimum\": 1, \"maximum\": "
                + settings.get(0).maximum() + " }, { \"$ref\": \"#/definitions/graphBlank\" }]";
        String extraOneOfBranch = replaceOne(runtime, firstOneOf,
                firstOneOf.substring(0, firstOneOf.length() - 1) + ", { \"type\": \"null\" }]");
        assertThrows(AssertionError.class, () -> assertRuntimeObject(extraOneOfBranch, settings));
    }

    private static void assertRuntimeSchema(String schema, List<Setting> settings) {
        Map<String, String> root = objectMembers(schema);
        assertTrue(stringArray(root.get("required")).contains("executionRuntime"));
        Map<String, String> properties = objectMembers(root.get("properties"));
        assertRuntimeObject(properties.get("executionRuntime"), settings);
    }

    private static String runtimeSchema(String schema) {
        return objectMembers(objectMembers(schema).get("properties")).get("executionRuntime");
    }

    private static void assertRuntimeObject(String runtime, List<Setting> settings) {
        Map<String, String> object = objectMembers(runtime);
        assertEquals(Set.of("type", "additionalProperties", "required", "properties"), object.keySet());
        assertEquals("\"object\"", object.get("type"));
        assertEquals("false", object.get("additionalProperties"));
        Set<String> expectedLeaves = settings.stream().map(Setting::helmLeaf).collect(Collectors.toSet());
        List<String> required = stringArray(object.get("required"));
        assertEquals(4, required.size());
        assertEquals(expectedLeaves, Set.copyOf(required));
        Map<String, String> properties = objectMembers(object.get("properties"));
        assertEquals(expectedLeaves, properties.keySet());
        for (Setting setting : settings) {
            Map<String, String> leaf = objectMembers(properties.get(setting.helmLeaf()));
            assertEquals(Set.of("x-ravenroot-environment", "oneOf"), leaf.keySet());
            assertEquals("\"" + setting.environment() + "\"", leaf.get("x-ravenroot-environment"));
            List<String> choices = arrayValues(leaf.get("oneOf"));
            assertEquals(2, choices.size(), setting.environment());
            Map<String, String> integer = objectMembers(choices.get(0));
            assertEquals(Set.of("type", "minimum", "maximum"), integer.keySet());
            assertEquals("\"integer\"", integer.get("type"));
            assertEquals("1", integer.get("minimum"));
            assertEquals(Long.toString(setting.maximum()), integer.get("maximum"));
            assertEquals(Map.of("$ref", "\"#/definitions/graphBlank\""),
                    objectMembers(choices.get(1)));
        }
    }

    private static List<Setting> settings() {
        ExecutionEnginePolicy frozen = ExecutionEnginePolicy.FROZEN_LEGACY;
        return List.of(
                new Setting(ExecutionRuntimeConfiguration.MAX_STASHED_COMMANDS_PER_NODE_VARIABLE,
                        "maxStashedCommandsPerNode", "maxStashedCommandsPerNode",
                        frozen.maxStashedCommandsPerNode(),
                        value -> value.enginePolicy().maxStashedCommandsPerNode()),
                new Setting(ExecutionRuntimeConfiguration.LIFECYCLE_STEP_SECONDS_VARIABLE,
                        "lifecycleStepBound", "lifecycleStepSeconds",
                        frozen.lifecycleStepBound().getSeconds(),
                        value -> value.enginePolicy().lifecycleStepBound().getSeconds()),
                new Setting(ExecutionRuntimeConfiguration.TERMINAL_HISTORY_CAPACITY_VARIABLE,
                        "terminalNodeHistoryCapacity", "terminalHistoryCapacity",
                        frozen.terminalNodeHistoryCapacity(),
                        value -> value.enginePolicy().terminalNodeHistoryCapacity()),
                new Setting(ExecutionRuntimeConfiguration.RUNNER_SHUTDOWN_STEP_SECONDS_VARIABLE,
                        "runnerShutdownStepBound", "runnerShutdownStepSeconds",
                        ExecutionRuntimeConfiguration.DEFAULTS.runnerShutdownStepBound().getSeconds(),
                        value -> value.runnerShutdownStepBound().getSeconds()));
    }

    private static Map<String, String> objectMembers(String json) {
        if (json == null) throw new IllegalStateException("missing JSON object");
        int cursor = skipWhitespace(json, 0);
        if (cursor >= json.length() || json.charAt(cursor++) != '{') {
            throw new IllegalStateException("expected JSON object");
        }
        var members = new LinkedHashMap<String, String>();
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
            if (cursor >= json.length() || json.charAt(cursor++) != ':') {
                throw new IllegalStateException("expected JSON member separator");
            }
            int valueStart = skipWhitespace(json, cursor);
            int valueEnd = jsonValueEnd(json, valueStart);
            if (members.putIfAbsent(key, json.substring(valueStart, valueEnd)) != null) {
                throw new IllegalStateException("duplicate JSON member " + key);
            }
            cursor = skipWhitespace(json, valueEnd);
            if (cursor < json.length() && json.charAt(cursor) == ',') {
                cursor++;
            } else if (cursor < json.length() && json.charAt(cursor) == '}') {
                cursor++;
                break;
            } else {
                throw new IllegalStateException("expected JSON member delimiter");
            }
        }
        if (skipWhitespace(json, cursor) != json.length()) {
            throw new IllegalStateException("trailing JSON content");
        }
        return Map.copyOf(members);
    }

    private static List<String> stringArray(String json) {
        return arrayValues(json).stream().map(value -> {
            if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
                throw new IllegalStateException("expected JSON string array value");
            }
            return value.substring(1, value.length() - 1);
        }).toList();
    }

    private static List<String> arrayValues(String json) {
        if (json == null) throw new IllegalStateException("missing JSON array");
        int cursor = skipWhitespace(json, 0);
        if (cursor >= json.length() || json.charAt(cursor++) != '[') {
            throw new IllegalStateException("expected JSON array");
        }
        var values = new ArrayList<String>();
        while (true) {
            cursor = skipWhitespace(json, cursor);
            if (cursor >= json.length()) throw new IllegalStateException("unterminated JSON array");
            if (json.charAt(cursor) == ']') return List.copyOf(values);
            int end = jsonValueEnd(json, cursor);
            values.add(json.substring(cursor, end));
            cursor = skipWhitespace(json, end);
            if (json.charAt(cursor) == ',') cursor++;
            else if (json.charAt(cursor) != ']') throw new IllegalStateException("expected array delimiter");
        }
    }

    private static int jsonValueEnd(String json, int start) {
        char first = json.charAt(start);
        if (first == '"') return jsonStringEnd(json, start);
        if (first == '{' || first == '[') {
            var closing = new ArrayDeque<Character>();
            closing.addLast(first == '{' ? '}' : ']');
            for (int cursor = start + 1; cursor < json.length(); cursor++) {
                char current = json.charAt(cursor);
                if (current == '"') cursor = jsonStringEnd(json, cursor) - 1;
                else if (current == '{') closing.addLast('}');
                else if (current == '[') closing.addLast(']');
                else if (current == '}' || current == ']') {
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
        return skipWhitespaceBack(json, cursor);
    }

    private static int jsonStringEnd(String json, int opening) {
        if (opening >= json.length() || json.charAt(opening) != '"') {
            throw new IllegalStateException("expected JSON string");
        }
        for (int cursor = opening + 1; cursor < json.length(); cursor++) {
            if (json.charAt(cursor) == '\\') cursor++;
            else if (json.charAt(cursor) == '"') return cursor + 1;
        }
        throw new IllegalStateException("unterminated JSON string");
    }

    private static int skipWhitespace(String text, int start) {
        int cursor = start;
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) cursor++;
        return cursor;
    }

    private static int skipWhitespaceBack(String text, int end) {
        int cursor = end;
        while (cursor > 0 && Character.isWhitespace(text.charAt(cursor - 1))) cursor--;
        return cursor;
    }

    private static String replaceOne(String source, String target, String replacement) {
        int first = source.indexOf(target);
        if (first < 0 || source.indexOf(target, first + target.length()) >= 0) {
            throw new IllegalStateException("expected one schema fragment: " + target);
        }
        return source.substring(0, first) + replacement + source.substring(first + target.length());
    }

    private record Setting(String environment, String component, String helmLeaf, long maximum,
                           ToLongFunction<ExecutionRuntimeConfiguration> value) { }
}
