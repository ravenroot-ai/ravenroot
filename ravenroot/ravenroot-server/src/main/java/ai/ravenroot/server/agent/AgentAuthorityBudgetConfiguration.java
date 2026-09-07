package ai.ravenroot.server.agent;

import ai.ravenroot.api.persistence.AgentBudgetVector;
import ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetPolicy;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Clock;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Operator-only finite authority, spend, and bundled rate-card configuration. */
public final class AgentAuthorityBudgetConfiguration {
    private static final SecureRandom BOOT_EPOCHS = new SecureRandom();
    private static final Pattern IDENTITY_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]*");
    private static final Pattern SCOPE_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]*");
    private static final int MAX_ENVIRONMENT_TOKEN_LENGTH = 128;
    private static final int MAX_SCOPES = 256;
    private static final String AUTHORITY_SCOPES = "RAVENROOT_AGENT_AUTHORITY_SCOPES";
    private static final String EFFECTIVE_AUTHORITY_LIMIT =
            "authorityScopes must contain at most 256 effective root scope tokens";

    private AgentAuthorityBudgetConfiguration() { }

    public static AgentAuthorityBudgetPolicy fromEnvironment(Map<String, String> environment) {
        return fromEnvironment(environment, Clock.systemUTC());
    }

    /** Resolves immutable policy and validates its finite root deadline before resource acquisition. */
    public static AgentAuthorityBudgetPolicy fromEnvironment(Map<String, String> environment, Clock clock) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(clock, "clock");
        String runtime = identity(environment, "RAVENROOT_AGENT_RUNTIME_INSTANCE", "ravenroot-server");
        String policy = identity(environment, "RAVENROOT_AGENT_POLICY_VERSION", "server-finite-v1");
        String rateCard = identity(environment, "RAVENROOT_AGENT_RATE_CARD_VERSION",
                "builtin-conservative-v1");
        String currency = currency(environment, "RAVENROOT_AGENT_COST_CURRENCY", "USD");
        long lifetime = positive(environment, "RAVENROOT_AGENT_ROOT_LIFETIME_SECONDS", 3_600);
        AgentBudgetVector maxima = new AgentBudgetVector(
                positive(environment, "RAVENROOT_AGENT_MAX_TURNS", 1_024),
                positive(environment, "RAVENROOT_AGENT_MAX_INPUT_TOKENS", 20_000_000),
                positive(environment, "RAVENROOT_AGENT_MAX_OUTPUT_TOKENS", 2_000_000),
                positive(environment, "RAVENROOT_AGENT_MAX_ELAPSED_MILLIS", 3_600_000),
                positive(environment, "RAVENROOT_AGENT_MAX_COST_MICROS", 100_000_000),
                positive(environment, "RAVENROOT_AGENT_MAX_TOOL_CALLS", 4_096),
                positive(environment, "RAVENROOT_AGENT_MAX_DELEGATION_DEPTH", 8),
                positive(environment, "RAVENROOT_AGENT_MAX_TEAM_CUMULATIVE", 64),
                positive(environment, "RAVENROOT_AGENT_MAX_TEAM_ACTIVE", 16));
        Set<String> dataScopes = tokens(environment, "RAVENROOT_AGENT_DATA_SCOPES", Set.of());
        Set<String> authorityScopes = tokens(environment, AUTHORITY_SCOPES, Set.of("runtime:delegate"));
        try {
            var resolved = new AgentAuthorityBudgetPolicy(runtime, BOOT_EPOCHS.nextLong(Long.MAX_VALUE), policy,
                    rateCard, currency, Duration.ofSeconds(lifetime), maxima,
                    positive(environment, "RAVENROOT_AGENT_MAX_INPUT_TOKENS_PER_TURN", 128_000),
                    positive(environment, "RAVENROOT_AGENT_MAX_OUTPUT_TOKENS_PER_TURN", 32_000),
                    nonNegative(environment, "RAVENROOT_AGENT_INPUT_TOKEN_RATE_MICROS", 10),
                    nonNegative(environment, "RAVENROOT_AGENT_OUTPUT_TOKEN_RATE_MICROS", 30),
                    dataScopes, authorityScopes);
            try {
                resolved.rootDeadlineAt(clock.instant());
            } catch (IllegalArgumentException invalidDeadline) {
                throw new IllegalArgumentException(
                        "RAVENROOT_AGENT_ROOT_LIFETIME_SECONDS must form a finite deadline at startup");
            }
            return resolved;
        } catch (IllegalArgumentException invalid) {
            if (EFFECTIVE_AUTHORITY_LIMIT.equals(invalid.getMessage())) {
                throw new IllegalArgumentException(
                        AUTHORITY_SCOPES + " must contain at most 256 effective root scope tokens");
            }
            throw invalid;
        }
    }

    private static long positive(Map<String, String> environment, String name, long fallback) {
        long value = number(environment, name, fallback);
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static long nonNegative(Map<String, String> environment, String name, long fallback) {
        long value = number(environment, name, fallback);
        if (value < 0) throw new IllegalArgumentException(name + " must be non-negative");
        return value;
    }

    private static long number(Map<String, String> environment, String name, long fallback) {
        String raw = environment.get(name);
        try {
            return raw == null || raw.isBlank() ? fallback : Long.parseLong(raw.strip());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }

    private static String identity(Map<String, String> environment, String name, String fallback) {
        String value = environment.get(name);
        value = value == null || value.isBlank() ? fallback : value.strip();
        if (value.length() > MAX_ENVIRONMENT_TOKEN_LENGTH || !IDENTITY_TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be an identity token of 1..128 characters");
        }
        return value;
    }

    private static String currency(Map<String, String> environment, String name, String fallback) {
        String raw = environment.get(name);
        String value = raw == null || raw.isBlank() ? fallback : raw.strip();
        value = value.toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException(name + " must be a three-letter currency code");
        }
        return value;
    }

    private static Set<String> tokens(Map<String, String> environment, String name, Set<String> absentDefault) {
        if (!environment.containsKey(name)) return absentDefault;
        String raw = environment.get(name);
        if (raw == null || raw.isBlank()) return Set.of();
        var tokens = new LinkedHashSet<String>();
        for (String element : raw.split(",", -1)) {
            String token = element.strip();
            if (token.length() > MAX_ENVIRONMENT_TOKEN_LENGTH || !SCOPE_TOKEN.matcher(token).matches()) {
                throw new IllegalArgumentException(
                        name + " must contain comma-separated scope tokens of 1..128 characters");
            }
            tokens.add(token);
            if (tokens.size() > MAX_SCOPES) {
                throw new IllegalArgumentException(name + " must contain at most 256 unique scope tokens");
            }
        }
        return Set.copyOf(tokens);
    }
}
