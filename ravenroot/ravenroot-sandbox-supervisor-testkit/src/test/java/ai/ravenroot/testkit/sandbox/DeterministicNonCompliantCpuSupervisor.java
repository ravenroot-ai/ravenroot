package ai.ravenroot.testkit.sandbox;

import ai.ravenroot.programming.graalvm.SandboxPolicy;
import ai.ravenroot.programming.graalvm.SandboxSupervisorLauncher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Arrays;

/**
 * Testkit meta-control that deterministically omits the CPU decision for the contract's exact CPU
 * recipe. It is not evidence that an OS supervisor enforces CPU accounting: every other policy is
 * delegated to the real-process fake with CPU enforcement absent.
 */
final class DeterministicNonCompliantCpuSupervisor implements SandboxSupervisorLauncher {

    private static final byte[] CPU_RECIPE = "BUSY 4000\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private final SandboxSupervisorLauncher realMissingCpu =
            RealisticFakeSupervisor.missing(RealisticFakeSupervisor.Check.CPU);

    @Override
    public void verifyCapability() throws java.io.IOException {
        realMissingCpu.verifyCapability();
    }

    @Override
    public SandboxSupervisorSession launch(SandboxPolicy policy) throws java.io.IOException {
        if (!isExactCpuPolicy(policy)) {
            return realMissingCpu.launch(policy);
        }
        return new ExactCpuSession();
    }

    private static boolean isExactCpuPolicy(SandboxPolicy policy) {
        return policy.deadline().equals(Duration.ofSeconds(8))
                && policy.cpuMillis() == 250
                && policy.memoryMiB() == 128
                && policy.maxPids() == 8
                && policy.maxFiles() == 8
                && policy.tmpfsMiB() == 64
                && policy.maxOutputBytes() == 4 * 1024 * 1024;
    }

    private static final class ExactCpuSession implements SandboxSupervisorSession {
        private final ByteArrayOutputStream input = new ByteArrayOutputStream();

        @Override
        public OutputStream workerInput() {
            return input;
        }

        @Override
        public InputStream supervisorControl() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream diagnostics() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void terminate(SandboxTermination termination) {
            throw new AssertionError("deterministic CPU meta-control must not be terminated before await()");
        }

        @Override
        public SandboxOutcome await(Duration remaining) {
            byte[] actual = input.toByteArray();
            if (!Arrays.equals(CPU_RECIPE, actual)) {
                throw new AssertionError("exact CPU policy must receive recipe bytes BUSY 4000\\n; received "
                        + java.util.HexFormat.of().formatHex(actual));
            }
            return SandboxOutcome.COMPLETED;
        }

        @Override
        public void close() {
            // No process is started for this one exact meta-control session.
        }
    }
}
