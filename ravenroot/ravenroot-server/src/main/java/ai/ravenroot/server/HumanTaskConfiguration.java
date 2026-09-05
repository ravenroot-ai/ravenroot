package ai.ravenroot.server;

import ai.ravenroot.api.persistence.HumanTaskPolicy;

import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** Reads the complete operator-owned Human Task policy once during server startup. */
public final class HumanTaskConfiguration {
    private HumanTaskConfiguration() {
    }

    public static HumanTaskPolicy fromEnvironment(Map<String, String> environment) {
        return fromSources(Map.of(), environment);
    }

    /** System properties take precedence over environment variables; blank values defer. */
    public static HumanTaskPolicy fromSystem(Properties properties, Map<String, String> environment) {
        Objects.requireNonNull(properties, "properties");
        var text = new java.util.HashMap<String, String>();
        properties.forEach((name, value) -> text.put(String.valueOf(name), String.valueOf(value)));
        return fromSources(text, environment);
    }

    static HumanTaskPolicy fromSources(Map<String, String> properties,
                                       Map<String, String> environment) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(environment, "environment");
        HumanTaskPolicy defaults = HumanTaskPolicy.DEFAULTS;
        try {
            return new HumanTaskPolicy(
                integer(properties, environment, "ravenroot.human-task.default-response-bytes",
                        "RAVENROOT_HUMAN_TASK_DEFAULT_RESPONSE_BYTES",
                        defaults.defaultResponseBytes()),
                integer(properties, environment, "ravenroot.human-task.max-response-bytes",
                        "RAVENROOT_HUMAN_TASK_MAX_RESPONSE_BYTES",
                        defaults.maxResponseBytes()),
                whole(properties, environment, "ravenroot.human-task.default-escalation-seconds",
                        "RAVENROOT_HUMAN_TASK_DEFAULT_ESCALATION_SECONDS",
                        defaults.defaultEscalationSeconds()),
                whole(properties, environment, "ravenroot.human-task.max-escalation-seconds",
                        "RAVENROOT_HUMAN_TASK_MAX_ESCALATION_SECONDS",
                        defaults.maxEscalationSeconds()),
                whole(properties, environment, "ravenroot.human-task.default-expiry-seconds",
                        "RAVENROOT_HUMAN_TASK_DEFAULT_EXPIRY_SECONDS",
                        defaults.defaultExpirySeconds()),
                whole(properties, environment, "ravenroot.human-task.max-expiry-seconds",
                        "RAVENROOT_HUMAN_TASK_MAX_EXPIRY_SECONDS",
                        defaults.maxExpirySeconds()),
                integer(properties, environment, "ravenroot.human-task.max-title-bytes",
                        "RAVENROOT_HUMAN_TASK_MAX_TITLE_BYTES",
                        defaults.maxTitleUtf8Bytes()),
                integer(properties, environment, "ravenroot.human-task.max-description-bytes",
                        "RAVENROOT_HUMAN_TASK_MAX_DESCRIPTION_BYTES",
                        defaults.maxDescriptionUtf8Bytes()),
                integer(properties, environment, "ravenroot.human-task.max-response-schema-bytes",
                        "RAVENROOT_HUMAN_TASK_MAX_RESPONSE_SCHEMA_BYTES",
                        defaults.maxResponseSchemaUtf8Bytes()),
                integer(properties, environment, "ravenroot.human-task.max-authorization-tokens",
                        "RAVENROOT_HUMAN_TASK_MAX_AUTHORIZATION_TOKENS",
                        defaults.maxAuthorizationTokens()),
                integer(properties, environment, "ravenroot.human-task.max-authorization-token-bytes",
                        "RAVENROOT_HUMAN_TASK_MAX_AUTHORIZATION_TOKEN_BYTES",
                        defaults.maxAuthorizationTokenUtf8Bytes()),
                integer(properties, environment, "ravenroot.human-task.max-decision-body-bytes",
                        "RAVENROOT_HUMAN_TASK_MAX_DECISION_BODY_BYTES",
                        defaults.decisionBodyMaxBytes()),
                integer(properties, environment, "ravenroot.human-task.default-page-size",
                        "RAVENROOT_HUMAN_TASK_DEFAULT_PAGE_SIZE",
                        defaults.inboxDefaultPageSize()),
                integer(properties, environment, "ravenroot.human-task.max-page-size",
                        "RAVENROOT_HUMAN_TASK_MAX_PAGE_SIZE",
                        defaults.inboxMaxPageSize()),
                integer(properties, environment, "ravenroot.human-task.response-max-depth",
                        "RAVENROOT_HUMAN_TASK_RESPONSE_MAX_DEPTH",
                        defaults.responseMaxDepth()),
                integer(properties, environment, "ravenroot.human-task.response-max-collection-size",
                        "RAVENROOT_HUMAN_TASK_RESPONSE_MAX_COLLECTION_SIZE",
                        defaults.responseMaxCollectionSize()),
                integer(properties, environment, "ravenroot.human-task.response-max-value-count",
                        "RAVENROOT_HUMAN_TASK_RESPONSE_MAX_VALUE_COUNT",
                        defaults.responseMaxValueCount()),
                integer(properties, environment, "ravenroot.human-task.response-max-text-length",
                        "RAVENROOT_HUMAN_TASK_RESPONSE_MAX_TEXT_LENGTH",
                        defaults.responseMaxTextLength()),
                integer(properties, environment, "ravenroot.human-task.response-max-key-length",
                        "RAVENROOT_HUMAN_TASK_RESPONSE_MAX_KEY_LENGTH",
                        defaults.responseMaxKeyLength()),
                integer(properties, environment, "ravenroot.human-task.write-attempts",
                        "RAVENROOT_HUMAN_TASK_WRITE_ATTEMPTS",
                        defaults.writeAttempts()));
        } catch (IllegalArgumentException invalid) {
            throw attributed(invalid);
        }
    }

    private static int integer(Map<String, String> properties, Map<String, String> environment,
                               String property, String variable, int fallback) {
        long value = whole(properties, environment, property, variable, fallback);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw invalid(property, variable);
        }
        return (int) value;
    }

    private static long whole(Map<String, String> properties, Map<String, String> environment,
                              String property, String variable, long fallback) {
        String raw = nonBlank(properties.get(property));
        if (raw == null) raw = nonBlank(environment.get(variable));
        if (raw == null) return fallback;
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException invalid) {
            throw invalid(property, variable);
        }
    }

    private static String nonBlank(String value) {
        if (value == null) return null;
        int start = 0;
        int end = value.length();
        while (start < end) {
            int point = value.codePointAt(start);
            if (!blank(point)) break;
            start += Character.charCount(point);
        }
        while (end > start) {
            int point = value.codePointBefore(end);
            if (!blank(point)) break;
            end -= Character.charCount(point);
        }
        return start == end ? null : value.substring(start, end);
    }

    private static boolean blank(int point) {
        return Character.isWhitespace(point) || Character.isSpaceChar(point);
    }

    private static IllegalArgumentException attributed(IllegalArgumentException invalid) {
        String message = invalid.getMessage() == null ? "violates the documented Human Task policy"
                : invalid.getMessage();
        String[][] names = {
                {"defaultResponseBytes", "default-response-bytes"}, {"maxResponseBytes", "max-response-bytes"},
                {"defaultEscalationSeconds", "default-escalation-seconds"}, {"maxEscalationSeconds", "max-escalation-seconds"},
                {"defaultExpirySeconds", "default-expiry-seconds"}, {"maxExpirySeconds", "max-expiry-seconds"},
                {"maxTitleUtf8Bytes", "max-title-bytes"}, {"maxDescriptionUtf8Bytes", "max-description-bytes"},
                {"maxResponseSchemaUtf8Bytes", "max-response-schema-bytes"},
                {"maxAuthorizationTokens", "max-authorization-tokens"},
                {"maxAuthorizationTokenUtf8Bytes", "max-authorization-token-bytes"},
                {"decisionBodyMaxBytes", "max-decision-body-bytes"},
                {"inboxDefaultPageSize", "default-page-size"}, {"inboxMaxPageSize", "max-page-size"},
                {"responseMaxDepth", "response-max-depth"},
                {"responseMaxCollectionSize", "response-max-collection-size"},
                {"responseMaxValueCount", "response-max-value-count"},
                {"responseMaxTextLength", "response-max-text-length"},
                {"responseMaxKeyLength", "response-max-key-length"}, {"writeAttempts", "write-attempts"}
        };
        for (String[] name : names) {
            if (message.contains(name[0])) {
                String property = "ravenroot.human-task." + name[1];
                String variable = "RAVENROOT_HUMAN_TASK_" + name[1].replace('-', '_').toUpperCase();
                message = message.replace(name[0], property + " / " + variable);
            }
        }
        return new IllegalArgumentException(message, invalid);
    }

    private static IllegalArgumentException invalid(String property, String variable) {
        return new IllegalArgumentException(property + " / " + variable
                + " must be a whole number within its documented range");
    }
}
