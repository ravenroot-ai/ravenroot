package ai.ravenroot.core.graph;

import java.util.List;

public final class GraphValidationException extends IllegalArgumentException {

    private final List<String> violations;

    public GraphValidationException(List<String> violations) {
        super(String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
