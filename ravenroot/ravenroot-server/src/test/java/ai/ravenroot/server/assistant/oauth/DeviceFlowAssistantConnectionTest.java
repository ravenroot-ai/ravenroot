package ai.ravenroot.server.assistant.oauth;

import ai.ravenroot.core.security.OutboundHttpPolicy;
import ai.ravenroot.server.assistant.AssistantCredential;
import ai.ravenroot.server.assistant.InMemoryAssistantTokenStore;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What holds the device grant between its two halves.
 *
 * <p>{@code AssistantDeviceAuthorizationTest} pins RFC 8628 conformance — what goes on the wire and
 * what is read back. This one pins the thing that had no owner before: which grant belongs to which
 * author, when it is dropped, and where the redeemed token goes. Those are session decisions rather
 * than protocol ones, which is why they are a separate class and a separate test.</p>
 *
 * <p><b>What this establishes and what it cannot.</b> No request here leaves the process; the client
 * is scripted. So this says the mechanism behaves correctly against responses shaped the way RFC
 * 8628 specifies — not that any provider produces them. The first real run remains an experiment:
 * nothing in this repository makes a real device-flow
 * request, and this file does not make it two.</p>
 */
class DeviceFlowAssistantConnectionTest {

    private static final String AUTHOR = "author-at-the-provider-page";
    private static final URI DEVICE_ENDPOINT = URI.create("https://api.anthropic.com/v1/oauth/device");
    private static final URI TOKEN_ENDPOINT = URI.create("https://api.anthropic.com/v1/oauth/token");
    private static final OutboundHttpPolicy ALLOWED =
            OutboundHttpPolicy.fromCommaSeparatedHosts("api.anthropic.com");

    private static final String GRANT = """
            {"device_code":"the-half-that-redeems-it","user_code":"WDJB-MJHT",
             "verification_uri":"https://provider.example/device","interval":5,"expires_in":900}""";

    /**
     * <b>The device code is not in what the author is shown.</b>
     *
     * <p>RFC 8628 gives the exchange two secrets and only one of them is meant to be seen: the user
     * code is read aloud and typed, the device code redeems the grant and anyone holding it can take
     * the author's token. {@code Prompt} has no field for it, so this asserts the type rather than a
     * habit — a route cannot serialize what a record cannot hold.</p>
     */
    @Test
    void whatTheAuthorIsShownCannotContainTheDeviceCode() {
        var connection = connection(responses(GRANT));

        AssistantConnection.Prompt prompt = connection.begin(AUTHOR);

        assertEquals("WDJB-MJHT", prompt.userCode());
        assertEquals(URI.create("https://provider.example/device"), prompt.verificationUri());
        assertFalse(prompt.toString().contains("the-half-that-redeems-it"),
                () -> "the prompt printed the device code: " + prompt);
        assertFalse(java.util.Arrays.stream(AssistantConnection.Prompt.class.getRecordComponents())
                        .anyMatch(component -> component.getName().toLowerCase(java.util.Locale.ROOT)
                                .contains("devicecode")),
                "Prompt must have no component that could carry the device code");
    }

    /**
     * <b>A redeemed grant becomes a token this author can be served with, and stops being pending.</b>
     *
     * <p>Both halves matter. Without the first the author completes a sign-in that changes nothing;
     * without the second the next poll redeems the same grant again, which an authorization server
     * answers with {@code expired_token} — reporting a failure to someone who had just succeeded.</p>
     */
    @Test
    void afinishedGrantPutsTheTokenWhereTheTurnPathLooksForIt() {
        var tokens = new InMemoryAssistantTokenStore();
        var connection = connection(tokens, responses(GRANT, """
                {"access_token":"the-authors-own-token","token_type":"bearer"}"""));
        connection.begin(AUTHOR);

        assertInstanceOf(AssistantConnection.Progress.Linked.class, connection.poll(AUTHOR));

        assertTrue(tokens.tokenFor(AUTHOR).isPresent(), "the author must now hold a token");
        assertEquals(AssistantCredential.Scheme.OAUTH_BEARER,
                tokens.tokenFor(AUTHOR).orElseThrow().scheme(),
                "an operator key stored per author would silently replace the per-author token");
        assertEquals(0, connection.pendingCount(),
                "a redeemed grant must stop being pending, or the next poll redeems it again");
    }

