package ai.ravenroot.server.ratelimit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
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
        String rateSchema = jsonObject(schema, "rateLimit");

        assertTrue(schema.contains("\"required\": [\"image\", \"auth\", \"graph\", \"assistant\","
                + " \"rateLimit\"]"));
        assertTrue(rateSchema.contains("\"additionalProperties\": false"));
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
        String schema = jsonObject(read("deploy/helm/ravenroot/values.schema.json"), "rateLimit");
        for (String component : List.of(
                "addressRequestsPerSecond", "principalConcurrentStreams", "maxHeaderValueBytes")) {
            Setting setting = setting(component);
            String tightened = replaceMaximum(schema, setting, setting.maximum() - 1);
            assertThrows(AssertionError.class, () -> assertSchemaLeaf(tightened, setting));
            assertEquals(setting.maximum(),
                    valueOf(RateLimitConfiguration.fromEnvironment(candidate(setting, setting.maximum())), setting));
        }
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

    private static String jsonObject(String json, String property) {
        int start = json.indexOf("\"" + property + "\": {");
        if (start < 0) throw new IllegalStateException("missing JSON object " + property);
        int opening = json.indexOf('{', start);
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = opening; index < json.length(); index++) {
            char current = json.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
            } else if (current == '"') quoted = true;
            else if (current == '{') depth++;
            else if (current == '}' && --depth == 0) return json.substring(opening, index + 1);
        }
        throw new IllegalStateException("unterminated JSON object " + property);
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
