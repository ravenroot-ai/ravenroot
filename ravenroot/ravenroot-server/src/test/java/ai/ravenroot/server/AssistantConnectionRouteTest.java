package ai.ravenroot.server;

import ai.ravenroot.api.security.AuthorizationDecision;
import ai.ravenroot.api.security.AuthorizationService;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.assistant.AssistantConfiguration;
import ai.ravenroot.server.assistant.AssistantContextClass;
import ai.ravenroot.server.assistant.AssistantConsentStore;
import ai.ravenroot.server.assistant.AssistantCredential;
import ai.ravenroot.server.assistant.AssistantCredentialSource;
import ai.ravenroot.server.assistant.AssistantService;
import ai.ravenroot.server.assistant.AssistantTokenStore;
import ai.ravenroot.server.assistant.oauth.AssistantConnection;
import ai.ravenroot.server.assistant.oauth.AssistantDeviceAuthorization;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The route that reaches the device flow.
 *
 * <p>The device-flow mechanism requires a route; otherwise the only way to start a connection is to
 * construct the class directly. This exercises the entry point
 * against a live server, and every state below is provoked rather than asserted to be reachable —
 * the standard {@code AssistantRouteTest} sets and the reason it says so out loud.</p>
 *
 * <p><b>What this does not establish.</b> The connection here is a double: no request leaves the
 * process and no provider is involved. It pins the shape on the wire and the route's decisions, not
 * that an authorization server accepts any of it; provider interoperability remains unverified.</p>
 */
class AssistantConnectionRouteTest {

    @TempDir
    Path uiDirectory;

    private static final String CONNECTION = "/v1/assistant/connection";

    /** OAuth mode: operator reach, no operator credential. Authors bring their own. */
    private static final Map<String, String> OAUTH_DEPLOYMENT = Map.of(
            AssistantConfiguration.PROVIDER_VARIABLE, AssistantConfiguration.ANTHROPIC_PROVIDER,
            AssistantConfiguration.ALLOWED_HOSTS_VARIABLE, "api.anthropic.com",
            AssistantConfiguration.CREDENTIAL_SOURCE_VARIABLE,
            AssistantCredentialSource.OAUTH.wireValue());

