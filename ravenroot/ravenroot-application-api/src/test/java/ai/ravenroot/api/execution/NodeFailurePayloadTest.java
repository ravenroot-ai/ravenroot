package ai.ravenroot.api.execution;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NodeFailurePayload}, the documented payload a failure route carries.
 */
class NodeFailurePayloadTest {

    @Test
    void unwrapsToTheDeepestCauseSameAsExecutionMonitorsOwnFailureMessage() {
        var payload = NodeFailurePayload.of("boom",
                new RuntimeException("outer", new IllegalStateException("inner cause")), "in");

        assertEquals(IllegalStateException.class.getName(), payload.errorClass(),
                "a wrapped cause must not hide behind its wrapper's class");
        assertEquals("inner cause", payload.message());
    }

    @Test
    void fallsBackToTheSimpleClassNameWhenTheDeepestCauseHasNoMessage() {
        var payload = NodeFailurePayload.of("boom", new IllegalArgumentException(), "in");

        assertEquals("IllegalArgumentException", payload.message());
    }

    @Test
    void carriesTheInputVerbatim() {
        record Order(String id) {
        }
        var input = new Order("o-1");

        var payload = NodeFailurePayload.of("boom", new RuntimeException("x"), input);

        assertEquals(input, payload.input());
    }

    @Test
    void neverCarriesARawStackTrace() {
        Throwable error = new RuntimeException("plain message");
        // Sanity: a stack trace is always non-empty for a thrown-and-caught exception, so if the
        // message ever started including one this fixture would actually exercise that.
        assertTrue(error.getStackTrace().length > 0);

        var payload = NodeFailurePayload.of("boom", error, "in");

        assertEquals("plain message", payload.message());
        assertFalse(payload.message().contains("at ai.ravenroot"),
                "the message must never carry stack frame text");
    }

    /**
     * Bounded exactly like {@link ExecutionEvent#detail()}: a redactor that tried to scrub
     * secrets from the text would provide false assurance, so the only real
     * guarantee is the bound. Sharing the constant, rather than inventing a second number, is the
     * point being pinned here.
     */
    @Test
    void boundsAnOversizedMessageLikeExecutionEventDetail() {
        String longMessage = "x".repeat(ExecutionEvent.MAX_DETAIL_LENGTH * 4);

        var payload = NodeFailurePayload.of("boom", new RuntimeException(longMessage), "in");

        assertEquals(ExecutionEvent.MAX_DETAIL_LENGTH, payload.message().length());
        assertTrue(payload.message().endsWith(ExecutionEvent.DETAIL_TRUNCATION_MARKER));
    }

    @Test
    void rejectsABlankNodeId() {
        assertThrows(IllegalArgumentException.class,
                () -> new NodeFailurePayload(" ", "java.lang.RuntimeException", "m", "in"));
    }

    @Test
    void rejectsABlankErrorClass() {
        assertThrows(IllegalArgumentException.class,
                () -> new NodeFailurePayload("boom", " ", "m", "in"));
    }

    @Test
    void normalizesANullMessageToEmptyRatherThanFailing() {
        var payload = new NodeFailurePayload("boom", "java.lang.RuntimeException", null, "in");

        assertEquals("", payload.message());
    }

    @Test
    void projectsOnlyTheFourDocumentedFieldsWithoutThrowableInternals() {
        var payload = NodeFailurePayload.of("boom",
                new RuntimeException("outer", new IllegalStateException("inner cause")),
                Map.of("orderId", "o-1"));

        String json = PayloadJson.write(PayloadValue.fromJava(payload, PayloadLimits.DEFAULTS));

        assertEquals("{\"errorClass\":\"java.lang.IllegalStateException\","
                + "\"input\":{\"orderId\":\"o-1\"},\"message\":\"inner cause\",\"nodeId\":\"boom\"}",
                json);
        assertFalse(json.contains("\"cause\":"), "the Throwable cause graph is not a payload member");
        assertFalse(json.contains("stackTrace"), "stack frames are never reflected into the payload");
        assertFalse(json.contains("suppressed"), "suppressed exceptions are never reflected into the payload");
    }

    @Test
    void projectsFailurePayloadsAtAnySupportedListOrMapDepth() {
        var failure = NodeFailurePayload.of("boom", new IllegalStateException("failed"), List.of("in", 7));
        var nested = Map.of("attempts", List.of(Map.of("failure", failure)));

        var projected = PayloadValue.fromJava(nested, PayloadLimits.DEFAULTS);

        assertEquals(projected, PayloadJson.read(PayloadJson.write(projected).getBytes(StandardCharsets.UTF_8),
                PayloadLimits.DEFAULTS));
        assertTrue(PayloadJson.write(projected).contains("\"failure\":{\"errorClass\":"));
    }

