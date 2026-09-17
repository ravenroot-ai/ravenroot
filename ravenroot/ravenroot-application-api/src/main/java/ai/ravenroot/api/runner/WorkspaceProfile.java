package ai.ravenroot.api.runner;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, operator-approved workspace placement, lifecycle and capacity policy.
 * @param reference exact tenant-owned profile version
 * @param workspaceScope filesystem ownership lifetime
 * @param runtimeLifecycle container reuse independently selected from filesystem lifetime
 * @param runnerPool approved placement pool name
 * @param runtimeProfile immutable installed runtime binding name
 * @param policy maximum filesystem, process, network and evidence authority
 * @param capacity resource concurrency, queue, storage and retention-count ceilings
 * @param retention minimum retained evidence lifetime after process termination
 * @param completionPolicy explicit response to reaching END while a resource remains open
 * @param allowedAgents exact eligible Agent definition names
 * @param fleetLimits atomic accounting ceilings for all placement scopes
 * @param cpuMillicores maximum runtime CPU in thousandths of one core
 */
public record WorkspaceProfile(AgentDefinition.Reference reference, Scope workspaceScope,
                               RuntimeLifecycle runtimeLifecycle, String runnerPool, String runtimeProfile,
                               RunnerPolicy policy, Capacity capacity, Duration retention,
                               CompletionPolicy completionPolicy, Set<String> allowedAgents,
                               RunnerFleetLimits fleetLimits, int cpuMillicores) {
    /**
     * Selects the compatibility CPU default of one core; the canonical constructor permits other values.
     * @param reference exact approved profile version
     * @param workspaceScope filesystem lifetime
     * @param runtimeLifecycle runtime reuse mode
     * @param runnerPool approved worker pool
     * @param runtimeProfile installed runtime binding
     * @param policy authority ceiling
     * @param capacity Workspace admission ceilings
     * @param retention minimum evidence retention
     * @param completionPolicy behavior for an open resource at END
     * @param allowedAgents eligible definition names
     * @param fleetLimits independent fleet accounting ceilings
     */
    public WorkspaceProfile(AgentDefinition.Reference reference, Scope workspaceScope, RuntimeLifecycle runtimeLifecycle,
                            String runnerPool, String runtimeProfile, RunnerPolicy policy, Capacity capacity,
                            Duration retention, CompletionPolicy completionPolicy, Set<String> allowedAgents, RunnerFleetLimits fleetLimits) {
        this(reference, workspaceScope, runtimeLifecycle, runnerPool, runtimeProfile, policy, capacity, retention,
                completionPolicy, allowedAgents, fleetLimits, 1000);
    }
    /**
     * Derives per-scope fleet defaults from the supplied Workspace capacities.
     * @param reference exact approved profile version
     * @param workspaceScope filesystem lifetime
     * @param runtimeLifecycle runtime reuse mode
     * @param runnerPool approved worker pool
     * @param runtimeProfile installed runtime binding
     * @param policy authority ceiling
     * @param capacity source of Workspace and derived fleet ceilings
     * @param retention minimum evidence retention
     * @param completionPolicy behavior for an open resource at END
     * @param allowedAgents eligible definition names
     */
    public WorkspaceProfile(AgentDefinition.Reference reference, Scope workspaceScope, RuntimeLifecycle runtimeLifecycle,
                            String runnerPool, String runtimeProfile, RunnerPolicy policy, Capacity capacity,
                            Duration retention, CompletionPolicy completionPolicy, Set<String> allowedAgents) {
        this(reference, workspaceScope, runtimeLifecycle, runnerPool, runtimeProfile, policy, capacity, retention,
                completionPolicy, allowedAgents, RunnerFleetLimits.from(capacity));
    }
    /** Filesystem ownership lifetime, independent of container lifetime. */
    public enum Scope {
        /** New isolated filesystem for each Agent invocation. */ EPHEMERAL,
        /** One filesystem per process and graph Workspace node. */ PROCESS_INSTANCE,
        /** Tenant-approved durable name with fenced sequential ownership generations. */ NAMED
    }
    /** Runtime reuse is explicit and never silently substituted by a driver. */
    public enum RuntimeLifecycle {
        /** New container for every invocation, restoring retained filesystem state. */ PER_INVOCATION,
        /** One retained runtime until explicit close, abort or cancellation. */ PER_WORKSPACE
    }
    /** Policy applied when a process would otherwise finish with an open workspace. */
    public enum CompletionPolicy {
        /** Reject completion until all materialized resources have explicitly closed. */ REQUIRE_CLOSED,
        /** Audit and request an automatic abort instead of leaking open resources. */ ABORT
    }
    /** Capacity behavior does not grant permission to migrate an existing placement. */
    public enum Admission {
        /** Retain accepted demand within the configured queue bound. */ QUEUE,
        /** Refuse admission when immediate configured capacity is unavailable. */ REJECT,
        /** Queue demand for an operator-managed autoscaler without creating hosts. */ AUTOSCALE
    }
    /**
     * Independent ceilings resolved by the operator, not graph-provided grants.
     * @param mutatingUsers concurrent mutators supported by the selected driver
     * @param readOnlyUsers concurrent structurally read-only invocations
     * @param materializedWorkspaces retained physical workspace reservations
     * @param aggregateStorageBytes total reserved storage across those resources
     * @param queuedJobs pending accepted invocations
     * @param retainedJobs retained job/evidence records per process resource history
     * @param admission capacity behavior selected by the operator
     */
    public record Capacity(int mutatingUsers, int readOnlyUsers, int materializedWorkspaces,
                           long aggregateStorageBytes, int queuedJobs, int retainedJobs,
                           Admission admission) {
        /** Requires positive finite ceilings and an explicit capacity response policy. */
        public Capacity {
            Objects.requireNonNull(admission);
            if (mutatingUsers < 1 || readOnlyUsers < 1 || materializedWorkspaces < 1
                    || aggregateStorageBytes < 1 || queuedJobs < 1 || retainedJobs < 1) {
                throw new IllegalArgumentException("workspace capacities must be positive");
            }
        }
    }
    /** Freezes the approved profile and refuses a total storage ceiling too small for one workspace. */
    public WorkspaceProfile {
        Objects.requireNonNull(reference); Objects.requireNonNull(workspaceScope);
        Objects.requireNonNull(runtimeLifecycle); Objects.requireNonNull(policy);
        Objects.requireNonNull(capacity); Objects.requireNonNull(retention); Objects.requireNonNull(completionPolicy);
        Objects.requireNonNull(fleetLimits);
        if (cpuMillicores < 1) throw new IllegalArgumentException("Workspace CPU ceiling must be positive");
        runnerPool = RunnerPolicy.identifier(runnerPool); runtimeProfile = RunnerPolicy.identifier(runtimeProfile);
        allowedAgents = RunnerPolicy.identifiers(allowedAgents);
        if (allowedAgents.isEmpty() || retention.isNegative()) throw new IllegalArgumentException("invalid workspace policy");
        if (capacity.aggregateStorageBytes() < policy.limits().workspaceBytes()) {
            throw new IllegalArgumentException("aggregate storage must admit one workspace");
        }
    }
}
