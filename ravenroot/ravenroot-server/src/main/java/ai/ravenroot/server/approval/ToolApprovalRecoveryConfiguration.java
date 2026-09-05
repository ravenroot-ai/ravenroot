package ai.ravenroot.server.approval;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Operator-only bounded tenant inventory and cadence for approval recovery. */
public record ToolApprovalRecoveryConfiguration(List<String> tenantIds, Duration interval,
                                                Duration leaseTtl, int batchLimit) {
    public ToolApprovalRecoveryConfiguration {
        tenantIds = List.copyOf(tenantIds);
        if (tenantIds.isEmpty()) throw new IllegalArgumentException("approval recovery tenants cannot be empty");
        if (interval.isNegative() || interval.isZero() || leaseTtl.isNegative() || leaseTtl.isZero()
                || batchLimit < 1 || batchLimit > 1_000) {
            throw new IllegalArgumentException("approval recovery bounds are invalid");
        }
    }

    public static ToolApprovalRecoveryConfiguration fromEnvironment(Map<String, String> environment) {
        String raw = environment.getOrDefault("RAVENROOT_TOOL_APPROVAL_RECOVERY_TENANTS", "local");
        List<String> tenants = Arrays.stream(raw.split(",", -1)).map(String::strip)
                .filter(value -> !value.isEmpty()).distinct().toList();
        if (tenants.stream().anyMatch(value -> value.length() > 200
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*"))) {
            throw new IllegalArgumentException("RAVENROOT_TOOL_APPROVAL_RECOVERY_TENANTS is invalid");
        }
        return new ToolApprovalRecoveryConfiguration(tenants, Duration.ofSeconds(1),
                Duration.ofSeconds(30), 32);
    }
}
