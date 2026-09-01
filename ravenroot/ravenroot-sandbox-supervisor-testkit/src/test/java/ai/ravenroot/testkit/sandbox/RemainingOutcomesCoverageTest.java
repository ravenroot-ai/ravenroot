package ai.ravenroot.testkit.sandbox;

import ai.ravenroot.programming.graalvm.SandboxPolicy;
import ai.ravenroot.programming.graalvm.SandboxSupervisorLauncher.SandboxOutcome;
import ai.ravenroot.programming.graalvm.SandboxSupervisorLauncher.SandboxSupervisorSession;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SandboxSupervisorContract} exercises six of the nine {@code SandboxOutcome} values with
 * portable, deterministic scenarios: {@code COMPLETED}, {@code DEADLINE_EXCEEDED},
 * {@code OUT_OF_MEMORY}, {@code CANCELLED}, {@code SETUP_FAILURE}, {@code PROTOCOL_FAILURE}.
 *
 * <p>{@code POLICY_REJECTED}, {@code SECCOMP_DENIED} and {@code REAP_FAILED} name failure modes
 * that belong to a specific real supervisor's own internal mechanism -- an unsatisfiable policy
 * shape, a kernel seccomp denial, a reap that timed out -- and no portable JVM-only workload can
 * provoke a compliant supervisor into one on demand. This class does not add them to the portable
 * contract; it demonstrates, with {@link RealisticFakeSupervisor#forcing}, that these three
 * {@code SandboxOutcome} values round-trip through the SPI exactly like the other six -- the enum
 * member survives {@code launch()}/{@code await()} unmodified and is not, for example, silently
 * coerced to some other value by anything in this suite's own plumbing. That is a narrower claim
 * than "this suite proves your supervisor reports these correctly," and this Javadoc says so on
 * purpose rather than leaving that boundary implicit.</p>
 */
class RemainingOutcomesCoverageTest {

    @Test
    void policyRejectedRoundTripsThroughTheSpi() throws Exception {
        assertRoundTrips(SandboxOutcome.POLICY_REJECTED);
    }

    @Test
    void seccompDeniedRoundTripsThroughTheSpi() throws Exception {
        assertRoundTrips(SandboxOutcome.SECCOMP_DENIED);
    }

    @Test
    void reapFailedRoundTripsThroughTheSpi() throws Exception {
        assertRoundTrips(SandboxOutcome.REAP_FAILED);
    }

    private void assertRoundTrips(SandboxOutcome outcome) throws Exception {
        var launcher = RealisticFakeSupervisor.forcing(outcome);
        launcher.verifyCapability();
        try (SandboxSupervisorSession session = launcher.launch(anyPolicy())) {
            assertEquals(outcome, session.await(Duration.ofSeconds(1)));
        }
    }

    /** Content is irrelevant: {@code forcing()} never inspects or acts on the policy. */
    private SandboxPolicy anyPolicy() {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path classpath = Path.of(System.getProperty("java.class.path"));
        return new SandboxPolicy(Duration.ofSeconds(5), 1_000, 64, 8, 64, 8, 1_000_000,
                classpath, "cp-id", java, "jre-id");
    }
}
