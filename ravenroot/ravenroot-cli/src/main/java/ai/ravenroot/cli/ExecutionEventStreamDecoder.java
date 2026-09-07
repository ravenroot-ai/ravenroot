package ai.ravenroot.cli;

import ai.ravenroot.api.application.StableEdgeId;
import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded, incremental decoder for captured {@code GET /v1/events} SSE bodies. */
final class ExecutionEventStreamDecoder {
    static final int MAX_FRAME_BYTES = StableEdgeId.SSE_FRAME_MAX_BYTES;
    static final PayloadLimits EVENT_JSON_LIMITS = new PayloadLimits(
            MAX_FRAME_BYTES,
            PayloadLimits.DEFAULTS.maxDepth(),
            PayloadLimits.DEFAULTS.maxCollectionSize(),
            PayloadLimits.DEFAULTS.maxValueCount(),
            MAX_FRAME_BYTES,
            PayloadLimits.DEFAULTS.maxKeyLength());

    private final InputStream input;
    private final PrintStream output;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final StringBuilder data = new StringBuilder();
    private String eventName = "";
    private String lastEventId = "";
    private boolean dataSeen;
    private boolean firstLine = true;
    private boolean pendingCarriageReturn;
    private int frameBytes;
    private long frameNumber = 1;

    private ExecutionEventStreamDecoder(InputStream input, PrintStream output) {
        this.input = input;
        this.output = output;
    }

    static void decode(InputStream input, PrintStream output) throws IOException {
        new ExecutionEventStreamDecoder(input, output).decode();
    }

    private void decode() throws IOException {
        byte[] chunk = new byte[4096];
        int read;
        while ((read = input.read(chunk)) >= 0) {
            for (int index = 0; index < read; index++) {
                accept(chunk[index]);
            }
        }
        if (pendingCarriageReturn) {
            pendingCarriageReturn = false;
            finishLine();
        } else if (line.size() > 0) {
            finishLine();
        }
        if (dataSeen) {
            throw failure(FailureCode.INCOMPLETE_EVENT_STREAM);
        }
    }

    private void accept(byte value) {
        if (pendingCarriageReturn) {
            if (value == '\n') {
                countByte();
                pendingCarriageReturn = false;
                finishLine();
                return;
            }
            pendingCarriageReturn = false;
            finishLine();
        }
        countByte();
        if (value == '\r') {
            pendingCarriageReturn = true;
        } else if (value == '\n') {
            finishLine();
        } else {
            line.write(value);
        }
    }

    private void countByte() {
        if (++frameBytes > MAX_FRAME_BYTES) {
            throw failure(FailureCode.FRAME_TOO_LARGE);
        }
    }

    private void finishLine() {
        String text = strictUtf8(line.toByteArray());
        line.reset();
        if (firstLine) {
            firstLine = false;
            if (!text.isEmpty() && text.charAt(0) == '\ufeff') {
                text = text.substring(1);
            }
        }
        if (text.isEmpty()) {
            dispatch();
            frameBytes = 0;
            frameNumber++;
            return;
        }
        if (text.charAt(0) == ':') {
            return;
        }
        int colon = text.indexOf(':');
        String field = colon < 0 ? text : text.substring(0, colon);
        String value = colon < 0 ? "" : text.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        switch (field) {
            case "event" -> eventName = value;
            case "data" -> {
                if (dataSeen) data.append('\n');
                data.append(value);
                dataSeen = true;
            }
            case "id" -> {
                if (value.indexOf('\0') < 0) lastEventId = value;
            }
            default -> {
                // retry and unknown fields affect an EventSource transport, not this offline decoder.
            }
        }
    }