    /**
     * <b>The panel can start a connection, and is shown what the author needs.</b>
     *
     * <p>This route gives an author access to the device flow; {@code /v1/assistant} and
     * {@code /v1/assistant/messages} alone do not expose it,
     * and neither begins a grant.</p>
     */
    @Test
    void beginningAConnectionAnswersWithTheCodeAndTheAddress() throws Exception {
        var connection = new ScriptedConnection();
        try (var engine = new PekkoExecutionEngine("assistant-connect");
             var server = server(engine, oauthService(connection))) {
            server.start();

            var response = call(server, "POST");

            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.body().contains("\"userCode\":\"WDJB-MJHT\""), response.body());
            assertTrue(response.body().contains("https://provider.example/device"), response.body());
            assertTrue(response.body().contains("\"interval\":5"), response.body());
        }
    }

    /**
     * <b>The device code never crosses the wire.</b>
     *
     * <p>The half of the exchange that redeems the grant: anyone holding it can take the author's
     * token. The route has no expression that could write it — {@code AssistantConnection.Prompt}
     * has no field for it — so this greps the response for the value the double is holding, the same
     * way {@code AssistantRouteTest} greps for a planted credential.</p>
     *
     * <p><b>Mutation proof.</b> Add the device code to {@code Prompt} and interpolate it into
     * {@code beginAssistantConnection}'s body, and this reds.</p>
     */
    @Test
    void theDeviceCodeIsNotInAnythingTheRouteSends() throws Exception {
        var connection = new ScriptedConnection();
        try (var engine = new PekkoExecutionEngine("assistant-connect-secret");
             var server = server(engine, oauthService(connection))) {
            server.start();

            var begun = call(server, "POST");
            var polled = call(server, "GET");

            for (var response : java.util.List.of(begun, polled)) {
                String whole = response.body() + response.headers().map();
                assertFalse(whole.contains(ScriptedConnection.DEVICE_CODE),
                        () -> "the device code reached the client: " + whole);
            }
        }
    }

    /**
     * <b>Every outcome the grant can have keeps its own name on the wire.</b>
     *
     * <p>Including the three state names explicitly. Their remedies differ —
     * wait, stop, start again — so a route that pooled them would hand the panel one sentence for
     * three situations.</p>
     */
    @Test
    void eachOutcomeReachesThePanelUnderItsOwnName() throws Exception {
        for (var failure : AssistantDeviceAuthorization.Failure.values()) {
            var connection = new ScriptedConnection();
            connection.waitingWith(failure);
            try (var engine = new PekkoExecutionEngine("assistant-connect-" + failure);
                 var server = server(engine, oauthService(connection))) {
                server.start();

                var response = call(server, "GET");

                assertEquals(200, response.statusCode(), response.body());
                assertTrue(response.body().contains("\"state\":\"waiting\""), response.body());
                assertTrue(response.body().contains("\"reason\":\"" + failure.name() + "\""),
                        () -> failure + " must reach the panel by name: " + response.body());
            }
        }
    }

    /** A finished connection says so and nothing else — no token, no scheme, no expiry. */
    @Test
    void aFinishedConnectionAnswersWithNothingButTheFact() throws Exception {
        var connection = new ScriptedConnection();
        connection.linked();
        try (var engine = new PekkoExecutionEngine("assistant-connect-linked");
             var server = server(engine, oauthService(connection))) {
            server.start();

            var response = call(server, "GET");

            assertEquals("{\"state\":\"linked\"}", response.body(),
                    "a finished connection must carry no credential material at all");
        }
    }

    /**
     * <b>Stopping is a request the route honours, and it is not a sign-out.</b>
     *
     * <p>It abandons the attempt in flight. It does not discard a token already obtained: those are
     * different acts with different consequences, and conflating them would let "stop waiting"
     * silently disconnect a working session.</p>
     */
    @Test
    void stoppingAbandonsTheAttempt() throws Exception {
        var connection = new ScriptedConnection();
        try (var engine = new PekkoExecutionEngine("assistant-connect-stop");
             var server = server(engine, oauthService(connection))) {
            server.start();

            var response = call(server, "DELETE");

            assertEquals(204, response.statusCode());
            assertEquals(1, connection.abandoned, "the route must actually abandon it");
        }
    }

    /**
     * <b>A deployment with no connection path refuses, and the panel never offers the control.</b>
     *
     * <p>This is every deployment today. The provider's device-flow endpoints have no defaults
     * because nothing has verified them against the provider, so no
     * composition root wires a connection yet. Two things must then be true together, and they are
     * asserted together because either alone would be misleading: the route refuses, and the STATUS
     * reports the operator's gap rather than the author's, so no Connect control is shown for a
     * connection that could not be made.</p>
     */
    @Test
    void withNoConnectionPathTheRouteRefusesAndNoControlIsOffered() throws Exception {
        try (var engine = new PekkoExecutionEngine("assistant-connect-absent");
             var server = server(engine, AssistantService.fromEnvironment(OAUTH_DEPLOYMENT,
                     consentToEverything(), nobodyConnected()))) {
            server.start();

            var refusal = call(server, "POST");
            var status = get(server, "/v1/assistant");

            assertEquals(409, refusal.statusCode(),
                    () -> "the route exists and the deployment is not configured for it, which is a "
                            + "conflict; 404 would tell the panel there is no assistant at all: "
                            + refusal.body());
            assertTrue(status.body().contains("\"linkRequired\":false"),
                    () -> "an author must not be invited to connect where connecting cannot work: "
                            + status.body());
            assertTrue(status.body().contains("\"configured\":false"),
                    () -> "what is missing here is the operator's configuration: " + status.body());
        }
    }

    /**
     * <b>The status says the connection is what is missing, per author, once a path exists.</b>
     *
     * <p>The disconnected state, provoked end to end: the deployment is whole, the caller is
     * authenticated, and the one outstanding thing is this author's connection. Reporting
     * {@code signedIn: false} would make the panel render "Sign in to Ravenroot", an instruction the
     * reader has already followed.</p>
     */
    @Test
    void anUnconnectedAuthorIsToldThatIsWhatIsMissingRatherThanThatTheyAreSignedOut() throws Exception {
        try (var engine = new PekkoExecutionEngine("assistant-connect-status");
             var server = server(engine, oauthService(new ScriptedConnection()))) {
            server.start();

            var status = get(server, "/v1/assistant");

            assertEquals(200, status.statusCode(), status.body());
            assertTrue(status.body().contains("\"linkRequired\":true"), status.body());
            assertTrue(status.body().contains("\"signedIn\":true"),
                    () -> "the Ravenroot session is fine; saying otherwise is the false "
                            + "diagnostic: " + status.body());
            assertTrue(status.body().contains("\"configured\":true"), status.body());
            assertTrue(status.body().contains("\"allowlisted\":true"), status.body());
        }
    }

    /**
     * <b>A connected author is READY, and the status says so.</b>
     *
     * <p>The other side of the state above, and the one that makes the control worth pressing.
     * It also pins the distinction required here: the status route must not ask the
     * DEPLOYMENT-level question, so in OAuth mode it reported every author unconnected — including
     * one holding a token — and the panel would have stayed inert after a successful connection.</p>
     */
    @Test
    void aConnectedAuthorIsReady() throws Exception {
        AssistantTokenStore connected = subject ->
                Optional.of(AssistantCredential.oauthToken("this-authors-own-token"));
        try (var engine = new PekkoExecutionEngine("assistant-connected");
             var server = server(engine, AssistantService
                     .fromEnvironment(OAUTH_DEPLOYMENT, consentToEverything(), connected)
                     .withConnection(new ScriptedConnection()))) {
            server.start();

            var status = get(server, "/v1/assistant");

            assertTrue(status.body().contains("\"linkRequired\":false"), status.body());
            assertTrue(status.body().contains("\"signedIn\":true"), status.body());
            assertFalse(status.body().contains("this-authors-own-token"),
                    () -> "the token must never reach the client: " + status.body());
        }
    }

    /**
     * <b>An operator-key deployment says nothing about connections and offers no control.</b>
     *
     * <p>The operator-key path stays the default. The field is present and false
     * rather than absent, so the answer is explicit rather than inferred from a missing key — and
     * the panel's own reading of a missing field is the same, which is what keeps a status written
     * by an older build behaving as it did.</p>
     */
    @Test
    void theOperatorKeyPathIsUnchangedAndInvitesNobodyToConnect() throws Exception {
        var keyed = Map.of(
                AssistantConfiguration.PROVIDER_VARIABLE,
                ai.ravenroot.server.assistant.provider.ScriptedAssistantProvider.ID,
                AssistantConfiguration.API_KEY_VARIABLE, "sk-ant-the-operators-own-key");
        try (var engine = new PekkoExecutionEngine("assistant-keyed");
             var server = server(engine, AssistantService.fromEnvironment(keyed))) {
            server.start();

            var status = get(server, "/v1/assistant");

            assertTrue(status.body().contains("\"linkRequired\":false"), status.body());
            assertTrue(status.body().contains("\"signedIn\":true"), status.body());
        }
    }

    /** An unsupported method is refused with the methods this path does have. */
    @Test
    void anUnsupportedMethodIsNamedRatherThanMishandled() throws Exception {
        try (var engine = new PekkoExecutionEngine("assistant-connect-method");
             var server = server(engine, oauthService(new ScriptedConnection()))) {
            server.start();

            var response = call(server, "PUT");

            assertEquals(405, response.statusCode());
            assertEquals(Optional.of("POST, GET, DELETE"), response.headers().firstValue("Allow"));
        }
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * A connection that answers from a script and records what it was asked.
     *
     * <p>It holds a device code it never hands out, which is what gives
     * {@link #theDeviceCodeIsNotInAnythingTheRouteSends} something real to grep for.</p>
     */
    private static final class ScriptedConnection implements AssistantConnection {
        static final String DEVICE_CODE = "device-code-CANARY-must-not-be-sent";

        private Progress progress = new Progress.Waiting(
                AssistantDeviceAuthorization.Failure.AUTHORIZATION_PENDING, Duration.ofSeconds(5));
        private int abandoned;

        void waitingWith(AssistantDeviceAuthorization.Failure failure) {
            progress = new Progress.Waiting(failure, Duration.ofSeconds(5));
        }

        void linked() {
            progress = new Progress.Linked();
        }

        @Override
        public Prompt begin(String subject) {
            // The device code stays here, exactly as the real implementation keeps it in its
            // pending register: the shape of `Prompt` is what stops it going any further.
            assertFalse(DEVICE_CODE.isEmpty());
            return new Prompt("WDJB-MJHT", URI.create("https://provider.example/device"), null,
                    Duration.ofSeconds(5), Duration.ofMinutes(15));
        }

        @Override
        public Progress poll(String subject) {
            return progress;
        }

        @Override
        public void abandon(String subject) {
            abandoned++;
        }
    }

    private static AssistantService oauthService(AssistantConnection connection) {
        return AssistantService.fromEnvironment(OAUTH_DEPLOYMENT, consentToEverything(),
                nobodyConnected()).withConnection(connection);
    }

    private static AssistantTokenStore nobodyConnected() {
        return subject -> Optional.empty();
    }

    private static AssistantConsentStore consentToEverything() {
        return (subject, provider) -> EnumSet.allOf(AssistantContextClass.class);
    }

    private RavenrootServer server(PekkoExecutionEngine engine, AssistantService assistant) {
        return new RavenrootServer(new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), uiDirectory,
                new DisabledLoopbackAuthenticator(), allowAll(), assistant);
    }

    private static AuthorizationService allowAll() {
        return (context, action, resource) -> new AuthorizationDecision(true, "test");
    }

    private static HttpResponse<String> call(RavenrootServer server, String method) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base(server) + CONNECTION))
                        .method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(RavenrootServer server, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base(server) + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String base(RavenrootServer server) {
        return "http://localhost:" + server.port();
    }
}
