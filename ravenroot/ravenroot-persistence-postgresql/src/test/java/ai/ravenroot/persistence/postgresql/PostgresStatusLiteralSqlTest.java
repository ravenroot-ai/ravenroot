package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.persistence.ExecutionPauseStatus;
import ai.ravenroot.api.persistence.HandlerStatus;
import ai.ravenroot.api.persistence.HumanTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
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
 * <p>Every status-bearing table this adapter ships was created in migration 1, so the selection below
 * searches the whole migration list and still finds one statement per index; what it has to prove is
 * that it found the statements at all. A filter that matched nothing would leave every assertion below
 * pinning an empty list — green, and pinning nothing — so each lookup asserts it matched exactly one
 * statement before it asserts anything about its content.</p>
 *
 * <p>{@link #namesIn} carries the same hazard one level down, which is why
 * {@link #theHelperExtractsAStatusNameCarryingDigitsAndUnderscores} exists: it is applied to both
 * sides of every comparison here, so a name it silently drops disappears from both and the pin passes
 * while the shipped index is wrong — the exact failure shape these pins are here to catch.</p>
 */
class PostgresStatusLiteralSqlTest {

    /** The handler partial-index predicate exactly as migration 1 shipped it. */
    private static final String FROZEN_HANDLER_PREDICATE = "WHERE status IN ('WAITING', 'ESCALATED')";

    /** The human-task partial-index predicate exactly as migration 1 shipped it. */
    private static final String FROZEN_HUMAN_TASK_PREDICATE =
            "WHERE status IN ('WAITING', 'ESCALATED')";

    /** The execution-pause partial-index predicate exactly as migration 1 shipped it. */
    private static final String FROZEN_EXECUTION_PAUSE_PREDICATE = "WHERE status = 'HELD'";

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
    void theDerivedHumanTaskListIsExactlyTheNonTerminalMembers() {
        // Against the enum, not against another constant. The earlier shape of this test compared
        // LIVE_HUMAN_TASK_STATUSES with a hardcoded frozen predicate that named the same two
        // statuses, so it could not fail for the reason the test exists: adding a live status would
        // have left both sides unchanged and the shipped index quietly wrong.
        assertEquals(expectedList(HumanTaskStatus.values(), HumanTaskStatus::terminal, false),
                PostgresExecutionStore.LIVE_HUMAN_TASK_STATUSES);
    }

    @Test
    void theShippedHumanTaskIndexStillCoversExactlyTheLiveStatuses() {
        String statement = theOneStatementContaining("human_task_live_correlation");
        assertTrue(statement.contains(FROZEN_HUMAN_TASK_PREDICATE),
                "the shipped index predicate changed, which a frozen migration's text may not do: "
                        + statement);
        assertEquals(namesIn(PostgresExecutionStore.LIVE_HUMAN_TASK_STATUSES),
                namesIn(FROZEN_HUMAN_TASK_PREDICATE),
                "a human-task status was added or its terminality changed, so the shipped partial "
                        + "index no longer enforces correlation-key uniqueness over every live task; "
                        + "a NEW migration has to rebuild the index rather than this one being edited");
    }

    @Test
    void theDerivedExecutionPauseListIsExactlyTheNonTerminalMembers() {
        assertEquals(expectedList(ExecutionPauseStatus.values(), ExecutionPauseStatus::terminal, false),
                PostgresExecutionStore.LIVE_EXECUTION_PAUSE_STATUSES);
    }

    @Test
    void theShippedExecutionPauseIndexStillCoversExactlyTheLiveStatuses() {
        String statement = theOneStatementContaining("execution_pause_held_traversal");
        assertTrue(statement.contains(FROZEN_EXECUTION_PAUSE_PREDICATE),
                "the shipped index predicate changed, which a frozen migration's text may not do: "
                        + statement);
        assertEquals(namesIn(PostgresExecutionStore.LIVE_EXECUTION_PAUSE_STATUSES),
                namesIn(FROZEN_EXECUTION_PAUSE_PREDICATE),
                "an execution-pause status became live, so the shipped partial index no longer makes "
                        + "\"is this traversal held\" a single deterministic answer; a NEW migration "
                        + "has to rebuild the index rather than this one being edited");
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
        return expectedList(HandlerStatus.values(), HandlerStatus::terminal, terminal);
    }

    /** The list the store should have derived, rebuilt here from the enum rather than restated. */
    private static <T extends Enum<T>> String expectedList(T[] values,
                                                           java.util.function.Predicate<T> terminal,
                                                           boolean wanted) {
        return Arrays.stream(values)
                .filter(status -> terminal.test(status) == wanted)
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", ", "(", ")"));
    }

    /**
     * A status name as this repository spells one: uppercase letters, digits and underscores.
     *
     * <p>Deliberately not "every character is uppercase". {@link Character#isUpperCase} is false for
     * {@code _} and for every digit, so a status named {@code WAITING_FOR_APPROVAL} or {@code TIER2}
     * would be dropped by such a filter — and dropped from <em>both</em> sides of every comparison
     * below, since the same helper reads the derived list and the frozen predicate. The pin would then
     * compare two sets that agree because neither contains the status that was added, which is
     * precisely the silent, one-sided drift these assertions exist to detect.</p>
     */
    private static final Pattern STATUS_NAME = Pattern.compile("[A-Z0-9_]+");

    /** The quoted status names in a SQL fragment, so spacing and ordering cannot fail the pin. */
    private static Set<String> namesIn(String sql) {
        return Arrays.stream(sql.split("'"))
                .filter(part -> STATUS_NAME.matcher(part).matches())
                .collect(Collectors.toSet());
    }

    /**
     * The helper itself, against the names it used to lose.
     *
     * <p>Every other assertion in this class runs {@code namesIn} over both operands, so none of them
     * can fail when it drops a name. This is the only test here that can, and it is the reason the
     * others mean anything.</p>
     */
    @Test
    void theHelperExtractsAStatusNameCarryingDigitsAndUnderscores() {
        assertEquals(Set.of("WAITING_FOR_APPROVAL", "TIER2"),
                namesIn("WHERE status IN ('WAITING_FOR_APPROVAL', 'TIER2')"),
                "a status name with an underscore or a digit must survive extraction, or the pins "
                        + "above compare two sets that are equal only because both lost it");
        assertEquals(Set.of("HELD"), namesIn("WHERE status = 'HELD'"),
                "and the ordinary single-name predicate keeps working");
    }
}
