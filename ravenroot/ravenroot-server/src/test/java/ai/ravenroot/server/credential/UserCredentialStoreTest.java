package ai.ravenroot.server.credential;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store that holds what an author typed.
 *
 * <p>{@code CredentialRouteTest} exercises the same properties through a live server, which is what
 * the public contract requires. This file pins them one layer down, where a failure names the
 * store rather than the route — and where the isolation predicate can be mutated in one line.</p>
 */
class UserCredentialStoreTest {

    @TempDir
    Path directory;

    private static final String TENANT = "tenant-a";
    private static final String ALICE = "alice";
    private static final String BOB = "bob";

    /**
     * <b>The server chooses the reference, and two mints never agree.</b>
     *
     * <p>the server-minted-reference contract's whole reason: a caller-chosen name is a cross-tenant clobber
     * primitive. The wire reader refuses a proposed one ({@code UserCredentialWireTest}); this is the
     * other half — that what the server chooses instead is not derived from anything the caller
     * supplied, so two credentials with identical labels are still two credentials.</p>
     */
    @Test
    void everyMintedReferenceIsDistinctAndOpaque() {
        try (var store = SqliteUserCredentialStore.openUnder(directory)) {
            var first = store.mint(TENANT, ALICE, "Claude connection",
                    CredentialScheme.API_KEY, "", "sk-first".toCharArray());
            var second = store.mint(TENANT, ALICE, "Claude connection",
                    CredentialScheme.API_KEY, "", "sk-second".toCharArray());

            assertNotEquals(first.reference(), second.reference(),
                    "two credentials with the same label must still be two credentials");
            assertTrue(CredentialReference.isMinted(first.reference()), first.reference());
            assertFalse(first.reference().contains("Claude"),
                    "a reference derived from the label would leak the label into every graph "
                            + "that names it, and would let a caller predict one");
        }
    }

    /**
     * <b>A second author does not see the first's credentials.</b>
     *
     * <p>Listing is isolated by both author and tenant.</p>
     *
     * <p><b>Mutation proof.</b> Drop {@code AND subject = ?} from {@code listFor}'s WHERE clause in
     * {@link SqliteUserCredentialStore} and this reds: Bob's list gains Alice's entry. Drop
     * {@code tenant_id = ?} instead and {@link #anotherTenantIsAlsoAnotherOwner} reds.</p>
     */
    @Test
    void oneAuthorNeverSeesAnothersCredentials() {
        try (var store = SqliteUserCredentialStore.openUnder(directory)) {
            var alices = store.mint(TENANT, ALICE, "OpenAI", CredentialScheme.API_KEY, "",
                    "sk-alice".toCharArray());
            store.mint(TENANT, BOB, "Anthropic", CredentialScheme.API_KEY, "", "sk-bob".toCharArray());

            List<StoredCredential> bobSees = store.listFor(TENANT, BOB);

            assertEquals(1, bobSees.size(), () -> "Bob must see only his own: " + bobSees);
            assertEquals("Anthropic", bobSees.get(0).label());
            assertFalse(bobSees.stream().anyMatch(entry -> entry.reference().equals(alices.reference())),
                    "Alice's reference reached Bob's list");
        }
    }

    /** The same rule across tenants, which is the other predicate in the same WHERE clause. */
    @Test
    void anotherTenantIsAlsoAnotherOwner() {
        try (var store = SqliteUserCredentialStore.openUnder(directory)) {
            store.mint(TENANT, ALICE, "OpenAI", CredentialScheme.API_KEY, "", "sk-a".toCharArray());

            assertTrue(store.listFor("tenant-b", ALICE).isEmpty(),
                    "a subject id that repeats across tenants is not the same author");
        }
    }

