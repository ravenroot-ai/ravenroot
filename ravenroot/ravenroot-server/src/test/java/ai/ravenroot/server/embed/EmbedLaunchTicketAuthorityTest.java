package ai.ravenroot.server.embed;

import ai.ravenroot.api.embed.EmbedCapability;
import ai.ravenroot.api.embed.EmbedGraphProjection;
import ai.ravenroot.api.embed.EmbedProjectionEligibility;
import ai.ravenroot.api.embed.EmbedProvisionCommand;
import ai.ravenroot.api.embed.EmbedProvisionOutcome;
import ai.ravenroot.api.embed.EmbedRegistrationAggregate;
import ai.ravenroot.api.embed.EmbedRegistrationAuthority;
import ai.ravenroot.api.embed.EmbedRegistrationResolution;
import ai.ravenroot.api.embed.EmbedRevokeCommand;
import ai.ravenroot.api.embed.EmbedRevokeOutcome;
import ai.ravenroot.api.embed.EmbedSnapshotLifecycle;
import ai.ravenroot.api.embed.VerifiedEmbedGraphGrant;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launch ticket, the browser session store and the P-256 proof verifier.
 *
 * <p>The acknowledgement phase's own guarantees live in {@link EmbedBrowserSessionAuthorityTest},
 * added when its two currency re-checks turned out to be unexercised from here. This suite keeps the
 * session tests it already had: they cover what they say they cover, and moving them for tidiness
 * would churn working code without changing a line of coverage.</p>
 */
