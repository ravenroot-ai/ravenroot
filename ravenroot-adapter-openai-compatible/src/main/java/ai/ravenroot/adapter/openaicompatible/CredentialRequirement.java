package ai.ravenroot.adapter.openaicompatible;

/**
 * Whether the configured endpoint takes an API key, declared by the operator at construction.
 *
 * <h2>Why this is an operator declaration and not an inference</h2>
 * <p>The sibling Anthropic adapter needs no such thing: {@code api.anthropic.com} always
 * authenticates, so an absent {@code credentialRef} is unambiguously a defect. "OpenAI-compatible" is
 * not one endpoint but a wire shape, and the two ends of its range differ on exactly this point — a
 * local Ollama on {@code 127.0.0.1:11434} accepts requests with no {@code Authorization} header at
 * all, while a hosted provider rejects them. The adapter cannot tell which it is talking to, and
 * guessing has a bad failure on each side:
 *
 * <ul>
 *   <li><b>Guessing "authenticated".</b> Every node against a local model fails on a credential the
 *   endpoint was never going to read.</li>
 *   <li><b>Guessing "unauthenticated when no reference is given".</b> An author who forgets
 *   {@code credentialRef} against a hosted endpoint gets an anonymous request. Against a hosted
 *   provider that is a 401 and merely annoying; against a <em>local</em> endpoint that ignores
 *   authentication it is a silent success, and the deployment runs unauthenticated for as long as
 *   nobody looks.</li>
 * </ul>
 *
 * <p>So it is declared once, by the operator, in the same code that supplies the base URL and the
 * model — which is the only place that actually knows.
 *
 * <h2>This is not an OAuth abstraction</h2>
 * <p>Both constants describe the operator-key path. This adapter has no token store, per-user
 * profile or expiry lifecycle; those capabilities require a separate authentication design rather
 * than a third constant here.
 */
public enum CredentialRequirement {

    /**
     * The endpoint authenticates. A node must declare a {@code credentialRef}; one that does not is
     * refused with {@code CREDENTIAL_REFERENCE_ABSENT}. The default for anything hosted.
     */
    REQUIRED,

    /**
     * The endpoint takes no credential — a local Ollama, LM Studio or vLLM on loopback.
     *
     * <p>Under this constant a node that <em>does</em> declare a {@code credentialRef} is refused with
     * {@code CREDENTIAL_NOT_ACCEPTED} rather than having it quietly dropped. Dropping it would be a
     * secret the author believed was in use and was not; honouring it would put a secret on a wire
     * the operator declared carries none. Refusing is the only branch that is true.
     */
    NONE
}
