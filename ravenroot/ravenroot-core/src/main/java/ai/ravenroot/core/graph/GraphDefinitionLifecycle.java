package ai.ravenroot.core.graph;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic in-memory lifecycle coordinator for graph definitions.
 *
 * <p>This class owns no execution records and performs no persistence. A
 * durable catalog can preserve the same immutable contracts outside
 * {@link GraphManager}, whose responsibility remains topology.</p>
 */
public final class GraphDefinitionLifecycle {
    private final Map<GraphVersionKey, GraphVersionRecord> versions = new LinkedHashMap<>();
    private final Map<String, GraphVersionKey> activeByGraph = new LinkedHashMap<>();

    public GraphVersionRecord validate(GraphVersionKey key, GraphDefinition definition) {
        return new GraphVersionRecord(GraphVersionSnapshot.create(key, definition), GraphVersionState.VALIDATED);
    }

    public synchronized GraphVersionRecord publish(GraphVersionRecord validated) {
        validateCandidate(validated);
        GraphVersionRecord existing = existingVersion(validated);
        if (existing != null) {
            return existing;
        }
        boolean graphAlreadyPublished = versions.keySet().stream()
                .anyMatch(key -> key.graphId().equals(validated.snapshot().key().graphId()));
        if (graphAlreadyPublished) {
            throw new IllegalStateException(
                    "Publishing a subsequent graph version requires an explicit baseline and compatibility policy");
        }
        return storePublished(validated);
    }

    public synchronized GraphVersionRecord publish(GraphVersionRecord validated, GraphVersionKey baseline,
                                                   GraphCompatibilityPolicy policy) {
        validateCandidate(validated);
        Objects.requireNonNull(policy, "policy");
        GraphVersionRecord existing = existingVersion(validated);
        if (existing != null) {
            return existing;
        }
        GraphVersionRecord reference = requireVersion(baseline);
        if (!reference.snapshot().key().graphId().equals(validated.snapshot().key().graphId())) {
            throw new IllegalArgumentException("Compatibility baseline belongs to a different logical graph");
        }
        GraphCompatibilityReport report = GraphCompatibilityReport.analyze(
                reference.snapshot().definition(), validated.snapshot().definition());
        report.require(policy);
        return storePublished(validated);
    }

    private void validateCandidate(GraphVersionRecord validated) {
        Objects.requireNonNull(validated, "validated");
        if (validated.state() != GraphVersionState.VALIDATED) {
            throw new IllegalStateException("Only a validated graph version can be published");
        }
    }

    private GraphVersionRecord existingVersion(GraphVersionRecord validated) {
        GraphVersionKey key = validated.snapshot().key();
        GraphVersionRecord existing = versions.get(key);
        if (existing != null) {
            if (!existing.snapshot().canonicalHash().equals(validated.snapshot().canonicalHash())) {
                throw new IllegalStateException("Graph version " + key
                        + " is immutable and already has a different canonical hash");
            }
        }
        return existing;
    }

    private GraphVersionRecord storePublished(GraphVersionRecord validated) {
        GraphVersionKey key = validated.snapshot().key();
        GraphVersionRecord published = validated.transition(GraphVersionState.PUBLISHED);
        versions.put(key, published);
        return published;
    }

    public synchronized GraphVersionRecord activate(GraphVersionKey key) {
        GraphVersionRecord selected = requireVersion(key);
        if (selected.state() == GraphVersionState.RETIRED) {
            throw new IllegalStateException("Retired graph version cannot be activated: " + key);
        }
        if (selected.state() != GraphVersionState.PUBLISHED && selected.state() != GraphVersionState.ACTIVE) {
            throw new IllegalStateException("Graph version must be published before activation: " + key);
        }
        GraphVersionKey previousKey = activeByGraph.put(key.graphId(), key);
        if (previousKey != null && !previousKey.equals(key)) {
            GraphVersionRecord previous = versions.get(previousKey);
            versions.put(previousKey, previous.transition(GraphVersionState.PUBLISHED));
        }
        GraphVersionRecord active = selected.transition(GraphVersionState.ACTIVE);
        versions.put(key, active);
        return active;
    }

    public synchronized GraphVersionRecord retire(GraphVersionKey key) {
        GraphVersionRecord selected = requireVersion(key);
        if (selected.state() == GraphVersionState.ACTIVE) {
            throw new IllegalStateException("Activate a replacement before retiring graph version: " + key);
        }
        if (selected.state() == GraphVersionState.RETIRED) {
            return selected;
        }
        if (selected.state() != GraphVersionState.PUBLISHED) {
            throw new IllegalStateException("Only a published graph version can be retired: " + key);
        }
        GraphVersionRecord retired = selected.transition(GraphVersionState.RETIRED);
        versions.put(key, retired);
        return retired;
    }

    public synchronized Optional<GraphVersionRecord> find(GraphVersionKey key) {
        return Optional.ofNullable(versions.get(key));
    }

    public synchronized Optional<GraphVersionRecord> active(String graphId) {
        GraphVersionKey key = activeByGraph.get(graphId);
        return key == null ? Optional.empty() : Optional.of(versions.get(key));
    }

    public synchronized GraphExecutionPin pinActive(String graphId) {
        return active(graphId)
                .map(GraphVersionRecord::snapshot)
                .map(GraphExecutionPin::from)
                .orElseThrow(() -> new IllegalStateException("Graph has no active version: " + graphId));
    }

    private GraphVersionRecord requireVersion(GraphVersionKey key) {
        Objects.requireNonNull(key, "key");
        GraphVersionRecord version = versions.get(key);
        if (version == null) {
            throw new IllegalArgumentException("Unknown published graph version: " + key);
        }
        return version;
    }
}
