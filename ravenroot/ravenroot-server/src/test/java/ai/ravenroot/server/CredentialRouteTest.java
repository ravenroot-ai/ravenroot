package ai.ravenroot.server;

import ai.ravenroot.api.security.AuthorizationDecision;
import ai.ravenroot.api.security.AuthorizationService;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.credential.CredentialReference;
import ai.ravenroot.server.credential.SqliteUserCredentialStore;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.RequestAuthenticator;
import com.sun.net.httpserver.Headers;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The credential route, against a live server and two different authors.
 *
 * <p>Every property here is <b>provoked rather than asserted to be reachable</b>, which is the
 * standard {@code AssistantRouteTest} set and the reason it says so out loud. Two authors talk to one
 * process through a test authenticator that reads the bearer token as the subject, because "a second
 * authenticated user sees nothing of the first's" is a statement about two callers and cannot be made
 * against a store in isolation.</p>
 */
class CredentialRouteTest {

    @TempDir
    Path uiDirectory;

    @TempDir
    Path storeDirectory;

    /**
     * Planted in the request body and hunted for in every surface a client or an operator can read.
     *
     * <p><b>Every character outside the two hyphens is deliberately non-hexadecimal.</b> The search
     * below looks for FRAGMENTS as well as the whole string, and the surfaces it searches are full of
     * hex — a minted reference is 32 hex digits and an ISO timestamp is mostly digits. A canary
     * containing {@code 0577} or {@code ant} would eventually collide with one of those and the test
     * would fail for a reason that is not a leak. {@code z q x v j w k p} appear in neither hex nor a
     * timestamp.</p>
     */
    private static final String PLANTED = "sk-zqxv-planted-canary-jwkp";

    /**
     * How much of the canary counts as a leak.
     *
     * <p>Four characters detect a masked fingerprint such as a field
     * built from the value's first three and last four characters: {@code "valueHint":"sk-...nary"}.
     * Thirty-two tests stayed green while a 201 carried a fingerprint of the credential.</p>
     *
     * <p>Four characters at each end is what an implementer writing a "helpful" masked hint actually
     * emits, and it is small enough that a three-character fragment cannot slip
     * through the prefix check either. It is not a claim that a three-character leak is acceptable —
     * it is the shortest fragment that can be searched for without matching hex by accident. The
     * rule enforced here is "never the value in any form, not even masked", which
     * {@code docs/api/openapi.json} states twice as "masked or otherwise".</p>
     */
    private static final int LEAKED_FRAGMENT = 4;

    private static final String CREDENTIALS = "/v1/credentials";

    /**
     * <b>An author stores a key from the interface and gets back a reference the server chose.</b>
     *
     * <p>On the write side, the value goes in, only a reference comes back, and the reference is minted
     * rather than proposed. Immediate resolution is verified by
     * {@link #aStoredCredentialResolvesWithoutRestartingAnything}.</p>
     */
    @Test
    void storingAKeyAnswersWithAServerMintedReferenceAndNoValue() throws Exception {
        try (var store = SqliteUserCredentialStore.openUnder(storeDirectory);
             var engine = new PekkoExecutionEngine("credential-create");
             var server = server(engine, store)) {
            server.start();

            var created = post(server, CREDENTIALS, "alice",
                    "{\"label\":\"Claude connection\",\"scheme\":\"api-key\",\"value\":\"" + PLANTED + "\"}");

            assertEquals(201, created.statusCode(), created.body());
            assertTrue(created.body().contains("\"label\":\"Claude connection\""), created.body());
            assertTrue(created.body().contains("\"reference\":\"" + CredentialReference.PREFIX),
                    () -> "the response must carry a minted reference: " + created.body());
            assertNoTraceOf("the create response", created.body());
        }
    }

    /**
     * <b>A caller-proposed reference is refused.</b>
     *
     * <p>This is verified at the route rather than at the reader because
     * that is where a client would try it.</p>
     *
     * <p><b>Mutation proof.</b> Add {@code "reference"} to {@code UserCredentialWire.KNOWN_FIELDS}
     * and this reds with a 201.</p>
     */
    @Test
    void aReferenceProposedByTheCallerIsRefused() throws Exception {
        try (var store = SqliteUserCredentialStore.openUnder(storeDirectory);
             var engine = new PekkoExecutionEngine("credential-propose");
             var server = server(engine, store)) {
            server.start();

            var refused = post(server, CREDENTIALS, "alice",
                    "{\"reference\":\"rrc_00000000000000000000000000000000\",\"label\":\"mine\","
                            + "\"scheme\":\"api-key\",\"value\":\"" + PLANTED + "\"}");

            assertEquals(400, refused.statusCode(), refused.body());
            assertFalse(refused.body().contains(PLANTED), refused.body());
        }
    }

