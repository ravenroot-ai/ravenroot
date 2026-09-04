package ai.ravenroot.core.humantask;

import ai.ravenroot.api.persistence.DurableHumanTask;

import java.util.UUID;

/** Deterministic outcome of a transport-neutral human-task operation. */
public record HumanTaskResult(Code code, DurableHumanTask task, UUID resumeTraversalId) {
    public enum Code {
        CREATED,
        RESOLVED,
        DENIED,
        CANCELLED,
        EXPIRED,
        ESCALATED,
        NOT_FOUND,
        UNAUTHORIZED,
        STALE_GENERATION,
        PAYLOAD_REFUSED,
        ALREADY_APPLIED,
        ALREADY_SETTLED,
        UNAVAILABLE
    }

    public boolean created() {
        return code == Code.CREATED || code == Code.ALREADY_APPLIED;
    }
}
