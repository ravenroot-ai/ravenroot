package ai.ravenroot.cli;

import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Offline, journaled, recoverable installation across independent audit and SQLite filesystems. */
final class RecoveryRestoreTransaction {
    static final String JOURNAL_FILE = ai.ravenroot.persistence.sqlite.SqliteStoreMaintenanceLock
            .RECOVERY_JOURNAL_FILE_NAME;
    private static final long MAX_JOURNAL_BYTES = 4096;

    private final BackupRestoreConfiguration configuration;
    private final Faults faults;

    RecoveryRestoreTransaction(BackupRestoreConfiguration configuration, Faults faults) {
        this.configuration = configuration;
        this.faults = faults;
    }

    void install(Path bundle) throws Exception {
        recoverInterrupted();
        Path privateParent = configuration.executionStoreLocation().directory();
        try (var snapshot = RecoveryBundle.snapshot(bundle, privateParent,
                () -> faults.after(Checkpoint.SOURCE_MANIFEST_COPIED))) {
            RecoveryBundle.verifySnapshot(snapshot);
            faults.after(Checkpoint.SOURCE_SNAPSHOT_VERIFIED);
            Transaction transaction = prepareTransaction(snapshot);
            installSnapshot(snapshot, transaction);
        }
    }

    private void installSnapshot(RecoveryBundle.Snapshot snapshot, Transaction transaction) throws Exception {
        try {
            writeJournal(transaction, Phase.PREPARING);
            faults.after(Checkpoint.PREPARATION_JOURNALED);
            stage(snapshot, transaction);
            faults.after(Checkpoint.STORE_ARTIFACTS_READY);
            writeJournal(transaction, Phase.PREPARED);
            faults.after(Checkpoint.JOURNAL_WRITTEN);
            moveOriginalAudit(transaction);
            writeJournal(transaction, Phase.AUDIT_BACKED_UP);
            faults.after(Checkpoint.AUDIT_BACKED_UP);
            atomicMove(transaction.stagedAudit, transaction.auditTarget, false);
            writeJournal(transaction, Phase.AUDIT_INSTALLED);
            faults.after(Checkpoint.AUDIT_INSTALLED);
            moveOriginalStore(transaction);
            writeJournal(transaction, Phase.STORE_BACKED_UP);
            faults.after(Checkpoint.STORE_BACKED_UP);
            atomicMove(transaction.stagedStore, transaction.storeTarget, false);
            RecoveryBundle.assertOwnerFile(transaction.storeTarget);
            RecoveryBundle.deleteTree(transaction.stagedStoreDirectory);
            writeJournal(transaction, Phase.STORE_INSTALLED);
            faults.after(Checkpoint.STORE_INSTALLED);
            writeJournal(transaction, Phase.COMMITTED);
            cleanupCommitted(transaction);
        } catch (Exception failed) {
            try {
                Transaction recovery = Files.exists(journalPath(), LinkOption.NOFOLLOW_LINKS)
                        ? parseJournal(journalPath()) : transaction;
                if (recovery.phase == Phase.COMMITTED) {
                    cleanupCommitted(recovery);
                } else {
                    rollback(recovery, true);
                }
            } catch (Exception rollbackFailed) {
                failed.addSuppressed(rollbackFailed);
            }
            throw failed;
        }
    }

