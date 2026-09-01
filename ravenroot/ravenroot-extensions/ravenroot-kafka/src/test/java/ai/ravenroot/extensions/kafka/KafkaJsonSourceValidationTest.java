package ai.ravenroot.extensions.kafka;

import ai.ravenroot.api.security.SecretValue;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class KafkaJsonSourceValidationTest {
    private static final List<String> UNSAFE = List.of("\uD800", "\uDC00", "\u0001", "\u0085");

    @Test void keyJsonAndValueJsonRejectUnsafeRootNestedArrayAndMapKeyBeforeAuthority() {
        for (String field : List.of("keyJson", "valueJson")) {
            for (String unsafe : UNSAFE) {
                assertJsonRejected(field, unsafe, "root string");
                assertJsonRejected(field, new StringBuilder(unsafe), "root CharSequence");
                assertJsonRejected(field, Map.of("outer", Map.of("leaf", unsafe)), "nested leaf");
                assertJsonRejected(field, Map.of("outer", List.of("safe", unsafe)), "nested array");
                assertJsonRejected(field, Map.of("outer", Map.of(unsafe, "value")), "nested map key");
            }
        }
    }

    @Test void keyTextAndHeaderNamesAndValuesRejectUnsafeTextBeforeAuthority() {
        for (String unsafe : UNSAFE) {
            var keyPayload = new LinkedHashMap<>(KafkaTestSupport.payload());
            keyPayload.put("keyText", unsafe);
            assertDirectRejected(keyPayload, "keyText");

            var headerKeyPayload = new LinkedHashMap<>(KafkaTestSupport.payload());
            headerKeyPayload.put("headers", Map.of(unsafe, "value"));
            assertDirectRejected(headerKeyPayload, "header key");

            var headerValuePayload = new LinkedHashMap<>(KafkaTestSupport.payload());
            headerValuePayload.put("headers", Map.of("trace", unsafe));
            assertDirectRejected(headerValuePayload, "header value");
        }
    }

    @Test void jsonDepthCollectionCountAndEncodedSizeLimitsRemainFailClosed() {
        Object tooDeep = "leaf";
        for (int depth = 0; depth < 16; depth++) tooDeep = List.of(tooDeep);
        List<String> tooWide = Collections.nCopies(129, "x");
        var tooMany = new ArrayList<List<String>>();
        for (int group = 0; group < 5; group++) tooMany.add(Collections.nCopies(128, "x"));
        String tooManyEncodedBytes = "é".repeat(600_000);

        for (String field : List.of("keyJson", "valueJson")) {
            assertJsonRejected(field, tooDeep, "depth limit");
            assertJsonRejected(field, tooWide, "collection limit");
            assertJsonRejected(field, tooMany, "value-count limit");
            assertJsonRejected(field, tooManyEncodedBytes, "encoded-size limit");
        }
    }

    @Test void nestedUnicodeAndSurrogatePairsCanonicalizeWithoutQuestionMarkCollision() {
        var key = new LinkedHashMap<String, Object>();
        key.put("é", List.of(new StringBuilder("😀"), Map.of("雪", "café")));
        var value = new LinkedHashMap<String, Object>();
        value.put("nested", List.of(Map.of("emoji", "😀"), "雪"));

        var payload = new LinkedHashMap<String, Object>();
        payload.put("version", "kafka.produce.v1");
        payload.put("keyJson", key);
        payload.put("valueJson", value);
        var protocol = new KafkaTestSupport.FakeProtocol(KafkaTestSupport.Event.ACK);

        assertEquals("ACKNOWLEDGED", KafkaTestSupport.output(
                KafkaTestSupport.behavior(protocol).create(KafkaTestSupport.configuration()), payload).get("status"));
        KafkaProtocol.Record record = protocol.records.getFirst();
        assertArrayEquals("{\"é\":[\"😀\",{\"雪\":\"café\"}]}".getBytes(StandardCharsets.UTF_8), record.key());
        assertArrayEquals("{\"nested\":[{\"emoji\":\"😀\"},\"雪\"]}".getBytes(StandardCharsets.UTF_8), record.value());
        assertFalse(java.util.Arrays.equals(record.key(),
                "{\"é\":[\"?\",{\"雪\":\"café\"}]}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, protocol.sends.get());
    }

    @Test void directSurrogatePairUsesItsUtf8BytesAndDoesNotCollideWithQuestionMark() {
        var payload = new LinkedHashMap<>(KafkaTestSupport.payload());
        payload.put("keyText", "😀");
        payload.put("headers", Map.of("trace", "😀"));
        var protocol = new KafkaTestSupport.FakeProtocol(KafkaTestSupport.Event.ACK);

        assertEquals("ACKNOWLEDGED", KafkaTestSupport.output(
                KafkaTestSupport.behavior(protocol).create(KafkaTestSupport.configuration()), payload).get("status"));
        KafkaProtocol.Record record = protocol.records.getFirst();
        assertArrayEquals(new byte[]{(byte) 0xf0, (byte) 0x9f, (byte) 0x98, (byte) 0x80}, record.key());
        assertArrayEquals(record.key(), record.headers().get("trace"));
        assertFalse(java.util.Arrays.equals("?".getBytes(StandardCharsets.UTF_8), record.key()));
    }

    private static void assertJsonRejected(String field, Object source, String position) {
        var payload = new LinkedHashMap<>(KafkaTestSupport.payload());
        if (field.equals("valueJson")) payload.remove("valueText");
        payload.put(field, source);
        Fixture fixture = new Fixture();
        Map<String, Object> result = KafkaTestSupport.output(fixture.behavior().create(KafkaTestSupport.configuration()), payload);
        assertEquals("REJECTED", result.get("status"), field + " " + position);
        assertEquals("INVALID_JSON", result.get("reason"), field + " " + position);
        fixture.assertUntouched();
    }

    private static void assertDirectRejected(Map<String, Object> payload, String position) {
        Fixture fixture = new Fixture();
        Map<String, Object> result = KafkaTestSupport.output(fixture.behavior().create(KafkaTestSupport.configuration()), payload);
        assertEquals("REJECTED", result.get("status"), position);
        fixture.assertUntouched();
    }

    private static final class Fixture {
        private final AtomicInteger credentials = new AtomicInteger();
        private final KafkaTestSupport.FakeProtocol protocol = new KafkaTestSupport.FakeProtocol();

        private KafkaProduceNodeBehavior behavior() {
            return new KafkaProduceNodeBehavior(ref -> {
                credentials.incrementAndGet();
                return Optional.of(new SecretValue("unused".toCharArray()));
            }, (tenant, name) -> Optional.of(KafkaTestSupport.profile()), protocol,
                    new KafkaRuntimeControls(System::nanoTime, Runnable::run, 8, 8, 32), System::nanoTime);
        }

        private void assertUntouched() {
            assertEquals(0, credentials.get(), "credential resolution");
            assertEquals(0, protocol.creates.get(), "beginCreate");
            assertEquals(0, protocol.sends.get(), "send");
        }
    }
}
