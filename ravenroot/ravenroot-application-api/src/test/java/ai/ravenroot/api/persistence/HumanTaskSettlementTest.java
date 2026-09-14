package ai.ravenroot.api.persistence;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanTaskSettlementTest {
    private static final OpaquePayload RESPONSE = OpaquePayload.of(
            "response".getBytes(StandardCharsets.UTF_8), "text/plain");

    @Test
    void actionResponseAndAuditCommentHaveDistinctClosedShapes() {
        HumanTaskSettlement resolved = HumanTaskSettlement.resolve(RESPONSE, "audit only");
        assertEquals(HumanTaskConfirmationAction.RESOLVE, resolved.action());
        assertEquals(RESPONSE, resolved.response().orElseThrow());
        assertEquals("audit only", resolved.comment());

        HumanTaskSettlement denied = HumanTaskSettlement.deny("reason");
        assertEquals(HumanTaskConfirmationAction.DENY, denied.action());
        assertTrue(denied.response().isEmpty());
        assertEquals("reason", denied.comment());

        assertThrows(IllegalArgumentException.class, () -> new HumanTaskSettlement(2,
                HumanTaskConfirmationAction.RESOLVE, Optional.of(RESPONSE), ""));
        assertThrows(IllegalArgumentException.class, () -> new HumanTaskSettlement(1,
                HumanTaskConfirmationAction.RESOLVE, Optional.empty(), "comment is not a response"));
        assertThrows(IllegalArgumentException.class, () -> new HumanTaskSettlement(1,
                HumanTaskConfirmationAction.CANCEL, Optional.of(RESPONSE), ""));
    }
}
