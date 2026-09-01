package ai.ravenroot.api.programming;

import java.time.Duration;

/**
 * The sandbox did not finish within this deployment's configured time budget, and it is not the
 * author's source that is wrong.
 *
 * <h2>The third door onto the same wrong sentence</h2>
 * <p>Two other routes by which infrastructure blamed the author's source are a source that does
 * not compile, and a deployment with no usable sandbox. This is the third, and two different runtime
 * paths previously misclassified it:</p>
 *
 * <ul>
 *   <li>a <b>raw {@link java.util.concurrent.TimeoutException}</b> escaping {@code
 *       GraalVmProgramRuntime.invokeSupervisor} — from the bounded request write, or from the wait
 *       on worker diagnostics — fell through {@code RavenrootServer.artifactFailureCode}'s default
 *       branch and was answered <b>400 {@code the request was rejected as invalid}</b>;</li>
 *   <li>the far commoner one: the deadline expiring anywhere the runtime checks it explicitly, which
 *       raised {@code IllegalStateException("SANDBOX_DEADLINE_EXCEEDED")} and was answered
 *       <b>409 {@code the request conflicts with the current state of the resource}</b> — a sentence
 *       identical in every diagnostic field to the one a genuine state conflict produces.</li>
 * </ul>
 *
 * <p>Neither sentence is true. A request that timed out was well formed, it named a real artifact,
 * and it conflicts with nothing: the machine was too busy, or the budget too small. The 409 was the
 * worse of the two precisely because it looked deliberate — it names a cause, so a reader believes
 * it rather than suspecting the classification.</p>
 *
 * <h2>Why this is not a {@code ProgramRuntimeUnavailableException} reason</h2>
 * <p>That type's two reasons answer <b>501</b> because each is a fact about this deployment's
 * <em>capability</em>: no runtime adapter, or no usable sandbox. A deadline is a different kind of
 * fact — the capability was present and worked, and only the clock ran out on one run against a
 * configured budget. It is temporary and load-dependent: GraalPy's cold start was measured at 2929 ms
 * against a budget that was 5000 ms, so the same request that fails on a loaded machine
 * succeeds on an idle one. A retry, or a larger budget, is the correct response, which is what
 * {@link ai.ravenroot.api.error.ErrorCode#PROGRAM_EXECUTION_TIMEOUT}'s 504 invites. Folding it into a
 * vocabulary whose members mean "an operator must change this deployment" would have addressed the
 * wrong person.</p>
 *
 * <p><b>Note what this does not say.</b> It does not say that a 501 in this deployment always holds
 * until a person intervenes — {@code SandboxSupervisorProcessLauncher.verifyCapability()} bounds its
 * capability probe at two seconds and answers 501 when that bound is missed, on every request, so
 * under load it produces a 501 a retry would clear. That is a pre-existing defect of the probe's own
 * classification and a separate defect; the reasoning above deliberately does not lean on the
 * universal claim it would falsify. {@code ErrorCode.PROGRAM_EXECUTION_TIMEOUT} carries the full
 * account.</p>
 *
 * <h2>The numbers travel on the exception, not to the caller</h2>
 * <p>{@link #budget()} and {@link #waited()} exist so the server can write them to its own log, the
 * same server-log mechanism used for the sandbox-unavailable case and for the same reason: the
 * caller-facing message is a server-authored literal owned by {@code ErrorCode}, so nothing derived
 * from this exception reaches the wire. An operator deciding whether to raise
 * {@code RAVENROOT_GRAAL_TIMEOUT_MS} needs to know the budget was 100 ms and the wait was 103 ms;
 * the author who triggered it needs only to know it was not their source.</p>
 */
public final class ProgramDeadlineExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

/** Fixed stage token identifying where the configured budget expired. */
    private final String stage;
/** Configured maximum sandbox execution duration. */
    private final Duration budget;
/** Measured elapsed duration when the deadline was observed. */
    private final Duration waited;

    /**
     * Creates a deadline-exceeded failure with its execution-stage evidence.
     * @param stage  where the budget ran out, as a fixed token this repository chooses (not free text
     *               derived from a caller). The adapter produces exactly seven: {@code before_launch},
     *               {@code after_launch}, {@code write_request}, {@code after_request_write},
     *               {@code sandbox_outcome}, {@code diagnostics}, {@code after_response}. (This list
     *               previously named {@code deadline_check}, which no code path has ever emitted, and
     *               omitted four that are emitted — it was written from memory rather than from the
     *               call sites.) It is for the server log; note that the token says where the budget
     *               ran out, <b>not</b> how far the run had got, since at {@code after_request_write}
     *               and {@code sandbox_outcome} whether the worker ran is not established.
     * @param budget the total time the sandbox was allowed, i.e. this deployment's configured policy
     *               deadline — never null.
     * @param waited how much of it had actually elapsed when the condition was detected. It can
     *               exceed {@code budget}: the check is not instantaneous, and reporting the honest
     *               overshoot is more useful to an operator than a value clamped to look tidy.
     */
    public ProgramDeadlineExceededException(String stage, Duration budget, Duration waited) {
        super("SANDBOX_DEADLINE_EXCEEDED");
        this.stage = java.util.Objects.requireNonNull(stage, "stage");
        this.budget = java.util.Objects.requireNonNull(budget, "budget");
        this.waited = java.util.Objects.requireNonNull(waited, "waited");
    }

/**
 * Creates a timeout diagnosis that retains the triggering infrastructure failure.
 * @param stage fixed execution-stage token at which the deadline was detected.
 * @param budget configured maximum duration for this execution.
 * @param waited elapsed duration measured when the deadline was detected.
 * @param cause underlying runtime failure, if one caused deadline observation.
 */
    public ProgramDeadlineExceededException(String stage, Duration budget, Duration waited, Throwable cause) {
        this(stage, budget, waited);
        initCause(cause);
    }

/**
 * Returns the fixed token naming the stage that exhausted the budget.
 * @return stage token suitable for operator logs.
 */
    public String stage() {
        return stage;
    }

/**
 * Returns the configured execution deadline.
 * @return maximum permitted sandbox duration.
 */
    public Duration budget() {
        return budget;
    }

/**
 * Returns the observed elapsed duration, which may exceed the configured budget.
 * @return elapsed time at deadline detection.
 */
    public Duration waited() {
        return waited;
    }
}
