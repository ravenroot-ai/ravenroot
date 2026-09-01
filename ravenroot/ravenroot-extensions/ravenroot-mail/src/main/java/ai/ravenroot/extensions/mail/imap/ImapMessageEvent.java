package ai.ravenroot.extensions.mail.imap;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeUtility;

import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded wire projection. No Jakarta Mail object, profile, endpoint or credential escapes. */
final class ImapMessageEvent {
    static final String VERSION = "mail.imap.message.v1";
    private static final int MAX_MIME_DEPTH = 8;
    private static final int MAX_MIME_PARTS = 64;
    private static final int MAX_ATTACHMENTS = 20;
    private static final int MAX_ADDRESSES = 50;
    private static final int MAX_ADDRESS_BYTES = 512;
    private static final int MAX_ADDRESSES_BYTES = 8_192;
    private static final int MAX_SUBJECT_BYTES = 512;
    private static final int MAX_MESSAGE_ID_BYTES = 512;
    private static final int MAX_ATTACHMENT_FIELD_BYTES = 256;
    private static final int MAX_ATTACHMENT_METADATA_BYTES = 8_192;
    private static final int MAX_HEADER_VALUES = 32;
    private static final int MAX_HEADER_VALUE_BYTES = 2_048;
    private static final int MAX_HEADER_AGGREGATE_BYTES = 8_192;
    static final int MAX_EVENT_WIRE_BYTES = 1_048_576;

    private ImapMessageEvent() { }

    static Projected project(ImapConsumerProtocol.Item item, String profile, String sourceFolder, long uidValidity,
                             Limits limits, int attempt) {
        requireIdentity(sourceFolder, uidValidity, item.uid());
        try {
            Message message = item.message();
            int size = message.getSize();
            // Jakarta Mail uses -1 for "unknown". It is not deterministic poison evidence, but
            // multipart getContent may materialize an unbounded body before our per-preview stream
            // counter can run, so reconnect/retry without checkpoint rather than traversing it.
            if (size < 0) throw new Unavailable("message-size-unavailable");
            if (size > limits.maxMessageBytes()) throw new Invalid("message-size-invalid");
            Projection projection = new Projection(limits);
            Map<String, Object> event = identity("message", sourceFolder, uidValidity, item.uid(), attempt);
            Bounded messageId = bounded(header(message, "Message-ID"), MAX_MESSAGE_ID_BYTES);
            Bounded subject = bounded(Objects.toString(message.getSubject(), ""), MAX_SUBJECT_BYTES);
            event.put("messageId", messageId.value());
            event.put("subject", subject.value());
            event.put("sentAt", message.getSentDate() == null ? "" : message.getSentDate().toInstant().toString());
            event.put("receivedAt", message.getReceivedDate() == null ? "" : message.getReceivedDate().toInstant().toString());
            event.put("from", addresses(message.getFrom(), projection, "from"));
            event.put("to", addresses(message.getRecipients(Message.RecipientType.TO), projection, "to"));
            event.put("cc", addresses(message.getRecipients(Message.RecipientType.CC), projection, "cc"));
            event.put("replyTo", addresses(message.getReplyTo(), projection, "replyTo"));
            event.put("headers", headers(message, limits.allowedHeaders(), projection));
            event.put("flags", Arrays.stream(message.getFlags().getSystemFlags()).map(Object::toString)
                    .limit(32).toList());
            event.put("size", size);
            if (messageId.truncated()) projection.truncated.add("messageId");
            if (subject.truncated()) projection.truncated.add("subject");
            projection.visit(message, 0);
            event.put("content", Map.of("mode", limits.contentMode(),
                    "textPreview", projection.text,
                    "htmlPreview", projection.html,
                    "attachments", List.copyOf(projection.attachments)));
            event.put("truncated", !projection.truncated.isEmpty());
            event.put("truncatedFields", List.copyOf(projection.truncated));
            event.put("sanitizedFields", List.copyOf(projection.sanitized));
            event.put("correlation", correlation(sourceFolder, uidValidity, item.uid()));
            Map<String, Object> immutable = Map.copyOf(event);
            if (wireUpperBound(immutable) > MAX_EVENT_WIRE_BYTES)
                throw new Invalid("message-wire-limit");
            return new Projected(immutable, key(profile, sourceFolder, uidValidity, item.uid()));
        } catch (Invalid invalid) {
            throw invalid;
        } catch (Unavailable unavailable) {
            throw unavailable;
        } catch (Exception hostile) {
            // Lazy Jakarta Mail getters and body streams perform provider I/O. Their failure is not
            // evidence that the message is malformed: poisoning it would advance the UID checkpoint
            // past data that may succeed after reconnect. Only explicit deterministic guards above
            // create Invalid; everything else is retried without checkpoint advancement.
            throw new Unavailable("message-projection-unavailable");
        }
    }

