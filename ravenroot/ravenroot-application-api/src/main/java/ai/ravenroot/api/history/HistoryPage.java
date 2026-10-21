package ai.ravenroot.api.history;
import java.util.*;
/** Store-bounded page. Empty data distinguishes an absent execution from data removed by retention. */
public record HistoryPage<T>(List<T> items, Optional<String> nextCursor, Availability availability) {
    public enum Availability { AVAILABLE, NOT_FOUND, EXPIRED }
    public HistoryPage {
        items = List.copyOf(items); nextCursor = nextCursor == null ? Optional.empty() : nextCursor;
        Objects.requireNonNull(availability);
        if (availability != Availability.AVAILABLE && (!items.isEmpty() || nextCursor.isPresent()))
            throw new IllegalArgumentException("unavailable history cannot contain fabricated items");
    }
    public static <T> HistoryPage<T> missing() { return new HistoryPage<>(List.of(), Optional.empty(), Availability.NOT_FOUND); }
}
