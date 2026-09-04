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
    private final ExecutionOrigin origin;
    private final List<HandlerRegistration> handlersToRegister;
    private final List<HandlerTransition> handlerTransitions;
    private final List<ToolApprovalRegistration> toolApprovalsToRegister;
    private final List<ToolApprovalTransition> toolApprovalTransitions;
    private final List<AgentBudgetOperation> agentBudgetOperations;
    private final List<HumanTaskRegistration> humanTasksToRegister;
    private final List<HumanTaskTransition> humanTaskTransitions;
    private final List<ExecutionPauseRegistration> executionPausesToRegister;
    private final List<ExecutionPauseTransition> executionPauseTransitions;

    private ExecutionBatch(Builder builder) {
        this.key = builder.key;
        this.expectation = builder.expectation;
        this.fencingToken = builder.fencingToken;
        this.transitions = List.copyOf(builder.transitions);
        this.timersToSchedule = List.copyOf(builder.timersToSchedule);
        this.timersToCancel = List.copyOf(builder.timersToCancel);
        this.idempotency = builder.idempotency;
        this.events = List.copyOf(builder.events);
        this.origin = builder.origin == null ? ExecutionOrigin.none() : builder.origin;
        this.handlersToRegister = List.copyOf(builder.handlersToRegister);
        this.handlerTransitions = List.copyOf(builder.handlerTransitions);
        this.toolApprovalsToRegister = List.copyOf(builder.toolApprovalsToRegister);
        this.toolApprovalTransitions = List.copyOf(builder.toolApprovalTransitions);
        this.agentBudgetOperations = List.copyOf(builder.agentBudgetOperations);
        this.humanTasksToRegister = List.copyOf(builder.humanTasksToRegister);
        this.humanTaskTransitions = List.copyOf(builder.humanTaskTransitions);
        this.executionPausesToRegister = List.copyOf(builder.executionPausesToRegister);
        this.executionPauseTransitions = List.copyOf(builder.executionPauseTransitions);
        // Every operation category is named here, and the guard has to grow with each one. An origin
        // counts as an operation: recording a deployment, workload or correlation identity changes
        // stored state and is a legitimate write on its own, which is what lets a caller that learns
        // the relationship after creation annotate the row without inventing a no-op transition to
        // carry it. A handler registration or transition counts for the same reason. A category
        // omitted from this condition would make its own batch look empty and be rejected.
        if (transitions.isEmpty() && timersToSchedule.isEmpty() && timersToCancel.isEmpty()
                && idempotency == null && events.isEmpty() && origin.isEmpty()
                && handlersToRegister.isEmpty() && handlerTransitions.isEmpty()
                && toolApprovalsToRegister.isEmpty()
                && toolApprovalTransitions.isEmpty()
                && humanTasksToRegister.isEmpty() && humanTaskTransitions.isEmpty()
                && executionPausesToRegister.isEmpty() && executionPauseTransitions.isEmpty()
                && agentBudgetOperations.isEmpty()) {
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
     * The deployment, workload and correlation identities this batch records on the instance's
     * inventory row.
     *
     * <p>These are descriptive relationships rather than lifecycle state, so they are annotation
     * semantics and not a transition: every component present here is written, and every component
     * absent leaves the stored value exactly as it was. A recovery or re-entry write that does not know
     * the deployment therefore cannot erase what creation recorded, and there is no ordering of
     * partially-informed callers that destroys information.</p>
     *
     * <p>Deliberately not the write-once rule {@link GraphVersionPin} follows: the pin is write-once
     * because replay correctness depends on replaying against the same definition, whereas a
     * redeployment genuinely can move hosting, so a later origin value is an update rather than a
     * contradiction.</p>
 * @return the origin components this batch records; {@link ExecutionOrigin#none()} when it records none.
     */
    public ExecutionOrigin origin() {
        return origin;
    }

    /**
     * Handlers created inside this batch's transaction, in the order the caller added them.
     *
     * <p>The second operation category ADR 0010 section 3 reserved the builder for, and the reason
     * this type never had a canonical constructor. A registration here commits with the
     * {@link ExecutionTransition.TraversalTransitioned} that moves its traversal to
     * {@link ai.ravenroot.api.application.TraversalStatus#WAITING} or neither commits, so there is no
     * instant at which a process is waiting and nothing durable records what for.</p>
     *
     * <p>Every registration's {@code traversalId} and {@code invocationId} must exist in the
     * <em>post-fold</em> aggregate, so a handler may be registered in the same batch that creates the
     * invocation it belongs to. A registration naming neither is
     * {@link ExecutionStoreFailure.InvalidRequest}. A registration whose correlation key is already
     * held by a live handler is {@link ExecutionStoreFailure.HandlerCorrelationTaken}; one whose
     * deduplication key was already used is a no-op success, which is what makes a retried wait
     * safe.</p>
     *
     * <p><strong>Every registration in a batch is folded before every transition in it</strong>, and
     * that ordering is observable rather than incidental. It means one batch cannot both close a wait
     * and re-open the same correlation key: the new registration is checked against a handler that is
     * still live, so it is refused with
     * {@link ExecutionStoreFailure.HandlerCorrelationTaken}. Both adapters agree on this and the
     * conformance suite holds them to it, so it is a contract rather than an accident — but it is a
     * contract that forbids a re-arming loop, and a caller that needs one must use two batches. If a
     * future graph shape needs re-arming to be atomic, that is a deliberate change to this ordering
     * with its own conformance assertion, not a fix to be discovered while writing the graph.</p>
     *
     * <p>Fails with {@link ExecutionStoreFailure.CapabilityNotSupported} unless
     * {@link StoreCapability#DURABLE_HANDLERS} is declared. The rejection is a property of the store
     * rather than of the builder, for the reason {@link #fencingToken()} already gives: the
     * conformance suite must be able to construct the offending batch in order to assert that every
     * adapter rejects it.</p>
 * @return immutable handler registrations in the order they were added.
     */
    public List<HandlerRegistration> handlersToRegister() {
        return handlersToRegister;
    }

    /**
     * Handler state changes applied inside this batch's transaction, in the order the caller added
     * them.
     *
     * <p>An outcome-bearing transition — {@link HandlerTransition.Expired},
     * {@link HandlerTransition.Denied}, {@link HandlerTransition.Resolved} — must name a
     * {@code resumeTraversalId} that exists in the post-fold aggregate, which in practice means the
     * batch also carries the {@link ExecutionTransition.TraversalAdded} that creates it. A
     * transition naming a traversal the batch did not produce is
     * {@link ExecutionStoreFailure.InvalidRequest}: a handler that closed a process and named a
     * re-entry point nobody created would strand the process silently.</p>
     *
     * <p>A transition the handler's stored state does not permit is
     * {@link ExecutionStoreFailure.HandlerNotResolvable}, which is where duplicate, late and
     * out-of-order triggers land. Re-escalating an already escalated handler is the one exception and
     * is answered as a no-op success, because an at-least-once timer delivery must not be able to
     * turn an escalation into a failure.</p>
 * @return immutable handler transitions in the order they were added.
     */
    public List<HandlerTransition> handlerTransitions() {
        return handlerTransitions;
    }

    /** Tool approvals registered atomically with this batch's execution changes. */
    public List<ToolApprovalRegistration> toolApprovalsToRegister() {
        return toolApprovalsToRegister;
    }

    /** Tool-approval lifecycle changes applied atomically with this batch. */
    public List<ToolApprovalTransition> toolApprovalTransitions() {
        return toolApprovalTransitions;
    }

    /** Agent authority and budget mutations applied atomically with this batch. */
    public List<AgentBudgetOperation> agentBudgetOperations() { return agentBudgetOperations; }

    /**
     * Human tasks registered atomically with this batch's execution changes.
     *
     * @return immutable registrations in insertion order.
     */
    public List<HumanTaskRegistration> humanTasksToRegister() {
        return humanTasksToRegister;
    }

    /**
     * Human-task lifecycle changes applied atomically with this batch.
     *
     * @return immutable transitions in insertion order.
     */
    public List<HumanTaskTransition> humanTaskTransitions() {
        return humanTaskTransitions;
    }

    /**
     * Operator holds committed atomically with this batch's execution changes.
     *
     * @return immutable registrations in insertion order.
     */
    public List<ExecutionPauseRegistration> executionPausesToRegister() {
        return executionPausesToRegister;
    }

    /**
     * Hold settlements applied atomically with this batch.
     *
     * @return immutable transitions in insertion order.
     */
    public List<ExecutionPauseTransition> executionPauseTransitions() {
        return executionPauseTransitions;
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
        private ExecutionOrigin origin;
        private final List<HandlerRegistration> handlersToRegister = new ArrayList<>();
        private final List<HandlerTransition> handlerTransitions = new ArrayList<>();
        private final List<ToolApprovalRegistration> toolApprovalsToRegister = new ArrayList<>();
        private final List<ToolApprovalTransition> toolApprovalTransitions = new ArrayList<>();
        private final List<AgentBudgetOperation> agentBudgetOperations = new ArrayList<>();
        private final List<ExecutionPauseRegistration> executionPausesToRegister = new ArrayList<>();
        private final List<ExecutionPauseTransition> executionPauseTransitions = new ArrayList<>();
        private final List<HumanTaskRegistration> humanTasksToRegister = new ArrayList<>();
        private final List<HumanTaskTransition> humanTaskTransitions = new ArrayList<>();

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
         * Records the deployment, workload and correlation identities of this execution.
         *
         * <p>Repeatable: a later call merges over an earlier one component by component, so a caller
         * assembling the origin from several sources never has to know which source runs last.</p>
 * @param value origin components to record; absent components leave stored values untouched.
 * @return this builder.
         */
        public Builder recordOrigin(ExecutionOrigin value) {
            if (value == null) throw new IllegalArgumentException("origin cannot be null");
            this.origin = origin == null ? value : origin.mergedWith(value);
            return this;
        }

/**
 * Registers a durable handler inside this batch's transaction.
 * @param registration handler to create atomically with the transitions beside it.
 * @return this builder.
 */
        public Builder registerHandler(HandlerRegistration registration) {
            if (registration == null) throw new IllegalArgumentException("registration cannot be null");
            handlersToRegister.add(registration);
            return this;
        }

/**
 * Applies one handler state change inside this batch's transaction.
 * @param transition handler transition to apply atomically with the transitions beside it.
 * @return this builder.
 */
        public Builder applyHandler(HandlerTransition transition) {
            if (transition == null) throw new IllegalArgumentException("transition cannot be null");
            handlerTransitions.add(transition);
            return this;
        }

        /** Registers one exact tool-approval request in this transaction. */
        public Builder registerToolApproval(ToolApprovalRegistration registration) {
            if (registration == null) throw new IllegalArgumentException("registration cannot be null");
            toolApprovalsToRegister.add(registration);
            return this;
        }

        /** Applies one tool-approval lifecycle transition in this transaction. */
        public Builder applyToolApproval(ToolApprovalTransition transition) {
            if (transition == null) throw new IllegalArgumentException("transition cannot be null");
            toolApprovalTransitions.add(transition);
            return this;
        }

        /** Adds one ordered agent authority/budget mutation to this transaction. */
        public Builder applyAgentBudget(AgentBudgetOperation operation) {
            if (operation == null) throw new IllegalArgumentException("operation cannot be null");
            agentBudgetOperations.add(operation);
            return this;
        }

        /**
         * Registers one first-class durable human task in this transaction.
         *
         * @param registration immutable task registration.
         * @return this builder.
         */
        public Builder registerHumanTask(HumanTaskRegistration registration) {
            if (registration == null) throw new IllegalArgumentException("registration cannot be null");
            humanTasksToRegister.add(registration);
            return this;
        }

        /**
         * Applies one generation-fenced human-task transition in this transaction.
         *
         * @param transition generation-fenced lifecycle transition.
         * @return this builder.
         */
        public Builder applyHumanTask(HumanTaskTransition transition) {
            if (transition == null) throw new IllegalArgumentException("transition cannot be null");
            humanTaskTransitions.add(transition);
            return this;
        }

        /**
         * Commits one durable operator hold in this transaction.
         *
         * @param registration immutable hold registration.
         * @return this builder.
         */
        public Builder registerExecutionPause(ExecutionPauseRegistration registration) {
            if (registration == null) throw new IllegalArgumentException("registration cannot be null");
            executionPausesToRegister.add(registration);
            return this;
        }

        /**
         * Settles one durable operator hold in this transaction.
         *
         * @param transition compare-and-set hold settlement.
         * @return this builder.
         */
        public Builder applyExecutionPause(ExecutionPauseTransition transition) {
            if (transition == null) throw new IllegalArgumentException("transition cannot be null");
            executionPauseTransitions.add(transition);
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