class EmbedLaunchTicketAuthorityTest {
    @Test
    void serverMintedTicketCarriesTheImmutableGrantAndOnlyOneConcurrentConsumeWins() throws Exception {
        var registration = EmbedSessionFixtures.registration(1);
        var registrations = new MutableEmbedRegistrations(registration);
        var authority = new EmbedLaunchTicketAuthority(Clock.systemUTC(), Duration.ofSeconds(30));
        var issued = authority.issue(registration);
        assertTrue(issued.value().length() >= 43);
        assertFalse(issued.value().contains("reg"));

        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> authority.consume(issued.value(), registrations));
            var two = pool.submit(() -> authority.consume(issued.value(), registrations));
            var first = one.get();
            var second = two.get();
            assertNotEquals(first.getClass(), second.getClass());
            var available = first instanceof EmbedLaunchTicketAuthority.Resolution.Available value
                    ? value : (EmbedLaunchTicketAuthority.Resolution.Available) second;
            assertEquals(registration, available.registration());
        }
        assertInstanceOf(EmbedLaunchTicketAuthority.Resolution.Unavailable.class,
                authority.consume(issued.value(), registrations));
        assertEquals(0, authority.retainedEntries());
    }

    @Test
    void revocationOrRevisionReplacementInvalidatesAnOtherwiseLiveTicket() {
        var first = EmbedSessionFixtures.registration(1);
        var registrations = new MutableEmbedRegistrations(first);
        var authority = new EmbedLaunchTicketAuthority(Clock.systemUTC(), Duration.ofMinutes(1));
        var revoked = authority.issue(first);
        registrations.withdraw();
        assertInstanceOf(EmbedLaunchTicketAuthority.Resolution.Unavailable.class,
                authority.consume(revoked.value(), registrations));

        registrations.current(first);
        var replaced = authority.issue(first);
        registrations.current(EmbedSessionFixtures.registration(2));
        assertInstanceOf(EmbedLaunchTicketAuthority.Resolution.Unavailable.class,
                authority.consume(replaced.value(), registrations));
    }

    @Test
    void capacityIsHardBoundedAndExpiredEntriesAreCleanedBeforeIssuance() {
        var clock = new MutableClock(Instant.parse("2026-08-26T00:00:00Z"));
        var values = new ArrayDeque<>(java.util.List.of(token('a'), token('b'), token('c')));
        var authority = new EmbedLaunchTicketAuthority(clock, Duration.ofSeconds(1), 1, values::remove);
        authority.issue(EmbedSessionFixtures.registration(1));
        assertThrows(EmbedLaunchTicketAuthority.CapacityExceededException.class,
                () -> authority.issue(EmbedSessionFixtures.registration(1)));
        clock.advance(Duration.ofSeconds(1));
        authority.issue(EmbedSessionFixtures.registration(1));
        assertEquals(1, authority.retainedEntries());
    }

    @Test
    void browserSessionCapExpiryRestartAndEveryPhaseRevocationFailClosed() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-08-26T00:00:00Z"));
        var values = new ArrayDeque<>(java.util.List.of(token('a'), token('b'), token('c'), token('d'),
                token('e'), token('f'), token('g'), token('h'), token('i'), token('j')));
        var sessions = new EmbedBrowserSessionAuthority(clock, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 1, values::remove);
        var registration = EmbedSessionFixtures.registration(1);
        var registrations = new MutableEmbedRegistrations(registration);
        var bootstrap = sessions.begin(registration);
        assertThrows(EmbedBrowserSessionAuthority.CapacityExceededException.class,
                () -> sessions.begin(registration));

        registrations.withdraw();
        assertNull(sessions.pending(bootstrap.exchangeId(), registrations));
        registrations.current(registration);
        var pending = sessions.pending(bootstrap.exchangeId(), registrations);
        var key = p256().getPublic();
        assertTrue(sessions.acknowledge(bootstrap.acknowledgementId(), bootstrap.channelId(),
                "correlation", registration, registrations, () -> { }));
        pending = sessions.acknowledged(bootstrap.exchangeId(), registrations);
        registrations.withdraw();
        assertNull(sessions.activate(bootstrap.exchangeId(), pending, (ECPublicKey) key, registrations));

        registrations.current(registration);
        clock.advance(Duration.ofSeconds(1));
        var fresh = sessions.begin(registration);
        assertTrue(sessions.acknowledge(fresh.acknowledgementId(), fresh.channelId(),
                "fresh-correlation", registration, registrations, () -> { }));
        var freshPending = sessions.acknowledged(fresh.exchangeId(), registrations);
        var bearer = sessions.activate(fresh.exchangeId(), freshPending, (ECPublicKey) key, registrations);
        assertInstanceOf(EmbedBrowserSessionAuthority.ActiveSession.class,
                sessions.resolve(bearer.bearer(), registrations));
        registrations.withdraw();
        assertNull(sessions.resolve(bearer.bearer(), registrations));

        registrations.current(registration);
        var restarted = new EmbedBrowserSessionAuthority(clock, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 1, () -> token('z'));
        assertNull(restarted.resolve(bearer.bearer(), registrations));
    }

    @Test
    void parentAcknowledgementIsExactOneUseAndAuditFailureRollsBack() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-08-26T00:00:00Z"));
        var values = new ArrayDeque<>(java.util.List.of(token('a'), token('b'), token('c'), token('d')));
        var sessions = new EmbedBrowserSessionAuthority(clock, Duration.ofSeconds(2),
                Duration.ofSeconds(2), 1, values::remove);
        var registration = EmbedSessionFixtures.registration(1);
        var registrations = new MutableEmbedRegistrations(registration);
        var bootstrap = sessions.begin(registration);
        assertNull(sessions.acknowledged(bootstrap.exchangeId(), registrations));
        assertFalse(sessions.acknowledge(bootstrap.acknowledgementId(), "wrong-channel",
                "correlation", registration, registrations, () -> { }));
        assertThrows(IllegalStateException.class, () -> sessions.acknowledge(
                bootstrap.acknowledgementId(), bootstrap.channelId(), "correlation", registration, registrations,
                () -> { throw new IllegalStateException("audit offline"); }));
        assertNull(sessions.acknowledged(bootstrap.exchangeId(), registrations));
        var auditAllows = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> sessions.acknowledge(bootstrap.acknowledgementId(),
                    bootstrap.channelId(), "correlation", registration, registrations, auditAllows::incrementAndGet));
            var two = pool.submit(() -> sessions.acknowledge(bootstrap.acknowledgementId(),
                    bootstrap.channelId(), "correlation", registration, registrations, auditAllows::incrementAndGet));
            assertNotEquals(one.get(), two.get());
        }
        assertEquals(1, auditAllows.get());
        assertFalse(sessions.acknowledge(bootstrap.acknowledgementId(), bootstrap.channelId(),
                "correlation", registration, registrations, () -> { }));
        assertEquals("correlation",
                sessions.acknowledged(bootstrap.exchangeId(), registrations).ackCorrelationId());

        var replacementRegistrations = new MutableEmbedRegistrations(registration);
        var replacementValues = new ArrayDeque<>(java.util.List.of(
                token('e'), token('f'), token('g'), token('h')));
        var replacementSessions = new EmbedBrowserSessionAuthority(clock, Duration.ofSeconds(2),
                Duration.ofSeconds(2), 1, replacementValues::remove);
        var replaced = replacementSessions.begin(registration);
        replacementRegistrations.current(EmbedSessionFixtures.registration(2));
        assertFalse(replacementSessions.acknowledge(replaced.acknowledgementId(), replaced.channelId(),
                "replacement-correlation", registration, replacementRegistrations, () -> { }));
    }

    @Test
    void es256ProofBindsKeyBearerRevisionNonceJtiMethodUriAndTimeAndCleansReplay() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-08-26T00:00:00Z"));
        var pair = p256();
        var other = p256();
        String bearer = token('b');
        String nonce = token('n');
        Instant issuedAt = clock.instant();
        byte[] signature = sign(pair, bearer, 7, nonce, "jti", "POST", "/v1/embed/projection", issuedAt);
        var verifier = new P256EmbedProofVerifier(clock, Duration.ofSeconds(1), 1);

        assertTrue(verifier.verifyAndConsume(bearer, 7, nonce, "jti", "POST",
                "/v1/embed/projection", issuedAt, (ECPublicKey) pair.getPublic(), signature));
        assertFalse(verifier.verifyAndConsume(bearer, 7, nonce, "jti", "POST",
                "/v1/embed/projection", issuedAt, (ECPublicKey) pair.getPublic(), signature));
        assertEquals(1, verifier.retainedReplayEntries());

        // Each binding failure uses a fresh verifier, so replay rejection cannot mask a missing
        // signature binding for a copied bearer, key, nonce, jti, verb, URI, revision or timestamp.
        assertRejected(clock, token('x'), 7, nonce, "jti", "POST",
                "/v1/embed/projection", issuedAt, pair, signature);
        assertRejected(clock, bearer, 8, nonce, "jti", "POST",
                "/v1/embed/projection", issuedAt, pair, signature);
        assertRejected(clock, bearer, 7, token('x'), "jti", "POST",
                "/v1/embed/projection", issuedAt, pair, signature);
        assertRejected(clock, bearer, 7, nonce, "jti-altered", "POST",
                "/v1/embed/projection", issuedAt, pair, signature);
        assertRejected(clock, bearer, 7, nonce, "jti", "GET",
                "/v1/embed/projection", issuedAt, pair, signature);
        assertRejected(clock, bearer, 7, nonce, "jti", "POST",
                "/wrong", issuedAt, pair, signature);
        assertRejected(clock, bearer, 7, nonce, "jti", "POST",
                "/v1/embed/projection", issuedAt, other, signature);
        byte[] corrupted = signature.clone();
        corrupted[0] ^= 1;
        assertRejected(clock, bearer, 7, nonce, "jti", "POST",
                "/v1/embed/projection", issuedAt, pair, corrupted);
        assertRejected(clock, bearer, 7, nonce, "jti", "POST",
                "/v1/embed/projection", issuedAt.minusSeconds(1), pair, signature);

        clock.advance(Duration.ofSeconds(1));
        Instant next = clock.instant();
        byte[] nextSignature = sign(pair, bearer, 7, nonce, "jti-next", "POST",
                "/v1/embed/projection", next);
        assertTrue(verifier.verifyAndConsume(bearer, 7, nonce, "jti-next", "POST",
                "/v1/embed/projection", next, (ECPublicKey) pair.getPublic(), nextSignature));
        assertEquals(1, verifier.retainedReplayEntries());
        assertFalse(verifier.verifyAndConsume(bearer, 7, nonce, "old", "POST",
                "/v1/embed/projection", issuedAt, (ECPublicKey) pair.getPublic(), signature));
    }

    private static void assertRejected(MutableClock clock, String bearer, long revision, String nonce, String jti,
                                       String method, String uri, Instant issuedAt,
                                       java.security.KeyPair verificationPair, byte[] signature) {
        var verifier = new P256EmbedProofVerifier(clock, Duration.ofSeconds(1), 1);
        assertFalse(verifier.verifyAndConsume(bearer, revision, nonce, jti, method, uri, issuedAt,
                (ECPublicKey) verificationPair.getPublic(), signature));
        assertEquals(0, verifier.retainedReplayEntries());
    }

    private static java.security.KeyPair p256() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static byte[] sign(java.security.KeyPair pair, String bearer, long revision, String nonce,
                               String jti, String method, String uri, Instant issuedAt) throws Exception {
        var signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(pair.getPrivate());
        signer.update(P256EmbedProofVerifier.payload(bearer, revision, nonce, jti, method, uri, issuedAt));
        return signer.sign();
    }

    private static String token(char value) {
        return String.valueOf(value).repeat(43);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
