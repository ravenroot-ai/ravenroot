package ai.ravenroot.server.persistence;

import ai.ravenroot.persistence.postgresql.PostgresStoreConfig;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** Resolves PostgreSQL adapter policy once at the server composition boundary. */
final class PostgresStoreConfiguration {
    private PostgresStoreConfiguration() {
    }

    static PostgresStoreConfig fromSystem(Properties properties, Map<String, String> environment) {
        Objects.requireNonNull(properties, "properties");
        return fromSources(properties.stringPropertyNames().stream()
                        .collect(java.util.stream.Collectors.toMap(name -> name, properties::getProperty)),
                environment);
    }

    static PostgresStoreConfig fromSources(Map<String, String> properties,
                                           Map<String, String> environment) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(environment, "environment");
        PostgresStoreConfig defaults = PostgresStoreConfig.defaults();
        return new PostgresStoreConfig(
                millis(properties, environment, "ravenroot.postgresql.lock-timeout-ms",
                        "RAVENROOT_POSTGRES_LOCK_TIMEOUT_MS",
                        defaults.lockTimeout()),
                millis(properties, environment, "ravenroot.postgresql.statement-timeout-ms",
                        "RAVENROOT_POSTGRES_STATEMENT_TIMEOUT_MS",
                        defaults.statementTimeout()),
                integer(properties, environment, "ravenroot.postgresql.serialization-retries",
                        "RAVENROOT_POSTGRES_SERIALIZATION_RETRIES",
                        defaults.serializationRetries(), 0),
                seconds(properties, environment, "ravenroot.postgresql.max-lease-ttl-seconds",
                        "RAVENROOT_POSTGRES_MAX_LEASE_TTL_SECONDS",
                        defaults.maxLeaseTtl(), false),
                integer(properties, environment, "ravenroot.postgresql.max-payload-bytes",
                        "RAVENROOT_POSTGRES_MAX_PAYLOAD_BYTES",
                        defaults.maxPayloadBytes(), 1),
                seconds(properties, environment, "ravenroot.postgresql.max-clock-skew-seconds",
                        "RAVENROOT_POSTGRES_MAX_CLOCK_SKEW_SECONDS",
                        defaults.maxClockSkew(), true),
                seconds(properties, environment, "ravenroot.postgresql.journal-retention-seconds",
                        "RAVENROOT_POSTGRES_JOURNAL_RETENTION_SECONDS",
                        defaults.journalRetention(), false),
                integer(properties, environment, "ravenroot.postgresql.max-inventory-page-size",
                        "RAVENROOT_POSTGRES_MAX_INVENTORY_PAGE_SIZE",
                        defaults.maxInventoryPageSize(), 1),
                seconds(properties, environment, "ravenroot.postgresql.terminal-retention-seconds",
                        "RAVENROOT_POSTGRES_TERMINAL_RETENTION_SECONDS",
                        defaults.terminalRetention(), false),
                seconds(properties, environment, "ravenroot.postgresql.execution-result-retention-seconds",
                        "RAVENROOT_POSTGRES_EXECUTION_RESULT_RETENTION_SECONDS",
                        defaults.executionResultRetention(), false),
                integer(properties, environment, "ravenroot.postgresql.graph-definition-upsert-attempts",
                        "RAVENROOT_POSTGRES_GRAPH_DEFINITION_UPSERT_ATTEMPTS",
                        defaults.graphDefinitionUpsertAttempts(), 1));
    }

    static boolean anyConfigured(Map<String, String> properties, Map<String, String> environment) {
        return properties.entrySet().stream().anyMatch(entry ->
                entry.getKey().startsWith("ravenroot.postgresql.") && nonblank(entry.getValue()))
                || environment.entrySet().stream().anyMatch(entry ->
                entry.getKey().startsWith("RAVENROOT_POSTGRES_") && nonblank(entry.getValue()));
    }

    private static Duration millis(Map<String, String> properties, Map<String, String> environment,
                                   String propertySuffix, String environmentSuffix, Duration fallback) {
        return duration(properties, environment, propertySuffix, environmentSuffix, fallback, false, false);
    }

    private static Duration seconds(Map<String, String> properties, Map<String, String> environment,
                                    String propertySuffix, String environmentSuffix, Duration fallback,
                                    boolean allowZero) {
        return duration(properties, environment, propertySuffix, environmentSuffix, fallback, true, allowZero);
    }

    private static Duration duration(Map<String, String> properties, Map<String, String> environment,
                                     String property, String variable, Duration fallback,
                                     boolean seconds, boolean allowZero) {
        String raw = selected(properties, environment, property, variable);
        if (raw == null) return fallback;
        try {
            long amount = Long.parseLong(raw);
            if (amount < 1 && !(allowZero && amount == 0)) {
                throw new NumberFormatException();
            }
            Duration value = seconds ? Duration.ofSeconds(amount) : Duration.ofMillis(amount);
            value.toMillis(); // JDBC consumes milliseconds; reject values it cannot represent.
            return value;
        } catch (ArithmeticException | NumberFormatException invalid) {
            String sign = allowZero ? "non-negative" : "positive";
            throw new IllegalArgumentException(property + " / " + variable
                    + " must be a representable " + sign + " whole number of "
                    + (seconds ? "seconds" : "milliseconds"));
        }
    }

    private static int integer(Map<String, String> properties, Map<String, String> environment,
                               String property, String variable, int fallback, int minimum) {
        String raw = selected(properties, environment, property, variable);
        if (raw == null) return fallback;
        try {
            int value = Integer.parseInt(raw);
            if (value < minimum) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(property + " / " + variable
                    + " must be a whole number of at least " + minimum);
        }
    }

    private static String selected(Map<String, String> properties, Map<String, String> environment,
                                   String property, String variable) {
        String raw = properties.get(property);
        if (!nonblank(raw)) raw = environment.get(variable);
        return nonblank(raw) ? raw.trim() : null;
    }

    private static boolean nonblank(String value) {
        return value != null && !value.isBlank();
    }
}
