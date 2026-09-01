package ai.ravenroot.extensions.kafka;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the immutable, bounded and serialization-safe kafka.record.v1 traversal payload. */
final class KafkaRecordEvent {
    private KafkaRecordEvent() { }

    static Map<String, Object> from(KafkaConsumerProtocol.Record record, KafkaConsumerProfile profile,
                                    int attempt, String correlationId) {
        int keySize = record.key() == null ? -1 : record.key().length;
        int valueSize = record.value().length;
        if (record.serializedKeySize() > profile.maxKeyBytes() || keySize > profile.maxKeyBytes()
                || record.serializedValueSize() > profile.maxValueBytes() || valueSize > profile.maxValueBytes()
                || safeAdd(Math.max(0, keySize), valueSize) > profile.maxRecordBytes()) {
            throw new InvalidRecord("record-too-large");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", "kafka.record.v1");
        payload.put("topic", record.partition().topic());
        payload.put("partition", record.partition().partition());
        payload.put("offset", record.offset());
        payload.put("timestamp", record.timestamp());
        payload.put("timestampType", record.timestampType());
        payload.put("key", representation(record.key()));
        payload.put("value", representation(record.value()));
        payload.put("headers", headers(record, profile));
        payload.put("serializedKeySize", record.serializedKeySize());
        payload.put("serializedValueSize", record.serializedValueSize());
        if (record.leaderEpoch() != null) payload.put("leaderEpoch", record.leaderEpoch());
        payload.put("group", profile.groupLogicalName());
        payload.put("attempt", attempt);
        payload.put("correlationId", correlationId);
        payload.put("provenance", Map.of(
                "source", "apache-kafka",
                "clusterProfile", profile.name(),
                "group", profile.groupLogicalName(),
                "topic", record.partition().topic(),
                "partition", record.partition().partition(),
                "offset", record.offset()));
        return java.util.Collections.unmodifiableMap(payload);
    }

    static String idempotentKey(KafkaConsumerProfile profile, KafkaConsumerProtocol.Record record) {
        return profile.name() + "/" + profile.groupLogicalName() + "/" + record.partition().topic()
                + "/" + record.partition().partition() + "/" + record.offset();
    }

    static String correlation(KafkaConsumerProtocol.Record record) {
        return "kafka:" + record.partition().topic() + ":" + record.partition().partition() + ":" + record.offset();
    }

    private static Map<String, Object> representation(byte[] bytes) {
        if (bytes == null) return Map.of("format", "null");
        String text = strictUtf8(bytes);
        if (text != null && KafkaText.safeCorrelation(text) && text.length() <= 4_096) {
            return Map.of("format", "utf8", "text", text, "size", bytes.length);
        }
        return Map.of("format", "base64", "base64", Base64.getEncoder().encodeToString(bytes),
                "size", bytes.length);
    }

    private static List<Map<String, Object>> headers(KafkaConsumerProtocol.Record record,
                                                      KafkaConsumerProfile profile) {
        var answer = new ArrayList<Map<String, Object>>();
        int total = 0;
        for (KafkaConsumerProtocol.Header header : record.headers()) {
            if (!profile.allowsHeader(header.name())) continue;
            byte[] value = header.value();
            int size = value == null ? 0 : value.length;
            total = safeAdd(total, safeAdd(header.name().getBytes(StandardCharsets.UTF_8).length, size));
            if (total > profile.maxHeaderBytes()) throw new InvalidRecord("headers-too-large");
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("name", header.name());
            safe.put("value", representation(value));
            answer.add(java.util.Collections.unmodifiableMap(safe));
        }
        return List.copyOf(answer);
    }

    private static String strictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) { return null; }
    }

    private static int safeAdd(int left, int right) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException overflow) { throw new InvalidRecord("size-overflow"); }
    }

    static final class InvalidRecord extends RuntimeException {
        private final String safeReason;
        InvalidRecord(String safeReason) { super(safeReason); this.safeReason = safeReason; }
        String safeReason() { return safeReason; }
    }
}
