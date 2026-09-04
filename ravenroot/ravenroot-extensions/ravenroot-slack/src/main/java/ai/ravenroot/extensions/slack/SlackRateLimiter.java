package ai.ravenroot.extensions.slack;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/** Small profile-bounded fixed-window limiter; provider headers remain authoritative too. */
final class SlackRateLimiter {
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> providerBlocks = new ConcurrentHashMap<>();
    SlackRateLimiter(Clock clock) { this.clock = java.util.Objects.requireNonNull(clock); }

    boolean allow(String key, int maximumPerSecond) {
        long now = clock.millis();
        Long blockedUntil = providerBlocks.get(key);
        if (blockedUntil != null && blockedUntil > now) return false;
        if (blockedUntil != null) providerBlocks.remove(key, blockedUntil);
        long second = now / 1_000;
        return windows.computeIfAbsent(key, ignored -> new Window()).allow(second, maximumPerSecond);
    }

    void blockFor(String key, long milliseconds) {
        if (milliseconds < 1) return;
        long now = clock.millis();
        long delay = Math.min(milliseconds, 300_000);
        long until = now > Long.MAX_VALUE - delay ? Long.MAX_VALUE : now + delay;
        providerBlocks.merge(key, until, Math::max);
    }

    private static final class Window {
        private long second = Long.MIN_VALUE; private int count;
        synchronized boolean allow(long now, int maximum) {
            if (second != now) { second = now; count = 0; }
            if (count >= maximum) return false;
            count++; return true;
        }
    }
}
