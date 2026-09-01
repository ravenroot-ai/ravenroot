package ai.ravenroot.testkit.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.platform.testkit.engine.EngineTestKit;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * the regression analysis disproved the earlier implementation's reliability not by re-reading the code but by measuring it under
 * load: interleaving this module's tests against the UNCHANGED, unrelated
 * {@code ravenroot-programming-graalvm} module, same command shape, same machine, same window.
 * Clean rate fell from an apparently-100% quiet-machine result to 5/17, including the compliant
 * fake twice failing its own disk check. "The one-to-one property holds on a quiet machine" was
 * not evidence of the property this suite exists to provide; it was evidence of an untested
 * precondition.
 *
 * <p>This test reproduces that measurement against the determinism regression fix in
 * {@link RealisticFakeSupervisor} and reports a clean/total ratio, rather than a single trusted
 * green run. The concurrent load is a real {@code mvn -pl ravenroot-programming-graalvm -am test}
 * process -- literally the same unchanged control module the regression analysis used, run in a loop for the
 * duration of this test so the load overlaps every repeat, not just the first.</p>
 *
 * <p><b>SEC-26.</b> A guard that only checks whether the {@code sh -c "while true; do mvn ...;
 * done"} WRAPPER shell is alive proves nothing about {@code mvn} itself: the wrapper stays alive
 * forever even if every {@code mvn} invocation inside it fails immediately, so a guard checking
 * only the wrapper can report a false 10/10 while measuring an effectively idle machine. This was
 * reproduced empirically against the unmodified guard at commit f5ef056: substituting an
 * always-instantly-failing wrapped command still passed {@code assertTrue(load.isAlive())} and
 * the suite still reported "10/10" with zero real load ever having run. See
 * {@link LoadWrapperGuard}, which this test now delegates to, and its permanent regression
 * coverage in {@code LoadWrapperGuardRedControlTest}. This test therefore fails loudly, with the
 * wrapped command's captured output, if {@code mvn} cannot be located, cannot start, or fails on
 * any iteration -- not only the first -- rather than silently measuring an unloaded machine and
 * calling it a load test.</p>
 *
 * <p><b>A flake in the loaded module fails this test BY DESIGN -- that is not a defect in this
 * guard.</b> The loaded module is {@code ravenroot-programming-graalvm}; its own tests are what
 * {@code mvn -pl ravenroot-programming-graalvm -am test} runs on every iteration. FIX-27 fixed
 * one real, observed race in that module's {@code GraalVmProgramRuntimeTest
 * .preservesWorkerFailureAndOutputBounds} -- an oversized-response scenario that used to spawn an
 * unnecessary real child JVM and race it against a 1s budget for no reason, since the response was
 * already fixed by the test itself. That fix is real and is not being second-guessed here. But
 * subsequent regression analysis instrumented and measured the SAME test's other half (the
 * {@code Java.type(...).getenv()} rejection) and found it still races TWO real child JVMs against
 * a 3s budget, because that half genuinely needs a real GraalVM sandbox response and cannot be
 * faked the way the oversized-response half could. So a flake in the loaded module under concurrent
 * load is now RARER, not GONE -- do not write or treat it as "fixed, no longer a concern": that
 * framing is precisely what would make a future reader misdiagnose an infrequent, genuine flake as
 * a defect in this guard, simply because it has become uncommon enough to look surprising. A
 * residual 3s race still needs to be addressed.</p>
 *
 * <p>If {@link LoadWrapperGuard#verifyEveryIterationSucceeded} fails here, read the captured
 * output it attaches (the last lines of the wrapped {@code mvn} invocation's own stdout/stderr)
 * before concluding anything about this guard. Two outcomes are both plausible and require
 * opposite responses: the load may have failed to RUN (a real defect in the wrapper mechanism,
 * worth investigating here), or {@code ravenroot-programming-graalvm}'s own tests may have FAILED
 * inside a genuinely running load (not this guard's concern at all -- re-run, and if it reproduces,
 * pursue it as that module's own residual flake described above). The correct response
 * to either is never to relax, retry-wrap or disable this guard: doing so silently restores the
 * exact false 10/10 SEC-26 exists to prevent, regardless of which of the two outcomes actually
 * occurred.</p>
 *
 * <p><b>This test used to be its own false positive, from the opposite direction of SEC-26.</b> It
 * measured a real determinism property honestly, but the thing it measured --
 * {@code CompliantSupervisorConformanceTest} -- used a fixed-sleep-then-single-check window
 * ({@code RealisticFakeSupervisor}'s old {@code SETTLE}) for its PID/OUTPUT/FILES violation
 * checks. That window's own success depended on the measured machine's scheduling behaviour, which
 * is exactly the shape this file's Javadoc above warns against for the LOAD side (SEC-26) and this
 * fixture had, independently, on the MEASUREMENT side. A concurrently-run reactor could delay the
 * probe JVM's own startup past the fixed window, so a real violation landed after the one check
 * had already happened and was missed -- not flaky in the sense of "occasionally wrong," but
 * silently and permanently missed for that call, indistinguishable from a rare genuine regression
 * in this suite's report. {@code RealisticFakeSupervisor.Session#settle()} replaces that fixed
 * window with a loop of short, genuinely OS-blocking waits that keeps watching for as long as the
 * workload runs, so a violation is caught whenever it actually occurs. The clean/total ratio this
 * test asserts is unchanged (still 10/10, not loosened) -- what changed is that the thing being
 * measured is no longer racing the same clock this test deliberately contends for.</p>
 *
 * <p><b>What temporal element remains.</b>
 * {@code settle()} still bounds its wait with a ceiling ({@code RealisticFakeSupervisor
 * .SETTLE_CEILING}, 5s, over 4x the old fixed window) as a safety net against a workload that
 * genuinely never resolves -- not as the detection mechanism itself. This suite cannot distinguish
 * "no violation occurred" from "the workload was starved past 5 seconds before resolving," and a
 * machine so oversubscribed that a JVM cannot even start forking within 5 seconds is outside what
 * this suite is tuned to measure -- the same caveat {@code SandboxSupervisorContract}'s own class
 * Javadoc already states for its budgets in general.</p>
 */
class LoadInterleavedStabilityTest {

    private static final int REPEATS = 10;

    /**
     * How long this test is willing to wait for the FIRST {@code mvn -pl
     * ravenroot-programming-graalvm -am test} invocation to complete before concluding the load never
     * genuinely started. Generous because it covers JVM/Maven startup plus a full module test run
     * on a possibly cold local repository cache, not because a slow success should be tolerated
     * indefinitely -- see {@link LoadWrapperGuard#verifyFirstIterationSucceeded}, which fails the
     * instant a completed iteration reports a non-zero exit code, regardless of how quickly that
     * happens.
     */
    private static final Duration FIRST_ITERATION_TIMEOUT = Duration.ofMinutes(3);

    /**
     * Propagates the parent build's local repository to the nested {@code mvn}, or nothing when the
     * parent did not set one. Declared scope carried from SEC-12, not silent extra work.
     *
     * <p><b>The defect.</b> The nested command ran plain {@code mvn -q -o}, which resolves from
     * {@code ~/.m2} whatever the parent was told to use. The team's standing rule is to build with an
     * isolated {@code -Dmaven.repo.local}, so the child was resolving <em>different artifacts than the
     * build under test</em> — a stale {@code ravenroot-application-api} whose API predated the change
     * being verified. This failure occurred when the child ran with
     * {@code NoClassDefFoundError: ai/ravenroot/api/programming/ProgramAdmission} against a jar built
     * before that type existed, while the same module passed in the isolated repo.
     *
     * <p><b>Why it matters beyond one build.</b> This test's own Javadoc argues that a flake in the
     * loaded module failing this guard is by design. That argument only holds if the load process runs
     * the code under test. Resolving the load module against a stale repository means a green run here
     * attests to an artifact set nobody is shipping — the guard measures a build that is not the one
     * being verified, which is the failure class SEC-26 documented one layer up.
     *
     * <p>Deliberately a no-op when {@code maven.repo.local} is unset, so the default developer flow is
     * unchanged.
     */
    private static String inheritedRepository() {
        String repository = System.getProperty("maven.repo.local");
        return repository == null || repository.isBlank()
                ? ""
                : "-Dmaven.repo.local=" + Path.of(repository).toAbsolutePath().normalize() + " ";
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void theCompliantContractStaysCleanWhileTheControlModuleRunsConcurrently() throws Exception {
        Path reactorRoot = Path.of("..").toAbsolutePath().normalize();
        // Loops the control module's own real test run for the duration of this test, using the
        // exact command shape ("mvn ... -pl ravenroot-programming-graalvm -am test") the regression analysis measured
        // against, rather than once. -am keeps a fresh release-version transition resolvable from
        // this source reactor without installing its upstream artifacts in the local repository.
        try (LoadWrapperGuard load = LoadWrapperGuard.start(
                "mvn -q -o " + inheritedRepository() + "-pl ravenroot-programming-graalvm -am test", reactorRoot)) {
            assertTrue(load.wrapperProcess().isAlive(), "the wrapper shell for the "
                    + "ravenroot-programming-graalvm load process exited immediately; this test "
                    + "measures nothing under load if the wrapper never started");
            // Necessary, not sufficient: the wrapper staying alive says nothing about mvn itself
            // ever succeeding (that is exactly SEC-26's finding). This is the check that does.
            load.verifyFirstIterationSucceeded(FIRST_ITERATION_TIMEOUT);

            int clean = 0;
            var failures = new StringBuilder();
            for (int i = 1; i <= REPEATS; i++) {
                var results = EngineTestKit.engine("junit-jupiter")
                        .selectors(selectClass(CompliantSupervisorConformanceTest.class))
                        .execute();
                long failedCount = results.testEvents().failed().count();
                long succeededCount = results.testEvents().succeeded().count();
                boolean thisRunClean = failedCount == 0 && succeededCount == 11;
                if (thisRunClean) {
                    clean++;
                } else {
                    failures.append("run ").append(i).append("/").append(REPEATS)
                            .append(": succeeded=").append(succeededCount)
                            .append(" failed=").append(failedCount).append(" [")
                            .append(results.testEvents().failed().list().stream()
                                    .map(e -> e.getTestDescriptor().getDisplayName())
                                    .reduce((a, b) -> a + ", " + b).orElse(""))
                            .append("]\n");
                }
            }
            System.out.println("[stability] compliant contract clean rate under load-interleaved "
                    + "conditions: " + clean + "/" + REPEATS);

            // Audits the WHOLE window, not just the first iteration checked above: a load that
            // starts healthy and later degrades to instant failures (e.g. resource exhaustion
            // mid-run) must not silently downgrade this into an unloaded-machine measurement for
            // its remaining repeats.
            load.verifyEveryIterationSucceeded();

            assertTrue(clean == REPEATS, "compliant contract clean rate was " + clean + "/" + REPEATS
                    + " while ravenroot-programming-graalvm ran concurrently -- the one-to-one "
                    + "determinism property must hold unconditionally with respect to load, not only "
                    + "on a quiet machine. Failures:\n" + failures);
        }
    }
}
