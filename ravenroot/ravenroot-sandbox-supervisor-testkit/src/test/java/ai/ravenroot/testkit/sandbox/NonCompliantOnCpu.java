package ai.ravenroot.testkit.sandbox;

import ai.ravenroot.programming.graalvm.SandboxSupervisorLauncher;

/** Deliberately non-compliant: every mechanism enforced except the CPU budget. See {@link NonCompliantOnDeadline}. */
final class NonCompliantOnCpu extends SandboxSupervisorContract {
    @Override
    protected SandboxSupervisorLauncher launcher() {
        return RealisticFakeSupervisor.missing(RealisticFakeSupervisor.Check.CPU);
    }
}