    @Test
    void rejectsAnUnsupportedInputWithoutCallingItsToString() {
        final class UnsupportedInput {
            @Override
            public String toString() {
                return "secret-input-must-not-leak";
            }
        }
        var failure = NodeFailurePayload.of("boom", new IllegalStateException("failed"), new UnsupportedInput());

        var rejection = assertThrows(PayloadException.class,
                () -> PayloadValue.fromJava(failure, PayloadLimits.DEFAULTS));

        assertEquals(PayloadException.Reason.UNSUPPORTED_TYPE, rejection.reason());
        assertFalse(rejection.getMessage().contains("secret-input-must-not-leak"));
    }

    @Test
    void appliesThePayloadBudgetsToTheWholeProjectedEnvelope() {
        var limits = new PayloadLimits(128, 4, 8, 16, 64, 64);
        var failure = NodeFailurePayload.of("boom", new IllegalStateException("failed"),
                PayloadValue.of("x".repeat(64)));

        var rejection = assertThrows(PayloadException.class, () -> PayloadValue.fromJava(failure, limits));

        assertEquals(PayloadException.Reason.TOO_LARGE, rejection.reason(),
                "the four-member envelope overhead must count against the same encoded-byte budget as its input");
    }

    @Test
    void appliesTheKeyBudgetToItsFixedFieldNames() {
        var limits = new PayloadLimits(1_024, 4, 8, 16, 64, 8);
        var failure = NodeFailurePayload.of("boom", new IllegalStateException("failed"), "in");

        var rejection = assertThrows(PayloadException.class, () -> PayloadValue.fromJava(failure, limits));

        assertEquals(PayloadException.Reason.KEY_TOO_LONG, rejection.reason());
    }

    @Test
    void rejectsAnAlreadyBuiltListThatExceedsTheNestedCollectionBudget() {
        var limits = limits(8, 4, 32, 64, 16);
        var input = PayloadValue.list(PayloadValue.NULL, PayloadValue.NULL, PayloadValue.NULL,
                PayloadValue.NULL, PayloadValue.NULL);
        assertEquals(5, ((PayloadValue.ListValue) input).values().size(),
                "the fixture must exceed only the nested collection budget");

        var rejection = assertThrows(PayloadException.class,
                () -> PayloadValue.fromJava(failureWithInput(input), limits));

        assertEquals(PayloadException.Reason.COLLECTION_LIMIT_EXCEEDED, rejection.reason());
    }

    @Test
    void countsAlreadyBuiltNestingFromTheFailureEnvelopeRoot() {
        var limits = limits(3, 4, 32, 64, 16);
        var input = PayloadValue.list(PayloadValue.list(PayloadValue.of("in")));

        var rejection = assertThrows(PayloadException.class,
                () -> PayloadValue.fromJava(failureWithInput(input), limits));

        assertEquals(PayloadException.Reason.DEPTH_LIMIT_EXCEEDED, rejection.reason(),
                "the text is at depth four only when the failure envelope and built input share one traversal");
    }

    @Test
    void sharesValueCountAccountingWithAnAlreadyBuiltInputTree() {
        var limits = limits(8, 4, 6, 64, 16);
        var input = PayloadValue.list(PayloadValue.NULL, PayloadValue.NULL);

        var rejection = assertThrows(PayloadException.class,
                () -> PayloadValue.fromJava(failureWithInput(input), limits));

        assertEquals(PayloadException.Reason.VALUE_COUNT_LIMIT_EXCEEDED, rejection.reason(),
                "root + three metadata scalars + input list + two elements is seven values, not a reset subtree");
    }

    @Test
    void checksTextBoundsInsideAnAlreadyBuiltScalar() {
        var limits = limits(8, 4, 32, 4, 16);
        var input = PayloadValue.of("12345");
        assertEquals(5, ((PayloadValue.TextValue) input).value().length());

        var rejection = assertThrows(PayloadException.class,
                () -> PayloadValue.fromJava(failureWithInput(input), limits));

        assertEquals(PayloadException.Reason.TEXT_TOO_LONG, rejection.reason());
    }

    @Test
    void checksKeyBoundsInsideAnAlreadyBuiltMap() {
        String oversizedKey = "input-key-1";
        var limits = limits(8, 4, 32, 64, 10);
        var input = PayloadValue.map(Map.of(oversizedKey, PayloadValue.NULL));
        assertEquals(11, oversizedKey.length(),
                "the nested key must exceed maxKeyLength while the fixed errorClass key still fits");

        var rejection = assertThrows(PayloadException.class,
                () -> PayloadValue.fromJava(failureWithInput(input), limits));

        assertEquals(PayloadException.Reason.KEY_TOO_LONG, rejection.reason());
    }

    private static NodeFailurePayload failureWithInput(Object input) {
        return new NodeFailurePayload("n", "E", "m", input);
    }

    private static PayloadLimits limits(
            int maxDepth, int maxCollectionSize, int maxValueCount, int maxTextLength, int maxKeyLength) {
        return new PayloadLimits(4_096, maxDepth, maxCollectionSize, maxValueCount, maxTextLength, maxKeyLength);
    }
}
