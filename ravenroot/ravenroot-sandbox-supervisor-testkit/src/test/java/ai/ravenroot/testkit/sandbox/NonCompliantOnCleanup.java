package ai.ravenroot.testkit.sandbox;

import ai.ravenroot.programming.graalvm.SandboxSupervisorLauncher;

/**
 * Deliberately non-compliant: reports {@code CANCELLED} correctly but kills only the immediate
 * worker process, leaving forked grandchildren orphaned and alive. See {@link NonCompliantOnDeadline}.
 */
final class NonCompliantOnCleanup extends SandboxSupervisorContract {
    @Override
    protected SandboxSupervisorLauncher launcher() {
        return RealisticFakeSupervisor.missing(RealisticFakeSupervisor.Check.CLEANUP);
    }
}
