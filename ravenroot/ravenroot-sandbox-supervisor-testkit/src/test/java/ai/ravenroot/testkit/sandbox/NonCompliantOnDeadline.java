package ai.ravenroot.testkit.sandbox;

import ai.ravenroot.programming.graalvm.SandboxSupervisorLauncher;

/**
 * Deliberately non-compliant: every mechanism enforced except the wall-clock deadline. Not named
 * {@code *Test}/{@code Test*} on purpose -- its inherited tests are EXPECTED to fail, and Surefire's
 * default discovery must not run it directly. {@link NonCompliantSupervisorRedControlTest} runs it
 * through {@code EngineTestKit} instead and asserts on the result as data.
 */
final class NonCompliantOnDeadline extends SandboxSupervisorContract {
    @Override
    protected SandboxSupervisorLauncher launcher() {
        return RealisticFakeSupervisor.missing(RealisticFakeSupervisor.Check.DEADLINE);
    }
}
