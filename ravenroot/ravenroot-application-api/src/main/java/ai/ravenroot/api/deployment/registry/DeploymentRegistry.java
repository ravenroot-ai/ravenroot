package ai.ravenroot.api.deployment.registry;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.persistence.RevisionExpectation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Engine- and storage-neutral authority for immutable deployment intent and evidence (ADR 0023). */
public interface DeploymentRegistry extends AutoCloseable {
/**
 * Defines the update strategy contract exposed to Ravenroot integrators.
 */
    enum UpdateStrategy {
        /** Stops the existing deployment before starting its replacement. */
        STOP_FIRST
    }
/**
 * Defines the desired kind contract exposed to Ravenroot integrators.
 */
    enum DesiredKind {
        /** Requests that the deployment be stopped. */
        STOPPED,
        /** Requests that the deployment be running. */
        RUNNING
    }
/**
 * Defines the observed kind contract exposed to Ravenroot integrators.
 */
    enum ObservedKind {
        /** Has not been started. */
        COLD,
        /** Is starting. */
        STARTING,
        /** Is ready to serve work. */
        READY,
        /** Is available with degraded capability. */
        DEGRADED,
        /** Is draining work before stopping. */
        DRAINING,
        /** Is stopping. */
        STOPPING,
        /** Has stopped. */
        STOPPED,
        /** Failed to reach or retain its desired state. */
        FAILED
    }

/**
 * Defines the desired contract exposed to Ravenroot integrators.
 * @param kind requested lifecycle state; {@code RUNNING} requires a version and update strategy.
 * @param desiredVersion the desired version constraint applied while processing the request.
 * @param updateStrategy update strategy supplied to this declaration.
 * @param generation monotonically increasing desired-state generation, beginning at zero.
 */
    record Desired(DesiredKind kind, Long desiredVersion, UpdateStrategy updateStrategy, long generation) {
/**
 * Rejects an incomplete running request and a stopped request that still carries versioning data.
 */
        public Desired {
            if (kind == null || generation < 0) throw new IllegalArgumentException("invalid desired state");
            if (kind == DesiredKind.RUNNING && (desiredVersion == null || desiredVersion < 1 || updateStrategy == null))
                throw new IllegalArgumentException("RUNNING requires version and strategy");
            if (kind == DesiredKind.STOPPED && (desiredVersion != null || updateStrategy != null))
                throw new IllegalArgumentException("STOPPED has no version or strategy");
        }
    }
/**
 * Defines the observation contract exposed to Ravenroot integrators.
 * @param state state observed from the deployment runtime.
 * @param activeVersion the active version constraint applied while processing the request.
 * @param observedGeneration desired-state generation seen by the runtime.
 * @param observedAt instant at which the runtime state was sampled.
 */
    record Observation(ObservedKind state, Long activeVersion, long observedGeneration, Instant observedAt) {
/**
 * Ensures that only active runtime states carry an active graph version.
 */
        public Observation {
            if (state == null || observedAt == null || observedGeneration < 0) throw new IllegalArgumentException("invalid observation");
            if (activeVersion != null && activeVersion < 1) throw new IllegalArgumentException("activeVersion");
            boolean requiresActiveVersion = switch (state) {
                case READY, DEGRADED, DRAINING, STOPPING -> true;
                case COLD, STARTING, STOPPED, FAILED -> false;
            };
            if (requiresActiveVersion != (activeVersion != null))
                throw new IllegalArgumentException(state + (requiresActiveVersion
                        ? " requires an activeVersion" : " cannot carry an activeVersion"));
        }
    }
/**
 * Defines the lease contract exposed to Ravenroot integrators.
 * @param tenantId stable tenant id for this declaration.
 * @param deploymentId stable deployment id for this declaration.
 * @param owner stable identity of the lease holder.
 * @param fence positive fencing token that orders successive lease holders.
 * @param acquiredAt instant at which the holder acquired the lease.
 * @param expiresAt exclusive lease-expiry instant, later than {@code acquiredAt}.
 */
    record Lease(String tenantId, DeploymentId deploymentId, String owner, long fence,
                 Instant acquiredAt, Instant expiresAt) {
/**
 * Rejects blank identities, non-positive fencing tokens, and leases with an invalid time interval.
 */
        public Lease {
            if (tenantId == null || tenantId.isBlank() || deploymentId == null || owner == null || owner.isBlank()
                    || fence < 1 || acquiredAt == null || expiresAt == null || !expiresAt.isAfter(acquiredAt))
                throw new IllegalArgumentException("invalid lease");
        }
    }
/**
 * Defines the failure contract exposed to Ravenroot integrators.
 * @param code uppercase machine-readable failure code.
 * @param message sanitized, bounded diagnostic safe to expose to a deployment client.
 * @param at instant at which the failure was recorded.
 */
    record Failure(String code, String message, Instant at) {
/**
 * Rejects malformed codes and messages that are blank, oversized, or contain control characters.
 */
        public Failure {
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}") || message == null || message.isBlank()
                    || message.length() > 256 || message.chars().anyMatch(c -> Character.isISOControl(c)) || at == null)
                throw new IllegalArgumentException("invalid sanitized failure");
        }
    }
