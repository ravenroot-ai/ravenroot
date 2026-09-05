package ai.ravenroot.core.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

/** Runner-scoped capacity shared by actors that belong to different traversals. */
final class RunnerActorCapacity {
    private final int limit;
    private int retained;

    RunnerActorCapacity(int limit) {
        if (limit < 1) throw new IllegalArgumentException("actor capacity must be positive");
        this.limit = limit;
    }

    synchronized Permit reserve() {
        long next = (long) retained + 1;
        if (next > limit) {
            throw new GraphExecutionLimitException(GraphExecutionLimitException.Reason.LIVE_ACTORS,
                    next, limit);
        }
        retained++;
        return new Permit(this);
    }

    synchronized int retained() {
        return retained;
    }

    private synchronized void release() {
        retained = Math.max(0, retained - 1);
    }

    static final class Permit implements AutoCloseable {
        private final RunnerActorCapacity capacity;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(RunnerActorCapacity capacity) {
            this.capacity = capacity;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) capacity.release();
        }
    }
}