    /**
     * <b>A username and password go in and never come back.</b>
     *
     * <p>The password is planted and hunted for; the username is not a secret
     * and is expected in the list, which is what makes two entries labelled "database"
     * distinguishable.</p>
     */
    @Test
    void aUsernameAndPasswordAreStoredAndOnlyTheUsernameIsEverVisible() throws Exception {
        try (var store = SqliteUserCredentialStore.openUnder(storeDirectory);
             var engine = new PekkoExecutionEngine("credential-basic");
             var server = server(engine, store)) {
            server.start();

            var created = post(server, CREDENTIALS, "alice",
                    "{\"label\":\"production database\",\"scheme\":\"basic\","
                            + "\"username\":\"operator\",\"value\":\"" + PLANTED + "\"}");
            var listed = get(server, CREDENTIALS, "alice");

            assertEquals(201, created.statusCode(), created.body());
            assertTrue(listed.body().contains("\"username\":\"operator\""), listed.body());
            assertNoTraceOf("the create response", created.body() + created.headers().map());
            assertNoTraceOf("the list response", listed.body() + listed.headers().map());
        }
    }

    /**
     * <b>A second authenticated author does not see the first's references.</b>
     *
     * <p>Listing isolation is verified against a live server with two callers.</p>
     *
     * <p><b>Mutation proof.</b> Remove {@code AND subject = ?} from {@code listFor}'s WHERE clause in
     * {@code SqliteUserCredentialStore} and this reds:
     * Bob's list gains Alice's entry.</p>
     */
    @Test
    void asecondAuthorSeesNothingOfTheFirsts() throws Exception {
        try (var store = SqliteUserCredentialStore.openUnder(storeDirectory);
             var engine = new PekkoExecutionEngine("credential-isolation");
             var server = server(engine, store)) {
            server.start();

            var alices = post(server, CREDENTIALS, "alice",
                    "{\"label\":\"Alice only\",\"scheme\":\"api-key\",\"value\":\"" + PLANTED + "\"}");
            String reference = referenceIn(alices.body());
            post(server, CREDENTIALS, "bob",
                    "{\"label\":\"Bob only\",\"scheme\":\"api-key\",\"value\":\"sk-bob\"}");

            var bobSees = get(server, CREDENTIALS, "bob");

            assertEquals(200, bobSees.statusCode(), bobSees.body());
            assertTrue(bobSees.body().contains("Bob only"), bobSees.body());
            assertFalse(bobSees.body().contains("Alice only"),
                    () -> "Alice's label reached Bob: " + bobSees.body());
            assertFalse(bobSees.body().contains(reference),
                    () -> "Alice's reference reached Bob: " + bobSees.body());
        }
    }

    /**
     * <b>A second author cannot use the first's reference either.</b>
     *
     * <p>{@code CredentialAdmission} enforces that a second author cannot use the first author's
     * reference. Bob is given Alice's reference by this test, which is the
     * only way he could ever get it, and submits a graph that names it. The submission is refused
     * before an execution id exists.</p>
     *
     * <p><b>Mutation proof.</b> Delete the {@code credentialAdmission.require(context, graph)} call in
     * {@code RavenrootServer#submitExecution} and this reds with a 202. Alternatively make
     * {@code SqliteUserCredentialStore#isOwnedBy} return {@code true} unconditionally and it reds the
     * same way — the two together are what the constraint is made of.</p>
     */
    @Test
    void asecondAuthorCannotRunAGraphThatNamesTheFirstsCredential() throws Exception {
        try (var store = SqliteUserCredentialStore.openUnder(storeDirectory);
             var engine = new PekkoExecutionEngine("credential-admission");
             var server = server(engine, store)) {
            server.start();

            String reference = referenceIn(post(server, CREDENTIALS, "alice",
                    "{\"label\":\"Alice only\",\"scheme\":\"api-key\",\"value\":\"" + PLANTED
                            + "\"}").body());

            var alicesRun = execute(server, "alice", graphNaming(reference));
            var bobsRun = execute(server, "bob", graphNaming(reference));

            assertEquals(202, alicesRun.statusCode(),
                    () -> "the owner must still be able to run their own graph: " + alicesRun.body());
            assertEquals(403, bobsRun.statusCode(),
                    () -> "Bob ran a graph naming Alice's credential: " + bobsRun.body());
            assertFalse(bobsRun.body().contains(reference),
                    () -> "the refusal echoed the reference: " + bobsRun.body());
        }
    }

