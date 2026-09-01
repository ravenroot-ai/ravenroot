package ai.ravenroot.testkit.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Lets the suite drive lease expiry and timer due-ness deterministically instead of sleeping.
 *
 * <p>ADR 0010 sections 4 and 7 are explicit that the store is its own clock authority and that
 * expiry and due-ness are evaluated against <em>its</em> clock, never the caller's. A contract suite
 * that used {@code Thread.sleep} to cross a TTL would be racy by construction and would occasionally
 * pass for the wrong reason under load. Handing every adapter under test the same controllable clock
 * removes that source of flakiness entirely.</p>
 */
public final class MutableClock extends Clock {
    private volatile Instant now;
    private final ZoneId zone;

    public MutableClock(Instant initial) {
        this(initial, ZoneOffset.UTC);
    }

    private MutableClock(Instant initial, ZoneId zone) {
        this.now = initial;
        this.zone = zone;
    }

    public void advance(Duration amount) {
        now = now.plus(amount);
    }

    public void set(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId requestedZone) {
        return new MutableClock(now, requestedZone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
