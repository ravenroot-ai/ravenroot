package ai.ravenroot.api.persistence;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded tenant-inbox query with a stable exclusive task cursor.
 *
 * @param statuses optional explicit status filter; empty means every otherwise-admitted status.
 * @param includeTerminal whether terminal tasks may be returned.
 * @param cursor exclusive task-identity cursor.
 * @param limit requested page size, subject to the store's maximum.
 */
public record HumanTaskQuery(Set<HumanTaskStatus> statuses, boolean includeTerminal,
                             Optional<UUID> cursor, int limit) {
    /** Copies status values and normalizes a null cursor to empty. */
    public HumanTaskQuery {
        statuses = statuses == null || statuses.isEmpty() ? Set.of()
                : Set.copyOf(EnumSet.copyOf(statuses));
        cursor = cursor == null ? Optional.empty() : cursor;
    }

    /**
     * Creates a first-page query for every non-terminal task.
     *
     * @param limit requested page size.
     * @return outstanding-task query.
     */
    public static HumanTaskQuery outstanding(int limit) {
        return new HumanTaskQuery(Set.of(), false, Optional.empty(), limit);
    }

    /**
     * Creates a first-page query including terminal tasks.
     *
     * @param limit requested page size.
     * @return all-task query.
     */
    public static HumanTaskQuery everything(int limit) {
        return new HumanTaskQuery(Set.of(), true, Optional.empty(), limit);
    }

    /**
     * Advances this query after an exclusive cursor.
     *
     * @param nextCursor last task identity observed by the caller.
     * @return equivalent query for the following page.
     */
    public HumanTaskQuery after(UUID nextCursor) {
        return new HumanTaskQuery(statuses, includeTerminal, Optional.ofNullable(nextCursor), limit);
    }

    /**
     * Tests a status against this query's filters.
     *
     * @param status status to test.
     * @return {@code true} when a task with the status may be returned.
     */
    public boolean admits(HumanTaskStatus status) {
        return (includeTerminal || !status.terminal()) && (statuses.isEmpty() || statuses.contains(status));
    }
}