    /**
     * <b>Ownership is a fact the store can be asked about, and it answers one bit.</b>
     *
     * <p>What {@link CredentialAdmission} consults. The absent-versus-foreign distinction is
     * deliberately not offered, so the pair cannot be used to enumerate references.</p>
     */
    @Test
    void ownershipIsExactAndSaysNothingElse() {
        try (var store = SqliteUserCredentialStore.openUnder(directory)) {
            var alices = store.mint(TENANT, ALICE, "OpenAI", CredentialScheme.API_KEY, "",
                    "sk-a".toCharArray());

            assertTrue(store.isOwnedBy(alices.reference(), TENANT, ALICE));
            assertFalse(store.isOwnedBy(alices.reference(), TENANT, BOB), "Bob does not own it");
            assertFalse(store.isOwnedBy(alices.reference(), "tenant-b", ALICE), "wrong tenant");
            assertFalse(store.isOwnedBy(CredentialReference.mint(), TENANT, ALICE),
                    "a reference that was never stored is owned by nobody");
        }
    }

    /**
     * <b>Each scheme resolves to the one thing its consumer expects.</b>
     *
     * <p>The rendering is fixed in one place precisely so a consumer never guesses from the shape of
     * the value — see {@link CredentialScheme}. A password containing a colon is the case that makes
     * guessing wrong, so it is the case tested.</p>
     */
    @Test
    void eachSchemeResolvesToItsOwnRendering() {
        try (var store = SqliteUserCredentialStore.openUnder(directory)) {
            var key = store.mint(TENANT, ALICE, "k", CredentialScheme.API_KEY, "",
                    "sk-plain".toCharArray());
            var basic = store.mint(TENANT, ALICE, "b", CredentialScheme.BASIC, "operator",
                    "pa:ss:word".toCharArray());
            var token = store.mint(TENANT, ALICE, "t", CredentialScheme.OAUTH_TOKEN, "",
                    "ya29.token".toCharArray());

            assertEquals("sk-plain", resolved(store, key.reference()));
            assertEquals("operator:pa:ss:word", resolved(store, basic.reference()),
                    "RFC 7617 puts the boundary at the FIRST colon, so a password may contain more");
            assertEquals("ya29.token", resolved(store, token.reference()),
                    "the scheme word belongs to whoever writes the header, not to the stored value");
        }
    }

    /**
     * <b>An operator's reference is not this store's business.</b>
     *
     * <p>The disjointness {@link CredentialReference#PREFIX} exists for. Without it the resolver
     * chain would be a precedence rule and an author could mint a reference that shadowed an
     * operator's binding.</p>
     */
    @Test
    void anOperatorProvisionedReferenceIsNotAnsweredHere() {
        try (var store = SqliteUserCredentialStore.openUnder(directory)) {
            store.mint(TENANT, ALICE, "k", CredentialScheme.API_KEY, "", "sk-a".toCharArray());

            assertTrue(store.resolve("openai-main").isEmpty(),
                    "the operator namespace must fall through to EnvironmentCredentialResolver");
            assertTrue(store.resolve(CredentialReference.PREFIX + "not-hex").isEmpty());
        }
    }

    /** The store outlives the process that wrote it: that is what "no restart" needs from it. */
    @Test
    void aCredentialSurvivesTheStoreBeingClosedAndReopened() {
        String reference;
        try (var store = SqliteUserCredentialStore.openUnder(directory)) {
            reference = store.mint(TENANT, ALICE, "k", CredentialScheme.API_KEY, "",
                    "sk-durable".toCharArray()).reference();
        }
        try (var reopened = SqliteUserCredentialStore.openUnder(directory)) {
            assertEquals("sk-durable", resolved(reopened, reference));
            assertEquals(1, reopened.listFor(TENANT, ALICE).size());
        }
    }

