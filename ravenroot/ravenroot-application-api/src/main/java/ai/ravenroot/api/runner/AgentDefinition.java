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
 * @param budgets finite definition-owned model and tool ceilings, further attenuated by deployment
 * @param skillInstructions immutable approved skill bodies; omitted bodies require an operator runtime binding
 */
public record AgentDefinition(Reference reference, String instructions, String runtimeProfile,
                              String modelProfile, Map<String, AgentCommand> commands, Set<String> skills,
                              Set<String> runnerRequirements, RunnerPolicy policy,
                              Duration workspaceRetention, String outputSchema, Budgets budgets,
                              Map<String, String> skillInstructions) {
    /**
     * Finite model budgets shared by conversational and workspace Agents.
     * @param modelTurns maximum model requests
     * @param toolCalls maximum tool invocations
     * @param modelTokens maximum aggregate reported input and output tokens
     * @param tokensPerTurn maximum requested output tokens in one turn
     */
    public record Budgets(int modelTurns, int toolCalls, long modelTokens, int tokensPerTurn) {
        /** Migration defaults for definitions persisted before budgets were explicit. */
        public static final Budgets LEGACY = new Budgets(12, 24, 20_000, 2_048);
        /** Requires finite positive ceilings; zero never means unlimited. */
        public Budgets {
            if (modelTurns < 1 || toolCalls < 1 || modelTokens < 1 || tokensPerTurn < 1)
                throw new IllegalArgumentException("Agent budgets must be positive");
        }
    }
    /**
     * Compatibility constructor applying the finite migration budgets to older definitions.
     * @param reference immutable catalog key
     * @param instructions approved instructions
     * @param runtimeProfile approved runtime reference
     * @param modelProfile approved model reference
     * @param commands approved command policies
     * @param skills approved skill references
     * @param runnerRequirements placement labels
     * @param policy definition authority ceiling
     * @param workspaceRetention legacy workspace retention
     * @param outputSchema output contract identifier
     */
    public AgentDefinition(Reference reference, String instructions, String runtimeProfile, String modelProfile,
                           Map<String, AgentCommand> commands, Set<String> skills, Set<String> runnerRequirements,
                           RunnerPolicy policy, Duration workspaceRetention, String outputSchema) {
        this(reference, instructions, runtimeProfile, modelProfile, commands, skills, runnerRequirements, policy,
                workspaceRetention, outputSchema, Budgets.LEGACY, Map.of());
    }
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
        /**
         * Names the logical Agent session independently from any Workspace or runtime container.
         * @param processInstanceId owning process, shared by its later traversals
         * @return tenant/process/exact-definition namespace
         */
        public java.util.UUID sessionId(java.util.UUID processInstanceId) {
            Objects.requireNonNull(processInstanceId, "processInstanceId");
            return java.util.UUID.nameUUIDFromBytes(("ravenroot.agent-session.v1:" + tenantId.length() + ":"
                    + tenantId + ":" + processInstanceId + ":" + name + ":" + version).getBytes(StandardCharsets.UTF_8));
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
        Objects.requireNonNull(budgets, "budgets");
        skillInstructions = Map.copyOf(Objects.requireNonNull(skillInstructions, "skillInstructions"));
        if (!skills.containsAll(skillInstructions.keySet()) || skillInstructions.values().stream().anyMatch(
                body -> body.isBlank() || body.getBytes(StandardCharsets.UTF_8).length > 65_536))
            throw new IllegalArgumentException("skill body must belong to an approved bounded skill");
        Objects.requireNonNull(workspaceRetention, "workspaceRetention");
        if (workspaceRetention.isNegative()) {
            throw new IllegalArgumentException("invalid workspace retention");
        }
        try { workspaceRetention.toMillis(); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("retention exceeds the store time representation", overflow); }
    }

    /** Instructions are deliberately excluded from diagnostic rendering. */
    @Override public String toString() {
        return "AgentDefinition[reference=" + reference + "]";
    }
}
