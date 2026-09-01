package ai.ravenroot.adapter.anthropic;

/**
 * The only failure this adapter reports, in a vocabulary that cannot carry text from anywhere else.
 *
 * <h2>The vector this type closes</h2>
 * <p>Everything reaching the adapter through {@code ModelRequest} is caller text: {@code prompt} is
 * rendered from graph content and the traversal payload, {@code model} and every entry of
 * {@code parameters} come straight off node properties, and {@code credentialReference} is the field
 * an author types into a plain {@code <input type="text">} in the editor — which means it is the one
 * field that routinely contains a pasted secret rather than a reference to one. Everything coming
 * back from the provider is remote text: a status line, a header set and a response body, none of
 * which this adapter can audit. A conventional {@code Exception(String message, Throwable cause)}
 * would let any of it into a rendered failure by a single careless concatenation at a single call
 * site.
 *
 * <h2>Enforced by the shape of the type, not by a rule someone has to remember</h2>
 * <ul>
 *   <li><b>No constructor or factory takes a {@link String}, or any {@link CharSequence}, at all.</b>
 *   The sole constructor is private and takes a {@link Reason}, an {@code int} status and a
 *   {@link Class}. There is no parameter free text can be passed to, on any creation path.
 *   {@code AnthropicModelProviderCredentialSafetyTest#theFailureTypeAcceptsNoFreeTextOnAnyCreationPath}
 *   asserts exactly that by reflection, so adding one back fails the build.</li>
 *   <li><b>The provider id is gone from the message, and that is the cost.</b> It was operator-scoped
 *   — {@code ModelProviderRegistry} keys its map by it, so graph content can select an id but never
 *   define one — which is why it read as safe. It was still a {@code String} parameter, and a
 *   parameter that is safe because of who happens to call it is the shape this repository keeps
 *   rejecting. What is lost: a failure no longer names which adapter produced it. What replaces it:
 *   the exception's own type and the stack trace, both compile-time artefacts, and this class ships
 *   in exactly one adapter.</li>
 *   <li><b>{@code transportType} is a {@link Class}, not a name.</b> The parameter's type refuses
 *   arbitrary text; the only thing a caller can supply is a loaded class, and its
 *   {@code getName()} is a compile-time artefact.</li>
 *   <li><b>The cause chain is empty by construction.</b> {@code super(..., null, ...)} on the only
 *   constructor: {@link #getCause()} returns {@code null} on every instance, so no foreign
 *   exception's message, and no exception it in turn wraps, is ever rendered by printing this one.</li>
 *   <li><b>Suppression is disabled.</b> {@code enableSuppression=false}, so {@code addSuppressed} is
 *   a no-op and {@link #getSuppressed()} is always empty. The mail adapter's safety test has to walk
 *   suppressed exceptions and a vendor-specific {@code getNextException()} chain because its
 *   exception type permits them; this one has nothing to walk.</li>
 *   <li><b>{@code final} class.</b> None of the above can be widened by a subclass.</li>
 * </ul>
 *
 * <h2>What this costs, stated plainly</h2>
 * <p>Diagnosis is by {@link Reason}, HTTP status and the transport exception's <em>class name</em>.
 * The transport exception's own message, the response body and the original stack trace are
 * discarded and cannot be recovered from a Ravenroot failure.
 */
