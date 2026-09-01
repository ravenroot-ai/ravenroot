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
import ai.ravenroot.server.persistence.ExecutionStoreBootstrap;
import ai.ravenroot.server.persistence.ExecutionStoreConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryRestoreTransactionTest {
    private static final String TENANT = "recovery-tenant";
    private static final UUID OLD_PROCESS = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NEW_PROCESS = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @TempDir
    Path root;

    @Test
    void everyForwardCheckpointRollsBackToAReadableGenerationAndRetryInstallsTheBundle() throws Exception {
        Path bundle = bundleWithNewGeneration();
        for (RecoveryRestoreTransaction.Checkpoint checkpoint : new RecoveryRestoreTransaction.Checkpoint[] {
                RecoveryRestoreTransaction.Checkpoint.SOURCE_MANIFEST_COPIED,
                RecoveryRestoreTransaction.Checkpoint.SOURCE_SNAPSHOT_VERIFIED,
                RecoveryRestoreTransaction.Checkpoint.PREPARATION_JOURNALED,
                RecoveryRestoreTransaction.Checkpoint.STORE_ARTIFACTS_READY,
                RecoveryRestoreTransaction.Checkpoint.JOURNAL_WRITTEN,
                RecoveryRestoreTransaction.Checkpoint.AUDIT_BACKED_UP,
                RecoveryRestoreTransaction.Checkpoint.AUDIT_INSTALLED,
                RecoveryRestoreTransaction.Checkpoint.STORE_BACKED_UP,
                RecoveryRestoreTransaction.Checkpoint.STORE_INSTALLED}) {
            var target = configuration(root.resolve("target-" + checkpoint));
            writeGeneration(target, OLD_PROCESS, "old-request");
            var errors = new ByteArrayOutputStream();
            var faulting = new BackupRestoreCommand(nullOutput(), new PrintStream(errors), reached -> {
                if (reached == checkpoint) {
                    throw new java.io.IOException("injected");
                }
            });

            assertEquals(1, faulting.restore(target, bundle), checkpoint + ": " + errors);
            assertGeneration(target, OLD_PROCESS, "old-request");
            assertNoRecoveryArtifacts(target);

            assertEquals(0, new BackupRestoreCommand(nullOutput(), new PrintStream(errors))
                    .restore(target, bundle), checkpoint + " retry: " + errors);
            assertGeneration(target, NEW_PROCESS, "new-request");
            assertNoRecoveryArtifacts(target);
        }
    }

    @Test
    void interruptedRestoreRollsBackAndPreservesInterruptStatusBeforeAValidRetry() throws Exception {
        Path bundle = bundleWithNewGeneration();
        var target = configuration(root.resolve("interrupted-target"));
        writeGeneration(target, OLD_PROCESS, "old-request");
        var errors = new ByteArrayOutputStream();
        var command = new BackupRestoreCommand(nullOutput(), new PrintStream(errors), checkpoint -> {
            if (checkpoint == RecoveryRestoreTransaction.Checkpoint.STORE_BACKED_UP) {
                throw new InterruptedIOException("injected interruption");
            }
        });

        try {
            assertEquals(1, command.restore(target, bundle));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(errors.toString(StandardCharsets.UTF_8).contains("INTERRUPTED"));
        } finally {
            Thread.interrupted();
        }
        assertGeneration(target, OLD_PROCESS, "old-request");
        assertEquals(0, new BackupRestoreCommand(nullOutput(), new PrintStream(errors)).restore(target, bundle));
        assertGeneration(target, NEW_PROCESS, "new-request");
    }

    @Test
    void failedRollbackLeavesAPathFreeJournalThatBlocksServerAndIsRecoveredOnRetry() throws Exception {
        Path bundle = bundleWithNewGeneration();
        var target = configuration(root.resolve("rollback-failure-target"));
        writeGeneration(target, OLD_PROCESS, "old-request");
        var errors = new ByteArrayOutputStream();
        var command = new BackupRestoreCommand(nullOutput(), new PrintStream(errors), checkpoint -> {
            if (checkpoint == RecoveryRestoreTransaction.Checkpoint.AUDIT_INSTALLED
                    || checkpoint == RecoveryRestoreTransaction.Checkpoint.ROLLBACK_STARTED) {
                throw new java.io.IOException("injected rollback failure");
            }
        });

        assertEquals(1, command.restore(target, bundle));
        Path journal = target.executionStoreLocation().directory().resolve(
                RecoveryRestoreTransaction.JOURNAL_FILE);
        assertTrue(Files.isRegularFile(journal));
        String journalText = Files.readString(journal);
        assertFalse(journalText.contains(target.auditDirectory().toString()));
        assertFalse(journalText.contains(target.executionStoreLocation().databaseFile().toString()));

        var startup = assertThrows(ExecutionStoreBootstrap.StartupException.class,
                () -> ExecutionStoreBootstrap.openOwned(new ExecutionStoreConfiguration(
                        true, target.executionStoreLocation()), Clock.systemUTC()));
        assertEquals(ExecutionStoreBootstrap.FailureReason.RECOVERY_PENDING, startup.reason());

        assertEquals(0, new BackupRestoreCommand(nullOutput(), new PrintStream(errors)).restore(target, bundle),
                errors::toString);
        assertGeneration(target, NEW_PROCESS, "new-request");
        assertNoRecoveryArtifacts(target);
    }

    @Test
    void hardlinkReplacementAfterManifestCopyIsRejectedWithoutTouchingLiveGeneration() throws Exception {
        Path bundle = bundleWithNewGeneration();
        Path store = bundle.resolve(RecoveryBundle.STORE_FILE);
        Path replacement = root.resolve("same-size-corrupt-store.db");
        Files.copy(store, replacement);
        try (var channel = FileChannel.open(replacement, StandardOpenOption.WRITE)) {
            channel.position(0);
            channel.write(ByteBuffer.wrap(new byte[] {'X'}));
        }
        var target = configuration(root.resolve("race-target"));
        writeGeneration(target, OLD_PROCESS, "old-request");
        var errors = new ByteArrayOutputStream();
        var command = new BackupRestoreCommand(nullOutput(), new PrintStream(errors), checkpoint -> {
            if (checkpoint == RecoveryRestoreTransaction.Checkpoint.SOURCE_MANIFEST_COPIED) {
                Files.delete(store);
                Files.createLink(store, replacement);
            }
        });

        assertEquals(2, command.restore(target, bundle), errors::toString);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("DIGEST_MISMATCH"));
        assertGeneration(target, OLD_PROCESS, "old-request");
        assertNoRecoveryArtifacts(target);
    }

    @Test
    void untrustedBundleMutationAfterSnapshotVerificationCannotChangeInstalledBytes() throws Exception {
        Path bundle = bundleWithNewGeneration();
        var target = configuration(root.resolve("snapshot-target"));
        writeGeneration(target, OLD_PROCESS, "old-request");
        var errors = new ByteArrayOutputStream();
        var command = new BackupRestoreCommand(nullOutput(), new PrintStream(errors), checkpoint -> {
            if (checkpoint == RecoveryRestoreTransaction.Checkpoint.SOURCE_SNAPSHOT_VERIFIED) {
                Files.writeString(bundle.resolve(RecoveryBundle.STORE_FILE), "untrusted mutation",
                        StandardOpenOption.TRUNCATE_EXISTING);
            }
        });

        assertEquals(0, command.restore(target, bundle), errors::toString);
        assertGeneration(target, NEW_PROCESS, "new-request");
        assertNoRecoveryArtifacts(target);
    }

    @Test
    void stagedAndRollbackStoreArtifactsArePrivateBeforeTheLiveRename() throws Exception {
        Path bundle = bundleWithNewGeneration();
        var target = configuration(root.resolve("permission-target"));
        writeGeneration(target, OLD_PROCESS, "old-request");
        if (!Files.getFileStore(target.executionStoreLocation().directory())
                .supportsFileAttributeView("posix")) {
            return;
        }
        var errors = new ByteArrayOutputStream();
        var command = new BackupRestoreCommand(nullOutput(), new PrintStream(errors), checkpoint -> {
            if (checkpoint != RecoveryRestoreTransaction.Checkpoint.STORE_ARTIFACTS_READY) {
                return;
            }
            try (var entries = Files.list(target.executionStoreLocation().directory())) {
                var privateDirectories = entries.filter(path -> path.getFileName().toString()
                        .startsWith(".ravenroot-restore-store-")).toList();
                assertEquals(2, privateDirectories.size());
                for (Path directory : privateDirectories) {
                    assertEquals(PosixFilePermissions.fromString("rwx------"),
                            Files.getPosixFilePermissions(directory));
                    try (var files = Files.list(directory)) {
                        for (Path file : files.toList()) {
                            assertEquals(PosixFilePermissions.fromString("rw-------"),
                                    Files.getPosixFilePermissions(file));
                        }
                    }
                }
            }
            throw new java.io.IOException("stop before live rename");
        });

        assertEquals(1, command.restore(target, bundle));
        assertGeneration(target, OLD_PROCESS, "old-request");
        assertNoRecoveryArtifacts(target);
    }

    @Test
    void recoveryRefusesSymlinkedRollbackDatabaseBeforeDeletingTheLiveStore() throws Exception {
        Path bundle = bundleWithNewGeneration();
        var target = configuration(root.resolve("unsafe-rollback-target"));
        writeGeneration(target, OLD_PROCESS, "old-request");
        var errors = new ByteArrayOutputStream();
        var leaveJournal = new BackupRestoreCommand(nullOutput(), new PrintStream(errors), checkpoint -> {
            if (checkpoint == RecoveryRestoreTransaction.Checkpoint.AUDIT_INSTALLED
                    || checkpoint == RecoveryRestoreTransaction.Checkpoint.ROLLBACK_STARTED) {
                throw new java.io.IOException("leave recovery artifacts");
            }
        });
        assertEquals(1, leaveJournal.restore(target, bundle));

        Path rollbackDirectory;
        try (var entries = Files.list(target.executionStoreLocation().directory())) {
            rollbackDirectory = entries.filter(path -> path.getFileName().toString()
                    .startsWith(".ravenroot-restore-store-rollback-")).findFirst().orElseThrow();
        }
        Path rollbackStore = rollbackDirectory.resolve(RecoveryBundle.STORE_FILE);
        Path sentinel = root.resolve("rollback-symlink-sentinel");
        Files.writeString(sentinel, "must remain untouched");
        Files.delete(rollbackStore);
        Files.createSymbolicLink(rollbackStore, sentinel);

        errors.reset();
        assertEquals(2, new BackupRestoreCommand(nullOutput(), new PrintStream(errors))
                .restore(target, bundle));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("UNSAFE_ARTIFACT"));
        assertEquals("must remain untouched", Files.readString(sentinel));
        assertStoreGeneration(target, OLD_PROCESS);
        assertTrue(Files.isRegularFile(target.executionStoreLocation().directory().resolve(
                RecoveryRestoreTransaction.JOURNAL_FILE)));
    }

    private Path bundleWithNewGeneration() throws Exception {
        var source = configuration(root.resolve("source"));
        writeGeneration(source, NEW_PROCESS, "new-request");
        Path bundle = root.resolve("bundle");
        var errors = new ByteArrayOutputStream();
        assertEquals(0, new BackupRestoreCommand(nullOutput(), new PrintStream(errors)).backup(source, bundle),
                errors::toString);
        return bundle;
    }

    private static BackupRestoreConfiguration configuration(Path root) {
        return new BackupRestoreConfiguration(root.resolve("audit"),
                SqliteStoreLocation.underDirectory(root.resolve("store")));
    }

    private static void writeGeneration(BackupRestoreConfiguration configuration, UUID processId,
                                        String requestId) throws Exception {
        try (var audit = new FileAuditTrail(configuration.auditDirectory(), Clock.systemUTC(), Duration.ZERO)) {
            audit.append(new AuditEnvelope(AuditEnvelope.CURRENT_VERSION, UUID.randomUUID(), TENANT,
                    "operator", AuditCategory.ACCESS, "EXECUTION_START", "execution", processId.toString(),
                    AuditOutcome.ALLOWED, "allowed", requestId, Instant.now(),
                    OpaquePayload.empty("application/json")));
        }
        var key = new ExecutionKey(TENANT, processId);
        UUID traversalId = UUID.randomUUID();
        var instance = new ProcessInstance(processId, ProcessInstanceStatus.ACCEPTED,
                Map.of(traversalId, new Traversal(
                        traversalId, "start", TraversalStatus.ACCEPTED, Map.of())));
        var batch = ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(instance, new GraphVersionPin("graph-v1")))
                .build();
        try (var store = new SqliteExecutionStore(configuration.executionStoreLocation(), Clock.systemUTC())) {
            store.apply(batch).toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    private static void assertGeneration(BackupRestoreConfiguration configuration, UUID processId,
                                         String requestId) throws Exception {
        try (var audit = new FileAuditTrail(configuration.auditDirectory(), Clock.systemUTC(), Duration.ZERO)) {
            var records = audit.read(TENANT, 0, 10);
            assertEquals(1, records.size());
            assertEquals(requestId, records.getFirst().envelope().correlationId());
            assertTrue(audit.verify(TENANT).intact());
        }
        assertStoreGeneration(configuration, processId);
    }

    private static void assertStoreGeneration(BackupRestoreConfiguration configuration, UUID processId)
            throws Exception {
        try (var store = new SqliteExecutionStore(configuration.executionStoreLocation(), Clock.systemUTC())) {
            var loaded = store.load(new ExecutionKey(TENANT, processId))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(processId, loaded.state().processInstanceId());
        }
    }

    private static void assertNoRecoveryArtifacts(BackupRestoreConfiguration configuration) throws Exception {
        assertFalse(Files.exists(configuration.executionStoreLocation().directory().resolve(
                RecoveryRestoreTransaction.JOURNAL_FILE)));
        try (var storeFiles = Files.list(configuration.executionStoreLocation().directory())) {
            assertTrue(storeFiles.noneMatch(path -> path.getFileName().toString().startsWith(
                    ".ravenroot-restore-")));
        }
        try (var auditFiles = Files.list(configuration.auditDirectory().getParent())) {
            assertTrue(auditFiles.noneMatch(path -> path.getFileName().toString().startsWith(
                    ".ravenroot-restore-")));
        }
    }

    private static PrintStream nullOutput() {
        return new PrintStream(java.io.OutputStream.nullOutputStream());
    }
}
