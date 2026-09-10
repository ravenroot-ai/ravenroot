package ai.ravenroot.server.persistence;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server composition root supplies an execution store by default, and now says which one.
 *
 * <p>The server composition root never wired an execution store, so every deferral that pointed at
 * "route the runner's writes through the store" pointed at a store that was not there. The default
 * therefore has to be <em>on</em>: an opt-in store would leave the runner's persistence dependency
 * merely satisfiable by configuration rather than satisfied.</p>
 *
 * <p>The selector is new; every other reading here is the one the previous record gave, asserted
 * against the variant type instead of against a boolean. That is the point of the legacy cases below:
 * a deployment that never heard of {@code RAVENROOT_EXECUTION_STORE} must get exactly the store it
 * had.</p>
 */
class ExecutionStoreConfigurationTest {

    @Test
    void anUnconfiguredServerGetsTheSingleHostExecutionStore() {
        var configuration = ExecutionStoreConfiguration.fromEnvironment(Map.of());
        var singleHost = assertInstanceOf(ExecutionStoreConfiguration.SingleHost.class, configuration,
                "absent configuration must mean the single-host store, enabled; a server that "
                        + "silently runs without a store is the state that made three correct "
                        + "deferrals add up to an incorrect sum");
        assertTrue(singleHost.location().databaseFile().toString().contains("execution-store"));
    }

    @Test
    void theDirectoryVariableIsTheOneTheBackupToolAlreadyUses() {
        var configuration = ExecutionStoreConfiguration.fromEnvironment(
                Map.of(ExecutionStoreConfiguration.DIRECTORY_VARIABLE, "/srv/ravenroot/store"));
        var singleHost = assertInstanceOf(ExecutionStoreConfiguration.SingleHost.class, configuration);
        assertTrue(singleHost.location().databaseFile().startsWith("/srv/ravenroot/store"),
                "a server and the CLI that backs it up must not be told the same path twice");
        assertEquals("RAVENROOT_EXECUTION_STORE_DIR", ExecutionStoreConfiguration.DIRECTORY_VARIABLE);
    }

    @Test
    void acceptsOnlyTheCanonicalPositiveAndDocumentedNegativeAliases() {
        for (String off : new String[] {"false", "off", "0", "no", "FALSE", " Off "}) {
            assertInstanceOf(ExecutionStoreConfiguration.Disabled.class,
                    ExecutionStoreConfiguration.fromEnvironment(
                            Map.of(ExecutionStoreConfiguration.ENABLED_VARIABLE, off)),
                    off + " must disable the store");
        }
        for (String enabled : new String[] {"true", "TRUE", " True "}) {
            assertInstanceOf(ExecutionStoreConfiguration.SingleHost.class,
                    ExecutionStoreConfiguration.fromEnvironment(
                            Map.of(ExecutionStoreConfiguration.ENABLED_VARIABLE, enabled)), enabled);
        }
        for (String blank : new String[] {"", "  ", "\t"}) {
            assertInstanceOf(ExecutionStoreConfiguration.SingleHost.class,
                    ExecutionStoreConfiguration.fromEnvironment(
                            Map.of(ExecutionStoreConfiguration.ENABLED_VARIABLE, blank)), blank);
        }
    }

    @Test
    void malformedEnabledValueIsRejectedWithoutEchoOrCause() {
        for (String invalid : new String[] {"flase", "disabled", "nope", "1", "on", "yes",
                "secret-enabled-value"}) {
            var failure = assertThrows(IllegalArgumentException.class, () ->
                    ExecutionStoreConfiguration.fromEnvironment(Map.of(
                            ExecutionStoreConfiguration.ENABLED_VARIABLE, invalid)));
            assertEquals(ExecutionStoreConfiguration.ENABLED_VARIABLE
                    + " must be 'true', 'false', 'off', '0', or 'no'", failure.getMessage());
            assertNull(failure.getCause());
            assertFalse(failure.getMessage().contains(invalid));
        }
    }

