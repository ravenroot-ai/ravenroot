package ai.ravenroot.api.runner;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One immutable, tenant-scoped version resolved by an operator-governed catalog. This value carries
 * no approval claim: only the authenticated control plane can decide which versions are usable.
 * Graphs select the reference; they do not construct this value or supply host authority.
 * @param reference exact tenant, definition name and version
 * @param instructions bounded instructions
 * @param runtimeProfile approved runtime profile identifier
 * @param modelProfile approved model profile identifier
 * @param commands exact command policies
 * @param skills approved skill identifiers
 * @param runnerRequirements required runner labels
 * @param policy definition authority ceiling
 * @param workspaceRetention retention after the process becomes terminal
 * @param outputSchema symbolic output contract identifier
 */
public record AgentDefinition(Reference reference, String instructions, String runtimeProfile,
                              String modelProfile, Map<String, AgentCommand> commands, Set<String> skills,
                              Set<String> runnerRequirements, RunnerPolicy policy,
                              Duration workspaceRetention, String outputSchema) {
    /**
     * Immutable catalog key. A new definition body requires a new version.
     * @param tenantId authenticated tenant identifier
     * @param name definition identifier
     * @param version positive immutable version number
     */
    public record Reference(String tenantId, String name, long version) {
        /** Validates the complete tenant-scoped definition reference. */
        public Reference {
            if (tenantId == null || tenantId.isBlank() || tenantId.length() > 256 || version < 1) {
                throw new IllegalArgumentException("invalid agent definition reference");
            }
            name = RunnerPolicy.identifier(name);
        }
    }

    /** Validates a complete bounded definition and freezes its command policies. */
    public AgentDefinition {
        Objects.requireNonNull(reference, "reference");
        if (instructions == null || instructions.isBlank()
                || instructions.getBytes(StandardCharsets.UTF_8).length > 65_536) {
            throw new IllegalArgumentException("invalid agent instructions");
        }
        runtimeProfile = RunnerPolicy.identifier(runtimeProfile);
        modelProfile = RunnerPolicy.identifier(modelProfile);
        outputSchema = RunnerPolicy.identifier(outputSchema);
        commands = Map.copyOf(Objects.requireNonNull(commands, "commands"));
        if (commands.isEmpty() || commands.size() > 64
                || commands.entrySet().stream().anyMatch(entry -> !entry.getKey().equals(entry.getValue().name()))) {
            throw new IllegalArgumentException("invalid agent command catalog");
        }
        skills = RunnerPolicy.identifiers(skills);
        runnerRequirements = RunnerPolicy.identifiers(runnerRequirements);
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(workspaceRetention, "workspaceRetention");
        if (workspaceRetention.isNegative() || workspaceRetention.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("invalid workspace retention");
        }
    }

    /** Instructions are deliberately excluded from diagnostic rendering. */
    @Override public String toString() {
        return "AgentDefinition[reference=" + reference + "]";
    }
}
