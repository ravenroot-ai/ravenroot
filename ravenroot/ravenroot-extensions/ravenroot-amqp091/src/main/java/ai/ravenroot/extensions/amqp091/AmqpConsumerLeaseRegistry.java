package ai.ravenroot.extensions.amqp091;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Linearizable process-local shared/exclusive ownership for one authorized queue. */
final class AmqpConsumerLeaseRegistry {
    enum Mode { SHARED, EXCLUSIVE }

    private final ConcurrentHashMap<String, State> resources = new ConcurrentHashMap<>();

    Lease tryAcquire(String resource, Mode mode) {
        Object token = new Object();
        AtomicBoolean acquired = new AtomicBoolean();
        resources.compute(resource, (ignored, current) -> {
            State state = current == null ? State.empty() : current;
            State next = state.acquire(mode, token);
            if (next == null) return current;
            acquired.set(true);
            return next;
        });
        return acquired.get() ? new Lease(this, resource, mode, token) : null;
    }

    private void release(String resource, Mode mode, Object token) {
        resources.computeIfPresent(resource, (ignored, current) -> current.release(mode, token));
    }

    int activeResources() { return resources.size(); }
    int activeLeases() { return resources.values().stream().mapToInt(State::holders).sum(); }

    private record State(Set<Object> shared, Object exclusive) {
        private State {
            shared = Set.copyOf(shared);
            if (exclusive != null && !shared.isEmpty()) throw new IllegalArgumentException("mixed lease state");
        }

        static State empty() { return new State(Set.of(), null); }

        State acquire(Mode mode, Object token) {
            if (mode == Mode.EXCLUSIVE) {
                return exclusive == null && shared.isEmpty() ? new State(Set.of(), token) : null;
            }
            if (exclusive != null) return null;
            LinkedHashSet<Object> next = new LinkedHashSet<>(shared);
            next.add(token);
            return new State(next, null);
        }

        State release(Mode mode, Object token) {
            if (mode == Mode.EXCLUSIVE) return exclusive == token ? null : this;
            if (!shared.contains(token)) return this;
            LinkedHashSet<Object> next = new LinkedHashSet<>(shared);
            next.remove(token);
            return next.isEmpty() ? null : new State(next, null);
        }

        int holders() { return exclusive == null ? shared.size() : 1; }
    }

    static final class Lease implements AutoCloseable {
        private final AmqpConsumerLeaseRegistry owner;
        private final String resource;
        private final Mode mode;
        private final Object token;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(AmqpConsumerLeaseRegistry owner, String resource, Mode mode, Object token) {
            this.owner = owner;
            this.resource = resource;
            this.mode = mode;
            this.token = token;
        }

        @Override public void close() {
            if (closed.compareAndSet(false, true)) owner.release(resource, mode, token);
        }
    }
}
