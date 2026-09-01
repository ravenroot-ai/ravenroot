package ai.ravenroot.testkit.sandbox;

import ai.ravenroot.programming.graalvm.SandboxSupervisorLauncher;

/** Deliberately non-compliant: every mechanism enforced except the disk (tmpfs) limit. See {@link NonCompliantOnDeadline}. */
final class NonCompliantOnDisk extends SandboxSupervisorContract {
    @Override
    protected SandboxSupervisorLauncher launcher() {
        return RealisticFakeSupervisor.missing(RealisticFakeSupervisor.Check.DISK);
    }
}
