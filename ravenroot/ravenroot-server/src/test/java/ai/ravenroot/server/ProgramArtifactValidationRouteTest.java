package ai.ravenroot.server;

import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramAdmission;
import ai.ravenroot.api.programming.ProgramDeadlineExceededException;
import ai.ravenroot.api.programming.ProgramRequest;
import ai.ravenroot.api.programming.ProgramRuntime;
import ai.ravenroot.api.programming.ProgramRuntimeUnavailableException;
import ai.ravenroot.api.programming.ProgramSourceRejectedException;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.core.ai.AgentRuntimeRegistry;
import ai.ravenroot.core.ai.ModelProviderRegistry;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import ai.ravenroot.server.security.RequestAuthenticator;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code POST /v1/program-artifacts/{id}/validate} tells the author why their source was
 * refused, and stops calling that a malformed request.
 *
 * <h2>The diagnostic under test</h2>
 * <p>A Python artifact with an indentation error previously produced only
 * {@code the request was rejected as invalid (HTTP 400 POST /v1/program-artifacts/{id}/validate)}.
 * GraalPy had produced {@code IndentationError: expected an indented block after function definition
 * on line 1}, the adapter carried it to this layer, and the handler read the exception only to choose
 * between two status codes.</p>
 *
 * <h2>Why the runtime here is a stub and that is not a weakness</h2>
 * <p>The claim under test is what THIS ROUTE does with a typed rejection — the status it answers, the
 * shape of the body, and that a rejection is not confused with a conflict or a malformed request. The
 * evidence that the rejection carries GraalPy's real text, with GraalPy's real line, is
 * {@code ProgramSourceRejectionDiagnosticTest}, which drives the actual worker. Running a child JVM
 * here would make this suite a measurement of the host rather than of the route, and would test the
 * adapter twice while testing the route once. The diagnostic the stub raises is therefore the exact
 * string that test measured, quoted rather than invented.</p>
 */
class ProgramArtifactValidationRouteTest {

    /**
     * The measured GraalPy output for {@code "def handler(request):\nreturn {'ok': True}\nhandler"},
     * quoted from {@code ProgramSourceRejectionDiagnosticTest}'s real-worker run. Only the artifact
     * id, which GraalPy takes from the source name, differs between runs.
     */
    private static final String MEASURED_PYTHON_DIAGNOSTIC =
            "IndentationError: expected an indented block after function definition on line 1 "
                    + "(artifact, line 2)";

    /**
     * End to end over HTTP, an unindented Python source produces an outcome
     * that names the cause and its line, and does not produce "rejected as invalid".
     */
    @Test
    void aSourceThatDoesNotCompileAnswersAnOutcomeNamingTheCauseAndItsLine() throws Exception {
        try (var fixture = new Fixture(rejectingRuntime())) {
            String id = fixture.createArtifact();
            var response = fixture.post("/" + id + "/validate");

            assertEquals(200, response.statusCode(),
                    "a source that does not compile is a RESULT of a well-formed request. 400 said "
                            + "the request was malformed, which was never true and told the author "
                            + "to look at the wrong thing.");
            assertTrue(response.body().contains("\"outcome\":\"rejected\""), response.body());
            assertTrue(response.body().contains("\"diagnostic\":\"" + MEASURED_PYTHON_DIAGNOSTIC + "\""),
                    "the compiler's own reason must reach the author verbatim, was: " + response.body());
            assertTrue(response.body().contains("\"line\":2"),
                    "the position must arrive as a number a caller can act on, was: " + response.body());
            assertTrue(response.body().contains("\"column\":1"),
                    "the position travels as numbers, both of them -- measured, GraalPy places this "
                            + "at line 2 column 1, was: " + response.body());
            assertFalse(response.body().contains("rejected as invalid"),
                    "a compilation failure must not accuse the request of being invalid: " + response.body());
            assertFalse(response.body().contains("\"contract\":\"ravenroot.error/1\""),
                    "this is not an error response and must not wear the error envelope's shape");
        }
    }

