package ai.ravenroot.server.payload;

import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.api.programming.ProgramAuthoringLimits;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProgramBuildSubmissionTest {
    @Test
    void configuredSourceAndBatchLimitsApplyDuringParsing() {
        var limits = new ProgramAuthoringLimits(4, 1024, 1);
        byte[] accepted = "{\"programs\":[{\"nodeId\":\"n\",\"language\":\"js\",\"source\":\"€a\"}]}"
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(1, ProgramBuildSubmission.read(accepted, PayloadLimits.DEFAULTS, limits)
                .programs().size());

        byte[] tooWide = "{\"programs\":[{\"nodeId\":\"n\",\"language\":\"js\",\"source\":\"€€\"}]}"
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> ProgramBuildSubmission.read(tooWide, PayloadLimits.DEFAULTS, limits));

        byte[] tooMany = "{\"programs\":["
                .concat("{\"nodeId\":\"a\",\"language\":\"js\",\"source\":\"a\"},")
                .concat("{\"nodeId\":\"b\",\"language\":\"js\",\"source\":\"b\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> ProgramBuildSubmission.read(tooMany, PayloadLimits.DEFAULTS, limits));
    }

    @Test
    void buildEnvelopeWidensOnlyEncodedAndSourceTextBudgets() {
        var authoring = new ProgramAuthoringLimits(1024 * 1024, 10 * 1024 * 1024, 256);
        PayloadLimits envelope = ProgramBuildSubmission.buildEnvelopeLimits(PayloadLimits.DEFAULTS, authoring);

        assertEquals(authoring.maxBuildRequestBytes(), envelope.maxEncodedBytes());
        assertEquals(authoring.maxSourceBytes(), envelope.maxTextLength());
        assertEquals(PayloadLimits.DEFAULTS.maxDepth(), envelope.maxDepth());
        assertEquals(PayloadLimits.DEFAULTS.maxCollectionSize(), envelope.maxCollectionSize());
        assertEquals(PayloadLimits.DEFAULTS.maxValueCount(), envelope.maxValueCount());
        assertEquals(PayloadLimits.DEFAULTS.maxKeyLength(), envelope.maxKeyLength());
    }

    @Test
    void widenedSourceEnvelopePreservesGenericLimitsEverywhereElse() {
        var authoring = ProgramAuthoringLimits.DEFAULTS;
        String overlong = "x".repeat(PayloadLimits.DEFAULTS.maxTextLength() + 1);
        for (String field : new String[] {"nodeId", "language", "testPayload", "extra"}) {
            String nodeId = "nodeId".equals(field) ? overlong : "n";
            String language = "language".equals(field) ? overlong : "js";
            String testPayload = "testPayload".equals(field) ? overlong : "{}";
            String extra = "extra".equals(field) ? ",\"extra\":\"" + overlong + "\"" : "";
            String body = "{\"programs\":[{\"nodeId\":\"" + nodeId + "\",\"language\":\""
                    + language + "\",\"source\":\"ok\",\"testPayload\":\"" + testPayload + "\""
                    + extra + "}]}";
            PayloadException rejection = assertThrows(PayloadException.class,
                    () -> ProgramBuildSubmission.read(body.getBytes(StandardCharsets.UTF_8),
                            PayloadLimits.DEFAULTS, authoring), field);
            assertEquals(PayloadException.Reason.TEXT_TOO_LONG, rejection.reason(), field);
        }

        assertPayloadReason("{]", PayloadLimits.DEFAULTS, PayloadException.Reason.MALFORMED);
        assertPayloadReason("{\"programs\":[],\"extra\":{\"nested\":{\"too\":{\"deep\":true}}}}",
                new PayloadLimits(1024, 4, 100, 100, 100, 100),
                PayloadException.Reason.DEPTH_LIMIT_EXCEEDED);
        assertPayloadReason("{\"programs\":[{\"nodeId\":\"n\",\"language\":\"js\",\"source\":\"ok\",\"extra\":0}]}",
                new PayloadLimits(1024, 8, 3, 100, 100, 100),
                PayloadException.Reason.COLLECTION_LIMIT_EXCEEDED);
        assertPayloadReason("{\"programs\":[{\"nodeId\":\"n\",\"language\":\"js\",\"source\":\"ok\"}]}",
                new PayloadLimits(1024, 8, 100, 4, 100, 100),
                PayloadException.Reason.VALUE_COUNT_LIMIT_EXCEEDED);
        assertPayloadReason("{\"programs\":[],\"longKey\":true}",
                new PayloadLimits(1024, 8, 100, 100, 100, 4),
                PayloadException.Reason.KEY_TOO_LONG);
    }

    @Test
    void compatibilityOverloadUsesThePublishedAuthoringEnvelope() {
        String source = "x".repeat(PayloadLimits.DEFAULTS.maxTextLength() + 1);
        byte[] body = ("{\"programs\":[{\"nodeId\":\"n\",\"language\":\"js\",\"source\":\""
                + source + "\"}]}").getBytes(StandardCharsets.UTF_8);

        assertEquals(source, ProgramBuildSubmission.read(body, PayloadLimits.DEFAULTS)
                .programs().getFirst().source());
    }

    private static void assertPayloadReason(String body, PayloadLimits structural,
                                            PayloadException.Reason expected) {
        PayloadException rejection = assertThrows(PayloadException.class,
                () -> ProgramBuildSubmission.read(body.getBytes(StandardCharsets.UTF_8), structural,
                        ProgramAuthoringLimits.DEFAULTS));
        assertEquals(expected, rejection.reason());
    }

    @Test
    void compatibilityOverloadRetainsThePublishedBatchCeiling() {
        assertEquals(ProgramAuthoringLimits.HARD_MAX_PROGRAMS_PER_BUILD,
                ProgramBuildSubmission.MAX_PROGRAMS);
    }
}
