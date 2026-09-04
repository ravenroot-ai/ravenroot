package ai.ravenroot.programming.graalvm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Test-only direct worker adapter. Production has no implementation of this behavior. Shared by
 * {@link GraalVmProgramRuntimeTest} and {@link GraalVmProgramRuntimeLoadSensitivityTest} (FIX-27)
 * so the load-sensitivity regression exercises the exact same fake, not a reimplementation
 * that could quietly drift from it.
 */
final class FakeSupervisor implements SandboxSupervisorLauncher {
    private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");

    volatile int capabilityChecks, launches, terminations;
    /**
     * How many times this supervisor actually spawned a real child JVM to run
     * {@link GraalVmWorkerMain}. FIX-27: {@link Session#await} skips the spawn whenever
     * {@link #response} is pre-set, since the real worker's output would be discarded anyway --
     * this counter is what proves that skip actually happens, deterministically, rather than
     * merely "usually".
     */
    volatile int realSubprocessSpawns;
    volatile boolean reaped, block;
    volatile java.util.concurrent.CountDownLatch launchEntered;
    volatile java.util.concurrent.CountDownLatch releaseLaunch;
    /**
     * Opt-in stalls for the adapter's <b>two bounded waits</b> — the request write and the wait on
     * worker diagnostics — which require direct coverage in addition to explicit deadline checks.
     *
     * <p><b>Why a stall and not a short budget.</b> Those two sites are reached through
     * {@code Future.get(timeout)}, which answers a raw {@link java.util.concurrent.TimeoutException}.
     * A merely small budget does not reach them: the explicit {@code checkDeadline} calls that bracket
     * them fire first and produce the condition through a different path, so the test would pass with
     * the two {@code try/catch} blocks deleted. Making the stream itself never answer is what forces
     * the wait to be the thing that expires, and therefore what makes those two blocks load-bearing.
     *
     * <p>Both default {@code false}, so every fixture that existed before this field behaves exactly
     * as it did. Neither is a model of the real launcher: production streams block on a pipe to a
     * child process, which is the same observable fact — a read or a write that does not return
     * within the budget — reproduced without depending on how loaded the machine is.
     */
    volatile boolean stallWorkerInput, stallDiagnostics;
    /**
     * The exact bytes the runtime wrote to the worker's stdin, captured when the session
     * is awaited. This is the only honest answer to "which artifact did the adapter actually execute":
     * reading it back from the admission would let the assertion agree with the runtime merely because
     * both consulted the same object.
     */
    volatile byte[] writtenRequest;
    String capabilityError;
    SandboxOutcome outcome = SandboxOutcome.COMPLETED;
    /**
     * Volatile because it is now REASSIGNED BETWEEN invocations on the same
     * supervisor, by {@code GraalVmProgramRuntimeTest#validatesTestsAndExecutesJavascriptThroughTestSupervisor}
     * and by the lifecycle sweep, which replay a different recording per mode. The write is
     * technically already visible through the happens-before edge of the
     * {@code Thread.startVirtualThread} that {@code GraalVmProgramRuntime#invoke} performs after it,
     * but that is a chain no reader should have to reconstruct to trust a fixture.
     */
    volatile byte[] response;
    SandboxPolicy policy;

    /**
     * A well-formed worker success envelope for a VALIDATE call, carrying the
     * {@code null} result the real worker returns for that mode, written with the product's own
     * {@link ProgramWireProtocol#writeSuccess}. Pre-seeding it as {@link #response} lets
     * {@code validate()} complete with no child JVM and therefore nothing to await.
     *
     * <p><b>Why stubbing this is legitimate, and why it is NOT the vacuity defect FIX-29
     * rejected.</b> The distinction is what the assertion is ABOUT.
     *
     * <p>In the vacuous version, the thing under assertion WAS the worker's output, so authoring a response there
     * would have been authoring the evidence -- the test would have proved only that it could write
     * down its own expected answer. That test therefore records a real worker's bytes and replays
     * them, and why substituting a test-written envelope there is a mutation that must go red.
     *
     * <p>Here the thing under assertion is {@code supervisor.policy.arguments()}: the request THE
     * PRODUCT CONSTRUCTED and handed to the launcher, captured at {@code launch(policy)} before any
     * response exists. The worker's reply is not evidence, it is plumbing -- it exists only so
     * {@code validate()} returns and the assertion can run. Stubbing plumbing is legitimate; stubbing
     * evidence is not. A reader who sees "pre-seeded response" and stops there will reach the wrong
     * conclusion, which is why this is written down rather than left to inference.
     *
     * <p>The response deliberately does NOT carry the policy, the arguments, or anything else the
     * assertion reads. If it ever does, this comment has stopped being true.
     */
    static byte[] wellFormedValidateResponse() {
        var response = new ByteArrayOutputStream();
        try {
            ProgramWireProtocol.writeSuccess(response, null);
        } catch (IOException error) {
            throw new AssertionError("writing to a ByteArrayOutputStream cannot fail", error);
        }
        return response.toByteArray();
    }

