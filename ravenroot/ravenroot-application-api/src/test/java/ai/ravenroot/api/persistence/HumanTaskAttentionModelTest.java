package ai.ravenroot.api.persistence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HumanTaskAttentionModelTest {

    @Test
    void queryRequiresOneExactEnclosingContext() {
        assertThrows(IllegalArgumentException.class, () -> new HumanTaskAttentionQuery(
                "graph-v1", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), 10));
        assertThrows(IllegalArgumentException.class, () -> new HumanTaskAttentionQuery(
                "graph-v1", Optional.of("deployment"), Optional.of(UUID.randomUUID()),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), 10));
    }

    @Test
    void cursorCarriesItsBoundaryAndBindsTenantContextAndAuthority() {
        var query = HumanTaskAttentionQuery.forDeployment("graph-v1", "deployment-a", 20);
        var authorization = new HumanTaskAttentionAuthorization("issuer|USER|operator",
                Set.of("REVIEWER"), Set.of("human:decide"));
        Instant createdAt = Instant.parse("2026-09-06T00:00:00.123456789Z");
        UUID taskId = UUID.fromString("00000000-0000-0000-0000-000000000125");

        HumanTaskAttentionCursor cursor = HumanTaskAttentionCursor.issue(
                "tenant-a", query, authorization, createdAt, taskId);
        assertEquals(new HumanTaskAttentionCursor.Boundary(createdAt, taskId),
                cursor.boundary("tenant-a", query, authorization));
        assertEquals(cursor, new HumanTaskAttentionCursor(cursor.value()));
        assertEquals(cursor, HumanTaskAttentionCursor.issue("tenant-a",
                HumanTaskAttentionQuery.forDeployment("graph-v1", "deployment-a", 5),
                authorization, createdAt, taskId),
                "page size is not a context identity and may change between requests");

        assertThrows(IllegalArgumentException.class,
                () -> cursor.boundary("tenant-b", query, authorization));
        assertThrows(IllegalArgumentException.class, () -> cursor.boundary("tenant-a",
                HumanTaskAttentionQuery.forDeployment("graph-v1", "deployment-b", 20),
                authorization));
        assertThrows(IllegalArgumentException.class, () -> cursor.boundary("tenant-a", query,
                new HumanTaskAttentionAuthorization("issuer|USER|operator",
                        Set.of("REVIEWER", "OPERATIONS"), Set.of("human:decide"))));
        String changed = (cursor.value().startsWith("A") ? "B" : "A") + cursor.value().substring(1);
        assertThrows(IllegalArgumentException.class, () -> new HumanTaskAttentionCursor(changed)
                .boundary("tenant-a", query, authorization));
    }

    @Test
    void presentationAndAuthorizedActionsRetainExplicitOrder() {
        var presentation = new HumanTaskConfirmationPresentation(1, "Confirm",
                HumanTaskCommentRequirement.OPTIONAL,
                List.of(HumanTaskConfirmationAction.CANCEL, HumanTaskConfirmationAction.DENY,
                        HumanTaskConfirmationAction.RESOLVE), "Resolve", "Deny", "Cancel");
        assertEquals(List.of(HumanTaskConfirmationAction.CANCEL, HumanTaskConfirmationAction.DENY,
                HumanTaskConfirmationAction.RESOLVE), presentation.actions());
        assertThrows(IllegalArgumentException.class, () -> new HumanTaskConfirmationPresentation(
                1, "Confirm", HumanTaskCommentRequirement.OPTIONAL,
                List.of(HumanTaskConfirmationAction.DENY, HumanTaskConfirmationAction.DENY),
                "Resolve", "Deny", "Cancel"));
    }

    @Test
    void reloadLocatorRequiresACompletePositiveFence() {
        assertThrows(IllegalArgumentException.class,
                () -> new HumanTaskAttentionLocator(null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new HumanTaskAttentionLocator(UUID.randomUUID(), 0));
    }
}
