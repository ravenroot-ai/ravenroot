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
 * The lifecycle level a deployment is asked to converge to (ADR 0038 D5).
 *
 * <p>The five members form a totally ordered lattice of restriction, exposed as
 * {@link #restriction()} rather than as {@code ordinal()} so that the declaration order stays free
 * to preserve the two members that existed before this axis was named. Precedence between competing
 * commands is decided on that number: {@code Stop} outranks {@code Drain} outranks {@code Pause}
 * because each closes strictly more than the one below it, and a deployment that is already
 * converging to a more restrictive level must not be pulled back by a less restrictive command that
 * arrives late.</p>
 *
 * <p>The ranks are spaced by ten rather than consecutive so that a command with no level of its own
 * can still be ranked among them. {@code LifecycleCommand.Cancel} is exactly that case: it is a
 * barrier, it outranks a drain and is outranked by a stop, and there is no room between consecutive
 * integers to say so. Spacing keeps the one published scale authoritative instead of growing a
 * second, parallel ordering for barriers.</p>
 *
 * <p>Only {@link #RUNNING} selects a graph version, because only {@link #RUNNING} answers the
 * question "which version should be active"; every other level is a statement about admission and
 * ownership of a deployment that already knows its own activation. The version a paused or draining
 * deployment still holds is reported by {@link Observation#activeVersion()}, which is evidence
 * rather than intent.</p>
 */
    enum DesiredKind {
        /** Requests that the deployment be stopped: admission closed, work finished, resources released. */
        STOPPED(30),
        /** Requests that the deployment be running and admitting work. */
        RUNNING(0),
        /**
         * Requests that admission be closed while accepted work is retained rather than finished.
         *
         * <p>Resumable, and the only level {@code Resume} may return from. The operator reason for a
         * pause travels on the command record, never on {@code DeploymentStatus}, whose invariant
         * that only degraded and failed states carry a cause is deliberately left intact.</p>
         */
        PAUSED(10),
        /** Requests that admission be closed and accepted work be carried to completion. */
        DRAINED(20),
        /** Requests that the deployment be removed, leaving an auditable tombstone. Absorbing. */
        REMOVED(40);

        /** Position in the restriction lattice; higher closes strictly more than lower. */
        private final int restriction;

        DesiredKind(int restriction) { this.restriction = restriction; }

/**
 * Returns this level's position in the restriction lattice.
 * @return monotone restriction rank, higher meaning strictly more closed.
 */
        public int restriction() { return restriction; }

/**
 * Whether this level closes at least as much as {@code other}, which is the precedence test.
 * @param other level to compare this one against.
 * @return whether this level is at least as restrictive as {@code other}.
 */
        public boolean atLeastAsRestrictiveAs(DesiredKind other) { return restriction >= other.restriction; }

/**
 * Whether this level still selects a graph version and an update strategy. Only {@code RUNNING} does.
 * @return whether a {@link Desired} of this kind carries a version and an update strategy.
 */
        public boolean carriesVersion() { return this == RUNNING; }
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
        FAILED,
        /**
         * Admission is closed by an operator hold and accepted work is retained, not finished.
         *
         * <p>Added beside {@link #DRAINING} because the two are genuinely different observations: a
         * draining deployment is finishing what it holds and will not be asked to serve again, a
         * paused one is holding what it has and can be resumed. Reporting a pause as
         * {@code DRAINING} would tell an operator that work is completing when it is not.</p>
         */
        PAUSED,
        /** Drain has completed: nothing is in flight, and the activation has not been released. */
        DRAINED
    }

/**
 * Defines the desired contract exposed to Ravenroot integrators.
 * @param kind requested lifecycle level; only {@code RUNNING} carries a version and update strategy.
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
            if (!kind.carriesVersion() && (desiredVersion != null || updateStrategy != null))
                throw new IllegalArgumentException(kind + " has no version or strategy");
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
                // PAUSED and DRAINED join this arm because neither releases the activation: a
                // deployment that is holding or has finished its work is still bound to the version
                // it was running, and reporting it without one would lose which version is held.
                case READY, DEGRADED, DRAINING, STOPPING, PAUSED, DRAINED -> true;
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
 * Identity and concurrency expectations carried by every mutation of an existing deployment.
 *
 * <h2>Two expectations, because there are two questions</h2>
 * <p>{@code expectedRevision} asks "has anything been written since I read", and
 * {@code expectedGeneration} asks "is the lifecycle still where I decided against" (ADR 0038 D1,
 * D10). They are not redundant: a lease renewal by the current owner advances the revision without
 * touching the generation, so a revision-only expectation would refuse a lifecycle command for a
 * reason unrelated to the lifecycle; and a caller replaying an accepted command sees a revision it
 * cannot predict while the generation it decided against is exactly what it wants to pin.</p>
 *
 * <p>Mutations that are not lifecycle decisions — appending a version, observing, reporting a
 * failure, and every lease operation — pass {@link GenerationExpectation#any()}, which is what the
 * pre-existing constructors supply, so that a write which genuinely has no opinion about the
 * lifecycle does not acquire one by omission.</p>
 * @param tenantId stable tenant id for this declaration.
 * @param deploymentId stable deployment id for this declaration.
 * @param key client-chosen idempotency key for this mutation.
 * @param digest content digest of the graph artifact to deploy.
 * @param expectedRevision exact aggregate revision required for compare-and-set.
 * @param expectedGeneration deployment generation this mutation was decided against.
 */
    record Command(String tenantId, DeploymentId deploymentId, String key, String digest,
                   RevisionExpectation.Exactly expectedRevision, GenerationExpectation expectedGeneration) {
/**
 * Requires a deployment target, an exact revision, and a stated generation expectation.
 */
        public Command {
            validate(tenantId, key, digest);
            if (deploymentId == null || expectedRevision == null || expectedGeneration == null)
                throw new IllegalArgumentException("invalid command");
        }

        /**
         * The pre-generation shape, retained so that every mutation which is not a lifecycle decision
         * keeps compiling and keeps meaning exactly what it meant: no opinion about the generation.
 * @param tenantId stable tenant id for this declaration.
 * @param deploymentId stable deployment id for this declaration.
 * @param key stable key for this declaration.
 * @param digest SHA-256 digest of the graph artifact to deploy.
 * @param expectedRevision exact aggregate revision required for compare-and-set.
         */
        public Command(String tenantId, DeploymentId deploymentId, String key, String digest,
                       RevisionExpectation.Exactly expectedRevision) {
            this(tenantId, deploymentId, key, digest, expectedRevision, GenerationExpectation.any());
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
            this(tenantId, deploymentId, key, digest, requireExact(expectedRevision), GenerationExpectation.any());
        }

        /**
         * The shared-supertype boundary for a lifecycle decision, which states both expectations.
 * @param tenantId stable tenant id for this declaration.
 * @param deploymentId stable deployment id for this declaration.
 * @param key stable key for this declaration.
 * @param digest SHA-256 digest of the graph artifact to deploy.
 * @param expectedRevision shared expectation narrowed to an exact CAS revision.
 * @param expectedGeneration deployment generation this mutation was decided against.
         */
        public Command(String tenantId, DeploymentId deploymentId, String key, String digest,
                       RevisionExpectation expectedRevision, GenerationExpectation expectedGeneration) {
            this(tenantId, deploymentId, key, digest, requireExact(expectedRevision), expectedGeneration);
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
 * Bounds an adapter publishes so a caller can size its own behaviour against them, not guess.
 *
 * <h2>Why the skew allowance is published rather than assumed</h2>
 * <p>Lease expiry is evaluated against the <em>store's</em> clock and never the caller's, which is
 * what makes takeover decidable at all. A holder that wants to stop working before it can be fenced
 * therefore has to know how far its own clock may be trusted against the authority's;
 * {@code maxClockSkew} is that allowance, stated by the adapter instead of each caller inventing a
 * constant. A caller renews at {@code expiresAt - maxClockSkew}, and a takeover decided at the
 * authority is correct even when the previous holder still believes it has time (ADR 0038 D2).</p>
 *
 * <p>Zero is permitted and means exactly what it says: an adapter whose clock is the caller's clock,
 * such as a single-process reference implementation, allows no skew because there is none to
 * allow.</p>
 * @param maximumPageSize the maximum page size constraint applied while processing the request.
 * @param maximumLeaseTtl maximum lease ttl supplied to this declaration.
 * @param maxClockSkew non-negative allowance between the store's clock and a caller's.
 */
    record Limits(int maximumPageSize, Duration maximumLeaseTtl, Duration maxClockSkew) {
/**
 * Requires a positive page bound, a positive maximum lease duration, and a non-negative skew allowance.
 */
        public Limits {
            if (maximumPageSize < 1 || maximumLeaseTtl == null || maximumLeaseTtl.isNegative()
                    || maximumLeaseTtl.isZero() || maxClockSkew == null || maxClockSkew.isNegative()
                    || maxClockSkew.compareTo(maximumLeaseTtl) >= 0)
                throw new IllegalArgumentException("invalid limits");
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
