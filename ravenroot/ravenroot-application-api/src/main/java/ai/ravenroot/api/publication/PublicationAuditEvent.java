package ai.ravenroot.api.publication;

import java.util.Objects;
import java.util.UUID;

/**
 * Payload-free decision evidence emitted by the built-in boundary guard.
 *
 * @param processInstanceId runtime process identity
 * @param traversalId runtime traversal identity
 * @param invocationId runtime invocation identity
 * @param attemptId runtime attempt identity
 * @param decision bounded payload-free publication decision
 */
public record PublicationAuditEvent(UUID processInstanceId, UUID traversalId, UUID invocationId,
                                    UUID attemptId, PublicationDecision decision) {
    /** Requires runtime identity and bounded decision metadata only. */
    public PublicationAuditEvent {
        Objects.requireNonNull(processInstanceId, "processInstanceId");
        Objects.requireNonNull(traversalId, "traversalId");
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(decision, "decision");
    }
}
