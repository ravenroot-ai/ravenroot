package ai.ravenroot.cli;

import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditEnvelope;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.core.audit.FileAuditTrail;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.persistence.sqlite.SqliteStoreLocation;
import ai.ravenroot.persistence.sqlite.SqliteStoreMaintenanceLock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the CLI command mechanism round-trips real content into a
 * genuinely separate location, exactly as a disaster-recovery restore onto a new machine would.
 * The concurrent-writes-in-flight drill is covered separately; this class covers only that
 * {@code backup}/{@code restore} do what they claim on a quiescent system.
 */
class BackupRestoreCommandTest {
    private static final Instant EPOCH = Instant.parse("2026-08-11T00:00:00Z");
    private static final String TENANT = "tenant-a";

    @TempDir
    Path root;

    @Test
    void backupThenRestoreRecreatesBothTheAuditRecordAndTheExecutionAtAGenuinelySeparateLocation()
            throws Exception {
        Path liveAuditDir = root.resolve("live/audit");
        Path liveStoreDir = root.resolve("live/store");
        Path backupDir = root.resolve("backup");
        Path restoredAuditDir = root.resolve("restored/audit");
        Path restoredStoreDir = root.resolve("restored/store");

        var liveConfiguration = new BackupRestoreConfiguration(liveAuditDir,
                SqliteStoreLocation.underDirectory(liveStoreDir));
        UUID processInstanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();
        var key = new ExecutionKey(TENANT, processInstanceId);

        // Write one audit record and one execution to the "live" location.
        try (var trail = new FileAuditTrail(liveAuditDir, Clock.fixed(EPOCH, ZoneOffset.UTC), Duration.ofHours(24))) {
            trail.append(auditEnvelope());
        }
        try (var store = new SqliteExecutionStore(liveConfiguration.executionStoreLocation(), Clock.systemUTC())) {
            store.apply(creationBatch(key, traversalId)).toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        var out = capturing();
        var err = capturing();
        var command = new BackupRestoreCommand(out.stream(), err.stream());

        assertEquals(0, command.backup(liveConfiguration, backupDir), () -> "backup failed: " + err.text());
        assertTrue(java.nio.file.Files.exists(backupDir.resolve("MANIFEST.txt")), "MANIFEST.txt must exist");
        assertTrue(java.nio.file.Files.exists(backupDir.resolve("execution-store.db")),
                "execution-store.db must exist");

        var restoredConfiguration = new BackupRestoreConfiguration(restoredAuditDir,
                SqliteStoreLocation.underDirectory(restoredStoreDir));
        assertEquals(0, command.restore(restoredConfiguration, backupDir), () -> "restore failed: " + err.text());

        // The audit chain verifies at the restored location, from a fresh trail instance.
        try (var restoredTrail = new FileAuditTrail(restoredAuditDir, Clock.fixed(EPOCH, ZoneOffset.UTC),
                Duration.ofHours(24))) {
            var verification = restoredTrail.verify(TENANT);
            assertTrue(verification.intact(), () -> "chain must verify after restore, anomalies: "
                    + verification.anomalies());
            assertEquals(1, restoredTrail.read(TENANT, 0, 10).size());
        }

        // The execution is readable at the restored location, from a fresh store instance.
        try (var restoredStore = new SqliteExecutionStore(restoredConfiguration.executionStoreLocation(),
                Clock.systemUTC())) {
            var loaded = restoredStore.load(key).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(processInstanceId, loaded.state().processInstanceId());
            assertEquals(ProcessInstanceStatus.ACCEPTED, loaded.state().status());
        }
    }

    @Test
    void backupRefusesAnExistingNonEmptyDestination() throws Exception {
        Path destination = root.resolve("occupied");
        java.nio.file.Files.createDirectories(destination);
        java.nio.file.Files.writeString(destination.resolve("something"), "already here");
        var configuration = new BackupRestoreConfiguration(root.resolve("audit"),
                SqliteStoreLocation.underDirectory(root.resolve("store")));
        var err = capturing();
        var command = new BackupRestoreCommand(capturing().stream(), err.stream());

        assertEquals(2, command.backup(configuration, destination));
        assertTrue(err.text().contains("DESTINATION_EXISTS"), err.text());
    }

    @Test
    void restoreRefusesADirectoryThatIsNotABackup() {
        Path notABackup = root.resolve("not-a-backup");
        var configuration = new BackupRestoreConfiguration(root.resolve("audit"),
                SqliteStoreLocation.underDirectory(root.resolve("store")));
        var err = capturing();
        var command = new BackupRestoreCommand(capturing().stream(), err.stream());

        assertEquals(2, command.restore(configuration, notABackup));
        assertTrue(err.text().contains("UNSAFE_LOCATION"), err.text());
    }

    @Test
    void backupAndRestoreFailClosedWhileTheServerMaintenanceLeaseIsHeld() throws Exception {
        var configuration = new BackupRestoreConfiguration(root.resolve("audit"),
                SqliteStoreLocation.underDirectory(root.resolve("store")));
        var backup = root.resolve("backup");
        var backupError = capturing();
        var restoreError = capturing();
        var command = new BackupRestoreCommand(capturing().stream(), backupError.stream());

        try (var heldByServer = SqliteStoreMaintenanceLock.acquire(configuration.executionStoreLocation())) {
            assertEquals(2, command.backup(configuration, backup));
            assertTrue(backupError.text().contains("BUSY"), backupError.text());
            command = new BackupRestoreCommand(capturing().stream(), restoreError.stream());
            assertEquals(2, command.restore(configuration, backup));
            assertTrue(restoreError.text().contains("BUSY"), restoreError.text());
        }
    }

    @Test
    void restoreRefusesASymlinkedStoreBeforeItWritesTheAuditDestination() throws Exception {
        Path liveAudit = root.resolve("live/audit");
        var live = new BackupRestoreConfiguration(liveAudit,
                SqliteStoreLocation.underDirectory(root.resolve("live/store")));
        Path backup = root.resolve("backup");
        var maker = new BackupRestoreCommand(capturing().stream(), capturing().stream());
        assertEquals(0, maker.backup(live, backup));
        Path original = backup.resolve("execution-store.db");
        Path moved = backup.resolve("actual-store.db");
        java.nio.file.Files.move(original, moved);
        java.nio.file.Files.createSymbolicLink(original, moved.getFileName());

        Path restoredAudit = root.resolve("restored/audit");
        var target = new BackupRestoreConfiguration(restoredAudit,
                SqliteStoreLocation.underDirectory(root.resolve("restored/store")));
        var err = capturing();
        var command = new BackupRestoreCommand(capturing().stream(), err.stream());

        assertEquals(2, command.restore(target, backup));
        assertTrue(err.text().contains("UNSAFE_ARTIFACT"), err.text());
        assertTrue(java.nio.file.Files.notExists(restoredAudit), "audit target must remain untouched");
    }

    @Test
    void restorePreflightsAnInvalidSqliteBackupBeforeItWritesTheAuditDestination() throws Exception {
        var live = new BackupRestoreConfiguration(root.resolve("live/audit"),
                SqliteStoreLocation.underDirectory(root.resolve("live/store")));
        Path backup = root.resolve("backup");
        var maker = new BackupRestoreCommand(capturing().stream(), capturing().stream());
        assertEquals(0, maker.backup(live, backup));
        java.nio.file.Files.writeString(backup.resolve("execution-store.db"), "not sqlite");

        Path restoredAudit = root.resolve("restored/audit");
        var target = new BackupRestoreConfiguration(restoredAudit,
                SqliteStoreLocation.underDirectory(root.resolve("restored/store")));
        var err = capturing();
        var command = new BackupRestoreCommand(capturing().stream(), err.stream());

        assertEquals(2, command.restore(target, backup));
        assertTrue(err.text().contains("DIGEST_MISMATCH"), err.text());
        assertTrue(java.nio.file.Files.notExists(restoredAudit), "audit target must remain untouched");
    }

    private static AuditEnvelope auditEnvelope() {
        return new AuditEnvelope(AuditEnvelope.CURRENT_VERSION, UUID.randomUUID(), TENANT, "alice",
                AuditCategory.ACCESS, "EXECUTION_START", "executions", "executions", AuditOutcome.ALLOWED,
                "policy allowed", "request-1", EPOCH, OpaquePayload.of("{}".getBytes(StandardCharsets.UTF_8),
                        "application/json"));
    }

    private static ExecutionBatch creationBatch(ExecutionKey key, UUID traversalId) {
        var instance = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                Map.of(traversalId, new Traversal(traversalId, "start", TraversalStatus.ACCEPTED, Map.of())));
        return ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(instance, new GraphVersionPin("graph-v1")))
                .build();
    }

    private static Capturing capturing() {
        return new Capturing();
    }

    private static final class Capturing {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final PrintStream stream = new PrintStream(buffer, true, StandardCharsets.UTF_8);

        PrintStream stream() {
            return stream;
        }

        String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
