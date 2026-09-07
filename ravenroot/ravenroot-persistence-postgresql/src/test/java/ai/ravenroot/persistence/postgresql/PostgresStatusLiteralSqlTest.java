package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.persistence.HandlerStatus;
import ai.ravenroot.api.persistence.HumanTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The status splits that decide uniqueness are stated twice — once derived from the enum for the
 * runtime queries, once as frozen migration text — and this is what stops the two from drifting.
 *
 * <p>The failure mode is silent and one-sided, which is why it needs a test rather than a comment. A
 * sixth {@link HandlerStatus} that is not terminal would immediately be honoured by every query the
 * store derives, while the shipped partial index would go on covering only the two statuses it was
 * written with: correlation-key uniqueness would stop applying to handlers in the new status, two
 * live handlers could take one key, and nothing would fail. A conformance suite cannot see that,
 * because every assertion it contains is written about the statuses that already exist.</p>
 *
 * <p>The runtime queries are derived and therefore cannot drift. The migration cannot be derived: a
 * migration's text is history, and rewriting it would change what an already-upgraded database was
 * told it received. So the shipped literals are pinned here instead, and adding a status fails this
 * test — which is the signal that a <em>new</em> migration is required, not an edit to the old
 * one.</p>
 *
 * <p>This adapter ships its whole schema as migration 1, so there is no ambiguity about which
 * migration to look in; what the selection still has to prove is that it found the statements at all.
 * A filter that matched nothing would leave every assertion below pinning an empty list — green, and
 * pinning nothing — so each lookup asserts it matched exactly one statement before it asserts
 * anything about its content.</p>
 */
class PostgresStatusLiteralSqlTest {

    /** The handler partial-index predicate exactly as migration 1 shipped it. */
    private static final String FROZEN_HANDLER_PREDICATE = "WHERE status IN ('WAITING', 'ESCALATED')";

    /** The human-task partial-index predicate exactly as migration 1 shipped it. */
    private static final String FROZEN_HUMAN_TASK_PREDICATE =
            "WHERE status IN ('WAITING', 'ESCALATED')";

    @Test
    void theDerivedHandlerListsAreExactlyTheNonTerminalAndTerminalMembers() {
        assertEquals(expectedHandlerList(false), PostgresExecutionStore.LIVE_HANDLER_STATUSES);
        assertEquals(expectedHandlerList(true), PostgresExecutionStore.TERMINAL_HANDLER_STATUSES);
        assertEquals(Set.of("WAITING", "ESCALATED"),
                namesIn(PostgresExecutionStore.LIVE_HANDLER_STATUSES));
        assertEquals(Set.of("RESOLVED", "DENIED", "EXPIRED"),
                namesIn(PostgresExecutionStore.TERMINAL_HANDLER_STATUSES));
    }

    @Test
    void theShippedHandlerIndexStillCoversExactlyTheLiveStatuses() {
        String statement = theOneStatementContaining("execution_handler_live_correlation");
        assertTrue(statement.contains(FROZEN_HANDLER_PREDICATE),
                "the shipped index predicate changed, which a frozen migration's text may not do: "
                        + statement);
        assertEquals(namesIn(PostgresExecutionStore.LIVE_HANDLER_STATUSES),
                namesIn(FROZEN_HANDLER_PREDICATE),
                "a handler status was added or its terminality changed, so the shipped partial index "
                        + "no longer enforces correlation-key uniqueness over every live handler; a "
                        + "NEW migration has to rebuild the index rather than this one being edited");
    }

    @Test
    void theShippedHumanTaskIndexStillCoversExactlyTheLiveStatuses() {
        String statement = theOneStatementContaining("human_task_live_correlation");
        assertTrue(statement.contains(FROZEN_HUMAN_TASK_PREDICATE),
                "the shipped index predicate changed, which a frozen migration's text may not do: "
                        + statement);
        assertEquals(namesIn(PostgresExecutionStore.LIVE_HUMAN_TASK_STATUSES),
                namesIn(FROZEN_HUMAN_TASK_PREDICATE));
        assertTrue(namesIn(PostgresExecutionStore.LIVE_HUMAN_TASK_STATUSES).stream()
                        .allMatch(name -> Arrays.stream(HumanTaskStatus.values())
                                .anyMatch(status -> status.name().equals(name))),
                "the live human-task list names a status this build does not have");
    }

    /**
     * The single migration statement containing {@code fragment}, failing loudly on none or many.
     *
     * <p>Both failure directions matter. None means the selection is pinning nothing; several means
     * the fragment stopped identifying one statement and the assertions below would be checking an
     * arbitrary one of them.</p>
     */
    private static String theOneStatementContaining(String fragment) {
        List<String> matches = PostgresSchema.migrations().stream()
                .flatMap(migration -> migration.statements().stream())
                .filter(statement -> statement.contains(fragment))
                .toList();
        assertEquals(1, matches.size(),
                "expected exactly one shipped statement mentioning " + fragment + ", found "
                        + matches.size());
        return matches.getFirst();
    }

    private static String expectedHandlerList(boolean terminal) {
        return Arrays.stream(HandlerStatus.values())
                .filter(status -> status.terminal() == terminal)
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", ", "(", ")"));
    }

    /** The quoted status names in a SQL fragment, so spacing and ordering cannot fail the pin. */
    private static Set<String> namesIn(String sql) {
        return Arrays.stream(sql.split("'"))
                .filter(part -> part.chars().allMatch(Character::isUpperCase) && !part.isEmpty())
                .collect(Collectors.toSet());
    }
}
