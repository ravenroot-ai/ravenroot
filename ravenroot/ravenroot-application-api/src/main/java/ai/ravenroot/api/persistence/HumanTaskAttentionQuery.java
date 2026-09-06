package ai.ravenroot.api.persistence;

import java.util.Optional;
import java.util.UUID;

/**
 * Exact durable context used to read actionable embedded Human Tasks.
 *
 * <p>A query names one graph version and exactly one enclosing deployment or process instance.
 * Optional identities only narrow that context. The cursor is scoped to every identity in this
 * record, excluding the page limit, so it cannot be reused to cross a context boundary.</p>
 *
 * @param graphVersion immutable graph-version pin recorded by the execution.
 * @param deploymentId hosting deployment, present only for a deployment-scoped query.
 * @param processInstanceId process instance, present only for a process-scoped query.
 * @param traversalId optional exact traversal identity.
 * @param nodeId optional exact graph node identity.
 * @param taskId optional exact task identity.
 * @param generation optional exact task generation.
 * @param cursor optional exclusive stable page boundary.
 * @param limit requested page size, subject to the store maximum.
 */
public record HumanTaskAttentionQuery(
        String graphVersion,
        Optional<String> deploymentId,
        Optional<UUID> processInstanceId,
        Optional<UUID> traversalId,
        Optional<String> nodeId,
        Optional<UUID> taskId,
        Optional<Long> generation,
        Optional<HumanTaskAttentionCursor> cursor,
        int limit) {

    /** Validates exact context and normalizes absent optional values. */
    public HumanTaskAttentionQuery {
        graphVersion = HandlerRegistration.requireBoundedKey(graphVersion, "graphVersion");
        deploymentId = deploymentId == null ? Optional.empty() : deploymentId;
        processInstanceId = processInstanceId == null ? Optional.empty() : processInstanceId;
        traversalId = traversalId == null ? Optional.empty() : traversalId;
        nodeId = nodeId == null ? Optional.empty() : nodeId;
        taskId = taskId == null ? Optional.empty() : taskId;
        generation = generation == null ? Optional.empty() : generation;
        cursor = cursor == null ? Optional.empty() : cursor;
        if (deploymentId.isPresent() == processInstanceId.isPresent()) {
            throw new IllegalArgumentException(
                    "exactly one of deploymentId or processInstanceId must be present");
        }
        deploymentId.ifPresent(value -> HandlerRegistration.requireBoundedKey(value, "deploymentId"));
        nodeId.ifPresent(value -> HandlerRegistration.requireBoundedKey(value, "nodeId"));
        generation.ifPresent(value -> {
            if (value < 1) throw new IllegalArgumentException("generation must be positive");
        });
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
    }

    /**
     * Creates a first-page deployment-scoped query.
     *
     * @param graphVersion immutable graph-version pin.
     * @param deploymentId hosting deployment.
     * @param limit requested page size.
     * @return exact deployment query.
     */
    public static HumanTaskAttentionQuery forDeployment(String graphVersion, String deploymentId,
                                                        int limit) {
        return new HumanTaskAttentionQuery(graphVersion, Optional.ofNullable(deploymentId),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), limit);
    }

    /**
     * Creates a first-page process-scoped query.
     *
     * @param graphVersion immutable graph-version pin.
     * @param processInstanceId exact process instance.
     * @param limit requested page size.
     * @return exact process query.
     */
    public static HumanTaskAttentionQuery forProcess(String graphVersion, UUID processInstanceId,
                                                     int limit) {
        return new HumanTaskAttentionQuery(graphVersion, Optional.empty(),
                Optional.ofNullable(processInstanceId), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), limit);
    }

    /**
     * Returns this exact query advanced past a stable cursor boundary.
     *
     * @param nextCursor opaque cursor returned by the preceding page.
     * @return query for the following page.
     */
    public HumanTaskAttentionQuery after(HumanTaskAttentionCursor nextCursor) {
        return new HumanTaskAttentionQuery(graphVersion, deploymentId, processInstanceId,
                traversalId, nodeId, taskId, generation, Optional.ofNullable(nextCursor), limit);
    }
}
