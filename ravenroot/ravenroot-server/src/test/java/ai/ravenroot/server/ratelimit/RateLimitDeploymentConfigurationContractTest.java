package ai.ravenroot.server.ratelimit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps supported deployment carriers aligned with the Java-owned HTTP limit policy. */
class RateLimitDeploymentConfigurationContractTest {
    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Map<String, String> RELATIONSHIP_COMPANIONS = Map.of(
            "RAVENROOT_RATELIMIT_ADDRESS_RPS", "RAVENROOT_RATELIMIT_ADDRESS_BURST",
            "RAVENROOT_RATELIMIT_TENANT_RPS", "RAVENROOT_RATELIMIT_TENANT_BURST",
            "RAVENROOT_RATELIMIT_PRINCIPAL_RPS", "RAVENROOT_RATELIMIT_PRINCIPAL_BURST",
            "RAVENROOT_RATELIMIT_SUBMISSION_RPS", "RAVENROOT_RATELIMIT_SUBMISSION_BURST",
            "RAVENROOT_RATELIMIT_PRINCIPAL_STREAMS", "RAVENROOT_RATELIMIT_TENANT_STREAMS",
            "RAVENROOT_RATELIMIT_MAX_HEADER_VALUE_BYTES", "RAVENROOT_RATELIMIT_MAX_HEADER_BYTES");

    @Test
    void allTwentyThreeSettingsHaveOneBlankCarrierAndTheExactJavaAcceptedScalarRange() throws Exception {
        List<Setting> settings = settings();
        assertEquals(23, settings.size());
        assertEquals(23, settings.stream().map(Setting::environment).collect(Collectors.toSet()).size());
        assertEquals(23, settings.stream().map(Setting::component).collect(Collectors.toSet()).size());
        assertEquals(23, settings.stream().map(Setting::helmLeaf).collect(Collectors.toSet()).size());

        String compose = read("compose.yaml");
        String values = read("deploy/helm/ravenroot/values.yaml");
        String schema = read("deploy/helm/ravenroot/values.schema.json");
        String template = read("deploy/helm/ravenroot/templates/deployment.yaml");
        String kubernetes = read("deploy/kubernetes/ravenroot.yaml");
        Map<String, String> helmValues = yamlScalars(values);
        String rateSchema = rateSchema(schema, settings);

        assertEquals(settings.stream().map(Setting::environment).collect(Collectors.toSet()),
                schemaEnvironmentNames(rateSchema));

        for (Setting setting : settings) {
            assertEquals(valueOf(RateLimitConfiguration.DEFAULTS, setting),
                    valueOf(RateLimitConfiguration.fromEnvironment(Map.of()), setting), setting.environment());
            assertEquals(valueOf(RateLimitConfiguration.DEFAULTS, setting),
                    valueOf(RateLimitConfiguration.fromEnvironment(Map.of(setting.environment(), " \t ")), setting),
                    setting.environment());
            assertEquals(1, occurrences(compose,
                    setting.environment() + ": ${" + setting.environment() + ":-}"), setting.environment());
            assertEquals("\"\"", helmValues.get("rateLimit." + setting.helmLeaf()), setting.helmLeaf());
            assertSchemaLeaf(rateSchema, setting);
            assertEquals(1, occurrences(template,
                    "- name: " + setting.environment() + "\n"
                            + "              value: {{ include \"ravenroot.graphLimitValue\" .Values.rateLimit."
                            + setting.helmLeaf() + " }}"), setting.environment());
            assertEquals(1, occurrences(kubernetes,
                    "- name: " + setting.environment() + "\n              value: \"\""),
                    setting.environment());
        }
    }

    @Test
    void independentlyConstructedAllMinimumAndAllMaximumTuplesAreAccepted() {
        RateLimitConfiguration allMinimum = RateLimitConfiguration.fromEnvironment(allMinimum());
        RateLimitConfiguration allMaximum = RateLimitConfiguration.fromEnvironment(allMaximum());

        for (Setting setting : settings()) {
            assertEquals(1, valueOf(allMinimum, setting), setting.environment());
            assertEquals(setting.maximum(), valueOf(allMaximum, setting), setting.environment());
        }
    }

