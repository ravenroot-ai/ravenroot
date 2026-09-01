package ai.ravenroot.api.embed;

import ai.ravenroot.api.security.RequestContext;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Single-process reference authority for embed registration.
 *
 * <h2>Not durable, and it does not pretend to be</h2>
 * <p>Everything here dies with the process. It exists so tests and an explicitly ephemeral
 * deployment can exercise the port's semantics without a database, and so the durable adapter has a
 * reference to agree with. A composition root that enables the packaged embed uses the SQLite
 * adapter; this one is never presented as durable or as shared between replicas.</p>
 *
 * <h2>Why one lock rather than a concurrent map</h2>
 * <p>{@code ConcurrentHashMap#compute} would give an atomic read-modify-write, but the compare-and-set
 * here has to return <em>which</em> of four outcomes happened, and threading that back out of a
 * remapping function is how a subtly non-atomic version of this gets written. A single monitor makes
 * the linearization point one word long and visible in the source. The write volume is operator
 * provisions, not requests.</p>
 */
public final class InMemoryEmbedRegistrationAuthority implements EmbedRegistrationAuthority {

    private final Map<String, EmbedRegistrationAggregate> records = new HashMap<>();
    private final Clock clock;
    private final EmbedProjectionBudget budget;

    /** Creates an authority using UTC timestamps and the standard projection budget. */
    public InMemoryEmbedRegistrationAuthority() {
        this(Clock.systemUTC(), EmbedProjectionBudget.DEFAULTS);
    }

    /** Creates a deterministic authority for an injected clock and projection ceiling.
     * @param clock source of aggregate timestamps
     * @param budget maximum projection accepted for provision
     */
    public InMemoryEmbedRegistrationAuthority(Clock clock, EmbedProjectionBudget budget) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    @Override
    public synchronized EmbedProvisionOutcome provision(EmbedProvisionCommand command) {
        Objects.requireNonNull(command, "command");
        // Re-applied here as well as in AuthorizedEmbedRegistrationAdministration: a caller holding
        // the adapter directly must be refused by the same rule as one going through the monitor.
        EmbedProvisionOutcome.Reason refusal = EmbedRegistrationRules.rejectionOf(command, budget);
        if (refusal != null) return new EmbedProvisionOutcome.Rejected(refusal);
        EmbedRegistrationAggregate current = records.get(command.registrationId());
        if (current == null) {
            if (command.expectedRevision() != 0) {
                return new EmbedProvisionOutcome.Conflict(command.expectedRevision(), 0);
            }
        } else {
            if (!current.tenantId().equals(command.tenantId())) {
                // Not a Conflict: reporting the current revision of a registration id owned by
                // another tenant would answer a question the caller is not entitled to ask.
                return new EmbedProvisionOutcome.Rejected(
                        EmbedProvisionOutcome.Reason.IDENTITY_INCOHERENT);
            }
            if (!current.active()) {
                return new EmbedProvisionOutcome.Rejected(
                        EmbedProvisionOutcome.Reason.REGISTRATION_REVOKED);
            }
            if (current.revision() != command.expectedRevision()) {
                return new EmbedProvisionOutcome.Conflict(command.expectedRevision(), current.revision());
            }
        }
        EmbedRegistrationAggregate written = command.aggregateAt(clock.instant());
        records.put(written.registrationId(), written);
        return new EmbedProvisionOutcome.Provisioned(written);
    }

    @Override
    public synchronized EmbedRevokeOutcome revoke(EmbedRevokeCommand command) {
        Objects.requireNonNull(command, "command");
        EmbedRegistrationAggregate current = records.get(command.registrationId());
        if (current == null || !current.tenantId().equals(command.tenantId())) {
            return EmbedRevokeOutcome.NotFound.INSTANCE;
        }
        if (!current.active()) return new EmbedRevokeOutcome.AlreadyRevoked(current.revision());
        if (current.revision() != command.expectedRevision()) {
            return new EmbedRevokeOutcome.Conflict(command.expectedRevision(), current.revision());
        }
        EmbedRegistrationAggregate revoked = current.revokedAt(current.revision() + 1, clock.instant());
        records.put(revoked.registrationId(), revoked);
        return new EmbedRevokeOutcome.Revoked(revoked.revision());
    }

    @Override
    public synchronized EmbedRegistrationResolution resolveCurrent(RequestContext workload,
                                                                   String registrationId) {
        Objects.requireNonNull(workload, "workload");
        if (registrationId == null || registrationId.isBlank()) {
            return EmbedRegistrationResolution.Unavailable.INSTANCE;
        }
        EmbedRegistrationAggregate current = records.get(registrationId);
        if (current == null || !current.active()) return EmbedRegistrationResolution.Unavailable.INSTANCE;
        var grant = current.sessionGrant();
        if (!grant.tenantId().equals(workload.tenantId())
                || !grant.workloadIssuer().equals(workload.issuer())
                || !grant.workloadSubject().equals(workload.subject())) {
            return EmbedRegistrationResolution.Unavailable.INSTANCE;
        }
        return new EmbedRegistrationResolution.Available(current);
    }

    @Override
    public synchronized boolean isCurrent(EmbedRegistrationAggregate captured) {
        return captured != null && captured.active()
                && captured.sameRevisionAs(records.get(captured.registrationId()));
    }
}
