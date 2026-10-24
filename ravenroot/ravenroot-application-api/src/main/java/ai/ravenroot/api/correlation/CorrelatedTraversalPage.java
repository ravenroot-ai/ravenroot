package ai.ravenroot.api.correlation;
import java.util.List;
import java.util.Optional;
/** Bounded constituent traversals, preserving their original process and traversal coordinates.
 * @param items immutable selected page members, bounded before public projection
 * @param nextCursor opaque continuation bound to the same scope and query, absent after the final page
 */
public record CorrelatedTraversalPage(List<CorrelatedTraversal> items, Optional<String> nextCursor) {
    /** Copies the page and normalizes its cursor. */
    public CorrelatedTraversalPage { items = List.copyOf(items); nextCursor = nextCursor == null ? Optional.empty() : nextCursor; }
}
