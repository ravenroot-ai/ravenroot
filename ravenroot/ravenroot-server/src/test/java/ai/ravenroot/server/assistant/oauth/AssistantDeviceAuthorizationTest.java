package ai.ravenroot.server.assistant.oauth;

import ai.ravenroot.core.security.OutboundHttpPolicy;
import ai.ravenroot.server.assistant.AssistantCredential;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFC 8628 conformance for the device grant.
 *
 * <h2>What these tests pin, and what they explicitly do not</h2>
 * <p>They pin that this build <em>emits and reads</em> the device authorization grant as RFC 8628
 * specifies: the grant type, the polling vocabulary, and the two terminal errors that must not be
 * retried. <b>They do not establish that any provider accepts it.</b> Every HTTP client here is a
 * fake, as in every other test in this repository that touches a provider. No real request is made;
 * the first real run remains an
 * experiment, and these tests are what make it a cheap one rather than a blind one.</p>
 */
class AssistantDeviceAuthorizationTest {

    private static final URI DEVICE_ENDPOINT = URI.create("https://api.anthropic.com/v1/oauth/device");
    private static final URI TOKEN_ENDPOINT = URI.create("https://api.anthropic.com/v1/oauth/token");
    private static final OutboundHttpPolicy ALLOWED =
            OutboundHttpPolicy.fromCommaSeparatedHosts("api.anthropic.com");

    /**
     * <b>The first call asks for a device code and returns what the author must be shown.</b>
     *
     * <p>The polling interval is read from the response rather than assumed, because RFC 8628 lets
     * the server set it and a client that ignored it would be the client the server then answers with
     * {@code slow_down}.</p>
     */
    @Test
    void beginAsksForADeviceCodeAndReturnsWhatTheAuthorMustBeShown() {
        var requests = new java.util.ArrayList<HttpRequest>();
        var bodies = new java.util.ArrayList<String>();
        var flow = flow(requests, bodies, responses(
                "{\"device_code\":\"dev-code-SECRET\",\"user_code\":\"WDJB-MJHT\","
                        + "\"verification_uri\":\"https://example.test/activate\",\"interval\":7,"
                        + "\"expires_in\":900}"));

        var grant = flow.begin();

        assertEquals("WDJB-MJHT", grant.userCode());
        assertEquals(URI.create("https://example.test/activate"), grant.verificationUri());
        assertEquals(Duration.ofSeconds(7), grant.pollInterval(),
                "the server's interval must be honoured, not replaced with our default");
        assertEquals(Duration.ofMinutes(15), grant.expiresIn());
        assertEquals(DEVICE_ENDPOINT, requests.get(0).uri());
        assertTrue(bodies.get(0).contains("client_id=ravenroot-test-client"),
                () -> "the device request must identify the client: " + bodies.get(0));
    }

    /**
     * <b>The device code is never in the grant's printed form.</b>
     *
     * <p>The user code is meant to be shown and read aloud; the device code is the bearer of the
     * pending grant, so anyone who holds it can redeem the author's token. A record whose generated
     * {@code toString} printed both would put it in the first log line that interpolated the grant.</p>
     *
     * <p><b>Mutation proof.</b> Delete {@code DeviceGrant#toString} and this test reds on the
     * generated one, which prints every component.</p>
     */
    @Test
    void theDeviceCodeIsNotPrintedButTheUserCodeIs() {
        var flow = flow(new java.util.ArrayList<>(), new java.util.ArrayList<>(), responses(
                "{\"device_code\":\"dev-code-SECRET\",\"user_code\":\"WDJB-MJHT\","
                        + "\"verification_uri\":\"https://example.test/activate\"}"));

        String printed = flow.begin().toString();

        assertFalse(printed.contains("dev-code-SECRET"),
                () -> "the device code reached a printed representation: " + printed);
        assertTrue(printed.contains("WDJB-MJHT"), "the user code is meant to be shown");
    }

