package ai.ravenroot.server.payload;

import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.api.payload.PayloadLimits;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactTestSubmissionTest {
    @Test
    void jsonPreservesObjectListAndScalarShapes() {
        assertEquals(Map.of("ready", true, "count", 2L), ArtifactTestSubmission.read(
                "{\"ready\":true,\"count\":2}".getBytes(StandardCharsets.UTF_8), "application/json", PayloadLimits.DEFAULTS).toJava());
        assertEquals(List.of("a", 2L), ArtifactTestSubmission.read(
                "[\"a\",2]".getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8", PayloadLimits.DEFAULTS).toJava());
        assertEquals(true, ArtifactTestSubmission.read("true".getBytes(StandardCharsets.UTF_8), "application/json", PayloadLimits.DEFAULTS).toJava());
    }

    @Test
    void textIsLiteralRatherThanJsonSniffed() {
        assertEquals("{\"still\":\"text\"}", ArtifactTestSubmission.read(
                "{\"still\":\"text\"}".getBytes(StandardCharsets.UTF_8), "text/plain", PayloadLimits.DEFAULTS).toJava());
    }

    @Test
    void rejectsMalformedAndOversizedJsonBeforeRuntime() {
        assertThrows(PayloadException.class, () -> ArtifactTestSubmission.read(
                "{]".getBytes(StandardCharsets.UTF_8), "application/json", PayloadLimits.DEFAULTS));
        byte[] over = new byte[PayloadLimits.DEFAULTS.maxEncodedBytes() + 1];
        assertThrows(PayloadException.class, () -> ArtifactTestSubmission.read(over, "text/plain", PayloadLimits.DEFAULTS));
        assertTrue(ArtifactTestSubmission.supports("application/json; charset=utf-8"));
        assertFalse(ArtifactTestSubmission.supports("application/xml"));
    }
}
