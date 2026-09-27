package ai.ravenroot.core.graph;

import java.util.List;
import java.util.Optional;

public final class GraphValidationException extends IllegalArgumentException {

    private final List<String> violations;
    private final String primaryNodeId;

    public GraphValidationException(List<String> violations) {
        this(violations, null);
    }

    public GraphValidationException(List<String> violations, String primaryNodeId) {
        super(String.join("; ", violations));
        this.violations = List.copyOf(violations);
        this.primaryNodeId = primaryNodeId;
    }

    public List<String> violations() {
        return violations;
    }

    /** Exact graph-local locator for the primary violation; format it before any public emission. */
    public Optional<String> primaryNodeId() {
        return Optional.ofNullable(primaryNodeId);
    }
}
