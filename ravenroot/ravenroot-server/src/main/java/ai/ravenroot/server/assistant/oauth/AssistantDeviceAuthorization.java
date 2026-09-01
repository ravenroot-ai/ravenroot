package ai.ravenroot.server.assistant.oauth;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import ai.ravenroot.core.security.egress.BoundedBodyHandlers;
import ai.ravenroot.server.assistant.AssistantCredential;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The OAuth 2.0 device authorization grant (RFC 8628), for signing an author into their own provider
 * subscription under the per-author credential contract.
 *
 * <h2>Why the device flow and not an authorization-code redirect</h2>
 * <p>Because the redirect flow needs a registered redirect URI per deployment, and Ravenroot is
 * self-hosted at addresses the provider has never heard of. The device grant needs no redirect at
 * all: this server asks for a code, the author types it into the provider's own page in whatever
 * browser they like, and this server polls for the result. the per-author credential contract chose it for exactly
 * that reason, and the assistant token-persistence contract accepted its cost in the same breath — "even on demand
 * whenever the application opens".</p>
 *
 * <h2>Why the session-scoped lifecycle needs no refresh token</h2>
 * <p>Signing in each time the app opens means <b>no refresh token, no rotation and no revocation
 * bookkeeping</b>. That is not a shortcut, it is what keeps this inside the credential-lifecycle contract: core does
 * not manage credential material, and "the holder owns TTL, refresh and revocation". A session-scoped
 * holder that forgets everything when the session ends owns those three obligations trivially and
 * correctly. An implementation that stored refresh tokens to spare the author a sign-in would have
 * moved Ravenroot into managing credential lifecycle outside this component's responsibility.</p>
 *
 * <h2>What this class cannot do, by construction</h2>
 * <p><b>It does not build an {@link HttpClient}</b>, for the same reason the provider adapter does
 * not: one is passed in, and it must be the {@code EgressHttpClients} client that cannot follow a
 * redirect, cannot be proxied and cannot skip TLS validation. A credential exchange that could follow
 * a redirect is one that can be pointed at a different host mid-flight, which is the worst possible
 * place to lose that guarantee.</p>
 *
 * <p><b>It does not choose its endpoints.</b> Both are constructor parameters, and every request is
 * checked against the {@link OutboundHttpPolicy} allowlist it is given before anything is sent — so
 * the operator-egress contract's "the operator owns reach" covers the token exchange and not only the
 * model call. {@code AssistantComposition} supplies both endpoints from explicit operator
 * configuration; without both, the OAuth deployment cannot be assembled.</p>
 *
 * <h2>The endpoints have no defaults, and that is deliberate rather than unfinished</h2>
 * <p>{@code AssistantConfiguration} defaults the <em>messages</em> endpoint because that URL is
 * published, stable and already exercised by this build. The device-authorization and token endpoints
 * are not defaulted because no test in this repository has made a real provider request. Shipping a
 * guessed URL as a default would turn an unverified constant into something an operator reasonably
 * assumes was tested. The operator supplies both explicitly; this class's tests pin RFC 8628
 * conformance rather than provider acceptance.</p>
 */
public final class AssistantDeviceAuthorization {

    /** RFC 8628 section 3.4. The one grant type this class requests. */
    static final String DEVICE_CODE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code";

    /** RFC 8628 section 3.5: the default when the authorization server names no interval. */
    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(5);

    /**
     * Bounded like every other response this product parses, and for the same reason: an
     * authorization server that streamed megabytes at us is refused rather than resident. Smaller
     * than the model-response budget because a device-grant response is a handful of short fields.
     */
    private static final PayloadLimits RESPONSE_LIMITS =
            new PayloadLimits(64 * 1024, 8, 100, 4_096, 64 * 1024, 32);

    private final HttpClient httpClient;
    private final URI deviceAuthorizationEndpoint;
    private final URI tokenEndpoint;
    private final String clientId;
    private final OutboundHttpPolicy egressPolicy;
    private final Duration timeout;

