package ai.ravenroot.testkit.sandbox;

import ai.ravenroot.programming.graalvm.SandboxSupervisorLauncher;

/**
 * The unmutated control: a supervisor that genuinely enforces every declared limit must pass
 * every test in {@link SandboxSupervisorContract} without exception. Run by the ordinary Surefire
 * discovery pattern (class name ends in {@code Test}), unlike the deliberately non-compliant
 * fixtures in this package, which do not.
 */
class CompliantSupervisorConformanceTest extends SandboxSupervisorContract {

    @Override
    protected SandboxSupervisorLauncher launcher() {
        return RealisticFakeSupervisor.fullyCompliant();
    }
}
