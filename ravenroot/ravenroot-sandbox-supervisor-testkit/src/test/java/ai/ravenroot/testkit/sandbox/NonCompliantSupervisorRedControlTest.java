package ai.ravenroot.testkit.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Event;
import org.opentest4j.AssertionFailedError;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * "A fake supervisor that does NOT enforce a limit makes the suite FAIL -- asserted, not argued."
 *
 * <p>Each method here runs the full {@link SandboxSupervisorContract} (11 test methods) through
 * {@code EngineTestKit} against a fake that is compliant everywhere except one named mechanism,
 * and asserts, as data read back from the JUnit Platform's own execution results -- not by
 * eyeballing console output -- that EXACTLY the one corresponding contract test failed and every
 * other one still passed. Isolation (only one test failing, not the whole class) is itself part
 * of the evidence: a suite whose tests all fail together the moment anything is wrong would not
 * actually be checking each declared limit independently.</p>
 *
 * <p>The fixture classes this drives ({@code NonCompliantOn*}) are deliberately not named
 * {@code *Test}/{@code Test*}, so Surefire's own default discovery never runs them directly --
 * only this class does, through the Platform Launcher API, where their failure is the assertion,
 * not a build break.</p>
 */
class NonCompliantSupervisorRedControlTest {

    private static final int TOTAL_CONTRACT_TESTS = 11;

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void aSupervisorNotEnforcingTheDeadlineFailsOnlyTheDeadlineTest() {
        assertExactlyOneFailure(NonCompliantOnDeadline.class, "theSupervisorEnforcesTheDeclaredDeadline()",
                "a workload that sleeps well past the declared deadline");
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void aSupervisorNotEnforcingCpuFailsOnlyTheCpuTest() {
        AssertionFailedError failure = assertExactlyOneFailure(NonCompliantOnCpu.class,
                "theSupervisorEnforcesTheDeclaredCpuBudget()", SandboxSupervisorContract.CPU_FAILURE_MESSAGE);
        assertTrue(failure.isExpectedDefined(), "CPU assertion must expose its expected outcome contract");
        assertTrue(failure.isActualDefined(), "CPU assertion must expose the actual supervisor outcome");
        assertEquals(SandboxSupervisorContract.CPU_EXPECTED_OUTCOME, failure.getExpected().getValue());
        assertEquals(ai.ravenroot.programming.graalvm.SandboxSupervisorLauncher.SandboxOutcome.COMPLETED,
                failure.getActual().getValue());
        String sample = System.getProperty("ravenroot.cpu.measurement.sample", "ordinary");
        String condition = System.getProperty("ravenroot.cpu.measurement.condition", "ordinary");
        System.out.println("RAVENROOT_CPU_RED_CONTROL={\"sample\":\"" + safeJson(sample)
                + "\",\"condition\":\"" + safeJson(condition)
                + "\",\"nestedStarted\":11,\"nestedSucceeded\":10,\"nestedFailed\":1"
                + ",\"descriptor\":\"theSupervisorEnforcesTheDeclaredCpuBudget()\""
                + ",\"throwable\":\"org.opentest4j.AssertionFailedError\""
                + ",\"expected\":\"" + safeJson(SandboxSupervisorContract.CPU_EXPECTED_OUTCOME)
                + "\",\"actual\":\"COMPLETED\"}");
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void aSupervisorNotEnforcingMemoryFailsOnlyTheMemoryTest() {
        assertExactlyOneFailure(NonCompliantOnMemory.class, "theSupervisorEnforcesTheDeclaredMemoryLimit()",
                "a workload that retains 256 MiB of live heap");
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void aSupervisorNotEnforcingThePidLimitFailsOnlyThePidTest() {
        assertExactlyOneFailure(NonCompliantOnPid.class, "theSupervisorEnforcesTheDeclaredPidLimit()",
                "a workload that forks 4 child processes");
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void aSupervisorNotEnforcingTheDiskLimitFailsOnlyTheDiskTest() {
        assertExactlyOneFailure(NonCompliantOnDisk.class, "theSupervisorEnforcesTheDeclaredDiskLimit()",
                "a workload that writes 64 MiB");
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void aSupervisorThatDoesNotReapTheFullTreeFailsOnlyTheCleanupAssertion() {
        assertExactlyOneFailure(NonCompliantOnCleanup.class,
                "cancellationStopsTheWorkloadAndReapsTheCompleteProcessTree()",
                "process-tree cleanup was not complete after cancellation");
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void aSupervisorThatTrustsAnyResponseFailsOnlyTheProtocolTest() {
        assertExactlyOneFailure(NonCompliantOnProtocol.class,
                "theSupervisorDoesNotTrustAnUnreadableWorkerResponse()",
                "a worker that exits 0 with a response that is not the agreed OK envelope");
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void aSupervisorNotEnforcingTheOutputByteLimitFailsOnlyTheOutputTest() {
        assertExactlyOneFailure(NonCompliantOnOutput.class,
                "theSupervisorEnforcesTheDeclaredOutputByteLimit()",
                "a response of 16000 bytes against a declared maxOutputBytes of 2048");
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void aSupervisorNotEnforcingTheFileDescriptorLimitFailsOnlyTheFilesTest() {
        assertExactlyOneFailure(NonCompliantOnFiles.class,
                "theSupervisorEnforcesTheDeclaredFileDescriptorLimit()",
                "a workload that opens 5 files against a declared maxFiles of 2");
    }

    private AssertionFailedError assertExactlyOneFailure(Class<? extends SandboxSupervisorContract> fixture,
                                                         String expectedFailingDisplayName,
                                                         String expectedMessageFragment) {
        var results = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(fixture))
                .execute();

        results.testEvents().assertStatistics(stats ->
                stats.started(TOTAL_CONTRACT_TESTS).failed(1).succeeded(TOTAL_CONTRACT_TESTS - 1));

        java.util.List<Event> failures = results.testEvents().failed().list();
        assertEquals(1, failures.size(), () -> fixture.getSimpleName()
                + ": expected exactly one failing contract test, found " + failures.size());
        String actualName = failures.get(0).getTestDescriptor().getDisplayName();
        assertTrue(actualName.equals(expectedFailingDisplayName),
                () -> fixture.getSimpleName() + ": expected '" + expectedFailingDisplayName
                        + "' to be the one that failed, but it was '" + actualName + "' -- this fake is not "
                        + "isolating the limit it claims to be missing");

        Throwable throwable = failures.get(0).getRequiredPayload(TestExecutionResult.class)
                .getThrowable().orElseThrow(() -> new AssertionError(fixture.getSimpleName()
                        + ": failed event did not expose its throwable"));
        assertEquals(AssertionFailedError.class, throwable.getClass(), () -> fixture.getSimpleName()
                + ": expected a direct contract AssertionFailedError, but found "
                + throwable.getClass().getName());
        assertNull(throwable.getCause(), () -> fixture.getSimpleName()
                + ": contract failure must be direct, not a wrapped setup/timeout/protocol error");
        assertTrue(throwable.getMessage() != null && throwable.getMessage().contains(expectedMessageFragment),
                () -> fixture.getSimpleName() + ": failure did not contain the distinctive contract message '"
                        + expectedMessageFragment + "': " + throwable.getMessage());
        System.out.println("RAVENROOT_RED_CONTROL={\"fixture\":\"" + safeJson(fixture.getSimpleName())
                + "\",\"nestedStarted\":" + TOTAL_CONTRACT_TESTS
                + ",\"nestedSucceeded\":" + (TOTAL_CONTRACT_TESTS - 1)
                + ",\"nestedFailed\":1,\"descriptor\":\"" + safeJson(actualName)
                + "\",\"throwable\":\"" + safeJson(throwable.getClass().getName()) + "\"}");
        return (AssertionFailedError) throwable;
    }

    private static String safeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
