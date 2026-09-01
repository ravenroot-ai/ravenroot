package ai.ravenroot.server.embed;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The acknowledgement phase of the embed session, and its two currency re-checks.
 *
 * <h2>Why this class exists at all</h2>
 * <p>{@link EmbedBrowserSessionAuthority} re-checks {@code isCurrent} at five points, and until this
 * suite it had no test file of its own: it was exercised sideways from
 * {@code EmbedLaunchTicketAuthorityTest}, whose subject is the launch ticket. Three of the five
 * re-checks were covered by that arrangement — {@code pending}, {@code activate}, {@code resolve} —
 * and the two inside {@code acknowledge} were not. Removing either survived the whole suite green.
 *
 * <p>The gap had a precise cause worth recording, because it is the kind that recurs: <b>no test
 * anywhere called {@code acknowledge} while the registration was not current at that moment.</b>
 * Every revocation test restored currency before acknowledging and then drove the non-current path
 * through the other three methods. The controls were correct by reading and unproved by execution,
 * which leaves the controls asserted but unproved. Revocation must be monotone and immediately visible
 * to ticket, ACK, exchange and bearer, including create-session, ACK S2S and projection.
 *
 * <p>A file per security-relevant collaborator provides the structure. The launch-ticket suite keeps
 * the tests it already had rather than being split for tidiness — moving them would churn code that
 * covers what it says it covers — but the acknowledgement guarantees live here, where someone
 * looking for "what does acknowledge promise" will look.</p>
 */
class EmbedBrowserSessionAuthorityTest {

    /**
     * The registration is already gone when the acknowledgement arrives.
     *
     * <p>The entry re-check is what must stop it. Nothing else in the guard can: the acknowledgement
     * id, channel and correlation are all well-formed and the captured aggregate still equals the one
     * the exchange was begun with — that is exactly the situation of a revocation that landed between
     * launch and the embedding application's server-to-server call.</p>
     */
    @Test
    void anAcknowledgementIsRefusedWhenTheRegistrationWasRevokedBeforeItArrived() {
        var clock = new MutableClock(EmbedSessionFixtures.AT);
        var registration = EmbedSessionFixtures.registration(1);
        var registrations = new MutableEmbedRegistrations(registration);
        var sessions = new EmbedBrowserSessionAuthority(clock, Duration.ofSeconds(30),
                Duration.ofSeconds(30), 4, tokens());
        var bootstrap = sessions.begin(registration);
        var audited = new AtomicInteger();

        registrations.withdraw();

        assertFalse(sessions.acknowledge(bootstrap.acknowledgementId(), bootstrap.channelId(),
                        "correlation", registration, registrations, audited::incrementAndGet),
                "a revoked registration must not be acknowledgeable");
        assertEquals(0, audited.get(),
                "and the refusal must come before the audit callback, not after it");

        // Restored only so the state can be read back: the acknowledgement must not have been
        // recorded, and the pending exchange must be exactly as begin() left it.
        registrations.current(registration);
        assertNull(sessions.acknowledged(bootstrap.exchangeId(), registrations));
        assertNotNull(sessions.pending(bootstrap.exchangeId(), registrations));
    }