    /**
     * <b>The token request uses the device-code grant type, and a redeemed token is a bearer
     * credential.</b>
     *
     * <p>The scheme assertion is the join to {@code AnthropicWireFormatTest}: a device flow that
     * produced an {@code API_KEY}-scheme credential would compile, store and be used — and would be
     * sent in {@code x-api-key}, which is the silent 401 this whole design exists to avoid.</p>
     */
    @Test
    void aRedeemedGrantYieldsABearerCredentialFromTheDeviceCodeGrantType() {
        var requests = new java.util.ArrayList<HttpRequest>();
        var bodies = new java.util.ArrayList<String>();
        var flow = flow(requests, bodies, responses(
                "{\"device_code\":\"dev-code\",\"user_code\":\"WDJB-MJHT\","
                        + "\"verification_uri\":\"https://example.test/activate\"}",
                "{\"access_token\":\"the-authors-token\",\"token_type\":\"bearer\"}"));

        var redemption = flow.redeem(flow.begin());

        var token = assertInstanceOf(AssistantDeviceAuthorization.Redemption.Token.class, redemption);
        assertEquals(AssistantCredential.Scheme.OAUTH_BEARER, token.credential().scheme(),
                "a device-flow token must be a bearer credential, or it will be sent as an API key");
        assertEquals(TOKEN_ENDPOINT, requests.get(1).uri());
        assertTrue(bodies.get(1).contains(java.net.URLEncoder.encode(
                        AssistantDeviceAuthorization.DEVICE_CODE_GRANT_TYPE,
                        java.nio.charset.StandardCharsets.UTF_8)),
                () -> "RFC 8628 requires the device_code grant type: " + bodies.get(1));
    }

    /**
     * <b>{@code authorization_pending} is not an error, and {@code slow_down} lengthens the
     * interval.</b>
     *
     * <p>RFC 8628 section 3.5. Treating {@code authorization_pending} as a failure aborts a sign-in
     * the author is in the middle of completing; ignoring {@code slow_down} and continuing at the old
     * rate is what gets a client rate-limited by the authorization server.</p>
     */
    @Test
    void pendingKeepsPollingAndSlowDownLengthensTheInterval() {
        var flow = flow(new java.util.ArrayList<>(), new java.util.ArrayList<>(), responses(
                "{\"device_code\":\"dev-code\",\"user_code\":\"WDJB-MJHT\","
                        + "\"verification_uri\":\"https://example.test/activate\",\"interval\":5}",
                "{\"error\":\"authorization_pending\"}",
                "{\"error\":\"slow_down\"}"));
        var grant = flow.begin();

        var pending = assertInstanceOf(AssistantDeviceAuthorization.Redemption.NotYet.class,
                flow.redeem(grant));
        var slowDown = assertInstanceOf(AssistantDeviceAuthorization.Redemption.NotYet.class,
                flow.redeem(grant));

        assertEquals(AssistantDeviceAuthorization.Failure.AUTHORIZATION_PENDING, pending.failure());
        assertEquals(Duration.ofSeconds(5), pending.retryAfter(),
                "a pending poll keeps the interval it was given");
        assertEquals(AssistantDeviceAuthorization.Failure.SLOW_DOWN, slowDown.failure());
        assertEquals(Duration.ofSeconds(10), slowDown.retryAfter(),
                "RFC 8628 section 3.5: slow_down adds five seconds to the interval");
    }

    /**
     * <b>A refusal and an expiry are terminal, and are told apart.</b>
     *
     * <p>Pooling them into "not yet" would poll forever against an author who said no. They are
     * distinguished from each other because only one of them is worth offering to restart.</p>
     */
    @Test
    void denialAndExpiryAreDistinctTerminalOutcomes() {
        var flow = flow(new java.util.ArrayList<>(), new java.util.ArrayList<>(), responses(
                "{\"device_code\":\"dev-code\",\"user_code\":\"WDJB-MJHT\","
                        + "\"verification_uri\":\"https://example.test/activate\"}",
                "{\"error\":\"access_denied\"}",
                "{\"error\":\"expired_token\"}"));
        var grant = flow.begin();

        assertEquals(AssistantDeviceAuthorization.Failure.ACCESS_DENIED,
                ((AssistantDeviceAuthorization.Redemption.NotYet) flow.redeem(grant)).failure());
        assertEquals(AssistantDeviceAuthorization.Failure.EXPIRED_TOKEN,
                ((AssistantDeviceAuthorization.Redemption.NotYet) flow.redeem(grant)).failure());
    }

    /**
     * <b>An endpoint outside the operator's allowlist is refused before anything is sent.</b>
     *
     * <p>the operator-egress contract — "the operator owns reach" — applied to the credential exchange and not
     * only to the model call. The assertion that the client was never called is the load-bearing
     * half: a check that refused the response after the request went out would have already leaked
     * the client id to an unallowlisted host.</p>
     *
     * <p><b>Mutation proof.</b> Delete the {@code egressPolicy.requireAllowed} call and this test
     * reds on the call count, not on the exception.</p>
     */
    @Test
    void anUnallowlistedEndpointIsRefusedWithoutSendingAnything() {
        var requests = new java.util.ArrayList<HttpRequest>();
        var flow = new AssistantDeviceAuthorization(
                new ScriptedHttpClient(requests, new java.util.ArrayList<>(), responses("{}")),
                URI.create("https://somewhere-else.test/v1/oauth/device"), TOKEN_ENDPOINT,
                "ravenroot-test-client", ALLOWED, Duration.ofSeconds(5));

        var refused = assertThrows(AssistantDeviceAuthorization.DeviceAuthorizationException.class,
                flow::begin);

        assertEquals(AssistantDeviceAuthorization.Failure.UNAVAILABLE, refused.failure());
        assertEquals(0, requests.size(),
                "the request must not leave: refusing after sending has already leaked the client id");
    }

