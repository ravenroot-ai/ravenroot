package ai.ravenroot.core.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Monotonic traversal budget shared by every asynchronous branch and cycle re-entry. */
final class ExecutionBudget {
    private final GraphExecutionLimits limits;
    private long traversalSteps;
    private long amplifiedDeliveries;
    private long payloadBytes;
    private int inFlightHops;
    private int liveActors;

    ExecutionBudget(GraphExecutionLimits limits) {
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
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

    synchronized Actor reserveActor() {
        long next = (long) liveActors + 1;
        require(GraphExecutionLimitException.Reason.LIVE_ACTORS, next, limits.maxLiveActorsPerTraversal());
        liveActors++;
        return new Actor(this);
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

    private synchronized void releaseActor() {
        liveActors = Math.max(0, liveActors - 1);
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
        private final AtomicBoolean closed = new AtomicBoolean();
        private Actor(ExecutionBudget budget) { this.budget = budget; }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) budget.releaseActor();
        }
    }
}
