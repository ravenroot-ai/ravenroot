package ai.ravenroot.core.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Monotonic traversal budget shared by every asynchronous branch and cycle re-entry. */
final class ExecutionBudget {
    private final GraphExecutionLimits limits;
    private final RunnerActorCapacity runnerActors;
    private long traversalSteps;
    private long amplifiedDeliveries;
    private long payloadBytes;
    private int inFlightHops;
    private int liveActors;

    ExecutionBudget(GraphExecutionLimits limits) {
        this(limits, new RunnerActorCapacity(limits.maxLiveActorsPerTraversal()));
    }

    ExecutionBudget(GraphExecutionLimits limits, RunnerActorCapacity runnerActors) {
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
        this.runnerActors = java.util.Objects.requireNonNull(runnerActors, "runnerActors");
    }

    static ExecutionBudget restore(GraphExecutionLimits limits, GraphExecutionBudgetSnapshot snapshot) {
        return restore(limits, snapshot, new RunnerActorCapacity(limits.maxLiveActorsPerTraversal()));
    }

    static ExecutionBudget restore(GraphExecutionLimits limits, GraphExecutionBudgetSnapshot snapshot,
                                   RunnerActorCapacity runnerActors) {
        java.util.Objects.requireNonNull(snapshot, "snapshot");
        var restored = new ExecutionBudget(limits, runnerActors);
        require(GraphExecutionLimitException.Reason.TRAVERSAL_STEPS, snapshot.traversalSteps(),
                limits.maxTraversalSteps());
        require(GraphExecutionLimitException.Reason.AMPLIFIED_DELIVERIES, snapshot.amplifiedDeliveries(),
                limits.maxAmplifiedDeliveries());
        require(GraphExecutionLimitException.Reason.PAYLOAD_BYTES, snapshot.payloadBytes(),
                limits.maxCumulativePayloadBytes());
        require(GraphExecutionLimitException.Reason.IN_FLIGHT_HOPS, snapshot.inFlightHops(),
                limits.maxInFlightHopsPerTraversal());
        require(GraphExecutionLimitException.Reason.LIVE_ACTORS, snapshot.liveActors(),
                limits.maxLiveActorsPerTraversal());
        restored.traversalSteps = snapshot.traversalSteps();
        restored.amplifiedDeliveries = snapshot.amplifiedDeliveries();
        restored.payloadBytes = snapshot.payloadBytes();
        restored.inFlightHops = snapshot.inFlightHops();
        // Actors from the pre-suspension runtime must terminate before its runner closes. They are
        // encoded and validated above, but are not live in the new execution domain after restart.
        restored.liveActors = 0;
        return restored;
    }

    synchronized GraphExecutionBudgetSnapshot snapshot() {
        return new GraphExecutionBudgetSnapshot(traversalSteps, amplifiedDeliveries, payloadBytes,
                inFlightHops, liveActors);
    }

    synchronized Hop resumeReservedHop() {
        if (inFlightHops != 1) {
            throw new IllegalStateException("approval re-entry requires exactly one reserved graph hop");
        }
        return new Hop(this);
    }

    synchronized Hop reserveRoot(long bytes) {
        reserve(1, 0, bytes, 1);
        return new Hop(this);
    }

    synchronized List<Hop> reserveFanOut(int count, long bytesPerDelivery) {
        if (count < 0 || bytesPerDelivery < 0) throw new IllegalArgumentException("budget charge cannot be negative");
        long bytes;
        try {
            bytes = Math.multiplyExact((long) count, bytesPerDelivery);
        } catch (ArithmeticException overflow) {
            throw exceeded(GraphExecutionLimitException.Reason.PAYLOAD_BYTES, Long.MAX_VALUE,
                    limits.maxCumulativePayloadBytes());
        }
        reserve(count, count, bytes, count);
        var reservations = new ArrayList<Hop>(count);
        for (int index = 0; index < count; index++) reservations.add(new Hop(this));
        return List.copyOf(reservations);
    }

    /** Charges a fresh attempt without taking a second concurrent-hop slot for the same visit. */
    synchronized void reserveRetry(boolean amplified, long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("budget charge cannot be negative");
        reserve(1, amplified ? 1 : 0, bytes, 0);
    }

    synchronized Actor reserveActor() {
        RunnerActorCapacity.Permit runnerPermit = runnerActors.reserve();
        try {
            long next = (long) liveActors + 1;
            require(GraphExecutionLimitException.Reason.LIVE_ACTORS, next,
                    limits.maxLiveActorsPerTraversal());
            liveActors++;
            return new Actor(this, runnerPermit);
        } catch (RuntimeException failure) {
            runnerPermit.close();
            throw failure;
        }
    }

    private void reserve(long steps, long amplification, long bytes, int hops) {
        long nextSteps = add(traversalSteps, steps, GraphExecutionLimitException.Reason.TRAVERSAL_STEPS,
                limits.maxTraversalSteps());
        long nextAmplification = add(amplifiedDeliveries, amplification,
                GraphExecutionLimitException.Reason.AMPLIFIED_DELIVERIES, limits.maxAmplifiedDeliveries());
        long nextBytes = add(payloadBytes, bytes, GraphExecutionLimitException.Reason.PAYLOAD_BYTES,
                limits.maxCumulativePayloadBytes());
        long nextHops = (long) inFlightHops + hops;
        require(GraphExecutionLimitException.Reason.IN_FLIGHT_HOPS, nextHops,
                limits.maxInFlightHopsPerTraversal());
        traversalSteps = nextSteps;
        amplifiedDeliveries = nextAmplification;
        payloadBytes = nextBytes;
        inFlightHops = Math.toIntExact(nextHops);
    }

    private static long add(long current, long increment, GraphExecutionLimitException.Reason reason, long limit) {
        long next;
        try {
            next = Math.addExact(current, increment);
        } catch (ArithmeticException overflow) {
            throw exceeded(reason, Long.MAX_VALUE, limit);
        }
        require(reason, next, limit);
        return next;
    }

    private static void require(GraphExecutionLimitException.Reason reason, long observed, long limit) {
        if (observed > limit) throw exceeded(reason, observed, limit);
    }

    private static GraphExecutionLimitException exceeded(
            GraphExecutionLimitException.Reason reason, long observed, long limit) {
        return new GraphExecutionLimitException(reason, observed, limit);
    }

    private synchronized void releaseHop() {
        inFlightHops = Math.max(0, inFlightHops - 1);
    }

    private synchronized void releaseActor(RunnerActorCapacity.Permit runnerPermit) {
        liveActors = Math.max(0, liveActors - 1);
        runnerPermit.close();
    }

    static final class Hop implements AutoCloseable {
        private final ExecutionBudget budget;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Hop(ExecutionBudget budget) { this.budget = budget; }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) budget.releaseHop();
        }
    }

    static final class Actor implements AutoCloseable {
        private final ExecutionBudget budget;
        private final RunnerActorCapacity.Permit runnerPermit;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Actor(ExecutionBudget budget, RunnerActorCapacity.Permit runnerPermit) {
            this.budget = budget;
            this.runnerPermit = runnerPermit;
        }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) budget.releaseActor(runnerPermit);
        }
    }
}
