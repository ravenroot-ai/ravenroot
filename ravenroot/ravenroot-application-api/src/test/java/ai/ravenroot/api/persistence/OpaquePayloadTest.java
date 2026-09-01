package ai.ravenroot.api.persistence;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpaquePayloadTest {

    @Test
    void equalsByValueSoConformanceComparisonsAreMeaningful() {
        var first = OpaquePayload.of("outcome".getBytes(StandardCharsets.UTF_8), "application/json");
        var second = OpaquePayload.of("outcome".getBytes(StandardCharsets.UTF_8), "application/json");

        // A byte[] record component would have made these unequal and silently broken every
        // idempotency fingerprint comparison in the contract.
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void distinguishesContentTypeAndBytes() {
        var json = OpaquePayload.of(new byte[]{1, 2}, "application/json");
        assertNotEquals(json, OpaquePayload.of(new byte[]{1, 2}, "application/cbor"));
        assertNotEquals(json, OpaquePayload.of(new byte[]{1, 3}, "application/json"));
    }

    @Test
    void copiesDefensivelyOnBothConstructionAndAccess() {
        byte[] source = {1, 2, 3};
        var payload = OpaquePayload.of(source, "application/octet-stream");

        source[0] = 99;
        assertArrayEquals(new byte[]{1, 2, 3}, payload.bytes());

        byte[] escaped = payload.bytes();
        escaped[0] = 99;
        assertArrayEquals(new byte[]{1, 2, 3}, payload.bytes());
    }

    @Test
    void neverRendersBytesBecauseThisStringReachesLogs() {
        var payload = OpaquePayload.of("secret-token".getBytes(StandardCharsets.UTF_8), "text/plain");

        assertFalse(payload.toString().contains("secret"));
        assertEquals(12, payload.size());
    }

    @Test
    void rejectsNullBytesAndBlankContentType() {
        assertThrows(IllegalArgumentException.class, () -> OpaquePayload.of(null, "text/plain"));
        assertThrows(IllegalArgumentException.class, () -> OpaquePayload.of(new byte[0], " "));
    }
}