    private void dispatch() {
        if (!dataSeen) {
            resetEvent();
            return;
        }
        String name = eventName.isEmpty() ? "message" : eventName;
        byte[] json = data.toString().getBytes(StandardCharsets.UTF_8);
        if ("execution".equals(name)) {
            PayloadValue normalized = normalizeExecution(parseObject(json), lastEventId);
            emit(name, lastEventId, normalized);
        } else if ("stream-truncated".equals(name)) {
            PayloadValue.MapValue control = parseObject(json);
            requireControlCode(control, "STREAM_RETENTION_EXCEEDED");
            if (controlInteger(control, "retainedFrom") <= 0
                    || controlInteger(control, "resumeFrom") < 0) {
                throw failure(FailureCode.INVALID_EVENT_JSON);
            }
            emit(name, lastEventId, control);
            throw failure(FailureCode.STREAM_RETENTION_EXCEEDED);
        } else if ("stream-overrun".equals(name)) {
            PayloadValue.MapValue control = parseObject(json);
            requireControlCode(control, "STREAM_CONSUMER_TOO_SLOW");
            controlInteger(control, "resumeAfter");
            emit(name, lastEventId, control);
            throw failure(FailureCode.STREAM_CONSUMER_TOO_SLOW);
        }
        // Unknown named events are intentionally skipped rather than fabricated as execution data.
        resetEvent();
    }

    private void resetEvent() {
        eventName = "";
        data.setLength(0);
        dataSeen = false;
    }

    private PayloadValue.MapValue parseObject(byte[] json) {
        try {
            PayloadValue parsed = PayloadJson.read(json, EVENT_JSON_LIMITS);
            if (parsed instanceof PayloadValue.MapValue object) return object;
        } catch (PayloadException ignored) {
            // The public CLI classification deliberately hides parser details and input fragments.
        }
        throw failure(FailureCode.INVALID_EVENT_JSON);
    }

    private PayloadValue normalizeExecution(PayloadValue.MapValue object, String transportId) {
        long cursor = canonicalLong(transportId);
        Map<String, PayloadValue> members = new LinkedHashMap<>(object.entries());
        String type = optionalText(members, "type");
        String eventType = optionalText(members, "eventType");

        PayloadValue version = members.get("schemaVersion");
        if (version == null) {
            if (eventType == null) eventType = type;
            if (eventType == null || eventType.isBlank() || type != null && !type.equals(eventType)) {
                throw failure(FailureCode.INVALID_EXECUTION_EVENT);
            }
            members.put("eventType", PayloadValue.of(eventType));
            normalizeLegacy(members, cursor, transportId);
        } else {
            if (!(version instanceof PayloadValue.IntegerValue integer) || integer.value() != 1) {
                throw failure(FailureCode.UNSUPPORTED_EVENT_SCHEMA);
            }
            if (eventType == null || eventType.isBlank() || type != null && !type.equals(eventType)) {
                throw failure(FailureCode.INVALID_EXECUTION_EVENT);
            }
            normalizeVersionOne(members, cursor, transportId);
        }
        return PayloadValue.map(members);
    }

    private void normalizeVersionOne(Map<String, PayloadValue> members, long cursor, String transportId) {
        String source = requiredText(members, "source");
        if (!transportId.equals(requiredText(members, "id"))) invalidExecution();
        requireInstant(members, "occurredAt");
        requireUuid(members, "processInstanceId");
        requireUuid(members, "traversalId");
        validateOptionalText(members, "graphVersion");
        validateOptionalText(members, "description");
        validateOptionalNullableUuid(members, "invocationId");
        validateOptionalNullableUuid(members, "attemptId");
        validateOptionalNullableText(members, "nodeId");
        validateOptionalNullableText(members, "edgeId");
        switch (source) {
            case "RING" -> {
                requireCursor(members, "sequence", cursor);
                requireText(members, "engineId");
                String traversalId = requiredText(members, "traversalId");
                if (!traversalId.equals(requiredText(members, "executionId"))) invalidExecution();
                if (optionalText(members, "type") == null) invalidExecution();
                requireNonNegative(members, "activeInstances");
                requireNonNegative(members, "inFlightArrivals");
                requireBoolean(members, "fallback");
                requireNullableText(members, "publicReason");
                validatePublicReason(members.get("publicReason"));
                requireNullableText(members, "message");
                requireBoolean(members, "messageRedacted");
                requireBoolean(members, "messageTruncated");
                validateOptionalBoolean(members, "outputRedacted");
                validateOptionalBoolean(members, "outputTruncated");
                requireNullableNumber(members, "processingDuration");
                rejectMembers(members, "journalOffset", "streamSequence", "eventId", "causationId", "handlerId");
            }
            case "DURABLE" -> {
                if (cursor <= 0) invalidExecution();
                requireCursor(members, "journalOffset", cursor);
                if (requiredInteger(members, "streamSequence") <= 0) invalidExecution();
                requireUuid(members, "eventId");
                requireNullableUuid(members, "causationId");
                requireNullableUuid(members, "handlerId");
                rejectMembers(members, "sequence", "engineId", "executionId", "activeInstances",
                        "inFlightArrivals", "fallback", "publicReason", "message", "messageRedacted",
                        "messageTruncated", "output", "outputRedacted", "outputTruncated",
                        "processingDuration");
            }
            default -> throw failure(FailureCode.INVALID_EXECUTION_EVENT);
        }
    }

