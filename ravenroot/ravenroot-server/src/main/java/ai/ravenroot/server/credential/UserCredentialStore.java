package ai.ravenroot.server.credential;

import ai.ravenroot.api.security.SecretValue;

import java.util.List;
import java.util.Optional;

/**
 * Where a credential an author entered from the interface lives.
 *
 * <h2>The three operations, and why they are three rather than one</h2>
 * <p>Each is reached by a different caller with a different amount of identity in hand, and folding
 * them together would mean the weakest caller's identity governed all three:</p>
 * <ul>
 *   <li>{@link #mint} and {@link #listFor} run on the HTTP request path, where the authenticated
 *       {@code RequestContext} supplies tenant and subject. Both are owner-scoped, and
 *       {@code listFor} provides the complete owner-scoped listing operation.</li>
 *   <li>{@link #isOwnedBy} runs at execution admission, also on the request path. It answers one
 *       boolean and deliberately nothing else — see below.</li>
 *   <li>{@link #resolve} runs during execution, from inside a {@code CredentialResolver}, where
 *       <b>there is no identity at all</b>. See the honest statement of that below.</li>
 * </ul>
 *
 * <h2>Where ownership is actually enforced, stated plainly</h2>
 * <p>{@code CredentialResolver.resolve(String)} takes a bare reference and nothing else. That is a
 * contract in {@code ravenroot-application-api} with implementations in core, in the mail extension
 * and in third-party adapters, and {@code CredentialResolverTenantScopeTest} exists specifically to
 * fail if its arity ever changes. SEC-07 records the remaining identity gap in that resolver
 * contract.</p>
 *
 * <p>So the ownership rule is enforced <b>at admission</b>, by {@link CredentialAdmission}, where the
 * authenticated submitter is known: a graph naming a minted reference the submitter does not own is
 * refused before an execution id exists. {@link #resolve} then resolves by reference alone.</p>
 *
 * <p><b>What that does and does not buy, without rounding up.</b> It buys: a second author cannot
 * list another's references (they are filtered), cannot be shown a value (nothing returns one), and
 * cannot run a graph that names one (admission refuses). It does not buy: a reference is still a
 * bearer string at the moment of resolution, so if the admission check were bypassed — by a future
 * execution entry point that does not call it — resolution would not catch it. That is why
 * {@link CredentialAdmission} is invoked on the byte array rather than on parsed nodes, and why its
 * test asserts the refusal rather than the parse. Closing it properly means giving the resolver an
 * identity, as required by SEC-07.</p>
 *
 * <h2>What this store does not do</h2>
 * <p>No rotation, no revocation, and no encryption at rest under an operator-managed key. The
 * consequence is concrete: <b>the secret is stored in clear in a local SQLite
 * file</b>, protected by the file system and by whoever can read the deployment's data volume. It is
 * written in the operator documentation in those words.</p>
 */
public interface UserCredentialStore {

    /**
     * Stores a credential and returns the reference the server chose for it.
     *
     * @param secret the value, taken as a {@code char[]} so the caller can zero it. This store copies
     *               what it needs and does not retain the array. It cannot promise the value is never
     *               materialised as a {@code String} — JDBC and SQLite both take one — which is
     *               exactly the honesty the secret-handling contract requires: Ravenroot does not intentionally
     *               persist, cache, log or emit secret material outside this store, and cannot
     *               guarantee erasure.
     */
    StoredCredential mint(String tenantId, String subject, String label, CredentialScheme scheme,
                          String username, char[] secret);

    /** This author's own credentials, newest first. Never anybody else's, and never a value. */
    List<StoredCredential> listFor(String tenantId, String subject);

    /**
     * Whether this exact author owns this exact reference.
     *
     * <p>One boolean, and deliberately no distinction between "does not exist" and "belongs to
     * somebody else". The two answers would let a caller enumerate which references exist, which is
     * the oracle a minted namespace exists to remove.</p>
     */
    boolean isOwnedBy(String reference, String tenantId, String subject);

    /**
     * The value behind a reference, for a consumer that has already passed admission.
     *
     * <p>Empty for an unknown reference, so a resolver chain can fall through to the operator's
     * environment bindings rather than failing the node.</p>
     */
    Optional<SecretValue> resolve(String reference);
}
