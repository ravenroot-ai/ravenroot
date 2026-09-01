package ai.ravenroot.programming.graalvm;

import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramRequest;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GraalVmProgramRuntimeTest {
    private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");

    /**
     * FIX-31, following FIX-27, FIX-29 and FIX-30 with the same shape: the temporal dependency is
     * REMOVED, not widened. What is different here is that it had
     * to be removed from TWO clocks, and a remedy that took away either one alone would have looked
     * like it worked while the test kept failing through the other.
     *
     * <p><b>Cause, for both waits.</b> The test drove {@code validate} + {@code test} +
     * {@code execute} with three real child-JVM spawns, and measured them twice over.
     * {@code GraalVmProgramRuntime#invoke} starts a virtual thread whose
     * {@code deadline = System.nanoTime() + policy.deadline().toNanos()} is established at that
     * thread's FIRST SCHEDULING, while the caller's {@code get(5, SECONDS)} started on the test
     * thread at the call. Two equal 5 s budgets begun milliseconds apart under scheduler jitter,
     * with {@code cleanup()}'s reaping latency charged to the inner one only. Whichever expired
     * first decided which exception surfaced, which is why two independent measurements of the same
     * defect reported different exceptions and looked like they contradicted each other.
     *
     * <p><b>Measured, 8x CPU oversubscription on 10 cores, 10 trials, before the fix: 9 failed.</b>
     * Eight through the caller's wait ({@code java.util.concurrent.TimeoutException}) and one
     * through the sandbox deadline ({@code IllegalStateException: SANDBOX_DEADLINE_EXCEEDED} out of
     * {@code invokeSupervisor}'s {@code session.await(remaining(deadline))}). Both doors, one
     * defect. {@code GraalVmProgramRuntimeLoadSensitivityTest} carries the permanent version of that
     * sweep.
     *
     * <p><b>The three displayed SANDBOX_DEADLINE_EXCEEDED lines represent one event, not one per real
     * spawn.</b> The surefire XML holds a single
     * {@code <error>} element; the string appears on three lines of console output because surefire
     * prints the exception line, the {@code Caused by:} line and the {@code [ERROR]} summary line.
     * A line count, not three events -- the test aborts at the first wait that gives way.
     *
     * <p><b>Fix: record and replay, exactly FIX-29's shape.</b> The real worker still runs, three
     * times, on the real engine, and the bytes asserted on are the ones it actually wrote -- this
     * test never authors a response envelope. What changed is that those runs happen in
     * {@link RealWorkerRun}, OUTSIDE any sandbox deadline, and their genuine output is then replayed
     * through the runtime deterministically. The caller's wait becomes an unbounded {@code get()}
     * under a method {@code @Timeout}, so a breach raises a self-describing JUnit timeout instead of
     * a {@code TimeoutException} that reads like the runtime misbehaved.
     *
     * <p><b>The 5 s sandbox deadline below is deliberately UNTOUCHED, and that is the evidence.</b>
     * Nothing was widened: both budgets stay exactly where they were and are instead emptied of
     * everything that can block. After the replay the whole path inside that deadline is
     * {@code ByteArrayInputStream}/{@code ByteArrayOutputStream} work. Independently confirmed on
     * the siblings under the identical 8x load (1-minute load average 90 to 232, worse than the
     * measured conditions): {@code
     * serializesExactImmutableVersionedPolicyWithoutEnvironment} replays through a <b>750 ms</b>
     * deadline, 10/10 green -- so the 5 s kept here is an untouched value the codebase already shows
     * to be far larger than needed once the spawn is gone, not a margin anyone chose.
     *
     * <p><b>What fails if a live spawn is put back is the counter, not the clock.</b>
     * {@code realSubprocessSpawns == 0} below is unconditional and load-independent.
     *
     * <p><b>@Timeout(240) is derived, not picked.</b> Three captures, each bounded by
     * {@link RealWorkerRun#CALLER_PATIENCE} (60 s for the whole call since FIX-31 gave it one shared
     * deadline), so 180 s of inner guard. The outer guard must exceed that or it would preempt the
     * inner, self-describing environment failure. It is a backstop against a hang in the replay
     * path, which is in-memory; no assertion depends on it and nothing here approaches it.
     */
    @Test @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void validatesTestsAndExecutesJavascriptThroughTestSupervisor() throws Exception {
        String source = "({ payload, attributes }) => ({ greeting: 'Hello ' + payload, count: attributes.count + 1 })";
        // One instance per invocation, shared by the capture and the replay, so each recording
        // provably belongs to the exact invocation it is later replayed for.
        var generated = artifact(source, ArtifactState.GENERATED);
        var validated = artifact(source, ArtifactState.VALIDATED);
        var active = artifact(source, ArtifactState.ACTIVE);
        var testRequest = request("Ravenroot");
        var executeRequest = request("Ravenroot");

        // The real worker runs here: three real child JVMs on the real GraalJS engine, none of them
        // under a sandbox deadline. These are the same three real runs this test always made.
        byte[] validateBytes = RealWorkerRun.capture(ProgramWireProtocol.Mode.VALIDATE, generated, null);
        byte[] testBytes = RealWorkerRun.capture(ProgramWireProtocol.Mode.TEST, validated, testRequest);
        byte[] executeBytes = RealWorkerRun.capture(ProgramWireProtocol.Mode.EXECUTE, active, executeRequest);

        // The VALIDATE recording is a genuine worker SUCCESS, not a refusal, asserted on the real
        // worker's own bytes before anything replays them. readResponse throws
        // ProgramWorkerException if the engine rejected the source, so this does discriminate.
        //
        // READ THIS BEFORE CONCLUDING IT PROVES MORE THAN IT DOES. For a valid artifact the real
        // worker's VALIDATE reply is writeSuccess(null), which is byte-identical to
        // FakeSupervisor.wellFormedValidateResponse(). So this assertion alone cannot tell a real
        // recording from the legitimate stub, and neither can the replay below. The validate
        // path's real-parsing coverage is therefore NOT carried by the happy path: it is carried by
        // theRealValidateWorkerRefusesAnArtifactThatIsNotAFunction, where the worker's verdict is
        // the only thing that can produce the expected bytes. Delete that test and this one silently
        // stops proving anything about the real engine.
        assertNull(ProgramWireProtocol.readResponse(new ByteArrayInputStream(validateBytes)),
                "the real worker's VALIDATE reply must be a success envelope carrying null; anything "
                        + "else means the engine refused this source and the recording is not the "
                        + "evidence this test replays");

        var supervisor = new FakeSupervisor();
        // 5 s, unchanged from before the fix. See the Javadoc: leaving it alone is the point.
        var runtime = runtime(supervisor, Duration.ofSeconds(5));

        supervisor.response = validateBytes;
        runtime.validate(generated).toCompletableFuture().get();
        supervisor.response = testBytes;
        Object tested = runtime.test(validated, testRequest).toCompletableFuture().get();
        // count == 2L, not 2.0: proves the number round-tripped through fromGuest's fitsInLong branch.
        assertEquals(Map.of("greeting", "Hello Ravenroot", "count", 2L), tested);
        supervisor.response = executeBytes;
        assertEquals(tested, runtime.execute(TestAdmission.of(active), executeRequest).toCompletableFuture().get());
        assertEquals(3, supervisor.capabilityChecks);
        assertEquals(0, supervisor.realSubprocessSpawns, "the lifecycle scenario must never depend on "
                + "a real child JVM completing inside the sandbox deadline -- the only legitimate "
                + "worker runs are the three un-raced RealWorkerRun.capture calls above. This counter, "
                + "not the 5 s budget, is what fails if a live spawn is put back under the clock");
    }

    /**
     * The negative control that makes the validate path's real-parsing coverage a
     * claim capable of failing.
     *
     * <p><b>Why it is required rather than nice to have.</b> A valid artifact's VALIDATE reply is
     * {@code writeSuccess(null)}, byte-identical to {@code FakeSupervisor.wellFormedValidateResponse()}.
     * So on the happy path, swapping a real recording for that stub goes green -- neither the runtime
     * nor the assertions can distinguish them. Without this test, "the validate path retains real
     * parsing coverage" would be a statement no test could falsify, which is worse than a flake.
     * Here the bytes can only come from the real engine's verdict: a stub would have to author a
     * failure envelope, and authoring evidence is the vacuity FIX-29 rejects.
     *
     * <p><b>Why {@code "42"} and why this message.</b> The worker wraps the source and evaluates it,
     * and {@code (42)} is not callable, so {@code GraalVmWorkerMain} refuses it at its own
     * {@code canExecute()} check. The asserted text is therefore the PRODUCT's message, not
     * GraalVM's. Pinning third-party wording would trade this flake for a version-drift one, the
     * trap {@code preservesWorkerFailureAndOutputBounds} already documents avoiding.
     *
     * <p>This also covers strictly more than the suite did before: until now only a well-formed
     * source was ever validated, so the refusal half of {@code Mode.VALIDATE} had no real coverage
     * at all.
     */
    @Test @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void theRealValidateWorkerRefusesAnArtifactThatIsNotAFunction() throws Exception {
        var notAFunction = artifact("42", ArtifactState.GENERATED);
        byte[] refusal = RealWorkerRun.capture(ProgramWireProtocol.Mode.VALIDATE, notAFunction, null);

        // What the REAL worker decided, read off its own output. "It failed" would be vacuous here
        // too -- a hash mismatch or an unreadable request would satisfy that -- so the refusal has
        // to be the not-a-function refusal specifically.
        var workerFailure = assertThrows(ProgramWireProtocol.ProgramWorkerException.class,
                () -> ProgramWireProtocol.readResponse(new ByteArrayInputStream(refusal)),
                "the real worker accepted a non-function artifact under Mode.VALIDATE -- refusing "
                        + "one is the property this test exists to prove");
        assertTrue(workerFailure.getMessage().contains("must evaluate to a JavaScript function"),
                "the real worker refused the artifact, but not for being a non-function. It reported: "
                        + workerFailure.getMessage());

        // And what the RUNTIME does with that genuine refusal.
        var supervisor = new FakeSupervisor();
        supervisor.response = refusal;
        var error = assertThrows(ExecutionException.class,
                () -> runtime(supervisor, Duration.ofSeconds(5)).validate(notAFunction).toCompletableFuture().get());
        // This type was narrowed, and the narrowing is the point rather than a side effect. "The
        // artifact must evaluate to a function" is a statement about the AUTHOR'S SOURCE, so it now
        // surfaces as ProgramSourceRejectedException -- which is what lets the HTTP layer report it
        // as a validation outcome naming the reason instead of a 400 saying the request was invalid.
        // ProgramSourceRejectedException is not an IllegalArgumentException, so asserting the old
        // type would assert that the reason is still being thrown away.
        assertTrue(error.getCause() instanceof ProgramSourceRejectedException,
                "a refusal about the author's own source must surface as the typed source rejection, "
                        + "but surfaced as " + error.getCause());
        assertTrue(error.getCause().getMessage().contains("must evaluate to a JavaScript function"),
                "and it must still carry the product's own reason, was: " + error.getCause().getMessage());
        assertEquals(0, supervisor.realSubprocessSpawns, "the refusal is replayed from the un-raced "
                + "capture above; no child JVM may run inside the sandbox deadline");
    }

    /**
     * Previously, {@code program_sandbox_unavailable} appeared in
     * exactly one production line and no test asserted on it -- a mutation could have deleted the log
     * call, the startup probe, {@code describe()}, or the HTTP message's pointer to the log, and this
     * whole suite would have stayed green. This assertion is what makes deleting the log call red.
     *
     * <p>Also the only place {@code MissingLauncher.describe()} is observable: it is a private nested
     * class, reached only through this stub path, and its whole reason to exist is that an operator
     * reading this exact log line sees which environment variable to set rather than a bare class name.
     */
    @Test void failsClosedWithoutAnExternalSupervisor() {
        var runtime = new GraalVmProgramRuntime(JAVA, Duration.ofSeconds(1), 64);
        String log = captureStderr(() -> {
            var error = assertThrows(ExecutionException.class, () -> runtime.test(artifact("() => 1", ArtifactState.VALIDATED), request("x")).toCompletableFuture().get());
            assertTrue(error.getCause().getMessage().contains("SANDBOX_LAUNCHER_MISSING"));
        });
        assertTrue(log.contains("\"event\":\"program_sandbox_unavailable\""), log);
        assertTrue(log.contains("\"stage\":\"request\""), log);
        assertTrue(log.contains("\"reason\":\"SANDBOX_LAUNCHER_MISSING\""), log);
        assertTrue(log.contains("\"launcher\":\"RAVENROOT_GRAAL_SANDBOX_SUPERVISOR is not set\""),
                "MissingLauncher.describe() must name the variable an operator sets, was: " + log);
    }

    /** The other sandbox-capability failure condition. */
    @Test void rejectsIncompatibleCapabilityBeforeLaunching() {
        var supervisor = new FakeSupervisor(); supervisor.capabilityError = "SANDBOX_CAPABILITY_UNSUPPORTED";
        String log = captureStderr(() -> {
            var error = assertThrows(ExecutionException.class, () -> runtime(supervisor, Duration.ofSeconds(1)).test(artifact("() => 1", ArtifactState.VALIDATED), request("x")).toCompletableFuture().get());
            assertTrue(error.getCause().getMessage().contains("SANDBOX_CAPABILITY_UNSUPPORTED")); assertEquals(0, supervisor.launches);
        });
        assertTrue(log.contains("\"event\":\"program_sandbox_unavailable\""), log);
        assertTrue(log.contains("\"stage\":\"request\""), log);
        assertTrue(log.contains("\"reason\":\"SANDBOX_CAPABILITY_UNSUPPORTED\""), log);
        assertTrue(log.contains("\"launcher\":\"FakeSupervisor\""), log);
    }

    /**
     * Captures everything written to {@link System#err} while {@code action} runs, restoring the
     * original stream even if {@code action} throws. Same shape as
     * {@code RavenrootServerMainLifecycleTest}'s capture, kept local here because this module has no
     * shared test-support dependency on {@code ravenroot-server}.
     */
    private static String captureStderr(Runnable action) {
        var output = new java.io.ByteArrayOutputStream();
        java.io.PrintStream previous = System.err;
        try (var captured = new java.io.PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setErr(captured);
            action.run();
        } finally {
            System.setErr(previous);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    /**
     * FIX-30, following FIX-27 and FIX-29 with the same shape: the
     * temporal dependency is REMOVED, not widened.
     *
     * <p><b>Cause.</b> This test asserts on the argument vector the product builds, but it used to
     * obtain that vector by running a real {@code validate()} end to end -- which spawned a real
     * child JVM (JVM boot plus Truffle/Graal JS cold start) and then awaited it inside
     * {@code policy.deadline()}. That deadline is 750 ms, the tightest in the class, so under
     * contention {@code session.await(remaining(deadline))} returned DEADLINE_EXCEEDED and
     * {@code validate()} failed with {@code IllegalStateException: SANDBOX_DEADLINE_EXCEEDED} before
     * the assertion was ever reached. Nothing about the property under test needs a worker to run.
     *
     * <p><b>Fix.</b> The supervisor answers from a pre-seeded well-formed response, so no process is
     * started and there is nothing to await. See {@code FakeSupervisor#wellFormedValidateResponse}
     * for why stubbing THIS is legitimate while stubbing FIX-29's worker output was not: here the
     * response is plumbing, and the evidence -- the policy -- is captured by {@code launch(policy)}
     * before any response exists.
     *
     * <p><b>The 750 ms stays, deliberately.</b> It is not a tolerance here, it is part of the
     * asserted value: the expected list literally contains {@code --deadline-ms=750} and
     * {@code --cpu-ms=750}. Anyone tempted to "fix" a flake by widening the budget would have to edit
     * the expected arguments in the same diff, which makes that widening explicit. That
     * property is worth more than the millisecond value; do not trade it away.
     */
    @Test void serializesExactImmutableVersionedPolicyWithoutEnvironment() throws Exception {
        var supervisor = new FakeSupervisor(); supervisor.response = FakeSupervisor.wellFormedValidateResponse();
        runtime(supervisor, Duration.ofMillis(750)).validate(artifact("() => 1", ArtifactState.GENERATED)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        // Without this, a runtime that never launched leaves supervisor.policy null and the assertion
        // below fails for a confusing reason -- or, worse, gets "repaired" into passing.
        assertEquals(1, supervisor.launches, "the runtime must have launched exactly one supervisor session");
        assertEquals(0, supervisor.realSubprocessSpawns, "policy serialization must never depend on a "
                + "real child JVM: a reintroduced spawn has to be caught by this counter, not by the clock");
        assertEquals(List.of("--ravenroot-sandbox-supervisor=v1", "--deadline-ms=750", "--cpu-ms=750", "--memory-mib=64", "--max-pids=32", "--max-files=256", "--tmpfs-mib=64", "--max-output-bytes=2097152",
                "--trusted-worker=" + Path.of(System.getProperty("java.class.path")).toAbsolutePath().normalize(), "--trusted-worker-sha256=" + supervisor.policy.workerIdentity(),
                "--trusted-jre=" + JAVA.toAbsolutePath().normalize(), "--trusted-jre-sha256=" + supervisor.policy.jreIdentity()), supervisor.policy.arguments());
    }

    @Test void workerBytesCannotForgeSupervisorOutcome() {
        var supervisor = new FakeSupervisor(); supervisor.outcome = SandboxSupervisorLauncher.SandboxOutcome.SECCOMP_DENIED;
        var error = assertThrows(ExecutionException.class, () -> runtime(supervisor, Duration.ofSeconds(1)).test(artifact("() => 'SANDBOX_COMPLETED'", ArtifactState.VALIDATED), request("x")).toCompletableFuture().get());
        assertTrue(error.getCause().getMessage().contains("SANDBOX_SECCOMP_DENIED"));
    }

    @Test void deadlineAndCancellationRequestOneAuthoritativeCleanup() throws Exception {
        var deadline = new FakeSupervisor(); deadline.outcome = SandboxSupervisorLauncher.SandboxOutcome.DEADLINE_EXCEEDED;
        var timeout = assertThrows(ExecutionException.class, () -> runtime(deadline, Duration.ofSeconds(1)).test(artifact("() => 1", ArtifactState.VALIDATED), request("x")).toCompletableFuture().get());
        assertTrue(timeout.getCause().getMessage().contains("SANDBOX_DEADLINE_EXCEEDED")); assertEquals(1, deadline.terminations);
        var cancelled = new FakeSupervisor(); cancelled.block = true;
        var future = runtime(cancelled, Duration.ofSeconds(5)).test(artifact("() => 1", ArtifactState.VALIDATED), request("x")).toCompletableFuture();
        while (cancelled.launches == 0) Thread.yield(); assertTrue(future.cancel(true));
        assertEquals(1, cancelled.terminations); assertTrue(cancelled.reaped);
    }

    /**
     * FIX-29 for the worker-failure half; FIX-27 for the oversized-response half.
     * Distinct from {@code JoinSemanticsTest} and {@code SseLeaseRevalidationIntegrationTest}.
     *
     * <p><b>FIX-27, oversized half.</b> It used to spawn a real child JVM (real GraalVM engine cold
     * start) it never needed, racing a 1s wall-clock budget it did not need to race either, since
     * the response bytes were already fixed by the test before the process ever ran.
     * {@code FakeSupervisor.Session#await} now skips the spawn whenever a response is pre-set.
     *
     * <p><b>FIX-29, worker-failure half.</b> FIX-27's remedy did not transfer, because here the
     * assertion is PRODUCED BY the worker: pre-seeding a response the test wrote itself would
     * replace the thing under test with a fixture that asserts itself. So this half kept running
     * real child JVMs -- two of them, measured as {@code launches=1, terminations=1,
     * realSubprocessSpawns=2}, the second one started by {@code GraalVmProgramRuntime#cleanup}'s
     * reaping await (see {@code FakeSupervisor.Session#resolved}) -- against a 3s budget.
     *
     * <p>That budget was never the "3x margin" it looked like. One child-JVM round trip was measured
     * at ~671 ms idle on a 10-core machine, ~2.0 s at 2x CPU oversubscription, ~3.0 s at 4x and
     * ~5.4 s at 8x: a SINGLE spawn exceeds 3000 ms from 4x upward. Driven end to end at the real 3s
     * budget, the half flipped in 8 of 10 trials at 4x load and 10 of 10 at 8x, always the same way
     * -- {@code IllegalStateException: SANDBOX_DEADLINE_EXCEEDED} instead of the expected
     * {@code IllegalArgumentException}. The wait that gave way is
     * {@code GraalVmProgramRuntime#invokeSupervisor}'s {@code session.await(remaining(deadline))},
     * with {@code checkDeadline} immediately after it as a second door to the identical wrong
     * outcome, so no larger budget could have closed this.
     *
     * <p><b>The fix is record-and-replay, not a skipped worker.</b> The real worker still runs, and
     * the bytes asserted on are the ones it actually wrote -- the test never authors a response
     * envelope. What changed is that the worker no longer runs INSIDE a sandbox deadline: it is
     * executed once by {@link RealWorkerRun} with no budget over it, and its genuine output is then
     * replayed through the runtime deterministically. The temporal dependency is removed rather than
     * widened, which is the same shape as FIX-27's fix. {@code GraalVmProgramRuntimeLoadSensitivityTest}
     * carries the under-load evidence for both halves.
     */
    @Test @Timeout(value = 120, unit = TimeUnit.SECONDS) void preservesWorkerFailureAndOutputBounds() throws Exception {
        // One artifact and one request instance, shared by the capture and the replay, so the recorded
        // bytes provably belong to this exact invocation rather than to a similar-looking one.
        var deniedArtifact = artifact("() => Java.type('java.lang.System').getenv()", ArtifactState.VALIDATED);
        var deniedRequest = request("x");
        byte[] workerBytes = RealWorkerRun.capture(ProgramWireProtocol.Mode.TEST, deniedArtifact, deniedRequest);

        // What the REAL worker decided, asserted against its own output and nothing else. Checking
        // only "it failed" would be vacuous: a worker failing on a hash mismatch or an unreadable
        // request would satisfy that too. The denial has to be the host-access denial.
        var workerFailure = assertThrows(ProgramWireProtocol.ProgramWorkerException.class,
                () -> ProgramWireProtocol.readResponse(new ByteArrayInputStream(workerBytes)),
                "the real worker accepted Java.type('java.lang.System') instead of refusing it -- the "
                        + "sandbox's host-access denial is the property this half exists to prove");
        assertTrue(workerFailure.getMessage().contains("java.lang.System")
                        && workerFailure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("host"),
                "the real worker failed, but not with a host-access denial for java.lang.System. It "
                        + "reported: " + workerFailure.getMessage());

        // And what the RUNTIME does with that genuine failure. Deliberately loose on the engine's exact
        // wording above: pinning third-party message text would trade this flake for a version-drift one.
        var denied = new FakeSupervisor();
        denied.response = workerBytes;
        var error = assertThrows(ExecutionException.class, () -> runtime(denied, Duration.ofSeconds(3))
                .test(deniedArtifact, deniedRequest).toCompletableFuture().get());
        assertTrue(error.getCause() instanceof IllegalArgumentException,
                "a genuine worker failure must surface as IllegalArgumentException, but surfaced as "
                        + error.getCause());
        // NOT a contradiction with "the worker is real", and not a candidate for "correction": exactly
        // one real child JVM ran in this test, in RealWorkerRun.capture above, with no deadline over it.
        // Zero ran under the supervisor, because the runtime replays that JVM's recorded output. This
        // assertion is what keeps it that way -- it, and not the 3s budget, is what fails if a future
        // change puts a live child JVM back inside the sandbox deadline.
        assertEquals(0, denied.realSubprocessSpawns, "the worker-failure scenario must never depend on "
                + "a real child JVM completing inside the sandbox deadline -- see RealWorkerRun and the "
                + "method Javadoc above");
        var oversized = new FakeSupervisor(); oversized.response = new byte[2 * 1024 * 1024 + 1];
        var bound = assertThrows(ExecutionException.class, () -> runtime(oversized, Duration.ofSeconds(1)).test(artifact("() => 1", ArtifactState.VALIDATED), request("x")).toCompletableFuture().get());
        assertTrue(bound.getCause().getMessage().contains("SANDBOX_OUTPUT_LIMIT"));
        assertEquals(0, oversized.realSubprocessSpawns, "the oversized-response scenario must never "
                + "depend on a real child JVM completing in time -- see FakeSupervisor#await and the "
                + "class Javadoc above; this is the unconditional, load-independent counterpart to "
                + "GraalVmProgramRuntimeLoadSensitivityTest's empirical under-load evidence");
    }

    /**
     * FIX-29, mechanism half. {@code GraalVmProgramRuntime#cleanup} reaps authoritatively:
     * {@code terminate(...)} followed by {@code await(REAP_TIMEOUT)}. A session with no terminal state
     * answered that reap by starting a SECOND real child JVM, which is where the measured
     * {@code realSubprocessSpawns=2} against {@code launches=1} came from.
     *
     * <p>Asserted here directly rather than through the runtime, because after the record-and-replay
     * fix the worker-failure test pre-seeds a response and so would report zero spawns either way --
     * it can no longer tell whether this mechanism is sound. This test starts no child JVM at all: the
     * spawn count IS the assertion. Against the pre-fix fake it reports one spawn and COMPLETED.
     */
    @Test void aTerminatedSessionReportsItsOutcomeInsteadOfStartingASecondSandbox() throws Exception {
        var supervisor = new FakeSupervisor();
        var session = supervisor.launch(policy(Duration.ofSeconds(3)));
        session.terminate(SandboxSupervisorLauncher.SandboxTermination.CANCELLED);
        var reaped = session.await(Duration.ofSeconds(3));
        // Spawn count first: it is the property. The outcome value is the weaker, secondary claim.
        assertEquals(0, supervisor.realSubprocessSpawns,
                "reaping a terminated session must never start a new sandbox -- the real "
                        + "SandboxSupervisorProcessLauncher cannot, because it creates its process in "
                        + "launch() and only observes it in await()");
        assertEquals(SandboxSupervisorLauncher.SandboxOutcome.CANCELLED, reaped,
                "a terminated session must report its settled outcome to the reaping await");
    }

    private static GraalVmProgramRuntime runtime(FakeSupervisor supervisor, Duration timeout) { return new GraalVmProgramRuntime(supervisor, policy(timeout)); }
    private static SandboxPolicy policy(Duration timeout) { return new SandboxPolicy(timeout, Math.toIntExact(timeout.toMillis()), 64, 32, 256, 64, 2 * 1024 * 1024, Path.of(System.getProperty("java.class.path")), "worker-test-id", JAVA, "jre-test-id"); }
    private static ProgramRequest request(Object payload) { return new ProgramRequest(UUID.randomUUID(), "program-test", payload, Map.of("count", 1)); }
    private static GeneratedArtifact artifact(String source, ArtifactState state) { try { String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); Instant now = Instant.now(); return new GeneratedArtifact("test-artifact", "javascript", hash, source, state, 1, now, now, Map.of()); } catch (Exception e) { throw new AssertionError(e); } }
}
