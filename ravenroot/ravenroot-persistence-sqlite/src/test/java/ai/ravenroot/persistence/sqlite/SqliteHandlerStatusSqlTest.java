package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.HandlerStatus;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The handler status split is stated three times — twice as derived SQL, once as frozen migration
 * text — and this is what stops the three from drifting apart.
 *
 * <p>The failure mode is silent and one-sided, which is why it needs a test rather than a comment. A
 * sixth {@link HandlerStatus} that is not terminal would immediately be enforced by the in-memory
 * adapter, whose check is {@code status().terminal()} and therefore always current, while a
 * hand-written {@code IN ('WAITING', 'ESCALATED')} in SQLite would go on ignoring it: the partial
 * unique index would stop covering handlers in the new status, two live handlers could take one
 * correlation key on the durable store and not on the in-memory one, and nothing would fail. That is
 * exactly the class of adapter divergence a conformance suite cannot see, because both adapters would
 * still pass every assertion written about the statuses that already exist.</p>
 *
 * <p>The runtime queries are derived from the enum, so they cannot drift. The migration cannot be:
 * a migration's text is history and rewriting it would change what an already-upgraded database was
 * told it received. So the shipped literal is pinned here instead, and adding a status fails this
 * test — which is the signal that a <em>new</em> migration is required, not an edit to the old one.</p>
 */
class SqliteHandlerStatusSqlTest {

    /** The partial-index predicate exactly as migration 5 shipped it. */
    private static final String FROZEN_MIGRATION_PREDICATE = "WHERE status IN ('WAITING', 'ESCALATED')";

    @Test
    void theDerivedStatusListsAreExactlyTheNonTerminalAndTerminalMembers() {
        assertEquals(expected(false), SqliteExecutionStore.LIVE_HANDLER_STATUSES);
        assertEquals(expected(true), SqliteExecutionStore.TERMINAL_HANDLER_STATUSES);
        assertEquals(Set.of("WAITING", "ESCALATED"), namesIn(SqliteExecutionStore.LIVE_HANDLER_STATUSES));
        assertEquals(Set.of("RESOLVED", "DENIED", "EXPIRED"),
                namesIn(SqliteExecutionStore.TERMINAL_HANDLER_STATUSES));
    }

    @Test
    void theFrozenMigrationPredicateStillMatchesTheCurrentNonTerminalStatuses() {
        String shippedMigration = SqliteSchema.migrations().stream()
                .filter(migration -> migration.version() == 5)
                .flatMap(migration -> migration.statements().stream())
                .filter(statement -> statement.contains("execution_handler_live_correlation"))
                .collect(Collectors.joining());

        assertTrue(shippedMigration.contains(FROZEN_MIGRATION_PREDICATE),
                "migration 5's partial index is history and must not be rewritten; it reads: "
                        + shippedMigration);
        assertEquals(namesIn(SqliteExecutionStore.LIVE_HANDLER_STATUSES), namesIn(FROZEN_MIGRATION_PREDICATE),
                "a status was added or its terminality changed, so the shipped partial index no longer "
                        + "enforces correlation-key uniqueness over every live handler. Add a NEW "
                        + "migration that rebuilds the index; do not edit migration 5");
    }

    private static String expected(boolean terminal) {
        return Arrays.stream(HandlerStatus.values())
                .filter(status -> status.terminal() == terminal)
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", ", "(", ")"));
    }

    /** The quoted status names in a SQL fragment, so the assertion is about the set and not the spacing. */
    private static Set<String> namesIn(String sqlFragment) {
        return Arrays.stream(HandlerStatus.values())
                .map(Enum::name)
                .filter(name -> sqlFragment.contains("'" + name + "'"))
                .collect(Collectors.toUnmodifiableSet());
    }
}
