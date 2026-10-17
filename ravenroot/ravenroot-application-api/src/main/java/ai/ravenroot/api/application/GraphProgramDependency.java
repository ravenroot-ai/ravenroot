package ai.ravenroot.api.application;

import java.util.Objects;

/**
 * One tenant-owned ACTIVE program artifact selected for an authored program node.
 *
 * @param nodeId graph node whose exact source selected the artifact
 * @param language runtime language used in the content identity
 * @param sourceSha256 server-calculated domain-separated source identity
 * @param artifactId authoritative tenant-owned artifact identifier
 * @param artifactSha256 content identity recorded on the artifact
 * @param artifactRevision lifecycle revision observed while resolving the dependency
 * @param runtimeCompatibilitySha256 digest of the active runtime compatibility fingerprint
 */
public record GraphProgramDependency(String nodeId, String language, String sourceSha256,
                                     String artifactId, String artifactSha256, long artifactRevision,
                                     String runtimeCompatibilitySha256)
        implements Comparable<GraphProgramDependency> {
    /** Validates stable identifiers and digests used by publication manifests. */
    public GraphProgramDependency {
        nodeId = requireText(nodeId, "nodeId", 256);
        language = requireText(language, "language", 128);
        sourceSha256 = requireSha256(sourceSha256, "sourceSha256");
        artifactId = requireText(artifactId, "artifactId", 256);
        artifactSha256 = requireSha256(artifactSha256, "artifactSha256");
        if (artifactRevision < 1) throw new IllegalArgumentException("artifactRevision must be positive");
        runtimeCompatibilitySha256 = requireSha256(
                runtimeCompatibilitySha256, "runtimeCompatibilitySha256");
    }

    @Override
    public int compareTo(GraphProgramDependency other) {
        int result = nodeId.compareTo(other.nodeId);
        if (result != 0) return result;
        result = sourceSha256.compareTo(other.sourceSha256);
        return result != 0 ? result : artifactId.compareTo(other.artifactId);
    }

    private static String requireText(String value, String name, int limit) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > limit || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static String requireSha256(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(name + " must be SHA-256");
        return value;
    }
}
