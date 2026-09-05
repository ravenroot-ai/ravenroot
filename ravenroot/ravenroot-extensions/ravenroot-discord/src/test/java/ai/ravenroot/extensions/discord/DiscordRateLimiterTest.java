package ai.ravenroot.extensions.discord;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class DiscordRateLimiterTest {
    @Test void partitionsFiniteWindowsByProfile() {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC);
        DiscordRateLimiter limiter = new DiscordRateLimiter(clock);
        assertTrue(limiter.allow("tenant-a\u0000profile-a", 2)); assertTrue(limiter.allow("tenant-a\u0000profile-a", 2));
        assertFalse(limiter.allow("tenant-a\u0000profile-a", 2)); assertTrue(limiter.allow("tenant-a\u0000profile-b", 2));
    }
}
