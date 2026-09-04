package ai.ravenroot.api.persistence;

import java.util.Objects;
import java.util.UUID;

/** Ordered mutations folded atomically with an execution batch. */
public sealed interface AgentBudgetOperation {
    /**
     * Registers the immutable process root.
     * @param root root authority
     * @param controlEpoch active global control epoch
     */
    record RegisterRoot(AgentAuthorityRootRegistration root, long controlEpoch) implements AgentBudgetOperation {
        /** Validates root registration inputs. */
        public RegisterRoot {
            Objects.requireNonNull(root, "root");
            if (controlEpoch < 0) throw new IllegalArgumentException("controlEpoch cannot be negative");
        }
    }
    /**
     * Registers one invocation-bound grant.
     * @param grant immutable grant
     * @param binding trusted invocation binding
     * @param bootEpoch diagnostic runtime boot epoch
     * @param controlEpoch active global control epoch
     */
    record RegisterGrant(AgentAuthorityGrantRegistration grant, AgentAuthorityBinding binding,
                         long bootEpoch, long controlEpoch)
            implements AgentBudgetOperation {
        /** Validates the grant and binding pair. */
        public RegisterGrant {
            Objects.requireNonNull(grant, "grant"); Objects.requireNonNull(binding, "binding");
            if (!grant.grantId().equals(binding.grantId())) throw new IllegalArgumentException("grant binding mismatch");
        }
    }
    /**
     * Holds resources before an external operation.
     * @param reservation empty held reservation
     * @param bootEpoch diagnostic runtime boot epoch
     * @param controlEpoch active global control epoch
     */
    record Hold(AgentBudgetReservation reservation, long bootEpoch, long controlEpoch) implements AgentBudgetOperation {
        /** Validates the initial reservation state. */
        public Hold {
            Objects.requireNonNull(reservation, "reservation");
            if (reservation.state() != AgentReservationState.HELD
                    || !reservation.actual().equals(AgentBudgetVector.ZERO)) {
                throw new IllegalArgumentException("new reservations must be empty and held");
            }
        }
    }
    /**
     * Makes a held reservation non-refundable immediately before egress.
     * @param reservationId reservation to dispatch
     * @param bootEpoch diagnostic runtime boot epoch
     * @param controlEpoch active global control epoch
     */
    record Dispatch(UUID reservationId, long bootEpoch, long controlEpoch) implements AgentBudgetOperation {
        /** Validates the reservation identity. */
        public Dispatch { Objects.requireNonNull(reservationId, "reservationId"); }
    }
    /**
     * Settles known usage within a dispatched reservation.
     * @param reservationId reservation to settle
     * @param actual known actual usage
     */
    record Settle(UUID reservationId, AgentBudgetVector actual) implements AgentBudgetOperation {
        /** Validates settlement inputs. */
        public Settle { Objects.requireNonNull(reservationId, "reservationId"); Objects.requireNonNull(actual, "actual"); }
    }
    /**
     * Records provider-reported usage outside the pre-authorized reservation and revokes the root.
     *
     * @param reservationId breached reservation identifier
     * @param observed bounded durable projection of the provider-reported usage
     */
    record Breach(UUID reservationId, AgentBudgetVector observed) implements AgentBudgetOperation {
        /** Validates breach inputs. */
        public Breach {
            Objects.requireNonNull(reservationId, "reservationId");
            Objects.requireNonNull(observed, "observed");
        }
    }
    /**
     * Marks a dispatched operation's outcome unknowable.
     * @param reservationId reservation to mark
     */
    record MarkIndeterminate(UUID reservationId) implements AgentBudgetOperation {
        /** Validates the reservation identity. */
        public MarkIndeterminate { Objects.requireNonNull(reservationId, "reservationId"); }
    }
    /**
     * Releases a held reservation before egress.
     * @param reservationId reservation to release
     */
    record Release(UUID reservationId) implements AgentBudgetOperation {
        /** Validates the reservation identity. */
        public Release { Objects.requireNonNull(reservationId, "reservationId"); }
    }
    /**
     * Cancels a grant and its delegated subtree.
     * @param grantId grant to cancel
     */
    record CancelGrant(UUID grantId) implements AgentBudgetOperation {
        /** Validates the grant identity. */
        public CancelGrant { Objects.requireNonNull(grantId, "grantId"); }
    }
    /**
     * Marks a grant exhausted and releases its active team slot.
     * @param grantId grant to exhaust
     */
    record ExhaustGrant(UUID grantId) implements AgentBudgetOperation {
        /** Validates the grant identity. */
        public ExhaustGrant { Objects.requireNonNull(grantId, "grantId"); }
    }
    /** Cancels the complete process-root authority tree. */
    record CancelRoot() implements AgentBudgetOperation { }
    /**
     * Revokes the root at an exact control epoch.
     * @param expectedControlEpoch control epoch the root must currently carry
     */
    record KillRoot(long expectedControlEpoch) implements AgentBudgetOperation { }
    /**
     * Replaces a killed root only at the exact expected epoch.
     * @param replacement replacement root registration
     * @param expectedControlEpoch expected killed-root epoch
     */
    record ResetRoot(AgentAuthorityRootRegistration replacement, long expectedControlEpoch)
            implements AgentBudgetOperation {
        /** Validates the replacement root. */
        public ResetRoot { Objects.requireNonNull(replacement, "replacement"); }
    }
    /**
     * Replaces a root for a compatible runtime reboot.
     * @param replacement replacement root registration
     * @param expectedControlEpoch expected root control epoch
     */
    record RebootRoot(AgentAuthorityRootRegistration replacement, long expectedControlEpoch)
            implements AgentBudgetOperation {
        /** Validates the replacement root. */
        public RebootRoot { Objects.requireNonNull(replacement, "replacement"); }
    }
}
