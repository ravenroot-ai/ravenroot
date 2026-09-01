package ai.ravenroot.extensions.telegram;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/** Shared bounded admission, rate and callback-deduplication controls for every Telegram action. */
class TelegramRuntimeControls {
    private static final Executor VIRTUAL_EXECUTOR = task ->
            Thread.ofVirtual().name("ravenroot-telegram-", 0).start(task);
    static final TelegramRuntimeControls PRODUCTION =
            new TelegramRuntimeControls(System::nanoTime, VIRTUAL_EXECUTOR, 32, 16, 4_096, 4_096);

    final Semaphore global;
    final int tenantLimit;
    final ConcurrentHashMap<String, Gate> tenants = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, Gate> profiles = new ConcurrentHashMap<>();
    final TelegramRateLimiter rates;
    final CallbackRegistry callbacks;
    final Executor executor;

    TelegramRuntimeControls(LongSupplier ticker, Executor executor, int globalLimit, int tenantLimit,
                            int maximumRateKeys, int maximumCallbackKeys) {
        if (globalLimit < 1 || tenantLimit < 1)
            throw new IllegalArgumentException("admission limits must be positive");
        this.global = new Semaphore(globalLimit, true);
        this.tenantLimit = tenantLimit;
        this.rates = new TelegramRateLimiter(ticker, maximumRateKeys);
        this.callbacks = new CallbackRegistry(ticker, maximumCallbackKeys);
        this.executor = java.util.Objects.requireNonNull(executor);
    }

    Admission acquire(String tenantId, TelegramProfile profile, int actionLimit,
                      ConcurrentHashMap<String, Gate> actions) {
        GateLease tenant = lease(tenants, tenantId, tenantLimit);
        GateLease profileLease = lease(profiles, tenantId + "\0" + profile.name(), profile.maxConcurrency());
        GateLease action = lease(actions, tenantId, actionLimit);
        Admission admission = new Admission(this, tenant, profileLease, action);
        if (!(admission.global = global.tryAcquire()) || !(admission.tenantHeld = tenant.gate.slots.tryAcquire())
                || !(admission.profileHeld = profileLease.gate.slots.tryAcquire())
                || !(admission.actionHeld = action.gate.slots.tryAcquire())) admission.release();
        return admission;
    }

    private static GateLease lease(ConcurrentHashMap<String, Gate> gates, String key, int permits) {
        Gate gate = gates.compute(key, (ignored, existing) -> existing == null ? new Gate(permits) : existing.retain());
        return new GateLease(gates, key, gate);
    }

    enum CallbackReservation { ACCEPTED, DUPLICATE, FULL }

    static final class CallbackRegistry {
        private static final long RETAIN_NANOS = Duration.ofHours(1).toNanos();
        private final LongSupplier ticker;
        private final int maximumKeys;
        private final LinkedHashMap<CallbackKey, Long> reservations = new LinkedHashMap<>();

        CallbackRegistry(LongSupplier ticker, int maximumKeys) {
            this.ticker = java.util.Objects.requireNonNull(ticker);
            if (maximumKeys < 1) throw new IllegalArgumentException("maximumCallbackKeys must be positive");
            this.maximumKeys = maximumKeys;
        }

        synchronized CallbackReservation reserve(String tenant, String profile, String callbackId) {
            CallbackKey key = new CallbackKey(tenant, profile, callbackId);
            long now = ticker.getAsLong();
            Iterator<java.util.Map.Entry<CallbackKey, Long>> iterator = reservations.entrySet().iterator();
            while (iterator.hasNext()) {
                long elapsed = elapsed(now, iterator.next().getValue());
                if (elapsed >= RETAIN_NANOS) iterator.remove();
            }
            if (reservations.containsKey(key)) return CallbackReservation.DUPLICATE;
            if (reservations.size() >= maximumKeys) return CallbackReservation.FULL;
            reservations.put(key, now);
            return CallbackReservation.ACCEPTED;
        }

        synchronized int size() { return reservations.size(); }

        private static long elapsed(long now, long before) {
            final long elapsed;
            try { elapsed = Math.subtractExact(now, before); }
            catch (ArithmeticException overflow) { return -1; }
            return elapsed < 0 ? -1 : elapsed;
        }

        private record CallbackKey(String tenant, String profile, String callbackId) { }
    }

    static final class Admission {
        private final TelegramRuntimeControls controls;
        private final GateLease tenant;
        private final GateLease profile;
        private final GateLease action;
        private boolean global;
        private boolean tenantHeld;
        private boolean profileHeld;
        private boolean actionHeld;

        Admission(TelegramRuntimeControls controls, GateLease tenant, GateLease profile, GateLease action) {
            this.controls = controls;
            this.tenant = tenant;
            this.profile = profile;
            this.action = action;
        }

        boolean acquired() { return global && tenantHeld && profileHeld && actionHeld; }

        void release() {
            if (actionHeld) { action.gate.slots.release(); actionHeld = false; }
            if (profileHeld) { profile.gate.slots.release(); profileHeld = false; }
            if (tenantHeld) { tenant.gate.slots.release(); tenantHeld = false; }
            if (global) { controls.global.release(); global = false; }
            action.close();
            profile.close();
            tenant.close();
        }
    }

    static final class Gate {
        final Semaphore slots;
        final AtomicInteger references = new AtomicInteger(1);
        Gate(int permits) { slots = new Semaphore(permits, true); }
        Gate retain() { references.incrementAndGet(); return this; }
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

        void close() {
            if (!closed) {
                closed = true;
                map.computeIfPresent(key, (ignored, current) -> current == gate
                        && current.references.decrementAndGet() == 0 ? null : current);
            }
        }
    }
}
