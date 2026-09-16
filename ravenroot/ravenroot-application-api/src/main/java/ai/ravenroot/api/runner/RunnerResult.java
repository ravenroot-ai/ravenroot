package ai.ravenroot.api.runner;

import ai.ravenroot.api.persistence.OpaquePayload;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded terminal report. The authenticated runner supplies it only after descendants have stopped
 * and workspace writes and referenced artifacts are durably flushed. The server validates the
 * owning job, fence, outcome and effective byte ceilings before accepting it.
 * @param outcome explicit graph routing classification
 * @param payload bounded context
 * @param artifacts durable references
 * @param quiescenceId stable runner acknowledgement of the ownership barrier
 */
public record RunnerResult(String outcome, OpaquePayload payload, List<RunnerArtifact> artifacts,
                            UUID quiescenceId) {
    /** Enforces protocol-wide bounds; admission applies the stricter effective policy. */
    public RunnerResult {
        outcome = RunnerPolicy.identifier(outcome);
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(quiescenceId, "quiescenceId");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        if (payload.size() > 1_048_576 || payload.contentType().length() > 128 || artifacts.size() > 128) {
            throw new IllegalArgumentException("runner result exceeds protocol bounds");
        }
        var ids = new HashSet<UUID>();
        if (artifacts.stream().anyMatch(artifact -> !ids.add(artifact.artifactId()))) {
            throw new IllegalArgumentException("duplicate runner artifact identifier");
        }
    }

    /** Payloads and references are not emitted into diagnostic logs. */
    @Override public String toString() {
        return "RunnerResult[outcome=" + outcome + ", payloadBytes=" + payload.size()
                + ", artifacts=" + artifacts.size() + "]";
    }
}
