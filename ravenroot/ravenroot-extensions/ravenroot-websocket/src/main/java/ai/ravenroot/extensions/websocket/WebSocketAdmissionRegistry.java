package ai.ravenroot.extensions.websocket;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Linearizable process-local admission shared by send actions and receive sources. */
final class WebSocketAdmissionRegistry {
    private static final WebSocketAdmissionRegistry GLOBAL = new WebSocketAdmissionRegistry();

    private final ConcurrentHashMap<Key, Gate> gates = new ConcurrentHashMap<>();

    static WebSocketAdmissionRegistry global() {
        return GLOBAL;
    }

    Lease tryAcquire(String tenantId, String profileName, int maximum) {
        Key key = new Key(tenantId, profileName);
        Gate gate = gates.compute(key, (ignored, current) -> current == null
                ? new Gate(maximum) : current.retain(maximum));
        if (!gate.tryAcquire(maximum)) {
            releaseReference(key, gate);
            return null;
        }
        return new Lease(() -> {
            gate.releasePermit();
            releaseReference(key, gate);
        });
    }

    int size() {
        return gates.size();
    }

    private void releaseReference(Key key, Gate gate) {
        gates.compute(key, (ignored, current) -> {
            if (current != gate) throw new IllegalStateException("WebSocket admission gate identity lost");
            return gate.releaseReference() ? null : gate;
        });
    }

    private record Key(String tenantId, String profileName) {
        private Key {
            if (tenantId == null || tenantId.isBlank() || profileName == null || profileName.isBlank()) {
                throw WebSocketException.of(WebSocketException.Code.CONFIGURATION);
            }
        }
    }

    private static final class Gate {
        private int references = 1;
        private int active;

        private Gate(int maximum) {
            if (maximum < 1) throw WebSocketException.of(WebSocketException.Code.CONFIGURATION);
        }

        private synchronized Gate retain(int expectedMaximum) {
            if (expectedMaximum < 1) throw WebSocketException.of(WebSocketException.Code.CONFIGURATION);
            references++;
            return this;
        }

        private synchronized boolean tryAcquire(int maximum) {
            if (maximum < 1) throw WebSocketException.of(WebSocketException.Code.CONFIGURATION);
            if (active >= maximum) return false;
            active++;
            return true;
        }

        private synchronized void releasePermit() {
            if (active < 1) throw new IllegalStateException("WebSocket admission permit underflow");
            active--;
        }

        private synchronized boolean releaseReference() {
            return --references == 0;
        }
    }

    static final class Lease implements AutoCloseable {
        private final Runnable release;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(Runnable release) {
            this.release = release;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) release.run();
        }
    }
}