    @Test
    void everyMaximumIsAcceptedAndTheNextScalarIsRefusedWithUnrelatedRelationshipsValid() {
        for (Setting setting : settings()) {
            Map<String, String> maximum = candidate(setting, setting.maximum());
            assertEquals(setting.maximum(),
                    valueOf(RateLimitConfiguration.fromEnvironment(maximum), setting), setting.environment());

            long above = setting.maximum() + 1;
            Map<String, String> aboveMaximum = candidate(setting, above);
            assertEquals(Long.toString(above), aboveMaximum.get(setting.environment()), setting.environment());
            String companionEnvironment = RELATIONSHIP_COMPANIONS.get(setting.environment());
            if (companionEnvironment != null) {
                assertTrue(Long.parseLong(aboveMaximum.get(companionEnvironment))
                                <= settingByEnvironment(companionEnvironment).maximum(),
                        companionEnvironment);
            }
            assertThrows(IllegalArgumentException.class,
                    () -> RateLimitConfiguration.fromEnvironment(aboveMaximum),
                    setting.environment());
        }
    }

    @Test
    void loweredRelationshipDependentSchemaMaximaCannotMasqueradeAsJavaPolicy() throws Exception {
        String schema = rateSchema(read("deploy/helm/ravenroot/values.schema.json"), settings());
        for (String component : List.of(
                "addressRequestsPerSecond", "principalConcurrentStreams", "maxHeaderValueBytes")) {
            Setting setting = setting(component);
            String tightened = replaceMaximum(schema, setting, setting.maximum() - 1);
            assertThrows(AssertionError.class, () -> assertSchemaLeaf(tightened, setting));
            assertEquals(setting.maximum(),
                    valueOf(RateLimitConfiguration.fromEnvironment(candidate(setting, setting.maximum())), setting));
        }
    }

    @Test
    void rateSchemaSelectionRejectsMissingDuplicateAndShadowedDirectMembers() throws Exception {
        String schema = read("deploy/helm/ravenroot/values.schema.json");
        List<Setting> settings = settings();
        rateSchema(schema, settings);

        String missingRequired = replaceOne(schema,
                "\"assistant\", \"rateLimit\"", "\"assistant\"");
        assertThrows(AssertionError.class, () -> rateSchema(missingRequired, settings));
        String duplicateRequired = replaceOne(schema,
                "\"assistant\", \"rateLimit\"", "\"assistant\", \"rateLimit\", \"rateLimit\"");
        assertThrows(AssertionError.class, () -> rateSchema(duplicateRequired, settings));

        String missingDirect = replaceOne(schema, "    \"rateLimit\": {", "    \"renamedRateLimit\": {");
        assertThrows(AssertionError.class, () -> rateSchema(missingDirect, settings));
        String nestedShadowOnly = addPrecedingRootShadow(missingDirect);
        assertThrows(AssertionError.class, () -> rateSchema(nestedShadowOnly, settings));
        String duplicateDirect = replaceOne(schema,
                "  \"properties\": {\n    \"replicaCount\"",
                "  \"properties\": {\n    \"rateLimit\": {},\n    \"replicaCount\"");
        assertThrows(IllegalStateException.class, () -> rateSchema(duplicateDirect, settings));

        String precedingNestedShadow = addPrecedingRootShadow(schema);
        assertEquals(rateSchema(schema, settings), rateSchema(precedingNestedShadow, settings));

        assertThrows(IllegalStateException.class, () -> objectMembers("{\"rateLimit\": {},}"));
        assertThrows(IllegalStateException.class, () -> stringArray("[\"rateLimit\",]"));
        assertThrows(IllegalStateException.class, () -> stringArray("[\"rateLimit\"] false"));
        assertThrows(IllegalStateException.class, () -> objectMembers("{\"rate\\\\/Limit\": {}}"));
    }

