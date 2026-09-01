package ai.ravenroot.testkit.sandbox;

import ai.ravenroot.programming.graalvm.SandboxPolicy;
import ai.ravenroot.programming.graalvm.SandboxSupervisorLauncher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A supervisor that really does what it claims: it launches {@link SandboxConformanceProbe} as a
 * genuine child JVM and enforces each declared limit using real, observable mechanisms -- so that
 * disabling exactly one mechanism (via {@link #missing(Check)}) produces a genuinely non-compliant
 * supervisor, not a scripted stand-in that merely asserts what {@link SandboxSupervisorContract}
 * wants to hear.
 *
 * <h2>Determinism (regression fix of the earlier implementation)</h2>
 * <p>the earlier implementation enforced every limit from a single {@code Thread.sleep(20)} poll loop racing
 * against the machine's scheduler. the regression analysis proved this unreliable by interleaving this module's
 * tests against the unchanged, unrelated {@code ravenroot-programming-graalvm} module under load:
 * clean rate fell from apparently-100% to 5/17, including the COMPLIANT fake twice failing its
 * own disk check. A poll loop can miss a window entirely if the polling thread is descheduled for
 * longer than the window lasts -- that is not a tuning problem, it is the shape of the mechanism.
 *
 * <p>This regression fix replaces polling with two mechanisms that do not race the scheduler the same way:
 * <ol>
 *   <li><b>Blocking waits with a timeout</b> ({@link Process#waitFor(long, TimeUnit)}) for
 *       deadline and cancellation. This is not polling: the JDK's process-reaper implementation is
 *       notified by the OS when the child exits, rather than the calling thread re-checking on a
 *       fixed interval, and {@code destroyForcibly()} from another thread (as {@link #terminate}
 *       does) unblocks it directly and promptly.</li>
 *   <li><b>A single settle-then-check window</b> for PID count, output bytes and open-file count.
 *       Forking, printing a progress line, and filling an OS pipe buffer all happen essentially
 *       immediately relative to how long the probe then holds ({@link SandboxConformanceProbe}'s
 *       recipes are written so this is true by a wide margin), so ONE well-timed synchronous
 *       observation after a generous settle window is far more reliable than many closely-spaced
 *       ones racing scheduler jitter. Where the evidence persists after the process exits (disk
 *       usage, final CPU/wall-clock reading, final response bytes), the check runs once, after
 *       exit, with no timing element at all -- see {@link #evaluateExit}.</li>
 * </ol>
 *
 * <h2>Environment this suite's timing-sensitive assertions are meaningful in</h2>
 * <p>This fake still spawns real child JVMs and OS process trees and still measures real
 * wall-clock, CPU and file-growth behaviour for the dimensions where no timing-free alternative
 * exists (deadline, cancellation). Its settle windows and blocking-wait budgets are generous
 * (seconds, not milliseconds) specifically so that ordinary CI/dev-machine scheduling does not
 * starve them, and the stability of that claim is verified, not assumed -- see
 * {@code LoadInterleavedStabilityTest}, which runs this suite repeatedly while an unrelated module
 * runs concurrently and reports a clean/total ratio rather than trusting one green run. An
 * integrator running this suite against their OWN supervisor on a heavily oversubscribed host
 * (e.g. a parallel test runner sharing all cores) should still expect more scheduling jitter than
 * either environment was tuned against, and should re-run before concluding a red result
 * indicts their supervisor rather than the host.
 *
 * <p><b>Where the specific numbers came from (SEC-26), and what was wrong with them.</b>
 * The CPU-accounting 3x tolerance in {@code SandboxConformanceProbe.busy} was chosen empirically
 * and validated against a single {@code LoadInterleavedStabilityTest} run on a single development
 * machine -- not derived analytically, and its cleanliness rate needs to keep being observed over
 * time rather than treated as closed. The PID/OUTPUT/FILES settle window used to be exactly the
 * same shape (a fixed sleep validated the same empirical way), and that shape was not
 * just under-tuned but structurally wrong: a SINGLE point-in-time check after a fixed sleep can
 * fire before the workload it is checking has done anything, if the workload's own JVM startup is
 * delayed by contention on the very machine being measured -- and nothing re-checks afterward, so
 * that miss is not "rare," it is silent and permanent for that call. {@link #settle()} replaces
 * the fixed sleep with a loop of short, genuinely OS-blocking waits that keeps watching for as
 * long as the workload keeps running, up to a generous ceiling, so a violation is caught whenever
 * it actually happens rather than only if it happens to fall inside one fixed window. See that
 * method's own Javadoc for the mechanism.</p>
 */
final class RealisticFakeSupervisor implements SandboxSupervisorLauncher {

    /**
     * Length of one blocking step inside {@link Session#settle()}. Genuinely OS-blocking (a real
     * {@link Process#waitFor(long, TimeUnit)} call), not a CPU-spinning poll, so a short step does
     * not add meaningful load to a machine that is already contended -- it only adds granularity:
     * a violation or the workload's own exit is never missed by more than this long.
     */
    private static final Duration SETTLE_STEP = Duration.ofMillis(100);

    /**
     * Upper bound on how long {@link Session#settle()} keeps watching before giving up and
     * concluding no violation occurred. Chosen to be comfortably larger than the old fixed 1200ms
     * single-shot window (over 4x) while still leaving ample headroom under this contract's 20s
     * per-test {@code @Timeout} once the subsequent deadline-bounded wait is added on top -- see
     * {@code SandboxSupervisorContract}'s {@code GENEROUS} (8s) for the same order of magnitude
     * used elsewhere in this suite for the same reason.
     */
    private static final Duration SETTLE_CEILING = Duration.ofSeconds(5);

    /**
     * One independently toggleable enforcement mechanism. {@code PID}, {@code DISK},
     * {@code OUTPUT} and {@code FILES} violations are reported as {@code SETUP_FAILURE} and
     * {@code CPU} violations as {@code DEADLINE_EXCEEDED} -- an internal convention of THIS fake,
     * chosen because no {@code SandboxOutcome} member names any of them specifically;
     * {@link SandboxSupervisorContract} only asserts {@code != COMPLETED} for these, precisely
     * because the exact value is not part of the contract.
     */
    enum Check { DEADLINE, CPU, MEMORY, PID, DISK, OUTPUT, FILES, CLEANUP, PROTOCOL }

    private final Set<Check> enforced;
    private final SandboxOutcome forcedOutcome;

    private RealisticFakeSupervisor(Set<Check> enforced, SandboxOutcome forcedOutcome) {
        this.enforced = enforced.isEmpty() ? EnumSet.noneOf(Check.class) : EnumSet.copyOf(enforced);
        this.forcedOutcome = forcedOutcome;
    }

    static RealisticFakeSupervisor fullyCompliant() {
        return new RealisticFakeSupervisor(EnumSet.allOf(Check.class), null);
    }

    /** Every mechanism enforced except {@code check} -- the deliberately non-compliant fake. */
    static RealisticFakeSupervisor missing(Check check) {
        var checks = EnumSet.allOf(Check.class);
        checks.remove(check);
        return new RealisticFakeSupervisor(checks, null);
    }

    /** Fully compliant, but {@code await()} returns {@code outcome} without running anything. */
    static RealisticFakeSupervisor forcing(SandboxOutcome outcome) {
        return new RealisticFakeSupervisor(EnumSet.allOf(Check.class), outcome);
    }

    @Override
    public void verifyCapability() {
        // This fake is always capability-available; SETUP_FAILURE is exercised through launch(),
        // not through capability verification -- see SandboxSupervisorContract's Javadoc on why
        // POLICY_REJECTED is out of scope for the portable contract.
    }

    @Override
    public SandboxSupervisorSession launch(SandboxPolicy policy) {
        return new Session(policy);
    }

    private final class Session implements SandboxSupervisorSession {
        private final SandboxPolicy policy;
        private final ByteArrayOutputStream input = new ByteArrayOutputStream();
        /**
         * Every byte ever read from the worker's stdout, across the settle phase and the final
         * post-exit drain. This is what {@code maxOutputBytes} measures (the total the worker
         * emitted) and what the FILES check's "OPENED n" progress line is parsed from.
         * {@link #evaluateExit} also reads this, but only after stripping any {@code OPENFILES}
         * progress marker (see {@link #stripFilesProgressMarker}): a recipe that prints an interim
         * progress line followed by a separate completion marker must not have the two
         * concatenated and then rejected as "not exactly OK".
         */
        private final ByteArrayOutputStream totalOutput = new ByteArrayOutputStream();
        private Process process;
        private long startNanos;
        private volatile boolean terminationRequested;
        private SandboxOutcome resolved;
        /** Non-silent per {@code SandboxSupervisorContract}'s environment note: exposed, not hidden. */
        private volatile boolean cpuFallbackUsed;
        /** Set by {@link #settle()} the instant a PID-count violation is observed; see its Javadoc. */
        private boolean settlePidViolation;

        Session(SandboxPolicy policy) {
            this.policy = policy;
        }

        @Override
        public OutputStream workerInput() {
            return input;
        }

        @Override
        public InputStream supervisorControl() {
            return new ByteArrayInputStream(totalOutput.toByteArray());
        }

        @Override
        public InputStream diagnostics() {
            return InputStream.nullInputStream();
        }

        boolean cpuFallbackUsed() {
            return cpuFallbackUsed;
        }

        @Override
        public void terminate(SandboxTermination termination) {
            terminationRequested = true;
            killTree();
        }

        @Override
        public synchronized SandboxOutcome await(Duration remaining) throws Exception {
            if (resolved != null) {
                return resolved;
            }
            if (forcedOutcome != null) {
                resolved = forcedOutcome;
                return resolved;
            }
            if (process == null) {
                ensureStarted();
            }
            if (process == null) {
                resolved = SandboxOutcome.SETUP_FAILURE;
                return resolved;
            }

            Path diskTarget = writeTargetIfAny();
            boolean needsSettle = enforced.contains(Check.PID) || enforced.contains(Check.OUTPUT)
                    || enforced.contains(Check.FILES);

            if (needsSettle) {
                settle();
                if (terminationRequested) {
                    killTree();
                    resolved = SandboxOutcome.CANCELLED;
                    return resolved;
                }
                if (settlePidViolation) {
                    killTree();
                    resolved = SandboxOutcome.SETUP_FAILURE; // this fake's convention; see Check
                    return resolved;
                }
                if (enforced.contains(Check.OUTPUT) && totalOutput.size() > policy.maxOutputBytes()) {
                    killTree();
                    resolved = SandboxOutcome.SETUP_FAILURE; // this fake's convention; see Check
                    return resolved;
                }
                if (enforced.contains(Check.FILES)) {
                    Integer opened = parseOpenedCount(totalOutput.toString(StandardCharsets.UTF_8));
                    if (opened != null && opened > policy.maxFiles()) {
                        killTree();
                        resolved = SandboxOutcome.SETUP_FAILURE; // this fake's convention; see Check
                        return resolved;
                    }
                }
            }

            // Deadline-bounded (or, if unenforced, caller-patience-bounded) BLOCKING wait -- an
            // OS-notified wait, not a userspace poll loop. destroyForcibly() from terminate() (on
            // another thread) unblocks this directly by making the process actually exit.
            Duration deadlineBudget = maxZero(policy.deadline().minus(elapsedSinceStart()));
            Duration waitBudget = enforced.contains(Check.DEADLINE) ? deadlineBudget : remaining;
            boolean exited = process.waitFor(waitBudget.toMillis(), TimeUnit.MILLISECONDS);
            if (terminationRequested) {
                killTree();
                resolved = SandboxOutcome.CANCELLED;
                return resolved;
            }
            if (!exited) {
                if (enforced.contains(Check.DEADLINE)) {
                    killTree();
                    resolved = SandboxOutcome.DEADLINE_EXCEEDED;
                    return resolved;
                }
                Duration remainingPatience = maxZero(remaining.minus(waitBudget));
                boolean exitedEventually = process.waitFor(remainingPatience.toMillis(), TimeUnit.MILLISECONDS);
                if (terminationRequested) {
                    killTree();
                    resolved = SandboxOutcome.CANCELLED;
                    return resolved;
                }
                if (!exitedEventually) {
                    killTree();
                    throw new TimeoutException("fake supervisor exceeded caller patience of " + remaining
                            + " without any mechanism resolving the session; this indicates a test-harness "
                            + "bound is too tight, not a real conformance result");
                }
            }

            // The process has exited. Everything left is a deterministic, non-racy point-in-time
            // check: the evidence (file size, final CPU/wall-clock reading, final response bytes)
            // persists after exit, so there is no window to miss. Whatever the settle-phase drain
            // above did not already consume is read here; either way it all lands in totalOutput.
            totalOutput.write(readAllQuietly(process.getInputStream()));

            if (enforced.contains(Check.DISK) && diskTarget != null
                    && sizeMibQuietly(diskTarget) > policy.tmpfsMiB()) {
                resolved = SandboxOutcome.SETUP_FAILURE; // this fake's convention; see Check
                return resolved;
            }
            if (enforced.contains(Check.CPU) && cpuMillisSoFar() > policy.cpuMillis()) {
                resolved = SandboxOutcome.DEADLINE_EXCEEDED; // this fake's convention; see Check
                return resolved;
            }
            resolved = evaluateExit();
            return resolved;
        }

        @Override
        public void close() {
            killTree();
        }

        /**
         * Watches for a PID, OUTPUT or FILES violation for as long as the workload keeps running,
         * up to {@link #SETTLE_CEILING} -- not a single point-in-time snapshot taken after a fixed
         * sleep. Each iteration is a genuine OS-blocking {@link Process#waitFor(long,
         * TimeUnit)} call of {@link #SETTLE_STEP}, so this does not spin: it takes only as many
         * steps as the workload actually needs before it exits, a violation is confirmed, or the
         * ceiling is reached, and each step costs no more CPU than the old design's one wait did.
         *
         * <p>Why the fixed window this replaces was not just under-tuned but wrong: the workload
         * being measured (the probe JVM's own startup, forking, or writing) can itself be delayed
         * by contention on the very machine this settle phase is trying to measure. A check that
         * fires once, at one fixed offset, can land before the workload has done anything --
         * observing zero descendants, zero bytes, no marker -- and nothing re-checked afterward, so
         * a real violation that only became observable a few hundred milliseconds later was missed
         * permanently, not merely late. Polling every {@link #SETTLE_STEP} for up to
         * {@link #SETTLE_CEILING} means a violation is caught whenever it actually happens, and a
         * PID violation in particular is caught immediately once the descendant count first crosses
         * the limit, rather than waiting for the ceiling.
         *
         * <p>Output is still drained unconditionally on every step (not only when OUTPUT/FILES are
         * enforced), for the same reason as before: an OUTPUT/FILES violation must be caught
         * promptly regardless of which other checks this fixture happens to have enabled, and
         * buffered-but-unread pipe data outlives a writer that has already exited, so reading it
         * early is always safe.
         *
         * <p><b>Never outlasts a shorter enforced deadline.</b> When {@code Check.DEADLINE} is
         * enforced, this loop's own ceiling is capped at whatever deadline budget remains, not just
         * {@link #SETTLE_CEILING}. Without that cap, a workload whose deadline is tighter than the
         * ceiling but that keeps running past it (exactly what
         * {@code theSupervisorEnforcesTheDeclaredDeadline} deliberately does: a 400ms deadline
         * against a 4s sleep) would have this loop wait all the way to the workload's own natural
         * exit before the deadline-enforcement code downstream ever got a chance to run -- silently
         * defeating deadline enforcement for every fixture that also enforces PID/OUTPUT/FILES,
         * which is all of them except {@code NonCompliantOnDeadline}. Verified against
         * {@code NonCompliantSupervisorRedControlTest}.
         */
        private void settle() throws InterruptedException, IOException {
            Duration ceiling = SETTLE_CEILING;
            if (enforced.contains(Check.DEADLINE)) {
                Duration remainingDeadline = maxZero(policy.deadline().minus(elapsedSinceStart()));
                if (remainingDeadline.compareTo(ceiling) < 0) {
                    ceiling = remainingDeadline;
                }
            }
            long deadline = System.nanoTime() + ceiling.toNanos();
            while (true) {
                long stepMillis = Math.min(SETTLE_STEP.toMillis(),
                        Math.max(0, (deadline - System.nanoTime()) / 1_000_000L));
                boolean exited = process.waitFor(stepMillis, TimeUnit.MILLISECONDS);
                if (terminationRequested) {
                    return;
                }
                totalOutput.write(readAvailableQuietly(process.getInputStream()));
                if (enforced.contains(Check.PID)) {
                    long descendants = process.toHandle().descendants().count();
                    if (descendants > policy.maxPids()) {
                        settlePidViolation = true;
                        return;
                    }
                }
                if (enforced.contains(Check.OUTPUT) && totalOutput.size() > policy.maxOutputBytes()) {
                    return;
                }
                if (enforced.contains(Check.FILES)) {
                    Integer opened = parseOpenedCount(totalOutput.toString(StandardCharsets.UTF_8));
                    if (opened != null && opened > policy.maxFiles()) {
                        return;
                    }
                }
                if (exited || System.nanoTime() >= deadline) {
                    return;
                }
            }
        }

        private Duration elapsedSinceStart() {
            return Duration.ofNanos(System.nanoTime() - startNanos);
        }

        private void ensureStarted() {
            var command = new ArrayList<String>();
            command.add(policy.trustedJre().toString());
            if (enforced.contains(Check.MEMORY)) {
                command.add("-Xmx" + policy.memoryMiB() + "m");
            }
            command.add("-cp");
            command.add(policy.trustedWorker().toString());
            command.add(SandboxConformanceProbe.class.getName());
            try {
                var builder = new ProcessBuilder(command);
                builder.redirectErrorStream(true);
                process = builder.start();
                startNanos = System.nanoTime();
                process.getOutputStream().write(input.toByteArray());
                process.getOutputStream().close();
            } catch (IOException notStarted) {
                process = null;
            }
        }

        /** Parses this session's own recipe for a WRITE target to poll -- see the class Javadoc. */
        private Path writeTargetIfAny() {
            var tokens = new StringTokenizer(input.toString(StandardCharsets.UTF_8));
            if (!tokens.hasMoreTokens() || !"WRITE".equals(tokens.nextToken())) {
                return null;
            }
            tokens.nextToken(); // mib, not needed here
            return Path.of(tokens.nextToken());
        }

        /**
         * See {@link SandboxConformanceProbe#busy}: real per-process CPU accounting is
         * authoritative. The wall-clock fallback below is a documented, non-silent, provisional
         * local accommodation for this environment (observed necessary on macOS/aarch64, where
         * {@link ProcessHandle.Info#totalCpuDuration()} reports empty/zero for a plain child JVM),
         * valid only because the probe's CPU-bound recipe is runtime-asserted single-threaded.
         */
        private long cpuMillisSoFar() {
            Duration reported = process.toHandle().info().totalCpuDuration().orElse(Duration.ZERO);
            if (!reported.isZero()) {
                return reported.toMillis();
            }
            cpuFallbackUsed = true;
            System.err.println("[ravenroot-sandbox-conformance] PROVISIONAL: "
                    + "ProcessHandle.Info.totalCpuDuration() was unavailable/zero for this child process; "
                    + "falling back to wall-clock-elapsed-since-start as a CPU-time proxy. This is a local "
                    + "accommodation for this platform/JDK, not part of the portable contract, and is valid "
                    + "only because SandboxConformanceProbe.busy() is runtime-asserted single-threaded.");
            return elapsedSinceStart().toMillis();
        }

        private long sizeMibQuietly(Path path) {
            try {
                return Files.size(path) / (1024 * 1024);
            } catch (IOException notYetCreated) {
                return 0L;
            }
        }

        private SandboxOutcome evaluateExit() {
            // OPENFILES's "OPENED n" progress marker is stripped before this content check runs:
            // it is real, correctly-drained output (and IS what the FILES/OUTPUT checks above
            // read), but it is not part of the completion response and must not make a
            // legitimately-completed OPENFILES run look like a garbled one.
            String text = stripFilesProgressMarker(totalOutput.toString(StandardCharsets.UTF_8));
            if (enforced.contains(Check.MEMORY) && text.contains("OutOfMemoryError")) {
                return SandboxOutcome.OUT_OF_MEMORY;
            }
            // "OK" covers every recipe except EMIT, whose legitimate (uncapped) response is a run
            // of the filler byte with no envelope at all -- recognised here, separately from OK,
            // so that a disabled OUTPUT check is what lets it through, not an accidental save by
            // the PROTOCOL check rejecting "not exactly OK" content.
            boolean recognized = "OK".equals(text) || isEmitFiller(text);
            if (!recognized && enforced.contains(Check.PROTOCOL)) {
                return SandboxOutcome.PROTOCOL_FAILURE;
            }
            return SandboxOutcome.COMPLETED;
        }

        private void killTree() {
            if (process == null) {
                return;
            }
            if (enforced.contains(Check.CLEANUP)) {
                process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
            }
            process.destroyForcibly();
        }
    }

    private static boolean isEmitFiller(String text) {
        return !text.isEmpty() && text.chars().allMatch(c -> c == 'X');
    }

    private static Integer parseOpenedCount(String text) {
        int marker = text.indexOf("OPENED ");
        if (marker < 0) {
            return null;
        }
        int start = marker + "OPENED ".length();
        int end = start;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        if (end == start) {
            return null;
        }
        try {
            return Integer.parseInt(text.substring(start, end));
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    /** Removes an "OPENED n" progress marker, if present, leaving everything else untouched. */
    private static String stripFilesProgressMarker(String text) {
        int marker = text.indexOf("OPENED ");
        if (marker < 0) {
            return text;
        }
        int end = marker + "OPENED ".length();
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        return text.substring(0, marker) + text.substring(end);
    }

    private static Duration maxZero(Duration value) {
        return value.isNegative() ? Duration.ZERO : value;
    }

    private static byte[] readAllQuietly(InputStream input) {
        try {
            return input.readAllBytes();
        } catch (IOException error) {
            return new byte[0];
        }
    }

    /** A single bounded, non-blocking-beyond-what's-already-buffered read -- not a poll. */
    private static byte[] readAvailableQuietly(InputStream input) {
        try {
            int n = input.available();
            if (n <= 0) {
                return new byte[0];
            }
            byte[] buffer = new byte[n];
            int read = input.read(buffer);
            return read <= 0 ? new byte[0] : java.util.Arrays.copyOf(buffer, read);
        } catch (IOException error) {
            return new byte[0];
        }
    }
}
