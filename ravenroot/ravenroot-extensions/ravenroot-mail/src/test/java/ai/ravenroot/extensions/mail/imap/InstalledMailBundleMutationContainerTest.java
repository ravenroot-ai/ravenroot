package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.plugin.bundle.PluginBundleLoader;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs only from scripts/verify-mail-imap-mutations-container.sh against an installed bundle. */
class InstalledMailBundleMutationContainerTest {
    private static final int IMAPS_PORT = 3993;

    @Test void installedBundleQueriesMovesTrashesAndObservesEffectsAfterReopen() throws Exception {
        String plugins = System.getProperty("ravenroot.mail.installedBundleRoot", "");
        assumeTrue(!plugins.isBlank(), "container-only installed-bundle proof");
        try (var fixture = DeterministicImapFixture.startInstalledBundleImaps(IMAPS_PORT);
             var activation = PluginBundleLoader.load(Path.of(plugins),
                     Set.of("ai.ravenroot.extensions.mail"))) {
            var user = fixture.server().setUser("reader@example.test", "reader", "secret");
            createFolders("Archive", "Trash");
            user.deliver(message("bundle move"));
            user.deliver(message("bundle trash"));

            var nodePackage = activation.packages().stream()
                    .filter(candidate -> candidate.id().equals("ai.ravenroot.extensions.mail"))
                    .findFirst().orElseThrow();
            Map<String, NodeBehavior> behaviors = nodePackage.behaviors().stream().collect(
                    java.util.stream.Collectors.toUnmodifiableMap(
                            behavior -> behavior.descriptor().behavior(), behavior -> behavior));
            assertEquals(Set.of("mail.send", "mail.imap.query", "mail.imap.consume",
                    "mail.imap.move", "mail.imap.delete"), behaviors.keySet());

            NodeAction queryInbox = query(behaviors.get("mail.imap.query"), "INBOX");
            NodeAction queryArchive = query(behaviors.get("mail.imap.query"), "Archive");
            NodeAction queryTrash = query(behaviors.get("mail.imap.query"), "Trash");
            NodeAction move = behaviors.get("mail.imap.move").create(new NodeConfiguration(
                    "move", "mail.imap.move", Map.of("profile", "reader",
                            "sourceFolder", "INBOX", "destinationFolder", "Archive")));
            NodeAction trash = behaviors.get("mail.imap.delete").create(new NodeConfiguration(
                    "trash", "mail.imap.delete", Map.of("profile", "reader",
                            "sourceFolder", "INBOX", "deleteMode", "TRASH")));

            Map<String, Object> moveQuery = query(queryInbox, "bundle move");
            assertEquals("success", invoke(move, moveQuery).outcome());
            assertTrue(messages(query(queryInbox, "bundle move")).isEmpty());
            assertEquals(1, messages(query(queryArchive, "bundle move")).size());

            Map<String, Object> trashQuery = query(queryInbox, "bundle trash");
            assertEquals("success", invoke(trash, trashQuery).outcome());
            assertTrue(messages(query(queryInbox, "bundle trash")).isEmpty());
            assertEquals(1, messages(query(queryTrash, "bundle trash")).size());
        }
    }

    private static NodeAction query(NodeBehavior behavior, String folder) {
        return behavior.create(new NodeConfiguration("query-" + folder, "mail.imap.query",
                Map.of("profile", "reader", "folder", folder, "limit", "10")));
    }

    private static Map<String, Object> query(NodeAction action, String subject) {
        return output(invoke(action,
                Map.of("version", "mail.imap.query.v1", "subject", subject)));
    }

    private static void createFolders(String... names) throws Exception {
        Session session = Session.getInstance(new Properties());
        try (Store store = session.getStore("imaps")) {
            store.connect("localhost", IMAPS_PORT, "reader", "secret");
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
        UUID id = UUID.randomUUID();
        NodeMessage message = new NodeMessage(new SecurityContext(
                "request", "tenant", "subject", PrincipalType.USER, "issuer"),
                id, id, id, id, Set.of(), "imap", payload, Map.of());
        return action.handle(message).toCompletableFuture().join();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(NodeResult result) {
        return (Map<String, Object>) result.payload();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> messages(Map<String, Object> output) {
        return (List<Map<String, Object>>) (List<?>) output.get("messages");
    }
}
