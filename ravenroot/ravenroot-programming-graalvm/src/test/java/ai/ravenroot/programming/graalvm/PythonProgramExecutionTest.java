package ai.ravenroot.programming.graalvm;

import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramRequest;
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
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Python artifacts are executed rather than refused, and the
 * JavaScript path is unchanged while that happens.
 *
 * <p><b>Two independent language gates are covered.</b> Against the previous adapter,
 * {@link #theRealWorkerExecutesAPythonArtifact} and
 * {@link #theRuntimeExecutesAPythonArtifactEndToEnd} failed on the worker's and the runtime's two
 * separate refusals ({@code Only JavaScript artifacts are enabled} and {@code Unsupported program
 * language: python}) -- two doors, both of which had to open, which is why both are asserted rather
 * than only the outer one. {@link #theRuntimeIdentifierNoLongerClaimsJavaScriptOnly} failed on
 * {@code graalvm-js-worker}.
 *
 * <p><b>Why the real worker runs outside the sandbox deadline here.</b> Same reason as FIX-29, and
 * more sharply: a Python context costs about 1.8 s to reach first execution on an idle
 * developer machine against a 5 s default deadline (measured; see
 * {@code docs/architecture/python-programmable-nodes.md}), so a live spawn under the clock would
 * make these tests a measurement of the host's scheduler. {@link RealWorkerRun} records the genuine
 * bytes and they are replayed deterministically -- this class never authors a response envelope.
 *
 * <p><b>The input shape is language-specific and was measured, not assumed.</b> A JavaScript
 * artifact reads {@code request.payload}; so does a Python one, because the worker hands the guest a
 * {@code ProxyObject}, which GraalPy surfaces as a {@code polyglot.ForeignObject} supporting
 * ATTRIBUTE access. Subscript access ({@code request['payload']}) raises {@code TypeError: object is
 * not subscriptable} and is therefore not the contract, which is the kind of thing that reads as
 * obvious in one language and is simply false in the other.
 */
class PythonProgramExecutionTest {
    private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");

    /**
     * A Python artifact evaluates to a callable, exactly as a JavaScript one evaluates to a
     * function. Note that it is NOT parenthesis-wrapped the way the JavaScript source is: wrapping
     * is what makes a bare JavaScript function declaration an expression, and applying it to Python
     * would turn every multi-statement artifact into a syntax error.
     */
    private static final String PYTHON_HANDLER =
            "def handler(request):\n"
            + "    return {'greeting': 'Hello ' + request.payload.name, 'count': request.payload.times * 2}\n"
            + "handler";

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void theRealWorkerExecutesAPythonArtifact() throws Exception {
        GeneratedArtifact artifact = artifact(PYTHON_HANDLER, "python", ArtifactState.ACTIVE);
        ProgramRequest request = request();

        byte[] bytes = RealWorkerRun.capture(ProgramWireProtocol.Mode.EXECUTE, artifact, request);
        Object result = ProgramWireProtocol.readResponse(new ByteArrayInputStream(bytes));

        // count == 2L, not 2.0: the Python int went through fromGuest's fitsInLong branch, the same
        // one the JavaScript lifecycle test pins. A Python dict is the interesting half -- it reports
        // BOTH hasMembers() and hasHashEntries(), and its member keys are its methods (pop, keys,
        // values, ...), not its entries. A result marshaller that checked members first would return
        // the dict's method table here, so this assertion is what distinguishes a correct Python
        // result from a plausible-looking wrong one.
        assertEquals(Map.of("greeting", "Hello Ravenroot", "count", 2L), result);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void theRuntimeExecutesAPythonArtifactEndToEnd() throws Exception {
        GeneratedArtifact generated = artifact(PYTHON_HANDLER, "python", ArtifactState.GENERATED);
        GeneratedArtifact active = artifact(PYTHON_HANDLER, "python", ArtifactState.ACTIVE);
        ProgramRequest request = request();

        byte[] validateBytes = RealWorkerRun.capture(ProgramWireProtocol.Mode.VALIDATE, generated, null);
        byte[] executeBytes = RealWorkerRun.capture(ProgramWireProtocol.Mode.EXECUTE, active, request);

        // The VALIDATE recording is a genuine success, not a refusal: readResponse throws
        // ProgramWorkerException when the engine rejected the source, so this discriminates before
        // anything is replayed.
        assertNull(ProgramWireProtocol.readResponse(new ByteArrayInputStream(validateBytes)));

        var supervisor = new FakeSupervisor();
        var runtime = runtime(supervisor, Duration.ofSeconds(5));
        supervisor.response = validateBytes;
        runtime.validate(generated).toCompletableFuture().get();
        supervisor.response = executeBytes;
        Object executed = runtime.execute(TestAdmission.of(active), request).toCompletableFuture().get();

        assertEquals(Map.of("greeting", "Hello Ravenroot", "count", 2L), executed);
        assertEquals(0, supervisor.realSubprocessSpawns, "the two legitimate worker runs are the "
                + "un-raced RealWorkerRun.capture calls above; a live spawn under the sandbox "
                + "deadline would make this test a measurement of the host, not of the adapter");
    }

    /**
     * The identifier is not decoration: {@code ProgramNodeBehaviorFactory} writes it into the
     * execution's own attributes as {@code program.runtime}, so it is read by whoever inspects a run
     * afterwards. A worker that executes Python while still calling itself the JavaScript worker
     * puts a false statement into that record.
     *
     * <p>Deliberately asserted as "no longer the old value, and no longer says js" rather than
     * pinned to one new string: WHICH string it becomes is an documented decision (the documented contract),
     * and a test that pins the answer would have to be edited by whoever implements the decision,
     * which is exactly how a pinned test stops being evidence.
     */
    @Test
    void theRuntimeIdentifierNoLongerClaimsJavaScriptOnly() {
        String id = new GraalVmProgramRuntime(new FakeSupervisor(), policy(Duration.ofSeconds(1))).id();
        assertNotEquals("graalvm-js-worker", id);
        assertTrue(id != null && !id.isBlank(), "the identifier is a registry key and an attribute "
                + "value; it can change, but it cannot become empty");
        assertTrue(!id.contains("-js-") && !id.endsWith("-js"), "the runtime executes Python as well "
                + "as JavaScript, so an identifier naming one of the two is now a false statement in "
                + "every execution record that carries it, was: " + id);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void theRealWorkerRefusesAPythonArtifactThatIsNotCallable() throws Exception {
        GeneratedArtifact artifact = artifact("41 + 1", "python", ArtifactState.GENERATED);

        byte[] bytes = RealWorkerRun.capture(ProgramWireProtocol.Mode.VALIDATE, artifact, null);

        var refusal = assertThrows(ProgramWireProtocol.ProgramWorkerException.class,
                () -> ProgramWireProtocol.readResponse(new ByteArrayInputStream(bytes)));
        assertTrue(refusal.getMessage().toLowerCase(java.util.Locale.ROOT).contains("callable")
                        || refusal.getMessage().toLowerCase(java.util.Locale.ROOT).contains("function"),
                "a Python artifact that evaluates to a number is not a handler and must be refused "
                        + "by the engine for that reason, not accepted and failed later, was: "
                        + refusal.getMessage());
    }

    /** A language that is neither of the two enabled ones is still refused, at both doors. */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void anUnknownLanguageIsStillRefused() throws Exception {
        GeneratedArtifact artifact = artifact("SELECT 1", "ruby", ArtifactState.GENERATED);

        var runtime = runtime(new FakeSupervisor(), Duration.ofSeconds(5));
        ExecutionException outer = assertThrows(ExecutionException.class,
                () -> runtime.validate(artifact).toCompletableFuture().get());
        assertTrue(outer.getCause().getMessage().contains("Unsupported program language: ruby"),
                outer.getCause().getMessage());

        byte[] bytes = RealWorkerRun.capture(ProgramWireProtocol.Mode.VALIDATE, artifact, null);
        var inner = assertThrows(ProgramWireProtocol.ProgramWorkerException.class,
                () -> ProgramWireProtocol.readResponse(new ByteArrayInputStream(bytes)));
        assertTrue(inner.getMessage().contains("ruby") || inner.getMessage().contains("Unsupported"),
                "the worker is the second door and must refuse independently of the runtime, was: "
                        + inner.getMessage());
    }

    /**
     * Compatibility control. JavaScript keeps the members-first result shape it has always had. This is not
     * redundant with the existing JavaScript tests: enabling Python required the result marshaller
     * to learn about hash entries, and the shortest way to do that -- checking hash entries before
     * members for every language -- silently changes what JavaScript returns for a {@code Map}
     * (measured: {@code new Map([['a',1]])} yields {@code {}} today, and would start yielding
     * {@code {a:1}}). That is an observable change to JavaScript result compatibility, so the
     * marshaller is language-gated and this test is what holds the gate shut.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void javascriptKeepsItsExistingResultShapeForAMap() throws Exception {
        GeneratedArtifact artifact = artifact(
                "function (request) { return new Map([['a', 1]]); }", "javascript", ArtifactState.ACTIVE);

        byte[] bytes = RealWorkerRun.capture(ProgramWireProtocol.Mode.EXECUTE, artifact, request());

        assertEquals(Map.of(), ProgramWireProtocol.readResponse(new ByteArrayInputStream(bytes)),
                "a JavaScript Map has never round-tripped its entries through this adapter; it "
                        + "returns an empty object. Enabling Python must not change that, because "
                        + "any caller relying on today's behaviour would see a different value");
    }

    private static ProgramRequest request() {
        return new ProgramRequest(UUID.randomUUID(), "node-1",
                Map.of("name", "Ravenroot", "times", 1), Map.of());
    }

    private static GraalVmProgramRuntime runtime(FakeSupervisor supervisor, Duration timeout) {
        return new GraalVmProgramRuntime(supervisor, policy(timeout));
    }

    private static SandboxPolicy policy(Duration timeout) {
        return new SandboxPolicy(timeout, Math.toIntExact(timeout.toMillis()), 64, 32, 256, 64,
                2 * 1024 * 1024, Path.of(System.getProperty("java.class.path")), "worker-test-id",
                JAVA, "jre-test-id");
    }

    private static GeneratedArtifact artifact(String source, String language, ArtifactState state) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
            Instant now = Instant.now();
            return new GeneratedArtifact("test-artifact", language, hash, source, state, 1, now, now, Map.of());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
