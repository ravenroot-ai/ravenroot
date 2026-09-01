package ai.ravenroot.server.assistant;

import java.util.Optional;

/**
 * Where a user's OAuth access token lives between the device flow and the provider call under the
 * per-author credential and credential-lifecycle contracts.
 *
 * <h2>The token boundary</h2>
 * <p>ADR 0018's boundary rule places the obtained token server-side rather than client-side. Two
 * contract rules determine that placement:</p>
 *
 * <ul>
 *   <li><b>the per-author credential contract</b> states it outright: "<em>The token never has a browser-readable
 *       representation in any mode</em>, and the calling component is always the server-side assistant
 *       service." It also records why the obvious client-side shapes fail, so they are not re-derived:
 *       an {@code HttpOnly} cookie "collapses on its own terms — the only cookie JavaScript cannot read
 *       is also one JavaScript cannot use to call a provider"; and {@code localStorage} is "strictly
 *       worse: readable by any script on an origin that renders user-authored graph content", which
 *       this product's origin does.</li>
 *   <li><b>the credential-lifecycle contract</b> supplies the shape rather than the side: core does not manage
 *       credential material, and "the holder owns TTL, refresh and revocation". That is why this is a
 *       <em>port</em> with the lifecycle behind it, rather than a field on the configuration: the
 *       obligation travels with the implementation that holds the token.</li>
 * </ul>
 *
 * <p>So: <b>server-side, and never persisted by this implementation.</b> The composition root chooses
 * which holder, and the per-author credential contract fixes both shapes structurally — a local single-user
 * composition may wire a keychain-backed store, because the OS session and the token's owner are one
 * principal; a multi-tenant server composition may wire <em>only</em> a session-scoped in-memory
 * store, because a shared server's keychain belongs to the service account and persisting there would
 * put a user's token in the operator's vault. {@link InMemoryAssistantTokenStore} is the second shape.
 * The first is not built here — see that class.</p>
 *
 * <h2>Keyed by subject, which is what makes it a user credential rather than a deployment one</h2>
 * <p>The operator API key is one value for the whole deployment. An OAuth token is one value per
 * signed-in author, so this port is keyed by {@code RequestContext#subject()} and consulted per turn.
 * That is also what lets the panel report {@code not-signed-in} — the user-actionable inert reason
 * represented by {@code AssistantAvailability} and {@code assistant-session.js}; it becomes
 * observable when the per-user credential source is empty.</p>
 */
public interface AssistantTokenStore {

    /**
     * This author's current provider token, if they have signed in and it has not been discarded.
     *
     * <p><b>Empty is not an error and must never fall back to the operator key.</b> The credential
     * source is an explicit choice; a store that answered "no token" by letting the deployment key be
     * used would silently replace the chosen source and would also
     * bill the wrong account. Empty means {@code not-signed-in}.</p>
     *
     * @param subject the authenticated author, as {@code RequestContext#subject()} reports them
     */
    Optional<AssistantCredential> tokenFor(String subject);
}