    /**
     * <b>Waiting is not failing, and the panel is told which of the five it is.</b>
     *
     * <p>Each of these needs something different from the author — keep waiting, do nothing while
     * the interval lengthens, accept that it is over, start again — so pooling them into "not yet"
     * would remove the only information the sentence carries.</p>
     */
    @Test
    void eachWayAGrantFailsToFinishKeepsItsOwnName() {
        for (var expected : List.of(
                AssistantDeviceAuthorization.Failure.AUTHORIZATION_PENDING,
                AssistantDeviceAuthorization.Failure.SLOW_DOWN,
                AssistantDeviceAuthorization.Failure.ACCESS_DENIED,
                AssistantDeviceAuthorization.Failure.EXPIRED_TOKEN)) {
            var connection = connection(responses(GRANT,
                    "{\"error\":\"" + expected.name().toLowerCase(java.util.Locale.ROOT) + "\"}"));
            connection.begin(AUTHOR);

            var progress = connection.poll(AUTHOR);

            var waiting = assertInstanceOf(AssistantConnection.Progress.Waiting.class, progress,
                    () -> expected + " must be reported as a named wait, not as a lost connection");
            assertEquals(expected, waiting.failure());
        }
    }

    /**
     * <b>A denial and an expiry end the attempt; a pending one does not.</b>
     *
     * <p>Asserted through {@code pendingCount} rather than through a second poll's answer, because
     * the property is that the grant is GONE — a poll after a denial must find nothing to ask about
     * rather than re-presenting a decision the author already made on the provider's own page.</p>
     */
    @Test
    void theTerminalOutcomesDropTheGrantAndTheOthersKeepIt() {
        for (var terminal : List.of("access_denied", "expired_token")) {
            var connection = connection(responses(GRANT, "{\"error\":\"" + terminal + "\"}"));
            connection.begin(AUTHOR);
            connection.poll(AUTHOR);
            assertEquals(0, connection.pendingCount(), terminal + " must end the attempt");
            assertInstanceOf(AssistantConnection.Progress.None.class, connection.poll(AUTHOR),
                    () -> "a poll after " + terminal + " must find nothing in flight");
        }
        for (var open : List.of("authorization_pending", "slow_down")) {
            var connection = connection(responses(GRANT, "{\"error\":\"" + open + "\"}"));
            connection.begin(AUTHOR);
            connection.poll(AUTHOR);
            assertEquals(1, connection.pendingCount(), open + " must leave the attempt in flight");
        }
    }

    /**
     * <b>{@code slow_down} lengthens the interval for the REST of the grant, not for one round.</b>
     *
     * <p>RFC 8628 section 3.5 says add five seconds and keep it. Reporting the longer interval once
     * while continuing to poll the grant at its original pace is the shape that reads as compliant
     * and is not: the second response below is another {@code slow_down}, so a mechanism that forgot
     * would report 10 seconds twice instead of 10 and then 15.</p>
     */
    @Test
    void slowDownIsRememberedRatherThanReportedOnce() {
        var connection = connection(responses(GRANT,
                "{\"error\":\"slow_down\"}", "{\"error\":\"slow_down\"}"));
        connection.begin(AUTHOR);

        var first = (AssistantConnection.Progress.Waiting) connection.poll(AUTHOR);
        var second = (AssistantConnection.Progress.Waiting) connection.poll(AUTHOR);

        assertEquals(Duration.ofSeconds(10), first.retryAfter(),
                "the provider's 5s interval plus RFC 8628's five seconds");
        assertEquals(Duration.ofSeconds(15), second.retryAfter(),
                "a second slow_down must build on the first, or the interval springs back");
    }