/**
 * Defines the tombstone contract exposed to Ravenroot integrators.
 * @param reason sanitized explanation of why the deployment was removed.
 * @param at instant at which the tombstone was written.
 */
    record Tombstone(String reason, Instant at) {
/**
 * Restricts tombstone reasons to bounded, display-safe text.
 */
        public Tombstone {
            if (reason == null || reason.isBlank() || reason.length() > 256
                    || reason.chars().anyMatch(c -> Character.isISOControl(c)) || at == null)
                throw new IllegalArgumentException("invalid tombstone");
        }
    }
/**
 * Defines the record contract exposed to Ravenroot integrators.
 * @param tenantId stable tenant id for this declaration.
 * @param deploymentId stable deployment id for this declaration.
 * @param latestVersion the latest version constraint applied while processing the request.
 * @param generation current desired-state generation.
 * @param revision compare-and-set revision of this aggregate.
 * @param desired requested lifecycle state.
 * @param observed latest runtime observation.
 * @param lease currently held lease, or {@code null} when unleased.
 * @param failure latest sanitized runtime failure, or {@code null}.
 * @param tombstone removal marker, or {@code null} for a live deployment.
 * @param createdAt instant at which this aggregate was created.
 * @param updatedAt instant of its most recent accepted mutation.
 */
    record Record(String tenantId, DeploymentId deploymentId, long latestVersion, long generation, long revision,
                  Desired desired, Observation observed, Lease lease, Failure failure, Tombstone tombstone,
                  Instant createdAt, Instant updatedAt) {
/**
 * Preserves the aggregate ordering and requires the update instant not to precede creation.
 */
        public Record {
            if (tenantId == null || tenantId.isBlank() || deploymentId == null || latestVersion < 1
                    || generation < 0 || revision < 1 || desired == null || observed == null
                    || createdAt == null || updatedAt == null || updatedAt.isBefore(createdAt))
                throw new IllegalArgumentException("invalid record");
        }
    }
/**
 * Defines the create command contract exposed to Ravenroot integrators.
 * @param tenantId stable tenant id for this declaration.
 * @param key stable key for this declaration.
 * @param digest content digest that identifies the graph artifact to create.
 */
    record CreateCommand(String tenantId, String key, String digest) {
/**
 * Validates the tenant, caller-provided key, and graph digest required for an idempotent create.
 */
        public CreateCommand { validate(tenantId, key, digest); }
    }
