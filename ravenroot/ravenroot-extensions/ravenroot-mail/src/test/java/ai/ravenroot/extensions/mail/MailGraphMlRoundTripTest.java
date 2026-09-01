package ai.ravenroot.extensions.mail;

import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.SecurityContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MailGraphMlRoundTripTest {
    @Test void retainsCredentialReferenceThroughARealResolverFlowWithoutSerializingItsSecret() throws Exception {
        String sentinel = "graph-secret-sentinel";
        var definition = new GraphDefinition(List.of(GraphNode.start("start"),
                new GraphNode("mail", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "mail.send", Map.of(
                        "mailProfile", MailTestSupport.PROFILE,
                        "host", "127.0.0.1", "port", "9", "credentialRef", "mail-primary", "authUsername", "mailer",
                        "securityMode", "STARTTLS", "maxConcurrency", "1", "defaultFrom", "from@example.test")),
                GraphNode.error("error"), GraphNode.end("end")), List.of());
        byte[] xml;
        try (var graph = GraphManager.from(definition); var output = new ByteArrayOutputStream()) { graph.writeGraphMl(output); xml = output.toByteArray(); }
        String text = new String(xml, StandardCharsets.UTF_8);
        assertTrue(text.contains("mail-primary")); assertFalse(text.contains(sentinel));
        try (var reread = GraphManager.readGraphMl(new ByteArrayInputStream(xml))) {
            assertEquals("mail-primary", reread.definition().node("mail").properties().get("credentialRef"));
            assertEquals("1", reread.definition().node("mail").properties().get("maxConcurrency"));
            assertEquals("mail.send", reread.definition().node("mail").behavior());
            var action = new MailSendNodeBehavior(ref -> java.util.Optional.of(new SecretValue(sentinel.toCharArray())),
                    (tenant, name) -> java.util.Optional.of(MailTestSupport.profile(tenant, name, "127.0.0.1", 9,
                            "STARTTLS", "mailer", "mail-primary", 0)))
                    .create(new NodeConfiguration("mail", "mail.send", reread.definition().node("mail").properties()));
            CompletionException failure = assertThrows(CompletionException.class, () -> action.handle(message()).toCompletableFuture().join());
            assertFalse(throwableText(failure).contains(sentinel));
        }
    }

    @Test void roundTripsTheOpaqueImapProfileConfiguration() throws Exception {
        var definition = new GraphDefinition(List.of(GraphNode.start("start"),
                new GraphNode("imap", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "mail.imap.query", Map.of(
                        "profile", "reader", "folder", "INBOX", "limit", "10", "maxConcurrency", "1")), GraphNode.error("error"), GraphNode.end("end")), List.of());
        byte[] xml;
        try (var graph = GraphManager.from(definition); var output = new ByteArrayOutputStream()) { graph.writeGraphMl(output); xml = output.toByteArray(); }
        try (var reread = GraphManager.readGraphMl(new ByteArrayInputStream(xml))) {
            assertEquals("mail.imap.query", reread.definition().node("imap").behavior());
            assertEquals("reader", reread.definition().node("imap").properties().get("profile"));
            assertFalse(new String(xml, StandardCharsets.UTF_8).contains("graph-secret-sentinel"));
        }
    }

    /**
     * {@code contentMode} exists as a node property, and not only as a
     * payload field, exactly so that this round trip is possible: a payload field is not inspectable
     * and not serialized, and the issue asks for both. The second half is that carrying a body-mode
     * selection adds no new authority to the graph -- it selects how much of an already-authorized
     * mailbox's body comes back, so the document must still name no host, username, credential
     * reference or secret. Asserted here rather than assumed, because "we only added an enum" is
     * precisely the kind of change nobody re-checks.
     */
    @Test void roundTripsTheImapFullContentModeWithoutSerializingAnEndpointOrCredential() throws Exception {
        String sentinel = "imap-full-secret-sentinel";
        var definition = new GraphDefinition(List.of(GraphNode.start("start"),
                new GraphNode("imap", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "mail.imap.query", Map.of(
                        "profile", "reader", "folder", "INBOX", "limit", "10", "maxConcurrency", "1",
                        "contentMode", "full")), GraphNode.error("error"), GraphNode.end("end")), List.of());
        byte[] xml;
        try (var graph = GraphManager.from(definition); var output = new ByteArrayOutputStream()) { graph.writeGraphMl(output); xml = output.toByteArray(); }
        String text = new String(xml, StandardCharsets.UTF_8);
        assertTrue(text.contains("full"));
        assertFalse(text.contains(sentinel));
        assertFalse(text.contains("credentialRef"));
        assertFalse(text.contains("password"));
        assertFalse(text.contains("mail.example.test"));
        try (var reread = GraphManager.readGraphMl(new ByteArrayInputStream(xml))) {
            assertEquals("mail.imap.query", reread.definition().node("imap").behavior());
            assertEquals("full", reread.definition().node("imap").properties().get("contentMode"));
            assertEquals("reader", reread.definition().node("imap").properties().get("profile"));
        }
    }

    @Test void roundTripsOnlyOpaqueConsumerAuthorityAndTighteningValues() throws Exception {
        String sentinel = "consumer-secret-sentinel";
        var definition = new GraphDefinition(List.of(GraphNode.start("start"),
                new GraphNode("consume", ai.ravenroot.core.graph.NodeKind.BEHAVIOR,
                        "mail.imap.consume", Map.of("profile", "reader", "folder", "INBOX",
                        "batchSize", "4", "contentMode", "metadata",
                        "allowedHeaders", "x-trace")),
                GraphNode.error("error"), GraphNode.end("end")), List.of());
        byte[] xml;
        try (var graph = GraphManager.from(definition); var output = new ByteArrayOutputStream()) {
            graph.writeGraphMl(output); xml = output.toByteArray();
        }
        String text = new String(xml, StandardCharsets.UTF_8);
        assertTrue(text.contains("mail.imap.consume"));
        assertTrue(text.contains("reader"));
        assertFalse(text.contains(sentinel));
        assertFalse(text.contains("mail.example.test"));
        assertFalse(text.matches("(?s).*(password|credentialRef|username).*"));
        try (var reread = GraphManager.readGraphMl(new ByteArrayInputStream(xml))) {
            assertEquals("mail.imap.consume", reread.definition().node("consume").behavior());
            assertEquals("reader", reread.definition().node("consume").properties().get("profile"));
            assertEquals("4", reread.definition().node("consume").properties().get("batchSize"));
            assertEquals("x-trace", reread.definition().node("consume").properties().get("allowedHeaders"));
        }
    }

    @Test void roundTripsOnlyOpaqueMutationIdentityAndPolicySelectors() throws Exception {
        String sentinel = "imap-password-sentinel";
        var definition = new GraphDefinition(List.of(GraphNode.start("start"),
                new GraphNode("move", ai.ravenroot.core.graph.NodeKind.BEHAVIOR, "mail.imap.move",
                        Map.of("profile", "reader", "sourceFolder", "INBOX",
                                "destinationFolder", "Archive", "maxConcurrency", "1",
                                "recovery.repeatable", "repeatable")),
                new GraphNode("delete", ai.ravenroot.core.graph.NodeKind.BEHAVIOR,
                        "mail.imap.delete", Map.of("profile", "reader",
                                "sourceFolder", "INBOX", "deleteMode", "HARD_DELETE",
                                "hardDeleteAcknowledgement", "I_UNDERSTAND_EXPUNGE_IS_PERMANENT",
                                "recovery.repeatable", "not-repeatable")),
                GraphNode.error("error"), GraphNode.end("end")), List.of());
        byte[] xml;
        try (var graph = GraphManager.from(definition);
             var output = new ByteArrayOutputStream()) {
            graph.writeGraphMl(output);
            xml = output.toByteArray();
        }
        String text = new String(xml, StandardCharsets.UTF_8);
        assertFalse(text.contains(sentinel));
        assertFalse(text.contains("credentialRef"));
        assertFalse(text.contains("password"));
        assertFalse(text.contains("host"));
        try (var reread = GraphManager.readGraphMl(new ByteArrayInputStream(xml))) {
            assertEquals("mail.imap.move", reread.definition().node("move").behavior());
            assertEquals("reader", reread.definition().node("move").properties().get("profile"));
            assertEquals("Archive",
                    reread.definition().node("move").properties().get("destinationFolder"));
            assertEquals("mail.imap.delete", reread.definition().node("delete").behavior());
            assertEquals("HARD_DELETE",
                    reread.definition().node("delete").properties().get("deleteMode"));
        }
    }

    private static NodeMessage message() {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("r", "t", "s", PrincipalType.USER, "i"), id, id, id, id, Set.of(), "mail",
                Map.of("version", "mail.send.v1", "to", List.of("to@example.test"), "text", "body"), Map.of());
    }
    private static String throwableText(Throwable value) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = value; current != null; current = current.getCause()) text.append(current).append(' ').append(current.getMessage()).append(' ');
        return text.toString();
    }
}
