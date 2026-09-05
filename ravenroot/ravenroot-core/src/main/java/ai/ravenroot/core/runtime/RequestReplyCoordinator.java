package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.ExecutionOutcome;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.deployment.RequestReplyAdmission;
import ai.ravenroot.api.deployment.RequestReplyBinding;
import ai.ravenroot.api.deployment.RequestReplyContext;
import ai.ravenroot.api.deployment.RequestReplyExchange;
import ai.ravenroot.api.deployment.RequestReplyLimits;
import ai.ravenroot.api.deployment.RequestReplyOutcome;
import ai.ravenroot.api.deployment.RequestReplyProjection;
import ai.ravenroot.api.deployment.RequestReplyRefusal;
import ai.ravenroot.api.deployment.RequestReplyTerminalState;
import ai.ravenroot.api.execution.ScheduledTask;
import ai.ravenroot.api.execution.Scheduler;
import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.security.SecurityContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Generation-scoped, engine-neutral request/reply coordination above {@link GraphRunner}.
 *
 * <p>The coordinator owns waiters, deadlines and exactly-once terminal arbitration. The execution
 * engine remains unaware of request/reply and no actor reference crosses this boundary.</p>
 */
final class RequestReplyCoordinator implements AutoCloseable {
    interface TraversalControl {
        CompletionStage<GraphExecutionResult> execute(SecurityContext security, UUID processInstanceId,
                                                       UUID traversalId, Object payload);

        void cancel(UUID traversalId);
    }

    private final String deploymentId;
    private final long generation;
    private final SecurityContext identity;
    private final ExecutionIdentitySource identitySource;
    private final Scheduler scheduler;
    private final Clock clock;
    private final RequestReplyLimits limits;
    private final Semaphore executionPermits;
    private final Semaphore waiterPermits;
    private final TraversalControl control;
    private final Runnable beforeDispatch;
    private final ConcurrentHashMap<SlotKey, ExchangeState> pending = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private boolean open = true;

    RequestReplyCoordinator(String deploymentId, long generation, SecurityContext identity,
                            ExecutionIdentitySource identitySource, Scheduler scheduler, Clock clock,
                            RequestReplyLimits limits, Semaphore executionPermits,
                            TraversalControl control) {
        this(deploymentId, generation, identity, identitySource, scheduler, clock, limits,
                executionPermits, control, () -> { });
    }

    /** Package-private deterministic race seam; production composition always uses the no-op hook. */
    RequestReplyCoordinator(String deploymentId, long generation, SecurityContext identity,
                            ExecutionIdentitySource identitySource, Scheduler scheduler, Clock clock,
                            RequestReplyLimits limits, Semaphore executionPermits,
                            TraversalControl control, Runnable beforeDispatch) {
        this.deploymentId = requireText(deploymentId, "deploymentId");
        this.generation = generation;
        this.identity = Objects.requireNonNull(identity, "identity");
        this.identitySource = Objects.requireNonNull(identitySource, "identitySource");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.executionPermits = Objects.requireNonNull(executionPermits, "executionPermits");
        this.waiterPermits = new Semaphore(limits.maxPendingWaiters());
        this.control = Objects.requireNonNull(control, "control");
        this.beforeDispatch = Objects.requireNonNull(beforeDispatch, "beforeDispatch");
    }

    RequestReplyAdmission request(IngressTarget target, PayloadValue payload, Instant deadline) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(deadline, "deadline");
        if (target.nodeId().isPresent()) {
            return refused(RequestReplyRefusal.UNSUPPORTED_TARGET);
        }

        Object boundedPayload;
        try {
            boundedPayload = boundedRequestPayload(payload);
        } catch (PayloadException rejected) {
            return refused(RequestReplyRefusal.PAYLOAD_REJECTED);
        }

        Duration remaining = remainingOrNull(deadline);
        if (remaining == null) {
            return refused(RequestReplyRefusal.INVALID_DEADLINE);
        }

