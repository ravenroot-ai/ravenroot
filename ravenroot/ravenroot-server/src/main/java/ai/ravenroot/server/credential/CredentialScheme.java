package ai.ravenroot.server.credential;

import java.util.Locale;

/**
 * What kind of credential an author entered, and therefore what a consumer receives.
 *
 * <h2>Why the scheme is stored rather than inferred</h2>
 * <p>The three supported kinds — an API key, a username and password, an OAuth subscription —
 * are indistinguishable once they are text. A consumer that had to guess would guess from the shape
 * of the value, which is how a password containing a colon becomes a malformed basic-auth header. The
 * author says which one it is at the moment they know, and it travels with the record.</p>
 *
 * <h2>What each one resolves to</h2>
 * <p>{@link ai.ravenroot.api.security.CredentialResolver} yields a single {@code SecretValue}, so each
 * scheme has exactly one rendering defined here rather than per consumer:</p>
 * <ul>
 *   <li>{@link #API_KEY} — the key itself, verbatim.</li>
 *   <li>{@link #BASIC} — {@code username:password}, the pre-Base64 form RFC 7617 defines. Not
 *       Base64-encoded here: encoding is the HTTP layer's job, and a consumer that needed the two
 *       halves separately could still split on the first colon, which is where RFC 7617 puts the
 *       boundary.</li>
 *   <li>{@link #OAUTH_TOKEN} — the bearer token, without the {@code Bearer } prefix, for the same
 *       reason: the scheme word belongs to whoever writes the header.</li>
 * </ul>
 */
public enum CredentialScheme {

    API_KEY("api-key"),

    BASIC("basic"),

    /**
     * A token an author obtained from a provider.
     *
     * <p><b>This entry does not conduct an OAuth exchange.</b> It stores a token the author already
     * holds. The assistant's device-flow sign-in is a separate mechanism with its own per-author
     * store, and it is deliberately not folded in here —
     * see {@code docs/getting-started/credenziali-dall-interfaccia.md} for which of the two an author
     * wants and why the product has both.</p>
     */
    OAUTH_TOKEN("oauth-token");

    private final String wireValue;

    CredentialScheme(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    /** Whether this scheme carries a username beside the secret. Only {@link #BASIC} does. */
    public boolean carriesUsername() {
        return this == BASIC;
    }

    /**
     * Parses an author's choice.
     *
     * <p><b>No default.</b> Unlike {@code AssistantCredentialSource}, which defaults because a
     * deployment that names nothing has a correct answer, an unnamed scheme here means the author did
     * not say what they were entering — and guessing would pick the rendering their consumer receives.
     */
    public static CredentialScheme parse(String raw) {
        String normalised = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        for (CredentialScheme candidate : values()) {
            if (candidate.wireValue.equals(normalised)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unknown credential scheme");
    }
}