    void recoverInterrupted() throws Exception {
        Path journal = journalPath();
        if (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        RecoveryBundle.requireRegularFile(journal);
        Transaction transaction = parseJournal(journal);
        if (transaction.phase == Phase.COMMITTED) {
            cleanupCommitted(transaction);
        } else {
            rollback(transaction, false);
        }
    }

    private Transaction prepareTransaction(RecoveryBundle.Snapshot snapshot) throws IOException {
        Path auditTarget = configuration.auditDirectory().toAbsolutePath().normalize();
        Path auditParent = requireParent(auditTarget);
        Path storeTarget = configuration.executionStoreLocation().databaseFile();
        Path storeParent = requireParent(storeTarget);
        Files.createDirectories(auditParent);
        Files.createDirectories(storeParent);
        rejectUnsafeTarget(auditTarget, true);
        rejectUnsafeTarget(storeTarget, false);
        long oldStoreBytes = Files.exists(storeTarget, LinkOption.NOFOLLOW_LINKS)
                ? Files.size(storeTarget) : 0;
        RecoveryBundle.requireUsableSpace(auditParent, RecoveryBundle.snapshotAuditBytes(snapshot));
        RecoveryBundle.requireUsableSpace(storeParent,
                Math.addExact(RecoveryBundle.snapshotStoreBytes(snapshot), oldStoreBytes));

        String id = UUID.randomUUID().toString();
        Path stagedAudit = auditParent.resolve(".ravenroot-restore-audit-stage-" + id);
        Path rollbackAudit = auditParent.resolve(".ravenroot-restore-audit-rollback-" + id);
        Path stagedStoreDirectory = storeParent.resolve(".ravenroot-restore-store-stage-" + id);
        Path rollbackStoreDirectory = storeParent.resolve(".ravenroot-restore-store-rollback-" + id);
        Path stagedStore = stagedStoreDirectory.resolve(RecoveryBundle.STORE_FILE);
        Path rollbackStore = rollbackStoreDirectory.resolve(RecoveryBundle.STORE_FILE);
        boolean auditExisted = Files.exists(auditTarget, LinkOption.NOFOLLOW_LINKS);
        boolean storeExisted = Files.exists(storeTarget, LinkOption.NOFOLLOW_LINKS);
        return new Transaction(id, auditTarget, storeTarget, stagedAudit, rollbackAudit,
                stagedStoreDirectory, rollbackStoreDirectory, stagedStore, rollbackStore,
                auditExisted, storeExisted, Phase.PREPARING);
    }

    private void stage(RecoveryBundle.Snapshot snapshot, Transaction transaction) throws Exception {
        RecoveryBundle.createPrivateDirectory(transaction.stagedAudit);
        Set<String> auditPaths = RecoveryBundle.auditPayloadPaths(snapshot);
        for (String relative : auditPaths.stream().sorted().toList()) {
            RecoveryBundle.copyVerified(snapshot, relative,
                    transaction.stagedAudit.resolve(Path.of(relative).getFileName()));
        }
        RecoveryBundle.forceDirectory(transaction.stagedAudit);
        RecoveryBundle.validateAudit(transaction.stagedAudit, auditPaths);
        RecoveryBundle.assertOwnerDirectory(transaction.stagedAudit);
        for (String relative : auditPaths) {
            RecoveryBundle.assertOwnerFile(transaction.stagedAudit.resolve(
                    Path.of(relative).getFileName()));
        }

        RecoveryBundle.createPrivateDirectory(transaction.stagedStoreDirectory);
        RecoveryBundle.createPrivateDirectory(transaction.rollbackStoreDirectory);
        RecoveryBundle.copyVerified(snapshot, RecoveryBundle.STORE_FILE, transaction.stagedStore);
        RecoveryBundle.forceFile(transaction.stagedStore);
        RecoveryBundle.validateSqlite(transaction.stagedStore);
        if (transaction.storeExisted) {
            // A plain move of the main database would not preserve committed frames that still
            // reside in its WAL. SQLite's own backup API produces a self-contained rollback
            // generation before any active file or sidecar is touched.
            try (var current = new SqliteExecutionStore(
                    configuration.executionStoreLocation(), Clock.systemUTC())) {
                current.backupTo(transaction.rollbackStore).toCompletableFuture()
                        .get(60, TimeUnit.SECONDS);
            }
            RecoveryBundle.makeOwnerFile(transaction.rollbackStore);
            RecoveryBundle.forceFile(transaction.rollbackStore);
        }
        RecoveryBundle.assertOwnerDirectory(transaction.stagedStoreDirectory);
        RecoveryBundle.assertOwnerDirectory(transaction.rollbackStoreDirectory);
        RecoveryBundle.assertOwnerFile(transaction.stagedStore);
        if (transaction.storeExisted) {
            RecoveryBundle.assertOwnerFile(transaction.rollbackStore);
        }
    }

    private void moveOriginalAudit(Transaction transaction) throws IOException {
        if (transaction.auditExisted) {
            RecoveryBundle.requireDirectory(transaction.auditTarget);
            atomicMove(transaction.auditTarget, transaction.rollbackAudit, false);
        }
    }

    private void moveOriginalStore(Transaction transaction) throws IOException {
        if (transaction.storeExisted) {
            RecoveryBundle.requireRegularFile(transaction.storeTarget);
        } else if (Files.exists(transaction.storeTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new TransactionException(Reason.UNSAFE_TARGET);
        }
        Files.deleteIfExists(configuration.executionStoreLocation().walFile());
        Files.deleteIfExists(configuration.executionStoreLocation().shmFile());
        Files.deleteIfExists(transaction.storeTarget);
    }

    private void rollback(Transaction transaction, boolean injected) throws Exception {
        if (injected) {
            faults.after(Checkpoint.ROLLBACK_STARTED);
        }
        if (Files.exists(transaction.rollbackStore, LinkOption.NOFOLLOW_LINKS)) {
            RecoveryBundle.requireDirectory(transaction.rollbackStoreDirectory);
            RecoveryBundle.requireRegularFile(transaction.rollbackStore);
            rejectUnsafeTarget(transaction.storeTarget, false);
            Files.deleteIfExists(transaction.storeTarget);
            Files.deleteIfExists(configuration.executionStoreLocation().walFile());
            Files.deleteIfExists(configuration.executionStoreLocation().shmFile());
            atomicMove(transaction.rollbackStore, transaction.storeTarget, false);
        } else if (!transaction.storeExisted
                && (transaction.phase.atLeast(Phase.STORE_INSTALLED)
                || !Files.exists(transaction.stagedStore, LinkOption.NOFOLLOW_LINKS))) {
            Files.deleteIfExists(transaction.storeTarget);
        }
        if (Files.exists(transaction.rollbackAudit, LinkOption.NOFOLLOW_LINKS)) {
            RecoveryBundle.requireDirectory(transaction.rollbackAudit);
            rejectUnsafeTarget(transaction.auditTarget, true);
            RecoveryBundle.deleteTree(transaction.auditTarget);
            atomicMove(transaction.rollbackAudit, transaction.auditTarget, false);
        } else if (!transaction.auditExisted
                && (transaction.phase.atLeast(Phase.AUDIT_INSTALLED)
                || !Files.exists(transaction.stagedAudit, LinkOption.NOFOLLOW_LINKS))) {
            RecoveryBundle.deleteTree(transaction.auditTarget);
        }
        RecoveryBundle.deleteTree(transaction.stagedAudit);
        RecoveryBundle.deleteTree(transaction.stagedStoreDirectory);
        RecoveryBundle.deleteTree(transaction.rollbackStoreDirectory);
        Files.deleteIfExists(journalPath());
    }

    private void cleanupCommitted(Transaction transaction) throws IOException {
        RecoveryBundle.deleteTree(transaction.rollbackAudit);
        RecoveryBundle.deleteTree(transaction.rollbackStoreDirectory);
        RecoveryBundle.deleteTree(transaction.stagedAudit);
        RecoveryBundle.deleteTree(transaction.stagedStoreDirectory);
        Files.deleteIfExists(journalPath());
    }

    private void writeJournal(Transaction transaction, Phase phase) throws IOException {
        Path journal = journalPath();
        rejectUnsafeJournal(journal);
        Path temporary = journal.resolveSibling(JOURNAL_FILE + ".tmp-" + UUID.randomUUID());
        String content = "version=1\n"
                + "id=" + transaction.id + "\n"
                + "phase=" + phase + "\n"
                + "audit-target-hash=" + pathHash(transaction.auditTarget) + "\n"
                + "store-target-hash=" + pathHash(transaction.storeTarget) + "\n"
                + "audit-existed=" + transaction.auditExisted + "\n"
                + "store-existed=" + transaction.storeExisted + "\n";
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            RecoveryBundle.createOwnerFile(temporary);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            RecoveryBundle.makeOwnerFile(temporary);
            RecoveryBundle.forceFile(temporary);
            atomicMove(temporary, journal, true);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void rejectUnsafeJournal(Path journal) throws IOException {
        if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) {
            throw new TransactionException(Reason.JOURNAL_INVALID);
        }
    }

    private static void atomicMove(Path source, Path target, boolean replace) throws IOException {
        if (replace) {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }
        forceDirectory(target.getParent());
    }

    private static void forceDirectory(Path directory) throws IOException {
        // Directory fsync makes journal and rename checkpoints durable on filesystems that expose
        // this primitive. A provider that cannot open directories fails the restore closed.
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private Transaction parseJournal(Path journal) throws IOException {
        if (Files.size(journal) > MAX_JOURNAL_BYTES) {
            throw new TransactionException(Reason.JOURNAL_INVALID);
        }
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(journal, StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator <= 0 || values.put(line.substring(0, separator), line.substring(separator + 1)) != null) {
                throw new TransactionException(Reason.JOURNAL_INVALID);
            }
        }
        try {
            if (!"1".equals(values.remove("version"))) {
                throw new TransactionException(Reason.JOURNAL_INVALID);
            }
            String id = values.remove("id");
            UUID.fromString(id);
            Phase phase = Phase.valueOf(values.remove("phase"));
            Path auditTarget = configuration.auditDirectory().toAbsolutePath().normalize();
            Path storeTarget = configuration.executionStoreLocation().databaseFile();
            if (!pathHash(auditTarget).equals(values.remove("audit-target-hash"))
                    || !pathHash(storeTarget).equals(values.remove("store-target-hash"))) {
                throw new TransactionException(Reason.CONFIGURATION_MISMATCH);
            }
            boolean auditExisted = strictBoolean(values.remove("audit-existed"));
            boolean storeExisted = strictBoolean(values.remove("store-existed"));
            if (!values.isEmpty()) {
                throw new TransactionException(Reason.JOURNAL_INVALID);
            }
            Path auditParent = requireParent(auditTarget);
            Path storeParent = requireParent(storeTarget);
            Path stagedStoreDirectory = storeParent.resolve(".ravenroot-restore-store-stage-" + id);
            Path rollbackStoreDirectory = storeParent.resolve(".ravenroot-restore-store-rollback-" + id);
            return new Transaction(id, auditTarget, storeTarget,
                    auditParent.resolve(".ravenroot-restore-audit-stage-" + id),
                    auditParent.resolve(".ravenroot-restore-audit-rollback-" + id),
                    stagedStoreDirectory, rollbackStoreDirectory,
                    stagedStoreDirectory.resolve(RecoveryBundle.STORE_FILE),
                    rollbackStoreDirectory.resolve(RecoveryBundle.STORE_FILE),
                    auditExisted, storeExisted, phase);
        } catch (TransactionException failed) {
            throw failed;
        } catch (RuntimeException failed) {
            throw new TransactionException(Reason.JOURNAL_INVALID);
        }
    }

    private Path journalPath() {
        return configuration.executionStoreLocation().directory().resolve(JOURNAL_FILE);
    }

    private static void rejectUnsafeTarget(Path path, boolean directory) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        boolean safe = directory ? Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        if (!safe) {
            throw new TransactionException(Reason.UNSAFE_TARGET);
        }
    }

    private static Path requireParent(Path path) {
        if (path.getParent() == null) {
            throw new TransactionException(Reason.UNSAFE_TARGET);
        }
        return path.getParent();
    }

    private static String pathHash(Path path) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(normalized.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static boolean strictBoolean(String value) {
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new TransactionException(Reason.JOURNAL_INVALID);
    }

    enum Checkpoint {
        SOURCE_MANIFEST_COPIED,
        SOURCE_SNAPSHOT_VERIFIED,
        PREPARATION_JOURNALED,
        STORE_ARTIFACTS_READY,
        JOURNAL_WRITTEN,
        AUDIT_BACKED_UP,
        AUDIT_INSTALLED,
        STORE_BACKED_UP,
        STORE_INSTALLED,
        ROLLBACK_STARTED
    }

    @FunctionalInterface
    interface Faults {
        Faults NONE = checkpoint -> { };

        void after(Checkpoint checkpoint) throws IOException;
    }

    enum Reason {
        JOURNAL_INVALID,
        CONFIGURATION_MISMATCH,
        UNSAFE_TARGET
    }

    static final class TransactionException extends IllegalStateException {
        private final Reason reason;

        TransactionException(Reason reason) {
            super("Recovery restore refused: " + reason);
            this.reason = reason;
        }

        Reason reason() {
            return reason;
        }
    }

    private enum Phase {
        PREPARING,
        PREPARED,
        AUDIT_BACKED_UP,
        AUDIT_INSTALLED,
        STORE_BACKED_UP,
        STORE_INSTALLED,
        COMMITTED;

        boolean atLeast(Phase other) {
            return ordinal() >= other.ordinal();
        }
    }

    private record Transaction(String id, Path auditTarget, Path storeTarget, Path stagedAudit,
                               Path rollbackAudit, Path stagedStoreDirectory, Path rollbackStoreDirectory,
                               Path stagedStore, Path rollbackStore,
                               boolean auditExisted, boolean storeExisted, Phase phase) {
    }
}
