package ai.ravenroot.api.correlation;
import java.util.List;
import java.util.Optional;
/** One bounded page selected before serialization.
 * @param items immutable selected page members, bounded before public projection
 * @param nextCursor opaque continuation bound to the same scope and query, absent after the final page
 */
public record BusinessHistoryPage(List<BusinessHistory> items, Optional<String> nextCursor) {
    /** Copies the page and normalizes its cursor. */
    public BusinessHistoryPage { items = List.copyOf(items); nextCursor = nextCursor == null ? Optional.empty() : nextCursor; }
}
