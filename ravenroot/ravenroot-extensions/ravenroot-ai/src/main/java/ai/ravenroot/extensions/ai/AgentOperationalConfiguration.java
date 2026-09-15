package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.persistence.ExecutionManifestDigest;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable process-startup defaults and admission ceilings for the AI bundle. */
public record AgentOperationalConfiguration(
        int defaultMaxTurns,
        int maxTurns,
        int maxMcpServers,
        int maxSkillPayloadBytes,
        int maxSkillNameChars,
        int maxSkillDescriptionChars,
        int maxSkillInstructionsChars,
        int maxMcpToolsPerServer,
        int defaultMaxDiscoveredMcpToolsPerServer,
        int maxDiscoveredMcpToolsPerServer,
        int maxLlmProfileBytes,
        int maxMcpProfileBytes,
        int defaultLlmTimeoutMs,
        int maxLlmTimeoutMs,
        int defaultMcpTimeoutMs,
        int maxMcpTimeoutMs,
        int defaultLlmRequestBytes,
        int maxLlmRequestBytes,
        int defaultLlmResponseBytes,
        int maxLlmResponseBytes,
        int defaultMcpRequestBytes,
        int maxMcpRequestBytes,
        int defaultMcpResponseBytes,
        int maxMcpResponseBytes,
        int defaultLlmConcurrency,
        int maxLlmConcurrency,
        int defaultMcpConcurrency,
        int maxMcpConcurrency,
        int maxSystemPreambleChars,
        int maxHttpDecompressionRatio,
        int maxModelInputProvenanceEntries) {

    public static final int DEFAULT_MAX_TURNS = 8;
    public static final int DEFAULT_MAX_TURNS_CEILING = 64;
    public static final int DEFAULT_MAX_MCP_SERVERS = 8;
    public static final int DEFAULT_MAX_SKILL_PAYLOAD_BYTES = 2 * 1024 * 1024;
    public static final int DEFAULT_MAX_SKILL_NAME_CHARS = 64;
    public static final int DEFAULT_MAX_SKILL_DESCRIPTION_CHARS = 512;
    public static final int DEFAULT_MAX_SKILL_INSTRUCTIONS_CHARS = 16_384;
    public static final int DEFAULT_MAX_MCP_TOOLS_PER_SERVER = 64;
    public static final int DEFAULT_MAX_DISCOVERED_MCP_TOOLS_PER_SERVER = 1_024;
    public static final int DEFAULT_MAX_PROFILE_BYTES = 8 * 1024;
    public static final int DEFAULT_MAX_TIMEOUT_MS = 600_000;
    public static final int DEFAULT_LLM_TIMEOUT_MS = 60_000;
    public static final int DEFAULT_MCP_TIMEOUT_MS = 30_000;
    public static final int DEFAULT_MAX_LLM_BYTES = 8 * 1024 * 1024;
    public static final int DEFAULT_MAX_MCP_BYTES = 4 * 1024 * 1024;
    public static final int DEFAULT_MCP_BYTES = 1024 * 1024;
    public static final int DEFAULT_MAX_CONCURRENCY = 256;
    public static final int DEFAULT_CONCURRENCY = 4;
    public static final int DEFAULT_MAX_SYSTEM_PREAMBLE_CHARS = 8 * 1024;
    public static final int DEFAULT_MAX_HTTP_DECOMPRESSION_RATIO = 100;
    public static final int DEFAULT_MAX_MODEL_INPUT_PROVENANCE_ENTRIES = 4_096;

    public AgentOperationalConfiguration {
        positive("RAVENROOT_AI_DEFAULT_MAX_TURNS", defaultMaxTurns);
        positive("RAVENROOT_AI_MAX_TURNS", maxTurns);
        positive("RAVENROOT_AI_MAX_MCP_SERVERS", maxMcpServers);
        positive("RAVENROOT_AI_MAX_SKILL_PAYLOAD_BYTES", maxSkillPayloadBytes);
        positive("RAVENROOT_AI_MAX_SKILL_NAME_CHARS", maxSkillNameChars);
        positive("RAVENROOT_AI_MAX_SKILL_DESCRIPTION_CHARS", maxSkillDescriptionChars);
        positive("RAVENROOT_AI_MAX_SKILL_INSTRUCTIONS_CHARS", maxSkillInstructionsChars);
        positive("RAVENROOT_AI_MAX_MCP_TOOLS_PER_SERVER", maxMcpToolsPerServer);
        positive("RAVENROOT_AI_DEFAULT_MAX_DISCOVERED_MCP_TOOLS_PER_SERVER", defaultMaxDiscoveredMcpToolsPerServer);
        positive("RAVENROOT_AI_MAX_DISCOVERED_MCP_TOOLS_PER_SERVER", maxDiscoveredMcpToolsPerServer);
        positive("RAVENROOT_AI_MAX_LLM_PROFILE_BYTES", maxLlmProfileBytes);
        positive("RAVENROOT_AI_MAX_MCP_PROFILE_BYTES", maxMcpProfileBytes);
        positive("RAVENROOT_AI_DEFAULT_LLM_TIMEOUT_MS", defaultLlmTimeoutMs);
        positive("RAVENROOT_AI_MAX_LLM_TIMEOUT_MS", maxLlmTimeoutMs);
        positive("RAVENROOT_AI_DEFAULT_MCP_TIMEOUT_MS", defaultMcpTimeoutMs);
        positive("RAVENROOT_AI_MAX_MCP_TIMEOUT_MS", maxMcpTimeoutMs);
        positive("RAVENROOT_AI_DEFAULT_LLM_REQUEST_BYTES", defaultLlmRequestBytes);
        positive("RAVENROOT_AI_MAX_LLM_REQUEST_BYTES", maxLlmRequestBytes);
        positive("RAVENROOT_AI_DEFAULT_LLM_RESPONSE_BYTES", defaultLlmResponseBytes);
        positive("RAVENROOT_AI_MAX_LLM_RESPONSE_BYTES", maxLlmResponseBytes);
        positive("RAVENROOT_AI_DEFAULT_MCP_REQUEST_BYTES", defaultMcpRequestBytes);
        positive("RAVENROOT_AI_MAX_MCP_REQUEST_BYTES", maxMcpRequestBytes);
        positive("RAVENROOT_AI_DEFAULT_MCP_RESPONSE_BYTES", defaultMcpResponseBytes);
        positive("RAVENROOT_AI_MAX_MCP_RESPONSE_BYTES", maxMcpResponseBytes);
        positive("RAVENROOT_AI_DEFAULT_LLM_CONCURRENCY", defaultLlmConcurrency);
        positive("RAVENROOT_AI_MAX_LLM_CONCURRENCY", maxLlmConcurrency);
        positive("RAVENROOT_AI_DEFAULT_MCP_CONCURRENCY", defaultMcpConcurrency);
        positive("RAVENROOT_AI_MAX_MCP_CONCURRENCY", maxMcpConcurrency);
        positive("RAVENROOT_AI_MAX_SYSTEM_PREAMBLE_CHARS", maxSystemPreambleChars);
        positive("RAVENROOT_AI_MAX_HTTP_DECOMPRESSION_RATIO", maxHttpDecompressionRatio);
        positive("RAVENROOT_AI_MAX_MODEL_INPUT_PROVENANCE_ENTRIES", maxModelInputProvenanceEntries);
        supportedPayload("RAVENROOT_AI_MAX_SKILL_NAME_CHARS", maxSkillNameChars);
        supportedPayload("RAVENROOT_AI_MAX_SKILL_DESCRIPTION_CHARS", maxSkillDescriptionChars);
        supportedPayload("RAVENROOT_AI_MAX_SKILL_INSTRUCTIONS_CHARS", maxSkillInstructionsChars);
        if (maxDiscoveredMcpToolsPerServer > PayloadLimits.HARD_MAX_COLLECTION_SIZE) {
            throw new IllegalArgumentException("RAVENROOT_AI_MAX_DISCOVERED_MCP_TOOLS_PER_SERVER"
                    + " exceeds the supported collection ceiling of "
                    + PayloadLimits.HARD_MAX_COLLECTION_SIZE);
        }
        supportedPayload("RAVENROOT_AI_MAX_LLM_PROFILE_BYTES", maxLlmProfileBytes);
        supportedPayload("RAVENROOT_AI_MAX_MCP_PROFILE_BYTES", maxMcpProfileBytes);
        supportedPayload("RAVENROOT_AI_MAX_LLM_REQUEST_BYTES", maxLlmRequestBytes);
        supportedPayload("RAVENROOT_AI_MAX_LLM_RESPONSE_BYTES", maxLlmResponseBytes);
        supportedPayload("RAVENROOT_AI_MAX_MCP_REQUEST_BYTES", maxMcpRequestBytes);
        supportedPayload("RAVENROOT_AI_MAX_MCP_RESPONSE_BYTES", maxMcpResponseBytes);
        supportedPayload("RAVENROOT_AI_MAX_SYSTEM_PREAMBLE_CHARS", maxSystemPreambleChars);
        if (defaultMaxTurns > maxTurns) {
            throw new IllegalArgumentException(
                    "RAVENROOT_AI_DEFAULT_MAX_TURNS must not exceed RAVENROOT_AI_MAX_TURNS");
        }
        if (maxMcpToolsPerServer > maxDiscoveredMcpToolsPerServer) {
            throw new IllegalArgumentException("RAVENROOT_AI_MAX_MCP_TOOLS_PER_SERVER must not exceed "
                    + "RAVENROOT_AI_MAX_DISCOVERED_MCP_TOOLS_PER_SERVER");
        }
        notAbove("RAVENROOT_AI_DEFAULT_MAX_DISCOVERED_MCP_TOOLS_PER_SERVER",
                defaultMaxDiscoveredMcpToolsPerServer,
                "RAVENROOT_AI_MAX_DISCOVERED_MCP_TOOLS_PER_SERVER", maxDiscoveredMcpToolsPerServer);
        notAbove("RAVENROOT_AI_DEFAULT_LLM_TIMEOUT_MS", defaultLlmTimeoutMs,
                "RAVENROOT_AI_MAX_LLM_TIMEOUT_MS", maxLlmTimeoutMs);
        notAbove("RAVENROOT_AI_DEFAULT_MCP_TIMEOUT_MS", defaultMcpTimeoutMs,
                "RAVENROOT_AI_MAX_MCP_TIMEOUT_MS", maxMcpTimeoutMs);
        notAbove("RAVENROOT_AI_DEFAULT_LLM_REQUEST_BYTES", defaultLlmRequestBytes,
                "RAVENROOT_AI_MAX_LLM_REQUEST_BYTES", maxLlmRequestBytes);
        notAbove("RAVENROOT_AI_DEFAULT_LLM_RESPONSE_BYTES", defaultLlmResponseBytes,
                "RAVENROOT_AI_MAX_LLM_RESPONSE_BYTES", maxLlmResponseBytes);
        notAbove("RAVENROOT_AI_DEFAULT_MCP_REQUEST_BYTES", defaultMcpRequestBytes,
                "RAVENROOT_AI_MAX_MCP_REQUEST_BYTES", maxMcpRequestBytes);
        notAbove("RAVENROOT_AI_DEFAULT_MCP_RESPONSE_BYTES", defaultMcpResponseBytes,
                "RAVENROOT_AI_MAX_MCP_RESPONSE_BYTES", maxMcpResponseBytes);
        notAbove("RAVENROOT_AI_DEFAULT_LLM_CONCURRENCY", defaultLlmConcurrency,
                "RAVENROOT_AI_MAX_LLM_CONCURRENCY", maxLlmConcurrency);
        notAbove("RAVENROOT_AI_DEFAULT_MCP_CONCURRENCY", defaultMcpConcurrency,
                "RAVENROOT_AI_MAX_MCP_CONCURRENCY", maxMcpConcurrency);
    }

    public static AgentOperationalConfiguration defaults() {
        return new AgentOperationalConfiguration(DEFAULT_MAX_TURNS, DEFAULT_MAX_TURNS_CEILING,
                DEFAULT_MAX_MCP_SERVERS, DEFAULT_MAX_SKILL_PAYLOAD_BYTES,
                DEFAULT_MAX_SKILL_NAME_CHARS, DEFAULT_MAX_SKILL_DESCRIPTION_CHARS,
                DEFAULT_MAX_SKILL_INSTRUCTIONS_CHARS, DEFAULT_MAX_MCP_TOOLS_PER_SERVER,
                DEFAULT_MAX_DISCOVERED_MCP_TOOLS_PER_SERVER,
                DEFAULT_MAX_DISCOVERED_MCP_TOOLS_PER_SERVER, DEFAULT_MAX_PROFILE_BYTES,
                DEFAULT_MAX_PROFILE_BYTES, DEFAULT_LLM_TIMEOUT_MS, DEFAULT_MAX_TIMEOUT_MS,
                DEFAULT_MCP_TIMEOUT_MS, DEFAULT_MAX_TIMEOUT_MS,
                DEFAULT_MAX_LLM_BYTES, DEFAULT_MAX_LLM_BYTES,
                DEFAULT_MAX_LLM_BYTES, DEFAULT_MAX_LLM_BYTES,
                DEFAULT_MCP_BYTES, DEFAULT_MAX_MCP_BYTES,
                DEFAULT_MCP_BYTES, DEFAULT_MAX_MCP_BYTES,
                DEFAULT_CONCURRENCY, DEFAULT_MAX_CONCURRENCY,
                DEFAULT_CONCURRENCY, DEFAULT_MAX_CONCURRENCY,
                DEFAULT_MAX_SYSTEM_PREAMBLE_CHARS, DEFAULT_MAX_HTTP_DECOMPRESSION_RATIO,
                DEFAULT_MAX_MODEL_INPUT_PROVENANCE_ENTRIES);
    }

    public static AgentOperationalConfiguration fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        AgentOperationalConfiguration d = defaults();
        return new AgentOperationalConfiguration(
                value(environment, "RAVENROOT_AI_DEFAULT_MAX_TURNS", d.defaultMaxTurns),
                value(environment, "RAVENROOT_AI_MAX_TURNS", d.maxTurns),
                value(environment, "RAVENROOT_AI_MAX_MCP_SERVERS", d.maxMcpServers),
                value(environment, "RAVENROOT_AI_MAX_SKILL_PAYLOAD_BYTES", d.maxSkillPayloadBytes),
                value(environment, "RAVENROOT_AI_MAX_SKILL_NAME_CHARS", d.maxSkillNameChars),
                value(environment, "RAVENROOT_AI_MAX_SKILL_DESCRIPTION_CHARS", d.maxSkillDescriptionChars),
                value(environment, "RAVENROOT_AI_MAX_SKILL_INSTRUCTIONS_CHARS", d.maxSkillInstructionsChars),
                value(environment, "RAVENROOT_AI_MAX_MCP_TOOLS_PER_SERVER", d.maxMcpToolsPerServer),
                value(environment, "RAVENROOT_AI_DEFAULT_MAX_DISCOVERED_MCP_TOOLS_PER_SERVER", d.defaultMaxDiscoveredMcpToolsPerServer),
                value(environment, "RAVENROOT_AI_MAX_DISCOVERED_MCP_TOOLS_PER_SERVER", d.maxDiscoveredMcpToolsPerServer),
                value(environment, "RAVENROOT_AI_MAX_LLM_PROFILE_BYTES", d.maxLlmProfileBytes),
                value(environment, "RAVENROOT_AI_MAX_MCP_PROFILE_BYTES", d.maxMcpProfileBytes),
                value(environment, "RAVENROOT_AI_DEFAULT_LLM_TIMEOUT_MS", d.defaultLlmTimeoutMs),
                value(environment, "RAVENROOT_AI_MAX_LLM_TIMEOUT_MS", d.maxLlmTimeoutMs),
                value(environment, "RAVENROOT_AI_DEFAULT_MCP_TIMEOUT_MS", d.defaultMcpTimeoutMs),
                value(environment, "RAVENROOT_AI_MAX_MCP_TIMEOUT_MS", d.maxMcpTimeoutMs),
                value(environment, "RAVENROOT_AI_DEFAULT_LLM_REQUEST_BYTES", d.defaultLlmRequestBytes),
                value(environment, "RAVENROOT_AI_MAX_LLM_REQUEST_BYTES", d.maxLlmRequestBytes),
                value(environment, "RAVENROOT_AI_DEFAULT_LLM_RESPONSE_BYTES", d.defaultLlmResponseBytes),
                value(environment, "RAVENROOT_AI_MAX_LLM_RESPONSE_BYTES", d.maxLlmResponseBytes),
                value(environment, "RAVENROOT_AI_DEFAULT_MCP_REQUEST_BYTES", d.defaultMcpRequestBytes),
                value(environment, "RAVENROOT_AI_MAX_MCP_REQUEST_BYTES", d.maxMcpRequestBytes),
                value(environment, "RAVENROOT_AI_DEFAULT_MCP_RESPONSE_BYTES", d.defaultMcpResponseBytes),
                value(environment, "RAVENROOT_AI_MAX_MCP_RESPONSE_BYTES", d.maxMcpResponseBytes),
                value(environment, "RAVENROOT_AI_DEFAULT_LLM_CONCURRENCY", d.defaultLlmConcurrency),
                value(environment, "RAVENROOT_AI_MAX_LLM_CONCURRENCY", d.maxLlmConcurrency),
                value(environment, "RAVENROOT_AI_DEFAULT_MCP_CONCURRENCY", d.defaultMcpConcurrency),
                value(environment, "RAVENROOT_AI_MAX_MCP_CONCURRENCY", d.maxMcpConcurrency),
                value(environment, "RAVENROOT_AI_MAX_SYSTEM_PREAMBLE_CHARS", d.maxSystemPreambleChars),
                value(environment, "RAVENROOT_AI_MAX_HTTP_DECOMPRESSION_RATIO", d.maxHttpDecompressionRatio),
                value(environment, "RAVENROOT_AI_MAX_MODEL_INPUT_PROVENANCE_ENTRIES",
                        d.maxModelInputProvenanceEntries));
    }

    /**
     * Returns the non-secret, canonical generation fingerprint used by distributed execution.
     *
     * @return lowercase SHA-256 digest of this complete operational policy
     */
    public String compatibilityDigest() {
        return ExecutionManifestDigest.component("ravenroot.ai.operational-policy.v1", List.of(
                String.valueOf(defaultMaxTurns), String.valueOf(maxTurns),
                String.valueOf(maxMcpServers), String.valueOf(maxSkillPayloadBytes),
                String.valueOf(maxSkillNameChars), String.valueOf(maxSkillDescriptionChars),
                String.valueOf(maxSkillInstructionsChars), String.valueOf(maxMcpToolsPerServer),
                String.valueOf(defaultMaxDiscoveredMcpToolsPerServer),
                String.valueOf(maxDiscoveredMcpToolsPerServer), String.valueOf(maxLlmProfileBytes),
                String.valueOf(maxMcpProfileBytes), String.valueOf(defaultLlmTimeoutMs),
                String.valueOf(maxLlmTimeoutMs), String.valueOf(defaultMcpTimeoutMs),
                String.valueOf(maxMcpTimeoutMs), String.valueOf(defaultLlmRequestBytes),
                String.valueOf(maxLlmRequestBytes), String.valueOf(defaultLlmResponseBytes),
                String.valueOf(maxLlmResponseBytes), String.valueOf(defaultMcpRequestBytes),
                String.valueOf(maxMcpRequestBytes), String.valueOf(defaultMcpResponseBytes),
                String.valueOf(maxMcpResponseBytes), String.valueOf(defaultLlmConcurrency),
                String.valueOf(maxLlmConcurrency), String.valueOf(defaultMcpConcurrency),
                String.valueOf(maxMcpConcurrency), String.valueOf(maxSystemPreambleChars),
                String.valueOf(maxHttpDecompressionRatio),
                String.valueOf(maxModelInputProvenanceEntries)));
    }

    private static int value(Map<String, String> environment, String name, int fallback) {
        String raw = environment.get(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(raw.strip());
            positive(name, parsed);
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " must be a positive 32-bit integer", invalid);
        }
    }

    private static void positive(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be a positive 32-bit integer");
        }
    }

    private static void supportedPayload(String name, int value) {
        if (value > PayloadLimits.HARD_MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException(name + " exceeds the supported 64 MiB payload ceiling");
        }
    }

    private static void notAbove(String defaultName, int defaultValue, String maximumName, int maximumValue) {
        if (defaultValue > maximumValue) {
            throw new IllegalArgumentException(defaultName + " must not exceed " + maximumName);
        }
    }
}
