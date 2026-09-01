package ai.ravenroot.programming.graalvm;

import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramDeadlineExceededException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The adapter side of "a deadline is not a state conflict and not an invalid source".
 *
 * <h2>What this fixes, measured before the change</h2>
 * <p>Measured against {@code POST /v1/program-artifacts/{id}/validate} with the
 * budget at 100 ms. A cold-worker deadline answered <b>409 {@code the request conflicts with the
 * current state of the resource}</b>, and a genuine state conflict answered a body identical in
 * every diagnostic field — {@code contract}, {@code code}, {@code message}, {@code error} — differing
 * only in {@code correlationId}, which differs between any two requests and says nothing about the
 * cause. Separately, a raw {@link java.util.concurrent.TimeoutException} escaping the two bounded
 * waits answered <b>400 {@code the request was rejected as invalid}</b>.</p>
 *
 * <p>The classification cannot be fixed at the server alone: the server can only read the type it is
 * given, and both shapes the adapter produced were types that already meant something else. So the
 * adapter has to name the condition, which is what this suite holds.</p>
 *
 * <p>The route-level half — that the named condition becomes 504 rather than 400 or 409 — is
 * {@code ProgramArtifactValidationRouteTest}. Both halves are needed: this one alone would pass if
 * the server dropped the new type on the floor.</p>
 */
class GraalVmProgramRuntimeDeadlineClassificationTest {

    private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");

