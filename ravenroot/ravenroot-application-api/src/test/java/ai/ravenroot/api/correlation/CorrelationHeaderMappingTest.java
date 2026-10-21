package ai.ravenroot.api.correlation;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CorrelationHeaderMappingTest {
    private static final ApplicationCorrelationContext CONTEXT = new ApplicationCorrelationContext("operation", Optional.of("part-2"), Optional.of("partition"), "round-3", Optional.of("file.csv"));

    @Test void projectsEstablishedContextWithoutMutatingTransportOrAuthoredObjects() {
        var mapping = CorrelationHeaderMapping.read(Map.of(CorrelationHeaderMapping.APPLICATION, "X-Operation", CorrelationHeaderMapping.CONTRIBUTION, "X-Part",
                CorrelationHeaderMapping.KIND, "X-Kind", CorrelationHeaderMapping.ROUND, "X-Round", CorrelationHeaderMapping.REFERENCE, "X-File"), List.of());
        var headers = Map.of("X-Custom", "original");
        var payload = Map.of("version", "test.v1", "correlationId", "transport-waiter", "messageId", "transport-message", "headers", headers);
        var mapped = (Map<?, ?>) mapping.attachPayloadHeaders(payload, CONTEXT);
        assertEquals("transport-waiter", mapped.get("correlationId")); assertEquals("transport-message", mapped.get("messageId"));
        assertEquals(Map.of("X-Custom", "original", "X-Operation", "operation", "X-Part", "part-2", "X-Kind", "partition", "X-Round", "round-3", "X-File", "file.csv"), mapped.get("headers"));
        assertSame(headers, payload.get("headers")); assertEquals(1, headers.size());
        assertThrows(UnsupportedOperationException.class, () -> mapping.merge(headers, CONTEXT).put("X-Other", "value"));
    }
    @Test void disabledMappingPreservesLegacyPayloadAndDoesNotInventContext() {
        var mapping = CorrelationHeaderMapping.read(Map.of(), List.of()); Object raw = "legacy payload";
        assertSame(raw, mapping.attachPayloadHeaders(raw, null)); assertEquals(Map.of(), mapping.merge(Map.of(), null));
    }
    @Test void configuredDuplicateProtocolCredentialAndRuntimeTargetsRefuse() {
        assertThrows(IllegalArgumentException.class, () -> CorrelationHeaderMapping.read(Map.of(CorrelationHeaderMapping.APPLICATION, "X-Operation", CorrelationHeaderMapping.ROUND, "x-operation"), List.of()));
        for (String reserved : List.of("X-Correlation-ID", "x-MESSAGE-id", "X-Ravenroot-Payload-SHA256", "X-Api-Key", "correlationId", "X-Bad\r\nHeader")) {
            assertThrows(IllegalArgumentException.class, () -> CorrelationHeaderMapping.read(Map.of(CorrelationHeaderMapping.APPLICATION, reserved), List.of("x-api-key")), reserved);
        }
    }
    @Test void effectiveCaseInsensitiveCollisionsRefuseButEqualValueReconciles() {
        var mapping = CorrelationHeaderMapping.read(Map.of(CorrelationHeaderMapping.APPLICATION, "X-Operation"), List.of());
        assertEquals(Map.of("x-operation", "operation"), mapping.merge(Map.of("x-operation", "operation"), CONTEXT));
        assertThrows(IllegalArgumentException.class, () -> mapping.merge(Map.of("x-operation", "different"), CONTEXT));
        assertThrows(IllegalArgumentException.class, () -> mapping.merge(Map.of("x-operation", "operation", "X-Operation", "operation"), CONTEXT));
        assertThrows(IllegalArgumentException.class, () -> mapping.merge(Map.of(), null));
    }
    @Test void optionalAbsentFieldsOmitOnlyAbsentTargetsAndUnrepresentableValuesRefuse() {
        var mapping = CorrelationHeaderMapping.read(Map.of(CorrelationHeaderMapping.APPLICATION, "X-Operation", CorrelationHeaderMapping.CONTRIBUTION, "X-Part"), List.of());
        var context = new ApplicationCorrelationContext("operation", Optional.empty(), Optional.empty(), "0", Optional.empty());
        assertEquals(Map.of("X-Operation", "operation"), mapping.merge(Map.of(), context));
        assertThrows(IllegalArgumentException.class, () -> mapping.merge(Map.of("X-Part", "old-unverified-part"), context));
        var unicode = new ApplicationCorrelationContext("opération", Optional.empty(), Optional.empty(), "0", Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> mapping.merge(Map.of(), unicode));
    }
    @Test void mappedPayloadStillRequiresAnExplicitHeadersObject() {
        var mapping = CorrelationHeaderMapping.read(Map.of(CorrelationHeaderMapping.APPLICATION, "X-Operation"), List.of());
        assertThrows(IllegalArgumentException.class, () -> mapping.attachPayloadHeaders(Map.of("headers", "not an object"), CONTEXT));
        assertThrows(IllegalArgumentException.class, () -> mapping.attachPayloadHeaders("no arbitrary object introspection", CONTEXT));
    }
}
