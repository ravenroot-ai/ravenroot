package ai.ravenroot.api.correlation;
import java.util.List;
import java.util.Optional;
/** One bounded page selected before serialization. */
public record BusinessHistoryPage(List<BusinessHistory> items, Optional<String> nextCursor) {
    /** Copies the page and normalizes its cursor. */
    public BusinessHistoryPage { items = List.copyOf(items); nextCursor = nextCursor == null ? Optional.empty() : nextCursor; }
}
