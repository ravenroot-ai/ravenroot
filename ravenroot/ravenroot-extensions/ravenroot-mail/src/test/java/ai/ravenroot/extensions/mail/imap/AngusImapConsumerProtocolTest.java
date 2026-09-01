package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.SecretValue;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AngusImapConsumerProtocolTest {
    @Test void imapsAndStartTlsAreHostnameVerifiedPeekedAndTimeoutClamped() {
        var huge = new ImapProfile("tenant", "reader", "mail.example.test", 993, "IMAPS",
                "reader", "credential", Set.of("INBOX"), Integer.MAX_VALUE, Integer.MAX_VALUE,
                1, 1, 0);
        var imaps = AngusImapConsumerProtocol.properties(huge, "imaps");
        assertEquals("true", imaps.getProperty("mail.imaps.ssl.checkserveridentity"));
        assertEquals("true", imaps.getProperty("mail.imaps.peek"));
        assertEquals("30000", imaps.getProperty("mail.imaps.connectiontimeout"));
        assertEquals("30000", imaps.getProperty("mail.imaps.timeout"));
        assertEquals("30000", imaps.getProperty("mail.imaps.writetimeout"));

        var startTls = AngusImapConsumerProtocol.properties(huge, "imap");
        assertEquals("true", startTls.getProperty("mail.imap.starttls.enable"));
        assertEquals("true", startTls.getProperty("mail.imap.starttls.required"));
        assertEquals("true", startTls.getProperty("mail.imap.ssl.checkserveridentity"));
        assertEquals("true", startTls.getProperty("mail.imap.peek"));
    }

    @Test void openingCancellationImmediatelyRevokesEverySocketWithoutProviderClose() {
        var opening = new ImapConsumerProtocol.Opening();
        AtomicBoolean firstClosed = new AtomicBoolean(), secondClosed = new AtomicBoolean();
        opening.track(socket(firstClosed));
        opening.track(socket(secondClosed));
        assertEquals(2, opening.trackedSockets());
        long started = System.nanoTime();
        opening.cancel();
        long millis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(firstClosed.get() && secondClosed.get());
        assertEquals(0, opening.trackedSockets());
        assertTrue(millis < 100, "socket revocation must not wait on provider cleanup");
    }

    @Test void requiredStartTlsConsumerBecomesReadyAndStopRevokesTheLiveSocket() throws Exception {
        try (var fixture = new DeterministicStartTlsImapFixture(true, true)) {
            var profile = new ImapProfile(ImapConsumerTestSupport.TENANT, ImapConsumerTestSupport.PROFILE,
                    "localhost", fixture.port(), "STARTTLS", "reader", "credential-ref", Set.of("INBOX"),
                    1_000, 1_000, 1, 20, 256);
            var policy = new ImapConsumerPolicy(ImapConsumerTestSupport.TENANT,
                    ImapConsumerTestSupport.PROFILE, "INBOX", 100, 4, 32, 100, 1_000, 3,
                    65_536, "metadata", 0);
            var socketFactory = DeterministicImapFixture.trustedSocketFactoryForTests();
            var protocol = new AngusImapConsumerProtocol(properties -> {
                properties.put("mail.imap.ssl.socketFactory", socketFactory);
                return properties;
            });
            var source = new ImapConsumerSource(new NodeConfiguration("consume",
                    MailImapConsumeNodeBehavior.BEHAVIOR, Map.of("profile", ImapConsumerTestSupport.PROFILE)),
                    ignored -> Optional.of(new SecretValue("secret".toCharArray())),
                    (tenant, id) -> Optional.of(profile), (tenant, id) -> Optional.of(policy), protocol,
                    task -> Thread.ofVirtual().name("imap-starttls-consumer").start(task), Clock.systemUTC());
            try {
                source.start(new ImapConsumerTestSupport.Context(new ImapConsumerTestSupport.Ingress()))
                        .toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(ImapConsumerSource.State.READY, source.state());
                assertTrue(fixture.upgraded(), "the consumer must complete a real TLS upgrade");
                assertEquals(1, fixture.credentialCommands());

                long started = System.nanoTime();
                source.stop().toCompletableFuture().get(1, TimeUnit.SECONDS);
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000);
                assertTrue(fixture.awaitSocketClose(), "stop must revoke the selected-session socket");
                assertEquals(0, ImapConsumerSource.activeLeases());
            } finally {
                source.stop().toCompletableFuture().orTimeout(1, TimeUnit.SECONDS).join();
            }
        }
    }

    private static Socket socket(AtomicBoolean closed) {
        return new Socket() {
            @Override public synchronized void close() { closed.set(true); }
        };
    }
}
