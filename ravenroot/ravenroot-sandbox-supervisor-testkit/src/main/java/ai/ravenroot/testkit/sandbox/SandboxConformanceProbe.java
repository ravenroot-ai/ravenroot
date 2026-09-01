package ai.ravenroot.testkit.sandbox;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

/**
 * The workload {@link SandboxSupervisorContract} runs inside whatever a supervisor under test
 * launches. It is deliberately not GraalVM- or JavaScript-specific: {@code SandboxPolicy} and
 * {@code SandboxSupervisorLauncher} declare a generic process-isolation contract (the {@code
 * trustedWorker}/{@code trustedJre} fields are caller-supplied paths, not hardcoded to any one
 * language runtime), and this suite tests exactly that generic contract -- not
 * {@code ai.ravenroot.programming.graalvm.GraalVmWorkerMain} or the JavaScript-specific wire
 * protocol next to it, both of which are internal to that module. A conformance suite that reused
 * them would need to either duplicate that internal protocol or loosen that module's
 * encapsulation for a detail that is not part of this contract.
 *
 * <p>This class ships as ordinary source. There is no binary anywhere in this repository an
 * integrator's run of this suite depends on: {@code trustedJre} is whatever JRE the integrator's
 * own build already has on hand, and {@code trustedWorker} is this module's own compiled test
 * classpath.</p>
 *
 * <p>Protocol: read ONE line from stdin, run exactly the requested behaviour, exit. On the
 * behaviour completing without incident, print exactly {@code OK} (no other bytes) and exit 0. On
 * an internal problem, print {@code ERR: <message>} and exit 1. A supervisor that cannot tell
 * these apart, or that reports success for a process it force-killed, fails the corresponding
 * contract test. Recipes that report interim progress (see {@code FORK} and {@code OPENFILES})
 * write progress lines and flush immediately, so a supervisor observing them synchronously (a
 * blocking read, never a sleep-then-poll loop) sees them without a timing race.</p>
 *
 * <ul>
 *   <li>{@code COMPLETE} -- return immediately.</li>
 *   <li>{@code SLEEP <millis>} -- block for {@code millis} wall-clock milliseconds, then return.
 *       Exists to breach a deadline without consuming CPU, isolating the deadline check from the
 *       CPU check.</li>
 *   <li>{@code BUSY <millis>} -- spin (no sleeping, single-threaded -- see the runtime assertion
 *       in {@link #busy}) until at least {@code millis} of CPU time has been consumed, then
 *       return. Exists to breach a CPU budget well inside a generous deadline, isolating the CPU
 *       check from the deadline check.</li>
 *   <li>{@code ALLOCATE <mib>} -- allocate and touch {@code mib} megabytes of heap (retained, not
 *       eligible for GC, so a real {@code -Xmx} bound really is exercised), then return.</li>
 *   <li>{@code FORK <count> <markerFile>} -- spawn {@code count} child processes (each a plain
 *       {@code sleep 300}) essentially immediately, append each child's PID to {@code markerFile}
 *       (one per line, flushed immediately after each spawn so a killed probe still leaves the
 *       record behind), then block holding the tree open until killed or 6 seconds pass.</li>
 *   <li>{@code WRITE <mib> <targetFile>} -- write {@code mib} megabytes to {@code targetFile} in
 *       1 MiB chunks, flushing after each so growth is observable from outside, then return.</li>
 *   <li>{@code EMIT <bytes>} -- write {@code bytes} bytes to stdout in 4 KiB chunks, flushing
 *       after each. A supervisor bounding {@code maxOutputBytes} must not let this exceed the
 *       declared cap before it reacts; OS pipe backpressure (a bounded buffer the write side
 *       blocks on once full) means a supervisor that reads synchronously cannot miss the excess
 *       regardless of how many bytes this recipe is asked to emit.</li>
 *   <li>{@code OPENFILES <count> <directory>} -- open {@code count} files in {@code directory}
 *       (kept open, not closed), print {@code OPENED <count>} and flush once all are open, then
 *       hold for 3 seconds before closing everything and returning.</li>
 *   <li>{@code GARBLE} -- write bytes that are not {@code OK} and exit 0 anyway, simulating a
 *       worker whose response a supervisor must not simply trust.</li>
 * </ul>
 */
