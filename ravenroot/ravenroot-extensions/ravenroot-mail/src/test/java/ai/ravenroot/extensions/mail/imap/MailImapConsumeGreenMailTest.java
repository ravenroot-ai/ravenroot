package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.SecretValue;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Folder;
import jakarta.mail.Flags;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MailImapConsumeGreenMailTest {
    @Test void emptyReadyThenAppendIsActivelyRefreshedAndRestartDoesNotDuplicate() throws Exception {
        try (var fixture = DeterministicImapFixture.startImaps()) {
            var user = fixture.server().setUser("reader@example.test", "reader", "secret");
            var profile = new ImapProfile(ImapConsumerTestSupport.TENANT, ImapConsumerTestSupport.PROFILE,
                    "localhost", fixture.port(), "IMAPS", "reader", "credential-ref", Set.of("INBOX"),
                    10_000, 10_000, 1, 20, 256);
            var policy = new ImapConsumerPolicy(ImapConsumerTestSupport.TENANT,
                    ImapConsumerTestSupport.PROFILE, "INBOX", 100, 4, 32, 100, 1_000, 3,
                    65_536, "preview", 256);
            var socketFactory = fixture.trustedSocketFactory();
            var protocol = new AngusImapConsumerProtocol(properties -> {
                properties.put("mail.imaps.ssl.socketFactory", socketFactory);
                return properties;
            });
            var ingress = new ImapConsumerTestSupport.Ingress(2);
            var context = new ImapConsumerTestSupport.Context(ingress, 2);
            var source = new ImapConsumerSource(
                    new NodeConfiguration("consume", MailImapConsumeNodeBehavior.BEHAVIOR,
                            Map.of("profile", ImapConsumerTestSupport.PROFILE)),
                    ignored -> Optional.of(new SecretValue("secret".toCharArray())),
                    (tenant, id) -> Optional.of(profile), (tenant, id) -> Optional.of(policy),
                    protocol, task -> Thread.ofVirtual().name("imap-greenmail-consumer").start(task),
                    Clock.systemUTC());
            try {
                source.start(context).toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
                assertEquals(ImapConsumerSource.State.READY, source.state());
                user.deliver(message("after-ready-one", "first"));
                awaitPayloads(ingress, 1);
                awaitAdvances(ingress, 1);
                String firstKey = ingress.keys.getFirst();
                @SuppressWarnings("unchecked")
                var firstCheckpoint = (Map<String, Object>) ingress.payloads.getFirst().get("checkpoint");
                assertEquals("mail.imap.checkpoint.v1", firstCheckpoint.get("version"));
                assertEquals(1L, firstCheckpoint.get("candidateDeliveredThroughUid"));

                source.stop().toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
                assertEquals(ImapConsumerSource.State.STOPPED, source.state());
                assertEquals(0, ImapConsumerSource.activeLeases());

                user.deliver(message("deleted-gap", "not delivered"));
                user.deliver(message("after-restart-three", "third"));
                expungeSubject(fixture, "deleted-gap");
                source.start(context).toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
                awaitPayloads(ingress, 2);
                awaitAdvances(ingress, 2);

                assertEquals(1, ingress.keys.stream().filter(firstKey::equals).count());
                assertEquals(java.util.List.of(1L, 3L), ingress.advances,
                        "a sparse expunged UID must not stall the bounded UID scan");
                assertEquals("after-ready-one", ingress.payloads.getFirst().get("subject"));
                assertEquals("after-restart-three", ingress.payloads.getLast().get("subject"));
                @SuppressWarnings("unchecked")
                var restartCheckpoint = (Map<String, Object>) ingress.payloads.getLast().get("checkpoint");
                assertEquals(firstCheckpoint.get("uidValidity"), restartCheckpoint.get("uidValidity"));
                assertEquals(3L, restartCheckpoint.get("candidateDeliveredThroughUid"));
            } finally {
                source.stop().toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
                assertEquals(0, ImapConsumerSource.activeLeases());
            }
        }
    }

    private static MimeMessage message(String subject, String body) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("sender@example.test"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("reader@example.test"));
        message.setSubject(subject);
        message.setText(body);
        message.saveChanges();
        return message;
    }

    private static void expungeSubject(DeterministicImapFixture fixture, String subject) throws Exception {
        Properties properties = new Properties();
        properties.put("mail.imaps.ssl.socketFactory", fixture.trustedSocketFactory());
        properties.setProperty("mail.imaps.ssl.checkserveridentity", "true");
        var store = Session.getInstance(properties).getStore("imaps");
        Folder folder = null;
        try {
            store.connect("localhost", fixture.port(), "reader", "secret");
            folder = store.getFolder("INBOX");
            folder.open(Folder.READ_WRITE);
            for (Message message : folder.getMessages())
                if (subject.equals(message.getSubject())) message.setFlag(Flags.Flag.DELETED, true);
            folder.close(true);
            folder = null;
        } finally {
            if (folder != null && folder.isOpen()) folder.close(false);
            if (store.isConnected()) store.close();
        }
    }

    private static void awaitPayloads(ImapConsumerTestSupport.Ingress ingress, int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ingress.payloads.size() < count && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(count, ingress.payloads.size());
    }

    private static void awaitAdvances(ImapConsumerTestSupport.Ingress ingress, int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ingress.advances.size() < count && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(count, ingress.advances.size());
    }
}
