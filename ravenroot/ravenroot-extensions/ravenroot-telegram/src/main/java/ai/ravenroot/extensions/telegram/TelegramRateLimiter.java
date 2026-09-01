package ai.ravenroot.extensions.telegram;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Exact rolling-window limiter scoped by tenant and bot profile.
 *
 * <p>A key may burst up to {@code limit} calls at once. Each accepted call occupies one slot until
 * exactly one monotonic second has elapsed from that call; there are no calendar buckets. A negative,
 * overflowing, or implausibly large single clock step is treated as a discontinuity: the current call
 * is rejected and a full synthetic burst is placed at the greatest safe epoch, so a clock anomaly can
 * never create capacity. Stable elapsed time restores those slots after one full second. Sampling is
 * guarded by a per-window observation token; a delayed sample that loses its token must resample and
 * cannot erase a newer acceptance or retire its live window.</p>
 */
final class TelegramRateLimiter {
    static final long WINDOW_NANOS = Duration.ofSeconds(1).toNanos();
    static final long EVICT_AFTER_NANOS = Duration.ofMinutes(5).toNanos();
    static final long MAX_TRUSTED_STEP_NANOS = Duration.ofHours(1).toNanos();

    private final ConcurrentHashMap<Key, Window> windows = new ConcurrentHashMap<>();
    private final LongSupplier ticker;
    private final int maximumKeys;
    private final Object creationLock = new Object();

    TelegramRateLimiter(LongSupplier ticker, int maximumKeys) {
        this.ticker = java.util.Objects.requireNonNull(ticker, "ticker");
        if (maximumKeys < 1) throw new IllegalArgumentException("maximumKeys must be positive");
        this.maximumKeys = maximumKeys;
    }

    boolean allow(String tenant, String profile, int limit) {
        if (limit < 1) return false;
        Key key = new Key(java.util.Objects.requireNonNull(tenant), java.util.Objects.requireNonNull(profile));
        Window window = windows.get(key);
        if (window == null) {
            synchronized (creationLock) {
                window = windows.get(key);
                if (window == null) {
                    if (windows.size() >= maximumKeys)
                        windows.entrySet().removeIf(entry -> entry.getValue().retireIfExpired(ticker));
                    if (windows.size() >= maximumKeys) return false;
                    window = new Window();
                    windows.put(key, window);
                }
            }
        }
        while (true) {
            Object observation = window.beginAllowObservation();
            if (observation == null) return false;
            final long now;
            try { now = ticker.getAsLong(); }
            catch (RuntimeException | Error failure) {
                window.cancelAllowObservation();
                throw failure;
            }
            Attempt result = window.allow(limit, now, observation);
            if (result != Attempt.RETRY) return result == Attempt.ALLOWED;
        }
    }

    int size() { return windows.size(); }

    List<Long> acceptedTimestamps(String tenant, String profile) {
        Window window = windows.get(new Key(tenant, profile));
        return window == null ? List.of() : window.acceptedTimestamps();
    }

    private static final long BACKWARD = -1;
    private static final long TOO_LARGE = -2;
    private static final long OVERFLOW = -3;

    private static long elapsed(long now, long before, long maximum) {
        final long elapsed;
        try { elapsed = Math.subtractExact(now, before); }
        catch (ArithmeticException overflow) { return OVERFLOW; }
        if (elapsed < 0) return BACKWARD;
        return elapsed > maximum ? TOO_LARGE : elapsed;
    }

    private record Key(String tenant, String profile) { }
    private enum Attempt { ALLOWED, REJECTED, RETRY }

    private static final class Window {
        private static final Object RETIRED_WINDOW = new Object();
        private final ArrayDeque<Long> accepted = new ArrayDeque<>();
        private boolean initialized;
        private boolean retired;
        private long lastObserved;
        private Object observation = new Object();
        private int activeAllows;

        synchronized Object beginAllowObservation() {
            if (retired) return null;
            activeAllows++;
            return observation;
        }

        synchronized void cancelAllowObservation() { activeAllows--; }

        synchronized Attempt allow(int limit, long now, Object expectedObservation) {
            activeAllows--;
            if (retired) return Attempt.REJECTED;
            if (observation != expectedObservation) return Attempt.RETRY;
            if (!initialized) {
                initialized = true;
                lastObserved = now;
            } else {
                long step = elapsed(now, lastObserved, MAX_TRUSTED_STEP_NANOS);
                if (step < 0) {
                    quarantine(limit, now, step);
                    advanceObservation();
                    return Attempt.REJECTED;
                }
                lastObserved = now;
            }
            while (!accepted.isEmpty()) {
                long age = elapsed(now, accepted.getFirst(), MAX_TRUSTED_STEP_NANOS);
                if (age < 0) {
                    quarantine(limit, now, age);
                    advanceObservation();
                    return Attempt.REJECTED;
                }
                if (age < WINDOW_NANOS) break;
                accepted.removeFirst();
            }
            Attempt result = Attempt.REJECTED;
            if (accepted.size() < limit) {
                accepted.addLast(now);
                result = Attempt.ALLOWED;
            }
            advanceObservation();
            return result;
        }

        boolean retireIfExpired(LongSupplier ticker) {
            Object expectedObservation = beginRetirementObservation();
            if (expectedObservation == RETIRED_WINDOW) return true;
            if (expectedObservation == null) return false;
            long now = ticker.getAsLong();
            synchronized (this) {
                if (retired) return true;
                if (activeAllows != 0 || observation != expectedObservation) return false;
                if (!initialized) {
                    retire();
                    return true;
                }
                long idle = elapsed(now, lastObserved, MAX_TRUSTED_STEP_NANOS);
                if (idle < EVICT_AFTER_NANOS) return false;
                retire();
                return true;
            }
        }

        private synchronized Object beginRetirementObservation() {
            if (retired) return RETIRED_WINDOW;
            return activeAllows == 0 ? observation : null;
        }

        synchronized List<Long> acceptedTimestamps() { return List.copyOf(accepted); }

        private void quarantine(int limit, long now, long discontinuity) {
            if (discontinuity == BACKWARD) {
                accepted.clear();
                while (accepted.size() < limit) accepted.addLast(lastObserved);
                return;
            }
            accepted.clear();
            while (accepted.size() < limit) accepted.addLast(now);
            initialized = true;
            lastObserved = now;
        }

        private void retire() { retired = true; advanceObservation(); }
        private void advanceObservation() { observation = new Object(); }
    }
}
