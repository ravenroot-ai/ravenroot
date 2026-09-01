package ai.ravenroot.testkit.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@link LoadWrapperGuard} actually verifies the WRAPPED command, not merely the
 * wrapper shell around it -- the defect this guard prevents. Both scenarios use POSIX
 * builtins ({@code true}/{@code false}) rather than real {@code mvn}: deterministic, instant,
 * and independent of this repository's own build state, so this coverage is fast and cannot
 * itself flake on an unrelated module's test outcome.
 *
 * <p><b>RED.</b> {@link #detectsAWrappedCommandThatFailsOnEveryIteration()} wraps a command that
 * fails on literally every iteration ({@code false}, exit code 1 forever). The wrapper shell
 * itself never exits -- {@code while true; do false; done} loops without end -- which is exactly
 * how the pre-fix guard was fooled: it only ever checked {@code Process#isAlive()} on that
 * eternally-alive wrapper. This test proves the FIX (checking the wrapped command's own exit code
 * via a heartbeat, not the wrapper's liveness) rejects this scenario instead. Empirically, the
 * unmodified guard at commit f5ef056 was verified to accept this exact scenario -- substituting
 * this same always-failing command for {@code mvn} in the pre-fix
 * {@code LoadInterleavedStabilityTest} still passed {@code assertTrue(load.isAlive())} and the
 * suite still reported a false "10/10" with zero genuine load.</p>
 *
 * <p><b>UNMUTATED CONTROL.</b> {@link #acceptsAWrappedCommandThatSucceedsOnEveryIteration()} wraps
 * a command that succeeds on every iteration ({@code true}, exit code 0 forever) and proves the
 * guard accepts it -- so a guard strict enough to catch the red scenario is not simply strict
 * enough to reject everything, which would make it pass by accident rather than by actually
 * distinguishing success from failure.</p>
 */
class LoadWrapperGuardRedControlTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(15);

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void detectsAWrappedCommandThatFailsOnEveryIteration() throws Exception {
        Path here = Path.of(".").toAbsolutePath().normalize();
        try (LoadWrapperGuard load = LoadWrapperGuard.start("false", here)) {
            assertTrue(load.wrapperProcess().isAlive(), "sanity: the wrapper shell around an "
                    + "infinite `while true; do false; done` loop must itself stay alive -- this "
                    + "is precisely the condition that fooled the pre-fix guard");

            AssertionError error = assertThrows(AssertionError.class,
                    () -> load.verifyFirstIterationSucceeded(SHORT_TIMEOUT),
                    "a wrapped command that fails on every iteration must be rejected even though "
                            + "the wrapper shell around it never exits; accepting this reproduces "
                            + "SEC-26 (a guard measuring the wrapper instead of the wrapped command)");
            assertTrue(error.getMessage().contains("exited with code 1"),
                    "the failure must name the actual observed exit code so a human can diagnose "
                            + "it without re-running anything; message was: " + error.getMessage());
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void acceptsAWrappedCommandThatSucceedsOnEveryIteration() throws Exception {
        Path here = Path.of(".").toAbsolutePath().normalize();
        try (LoadWrapperGuard load = LoadWrapperGuard.start("true", here)) {
            assertTrue(load.wrapperProcess().isAlive());

            // Must not throw: a genuinely succeeding wrapped command is exactly what this guard
            // exists to let through undisturbed.
            load.verifyFirstIterationSucceeded(SHORT_TIMEOUT);

            // Give the loop a moment to accumulate more than one heartbeat, then confirm the
            // whole-window audit also accepts sustained success, not only the first iteration.
            Thread.sleep(200);
            load.verifyEveryIterationSucceeded();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void wholeWindowAuditCatchesADegradationAfterAHealthyStart() throws Exception {
        Path here = Path.of(".").toAbsolutePath().normalize();
        Path scratch = java.nio.file.Files.createTempDirectory("degrade-control");
        scratch.toFile().deleteOnExit();
        // A marker file that does not exist yet: the FIRST iteration finds it absent and creates
        // it (succeeds, exit 0); every SUBSEQUENT iteration finds it present and fails (exit 1)
        // unconditionally -- simulating a load that starts healthy and then degrades, the
        // regression this guard's end-of-run audit (verifyEveryIterationSucceeded), not just the
        // first-iteration check, exists to catch.
        Path marker = scratch.resolve("degrade-marker");
        String wrappedCommand = "if [ -e '" + marker + "' ]; then exit 1; else touch '" + marker
                + "'; exit 0; fi";
        try (LoadWrapperGuard degrading = LoadWrapperGuard.start(wrappedCommand, here)) {
            degrading.verifyFirstIterationSucceeded(SHORT_TIMEOUT);
            Thread.sleep(300);
            AssertionError error = assertThrows(AssertionError.class,
                    degrading::verifyEveryIterationSucceeded,
                    "a load that starts healthy and then fails on every subsequent iteration must "
                            + "be caught by the whole-window audit, not only by the first-iteration "
                            + "check that already passed");
            assertFalse(error.getMessage().contains("0/0"),
                    "the diagnostic must report real observed counts, not a degenerate 0/0 that "
                            + "would misleadingly read as clean; message was: " + error.getMessage());
        } finally {
            marker.toFile().delete();
        }
    }
}