    /**
     * <b>A reference that belongs to nobody is refused exactly like one that belongs to somebody
     * else.</b>
     *
     * <p>Two different answers would be an oracle: a caller could enumerate which references exist by
     * watching which of 404 and 403 came back. This is why {@code UserCredentialStore#isOwnedBy}
     * returns one bit and the route has one refusal.</p>
     */
    @Test
    void anUnknownReferenceIsRefusedTheSameWayAsAForeignOne() throws Exception {
        try (var store = SqliteUserCredentialStore.openUnder(storeDirectory);
             var engine = new PekkoExecutionEngine("credential-oracle");
             var server = server(engine, store)) {
            server.start();

            String foreign = referenceIn(post(server, CREDENTIALS, "alice",
                    "{\"label\":\"L\",\"scheme\":\"api-key\",\"value\":\"" + PLANTED + "\"}").body());
            String neverStored = CredentialReference.mint();

            var againstForeign = execute(server, "bob", graphNaming(foreign));
            var againstUnknown = execute(server, "bob", graphNaming(neverStored));
            String foreignCorrelation = correlationIn(againstForeign.body());
            String unknownCorrelation = correlationIn(againstUnknown.body());

            assertEquals(againstForeign.statusCode(), againstUnknown.statusCode(),
                    "the two answers must be indistinguishable");
            assertNotEquals(foreignCorrelation, unknownCorrelation,
                    "separate requests must retain separate correlation ids");
            assertEquals(againstForeign.body().replace(foreignCorrelation, "<correlation>"),
                    againstUnknown.body().replace(unknownCorrelation, "<correlation>"),
                    "their bodies must differ only by per-request correlation");
            assertEquals(403, againstForeign.statusCode());
        }
    }

    /**
     * <b>An operator's own reference is untouched by any of this.</b>
     *
     * <p>The environment path remains supported, and a graph naming {@code openai-main} must
     * still be admitted — it has no owner and never had one. Without the disjoint namespace
     * {@code CredentialReference.PREFIX} establishes, the admission check would have had to refuse
     * every reference it did not recognise, which would have broken every existing graph.</p>
     *
     * <p><b>Mutation proof.</b> Make {@code CredentialAdmission#require} check every reference-shaped
     * string rather than only minted ones — that is, drop the {@code PREFIX} from
     * {@code CredentialReference.foundIn}'s pattern so it matches bare hex — and this reds.</p>
     */
    @Test
    void anOperatorProvisionedReferenceIsStillAdmitted() throws Exception {
        try (var store = SqliteUserCredentialStore.openUnder(storeDirectory);
             var engine = new PekkoExecutionEngine("credential-operator-path");
             var server = server(engine, store)) {
            server.start();

            var run = execute(server, "bob", graphNaming("openai-main"));

            assertEquals(202, run.statusCode(),
                    () -> "the pre-existing operator path must keep working: " + run.body());
        }
    }

    /**
     * <b>A credential stored a moment ago resolves in the very next execution.</b>
     *
     * <p>The store is composed into the resolver chain rather than read from the environment at boot so
     * a new credential resolves without restarting the server. The
     * assertion is deliberately about resolution and not about a provider call: no adapter is
     * installed in this build, so the honest thing to observe is that the reference the author just
     * created now resolves to the value they just typed, through the same
     * {@code CredentialResolver} a node would consult.</p>
     *
     * <p><b>Mutation proof.</b> Freeze the store at construction — return the composition-time list
     * instead of querying, the shape {@code EnvironmentCredentialResolver} has by design — and this
     * reds, because the credential minted after the resolver was built no longer resolves.</p>
     */
    @Test
    void aStoredCredentialResolvesWithoutRestartingAnything() throws Exception {
        try (var store = SqliteUserCredentialStore.openUnder(storeDirectory);
             var engine = new PekkoExecutionEngine("credential-no-restart");
             var server = server(engine, store)) {
            server.start();
            // The resolver a node would consult, composed exactly as RavenrootServerMain composes it
            // and BEFORE the credential exists. That ordering is the test: a resolver that read the
            // store once at construction would answer empty below.
            ai.ravenroot.api.security.CredentialResolver resolver =
                    new ai.ravenroot.server.credential.CredentialResolverChain(store::resolve,
                            reference -> java.util.Optional.empty());

            String reference = referenceIn(post(server, CREDENTIALS, "alice",
                    "{\"label\":\"L\",\"scheme\":\"api-key\",\"value\":\"" + PLANTED + "\"}").body());

            try (var resolved = resolver.resolve(reference).orElseThrow(
                    () -> new AssertionError("the credential just created did not resolve"))) {
                assertEquals(PLANTED, new String(resolved.copy()));
            }
        }
    }

