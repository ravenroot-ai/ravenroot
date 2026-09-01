package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.SecurityContext;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSocketFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MailImapMutationNodeBehaviorIntegrationTest {
    private static final int TIMEOUT_MS = 10_000;

    @Test void moveAndTrashAreVerifiedByUidAndVisibleAfterReopen() throws Exception {
        try (var fixture = DeterministicImapFixture.startImaps()) {
            var user = fixture.server().setUser("reader@example.test", "reader", "secret");
            createFolders(fixture, "Archive", "Trash");
            user.deliver(message("move me"));
            user.deliver(message("trash me"));

            Map<String, Object> moveIdentity = singleQuery(fixture, "INBOX", "move me");
            NodeResult moved = invoke(move(fixture, policy(Set.of(ImapMutationOperation.MOVE),
                            Set.of("Archive"), ""), "Archive"), moveIdentity);
            assertEquals("success", moved.outcome(), moved::toString);
            assertEquals("MOVED", output(moved).get("status"));
            Map<String, Object> moveDestination = map(output(moved).get("destination"));
            assertEquals("Archive", moveDestination.get("folder"));
            if (moveDestination.containsKey("uid")) {
                assertTrue(((Number) moveDestination.get("uidValidity")).longValue() > 0);
                assertTrue(((Number) moveDestination.get("uid")).longValue() > 0);
            }
            assertEquals(0, queryMessages(fixture, "INBOX", "move me").size());
            assertEquals(1, queryMessages(fixture, "Archive", "move me").size());
            NodeResult repeatedMove = invoke(move(fixture,
                    policy(Set.of(ImapMutationOperation.MOVE), Set.of("Archive"), ""),
                    "Archive"), moveIdentity);
            assertEquals("missing", repeatedMove.outcome());
            assertEquals("NO_EFFECT_ALREADY_ABSENT", output(repeatedMove).get("retry"));

            Map<String, Object> trashIdentity = withVersion(
                    singleQuery(fixture, "INBOX", "trash me"), "mail.imap.delete.v1");
            NodeResult trashed = invoke(delete(fixture,
                    policy(Set.of(ImapMutationOperation.TRASH), Set.of("Trash"), "Trash"),
                    "TRASH"), trashIdentity);
            assertEquals("success", trashed.outcome());
            assertEquals("TRASHED", output(trashed).get("status"));
            assertEquals(0, queryMessages(fixture, "INBOX", "trash me").size());
            assertEquals(1, queryMessages(fixture, "Trash", "trash me").size());
            NodeResult repeatedTrash = invoke(delete(fixture,
                    policy(Set.of(ImapMutationOperation.TRASH), Set.of("Trash"), "Trash"),
                    "TRASH"), trashIdentity);
            assertEquals("missing", repeatedTrash.outcome());
        }
    }

    @Test void hardDeleteRequiresProfileGraphAndPayloadAuthorizationAndExpungesOnlyTheUid()
            throws Exception {
        try (var fixture = DeterministicImapFixture.startImaps()) {
            var user = fixture.server().setUser("reader@example.test", "reader", "secret");
            user.deliver(message("delete me"));
            user.deliver(message("keep me"));
            Map<String, Object> identity = withVersion(
                    singleQuery(fixture, "INBOX", "delete me"), "mail.imap.delete.v1");
            ImapMutationPolicy policy = policy(Set.of(ImapMutationOperation.HARD_DELETE), Set.of(), "");

            NodeAction action = delete(fixture, policy, "HARD_DELETE");
            NodeResult refused = invoke(action, identity);
            assertEquals("refused", refused.outcome());
            assertEquals("HARD_DELETE_PAYLOAD_AUTHORIZATION", output(refused).get("reason"));
            assertEquals(1, queryMessages(fixture, "INBOX", "delete me").size());

            var authorized = new java.util.LinkedHashMap<>(identity);
            authorized.put("authorizeHardDelete", true);
            NodeResult deleted = invoke(action, Map.copyOf(authorized));
            assertEquals("success", deleted.outcome());
            assertEquals("HARD_DELETED", output(deleted).get("status"));
            assertEquals("DO_NOT_RETRY_AUTOMATICALLY", output(deleted).get("retry"));
            assertEquals(0, queryMessages(fixture, "INBOX", "delete me").size());
            assertEquals(1, queryMessages(fixture, "INBOX", "keep me").size());

            assertThrows(ImapMutationException.class, () ->
                    new MailImapMutationNodeBehavior(MailImapMutationNodeBehavior.Kind.DELETE)
                            .create(deleteConfiguration("HARD_DELETE",
                                    MailImapMutationNodeBehavior.HARD_DELETE_ACKNOWLEDGEMENT,
                                    RecoveryRepeatabilityProperty.REPEATABLE)));
        }
    }

    @Test void staleMissingAndPolicyRefusalsAreDistinctAndDoNotLookUpCredentials() throws Exception {
        try (var fixture = DeterministicImapFixture.startImaps()) {
            var user = fixture.server().setUser("reader@example.test", "reader", "secret");
            createFolders(fixture, "Archive");
            user.deliver(message("identity"));
            Map<String, Object> identity = singleQuery(fixture, "INBOX", "identity");
            NodeAction action = move(fixture,
                    policy(Set.of(ImapMutationOperation.MOVE), Set.of("Archive"), ""), "Archive");

            var stale = new java.util.LinkedHashMap<>(identity);
            stale.put("version", "mail.imap.move.v1");
            stale.put("uidValidity", ((Number) identity.get("uidValidity")).longValue() + 1);
            assertEquals("stale", invoke(action, Map.copyOf(stale)).outcome());

            var absent = new java.util.LinkedHashMap<>(identity);
            absent.put("version", "mail.imap.move.v1");
            absent.put("uid", Long.MAX_VALUE);
            assertEquals("missing", invoke(action, Map.copyOf(absent)).outcome());

            AtomicInteger credentials = new AtomicInteger();
            NodeAction refused = behavior(MailImapMutationNodeBehavior.Kind.MOVE, fixture,
                    policy(Set.of(ImapMutationOperation.MOVE), Set.of("Archive"), ""), ref -> {
                        credentials.incrementAndGet();
                        return Optional.of(new SecretValue("secret".toCharArray()));
                    }).create(moveConfiguration("Forbidden"));
            assertEquals("refused", invoke(refused, identity).outcome());
            assertEquals(0, credentials.get());

            NodeAction noPolicy = behavior(MailImapMutationNodeBehavior.Kind.MOVE, fixture,
                    null, ref -> {
                        credentials.incrementAndGet();
                        return Optional.of(new SecretValue("secret".toCharArray()));
                    }).create(moveConfiguration("Archive"));
            assertEquals("refused", invoke(noPolicy, identity).outcome());
            assertEquals(0, credentials.get());
        }
    }

    @Test void consumesTheStableConsumerIdentityAndOneMessageQueryWithoutTranscription()
            throws Exception {
        try (var fixture = DeterministicImapFixture.startImaps()) {
            var user = fixture.server().setUser("reader@example.test", "reader", "secret");
            createFolders(fixture, "Archive");
            user.deliver(message("consumer identity"));
            Map<String, Object> query = query(fixture, "INBOX", "consumer identity");
            Map<String, Object> row = map(((List<?>) query.get("messages")).getFirst());
            Map<String, Object> consumerEvent = Map.of(
                    "version", "mail.imap.message.v1",
                    "sourceFolder", query.get("folder"),
                    "uidValidity", map(query.get("mailbox")).get("uidValidity"),
                    "uid", row.get("uid"),
                    "subject", "ignored bounded message metadata");
            NodeAction action = move(fixture,
                    policy(Set.of(ImapMutationOperation.MOVE), Set.of("Archive"), ""), "Archive");
            NodeResult consumed = invoke(action, consumerEvent);
            assertEquals("success", consumed.outcome(), consumed::toString);

            user.deliver(message("query identity"));
            Map<String, Object> directQuery = query(fixture, "INBOX", "query identity");
            assertEquals("success", invoke(action, directQuery).outcome());
        }
    }

    @Test void rejectsUnboundedOrSecretBearingDirectPayloadsAndSanitizesFailures() throws Exception {
        AtomicInteger credentials = new AtomicInteger();
        ImapProfile profile = profile(1, Set.of("INBOX"));
        NodeAction action = new MailImapMutationNodeBehavior(MailImapMutationNodeBehavior.Kind.MOVE,
                (tenant, name) -> Optional.of(profile),
                (tenant, name) -> Optional.of(policy(Set.of(ImapMutationOperation.MOVE),
                        Set.of("Archive"), "")), ref -> {
                            credentials.incrementAndGet();
                            throw new IllegalStateException("password=transport-secret");
                }).create(moveConfiguration("Archive"));
        Map<String, Object> identity = Map.of("version", "mail.imap.move.v1", "sourceFolder", "INBOX",
                "uidValidity", 1, "uid", 1);
        CompletionException failure = assertThrows(CompletionException.class,
                () -> invoke(action, identity));
        assertEquals(ImapMutationException.Code.CREDENTIAL_UNAVAILABLE,
                ((ImapMutationException) failure.getCause()).code());
        assertFalse(throwableText(failure).contains("transport-secret"));
        assertEquals(1, credentials.get());

        var secretBearing = new java.util.LinkedHashMap<>(identity);
        secretBearing.put("password", "payload-secret");
        CompletionException rejected = assertThrows(CompletionException.class,
                () -> invoke(action, Map.copyOf(secretBearing)));
        assertEquals(ImapMutationException.Code.INVALID_INPUT,
                ((ImapMutationException) rejected.getCause()).code());
        assertFalse(throwableText(rejected).contains("payload-secret"));

        var oversized = new java.util.LinkedHashMap<>(identity);
        oversized.put("version", "mail.imap.message.v1");
        oversized.put("sourceFolder", "INBOX");
        oversized.put("body", "x".repeat(65_537));
        CompletionException bounded = assertThrows(CompletionException.class,
                () -> invoke(action, Map.copyOf(oversized)));
        assertEquals(ImapMutationException.Code.INVALID_INPUT,
                ((ImapMutationException) bounded.getCause()).code());

        CompletionException subjectOnly = assertThrows(CompletionException.class,
                () -> invoke(action, Map.of("version", "mail.imap.message.v1",
                        "subject", "not an identity")));
        assertEquals(ImapMutationException.Code.INVALID_INPUT,
                ((ImapMutationException) subjectOnly.getCause()).code());
    }

    @Test void disconnectAndAbsoluteTimeoutAfterMoveCommandAreExplicitlyAmbiguous()
            throws Exception {
        assertAmbiguous(DeterministicMutationImapFixture.Failure.DISCONNECT,
                "DISCONNECT_AFTER_COMMAND", 2_000);
        assertAmbiguous(DeterministicMutationImapFixture.Failure.SLOW_RESPONSE,
                "TIMEOUT_AFTER_COMMAND", 500);
    }

    @Test void uidValidityChangeOnVerificationSelectIsAmbiguousAndNeverAutoRetried()
            throws Exception {
        try (var fixture = new DeterministicMutationImapFixture(
                DeterministicMutationImapFixture.Failure.UIDVALIDITY_ROLLOVER)) {
            SSLSocketFactory ssl = DeterministicImapFixture.trustedSocketFactoryForTests();
            ImapProfile profile = new ImapProfile("tenant", "reader", "localhost", fixture.port(),
                    "IMAPS", "reader", "credential", Set.of("INBOX", "Archive"),
                    TIMEOUT_MS, TIMEOUT_MS, 1, 1, 10);
            NodeAction action = new MailImapMutationNodeBehavior(
                    MailImapMutationNodeBehavior.Kind.MOVE,
                    (tenant, name) -> Optional.of(profile),
                    (tenant, name) -> Optional.of(policy(Set.of(ImapMutationOperation.MOVE),
                            Set.of("Archive"), "")),
                    ref -> Optional.of(new SecretValue("secret".toCharArray())),
                    properties -> {
                        properties.put("mail.imaps.ssl.socketFactory", ssl);
                        return properties;
                    }).create(moveConfiguration("Archive"));

            NodeResult result = invoke(action, Map.of("version", "mail.imap.move.v1",
                    "sourceFolder", "INBOX", "uidValidity", 7, "uid", 1));

            assertTrue(fixture.awaitMutation(), "the MOVE command must be accepted");
            assertEquals("ambiguous", result.outcome(), result::toString);
            assertEquals("AMBIGUOUS", output(result).get("status"));
            assertEquals("UIDVALIDITY_CHANGED_AFTER_COMMAND", output(result).get("reason"));
            assertEquals("DO_NOT_RETRY_AUTOMATICALLY", output(result).get("retry"));
            assertNotEquals("success", result.outcome());
            assertTrue(fixture.awaitSocketClose(), "session socket must close deterministically");
        }
    }

    private static void assertAmbiguous(DeterministicMutationImapFixture.Failure failure,
                                        String expectedReason, int timeoutMs) throws Exception {
        try (var fixture = new DeterministicMutationImapFixture(failure)) {
            SSLSocketFactory ssl = DeterministicImapFixture.trustedSocketFactoryForTests();
            ImapProfile profile = new ImapProfile("tenant", "reader", "localhost", fixture.port(),
                    "IMAPS", "reader", "credential", Set.of("INBOX", "Archive"),
                    timeoutMs, timeoutMs, 1, 1, 10);
            NodeAction action = new MailImapMutationNodeBehavior(
                    MailImapMutationNodeBehavior.Kind.MOVE,
                    (tenant, name) -> Optional.of(profile),
                    (tenant, name) -> Optional.of(policy(Set.of(ImapMutationOperation.MOVE),
                            Set.of("Archive"), "")),
                    ref -> Optional.of(new SecretValue("secret".toCharArray())),
                    properties -> {
                        properties.put("mail.imaps.ssl.socketFactory", ssl);
                        return properties;
                    }).create(moveConfiguration("Archive"));
            var stage = action.handle(node(Map.of("version", "mail.imap.move.v1",
                    "sourceFolder", "INBOX", "uidValidity", 7, "uid", 1)))
                    .toCompletableFuture();
            if (!fixture.awaitMutation()) fail(
                    "mutation command must be accepted before failure; commands="
                            + fixture.commands() + "; result=" + stage.join());
            NodeResult result = stage.join();
            assertEquals("ambiguous", result.outcome());
            assertEquals(expectedReason, output(result).get("reason"));
            assertEquals("DO_NOT_RETRY_AUTOMATICALLY", output(result).get("retry"));
            assertTrue(fixture.awaitSocketClose(), "session socket must close deterministically");
        }
    }

    private static NodeAction move(DeterministicImapFixture fixture, ImapMutationPolicy policy,
                                   String destination) throws Exception {
        return behavior(MailImapMutationNodeBehavior.Kind.MOVE, fixture, policy,
                ref -> Optional.of(new SecretValue("secret".toCharArray())))
                .create(moveConfiguration(destination));
    }

    private static NodeAction delete(DeterministicImapFixture fixture, ImapMutationPolicy policy,
                                     String mode) throws Exception {
        return behavior(MailImapMutationNodeBehavior.Kind.DELETE, fixture, policy,
                ref -> Optional.of(new SecretValue("secret".toCharArray())))
                .create(deleteConfiguration(mode,
                        mode.equals("HARD_DELETE")
                                ? MailImapMutationNodeBehavior.HARD_DELETE_ACKNOWLEDGEMENT : "",
                        mode.equals("HARD_DELETE")
                                ? RecoveryRepeatabilityProperty.NOT_REPEATABLE
                                : RecoveryRepeatabilityProperty.REPEATABLE));
    }

    private static MailImapMutationNodeBehavior behavior(
            MailImapMutationNodeBehavior.Kind kind, DeterministicImapFixture fixture,
            ImapMutationPolicy policy, ai.ravenroot.api.security.CredentialResolver credentials)
            throws Exception {
        ImapProfile profile = profile(fixture.port(), Set.of("INBOX", "Archive", "Trash"));
        SSLSocketFactory ssl = fixture.trustedSocketFactory();
        return new MailImapMutationNodeBehavior(kind,
                (tenant, name) -> Optional.of(profile),
                (tenant, name) -> Optional.ofNullable(policy), credentials,
                properties -> { properties.put("mail.imaps.ssl.socketFactory", ssl); return properties; });
    }

    private static NodeConfiguration moveConfiguration(String destination) {
        return new NodeConfiguration("move", MailImapMutationNodeBehavior.MOVE_BEHAVIOR,
                Map.of("profile", "reader", "sourceFolder", "INBOX",
                        "destinationFolder", destination,
                        RecoveryRepeatabilityProperty.NAME,
                        RecoveryRepeatabilityProperty.REPEATABLE));
    }

    private static NodeConfiguration deleteConfiguration(String mode, String acknowledgement,
                                                         String repeatability) {
        var properties = new java.util.LinkedHashMap<String, String>();
        properties.put("profile", "reader");
        properties.put("sourceFolder", "INBOX");
        properties.put("deleteMode", mode);
        properties.put("hardDeleteAcknowledgement", acknowledgement);
        properties.put(RecoveryRepeatabilityProperty.NAME, repeatability);
        return new NodeConfiguration("delete", MailImapMutationNodeBehavior.DELETE_BEHAVIOR,
                Map.copyOf(properties));
    }

    private static ImapProfile profile(int port, Set<String> folders) {
        return new ImapProfile("tenant", "reader", "localhost", port, "IMAPS", "reader",
                "credential", folders, TIMEOUT_MS, TIMEOUT_MS, 4, 50, 128);
    }

    private static ImapMutationPolicy policy(Set<ImapMutationOperation> operations,
                                             Set<String> destinations, String trash) {
        return new ImapMutationPolicy("tenant", "reader", operations, destinations, trash);
    }

    private static Map<String, Object> singleQuery(DeterministicImapFixture fixture, String folder,
                                                   String subject) throws Exception {
        Map<String, Object> page = query(fixture, folder, subject);
        Map<String, Object> row = map(((List<?>) page.get("messages")).getFirst());
        return Map.of("version", "mail.imap.move.v1", "sourceFolder", page.get("folder"),
                "uidValidity", map(page.get("mailbox")).get("uidValidity"), "uid", row.get("uid"));
    }

    private static Map<String, Object> query(DeterministicImapFixture fixture, String folder,
                                             String subject) throws Exception {
        SSLSocketFactory ssl = fixture.trustedSocketFactory();
        NodeAction query = new MailImapQueryNodeBehavior(
                (tenant, name) -> Optional.of(profile(fixture.port(),
                        Set.of("INBOX", "Archive", "Trash"))),
                ref -> Optional.of(new SecretValue("secret".toCharArray())),
                properties -> {
                    properties.put("mail.imaps.ssl.socketFactory", ssl);
                    return properties;
                }).create(new NodeConfiguration("query", MailImapQueryNodeBehavior.BEHAVIOR,
                        Map.of("profile", "reader", "folder", folder, "limit", "10")));
        return output(invoke(query, Map.of("version", "mail.imap.query.v1", "subject", subject)));
    }

    private static Map<String, Object> withVersion(Map<String, Object> source, String version) {
        var copy = new java.util.LinkedHashMap<>(source);
        copy.put("version", version);
        return Map.copyOf(copy);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> queryMessages(DeterministicImapFixture fixture,
                                                           String folder, String subject)
            throws Exception {
        return (List<Map<String, Object>>) (List<?>) query(fixture, folder, subject).get("messages");
    }

    private static void createFolders(DeterministicImapFixture fixture, String... names)
            throws Exception {
        Properties properties = new Properties();
        properties.put("mail.imaps.ssl.socketFactory", fixture.trustedSocketFactory());
        properties.setProperty("mail.imaps.ssl.checkserveridentity", "true");
        Session session = Session.getInstance(properties);
        try (Store store = session.getStore("imaps")) {
            store.connect("localhost", fixture.port(), "reader", "secret");
            for (String name : names) {
                Folder folder = store.getFolder(name);
                if (!folder.exists()) assertTrue(folder.create(Folder.HOLDS_MESSAGES));
            }
        }
    }

    private static MimeMessage message(String subject) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("sender@example.test"));
        message.setRecipients(Message.RecipientType.TO, "reader@example.test");
        message.setSubject(subject);
        message.setText("body");
        message.setSentDate(java.util.Date.from(Instant.parse("2026-01-01T00:00:00Z")));
        message.saveChanges();
        return message;
    }

    private static NodeResult invoke(NodeAction action, Object payload) {
        return action.handle(node(payload)).toCompletableFuture().join();
    }

    private static NodeMessage node(Object payload) {
        UUID id = UUID.randomUUID();
        return new NodeMessage(
                new SecurityContext("request", "tenant", "subject", PrincipalType.USER, "issuer"),
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

    private static String throwableText(Throwable value) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = value; current != null; current = current.getCause())
            text.append(current).append(' ').append(current.getMessage()).append(' ');
        return text.toString();
    }
}
