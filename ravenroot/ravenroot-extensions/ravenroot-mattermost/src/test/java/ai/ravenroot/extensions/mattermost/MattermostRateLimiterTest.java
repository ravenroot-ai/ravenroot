package ai.ravenroot.extensions.mattermost;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class MattermostRateLimiterTest {
    @Test void enforcesFixedWindowAndProviderBackoff() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        MattermostRateLimiter limiter = new MattermostRateLimiter(clock);
        assertTrue(limiter.allow("profile", 2)); assertTrue(limiter.allow("profile", 2));
        assertFalse(limiter.allow("profile", 2)); clock.advanceMillis(1_000);
        assertTrue(limiter.allow("profile", 2)); limiter.blockFor("profile", 2_000);
        assertFalse(limiter.allow("profile", 2)); clock.advanceMillis(2_000);
        assertTrue(limiter.allow("profile", 2));
    }
    private static final class MutableClock extends Clock {
        private Instant instant; MutableClock(Instant instant) { this.instant = instant; }
        void advanceMillis(long milliseconds) { instant = instant.plusMillis(milliseconds); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
