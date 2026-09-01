package ai.ravenroot.testkit.sandbox;

import ai.ravenroot.programming.graalvm.SandboxSupervisorLauncher;

/** Deliberately non-compliant: every mechanism enforced except the PID-count limit. See {@link NonCompliantOnDeadline}. */
final class NonCompliantOnPid extends SandboxSupervisorContract {
    @Override
    protected SandboxSupervisorLauncher launcher() {
        return RealisticFakeSupervisor.missing(RealisticFakeSupervisor.Check.PID);
    }
}
