package ai.ravenroot.server.assistant;

import java.util.Objects;

/**
 * The provider credential, wrapped so that the ordinary ways a secret escapes do not work under the
 * per-author credential contract and ADR 0018.
 *
 * <h2>What this type is for</h2>
 * <p>A {@code String} credential leaks by being convenient: it lands in a log line the moment someone
 * interpolates the object that holds it, in a stack trace's {@code toString()}, in a debugger's
 * inspector, and in any JSON writer that reflects over fields. This wrapper closes the first two by
 * construction — {@link #toString()} is the redaction, and there is no getter that a formatter would
 * call by accident. The raw value is reachable only through {@link #expose()}, which is named to be
 * conspicuous in a diff and is called only by the dedicated provider adapters, at the moment one
 * writes the outbound request header.</p>
 *
 * <h2>What it deliberately does not do</h2>
 * <p>It is not a security boundary against code that wants the value — any caller can invoke
 * {@link #expose()}, and no JVM-level protection would change that. It removes the <em>accidental</em>
 * paths, which is the class of leak that actually happens. The claim that the token never reaches the
 * client is not made by this class; it is made by
 * {@code AssistantRouteTest#theProviderCredentialNeverReachesTheClientOrTheLog}, which plants a
 * distinctive value and greps
 * every response body, every response header and the captured server log for it. This type is what
 * makes that test pass for a structural reason rather than by luck.</p>
 *
 * <p>{@link #equals} and {@link #hashCode} are deliberately <em>not</em> overridden to compare the
 * secret: a constant-time comparison is not what this type is for, and an {@code equals} that reads
 * the value is another accidental path to it.</p>
 */
public final class AssistantCredential {

    /**
     * How this credential presents itself on the wire.
     *
     * <h2>Why the credential carries this and the adapter does not decide it</h2>
     * <p>Because an OAuth access token is <b>not a drop-in replacement value</b> for an API key on
     * the Anthropic Messages API, and treating it as one fails silently. An API
     * key goes in {@code x-api-key}; an OAuth bearer token goes in {@code Authorization: Bearer} and
     * additionally requires the {@code anthropic-beta: oauth-2025-04-20} header, which
     * {@code /v1/messages} does not accept the token without. Putting a bearer token into
     * {@code x-api-key} produces a provider 401 that this build reports as
     * {@code PROVIDER_REJECTED}, "check the configured profile" — an operator sent hunting for a
     * revoked key that was never issued.</p>
     *
     * <p>Keeping the scheme on the credential rather than in the adapter preserves the external
     * interface: the adapter's constructor and its {@code AssistantProvider} methods are
     * unchanged, because the thing that varies travels inside the argument it already takes. An
     * adapter that had to be told which scheme to use would need a new parameter.</p>
     */
    public enum Scheme {
        /** The operator's deployment key. {@code x-api-key}. The default deployment credential model. */
        API_KEY,
        /** A signed-in author's OAuth access token. {@code Authorization: Bearer} plus the beta header. */
        OAUTH_BEARER
    }

    private final String value;
    private final Scheme scheme;

    private AssistantCredential(String value, Scheme scheme) {
        this.value = Objects.requireNonNull(value, "value");
        this.scheme = Objects.requireNonNull(scheme, "scheme");
    }

    /** How this credential must be presented. Never inferred from the value's shape. */
    public Scheme scheme() {
        return scheme;
    }