    @Override
    public void verifyCapability() throws IOException {
        capabilityChecks++;
        if (capabilityError != null) {
            throw new IOException(capabilityError);
        }
    }

    @Override
    public SandboxSupervisorSession launch(SandboxPolicy policy) {
        launches++;
        this.policy = policy;
        if (launchEntered != null) launchEntered.countDown();
        if (releaseLaunch != null) {
            try { releaseLaunch.await(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("test launch barrier interrupted", interrupted);
            }
        }
        return new Session();
    }

    private final class Session implements SandboxSupervisorSession {
        private final ByteArrayOutputStream input = new ByteArrayOutputStream();
        private Process process;
        private byte[] control = new byte[0];
        /**
         * The outcome this session has already settled on, or {@code null} while it is
         * still undecided. A session resolves exactly once and then reports that same outcome for
         * every later {@link #await}.
         *
         * <p><b>Why this is required, not cosmetic.</b> {@code GraalVmProgramRuntime#cleanup} reaps
         * authoritatively: it calls {@code terminate(...)} and then {@code await(REAP_TIMEOUT)} to get
         * the supervisor's acknowledgement. Because this fake creates its child JVM lazily inside
         * {@code await} rather than inside {@code launch}, a session with no terminal state answered
         * that reap by starting a <b>second</b> real child JVM -- measured on the first half of
         * {@code GraalVmProgramRuntimeTest#preservesWorkerFailureAndOutputBounds} as
         * {@code launches=1, terminations=1, realSubprocessSpawns=2}, with the extra JVM sitting on
         * the critical path and adding up to {@code REAP_TIMEOUT} (observed wall time 5.03 s = the
         * 3 s deadline plus the 2 s reap).
         *
         * <p>No production code has this defect, and none is changed for it. The real
         * {@code SandboxSupervisorProcessLauncher.Session} starts its process in {@code launch} and
         * only ever observes it in {@code await}, so once {@code terminate} has destroyed and reaped
         * that process a second {@code await} returns immediately and cannot start a new sandbox. The
         * {@code RealisticFakeSupervisor.Session} in ravenroot-sandbox-supervisor-testkit models
         * the same rule with its own {@code resolved} field. This field brings this fake in line with
         * both; it does not invent session semantics.
         */
        private SandboxOutcome resolved;

        /**
         * Released by {@link #close()}, which {@code GraalVmProgramRuntime#invokeSupervisor} runs in
         * its {@code finally} on every path. Without it a stalled stream would leave its virtual
         * thread parked for the lifetime of the JVM running the suite; with it the thread unparks as
         * soon as the adapter is done with the session, so the stall lasts exactly as long as the
         * call it is meant to expire.
         */
        private final java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);

        @Override
        public OutputStream workerInput() {
            return stallWorkerInput ? new StalledOutputStream(released) : input;
        }

        @Override
        public java.io.InputStream supervisorControl() {
            return new ByteArrayInputStream(control);
        }

        @Override
        public java.io.InputStream diagnostics() {
            return stallDiagnostics ? new StalledInputStream(released) : new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void terminate(SandboxTermination termination) {
            terminations++;
            if (process != null) {
                process.destroyForcibly();
            }
            reaped = true;
            // A session that had not decided anything yet is settled by the termination itself, so the
            // reaping await that follows reports it instead of starting a fresh sandbox. An outcome
            // already reached is never overwritten: deadlineAndCancellationRequestOneAuthoritativeCleanup
            // asserts the DEADLINE_EXCEEDED it observed before cleanup ran, not the cleanup's reason.
            //
            // KNOWN, DELIBERATE DIVERGENCE FROM THE REAL LAUNCHER. Terminating an undecided session
            // here resolves it to CANCELLED,
            // matching RealisticFakeSupervisor. The real
            // SandboxSupervisorProcessLauncher.Session would instead report SETUP_FAILURE in the same
            // situation, because terminate() destroys the process and its await() then reads a
            // non-zero exitValue(). Consistency between the two fakes was chosen over fidelity to the
            // launcher on this one value, and no test asserts it: every caller that reaches this path
            // has already resolved the session, and GraalVmProgramRuntime#cleanup discards the reaping
            // await's return value entirely. If a future test ever DOES assert the post-terminate
            // outcome of an undecided session, it must decide which of the two models it means.
            if (resolved == null) {
                resolved = SandboxOutcome.CANCELLED;
            }
        }

        /**
         * Synchronized so a session resolves once even when the reaping await races the in-flight one,
         * as it does under cancellation.
         *
         * <p>No deadlock, and the reasoning is not reconstructible from the code alone. In the
         * {@code block} path the in-flight await holds this monitor while it spins on {@code reaped},
         * and {@code GraalVmProgramRuntime#cleanup} calls {@code terminate(...)} before its reaping
         * {@code await}. {@code terminate} is deliberately NOT synchronized, so it always gets through
         * and sets {@code reaped}; the spinning await then resolves and releases, and the reaping await
         * acquires the monitor and returns the settled outcome. Synchronizing {@code terminate} would
         * close that door and hang the cancellation test.
         */
        @Override
        public synchronized SandboxOutcome await(Duration remaining) throws Exception {
            // Captured before any early return: writeRequestBounded has already completed by the time
            // GraalVmProgramRuntime#invokeSupervisor awaits, so this is the whole request.
            writtenRequest = input.toByteArray();
            if (resolved != null) {
                return resolved;
            }
            if (block) {
                while (!reaped) {
                    Thread.sleep(1);
                }
                return resolved = SandboxOutcome.CANCELLED;
            }
            if (outcome != SandboxOutcome.COMPLETED) {
                return resolved = outcome;
            }
            byte[] worker;
            if (response != null) {
                // See the field Javadoc on FakeSupervisor.response / realSubprocessSpawns and the
                // class Javadoc on GraalVmProgramRuntimeTest#preservesWorkerFailureAndOutputBounds.
                worker = response;
            } else {
                realSubprocessSpawns++;
                process = new ProcessBuilder(JAVA.toString(), "-cp", System.getProperty("java.class.path"),
                        GraalVmWorkerMain.class.getName()).start();
                process.getOutputStream().write(input.toByteArray());
                process.getOutputStream().close();
                if (!process.waitFor(Math.max(1, remaining.toMillis()), TimeUnit.MILLISECONDS)) {
                    return SandboxOutcome.DEADLINE_EXCEEDED;
                }
                worker = process.getInputStream().readAllBytes();
            }
            var envelope = new ByteArrayOutputStream();
            var data = new DataOutputStream(envelope);
            data.writeInt(0x52525331);
            data.writeInt(1);
            data.writeByte(SandboxOutcome.COMPLETED.ordinal());
            data.writeInt(worker.length);
            data.write(worker);
            data.flush();
            control = envelope.toByteArray();
            reaped = true;
            return resolved = SandboxOutcome.COMPLETED;
        }

        @Override
        public void close() {
            released.countDown();
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    /** A worker stdin that never accepts the request. See {@link #stallWorkerInput}. */
    private static final class StalledOutputStream extends OutputStream {
        private final java.util.concurrent.CountDownLatch released;

        StalledOutputStream(java.util.concurrent.CountDownLatch released) {
            this.released = released;
        }

        @Override
        public void write(int b) throws IOException {
            park();
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            park();
        }

        private void park() throws IOException {
            try {
                released.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("stall interrupted", interrupted);
            }
        }
    }

    /** Worker diagnostics that never answer and never reach end of stream. See {@link #stallDiagnostics}. */
    private static final class StalledInputStream extends java.io.InputStream {
        private final java.util.concurrent.CountDownLatch released;

        StalledInputStream(java.util.concurrent.CountDownLatch released) {
            this.released = released;
        }

        @Override
        public int read() throws IOException {
            park();
            return -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            park();
            return -1;
        }

        private void park() throws IOException {
            try {
                released.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("stall interrupted", interrupted);
            }
        }
    }
}
