package ai.ravenroot.server.interaction;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Strict parser and bounded JSON encoder for {@code ravenroot.interactions.v1}. */
final class InteractionProtocol {
    private static final int MAX_TOKEN_BYTES = 8_192;
    private static final int MAX_MESSAGE_ID_BYTES = 128;
    private static final int MAX_COMMENT_BYTES = 4_096;

    sealed interface Inbound permits Authenticate, Resume, Acknowledge, Command { }
    record Authenticate(String bearer) implements Inbound { }
    record Resume(long afterJournalOffset) implements Inbound { }
    record Acknowledge(long journalOffset, UUID eventId) implements Inbound { }
    record Command(String messageId, String name, UUID taskId, long generation,
                   byte[] payload, String contentType, String comment) implements Inbound { }

    static Inbound parse(String json, int maxBytes) {
        byte[] encoded = json.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > maxBytes) throw new ProtocolFailure(1009, "message too large");
        PayloadValue parsed;
        try {
            parsed = PayloadJson.read(encoded, new PayloadLimits(maxBytes, 4, 32, 128,
                    maxBytes, 256));
        } catch (RuntimeException invalid) {
            throw new ProtocolFailure(1002, "invalid protocol message");
        }
        if (!(parsed instanceof PayloadValue.MapValue map)) {
            throw new ProtocolFailure(1002, "invalid protocol message");
        }
        Map<String, PayloadValue> fields = map.entries();
        if (integer(fields, "version") != 1) fail();
        String type = text(fields, "type", 32);
        try {
            return switch (type) {
                case "authenticate" -> {
                    exact(fields, "version", "type", "bearer");
                    yield new Authenticate(text(fields, "bearer", MAX_TOKEN_BYTES));
                }
                case "resume" -> {
                    exact(fields, "version", "type", "afterJournalOffset");
                    long after = integer(fields, "afterJournalOffset");
                    if (after < 0) fail();
                    yield new Resume(after);
                }
                case "ack" -> {
                    exact(fields, "version", "type", "journalOffset", "eventId");
                    long offset = integer(fields, "journalOffset");
                    if (offset < 1) fail();
                    yield new Acknowledge(offset, UUID.fromString(text(fields, "eventId", 36)));
                }
                case "command" -> command(fields, maxBytes);
                default -> throw new ProtocolFailure(1002, "unsupported protocol message");
            };
        } catch (ProtocolFailure failure) {
            throw failure;
        } catch (IllegalArgumentException invalid) {
            throw new ProtocolFailure(1002, "invalid protocol message");
        }
    }

    private static Command command(Map<String, PayloadValue> fields, int maxBytes) {
        String name = text(fields, "command", 64);
        Set<String> permitted = switch (name) {
            case "human-task.resolve" -> Set.of("version", "type", "messageId", "command", "taskId",
                    "generation", "payloadBase64", "contentType", "comment");
            case "human-task.deny", "human-task.cancel" -> Set.of("version", "type", "messageId", "command",
                    "taskId", "generation", "comment");
            default -> throw new ProtocolFailure(1002, "unsupported command");
        };
        if (!permitted.containsAll(fields.keySet()) || !fields.keySet().containsAll(
                Set.of("version", "type", "messageId", "command", "taskId", "generation"))) fail();
        long generation = integer(fields, "generation");
        if (generation < 1) fail();
        String comment = optionalText(fields, "comment", MAX_COMMENT_BYTES);
        byte[] payload = null;
        String contentType = null;
        if ("human-task.resolve".equals(name)) {
            String encoded = textAllowEmpty(fields, "payloadBase64", maxBytes);
            payload = decode(encoded);
            if (payload.length > maxBytes) throw new ProtocolFailure(1009, "message too large");
            contentType = optionalTextAllowEmpty(fields, "contentType", 255);
            if (contentType == null || contentType.isBlank()) contentType = "application/octet-stream";
        }
        return new Command(text(fields, "messageId", MAX_MESSAGE_ID_BYTES), name,
                UUID.fromString(text(fields, "taskId", 36)), generation, payload, contentType, comment);
    }

    static String authenticated() {
        return "{\"version\":1,\"type\":\"authenticated\"}";
    }

    static String commandResult(Command command, ai.ravenroot.core.humantask.HumanTaskResult result) {
        String replyTo = ai.ravenroot.server.ExecutionEventWireJson.escape(command.messageId());
        String refusal = switch (result.code()) {
            case NOT_FOUND, UNAUTHORIZED -> "RESOURCE_REFUSED";
            case STALE_GENERATION -> "STALE_GENERATION";
            case PAYLOAD_REFUSED -> "PAYLOAD_REFUSED";
            case UNAVAILABLE -> "RESOURCE_UNAVAILABLE";
            default -> null;
        };
        if (refusal != null) {
            return "{\"version\":1,\"type\":\"error\",\"inReplyTo\":\"" + replyTo
                    + "\",\"code\":\"" + refusal + "\"}";
        }
        String outcome = switch (result.code()) {
            case NOT_FOUND, UNAUTHORIZED -> "refused";
            default -> result.code().name().toLowerCase(java.util.Locale.ROOT);
        };
        boolean disclose = switch (result.code()) {
            case RESOLVED, DENIED, CANCELLED, ALREADY_APPLIED, ALREADY_SETTLED -> true;
            default -> false;
        };
        String generation = disclose && result.task() != null
                ? Long.toString(result.task().generation()) : "null";
        String resume = disclose && result.resumeTraversalId() != null
                ? "\"" + result.resumeTraversalId() + "\"" : "null";
        return "{\"version\":1,\"type\":\"command.result\",\"inReplyTo\":\""
                + replyTo
                + "\",\"outcome\":\"" + outcome
                + "\",\"taskId\":\"" + command.taskId() + "\",\"generation\":" + generation
                + ",\"resumeTraversalId\":" + resume + "}";
    }

    static String event(ai.ravenroot.api.application.DurableExecutionEvent event) {
        return "{\"version\":1,\"type\":\"execution.event\",\"eventId\":\"" + event.eventId()
                + "\",\"event\":" + ai.ravenroot.server.ExecutionEventWireJson.durable(event) + "}";
    }

    static String streamTruncated(long retainedFrom) {
        return "{\"version\":1,\"type\":\"stream.truncated\",\"code\":"
                + "\"STREAM_RETENTION_EXCEEDED\",\"retainedFrom\":" + retainedFrom
                + ",\"resumeFrom\":" + (retainedFrom - 1) + "}";
    }

    private static void exact(Map<String, PayloadValue> fields, String... names) {
        if (!fields.keySet().equals(Set.of(names))) fail();
    }

    private static String text(Map<String, PayloadValue> fields, String name, int maxBytes) {
        if (!(fields.get(name) instanceof PayloadValue.TextValue text)) {
            throw new ProtocolFailure(1002, "invalid protocol message");
        }
        if (text.value().isBlank() || text.value().getBytes(StandardCharsets.UTF_8).length > maxBytes) fail();
        return text.value();
    }

    private static byte[] decode(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException invalid) {
            throw new ProtocolFailure(1002, "invalid protocol message");
        }
    }

    private static String optionalText(Map<String, PayloadValue> fields, String name, int maxBytes) {
        if (!fields.containsKey(name)) return null;
        return textAllowEmpty(fields, name, maxBytes);
    }

    private static String optionalTextAllowEmpty(Map<String, PayloadValue> fields, String name, int maxBytes) {
        if (!fields.containsKey(name)) return null;
        return textAllowEmpty(fields, name, maxBytes);
    }

    private static String textAllowEmpty(Map<String, PayloadValue> fields, String name, int maxBytes) {
        if (!(fields.get(name) instanceof PayloadValue.TextValue text)) {
            throw new ProtocolFailure(1002, "invalid protocol message");
        }
        if (text.value().getBytes(StandardCharsets.UTF_8).length > maxBytes) fail();
        return text.value();
    }

    private static long integer(Map<String, PayloadValue> fields, String name) {
        if (!(fields.get(name) instanceof PayloadValue.IntegerValue integer)) {
            throw new ProtocolFailure(1002, "invalid protocol message");
        }
        return integer.value();
    }

    private static void fail() {
        throw new ProtocolFailure(1002, "invalid protocol message");
    }

    static final class ProtocolFailure extends RuntimeException {
        private final int closeCode;
        ProtocolFailure(int closeCode, String reason) {
            super(reason);
            this.closeCode = closeCode;
        }
        int closeCode() { return closeCode; }
    }
}
