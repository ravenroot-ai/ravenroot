package ai.ravenroot.testkit.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Event;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertExactlyOneFailure(NonCompliantOnDeadline.class, "theSupervisorEnforcesTheDeclaredDeadline()");
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void aSupervisorNotEnforcingCpuFailsOnlyTheCpuTest() {
        assertExactlyOneFailure(NonCompliantOnCpu.class, "theSupervisorEnforcesTheDeclaredCpuBudget()");
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void aSupervisorNotEnforcingMemoryFailsOnlyTheMemoryTest() {
        assertExactlyOneFailure(NonCompliantOnMemory.class, "theSupervisorEnforcesTheDeclaredMemoryLimit()");
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void aSupervisorNotEnforcingThePidLimitFailsOnlyThePidTest() {
        assertExactlyOneFailure(NonCompliantOnPid.class, "theSupervisorEnforcesTheDeclaredPidLimit()");
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void aSupervisorNotEnforcingTheDiskLimitFailsOnlyTheDiskTest() {
        assertExactlyOneFailure(NonCompliantOnDisk.class, "theSupervisorEnforcesTheDeclaredDiskLimit()");
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void aSupervisorThatDoesNotReapTheFullTreeFailsOnlyTheCleanupAssertion() {
        assertExactlyOneFailure(NonCompliantOnCleanup.class,
                "cancellationStopsTheWorkloadAndReapsTheCompleteProcessTree()");
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void aSupervisorThatTrustsAnyResponseFailsOnlyTheProtocolTest() {
        assertExactlyOneFailure(NonCompliantOnProtocol.class,
                "theSupervisorDoesNotTrustAnUnreadableWorkerResponse()");
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void aSupervisorNotEnforcingTheOutputByteLimitFailsOnlyTheOutputTest() {
        assertExactlyOneFailure(NonCompliantOnOutput.class,
                "theSupervisorEnforcesTheDeclaredOutputByteLimit()");
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void aSupervisorNotEnforcingTheFileDescriptorLimitFailsOnlyTheFilesTest() {
        assertExactlyOneFailure(NonCompliantOnFiles.class,
                "theSupervisorEnforcesTheDeclaredFileDescriptorLimit()");
    }

    private void assertExactlyOneFailure(Class<? extends SandboxSupervisorContract> fixture,
                                          String expectedFailingDisplayName) {
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
    }
}