    /**
     * <b>A 200 carrying neither a token nor an error is not treated as "keep polling".</b>
     *
     * <p>It is the shape that hangs a sign-in dialog forever with nothing to show the author, because
     * every poll looks like progress and none is.</p>
     */
    @Test
    void aResponseWithNeitherTokenNorErrorIsUnavailableRatherThanPending() {
        var flow = flow(new java.util.ArrayList<>(), new java.util.ArrayList<>(), responses(
                "{\"device_code\":\"dev-code\",\"user_code\":\"WDJB-MJHT\","
                        + "\"verification_uri\":\"https://example.test/activate\"}",
                "{\"token_type\":\"bearer\"}"));

        var redemption = (AssistantDeviceAuthorization.Redemption.NotYet)
                flow.redeem(flow.begin());

        assertEquals(AssistantDeviceAuthorization.Failure.UNAVAILABLE, redemption.failure());
    }

    /**
     * <b>A response one byte over the budget is refused, and refused as a budget failure.</b>
     *
     * <p>This exercises the response-size control. The fixture is deliberately <em>well-formed</em>
     * JSON that this class
     * would otherwise accept, and differs from
     * {@link #aResponseOfExactlyTheBudgetIsAccepted()}'s by exactly one byte: what is being pinned is
     * that the size decided the outcome, not the shape.</p>
     *
     * <p>The asserted number is the budget itself, read back out of the authored message. That is what
     * ties this test to {@code RESPONSE_LIMITS}, which is private: change the budget and this reds,
     * rather than passing against whatever the new number happens to be.</p>
     *
     * <p><b>Mutation proof, measured rather than reasoned.</b> Replace
     * {@code BoundedBodyHandlers.ofByteArray} with {@code BodyHandlers.ofByteArray} and this reds
     * <b>on the message</b>, not on the exception: the 65537 bytes arrive whole, and
     * {@code PayloadJson} — which applies the same ceiling to the array it is handed — refuses them
     * as {@code TOO_LARGE}, so {@code begin()} still throws {@code UNAVAILABLE} and only the authored
     * message changes, to "returned a response this build cannot read". That second ceiling is why
     * the assertion on the <em>message</em> is the load-bearing one here: without it this test would
     * pass against an entirely unbounded read; the required behavior is precisely the refusal that
     * happens <em>before</em> the bytes are allocated.</p>
     */
    @Test
    void aResponseOverTheBudgetIsRefusedAsABudgetFailureRatherThanRead() {
        var flow = flow(new java.util.ArrayList<>(), new java.util.ArrayList<>(),
                responses(deviceResponseOfExactly(RESPONSE_BUDGET_BYTES + 1)));

        var refused = assertThrows(AssistantDeviceAuthorization.DeviceAuthorizationException.class,
                flow::begin);

        assertEquals(AssistantDeviceAuthorization.Failure.UNAVAILABLE, refused.failure());
        assertTrue(refused.getMessage().contains(RESPONSE_BUDGET_BYTES + " byte response budget"),
                () -> "an over-budget response must say so, not report an unreachable endpoint: "
                        + refused.getMessage());
    }

    /**
     * <b>The refusal is recognised in both shapes a client can deliver it in.</b>
     *
     * <p>{@code BoundedBodyHandlers.ResponseTooLargeException} is an {@code IOException}, so it
     * reaches {@code post} through the same catch as a socket failure and has to be told apart there.
     * Measured against the JDK's own client — 21.0.11, HTTP/1.1 over loopback, synchronous and
     * asynchronous, length declared and chunked — it always arrives <b>wrapped</b>, as the cause of a
     * fresh {@code IOException}; that is what {@link ScriptedHttpClient.Surfacing#WRAPPED} reproduces
     * and what the test above therefore exercises.</p>
     *
     * <p>This one pins the other half. The bare shape did not appear in that measurement, but the
     * measurement covers one JDK minor and one protocol version and {@code send} does not promise the
     * wrapping, so production keeps handling both — and an unexercised branch is one a later cleanup
     * deletes as dead. Both together are what stop {@code overBudget} from being trimmed to whichever
     * half a single test happened to reach.</p>
     */
    @Test
    void anUnwrappedBudgetRefusalIsRecognisedToo() {
        var flow = new AssistantDeviceAuthorization(
                new ScriptedHttpClient(new java.util.ArrayList<>(), new java.util.ArrayList<>(),
                        responses(deviceResponseOfExactly(RESPONSE_BUDGET_BYTES + 1)),
                        ScriptedHttpClient.Surfacing.BARE),
                DEVICE_ENDPOINT, TOKEN_ENDPOINT, "ravenroot-test-client", ALLOWED,
                Duration.ofSeconds(5));

        var refused = assertThrows(AssistantDeviceAuthorization.DeviceAuthorizationException.class,
                flow::begin);

        assertEquals(AssistantDeviceAuthorization.Failure.UNAVAILABLE, refused.failure());
        assertTrue(refused.getMessage().contains(RESPONSE_BUDGET_BYTES + " byte response budget"),
                () -> "the refusal must be recognised unwrapped as well: " + refused.getMessage());
    }