    private static void assertSchemaLeaf(String schema, Setting setting) {
        String expected = "\"" + setting.helmLeaf() + "\": {\n"
                + "          \"x-ravenroot-environment\": \"" + setting.environment() + "\",\n"
                + "          \"oneOf\": [{ \"type\": \"integer\", \"minimum\": 1, \"maximum\": "
                + setting.maximum() + " }, { \"$ref\": \"#/definitions/graphBlank\" }]\n"
                + "        }";
        assertEquals(1, occurrences(schema, expected), setting.environment());
    }

    private static String replaceMaximum(String schema, Setting setting, long replacement) {
        String original = "\"maximum\": " + setting.maximum();
        int leaf = schema.indexOf("\"" + setting.helmLeaf() + "\": {");
        int maximum = schema.indexOf(original, leaf);
        if (leaf < 0 || maximum < 0) throw new IllegalStateException("missing schema leaf");
        return schema.substring(0, maximum) + "\"maximum\": " + replacement
                + schema.substring(maximum + original.length());
    }

    private static Map<String, String> allMinimum() {
        return settings().stream().collect(Collectors.toUnmodifiableMap(Setting::environment, ignored -> "1"));
    }

    private static Map<String, String> allMaximum() {
        return settings().stream().collect(Collectors.toUnmodifiableMap(
                Setting::environment, setting -> Long.toString(setting.maximum())));
    }

    private static Map<String, String> candidate(Setting setting, long value) {
        var environment = new LinkedHashMap<>(allMinimum());
        environment.put(setting.environment(), Long.toString(value));
        switch (setting.component()) {
            case "addressRequestsPerSecond" -> environment.put("RAVENROOT_RATELIMIT_ADDRESS_BURST",
                    boundedCompanion("RAVENROOT_RATELIMIT_ADDRESS_BURST", value));
            case "tenantRequestsPerSecond" -> environment.put("RAVENROOT_RATELIMIT_TENANT_BURST",
                    boundedCompanion("RAVENROOT_RATELIMIT_TENANT_BURST", value));
            case "principalRequestsPerSecond" -> environment.put("RAVENROOT_RATELIMIT_PRINCIPAL_BURST",
                    boundedCompanion("RAVENROOT_RATELIMIT_PRINCIPAL_BURST", value));
            case "submissionsPerSecond" -> environment.put("RAVENROOT_RATELIMIT_SUBMISSION_BURST",
                    boundedCompanion("RAVENROOT_RATELIMIT_SUBMISSION_BURST", value));
            case "principalConcurrentStreams" -> environment.put("RAVENROOT_RATELIMIT_TENANT_STREAMS",
                    boundedCompanion("RAVENROOT_RATELIMIT_TENANT_STREAMS", value));
            case "maxHeaderValueBytes" -> environment.put("RAVENROOT_RATELIMIT_MAX_HEADER_BYTES",
                    boundedCompanion("RAVENROOT_RATELIMIT_MAX_HEADER_BYTES", value));
            default -> { }
        }
        return Map.copyOf(environment);
    }

    private static Setting setting(String component) {
        return settings().stream().filter(setting -> setting.component().equals(component)).findFirst()
                .orElseThrow();
    }

    private static Setting settingByEnvironment(String environment) {
        return settings().stream().filter(setting -> setting.environment().equals(environment)).findFirst()
                .orElseThrow();
    }

    private static String boundedCompanion(String environment, long value) {
        return Long.toString(Math.min(value, settingByEnvironment(environment).maximum()));
    }

    private static long valueOf(RateLimitConfiguration configuration, Setting setting) {
        return setting.value().applyAsLong(configuration);
    }

    private static Set<String> schemaEnvironmentNames(String schema) {
        Matcher matcher = Pattern.compile("\\\"x-ravenroot-environment\\\": \\\"([^\\\"]+)\\\"")
                .matcher(schema);
        var names = new java.util.HashSet<String>();
        while (matcher.find()) names.add(matcher.group(1));
        return Set.copyOf(names);
    }

