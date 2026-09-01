package ai.ravenroot.extensions.kafka;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/** Shared bounded global, tenant, profile, action and rate admission. */
final class KafkaRuntimeControls {
    private static final Executor VIRTUAL_CLEANUP = task ->
            Thread.ofVirtual().name("ravenroot-kafka-cleanup-", 0).start(task);
    static final KafkaRuntimeControls PRODUCTION = new KafkaRuntimeControls(System::nanoTime,
            task -> Thread.ofVirtual().name("ravenroot-kafka-", 0).start(task), VIRTUAL_CLEANUP,
            32, 16, 4_096);

    final LongSupplier ticker;
    final Executor executor;
    final Executor cleanupExecutor;
    final Semaphore global;
    final int tenantLimit;
    final ConcurrentHashMap<String, Gate> tenants = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, Gate> profiles = new ConcurrentHashMap<>();
    final Set<Object> pendingCleanups = ConcurrentHashMap.newKeySet();
    private final Object cleanupMonitor = new Object();
    final RateLimiter rates;

    KafkaRuntimeControls(LongSupplier ticker, Executor executor, int globalLimit, int tenantLimit,
                        int maximumRateKeys) {
        this(ticker, executor, VIRTUAL_CLEANUP, globalLimit, tenantLimit, maximumRateKeys);
    }

    KafkaRuntimeControls(LongSupplier ticker, Executor executor, Executor cleanupExecutor,
                         int globalLimit, int tenantLimit, int maximumRateKeys) {
        if (globalLimit < 1 || tenantLimit < 1 || maximumRateKeys < 1)
            throw new IllegalArgumentException("Kafka runtime limits must be positive");
        this.ticker = java.util.Objects.requireNonNull(ticker);
        this.executor = java.util.Objects.requireNonNull(executor);
        this.cleanupExecutor = java.util.Objects.requireNonNull(cleanupExecutor);
        this.global = new Semaphore(globalLimit, true);
        this.tenantLimit = tenantLimit;
        this.rates = new RateLimiter(ticker, maximumRateKeys);
    }

    void trackCleanup(Object cleanup) { synchronized (cleanupMonitor) { pendingCleanups.add(cleanup); } }
    void finishCleanup(Object cleanup) {
        synchronized (cleanupMonitor) { pendingCleanups.remove(cleanup); cleanupMonitor.notifyAll(); }
    }
    boolean awaitNoPendingCleanups(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (cleanupMonitor) {
            while (!pendingCleanups.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(cleanupMonitor, remaining);
            }
            return true;
        }
    }

    Admission acquire(String tenantId, KafkaProfile profile, int actionLimit,
                      ConcurrentHashMap<String, Gate> actions) {
        GateLease tenant = lease(tenants, tenantId, tenantLimit);
        GateLease profileLease = lease(profiles, tenantId + "\0" + profile.name(), profile.maxConcurrency());
        GateLease action = lease(actions, tenantId, actionLimit);
        Admission admission = new Admission(this, tenant, profileLease, action);
        if (!(admission.globalHeld = global.tryAcquire())
                || !(admission.tenantHeld = tenant.gate.slots.tryAcquire())
                || !(admission.profileHeld = profileLease.gate.slots.tryAcquire())
                || !(admission.actionHeld = action.gate.slots.tryAcquire())) admission.release();
        return admission;
    }

    private static GateLease lease(ConcurrentHashMap<String, Gate> gates, String key, int permits) {
        Gate gate = gates.compute(key, (ignored, existing) -> existing == null ? new Gate(permits) : existing.retain());
        return new GateLease(gates, key, gate);
    }

    static final class RateLimiter {
        private static final long WINDOW_NANOS = Duration.ofSeconds(1).toNanos();
        private final LongSupplier ticker;
        private final int maximumKeys;
        private final LinkedHashMap<String, Window> windows = new LinkedHashMap<>();

        RateLimiter(LongSupplier ticker, int maximumKeys) {
            this.ticker = ticker;
            this.maximumKeys = maximumKeys;
        }

        synchronized boolean allow(String tenant, String profile, int maximum) {
            long now = ticker.getAsLong();
            Iterator<java.util.Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
            while (iterator.hasNext()) {
                Window window = iterator.next().getValue();
                if (elapsed(now, window.started) >= WINDOW_NANOS) iterator.remove();
            }
            String key = tenant + "\0" + profile;
            Window current = windows.get(key);
            if (current == null) {
                if (windows.size() >= maximumKeys) return false;
                windows.put(key, new Window(now, 1));
                return true;
            }
            long elapsed = elapsed(now, current.started);
            if (elapsed < 0) return false;
            if (elapsed >= WINDOW_NANOS) {
                windows.put(key, new Window(now, 1));
                return true;
            }
            if (current.count >= maximum) return false;
            windows.put(key, new Window(current.started, current.count + 1));
            return true;
        }

        synchronized int size() {
            return windows.size();
        }

        private static long elapsed(long now, long before) {
            try {
                long elapsed = Math.subtractExact(now, before);
                return elapsed < 0 ? -1 : elapsed;
            } catch (ArithmeticException overflow) {
                return -1;
            }
        }

        private record Window(long started, int count) { }
    }

    static final class Admission {
        private final KafkaRuntimeControls controls;
        private final GateLease tenant;
        private final GateLease profile;
        private final GateLease action;
        private boolean globalHeld;
        private boolean tenantHeld;
        private boolean profileHeld;
        private boolean actionHeld;
        private boolean released;

        Admission(KafkaRuntimeControls controls, GateLease tenant, GateLease profile, GateLease action) {
            this.controls = controls;
            this.tenant = tenant;
            this.profile = profile;
            this.action = action;
        }

        boolean acquired() {
            return globalHeld && tenantHeld && profileHeld && actionHeld;
        }

        synchronized void release() {
            if (released) return;
            released = true;
            if (actionHeld) action.gate.slots.release();
            if (profileHeld) profile.gate.slots.release();
            if (tenantHeld) tenant.gate.slots.release();
            if (globalHeld) controls.global.release();
            action.close();
            profile.close();
            tenant.close();
        }
    }

    static final class Gate {
        final Semaphore slots;
        final AtomicInteger references = new AtomicInteger(1);

        Gate(int permits) {
            slots = new Semaphore(permits, true);
        }

        Gate retain() {
            references.incrementAndGet();
            return this;
        }
    }

    private static final class GateLease {
        private final ConcurrentHashMap<String, Gate> map;
        private final String key;
        private final Gate gate;
        private boolean closed;

        GateLease(ConcurrentHashMap<String, Gate> map, String key, Gate gate) {
            this.map = map;
            this.key = key;
            this.gate = gate;
        }

        synchronized void close() {
            if (closed) return;
            closed = true;
            map.computeIfPresent(key, (ignored, current) -> current == gate
                    && current.references.decrementAndGet() == 0 ? null : current);
        }
    }
}