    private void normalizeLegacy(Map<String, PayloadValue> members, long cursor, String transportId) {
        boolean ring = members.containsKey("sequence");
        boolean durable = members.containsKey("journalOffset");
        if (ring == durable) invalidExecution();
        String inferred = ring ? "RING" : "DURABLE";
        requireCursor(members, ring ? "sequence" : "journalOffset", cursor);
        if (durable && cursor <= 0) invalidExecution();
        String declaredSource = optionalText(members, "source");
        if (declaredSource != null && !inferred.equals(declaredSource)) invalidExecution();
        String declaredId = optionalText(members, "id");
        if (declaredId != null && !transportId.equals(declaredId)) invalidExecution();
        String traversalId = optionalText(members, "traversalId");
        String executionId = optionalText(members, "executionId");
        if (traversalId != null && executionId != null && !traversalId.equals(executionId)) invalidExecution();
        members.put("source", PayloadValue.of(inferred));
    }

    private void requireControlCode(PayloadValue.MapValue object, String code) {
        if (!(object.entries().get("code") instanceof PayloadValue.TextValue value)
                || !code.equals(value.value())) {
            throw failure(FailureCode.INVALID_EVENT_JSON);
        }
    }

    private long controlInteger(PayloadValue.MapValue object, String name) {
        if (object.entries().get(name) instanceof PayloadValue.IntegerValue integer) {
            return integer.value();
        }
        throw failure(FailureCode.INVALID_EVENT_JSON);
    }

    private void emit(String event, String id, PayloadValue dataValue) {
        String line = "{\"event\":" + PayloadJson.write(PayloadValue.of(event))
                + ",\"id\":" + (id.isEmpty() ? "null" : PayloadJson.write(PayloadValue.of(id)))
                + ",\"data\":" + PayloadJson.write(dataValue) + "}";
        output.println(line);
        output.flush();
    }

