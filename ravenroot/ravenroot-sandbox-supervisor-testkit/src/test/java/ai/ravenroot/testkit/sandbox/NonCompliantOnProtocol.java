package ai.ravenroot.testkit.sandbox;

import ai.ravenroot.programming.graalvm.SandboxSupervisorLauncher;

/**
 * Deliberately non-compliant: trusts a worker's exit code alone and never inspects whether its
 * response is the agreed {@code OK} envelope. See {@link NonCompliantOnDeadline}.
 */
final class NonCompliantOnProtocol extends SandboxSupervisorContract {
    @Override
    protected SandboxSupervisorLauncher launcher() {
        return RealisticFakeSupervisor.missing(RealisticFakeSupervisor.Check.PROTOCOL);
    }
}