/**
 * Defines the command contract exposed to Ravenroot integrators.
 * @param tenantId stable tenant id for this declaration.
 * @param deploymentId stable deployment id for this declaration.
 * @param key client-chosen idempotency key for this mutation.
 * @param digest content digest of the graph artifact to deploy.
 * @param expectedRevision exact aggregate revision required for compare-and-set.
 */
    record Command(String tenantId, DeploymentId deploymentId, String key, String digest,
                   RevisionExpectation.Exactly expectedRevision) {
/**
 * Requires a deployment target and an exact revision before an existing deployment can change.
 */
        public Command {
            validate(tenantId, key, digest);
            if (deploymentId == null || expectedRevision == null) throw new IllegalArgumentException("invalid command");
        }

        /**
         * Compatibility boundary for callers holding the shared expectation supertype. Registry writes are
         * deliberately stricter than ExecutionStore writes: every existing aggregate mutation requires CAS.
 * @param tenantId stable tenant id for this declaration.
 * @param deploymentId stable deployment id for this declaration.
 * @param key stable key for this declaration.
 * @param digest SHA-256 digest of the graph artifact to deploy.
 * @param expectedRevision shared expectation narrowed to an exact CAS revision.
         */
        public Command(String tenantId, DeploymentId deploymentId, String key, String digest,
                       RevisionExpectation expectedRevision) {
            this(tenantId, deploymentId, key, digest, requireExact(expectedRevision));
        }

        private static RevisionExpectation.Exactly requireExact(RevisionExpectation expectation) {
            if (expectation instanceof RevisionExpectation.Exactly exact) return exact;
            throw new IllegalArgumentException("deployment mutation requires an exact revision");
        }
    }
/**
 * Defines the page contract exposed to Ravenroot integrators.
 * @param items immutable page of deployment records.
 * @param nextCursor opaque cursor for the following page, or {@code null} at the end.
 */
    record Page(List<Record> items, String nextCursor) {
/**
 * Takes an immutable snapshot of the returned page so callers cannot mutate registry state.
 */
        public Page { items = List.copyOf(items); }
    }
/**
 * Defines the limits contract exposed to Ravenroot integrators.
 * @param maximumPageSize the maximum page size constraint applied while processing the request.
 * @param maximumLeaseTtl maximum lease ttl supplied to this declaration.
 */
    record Limits(int maximumPageSize, Duration maximumLeaseTtl) {
/**
 * Requires a positive page bound and a positive maximum lease duration.
 */
        public Limits {
            if (maximumPageSize < 1 || maximumLeaseTtl == null || maximumLeaseTtl.isNegative()
                    || maximumLeaseTtl.isZero()) throw new IllegalArgumentException("invalid limits");
        }
    }
/**
 * Defines the failure reason contract exposed to Ravenroot integrators.
 */
    sealed interface FailureReason permits FailureReason.NotFound, FailureReason.Conflict,
            FailureReason.Fenced, FailureReason.LeaseLost, FailureReason.InvalidRequest {
/**
 * Defines the not found contract exposed to Ravenroot integrators.
 */
        record NotFound() implements FailureReason {}
/**
 * Defines the conflict contract exposed to Ravenroot integrators.
 */
        record Conflict() implements FailureReason {}
/**
 * Defines the fenced contract exposed to Ravenroot integrators.
 */
        record Fenced() implements FailureReason {}
/**
 * Defines the lease lost contract exposed to Ravenroot integrators.
 */
        record LeaseLost() implements FailureReason {}
/**
 * Defines the invalid request contract exposed to Ravenroot integrators.
 * @param message explanation suitable for returning to the mutation caller.
 */
        record InvalidRequest(String message) implements FailureReason {}
    }
/**
 * Defines the registry exception contract exposed to Ravenroot integrators.
 */
    final class RegistryException extends RuntimeException {
/** Structured reason for rejection of a registry operation. */
        private final FailureReason reason;
/**
 * Creates a rejected-operation exception for the supplied structured reason.
 * @param reason failure category that callers can map without parsing exception text.
 */
        public RegistryException(FailureReason reason) { super(reason.toString()); this.reason = reason; }
/**
 * Returns the structured rejection reason.
 * @return category that explains why the registry rejected the operation.
 */
        public FailureReason reason() { return reason; }
    }

/**
 * Returns implementation limits for registry pagination and leasing.
 * @return immutable limits enforced by this registry.
 */
    Limits limits();
/**
 * Creates a deployment and stores its initial immutable graph version.
 * @param firstVersion the first version constraint applied while processing the request.
 * @param command validated idempotent create request.
 * @return stage completing with the created deployment record.
 */
    CompletionStage<Record> create(GraphVersion.Content firstVersion, CreateCommand command);
