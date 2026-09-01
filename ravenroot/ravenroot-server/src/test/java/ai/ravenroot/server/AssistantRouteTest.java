package ai.ravenroot.server;

import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.AuthorizationDecision;
import ai.ravenroot.api.security.AuthorizationService;
import ai.ravenroot.api.security.ProtectedResource;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.assistant.AssistantHarness;
import ai.ravenroot.server.assistant.AssistantService;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two assistant routes, exercised against a live server (ADR 0025).
 *
 * <p>Every test here provokes a state rather than asserting one is reachable in principle. That is
 * the standard this repository's own record demands: it has catalogued thirty-five controls that could
 * not fail, and an inert reason no test can provoke, or an authorization check never exercised against
 * a denial, would be the thirty-sixth and thirty-seventh.</p>
 */
class AssistantRouteTest {
    @TempDir
    Path uiDirectory;

    /**
     * <b>A denial to the user is a denial to the panel.</b>
     *
     * <p>The provider asks for the catalog; the authorization service denies {@code CATALOG_READ} for
     * this principal. The turn must end as {@code ACCESS_DENIED} — the same response
     * {@code GET /v1/node-types} itself gives this principal, asserted here side by side so the claim
     * is "the same denial", not "a denial too". The provider must be called exactly once: a second
     * call would mean the loop continued past a refused read, which is the shape of an assistant
     * quietly working around its author's permissions.</p>
     *
     * <p><b>Mutation proof.</b> Point {@code AssistantInternalContext} at the undecorated
     * {@code RavenrootApplication} instead of the authorized one and this test reds — the read
     * succeeds, the loop continues, and the route answers 200 instead of 403.</p>
     */
    @Test
    void aDeniedReadEndsTheTurnWithTheSameDenialTheRouteGives() throws Exception {
        var provider = new AssistantHarness.ScriptedProviderView()
                .callingTool("ravenroot_node_types")
                .answering("this line must never be reached");
        var denyCatalog = denying(AuthorizationAction.CATALOG_READ);
        try (var engine = new PekkoExecutionEngine("assistant-denial");
             var server = server(engine, denyCatalog, provider.service())) {
            server.start();
            var client = HttpClient.newHttpClient();

            var routeDenial = get(client, server, "/v1/node-types");
            var turnDenial = postTurn(client, server, "{\"prompt\":\"what nodes exist?\"}");

            assertEquals(403, routeDenial.statusCode(),
                    "the author's own catalog call must be denied for this test to mean anything");
            assertEquals(routeDenial.statusCode(), turnDenial.statusCode(),
                    "the panel must receive the same status the author's own call receives");
            assertTrue(turnDenial.body().contains("ACCESS_DENIED"),
                    () -> "the panel must receive the same wire code, not a generic failure: "
                            + turnDenial.body());
            assertEquals(1, provider.callCount(),
                    "the loop must stop at the denial: a second provider call means the assistant "
                            + "continued past a read its author is not allowed to perform");
        }
    }

    /**
     * <b>The provider credential never reaches the client.</b>
     *
     * <p>Plants a distinctive credential, drives the status route, a successful turn and a failing
     * turn, and greps every response body, every response header name and value, and everything the
     * server wrote to stdout while serving them.</p>
     *
     * <p><b>Mutation proof.</b> Add {@code ,"apiKey":"…credential.expose()…"} to
     * {@code AssistantAvailability#toJson}, or interpolate the configuration's credential into
     * {@code recordAssistantFailure}'s log line, and this test reds on the corresponding assertion.</p>
     */
    @Test
    void theProviderCredentialNeverReachesTheClientOrTheLog() throws Exception {
        var provider = new AssistantHarness.ScriptedProviderView()
                .answering("the catalog has four node types")
                .failing();
        var captured = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try (var engine = new PekkoExecutionEngine("assistant-credential")) {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            String status;
            String reply;
            String failure;
            try (var server = server(engine, allowAll(), provider.service())) {
                server.start();
                var client = HttpClient.newHttpClient();
                var statusResponse = get(client, server, "/v1/assistant");
                var replyResponse = postTurn(client, server, "{\"prompt\":\"hello\"}");
                var failureResponse = postTurn(client, server, "{\"prompt\":\"hello again\"}");
                status = statusResponse.body() + headers(statusResponse);
                reply = replyResponse.body() + headers(replyResponse);
                failure = failureResponse.body() + headers(failureResponse);
                assertEquals(200, statusResponse.statusCode());
                assertEquals(200, replyResponse.statusCode());
                assertFalse(failureResponse.statusCode() < 400,
                    "the failing turn must fail, or this half of the test proves nothing");
            } finally {
                System.setOut(previous);
            }
            String log = captured.toString(StandardCharsets.UTF_8);
            for (String surface : new String[] {status, reply, failure, log}) {
                assertFalse(surface.contains(AssistantHarness.PLANTED_CREDENTIAL),
                        () -> "the planted provider credential appeared in a client-visible surface "
                                + "or in the server log");
            }
            assertTrue(log.contains("assistant.turn.failed"),
                    "the failure must be recorded server-side, or the log grep above greps nothing");
        }
    }