    @Test
    void disabledKeepsTheConfiguredDirectoryAsTheMaintenanceAuthority() {
        var disabled = assertInstanceOf(ExecutionStoreConfiguration.Disabled.class,
                ExecutionStoreConfiguration.fromEnvironment(Map.of(
                        ExecutionStoreConfiguration.ENABLED_VARIABLE, "false",
                        ExecutionStoreConfiguration.DIRECTORY_VARIABLE, "/srv/ravenroot/offline-authority")));
        assertTrue(disabled.location().databaseFile().startsWith("/srv/ravenroot/offline-authority"));
    }

    @Test
    void malformedDirectoryIsRejectedWithoutRepeatingTheEnvironmentValue() {
        String invalid = "secret-prefix\0secret-suffix";

        var failure = assertThrows(IllegalArgumentException.class, () ->
                ExecutionStoreConfiguration.fromEnvironment(Map.of(
                        ExecutionStoreConfiguration.DIRECTORY_VARIABLE, invalid)));

        assertEquals("Invalid execution store directory configuration", failure.getMessage());
        assertFalse(failure.getMessage().contains("secret-prefix"));
        assertNull(failure.getCause());
    }

    @Test
    void anExplicitSqliteSelectorIsTheSameAsNoSelector() {
        for (String value : new String[] {"sqlite", "SQLite", " SQLITE "}) {
            assertInstanceOf(ExecutionStoreConfiguration.SingleHost.class,
                    ExecutionStoreConfiguration.fromEnvironment(
                            Map.of(ExecutionStoreConfiguration.SELECTOR_VARIABLE, value)), value);
        }
    }

    @Test
    void thePostgresqlSelectorComposesTheSharedStore() {
        var shared = assertInstanceOf(ExecutionStoreConfiguration.Shared.class,
                ExecutionStoreConfiguration.fromEnvironment(Map.of(
                        ExecutionStoreConfiguration.SELECTOR_VARIABLE, "postgresql",
                        ExecutionStoreConfiguration.URL_VARIABLE, "jdbc:postgresql://db:5432/ravenroot")));
        assertEquals("jdbc:postgresql://db:5432/ravenroot", shared.connection().url());
        assertEquals(3, shared.manifestPinAttempts());
    }

    @Test
    void manifestPinRepairAttemptsAreTypedAndPostgresqlOnly() {
        var environment = new HashMap<String, String>();
        environment.put(ExecutionStoreConfiguration.SELECTOR_VARIABLE, "postgresql");
        environment.put(ExecutionStoreConfiguration.URL_VARIABLE, "jdbc:postgresql://db/ravenroot");
        environment.put(ExecutionStoreConfiguration.MANIFEST_PIN_ATTEMPTS_VARIABLE, "7");
        var shared = assertInstanceOf(ExecutionStoreConfiguration.Shared.class,
                ExecutionStoreConfiguration.fromEnvironment(environment));
        assertEquals(7, shared.manifestPinAttempts());

        for (String invalid : new String[]{"0", "-1", "many", "2147483648"}) {
            environment.put(ExecutionStoreConfiguration.MANIFEST_PIN_ATTEMPTS_VARIABLE, invalid);
            var refusal = assertThrows(IllegalArgumentException.class,
                    () -> ExecutionStoreConfiguration.fromEnvironment(environment));
            assertEquals(ExecutionStoreConfiguration.MANIFEST_PIN_ATTEMPTS_VARIABLE
                    + " must be a positive integer", refusal.getMessage());
        }
    }

