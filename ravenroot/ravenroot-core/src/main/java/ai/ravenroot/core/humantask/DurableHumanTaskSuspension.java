package ai.ravenroot.core.humantask;

import java.util.Objects;
import java.util.UUID;

/** Core-owned payload-free signal emitted only after a human-task suspension commits. */
public final class DurableHumanTaskSuspension extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final UUID taskId;

    public DurableHumanTaskSuspension(UUID taskId) {
        super("Node suspended for a durable human task", null, false, false);
        this.taskId = Objects.requireNonNull(taskId, "taskId");
    }

    public UUID taskId() {
        return taskId;
    }
}
