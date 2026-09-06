package ai.ravenroot.extensions.mattermost;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/** Profile and channel fixed-window limiter with bounded provider backoff. */
final class MattermostRateLimiter {
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> providerBlocks = new ConcurrentHashMap<>();
    MattermostRateLimiter(Clock clock) { this.clock = java.util.Objects.requireNonNull(clock); }
    boolean allow(String key, int maximumPerSecond) {
        long now = clock.millis(); Long blocked = providerBlocks.get(key);
        if (blocked != null && blocked > now) return false;
        if (blocked != null) providerBlocks.remove(key, blocked);
        return windows.computeIfAbsent(key, ignored -> new Window()).allow(now / 1_000, maximumPerSecond);
    }
    void blockFor(String key, long milliseconds) {
        if (milliseconds < 1) return;
        long now = clock.millis(), delay = Math.min(milliseconds, 300_000);
        providerBlocks.merge(key, now > Long.MAX_VALUE - delay ? Long.MAX_VALUE : now + delay, Math::max);
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
