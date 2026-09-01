package ai.ravenroot.api.programming;

/**
 * Output and resulting immutable revision produced by a successful artifact test.
 * @param artifact immutable artifact revision that was tested.
 * @param output value returned by the successful sandbox test.
 */
public record ArtifactTestResult(GeneratedArtifact artifact, Object output) {
/**
 * Requires an artifact because test output without its revision has no lifecycle meaning.
 */
    public ArtifactTestResult {
        if (artifact == null) throw new IllegalArgumentException("Artifact cannot be null");
    }
}
