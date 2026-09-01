package ai.ravenroot.cli;

import ai.ravenroot.persistence.sqlite.SqliteStoreLocation;
import ai.ravenroot.server.persistence.ExecutionStoreBootstrap;
import ai.ravenroot.server.persistence.ExecutionStoreConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisabledServerMaintenanceLockIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void disabledExecutionStoreStillBlocksBothAdministrativeCommandsWhileAuditMayBeLive() {
        var location = SqliteStoreLocation.underDirectory(temporaryDirectory.resolve("store"));
        var serverConfiguration = new ExecutionStoreConfiguration(false, location);
        var commandConfiguration = new BackupRestoreConfiguration(temporaryDirectory.resolve("audit"), location);
        var errors = new ByteArrayOutputStream();
        var command = new BackupRestoreCommand(new PrintStream(java.io.OutputStream.nullOutputStream()),
                new PrintStream(errors, true, StandardCharsets.UTF_8));

        try (var disabledServerOwner = ExecutionStoreBootstrap.openOwned(serverConfiguration, Clock.systemUTC())) {
            assertEquals(2, command.backup(commandConfiguration, temporaryDirectory.resolve("backup")));
            assertEquals(2, command.restore(commandConfiguration, temporaryDirectory.resolve("backup")));
        }

        String diagnostics = errors.toString(StandardCharsets.UTF_8);
        assertTrue(diagnostics.contains("backup refused: BUSY"), diagnostics);
        assertTrue(diagnostics.contains("restore refused: BUSY"), diagnostics);
    }
}