        lifecycle.readLock().lock();
        try {
            if (!open) {
                return refused(RequestReplyRefusal.ADMISSION_CLOSED);
            }
            Optional<RequestReplyRefusal> capacity = reserveCapacity();
            if (capacity.isPresent()) {
                return refused(capacity.get());
            }
            IssuedIdentity issued = issueIdentity();
            return register(issued, deadline, remaining, boundedPayload);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /**
     * Admits one traversal whose payload is projected after admission and before dispatch.
     *
     * <h2>Why the projection runs where it runs</h2>
     * <p>It runs <em>after</em> capacity reservation and identity allocation because that is the only
     * point at which the context can be complete: correlation, process and traversal identifiers do
     * not exist earlier, and a projection that had to run before them could only be handed values the
     * caller supplied — which is exactly the caller-chosen identity this contract forbids.</p>
     *
     * <p>It runs <em>before</em> the slot is registered, the deadline is armed and the traversal is
     * dispatched, because a failing projection must leave nothing behind. At this point the only
     * runtime state that exists is the two permits, both held by this frame, so every failure exit
     * below is a plain release: no entry reaches {@code pending}, no scheduler task is armed, and
     * {@link TraversalControl#execute} is never called.</p>
     *
     * <p>The identifiers allocated for a refused offer are discarded and never reissued. That is the
     * intended property rather than a leak: reissuing them would let a refused offer's identity
     * reappear on a later, unrelated traversal, and the fence in {@link SlotKey} would then no longer
     * distinguish the two.</p>
     *
     * @param sourceNodeId the node whose generation-fenced view is admitting, or empty for the
     *                     deployment-wide view. Tenant, deployment and generation are <b>not</b>
     *                     parameters: this coordinator already owns them, so they cannot be supplied
     *                     — or altered — by anything upstream of it.
     */
    RequestReplyAdmission requestProjected(IngressTarget target, Optional<String> sourceNodeId,
                                           RequestReplyProjection projection, Instant deadline) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(sourceNodeId, "sourceNodeId");
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(deadline, "deadline");
        if (target.nodeId().isPresent()) {
            return refused(RequestReplyRefusal.UNSUPPORTED_TARGET);
        }

        Duration remaining = remainingOrNull(deadline);
        if (remaining == null) {
            return refused(RequestReplyRefusal.INVALID_DEADLINE);
        }

        lifecycle.readLock().lock();
        try {
            if (!open) {
                return refused(RequestReplyRefusal.ADMISSION_CLOSED);
            }
            Optional<RequestReplyRefusal> capacity = reserveCapacity();
            if (capacity.isPresent()) {
                return refused(capacity.get());
            }
            IssuedIdentity issued = issueIdentity();

            var context = new RequestReplyContext(
                    new RequestReplyBinding(identity.tenantId(), deploymentId, generation, sourceNodeId),
                    target, issued.correlationId(), issued.processInstanceId(), issued.traversalId(),
                    deadline);
            Object boundedPayload;
            try {
                PayloadValue projected = projection.project(context);
                if (projected == null) {
                    return releaseAndRefuse(RequestReplyRefusal.PAYLOAD_REJECTED);
                }
                boundedPayload = boundedRequestPayload(projected);
            } catch (RuntimeException rejected) {
                // PayloadException is unchecked, and so is anything else a caller's projection may
                // throw. Neither escapes: a call that asked for an admission decision receives one,
                // and an extension's own failure never surfaces as a runtime fault of the coordinator.
                return releaseAndRefuse(RequestReplyRefusal.PAYLOAD_REJECTED);
            } catch (Error projectionFailure) {
                // Same discipline as an identity-source failure: capacity is returned, then the JVM
                // error propagates unchanged rather than being reported as an ordinary refusal.
                releasePermits();
                throw projectionFailure;
            }

            return register(issued, deadline, remaining, boundedPayload);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /**
     * The one bounded-request rule. Both admission paths call this, so "the projected payload goes
     * through the same validation as an ordinary request" is literally the same code rather than a
     * copy that can drift.
     */
    private Object boundedRequestPayload(PayloadValue payload) {
        limits.requestPayload().enforce(payload);
        PayloadValue.requireNoReservedKeys(payload);
        return payload.toJava();
    }

    /** The remaining time this deadline allows, or {@code null} when the deadline is not admissible. */
    private Duration remainingOrNull(Instant deadline) {
        Duration remaining;
        try {
            remaining = Duration.between(clock.instant(), deadline);
        } catch (ArithmeticException invalid) {
            return null;
        }
        if (remaining.isZero() || remaining.isNegative() || remaining.compareTo(limits.maxDeadline()) > 0) {
            return null;
        }
        return remaining;
    }

    /** Reserves one waiter and one execution permit together, or refuses while holding neither. */
    private Optional<RequestReplyRefusal> reserveCapacity() {
        if (!waiterPermits.tryAcquire()) {
            return Optional.of(RequestReplyRefusal.CAPACITY_EXHAUSTED);
        }
        if (!executionPermits.tryAcquire()) {
            waiterPermits.release();
            return Optional.of(RequestReplyRefusal.CAPACITY_EXHAUSTED);
        }
        return Optional.empty();
    }

    private void releasePermits() {
        waiterPermits.release();
        executionPermits.release();
    }

    private RequestReplyAdmission releaseAndRefuse(RequestReplyRefusal reason) {
        releasePermits();
        return refused(reason);
    }

    /** Allocates the three runtime-owned identifiers, returning capacity if the source fails. */
    private IssuedIdentity issueIdentity() {
        try {
            return new IssuedIdentity(identitySource.nextEventId(), identitySource.nextProcessInstanceId(),
                    identitySource.nextTraversalId());
        } catch (RuntimeException | Error identityFailure) {
            releasePermits();
            throw identityFailure;
        }
    }

    /**
     * The shared tail of both admission paths: registers the fenced slot, arms the deadline and hands
     * dispatch to the exchange. Called with the lifecycle read lock held and both permits reserved;
     * from here on every exit either publishes an exchange or releases capacity through it.
     */
    private RequestReplyAdmission register(IssuedIdentity issued, Instant deadline, Duration remaining,
                                           Object boundedPayload) {
        var key = new SlotKey(identity.tenantId(), deploymentId, generation, issued.correlationId(),
                issued.traversalId());
        var state = new ExchangeState(key, issued.processInstanceId(), deadline);
        if (pending.putIfAbsent(key, state) != null) {
            return releaseAndRefuse(RequestReplyRefusal.IDENTITY_COLLISION);
        }

        ScheduledTask deadlineTask;
        try {
            deadlineTask = scheduler.schedule(remaining, state::timeout);
        } catch (RuntimeException | Error schedulingFailure) {
            pending.remove(key, state);
            state.releaseWaiter();
            state.releaseExecution();
            return refused(RequestReplyRefusal.RUNTIME_UNAVAILABLE);
        }
        state.arm(deadlineTask);

        state.dispatch(boundedPayload);
        return new RequestReplyAdmission.Accepted(state);
    }

    private record IssuedIdentity(UUID correlationId, UUID processInstanceId, UUID traversalId) {
    }

    @Override
    public void close() {
        java.util.List<ExchangeState> retiring;
        lifecycle.writeLock().lock();
        try {
            if (!open) {
                return;
            }
            open = false;
            retiring = java.util.List.copyOf(pending.values());
        } finally {
            lifecycle.writeLock().unlock();
        }
        retiring.forEach(ExchangeState::cancelForStop);
    }

    int pendingCount() {
        return pending.size();
    }

    private static RequestReplyAdmission refused(RequestReplyRefusal reason) {
        return new RequestReplyAdmission.Refused(reason);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record SlotKey(String tenantId, String deploymentId, long generation,
                           UUID correlationId, UUID traversalId) {
        private SlotKey {
            requireText(tenantId, "tenantId");
            requireText(deploymentId, "deploymentId");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(traversalId, "traversalId");
        }
    }

    private final class ExchangeState implements RequestReplyExchange {
        private final SlotKey key;
        private final UUID processInstanceId;
        private final Instant deadline;
        private final CompletableFuture<RequestReplyOutcome> completion = new CompletableFuture<>();
        private final CompletionStage<RequestReplyOutcome> readOnlyCompletion = completion.minimalCompletionStage();
        private final AtomicReference<RequestReplyOutcome> terminal = new AtomicReference<>();
        private final AtomicReference<ScheduledTask> deadlineTask = new AtomicReference<>();
        private final AtomicBoolean waiterReleased = new AtomicBoolean();
        private final AtomicBoolean executionReleased = new AtomicBoolean();
        /** Serializes the terminal-vs-start handshake; guarded because starting calls external code. */
        private final Object dispatchTerminalLock = new Object();
        private boolean dispatched;

        private ExchangeState(SlotKey key, UUID processInstanceId, Instant deadline) {
            this.key = key;
            this.processInstanceId = Objects.requireNonNull(processInstanceId, "processInstanceId");
            this.deadline = deadline;
        }

        @Override
        public UUID correlationId() {
            return key.correlationId();
        }

        @Override
        public UUID processInstanceId() {
            return processInstanceId;
        }

        @Override
        public UUID traversalId() {
            return key.traversalId();
        }

        @Override
        public Instant deadline() {
            return deadline;
        }

        @Override
        public CompletionStage<RequestReplyOutcome> completion() {
            return readOnlyCompletion;
        }

        @Override
        public boolean cancel() {
            return detach(RequestReplyTerminalState.CANCELLED);
        }

        private void cancelForStop() {
            detach(RequestReplyTerminalState.CANCELLED);
        }

        private void timeout() {
            detach(RequestReplyTerminalState.TIMED_OUT);
        }

        private boolean detach(RequestReplyTerminalState state) {
            var outcome = new RequestReplyOutcome(processInstanceId, key.traversalId(), state, Optional.empty());
            return finish(outcome, true);
        }

        private void arm(ScheduledTask task) {
            Objects.requireNonNull(task, "task");
            if (!deadlineTask.compareAndSet(null, task) || terminal()) {
                cancelQuietly(task);
            }
        }

        private void dispatch(Object payload) {
            try {
                // Tests can stop here, after the deadline is armed but before dispatch ownership is
                // published. The authoritative terminal check remains inside the handshake below.
                beforeDispatch.run();
            } catch (RuntimeException | Error hookFailure) {
                executionSettled(null, hookFailure);
                return;
            }

            CompletionStage<GraphExecutionResult> execution;
            Throwable dispatchFailure = null;
            synchronized (dispatchTerminalLock) {
                if (terminal()) {
                    return;
                }
                // The external call stays inside the handshake. A timeout that loses this lock can
                // publish only after execute has returned, so its cancellation cannot run before the
                // traversal exists and leave subsequently-started work unobserved.
                dispatched = true;
                try {
                    execution = Objects.requireNonNull(control.execute(identity, processInstanceId,
                            key.traversalId(), payload), "traversal execution stage");
                } catch (RuntimeException | Error failure) {
                    execution = null;
                    dispatchFailure = failure;
                }
            }
            if (dispatchFailure != null) {
                executionSettled(null, dispatchFailure);
            } else {
                execution.whenComplete(this::executionSettled);
            }
        }

        private boolean terminal() {
            return terminal.get() != null;
        }

        private void executionSettled(GraphExecutionResult result, Throwable failure) {
            releaseExecution();
            if (terminal()) {
                return;
            }
            // The absolute deadline wins even if the scheduler callback was delayed. Wall-clock
            // scheduling is a wake-up mechanism, never the authority for whether a result was timely.
            if (!clock.instant().isBefore(deadline)) {
                timeout();
                return;
            }
            if (failure != null || result == null || !processInstanceId.equals(result.processInstanceId())
                    || !key.traversalId().equals(result.traversalId())) {
                finish(failedOutcome(Set.of(), Set.of(), Set.of(), Set.of(), failure), false);
                return;
            }

            try {
                PayloadValue bounded = PayloadValue.fromJava(result.payload(), limits.outcomePayload());
                var execution = new ExecutionOutcome(processInstanceId, key.traversalId(),
                        ProcessInstanceStatus.COMPLETED, bounded.toJava(), result.visitedNodes(),
                        result.defaultedNodes(), result.bypassedNodes(), result.handledFailureNodes());
                finish(new RequestReplyOutcome(processInstanceId, key.traversalId(),
                        RequestReplyTerminalState.COMPLETED, Optional.of(execution)), false);
            } catch (PayloadException rejected) {
                // A payload the caller cannot be handed is never a cancellation, whatever failure (if
                // any) accompanied this settlement -- the traversal itself may have completed cleanly.
                finish(failedOutcome(result.visitedNodes(), result.defaultedNodes(),
                        result.bypassedNodes(), result.handledFailureNodes(), null), false);
            }
        }

        /**
         * @param failure the throwable this settlement observed, or {@code null} when none did --
         *                classified through {@link ExecutionTermination#reasonOf}, the same
         *                package-private classifier {@link GraphRunner}'s own four terminal handlers
         *                and {@link DefaultRavenrootApplication}'s result recording already share, so
         *                a request/reply waiter cannot disagree with the durable aggregate about
         *                whether the same termination was a cancellation.
         */
        private RequestReplyOutcome failedOutcome(Set<String> visited, Set<String> defaulted,
                                                  Set<String> bypassed, Set<String> handledFailures,
                                                  Throwable failure) {
            var execution = new ExecutionOutcome(processInstanceId, key.traversalId(),
                    ProcessInstanceStatus.FAILED, null, visited, defaulted, bypassed, handledFailures,
                    Set.of(), false, ExecutionTermination.reasonOf(failure));
            return new RequestReplyOutcome(processInstanceId, key.traversalId(),
                    RequestReplyTerminalState.FAILED, Optional.of(execution));
        }

        private boolean finish(RequestReplyOutcome outcome, boolean cancelTraversal) {
            boolean cancelDispatched;
            synchronized (dispatchTerminalLock) {
                if (!terminal.compareAndSet(null, outcome)) {
                    return false;
                }
                cancelDispatched = cancelTraversal && dispatched;
            }
            // Cleanup precedes publication: a caller observing completion can immediately assert the
            // waiter inventory is empty and its slot reusable.
            pending.remove(key, this);
            cancelQuietly(deadlineTask.get());
            releaseWaiter();
            if (cancelTraversal && !cancelDispatched) {
                // A timeout/cancel that wins before dispatch owns no runner work. Return execution
                // capacity here, without waiting for the request thread paused before the handshake.
                releaseExecution();
            } else if (cancelDispatched) {
                try {
                    control.cancel(key.traversalId());
                } catch (RuntimeException ignored) {
                    // Cancellation is cooperative and best-effort; terminal waiter fencing already won.
                }
            }
            completion.complete(outcome);
            return true;
        }

        private void releaseWaiter() {
            if (waiterReleased.compareAndSet(false, true)) {
                waiterPermits.release();
            }
        }

        private void releaseExecution() {
            if (executionReleased.compareAndSet(false, true)) {
                executionPermits.release();
            }
        }
    }

    private static void cancelQuietly(ScheduledTask task) {
        if (task == null) {
            return;
        }
        try {
            task.cancel();
        } catch (RuntimeException ignored) {
            // A scheduler that cannot remove a task may still run it; terminal CAS makes it inert.
        }
    }
}
