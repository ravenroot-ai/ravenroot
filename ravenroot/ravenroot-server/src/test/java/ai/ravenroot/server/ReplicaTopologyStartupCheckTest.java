package ai.ravenroot.server;

import ai.ravenroot.server.persistence.ExecutionStoreConfiguration;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every combination this build cannot honour, and the one it can.
 *
 * <p>Each refusal replaces a configuration that previously started and then behaved as if it had not:
 * two replicas with a per-pod SQLite file, a shared store selected beside a directory the CLI's
 * backup still reads, replicas beyond one while several authorities remain per-pod.</p>
 */
class ReplicaTopologyStartupCheckTest {

    @Test
    void oneReplicaOnTheSingleHostStoreIsTheShippedDefaultAndIsAccepted() {
        assertNull(ReplicaTopologyStartupCheck.evaluate(Map.of(),
                ExecutionStoreConfiguration.fromEnvironment(Map.of())));
    }

    @Test
    void oneReplicaOnTheSharedStoreIsAccepted() {
        var environment = sharedEnvironment(Map.of());
        assertNull(ReplicaTopologyStartupCheck.evaluate(environment,
                ExecutionStoreConfiguration.fromEnvironment(environment)));
    }

    @Test
    void severalReplicasOnTheSingleHostStoreAreRefusedNamingTheSelector() {
        var environment = Map.of(ReplicaCount.VARIABLE, "3");

        var refusal = ReplicaTopologyStartupCheck.evaluate(environment,
                ExecutionStoreConfiguration.fromEnvironment(environment));

        assertNotNull(refusal, "two replicas are two SQLite files, not one store with two writers");
        assertEquals("EXECUTION_STORE_SINGLE_HOST", refusal.code());
        assertTrue(refusal.detail().contains(ReplicaCount.VARIABLE));
        assertTrue(refusal.detail().contains(ExecutionStoreConfiguration.SELECTOR_VARIABLE));
    }

    @Test
    void severalReplicasOnTheSharedStoreAreRefusedNamingTheAuthoritiesThatAreStillPerReplica() {
        var environment = sharedEnvironment(Map.of(ReplicaCount.VARIABLE, "2"));

        var refusal = ReplicaTopologyStartupCheck.evaluate(environment,
                ExecutionStoreConfiguration.fromEnvironment(environment));

        assertNotNull(refusal, "a shared execution store does not make the rest of the process shared");
        assertEquals("REPLICA_LOCAL_STATE_UNSUPPORTED", refusal.code());
        for (String named : new String[] {"author-entered credentials", "program artifact registry",
                "authoring assistant consent", "audit trail"}) {
            assertTrue(refusal.detail().contains(named), named + " missing from: " + refusal.detail());
        }
        assertFalse(refusal.detail().contains("embed registration authority"),
                "the embed is conditional and this deployment did not enable it");
    }

    @Test
    void anEnabledEmbedIsNamedAmongThePerReplicaAuthorities() {
        var environment = sharedEnvironment(Map.of(ReplicaCount.VARIABLE, "2",
                "RAVENROOT_EMBED_ENABLED", "true"));

        var refusal = ReplicaTopologyStartupCheck.evaluate(environment,
                ExecutionStoreConfiguration.fromEnvironment(environment));

        assertNotNull(refusal);
        assertTrue(refusal.detail().contains("embed registration authority"), refusal.detail());
    }

    @Test
    void theSharedStoreBesideASingleHostDirectoryIsRefusedBeforeAnythingElse() {
        var environment = sharedEnvironment(Map.of(
                ExecutionStoreConfiguration.DIRECTORY_VARIABLE, "/srv/ravenroot/store",
                ReplicaCount.VARIABLE, "4"));

        var refusal = ReplicaTopologyStartupCheck.evaluate(environment,
                ExecutionStoreConfiguration.fromEnvironment(environment));

        assertNotNull(refusal);
        assertEquals("EXECUTION_STORE_LOCATION_CONFLICT", refusal.code(),
                "the contradiction is reported before the topology, because it is the next thing to fix");
        assertTrue(refusal.detail().contains(ExecutionStoreConfiguration.DIRECTORY_VARIABLE));
        assertFalse(refusal.detail().contains("/srv/ravenroot/store"),
                "a refusal reaches a log aggregator and must not print a path");
    }

