package ai.ravenroot.api.history;
import java.util.*;
/** Store-bounded page. Empty data distinguishes an absent execution from data removed by retention.
 * @param items immutable selected page members, bounded before public projection
 * @param nextCursor opaque continuation bound to the same scope and query, absent after the final page
 * @param availability explicit metadata or content availability without inventing missing data
 * @param <T> public metadata row type in this bounded page
 */
public record HistoryPage<T>(List<T> items, Optional<String> nextCursor, Availability availability) {
    /** Metadata availability independent from captured payload retention. */
    public enum Availability {
        /** The selection exists and the bounded metadata page is available. */
        AVAILABLE,
        /** The selected process, traversal or visit is not retained in the authenticated scope. */
        NOT_FOUND,
        /** The requested metadata retention window expired. */
        EXPIRED,
        /** Older data predates this scalar projection; no zero counts are fabricated. */
        NOT_RECORDED
    }
    /** Copies selected metadata and rejects rows on a missing or expired selection. */
    public HistoryPage {
        items = List.copyOf(items); nextCursor = nextCursor == null ? Optional.empty() : nextCursor;
        Objects.requireNonNull(availability);
        if (availability != Availability.AVAILABLE && (!items.isEmpty() || nextCursor.isPresent()))
            throw new IllegalArgumentException("unavailable history cannot contain fabricated items");
    }
/** Empty page explicitly classified as unavailable for the selected identity.
 * @return empty page explicitly classified as unavailable for the selected identity
 * @param <T> metadata row type represented by this empty page
 */
        public static <T> HistoryPage<T> missing() { return new HistoryPage<>(List.of(), Optional.empty(), Availability.NOT_FOUND); }
}
