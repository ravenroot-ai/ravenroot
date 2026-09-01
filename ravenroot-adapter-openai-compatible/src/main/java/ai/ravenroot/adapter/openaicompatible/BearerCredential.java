package ai.ravenroot.adapter.openaicompatible;

import ai.ravenroot.api.security.SecretValue;

import java.net.http.HttpRequest;
import java.util.Arrays;

/**
 * The operator's API key, held in the one shape that cannot be printed.
 *
 * <p>The same type as {@code ravenroot-adapter-anthropic}'s {@code AnthropicOperatorKey}, with one
 * difference on the wire and none in the guarantees. It is duplicated rather than shared because the
 * two adapters have no common artifact below {@code ravenroot-application-api}, and putting a
 * credential holder into the published SPI is a decision about the public API rather than a
 * convenience this module may take on its own.
 *
 * <h2>What is enforced by the language, not by this comment</h2>
 * <ul>
 *   <li><b>No accessor returns the material.</b> No getter, no {@code copy()}, no {@code char[]} or
 *   {@code String} return type anywhere on this class. The single method that touches the material —
 *   {@link #authenticate} — consumes it and returns the request builder it was given, so there is no
 *   expression a caller can write that yields the key as a value.</li>
 *   <li><b>{@link #toString()} is a constant.</b> Any concatenation, {@code String.valueOf}, logger
 *   call or formatter that reaches an instance renders {@link #REDACTED} and nothing else. A
 *   {@code record} carrying the same field would print it, which is precisely the shape a future
 *   refactor reaches for.</li>
 *   <li><b>{@code final} class, {@code private} constructor.</b> The redacting {@code toString} and
 *   the absent accessor cannot be subclassed away.</li>
 *   <li><b>No {@code equals}/{@code hashCode}.</b> Identity semantics, so the key is never a map key
 *   and never compared by content.</li>
 * </ul>
 *
 * <h2>The one place the material becomes a String, and why it is unavoidable</h2>
 * <p>{@link HttpRequest.Builder#header(String, String)} accepts a {@code String}. Inside
 * {@link #authenticate} the material is therefore converted once, as an argument expression, and the
 * resulting {@code String} is reachable only from the immutable header map of the one
 * {@link HttpRequest} being built. Ravenroot's own copy is zeroed by {@link #close()}, which the
 * provider calls as soon as the request is built — before it is sent, not after it returns. Stated as
 * a limit rather than claimed as a guarantee: it is a property of a JDK signature, and no structure
 * on this side can impose it.
 *
 * <h2>{@code Authorization: Bearer}, and why the header is not configurable</h2>
 * <p>Every endpoint in the OpenAI-compatible family this adapter targets — a local Ollama, LM Studio,
 * vLLM, and the hosted providers that advertise the shape — authenticates with
 * {@code Authorization: Bearer <key>}. Azure OpenAI does not (it uses {@code api-key}) and is
 * therefore <b>not</b> a target of this adapter; that is recorded as a limitation rather than closed
 * by a header parameter, because a configurable credential header is a knob whose only correct value
 * is the one it already has, and whose wrong values are all ways to send a key somewhere unintended.
 */
public final class BearerCredential implements AutoCloseable {

    /** Rendered by {@link #toString()} in place of the material, on every instance, always. */
    static final String REDACTED = "BearerCredential[redacted]";

    private final char[] material;

    private BearerCredential(char[] material) {
        this.material = material;
    }

    /**
     * Takes a private copy of {@code secret}. The caller keeps ownership of its own
     * {@link SecretValue} and must close it; closing it does not affect this instance.
     */
    static BearerCredential from(SecretValue secret) {
        return new BearerCredential(secret.copy());
    }

    /**
     * Whether the material is empty or entirely whitespace.
     *
     * <p>{@code header("authorization", "Bearer ")} is not refused by the JDK — an empty-ish field
     * value is legal HTTP — so the request would build and the blank credential would go out, and the
     * failure would surface as a remote 401 or, against a local endpoint that ignores authentication
     * entirely, as a <em>success</em> that silently ran unauthenticated. Refusing here means the
     * diagnosis names the credential rather than the network or nothing at all.
     *
     * <p>Returns a {@code boolean}, and computes it by walking the characters rather than building a
     * {@code String}: a predicate cannot carry the material out, and a temporary {@code String} would
     * reintroduce exactly the reachable copy this class exists to prevent.
     */
    boolean isBlank() {
        for (char character : material) {
            if (!Character.isWhitespace(character)) return false;
        }
        return true;
    }

    /**
     * Applies this credential to a request under construction, and nowhere else.
     *
     * @throws IllegalArgumentException if the material is not a legal HTTP field value — the JDK's
     *     own check, which is why a credential carrying a control character never reaches a socket.
     *     The provider maps it to {@code CREDENTIAL_UNUSABLE} without rendering it: the JDK's own
     *     message quotes the value it rejected.
     */
    HttpRequest.Builder authenticate(HttpRequest.Builder builder) {
        return builder.header("authorization", "Bearer " + new String(material));
    }

    @Override
    public String toString() {
        return REDACTED;
    }

    /** Best-effort erasure, on the same terms as {@link SecretValue#close()}. */
    @Override
    public void close() {
        Arrays.fill(material, '\0');
    }
}
