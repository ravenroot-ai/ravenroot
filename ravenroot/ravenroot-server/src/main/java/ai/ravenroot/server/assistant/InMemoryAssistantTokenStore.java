package ai.ravenroot.server.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The session-scoped token holder under the per-author credential contract: in memory, never written
 * anywhere, gone when the process ends.
 *
 * <h2>This is the multi-tenant shape, and the other one is deliberately absent</h2>
 * <p>the per-author credential contract fixes two deployment shapes and says the unsafe combination "has no code
 * path — that is a guarantee, not a habit". A <b>local single-user composition</b> may wire a
 * keychain-backed store, because the OS session and the token's owner are one principal. A
 * <b>multi-tenant server composition</b> may wire only this one, because a shared server's keychain
 * belongs to the service account, so persisting there would put one user's provider token in the
 * operator's vault.</p>
 *
 * <p><b>Only this shape is built.</b> A keychain store needs native platform access this build has no
 * dependency for, and the guarantee above is kept most
 * cheaply by there being no persistent implementation to wire by mistake. A deployment that wants
 * persistence implements {@link AssistantTokenStore} and takes on the credential-lifecycle contract's obligations —
 * TTL, refresh and revocation — which is exactly where that ruling puts them.</p>
 *
 * <h2>Why forgetting is the feature</h2>
 * <p>Losing every token on restart looks like a defect until you read the assistant token-persistence contract:
 * signing in again is acceptable "even on demand whenever the application opens". Given that,
 * this class needs no refresh token, no rotation and no revocation list — the three obligations
 * the credential-lifecycle contract says core must not carry. It discharges them by holding nothing that outlives the
 * session it was granted for.</p>
 *
 * <h2>Expiry is enforced on read, not by a sweeper</h2>
 * <p>A background thread that evicted expired tokens would be a second place where "is this still
 * valid?" is decided, and the two would disagree in the window between them. Checking at the only
 * moment the answer is used means an expired token is never returned, whether or not anything has
 * swept — and {@link #tokenFor} is the only way out of this class.</p>
 */
public final class InMemoryAssistantTokenStore implements AssistantTokenStore {

    private record Held(AssistantCredential credential, Instant expiresAt) { }

    private final Map<String, Held> bySubject = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryAssistantTokenStore() {
        this(Clock.systemUTC());
    }

    public InMemoryAssistantTokenStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Records the token this author just obtained.
     *
     * @param lifetime how long the provider said it lasts. A non-positive lifetime stores nothing:
     *                 an already-expired token is not a token, and storing it would produce an author
     *                 who appears signed in and is refused on every turn.
     */
    public void signIn(String subject, AssistantCredential credential, Duration lifetime) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(lifetime, "lifetime");
        if (credential.scheme() != AssistantCredential.Scheme.OAUTH_BEARER) {
            // An operator key stored per author would silently replace the selected OAuth source,
            // arriving by a different door: every author would look signed in, on the
            // deployment's account, with no operator having chosen that.
            throw new IllegalArgumentException(
                    "only an OAuth bearer token belongs in the per-author token store");
        }
        if (lifetime.isZero() || lifetime.isNegative()) {
            return;
        }
        bySubject.put(subject, new Held(credential, clock.instant().plus(lifetime)));
    }

    /** Forgets this author's token. What a sign-out is, and what a revocation is answered with. */
    public void signOut(String subject) {
        bySubject.remove(Objects.requireNonNull(subject, "subject"));
    }

    @Override
    public Optional<AssistantCredential> tokenFor(String subject) {
        if (subject == null) {
            return Optional.empty();
        }
        Held held = bySubject.get(subject);
        if (held == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(held.expiresAt())) {
            // Removed on the way out so a long-lived process does not accumulate dead entries, but
            // the answer would be empty either way -- the removal is hygiene, the check is the rule.
            bySubject.remove(subject, held);
            return Optional.empty();
        }
        return Optional.of(held.credential());
    }

    /** How many authors currently hold a token. Diagnostic only; never the tokens themselves. */
    public int signedInCount() {
        return bySubject.size();
    }
}
