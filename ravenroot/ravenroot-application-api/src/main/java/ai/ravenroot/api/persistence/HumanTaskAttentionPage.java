package ai.ravenroot.api.persistence;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * One authorized exact-context Human Task attention page and authoritative pre-page counts.
 *
 * @param items immutable rows in creation-time and task-id order.
 * @param nextCursor exclusive opaque cursor for the next page.
 * @param counts counts across every authorized row matching the query.
 * @param nodeCounts per-node counts across every authorized row matching an aggregate query, empty
 *                   when the query already selects one node.
 */
public record HumanTaskAttentionPage(
        List<HumanTaskAttentionItem> items,
        Optional<HumanTaskAttentionCursor> nextCursor,
        HumanTaskAttentionCounts counts,
        List<HumanTaskNodeAttentionCounts> nodeCounts) {

    /** Copies values and verifies deterministic row and node ordering. */
    public HumanTaskAttentionPage {
        items = List.copyOf(items == null ? List.of() : items);
        nextCursor = nextCursor == null ? Optional.empty() : nextCursor;
        if (counts == null) throw new IllegalArgumentException("counts cannot be null");
        nodeCounts = List.copyOf(nodeCounts == null ? List.of() : nodeCounts);
        Comparator<HumanTaskAttentionItem> rows = Comparator.comparing(HumanTaskAttentionItem::createdAt)
                .thenComparing(item -> item.taskId().toString());
        if (!items.equals(items.stream().sorted(rows).toList())) {
            throw new IllegalArgumentException("attention items are not in stable order");
        }
        if (!nodeCounts.equals(nodeCounts.stream()
                .sorted(Comparator.comparing(HumanTaskNodeAttentionCounts::nodeId)).toList())) {
            throw new IllegalArgumentException("node attention counts are not in node order");
        }
    }
}