    @Test
    void manifestPinRepairAttemptsAreInertWhenBlankAndRefusedForOtherStoreSelections() {
        var blank = new HashMap<String, String>();
        blank.put(ExecutionStoreConfiguration.MANIFEST_PIN_ATTEMPTS_VARIABLE, " \t");
        assertInstanceOf(ExecutionStoreConfiguration.SingleHost.class,
                ExecutionStoreConfiguration.fromEnvironment(blank));

        for (Map<String, String> environment : List.of(
                Map.of(ExecutionStoreConfiguration.MANIFEST_PIN_ATTEMPTS_VARIABLE, "7"),
                Map.of(ExecutionStoreConfiguration.SELECTOR_VARIABLE, "sqlite",
                        ExecutionStoreConfiguration.MANIFEST_PIN_ATTEMPTS_VARIABLE, "7"),
                Map.of(ExecutionStoreConfiguration.ENABLED_VARIABLE, "false",
                        ExecutionStoreConfiguration.MANIFEST_PIN_ATTEMPTS_VARIABLE, "7"))) {
            var refusal = assertThrows(IllegalArgumentException.class,
                    () -> ExecutionStoreConfiguration.fromEnvironment(environment));
            assertEquals(ExecutionStoreConfiguration.MANIFEST_PIN_ATTEMPTS_VARIABLE + " requires "
                    + ExecutionStoreConfiguration.SELECTOR_VARIABLE + "="
                    + ExecutionStoreConfiguration.POSTGRESQL_SELECTOR, refusal.getMessage());
        }
    }

    @Test
    void anUnknownSelectorFailsClosedWithoutEchoingIt() {
        // "postgres" is the typo an operator actually makes, and it is checked here rather than in
        // the non-echo assertion below because it is a substring of the permitted value the message
        // names: asserting the message does not contain it would assert the message is wrong.
        for (String invalid : new String[] {"postgres", "mysql", "sqlite3", "secret-store-name"}) {
            var failure = assertThrows(IllegalArgumentException.class, () ->
                    ExecutionStoreConfiguration.fromEnvironment(Map.of(
                            ExecutionStoreConfiguration.SELECTOR_VARIABLE, invalid)));
            assertEquals(ExecutionStoreConfiguration.SELECTOR_VARIABLE
                    + " must be 'sqlite' or 'postgresql'", failure.getMessage());
        }
        var echoed = assertThrows(IllegalArgumentException.class, () ->
                ExecutionStoreConfiguration.fromEnvironment(Map.of(
                        ExecutionStoreConfiguration.SELECTOR_VARIABLE, "secret-store-name")));
        assertFalse(echoed.getMessage().contains("secret-store-name"));
    }

    @Test
    void theSharedStoreRequiresAUrlAndRefusesAnotherDriversUrl() {
        var missing = assertThrows(IllegalArgumentException.class, () ->
                ExecutionStoreConfiguration.fromEnvironment(Map.of(
                        ExecutionStoreConfiguration.SELECTOR_VARIABLE, "postgresql")));
        assertTrue(missing.getMessage().startsWith(ExecutionStoreConfiguration.URL_VARIABLE),
                missing.getMessage());

        var wrongDriver = assertThrows(IllegalArgumentException.class, () ->
                ExecutionStoreConfiguration.fromEnvironment(Map.of(
                        ExecutionStoreConfiguration.SELECTOR_VARIABLE, "postgresql",
                        ExecutionStoreConfiguration.URL_VARIABLE, "jdbc:mysql://db/ravenroot?user=root")));
        assertEquals(ExecutionStoreConfiguration.URL_VARIABLE
                + " must be a 'jdbc:postgresql:' URL", wrongDriver.getMessage());
        assertFalse(wrongDriver.getMessage().contains("root"),
                "the refusal must not echo a URL, which routinely carries a role and a password");
    }

    @Test
    void disablingTheStoreAndSelectingTheSharedOneIsRefusedRatherThanResolved() {
        var failure = assertThrows(IllegalArgumentException.class, () ->
                ExecutionStoreConfiguration.fromEnvironment(Map.of(
                        ExecutionStoreConfiguration.SELECTOR_VARIABLE, "postgresql",
                        ExecutionStoreConfiguration.ENABLED_VARIABLE, "false")));
        assertTrue(failure.getMessage().contains(ExecutionStoreConfiguration.SELECTOR_VARIABLE));
        assertTrue(failure.getMessage().contains(ExecutionStoreConfiguration.ENABLED_VARIABLE));
    }

