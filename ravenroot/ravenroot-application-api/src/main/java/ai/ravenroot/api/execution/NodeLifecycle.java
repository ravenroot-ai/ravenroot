package ai.ravenroot.api.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Supervision, cancellation and drain bookkeeping for one node, shared by every adapter (CORE-04).
 *
 * <p>This class is engine-support, not application surface: adapters call it, applications do not.
 * It lives beside the SPI it implements because there is no engine-SPI module, and it holds no
 * dependency beyond {@code java.util.concurrent} — no actor, no dispatcher, no framework type.</p>
 *
 * <h2>Why the state machine is shared rather than copied</h2>
 * <p>Both engines must offer the <em>same</em> Ravenroot semantics. Two
 * adapters each carrying their own copy of an admission rule, an accounting counter and a transition
 * table are two adapters that drift, and the drift shows up as a race that only one of them loses.
 * What is genuinely framework-specific — the actor that serialises message handling, the scheduler
 * and the runtime's own shutdown — stays in the adapter.</p>
 *
 * <h2>The two guarantees</h2>
 * <p><strong>Nothing is accepted after the node stops accepting.</strong> {@link #accept} performs the
 * admission test and the enqueue as one operation under a read lock, while every transition out of
 * {@link NodeLifecycleState#RUNNING} takes the write lock. Without that, a sender could pass the test,
 * be descheduled, and enqueue behind a stop command that has already terminated the node — the
 * message would be dropped and its caller would wait forever.</p>
 *
 * <p><strong>Every accepted message settles exactly once.</strong> The reply is registered at
 * admission, so termination can settle whatever the node never got to, including messages the node
 * never even received. {@link #settle} completes only the reply it succeeds in removing from the
 * registry, which is what makes the outcome of a race a first-writer-wins fact rather than an
 * ordering assumption: a failure arriving after a cancellation cannot rewrite the cancellation the
 * caller already observed, and a success that landed before it is not retro-actively cancelled.</p>
 */
public final class NodeLifecycle {
    private final NodeRef node;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Set<CompletableFuture<NodeResult>> accepted = ConcurrentHashMap.newKeySet();
    private final List<Runnable> cancellationListeners = new ArrayList<>();
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private final CancellationSignal cancellation = new CancellationSignal() {
        @Override
        public boolean cancelled() {
            return NodeLifecycle.this.cancelled();
        }

        @Override
        public void onCancel(Runnable listener) {
            NodeLifecycle.this.onCancel(listener);
        }
    };

    private volatile NodeLifecycleState state = NodeLifecycleState.RUNNING;
    private volatile NodeTerminationReason reason;

/**
 * Starts supervision for an engine-issued node reference in the running state.
 *
 * @param node the node whose admission, cancellation and termination this instance coordinates
 */
    public NodeLifecycle(NodeRef node) {
        this.node = Objects.requireNonNull(node, "node");
    }

/**
 * The node this lifecycle supervises.
 * @return the node reference supervised by this lifecycle
 */
    public NodeRef node() {
        return node;
    }

/**
 * The current observable state.
 * @return the latest lifecycle state visible to callers
 */
    public NodeLifecycleState state() {
        return state;
    }

/**
 * An immutable snapshot of state, termination reason and outstanding work.
 * @return a consistent snapshot of state, termination reason and unsettled-message count
 */
    public NodeStatus status() {
        // Read the state once: a concurrent termination between the two reads would otherwise be
        // able to produce a snapshot whose reason and state disagree, which NodeStatus rejects.
        NodeLifecycleState observed = state;
        NodeTerminationReason observedReason = observed.terminal() ? terminationReason() : null;
        return new NodeStatus(node, observed, observedReason, accepted.size());
    }

/**
 * Messages accepted by the engine and not yet settled.
 * @return the number of admitted replies that have not yet settled
 */
    public int acceptedMessages() {
        return accepted.size();
    }

/**
 * Completes when the node reaches {@link NodeLifecycleState#TERMINATED}, however it got there.
 * @return a stage completed when this node reaches its terminal state
 */
    public CompletionStage<Void> terminated() {
        return terminated;
    }

/**
 * The cooperative signal handed to the node through {@link NodeContext#cancellation()}.
 * @return the cooperative cancellation signal supplied to the node
 */
    public CancellationSignal cancellation() {
        return cancellation;
    }

/**
 * Returns whether cancellation has been decided for this node.
 * @return whether cancellation has been decided or completed for this node
 */
    public boolean cancelled() {
        return state == NodeLifecycleState.CANCELLING
                || (state.terminal() && reason == NodeTerminationReason.CANCELLED);
    }

    /**
     * Admits one message and enqueues it, atomically with respect to every lifecycle transition.
     *
     * @param reply   the caller's reply, registered so that termination can always settle it
     * @param enqueue hands the message to the runtime; runs only if the message was admitted
     * @return whether the message was admitted
     */
    public boolean accept(CompletableFuture<NodeResult> reply, Runnable enqueue) {
        Objects.requireNonNull(reply, "reply");
        Objects.requireNonNull(enqueue, "enqueue");
        lock.readLock().lock();
        try {
            if (!state.accepting()) {
                return false;
            }
            accepted.add(reply);
            try {
                enqueue.run();
            } catch (RuntimeException | Error failure) {
                accepted.remove(reply);
                throw failure;
            }
            return true;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Settles one accepted message, once.
     *
     * @return whether this call was the one that settled it
 * @param reply the registered reply stage to remove from the unsettled set
 * @param result the successful node result, used only when {@code error} is {@code null}
 * @param error the failure to deliver to the reply, or {@code null} for normal completion
     */
    public boolean settle(CompletableFuture<NodeResult> reply, NodeResult result, Throwable error) {
        if (reply == null || !accepted.remove(reply)) {
            return false;
        }
        if (error == null) {
            reply.complete(result);
        } else {
            reply.completeExceptionally(error);
        }
        return true;
    }

/**
 * The exception a refused message is failed with, carrying the state that refused it.
 * @return an exception describing the current state that rejected admission
 */
    public NodeNotAcceptingException refusal() {
        return new NodeNotAcceptingException(node, state);
    }

    /**
     * Moves {@code RUNNING -> DRAINING}.
     *
     * @return whether this call performed the transition; {@code false} means the node was already
     *         draining, cancelling or terminated and the caller should simply await
     *         {@link #terminated()}
     */
    public boolean beginDrain() {
        lock.writeLock().lock();
        try {
            if (!state.canTransitionTo(NodeLifecycleState.DRAINING)) {
                return false;
            }
            state = NodeLifecycleState.DRAINING;
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Moves {@code RUNNING|DRAINING -> CANCELLING}, failing every unsettled message with
     * {@link NodeCancelledException} and firing the cooperative signal.
     *
     * <p>Settlement and listeners run outside the lock, because both hand control to code this class
     * does not own. They are nevertheless exactly-once: the snapshot is taken inside the critical
     * section, so a message admitted concurrently is either in it or was never admitted, and a
     * listener registered concurrently either is in it or observes the decision and runs itself.</p>
     *
     * @return whether this call performed the transition
     */
    public boolean beginCancel() {
        List<CompletableFuture<NodeResult>> abandoned;
        List<Runnable> listeners;
        lock.writeLock().lock();
        try {
            if (!state.canTransitionTo(NodeLifecycleState.CANCELLING)) {
                return false;
            }
            state = NodeLifecycleState.CANCELLING;
            abandoned = List.copyOf(accepted);
            synchronized (cancellationListeners) {
                listeners = List.copyOf(cancellationListeners);
                cancellationListeners.clear();
            }
        } finally {
            lock.writeLock().unlock();
        }
        abandoned.forEach(reply -> settle(reply, null, new NodeCancelledException(node)));
        listeners.forEach(NodeLifecycle::runQuietly);
        return true;
    }

    /**
     * Moves {@code DRAINING|CANCELLING -> TERMINATED}.
     *
     * <p>The reason is derived rather than supplied. A drain and a cancellation can be requested
     * concurrently, and the command that happens to complete the node is not necessarily the one that
     * decided its fate: if a cancellation has already abandoned messages, reporting {@code STOPPED}
     * because a stop command arrived last would describe the node as having drained cleanly when
     * callers hold the cancellation that says otherwise.</p>
     *
     * @return whether this call performed the transition
     */
    public boolean terminate() {
        return terminate(null);
    }

    /**
     * Moves {@code DRAINING|CANCELLING -> TERMINATED}, reporting a failure raised by
     * {@code RavenNode.onStop} to whoever asked for the termination.
     *
     * <p>The node still terminates. A node whose cleanup throws is not a node that can be left
     * running, but the caller that asked for the stop is the only party in a position to react, so
     * the failure surfaces on {@link #terminated()} rather than being swallowed.</p>
     *
     * @param onStopFailure the exception {@code onStop} threw, or {@code null}
     * @return whether this call performed the transition
     */
    public boolean terminate(Throwable onStopFailure) {
        lock.writeLock().lock();
        try {
            if (state.terminal()) {
                return false;
            }
            if (!state.canTransitionTo(NodeLifecycleState.TERMINATED)) {
                throw new IllegalStateException(
                        "A node cannot terminate from " + state + " without draining or cancelling first");
            }
            reason = state == NodeLifecycleState.CANCELLING
                    ? NodeTerminationReason.CANCELLED
                    : NodeTerminationReason.STOPPED;
            state = NodeLifecycleState.TERMINATED;
        } finally {
            lock.writeLock().unlock();
        }
        if (onStopFailure == null) {
            terminated.complete(null);
        } else {
            terminated.completeExceptionally(onStopFailure);
        }
        return true;
    }

/**
 * Why the node terminated, or {@code null} while it has not.
 * @return the terminal reason, or {@code null} while termination has not been decided
 */
    public NodeTerminationReason terminationReason() {
        return reason;
    }

    /**
     * Separates a failure a node may be resumed after from one the JVM itself declares unrecoverable.
     *
     * <p>Catching {@code Error} is what stops an {@code AssertionError} or a {@code LinkageError} —
     * an assertion, a test double, a library compiled against a different version — from killing the
     * actor and stranding the caller's reply. Those are node failures and the node resumes from them.
     * A {@link VirtualMachineError} is a different claim: the virtual machine is reporting that it can
     * no longer do its job. Reporting an {@code OutOfMemoryError} as "this one message failed, carry
     * on" hides the one failure an operator most needs to see and leaves a process running on a heap
     * it has already lost.</p>
     *
     * <p>So it is rethrown, and the actor runtime's own fatal-error policy decides. Measured on Pekko
     * 1.6.0: an {@code OutOfMemoryError} or a {@code StackOverflowError} thrown by a node escapes the
     * actor cell and the process ends with <em>"shutting down JVM since 'pekko.jvm-exit-on-fatal-error'
     * is enabled"</em>, exit code 255, while an {@code AssertionError} or a {@code LinkageError} leaves
     * the node {@code RUNNING} and the JVM alive. That is deliberately the behaviour the engine had
     * before {@code Error} was caught at all: the point of catching {@code Error} was to stop losing
     * <em>recoverable</em> failures into the actor cell, not to take the JVM's fatal-error policy away
     * from the operator who configured it.</p>
     *
     * <p>{@code StackOverflowError} is a {@code VirtualMachineError} and is therefore not exempted,
     * even though a stack overflow is often a plain user-code bug. Drawing the line anywhere other
     * than the JVM's own type hierarchy would mean this class deciding, case by case, which failures
     * an operator is allowed to configure a policy for.</p>
     *
     * @return {@code error}, when it is a failure a node may be resumed after
     * @throws VirtualMachineError when it is not
 * @param error the callback failure to classify for resume-on-failure supervision
     */
    public static Throwable resumableOrRethrow(Throwable error) {
        if (error instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        return error;
    }

    private void onCancel(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        boolean alreadyCancelled;
        lock.readLock().lock();
        try {
            alreadyCancelled = cancelled();
            if (!alreadyCancelled) {
                synchronized (cancellationListeners) {
                    cancellationListeners.add(listener);
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        if (alreadyCancelled) {
            runQuietly(listener);
        }
    }

    private static void runQuietly(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException | Error error) {
            // A node's own cancellation callback must not be able to fail the cancellation, nor to
            // stop the remaining callbacks from running. A VirtualMachineError is not one of those:
            // swallowing it here would leave the process running on a broken JVM with nothing logged.
            resumableOrRethrow(error);
        }
    }
}
