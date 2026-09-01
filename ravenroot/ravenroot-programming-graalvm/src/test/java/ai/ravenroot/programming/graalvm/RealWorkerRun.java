package ai.ravenroot.programming.graalvm;

import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramRequest;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Test-only. Runs the <b>real</b> {@link GraalVmWorkerMain} in a real child JVM and
 * returns the exact bytes it wrote to stdout.
 *
 * <p><b>Why this exists.</b> {@code GraalVmProgramRuntimeTest#preservesWorkerFailureAndOutputBounds}
 * has to prove that a genuine worker refusing host access surfaces as an
 * {@link IllegalArgumentException}. Pre-seeding a failure response the test wrote itself would
 * replace the thing under test with a fixture that asserts itself. But letting the runtime wait on a
 * live child JVM puts the worker's cold start inside {@code policy.deadline()}, and that wait is
 * what FIX-29 measured breaking: one child-JVM round trip (fork, JVM boot, Truffle/Graal JS
 * interpreted cold start, eval, wire write) costs ~671 ms idle on a 10-core machine but ~3.0 s at 4x
 * CPU oversubscription and ~5.4 s at 8x, against a 3 s budget.
 *
 * <p>This class separates the two: the worker runs for real, <b>outside</b> any sandbox deadline,
 * and the bytes it actually produced are then replayed through the runtime deterministically. The
 * assertion still comes from the real worker -- the test never authors a response envelope -- but no
 * assertion depends on how fast a JVM happened to start.
 *
 * <p><b>On {@link #CALLER_PATIENCE}.</b> This is not the budget that FIX-29 removed, and widening it
 * is not what fixes anything. It is the same concept that
 * {@code SandboxSupervisorContract.CALLER_PATIENCE} already documents: how long the <i>test</i> is
 * willing to block, independent of any limit under test. The distinction that matters is the failure
 * mode. Breaching the old 3 s sandbox deadline silently changed <i>which exception the test
 * observed</i>, so the test failed on false grounds and read as flaky. Breaching this one cannot
 * change any observed outcome -- it raises a dedicated, self-describing environment failure and
 * nothing else. It sits above the worst case measured under 8x synthetic load by an order of
 * magnitude precisely because it is a hang guard, not a race the test is expected to win.
 *
 * <p><b>FIX-31: {@link #CALLER_PATIENCE} is a bound on {@link #capture}, not on each of its
 * waits.</b> It used to be spent twice -- once in {@code output.get(...)} and again in
 * {@code process.waitFor(...)} -- so a call documented as bounded by 60 s could take 120 s. That is
 * the defect class this whole family keeps turning up: a stated bound the code does not honour. The
 * two waits now share one deadline, so the class Javadoc's number is the real worst case.
 *
 * <p>This is not cosmetic. A caller's {@code @Timeout} is chosen to sit ABOVE the inner guards so
 * that a hang surfaces as this class's self-describing environment failure rather than as an
 * anonymous JUnit timeout. An inner guard that can silently cost double defeats that ordering:
 * {@code GraalVmProgramRuntimeTest#preservesWorkerFailureAndOutputBounds}'s {@code @Timeout(120)}
 * was only accidentally correct before this change, and
 * {@code GraalVmProgramRuntimeTest#validatesTestsAndExecutesJavascriptThroughTestSupervisor} makes
 * three captures, where the old behaviour would have needed 360 s of outer guard to dominate.
 */
final class RealWorkerRun {
    private static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");

    /**
     * Hang guard only. See the class Javadoc: no assertion may depend on this value. This is the
     * budget for one whole {@link #capture} call, shared across both of its waits -- not a budget
     * granted to each wait separately.
     */
    static final Duration CALLER_PATIENCE = Duration.ofSeconds(60);

    private RealWorkerRun() {
    }

    /**
     * Executes the real worker once and returns everything it wrote to stdout. The caller must pass
     * the same {@code artifact} and {@code request} instances it later drives the runtime with, so
     * the recorded bytes provably belong to that exact invocation.
     */
    static byte[] capture(ProgramWireProtocol.Mode mode, GeneratedArtifact artifact, ProgramRequest request)
            throws Exception {
        return capture(mode, artifact, request, java.util.List.of());
    }

    /**
     * As above, with extra JVM arguments inserted between {@code -cp <classpath>} and
     * the worker's main class -- for {@code PythonResourceCacheUnwritableWorkerTest}, which needs a
     * {@code -Dpolyglot.engine.userResourceCache=<unwritable path>} flag ahead of the worker's own
     * default so the declared-failure path under test is reached deterministically, independent of
     * whether {@code /opt/ravenroot} happens to exist on the machine running this suite.
     */
    static byte[] capture(ProgramWireProtocol.Mode mode, GeneratedArtifact artifact, ProgramRequest request,
                           java.util.List<String> extraJvmArguments) throws Exception {
        var payload = new ByteArrayOutputStream();
        ProgramWireProtocol.writeRequest(payload, mode, artifact, request);
        var command = new java.util.ArrayList<String>();
        command.add(JAVA.toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.addAll(extraJvmArguments);
        command.add(GraalVmWorkerMain.class.getName());
        Process process = new ProcessBuilder(command).start();
        // One deadline for the whole capture, established before the first wait and shared by both.
        // See the class Javadoc (FIX-31): spending CALLER_PATIENCE once per wait let a call the
        // documentation bounds at 60 s take 120 s.
        final long deadline = System.nanoTime() + CALLER_PATIENCE.toNanos();
        try {
            var output = new CompletableFuture<byte[]>();
            Thread.startVirtualThread(() -> {
                try (var stdout = process.getInputStream()) {
                    output.complete(stdout.readAllBytes());
                } catch (Throwable error) {
                    output.completeExceptionally(error);
                }
            });
            try (var stdin = process.getOutputStream()) {
                stdin.write(payload.toByteArray());
            }
            byte[] bytes = output.get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
            if (!process.waitFor(remainingMillis(deadline), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("the real worker JVM did not exit within " + CALLER_PATIENCE
                        + " of caller patience. This is an environment failure (no JVM available, a "
                        + "hung child, a broken classpath), not the property under test -- see "
                        + "RealWorkerRun's Javadoc. It is deliberately NOT the sandbox deadline that "
                        + "FIX-29 removed.");
            }
            if (bytes.length == 0) {
                throw new AssertionError("the real worker JVM produced no output at all, so there is "
                        + "nothing genuine to assert on. Refusing to continue rather than let a test "
                        + "pass on an empty recording.");
            }
            return bytes;
        } finally {
            process.destroyForcibly();
        }
    }

    /**
     * Milliseconds left of the capture's single deadline, never below 1 -- a non-positive timeout
     * would mean "wait forever" to {@code Future#get} and {@code Process#waitFor}, turning an
     * exhausted hang guard into the hang it exists to prevent.
     */
    private static long remainingMillis(long deadline) {
        return Math.max(1, Duration.ofNanos(deadline - System.nanoTime()).toMillis());
    }
}