    /**
     * The mirror of the location conflict, and the one that fails in the more expensive direction.
     *
     * <p>These settings are read by the shared store and by nothing else, so with the selector on the
     * single-host store they are inert. Without this refusal the server starts on a pod-local file
     * while an operator who configured a database believes their durable state is in it — and points a
     * backup procedure at a database that holds nothing.</p>
     */
    @Test
    void sharedSettingsWithoutTheSharedSelectorAreRefused() {
        var environment = Map.of(
                ExecutionStoreConfiguration.URL_VARIABLE,
                "jdbc:postgresql://db.internal:5432/ravenroot",
                ExecutionStoreConfiguration.PASSWORD_VARIABLE, "hunter2");

        var refusal = ReplicaTopologyStartupCheck.evaluate(environment,
                ExecutionStoreConfiguration.fromEnvironment(environment));

        assertNotNull(refusal, "a database was configured and silently not used");
        assertEquals("EXECUTION_STORE_SELECTOR_CONFLICT", refusal.code());
        assertTrue(refusal.detail().contains(ExecutionStoreConfiguration.URL_VARIABLE),
                "the refusal must name the setting that was seen: " + refusal.detail());
        assertFalse(refusal.detail().contains("db.internal"),
                "a refusal reaches a log aggregator and must not print a URL");
        assertFalse(refusal.detail().contains("hunter2"),
                "a refusal must never print a password");
    }

    @Test
    void theSameSettingsAreAcceptedOnceTheSharedStoreIsSelected() {
        var environment = sharedEnvironment(Map.of());

        assertNull(ReplicaTopologyStartupCheck.evaluate(environment,
                ExecutionStoreConfiguration.fromEnvironment(environment)),
                "the settings the shared store reads cannot be a conflict for the shared store");
    }

    @Test
    void manifestPinAttemptsWithoutTheSharedSelectorAreRefused() {
        var environment = Map.of(
                ExecutionStoreConfiguration.MANIFEST_PIN_ATTEMPTS_VARIABLE, "7");

        // Exercise the topology check's independent defensive boundary. Production parses first,
        // and the parser has its own stricter test for this same contradictory environment.
        var refusal = ReplicaTopologyStartupCheck.evaluate(environment,
                ExecutionStoreConfiguration.fromEnvironment(Map.of()));

        assertNotNull(refusal);
        assertEquals("EXECUTION_STORE_SELECTOR_CONFLICT", refusal.code());
        assertTrue(refusal.detail().contains(
                ExecutionStoreConfiguration.MANIFEST_PIN_ATTEMPTS_VARIABLE));
    }

    @Test
    void aSingleHostDirectoryIsNotAConflictForTheSingleHostStore() {
        var environment = Map.of(ExecutionStoreConfiguration.DIRECTORY_VARIABLE, "/srv/ravenroot/store");
        assertNull(ReplicaTopologyStartupCheck.evaluate(environment,
                ExecutionStoreConfiguration.fromEnvironment(environment)));
    }

    @Test
    void aMalformedReplicaCountIsRefusedRatherThanAssumedToBeOne() {
        var environment = Map.of(ReplicaCount.VARIABLE, "two");

        var refusal = ReplicaTopologyStartupCheck.evaluate(environment,
                ExecutionStoreConfiguration.fromEnvironment(environment));

        assertNotNull(refusal);
        assertEquals("REPLICA_CONFIGURATION_INVALID", refusal.code());
    }

    @Test
    void everyRefusalRendersAsOneMachineReadableLineThatNamesNoSecret() {
        var environment = sharedEnvironment(Map.of(ReplicaCount.VARIABLE, "2",
                ExecutionStoreConfiguration.PASSWORD_VARIABLE, "correct-horse-battery-staple"));

        var refusal = ReplicaTopologyStartupCheck.evaluate(environment,
                ExecutionStoreConfiguration.fromEnvironment(environment));

        assertNotNull(refusal);
        String diagnostic = refusal.diagnostic();
        assertTrue(diagnostic.startsWith("{\"event\":\"startup_refused\",\"code\":\""), diagnostic);
        assertTrue(diagnostic.endsWith("\"}"), diagnostic);
        assertFalse(diagnostic.contains("correct-horse-battery-staple"));
        assertFalse(diagnostic.contains("jdbc:"));
        assertFalse(diagnostic.contains("\n"), "one refusal is one line");
    }

    private static Map<String, String> sharedEnvironment(Map<String, String> extra) {
        var environment = new HashMap<String, String>();
        environment.put(ExecutionStoreConfiguration.SELECTOR_VARIABLE, "postgresql");
        environment.put(ExecutionStoreConfiguration.URL_VARIABLE, "jdbc:postgresql://db:5432/ravenroot");
        environment.putAll(extra);
        return Map.copyOf(environment);
    }
}
