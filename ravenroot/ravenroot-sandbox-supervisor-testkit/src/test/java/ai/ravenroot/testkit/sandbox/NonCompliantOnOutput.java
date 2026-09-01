package ai.ravenroot.testkit.sandbox;

import ai.ravenroot.programming.graalvm.SandboxSupervisorLauncher;

/** Deliberately non-compliant: every mechanism enforced except the output-byte limit. See {@link NonCompliantOnDeadline}. */
final class NonCompliantOnOutput extends SandboxSupervisorContract {
    @Override
    protected SandboxSupervisorLauncher launcher() {
        return RealisticFakeSupervisor.missing(RealisticFakeSupervisor.Check.OUTPUT);
    }
}
