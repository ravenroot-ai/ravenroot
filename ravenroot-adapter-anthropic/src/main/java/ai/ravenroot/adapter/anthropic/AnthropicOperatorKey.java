package ai.ravenroot.adapter.anthropic;

import ai.ravenroot.api.security.SecretValue;

import java.net.http.HttpRequest;
import java.util.Arrays;

/**
 * The operator's Anthropic API key, held in the one shape that cannot be printed.
 *
 * <h2>Why this type exists at all</h2>
 * <p>{@link SecretValue} is already erasable, but its {@link SecretValue#copy()} hands out a
 * {@code char[]} that any caller can turn into a {@code String}, and from there into a log line, an
 * exception message or a response field. This class exists to make that impossible on the one path
 * where the operator key actually travels: it takes the material once and never gives it back.
 *
 * <h2>What is enforced by the language, not by this comment</h2>
 * <ul>
 *   <li><b>No accessor returns the material.</b> There is no getter, no {@code copy()}, no
 *   {@code char[]} or {@code String} return type anywhere on this class. The single method that
 *   touches the material — {@link #authenticate} — consumes it and returns the request builder it
 *   was given. So there is no expression a caller can write that yields the key as a value.</li>
 *   <li><b>{@link #toString()} is a constant.</b> Any string concatenation, {@code String.valueOf},
 *   logger call or formatter that reaches an instance of this class renders
 *   {@code AnthropicOperatorKey[redacted]} and nothing else. This is the difference between a
 *   guarantee and a note: a {@code record} carrying the same field would print it, and that is
 *   precisely the shape a future refactor would reach for.</li>
 *   <li><b>{@code final} class, {@code private} constructor.</b> The redacting {@code toString} and
 *   the absent accessor cannot be subclassed away, and no other package can construct one holding
 *   material it also kept a reference to.</li>
 *   <li><b>No {@code equals}/{@code hashCode}.</b> Identity semantics, so the key is never a map key
 *   and never compared by content — neither of which would leak on its own, but both of which are
 *   how a value ends up somewhere it gets rendered.</li>
 * </ul>
 *
 * <h2>The one place the material becomes a String, and why it is unavoidable</h2>
 * <p>{@link HttpRequest.Builder#header(String, String)} accepts a {@code String}. Inside
 * {@link #authenticate} the material is therefore converted once, as an argument expression, and the
 * resulting {@code String} is reachable only from the immutable header map of the one
 * {@link HttpRequest} being built. Ravenroot's own copy is zeroed by {@link #close()}, which
 * {@code AnthropicModelProvider} calls as soon as the request is built — before it is sent, not
 * after it returns.
 *
 * <p>The resulting {@code String} lives for the lifetime of one request rather than being retained
 * by a client object. This is stated as a limit rather than claimed as a guarantee: it is a
 * property of a JDK signature, and no structure on this side can impose it.
 */
public final class AnthropicOperatorKey implements AutoCloseable {

    /** Rendered by {@link #toString()} in place of the material, on every instance, always. */
    static final String REDACTED = "AnthropicOperatorKey[redacted]";

    private final char[] material;

    private AnthropicOperatorKey(char[] material) {
        this.material = material;
    }

    /**
     * Takes a private copy of {@code secret}. The caller keeps ownership of its own
     * {@link SecretValue} and must close it; closing it does not affect this instance.
     */
    static AnthropicOperatorKey from(SecretValue secret) {
        return new AnthropicOperatorKey(secret.copy());
    }

    /**
     * Whether the material is empty or entirely whitespace.
     *
     * <p>Measured against the JDK client this adapter now uses: {@code header("x-api-key", "")} is
     * <b>not</b> refused — an empty field value is legal HTTP, the request builds, and the blank
     * value goes out on the wire, so the failure surfaces as a remote authentication error or, on an
     * unreachable host, as a transport error. An operator whose {@code RAVENROOT_CREDENTIAL_*}
     * variable exists and is empty would therefore learn nothing useful. This adapter refuses it
     * first, so the diagnosis names the credential rather than the network. Pinned by
     * {@code AnthropicModelProviderCredentialSafetyTest#theRequestBuilderAcceptsABlankApiKeyHeader},
     * so the premise this guard rests on is checked rather than remembered.
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
     * Applies this key to a request under construction, and nowhere else.
     *
     * <p>{@code x-api-key} is the operator-key scheme's header. This adapter has no other scheme:
     * OAuth is outside this operator-key scheme, so there is deliberately no branch here to extend
     * and no second header to forget.
     *
     * @throws IllegalArgumentException if the material is not a legal HTTP field value — the JDK's
     *     own check, which is why a credential carrying a control character never reaches a socket.
     *     {@code AnthropicModelProvider} maps it to {@code CREDENTIAL_UNUSABLE} without rendering it.
     */
    HttpRequest.Builder authenticate(HttpRequest.Builder builder) {
        return builder.header("x-api-key", new String(material));
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