/**
 * Appends a new immutable graph version using compare-and-set protection.
 * @param version the version constraint applied while processing the request.
 * @param content canonical graph content for the next version.
 * @param command mutation identity and exact expected revision.
 * @return stage completing with the record that references the appended version.
 */
    CompletionStage<Record> append(long version, GraphVersion.Content content, Command command);
/**
 * Changes the desired lifecycle state of an existing deployment.
 * @param desired validated requested lifecycle state.
 * @param command mutation identity and exact expected revision.
 * @return stage completing with the record carrying the new desired state.
 */
    CompletionStage<Record> command(Desired desired, Command command);
/**
 * Records an observation from a holder of the current deployment lease.
 * @param observation runtime state and version observed by the holder.
 * @param lease lease whose fencing token authorizes the observation.
 * @param command mutation identity and exact expected revision.
 * @return stage completing with the observed record.
 */
    CompletionStage<Record> observe(Observation observation, Lease lease, Command command);
/**
 * Records a sanitized runtime failure from the current lease holder.
 * @param failure bounded failure details safe to retain with the deployment.
 * @param lease lease whose fencing token authorizes the report.
 * @param command mutation identity and exact expected revision.
 * @return stage completing with the record containing the failure.
 */
    CompletionStage<Record> fail(Failure failure, Lease lease, Command command);
/**
 * Marks a deployment removed while retaining an auditable tombstone.
 * @param tombstone bounded reason and time of removal.
 * @param command mutation identity and exact expected revision.
 * @return stage completing with the tombstoned record.
 */
    CompletionStage<Record> tombstone(Tombstone tombstone, Command command);
/**
 * Retrieves one deployment without exposing registry implementation state.
 * @param tenantId stable tenant id for this declaration.
 * @param deploymentId stable deployment id for this declaration.
 * @return stage yielding the record when found, otherwise empty.
 */
    CompletionStage<Optional<Record>> get(String tenantId, DeploymentId deploymentId);
/**
 * Retrieves an immutable graph version belonging to a deployment.
 * @param tenantId stable tenant id for this declaration.
 * @param deploymentId stable deployment id for this declaration.
 * @param version the version constraint applied while processing the request.
 * @return stage yielding that version when it exists, otherwise empty.
 */
    CompletionStage<Optional<GraphVersion>> version(String tenantId, DeploymentId deploymentId, long version);
/**
 * Lists a bounded page of a tenant's deployments.
 * @param tenantId stable tenant id for this declaration.
 * @param cursor opaque continuation cursor, or {@code null} for the first page.
 * @param limit the limit constraint applied while processing the request.
 * @return stage yielding an immutable page and optional continuation cursor.
 */
    CompletionStage<Page> list(String tenantId, String cursor, int limit);
/**
 * Acquires a generation-fenced lease for a deployment.
 * @param owner stable identity of the requested lease holder.
 * @param ttl requested positive lease duration.
 * @param command mutation identity and exact expected revision.
 * @return stage completing with the record containing the acquired lease.
 */
    CompletionStage<Record> acquire(String owner, Duration ttl, Command command);
/**
 * Extends a lease when its fencing token still identifies the current holder.
 * @param lease current lease to renew.
 * @param ttl requested positive replacement lease duration.
 * @param command mutation identity and exact expected revision.
 * @return stage completing with the record containing the renewed lease.
 */
    CompletionStage<Record> renew(Lease lease, Duration ttl, Command command);
/**
 * Releases a lease only when it has not been superseded by a newer fencing token.
 * @param lease current lease to release.
 * @param command mutation identity and exact expected revision.
 * @return stage completing with the record after the lease is removed.
 */
    CompletionStage<Record> release(Lease lease, Command command);
    @Override default void close() {}

    private static void validate(String tenantId, String key, String digest) {
        if (tenantId == null || tenantId.isBlank() || key == null || key.isBlank()
                || digest == null || !digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid command");
    }
}
