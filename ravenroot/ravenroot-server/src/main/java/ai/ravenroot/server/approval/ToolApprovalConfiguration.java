package ai.ravenroot.server.approval;

import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.core.approval.ToolApprovalSettings;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Operator-only environment reader for durable tool approval policy. */
public final class ToolApprovalConfiguration {
    private ToolApprovalConfiguration() {
    }

    /** Reads bounded expiry, approver requirements, separation, and policy identity. */
    public static ToolApprovalSettings fromEnvironment(Map<String, String> environment) {
        String rawTtl = environment.getOrDefault("RAVENROOT_TOOL_APPROVAL_TTL_SECONDS", "300").strip();
        long seconds;
        try {
            seconds = Long.parseLong(rawTtl.isEmpty() ? "300" : rawTtl);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("RAVENROOT_TOOL_APPROVAL_TTL_SECONDS must be an integer");
        }
        if (seconds < 1 || seconds > 86_400) {
            throw new IllegalArgumentException(
                    "RAVENROOT_TOOL_APPROVAL_TTL_SECONDS must be between 1 and 86400");
        }
        boolean requesterMayApprove = strictBoolean(environment,
                "RAVENROOT_TOOL_APPROVAL_REQUESTER_MAY_APPROVE", false);
        Set<String> roles = tokens(environment.getOrDefault(
                "RAVENROOT_TOOL_APPROVAL_REQUIRED_ROLES", "APPROVER"));
        Set<String> scopes = tokens(environment.getOrDefault(
                "RAVENROOT_TOOL_APPROVAL_REQUIRED_SCOPES", ""));
        String version = environment.getOrDefault("RAVENROOT_TOOL_POLICY_VERSION", "environment-v1").strip();
        return new ToolApprovalSettings(version, Duration.ofSeconds(seconds),
                new HandlerAuthorization(roles, scopes), requesterMayApprove);
    }

    private static boolean strictBoolean(Map<String, String> environment, String name,
                                         boolean fallback) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) return fallback;
        if ("true".equalsIgnoreCase(value.strip())) return true;
        if ("false".equalsIgnoreCase(value.strip())) return false;
        throw new IllegalArgumentException(name + " must be true or false");
    }

    private static Set<String> tokens(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        return java.util.Arrays.stream(raw.split(",", -1))
                .map(String::strip)
                .collect(Collectors.toUnmodifiableSet());
    }
}
