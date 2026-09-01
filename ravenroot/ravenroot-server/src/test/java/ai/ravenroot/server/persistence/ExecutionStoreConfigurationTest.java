package ai.ravenroot.server.persistence;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server composition root supplies an execution store by default.
 *
 * <p>The server composition root never wired an execution store, so every deferral that pointed at
 * "route the runner's writes through the store" pointed at a store that was not there. The default
 * therefore has to be <em>on</em>: an opt-in store would leave the runner's persistence dependency
 * merely satisfiable by configuration rather than satisfied.</p>
 */
class ExecutionStoreConfigurationTest {

    @Test
    void anUnconfiguredServerGetsAnExecutionStore() {
        ExecutionStoreConfiguration configuration = ExecutionStoreConfiguration.fromEnvironment(Map.of());
        assertTrue(configuration.enabled(),
                "absent configuration must mean enabled; a server that silently runs without a store "
                        + "is the state that made three correct deferrals add up to an incorrect sum");
        assertTrue(configuration.location().databaseFile().toString().contains("execution-store"));
    }

    @Test
    void theDirectoryVariableIsTheOneTheBackupToolAlreadyUses() {
        ExecutionStoreConfiguration configuration = ExecutionStoreConfiguration.fromEnvironment(
                Map.of(ExecutionStoreConfiguration.DIRECTORY_VARIABLE, "/srv/ravenroot/store"));
        assertTrue(configuration.enabled());
        assertTrue(configuration.location().databaseFile().startsWith("/srv/ravenroot/store"),
                "a server and the CLI that backs it up must not be told the same path twice");
        assertEquals("RAVENROOT_EXECUTION_STORE_DIR", ExecutionStoreConfiguration.DIRECTORY_VARIABLE);
    }

    @Test
    void onlyAnExplicitRecognisedNegativeDisablesIt() {
        for (String off : new String[] {"false", "off", "0", "no", "FALSE", " Off "}) {
            assertFalse(ExecutionStoreConfiguration.fromEnvironment(
                            Map.of(ExecutionStoreConfiguration.ENABLED_VARIABLE, off)).enabled(),
                    off + " must disable the store");
        }
        // A typo must not silently turn durability off. Failing towards "on" is the safe direction:
        // an unwanted store is visible on disk, an absent one is invisible until a crash loses work.
        for (String noise : new String[] {"", "  ", "flase", "disabled", "nope", "true", "1"}) {
            assertTrue(ExecutionStoreConfiguration.fromEnvironment(
                            Map.of(ExecutionStoreConfiguration.ENABLED_VARIABLE, noise)).enabled(),
                    "'" + noise + "' is not a recognised negative and must not disable durability");
        }
    }

    @Test
    void disabledKeepsTheConfiguredDirectoryAsTheMaintenanceAuthority() {
        ExecutionStoreConfiguration disabled = ExecutionStoreConfiguration.fromEnvironment(Map.of(
                ExecutionStoreConfiguration.ENABLED_VARIABLE, "false",
                ExecutionStoreConfiguration.DIRECTORY_VARIABLE, "/srv/ravenroot/offline-authority"));
        assertFalse(disabled.enabled());
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
        org.junit.jupiter.api.Assertions.assertNull(failure.getCause());
    }
}
