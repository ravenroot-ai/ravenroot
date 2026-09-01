package ai.ravenroot.persistence.sqlite;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-wide offline-maintenance lease for one local execution-store directory.
 *
 * <p>The server holds this lease for the complete lifetime of its execution and audit stores.
 * Administrative commands acquire the same lease before touching either store.  The operating
 * system lock, rather than a PID file or a SQLite lock probe, is the authority: an open-but-idle
 * SQLite connection need not hold any SQLite file lock and therefore cannot prove that the server
 * is offline.</p>
 *
 * <p>The lock artifact is deliberately persistent.  Deleting it on release would permit two
 * processes to lock different inodes under the same path.  Backup and restore code must likewise
 * leave it in place.</p>
 */
public final class SqliteStoreMaintenanceLock implements AutoCloseable {
    public static final String FILE_NAME = ".ravenroot-maintenance.lock";
    public static final String RECOVERY_JOURNAL_FILE_NAME = ".ravenroot-restore.journal";

    /** Stable, path-free failure reasons suitable for operator-facing command output. */
    public enum Failure {
        BUSY,
        UNSAFE_LOCATION,
        RECOVERY_PENDING,
        UNAVAILABLE
    }

    /** A deliberately redacted acquisition/release failure. */
    public static final class MaintenanceLockException extends IllegalStateException {
        private final Failure failure;

        private MaintenanceLockException(Failure failure) {
            super("Execution store maintenance lock unavailable: " + failure);
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }

    private final FileChannel channel;
    private final FileLock lock;
    private final AtomicBoolean closed = new AtomicBoolean();

    private SqliteStoreMaintenanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Acquires exclusive offline ownership for the store directory without following a lock-file
     * symbolic link.  Acquisition never blocks: a live owner produces {@link Failure#BUSY}.
     */
    public static SqliteStoreMaintenanceLock acquire(SqliteStoreLocation location) {
        Objects.requireNonNull(location, "location");
        try {
            location.prepare();
            Path directory = location.directory().toRealPath();
            Path lockPath = directory.resolve(FILE_NAME);
            rejectUnsafeArtifact(lockPath);

            FileChannel channel = null;
            try {
                channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
                rejectUnsafeArtifact(lockPath);
                FileLock lock;
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException busy) {
                    throw failure(Failure.BUSY);
                }
                if (lock == null) {
                    throw failure(Failure.BUSY);
                }
                return new SqliteStoreMaintenanceLock(channel, lock);
            } catch (RuntimeException | IOException failed) {
                closeQuietly(channel);
                if (failed instanceof MaintenanceLockException maintenanceFailure) {
                    throw maintenanceFailure;
                }
                throw failure(Failure.UNAVAILABLE);
            }
        } catch (MaintenanceLockException failed) {
            throw failed;
        } catch (RuntimeException | IOException failed) {
            throw failure(Failure.UNAVAILABLE);
        }
    }

    private static void rejectUnsafeArtifact(Path lockPath) throws IOException {
        if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        BasicFileAttributes attributes = Files.readAttributes(
                lockPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw failure(Failure.UNSAFE_LOCATION);
        }
    }

    private static MaintenanceLockException failure(Failure failure) {
        return new MaintenanceLockException(failure);
    }

    /** Server startup guard: only the offline recovery command may reconcile this marker. */
    public static void requireNoPendingRecovery(SqliteStoreLocation location) {
        Objects.requireNonNull(location, "location");
        try {
            Path directory = location.directory().toRealPath();
            Path journal = directory.resolve(RECOVERY_JOURNAL_FILE_NAME);
            if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(
                        journal, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                    throw failure(Failure.UNSAFE_LOCATION);
                }
                throw failure(Failure.RECOVERY_PENDING);
            }
        } catch (MaintenanceLockException failed) {
            throw failed;
        } catch (IOException | RuntimeException failed) {
            throw failure(Failure.UNAVAILABLE);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // The acquisition failure is the actionable and stable result.
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            lock.release();
            channel.close();
        } catch (IOException failed) {
            closeQuietly(channel);
            throw failure(Failure.UNAVAILABLE);
        }
    }
}
