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
    void acceptsOnlyTheCanonicalPositiveAndDocumentedNegativeAliases() {
        for (String off : new String[] {"false", "off", "0", "no", "FALSE", " Off "}) {
            assertFalse(ExecutionStoreConfiguration.fromEnvironment(
                            Map.of(ExecutionStoreConfiguration.ENABLED_VARIABLE, off)).enabled(),
                    off + " must disable the store");
        }
        for (String enabled : new String[] {"true", "TRUE", " True "}) {
            assertTrue(ExecutionStoreConfiguration.fromEnvironment(
                            Map.of(ExecutionStoreConfiguration.ENABLED_VARIABLE, enabled)).enabled(), enabled);
        }
        for (String blank : new String[] {"", "  ", "\t"}) {
            assertTrue(ExecutionStoreConfiguration.fromEnvironment(
                    Map.of(ExecutionStoreConfiguration.ENABLED_VARIABLE, blank)).enabled(), blank);
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
            org.junit.jupiter.api.Assertions.assertNull(failure.getCause());
            org.junit.jupiter.api.Assertions.assertFalse(failure.getMessage().contains(invalid));
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
    private static final String[] POLICY_VARIABLES = {
            "RAVENROOT_EXECUTION_STORE_MAX_LEASE_TTL_SECONDS",
            "RAVENROOT_EXECUTION_STORE_MAX_PAYLOAD_BYTES",
            "RAVENROOT_EXECUTION_STORE_MAX_CLOCK_SKEW_MILLIS",
            "RAVENROOT_EXECUTION_STORE_JOURNAL_RETENTION_SECONDS",
            "RAVENROOT_EXECUTION_STORE_MAX_INVENTORY_PAGE_SIZE",
            "RAVENROOT_EXECUTION_STORE_TERMINAL_RETENTION_SECONDS",
            "RAVENROOT_EXECUTION_STORE_RESULT_RETENTION_SECONDS",
            "RAVENROOT_SQLITE_BUSY_TIMEOUT_MILLIS"
    };

    @Test void resolvedDefaultsAndBlankBindingsPreserveTheOldRecordShape() {
        assertEquals(2, ExecutionStoreConfiguration.class.getRecordComponents().length);
        var expected = ai.ravenroot.api.persistence.ExecutionStorePolicy.DEFAULTS;
        assertEquals(expected, ExecutionStoreConfiguration.resolveEnvironment(Map.of()).policy());
        for (String variable : POLICY_VARIABLES) {
            for (String blank : new String[]{"", "  ", "\t\n", "\u2003"}) {
                var resolved = ExecutionStoreConfiguration.resolveEnvironment(Map.of(variable, blank));
                assertEquals(expected, resolved.policy());
                assertEquals(java.time.Duration.ofSeconds(5), resolved.sqliteBusyTimeout());
                assertEquals(ai.ravenroot.persistence.sqlite.SqliteStoreConfig.SynchronousMode.FULL,
                        resolved.sqliteStoreConfig().synchronousMode());
            }
        }
    }

    @Test void allEightBindingsResolveTheirOwnUnitsWithoutAmbientReads() {
        var resolved = ExecutionStoreConfiguration.resolveEnvironment(Map.of(
                POLICY_VARIABLES[0], " 013 ", POLICY_VARIABLES[1], "257", POLICY_VARIABLES[2], "1700",
                POLICY_VARIABLES[3], "7200", POLICY_VARIABLES[4], "3", POLICY_VARIABLES[5], "172800",
                POLICY_VARIABLES[6], "14400", POLICY_VARIABLES[7], "19"));
        var policy = resolved.policy();
        assertEquals(java.time.Duration.ofSeconds(13), policy.maxLeaseTtl());
        assertEquals(257, policy.maxPayloadBytes());
        assertEquals(java.time.Duration.ofMillis(1700), policy.maxClockSkew());
        assertEquals(java.time.Duration.ofHours(2), policy.journalRetention());
        assertEquals(3, policy.maxInventoryPageSize());
        assertEquals(java.time.Duration.ofDays(2), policy.terminalRetention());
        assertEquals(java.time.Duration.ofHours(4), policy.executionResultRetention());
        assertEquals(java.time.Duration.ofMillis(19), resolved.sqliteBusyTimeout());
        assertEquals(policy, resolved.sqliteStoreConfig().executionStorePolicy());
    }

    @Test void malformedOrOverflowingValuesFailWithOnlyTheSettingNameAndNoCause() {
        for (String variable : POLICY_VARIABLES) {
            for (String value : new String[]{"secret-value", "+1", "-1", "1.5", "1e3", "1_000",
                    "\u0661", "1\0secret", "9223372036854775808"}) {
                var failure = assertThrows(IllegalArgumentException.class,
                        () -> ExecutionStoreConfiguration.resolveEnvironment(Map.of(variable, value)));
                assertTrue(failure.getMessage().startsWith(variable + " must be a whole number from "));
                assertFalse(failure.getMessage().contains(value));
                org.junit.jupiter.api.Assertions.assertNull(failure.getCause());
            }
        }
    }

    @Test void zeroIsAllowedOnlyForSkewAndBusyTimeout() {
        for (String variable : POLICY_VARIABLES) {
            if (variable.equals(POLICY_VARIABLES[2]) || variable.equals(POLICY_VARIABLES[7])) {
                ExecutionStoreConfiguration.resolveEnvironment(Map.of(variable, "0"));
            } else {
                assertThrows(IllegalArgumentException.class,
                        () -> ExecutionStoreConfiguration.resolveEnvironment(Map.of(variable, "0")));
            }
        }
        assertEquals(java.time.Duration.ZERO,
                ExecutionStoreConfiguration.resolveEnvironment(Map.of(POLICY_VARIABLES[2], "0")).policy().maxClockSkew());
        assertEquals(java.time.Duration.ZERO,
                ExecutionStoreConfiguration.resolveEnvironment(Map.of(POLICY_VARIABLES[7], "0")).sqliteBusyTimeout());
    }

    @Test void representationBoundariesDoNotIntroduceOperationalCeilings() {
        String maximum = Long.toString(Long.MAX_VALUE);
        var resolved = ExecutionStoreConfiguration.resolveEnvironment(Map.of(
                POLICY_VARIABLES[0], maximum, POLICY_VARIABLES[1], "2147483647",
                POLICY_VARIABLES[2], maximum, POLICY_VARIABLES[3], maximum,
                POLICY_VARIABLES[4], "2147483647", POLICY_VARIABLES[5], maximum,
                POLICY_VARIABLES[6], maximum, POLICY_VARIABLES[7], "2147483647"));
        assertEquals(java.time.Duration.ofSeconds(Long.MAX_VALUE), resolved.policy().journalRetention());
        assertEquals(java.time.Duration.ofMillis(Long.MAX_VALUE), resolved.policy().maxClockSkew());
        assertEquals(java.time.Duration.ofMillis(Integer.MAX_VALUE), resolved.sqliteBusyTimeout());
        for (String variable : new String[]{POLICY_VARIABLES[1], POLICY_VARIABLES[4], POLICY_VARIABLES[7]}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ExecutionStoreConfiguration.resolveEnvironment(Map.of(variable, "2147483648")));
        }
        assertThrows(IllegalArgumentException.class, () -> new ExecutionStoreConfiguration.Resolved(
                resolved.configuration(), resolved.policy(), java.time.Duration.ofNanos(1)));
    }

    @Test void bothRetentionRelationsNameTheirBindingsWithoutValuesOrCauses() {
        for (String subordinate : new String[]{POLICY_VARIABLES[3], POLICY_VARIABLES[6]}) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> ExecutionStoreConfiguration.resolveEnvironment(Map.of(subordinate, "604801")));
            assertEquals(POLICY_VARIABLES[5] + " must be at least " + subordinate, failure.getMessage());
            org.junit.jupiter.api.Assertions.assertNull(failure.getCause());
        }
        assertEquals(java.time.Duration.ofSeconds(1), ExecutionStoreConfiguration.resolveEnvironment(Map.of(
                POLICY_VARIABLES[3], "1", POLICY_VARIABLES[5], "1", POLICY_VARIABLES[6], "1"))
                .policy().terminalRetention());
    }

    @Test void disabledModeStillValidatesExplicitPolicyAndRetainsTheLocation() {
        var values = new java.util.HashMap<String, String>();
        values.put(ExecutionStoreConfiguration.ENABLED_VARIABLE, "false");
        values.put(ExecutionStoreConfiguration.DIRECTORY_VARIABLE, "/srv/ravenroot/maintenance-authority");
        var resolved = ExecutionStoreConfiguration.resolveEnvironment(values);
        assertFalse(resolved.configuration().enabled());
        assertTrue(resolved.configuration().location().directory().endsWith("maintenance-authority"));
        for (String variable : POLICY_VARIABLES) {
            var malformed = new java.util.HashMap<>(values);
            malformed.put(variable, "secret-disabled-value");
            assertThrows(IllegalArgumentException.class,
                    () -> ExecutionStoreConfiguration.resolveEnvironment(malformed));
        }
    }

}