    static Projected poison(String profile, String sourceFolder, long uidValidity, long uid,
                            String reason, int attempt) {
        requireIdentity(sourceFolder, uidValidity, uid);
        Map<String, Object> event = identity("poison", sourceFolder, uidValidity, uid, attempt);
        event.put("failure", Map.of("type", "projection", "reason", safeReason(reason)));
        event.put("correlation", correlation(sourceFolder, uidValidity, uid));
        return new Projected(Map.copyOf(event), key(profile, sourceFolder, uidValidity, uid));
    }

    static String sourceId(String nodeId, String profile, String folder, long uidValidity) {
        requireIdentity(folder, uidValidity, 1);
        return "mail.imap.consume/" + encode(nodeId) + "/" + encode(profile) + "/"
                + encode(folder) + "/" + Long.toUnsignedString(uidValidity);
    }

    static String key(String profile, String folder, long uidValidity, long uid) {
        requireIdentity(folder, uidValidity, uid);
        return "mail.imap.message.v1/" + encode(profile) + "/" + encode(folder) + "/"
                + Long.toUnsignedString(uidValidity) + "/" + Long.toUnsignedString(uid);
    }

    private static Map<String, Object> identity(String kind, String folder, long validity, long uid, int attempt) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("version", VERSION);
        event.put("kind", kind);
        event.put("sourceFolder", folder);
        event.put("uidValidity", validity);
        event.put("uid", uid);
        event.put("checkpoint", Map.of(
                "version", "mail.imap.checkpoint.v1",
                "sourceFolder", folder,
                "uidValidity", validity,
                "candidateDeliveredThroughUid", uid));
        event.put("deliveryAttempt", attempt);
        return event;
    }

    private static String correlation(String folder, long validity, long uid) {
        return "imap:" + encode(folder) + ":" + Long.toUnsignedString(validity) + ":"
                + Long.toUnsignedString(uid);
    }

    private static String encode(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("invalid identity component");
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static int wireUpperBound(Object value) {
        long result = wireUpperBoundLong(value);
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    private static long wireUpperBoundLong(Object value) {
        if (value == null) return 4;
        if (value instanceof String text) {
            long bytes = 2;
            for (int offset = 0; offset < text.length();) {
                int point = text.codePointAt(offset);
                if (point == '"' || point == '\\' || point < 0x20) bytes += 6;
                else bytes += new String(Character.toChars(point)).getBytes(StandardCharsets.UTF_8).length;
                offset += Character.charCount(point);
            }
            return bytes;
        }
        if (value instanceof Map<?, ?> map) {
            long bytes = 2;
            for (Map.Entry<?, ?> entry : map.entrySet())
                bytes += wireUpperBoundLong(Objects.toString(entry.getKey()))
                        + 1 + wireUpperBoundLong(entry.getValue()) + 1;
            return bytes;
        }
        if (value instanceof Iterable<?> values) {
            long bytes = 2;
            for (Object item : values) bytes += wireUpperBoundLong(item) + 1;
            return bytes;
        }
        return Objects.toString(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private static void requireIdentity(String folder, long validity, long uid) {
        if (!ImapConsumerPolicy.folder(folder) || validity < 1 || validity > 0xffff_ffffL
                || uid < 1 || uid > 0xffff_ffffL) throw new IllegalArgumentException("invalid IMAP identity");
    }

    private static String safeReason(String reason) {
        return reason != null && reason.matches("[a-z][a-z0-9-]{0,63}")
                ? reason : "message-projection-failed";
    }

    private static String header(Message message, String name) throws Exception {
        String[] values = message.getHeader(name);
        return values == null || values.length == 0 ? "" : values[0];
    }

    private static List<String> addresses(Address[] values, Projection projection, String field) {
        if (values == null) return List.of();
        if (values.length > MAX_ADDRESSES) throw new Invalid("message-address-limit");
        List<String> result = new ArrayList<>(values.length);
        int bytes = 0;
        for (Address value : values) {
            Bounded bounded = bounded(Objects.toString(value, ""), MAX_ADDRESS_BYTES);
            bytes = Math.addExact(bytes, bounded.value().getBytes(StandardCharsets.UTF_8).length);
            if (bytes > MAX_ADDRESSES_BYTES) throw new Invalid("message-address-limit");
            result.add(bounded.value());
            if (bounded.truncated()) projection.truncated.add(field);
        }
        return List.copyOf(result);
    }

    private static Map<String, List<String>> headers(Message message, Set<String> allowed,
                                                      Projection projection) throws Exception {
        Map<String, List<String>> result = new LinkedHashMap<>();
        int count = 0;
        int aggregateBytes = 0;
        for (String name : allowed.stream().sorted().toList()) {
            if (ImapConsumerPolicy.sensitiveHeader(name))
                throw new Invalid("message-header-policy-invalid");
            String[] values = message.getHeader(name);
            if (values == null || values.length == 0) continue;
            count = Math.addExact(count, values.length);
            if (count > MAX_HEADER_VALUES) throw new Invalid("message-header-count-limit");
            List<String> boundedValues = new ArrayList<>(values.length);
            for (String value : values) {
                HeaderValue bounded = boundedHeaderValue(value);
                aggregateBytes = Math.addExact(aggregateBytes,
                        name.getBytes(StandardCharsets.UTF_8).length
                                + bounded.value().getBytes(StandardCharsets.UTF_8).length);
                if (aggregateBytes > MAX_HEADER_AGGREGATE_BYTES)
                    throw new Invalid("message-header-aggregate-limit");
                boundedValues.add(bounded.value());
                if (bounded.truncated()) projection.truncated.add("headers." + name);
                if (bounded.sanitized()) projection.sanitized.add("headers." + name);
            }
            result.put(name, List.copyOf(boundedValues));
        }
        return Map.copyOf(result);
    }

    private static HeaderValue boundedHeaderValue(String value) {
        String raw = Objects.toString(value, "");
        StringBuilder clean = new StringBuilder(raw.length());
        boolean sanitized = false;
        for (int offset = 0; offset < raw.length();) {
            int point = raw.codePointAt(offset);
            if (Character.isISOControl(point)) {
                clean.append(' ');
                sanitized = true;
            } else {
                clean.appendCodePoint(point);
            }
            offset += Character.charCount(point);
        }
        Bounded bounded = bounded(clean.toString(), MAX_HEADER_VALUE_BYTES);
        return new HeaderValue(bounded.value(), bounded.truncated(), sanitized);
    }

    private static Bounded bounded(String value, int maximumBytes) {
        String clean = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
        byte[] all = clean.getBytes(StandardCharsets.UTF_8);
        if (all.length <= maximumBytes) return new Bounded(clean, false);
        StringBuilder prefix = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < clean.length();) {
            int point = clean.codePointAt(offset);
            String next = new String(Character.toChars(point));
            int width = next.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + width > maximumBytes) break;
            prefix.append(next); bytes += width; offset += Character.charCount(point);
        }
        return new Bounded(prefix.toString(), true);
    }

    record Limits(int maxMessageBytes, String contentMode, int previewChars, Set<String> allowedHeaders) {
        Limits {
            if (maxMessageBytes < 1 || maxMessageBytes > 1_048_576
                    || !java.util.Set.of("metadata", "preview").contains(contentMode)
                    || previewChars < 0 || previewChars > 65_536
                    || contentMode.equals("metadata") && previewChars != 0)
                throw new IllegalArgumentException("invalid projection limits");
            allowedHeaders = ImapConsumerPolicy.parseHeaders(String.join(",", allowedHeaders));
        }

        Limits(int maxMessageBytes, String contentMode, int previewChars) {
            this(maxMessageBytes, contentMode, previewChars, Set.of());
        }
    }

    record Projected(Map<String, Object> payload, String idempotentKey) { }
    private record Bounded(String value, boolean truncated) { }
    private record HeaderValue(String value, boolean truncated, boolean sanitized) { }

    static final class Invalid extends RuntimeException {
        private final String safeReason;
        Invalid(String safeReason) { super(safeReason); this.safeReason = safeReason; }
        String safeReason() { return safeReason; }
    }
    static final class Unavailable extends RuntimeException {
        private final String safeReason;
        Unavailable(String safeReason) { super(safeReason); this.safeReason = safeReason; }
        String safeReason() { return safeReason; }
    }

    private static final class Projection {
        private final Limits limits;
        private final List<Map<String, Object>> attachments = new ArrayList<>();
        private final List<String> truncated = new ArrayList<>();
        private final List<String> sanitized = new ArrayList<>();
        private int parts;
        private int inspectedBytes;
        private int attachmentBytes;
        private String text = "";
        private String html = "";

        Projection(Limits limits) { this.limits = limits; }

        void visit(Part part, int depth) throws Exception {
            if (depth > MAX_MIME_DEPTH || ++parts > MAX_MIME_PARTS)
                throw new Invalid("message-mime-limit");
            int declared = part.getSize();
            if (declared > limits.maxMessageBytes()) throw new Invalid("message-size-invalid");
            boolean attachment = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())
                    || part.getFileName() != null;
            if (attachment) {
                addAttachment(part);
                return;
            }
            if (limits.contentMode().equals("preview") && part.isMimeType("text/plain") && text.isEmpty()) {
                text = preview(part, "textPreview");
                return;
            }
            if (limits.contentMode().equals("preview") && part.isMimeType("text/html") && html.isEmpty()) {
                html = preview(part, "htmlPreview");
                return;
            }
            if (!part.isMimeType("multipart/*")) return;
            Object value = part.getContent();
            if (!(value instanceof Multipart multipart)) throw new Invalid("message-mime-invalid");
            int count = multipart.getCount();
            if (count > MAX_MIME_PARTS - parts + 1) throw new Invalid("message-mime-limit");
            for (int index = 0; index < count; index++) visit(multipart.getBodyPart(index), depth + 1);
        }

        private String preview(Part part, String field) throws Exception {
            String charset = new ContentType(part.getContentType()).getParameter("charset");
            java.nio.charset.Charset decoded;
            try { decoded = java.nio.charset.Charset.forName(MimeUtility.javaCharset(
                    charset == null ? "UTF-8" : charset)); }
            catch (RuntimeException invalid) { throw new Invalid("message-charset-invalid"); }
            try (InputStream raw = part.getInputStream(); CountingInputStream counted = new CountingInputStream(raw);
                 Reader reader = new InputStreamReader(counted, decoded)) {
                char[] chars = new char[limits.previewChars() + 1];
                int offset = 0;
                while (offset < chars.length) {
                    int read = reader.read(chars, offset, chars.length - offset);
                    if (read < 0) break;
                    offset += read;
                }
                inspectedBytes = Math.addExact(inspectedBytes, counted.count());
                if (inspectedBytes > limits.maxMessageBytes()) throw new Invalid("message-size-invalid");
                if (offset > limits.previewChars()) truncated.add(field);
                return new String(chars, 0, Math.min(offset, limits.previewChars()));
            }
        }

        private void addAttachment(Part part) throws Exception {
            if (attachments.size() == MAX_ATTACHMENTS) throw new Invalid("message-attachment-limit");
            Bounded name = bounded(Objects.toString(part.getFileName(), ""), MAX_ATTACHMENT_FIELD_BYTES);
            Bounded type = bounded(Objects.toString(part.getContentType(), ""), MAX_ATTACHMENT_FIELD_BYTES);
            int size = part.getSize();
            // Preserve the provider's -1 unknown sentinel without reading attachment content.
            if (size > limits.maxMessageBytes()) throw new Invalid("message-size-invalid");
            attachmentBytes = Math.addExact(attachmentBytes,
                    name.value().getBytes(StandardCharsets.UTF_8).length
                            + type.value().getBytes(StandardCharsets.UTF_8).length + 8);
            if (attachmentBytes > MAX_ATTACHMENT_METADATA_BYTES)
                throw new Invalid("message-attachment-limit");
            attachments.add(Map.of("name", name.value(), "contentType", type.value(), "size", size));
            if (name.truncated() || type.truncated()) truncated.add("attachmentMetadata");
        }

        private final class CountingInputStream extends FilterInputStream {
            private int count;
            CountingInputStream(InputStream input) { super(input); }
            int count() { return count; }
            @Override public int read() throws java.io.IOException {
                int value = super.read();
                if (value >= 0) increment(1);
                return value;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
                int remaining = limits.maxMessageBytes() + 1 - count;
                int read = super.read(bytes, offset, Math.min(length, Math.max(1, remaining)));
                if (read > 0) increment(read);
                return read;
            }
            private void increment(int amount) throws java.io.IOException {
                count += amount;
                if (count > limits.maxMessageBytes()) throw new java.io.IOException("IMAP content limit");
            }
        }
    }
}