    /**
     * <b>A response of exactly the budget is accepted.</b>
     *
     * <p>The other half of the boundary, and the half that stops the refusal above from being provable
     * by a ceiling that is simply too tight to pass. One byte separates the two fixtures.</p>
     */
    @Test
    void aResponseOfExactlyTheBudgetIsAccepted() {
        var flow = flow(new java.util.ArrayList<>(), new java.util.ArrayList<>(),
                responses(deviceResponseOfExactly(RESPONSE_BUDGET_BYTES)));

        var grant = flow.begin();

        assertEquals("WDJB-MJHT", grant.userCode());
    }

    /**
     * <b>An empty response and a whitespace-only response are both refused, by different rules.</b>
     *
     * <p>The guard uses {@code body.length == 0} on the bytes rather than {@code body.isBlank()} on a
     * decoded {@code String}, so the two cases take different paths: an empty
     * body is caught by this class, and a body of only spaces now travels on to {@code PayloadJson},
     * which refuses it as a document with no value in it. The classification an author sees is
     * unchanged — both are {@code UNAVAILABLE} — and the messages differ, which is the observable part
     * of the change and the reason it is pinned rather than left to be rediscovered.</p>
     */
    @Test
    void anEmptyResponseAndAWhitespaceOnlyResponseAreBothUnavailableByDifferentRules() {
        var empty = assertThrows(AssistantDeviceAuthorization.DeviceAuthorizationException.class,
                flow(new java.util.ArrayList<>(), new java.util.ArrayList<>(), responses(""))::begin);
        var blank = assertThrows(AssistantDeviceAuthorization.DeviceAuthorizationException.class,
                flow(new java.util.ArrayList<>(), new java.util.ArrayList<>(), responses("   \n\t "))::begin);

        assertEquals(AssistantDeviceAuthorization.Failure.UNAVAILABLE, empty.failure());
        assertEquals(AssistantDeviceAuthorization.Failure.UNAVAILABLE, blank.failure());
        assertTrue(empty.getMessage().contains("empty response"), empty.getMessage());
        assertTrue(blank.getMessage().contains("cannot read"),
                () -> "whitespace now reaches the parser and is refused there: " + blank.getMessage());
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * The response budget {@code AssistantDeviceAuthorization} applies, which its own field keeps
     * private. Not shared with production: the budget assertions above read the number back out of the
     * authored message, so a drift between this constant and the real one reds rather than hides.
     */
    private static final int RESPONSE_BUDGET_BYTES = 64 * 1024;

    /**
     * A device-authorization response this class would accept, padded to exactly {@code bytes} bytes.
     *
     * <p>All ASCII, so one character is one byte and the padding arithmetic is the byte arithmetic.
     * The pad is an unknown member, which RFC 8628 responses may carry and this class ignores — so the
     * only thing that varies between the over-budget and at-budget fixtures is the length.</p>
     */
    private static String deviceResponseOfExactly(int bytes) {
        String head = "{\"device_code\":\"dev-code\",\"user_code\":\"WDJB-MJHT\","
                + "\"verification_uri\":\"https://example.test/activate\",\"pad\":\"";
        String tail = "\"}";
        String body = head + "x".repeat(bytes - head.length() - tail.length()) + tail;
        assertEquals(bytes, body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                "the fixture must measure exactly what the test claims it measures");
        return body;
    }

    // ---------------------------------------------------------------------------------------------

    private static Deque<String> responses(String... bodies) {
        return new ArrayDeque<>(List.of(bodies));
    }

    private static AssistantDeviceAuthorization flow(java.util.List<HttpRequest> requests,
                                                     java.util.List<String> bodies,
                                                     Deque<String> responses) {
        return new AssistantDeviceAuthorization(new ScriptedHttpClient(requests, bodies, responses),
                DEVICE_ENDPOINT, TOKEN_ENDPOINT, "ravenroot-test-client", ALLOWED,
                Duration.ofSeconds(5));
    }
}
