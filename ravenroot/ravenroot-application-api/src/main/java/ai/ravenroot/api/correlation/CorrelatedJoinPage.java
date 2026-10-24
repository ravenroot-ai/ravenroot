package ai.ravenroot.api.correlation;
import java.util.*;
/** Store-bounded page of join summaries/continuation intents; only authorized execution services read data.
 * @param items immutable selected page members, bounded before public projection
 * @param nextCursor opaque continuation bound to the same scope and query, absent after the final page
 */
public record CorrelatedJoinPage(List<CorrelatedJoinSummary> items, Optional<String> nextCursor) {
    /** Copies only selected scalar summaries and normalizes an absent final-page continuation. */
    public CorrelatedJoinPage { items = List.copyOf(items); nextCursor = nextCursor == null ? Optional.empty() : nextCursor; }
}