    private static String rateSchema(String schema, List<Setting> settings) {
        Map<String, String> root = objectMembers(schema);
        List<String> rootRequired = stringArray(root.get("required"));
        assertEquals(1, Collections.frequency(rootRequired, "rateLimit"),
                "root required must contain rateLimit exactly once");
        Map<String, String> rootProperties = objectMembers(root.get("properties"));
        assertTrue(rootProperties.containsKey("rateLimit"),
                "root properties must contain a direct rateLimit group");

        String rateSchema = rootProperties.get("rateLimit");
        Map<String, String> rate = objectMembers(rateSchema);
        assertEquals(Set.of("type", "additionalProperties", "required", "properties"), rate.keySet());
        assertEquals("\"object\"", rate.get("type"));
        assertEquals("false", rate.get("additionalProperties"));
        Set<String> expectedLeaves = settings.stream().map(Setting::helmLeaf).collect(Collectors.toSet());
        List<String> required = stringArray(rate.get("required"));
        assertEquals(23, required.size());
        assertEquals(expectedLeaves, Set.copyOf(required));
        assertEquals(expectedLeaves, objectMembers(rate.get("properties")).keySet());
        return rateSchema;
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
            if (key.indexOf('\\') >= 0) {
                throw new IllegalStateException("escaped JSON member names are unsupported");
            }
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
                cursor = skipWhitespace(json, cursor + 1);
                if (cursor >= json.length() || json.charAt(cursor) == '}') {
                    throw new IllegalStateException("trailing JSON object delimiter");
                }
            } else if (cursor < json.length() && json.charAt(cursor) == '}') {
                cursor++;
                break;
            } else throw new IllegalStateException("expected JSON member delimiter");
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
            if (json.charAt(cursor) == ']') {
                if (skipWhitespace(json, cursor + 1) != json.length()) {
                    throw new IllegalStateException("trailing JSON array content");
                }
                return List.copyOf(values);
            }
            int end = jsonValueEnd(json, cursor);
            values.add(json.substring(cursor, end));
            cursor = skipWhitespace(json, end);
            if (cursor < json.length() && json.charAt(cursor) == ',') {
                cursor = skipWhitespace(json, cursor + 1);
                if (cursor >= json.length() || json.charAt(cursor) == ']') {
                    throw new IllegalStateException("trailing JSON array delimiter");
                }
            } else if (cursor < json.length() && json.charAt(cursor) == ']') {
                if (skipWhitespace(json, cursor + 1) != json.length()) {
                    throw new IllegalStateException("trailing JSON array content");
                }
                return List.copyOf(values);
            } else throw new IllegalStateException("expected JSON array delimiter");
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
        while (cursor < json.length() && json.charAt(cursor) != ','
                && json.charAt(cursor) != '}' && json.charAt(cursor) != ']') cursor++;
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

