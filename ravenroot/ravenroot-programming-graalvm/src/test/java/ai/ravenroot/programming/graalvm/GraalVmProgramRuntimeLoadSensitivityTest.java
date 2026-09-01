package ai.ravenroot.programming.graalvm;

import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramDeadlineExceededException;
import ai.ravenroot.api.programming.ProgramRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.EOFException;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Permanent regression and empirical, load-based evidence: repeats {@code
 * GraalVmProgramRuntimeTest#preservesWorkerFailureAndOutputBounds}'s oversized-response scenario
 * while every available CPU core is kept busy by synthetic load, and asserts the outcome stays
 * SANDBOX_OUTPUT_LIMIT throughout -- not "usually", every single trial.
 *
 * <p><b>Why this is deterministic, not tolerant.</b> The fix removed the temporal dependency
 * entirely ({@code FakeSupervisor.Session#await} never spawns a real child JVM once a response is
 * pre-set), so this test does not need the load to pass -- it needs the load to prove the fix
 * does not merely make the race narrower. {@link #oversizedResponseStaysDeterministicUnderCpuContention}
 * additionally asserts {@code realSubprocessSpawns == 0} on every trial, which is the
 * unconditional version of the same claim the load only stress-tests.
 *
 * <p><b>Mutation testing (run manually and reverted --
 * a permanent test should not sabotage itself on every run).</b> Two mutations were applied to a
 * scratch copy of this class and confirmed to turn it red, then reverted:
 * <ol>
 *   <li>Reverting {@code FakeSupervisor.Session#await} to the pre-fix shape (unconditional real
 *       subprocess spawn) under this same synthetic load: FAILED, reproducing FIX-27 exactly (some
 *       trials returned SANDBOX_DEADLINE_EXCEEDED instead of SANDBOX_OUTPUT_LIMIT). This is the
 *       red-control run required before the fix; the fixed code passes the identical test under
 *       the identical load.</li>
 *   <li>Emptying the sweep (forcing the trial loop to execute zero iterations, simulating an
 *       accidentally-vacuous condition) still FAILED, via {@link #atLeastOneTrialActuallyRan()}
 *       and the {@code trialsRun >= MIN_TRIALS} gate below -- a sweep that silently ran nothing is
 *       not disguised as "no failures observed".</li>
 * </ol>
 */
class GraalVmProgramRuntimeLoadSensitivityTest {
    private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");
    private static final int MIN_TRIALS = 8;
    private static final int MAX_TRIALS = 60;
    private static final Duration LOAD_WINDOW = Duration.ofSeconds(3);
    /** FIX-29. CPU oversubscription factor for the worker-failure sweep; see that test's Javadoc. */
    private static final int LOAD_MULTIPLIER = 4;
    /**
     * The lifecycle sweep needs 8x, not {@link #LOAD_MULTIPLIER}'s 4x, and this is a
     * measured constraint rather than a preference: the unfixed lifecycle test was 10/10 GREEN at 4x
     * and failed 9 of 10 trials at 8x. A 4x sweep here would pass against the pre-fix code and so
     * would prove nothing. Do not "harmonise" this with the constant above.
     */
    private static final int LIFECYCLE_LOAD_MULTIPLIER = 8;

    private int trialsRun;
    private int deniedTrialsRun;
    private int policyTrialsRun;
    private int lifecycleTrialsRun;

    /**
     * The exact argument vector the product is expected to construct, spelled out as
     * literals rather than read back from {@code supervisor.policy}, so this sweep cannot agree with
     * the runtime merely because both consulted the same object.
     */
    private static final List<String> EXPECTED_POLICY_ARGUMENTS = List.of(
            "--ravenroot-sandbox-supervisor=v1", "--deadline-ms=750", "--cpu-ms=750", "--memory-mib=64",
            "--max-pids=32", "--max-files=256", "--tmpfs-mib=64", "--max-output-bytes=2097152",
            "--trusted-worker=" + Path.of(System.getProperty("java.class.path")).toAbsolutePath().normalize(),
            "--trusted-worker-sha256=worker-test-id",
            "--trusted-jre=" + JAVA.toAbsolutePath().normalize(),
            "--trusted-jre-sha256=jre-test-id");

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void oversizedResponseStaysDeterministicUnderCpuContention() throws Exception {
        AtomicBoolean keepSpinning = new AtomicBoolean(true);
        int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
        Thread[] load = new Thread[cores];
        for (int i = 0; i < cores; i++) {
            load[i] = new Thread(spin(keepSpinning), "synthetic-load-" + i);
            load[i].setDaemon(true);
            load[i].start();
        }
        try {
            long deadline = System.nanoTime() + LOAD_WINDOW.toNanos();
            while (trialsRun < MAX_TRIALS && (trialsRun < MIN_TRIALS || System.nanoTime() < deadline)) {
                runOneTrial();
            }
        } finally {
            keepSpinning.set(false);
            for (Thread t : load) {
                t.join(5_000);
            }
        }
        assertTrue(trialsRun >= MIN_TRIALS, "expected at least " + MIN_TRIALS + " trials under load, "
                + "only " + trialsRun + " completed -- a sweep that ran fewer trials than intended "
                + "would silently prove less than it claims");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void atLeastOneTrialActuallyRan() throws Exception {
        // Independent, no-load sanity check that runOneTrial() itself works and increments the
        // counter -- so a change that broke trial execution (the accidental-empty-sweep failure
        // mode flagged for this run) cannot hide behind "the load test above reported no failures".
        int before = trialsRun;
        runOneTrial();
        assertTrue(trialsRun > before, "runOneTrial() must execute and increment the counter -- if "
                + "this fails, the sweep in oversizedResponseStaysDeterministicUnderCpuContention "
                + "cannot be trusted either, no matter what it reports");
    }

    /**
     * The worker-failure counterpart of the sweep above, and the evidence that FIX-29 removed the
     * temporal dependency instead of widening it.
     *
     * <p>The real worker runs exactly once, BEFORE the load window opens and with no sandbox deadline
     * over it ({@link RealWorkerRun}); every trial then replays the bytes it genuinely produced. So
     * this sweep, like the oversized one, does not need the load in order to pass -- the load is here
     * to prove the fix is not merely a narrower race.
     *
     * <p><b>Why 4x and not 8x.</b> Measured on a 10-core machine before the fix, a single child-JVM
     * round trip cost ~671 ms idle, ~2.0 s at 2x oversubscription, ~3.0 s at 4x and ~5.4 s at 8x, and
     * the unfixed half flipped to {@code SANDBOX_DEADLINE_EXCEEDED} in 8 of 10 trials at 4x and 10 of
     * 10 at 8x. 4x already reproduces it; buying the last two trials with 80 spinner threads would be
     * CI cost for no additional assertion.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void workerFailureStaysDeterministicUnderCpuContention() throws Exception {
        var deniedArtifact = artifact("() => Java.type('java.lang.System').getenv()", ArtifactState.VALIDATED);
        var deniedRequest = request("x");
        byte[] workerBytes = RealWorkerRun.capture(ProgramWireProtocol.Mode.TEST, deniedArtifact, deniedRequest);

        AtomicBoolean keepSpinning = new AtomicBoolean(true);
        int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
        Thread[] load = new Thread[cores * LOAD_MULTIPLIER];
        for (int i = 0; i < load.length; i++) {
            load[i] = new Thread(spin(keepSpinning), "synthetic-load-denied-" + i);
            load[i].setDaemon(true);
            load[i].start();
        }
        try {
            long deadline = System.nanoTime() + LOAD_WINDOW.toNanos();
            while (deniedTrialsRun < MAX_TRIALS && (deniedTrialsRun < MIN_TRIALS || System.nanoTime() < deadline)) {
                runOneDeniedTrial(workerBytes, deniedArtifact, deniedRequest);
            }
        } finally {
            keepSpinning.set(false);
            for (Thread t : load) {
                t.join(5_000);
            }
        }
        assertTrue(deniedTrialsRun >= MIN_TRIALS, "expected at least " + MIN_TRIALS + " trials under "
                + "load, only " + deniedTrialsRun + " completed -- a sweep that ran fewer trials than "
                + "intended would silently prove less than it claims");
    }

    /**
     * The policy-serialization counterpart. Repeats
     * {@code GraalVmProgramRuntimeTest#serializesExactImmutableVersionedPolicyWithoutEnvironment}
     * under synthetic CPU load and asserts the product still hands the supervisor the exact argument
     * vector -- every trial, not usually.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void policySerializationStaysDeterministicUnderCpuContention() throws Exception {
        AtomicBoolean keepSpinning = new AtomicBoolean(true);
        int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
        Thread[] load = new Thread[cores * LOAD_MULTIPLIER];
        for (int i = 0; i < load.length; i++) {
            load[i] = new Thread(spin(keepSpinning), "synthetic-load-policy-" + i);
            load[i].setDaemon(true);
            load[i].start();
        }
        try {
            long deadline = System.nanoTime() + LOAD_WINDOW.toNanos();
            while (policyTrialsRun < MAX_TRIALS && (policyTrialsRun < MIN_TRIALS || System.nanoTime() < deadline)) {
                runOnePolicyTrial();
            }
        } finally {
            keepSpinning.set(false);
            for (Thread t : load) {
                t.join(5_000);
            }
        }
        assertTrue(policyTrialsRun >= MIN_TRIALS, "expected at least " + MIN_TRIALS + " trials under "
                + "load, only " + policyTrialsRun + " completed -- a sweep that ran fewer trials than "
                + "intended would silently prove less than it claims");
    }

    /**
     * FIX-33 red control. Reproduces the empty pre-set response mutation: an empty pre-set
     * {@link FakeSupervisor#response} makes {@code FakeSupervisor.Session#await} report
     * {@code SandboxOutcome.COMPLETED} with a zero-length worker payload, so
     * {@code ProgramWireProtocol.readResponse}'s very first read -- the magic-number
     * {@code DataInputStream#readInt()} -- hits end of stream and throws {@link EOFException}.
     * That is a real, reproduced failure with nothing to do with the 750 ms sandbox deadline.
     *
     * <p>Before the fix, {@code runOnePolicyTrial}'s catch block unconditionally named the
     * deadline regardless of cause, so this assertion on the message text failed even though the
     * underlying {@code assertThrows} succeeded -- exactly the malformed-diagnostic shape this test prevents:
     * the gate was already correct, the explanation printed alongside it was not.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void policyTrialFailureMessageNamesTheActualCauseNotTheDeadline() throws Exception {
        var supervisor = new FakeSupervisor();
        supervisor.response = new byte[0];
        var runtime = new GraalVmProgramRuntime(supervisor, policy(Duration.ofMillis(750)));
        var error = assertThrows(ExecutionException.class, () -> runtime
                .validate(artifact("() => 1", ArtifactState.GENERATED))
                .toCompletableFuture().get());
        assertTrue(error.getCause() instanceof EOFException,
                "test setup did not reproduce the empty pre-set response mutation; got "
                        + error.getCause());

        String message = policyTrialFailureMessage(1, error);
        assertTrue(message.contains("EOFException"),
                "message must report the actually observed cause type instead of a fixed one; got: "
                        + message);
        assertTrue(!message.contains("750 ms sandbox deadline being raced"),
                "message must not attribute a non-deadline failure (EOFException) to the sandbox "
                        + "deadline; got: " + message);
    }

    /**
     * FIX-33 green control: when the cause genuinely is the
     * sandbox deadline, the message must stay exactly as informative as before the fix, not
     * regress to a bare cause dump. {@link SandboxSupervisorLauncher.SandboxOutcome#DEADLINE_EXCEEDED}
     * forces {@code GraalVmProgramRuntime#invokeSupervisor} down its deadline path, which is the same
     * {@code ProgramDeadlineExceededException} that a real deadline expiry produces via
     * {@code checkDeadline} -- no clock race needed to prove this.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void policyTrialFailureMessageStaysInformativeForAGenuineDeadline() throws Exception {
        var supervisor = new FakeSupervisor();
        supervisor.outcome = SandboxSupervisorLauncher.SandboxOutcome.DEADLINE_EXCEEDED;
        var runtime = new GraalVmProgramRuntime(supervisor, policy(Duration.ofMillis(750)));
        var error = assertThrows(ExecutionException.class, () -> runtime
                .validate(artifact("() => 1", ArtifactState.GENERATED))
                .toCompletableFuture().get());

        String message = policyTrialFailureMessage(1, error);
        assertTrue(message.contains("750 ms sandbox deadline being raced"),
                "a genuine deadline expiry must stay as informative as before the fix; got: " + message);
    }

    @Test
    void policyTrialFailureMessageTreatsTimeoutExceptionAsSandboxDeadlineDerived() {
        String message = policyTrialFailureMessage(1, new ExecutionException(new TimeoutException()));

        assertTrue(message.contains("750 ms sandbox deadline being raced"),
                "a TimeoutException must be treated as sandbox-deadline-derived; got: " + message);
        assertTrue(!message.contains("NOT the 750 ms sandbox deadline"),
                "a TimeoutException must not deny the sandbox deadline; got: " + message);
    }

    /**
     * The lifecycle counterpart, and the permanent under-load evidence that FIX-31 removed the
     * temporal dependency from BOTH clocks rather than widening either.
     *
     * <p><b>What it reproduces.</b> Before the fix, {@code
     * GraalVmProgramRuntimeTest#validatesTestsAndExecutesJavascriptThroughTestSupervisor} ran three
     * real child JVMs measured twice over -- once by the caller's {@code get(5, SECONDS)} and once by
     * the sandbox deadline started, milliseconds later, on the runtime's virtual thread. At 8x, 9 of
     * 10 trials failed: eight through the caller's wait, one through the sandbox deadline. This sweep
     * asserts against both, every trial: no invocation may fail at all, and the trial blocks on an
     * unbounded {@code get()} so a caller-side hang surfaces as this method's {@code @Timeout} rather
     * than as a wrong exception. When one does fail, {@link #lifecycleTrialFailureMessage} names the
     * cause it actually observed -- per FIX-33, a trial may not blame the deadline for
     * everything.
     *
     * <p><b>Why it does not need the load to pass.</b> The three real workers run once each, before
     * the load window opens and with no sandbox deadline over them ({@link RealWorkerRun}); every
     * trial replays the bytes they genuinely produced. The load is here to prove the fix is not
     * merely a narrower race, and {@code realSubprocessSpawns == 0} is the unconditional version of
     * the same claim.
     *
     * <p><b>Mutation-tested in both senses</b> (run manually against a scratch copy and reverted --
     * a permanent test must not sabotage itself on every run):
     * <ol>
     *   <li>Not pre-seeding {@code supervisor.response}, so the fake spawns real child JVMs under the
     *       sandbox deadline -- the exact pre-fix shape: FAILED under this same 8x load, reproducing
     *       FIX-31's failure through both doors.</li>
     *   <li>Forcing the trial loop to execute zero iterations: still FAILED, via the
     *       {@code lifecycleTrialsRun >= MIN_TRIALS} gate below -- a sweep that silently ran nothing
     *       is not disguised as "no failures observed".</li>
     * </ol>
     */
    @Test
    @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void validateTestAndExecuteStayDeterministicUnderCpuContention() throws Exception {
        String source = "({ payload, attributes }) => ({ greeting: 'Hello ' + payload, count: attributes.count + 1 })";
        var generated = artifact(source, ArtifactState.GENERATED);
        var validated = artifact(source, ArtifactState.VALIDATED);
        var active = artifact(source, ArtifactState.ACTIVE);
        var testRequest = request("Ravenroot");
        var executeRequest = request("Ravenroot");
        byte[] validateBytes = RealWorkerRun.capture(ProgramWireProtocol.Mode.VALIDATE, generated, null);
        byte[] testBytes = RealWorkerRun.capture(ProgramWireProtocol.Mode.TEST, validated, testRequest);
        byte[] executeBytes = RealWorkerRun.capture(ProgramWireProtocol.Mode.EXECUTE, active, executeRequest);

        AtomicBoolean keepSpinning = new AtomicBoolean(true);
        int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
        Thread[] load = new Thread[cores * LIFECYCLE_LOAD_MULTIPLIER];
        for (int i = 0; i < load.length; i++) {
            load[i] = new Thread(spin(keepSpinning), "synthetic-load-lifecycle-" + i);
            load[i].setDaemon(true);
            load[i].start();
        }
        try {
            long deadline = System.nanoTime() + LOAD_WINDOW.toNanos();
            while (lifecycleTrialsRun < MAX_TRIALS
                    && (lifecycleTrialsRun < MIN_TRIALS || System.nanoTime() < deadline)) {
                runOneLifecycleTrial(validateBytes, testBytes, executeBytes, generated, validated, active,
                        testRequest, executeRequest);
            }
        } finally {
            keepSpinning.set(false);
            for (Thread t : load) {
                t.join(5_000);
            }
        }
        assertTrue(lifecycleTrialsRun >= MIN_TRIALS, "expected at least " + MIN_TRIALS + " trials under "
                + "load, only " + lifecycleTrialsRun + " completed -- a sweep that ran fewer trials than "
                + "intended would silently prove less than it claims");
    }

    private void runOneLifecycleTrial(byte[] validateBytes, byte[] testBytes, byte[] executeBytes,
                                      GeneratedArtifact generated, GeneratedArtifact validated,
                                      GeneratedArtifact active, ProgramRequest testRequest,
                                      ProgramRequest executeRequest) throws Exception {
        var supervisor = new FakeSupervisor();
        // 5 s, the same budget the pre-fix test raced and lost. Kept deliberately: the fix is that
        // nothing which can block runs inside it any more, not that it was made bigger.
        var runtime = new GraalVmProgramRuntime(supervisor, policy(Duration.ofSeconds(5)));
        int trial = lifecycleTrialsRun + 1;
        Object tested;
        try {
            // Unbounded on purpose. A caller-side timeout was one of the two failure doors; making
            // it unbounded means a stall surfaces as this test's @Timeout, which cannot be mistaken
            // for the runtime returning the wrong thing.
            supervisor.response = validateBytes;
            runtime.validate(generated).toCompletableFuture().get();
            supervisor.response = testBytes;
            tested = runtime.test(validated, testRequest).toCompletableFuture().get();
            supervisor.response = executeBytes;
            assertEquals(tested, runtime.execute(TestAdmission.of(active), executeRequest).toCompletableFuture().get(),
                    "trial " + trial + ": execute disagreed with test on identical input");
        } catch (ExecutionException error) {
            throw new AssertionError(lifecycleTrialFailureMessage(trial, error), error);
        }
        assertEquals(Map.of("greeting", "Hello Ravenroot", "count", 2L), tested,
                "trial " + trial + " parsed a different result out of the real worker's recorded bytes");
        assertEquals(3, supervisor.capabilityChecks,
                "trial " + trial + ": the runtime must attest capability once per invocation");
        assertEquals(0, supervisor.realSubprocessSpawns,
                "trial " + trial + " spawned a real child JVM under the sandbox deadline; the only "
                        + "legitimate worker runs are the three un-raced RealWorkerRun.capture calls "
                        + "made before the load window opened");
        lifecycleTrialsRun++;
    }

    /**
     * FIX-31, following the diagnostic rule FIX-33 established for
     * {@link #policyTrialFailureMessage}: a trial's failure message may not attribute every
     * {@link ExecutionException} to the sandbox deadline. A previous draft of this sweep did exactly
     * that. The rule is recorded here because the next sweep in this family will likely copy one of
     * these, and copying the wrong one reintroduces the defect.
     *
     * <p>A genuine deadline expiry is distinguishable rather than assumed: {@code GraalVmProgramRuntime}
     * reports it as a {@link ProgramDeadlineExceededException}, from {@code
     * checkDeadline}, from either bounded wait, or from {@code SandboxOutcome.DEADLINE_EXCEEDED}.
     * Anything else is reported as what it actually is.
     */
    private static String lifecycleTrialFailureMessage(int trial, ExecutionException error) {
        Throwable cause = error.getCause();
        if (isSandboxDeadlineExceeded(cause)) {
            return "trial " + trial + " under synthetic CPU load failed with \"" + cause
                    + "\" -- this is FIX-31's exact regression: the 5 s sandbox deadline being raced "
                    + "by something that should not be on the clock at all";
        }
        return "trial " + trial + " under synthetic CPU load failed with "
                + (cause == null ? "no cause" : cause.getClass().getName() + ": " + cause.getMessage())
                + " -- NOT the 5 s sandbox deadline (a genuine deadline expiry reports "
                + "ProgramDeadlineExceededException); this is the actual, observed "
                + "cause and is what needs investigating";
    }

    /**
     * FIX-31 applying FIX-33's diagnostic bar to its own new message: the red control
     * proving {@link #lifecycleTrialFailureMessage} reports the cause it observed rather than the one
     * it expected. The same mechanism uses an empty pre-set response, which makes the session report
     * COMPLETED with a zero-length payload, so {@code ProgramWireProtocol.readResponse} hits end of
     * stream on the magic number. A real failure with nothing to do with any deadline.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void lifecycleTrialFailureMessageNamesTheActualCauseNotTheDeadline() {
        var supervisor = new FakeSupervisor();
        supervisor.response = new byte[0];
        var runtime = new GraalVmProgramRuntime(supervisor, policy(Duration.ofSeconds(5)));
        var error = assertThrows(ExecutionException.class, () -> runtime
                .validate(artifact("() => 1", ArtifactState.GENERATED)).toCompletableFuture().get());
        assertTrue(error.getCause() instanceof EOFException,
                "test setup did not reproduce the empty-response mechanism; got " + error.getCause());

        String message = lifecycleTrialFailureMessage(1, error);
        assertTrue(message.contains("EOFException"),
                "message must report the actually observed cause type instead of a fixed one; got: "
                        + message);
        assertTrue(!message.contains("5 s sandbox deadline being raced"),
                "message must not attribute a non-deadline failure (EOFException) to the sandbox "
                        + "deadline; got: " + message);
    }

    /**
     * The green counterpart: when the cause genuinely is the sandbox deadline, the
     * message must stay as informative as the one it replaced rather than regress to a bare cause
     * dump. No clock race is needed to prove it -- forcing the outcome drives
     * {@code invokeSupervisor} through the same {@code sandboxFailure(outcome)} a real expiry uses.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void lifecycleTrialFailureMessageStaysInformativeForAGenuineDeadline() {
        var supervisor = new FakeSupervisor();
        supervisor.outcome = SandboxSupervisorLauncher.SandboxOutcome.DEADLINE_EXCEEDED;
        var runtime = new GraalVmProgramRuntime(supervisor, policy(Duration.ofSeconds(5)));
        var error = assertThrows(ExecutionException.class, () -> runtime
                .validate(artifact("() => 1", ArtifactState.GENERATED)).toCompletableFuture().get());

        String message = lifecycleTrialFailureMessage(1, error);
        assertTrue(message.contains("5 s sandbox deadline being raced"),
                "a genuine deadline expiry must stay as informative as the message this replaced; got: "
                        + message);
    }

    private void runOnePolicyTrial() throws Exception {
        var supervisor = new FakeSupervisor();
        supervisor.response = FakeSupervisor.wellFormedValidateResponse();
        var runtime = new GraalVmProgramRuntime(supervisor, policy(Duration.ofMillis(750)));
        int trial = policyTrialsRun + 1;
        try {
            // This was a caller-side get(5, SECONDS) on a pure in-memory replay path: the same defect
            // class FIX-31 removes, a wall-clock wait whose breach would have been reported as this
            // sweep's own regression message rather than as the environment failure it is. Unbounded
            // now, under this method's existing @Timeout(120), so a stall cannot impersonate a
            // policy-serialization failure.
            runtime.validate(artifact("() => 1", ArtifactState.GENERATED))
                    .toCompletableFuture().get();
        } catch (ExecutionException error) {
            throw new AssertionError(policyTrialFailureMessage(trial, error), error);
        }
        assertEquals(1, supervisor.launches,
                "trial " + trial + ": the runtime must have launched exactly one supervisor session, "
                        + "otherwise supervisor.policy is whatever a previous trial left behind and the "
                        + "argument assertion below proves nothing");
        assertEquals(0, supervisor.realSubprocessSpawns,
                "trial " + trial + " spawned a real child JVM; policy serialization is a property of the "
                        + "request the product builds, and must never depend on a worker running");
        assertEquals(EXPECTED_POLICY_ARGUMENTS, supervisor.policy.arguments(),
                "trial " + trial + " serialized a different policy argument vector");
        policyTrialsRun++;
    }

    /**
     * Builds {@code runOnePolicyTrial}'s failure message without assuming the
     * cause. A previous version unconditionally blamed the 750 ms sandbox deadline for every
     * {@link ExecutionException}: a message asserting a cause it never established.
     *
     * <p>A genuine deadline expiry IS distinguishable at this point: {@code GraalVmProgramRuntime}
     * reports it as a {@link ProgramDeadlineExceededException} from every one
     * of its deadline sites -- {@code checkDeadline}'s four checks, the two bounded waits, and
     * {@code SandboxOutcome.DEADLINE_EXCEEDED} -- so the match is now on a type rather than on a
     * string. Previously it was an {@link IllegalStateException} with the message
     * {@code "SANDBOX_DEADLINE_EXCEEDED"} from the checks and a RAW {@link TimeoutException} from the
     * bounded waits; both are still matched below, because this predicate also serves trials whose
     * failure originates outside the adapter. Anything else is
     * reported as what it actually is -- the real cause's class and message, which is bounded by
     * definition because it is exactly what {@link ExecutionException#getCause()} returned --
     * instead of a fabricated deadline claim or an uninformative "unknown".
     */
    private static String policyTrialFailureMessage(int trial, ExecutionException error) {
        Throwable cause = error.getCause();
        if (isSandboxDeadlineExceeded(cause)) {
            return "trial " + trial + " under synthetic CPU load failed with \"" + cause
                    + "\" -- this is FIX-30's exact regression: the 750 ms sandbox deadline being "
                    + "raced by something that should not be on the clock at all";
        }
        return "trial " + trial + " under synthetic CPU load failed with "
                + (cause == null ? "no cause" : cause.getClass().getName() + ": " + cause.getMessage())
                + " -- NOT the 750 ms sandbox deadline (a genuine deadline expiry reports "
                + "ProgramDeadlineExceededException); this is the actual, observed "
                + "cause and is what needs investigating";
    }

    private static boolean isSandboxDeadlineExceeded(Throwable cause) {
        return cause instanceof ProgramDeadlineExceededException
                || cause instanceof TimeoutException
                || cause instanceof IllegalStateException
                && "SANDBOX_DEADLINE_EXCEEDED".equals(cause.getMessage());
    }

    private void runOneDeniedTrial(byte[] workerBytes, GeneratedArtifact artifact, ProgramRequest request) {
        var denied = new FakeSupervisor();
        denied.response = workerBytes;
        var runtime = new GraalVmProgramRuntime(denied, policy(Duration.ofSeconds(3)));
        int trial = deniedTrialsRun + 1;
        var error = assertThrows(ExecutionException.class,
                () -> runtime.test(artifact, request).toCompletableFuture().get());
        assertTrue(error.getCause() instanceof IllegalArgumentException,
                "trial " + trial + " under synthetic CPU load surfaced \"" + error.getCause()
                        + "\" instead of IllegalArgumentException -- this is FIX-29's exact regression: "
                        + "a real child JVM racing the sandbox deadline resurfacing under load");
        assertEquals(0, denied.realSubprocessSpawns,
                "trial " + trial + " spawned a real child JVM under the sandbox deadline; the only "
                        + "legitimate worker run is the single un-raced RealWorkerRun.capture above");
        deniedTrialsRun++;
    }

    private void runOneTrial() throws Exception {
        var oversized = new FakeSupervisor();
        oversized.response = new byte[2 * 1024 * 1024 + 1];
        var runtime = new GraalVmProgramRuntime(oversized, policy(Duration.ofSeconds(1)));
        int trial = trialsRun + 1;
        var bound = assertThrows(ExecutionException.class, () -> runtime
                .test(artifact("() => 1", ArtifactState.VALIDATED), request("x"))
                .toCompletableFuture().get());
        assertTrue(bound.getCause().getMessage().contains("SANDBOX_OUTPUT_LIMIT"),
                "trial " + trial + " under synthetic CPU load returned \"" + bound.getCause().getMessage()
                        + "\" instead of SANDBOX_OUTPUT_LIMIT -- this is FIX-27's exact regression: a "
                        + "real child JVM race resurfacing under load");
        assertEquals(0, oversized.realSubprocessSpawns,
                "trial " + trial + " spawned a real child JVM it should never spawn");
        trialsRun++;
    }

    private static Runnable spin(AtomicBoolean keepSpinning) {
        return () -> {
            long x = 0;
            while (keepSpinning.get()) {
                x = x * 31 + 7;
                if (x == Long.MIN_VALUE) {
                    x = 1; // never true; keeps the JIT from proving the loop body is dead.
                }
            }
        };
    }

    private static SandboxPolicy policy(Duration timeout) {
        return new SandboxPolicy(timeout, Math.toIntExact(timeout.toMillis()), 64, 32, 256, 64,
                2 * 1024 * 1024, Path.of(System.getProperty("java.class.path")), "worker-test-id",
                JAVA, "jre-test-id");
    }

    private static ProgramRequest request(Object payload) {
        return new ProgramRequest(UUID.randomUUID(), "program-test", payload, Map.of("count", 1));
    }

    private static GeneratedArtifact artifact(String source, ArtifactState state) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
            Instant now = Instant.now();
            return new GeneratedArtifact("test-artifact", "javascript", hash, source, state, 1, now, now,
                    Map.of());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
