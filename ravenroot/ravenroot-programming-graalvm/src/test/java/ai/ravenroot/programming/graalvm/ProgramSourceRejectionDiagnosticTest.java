package ai.ravenroot.programming.graalvm;

import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramSourceRejectedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A source the runtime cannot compile reaches the caller as a reason, and everything else does
 * not become one.
 *
 * <h2>The recorded worker is real, and that is load-bearing here</h2>
 * <p>Every case below drives {@link RealWorkerRun}, so the diagnostic asserted on is GraalPy's and
 * Graal.js's own text rather than something this test wrote down and then found again. A fixture
 * response would make the central claim — "the author is told what the compiler said" — unfalsifiable,
 * which is the vacuity mistake {@code RealWorkerRun}'s Javadoc records. The bytes are then replayed
 * through {@link FakeSupervisor} so that no assertion depends on how fast a child JVM started.</p>
 *
 * <h2>Measured before the change</h2>
 * <p>Against the unmodified adapter the first case produced
 * {@code IllegalArgumentException: PolyglotException: IndentationError: expected an indented block
 * after function definition on line 1 (…, line 2)} — the text existed at this layer already and the
 * HTTP handler above discarded it. What did not exist was any way for that handler to tell this
 * refusal apart from a sandbox that never started, which is why the classification is a bit on the
 * wire and not a judgement about an exception type.</p>
 */
class ProgramSourceRejectionDiagnosticTest {

    private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");