    /**
     * <b>Every server-producible inert state is provokable, and each says which one it is.</b>
     *
     * <p>{@code service-unavailable} is asserted as a 404 rather than as a body: it has no 200
     * representation by design (see {@code AssistantAvailability}), and 404 is what
     * {@code assistant-client.js} already maps to it.</p>
     */
    @Test
    void eachInertStateIsProvokableAndDistinguished() throws Exception {
        try (var engine = new PekkoExecutionEngine("assistant-inert")) {
            assertEquals(404, statusWith(engine, AssistantHarness.disabledService()).statusCode(),
                    "an operator-disabled service must answer 404, which the panel reads as "
                            + "service-unavailable");

            var noProfile = statusWith(engine, AssistantHarness.serviceFrom(java.util.Map.of()));
            assertEquals(200, noProfile.statusCode());
            assertTrue(noProfile.body().contains("\"configured\":false"),
                    () -> "an unconfigured deployment must report no-profile: " + noProfile.body());

            var insecure = statusWith(engine, AssistantHarness.serviceFrom(java.util.Map.of(
                    "RAVENROOT_ASSISTANT_PROVIDER", "openai-compatible",
                    "RAVENROOT_ASSISTANT_ENDPOINT", "http://localhost:8081/v1/chat/completions",
                    "RAVENROOT_ASSISTANT_MODEL", "local-model",
                    "RAVENROOT_ASSISTANT_ALLOWED_HOSTS", "localhost",
                    "RAVENROOT_ASSISTANT_ALLOWED_PORTS", "8081")));
            assertEquals(200, insecure.statusCode());
            assertTrue(insecure.body().contains("\"configured\":true")
                            && insecure.body().contains("\"insecureRefused\":true"),
                    () -> "an ineligible HTTP endpoint must report insecure-refused separately: "
                            + insecure.body());

            var notAllowlisted = statusWith(engine, AssistantHarness.serviceFrom(java.util.Map.of(
                    "RAVENROOT_ASSISTANT_PROVIDER", "anthropic",
                    "RAVENROOT_ASSISTANT_API_KEY", "sk-ant-irrelevant")));
            assertEquals(200, notAllowlisted.statusCode());
            assertTrue(notAllowlisted.body().contains("\"configured\":true")
                            && notAllowlisted.body().contains("\"allowlisted\":false"),
                    () -> "a configured provider whose host the operator has not allowlisted must "
                            + "report host-not-allowlisted, not no-profile: " + notAllowlisted.body());
        }
    }

    /**
     * <b>A turn is answered or named — never an empty assistant bubble.</b>
     *
     * <p>The truncated case is asserted as a <em>reply</em>, not a failure: partial text the author can
     * use must not be discarded into the error tier, and the truncation must be visible rather than
     * silent.</p>
     */
    @Test
    void aTurnIsAnsweredOrNamedAndTruncationStaysAReply() throws Exception {
        var provider = new AssistantHarness.ScriptedProviderView()
                .truncating("the first half of an answer")
                .refusing();
        try (var engine = new PekkoExecutionEngine("assistant-outcomes");
             var server = server(engine, allowAll(), provider.service())) {
            server.start();
            var client = HttpClient.newHttpClient();

            var truncated = postTurn(client, server, "{\"prompt\":\"explain everything\"}");
            assertEquals(200, truncated.statusCode(), "a truncated turn still carries a usable answer");
            assertTrue(truncated.body().contains("the first half of an answer"),
                    () -> "the partial answer must survive: " + truncated.body());
            assertTrue(truncated.body().contains("\"truncated\":true")
                            && truncated.body().contains("output limit"),
                    () -> "truncation must be shown, never silent: " + truncated.body());

            var refused = postTurn(client, server, "{\"prompt\":\"something declined\"}");
            assertFalse(refused.statusCode() < 400,
                    "a provider refusal is a named failure, not a 200 with empty text");
            // The name must be ON the response, not only in the log. Before this, seven reasons were
            // mapped onto four ErrorCodes and only the code crossed the wire, so a refusal and an
            // exhausted tool loop were indistinguishable to the panel.
            assertTrue(refused.body().contains("\"assistantReason\":\"ASSISTANT_PROVIDER_REFUSED\""),
                    () -> "the failure must name its own reason on the response: " + refused.body());
            assertFalse(refused.body().contains(AssistantHarness.PLANTED_CREDENTIAL),
                    "the reason is a reason, not a diagnostic");
        }
    }

