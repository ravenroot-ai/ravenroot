package ai.ravenroot.api.correlation;
import ai.ravenroot.api.payload.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CorrelationPreviewTest {
    @Test void sampleAndActualAdmissionUseIdenticalExtractionAndGeneratedPlaceholderIsExplicit() {
        var properties = Map.of("correlation.mode", "ACCEPT_OR_CREATE", "correlation.selector", "/metadata/id", "correlation.contributionSelector", "/part");
        var sample = Map.of("metadata", Map.of("id", "operation-42"), "part", "part-3");
        var preview = CorrelationPreview.evaluate(properties, sample, CorrelationLimits.DEFAULTS);
        assertTrue(preview.valid()); assertFalse(preview.generatedIdentity());
        assertEquals(CorrelationMappingProperties.read(properties, CorrelationLimits.DEFAULTS)
                .extract(PayloadValue.fromJava(sample, PayloadLimits.DEFAULTS), UUID::randomUUID), preview.context());
        var generated = CorrelationPreview.evaluate(Map.of(), Map.of(), CorrelationLimits.DEFAULTS);
        assertTrue(generated.valid()); assertTrue(generated.generatedIdentity());
        assertEquals(new UUID(0, 0).toString(), generated.context().applicationCorrelationId());
    }
    @Test void malformedValuesAndConflictsReturnOnlySelectorAndFixedReason() {
        var properties = Map.of("correlation.mode", "REQUIRE_EXISTING", "correlation.selector", "/id");
        for (Object value : Arrays.asList(null, Map.of("secret", "do-not-echo"), List.of("do-not-echo"), "\nsecret", 42)) {
            var sample = new LinkedHashMap<String, Object>(); sample.put("id", value);
            var rejected = CorrelationPreview.evaluate(properties, sample, CorrelationLimits.DEFAULTS);
            assertFalse(rejected.valid()); assertEquals("/id", rejected.selector());
            assertFalse(rejected.toJson().contains("do-not-echo")); assertNull(rejected.context());
        }
        var conflict = CorrelationPreview.evaluate(Map.of("correlation.mode", "ACCEPT_OR_CREATE", "correlation.selector", "/id", "correlation.secondarySelector", "/other"),
                Map.of("id", "private-first", "other", "private-second"), CorrelationLimits.DEFAULTS);
        assertEquals("CONFLICTING_MAPPINGS", conflict.reason()); assertFalse(conflict.toJson().contains("private-"));
        assertEquals("INVALID_CONFIGURATION", CorrelationPreview.evaluate(Map.of("tenant", "foreign"), Map.of(), CorrelationLimits.DEFAULTS).reason());
    }
    @Test void configuredLimitsAndRequireExistingAreHonestAndRequestShapeCannotChooseScope() {
        var limits = new CorrelationLimits(8, 16, 2, 3, PayloadLimits.DEFAULTS);
        var rejected = CorrelationPreview.evaluate(Map.of("correlation.mode", "REQUIRE_EXISTING", "correlation.selector", "/id"),
                Map.of("id", "too-long-secret"), limits);
        assertEquals("INVALID_ID", rejected.reason()); assertTrue(rejected.requiresExistingHistory());
        assertFalse(rejected.toJson().contains("too-long-secret"));
        var body = "{\"properties\":{},\"sample\":null}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertNull(CorrelationPreviewRequest.read(body, PayloadLimits.DEFAULTS).sample());
        assertThrows(IllegalArgumentException.class, () -> CorrelationPreviewRequest.read("{\"properties\":{},\"sample\":{},\"tenant\":\"foreign\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8), PayloadLimits.DEFAULTS));
    }
}
