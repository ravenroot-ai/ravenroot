package ai.ravenroot.api.correlation;

import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CorrelationSelectorTest {
    private static final CorrelationLimits LIMITS = CorrelationLimits.DEFAULTS;
    private static PayloadValue input(Object value) { return PayloadValue.fromJava(value, PayloadLimits.DEFAULTS); }
    private static CorrelationSelector pointer(String value) { return new CorrelationSelector(value, LIMITS); }

    @Test void selectsExactPayloadAndConnectorFieldsWithoutFallbackOrCoercion() {
        var event = input(Map.of("metadata", Map.of("ingestionId", "ingestion-42"), "headers",
                Map.of("x-operation", "external-42"), "correlationId", "amqp-request-reply",
                "items", List.of(Map.of("id", "partition-1")), "a/b", Map.of("~", "escaped")));
        assertEquals("ingestion-42", pointer("/metadata/ingestionId").selectId(event));
        assertEquals("external-42", pointer("/headers/x-operation").selectId(event));
        assertEquals("partition-1", pointer("/items/0/id").selectId(event));
        assertEquals("escaped", pointer("/a~1b/~0").selectId(event));
        assertEquals("direct", pointer("").selectId(PayloadValue.of("direct")));
        assertThrows(CorrelationSelector.Invalid.class, () -> pointer("/ingestionId").selectId(event));
        assertThrows(CorrelationSelector.Invalid.class, () -> pointer("/items/00/id").selectId(event));
    }

    @Test void invalidTypesPointersAndLimitsNeverExposeSelectedValues() {
        for (Object rejected : Arrays.asList(null, List.of("secret"), Map.of("secret", "value"), 42, true, " ", "a\nb")) {
            var error = assertThrows(CorrelationSelector.Invalid.class, () -> pointer("").selectId(input(rejected)));
            assertFalse(error.getMessage().contains("secret"));
            assertEquals("", error.selector());
        }
        for (String invalid : List.of("metadata/id", "/~2", "/~", "/line\nbreak"))
            assertThrows(CorrelationSelector.Invalid.class, () -> pointer(invalid));
        var tight = new CorrelationLimits(8, 16, 2, 3, PayloadLimits.DEFAULTS);
        assertThrows(CorrelationSelector.Invalid.class, () -> new CorrelationSelector("/a/b/c", tight));
        assertThrows(CorrelationSelector.Invalid.class, () -> new CorrelationSelector("", tight)
                .selectId(PayloadValue.of("oversized-identifier")));
    }

    @Test void countsAndManifestsRemainDifferentAndDuplicatesCannotChangeCompleteness() {
        var event = input(Map.of("count", 6, "manifest", List.of("1", "2", "3", "4", "5", "6")));
        assertEquals(6, pointer("/count").selectCount(event));
        assertEquals(List.of("1", "2", "3", "4", "5", "6"), pointer("/manifest").selectManifest(event));
        assertThrows(CorrelationSelector.Invalid.class, () -> pointer("/manifest").selectCount(event));
        assertThrows(CorrelationSelector.Invalid.class, () -> pointer("").selectManifest(input(List.of("1", "1"))));
        assertThrows(CorrelationSelector.Invalid.class, () -> pointer("").selectManifest(input(List.of(1, 2))));
    }

    @Test void mappingModesConflictsAndGeneratedIdentityAreExplicit() {
        UUID generated = UUID.randomUUID();
        var accept = new CorrelationMapping(CorrelationMapping.Mode.ACCEPT_OR_CREATE,
                List.of(pointer("/id"), pointer("/transport/application")), Optional.of(pointer("/part")),
                Optional.empty(), Optional.empty(), Optional.of(pointer("/filename")), LIMITS);
        var external = accept.extract(input(Map.of("id", "ingestion-42", "transport", Map.of("application", "ingestion-42"),
                "part", "3", "filename", "orders.csv")), () -> { throw new AssertionError("external ID must be preserved"); });
        assertEquals("ingestion-42", external.applicationCorrelationId());
        assertEquals(Optional.of("3"), external.contributionId());
        assertEquals("0", external.roundId());
        assertThrows(IllegalArgumentException.class, () -> accept.extract(input(Map.of("id", "one",
                "transport", Map.of("application", "two"), "part", "1", "filename", "orders.csv")), () -> generated));
        assertEquals(generated.toString(), accept.extract(input(Map.of("part", "1", "filename", "orders.csv")),
                () -> generated).applicationCorrelationId());
        var require = new CorrelationMapping(CorrelationMapping.Mode.REQUIRE_EXISTING, List.of(pointer("/id")),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), LIMITS);
        assertThrows(CorrelationSelector.Invalid.class, () -> require.extract(input(Map.of()), () -> generated));
        assertThrows(CorrelationSelector.Invalid.class, () -> accept.extract(input(Map.of("id", Map.of("secret", "value"))),
                () -> generated));
    }

    @Test void opaqueIdentityMustHaveAnExactUtf8Representation() {
        for (String malformed : List.of("\uD800", "\uDC00", "prefix\uD800suffix")) {
            assertThrows(IllegalArgumentException.class, () -> LIMITS.requireId(malformed, "id"));
            assertThrows(IllegalArgumentException.class, () -> new ApplicationCorrelationContext("business",
                    Optional.empty(), Optional.empty(), "round", Optional.of(malformed)));
        }
        String exact = "  操作-😀  ";
        assertEquals(exact, LIMITS.requireId(exact, "id"));
    }
}
