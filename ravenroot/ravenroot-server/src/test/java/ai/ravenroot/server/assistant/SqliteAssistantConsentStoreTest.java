package ai.ravenroot.server.assistant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The consent register keeps what it was told and keeps each author's decision separate.
 *
 * <p>Every test here reopens the store before asserting. Asserting against the same open instance
 * would pass equally well against a {@code HashMap} field, so the round trip through the file is the
 * test, not an incidental detail
 * of it.</p>
 */
class SqliteAssistantConsentStoreTest {

    private static final String ANTHROPIC = "anthropic";

    @TempDir
    Path directory;

    /**
     * <b>Consent survives the process that recorded it.</b>
     *
     * <p><b>Mutation proof.</b> Replace the table with an in-memory map and this test reds on the
     * reopened instance, which is the only instance it asserts against.</p>
     */
    @Test
    void consentSurvivesReopening() {
        try (var store = SqliteAssistantConsentStore.openUnder(directory)) {
            store.recordConsent("author-one", ANTHROPIC,
                    EnumSet.of(AssistantContextClass.STATUS, AssistantContextClass.RUNTIME));
        }
        try (var reopened = SqliteAssistantConsentStore.openUnder(directory)) {
            assertEquals(Set.of(AssistantContextClass.STATUS, AssistantContextClass.RUNTIME),
                    reopened.consentedClasses("author-one", ANTHROPIC),
                    "a register that forgets across a restart is not a register");
            assertTrue(reopened.hasConsented("author-one", ANTHROPIC));
        }
    }

    /**
     * <b>One author's consent is not another's.</b>
     *
     * <p>Asserted in both directions: the second author sees nothing, and the first still sees exactly
     * what they chose. A store that keyed on the provider alone passes the first assertion by
     * returning everything to everyone and fails the second only if the two authors chose differently,
     * so they do.</p>
     */
    @Test
    void consentIsPerAuthor() {
        try (var store = SqliteAssistantConsentStore.openUnder(directory)) {
            store.recordConsent("author-one", ANTHROPIC, EnumSet.of(AssistantContextClass.NODE_TYPES));
            store.recordConsent("author-two", ANTHROPIC,
                    EnumSet.of(AssistantContextClass.EXECUTION_EVENTS));
        }
        try (var reopened = SqliteAssistantConsentStore.openUnder(directory)) {
            assertEquals(Set.of(AssistantContextClass.NODE_TYPES),
                    reopened.consentedClasses("author-one", ANTHROPIC));
            assertEquals(Set.of(AssistantContextClass.EXECUTION_EVENTS),
                    reopened.consentedClasses("author-two", ANTHROPIC));
            assertEquals(Set.of(), reopened.consentedClasses("author-three", ANTHROPIC),
                    "an author who never chose has consented to nothing, not to everything");
            assertFalse(reopened.hasConsented("author-three", ANTHROPIC));
        }
    }

    /**
     * <b>One provider's consent is not another's.</b>
     *
     * <p>The same author, the same classes, two provider ids. Consent is a decision about who receives
     * the data, so an operator who reconfigures the deployment onto a second provider must not inherit
     * the permission the author gave the first.</p>
     */
    @Test
    void consentIsPerProvider() {
        try (var store = SqliteAssistantConsentStore.openUnder(directory)) {
            store.recordConsent("author-one", ANTHROPIC,
                    EnumSet.allOf(AssistantContextClass.class));
        }
        try (var reopened = SqliteAssistantConsentStore.openUnder(directory)) {
            assertEquals(EnumSet.allOf(AssistantContextClass.class),
                    EnumSet.copyOf(reopened.consentedClasses("author-one", ANTHROPIC)));
            assertEquals(Set.of(), reopened.consentedClasses("author-one", "some-other-provider"),
                    "consent given to one provider is not consent given to another");
        }
    }

    /**
     * <b>A later choice replaces the earlier one, and the empty set is a complete revocation.</b>
     *
     * <p>The narrowing case is asserted before the revocation because it is the one a merge-based
     * implementation gets wrong: an author who unticks a box would keep the class they removed, and
     * every other test in this file would still pass.</p>
     */
    @Test
    void aLaterChoiceReplacesTheEarlierOne() {
        try (var store = SqliteAssistantConsentStore.openUnder(directory)) {
            store.recordConsent("author-one", ANTHROPIC, EnumSet.allOf(AssistantContextClass.class));
            store.recordConsent("author-one", ANTHROPIC, EnumSet.of(AssistantContextClass.STATUS));
        }
        try (var reopened = SqliteAssistantConsentStore.openUnder(directory)) {
            assertEquals(Set.of(AssistantContextClass.STATUS),
                    reopened.consentedClasses("author-one", ANTHROPIC),
                    "unticking a class must remove it, not leave it granted");
            reopened.recordConsent("author-one", ANTHROPIC, Set.of());
        }
        try (var afterRevocation = SqliteAssistantConsentStore.openUnder(directory)) {
            assertEquals(Set.of(), afterRevocation.consentedClasses("author-one", ANTHROPIC));
            assertFalse(afterRevocation.hasConsented("author-one", ANTHROPIC),
                    "a full revocation must also answer the provider-level question as no, or the "
                            + "two answers can disagree");
        }
    }

    /**
     * <b>An unreachable register throws; it does not answer "nothing".</b>
     *
     * <p>This is the assertion that keeps the failure mode honest, and it is worth stating why the
     * quieter alternative is worse. {@code AssistantService#send} consults this register before it
     * composes anything, so a throw ends the turn with no bytes sent — the same outcome as a refusal,
     * arrived at safely. But an empty set returned on failure would be <em>indistinguishable from a
     * genuine refusal</em>: the author would see an assistant that had silently stopped reading their
     * deployment, and the operator would have nothing naming the database. Both are fail-closed; only
     * one is diagnosable.</p>
     *
     * <p>A closed connection is used as the fault because it is the one unreachable-database state a
     * test can provoke deterministically, on every platform, without breaking a file underneath a live
     * store or depending on filesystem permissions that differ between CI and a developer's machine.</p>
     *
     * <p><b>Mutation proof.</b> Make {@code consentedClasses} catch {@code SQLException} and return
     * {@code Set.of()} — the shape that turns an outage into a silent revocation — and this test reds,
     * because the call returns instead of throwing.</p>
     */
    @Test
    void anUnreachableRegisterFailsClosedAndSaysSo() {
        var store = SqliteAssistantConsentStore.openUnder(directory);
        store.recordConsent("author-one", ANTHROPIC, EnumSet.of(AssistantContextClass.STATUS));
        assertEquals(Set.of(AssistantContextClass.STATUS),
                store.consentedClasses("author-one", ANTHROPIC),
                "the register must be working before it is broken, or this test proves nothing");

        store.close();

        var refused = assertThrows(IllegalStateException.class,
                () -> store.consentedClasses("author-one", ANTHROPIC),
                "an unreachable register must not answer 'this author consented to nothing': that is "
                        + "indistinguishable from a real refusal");
        assertTrue(refused.getMessage().contains("consent"),
                () -> "the failure must name what could not be read: " + refused.getMessage());
    }

}
