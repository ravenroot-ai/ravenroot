package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Where the local execution store lives on disk, and the administration that only a local adapter
 * can perform on it.
 *
 * <h2>Why this is not on the port, and cannot be</h2>
 * <p>{@code ExecutionStore} is engine- and location-neutral: a remote adapter has no database file, no
 * directory to create and nothing to restore a file over. A path, a sidecar set and a restore are
 * therefore adapter-local administration, and putting any of it on the port would publish an operation
 * that a conforming adapter could only implement by refusing it. ADR 0010 section 12.4 draws the same
 * line for the failure members that only a durable adapter can reach.</p>
 *
 * <h2>The three files, which are one database</h2>
 * <p>In WAL mode a SQLite database is {@code name.db} plus {@code name.db-wal} plus {@code name.db-shm}.
 * The two sidecars are not caches: the {@code -wal} holds committed transactions that have not been
 * checkpointed back into the main file yet, so a copy of {@code name.db} alone is a copy of the
 * database as of the last checkpoint and silently loses every commit after it. This is the single most
 * common way a "backup" of a SQLite database turns out, months later, not to have been one.</p>
 *
 * <p>{@link #files()} names all three so an operator can see what the unit actually is. It is
 * nevertheless <strong>not</strong> the way to take a backup — copying three files while a writer is
 * running captures them at three different instants. Use {@link SqliteExecutionStore#backupTo(Path)},
 * which asks SQLite for one transactionally consistent snapshot and emits a single self-contained
 * file with no sidecars at all.</p>
 *
 * @param databaseFile the main database file, absolute
 */
public record SqliteStoreLocation(Path databaseFile) {

    /** The file name {@link #underDirectory(Path)} uses, so two deployments agree without configuring it. */
    public static final String DEFAULT_FILE_NAME = "ravenroot-execution-store.db";

    public SqliteStoreLocation {
        Objects.requireNonNull(databaseFile, "databaseFile");
        databaseFile = databaseFile.toAbsolutePath().normalize();
        if (databaseFile.getParent() == null) {
            throw new IllegalArgumentException(
                    "the execution store file must sit inside a directory: " + databaseFile);
        }
    }

    /** The store at an explicit file path. */
    public static SqliteStoreLocation ofFile(Path databaseFile) {
        return new SqliteStoreLocation(databaseFile);
    }

    /** The store at {@link #DEFAULT_FILE_NAME} inside {@code directory}. */
    public static SqliteStoreLocation underDirectory(Path directory) {
        Objects.requireNonNull(directory, "directory");
        return new SqliteStoreLocation(directory.resolve(DEFAULT_FILE_NAME));
    }

    /** The directory holding the database and its sidecars. */
    public Path directory() {
        return databaseFile.getParent();
    }

    /** The write-ahead log. Committed-but-uncheckpointed transactions live here, not in the main file. */
    public Path walFile() {
        return sidecar("-wal");
    }

    /** The shared-memory index. Rebuildable, but never valid against a different main file. */
    public Path shmFile() {
        return sidecar("-shm");
    }

    private Path sidecar(String suffix) {
        return databaseFile.resolveSibling(databaseFile.getFileName() + suffix);
    }

    /** Main file first, then the two sidecars, whether or not they currently exist. */
    public List<Path> files() {
        return List.of(databaseFile, walFile(), shmFile());
    }

    /**
     * Creates the containing directory if it is absent and reports an unusable location <em>before</em>
     * a connection is attempted.
     *
     * <p>The classification is the point. Opening an unreadable directory through JDBC raises
     * {@code SQLITE_CANTOPEN}, which the adapter cannot honestly read as a permission problem — the
     * same code covers a missing path and an exhausted file-descriptor table — so without this check a
     * permission failure reaches the caller as {@link ExecutionStoreFailure.Unavailable}. That member
     * is {@code RETRYABLE_NO_EFFECT}, and a caller that believes a permission failure is retryable
     * retries it forever. Here the filesystem is asked directly, so the answer is
     * {@link ExecutionStoreFailure.NotAuthorized}, which is {@code DETERMINISTIC_REJECT} and stops the
     * loop before it starts.</p>
     *
     * @throws ExecutionStoreException {@code NotAuthorized} if the location cannot be read or written
     */
    public void prepare() {
        Path directory = directory();
        if (!Files.exists(directory)) {
            try {
                Files.createDirectories(directory);
            } catch (IOException failed) {
                throw notAuthorized("cannot create the execution store directory " + directory
                        + ": " + failed);
            }
        }
        if (!Files.isDirectory(directory)) {
            throw new ExecutionStoreException(ExecutionStoreFailure.invalid(
                    "the execution store path " + databaseFile + " is not inside a directory"));
        }
        // An existing database is opened, so it must be both readable and writable. A database that
        // does not exist yet is created, which needs the directory to be writable and searchable.
        if (Files.exists(databaseFile)) {
            if (!Files.isReadable(databaseFile)) {
                throw notAuthorized("the execution store file " + databaseFile + " is not readable");
            }
            if (!Files.isWritable(databaseFile)) {
                throw notAuthorized("the execution store file " + databaseFile + " is not writable");
            }
        }
        if (!Files.isReadable(directory) || !Files.isExecutable(directory)) {
            throw notAuthorized("the execution store directory " + directory + " is not searchable");
        }
        // Writable even when the database already exists: SQLite creates and deletes the -wal and -shm
        // sidecars in this directory, so a read-only directory blocks writes to a writable database.
        if (!Files.isWritable(directory)) {
            throw notAuthorized("the execution store directory " + directory + " is not writable; "
                    + "SQLite must create its -wal and -shm sidecars beside the database");
        }
    }

    /**
     * Replaces this location's database with a backup, and is deliberately an <strong>offline</strong>
     * operation.
     *
     * <p>Restoring under a live store is not a durability trade-off that an operator could accept
     * knowingly, it is unimplementable: the open connection holds its own view of the file it is
     * reading, and swapping the bytes beneath it produces neither the old database nor the new one.
     * The caller must therefore close the store first, restore, and reopen.</p>
     *
     * <p>A SQLite lock probe cannot enforce that obligation: an idle WAL connection may hold no
     * SQLite file lock.  Server and administrative composition roots therefore coordinate through
     * {@link SqliteStoreMaintenanceLock}, acquired before either store is touched.  This adapter-local
     * method remains intentionally unaware of process lifecycle; callers that bypass those roots are
     * responsible for holding the same lease.</p>
     *
     * <p><strong>Deleting the sidecars is the load-bearing step, not cleanup.</strong> A {@code -wal}
     * left beside a restored database is a log of transactions belonging to a <em>different</em>
     * database, and SQLite will replay it into the restored file on the next open. The result passes
     * an integrity check and is silently wrong. Copying the backup over the main file without removing
     * the sidecars is the standard way a restore appears to succeed and does not.</p>
     *
     * @param backup a file produced by {@link SqliteExecutionStore#backupTo(Path)}
     * @throws ExecutionStoreException {@code InvalidRequest} if the backup is absent or not a
     *                                 Ravenroot execution store, {@code NotAuthorized} if the
     *                                 location cannot be written
     */
    public void restoreFrom(Path backup) {
        Objects.requireNonNull(backup, "backup");
        Path source = backup.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new ExecutionStoreException(ExecutionStoreFailure.invalid(
                    "there is no backup file at " + source));
        }
        if (source.equals(databaseFile)) {
            throw new ExecutionStoreException(ExecutionStoreFailure.invalid(
                    "a store cannot be restored from itself: " + source));
        }
        // Refusing an unrecognisable file here is what keeps a mistyped path from destroying a live
        // store: the check runs before anything is deleted.
        SqliteSchema.requireRestorableStore(source);
        prepare();
        try {
            Files.deleteIfExists(walFile());
            Files.deleteIfExists(shmFile());
            Files.copy(source, databaseFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failed) {
            throw notAuthorized("cannot restore " + databaseFile + " from " + source + ": " + failed);
        }
    }

    private static ExecutionStoreException notAuthorized(String detail) {
        return new ExecutionStoreException(new ExecutionStoreFailure.NotAuthorized(detail));
    }
}
