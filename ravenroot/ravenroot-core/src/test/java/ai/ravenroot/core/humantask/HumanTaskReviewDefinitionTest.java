package ai.ravenroot.core.humantask;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HumanTaskReviewDefinitionTest {
    @Test
    void selectsOnlyAnExactPayloadTextValue() {
        var definition = new HumanTaskReviewDefinition(1, "payload.messages.0.body", 64);

        var review = definition.presentation(Map.of("messages",
                List.of(Map.of("body", "Mail body\nsecond line"))));

        assertEquals("Mail body\nsecond line", review.text());
        assertEquals(64, review.maxUtf8Bytes());
    }

    @Test
    void refusesMissingNonTextAndOversizedSelectionsWithoutCoercionOrTruncation() {
        var definition = new HumanTaskReviewDefinition(1, "payload.body", 4);

        assertThrows(IllegalArgumentException.class,
                () -> definition.presentation(Map.of("body", 42)));
        assertThrows(IllegalArgumentException.class,
                () -> definition.presentation(Map.of("subject", "none")));
        assertThrows(IllegalArgumentException.class,
                () -> definition.presentation(Map.of("body", "12345")));
    }

    @Test
    void definitionSurfaceIsClosedAndPayloadRooted() {
        assertThrows(IllegalArgumentException.class,
                () -> new HumanTaskReviewDefinition(2, "payload", 64));
        assertThrows(IllegalArgumentException.class,
                () -> new HumanTaskReviewDefinition(1, "headers.authorization", 64));
        assertThrows(IllegalArgumentException.class,
                () -> new HumanTaskReviewDefinition(1, "payload..body", 64));
    }
}
