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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every limit {@code SandboxPolicy} declares, breached FROM PYTHON.
 *
 * <p><b>Why a JavaScript suite says nothing about this.</b> How a limit is breached is a property of
 * the language, not of the sandbox. {@code os.system} and {@code open()} are ordinary Python and have
 * no JavaScript counterpart in a context with no host access; a JavaScript artifact simply has no way
 * to express the attempt, so a JavaScript suite reports "not breached" for reasons that have nothing
 * to do with whether the limit works.
 *
 * <p><b>The result is not uniform, and that is the finding.</b> Of the five declared limits,
 * <b>three</b> are reachable from Python -- time, memory and output size -- and <b>two</b> are
 * refused inside the worker's own context, processes and files, before the supervisor is ever
 * consulted. (An earlier revision of this Javadoc said "two and three", contradicting the per-test
 * Javadoc below it; the count here is the one the five tests actually assert.) Both outcomes are
 * asserted explicitly, because
 * "the supervisor never saw it" and "the supervisor stopped it" are different security properties and
 * collapsing them into a single "did not complete" would hide which one is actually load-bearing.
 * Where a limit is unreachable, the test says so and pins the refusal, so that the day it BECOMES
 * reachable -- a different distribution shape, a relaxed context -- this suite goes red instead of
 * quietly continuing to pass.
 *
 * <p><b>Previous behavior.</b> Every test here failed against the previous adapter, all of them on
 * the same refusal, {@code Only JavaScript artifacts are enabled}: no Python
 * artifact reached any limit because none reached the engine.
 */
class PythonSandboxLimitBreachTest {
    private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");

    // -- Limit 1 of 5: time -------------------------------------------------------------------

