package ai.ravenroot.api.correlation;
import java.util.*;
/** Store-bounded page of join summaries/continuation intents; only authorized execution services read data. */
public record CorrelatedJoinPage(List<CorrelatedJoinSummary> items, Optional<String> nextCursor) {
    public CorrelatedJoinPage { items = List.copyOf(items); nextCursor = nextCursor == null ? Optional.empty() : nextCursor; }
}