public final class ModelInvocationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The closed vocabulary. Every failure of this adapter is exactly one of these. */
    public enum Reason {
        /** The node declared no {@code credentialRef}; on the operator-key path there is nothing to resolve. */
        CREDENTIAL_REFERENCE_ABSENT("the node declared no credential reference"),
        /** The reference resolved to nothing on this deployment — unprovisioned, or not encodable. */
        CREDENTIAL_UNRESOLVED("the credential reference resolves to nothing on this deployment"),
        /**
         * The reference resolved, but the material was refused before a request existed — a blank
         * value, or one the request builder rejects. Separate from {@link #CREDENTIAL_UNRESOLVED}
         * because the operator's remedy differs: there the reference names nothing, here it names
         * something unusable.
         */
        CREDENTIAL_UNUSABLE("the resolved credential was refused before any request was made"),
        /** The provider refused the request as malformed or unknown (4xx other than auth and rate limit). */
        REQUEST_REJECTED("the provider rejected the request"),
        /** The operator credential was not accepted (401/403). */
        NOT_AUTHORIZED("the operator credential was not accepted"),
        /** The provider is rate limiting this deployment (429). */
        RATE_LIMITED("the provider is rate limiting this deployment"),
        /** The provider reported a failure of its own (5xx). */
        PROVIDER_UNAVAILABLE("the provider reported an internal failure"),
        /**
         * The provider answered with a redirect, and this deployment does not follow one.
         *
         * <p>Its own reason rather than a 4xx, because the remedy is an operator's and the diagnosis
         * is unobvious: nothing failed, the request was simply pointed at a second host that SEC-10's
         * policy never saw. Reported as a failure precisely because the alternative — following it —
         * is what sends an operator key and a rendered prompt to that host.
         */
        REDIRECT_REFUSED("the provider answered with a redirect, which this deployment does not follow"),
        /** The call did not complete: connection, timeout, or a socket that failed mid-response. */
        TRANSPORT_FAILURE("the call to the provider did not complete"),
        /**
         * Outbound policy refused the destination before any byte left. Distinct from
         * {@link #TRANSPORT_FAILURE} because the provider is not the problem and a retry reproduces
         * it exactly.
         */
        EGRESS_REFUSED("outbound policy refused the provider destination"),
        /**
         * The response arrived and this build could not read it: over the response budget, or not
         * the document the Messages API describes. Not a transport failure — the socket did not
         * fail, so advising a retry would be false.
         */
        RESPONSE_UNREADABLE("the provider response could not be read"),
        /** A successful HTTP response whose {@code stop_reason} was {@code refusal}. */
        COMPLETION_REFUSED("the model declined to answer this request"),
        /** A successful HTTP response carrying no text content block. */
        COMPLETION_EMPTY("the provider returned no text content");

        private final String sentence;

        Reason(String sentence) {
            this.sentence = sentence;
        }

        /** Fixed prose for this reason. A compile-time constant, never assembled from input. */
        public String sentence() {
            return sentence;
        }
    }

    private final Reason reason;
    private final int httpStatus;
    private final String transportType;

    private ModelInvocationException(Reason reason, int httpStatus,
                                     Class<? extends Throwable> transportType) {
        // cause = null, enableSuppression = false, writableStackTrace = true.
        // The stack trace is kept: it is built from class and method names, which are compile-time
        // artefacts and carry nothing from a request or a response.
        super(render(reason, httpStatus, transportType), null, false, true);
        this.reason = reason;
        this.httpStatus = httpStatus;
        this.transportType = transportType == null ? "" : transportType.getName();
    }

    static ModelInvocationException of(Reason reason) {
        return new ModelInvocationException(reason, 0, null);
    }

    static ModelInvocationException fromStatus(Reason reason, int httpStatus) {
        return new ModelInvocationException(reason, httpStatus, null);
    }

    static ModelInvocationException fromTransport(Reason reason, Class<? extends Throwable> transportType) {
        return new ModelInvocationException(reason, 0, transportType);
    }

    private static String render(Reason reason, int httpStatus, Class<? extends Throwable> transportType) {
        StringBuilder rendered = new StringBuilder("The model provider could not complete the request: ")
                .append(reason.sentence())
                .append(" [reason=").append(reason.name());
        if (httpStatus > 0) {
            rendered.append(", httpStatus=").append(httpStatus);
        }
        if (transportType != null) {
            rendered.append(", transport=").append(transportType.getName());
        }
        return rendered.append(']').toString();
    }

    public Reason reason() {
        return reason;
    }

    /** The provider HTTP status, or {@code 0} when the failure never reached one. */
    public int httpStatus() {
        return httpStatus;
    }

    /** Class name of the transport exception that was discarded, or {@code ""} if there was none. */
    public String transportType() {
        return transportType;
    }
}
