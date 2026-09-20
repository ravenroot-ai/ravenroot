package ai.ravenroot.api.persistence;

import ai.ravenroot.api.payload.PayloadValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
                        true, 32, List.of(), Optional.of(10.0), Optional.of(20.0)),
                new HumanTaskFormSchema.Field("notes", "Notes", "Explain the decision",
                        HumanTaskFormSchema.Type.MULTILINE_TEXT, false, 1_024, List.of()),
                new HumanTaskFormSchema.Field("region", "Region", "", HumanTaskFormSchema.Type.ENUM,
                        false, 16, List.of("eu", "us")),
                new HumanTaskFormSchema.Field("due", "Due", "", HumanTaskFormSchema.Type.DATE,
                        false, 32, List.of())));

        assertEquals(schema, HumanTaskFormSchema.decode(schema.encode()));
        schema.requireResponse(PayloadValue.map(Map.of(
                "approved", PayloadValue.of(true),
                "amount", PayloadValue.of(12.5),
                "notes", PayloadValue.of("first line\nsecond line"),
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
        assertThrows(IllegalArgumentException.class, () -> schema.requireResponse(PayloadValue.map(Map.of(
                "approved", PayloadValue.of(true), "amount", PayloadValue.of(21)))));

        String legacy = "{\"version\":1,\"fields\":[{\"name\":\"legacy\",\"label\":\"Legacy\","
                + "\"help\":\"\",\"type\":\"TEXT\",\"required\":false,\"maxUtf8Bytes\":16,"
                + "\"allowedValues\":[]}]}";
        assertEquals(Optional.empty(), HumanTaskFormSchema.decode(legacy).fields().getFirst().minimum());
    }

    @Test
    void requiredBooleanFalseAndExactUtf8TextBoundariesAreValid() {
        var schema = new HumanTaskFormSchema(1, List.of(
                new HumanTaskFormSchema.Field("approved", "Approved", "",
                        HumanTaskFormSchema.Type.BOOLEAN, true, 1, List.of()),
                new HumanTaskFormSchema.Field("title", "Title", "",
                        HumanTaskFormSchema.Type.TEXT, true, 4, List.of()),
                new HumanTaskFormSchema.Field("notes", "Notes", "",
                        HumanTaskFormSchema.Type.MULTILINE_TEXT, true, 4, List.of())));

        schema.requireResponse(PayloadValue.map(Map.of(
                "approved", PayloadValue.of(false),
                "title", PayloadValue.of("abcd"),
                "notes", PayloadValue.of("éé"))));
        assertThrows(IllegalArgumentException.class, () -> schema.requireResponse(PayloadValue.map(Map.of(
                "approved", PayloadValue.of(false),
                "title", PayloadValue.of("ééx"),
                "notes", PayloadValue.of("🙂")))));
        assertThrows(IllegalArgumentException.class, () -> schema.requireResponse(PayloadValue.map(Map.of(
                "approved", PayloadValue.of(false),
                "title", PayloadValue.of("abc"),
                "notes", PayloadValue.of("🙂x")))));
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
        assertThrows(IllegalArgumentException.class, () -> new HumanTaskFormSchema.Field(
                "field", "Label", "", HumanTaskFormSchema.Type.INTEGER, true, 16, List.of(),
                Optional.of(1.5), Optional.of(10.0)));
        assertThrows(IllegalArgumentException.class, () -> new HumanTaskFormSchema.Field(
                "field", "Label", "", HumanTaskFormSchema.Type.DECIMAL, true, 16, List.of(),
                Optional.of(10.0), Optional.of(1.0)));
    }

    @Test
    void defaultOffResponderEnforcementPermitsAllActionsAndEnforcedReviewIsIndependent() {
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
        assertTrue(permissive.contains(HumanTaskConfirmationAction.CANCEL));
        assertTrue(new HumanTaskAttentionAuthorization("issuer:other", java.util.Set.of(),
                java.util.Set.of(), false).mayReview(requirements, "issuer:requester"));

        var enforced = new HumanTaskAttentionAuthorization("issuer:requester", java.util.Set.of(),
                java.util.Set.of(), true).permittedActions(requirements, "issuer:requester",
                presentation.actions());
        assertFalse(enforced.contains(HumanTaskConfirmationAction.RESOLVE));
        assertFalse(enforced.contains(HumanTaskConfirmationAction.DENY));
        assertTrue(enforced.contains(HumanTaskConfirmationAction.CANCEL));
        assertFalse(new HumanTaskAttentionAuthorization("issuer:requester", java.util.Set.of(),
                java.util.Set.of(), true).mayReview(requirements, "issuer:requester"));

        var override = new HumanTaskAttentionAuthorization("issuer:administrator", java.util.Set.of(),
                java.util.Set.of(), true, true);
        assertEquals(presentation.actions(), override.permittedActions(
                requirements, "issuer:requester", presentation.actions()));
        assertTrue(override.mayReview(requirements, "issuer:requester"));
    }
}
