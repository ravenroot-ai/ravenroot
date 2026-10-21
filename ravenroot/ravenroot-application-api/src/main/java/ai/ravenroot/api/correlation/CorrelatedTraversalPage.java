package ai.ravenroot.api.correlation;
import java.util.List;
import java.util.Optional;
/** Bounded constituent traversals, preserving their original process and traversal coordinates. */
public record CorrelatedTraversalPage(List<CorrelatedTraversal> items, Optional<String> nextCursor) {
    /** Copies the page and normalizes its cursor. */
    public CorrelatedTraversalPage { items = List.copyOf(items); nextCursor = nextCursor == null ? Optional.empty() : nextCursor; }
}
