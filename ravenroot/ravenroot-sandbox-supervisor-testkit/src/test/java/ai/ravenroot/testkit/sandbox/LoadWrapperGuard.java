package ai.ravenroot.testkit.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Starts a {@code while true; do <wrappedCommand>; done} wrapper shell and verifies that the
 * WRAPPED command itself is genuinely, repeatedly succeeding -- not merely that the wrapper shell
 * process is alive.
 *
 * <p><b>The defect this replaces.</b> {@code sh -c "while true; do X; done"} stays alive forever
 * even if every single invocation of {@code X} fails immediately, because the shell's own
 * liveness is the loop control, not {@code X}'s success. A guard that only checks {@code
 * Process#isAlive()} on the wrapper therefore proves nothing about {@code X} ever having run
 * successfully: it can report a clean load while {@code X} has failed on every iteration since
 * the very first one, invisibly, for the entire duration of a caller's own measurement. This was
 * demonstrated empirically: with the unmodified guard shape and a wrapped command
 * that fails on 100% of iterations, the caller still reported a false 10/10 clean rate.
 *
 * <p><b>How this verifies instead.</b> The wrapped command's own stdout/stderr are appended to a
 * real, inspectable log file (never discarded -- a failing wrapped command must be diagnosable by
 * a human, not merely detectable by this class), and the wrapper script appends one line -- the
 * wrapped command's own exit code -- to a heartbeat file after every completed iteration. "The
 * wrapped command is genuinely running" is defined as: at least one heartbeat has appeared
 * reporting exit code 0 within a timeout ({@link #verifyFirstIterationSucceeded}), and, once a
 * caller's own workload has finished running concurrently, that every heartbeat recorded during
 * the whole window reported exit code 0 ({@link #verifyEveryIterationSucceeded}) -- so a load
 * that starts healthy and later degrades to instant failures is caught too, not only a load that
 * was broken from the very first iteration.
 */
final class LoadWrapperGuard implements AutoCloseable {

    private final Process wrapper;
    private final Path heartbeatFile;
    private final Path logFile;

    private LoadWrapperGuard(Process wrapper, Path heartbeatFile, Path logFile) {
        this.wrapper = wrapper;
        this.heartbeatFile = heartbeatFile;
        this.logFile = logFile;
    }

    /**
     * Starts {@code sh -c "while true; do <wrappedCommand>; done"} in {@code workingDirectory}.
     * {@code wrappedCommand} is inlined into the shell script as-is (it is caller-controlled test
     * infrastructure, e.g. a fixed {@code mvn} invocation or, for the deterministic controls in
     * {@code LoadWrapperGuardRedControlTest}, the POSIX builtins {@code true}/{@code false} --
     * never untrusted input).
     */
    static LoadWrapperGuard start(String wrappedCommand, Path workingDirectory) throws IOException {
        Path heartbeatFile = Files.createTempFile("load-wrapper-heartbeat", ".log");
        Path logFile = Files.createTempFile("load-wrapper-output", ".log");
        heartbeatFile.toFile().deleteOnExit();
        logFile.toFile().deleteOnExit();
        String script = "while true; do "
                + "( " + wrappedCommand + " ) >>'" + logFile + "' 2>&1; "
                + "code=$?; "
                + "printf '%s\\n' \"$code\" >> '" + heartbeatFile + "'; "
                + "done";
        var builder = new ProcessBuilder("sh", "-c", script);
        builder.directory(workingDirectory.toFile());
        // Only the wrapper shell's OWN stdout/stderr are discarded here (it prints nothing of its
        // own); the wrapped command's output goes to logFile above, unconditionally captured.
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return new LoadWrapperGuard(builder.start(), heartbeatFile, logFile);
    }

    Process wrapperProcess() {
        return wrapper;
    }

    /**
     * Blocks until the wrapped command has completed at least one iteration or {@code timeout}
     * elapses, then throws {@link AssertionError} unless that first completed iteration reported
     * exit code 0. This is the check that replaces a bare {@code Process#isAlive()} check on the
     * wrapper: it verifies the WRAPPED command actually ran and succeeded at least once, which
     * "the wrapper shell is alive" never proves -- the wrapper is alive in both the healthy and
     * the permanently-failing case.
     */
    void verifyFirstIterationSucceeded(Duration timeout) throws InterruptedException, IOException {
        List<Integer> exitCodes = awaitAtLeastOneParsedHeartbeat(timeout);
        if (exitCodes.isEmpty()) {
            throw new AssertionError(diagnose("the wrapped command never completed a single "
                    + "iteration within " + timeout + " -- the wrapper shell staying alive proves "
                    + "nothing about this; either the wrapped command hangs or " + timeout
                    + " was not long enough for it to complete even once"));
        }
        int exitCode = exitCodes.get(0);
        if (exitCode != 0) {
            throw new AssertionError(diagnose("the wrapped command's FIRST completed iteration "
                    + "exited with code " + exitCode + " (not 0) -- the wrapper shell staying "
                    + "alive proves nothing about this; this test measures nothing under load if "
                    + "the wrapped command never actually succeeds. See the captured output below "
                    + "for the actual cause"));
        }
    }

    /**
     * After a caller's own workload has finished, verifies that EVERY heartbeat recorded during
     * the whole run reported exit code 0 -- not only the first one -- so a load that degrades
     * mid-run (rather than failing from the start) is still caught.
     *
     * <p><b>What a failure here does and does not establish.</b> This asserts the OBSERVED fact --
     * some iterations exited non-zero -- not a diagnosis of why. A non-zero exit can mean the
     * wrapped command failed to run at all, or it can mean the wrapped command ran for real and
     * something genuinely inside it failed; this method cannot and does not distinguish those, and
     * the failure message says so instead of naming a cause it has not established. See the
     * captured output {@link #diagnose} attaches for that determination, and see the caller's own
     * documentation (e.g. {@code LoadInterleavedStabilityTest}'s class Javadoc) for whether a
     * specific wrapped command is known to have its own, separately tracked flakiness.
     */
    void verifyEveryIterationSucceeded() throws IOException {
        List<Integer> exitCodes = readParsedHeartbeats();
        if (exitCodes.isEmpty()) {
            throw new AssertionError(diagnose("no heartbeat was ever recorded across the whole "
                    + "run -- the wrapped command never completed a single iteration"));
        }
        long failed = exitCodes.stream().filter(code -> code != 0).count();
        if (failed > 0) {
            throw new AssertionError(diagnose(failed + "/" + exitCodes.size() + " wrapped-command "
                    + "iterations exited non-zero across the whole run -- the measurement window "
                    + "was not clean. This does not by itself say WHY: the wrapped command may have "
                    + "failed to RUN, or it may have run and something inside it genuinely FAILED. "
                    + "See the captured output below to tell which."));
        }
    }

    /** Forcibly terminates the wrapper and every descendant it spawned, then waits briefly. */
    @Override
    public void close() throws InterruptedException {
        wrapper.descendants().forEach(ProcessHandle::destroyForcibly);
        wrapper.destroyForcibly();
        wrapper.waitFor(10, TimeUnit.SECONDS);
    }

    private List<Integer> awaitAtLeastOneParsedHeartbeat(Duration timeout) throws InterruptedException, IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            List<Integer> exitCodes = readParsedHeartbeats();
            if (!exitCodes.isEmpty()) {
                return exitCodes;
            }
            Thread.sleep(50);
        }
        return readParsedHeartbeats();
    }

    private List<String> readHeartbeats() throws IOException {
        if (!Files.exists(heartbeatFile)) {
            return List.of();
        }
        return Files.readAllLines(heartbeatFile, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .toList();
    }

    /**
     * Parses every heartbeat line, silently skipping any that do not parse as a plain integer
     * rather than throwing {@link NumberFormatException}. The write is a {@code printf} of at most
     * a few bytes under {@code O_APPEND} (POSIX guarantees that append is atomic for writes this
     * small), so a torn read is very unlikely -- but if one ever happens, the honest response is
     * to treat that one line as not-yet-observed and keep going, not to let a partial write on the
     * filesystem surface as a confusing crash instead of a clean pass/fail verdict. This does not
     * weaken what is checked: every line that DOES parse is still required to report exit code 0.
     */
    private List<Integer> readParsedHeartbeats() throws IOException {
        List<Integer> exitCodes = new ArrayList<>();
        for (String line : readHeartbeats()) {
            try {
                exitCodes.add(Integer.parseInt(line.strip()));
            } catch (NumberFormatException torn) {
                // Skip: see this method's Javadoc.
            }
        }
        return exitCodes;
    }

    private String diagnose(String reason) {
        String tail = readLogTail();
        return reason + " -- captured output (" + logFile + "):\n" + tail;
    }

    private String readLogTail() {
        try {
            if (!Files.exists(logFile)) {
                return "(log file does not exist)";
            }
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - 40);
            return String.join("\n", lines.subList(from, lines.size()));
        } catch (IOException error) {
            return "(failed to read log file: " + error.getMessage() + ")";
        }
    }
}