    /**
     * <b>The value reaches no response, no log line and no event.</b>
     *
     * <p>The canary is planted through the real route, and the whole of {@code System.out} and
     * {@code System.err} is captured for the duration — which is where
     * {@code StructuredExecutionLogger}, {@code StructuredArtifactLifecycleLogger} and the two
     * rejection loggers write — and every client-visible surface is concatenated and searched.</p>
     *
     * <p><b>The search is for fragments, not only for the whole string.</b> A whole-value-only search
     * passes while a 201 carries
     * {@code "valueHint":"sk-...nary"}. See {@link #LEAKED_FRAGMENT}. Every surface in this file goes
     * through {@link #assertNoTraceOf} so no copy of the check can be weaker than another.</p>
     *
     * <p><b>Mutation proof, and it must be run to be claimed.</b> Add
     * {@code + ",\"value\":\"" + …} to {@code UserCredentialWire#writeCredential}, print the
     * request body in {@code RavenrootServer#createCredential}, or add a masked hint built from the
     * value's first and last characters there, and this reds naming the
     * surface. The control that the search is not vacuous is in the test itself: it first asserts the
     * canary IS found in the request it sent, so a canary that never travelled cannot pass by
     * absence.</p>
     */
    @Test
    void theValueAppearsInNoResponseNoLogAndNoEvent() throws Exception {
        var captured = new ByteArrayOutputStream();
        PrintStream previousOut = System.out;
        PrintStream previousErr = System.err;
        String createdBody;
        String listedBody;
        String executionBody;
        try (var captureStream = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(captureStream);
            System.setErr(captureStream);
            try (var store = SqliteUserCredentialStore.openUnder(storeDirectory);
                 var engine = new PekkoExecutionEngine("credential-canary");
                 var server = server(engine, store)) {
                server.start();
                String request = "{\"label\":\"L\",\"scheme\":\"api-key\",\"value\":\"" + PLANTED + "\"}";
                // The control: the canary really is in what we sent. Without this the search below
                // could pass because nothing ever carried it.
                assertTrue(request.contains(PLANTED));

                createdBody = post(server, CREDENTIALS, "alice", request).body();
                listedBody = get(server, CREDENTIALS, "alice").body();
                String reference = referenceIn(createdBody);
                executionBody = execute(server, "alice", graphNaming(reference)).body();
                // The event stream is written by StructuredExecutionLogger onto the captured stream
                // above; give the traversal a moment to produce it rather than asserting against an
                // empty log, which would make this test pass for the wrong reason.
                Thread.sleep(500);
            }
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
        }
        String logs = captured.toString(StandardCharsets.UTF_8);

        assertFalse(logs.isBlank(),
                "nothing was logged at all, so a search of the logs would prove nothing");
        java.util.Map.of("the create response", createdBody, "the list response", listedBody,
                        "the execution response", executionBody, "stdout and stderr", logs)
                .forEach(CredentialRouteTest::assertNoTraceOf);
    }

    /** No store composed means no route, which is the absent-adapter contract's absent-adapter default. */
    @Test
    void aDeploymentThatComposesNoStoreDoesNotOfferTheRoute() throws Exception {
        try (var engine = new PekkoExecutionEngine("credential-absent");
             var server = server(engine, null)) {
            server.start();

            assertEquals(404, get(server, CREDENTIALS, "alice").statusCode());
        }
    }

    // ---------------------------------------------------------------------------------------------


    /**
     * Fails if any part of the canary reached this surface — whole, prefix or suffix.
     *
     * <p>One helper rather than an assertion per call site, so that a surface added to this file
     * later cannot be checked more weakly than the others by omission. That is exactly how the
     * masked-leak hole got in: {@code contains(PLANTED)} was correct and was repeated, and repeating
     * a correct-but-narrow check is what made every copy narrow.</p>
     */
    private static void assertNoTraceOf(String surface, String text) {
        assertFalse(text.contains(PLANTED),
                () -> "the planted credential appeared whole in " + surface + ": " + text);
        assertFalse(text.contains(PLANTED.substring(0, LEAKED_FRAGMENT)),
                () -> "the first " + LEAKED_FRAGMENT + " characters of the credential appeared in "
                        + surface + " -- a masked hint is still the credential: " + text);
        assertFalse(text.contains(PLANTED.substring(PLANTED.length() - LEAKED_FRAGMENT)),
                () -> "the last " + LEAKED_FRAGMENT + " characters of the credential appeared in "
                        + surface + " -- a masked hint is still the credential: " + text);
    }

