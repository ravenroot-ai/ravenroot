package ai.ravenroot.api.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * The commit unit: an open-ended, <strong>all-or-nothing</strong> batch (ADR 0010 section 3).
 *
 * <p>Adapters must not partially apply a batch. The type is built through a builder rather than a
 * canonical constructor precisely so that PERS-07 can add outbox entries as further operations
 * inside the same batch without a breaking change to every existing call site.</p>
 *
 * <p>The batch is instantiated through {@link #to(ExecutionKey)}; there is no public constructor,
 * so adding an operation category later stays source-compatible.</p>
 */
public final class ExecutionBatch {
    private final ExecutionKey key;
    private final RevisionExpectation expectation;
    private final Long fencingToken;
    private final List<ExecutionTransition> transitions;
    private final List<TimerSchedule> timersToSchedule;
    private final List<UUID> timersToCancel;
    private final IdempotencyWrite idempotency;
    private final List<EventEnvelope> events;

    private ExecutionBatch(Builder builder) {
        this.key = builder.key;
        this.expectation = builder.expectation;
        this.fencingToken = builder.fencingToken;
        this.transitions = List.copyOf(builder.transitions);
        this.timersToSchedule = List.copyOf(builder.timersToSchedule);
        this.timersToCancel = List.copyOf(builder.timersToCancel);
        this.idempotency = builder.idempotency;
        this.events = List.copyOf(builder.events);
        if (transitions.isEmpty() && timersToSchedule.isEmpty() && timersToCancel.isEmpty()
                && idempotency == null && events.isEmpty()) {
            throw new IllegalArgumentException("an execution batch must contain at least one operation");
        }
    }

/**
 * Starts a batch addressed to one execution aggregate.
 * @param key the stable key used to identify the requested resource.
 * @return mutable builder whose completed batch writes only that key.
 */
    public static Builder to(ExecutionKey key) {
        return new Builder(key);
    }

/**
 * Returns the aggregate every operation in this batch affects.
 * @return durable execution key.
 */
    public ExecutionKey key() {
        return key;
    }

/**
 * Returns the revision condition enforced when applying this batch.
 * @return optimistic-concurrency expectation.
 */
    public RevisionExpectation expectation() {
        return expectation;
    }

    /**
     * The fencing token the caller holds, when it holds a lease. Absent means the caller is not
     * writing under a lease; adapters reject a present token that is not the current one with
     * {@link ExecutionStoreFailure.FencedOut}.
     *
     * <p>Presenting a token while {@link #expectation()} is {@link RevisionExpectation.NotPresent}
     * is a contradiction and is rejected with {@link ExecutionStoreFailure.InvalidRequest} (ADR 0010
     * section 13.2): a token is issued only by a successful claim, and a claim requires the instance
     * to exist, so a caller holding a genuine token holds proof of the existence {@code NotPresent}
     * denies.</p>
     *
     * <p>The builder deliberately does <strong>not</strong> enforce this. The rejection is a
     * property of the store, so the conformance suite must be able to construct the offending batch
     * in order to assert that every adapter rejects it — and an adapter receiving a batch it did not
     * build locally, as a remote adapter does, would be unguarded by a builder check. It is also a
     * contract violation, so it belongs in {@link ExecutionStoreFailure} with every other contract
     * violation rather than on a separate {@link IllegalArgumentException} channel.</p>
 * @return lease fencing token when this batch writes under a claim.
     */
    public OptionalLong fencingToken() {
        return fencingToken == null ? OptionalLong.empty() : OptionalLong.of(fencingToken);
    }

/**
 * Returns immutable state transitions committed atomically by this batch.
 * @return ordered transitions.
 */
    public List<ExecutionTransition> transitions() {
        return transitions;
    }

/**
 * Returns timers to create atomically with the state transitions.
 * @return immutable timer schedules.
 */
    public List<TimerSchedule> timersToSchedule() {
        return timersToSchedule;
    }

/**
 * Returns timer identifiers to cancel atomically with the batch.
 * @return immutable cancellation identifiers.
 */
    public List<UUID> timersToCancel() {
        return timersToCancel;
    }

/**
 * Returns optional inbound-delivery idempotency write.
 * @return idempotency record when the batch records one.
 */
    public Optional<IdempotencyWrite> idempotency() {
        return Optional.ofNullable(idempotency);
    }

    /**
     * Publishable events journalled inside this batch's transaction, in the order the caller added
     * them (ADR 0011, PERS-07).
     *
     * <p>This is the operation category ADR 0010 section 3 reserved the builder for, and it is the
     * whole of PERS-07's write path: an event here commits with the transitions beside it or neither
     * commits. There is no publish call to forget, because there is no publish call — a crash between
     * the transition and the event is not a window that can be narrowed, it is a window that does not
     * exist.</p>
     *
     * <p>Every envelope's {@link EventEnvelope#tenantId()} and
     * {@link EventEnvelope#processInstanceId()} must equal this batch's {@link #key()}. A mismatch is
     * {@link ExecutionStoreFailure.InvalidRequest}, decidable from the request alone under ADR 0010
     * section 12.3. It is a security guard and not merely a consistency one: an envelope naming
     * another tenant, written into this tenant's journal, would be delivered to that journal's
     * subscribers — a cross-tenant disclosure produced by a caller bug rather than by an attack.</p>
     *
     * <p>Order within the batch is preserved as
     * {@link JournalRecord#streamSequence()}, so a caller that publishes a cause before its effect
     * gets them back in that order.</p>
 * @return immutable events in their transaction and stream order.
     */
    public List<EventEnvelope> events() {
        return events;
    }

/**
 * Defines the builder contract exposed to Ravenroot integrators.
 */
    public static final class Builder {
        private final ExecutionKey key;
        private RevisionExpectation expectation = RevisionExpectation.any();
        private Long fencingToken;
        private final List<ExecutionTransition> transitions = new ArrayList<>();
        private final List<TimerSchedule> timersToSchedule = new ArrayList<>();
        private final List<UUID> timersToCancel = new ArrayList<>();
        private IdempotencyWrite idempotency;
        private final List<EventEnvelope> events = new ArrayList<>();

        private Builder(ExecutionKey key) {
            if (key == null) throw new IllegalArgumentException("key cannot be null");
            this.key = key;
        }

/**
 * Sets the optimistic-concurrency condition for the completed batch.
 * @param expectation value required by this persistence operation.
 * @return this builder.
 */
        public Builder expecting(RevisionExpectation expectation) {
            if (expectation == null) throw new IllegalArgumentException("expectation cannot be null");
            this.expectation = expectation;
            return this;
        }

/**
 * Attaches a raw lease fencing token to the completed batch.
 * @param fencingToken the stable fencing token used to identify the requested resource.
 * @return this builder.
 */
        public Builder fencedBy(long fencingToken) {
            this.fencingToken = fencingToken;
            return this;
        }

/**
 * Attaches the fencing token from an acquired lease.
 * @param lease lease that authorizes the worker.
 * @return this builder.
 */
        public Builder fencedBy(LeaseHandle lease) {
            if (lease == null) throw new IllegalArgumentException("lease cannot be null");
            return fencedBy(lease.fencingToken());
        }

/**
 * Adds one aggregate transition to the atomic write set.
 * @param transition value required by this persistence operation.
 * @return this builder.
 */
        public Builder apply(ExecutionTransition transition) {
            if (transition == null) throw new IllegalArgumentException("transition cannot be null");
            transitions.add(transition);
            return this;
        }

/**
 * Adds a durable timer schedule to the atomic write set.
 * @param timer timer schedule being persisted.
 * @return this builder.
 */
        public Builder scheduleTimer(TimerSchedule timer) {
            if (timer == null) throw new IllegalArgumentException("timer cannot be null");
            timersToSchedule.add(timer);
            return this;
        }

/**
 * Adds a durable timer cancellation to the atomic write set.
 * @param timerId the stable timer id used to identify the requested resource.
 * @return this builder.
 */
        public Builder cancelTimer(UUID timerId) {
            if (timerId == null) throw new IllegalArgumentException("timerId cannot be null");
            timersToCancel.add(timerId);
            return this;
        }

/**
 * Adds the inbound idempotency record written with this batch.
 * @param write idempotency record to persist with the batch.
 * @return this builder.
 */
        public Builder recordIdempotency(IdempotencyWrite write) {
            if (write == null) throw new IllegalArgumentException("write cannot be null");
            this.idempotency = write;
            return this;
        }

        /**
         * Journals a publishable event inside this batch's transaction.
         *
         * <p>The builder deliberately does <strong>not</strong> validate the envelope's tenant or
         * instance against the key, for the reason {@link #fencingToken()} already gives: the
         * rejection is a property of the store, so the conformance suite must be able to construct
         * the offending batch to assert that every adapter rejects it, and a remote adapter receiving
         * a batch it did not build locally would be unguarded by a builder check.</p>
 * @param envelope event envelope being committed.
 * @return this builder.
         */
        public Builder publish(EventEnvelope envelope) {
            if (envelope == null) throw new IllegalArgumentException("envelope cannot be null");
            events.add(envelope);
            return this;
        }

/**
 * Validates and freezes the accumulated atomic persistence operations.
 * @return immutable non-empty execution batch.
 */
        public ExecutionBatch build() {
            return new ExecutionBatch(this);
        }
    }
}