    /**
     * <b>One credential serves many consumers, and using it in one does not spend it.</b>
     *
     * <p>The same credential can be selected by multiple consumers —
     * assistant, llm-prompt, agent — and using it in one does not make it unavailable to others."</p>
     *
     * <p><b>Two of those three consumers exist; the assistant is not one of them, and quoting the
     * statement above without saying so would overstate coverage.</b>
     * {@code llm-prompt} and {@code agent} both carry a {@code SECRET_REFERENCE} property and hand it
     * to their adapter. The assistant does not: {@code grep -rn "credentialRef\|CredentialResolver"}
     * over {@code ai/ravenroot/server/assistant} returns nothing. It authenticates by its own two
     * routes — the operator's deployment-wide key or the reachable per-author device flow — and reads
     * no reference at all. Making it a consumer of this store is real work with
     * its own decisions (which author's credential serves a turn, and what happens when they have
     * several), and it is recorded in the register rather than implied by this test being green.</p>
     *
     * <p>This is not the tautology it looks like, and the reason is {@link ai.ravenroot.api.security.SecretValue}:
     * its {@code close()} zeroes the array it holds, and callers are told to close immediately after
     * use. A store that returned <em>the same instance</em> to every caller would therefore be a store
     * where the first consumer to finish blanks the value for every consumer still running — and with
     * ADR 0024's concurrent traversals, "still running" is the normal case rather than the unlucky
     * one. So the property is real and it is about instance identity.</p>
     *
     * <p><b>Mutation proof.</b> Cache one {@code SecretValue} per reference in
     * {@link SqliteUserCredentialStore#resolve} and hand the same instance out repeatedly; this reds
     * on the second resolution, which reads as an empty secret.</p>
     */
    @Test
    void oneCredentialServesManyConsumersAndClosingOneDoesNotSpendIt() {
        try (var store = SqliteUserCredentialStore.openUnder(directory)) {
            String reference = store.mint(TENANT, ALICE, "Claude connection",
                    CredentialScheme.API_KEY, "", "sk-shared".toCharArray()).reference();

            var first = store.resolve(reference).orElseThrow();
            var second = store.resolve(reference).orElseThrow();
            assertNotSame(first, second,
                    "two consumers must not share one erasable instance");

            // The first consumer finishes and erases its copy, exactly as the contract tells it to.
            first.close();

            assertEquals("sk-shared", new String(second.copy()),
                    "the second consumer's credential was blanked by the first one finishing");
            assertEquals("sk-shared", resolved(store, reference),
                    "and a third consumer arriving later still resolves it");
            second.close();
        }
    }

    /**
     * <b>Nothing that comes out of this store can carry the value.</b>
     *
     * <p>Asserted against the type rather than against a rendering, because a rendering can be
     * changed and a missing component cannot be printed. This is the structural half of the
     * no-secret-output guarantee; {@code CredentialRouteTest} does the behavioral half by mutation.</p>
     */
    @Test
    void theTypeReturnedToCallersHasNoComponentThatCouldHoldASecret() {
        Set<String> components = java.util.Arrays.stream(StoredCredential.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        assertEquals(Set.of("reference", "label", "scheme", "username", "createdAt"), components,
                "StoredCredential is what the route, the list and the CLI all render. A component "
                        + "added here is a component something will eventually print, so the set is "
                        + "pinned structurally rather than assumed.");
    }

    /** A newer file is refused rather than read with columns this build cannot see. */
    @Test
    void aStoreWrittenByANewerBuildIsRefused() throws Exception {
        try (var store = SqliteUserCredentialStore.openUnder(directory)) {
            store.mint(TENANT, ALICE, "k", CredentialScheme.API_KEY, "", "sk".toCharArray());
        }
        Path file = directory.toAbsolutePath().normalize()
                .resolve(SqliteUserCredentialStore.FILE_NAME);
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 99");
        }

        var refused = assertThrows(IllegalStateException.class,
                () -> SqliteUserCredentialStore.openUnder(directory));
        assertTrue(refused.getMessage().contains("newer build"), refused.getMessage());
    }

    private static String resolved(UserCredentialStore store, String reference) {
        try (var secret = store.resolve(reference).orElseThrow()) {
            return new String(secret.copy());
        }
    }
}