    @Test
    void preEgressInputAndPostEgressModelProposalFailuresAreTruthfullyDistinctAndCorrelated()
            throws Exception {
        String promptCanary = "raw-prompt-must-not-be-logged";
        String argumentCanary = "raw-tool-arguments-must-not-be-logged";
        String invalidProposal = "{\"summary\":\"" + argumentCanary + "\",\"operations\":["
                + "{\"op\":\"create-node\",\"id\":\"log\",\"behavior\":\"template\"}]}";
        var provider = new AssistantHarness.ScriptedProviderView()
                .callingTool(ai.ravenroot.server.assistant.AssistantGraphProposal.TOOL_NAME,
                        invalidProposal)
                .callingTool(ai.ravenroot.server.assistant.AssistantGraphProposal.TOOL_NAME,
                        invalidProposal)
                .callingTool(ai.ravenroot.server.assistant.AssistantGraphProposal.TOOL_NAME,
                        invalidProposal);
        String boundTurn = "{\"prompt\":\"" + promptCanary + "\",\"context\":{"
                + "\"graph\":{\"nodes\":[],\"edges\":[]},\"catalog\":[{"
                + "\"behavior\":\"template\",\"properties\":[]}]},"
                + "\"attached\":[\"graph\",\"catalog\"],\"document\":{"
                + "\"incarnation\":\"document-a\",\"revision\":1,"
                + "\"catalogDigest\":\"catalog-a\"}}";
        var captured = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try (var engine = new PekkoExecutionEngine("assistant-proposal-diagnostics")) {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            HttpResponse<String> postEgress;
            HttpResponse<String> preEgress;
            try (var server = server(engine, allowAll(), provider.service())) {
                server.start();
                var client = HttpClient.newHttpClient();
                postEgress = postTurn(client, server, boundTurn);
                preEgress = postTurn(client, server, "{\"prompt\":\"\"}");
            } finally {
                System.setOut(previous);
            }

            assertEquals(409, postEgress.statusCode(), postEgress.body());
            assertTrue(postEgress.body().contains(
                    "\"assistantReason\":\"ASSISTANT_MODEL_PROPOSAL_INVALID\""),
                    postEgress.body());
            assertEquals(400, preEgress.statusCode(), preEgress.body());
            assertFalse(preEgress.body().contains("assistantReason"), preEgress.body());
            assertEquals(3, provider.callCount(), "pre-egress rejection must not call the provider");

            String postCorrelation = field(postEgress.body(), "correlationId");
            String preCorrelation = field(preEgress.body(), "correlationId");
            assertNotEquals(postCorrelation, preCorrelation,
                    "independent assistant attempts need independent correlation handles");
            String log = captured.toString(StandardCharsets.UTF_8);
            assertTrue(log.contains("\"reason\":\"MODEL_PROPOSAL_INVALID\""), log);
            assertTrue(log.contains("\"requestId\":\"" + postCorrelation + "\""),
                    "one request must keep the same correlation from processing to failure log: " + log);
            for (String secretSurface : List.of(log, postEgress.body(), preEgress.body())) {
                assertFalse(secretSurface.contains(promptCanary), secretSurface);
                assertFalse(secretSurface.contains(argumentCanary), secretSurface);
                assertFalse(secretSurface.contains(invalidProposal), secretSurface);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    private HttpResponse<String> statusWith(PekkoExecutionEngine engine, AssistantService service)
            throws Exception {
        try (var server = server(engine, allowAll(), service)) {
            server.start();
            return get(HttpClient.newHttpClient(), server, "/v1/assistant");
        }
    }

    private RavenrootServer server(PekkoExecutionEngine engine, AuthorizationService authorization,
                                   AssistantService assistant) {
        return new RavenrootServer(new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), uiDirectory,
                new DisabledLoopbackAuthenticator(), authorization, assistant);
    }

    private static AuthorizationService allowAll() {
        return (context, action, resource) -> new AuthorizationDecision(true, "test");
    }

    /** Denies exactly one action, so the test isolates that action rather than disabling the server. */
    private static AuthorizationService denying(AuthorizationAction denied) {
        return (RequestContext context, AuthorizationAction action, ProtectedResource resource) ->
                action == denied
                        ? new AuthorizationDecision(false, "denied by test")
                        : new AuthorizationDecision(true, "test");
    }

    private static HttpResponse<String> get(HttpClient client, RavenrootServer server, String path)
            throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base(server) + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postTurn(HttpClient client, RavenrootServer server, String body)
            throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base(server) + "/v1/assistant/messages"))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String headers(HttpResponse<String> response) {
        return response.headers().map().toString();
    }

    private static String field(String json, String name) {
        var matcher = java.util.regex.Pattern.compile("\\\"" + name + "\\\":\\\"([^\\\"]+)\\\"")
                .matcher(json);
        assertTrue(matcher.find(), () -> "missing " + name + " in " + json);
        return matcher.group(1);
    }

    private static String base(RavenrootServer server) {
        return "http://localhost:" + server.port();
    }
}
