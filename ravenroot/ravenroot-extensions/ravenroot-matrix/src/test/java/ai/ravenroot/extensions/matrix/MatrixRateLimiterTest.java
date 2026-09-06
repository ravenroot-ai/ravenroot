package ai.ravenroot.extensions.matrix;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class MatrixRateLimiterTest {
    @Test void fixedWindowIsPerKey() {
        MutableClock clock = new MutableClock(); MatrixRateLimiter limiter = new MatrixRateLimiter(clock);
        assertTrue(limiter.allow("a", 1)); assertFalse(limiter.allow("a", 1)); assertTrue(limiter.allow("b", 1));
        clock.now = clock.now.plusSeconds(1); assertTrue(limiter.allow("a", 1));
    }
    private static final class MutableClock extends Clock {
        Instant now = MatrixTestSupport.NOW;
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
