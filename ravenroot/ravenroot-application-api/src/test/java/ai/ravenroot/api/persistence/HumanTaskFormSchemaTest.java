package ai.ravenroot.api.persistence;

import ai.ravenroot.api.payload.PayloadValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HumanTaskFormSchemaTest {
    @Test
    void canonicalRoundTripAndTypedResponseAreClosed() {
        var schema = new HumanTaskFormSchema(1, List.of(
                new HumanTaskFormSchema.Field("approved", "Approved", "", HumanTaskFormSchema.Type.BOOLEAN,
                        true, 16, List.of()),
                new HumanTaskFormSchema.Field("amount", "Amount", "", HumanTaskFormSchema.Type.DECIMAL,
                        true, 32, List.of()),
                new HumanTaskFormSchema.Field("region", "Region", "", HumanTaskFormSchema.Type.ENUM,
                        false, 16, List.of("eu", "us")),
                new HumanTaskFormSchema.Field("due", "Due", "", HumanTaskFormSchema.Type.DATE,
                        false, 32, List.of())));

        assertEquals(schema, HumanTaskFormSchema.decode(schema.encode()));
        schema.requireResponse(PayloadValue.map(Map.of(
                "approved", PayloadValue.of(true),
                "amount", PayloadValue.of(12.5),
                "region", PayloadValue.of("eu"),
                "due", PayloadValue.of("2026-09-20"))));
        assertThrows(IllegalArgumentException.class, () -> schema.requireResponse(PayloadValue.map(Map.of(
                "approved", PayloadValue.of(true), "amount", PayloadValue.of(12),
                "unexpected", PayloadValue.of("data")))));
        assertThrows(IllegalArgumentException.class, () -> schema.requireResponse(PayloadValue.map(Map.of(
                "approved", PayloadValue.of("true"), "amount", PayloadValue.of(12)))));
        assertThrows(IllegalArgumentException.class, () -> schema.requireResponse(PayloadValue.map(Map.of(
                "approved", PayloadValue.of(true), "amount", PayloadValue.of(12),
                "region", PayloadValue.of("other")))));
    }

    @Test
    void schemaRejectsExecutableShapesAndBounds() {
        assertThrows(IllegalArgumentException.class, () -> HumanTaskFormSchema.decode(
                "{\"version\":1,\"fields\":[],\"script\":\"alert(1)\"}"));
        assertThrows(IllegalArgumentException.class, () -> new HumanTaskFormSchema.Field(
                "bad name", "Label", "", HumanTaskFormSchema.Type.TEXT, true, 16, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new HumanTaskFormSchema.Field(
                "field", "Label", "", HumanTaskFormSchema.Type.TEXT, true,
                HumanTaskFormSchema.MAX_TEXT_UTF8_BYTES + 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new HumanTaskFormSchema.Field(
                "field", "<script>\u0000", "", HumanTaskFormSchema.Type.TEXT, true, 16, List.of()));
    }

    @Test
    void defaultOffResponderEnforcementKeepsCancelRequesterOnly() {
        var presentation = new HumanTaskConfirmationPresentation(1, "Review",
                HumanTaskCommentRequirement.OPTIONAL,
                List.of(HumanTaskConfirmationAction.RESOLVE, HumanTaskConfirmationAction.DENY,
                        HumanTaskConfirmationAction.CANCEL),
                "Resolve", "Deny", "Cancel");
        var requirements = new HandlerAuthorization(java.util.Set.of("reviewer"),
                java.util.Set.of("tasks:settle"));
        var permissive = new HumanTaskAttentionAuthorization("issuer:other", java.util.Set.of(),
                java.util.Set.of(), false).permittedActions(requirements, "issuer:requester",
                presentation.actions());
        assertTrue(permissive.contains(HumanTaskConfirmationAction.RESOLVE));
        assertTrue(permissive.contains(HumanTaskConfirmationAction.DENY));
        assertFalse(permissive.contains(HumanTaskConfirmationAction.CANCEL));

        var enforced = new HumanTaskAttentionAuthorization("issuer:requester", java.util.Set.of(),
                java.util.Set.of(), true).permittedActions(requirements, "issuer:requester",
                presentation.actions());
        assertFalse(enforced.contains(HumanTaskConfirmationAction.RESOLVE));
        assertFalse(enforced.contains(HumanTaskConfirmationAction.DENY));
        assertTrue(enforced.contains(HumanTaskConfirmationAction.CANCEL));
    }
}
