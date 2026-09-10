package ai.ravenroot.server.persistence;

import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.core.runtime.ExecutionOwnership;
import ai.ravenroot.core.runtime.WorkerIdentity;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Who this replica is when it takes a lease, and for how long it claims.
 *
 * <p>Read at the composition root in the {@code fromEnvironment(Map)} idiom every other server-side
 * configuration uses. Core holds the seam ({@link ExecutionOwnership}) and deliberately no
 * configuration channel, so the two variables are read here and the decision travels inward as a
 * parameter.</p>
 *
 * <h2>Two identities, one process</h2>
 * <p>{@link #runtimeOwnership()} and {@link #recoveryIdentity()} share a replica name and an
 * incarnation and differ only in role, which is the whole point: an operator reading
 * {@code ownerWorkerId} can see that both claims come from the same start of the same replica, while
 * the store still sees two distinct workers. They must stay distinct — the shared store's
 * claim-candidate query skips instances whose live lease belongs to a <em>different</em> worker, and
 * a same-worker claim keeps the fencing token where a different-worker claim rotates it. One identity
 * for both roles would let this replica's recovery sweep claim work its own runtime is advancing and
 * keep the fence under the runtime's recorder. {@link WorkerIdentity} states the mechanism in full.</p>
 */
public record ExecutionOwnershipConfiguration(String replicaName, Duration leaseTtl) {

    /**
     * The replica's operator-visible name. Defaults to the host name, which in every shipped
     * deployment descriptor is already the thing an operator would type: a Kubernetes pod's host name
     * is its pod name, and a Compose service's is its container name.
     */
    public static final String WORKER_ID_VARIABLE = "RAVENROOT_WORKER_ID";

    /** How long a traversal lease runs before renewal. Whole seconds, in the house spelling. */
    public static final String LEASE_TTL_VARIABLE = "RAVENROOT_EXECUTION_LEASE_TTL_SECONDS";

    /**
     * The name used when neither the variable nor the operating system supplies one.
     *
     * <p>Not a failure, deliberately. A process whose host name cannot be resolved is a perfectly
     * ordinary process — a container with no DNS, a locked-down sandbox — and refusing to start over a
     * cosmetic identifier would trade a real outage for a readability improvement. Uniqueness does not
     * depend on this: {@link WorkerIdentity#processIncarnation()} carries it.</p>
     */
    private static final String UNRESOLVED_REPLICA_NAME = "unnamed-replica";

    public ExecutionOwnershipConfiguration {
        Objects.requireNonNull(replicaName, "replicaName");
        Objects.requireNonNull(leaseTtl, "leaseTtl");
    }

    /**
     * Reads the replica name and lease ttl an operator configured.
     *
     * @param environment the process environment.
     * @return the ownership settings, with today's ttl and the host name when nothing is set.
     * @throws IllegalArgumentException when either value is malformed.
     */
    public static ExecutionOwnershipConfiguration fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String configured = environment.get(WORKER_ID_VARIABLE);
        String replicaName = configured == null || configured.isBlank()
                ? hostName() : configured.trim();
        try {
            // Validated by constructing the identity rather than by a second copy of the rule here.
            // Two charset checks that could disagree is exactly the defect the single ReplicaCount
            // parser exists to prevent one variable's worth of. The message names this variable
            // because that is what the operator set; the rule itself comes from the one place that
            // owns it.
            WorkerIdentity.of(replicaName, WorkerIdentity.Role.RUNTIME);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException(WORKER_ID_VARIABLE + " " + WorkerIdentity.NAME_RULE);
        }
        return new ExecutionOwnershipConfiguration(replicaName, leaseTtl(environment));
    }

    /** The identity and ttl the application advances traversals under. */
    public ExecutionOwnership runtimeOwnership() {
        return new ExecutionOwnership(WorkerIdentity.of(replicaName, WorkerIdentity.Role.RUNTIME), leaseTtl);
    }

    /** The identity the recovery sweep settles abandoned work under; never the runtime's. */
    public WorkerIdentity recoveryIdentity() {
        return WorkerIdentity.of(replicaName, WorkerIdentity.Role.RECOVERY);
    }

    /**
     * Refuses a ttl the composed store publishes bounds against, naming the variable the operator set.
     *
     * <p>{@link ExecutionOwnership#requireCompatible} makes the same two checks as a construction
     * invariant and knows no variable name; this is the operator's diagnostic, and it runs first, so
     * a misconfiguration is reported in the operator's own terms rather than as a constructor
     * complaint about a {@code Duration}. The bounds are the store's, not this class's: {@code
     * maxLeaseTtl()} is the largest claim it will accept, and {@code maxClockSkew()} is how far two
     * hosts' clocks may disagree, so a ttl at or below it can be judged expired by a peer while its
     * holder still believes it live.</p>
     *
     * @param store the composed execution store, or {@code null} when none is composed.
     */
    public void requireCompatible(ExecutionStore store) {
        if (store == null) {
            return;
        }
        if (leaseTtl.compareTo(store.maxLeaseTtl()) > 0) {
            throw new IllegalArgumentException(LEASE_TTL_VARIABLE + " is longer than the configured "
                    + "store's published maximum lease of " + store.maxLeaseTtl().toSeconds() + " seconds");
        }
        if (leaseTtl.compareTo(store.maxClockSkew()) <= 0) {
            throw new IllegalArgumentException(LEASE_TTL_VARIABLE + " must be longer than the configured "
                    + "store's declared clock-skew budget of " + store.maxClockSkew().toSeconds()
                    + " seconds, or a peer can judge a live lease expired");
        }
    }

    private static Duration leaseTtl(Map<String, String> environment) {
        String raw = environment.get(LEASE_TTL_VARIABLE);
        if (raw == null || raw.isBlank()) {
            return ExecutionOwnership.DEFAULT_LEASE_TTL;
        }
        try {
            long seconds = Long.parseLong(raw.trim());
            if (seconds < 1) {
                throw new NumberFormatException();
            }
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException invalid) {
            // Fails closed rather than defaulting, for ReplicaCount's reason: a deployment that
            // mistyped how long it holds work is a deployment whose recovery timing is unverified,
            // and quietly substituting the shipped default is how that stops being checked.
            throw new IllegalArgumentException(LEASE_TTL_VARIABLE + " must be a positive whole "
                    + "number of seconds");
        }
    }

    /**
     * The operating system's idea of this host's name.
     *
     * <p>{@code InetAddress.getLocalHost()} is the only JDK API for this and it performs a name
     * resolution, which can be slow, can fail, and runs under this process's own resolver filter. It
     * is therefore the fallback rather than the first choice: the container runtimes this product
     * ships descriptors for already export the name as an environment variable, so the common case
     * costs nothing and never touches the resolver. Every failure lands on
     * {@link #UNRESOLVED_REPLICA_NAME} rather than aborting startup, and a name the identity charset
     * rejects is treated as no name at all — an operator who wants a specific one sets
     * {@link #WORKER_ID_VARIABLE}, which is validated and refused loudly.</p>
     */
    private static String hostName() {
        for (String candidate : new String[] {System.getenv("HOSTNAME"), System.getenv("COMPUTERNAME")}) {
            String usable = usableName(candidate);
            if (usable != null) {
                return usable;
            }
        }
        try {
            String usable = usableName(InetAddress.getLocalHost().getHostName());
            return usable == null ? UNRESOLVED_REPLICA_NAME : usable;
        } catch (Exception unresolved) {
            return UNRESOLVED_REPLICA_NAME;
        }
    }

    private static String usableName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String trimmed = candidate.trim();
        try {
            WorkerIdentity.of(trimmed, WorkerIdentity.Role.RUNTIME);
            return trimmed;
        } catch (IllegalArgumentException unusable) {
            return null;
        }
    }
}