    /**
     * Reachable, and stopped by the supervisor. A Python {@code while True} loop consumes its
     * deadline in the guest with no host call anywhere, so nothing but the supervisor's clock ends
     * it.
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void aPythonArtifactThatNeverReturnsIsStoppedByTheDeadline() {
        var supervisor = new PythonLimitSupervisor();
        // 20 s, not the 5 s default: a Python context costs ~1.8 s to reach first execution on an
        // idle machine (measured, see docs/architecture/python-programmable-nodes.md), so a tight
        // budget here would be breached by the startup rather than by the loop, and the test would
        // pass for the wrong reason. This is the same distinction FIX-29 draws.
        var runtime = new GraalVmProgramRuntime(supervisor, policy(Duration.ofSeconds(20)));
        GeneratedArtifact artifact = artifact("def handler(request):\n"
                + "    while True:\n"
                + "        pass\n"
                + "handler", ArtifactState.ACTIVE);

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> runtime.execute(TestAdmission.of(artifact), request()).toCompletableFuture().get());

        assertEquals("SANDBOX_DEADLINE_EXCEEDED", error.getCause().getMessage(),
                "an unbounded Python loop must be ended by the declared deadline and by nothing else");
    }

    // -- Limit 2 of 5: memory -----------------------------------------------------------------

    /**
     * Reachable, and stopped -- but by the heap ceiling rather than by an outcome, which is worth
     * pinning precisely. The worker's {@code main} catches {@code Throwable}, so an
     * {@code OutOfMemoryError} raised by a Python allocation becomes a failure ENVELOPE the
     * supervisor forwards, not a dead process. The execution fails, which is correct; it does not
     * fail as {@code OUT_OF_MEMORY}, and a reader assuming otherwise would be wrong.
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void aPythonArtifactThatAllocatesBeyondTheHeapDoesNotComplete() {
        var supervisor = new PythonLimitSupervisor();
        var runtime = new GraalVmProgramRuntime(supervisor, policy(Duration.ofSeconds(60)));
        // The declared memoryMiB is 64 (see policy below) and is applied to the child as -Xmx.
        GeneratedArtifact artifact = artifact("def handler(request):\n"
                + "    hold = []\n"
                + "    while True:\n"
                + "        hold.append(bytearray(16 * 1024 * 1024))\n"
                + "handler", ArtifactState.ACTIVE);

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> runtime.execute(TestAdmission.of(artifact), request()).toCompletableFuture().get());

        // ASSERT THE REASON, NOT MERELY THE FAILURE. The two assertions this replaced were
        // "the message is not SANDBOX_COMPLETED" and "the cause is not null", and neither could
        // fail: assertThrows has already established a cause, and the message was never going to be
        // that string. A control proves it -- an artifact that allocates NOTHING and is refused at
        // import for a reason with no bearing on memory
        //
        //     def handler(request):
        //         import _ctypes
        //         return {'ok': 1}
        //     handler
        //
        // fails with "ModuleNotFoundError: No module named '_ctypes'" and passed both of the old
        // assertions identically to a genuine breach. That is exactly the collapse between "stopped"
        // and "never seen" this class's own Javadoc claims to avoid.
        //
        // Measured message for the real breach, from the real worker at -Xmx64m: "MemoryError".
        assertTrue(error.getCause().getMessage().contains("MemoryError"),
                "the artifact must fail because it exhausted the declared memory, not for some "
                        + "other reason that happens to also fail, was: " + error.getCause().getMessage());
    }

    // -- Limit 3 of 5: processes ---------------------------------------------------------------

    /**
     * NOT reachable from Python: the context refuses process creation before the PID limit is
     * consulted, so {@code maxPids} is not the control that protects this path. Asserted on both of
     * Python's two ordinary routes, because they fail differently -- {@code os.system} surfaces the
     * polyglot refusal itself, {@code subprocess} surfaces a {@code PermissionError} from the
     * emulated posix layer -- and a test that checked only one would miss a relaxation of the other.
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void pythonCannotReachTheProcessLimitBecauseTheContextRefusesFirst() throws Exception {
        String refusal = workerRefusalFor("def handler(request):\n"
                + "    import os\n"
                + "    os.system('touch /tmp/ravenroot-409-pids')\n"
                + "    return {'spawned': True}\n"
                + "handler");
        assertTrue(refusal.contains("Process creation is not allowed"),
                "os.system must be refused by the context, not counted against maxPids, was: " + refusal);

        String viaSubprocess = workerRefusalFor("def handler(request):\n"
                + "    import subprocess\n"
                + "    subprocess.run(['/usr/bin/touch', '/tmp/ravenroot-409-pids'])\n"
                + "    return {'spawned': True}\n"
                + "handler");
        assertTrue(viaSubprocess.contains("PermissionError") || viaSubprocess.contains("not allowed"),
                "subprocess must be refused too, was: " + viaSubprocess);

        assertTrue(!java.nio.file.Files.exists(Path.of("/tmp/ravenroot-409-pids")),
                "neither route may leave a process behind; a refusal that still ran the command "
                        + "would be the worst of both readings");
    }

    // -- Limit 4 of 5: files -------------------------------------------------------------------

    /**
     * NOT reachable from Python: {@code IOAccess.NONE} refuses the open, so {@code maxFiles} is not
     * the control that protects this path either.
     *
     * <p><b>The file limit counts descriptors, not materialised files.</b>
     * {@code maxFiles} counts OPEN DESCRIPTORS, not files on disk. That is not an inference from the
     * name: {@code SandboxSupervisorContract} names its test
     * {@code theSupervisorEnforcesTheDeclaredFileDescriptorLimit}, drives it with the probe recipe
     * {@code OPENFILES}, whose contract is "open {@code count} files ... kept open, not closed", and
     * asserts against a workload "that opens 5 files against a declared maxFiles of 2". So the Python
     * component's one-off materialisation of its standard library -- over a thousand files, written
     * once and not held open -- does not meet this limit, and the fear that the worker would fail to
     * start on that count does not hold against the contract as written.
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void pythonCannotReachTheFileLimitBecauseTheContextRefusesFirst() throws Exception {
        String refusal = workerRefusalFor("def handler(request):\n"
                + "    handles = [open('/tmp/ravenroot-409-fd-%d' % i, 'w') for i in range(300)]\n"
                + "    return {'opened': len(handles)}\n"
                + "handler");
        assertTrue(refusal.contains("PermissionError") || refusal.contains("not permitted"),
                "opening 300 files against a declared maxFiles of 256 must be refused at the language "
                        + "boundary, was: " + refusal);
        assertTrue(!java.nio.file.Files.exists(Path.of("/tmp/ravenroot-409-fd-0")),
                "the refusal must precede the first open, not follow it");
    }

    // -- Limit 5 of 5: output size --------------------------------------------------------------

    /**
     * Reachable, and stopped twice over, which is why both halves are asserted. A Python result
     * larger than the worker's own element cap is refused by the worker; a result that is small in
     * elements but large in bytes gets past the worker and is stopped by the supervisor's byte count.
     * Testing only the first would leave the byte limit -- the one that is a denial-of-service vector
     * against a supervisor's buffers -- unexercised from this language.
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void aPythonResultBeyondTheDeclaredOutputBoundsIsRefused() throws Exception {
        String byElementCount = workerRefusalFor("def handler(request):\n"
                + "    return list(range(20000))\n"
                + "handler");
        assertTrue(byElementCount.contains("exceeds limit"),
                "a 20000-element Python list is beyond the worker's own array cap, was: " + byElementCount);

        var supervisor = new PythonLimitSupervisor();
        var runtime = new GraalVmProgramRuntime(supervisor, policy(Duration.ofSeconds(60)));
        // Few elements, many bytes: one string of 8 MiB against a declared maxOutputBytes of 2 MiB.
        GeneratedArtifact artifact = artifact("def handler(request):\n"
                + "    return {'blob': 'x' * (8 * 1024 * 1024)}\n"
                + "handler", ArtifactState.ACTIVE);

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> runtime.execute(TestAdmission.of(artifact), request()).toCompletableFuture().get());

        // The security-relevant property: an oversized result never comes back as a value. That is
        // already established by assertThrows above, so there is no separate assertion for it --
        // an "assertNotEquals(null, error.getCause())" here would be one more of the vacuous checks
        // this file just removed from the memory test. What still needs asserting is the REASON,
        // below.

        // This assertion pins the single-envelope correction. Previously the test exposed the
        // defect as "Invalid string length", because the worker wrote its
        // success envelope straight to stdout, failed partway through the 8 MiB string, and then
        // appended a complete failure envelope onto the half-written one. The reader consumed the
        // first envelope's key "blob" and read the second envelope's magic bytes as the pending
        // string's length -- 0x52525031 = 1381126193. The correct refusal was in those same 78
        // bytes, in clear text, and no caller could reach it.
        //
        // Measured before and after, on the real worker:
        //   before  78 bytes  RRP1..........blob.RRP1.....IOException...#Value exceeds worker protocol limit
        //   after   59 bytes  RRP1.....IOException...#Value exceeds worker protocol limit
        //
        // These bytes do not depend on the heap ceiling, and saying "-Xmx64m" here would have
        // implied they did. Recorded both through this test's supervisor, which applies
        // -Xmx=memoryMiB, and through RealWorkerRun, which applies no -Xmx at all; identical. The
        // -Xmx64m figure belongs to the MEMORY measurements in WorkerFailureEnvelopeTest, where it
        // is load-bearing.
        //
        // The refusal did not change. What changed is that there is now only one envelope, because
        // writeSuccess serialises into a bounded buffer and copies out only when whole. The
        // JavaScript half of this -- byte-identical, which is what proves it was never a Python
        // defect -- is in WorkerFailureEnvelopeTest.
        assertTrue(error.getCause().getMessage().contains("Value exceeds worker protocol limit"),
                "the caller must be told the real reason the result was refused, not a format error "
                        + "produced by reading a second envelope's magic as a length, was: "
                        + error.getCause().getMessage());
    }

    /**
     * Runs a Python artifact in the REAL worker, outside any deadline, and returns the refusal
     * message the worker itself produced. Fails loudly if the worker succeeded instead: a test whose
     * subject is a refusal must not silently pass when nothing was refused.
     */
    private static String workerRefusalFor(String source) throws Exception {
        GeneratedArtifact artifact = artifact(source, ArtifactState.ACTIVE);
        byte[] bytes = RealWorkerRun.capture(ProgramWireProtocol.Mode.EXECUTE, artifact, request());
        try {
            Object result = ProgramWireProtocol.readResponse(new ByteArrayInputStream(bytes));
            throw new AssertionError("the worker was expected to refuse this artifact and instead "
                    + "returned: " + result);
        } catch (ProgramWireProtocol.ProgramWorkerException refusal) {
            return refusal.getMessage();
        }
    }

    private static ProgramRequest request() {
        return new ProgramRequest(UUID.randomUUID(), "node-1", Map.of("name", "Ravenroot"), Map.of());
    }

    private static SandboxPolicy policy(Duration timeout) {
        return new SandboxPolicy(timeout, Math.toIntExact(timeout.toMillis()), 64, 32, 256, 64,
                2 * 1024 * 1024, Path.of(System.getProperty("java.class.path")), "worker-test-id",
                JAVA, "jre-test-id");
    }

    private static GeneratedArtifact artifact(String source, ArtifactState state) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
            Instant now = Instant.now();
            return new GeneratedArtifact("limit-artifact", "python", hash, source, state, 1, now, now, Map.of());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
