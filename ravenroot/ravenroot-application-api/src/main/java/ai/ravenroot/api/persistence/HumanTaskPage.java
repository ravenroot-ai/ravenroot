package ai.ravenroot.api.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One deterministic page of the transport-neutral tenant human-task inbox.
 *
 * @param items immutable tasks in stable task-identity order.
 * @param nextCursor exclusive cursor for the next page, when one exists.
 */
public record HumanTaskPage(List<DurableHumanTask> items, Optional<UUID> nextCursor) {
    /** Copies collection values and normalizes a null cursor to empty. */
    public HumanTaskPage {
        items = List.copyOf(items == null ? List.of() : items);
        nextCursor = nextCursor == null ? Optional.empty() : nextCursor;
    }
}
