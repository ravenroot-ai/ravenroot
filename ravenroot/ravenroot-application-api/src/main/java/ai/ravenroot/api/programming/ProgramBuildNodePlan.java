package ai.ravenroot.api.programming;

/**
 * Durable, canonical input retained so an interrupted build can safely resume.
* @param nodeId graph node identity associated with the operation or event
* @param language runtime language identifier used to interpret the source
* @param source exact source text used to build the artifact
* @param sourceDigest server-calculated digest of the language and exact source
* @param payloadJson canonical JSON retained for deterministic smoke testing
* @param payloadDigest digest of the canonical smoke-test payload
 */
public record ProgramBuildNodePlan(
        String nodeId,
        String language,
        String source,
        String sourceDigest,
        String payloadJson,
        String payloadDigest) {

    /** Validates content digests and canonical smoke-test input. */
public ProgramBuildNodePlan {
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("nodeId is required");
        String calculated = ProgramArtifactIdentity.sha256(language, source);
        if (!calculated.equals(sourceDigest)) throw new IllegalArgumentException("source digest does not match content");
        if (payloadJson == null || payloadJson.isBlank()) throw new IllegalArgumentException("payloadJson is required");
        if (payloadDigest == null || payloadDigest.isBlank()) throw new IllegalArgumentException("payloadDigest is required");
    }
}
