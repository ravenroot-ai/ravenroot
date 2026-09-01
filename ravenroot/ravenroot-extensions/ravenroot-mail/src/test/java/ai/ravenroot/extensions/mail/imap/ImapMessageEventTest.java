package ai.ravenroot.extensions.mail.imap;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ImapMessageEventTest {
    @Test void allowedHeadersAreBoundedSanitizedAndSensitiveOrDisallowedHeadersNeverEscape() {
        MimeMessage message = sizedMessageWithHeaders(Map.of(
                "x-trace", new String[]{"safe\r\nnext\u0000value"},
                "x-utf8", new String[]{"😀".repeat(600)},
                "authorization", new String[]{"secret-value"},
                "x-not-allowed", new String[]{"dropped"}));
        var projected = ImapMessageEvent.project(new ImapConsumerProtocol.Item(1, message),
                "reader", "INBOX", 1,
                new ImapMessageEvent.Limits(1_048_576, "metadata", 0,
                        Set.of("x-trace", "x-utf8")), 1);
        @SuppressWarnings("unchecked")
        var headers = (Map<String, java.util.List<String>>) projected.payload().get("headers");
        assertEquals(Set.of("x-trace", "x-utf8"), headers.keySet());
        assertEquals("safe  next value", headers.get("x-trace").getFirst());
        assertTrue(headers.get("x-utf8").getFirst().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= 2_048);
        assertFalse(projected.payload().toString().contains("secret-value"));
        assertFalse(projected.payload().toString().contains("dropped"));
        @SuppressWarnings("unchecked")
        var truncated = (java.util.List<String>) projected.payload().get("truncatedFields");
        assertTrue(truncated.contains("headers.x-utf8"));
        @SuppressWarnings("unchecked")
        var sanitized = (java.util.List<String>) projected.payload().get("sanitizedFields");
        assertTrue(sanitized.contains("headers.x-trace"));
    }

    @Test void headerValueCountAndAggregateUtf8BoundsFailDeterministically() {
        String[] tooMany = java.util.stream.IntStream.range(0, 33)
                .mapToObj(index -> "value-" + index).toArray(String[]::new);
        var count = assertThrows(ImapMessageEvent.Invalid.class, () -> ImapMessageEvent.project(
                new ImapConsumerProtocol.Item(1, sizedMessageWithHeaders(Map.of("x-many", tooMany))),
                "reader", "INBOX", 1,
                new ImapMessageEvent.Limits(1_048_576, "metadata", 0, Set.of("x-many")), 1));
        assertEquals("message-header-count-limit", count.safeReason());

        Map<String, String[]> large = new java.util.LinkedHashMap<>();
        for (int index = 0; index < 5; index++)
            large.put("x-large-" + index, new String[]{"é".repeat(1_024)});
        var aggregate = assertThrows(ImapMessageEvent.Invalid.class, () -> ImapMessageEvent.project(
                new ImapConsumerProtocol.Item(1, sizedMessageWithHeaders(large)),
                "reader", "INBOX", 1,
                new ImapMessageEvent.Limits(1_048_576, "metadata", 0, large.keySet()), 1));
        assertEquals("message-header-aggregate-limit", aggregate.safeReason());
    }

    @Test void partAttachmentAndAddressCapsFailDeterministically() throws Exception {
        MimeMessage tooManyParts = sizedMessage();
        MimeMultipart parts = new MimeMultipart();
        for (int index = 0; index < 65; index++) {
            MimeBodyPart part = new MimeBodyPart();
            part.setText("part");
            parts.addBodyPart(part);
        }
        tooManyParts.setContent(parts);
        tooManyParts.saveChanges();
        assertInvalid("message-mime-limit", tooManyParts);

        MimeMessage tooManyAttachments = sizedMessage();
        MimeMultipart attachments = new MimeMultipart();
        for (int index = 0; index < 21; index++) {
            MimeBodyPart part = new MimeBodyPart() {
                @Override public int getSize() { return 32; }
            };
            part.setFileName("a" + index);
            part.setText("metadata only");
            attachments.addBodyPart(part);
        }
        tooManyAttachments.setContent(attachments);
        tooManyAttachments.saveChanges();
        assertInvalid("message-attachment-limit", tooManyAttachments);

        MimeMessage tooManyAddresses = sizedMessage();
        InternetAddress[] addresses = new InternetAddress[51];
        for (int index = 0; index < addresses.length; index++)
            addresses[index] = new InternetAddress("a" + index + "@example.test");
        tooManyAddresses.addFrom(addresses);
        assertInvalid("message-address-limit", tooManyAddresses);
    }

    @Test void utf8WorstCasePreviewRemainsWithinTheDeclaredWireCeiling() throws Exception {
        MimeMessage message = sizedMessage();
        message.setText("😀".repeat(65_536));
        message.saveChanges();
        var projected = ImapMessageEvent.project(new ImapConsumerProtocol.Item(1, message),
                "reader", "INBOX", 1,
                new ImapMessageEvent.Limits(1_048_576, "preview", 65_536), 1);
        assertEquals("message", projected.payload().get("kind"));
        assertTrue(projected.payload().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                < ImapMessageEvent.MAX_EVENT_WIRE_BYTES);
    }

    @Test void unknownSizesAreNeverPoisonEvidenceOrUnboundedMultipartReads() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean materialized = new java.util.concurrent.atomic.AtomicBoolean();
        MimeMessage unknownMessage = new MimeMessage(Session.getInstance(new Properties())) {
            @Override public int getSize() { return -1; }
            @Override public Object getContent() {
                materialized.set(true);
                throw new AssertionError("unknown multipart must not be materialized");
            }
        };
        unknownMessage.setContent(new MimeMultipart());
        unknownMessage.saveChanges();
        var unavailable = assertThrows(ImapMessageEvent.Unavailable.class, () -> ImapMessageEvent.project(
                new ImapConsumerProtocol.Item(1, unknownMessage), "reader", "INBOX", 1,
                new ImapMessageEvent.Limits(1_048_576, "metadata", 0), 1));
        assertEquals("message-size-unavailable", unavailable.safeReason());
        assertFalse(materialized.get());

        MimeMessage message = sizedMessage();
        MimeMultipart multipart = new MimeMultipart();
        MimeBodyPart attachment = new MimeBodyPart() {
            @Override public int getSize() { return -1; }
        };
        attachment.setFileName("unknown.bin");
        attachment.setContent("opaque", "application/octet-stream");
        multipart.addBodyPart(attachment);
        message.setContent(multipart);
        message.saveChanges();

        var projected = ImapMessageEvent.project(new ImapConsumerProtocol.Item(1, message),
                "reader", "INBOX", 1,
                new ImapMessageEvent.Limits(1_048_576, "metadata", 0), 1);
        assertEquals("message", projected.payload().get("kind"));
        assertEquals(524_288, projected.payload().get("size"));
        @SuppressWarnings("unchecked")
        var content = (java.util.Map<String, Object>) projected.payload().get("content");
        @SuppressWarnings("unchecked")
        var attachments = (java.util.List<java.util.Map<String, Object>>) content.get("attachments");
        assertEquals(-1, attachments.getFirst().get("size"));
    }

    private static MimeMessage sizedMessage() {
        return new MimeMessage(Session.getInstance(new Properties())) {
            @Override public int getSize() { return 524_288; }
        };
    }

    private static MimeMessage sizedMessageWithHeaders(Map<String, String[]> headers) {
        Map<String, String[]> normalized = new java.util.HashMap<>();
        headers.forEach((name, values) -> normalized.put(name.toLowerCase(java.util.Locale.ROOT), values));
        return new MimeMessage(Session.getInstance(new Properties())) {
            @Override public int getSize() { return 524_288; }
            @Override public String[] getHeader(String name) throws jakarta.mail.MessagingException {
                String[] values = normalized.get(name.toLowerCase(java.util.Locale.ROOT));
                return values == null ? super.getHeader(name) : values.clone();
            }
        };
    }

    private static void assertInvalid(String reason, MimeMessage message) {
        var invalid = assertThrows(ImapMessageEvent.Invalid.class, () -> ImapMessageEvent.project(
                new ImapConsumerProtocol.Item(1, message), "reader", "INBOX", 1,
                new ImapMessageEvent.Limits(1_048_576, "preview", 64), 1));
        assertEquals(reason, invalid.safeReason());
    }
}
