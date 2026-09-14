package ai.ravenroot.core.runner;

import java.util.Objects;
import java.util.UUID;

/** Payload-free signal emitted only after the exact graph attempt and runner job commit as waiting. */
public final class RunnerJobSuspension extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final UUID jobId;
    public RunnerJobSuspension(UUID jobId) {
        super("Node suspended for a governed runner job", null, false, false);
        this.jobId = Objects.requireNonNull(jobId);
    }
    public UUID jobId() { return jobId; }
}