    /**
     * @param httpClient <b>must</b> come from {@code EgressHttpClients}; this class must never grow a
     *                   no-client constructor
     * @param clientId   the provider's public client identifier. Not a secret — RFC 8628 device
     *                   clients are public clients and hold no client secret — so it is not wrapped in
     *                   {@link AssistantCredential}, and pretending it were a secret would dilute what
     *                   that type means.
     */
    public AssistantDeviceAuthorization(HttpClient httpClient, URI deviceAuthorizationEndpoint,
                                        URI tokenEndpoint, String clientId,
                                        OutboundHttpPolicy egressPolicy, Duration timeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.deviceAuthorizationEndpoint =
                Objects.requireNonNull(deviceAuthorizationEndpoint, "deviceAuthorizationEndpoint");
        this.tokenEndpoint = Objects.requireNonNull(tokenEndpoint, "tokenEndpoint");
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.egressPolicy = Objects.requireNonNull(egressPolicy, "egressPolicy");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /**
     * What the author must be shown, and what this server must keep to finish the exchange.
     *
     * @param userCode             the short code the author types on the provider's page
     * @param verificationUri      where they type it
     * @param verificationUriComplete the same page with the code embedded, when the server offers one
     * @param pollInterval         how often {@link #redeem} may ask, per RFC 8628 section 3.5
     * @param expiresIn            how long the grant lives
     */
    public record DeviceGrant(String deviceCode, String userCode, URI verificationUri,
                              URI verificationUriComplete, Duration pollInterval, Duration expiresIn) {

        /**
         * {@code deviceCode} is the half that must never be displayed or logged.
         *
         * <p>The user code is meant to be read aloud; the device code is the bearer of the pending
         * grant, and anyone holding it can redeem the author's token. Redacting it here rather than
         * relying on nobody printing the record is the same discipline
         * {@code AssistantCredential#toString} applies, for the same reason: the leak that happens is
         * the accidental one.</p>
         */
        @Override
        public String toString() {
            return "DeviceGrant[userCode=" + userCode + ", verificationUri=" + verificationUri
                    + ", deviceCode=redacted, expiresIn=" + expiresIn + "]";
        }
    }

    /** Every way this exchange ends other than with a token. */
    public enum Failure {
        /** The author has not finished yet. Not an error: RFC 8628 section 3.5 says keep polling. */
        AUTHORIZATION_PENDING,
        /** The server asked us to slow down. The caller must lengthen its interval, not retry faster. */
        SLOW_DOWN,
        /** The author refused. Terminal, and not retried — a denial is an answer. */
        ACCESS_DENIED,
        /** The grant timed out before the author finished. Terminal; start a new one. */
        EXPIRED_TOKEN,
        /** The exchange could not be completed: transport, an unusable response, or a refused host. */
        UNAVAILABLE
    }

    /** A redeemed grant: either the author's credential or the named reason there is none. */
    public sealed interface Redemption {
        record Token(AssistantCredential credential) implements Redemption { }

        record NotYet(Failure failure, Duration retryAfter) implements Redemption { }
    }

    /**
     * Step one: ask the provider for a device code and the page to send the author to.
     *
     * @throws DeviceAuthorizationException when the endpoint is refused by the operator's egress
     *                                      policy, is unreachable, or answers something this build
     *                                      cannot read
     */
    public DeviceGrant begin() {
        PayloadValue.MapValue body = post(deviceAuthorizationEndpoint,
                Map.of("client_id", clientId));
        String deviceCode = text(body, "device_code");
        String userCode = text(body, "user_code");
        String verification = text(body, "verification_uri");
        if (deviceCode == null || userCode == null || verification == null) {
            throw new DeviceAuthorizationException(Failure.UNAVAILABLE,
                    "the device authorization response was missing a required field");
        }
        String complete = text(body, "verification_uri_complete");
        return new DeviceGrant(deviceCode, userCode, URI.create(verification),
                complete == null ? null : URI.create(complete),
                seconds(body, "interval", DEFAULT_POLL_INTERVAL),
                seconds(body, "expires_in", Duration.ofMinutes(15)));
    }

    /**
     * Step two: ask once whether the author has finished.
     *
     * <p><b>Polls once, and does not sleep.</b> The loop belongs to the caller, for the same reason
     * the tool loop belongs to {@code AssistantService} rather than to the provider adapter: how long
     * to wait, when to give up and what to show meanwhile are decisions about a user's session, and a
     * method that blocked a request thread for fifteen minutes would be making them silently.</p>
     */
    public Redemption redeem(DeviceGrant grant) {
        Objects.requireNonNull(grant, "grant");
        PayloadValue.MapValue body;
        try {
            body = post(tokenEndpoint, Map.of(
                    "grant_type", DEVICE_CODE_GRANT_TYPE,
                    "device_code", grant.deviceCode(),
                    "client_id", clientId));
        } catch (DeviceAuthorizationException failed) {
            return new Redemption.NotYet(failed.failure(), grant.pollInterval());
        }
        String error = text(body, "error");
        if (error != null) {
            Failure failure = switch (error) {
                case "authorization_pending" -> Failure.AUTHORIZATION_PENDING;
                case "slow_down" -> Failure.SLOW_DOWN;
                case "access_denied" -> Failure.ACCESS_DENIED;
                case "expired_token" -> Failure.EXPIRED_TOKEN;
                default -> Failure.UNAVAILABLE;
            };
            // RFC 8628 section 3.5: slow_down means add five seconds to the interval and keep it.
            Duration next = failure == Failure.SLOW_DOWN
                    ? grant.pollInterval().plusSeconds(5)
                    : grant.pollInterval();
            return new Redemption.NotYet(failure, next);
        }
        AssistantCredential credential = AssistantCredential.oauthToken(text(body, "access_token"));
        if (credential == null) {
            // A 200 with neither an error nor a usable token. Deliberately not treated as pending:
            // polling forever against a server that will never answer differently is how a sign-in
            // dialog hangs with no explanation.
            return new Redemption.NotYet(Failure.UNAVAILABLE, grant.pollInterval());
        }
        return new Redemption.Token(credential);
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * One form-encoded POST, against an endpoint the operator allowlisted.
     *
     * <p>The allowlist is checked here rather than only at configuration time because this class can
     * be constructed directly, and a credential exchange is the last place to rely on a caller having
     * checked something.</p>
     */
    private PayloadValue.MapValue post(URI endpoint, Map<String, String> form) {
        try {
            egressPolicy.requireAllowed(endpoint);
        } catch (SecurityException refused) {
            throw new DeviceAuthorizationException(Failure.UNAVAILABLE,
                    "the OAuth endpoint is not permitted by this deployment's outbound policy");
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("content-type", "application/x-www-form-urlencoded")
                    .header("accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(formEncode(form), StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException rejected) {
            throw new DeviceAuthorizationException(Failure.UNAVAILABLE,
                    "the OAuth endpoint was refused");
        }
        // BoundedBodyHandlers.ofByteArray, never BodyHandlers.ofString: the string handler buffers the
        // whole response into memory before RESPONSE_LIMITS,
        // or even PayloadJson's own byte-length check, is ever consulted. By the time a String existed
        // to measure, the allocation the budget exists to prevent had already happened.
        //
        // The ceiling used here is the one ravenroot-core already publishes for this exact purpose,
        // and it is stronger than reading budget+1 bytes off an InputStream: it refuses a declared
        // Content-Length over the ceiling before a single body byte is read, and cancels the
        // subscription mid-stream when the declared length was understated or absent -- so an
        // over-budget response costs nothing here, rather than costing budget+1 bytes and then being
        // discarded. AnthropicAssistantProvider#readBounded and JwkSetProvider read their own
        // streams that way because they hold a stream for other reasons; this call site holds none, so
        // a third private copy of that idiom would have been one more shape to keep in step, guarding
        // less than the handler already does.
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request,
                    BoundedBodyHandlers.ofByteArray(RESPONSE_LIMITS.maxEncodedBytes()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DeviceAuthorizationException(Failure.UNAVAILABLE,
                    "the OAuth call was interrupted");
        } catch (IOException | SecurityException failed) {
            // The message is authored here and never interpolates the provider's -- the same
            // sanitization discipline the provider adapter applies, and more important on this path,
            // because an authorization server's error body can echo the request back.
            if (overBudget(failed)) {
                // Told apart from an unreachable endpoint on purpose: the socket did not fail, so
                // "could not be reached" would be false and would advise a retry that reproduces the
                // same oversized response. AnthropicAssistantProvider draws that same distinction with
                // PROVIDER_UNREADABLE; RFC 8628's Failure vocabulary here has no unreadable-response
                // member -- AUTHORIZATION_PENDING, SLOW_DOWN, ACCESS_DENIED and EXPIRED_TOKEN are all
                // answers the authorization server gave -- so UNAVAILABLE is the only truthful one, and
                // the distinction survives in the message rather than in the enum.
                throw new DeviceAuthorizationException(Failure.UNAVAILABLE,
                        "the OAuth endpoint response exceeded the "
                                + RESPONSE_LIMITS.maxEncodedBytes() + " byte response budget");
            }
            throw new DeviceAuthorizationException(Failure.UNAVAILABLE,
                    "the OAuth endpoint could not be reached");
        }
        // Not null-guarded: the array is built by BoundedBodyHandlers, which completes with an
        // allocated array or completes exceptionally, so there is no path -- production or test double
        // -- through which null arrives. The double no longer chooses the body object at all.
        byte[] body = response.body();
        if (body.length == 0) {
            throw new DeviceAuthorizationException(Failure.UNAVAILABLE,
                    "the OAuth endpoint returned an empty response");
        }
        try {
            PayloadValue parsed = PayloadJson.read(body, RESPONSE_LIMITS);
            if (parsed instanceof PayloadValue.MapValue object) {
                return object;
            }
            throw new DeviceAuthorizationException(Failure.UNAVAILABLE,
                    "the OAuth endpoint returned a response this build cannot read");
        } catch (RuntimeException unreadable) {
            if (unreadable instanceof DeviceAuthorizationException named) {
                throw named;
            }
            throw new DeviceAuthorizationException(Failure.UNAVAILABLE,
                    "the OAuth endpoint returned a response this build cannot read");
        }
    }

    /**
     * Whether a failed {@code send} failed because the response was over budget.
     *
     * <p>{@link BoundedBodyHandlers.ResponseTooLargeException} is an {@link IOException} so that it
     * surfaces through the ordinary failure path rather than needing one of its own — which is also
     * why it has to be told apart again here.</p>
     *
     * <p><b>Both shapes are checked, and the wrapped one is the shape production actually meets.</b>
     * Measured against the JDK client on 21.0.11 — HTTP/1.1 over loopback, synchronous and
     * asynchronous, with the length declared and with it chunked — the body subscriber's exception
     * arrived every time as the <em>cause</em> of a fresh {@code IOException}, never as itself. So
     * the second test below is not the defensive one: it is the live branch. The first is kept
     * because {@link java.net.http.HttpClient#send} does not promise that wrapping and the
     * measurement covers one minor and one protocol version — and it is exercised, by
     * {@code ScriptedHttpClient.Surfacing.BARE}, so that it cannot rot into something a later cleanup
     * deletes as dead. The asynchronous adapters unwrap a {@code CompletionException} at the same
     * point for the same reason.</p>
     */
    private static boolean overBudget(Exception failed) {
        return failed instanceof BoundedBodyHandlers.ResponseTooLargeException
                || failed.getCause() instanceof BoundedBodyHandlers.ResponseTooLargeException;
    }

    private static String formEncode(Map<String, String> form) {
        var ordered = new LinkedHashMap<>(form);
        var encoded = new StringBuilder();
        for (Map.Entry<String, String> entry : ordered.entrySet()) {
            if (!encoded.isEmpty()) {
                encoded.append('&');
            }
            encoded.append(java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(java.net.URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return encoded.toString();
    }

    private static String text(PayloadValue.MapValue object, String field) {
        PayloadValue value = object.entries().get(field);
        return value instanceof PayloadValue.TextValue text ? text.value() : null;
    }

    private static Duration seconds(PayloadValue.MapValue object, String field, Duration fallback) {
        PayloadValue value = object.entries().get(field);
        if (value instanceof PayloadValue.IntegerValue number && number.value() > 0) {
            return Duration.ofSeconds(number.value());
        }
        return fallback;
    }

    /** A device-flow fault this build can name. Never carries a provider-authored message. */
    public static final class DeviceAuthorizationException extends RuntimeException {
        private final Failure failure;

        DeviceAuthorizationException(Failure failure, String message) {
            super(message);
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }
}
