package ai.ravenroot.server.agent;

import ai.ravenroot.api.persistence.AgentBudgetVector;
import ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetPolicy;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Operator-only finite authority, spend, and bundled rate-card configuration. */
public final class AgentAuthorityBudgetConfiguration {
    private static final SecureRandom BOOT_EPOCHS = new SecureRandom();

    private AgentAuthorityBudgetConfiguration() { }

    public static AgentAuthorityBudgetPolicy fromEnvironment(Map<String, String> environment) {
        String runtime = text(environment, "RAVENROOT_AGENT_RUNTIME_INSTANCE", "ravenroot-server");
        String policy = text(environment, "RAVENROOT_AGENT_POLICY_VERSION", "server-finite-v1");
        String rateCard = text(environment, "RAVENROOT_AGENT_RATE_CARD_VERSION",
                "builtin-conservative-v1");
        String currency = text(environment, "RAVENROOT_AGENT_COST_CURRENCY", "USD").toUpperCase();
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
        return new AgentAuthorityBudgetPolicy(runtime, BOOT_EPOCHS.nextLong(Long.MAX_VALUE), policy,
                rateCard, currency, Duration.ofSeconds(lifetime), maxima,
                positive(environment, "RAVENROOT_AGENT_MAX_INPUT_TOKENS_PER_TURN", 128_000),
                positive(environment, "RAVENROOT_AGENT_MAX_OUTPUT_TOKENS_PER_TURN", 32_000),
                nonNegative(environment, "RAVENROOT_AGENT_INPUT_TOKEN_RATE_MICROS", 10),
                nonNegative(environment, "RAVENROOT_AGENT_OUTPUT_TOKEN_RATE_MICROS", 30),
                tokens(environment.getOrDefault("RAVENROOT_AGENT_DATA_SCOPES", "")),
                tokens(environment.getOrDefault("RAVENROOT_AGENT_AUTHORITY_SCOPES", "runtime:delegate")));
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
            throw new IllegalArgumentException(name + " must be an integer", invalid);
        }
    }

    private static String text(Map<String, String> environment, String name, String fallback) {
        String value = environment.get(name);
        value = value == null || value.isBlank() ? fallback : value.strip();
        if (value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(name + " must contain 1..128 characters");
        }
        return value;
    }

    private static Set<String> tokens(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        return Arrays.stream(raw.split(",", -1)).map(String::strip)
                .peek(token -> {
                    if (token.isEmpty() || token.length() > 128) {
                        throw new IllegalArgumentException("agent scope tokens must contain 1..128 characters");
                    }
                }).collect(Collectors.toUnmodifiableSet());
    }
}
