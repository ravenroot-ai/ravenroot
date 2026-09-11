package ai.ravenroot.server.persistence;

import ai.ravenroot.cli.BackupRestoreConfiguration;
import ai.ravenroot.core.audit.AuditTrailDirectory;
import ai.ravenroot.server.audit.AuditTrailConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The offline tool must address the same stores as the server for the same deployment input. */
class BackupRestoreDirectoryParityTest {

    @Test
    void auditDirectoryDefaultBlankAndNondefaultResolutionMatchesTheServer() {
        assertAuditDirectoryParity(Map.of());
        for (String value : new String[] {"", "  ", "\t", "  /srv/ravenroot/audit  "}) {
            assertAuditDirectoryParity(Map.of(BackupRestoreConfiguration.AUDIT_DIR_VARIABLE, value));
        }
        assertEquals(Path.of(AuditTrailDirectory.DEFAULT_DIRECTORY),
                BackupRestoreConfiguration.fromEnvironment(Map.of()).auditDirectory());
    }

    @Test
    void executionStoreDirectoryDefaultBlankAndNondefaultResolutionMatchesTheServer() {
        assertExecutionStoreDirectoryParity(Map.of());
        for (String value : new String[] {"", "  ", "\t", "  /srv/ravenroot/store  "}) {
            assertExecutionStoreDirectoryParity(Map.of(
                    BackupRestoreConfiguration.EXECUTION_STORE_DIR_VARIABLE, value));
        }
    }

    @Test
    void bothCompositionRootsRefuseTheSameMalformedPaths() {
        Map<String, String> invalidAudit = Map.of(
                BackupRestoreConfiguration.AUDIT_DIR_VARIABLE, "bad\0audit");
        assertThrows(IllegalArgumentException.class,
                () -> BackupRestoreConfiguration.fromEnvironment(invalidAudit));
        assertThrows(IllegalArgumentException.class,
                () -> AuditTrailConfiguration.fromEnvironment(invalidAudit));

        Map<String, String> invalidStore = Map.of(
                BackupRestoreConfiguration.EXECUTION_STORE_DIR_VARIABLE, "bad\0store");
        assertThrows(IllegalArgumentException.class,
                () -> BackupRestoreConfiguration.fromEnvironment(invalidStore));
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionStoreConfiguration.fromEnvironment(invalidStore));
    }

    private static void assertAuditDirectoryParity(Map<String, String> environment) {
        assertEquals(AuditTrailConfiguration.fromEnvironment(environment).directory().path(),
                BackupRestoreConfiguration.fromEnvironment(environment).auditDirectory());
    }

    private static void assertExecutionStoreDirectoryParity(Map<String, String> environment) {
        var server = (ExecutionStoreConfiguration.SingleHost)
                ExecutionStoreConfiguration.fromEnvironment(environment);
        assertEquals(server.location(),
                BackupRestoreConfiguration.fromEnvironment(environment).executionStoreLocation());
    }
}
