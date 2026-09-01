package ai.ravenroot.server.assistant;

import java.util.Locale;

/**
 * Which credential a deployment authenticates the assistant with — chosen, never discovered.
 *
 * <h2>Why this is a switch and not a preference order</h2>
 * <p>The obvious shape for "support OAuth as well as a key" is a chain: use the token if there is
 * one, otherwise the key. This contract forbids that fallback, and the reason is worth stating
 * because the chain looks strictly more helpful:</p>
 *
 * <ul>
 *   <li><b>It bills the wrong account, silently.</b> The operator key is charged to the deployment;
 *       an author's OAuth token is charged to that author's own account. A chain moves money between
 *       two parties on the basis of which credential happened to be present at that instant, and
 *       neither party is told. A subscription does not emit API keys; they are separate products
 *       with separate billing.</li>
 *   <li><b>It is not even a graceful degradation on the wire.</b> Presenting both credentials to the
 *       Anthropic API is a request the provider <em>rejects</em>, not one it serves with whichever it
 *       prefers. So the fallback's supposed benefit — something still works — is not on offer.</li>
 *   <li><b>It makes a failure look like a success.</b> An author whose token expired mid-session
 *       would keep getting answers, on the operator's key, with nothing anywhere reporting that they
 *       are no longer signed in.</li>
 * </ul>
 *
 * <p>So the source is a deployment-level decision an operator makes once, and a deployment in one
 * mode does not consult the other's credential at all — not as a fallback, and not to "check".
 * {@code AssistantOauthCredentialTest} asserts both directions, because "explicit" is a property of
 * the switch rather than of either position.</p>
 */
public enum AssistantCredentialSource {

    /**
     * The operator's deployment key from {@code RAVENROOT_ASSISTANT_API_KEY}. <b>The default</b> and
     * the deployment-wide credential model; OAuth is an option beside it, never an implicit migration
     * away from it.
     */
    API_KEY("api-key"),

    /**
     * Each author's own token, obtained through the provider's device-authorization flow.
     *
     * <p>In this mode the deployment has no credential of its own: {@code RAVENROOT_ASSISTANT_API_KEY}
     * is not read, and an author who has not signed in is {@code not-signed-in} rather than served on
     * someone else's account.</p>
     */
    OAUTH("oauth");

    private final String wireValue;

    AssistantCredentialSource(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The exact string an operator writes in the environment. */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Parses the operator's choice, defaulting to {@link #API_KEY}.
     *
     * <p><b>An unrecognised value is the default, not OAuth.</b> Same reasoning as
     * {@code ExecutionStoreConfiguration}'s enabled flag: a typo must not silently move a deployment
     * onto a different credential model. Here it matters more, because the typo would move it onto a
     * model where nobody is signed in, and the panel would go inert for every author at once.</p>
     */
    public static AssistantCredentialSource parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return API_KEY;
        }
        String normalised = raw.strip().toLowerCase(Locale.ROOT);
        for (AssistantCredentialSource candidate : values()) {
            if (candidate.wireValue.equals(normalised)) {
                return candidate;
            }
        }
        return API_KEY;
    }
}
