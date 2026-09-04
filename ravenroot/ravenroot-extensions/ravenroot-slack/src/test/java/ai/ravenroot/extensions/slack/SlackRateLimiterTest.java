package ai.ravenroot.extensions.slack;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlackRateLimiterTest {
    @Test
    void enforcesFixedWindowAndProviderBackoff() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        SlackRateLimiter limiter = new SlackRateLimiter(clock);

        assertTrue(limiter.allow("workspace", 2));
        assertTrue(limiter.allow("workspace", 2));
        assertFalse(limiter.allow("workspace", 2));

        clock.advanceMillis(1_000);
        assertTrue(limiter.allow("workspace", 2));
        limiter.blockFor("workspace", 2_000);
        assertFalse(limiter.allow("workspace", 2));
        clock.advanceMillis(2_000);
        assertTrue(limiter.allow("workspace", 2));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) { this.instant = instant; }
        private void advanceMillis(long milliseconds) { instant = instant.plusMillis(milliseconds); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