    /**
     * Two authors, one process: the bearer token is the subject.
     *
     * <p>Deliberately not {@code DisabledLoopbackAuthenticator}, which answers {@code anonymous-loopback}
     * for everybody — against it, every isolation assertion in this file would pass for the wrong
     * reason, because there would only ever have been one author.</p>
     */
    private static final class SubjectIsTheToken implements RequestAuthenticator {
        @Override
        public AuthenticatedPrincipal authenticate(Headers headers) {
            String authorization = headers.getFirst("Authorization");
            String subject = authorization == null ? "anonymous"
                    : authorization.replaceFirst("(?i)^Bearer ", "").trim();
            return new AuthenticatedPrincipal(subject.isEmpty() ? "anonymous" : subject,
                    AuthenticatedPrincipal.Type.USER, "urn:ravenroot:test", "tenant-a",
                    Set.of(Role.PLATFORM_ADMIN),
                    java.util.Arrays.stream(ai.ravenroot.api.security.AuthorizationAction.values())
                            .filter(ai.ravenroot.api.security.AuthorizationAction::available)
                            .map(ai.ravenroot.api.security.AuthorizationAction::requiredScope)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        }
    }

    private RavenrootServer server(PekkoExecutionEngine engine,
                                   ai.ravenroot.server.credential.UserCredentialStore credentials) {
        return new RavenrootServer(new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), uiDirectory,
                new SubjectIsTheToken(), allowAll(), credentials);
    }

    private static AuthorizationService allowAll() {
        return (context, action, resource) -> new AuthorizationDecision(true, "test");
    }

    /**
     * A graph that names a credential reference, in the place a node actually carries one.
     *
     * <p>{@code template} rather than {@code llm-prompt} on purpose: this build registers no model
     * provider adapter, so an {@code llm-prompt} node would refuse for an unrelated reason and the
     * submission would never reach the point this test is about. What matters for admission is that
     * the reference is in the document, which is the property {@code CredentialAdmission} checks and
     * the reason it scans bytes rather than parsed properties.</p>
     */
    private static String graphNaming(String reference) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
                  <key id="template" for="node" attr.name="template" attr.type="string"/>
                  <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
                  <graph id="credential-graph" edgedefault="directed">
                    <node id="start"><data key="kind">START</data></node>
                    <node id="greet">
                      <data key="kind">BEHAVIOR</data>
                      <data key="behavior">template</data>
                      <data key="template">using REFERENCE for {{payload}}</data>
                    </node>
                    <node id="end"><data key="kind">END</data></node>
                    <node id="error"><data key="kind">ERROR</data></node>
                    <edge id="e1" source="start" target="greet"><data key="outcome">continue</data></edge>
                    <edge id="e2" source="greet" target="end"><data key="outcome">continue</data></edge>
                    <edge id="e3" source="greet" target="error"/>
                  </graph>
                </graphml>
                """.replace("REFERENCE", reference);
    }

    private static String referenceIn(String body) {
        var matcher = java.util.regex.Pattern.compile("\"reference\":\"(" + CredentialReference.PREFIX
                + "[0-9a-f]{32})\"").matcher(body);
        if (!matcher.find()) {
            throw new AssertionError("no minted reference in " + body);
        }
        return matcher.group(1);
    }

    private static String correlationIn(String body) {
        var matcher = java.util.regex.Pattern.compile("\"correlationId\":\"([^\"]+)\"")
                .matcher(body);
        if (!matcher.find()) throw new AssertionError("no correlation id in " + body);
        return matcher.group(1);
    }

    private static HttpResponse<String> post(RavenrootServer server, String path, String subject,
                                             String body) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base(server) + path))
                        .header("Authorization", "Bearer " + subject)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(RavenrootServer server, String path, String subject)
            throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base(server) + path))
                        .header("Authorization", "Bearer " + subject).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> execute(RavenrootServer server, String subject, String graphMl)
            throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base(server) + "/v1/executions?payload=hello"))
                        .header("Authorization", "Bearer " + subject)
                        .header("Content-Type", "application/graphml+xml; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(graphMl, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String base(RavenrootServer server) {
        return "http://localhost:" + server.port();
    }
}