    /**
     * Wraps a non-blank, header-safe credential, or returns {@code null} for an absent one.
     *
     * <p>Absence is represented as a {@code null} {@code AssistantCredential} rather than an
     * "empty credential" instance, so that "is a credential configured?" is a null check a reader
     * cannot get wrong, and so that no code path can send a blank {@code x-api-key} header and receive
     * a provider authentication error that looks like a revoked key.</p>
     *
     * <h4>Why emptiness is re-checked after trimming</h4>
     * <p>{@code isBlank()} is defined by {@code Character.isWhitespace}, which is
     * {@code false} for NUL, SOH and STX but {@code true} for 0x1C–0x1F. So {@code "\0"} passed the
     * blank check, {@code trim()} — which strips everything at or below {@code U+0020} — reduced it to
     * {@code ""}, and {@link #isHeaderSafe(String)} returned {@code true} <b>vacuously, because its
     * loop never ran</b>. A credential of one NUL byte was constructible as an empty one, and once
     * connected it would have sent an empty header and produced a provider 401 reported as
     * {@code PROVIDER_REJECTED}, "check the configured profile" — the operator sent hunting for a
     * revoked key that was never revoked.</p>
     *
     * <p>This is the vacuous-truth pattern: a loop proves nothing when it has nothing to iterate over.
     * The re-check prevents the empty post-trim value from reaching that loop.</p>
     *
     * <h4>Why a credential containing a control character is refused here</h4>
     * <p><b>Verified against the JDK rather than assumed</b> (OpenJDK 21):
     * {@code HttpRequest.Builder.header} rejects such a value with an
     * {@code IllegalArgumentException} whose message is {@code invalid header value: "<the whole
     * value>"} — the credential, verbatim, inside an exception. The adapter catches that exception and
     * keeps it as the cause of a named failure, so anything that later prints the stack trace prints
     * the key.</p>
     *
     * <p>Refusing at construction beats suppressing the cause at the throw site, and the reason is
     * worth stating: a credential containing CR, LF or NUL <b>is not a usable credential at all</b> —
     * it is also a malformed header and, with CRLF, a request-splitting attempt. Rejecting it here
     * kills the leak and the malformed request in one move, and it does so before the value has been
     * copied into a request builder rather than after. A misconfigured key becomes {@code no-profile},
     * which is a state the panel already renders and an operator can act on.</p>
     */
    public static AssistantCredential ofNullable(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        // Emptiness re-checked HERE, after trimming, not only via isBlank() before it: see the Javadoc.
        // isBlank() does not consider NUL blank, trim() removes it anyway, and isHeaderSafe("") is
        // vacuously true -- so without this line a credential of one NUL byte becomes an empty one.
        if (trimmed.isEmpty()) {
            return null;
        }
        return isHeaderSafe(trimmed) ? new AssistantCredential(trimmed, Scheme.API_KEY) : null;
    }

    /**
     * Wraps an OAuth access token obtained through the device-authorization flow.
     *
     * <p>Same validation as {@link #ofNullable(String)} and for the same reasons — an access token
     * containing a control character is not a usable credential, it is a malformed header and, with
     * CRLF, a request-splitting attempt. The <em>only</em> difference is {@link Scheme}, which is
     * passed rather than guessed: nothing here inspects the value to decide what kind of credential it
     * is. A token that happens to start with {@code sk-ant-} is still a bearer token if that is how it
     * was obtained, and an API key that happens to look like a JWT is still an API key.</p>
     *
     * @return {@code null} for an absent, blank or header-unsafe token, exactly as
     *         {@link #ofNullable(String)} does — so "is a credential present?" stays the same null
     *         check regardless of which source produced it
     */
    public static AssistantCredential oauthToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return isHeaderSafe(trimmed) ? new AssistantCredential(trimmed, Scheme.OAUTH_BEARER) : null;
    }

    /**
     * Whether every character is legal in an HTTP field value.
     *
     * <p>The check is on the character range rather than on a blocklist of {@code \r}, {@code \n} and
     * {@code \0}: a blocklist of the three characters someone thought of is how the fourth gets
     * through, and no control character belongs in an API key regardless of which one it is.</p>
     */
    private static boolean isHeaderSafe(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character == 0x7F) {
                return false;
            }
        }
        return true;
    }

    /**
     * The raw credential. <b>The only permitted caller is the provider adapter, writing the outbound
     * authentication header.</b> Never log this, never place it in a response, never put it in an
     * exception message — see this class's Javadoc for the test that enforces the last three.
     */
    public String expose() {
        return value;
    }

    /** Always the redaction, never the value. This is the control; changing it reds a test. */
    @Override
    public String toString() {
        return "AssistantCredential[" + scheme + ", redacted]";
    }
}
