package ai.ravenroot.api.correlation;

import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** A business join is scoped independently of any contributing process, traversal or graph edge. */
public record CorrelatedJoinKey(BusinessScope scope, String applicationCorrelationId, String nodeId, String roundId) {
    public CorrelatedJoinKey {
        Objects.requireNonNull(scope, "scope");
        for (String value : new String[]{applicationCorrelationId, nodeId, roundId})
            if (value == null || value.isBlank() || value.length() > 4096
                    || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid join key");
    }
    /** Stable, domain-separated continuation identity, preserved across retries and store recovery. */
    public UUID continuationProcessId() { return identity("process"); }
    /** Stable continuation traversal identity, never a contributing traversal's ID. */
    public UUID continuationTraversalId() { return identity("traversal"); }
    private UUID identity(String domain) {
        return UUID.nameUUIDFromBytes(("correlated-join-v1\0" + domain + '\0' + scope.tenantId() + '\0'
                + scope.deploymentId() + '\0' + scope.graphVersion() + '\0' + applicationCorrelationId + '\0'
                + nodeId + '\0' + roundId).getBytes(StandardCharsets.UTF_8));
    }
}
