package ai.ravenroot.server.assistant;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-author token holder and its credential contract.
 *
 * <p>Every test here uses a movable clock rather than real time. A token store whose expiry test
 * sleeps is a test that is either slow or flaky, and usually both.</p>
 */
class InMemoryAssistantTokenStoreTest {

    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-08-20T12:00:00Z"));
    private InMemoryAssistantTokenStore store() {
        return new InMemoryAssistantTokenStore(new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        });
    }

    /** <b>One author's token is that author's, and nobody else is signed in by it.</b> */
    @Test
    void aTokenBelongsToTheAuthorItWasGrantedTo() {
        var store = store();
        store.signIn("author-one", AssistantCredential.oauthToken("token-one"), Duration.ofHours(1));

        assertTrue(store.tokenFor("author-one").isPresent());
        assertTrue(store.tokenFor("author-two").isEmpty(),
                "one author's sign-in must not sign in another");
        assertTrue(store.tokenFor(null).isEmpty(), "an unauthenticated caller holds no token");
    }

    /**
     * <b>An expired token is not returned, and the check happens on read.</b>
     *
     * <p><b>Mutation proof.</b> Delete the expiry branch in {@code tokenFor} and this test reds: the
     * token is still returned an hour after it lapsed, which in production means every turn failing
     * against the provider with a 401 the panel reports as {@code PROVIDER_REJECTED}.</p>
     */
    @Test
    void anExpiredTokenIsGoneWithoutAnythingHavingSweptIt() {
        var store = store();
        store.signIn("author-one", AssistantCredential.oauthToken("token-one"), Duration.ofMinutes(30));
        assertTrue(store.tokenFor("author-one").isPresent(), "valid before it expires");

        now.set(now.get().plus(Duration.ofMinutes(31)));

        assertTrue(store.tokenFor("author-one").isEmpty(), "an expired token must not be served");
        assertEquals(0, store.signedInCount(),
                "the lapsed entry is dropped on the way out rather than accumulating");
    }

    /**
     * <b>An already-expired lifetime stores nothing.</b>
     *
     * <p>Otherwise the author appears signed in and is refused on every turn — the worst of both
     * states, and one where the panel would say {@code ready} while nothing worked.</p>
     */
    @Test
    void anAlreadyExpiredLifetimeStoresNothing() {
        var store = store();
        store.signIn("author-one", AssistantCredential.oauthToken("token-one"), Duration.ZERO);

        assertTrue(store.tokenFor("author-one").isEmpty());
        assertEquals(0, store.signedInCount());
    }

    /** <b>Signing out forgets the token, which is also what a revocation is answered with.</b> */
    @Test
    void signingOutForgetsTheToken() {
        var store = store();
        store.signIn("author-one", AssistantCredential.oauthToken("token-one"), Duration.ofHours(1));

        store.signOut("author-one");

        assertTrue(store.tokenFor("author-one").isEmpty());
    }

    /**
     * <b>An operator API key cannot be smuggled into the per-author store.</b>
     *
     * <p>This is the silent-replacement failure arriving by a different door than the one
     * {@code AssistantOauthCredentialTest} guards. If a deployment key could be stored per author,
     * every author would look signed in, every turn would be billed to the deployment, and no
     * operator would have chosen it — the configuration would still say {@code oauth}.</p>
     */
    @Test
    void anOperatorKeyCannotBeStoredAsAnAuthorsToken() {
        var store = store();

        var refused = assertThrows(IllegalArgumentException.class,
                () -> store.signIn("author-one", AssistantCredential.ofNullable("sk-ant-operator-key"),
                        Duration.ofHours(1)));

        assertTrue(refused.getMessage().contains("OAuth"),
                () -> "the refusal must name what belongs here: " + refused.getMessage());
        assertEquals(0, store.signedInCount());
    }
}