    private static String addPrecedingRootShadow(String schema) {
        return replaceOne(schema,
                "  \"properties\": {\n    \"replicaCount\"",
                "  \"properties\": {\n    \"shadow\": {\"rateLimit\": {}},\n    \"replicaCount\"");
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
            if (value == null || value.isEmpty()) parents.addLast(key);
            else values.put(String.join(".", parents) + (parents.isEmpty() ? "" : ".") + key, value);
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

    private static List<Setting> settings() {
        return List.of(
                setting("RAVENROOT_RATELIMIT_ADDRESS_RPS", "addressRequestsPerSecond", 1_000_000,
                        RateLimitConfiguration::addressRequestsPerSecond),
                setting("RAVENROOT_RATELIMIT_ADDRESS_BURST", "addressBurst", 1_000_000,
                        RateLimitConfiguration::addressBurst),
                setting("RAVENROOT_RATELIMIT_TENANT_RPS", "tenantRequestsPerSecond", 1_000_000,
                        RateLimitConfiguration::tenantRequestsPerSecond),
                setting("RAVENROOT_RATELIMIT_TENANT_BURST", "tenantBurst", 1_000_000,
                        RateLimitConfiguration::tenantBurst),
                setting("RAVENROOT_RATELIMIT_PRINCIPAL_RPS", "principalRequestsPerSecond", 1_000_000,
                        RateLimitConfiguration::principalRequestsPerSecond),
                setting("RAVENROOT_RATELIMIT_PRINCIPAL_BURST", "principalBurst", 1_000_000,
                        RateLimitConfiguration::principalBurst),
                setting("RAVENROOT_RATELIMIT_SUBMISSION_RPS", "submissionsPerSecond", 1_000_000,
                        RateLimitConfiguration::submissionsPerSecond),
                setting("RAVENROOT_RATELIMIT_SUBMISSION_BURST", "submissionBurst", 1_000_000,
                        RateLimitConfiguration::submissionBurst),
                setting("RAVENROOT_RATELIMIT_TENANT_CONCURRENT_SUBMISSIONS", "tenantConcurrentSubmissions",
                        Integer.MAX_VALUE, RateLimitConfiguration::tenantConcurrentSubmissions),
                setting("RAVENROOT_RATELIMIT_GLOBAL_ACTIVE_EXECUTIONS", "globalActiveExecutions",
                        Integer.MAX_VALUE, RateLimitConfiguration::globalActiveExecutions),
                setting("RAVENROOT_RATELIMIT_TENANT_STREAMS", "tenantConcurrentStreams",
                        Integer.MAX_VALUE, RateLimitConfiguration::tenantConcurrentStreams),
                setting("RAVENROOT_RATELIMIT_PRINCIPAL_STREAMS", "principalConcurrentStreams",
                        Integer.MAX_VALUE, RateLimitConfiguration::principalConcurrentStreams),
                setting("RAVENROOT_SSE_QUEUE_CAPACITY", "streamQueueCapacity", Integer.MAX_VALUE,
                        RateLimitConfiguration::streamQueueCapacity),
                setting("RAVENROOT_RATELIMIT_MAX_QUERY_BYTES", "maxQueryBytes", Integer.MAX_VALUE,
                        RateLimitConfiguration::maxQueryBytes),
                setting("RAVENROOT_RATELIMIT_MAX_QUERY_PARAMETERS", "maxQueryParameters", Integer.MAX_VALUE,
                        RateLimitConfiguration::maxQueryParameters),
                setting("RAVENROOT_RATELIMIT_MAX_HEADER_COUNT", "maxHeaderCount", Integer.MAX_VALUE,
                        RateLimitConfiguration::maxHeaderCount),
                setting("RAVENROOT_RATELIMIT_MAX_HEADER_BYTES", "maxHeaderBytes", Integer.MAX_VALUE,
                        RateLimitConfiguration::maxHeaderBytes),
                setting("RAVENROOT_RATELIMIT_MAX_HEADER_VALUE_BYTES", "maxHeaderValueBytes", Integer.MAX_VALUE,
                        RateLimitConfiguration::maxHeaderValueBytes),
                setting("RAVENROOT_RATELIMIT_MAX_TRACKED_CLIENTS", "maxTrackedClients", Integer.MAX_VALUE,
                        RateLimitConfiguration::maxTrackedClients),
                setting("RAVENROOT_RATELIMIT_MAX_TRACKED_TENANTS", "maxTrackedTenants", Integer.MAX_VALUE,
                        RateLimitConfiguration::maxTrackedTenants),
                setting("RAVENROOT_RATELIMIT_MAX_TRACKED_PRINCIPALS", "maxTrackedPrincipals", Integer.MAX_VALUE,
                        RateLimitConfiguration::maxTrackedPrincipals),
                setting("RAVENROOT_RATELIMIT_IDLE_TTL_SECONDS", "idleEntryTtl", 3_600,
                        configuration -> configuration.idleEntryTtl().toSeconds()),
                setting("RAVENROOT_RATELIMIT_EXECUTION_MAX_AGE_SECONDS", "executionMaxAge", 86_400,
                        configuration -> configuration.executionMaxAge().toSeconds()));
    }

    private static Setting setting(String environment, String component, long maximum,
                                   ToLongFunction<RateLimitConfiguration> value) {
        String helmLeaf = switch (component) {
            case "idleEntryTtl" -> "idleEntryTtlSeconds";
            case "executionMaxAge" -> "executionMaxAgeSeconds";
            default -> component;
        };
        return new Setting(environment, component, helmLeaf, maximum, value);
    }

    private record Setting(String environment, String component, String helmLeaf, long maximum,
                           ToLongFunction<RateLimitConfiguration> value) {
    }
}
