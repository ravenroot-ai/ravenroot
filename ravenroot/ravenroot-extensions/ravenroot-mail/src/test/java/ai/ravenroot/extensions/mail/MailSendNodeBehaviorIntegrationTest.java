package ai.ravenroot.extensions.mail;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.SecurityContext;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MailSendNodeBehaviorIntegrationTest {
    /**
     * Same convention and value as {@link MailSendNodeSmtpProtocolTest#GENEROUS_TIMEOUT_MS}:
     * this file's three real GreenMail round trips ({@link #sendsV1MimeMessageToAnInProcessSmtpServerWithoutAuth()},
     * {@link #deliversOneMessageForRawAndDisplayNameAliasesAcrossRecipientLists()},
     * {@link #deliversRfc4648Base64AndEmbeddedByteArrayAttachmentsWithExactMimeMetadata()}) have the
     * same shape as the tight-budget defect (a real,
     * completed round trip on {@link MailTestSupport#profile}'s fixed 2000ms), just against GreenMail
     * rather than the in-repository fixture. {@link #rejectsRealHeaderLineBreaksBeforeOpeningNetwork()} is
     * correctly left on the tight budget: header injection is rejected before any socket opens, so it has
     * no round trip to be exposed by scheduling jitter.
     */
    private static final int GENEROUS_TIMEOUT_MS = 10_000;

    private GreenMail smtp;

    @AfterEach void stop() { if (smtp != null) smtp.stop(); }

    @Test void sendsV1MimeMessageToAnInProcessSmtpServerWithoutAuth() throws Exception {
        smtp = new GreenMail(ServerSetupTest.SMTP); smtp.start();
        var action = MailTestSupport.action(ref -> { throw new AssertionError("no secret required"); },
                "127.0.0.1", smtp.getSmtp().getPort(), "SMTP", 0, GENEROUS_TIMEOUT_MS);
        var result = action.handle(message(Map.of("version", "mail.send.v1", "to", List.of("to@example.test"),
                "from", "from@example.test", "subject", "Subject", "text", "plain", "html", "<b>html</b>",
                "headers", Map.of("X-Correlation", "safe"), "correlationId", "cid-1"))).toCompletableFuture().join();
        assertEquals("SENT", ((Map<?, ?>) result.payload()).get("status"));
        assertEquals("cid-1", ((Map<?, ?>) result.payload()).get("correlationId"));
        assertEquals(1, smtp.getReceivedMessages().length);
        assertEquals("Subject", smtp.getReceivedMessages()[0].getSubject());
        assertEquals("from@example.test", smtp.getReceivedMessages()[0].getFrom()[0].toString());
    }

    @Test void rejectsRealHeaderLineBreaksBeforeOpeningNetwork() {
        smtp = new GreenMail(ServerSetupTest.SMTP); smtp.start();
        var action = MailTestSupport.action(ref -> { throw new AssertionError("no secret required"); },
                "127.0.0.1", smtp.getSmtp().getPort(), "SMTP", 0);
        var error = assertThrows(java.util.concurrent.CompletionException.class, () -> action.handle(message(Map.of(
                "version", "mail.send.v1", "to", List.of("to@example.test"), "text", "body",
                "headers", Map.of("X-Test", "safe\r\nBcc: attacker@example.test")))).toCompletableFuture().join());
        assertInstanceOf(MailSendException.class, error.getCause());
        assertEquals(0, smtp.getReceivedMessages().length);
    }

    @Test void authenticatesThroughInjectedResolverWithoutLeakingTheSecret() throws Exception {
        try (var server = DeterministicSmtpFixture.acceptingAuthentication("not-in-result")) {
            var action = MailTestSupport.authenticatedAction(
                    ref -> java.util.Optional.of(new SecretValue("not-in-result".toCharArray())),
                    "127.0.0.1", server.port(), "STARTTLS");
            synchronized (MailSendNodeBehaviorIntegrationTest.class) {
                try (var trusted = DeterministicSmtpFixture.trustFixtureCertificate()) {
                    var result = action.handle(message(Map.of("version", "mail.send.v1", "to", List.of("to@example.test"), "text", "body")))
                            .toCompletableFuture().join();
                    assertEquals("SENT", ((Map<?, ?>) result.payload()).get("status"));
                    assertFalse(result.payload().toString().contains("not-in-result"));
                    assertEquals(1, server.dataAccepted());
                }
            }
        }
    }

    @Test void deliversOneMessageForRawAndDisplayNameAliasesAcrossRecipientLists() throws Exception {
        smtp = new GreenMail(ServerSetupTest.SMTP); smtp.start();
        var action = MailTestSupport.action(ref -> { throw new AssertionError("no secret required"); },
                "127.0.0.1", smtp.getSmtp().getPort(), "SMTP", 0, GENEROUS_TIMEOUT_MS);
        var result = action.handle(message(Map.of("version", "mail.send.v1", "to", List.of("One <to@example.test>"),
                "cc", List.of("Two <TO@example.test>"), "bcc", List.of("to@example.test"), "text", "body")))
                .toCompletableFuture().join();
        Map<?, ?> payload = (Map<?, ?>) result.payload();
        assertEquals("SENT", payload.get("status"));
        assertEquals(1, ((List<?>) payload.get("acceptedRecipients")).size());
        assertEquals(1, smtp.getReceivedMessages().length);
    }

    @Test void deliversRfc4648Base64AndEmbeddedByteArrayAttachmentsWithExactMimeMetadata() throws Exception {
        smtp = new GreenMail(ServerSetupTest.SMTP); smtp.start();
        var action = MailTestSupport.action(ref -> { throw new AssertionError("no secret required"); },
                "127.0.0.1", smtp.getSmtp().getPort(), "SMTP", 0, GENEROUS_TIMEOUT_MS);
        byte[] binary = new byte[] {0, 1, 2, 3, 127, (byte) 0xff};
        byte[] raw = "embedded bytes".getBytes(StandardCharsets.UTF_8);
        var result = action.handle(message(Map.of("version", "mail.send.v1", "to", List.of("to@example.test"),
                "text", "body", "attachments", List.of(
                Map.of("name", "payload.bin", "contentType", "application/octet-stream", "content", Base64.getEncoder().encodeToString(binary)),
                Map.of("name", "raw.txt", "contentType", "text/plain", "content", raw))))).toCompletableFuture().join();

        assertEquals("SENT", ((Map<?, ?>) result.payload()).get("status"));
        assertEquals(1, smtp.getReceivedMessages().length);
        jakarta.mail.Multipart multipart = assertInstanceOf(jakarta.mail.Multipart.class, smtp.getReceivedMessages()[0].getContent());
        assertAttachment(multipart, "payload.bin", "application/octet-stream", binary);
        assertAttachment(multipart, "raw.txt", "text/plain", raw);
    }

    private static void assertAttachment(jakarta.mail.Multipart multipart, String name, String type, byte[] expected) throws Exception {
        for (int i = 0; i < multipart.getCount(); i++) {
            jakarta.mail.BodyPart part = multipart.getBodyPart(i);
            if (name.equals(part.getFileName())) {
                assertTrue(part.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith(type));
                assertArrayEquals(expected, part.getInputStream().readAllBytes());
                return;
            }
        }
        fail("missing attachment " + name);
    }

    private static NodeMessage message(Object payload) {
        UUID id = UUID.randomUUID(); return new NodeMessage(new SecurityContext("r","t","s", PrincipalType.USER,"i"), id,id,id,id, Set.of(),"mail",payload,Map.of());
    }
}
