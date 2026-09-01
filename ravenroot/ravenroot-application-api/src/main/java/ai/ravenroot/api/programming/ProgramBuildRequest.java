package ai.ravenroot.api.programming;

/**
 * One editor-owned program descriptor submitted to a durable graph-level build.
* @param nodeId graph node identity associated with the operation or event
* @param language runtime language identifier used to interpret the source
* @param source exact source text used to build the artifact
* @param testPayload value supplied to qualification smoke testing
 */
public record ProgramBuildRequest(String nodeId, String language, String source, Object testPayload) {
    /** Validates the node identity and source identity inputs. */
public ProgramBuildRequest {
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("nodeId is required");
        ProgramArtifactIdentity.sha256(language, source);
    }
}