public final class SandboxConformanceProbe {
    private SandboxConformanceProbe() {
    }

    public static void main(String[] args) throws Exception {
        String line;
        try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            line = reader.readLine();
        }
        if (line == null || line.isBlank()) {
            fail("no recipe supplied on stdin");
            return;
        }
        var tokens = new StringTokenizer(line.strip());
        String command = tokens.nextToken();
        try {
            switch (command) {
                case "COMPLETE" -> { }
                case "SLEEP" -> Thread.sleep(Long.parseLong(tokens.nextToken()));
                case "BUSY" -> busy(Long.parseLong(tokens.nextToken()));
                case "ALLOCATE" -> allocate(Integer.parseInt(tokens.nextToken()));
                case "FORK" -> fork(Integer.parseInt(tokens.nextToken()), tokens.nextToken());
                case "WRITE" -> write(Integer.parseInt(tokens.nextToken()), tokens.nextToken());
                case "EMIT" -> {
                    emit(Long.parseLong(tokens.nextToken()));
                    return; // emit() owns stdout directly; no trailing OK marker.
                }
                case "OPENFILES" -> openFiles(Integer.parseInt(tokens.nextToken()), tokens.nextToken());
                case "GARBLE" -> {
                    System.out.print("NOT-OK-GARBLED-RESPONSE");
                    System.out.flush();
                    return;
                }
                default -> {
                    fail("unknown recipe: " + command);
                    return;
                }
            }
        } catch (OutOfMemoryError error) {
            // Deliberately not caught by a broad Throwable below: a real OOM should surface as a
            // real non-zero-friendly failure a supervisor observes (process death or ERR), never
            // as a silently swallowed OK.
            fail("OutOfMemoryError: " + error.getMessage());
            return;
        }
        System.out.print("OK");
        System.out.flush();
    }

    private static void fail(String message) {
        System.out.print("ERR: " + message);
        System.out.flush();
        System.exit(1);
    }

    /**
     * Runtime-asserted single-threadedness: a supervisor's CPU-budget check may fall back to
     * wall-clock elapsed time when true per-process CPU accounting is unavailable (see
     * {@code RealisticFakeSupervisor.cpuMillisSoFar}), and that fallback is valid only because
     * this loop consumes CPU on exactly one thread -- if a future edit parallelised it, wall clock
     * would under-count true CPU consumption and the fallback would silently stop being a safe
     * proxy.
     *
     * <p>Pinned with a real measurement, not a thread-count snapshot (which is too easily
     * disturbed by the JVM's own incidental housekeeping threads and would make this assertion
     * itself a source of flakiness): if process-wide CPU time grew far more than this thread's own
     * CPU time did over the same interval, work happened on another thread concurrently. A
     * generous 3x tolerance absorbs ordinary GC/JIT background activity while still catching a
     * genuinely parallelised loop, which would consume close to (threads count)x as much process
     * CPU per unit of this thread's own CPU time.</p>
     */
    private static void busy(long millis) {
        var os = com.sun.management.OperatingSystemMXBean.class.cast(
                java.lang.management.ManagementFactory.getOperatingSystemMXBean());
        long processCpuBefore = os.getProcessCpuTime();
        long budgetNanos = millis * 1_000_000L;
        long start = bean().getCurrentThreadCpuTime();
        long x = 0;
        while (bean().getCurrentThreadCpuTime() - start < budgetNanos) {
            // Deliberately CPU-bound, no I/O and no sleeping, so wall clock and CPU time track
            // closely and the test can set a generous deadline while still breaching a tight CPU
            // budget.
            x = x * 31 + 7;
            if (x == Long.MIN_VALUE) {
                x = 1; // never true; keeps the JIT from proving the loop body is dead.
            }
        }
        long threadCpuDelta = bean().getCurrentThreadCpuTime() - start;
        long processCpuDelta = os.getProcessCpuTime() - processCpuBefore;
        if (processCpuBefore < 0 || processCpuDelta < 0) {
            return; // getProcessCpuTime() is documented to return -1 when unsupported; nothing to assert.
        }
        if (processCpuDelta > threadCpuDelta * 3) {
            throw new AssertionError("BUSY must be single-threaded: process-wide CPU time grew by "
                    + processCpuDelta + "ns while this thread's own CPU time only grew by "
                    + threadCpuDelta + "ns -- some other thread did real work concurrently, which "
                    + "would make wall-clock elapsed time an invalid proxy for CPU time consumed");
        }
    }

    private static java.lang.management.ThreadMXBean bean() {
        return java.lang.management.ManagementFactory.getThreadMXBean();
    }

    private static void allocate(int mib) {
        List<byte[]> retained = new ArrayList<>();
        int chunkMib = 4;
        for (int done = 0; done < mib; done += chunkMib) {
            byte[] chunk = new byte[chunkMib * 1024 * 1024];
            java.util.Arrays.fill(chunk, (byte) 1); // force real commit, not a lazy/zero page
            retained.add(chunk);
        }
        // Hold the reference until the process exits so a supervisor sampling RSS/heap after
        // ALLOCATE returns still sees the full footprint, not a GC'd one.
        if (retained.isEmpty()) {
            throw new IllegalStateException("unreachable");
        }
    }

    private static void fork(int count, String markerFile) throws IOException, InterruptedException {
        Path marker = Path.of(markerFile);
        List<Process> children = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Process child = new ProcessBuilder("sleep", "300").start();
            children.add(child);
            Files.writeString(marker, child.pid() + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        // Hold the tree open so a supervisor enforcing (or failing to enforce) maxPids has
        // something to observe, but bounded: a supervisor that never kills this must still let
        // the conformance suite's own test method finish in reasonable time. The forked children
        // (a plain "sleep 300") deliberately outlive this bound by a wide margin, so a cleanup
        // check racing this probe's own natural exit still finds real, independently-alive PIDs
        // to assert against, not ones this probe already reaped itself. Forking happens
        // essentially immediately relative to this hold, which is what makes a single settle-then
        // -check by the supervisor reliable without polling.
        Thread.sleep(6_000);
        for (Process child : children) {
            child.destroyForcibly();
        }
    }

    private static void write(int mib, String targetFile) throws IOException {
        Path target = Path.of(targetFile);
        byte[] chunk = new byte[1024 * 1024];
        java.util.Arrays.fill(chunk, (byte) 7);
        try (var out = Files.newOutputStream(target, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            for (int written = 0; written < mib; written++) {
                out.write(chunk);
                out.flush();
            }
        }
    }

    private static void emit(long bytes) throws IOException {
        byte[] chunk = new byte[4096];
        java.util.Arrays.fill(chunk, (byte) 'X');
        long written = 0;
        while (written < bytes) {
            int size = (int) Math.min(chunk.length, bytes - written);
            System.out.write(chunk, 0, size);
            System.out.flush(); // each flush is a backpressure point a supervisor can observe.
            written += size;
        }
    }

    private static void openFiles(int count, String directory) throws IOException, InterruptedException {
        Path dir = Path.of(directory);
        Files.createDirectories(dir);
        var handles = new ArrayList<java.io.OutputStream>();
        for (int i = 0; i < count; i++) {
            handles.add(Files.newOutputStream(dir.resolve("fd-" + i + ".conformance"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE));
        }
        System.out.print("OPENED " + handles.size());
        System.out.flush();
        // All opens finish essentially immediately (file creation is not backpressured the way a
        // pipe write is), so a fixed settle window comfortably after this point, but well before
        // the hold below ends, is a reliable single checkpoint for a supervisor -- not a poll race.
        Thread.sleep(3_000);
        for (var handle : handles) {
            handle.close();
        }
        // No trailing OK here: main()'s own unconditional "print OK and exit" runs immediately
        // after this method returns normally, exactly like every other recipe except EMIT (which
        // owns stdout completely and returns early specifically to skip it). Printing one here too
        // would leave "OPENED <n>OKOK" in the response, which is what EMIT's isEmitFiller-style
        // recognition doesn't cover and PROTOCOL validation would then reject as garbled -- for the
        // wrong reason (a probe bug), not because a supervisor failed to enforce anything.
    }
}
