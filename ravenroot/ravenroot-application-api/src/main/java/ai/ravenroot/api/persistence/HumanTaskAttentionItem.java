package ai.ravenroot.api.persistence;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Safe authorized projection of one actionable embedded Human Task.
 *
 * <p>The projection intentionally omits response schemas and bytes, decision comments and actors,
 * requester identity, continuation bytes, handler keys, scopes and roles.</p>
 *
 * @param taskId exact task identity.
 * @param generation current optimistic decision fence.
 * @param status current actionable lifecycle status.
 * @param graphVersion immutable graph-version pin.
 * @param deploymentId durable hosting deployment, absent for a transient process.
 * @param processInstanceId exact owning process instance.
 * @param traversalId exact suspended traversal.
 * @param nodeId exact graph node.
 * @param createdAt immutable registration time.
 * @param expiresAt durable expiry deadline.
 * @param escalateAt optional durable escalation deadline.
 * @param presentation immutable pinned confirmation presentation.
 * @param promptMaxUtf8Bytes immutable pinned prompt limit.
 * @param actionLabelMaxUtf8Bytes immutable pinned action-label limit.
 * @param commentMaxUtf8Bytes immutable pinned comment limit.
 * @param availableActions pinned actions currently authorized for this caller.
 */
public record HumanTaskAttentionItem(
        UUID taskId,
        long generation,
        HumanTaskStatus status,
        String graphVersion,
        Optional<String> deploymentId,
        UUID processInstanceId,
        UUID traversalId,
        String nodeId,
        Instant createdAt,
        Instant expiresAt,
        Optional<Instant> escalateAt,
        HumanTaskConfirmationPresentation presentation,
        int promptMaxUtf8Bytes,
        int actionLabelMaxUtf8Bytes,
        int commentMaxUtf8Bytes,
        List<HumanTaskConfirmationAction> availableActions) {

    /** Validates the bounded safe projection. */
    public HumanTaskAttentionItem {
        if (taskId == null) throw new IllegalArgumentException("taskId cannot be null");
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        if (status != HumanTaskStatus.WAITING && status != HumanTaskStatus.ESCALATED) {
            throw new IllegalArgumentException("attention item must be actionable");
        }
        graphVersion = HandlerRegistration.requireBoundedKey(graphVersion, "graphVersion");
        deploymentId = deploymentId == null ? Optional.empty() : deploymentId;
        deploymentId.ifPresent(value -> HandlerRegistration.requireBoundedKey(value, "deploymentId"));
        if (processInstanceId == null) throw new IllegalArgumentException("processInstanceId cannot be null");
        if (traversalId == null) throw new IllegalArgumentException("traversalId cannot be null");
        nodeId = HandlerRegistration.requireBoundedKey(nodeId, "nodeId");
        if (createdAt == null) throw new IllegalArgumentException("createdAt cannot be null");
        if (expiresAt == null) throw new IllegalArgumentException("expiresAt cannot be null");
        escalateAt = escalateAt == null ? Optional.empty() : escalateAt;
        if (presentation == null || !presentation.embedded()) {
            throw new IllegalArgumentException("attention item requires embedded presentation");
        }
        if (promptMaxUtf8Bytes < 1 || actionLabelMaxUtf8Bytes < 1
                || commentMaxUtf8Bytes < 1) {
            throw new IllegalArgumentException("pinned confirmation limits must be positive");
        }
        availableActions = List.copyOf(availableActions == null ? List.of() : availableActions);
        if (availableActions.isEmpty()
                || new HashSet<>(availableActions).size() != availableActions.size()
                || !presentation.actions().containsAll(availableActions)) {
            throw new IllegalArgumentException("attention item requires authorized pinned actions");
        }
    }
}
