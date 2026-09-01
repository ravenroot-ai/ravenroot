package ai.ravenroot.server.assistant;

import java.util.Set;

/**
 * Where a user's consent to send each class of context to a provider is recorded, and the only
 * question the composer is allowed to ask about it under the consent contract.
 *
 * <h2>A required collaborator for egressing adapters</h2>
 * <p>{@link AssistantService#fromEnvironment} <b>throws</b> rather than wiring a network-backed adapter
 * without a consent register, turning "we have not connected a model yet" from a configuration
 * discipline into a boot-time fact. {@link SqliteAssistantConsentStore} is the production
 * implementation, and a deployment that composes no store refuses to wire an egressing adapter.</p>
 *
 * <h2>Two questions, one of which is derived</h2>
 * <p>{@link #consentedClasses} is the abstract one because it is the one the composer needs: the set of
 * classes this author has agreed may be sent to this provider, which is what
 * {@code AssistantService#send} filters the tool surface by before every request it composes.
 * {@link #hasConsented} is a {@code default} derived from it rather than a second stored fact,
 * because two stored answers to "has this author
 * consented?" and "to what?" are two answers that can disagree, and the disagreement would be silent:
 * a provider-level yes with an empty class set is a consent record that authorizes sending nothing.</p>
 *
 * <h2>Read-only on purpose</h2>
 * <p>There is no {@code grant} or {@code revoke} here. Recording consent is a different actor's job —
 * the author's, through a surface that shows them what each class contains — and the composer must not
 * hold a type that could record consent on their behalf. {@link SqliteAssistantConsentStore} carries
 * the write side; what {@code AssistantService} is handed can only ask.</p>
 */
public interface AssistantConsentStore {

    /**
     * The classes of context this author has consented to send to this provider.
     *
     * <p><b>An unknown pair answers with the empty set, never with everything.</b> The default for an
     * author who has never chosen is that nothing of theirs is sent, and an implementation that treated
     * a missing row as an unrestricted grant would make the register decorative: recorded before the
     * first send, but never actually consulted for a denial.</p>
     *
     * @param subject  the authenticated author, as {@code RequestContext#subject()} reports them
     * @param provider the provider id the operator configured, as {@code AssistantProvider#id()}
     *                 reports it — consent given to one provider is not consent given to another
     * @return an immutable set; empty means no class of context may be composed for this pair
     */
    Set<AssistantContextClass> consentedClasses(String subject, String provider);

    /**
     * Whether this author has consented to sending <em>any</em> context to this provider.
     *
     * <p>Derived, never stored. See this interface's Javadoc for why.</p>
     */
    default boolean hasConsented(String subject, String provider) {
        return !consentedClasses(subject, provider).isEmpty();
    }
}
