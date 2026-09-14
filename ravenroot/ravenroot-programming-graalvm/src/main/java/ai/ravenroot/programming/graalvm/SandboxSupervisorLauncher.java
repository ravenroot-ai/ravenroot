package ai.ravenroot.programming.graalvm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;

/** Trusted boundary for launching and accounting for an external sandbox. */
public interface SandboxSupervisorLauncher {
    void verifyCapability() throws IOException;

    SandboxSupervisorSession launch(SandboxPolicy policy) throws IOException;

    /** Optional cache-placement capability; legacy implementations must never silently ignore it. */
    default void verifyPlacement(SandboxLaunchPlacement placement) throws IOException {
        if (placement == null) throw new IllegalArgumentException("Launch placement is required");
        if (placement.hasOverride()) throw new IOException("SANDBOX_RESOURCE_CACHE_UNSUPPORTED");
    }

    /** Keeps existing v1 implementations compatible for default placement and closed for overrides. */
    default SandboxSupervisorSession launch(SandboxPolicy policy, SandboxLaunchPlacement placement) throws IOException {
        if (placement == null) throw new IllegalArgumentException("Launch placement is required");
        // Do not delegate this guard to overridable verification: a legacy launch implementation
        // cannot apply placement merely because a custom verifier says it supports the extension.
        if (placement.hasOverride()) throw new IOException("SANDBOX_RESOURCE_CACHE_UNSUPPORTED");
        return launch(policy);
    }

    /**
     * Identifies this launcher in the operational log when {@link #verifyCapability()} fails --
     * for the process launcher, the configured script's own path, so the operator reading the log does
     * not have to go looking for which deployment variable it came from. A default rather than an
     * abstract method so the test doubles that already implement this interface (none of which are
     * ever the subject of a capability-failure log line) do not need a mechanical update for it.
     */
    default String describe() {
        return getClass().getSimpleName();
    }

    interface SandboxSupervisorSession extends AutoCloseable {
        OutputStream workerInput();
        InputStream supervisorControl();
        InputStream diagnostics();
        void terminate(SandboxTermination termination) throws IOException;
        SandboxOutcome await(Duration remaining) throws Exception;
        @Override void close() throws IOException;
    }

    enum SandboxTermination { DEADLINE, CANCELLED, IO_FAILURE, PROTOCOL_FAILURE }

    enum SandboxOutcome { COMPLETED, POLICY_REJECTED, SETUP_FAILURE, SECCOMP_DENIED, OUT_OF_MEMORY,
        DEADLINE_EXCEEDED, CANCELLED, REAP_FAILED, PROTOCOL_FAILURE }
}