    /**
     * The {@code DEADLINE_EXCEEDED} outcome from {@code session.await(...)}.
     *
     * <p>Not "the supervisor's own verdict", which is what this line used to call it: the production
     * launcher derives that outcome from {@code !process.waitFor(remaining)} alone, so it establishes
     * only that the child had not exited in time and says nothing about whether the worker ran.</p>
     *
     * <p>{@code SandboxOutcome.DEADLINE_EXCEEDED} is used rather than a real clock race for the
     * reason {@code GraalVmProgramRuntimeLoadSensitivityTest} already gives for the same fixture: it
     * drives {@code invokeSupervisor} down the identical path a genuine expiry takes, deterministically,
     * with no dependence on how loaded the machine running the suite happens to be.</p>
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aSupervisorDeadlineVerdictIsTypedAsADeadlineAndNotAsAStateConflict() throws Exception {
        var supervisor = new FakeSupervisor();
        supervisor.outcome = SandboxSupervisorLauncher.SandboxOutcome.DEADLINE_EXCEEDED;
        var runtime = new GraalVmProgramRuntime(supervisor, policy(Duration.ofMillis(750)));

        var error = assertThrows(ExecutionException.class, () -> runtime
                .validate(artifact("() => 1", ArtifactState.GENERATED))
                .toCompletableFuture().get());

        var deadline = assertInstanceOf(ProgramDeadlineExceededException.class, error.getCause(),
                "the whole defect was that this arrived as a type the server already reads as "
                        + "something else; it must now arrive as the condition it actually is");
        assertEquals("sandbox_outcome", deadline.stage());
        assertEquals(Duration.ofMillis(750), deadline.budget(),
                "the budget an operator would have to raise must travel with the failure");

        // The load-bearing negative. IllegalStateException is what RavenrootServer.artifactFailureCode
        // maps to CONFLICT, so as long as the deadline is still one, no server-side change can tell
        // the two apart -- this is the assertion that would fail if someone "simplified" the new type
        // back into an IllegalStateException subclass.
        //
        // Written reflectively on purpose. As `deadline instanceof IllegalStateException` the compiler
        // REJECTS the line outright -- ProgramDeadlineExceededException is final, so the relationship
        // is provably impossible and the cast is a compile error. That is a stronger guarantee than
        // this assertion, but it is a guarantee that disappears silently the moment someone drops
        // `final` or changes the supertype, and the disappearance is exactly the regression worth
        // catching. isInstance defers the question to runtime so it survives that edit and fails.
        assertFalse(IllegalStateException.class.isInstance(deadline),
                "a deadline must not be an IllegalStateException: that is precisely the type the "
                        + "server reads as a state conflict, and it is what produced the 409");
    }

    /**
     * Escape site one of the two bounded waits:
     * {@code GraalVmProgramRuntime.java:368}, inside {@code writeRequestBounded}.
     *
     * <p><b>Why this test exists as a separate one.</b> The four mutants covering the supervisor's
     * verdict, the log line and the two server branches all leave these two {@code try/catch}
     * blocks untested: reverting
     * both to the pre-fix raw {@link java.util.concurrent.TimeoutException} kept the whole of
     * {@code ravenroot-application-api} plus the TCK plus this adapter green. A correction no test
     * can see is a correction that can be deleted in silence, which for a classification defect is
     * how the defect comes back.</p>
     *
     * <p>The stall, rather than a tiny budget, is what makes the block load-bearing — see
     * {@link FakeSupervisor#stallWorkerInput}. The assertion on the cause is the second half of the
     * same point: a {@code TimeoutException} <em>underneath</em> the typed exception is proof that
     * this is the raw shape being converted here, and not the deadline arriving from one of the
     * explicit {@code checkDeadline} calls that bracket this wait.</p>
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void aDeadlineOnTheBoundedRequestWriteIsTypedRatherThanEscapingAsARawTimeout() throws Exception {
        var supervisor = new FakeSupervisor();
        supervisor.stallWorkerInput = true;
        var runtime = new GraalVmProgramRuntime(supervisor, policy(Duration.ofMillis(600)));

        var error = assertThrows(ExecutionException.class, () -> runtime
                .validate(artifact("() => 1", ArtifactState.GENERATED))
                .toCompletableFuture().get());

        var deadline = assertInstanceOf(ProgramDeadlineExceededException.class, error.getCause(),
                "a raw TimeoutException here fell through every typed branch of "
                        + "RavenrootServer.artifactFailureCode and reached the author as 400 "
                        + "\"the request was rejected as invalid\"");
        assertEquals("write_request", deadline.stage(),
                "the stage is the operator's only way to tell this wait apart from the others");
        assertEquals(Duration.ofMillis(600), deadline.budget());
        assertInstanceOf(TimeoutException.class, deadline.getCause(),
                "the raw shape must be preserved as the cause: that is what proves this exception "
                        + "was produced by the catch on the bounded write and not by a deadline check");
        assertFalse(IllegalStateException.class.isInstance(deadline),
                "a deadline must not be the type the server reads as a state conflict");
    }

    /**
     * Escape site two of two: {@code GraalVmProgramRuntime.java:228}, the wait on worker diagnostics.
     *
     * <p>Reached deliberately on the far side of a <b>successful</b> run: the supervisor answers
     * {@code COMPLETED} with a well-formed envelope, the worker response is read, and only then does
     * the diagnostics wait run out. That ordering matters, because it is the one an operator finds
     * hardest to believe — the program did its work, and the request still fails — and because it is
     * the only way to reach this line without an earlier check firing first.</p>
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void aDeadlineOnTheWaitForWorkerDiagnosticsIsTypedRatherThanEscapingAsARawTimeout() throws Exception {
        var supervisor = new FakeSupervisor();
        supervisor.stallDiagnostics = true;
        // Plumbing, not evidence: it lets the run reach the diagnostics wait with no child JVM. See
        // FakeSupervisor.wellFormedValidateResponse for why that distinction is the legitimate one.
        supervisor.response = FakeSupervisor.wellFormedValidateResponse();
        var runtime = new GraalVmProgramRuntime(supervisor, policy(Duration.ofMillis(600)));

        var error = assertThrows(ExecutionException.class, () -> runtime
                .validate(artifact("() => 1", ArtifactState.GENERATED))
                .toCompletableFuture().get());

        var deadline = assertInstanceOf(ProgramDeadlineExceededException.class, error.getCause(),
                "the second bounded wait escaped the same way as the first");
        assertEquals("diagnostics", deadline.stage(),
                "\"diagnostics\" is what tells an operator the program finished and the collection of "
                        + "its output did not — a different remedy from a slow cold start");
        assertEquals(Duration.ofMillis(600), deadline.budget());
        assertInstanceOf(TimeoutException.class, deadline.getCause(),
                "same discriminant as the request-write case: the raw shape must be underneath");
        assertFalse(IllegalStateException.class.isInstance(deadline),
                "a deadline must not be the type the server reads as a state conflict");
    }

    /**
     * The operational requirement is that the reason reaches the operator, with the
     * numbers, at the moment the condition occurs.
     *
     * <p>The caller-facing message is a fixed literal owned by {@code ErrorCode}, so the budget and
     * the elapsed wait have no route to the response by design. This line is the only place they
     * exist, which is why its absence would be silent.</p>
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void theBudgetAndTheElapsedWaitAreWrittenToTheServerLog() throws Exception {
        var supervisor = new FakeSupervisor();
        supervisor.outcome = SandboxSupervisorLauncher.SandboxOutcome.DEADLINE_EXCEEDED;
        var runtime = new GraalVmProgramRuntime(supervisor, policy(Duration.ofMillis(750)));

        var captured = new ByteArrayOutputStream();
        PrintStream original = System.err;
        String log;
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            assertThrows(ExecutionException.class, () -> runtime
                    .validate(artifact("() => 1", ArtifactState.GENERATED))
                    .toCompletableFuture().get());
        } finally {
            System.setErr(original);
            log = captured.toString(StandardCharsets.UTF_8);
        }

        assertTrue(log.contains("\"event\":\"program_deadline_exceeded\""),
                "the operator's only copy of the reason, was: " + log);
        assertTrue(log.contains("\"stage\":\"sandbox_outcome\""),
                "which wait ran out is what tells a slow cold start from a slow response, was: " + log);
        assertTrue(log.contains("\"budgetMs\":750"),
                "the budget an operator would raise, was: " + log);
        assertTrue(log.contains("\"waitedMs\":"),
                "how long was actually waited, without which the budget alone decides nothing, was: " + log);
    }

    private static SandboxPolicy policy(Duration timeout) {
        return new SandboxPolicy(timeout, Math.toIntExact(timeout.toMillis()), 64, 32, 256, 64,
                2 * 1024 * 1024, Path.of(System.getProperty("java.class.path")), "worker-test-id",
                JAVA, "jre-test-id");
    }

    private static GeneratedArtifact artifact(String source, ArtifactState state) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(source.getBytes(StandardCharsets.UTF_8)));
        Instant now = Instant.now();
        return new GeneratedArtifact("test-artifact", "javascript", hash, source, state, 1, now, now,
                Map.of());
    }
}
