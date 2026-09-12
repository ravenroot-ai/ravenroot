package ai.ravenroot.cli;

import ai.ravenroot.server.persistence.ExecutionStoreConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recovery bundle is single-host administration and says so under the shared store.
 *
 * <p>Refusing is the whole answer, not a placeholder for a port. A bundle is a copy of SQLite files
 * taken under a file lock; against a shared database there are no files to copy, the lock would
 * exclude one process on one host and none of the deployment's other replicas, and a restore that
 * replaced the database underneath live replicas would lose the deployment rather than recover it.</p>
 */
class SharedStoreBundleRefusalTest {

    @TempDir
    Path directory;

    @Test
    void theSelectorSpellingMatchesTheServersOwn() {
        // ravenroot-server is a test-scope dependency here, so the constants can be compared but not
        // imported by the shipped CLI. This assertion is what stops the two copies drifting apart.
        assertEquals(ExecutionStoreConfiguration.SELECTOR_VARIABLE,
                BackupRestoreConfiguration.STORE_SELECTOR_VARIABLE);
        assertEquals(ExecutionStoreConfiguration.SELECTOR_PROPERTY,
                BackupRestoreConfiguration.STORE_SELECTOR_PROPERTY);
        assertEquals(ExecutionStoreConfiguration.POSTGRESQL_SELECTOR,
                BackupRestoreConfiguration.SHARED_STORE_SELECTOR);
        assertEquals(ExecutionStoreConfiguration.DIRECTORY_VARIABLE,
                BackupRestoreConfiguration.EXECUTION_STORE_DIR_VARIABLE);
    }

    @Test
    void selectorPropertyOverridesEnvironmentForBundleRefusal() {
        var properties = new Properties();
        properties.setProperty(BackupRestoreConfiguration.STORE_SELECTOR_PROPERTY, "postgresql");
        assertTrue(BackupRestoreConfiguration.sharedStoreSelected(properties,
                Map.of(BackupRestoreConfiguration.STORE_SELECTOR_VARIABLE, "sqlite")));
    }

    @Test
    void backupAndRestoreAreRefusedUnderTheSharedStore() {
        for (String command : new String[] {"backup", "restore"}) {
            var errors = new ByteArrayOutputStream();

            int code = RavenrootCliMain.runBackupRestore(
                    new String[] {command, directory.toString()},
                    Map.of(BackupRestoreConfiguration.STORE_SELECTOR_VARIABLE, "postgresql"),
                    nullStream(), new PrintStream(errors, true, StandardCharsets.UTF_8));

            assertEquals(2, code, command + " must be refused, not attempted");
            String reported = errors.toString(StandardCharsets.UTF_8);
            assertTrue(reported.contains(command + " refused"), reported);
            assertTrue(reported.contains("single-host administration"), reported);
            assertTrue(reported.contains("PostgreSQL"), reported);
        }
    }

    @Test
    void theSelectorIsFoldedButNotGuessedAt() {
        assertTrue(BackupRestoreConfiguration.sharedStoreSelected(
                Map.of(BackupRestoreConfiguration.STORE_SELECTOR_VARIABLE, " PostgreSQL ")));
        // A misspelling reads as "not the shared store". The server is the authority on whether a
        // selector is well formed and refuses a malformed one at startup; answering "no" here is the
        // safe direction, because a deployment with no valid shared selector has a single-host store.
        for (String other : new String[] {"postgres", "sqlite", "", "  "}) {
            assertFalse(BackupRestoreConfiguration.sharedStoreSelected(
                    Map.of(BackupRestoreConfiguration.STORE_SELECTOR_VARIABLE, other)), other);
        }
        assertFalse(BackupRestoreConfiguration.sharedStoreSelected(Map.of()));
    }

    @Test
    void verifyStillRunsUnderTheSharedStoreBecauseItReadsOnlyTheBundle() {
        var errors = new ByteArrayOutputStream();

        int code = RavenrootCliMain.runBackupRestore(
                new String[] {"verify", directory.resolve("absent-bundle").toString()},
                Map.of(BackupRestoreConfiguration.STORE_SELECTOR_VARIABLE, "postgresql"),
                nullStream(), new PrintStream(errors, true, StandardCharsets.UTF_8));

        // It fails, because the bundle is not there - which is the point: it reached the bundle
        // verification rather than the store-selection refusal, so an operator can still check an old
        // single-host bundle after moving to the shared store.
        assertEquals(2, code);
        String reported = errors.toString(StandardCharsets.UTF_8);
        assertTrue(reported.contains("verification refused"), reported);
        assertFalse(reported.contains("single-host administration"), reported);
    }

    private static PrintStream nullStream() {
        return new PrintStream(java.io.OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
    }
}
