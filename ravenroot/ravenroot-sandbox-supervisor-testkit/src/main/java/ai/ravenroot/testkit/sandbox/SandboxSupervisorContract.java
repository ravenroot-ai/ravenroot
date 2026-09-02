package ai.ravenroot.testkit.sandbox;

import ai.ravenroot.programming.graalvm.SandboxPolicy;
import ai.ravenroot.programming.graalvm.SandboxSupervisorLauncher;
import ai.ravenroot.programming.graalvm.SandboxSupervisorLauncher.SandboxOutcome;
import ai.ravenroot.programming.graalvm.SandboxSupervisorLauncher.SandboxSupervisorSession;
import ai.ravenroot.programming.graalvm.SandboxSupervisorLauncher.SandboxTermination;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SEC-11b. Conformance tests every {@code SandboxSupervisorLauncher} implementation must pass,
 * whether it wraps a real integrator-owned OS-level supervisor or (for this repository's own
 * evidence) a fake standing in for one. A third-party author extends this class, returns their
 * launcher from one method, and inherits every assertion -- the same shape as
 * {@code NodeBehaviorContract} (CORE-06), {@code ExecutionEngineContract} and
 * {@code ExecutionStoreContract} already use in this repository.
 *
 * <p><b>What this is not.</b> SEC-11's option B ruling is that the OS sandbox supervisor is
 * integrator-provided, not written or shipped by Ravenroot, and {@code program} stays
 * non-executable in the default distribution. This is therefore not, and must never become, an
 * adversarial escape suite run in CI against a binary this project owns -- that was SEC-11's
 * original, superseded documented requirements. It is a suite an integrator runs, deliberately, against their own
 * implementation, before trusting it.</p>
 *
 * <p><b>What "the contract" means here.</b> {@code SandboxPolicy} and {@code SandboxSupervisorLauncher}
 * declare a generic process-isolation contract: {@code trustedWorker}/{@code trustedJre} are
 * caller-supplied paths, not hardcoded to any one language runtime. This suite therefore drives
 * {@link SandboxConformanceProbe}, a minimal workload this module ships and compiles fresh on
 * every build, rather than {@code ai.ravenroot.programming.graalvm.GraalVmWorkerMain} and its
 * package-private wire protocol, which are internal to the JavaScript-specific adapter and not
 * part of this contract.</p>
 *
 * <p><b>Coverage.</b> Every numeric limit {@code SandboxPolicy} declares gets its own test that
 * supplies a workload engineered to breach exactly that one limit while satisfying every other, so
 * a supervisor that enforces every limit but one still fails on precisely the one it does not
 * enforce: deadline, CPU, memory, PID count, disk (tmpfs), output byte count and open-file count.
 * (The contract requirement for this suite -- "covers every limit declared by {@code
 * SandboxPolicy}" -- is the binding checklist; an earlier draft narrowed this to the six
 * dimensions an earlier checklist happened to list and left {@code maxOutputBytes} and
 * {@code maxFiles} as a declared, undefended gap. Declaring a gap does not close it: both are
 * provoked exactly the way the rest of this contract provokes everything else, and both now have
 * red controls. {@code maxOutputBytes} is the security-relevant one of the two -- unbounded output
 * is a denial-of-service vector against a supervisor's own buffers.) Cancellation gets a test that
 * also asserts complete process-tree cleanup, not merely the reported outcome -- cleanup is the
 * easiest of the declared limits to fake. Six of the nine {@code SandboxOutcome} values are
 * exercised here with deterministic, portable scenarios: {@code COMPLETED},
 * {@code DEADLINE_EXCEEDED}, {@code OUT_OF_MEMORY}, {@code CANCELLED}, {@code SETUP_FAILURE} and
 * {@code PROTOCOL_FAILURE}. {@code POLICY_REJECTED}, {@code SECCOMP_DENIED} and
 * {@code REAP_FAILED} name failure modes that belong to a specific supervisor's own internal
 * mechanism (an unsatisfiable policy shape, a kernel seccomp denial, a reap that timed out); no
 * portable, JVM-only workload can provoke a compliant supervisor into one on demand, so this
 * contract does not assert on them -- that impossibility argument does not extend to
 * {@code maxOutputBytes}/{@code maxFiles}, which is exactly why those two are covered here and
 * those three are not. This module's own {@code src/test/java} instead demonstrates, with a
 * scripted double documented as such, that the {@code SandboxOutcome} enum's remaining members
 * round-trip through the SPI correctly -- see {@code RemainingOutcomesCoverageTest}.</p>
 *
 * <p><b>Environment these results are meaningful in.</b> This contract spawns real child JVMs and
 * OS process trees and measures real wall-clock, CPU and file-growth behaviour for the dimensions
 * where no timing-free alternative exists. Its budgets are generous (seconds) specifically so
 * ordinary CI/dev-machine scheduling does not starve them, and that stability claim is verified,
 * not assumed -- see {@code LoadInterleavedStabilityTest} for a repeated, load-interleaved
 * clean/total ratio rather than one trusted green run. Running this suite on a heavily
 * oversubscribed host (for example a parallel test runner sharing every core with unrelated work)
 * should be expected to introduce scheduling jitter beyond what either environment was tuned
 * against; an integrator seeing an isolated red result there should re-run before concluding it
 * indicts their own supervisor rather than the host it ran on.</p>
 *
 * <p><b>The specific budgets, and what "verified" actually means (SEC-26).</b>
 * The 3x CPU-accounting tolerance ({@code SandboxConformanceProbe.busy}'s single-threadedness
 * check) was chosen empirically and validated against a SINGLE {@code LoadInterleavedStabilityTest}
 * run on a SINGLE development machine, not derived analytically and not validated across a fleet
 * of machines or over repeated runs on this one; the honest posture is to keep observing it over
 * time (repeated CI runs, different hardware, different schedulers) rather than treating any
 * single clean run as closed, permanent proof. If it degrades on a class of machine or CI runner
 * this suite has not yet been measured on, the tolerance is the first thing to revisit, not the
 * supervisor under test. The PID/OUTPUT/FILES settle phase this note used to also cover
 * ({@code RealisticFakeSupervisor.SETTLE}, a single fixed 1200ms sleep) no longer has that
 * character: the fixed window could silently and permanently miss a violation whose probe process
 * was itself delayed by contention on the machine being measured, so it was replaced
 * with {@code RealisticFakeSupervisor.Session#settle()}, which watches continuously for as long as
 * the workload runs, up to a generous ceiling -- a mechanism, not a tuned constant, so it has
 * nothing analogous to "revisit the number" left to say.</p>
 *
 * <p>Passing is necessary, not sufficient: it says the supervisor enforces the limits this suite
 * knows how to provoke portably. It is not a certification that the supervisor is safe against a
 * genuinely adversarial workload -- that determination is the integrator's, using tools that own
 * the binary under test, which this suite deliberately is not.</p>
 */
public abstract class SandboxSupervisorContract {

    private static final Duration GENEROUS = Duration.ofSeconds(8);
    private static final Path PROBE_JRE = Path.of(System.getProperty("java.home"), "bin", "java");

    /**
     * A single, genuine filesystem {@link Path} suitable for {@code SandboxPolicy.trustedWorker()}
     * -- the SPI declares that field as a single {@code Path}, so this suite must hand every
     * launcher one, not a colon-joined string masquerading as one.
     *
     * <p><b>The precondition this resolves, documented and verified at startup (SEC-26).</b>
     * {@code System.getProperty("java.class.path")} is a {@code File.pathSeparator}-joined list
     * that is very often MULTI-entry (this module's own {@code test-classes} directory, its
     * {@code classes} directory, {@code ravenroot-programming-graalvm}'s classes, every dependency
     * jar -- an ordinary {@code mvn test} run under this repository's Surefire configuration
     * produces on the order of thirty entries, not one). Wrapping that whole string in a single
     * {@code Path.of(...)} used to compile without error -- POSIX path parsing does not treat
     * {@code :} as a separator -- but the result did not name any real file: {@code Files.exists()}
     * on it returns {@code false}, and any code that treats {@code trustedWorker} as a genuine,
     * resolvable path (exactly what its declared type promises, and exactly what a careful
     * integrator's OWN {@code SandboxSupervisorLauncher} implementation is entitled to do, e.g. a
     * sanity {@code Files.exists()} check before spawning a child) would reject it -- failing a
     * PERFECTLY VALID supervisor against a broken precondition this suite handed it, not against
     * anything the supervisor did wrong. That the multi-entry string still worked when used
     * PURELY as an opaque {@code -cp} argument string (see {@code RealisticFakeSupervisor}, which
     * only ever calls {@code .toString()} on it) was an accident of that one call site, not a
     * property of the field's contract.
     *
     * <p>This method resolves that precondition instead of assuming it: when {@code
     * java.class.path} already names exactly one entry, it is used directly (and verified to
     * exist). When it names more than one, this method synthesizes a genuine single-entry JAR
     * file on disk whose manifest {@code Class-Path} attribute lists every original entry as an
     * absolute {@code file:} URI (the JAR File Specification explicitly permits absolute URLs in
     * this attribute, not only relative ones) and returns THAT jar's path -- a real, single,
     * {@code Files.exists()}-true {@link Path} that resolves to the exact same effective classpath
     * when a JVM is launched with {@code -cp <thatJar>}. If the synthesis itself cannot complete
     * (I/O failure), this fails loudly at class-initialization time with the observed entry count
     * and cause, before any test in a subclass runs -- not as a confusing per-test
     * {@code SETUP_FAILURE} attributed to the supervisor under test.
     */
    private static final Path PROBE_CLASSPATH = resolveProbeClasspath();

    /** The launcher under test. Called repeatedly; return a launcher ready for a fresh session each time. */
    protected abstract SandboxSupervisorLauncher launcher();

    // -- COMPLETED -----------------------------------------------------------------------------

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void aWorkloadWithinEveryDeclaredLimitCompletes() throws Exception {
        SandboxOutcome outcome = run(policy(GENEROUS, 5_000, 128, 8, 64, 8), "COMPLETE");
        assertEquals(SandboxOutcome.COMPLETED, outcome,
                "a workload that stays within every declared limit must report COMPLETED");
    }

    // -- Deadline --------------------------------------------------------------------------------

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void theSupervisorEnforcesTheDeclaredDeadline() throws Exception {
        SandboxPolicy tight = policy(Duration.ofMillis(400), 60_000, 128, 8, 64, 8);
        SandboxOutcome outcome = run(tight, "SLEEP 4000");
        assertEquals(SandboxOutcome.DEADLINE_EXCEEDED, outcome,
                "a workload that sleeps well past the declared deadline, with no other limit even "
                        + "close to breached, must be terminated and reported as DEADLINE_EXCEEDED, not "
                        + "COMPLETED and not left to the caller's own timeout");
    }

    // -- CPU ---------------------------------------------------------------------------------------

    /**
     * A compliant launcher may satisfy this by reading real per-process CPU accounting or, where
     * that is unavailable on the host JDK/platform, by a documented, NON-SILENT wall-clock
     * fallback (see {@code RealisticFakeSupervisor.cpuMillisSoFar}, which announces the fallback
     * to stderr every time it is used, rather than letting a green run be silently ambiguous about
     * which mechanism actually ran) that is only valid for a verified-single-threaded CPU-bound
     * workload -- see the runtime assertion in {@code SandboxConformanceProbe.busy}.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void theSupervisorEnforcesTheDeclaredCpuBudget() throws Exception {
        // Deadline is generous (8s) so this isolates CPU enforcement from deadline enforcement:
        // a supervisor that only checks wall clock would let this workload run to completion.
        SandboxPolicy tightCpu = policy(GENEROUS, 250, 128, 8, 64, 8);
        SandboxOutcome outcome = run(tightCpu, "BUSY 4000");
        assertNotEquals(SandboxOutcome.COMPLETED, outcome,
                "a workload that spends roughly 4s of real CPU time against a 250ms CPU budget, "
                        + "well inside an 8s deadline, must not be reported as COMPLETED");
    }

    // -- Memory --------------------------------------------------------------------------------

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void theSupervisorEnforcesTheDeclaredMemoryLimit() throws Exception {
        SandboxPolicy tightMemory = policy(GENEROUS, 60_000, 32, 8, 64, 8);
        SandboxOutcome outcome = run(tightMemory, "ALLOCATE 256");
        assertEquals(SandboxOutcome.OUT_OF_MEMORY, outcome,
                "a workload that retains 256 MiB of live heap against a declared 32 MiB memory "
                        + "limit must be terminated and reported as OUT_OF_MEMORY");
    }

    // -- PID count -------------------------------------------------------------------------------

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void theSupervisorEnforcesTheDeclaredPidLimit() throws Exception {
        Path marker = tempFile("pid-marker");
        SandboxPolicy tightPids = policy(GENEROUS, 60_000, 128, 1, 64, 8);
        SandboxOutcome outcome = run(tightPids, "FORK 4 " + marker);
        assertNotEquals(SandboxOutcome.COMPLETED, outcome,
                "a workload that forks 4 child processes against a declared maxPids of 1 must not "
                        + "be reported as COMPLETED");
    }

    // -- Disk / tmpfs --------------------------------------------------------------------------

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void theSupervisorEnforcesTheDeclaredDiskLimit() throws Exception {
        Path target = tempFile("disk-target");
        SandboxPolicy tightDisk = policy(GENEROUS, 60_000, 128, 8, 4, 8);
        SandboxOutcome outcome = run(tightDisk, "WRITE 64 " + target);
        assertNotEquals(SandboxOutcome.COMPLETED, outcome,
                "a workload that writes 64 MiB against a declared tmpfs limit of 4 MiB must not be "
                        + "reported as COMPLETED");
    }

    // -- Output byte count -----------------------------------------------------------------------

    /**
     * {@code maxOutputBytes}: the contract requirement for this suite is "covers every limit
     * declared by {@code SandboxPolicy}," and this is the security-relevant one an earlier draft
     * left uncovered -- unbounded response output is a denial-of-service vector against a
     * supervisor's own buffers, not just an inconvenience. Provoked exactly the way
     * {@code SandboxConformanceProbe#emit} is written for: writing far more bytes than the cap
     * allows, in a size that comfortably fits inside a normal OS pipe buffer so this test does not
     * depend on backpressure ever engaging (see the probe's Javadoc) -- only on whether the
     * supervisor actually looked before letting the response through.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void theSupervisorEnforcesTheDeclaredOutputByteLimit() throws Exception {
        SandboxPolicy tightOutput = policy(GENEROUS, 60_000, 128, 8, 64, 8, 2_048);
        SandboxOutcome outcome = run(tightOutput, "EMIT 16000");
        assertNotEquals(SandboxOutcome.COMPLETED, outcome,
                "a response of 16000 bytes against a declared maxOutputBytes of 2048 must not be "
                        + "reported as COMPLETED");
    }

    // -- Open file-descriptor count ----------------------------------------------------------------

    /** {@code maxFiles}: same acceptance-criterion reasoning as the output-byte test above. */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void theSupervisorEnforcesTheDeclaredFileDescriptorLimit() throws Exception {
        Path scratch = tempDirectory("openfiles-scratch");
        SandboxPolicy tightFiles = policy(GENEROUS, 60_000, 128, 8, 64, 2);
        SandboxOutcome outcome = run(tightFiles, "OPENFILES 5 " + scratch);
        assertNotEquals(SandboxOutcome.COMPLETED, outcome,
                "a workload that opens 5 files against a declared maxFiles of 2 must not be "
                        + "reported as COMPLETED");
    }

    // -- Cancellation and complete process-tree cleanup -----------------------------------------

    /**
     * How long the canceller thread below is willing to wait for the probe to have recorded its
     * complete declared process tree before giving up and cancelling anyway. Generous on purpose:
     * it is a safety net for a probe that never finishes forking (in which case the assertion right
     * after {@code await()} reports that clearly), not the mechanism that decides when to cancel.
     */
    private static final Duration CANCEL_TRIGGER_TIMEOUT = Duration.ofSeconds(8);

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void cancellationStopsTheWorkloadAndReapsTheCompleteProcessTree() throws Exception {
        Path marker = tempFile("cancel-marker");
        SandboxPolicy policy = policy(GENEROUS, 60_000, 128, 8, 64, 8);
        SandboxSupervisorLauncher launcher = launcher();
        launcher.verifyCapability();
        try (SandboxSupervisorSession session = launcher.launch(policy)) {
            session.workerInput().write(("FORK 3 " + marker + "\n").getBytes(StandardCharsets.UTF_8));
            session.workerInput().flush();
            var cancelled = new AtomicReference<Exception>();
            // A fixed sleep here used to guess how long the probe's own JVM needs to start
            // and reach its first fork; under contention on the machine running this test, that
            // guess can be wrong in either direction and either race cancellation ahead of any
            // fork (leaving the marker empty and the assertion below unable to prove anything) or
            // needlessly slow this test down on an idle machine. Waiting for the marker file to
            // actually gain a line is the real signal the guess was standing in for.
            Thread canceller = new Thread(() -> {
                try {
                    awaitPidCount(marker, 3, CANCEL_TRIGGER_TIMEOUT);
                    session.terminate(SandboxTermination.CANCELLED);
                } catch (Exception error) {
                    cancelled.set(error);
                }
            });
            canceller.start();
            SandboxOutcome outcome = session.await(GENEROUS);
            canceller.join(GENEROUS.toMillis());
            assertNull(cancelled.get(), "terminate() must not throw");
            assertEquals(SandboxOutcome.CANCELLED, outcome,
                    "a session terminated with CANCELLED must report CANCELLED, not whatever state "
                            + "the workload happened to be in");

            List<Long> pids = readPids(marker);
            assertEquals(3, pids.size(), "the probe must have recorded the complete three-child "
                    + "process tree before cancellation reached it -- otherwise the cleanup "
                    + "assertion cannot distinguish a surviving child from one that was never forked");
            awaitAllDead(pids);
        }
    }

    // -- Response-envelope validation / PROTOCOL_FAILURE -----------------------------------------

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void theSupervisorDoesNotTrustAnUnreadableWorkerResponse() throws Exception {
        SandboxOutcome outcome = run(policy(GENEROUS, 5_000, 128, 8, 64, 8), "GARBLE");
        assertEquals(SandboxOutcome.PROTOCOL_FAILURE, outcome,
                "a worker that exits 0 with a response that is not the agreed OK envelope must be "
                        + "reported as PROTOCOL_FAILURE, not COMPLETED -- a supervisor that trusts exit "
                        + "code alone lets a compromised or buggy worker forge success");
    }

    // -- SETUP_FAILURE -----------------------------------------------------------------------------

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void theSupervisorReportsSetupFailureWhenTheWorkerCannotBeStarted() throws Exception {
        SandboxPolicy unusable = new SandboxPolicy(GENEROUS, 5_000, 128, 8, 64, 8, 1_000_000,
                PROBE_CLASSPATH, sha256(PROBE_CLASSPATH.toString()),
                Path.of("/nonexistent/does-not-exist/java"), sha256("nonexistent"));
        SandboxSupervisorLauncher launcher = launcher();
        launcher.verifyCapability();
        SandboxOutcome outcome;
        try (SandboxSupervisorSession session = launcher.launch(unusable)) {
            session.workerInput().write("COMPLETE\n".getBytes(StandardCharsets.UTF_8));
            session.workerInput().flush();
            outcome = session.await(GENEROUS);
        }
        assertEquals(SandboxOutcome.SETUP_FAILURE, outcome,
                "a policy naming a trustedJre that does not exist must fail at setup, reported as "
                        + "SETUP_FAILURE, not surface as an uncaught exception or as COMPLETED");
    }

    // -- helpers ---------------------------------------------------------------------------------

    /** Builds a policy pointed at this suite's own probe -- see the class Javadoc for why. */
    protected SandboxPolicy policy(Duration deadline, int cpuMillis, int memoryMiB, int maxPids,
                                    int tmpfsMiB, int maxFiles) {
        return policy(deadline, cpuMillis, memoryMiB, maxPids, tmpfsMiB, maxFiles, 4 * 1024 * 1024);
    }

    /** As above, with an explicit {@code maxOutputBytes} for {@link #theSupervisorEnforcesTheDeclaredOutputByteLimit}. */
    protected SandboxPolicy policy(Duration deadline, int cpuMillis, int memoryMiB, int maxPids,
                                    int tmpfsMiB, int maxFiles, int maxOutputBytes) {
        return new SandboxPolicy(deadline, cpuMillis, memoryMiB, maxPids, maxFiles, tmpfsMiB,
                maxOutputBytes, PROBE_CLASSPATH, sha256(PROBE_CLASSPATH.toString()), PROBE_JRE,
                sha256(PROBE_JRE.toString()));
    }

    /**
     * How long THIS TEST is willing to block on {@code await()}, independent of the (often
     * deliberately tiny) {@code policy.deadline()} under test. A compliant supervisor detects a
     * breach and returns in well under this; a supervisor not enforcing the limit under test
     * still resolves within it because every probe recipe this suite uses is itself bounded to a
     * few seconds. This is caller patience, not a limit the supervisor is being tested against.
     */
    private static final Duration CALLER_PATIENCE = Duration.ofSeconds(10);

    private SandboxOutcome run(SandboxPolicy policy, String recipe) throws Exception {
        SandboxSupervisorLauncher launcher = launcher();
        launcher.verifyCapability();
        try (SandboxSupervisorSession session = launcher.launch(policy)) {
            session.workerInput().write((recipe + "\n").getBytes(StandardCharsets.UTF_8));
            session.workerInput().flush();
            return session.await(CALLER_PATIENCE);
        }
    }

    private Path tempFile(String prefix) throws IOException {
        Path file = Files.createTempFile(prefix, ".conformance");
        Files.deleteIfExists(file);
        file.toFile().deleteOnExit();
        return file;
    }

    private Path tempDirectory(String prefix) throws IOException {
        Path dir = Files.createTempDirectory(prefix);
        dir.toFile().deleteOnExit();
        return dir;
    }

    private List<Long> readPids(Path marker) throws IOException {
        if (!Files.exists(marker)) {
            return List.of();
        }
        return Files.readAllLines(marker, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .map(String::strip)
                .map(Long::parseLong)
                .toList();
    }

    /**
     * Blocks until {@code marker} names the complete expected process tree or {@code timeout}
     * elapses, whichever comes first -- the real signal
     * {@code cancellationStopsTheWorkloadAndReapsTheCompleteProcessTree} needs before it can
     * meaningfully verify cleanup, in place of a fixed sleep that could only ever be a guess at how
     * long the probe's own JVM startup and forks take on
     * whatever machine and load this suite happens to be running under. Short polling
     * interval because this is a lightweight file read, not a costly operation worth spacing out.
     */
    private void awaitPidCount(Path marker, int expected, Duration timeout)
            throws InterruptedException, IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (readPids(marker).size() >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        // Timed out without the complete tree: proceed to cancel anyway. The size assertion just
        // after await() reports this precisely instead of this method retrying forever or masking
        // a genuinely broken probe.
    }

    private void awaitAllDead(List<Long> pids) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            boolean anyAlive = pids.stream()
                    .anyMatch(pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            if (!anyAlive) {
                return;
            }
            Thread.sleep(100);
        }
        List<Long> stillAlive = pids.stream()
                .filter(pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                .toList();
        fail("process-tree cleanup was not complete after cancellation: PID(s) " + stillAlive
                + " were still alive 5s after terminate(CANCELLED) was called. Complete "
                + "process-tree cleanup is one of the declared guarantees, not an incidental one.");
    }

    /** See the Javadoc on {@link #PROBE_CLASSPATH} for why this exists and what it guarantees. */
    private static Path resolveProbeClasspath() {
        String raw = System.getProperty("java.class.path");
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("system property java.class.path is unset or blank; "
                    + "SandboxSupervisorContract cannot determine a trustedWorker classpath for "
                    + "the probe it spawns");
        }
        List<String> entries = new ArrayList<>();
        for (String entry : raw.split(java.util.regex.Pattern.quote(File.pathSeparator), -1)) {
            if (!entry.isBlank()) {
                entries.add(entry);
            }
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("java.class.path ('" + raw + "') contained no "
                    + "non-blank entries after splitting on '" + File.pathSeparator + "'");
        }
        if (entries.size() == 1) {
            Path single = Path.of(entries.get(0));
            if (!Files.exists(single)) {
                throw new IllegalStateException("java.class.path resolved to a single entry ('"
                        + single + "') that does not exist on disk; SandboxSupervisorContract "
                        + "requires trustedWorker to name a real, launchable classpath entry");
            }
            return single;
        }
        return synthesizeManifestOnlyClasspathJar(entries);
    }

    /**
     * Writes a temporary, otherwise-empty JAR whose manifest {@code Class-Path} attribute lists
     * every given classpath entry as an absolute {@code file:} URI, so a single {@code -cp
     * <thisJar>} launch resolves to the exact same effective classpath as the original,
     * multi-entry {@code java.class.path} -- while {@code PROBE_CLASSPATH} itself stays a single,
     * genuine, existing {@link Path} for any caller that inspects it as one.
     */
    private static Path synthesizeManifestOnlyClasspathJar(List<String> entries) {
        try {
            Path jar = Files.createTempFile("sandbox-supervisor-probe-classpath", ".jar");
            jar.toFile().deleteOnExit();
            var classPathHeader = new StringBuilder();
            for (String entry : entries) {
                Path entryPath = Path.of(entry).toAbsolutePath().normalize();
                if (!Files.exists(entryPath)) {
                    throw new IllegalStateException("java.class.path entry '" + entry
                            + "' does not exist on disk; refusing to synthesize a probe classpath "
                            + "jar from an incomplete build (run a compile/test-compile first)");
                }
                String uri = entryPath.toUri().toString();
                if (Files.isDirectory(entryPath) && !uri.endsWith("/")) {
                    // The JAR File Specification requires a trailing '/' for a Class-Path entry
                    // that names a directory; without it the entry is a plain file reference.
                    uri = uri + "/";
                }
                if (!classPathHeader.isEmpty()) {
                    classPathHeader.append(' ');
                }
                classPathHeader.append(uri);
            }
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPathHeader.toString());
            try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
                // No entries beyond the manifest itself: every real class comes from the Class-Path
                // attribute's own referenced directories/jars, resolved by the JVM at launch time.
            }
            return jar;
        } catch (IOException error) {
            throw new IllegalStateException("failed to synthesize a single-entry manifest jar for "
                    + "a " + entries.size() + "-entry java.class.path ("
                    + String.join(File.pathSeparator, entries) + "); PROBE_CLASSPATH must resolve "
                    + "to exactly one filesystem Path because SandboxPolicy.trustedWorker() is "
                    + "Path-typed", error);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