    @Test
    void disablingTheStoreWithAnExplicitSqliteSelectorStillMeansDisabled() {
        assertInstanceOf(ExecutionStoreConfiguration.Disabled.class,
                ExecutionStoreConfiguration.fromEnvironment(Map.of(
                        ExecutionStoreConfiguration.SELECTOR_VARIABLE, "sqlite",
                        ExecutionStoreConfiguration.ENABLED_VARIABLE, "off")));
    }

    @Test
    void poolSettingsDefaultAndAreBoundedOnBothSides() {
        var defaults = sharedConnection(Map.of());
        assertEquals(10, defaults.poolSize());
        assertEquals(10_000, defaults.poolTimeout().toMillis());

        var configured = sharedConnection(Map.of(
                ExecutionStoreConfiguration.POOL_SIZE_VARIABLE, "24",
                ExecutionStoreConfiguration.POOL_TIMEOUT_VARIABLE, "2500"));
        assertEquals(24, configured.poolSize());
        assertEquals(2_500, configured.poolTimeout().toMillis());

        for (String invalid : new String[] {"0", "-1", "10000", "twelve", "10.5"}) {
            var failure = assertThrows(IllegalArgumentException.class, () -> sharedConnection(
                    Map.of(ExecutionStoreConfiguration.POOL_SIZE_VARIABLE, invalid)));
            assertTrue(failure.getMessage().startsWith(ExecutionStoreConfiguration.POOL_SIZE_VARIABLE),
                    failure.getMessage());
        }
        // 30000 is the adapter's own statement timeout and is refused because the bound is exclusive:
        // past it a caller blocked in the pool outlives the statement bound the store publishes, so a
        // saturated pool would present to an operator as a slow database.
        for (String invalid : new String[] {"0", "-1", "100", "30000", "45000", "soon"}) {
            var failure = assertThrows(IllegalArgumentException.class, () -> sharedConnection(
                    Map.of(ExecutionStoreConfiguration.POOL_TIMEOUT_VARIABLE, invalid)));
            assertTrue(failure.getMessage().startsWith(ExecutionStoreConfiguration.POOL_TIMEOUT_VARIABLE),
                    failure.getMessage());
        }
        assertEquals(29_999, sharedConnection(Map.of(
                        ExecutionStoreConfiguration.POOL_TIMEOUT_VARIABLE, "29999")).poolTimeout().toMillis(),
                "the ceiling is exclusive, so the millisecond below it must still be accepted");
    }

    @Test
    void theConnectionNeverRendersItsUrlOrPassword() {
        var connection = sharedConnection(Map.of(
                ExecutionStoreConfiguration.USER_VARIABLE, "ravenroot",
                ExecutionStoreConfiguration.PASSWORD_VARIABLE, "correct-horse-battery-staple"));

        String rendered = connection.toString();

        assertFalse(rendered.contains("correct-horse-battery-staple"),
                "a record's generated toString reaches exception messages and assertion failures");
        assertFalse(rendered.contains("jdbc:"), rendered);
        assertTrue(rendered.contains("password=<set>"), rendered);
        assertEquals("correct-horse-battery-staple", connection.password().orElseThrow(),
                "redaction is for rendering only; the pool builder still reads the real value");
    }

    @Test
    void aPasswordIsNotTrimmedBecauseItIsOpaque() {
        var connection = sharedConnection(Map.of(
                ExecutionStoreConfiguration.PASSWORD_VARIABLE, "  spaced  "));
        assertEquals("  spaced  ", connection.password().orElseThrow());
    }

    private static SharedStoreConnection sharedConnection(Map<String, String> extra) {
        var environment = new HashMap<String, String>();
        environment.put(ExecutionStoreConfiguration.SELECTOR_VARIABLE, "postgresql");
        environment.put(ExecutionStoreConfiguration.URL_VARIABLE, "jdbc:postgresql://db:5432/ravenroot");
        environment.putAll(extra);
        return assertInstanceOf(ExecutionStoreConfiguration.Shared.class,
                ExecutionStoreConfiguration.fromEnvironment(environment)).connection();
    }
}
