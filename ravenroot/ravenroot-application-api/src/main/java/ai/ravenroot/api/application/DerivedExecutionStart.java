package ai.ravenroot.api.application;

import java.util.UUID;

/** Fresh derived identities and their independently cancellable execution result.
 * @param processInstanceId new derived process identity
 * @param traversalId new derived traversal identity
 * @param graphVersion pinned graph version
 */
public record DerivedExecutionStart(UUID processInstanceId, UUID traversalId, String graphVersion) {
    /** Validates the returned identities and graph version. */
    public DerivedExecutionStart {
        if (processInstanceId == null || traversalId == null || graphVersion == null || graphVersion.isBlank())
            throw new IllegalArgumentException("derived execution start fields cannot be null");
    }
}
