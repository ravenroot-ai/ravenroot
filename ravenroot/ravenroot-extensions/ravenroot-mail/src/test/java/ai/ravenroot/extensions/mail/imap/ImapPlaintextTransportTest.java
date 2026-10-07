package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;
import jakarta.mail.Folder;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ImapPlaintextTransportTest {
    @Test void transportPropertiesEnablePlainOnlyForAuthorizedPlainProfileAndKeepTlsModesStrict()
            throws Exception {
        ReservedNetworkPolicy policy = TrustedNetworkImapProfileTest.policy(
                "tenant/reader", "localhost", 143, true);
        ImapProfile plain = profile("localhost", 143, "PLAIN",
                policy.authorizePlaintext("imap", "tenant/reader", "localhost", 143));
        ImapProfile startTls = profile("localhost", 143, "STARTTLS", null);
        ImapProfile imaps = profile("localhost", 993, "IMAPS", null);

        for (Class<?> behavior : List.of(
                MailImapQueryNodeBehavior.class, MailImapMutationNodeBehavior.class)) {
            Method method = behavior.getDeclaredMethod("properties", ImapProfile.class, String.class);
            method.setAccessible(true);
            assertPlain((Properties) method.invoke(null, plain, "imap"));
            assertStartTls((Properties) method.invoke(null, startTls, "imap"));
            assertEquals("true", ((Properties) method.invoke(null, imaps, "imaps"))
                    .getProperty("mail.imaps.ssl.checkserveridentity"));
        }

        assertPlain(AngusImapConsumerProtocol.properties(plain, "imap"));
        assertStartTls(AngusImapConsumerProtocol.properties(startTls, "imap"));
        assertEquals("true", AngusImapConsumerProtocol.properties(imaps, "imaps")
                .getProperty("mail.imaps.ssl.checkserveridentity"));
    }

    @Test void authenticatedPlainQueryAndMutationRequireMatchingPolicyAtTransport() throws Exception {
        try (var fixture = DeterministicImapFixture.startPlain()) {
            var user = fixture.server().setUser("reader@example.test", "reader", "secret");
            createFolder(fixture, "Archive");
            user.deliver(message("plain move"));

            ReservedNetworkPolicy policy = TrustedNetworkImapProfileTest.policy(
                    "tenant/reader", "localhost", fixture.port(), true);
            ImapProfile profile = profile("localhost", fixture.port(), "PLAIN",
                    policy.authorizePlaintext(
                            "imap", "tenant/reader", "localhost", fixture.port()));

            AtomicInteger credentialReads = new AtomicInteger();
            var credentials = (ai.ravenroot.api.security.CredentialResolver) ref -> {
                credentialReads.incrementAndGet();
                return Optional.of(new SecretValue("secret".toCharArray()));
            };
            NodeAction query = new MailImapQueryNodeBehavior(
                    (tenant, name) -> Optional.of(profile), credentials,
                    java.util.function.UnaryOperator.identity(), Runnable::run,
                    System::nanoTime, System::nanoTime, policy).create(queryConfiguration());
            Map<String, Object> page = output(query.handle(node(Map.of(
                    "version", "mail.imap.query.v1", "subject", "plain move")))
                    .toCompletableFuture().join());
            Map<String, Object> row = map(((List<?>) page.get("messages")).getFirst());
            assertEquals("plain move", row.get("subject"));

            ImapMutationPolicy mutationPolicy = new ImapMutationPolicy(
                    "tenant", "reader", Set.of(ImapMutationOperation.MOVE), Set.of("Archive"), "");
            NodeAction move = new MailImapMutationNodeBehavior(
                    MailImapMutationNodeBehavior.Kind.MOVE,
                    (tenant, name) -> Optional.of(profile),
                    (tenant, name) -> Optional.of(mutationPolicy), credentials,
                    java.util.function.UnaryOperator.identity(), Runnable::run, policy)
                    .create(moveConfiguration());
            NodeResult moved = move.handle(node(Map.of(
                    "version", "mail.imap.move.v1",
                    "sourceFolder", page.get("folder"),
                    "uidValidity", map(page.get("mailbox")).get("uidValidity"),
                    "uid", row.get("uid")))).toCompletableFuture().join();
            assertEquals("success", moved.outcome());
            assertEquals("MOVED", output(moved).get("status"));
            assertEquals(2, credentialReads.get(), "query and mutation each authenticate once");

            ReservedNetworkPolicy admissionOnly = ReservedNetworkPolicy.fromCommaSeparatedExceptions(
                    "localhost:LOOPBACK");
            AtomicInteger refusedCredentialReads = new AtomicInteger();
            NodeAction refused = new MailImapQueryNodeBehavior(
                    (tenant, name) -> Optional.of(profile), ref -> {
                        refusedCredentialReads.incrementAndGet();
                        return Optional.of(new SecretValue("secret".toCharArray()));
                    }, java.util.function.UnaryOperator.identity(), Runnable::run,
                    System::nanoTime, System::nanoTime, admissionOnly).create(queryConfiguration());
            assertThrows(CompletionException.class, () -> refused.handle(node(Map.of(
                    "version", "mail.imap.query.v1"))).toCompletableFuture().join());
            assertEquals(0, refusedCredentialReads.get(),
                    "transport plaintext policy must be revalidated before reading credentials");
        }
    }

    @Test void consumerRevalidatesPlaintextBeforeOpeningProtocol() {
        int port = 143;
        ReservedNetworkPolicy issuing = TrustedNetworkImapProfileTest.policy(
                "tenant/reader", "localhost", port, true);
        ImapProfile profile = profile("localhost", port, "PLAIN",
                issuing.authorizePlaintext("imap", "tenant/reader", "localhost", port));
        AtomicInteger opens = new AtomicInteger();
        ImapConsumerProtocol protocol = (candidate, folder, password, opening) -> {
            opens.incrementAndGet();
            throw new ImapConsumerProtocol.Failure(true, "unexpected-open");
        };
        ImapConsumerSource source = new ImapConsumerSource(
                new NodeConfiguration("consume", MailImapConsumeNodeBehavior.BEHAVIOR,
                        Map.of("profile", "reader")),
                ref -> Optional.of(new SecretValue("secret".toCharArray())),
                (tenant, name) -> Optional.of(profile),
                (tenant, name) -> Optional.of(new ImapConsumerPolicy(
                        "tenant", "reader", "INBOX", 100, 1, 1, 100, 1_000, 1,
                        65_536, "metadata", 0)),
                protocol, Runnable::run, Clock.systemUTC(), ignored -> { }, () -> 0.5,
                ReservedNetworkPolicy.fromCommaSeparatedExceptions("localhost:LOOPBACK"));

        assertThrows(CompletionException.class, () -> source.start(
                new ImapConsumerTestSupport.Context(new ImapConsumerTestSupport.Ingress()))
                .toCompletableFuture().join());
        assertEquals(0, opens.get());
    }

    private static void assertPlain(Properties properties) {
        assertNull(properties.getProperty("mail.imap.starttls.enable"));
        assertNull(properties.getProperty("mail.imap.starttls.required"));
        assertEquals("true", properties.getProperty("mail.imap.ssl.checkserveridentity"));
    }

    private static void assertStartTls(Properties properties) {
        assertEquals("true", properties.getProperty("mail.imap.starttls.enable"));
        assertEquals("true", properties.getProperty("mail.imap.starttls.required"));
        assertEquals("true", properties.getProperty("mail.imap.ssl.checkserveridentity"));
    }

    private static ImapProfile profile(String host, int port, String mode,
                                       ReservedNetworkPolicy.PlaintextAuthorization proof) {
        return new ImapProfile("tenant", "reader", host, port, mode, "reader", "credential",
                Set.of("INBOX", "Archive"), 2_000, 2_000, 1, 10, 128, proof);
    }

    private static NodeConfiguration queryConfiguration() {
        return new NodeConfiguration("query", MailImapQueryNodeBehavior.BEHAVIOR,
                Map.of("profile", "reader", "folder", "INBOX", "limit", "10"));
    }

    private static NodeConfiguration moveConfiguration() {
        return new NodeConfiguration("move", MailImapMutationNodeBehavior.MOVE_BEHAVIOR,
                Map.of("profile", "reader", "sourceFolder", "INBOX",
                        "destinationFolder", "Archive", RecoveryRepeatabilityProperty.NAME,
                        RecoveryRepeatabilityProperty.REPEATABLE));
    }

    private static void createFolder(DeterministicImapFixture fixture, String name) throws Exception {
        try (Store store = Session.getInstance(new Properties()).getStore("imap")) {
            store.connect("localhost", fixture.port(), "reader", "secret");
            Folder folder = store.getFolder(name);
            if (!folder.exists()) assertTrue(folder.create(Folder.HOLDS_MESSAGES));
        }
    }

    private static MimeMessage message(String subject) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("sender@example.test"));
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, "reader@example.test");
        message.setSubject(subject);
        message.setText("body");
        message.saveChanges();
        return message;
    }

    private static NodeMessage node(Map<String, Object> payload) {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext(
                "request", "tenant", "subject", PrincipalType.USER, "issuer"),
                id, id, id, id, Set.of(), "imap", payload, Map.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(NodeResult result) {
        return (Map<String, Object>) result.payload();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
