package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.HandlerStatus;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * <p>The runtime queries are derived from the enum, so they cannot drift. The migration cannot be: a
 * migration's text is history and rewriting it would change what an already-upgraded database was
 * told it received. So the shipped literal is pinned here instead, and adding a status fails this
 * test — which is the signal that a <em>new</em> migration is required, not an edit to the old one.</p>
 *
 * <h2>Selected by description, not by number</h2>
 * <p>The handler migration's number is not stable. It shipped as 5 and became 6 when it merged behind
 * another feature that had taken that number, and the next such merge must not require editing this
 * file. Selecting by number also has a specific failure mode worth naming: a filter left pointing at a
 * number another feature now owns would still find a migration, would still find no handler index
 * inside it, and every assertion below would then be pinning an empty string — green, and pinning
 * nothing. The selection therefore asserts that it matched exactly one migration and that the
 * migration it matched is the one that creates the handler table.</p>
 */
class SqliteHandlerStatusSqlTest {

    /** The partial-index predicate exactly as the handler migration shipped it. */
    private static final String FROZEN_MIGRATION_PREDICATE = "WHERE status IN ('WAITING', 'ESCALATED')";

    /** How the handler migration is identified, independently of the number it happens to carry. */
    private static final String HANDLER_MIGRATION_DESCRIPTION =
            "PERS-05 durable handlers for wait, re-entry and human tasks";

    @Test
    void theDerivedStatusListsAreExactlyTheNonTerminalAndTerminalMembers() {
        assertEquals(expected(false), SqliteExecutionStore.LIVE_HANDLER_STATUSES);
        assertEquals(expected(true), SqliteExecutionStore.TERMINAL_HANDLER_STATUSES);
        assertEquals(Set.of("WAITING", "ESCALATED"), namesIn(SqliteExecutionStore.LIVE_HANDLER_STATUSES));
        assertEquals(Set.of("RESOLVED", "DENIED", "EXPIRED"),
                namesIn(SqliteExecutionStore.TERMINAL_HANDLER_STATUSES));
    }

    @Test
    void thePinSelectsTheHandlerMigrationItselfRatherThanTheNumberItCurrentlyCarries() {
        SchemaMigration migration = handlerMigration();

        // What must hold is that it sits inside the contiguous 1..n range the runner enforces, not
        // that it holds any particular number. The contiguity assertion is the one that turns two
        // branches shipping the same number into a merge decision instead of a silent bug.
        assertTrue(migration.version() >= 1 && migration.version() <= SqliteSchema.currentVersion(),
                "the handler migration must sit inside the range the runner accepts");
        assertEquals(SqliteSchema.migrations().size(), SqliteSchema.currentVersion(),
                "migrations must be numbered 1..n with no gaps or duplicates");
        assertEquals(1, SqliteSchema.migrations().stream()
                        .filter(other -> other.version() == migration.version()).count(),
                "no other migration may share the handler migration's number");
    }

    @Test
    void theFrozenMigrationPredicateStillMatchesTheCurrentNonTerminalStatuses() {
        String shippedIndex = handlerMigration().statements().stream()
                .filter(statement -> statement.contains("execution_handler_live_correlation"))
                .collect(Collectors.joining());

        assertFalse(shippedIndex.isEmpty(),
                "the handler migration must still create the partial index this test exists to pin");
        assertTrue(shippedIndex.contains(FROZEN_MIGRATION_PREDICATE),
                "the handler migration's partial index is history and must not be rewritten; it reads: "
                        + shippedIndex);
        assertEquals(namesIn(SqliteExecutionStore.LIVE_HANDLER_STATUSES), namesIn(FROZEN_MIGRATION_PREDICATE),
                "a status was added or its terminality changed, so the shipped partial index no longer "
                        + "enforces correlation-key uniqueness over every live handler. Add a NEW "
                        + "migration that rebuilds the index; do not edit the one that shipped");
    }

    /**
     * The handler migration, selected by description and then proved to be the one that owns the
     * handler DDL, so a renumber cannot quietly leave this test pinning another feature's statements.
     */
    private static SchemaMigration handlerMigration() {
        List<SchemaMigration> matched = SqliteSchema.migrations().stream()
                .filter(migration -> HANDLER_MIGRATION_DESCRIPTION.equals(migration.description()))
                .toList();
        assertEquals(1, matched.size(),
                "exactly one migration must carry the handler description, or this pin is ambiguous");
        SchemaMigration migration = matched.getFirst();
        assertTrue(migration.statements().stream()
                        .anyMatch(statement -> statement.contains("CREATE TABLE execution_handler")),
                "the migration selected by description must be the one creating the handler table, or "
                        + "the assertions built on it are pinning DDL that belongs to another feature");
        return migration;
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
