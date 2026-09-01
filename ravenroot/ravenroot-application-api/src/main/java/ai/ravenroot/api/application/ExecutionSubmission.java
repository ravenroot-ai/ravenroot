package ai.ravenroot.api.application;

import java.util.UUID;

/**
 * Acknowledgement returned as soon as a validated graph traversal has been accepted.
 * @param processInstanceId the stable process instance id used to identify the requested resource.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param graphVersion the graph version constraint applied while processing the request.
 */
public record ExecutionSubmission(UUID processInstanceId, UUID traversalId, String graphVersion) {
/**
 * Validates the linked process and traversal identities created by a submission.
 */
    public ExecutionSubmission {
        if (processInstanceId == null) throw new IllegalArgumentException("processInstanceId cannot be null");
        if (traversalId == null) throw new IllegalArgumentException("traversalId cannot be null");
        graphVersion = graphVersion == null ? "" : graphVersion;
    }

/**
 * Compatibility constructor for callers that predate process-instance identity.
 * @param executionId the stable execution id used to identify the requested resource.
 * @param graphVersion the graph version constraint applied while processing the request.
 */
    public ExecutionSubmission(UUID executionId, String graphVersion) {
        this(executionId, executionId, graphVersion);
    }

/**
 * Compatibility alias: legacy execution IDs identify one traversal, not a whole process.
 * @return legacy execution identifier, equal to this submission's traversal ID
 */
    public UUID executionId() {
        return traversalId;
    }
}