    /** An author with nothing in flight is told so, rather than being given a vague wait. */
    @Test
    void anAuthorWithNothingInFlightIsToldThereIsNothingToWatch() {
        var connection = connection(responses(GRANT));

        assertInstanceOf(AssistantConnection.Progress.None.class, connection.poll(AUTHOR));
        assertInstanceOf(AssistantConnection.Progress.None.class, connection.poll(null));
    }

    /**
     * <b>Grants do not pool per author, and one author's attempt is not another's.</b>
     *
     * <p>Two live grants for one author means two codes on screen, one of them dead, with no way to
     * tell which — while the panel polls for one and the author types the other.</p>
     */
    @Test
    void startingAgainReplacesTheAttemptAndAuthorsDoNotShareOne() {
        var connection = connection(responses(GRANT, GRANT, GRANT));
        connection.begin(AUTHOR);
        connection.begin(AUTHOR);
        assertEquals(1, connection.pendingCount(), "an author has one attempt, not a growing list");

        connection.begin("a-different-author");
        assertEquals(2, connection.pendingCount(), "attempts are per author");

        connection.abandon(AUTHOR);
        assertEquals(1, connection.pendingCount(), "abandoning must drop only this author's");
        assertInstanceOf(AssistantConnection.Progress.None.class, connection.poll(AUTHOR));
    }

    /**
     * <b>A lifetime that stores nothing is refused at construction.</b>
     *
     * <p>The store drops a non-positive lifetime, so the author would appear to connect and be
     * refused on every turn — a state that reports success and behaves like failure. Refusing to
     * build is the only place that can be caught before an author experiences it.</p>
     */
    @Test
    void aLifetimeThatWouldStoreNothingIsRefusedRatherThanAccepted() {
        for (var lifetime : List.of(Duration.ZERO, Duration.ofMinutes(-5))) {
            assertThrows(IllegalArgumentException.class, () -> new DeviceFlowAssistantConnection(
                    authorization(new ArrayList<>(), new ArrayList<>(), responses(GRANT)),
                    new InMemoryAssistantTokenStore(), lifetime));
        }
    }

    /** Nothing in this class names a URL, so there is no default endpoint to mistake for a tested one. */
    @Test
    void theImplementationSuppliesNoEndpointOfItsOwn() throws Exception {
        var source = java.nio.file.Path.of("src/main/java/ai/ravenroot/server/assistant/oauth/"
                + "DeviceFlowAssistantConnection.java");
        assertTrue(java.nio.file.Files.exists(source), "the source must be readable to be checked");
        var body = java.nio.file.Files.readString(source).lines()
                .filter(line -> !line.strip().startsWith("*") && !line.strip().startsWith("//"))
                .toList();
        assertTrue(body.stream().noneMatch(line -> line.contains("http://") || line.contains("https://")),
                () -> "device-flow endpoints have no defaults; a URL here would "
                        + "be an unverified constant an operator assumes was tested: " + body.stream()
                        .filter(line -> line.contains("http")).toList());
        assertNull(System.getenv("RAVENROOT_ASSISTANT_DEVICE_ENDPOINT"),
                "no variable names the endpoints yet, and this test must not start assuming one");
    }

    // ---------------------------------------------------------------------------------------------

    private static Deque<String> responses(String... bodies) {
        return new ArrayDeque<>(List.of(bodies));
    }

    private static DeviceFlowAssistantConnection connection(Deque<String> responses) {
        return connection(new InMemoryAssistantTokenStore(), responses);
    }

    private static DeviceFlowAssistantConnection connection(InMemoryAssistantTokenStore tokens,
                                                            Deque<String> responses) {
        return new DeviceFlowAssistantConnection(
                authorization(new ArrayList<>(), new ArrayList<>(), responses), tokens,
                Duration.ofHours(8));
    }

    private static AssistantDeviceAuthorization authorization(List<HttpRequest> requests,
                                                              List<String> bodies,
                                                              Deque<String> responses) {
        return new AssistantDeviceAuthorization(new ScriptedHttpClient(requests, bodies, responses),
                DEVICE_ENDPOINT, TOKEN_ENDPOINT, "ravenroot-test-client", ALLOWED,
                Duration.ofSeconds(5));
    }
}