    /**
     * A Python handler whose body is not indented.
     *
     * <p>The assertions make "the author receives the reason, not 'rejected as invalid'" concrete:
     * the exception is the typed rejection, its
     * text names {@code IndentationError}, and it carries the line the runtime placed it at rather
     * than leaving the author to find it.</p>
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void anUnindentedPythonBodyReachesTheCallerAsTheCompilersOwnReasonWithItsLine() throws Exception {
        var rejected = rejectionFor("python", "def handler(request):\nreturn {'ok': True}\nhandler");

        assertTrue(rejected.diagnostic().startsWith("IndentationError:"),
                "the author must be told what the compiler said, and told it first -- the adapter's "
                        + "own PolyglotException wrapper is plumbing, not part of the diagnosis. was: "
                        + rejected.diagnostic());
        assertTrue(rejected.diagnostic().contains("expected an indented block"),
                "the reason itself must survive, not merely the error class. was: " + rejected.diagnostic());
        assertFalse(rejected.diagnostic().contains("rejected as invalid"),
                "the generic refusal must not reappear. was: " + rejected.diagnostic());
        assertEquals(2, rejected.line(),
                "GraalPy places this refusal at the line where the indented block was expected, and "
                        + "the caller must receive that rather than reconstruct it from prose");
        assertEquals(1, rejected.column(),
                "and it supplies a column too. An earlier draft of this change documented GraalPy as "
                        + "supplying no column; measuring it said otherwise, so the number is pinned "
                        + "here instead of asserted in a comment");
    }

    /**
     * A source that parses but is not a handler is the author's mistake too, and is reported the same
     * way — with no line, because the runtime supplies none for it.
     *
     * <p>The zero is asserted rather than tolerated. "The runtime did not say where" and "the runtime
     * said line 1" are different facts, and a contract that defaulted the first to the second would
     * point an editor's cursor at a line nobody chose.</p>
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void aPythonSourceThatIsNotCallableIsAlsoASourceRejectionAndCarriesNoInventedLine() throws Exception {
        var rejected = rejectionFor("python", "41 + 1");

        assertTrue(rejected.diagnostic().toLowerCase(java.util.Locale.ROOT).contains("callable"),
                "was: " + rejected.diagnostic());
        assertEquals(0, rejected.line(), "the runtime supplied no position for this one");
        assertEquals(0, rejected.column(), "the runtime supplied no position for this one");
    }

    /**
     * JavaScript, the other shipped language, and the one whose diagnostic is multi-line.
     *
     * <p>Graal.js appends a caret display — the offending source line, a newline, and a caret — to its
     * syntax errors. That trailing display is the only part of either language's diagnostic that
     * echoes raw source, and {@link ProgramSourceRejectedException#delimit(String)} drops everything
     * after the first line. This test is what proves the drop happens against real output rather than
     * against a string the test invented, and that what remains still names the cause and the
     * position.</p>
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void aJavaScriptSyntaxErrorArrivesOnOneLineWithItsPosition() throws Exception {
        var rejected = rejectionFor("javascript", "function (request) { return { ; }");

        assertTrue(rejected.diagnostic().startsWith("SyntaxError:"), "was: " + rejected.diagnostic());
        assertFalse(rejected.diagnostic().contains("\n") || rejected.diagnostic().contains("\r"),
                "the caret display must not survive into a single-line field. was: " + rejected.diagnostic());
        // The coordinate must point at the author's OWN source, and the assertion says that rather
        // than repeating a number: the adapter wraps JavaScript in "(...)" before parsing it, so the
        // column the language reports on line 1 is one greater than the author's. Measured before the
        // correction, this delivered 32 while the ';' sits at column 31 of the source below -- an
        // off-by-one on every JavaScript syntax error, which is worse than no column at all because
        // it looks right. Indexing back into the source is what makes that unfakeable.
        String source = "function (request) { return { ; }";
        assertEquals(1, rejected.line(), "was: " + rejected.line());
        // The bound is checked before indexing, and the message is built lazily. Composed eagerly it
        // would itself call charAt(column - 1), so a regression to column 0 would kill this test with
        // StringIndexOutOfBoundsException instead of the sentence written to explain the failure --
        // an assertion whose diagnostic breaks exactly when it is needed.
        int column = rejected.column();
        assertTrue(column >= 1 && column <= source.length(),
                () -> "the column must be a position in the author's source, was: " + column);
        assertEquals(';', source.charAt(column - 1),
                () -> "the column must land on the offending character of the AUTHOR'S source, but "
                        + "landed on '" + source.charAt(column - 1) + "' at column " + column);
        assertEquals(31, column);
    }

    /**
     * The other half of the claim, and the one that keeps the first honest.
     *
     * <p>A worker refusal that is NOT about the source keeps the type and the text it always had. If
     * this went the other way, a sandbox that could not start would be reported to an author as "your
     * source does not compile" — a false cause, which is worse than a generic refusal. The
     * case chosen is an oversized result, because it is raised by the worker's own marshalling after
     * the source compiled and ran, so it is unambiguously not the author's syntax.</p>
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void aFailureThatIsNotTheSourceIsNotReportedAsASourceRejection() throws Exception {
        GeneratedArtifact artifact = artifact("python",
                "def handler(request):\n    return {'blob': 'x' * (8 * 1024 * 1024)}\nhandler",
                ArtifactState.ACTIVE);
        byte[] recorded = RealWorkerRun.capture(ProgramWireProtocol.Mode.EXECUTE, artifact,
                new ai.ravenroot.api.programming.ProgramRequest(java.util.UUID.randomUUID(), "node-1",
                        Map.of(), Map.of()));

        var refusal = assertThrows(ProgramWireProtocol.ProgramWorkerException.class,
                () -> ProgramWireProtocol.readResponse(new ByteArrayInputStream(recorded)));
        assertFalse(refusal.sourceRejected(),
                "the worker compiled and ran this source; the refusal is about the result it produced");
        assertTrue(refusal.getMessage().contains("Value exceeds worker protocol limit"),
                "was: " + refusal.getMessage());
    }

    /**
     * The token the compiler named survives the delimiting, on the real engine.
     *
     * <p>This is the case an earlier version of this change destroyed and justified in a comment.
     * {@code delimit} replaced {@code <}, {@code >} and {@code &} with {@code ?} on the written
     * grounds that a compiler's prose does not contain them; driving the real engine falsified that
     * engine, because Graal.js names the offending token <b>inside the first line</b>. The author was
     * shown {@code found =?} for a misplaced arrow and {@code found ?} for a stray {@code <} or
     * {@code &} — the substitution landing on the one character the diagnostic exists to identify,
     * inside the diagnostic whose purpose is to preserve the compiler's reason.</p>
     *
     * <p>{@code ProgramSourceRejectedExceptionTest} pins the same three cases against
     * {@code delimit} directly. This one is what makes them evidence rather than fixtures: the
     * strings there were measured here, and if Graal.js ever changes how it names a token, this test
     * is the one that notices.</p>
     */
    @Test
    @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void theOffendingTokenSurvivesEvenWhenItIsAMarkupCharacter() throws Exception {
        assertTrue(rejectionFor("javascript", "function (request) => { return 1; }")
                        .diagnostic().endsWith("Expected { but found =>"),
                "a misplaced arrow must still read as an arrow");
        assertTrue(rejectionFor("javascript", "function (request) { return <div>hi</div>; }")
                        .diagnostic().endsWith("Expected an operand but found <"),
                "a stray angle bracket must still read as an angle bracket");
        assertTrue(rejectionFor("javascript", "function (request) { if (a &&& b) return 1; }")
                        .diagnostic().endsWith("Expected an operand but found &"),
                "a stray ampersand must still read as an ampersand");
    }