    /**
     * The revocation lands <em>inside</em> the acknowledgement.
     *
     * <p>The registration is current on entry, so the first re-check passes and the pending exchange
     * is optimistically marked acknowledged. The audit callback is the seam where a real revocation
     * can land — it is where {@code acknowledge} gives up its monitor-held certainty for the duration
     * of an I/O call — so the double revokes from inside it. The second re-check must refuse.
     *
     * <p><b>The rollback is half of the guarantee.</b> A refusal that leaves the exchange marked
     * acknowledged has burned it: the operation did not happen, but it cannot be retried either, so a
     * transient revocation-and-reinstatement would strand a session that should have recovered. The
     * final acknowledgement below is what distinguishes a real rollback from a refusal that merely
     * looks like one — a mutant that refuses without restoring the state fails on it.</p>
     */
    @Test
    void aRevocationBetweenTheAuditWriteAndTheCommitRefusesAndRollsTheExchangeBack() {
        var clock = new MutableClock(EmbedSessionFixtures.AT);
        var registration = EmbedSessionFixtures.registration(1);
        var registrations = new MutableEmbedRegistrations(registration);
        var sessions = new EmbedBrowserSessionAuthority(clock, Duration.ofSeconds(30),
                Duration.ofSeconds(30), 4, tokens());
        var bootstrap = sessions.begin(registration);
        var audited = new AtomicInteger();

        boolean acknowledged = sessions.acknowledge(bootstrap.acknowledgementId(),
                bootstrap.channelId(), "correlation", registration, registrations, () -> {
                    audited.incrementAndGet();
                    // The window the second re-check exists to close.
                    registrations.withdraw();
                });

        assertFalse(acknowledged,
                "a revocation that lands between the audit write and the commit must fail closed");
        assertEquals(1, audited.get(), "the audit callback did run; the refusal is after it");

        registrations.current(registration);
        assertNull(sessions.acknowledged(bootstrap.exchangeId(), registrations),
                "the optimistic acknowledgement must have been rolled back");
        assertNotNull(sessions.pending(bootstrap.exchangeId(), registrations),
                "and the pending exchange must still exist");

        // The decisive half: the rolled-back state is usable, not merely present.
        assertTrue(sessions.acknowledge(bootstrap.acknowledgementId(), bootstrap.channelId(),
                        "correlation", registration, registrations, audited::incrementAndGet),
                "a refused acknowledgement must not consume the exchange it refused");
        assertEquals("correlation",
                sessions.acknowledged(bootstrap.exchangeId(), registrations).ackCorrelationId());
    }

    /** The positive control, so the two refusals above cannot be passing for a trivial reason. */
    @Test
    void aCurrentRegistrationIsAcknowledgedExactlyOnce() {
        var clock = new MutableClock(EmbedSessionFixtures.AT);
        var registration = EmbedSessionFixtures.registration(1);
        var registrations = new MutableEmbedRegistrations(registration);
        var sessions = new EmbedBrowserSessionAuthority(clock, Duration.ofSeconds(30),
                Duration.ofSeconds(30), 4, tokens());
        var bootstrap = sessions.begin(registration);
        var audited = new AtomicInteger();

        assertTrue(sessions.acknowledge(bootstrap.acknowledgementId(), bootstrap.channelId(),
                "correlation", registration, registrations, audited::incrementAndGet));
        assertFalse(sessions.acknowledge(bootstrap.acknowledgementId(), bootstrap.channelId(),
                        "correlation", registration, registrations, audited::incrementAndGet),
                "one use only");
        assertEquals(1, audited.get());
    }

    /**
     * Replacing the registration with a newer revision is a revocation of the captured one.
     *
     * <p>Same control as the first test, different cause, and the one an operator actually reaches
     * for more often: re-provisioning to point an embed at a new snapshot.</p>
     */
    @Test
    void aReplacedRevisionIsAlsoRefusedAtTheAcknowledgement() {
        var clock = new MutableClock(EmbedSessionFixtures.AT);
        var registration = EmbedSessionFixtures.registration(1);
        var registrations = new MutableEmbedRegistrations(registration);
        var sessions = new EmbedBrowserSessionAuthority(clock, Duration.ofSeconds(30),
                Duration.ofSeconds(30), 4, tokens());
        var bootstrap = sessions.begin(registration);

        registrations.current(EmbedSessionFixtures.registration(2));

        assertFalse(sessions.acknowledge(bootstrap.acknowledgementId(), bootstrap.channelId(),
                "correlation", registration, registrations, () -> { }));
    }

    /** Deterministic opaque values, so a failure is never a collision. */
    private static java.util.function.Supplier<String> tokens() {
        var values = new ArrayDeque<>(List.of(token('a'), token('b'), token('c'), token('d'),
                token('e'), token('f'), token('g'), token('h')));
        return values::remove;
    }

    private static String token(char value) {
        return String.valueOf(value).repeat(43);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return instant;
        }
    }
}
