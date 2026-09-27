package ai.ravenroot.api.runner;

import java.util.Map;
import java.util.Objects;

/**
 * Operator-approved limits enforced atomically by the shared store, not by a worker advertisement.
 * @param scopes explicit positive ceilings for every supported admission scope
 */
public record RunnerFleetLimits(Map<Scope, Ceiling> scopes) {
    /** Independent accounting partitions evaluated in one store transaction. */
    public enum Scope {
        /** Every tenant and worker sharing this execution store. */ GLOBAL,
        /** One tenant's approved runner pool. */ POOL,
        /** One tenant's approved worker identity. */ WORKER,
        /** All resources owned by one tenant. */ TENANT,
        /** One exact tenant-owned Workspace profile version. */ PROFILE
    }
    /**
     * Inclusive reservation ceilings; uncertain effects remain charged.
     * @param claimedJobs dispatched, cancelling or unknown jobs
     * @param queuedJobs accepted jobs awaiting their sole dispatch
     * @param retainedWorkspaces physical workspace reservations not yet released
     * @param storageBytes aggregate reserved writable storage, not measured current bytes
     */
    public record Ceiling(int claimedJobs, int queuedJobs, int retainedWorkspaces, long storageBytes) {
        /** Requires a positive finite ceiling for every resource dimension. */
        public Ceiling {
            if (claimedJobs < 1 || queuedJobs < 1 || retainedWorkspaces < 1 || storageBytes < 1)
                throw new IllegalArgumentException("fleet capacities must be positive");
        }
    }
    /** Freezes the scope map and refuses incomplete accounting policies. */
    public RunnerFleetLimits {
        scopes = Map.copyOf(Objects.requireNonNull(scopes));
        if (!scopes.keySet().equals(java.util.Set.of(Scope.values())))
            throw new IllegalArgumentException("all fleet capacity scopes must be explicit");
    }
    /**
     * Derives compatibility defaults from explicitly configured Workspace capacities.
     * @param capacity source of all derived ceilings
     * @return equal per-scope defaults, independently replaceable by an approved profile
     */
    public static RunnerFleetLimits from(WorkspaceProfile.Capacity capacity) {
        var values = new java.util.EnumMap<Scope, Ceiling>(Scope.class);
        for (Scope scope : Scope.values()) values.put(scope, new Ceiling(Math.addExact(capacity.mutatingUsers(), capacity.readOnlyUsers()),
                capacity.queuedJobs(), capacity.materializedWorkspaces(), capacity.aggregateStorageBytes()));
        return new RunnerFleetLimits(values);
    }
}
