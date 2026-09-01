package ai.ravenroot.server.credential;

import java.time.Instant;
import java.util.Objects;

/**
 * One stored credential as anybody other than its resolver is allowed to see it.
 *
 * <h2>There is no component for the value, and that is the type doing the work</h2>
 * <p>This record is what the create response returns, what the list route renders, and what the CLI
 * prints. None of those may carry the secret — not in clear, not masked, not truncated — so the
 * simplest way to guarantee it is a type that cannot hold one. A masked value would be the shape most
 * likely to be added later "for convenience", so this comment states the requirement explicitly:
 * users see the label and never the value. The author who entered it does not need it echoed back.</p>
 *
 * <p>{@code username} is present because it is not a secret and because a list of three entries all
 * labelled "database" is unusable without it. It is the same reasoning that puts {@code label} here
 * and keeps the password out.</p>
 *
 * @param reference the server-minted identifier — see {@link CredentialReference}
 * @param label     the author's own words for it, shown in the selector and nowhere authoritative
 * @param scheme    which kind, and therefore what a consumer receives
 * @param username  the non-secret half of a {@link CredentialScheme#BASIC} entry; empty otherwise
 * @param createdAt when it was minted
 */
public record StoredCredential(String reference, String label, CredentialScheme scheme,
                               String username, Instant createdAt) {

    public StoredCredential {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(scheme, "scheme");
        username = username == null ? "" : username;
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
