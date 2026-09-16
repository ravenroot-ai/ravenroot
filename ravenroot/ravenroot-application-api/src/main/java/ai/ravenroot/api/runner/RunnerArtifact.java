package ai.ravenroot.api.runner;

import java.util.Objects;
import java.util.UUID;

/**
 * A durable artifact reference, never a host path or a runner-supplied URL. Retrieval must authorize
 * the complete job identity, verify the digest and enforce retention and byte quotas.
 * @param job owning job and tenant/process scope
 * @param artifactId opaque artifact identifier
 * @param kind logical artifact kind
 * @param sha256 lowercase SHA-256 digest
 * @param sizeBytes exact stored byte size
 */
public record RunnerArtifact(RunnerJobIdentity job, UUID artifactId, Kind kind, String sha256, long sizeBytes) {
    /** Supported reference kinds; large streams remain outside graph payloads. */
    public enum Kind {
        /** Bounded diagnostic log. */
        LOG,
        /** Bounded standard output capture. */
        STDOUT,
        /** Bounded standard error capture. */
        STDERR,
        /** Reviewable workspace change set. */
        PATCH,
        /** Bounded workspace or execution inventory. */
        MANIFEST,
        /** Structured validation evidence. */
        TEST_REPORT
    }

    /** Rejects incomplete or unbounded artifact references. */
    public RunnerArtifact {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(kind, "kind");
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}") || sizeBytes < 0) {
            throw new IllegalArgumentException("invalid runner artifact digest or size");
        }
    }
}
