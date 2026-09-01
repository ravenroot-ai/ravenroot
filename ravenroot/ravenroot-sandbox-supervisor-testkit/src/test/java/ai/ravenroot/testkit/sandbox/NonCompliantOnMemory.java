package ai.ravenroot.testkit.sandbox;

import ai.ravenroot.programming.graalvm.SandboxSupervisorLauncher;

/** Deliberately non-compliant: every mechanism enforced except the memory limit. See {@link NonCompliantOnDeadline}. */
final class NonCompliantOnMemory extends SandboxSupervisorContract {
    @Override
    protected SandboxSupervisorLauncher launcher() {
        return RealisticFakeSupervisor.missing(RealisticFakeSupervisor.Check.MEMORY);
    }
}
