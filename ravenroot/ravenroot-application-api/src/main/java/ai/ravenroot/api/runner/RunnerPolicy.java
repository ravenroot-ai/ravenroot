package ai.ravenroot.api.runner;

import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable authority ceilings for a workspace runner. Values identify operator-owned grants, never
 * credentials, host paths, shell commands or wildcard grants. A runner must enforce every resulting
 * restriction before executing; capability advertisement alone is not an enforcement mechanism.
 *
 * @param capabilities permitted operations
 * @param tools exact approved tool identifiers
 * @param egress exact approved network-policy identifiers
 * @param secrets exact approved secret-binding identifiers
 * @param mounts exact approved additional mount identifiers
 * @param limits inclusive resource ceilings
 */
public record RunnerPolicy(Set<Capability> capabilities, Set<String> tools, Set<String> egress,
                           Set<String> secrets, Set<String> mounts, Limits limits) {
    /** Operations whose absence must be enforced by the execution driver. */
    public enum Capability {
        /** Read the owning process workspace. */
        WORKSPACE_READ,
        /** Modify the owning process workspace under its byte quota. */
        WORKSPACE_WRITE,
        /** Start bounded child processes inside the approved execution boundary. */
        PROCESS_EXECUTE,
        /** Invoke only effective approved tool identifiers. */
        TOOL_CALL,
        /** Use only effective approved network policies. */
        NETWORK_EGRESS,
        /** Resolve only effective approved secret bindings. */
        SECRET_ACCESS,
        /** Attach only effective operator-managed mount bindings. */
        ADDITIONAL_MOUNTS
    }

    /**
     * Inclusive limits; zero is never an alias for unlimited.
     * @param wallTime maximum job duration
     * @param memoryBytes maximum resident memory
     * @param processes maximum process count including descendants
     * @param workspaceBytes maximum process workspace size
     * @param artifactBytes maximum total retained artifact bytes per job
     * @param logBytes maximum retained log bytes per job
     * @param payloadBytes maximum input or terminal result bytes
     */
    public record Limits(Duration wallTime, long memoryBytes, int processes, long workspaceBytes,
                         long artifactBytes, long logBytes, int payloadBytes) {
        /** Rejects nonpositive or protocol-unrepresentable limits. */
        public Limits {
            Objects.requireNonNull(wallTime, "wallTime");
            if (wallTime.isZero() || wallTime.isNegative() || wallTime.compareTo(Duration.ofDays(7)) > 0
                    || memoryBytes < 1 || processes < 1 || workspaceBytes < 1 || artifactBytes < 1
                    || logBytes < 1 || payloadBytes < 1 || payloadBytes > 1_048_576) {
                throw new IllegalArgumentException("invalid runner resource limits");
            }
        }

        private Limits intersect(Limits other) {
            return new Limits(wallTime.compareTo(other.wallTime) <= 0 ? wallTime : other.wallTime,
                    Math.min(memoryBytes, other.memoryBytes), Math.min(processes, other.processes),
                    Math.min(workspaceBytes, other.workspaceBytes), Math.min(artifactBytes, other.artifactBytes),
                    Math.min(logBytes, other.logBytes), Math.min(payloadBytes, other.payloadBytes));
        }
    }

    /** Freezes and validates all allowlists. */
    public RunnerPolicy {
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        tools = identifiers(tools);
        egress = identifiers(egress);
        secrets = identifiers(secrets);
        mounts = identifiers(mounts);
        Objects.requireNonNull(limits, "limits");
        if (!capabilities.contains(Capability.TOOL_CALL)) tools = Set.of();
        if (!capabilities.contains(Capability.NETWORK_EGRESS)) egress = Set.of();
        if (!capabilities.contains(Capability.SECRET_ACCESS)) secrets = Set.of();
        if (!capabilities.contains(Capability.ADDITIONAL_MOUNTS)) mounts = Set.of();
    }

    /**
     * Attenuates deployment, definition, command and runner authority; none can add a grant.
     * Read-only commands also remove processes, tools, secrets, egress and additional mounts. An
     * arbitrary shell or tool cannot be made read-only merely by naming its command "read".
     * @param deployment deployment ceiling
     * @param definition approved definition ceiling
     * @param command approved command policy
     * @param runner runner's enforceable capability ceiling
     * @return immutable effective authority to pin on the accepted job
     */
    public static RunnerPolicy effective(RunnerPolicy deployment, RunnerPolicy definition,
                                         AgentCommand command, RunnerPolicy runner) {
        Objects.requireNonNull(command, "command");
        RunnerPolicy result = deployment.intersect(definition).intersect(command.policy()).intersect(runner);
        if (command.readOnly()) {
            var read = result.capabilities.contains(Capability.WORKSPACE_READ)
                    ? Set.of(Capability.WORKSPACE_READ) : Set.<Capability>of();
            result = new RunnerPolicy(read, Set.of(), Set.of(), Set.of(), Set.of(), result.limits);
        }
        if (!result.capabilities.contains(Capability.WORKSPACE_READ)) {
            throw new IllegalArgumentException("runner job requires workspace read authority");
        }
        return result;
    }

    private RunnerPolicy intersect(RunnerPolicy other) {
        Objects.requireNonNull(other, "policy");
        return new RunnerPolicy(intersection(capabilities, other.capabilities), intersection(tools, other.tools),
                intersection(egress, other.egress), intersection(secrets, other.secrets),
                intersection(mounts, other.mounts), limits.intersect(other.limits));
    }

    private static <T> Set<T> intersection(Set<T> left, Set<T> right) {
        var result = new HashSet<>(left);
        result.retainAll(right);
        return Set.copyOf(result);
    }

    static String identifier(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("runner identifier must be a bounded symbolic name");
        }
        return value;
    }

    static Set<String> identifiers(Set<String> values) {
        Objects.requireNonNull(values, "identifiers");
        if (values.size() > 128) throw new IllegalArgumentException("too many runner identifiers");
        values.forEach(RunnerPolicy::identifier);
        return Set.copyOf(values);
    }
}