    /**
     * The deployment-condition case at the layer that produces it: an unconfigured deployment fails before the
     * source is read, and says which of the two it is.
     *
     * <p>With {@code RAVENROOT_GRAAL_SANDBOX_SUPERVISOR} unset, the constructor installs
     * {@code MissingLauncher} and the capability check throws. The two assertions are the whole
     * point: the failure is <b>not</b> a source rejection (the source has not been compiled, or even
     * sent), and it carries the operator's own token so the server-side record still names which
     * check failed.</p>
     */
    @Test
    void anUnconfiguredSandboxFailsAsADeploymentConditionAndNotAsASourceRejection() throws Exception {
        var runtime = new GraalVmProgramRuntime(JAVA, Duration.ofSeconds(5), 64);
        GeneratedArtifact wellFormed = artifact("python",
                "def handler(request):\n    return 1\nhandler", ArtifactState.GENERATED);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> runtime.validate(wellFormed).toCompletableFuture().get());

        assertFalse(failure.getCause() instanceof ProgramSourceRejectedException,
                "this source compiles; nothing has looked at it. Reporting it as a source rejection "
                        + "is the false cause that cost the reporter an hour of reading correct code.");
        assertTrue(failure.getCause() instanceof ai.ravenroot.api.programming.ProgramRuntimeUnavailableException,
                "was: " + failure.getCause());
        var unavailable = (ai.ravenroot.api.programming.ProgramRuntimeUnavailableException) failure.getCause();
        assertEquals(ai.ravenroot.api.programming.ProgramRuntimeUnavailableException.Reason.SANDBOX_UNAVAILABLE,
                unavailable.reason());
        assertTrue(unavailable.getMessage().contains("SANDBOX_LAUNCHER_MISSING"),
                "the operator's own token must survive to the server-side record, was: "
                        + unavailable.getMessage());
    }

    /** Drives the real worker once, replays its bytes, and returns the typed rejection. */
    private static ProgramSourceRejectedException rejectionFor(String language, String source) throws Exception {
        GeneratedArtifact artifact = artifact(language, source, ArtifactState.GENERATED);
        byte[] recorded = RealWorkerRun.capture(ProgramWireProtocol.Mode.VALIDATE, artifact, null);

        var supervisor = new FakeSupervisor();
        supervisor.response = recorded;
        var runtime = new GraalVmProgramRuntime(supervisor, policy());
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> runtime.validate(artifact).toCompletableFuture().get());
        assertTrue(failure.getCause() instanceof ProgramSourceRejectedException,
                "a source the runtime could not compile must arrive as the typed rejection so the "
                        + "HTTP layer can report it as a result rather than as a malformed request, was: "
                        + failure.getCause());
        return (ProgramSourceRejectedException) failure.getCause();
    }

    private static SandboxPolicy policy() {
        Duration timeout = Duration.ofSeconds(30);
        return new SandboxPolicy(timeout, Math.toIntExact(timeout.toMillis()), 64, 32, 256, 64,
                2 * 1024 * 1024, Path.of(System.getProperty("java.class.path")), "worker-test-id",
                JAVA, "jre-test-id");
    }

    private static GeneratedArtifact artifact(String language, String source, ArtifactState state)
            throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(source.getBytes(StandardCharsets.UTF_8)));
        Instant now = Instant.now();
        return new GeneratedArtifact("rejection-artifact", language, hash, source, state, 1, now, now, Map.of());
    }
}