    private String strictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw failure(FailureCode.INVALID_UTF8);
        }
    }

    private long canonicalLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (!Long.toString(parsed).equals(value)) invalidExecution();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw failure(FailureCode.INVALID_EXECUTION_EVENT);
        }
    }

    private String optionalText(Map<String, PayloadValue> members, String name) {
        PayloadValue value = members.get(name);
        if (value == null) return null;
        if (value instanceof PayloadValue.TextValue text) return text.value();
        invalidExecution();
        return null;
    }

    private String requiredText(Map<String, PayloadValue> members, String name) {
        String value = requireText(members, name);
        if (value == null || value.isBlank()) invalidExecution();
        return value;
    }

    private String requireText(Map<String, PayloadValue> members, String name) {
        String value = optionalText(members, name);
        if (value == null) invalidExecution();
        return value;
    }

    private long requiredInteger(Map<String, PayloadValue> members, String name) {
        if (members.get(name) instanceof PayloadValue.IntegerValue integer) return integer.value();
        invalidExecution();
        return 0;
    }

    private void requireCursor(Map<String, PayloadValue> members, String name, long expected) {
        if (requiredInteger(members, name) != expected) invalidExecution();
    }

    private void requireInstant(Map<String, PayloadValue> members, String name) {
        try {
            Instant.parse(requiredText(members, name));
        } catch (DateTimeParseException invalid) {
            invalidExecution();
        }
    }

    private void requireUuid(Map<String, PayloadValue> members, String name) {
        try {
            String text = requiredText(members, name);
            if (!UUID.fromString(text).toString().equalsIgnoreCase(text)) invalidExecution();
        } catch (IllegalArgumentException invalid) {
            invalidExecution();
        }
    }

    private void requireNullableUuid(Map<String, PayloadValue> members, String name) {
        PayloadValue value = members.get(name);
        if (value instanceof PayloadValue.NullValue) return;
        requireUuid(members, name);
    }

    private void requireNullableText(Map<String, PayloadValue> members, String name) {
        PayloadValue value = members.get(name);
        if (value instanceof PayloadValue.NullValue || value instanceof PayloadValue.TextValue) return;
        invalidExecution();
    }

    private void validateOptionalText(Map<String, PayloadValue> members, String name) {
        if (members.containsKey(name) && !(members.get(name) instanceof PayloadValue.TextValue)) {
            invalidExecution();
        }
    }

    private void validateOptionalNullableText(Map<String, PayloadValue> members, String name) {
        if (members.containsKey(name)) requireNullableText(members, name);
    }

    private void validateOptionalNullableUuid(Map<String, PayloadValue> members, String name) {
        if (members.containsKey(name)) requireNullableUuid(members, name);
    }

    private void validateOptionalBoolean(Map<String, PayloadValue> members, String name) {
        if (members.containsKey(name)) requireBoolean(members, name);
    }

    private void validatePublicReason(PayloadValue value) {
        if (!(value instanceof PayloadValue.TextValue text)) return;
        String reason = text.value();
        if (reason.length() > 64 || !reason.matches("[A-Za-z0-9._:-]*")) invalidExecution();
    }

    private void requireBoolean(Map<String, PayloadValue> members, String name) {
        if (!(members.get(name) instanceof PayloadValue.BooleanValue)) invalidExecution();
    }

    private void requireNonNegative(Map<String, PayloadValue> members, String name) {
        if (requiredInteger(members, name) < 0) invalidExecution();
    }

    private void requireNullableNumber(Map<String, PayloadValue> members, String name) {
        PayloadValue value = members.get(name);
        if (value instanceof PayloadValue.NullValue) return;
        if (value instanceof PayloadValue.IntegerValue integer && integer.value() >= 0) return;
        if (value instanceof PayloadValue.DecimalValue decimal && decimal.value() >= 0) return;
        invalidExecution();
    }

    private void rejectMembers(Map<String, PayloadValue> members, String... names) {
        for (String name : names) {
            if (members.containsKey(name)) invalidExecution();
        }
    }

    private void invalidExecution() {
        throw failure(FailureCode.INVALID_EXECUTION_EVENT);
    }

    private DecodeFailure failure(FailureCode code) {
        return new DecodeFailure(code, frameNumber);
    }

    enum FailureCode {
        FRAME_TOO_LARGE,
        INVALID_UTF8,
        INVALID_EVENT_JSON,
        INVALID_EXECUTION_EVENT,
        UNSUPPORTED_EVENT_SCHEMA,
        INCOMPLETE_EVENT_STREAM,
        STREAM_RETENTION_EXCEEDED,
        STREAM_CONSUMER_TOO_SLOW,
        EVENT_STREAM_IO_FAILED
    }

    static final class DecodeFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final FailureCode code;
        private final long frame;

        DecodeFailure(FailureCode code, long frame) {
            super(code.name());
            this.code = code;
            this.frame = frame;
        }

        FailureCode code() {
            return code;
        }

        long frame() {
            return frame;
        }
    }
}