    /**
     * The artifact is untouched by a rejection, and the author can fix the source and try again.
     *
     * <p>Asserted because the outcome body deliberately carries no {@code artifact} member: a reader
     * must be able to rely on "nothing changed" rather than infer it from the member's absence.</p>
     */
    @Test
    void aRejectedValidationLeavesTheArtifactWhereItWas() throws Exception {
        try (var fixture = new Fixture(rejectingRuntime())) {
            String id = fixture.createArtifact();
            fixture.post("/" + id + "/validate");

            var listed = fixture.get("");
            assertTrue(listed.body().contains("\"state\":\"GENERATED\""),
                    "a refused source must leave the artifact validatable again, was: " + listed.body());
        }
    }

    /** The accepted outcome is wrapped too, so no caller can read a rejection as a success by habit. */
    @Test
    void anAcceptedSourceAnswersTheSameShapeWithTheArtifactInItsNewState() throws Exception {
        try (var fixture = new Fixture(acceptingRuntime())) {
            String id = fixture.createArtifact();
            var response = fixture.post("/" + id + "/validate");

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"outcome\":\"validated\""), response.body());
            assertTrue(response.body().contains("\"state\":\"VALIDATED\""), response.body());
            assertTrue(response.body().contains("\"artifact\":{"),
                    "the artifact is still delivered, one member in, was: " + response.body());
        }
    }

    /**
     * A missing sandbox launcher is a deployment failure, not invalid source.
     *
     * <p>With {@code RAVENROOT_GRAAL_SANDBOX_SUPERVISOR} unset the adapter installs its missing-launcher
     * stub and every validation fails at the capability check, <b>before the source is read</b>. That
     * used to answer 400 {@code the request was rejected as invalid} — the identical sentence a real
     * syntax error produced — so correcting the indentation changed nothing and there was no way to
     * learn why.</p>
     *
     * <p>The assertion that matters most is the last one: the answer must not send the reader back to
     * their own source, because their source is not what failed and nothing has looked at it.</p>
     */
    @Test
    void aDeploymentWithNoUsableSandboxNamesTheSandboxRatherThanBlamingTheRequest() throws Exception {
        try (var fixture = new Fixture(failingRuntime(new ProgramRuntimeUnavailableException(
                ProgramRuntimeUnavailableException.Reason.SANDBOX_UNAVAILABLE, "SANDBOX_LAUNCHER_MISSING")))) {
            String id = fixture.createArtifact();
            var response = fixture.post("/" + id + "/validate");

            assertEquals(501, response.statusCode(),
                    "nothing about the request was wrong, so 400 was false; and the condition holds "
                            + "until a person changes this deployment, so 503 would invite a retry "
                            + "that can never succeed");
            assertTrue(response.body().contains("\"code\":\"PROGRAM_SANDBOX_UNAVAILABLE\""), response.body());
            assertTrue(response.body().contains("an operator must configure or repair it"),
                    "the author has to learn that this is somebody else's job, was: " + response.body());
            assertTrue(response.body().contains("The artifact source is not at fault"),
                    "the sentence that stops the reader going back to their own code, was: " + response.body());
            assertFalse(response.body().contains("rejected as invalid"), response.body());
            assertFalse(response.body().contains("SANDBOX_LAUNCHER_MISSING"),
                    "the internal token belongs in the server's own record, not in the answer");
            // Mutating ErrorCode.PROGRAM_SANDBOX_UNAVAILABLE's literal to
            // append the launcher's own path -- "(launcher: /opt/ravenroot/dev/sandbox-supervisor.sh)",
            // the shipped compose default -- left this test suite entirely green before this line
            // existed: nothing checked for a path, only for the token above. This is the assertion
            // that makes that specific mutation fail; the token assertion above and this one together
            // are what actually hold the policy boundary, not the ErrorCode structural guarantee alone
            // (that guarantee is real, but it is about what reaches getMessage(), which a hand-edited
            // literal never goes through).
            assertFalse(response.body().contains("/opt/ravenroot/dev/sandbox-supervisor.sh"),
                    "no launcher path may reach the HTTP response, regardless of where it might be "
                            + "hand-added to the literal, was: " + response.body());
            assertTrue(response.body().contains("recorded in this deployment's server log"),
                    "the message must say where to look for the reason, was: " + response.body());
        }
    }

    /**
     * The same condition met the other way, and the way a stock install meets it.
     *
     * <p>{@code DisabledProgramRuntime} is the safe default: an install that has never been configured
     * for programmable nodes has no adapter at all, and its {@code validate} is the interface default.
     * An author pressing Validate on such a deployment was told their request was invalid, which makes
     * this the <em>likeliest</em> way to encounter the false invalid-request diagnosis.</p>
     */
    @Test
    void aDeploymentWithNoAdapterInstalledSaysSoRatherThanBlamingTheRequest() throws Exception {
        try (var fixture = new Fixture(new ProgramRuntime() {
            @Override
            public String id() {
                return "disabled";
            }

            @Override
            public CompletionStage<Object> execute(ProgramAdmission admission, ProgramRequest request) {
                return CompletableFuture.failedFuture(new IllegalStateException("disabled"));
            }
        })) {
            String id = fixture.createArtifact();
            var response = fixture.post("/" + id + "/validate");

            assertEquals(501, response.statusCode(), response.body());
            assertTrue(response.body().contains("\"code\":\"PROGRAM_RUNTIME_NOT_INSTALLED\""), response.body());
            assertTrue(response.body().contains("The artifact source is not at fault"), response.body());
        }
    }

    /**
     * The half that keeps every other half honest.
     *
     * <p>A conflicting artifact state remains a conflict. The two narrow failure classifications do
     * not reclassify this already-correct status.</p>
     */
    @Test
    void aConflictingArtifactStateIsStillAConflict() throws Exception {
        try (var fixture = new Fixture(failingRuntime(new IllegalStateException("already validating")))) {
            String id = fixture.createArtifact();
            var response = fixture.post("/" + id + "/validate");

            assertEquals(409, response.statusCode(),
                    "this route's 4xx codes must keep their meanings exactly");
            assertTrue(response.body().contains("\"contract\":\"ravenroot.error/1\""), response.body());
            assertFalse(response.body().contains("\"outcome\""),
                    "a failure that is not a validation outcome must never be dressed as one");
        }
    }

    /**
     * A sandbox that times out after launch must produce a deployment-capacity error.
     *
     * <p><b>Measurement.</b> With the budget at 100 ms, classifying a cold-worker deadline as a
     * conflict answers <b>409</b> with a body identical in every
     * diagnostic field to the one {@link #aConflictingArtifactStateIsStillAConflict} produces. The
     * only difference between the two responses was {@code correlationId}, which differs between any
     * two requests and carries nothing about the cause. A timeout was therefore not merely
     * unhelpfully labelled — it was <em>indistinguishable</em> from a different failure, while naming
     * a cause that was false: the request conflicted with no state at all.</p>
     */
    @Test
    void aSandboxDeadlineNamesTheTimeoutRatherThanTheStateOrTheRequest() throws Exception {
        try (var fixture = new Fixture(failingRuntime(new ProgramDeadlineExceededException(
                "after_launch", Duration.ofMillis(100), Duration.ofMillis(103))))) {
            String id = fixture.createArtifact();
            var response = fixture.post("/" + id + "/validate");

            assertEquals(504, response.statusCode(),
                    "nothing about the request was wrong, so 400 was false; and it conflicts with no "
                            + "state, so 409 was false too. What is left is a fact about one run's "
                            + "elapsed time against a configured budget, not about a capability this "
                            + "deployment lacks, and 504 is the status for that");
            assertTrue(response.body().contains("\"code\":\"PROGRAM_EXECUTION_TIMEOUT\""), response.body());
            assertTrue(response.body().contains("The artifact source is not at fault"),
                    "the sentence that stops the reader going back to their own code, was: " + response.body());
            assertFalse(response.body().contains("rejected as invalid"), response.body());
            assertFalse(response.body().contains("conflicts with the current state"),
                    "the 409 was the worse of the two failures precisely because it NAMES a cause, so "
                            + "a reader believes it instead of suspecting the classification, was: "
                            + response.body());
            assertFalse(response.body().contains("after_launch"),
                    "the stage is an internal token for the server log, like the launcher path");
            assertFalse(response.body().contains("103"),
                    "the elapsed wait describes this deployment's load, not the caller's request");
        }
    }

    /**
     * The three causes are each distinguishable in one place, so collapsing any two fails here rather
     * than escaping into a deployment diagnosis.
     *
     * <p>They are asserted together on purpose. Each of the three has at some point been answered
     * with another's sentence, and a suite with three separate tests still passes when two of them
     * are made to agree — only comparing the answers catches that.</p>
     */
    @Test
    void theThreeCausesAreEachAnsweredDistinctly() throws Exception {
        int sourceRejected;
        int sandboxUnavailable;
        int deadline;
        String rejectedBody;
        String unavailableBody;
        String deadlineBody;

        try (var fixture = new Fixture(rejectingRuntime())) {
            var response = fixture.post("/" + fixture.createArtifact() + "/validate");
            sourceRejected = response.statusCode();
            rejectedBody = response.body();
        }
        try (var fixture = new Fixture(failingRuntime(new ProgramRuntimeUnavailableException(
                ProgramRuntimeUnavailableException.Reason.SANDBOX_UNAVAILABLE, "SANDBOX_LAUNCHER_MISSING")))) {
            var response = fixture.post("/" + fixture.createArtifact() + "/validate");
            sandboxUnavailable = response.statusCode();
            unavailableBody = response.body();
        }
        try (var fixture = new Fixture(failingRuntime(new ProgramDeadlineExceededException(
                "diagnostics", Duration.ofMillis(100), Duration.ofMillis(140))))) {
            var response = fixture.post("/" + fixture.createArtifact() + "/validate");
            deadline = response.statusCode();
            deadlineBody = response.body();
        }

        assertEquals(200, sourceRejected, "the author's source is a result, not an error: " + rejectedBody);
        assertEquals(501, sandboxUnavailable, unavailableBody);
        assertEquals(504, deadline, deadlineBody);
        assertTrue(rejectedBody.contains("\"outcome\":\"rejected\""), rejectedBody);
        assertTrue(unavailableBody.contains("\"code\":\"PROGRAM_SANDBOX_UNAVAILABLE\""), unavailableBody);
        assertTrue(deadlineBody.contains("\"code\":\"PROGRAM_EXECUTION_TIMEOUT\""), deadlineBody);
    }

    /**
     * The default branch does not accuse the author by construction.
     *
     * <p>Before this, every exception the classification did not name became {@code INVALID_REQUEST}
     * — "the request was rejected as invalid" — so each newly discovered cause arrived as an
     * accusation of the author's source until someone noticed and named it. That is three false
     * diagnoses from one branch, which is a property of the default rather
     * than bad luck.</p>
     *
     * <p>A raw {@code TimeoutException} is used as the probe deliberately: it is the exact shape the
     * adapter used to let escape, so this test also pins the behaviour for any third-party {@code
     * ProgramRuntime} that still emits one. The adapter in this repository no longer does — see
     * {@code GraalVmProgramRuntimeDeadlineClassificationTest} — but the classification must not
     * depend on that, because the interface is implementable.</p>
     */
    @Test
    void anUnclassifiedFailureIsAnInternalErrorRatherThanAnAccusation() throws Exception {
        try (var fixture = new Fixture(failingRuntime(new TimeoutException()))) {
            String id = fixture.createArtifact();
            var response = fixture.post("/" + id + "/validate");

            assertEquals(500, response.statusCode(),
                    "a cause the server cannot classify is the server's problem to explain, not "
                            + "evidence that the caller's request was malformed. 500 errs toward the "
                            + "harmless reading; 400 errs toward the one that costs an author an hour "
                            + "in their own source");
            assertTrue(response.body().contains("\"code\":\"INTERNAL_ERROR\""), response.body());
            assertFalse(response.body().contains("rejected as invalid"), response.body());
        }
    }

    /**
     * The half that keeps the inversion honest, and the demonstration it requires: inverting the
     * default is only safe if no legitimate "the source really is invalid" cause was relying on it.
     *
     * <p>The author-attributable causes are few and nameable, and both are named explicitly by the
     * classification rather than left to a default: {@code ProgramSourceRejectedException} (answered
     * as a 200 outcome by validate, and 400 wherever it is not) and {@code IllegalArgumentException},
     * which is what the adapter raises for a worker refusal and for a malformed request. This test is
     * the second of those; {@link #aSourceThatDoesNotCompileAnswersAnOutcomeNamingTheCauseAndItsLine}
     * is the first.</p>
     */
    @Test
    void agenuinelyInvalidRequestIsStillInvalid() throws Exception {
        try (var fixture = new Fixture(failingRuntime(new IllegalArgumentException("malformed payload")))) {
            String id = fixture.createArtifact();
            var response = fixture.post("/" + id + "/validate");

            assertEquals(400, response.statusCode(),
                    "inverting the default must not stop the server saying 'invalid' when the request "
                            + "genuinely was: that would trade one false sentence for another");
            assertTrue(response.body().contains("\"code\":\"INVALID_REQUEST\""), response.body());
        }
    }

    /** The sibling operations are untouched: only validate reports an outcome. */
    @Test
    void anUnknownOperationIsStillARouteError() throws Exception {
        try (var fixture = new Fixture(acceptingRuntime())) {
            String id = fixture.createArtifact();
            var response = fixture.post("/" + id + "/vali");

            assertEquals(404, response.statusCode());
            assertTrue(response.body().contains("UNKNOWN_ARTIFACT_OPERATION"), response.body());
        }
    }

    // ------------------------------------------------------------------------------------ fixtures

    private static ProgramRuntime rejectingRuntime() {
        return failingRuntime(new ProgramSourceRejectedException(MEASURED_PYTHON_DIAGNOSTIC, 2, 1));
    }

    /**
     * The fixture accepts {@code Throwable}. A raw {@code TimeoutException} is checked, and it is
     * exactly what the adapter can let escape, so a
     * fixture that could not express it could not reproduce the defect it is here to hold shut.
     */
    private static ProgramRuntime failingRuntime(Throwable failure) {
        return new StubRuntime() {
            @Override
            public CompletionStage<Void> validate(GeneratedArtifact artifact) {
                return CompletableFuture.failedFuture(failure);
            }
        };
    }

    private static ProgramRuntime acceptingRuntime() {
        return new StubRuntime();
    }

    private static class StubRuntime implements ProgramRuntime {
        @Override
        public String id() {
            return "test-program-runtime";
        }

        @Override
        public CompletionStage<Void> validate(GeneratedArtifact artifact) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Object> test(GeneratedArtifact artifact, ProgramRequest request) {
            return CompletableFuture.completedFuture(Map.of());
        }

        @Override
        public CompletionStage<Object> execute(ProgramAdmission admission, ProgramRequest request) {
            return CompletableFuture.completedFuture(request.payload());
        }
    }

    /** A started server with one program runtime and a developer principal, closed with the test. */
    private static final class Fixture implements AutoCloseable {
        private final PekkoExecutionEngine engine;
        private final RavenrootServer server;
        private final HttpClient client = HttpClient.newHttpClient();
        private final String base;

        Fixture(ProgramRuntime runtime) throws Exception {
            engine = new PekkoExecutionEngine("ravenroot-validate-outcome-test");
            var environment = new BehaviorEnvironment(new ModelProviderRegistry(), new AgentRuntimeRegistry(),
                    new InMemoryArtifactRegistry(), runtime, ignored -> Optional.empty(),
                    ignored -> new ToolDecision(ToolDecision.Disposition.ALLOW, "test", ""),
                    OutboundHttpPolicy.disabled());
            server = new RavenrootServer(
                    new DefaultRavenrootApplication(engine, new ExecutionMonitor(), environment),
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, developer());
            server.start();
            base = "http://localhost:" + server.port() + "/v1/program-artifacts";
        }

        String createArtifact() throws Exception {
            var created = client.send(HttpRequest.newBuilder(URI.create(base + "?language=python&name=echo"))
                            .header("Authorization", "Bearer creator")
                            .POST(HttpRequest.BodyPublishers.ofString("def handler(request):\nreturn 1\nhandler"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, created.statusCode(), created.body());
            return created.body().split("\"id\":\"")[1].split("\"")[0];
        }

        HttpResponse<String> post(String suffix) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create(base + suffix))
                            .header("Authorization", "Bearer creator")
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> get(String suffix) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create(base + suffix))
                            .header("Authorization", "Bearer creator").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public void close() throws Exception {
            server.close();
            engine.close();
        }
    }

    private static RequestAuthenticator developer() {
        return headers -> new AuthenticatedPrincipal("alice", AuthenticatedPrincipal.Type.USER,
                "issuer", "tenant-a", Set.of(Role.DEVELOPER),
                Set.of("ravenroot.artifact.read", "ravenroot.artifact.manage"));
    }
}
