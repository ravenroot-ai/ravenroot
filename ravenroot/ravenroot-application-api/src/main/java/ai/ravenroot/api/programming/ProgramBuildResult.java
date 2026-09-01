package ai.ravenroot.api.programming;

/**
 * Snapshot returned by the one server-owned Build/readiness operation.
 * {@code smokeOutput} is already constrained by the normal payload boundary.
* @param nodeId graph node identity associated with the operation or event
* @param artifact qualified artifact snapshot associated with the node
* @param sourceDigest server-calculated digest of the language and exact source
* @param payloadDigest digest of the canonical smoke-test payload
* @param phase current server-owned build phase
* @param ready whether the artifact passed every readiness gate
* @param reused whether an existing content-addressed artifact satisfied the build
* @param smokeOutput bounded output returned by qualification smoke testing
* @param diagnostic bounded diagnostic text, or an empty string when none is available
 */
public record ProgramBuildResult(
        String nodeId,
        GeneratedArtifact artifact,
        String sourceDigest,
        String payloadDigest,
        ProgramBuildPhase phase,
        boolean ready,
        boolean reused,
        Object smokeOutput,
        String diagnostic) {

    /** Validates required identities and normalizes optional diagnostics. */
public ProgramBuildResult {
        nodeId = nodeId == null ? "" : nodeId;
        if (artifact == null) throw new IllegalArgumentException("artifact is required");
        if (sourceDigest == null || sourceDigest.isBlank()) {
            throw new IllegalArgumentException("sourceDigest is required");
        }
        if (payloadDigest == null || payloadDigest.isBlank()) {
            throw new IllegalArgumentException("payloadDigest is required");
        }
        if (phase == null) throw new IllegalArgumentException("phase is required");
        diagnostic = diagnostic == null ? "" : diagnostic;
    }
}
