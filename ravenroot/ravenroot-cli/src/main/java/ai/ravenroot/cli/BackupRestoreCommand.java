package ai.ravenroot.cli;

import ai.ravenroot.persistence.sqlite.SqliteStoreMaintenanceLock;

import java.io.PrintStream;
import java.io.InterruptedIOException;
import java.nio.file.Path;

/** Offline CLI surface for versioned, verifiable recovery bundles. */
final class BackupRestoreCommand {
    private final PrintStream output;
    private final PrintStream errors;
    private final RecoveryRestoreTransaction.Faults restoreFaults;

    BackupRestoreCommand(PrintStream output, PrintStream errors) {
        this(output, errors, RecoveryRestoreTransaction.Faults.NONE);
    }

    BackupRestoreCommand(PrintStream output, PrintStream errors,
                         RecoveryRestoreTransaction.Faults restoreFaults) {
        this.output = output;
        this.errors = errors;
        this.restoreFaults = restoreFaults;
    }

    int backup(BackupRestoreConfiguration configuration, Path destination) {
        return backup(configuration, destination, RecoveryBundle.AuditCopyObserver.NONE);
    }

    int backup(BackupRestoreConfiguration configuration, Path destination,
               RecoveryBundle.AuditCopyObserver observer) {
        try (var maintenance = SqliteStoreMaintenanceLock.acquire(configuration.executionStoreLocation())) {
            new RecoveryRestoreTransaction(configuration, RecoveryRestoreTransaction.Faults.NONE)
                    .recoverInterrupted();
            RecoveryBundle.create(configuration, destination, observer);
            output.println("recovery bundle created: version=2 verification=passed encryption=none");
            return 0;
        } catch (SqliteStoreMaintenanceLock.MaintenanceLockException failed) {
            errors.println("Error: backup refused: " + failed.failure());
            return 2;
        } catch (RecoveryBundle.BundleException failed) {
            errors.println("Error: backup refused: " + failed.reason());
            return 2;
        } catch (RecoveryRestoreTransaction.TransactionException failed) {
            errors.println("Error: backup refused: " + failed.reason());
            return 2;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            errors.println("Error: backup failed: INTERRUPTED");
            return 1;
        } catch (InterruptedIOException interrupted) {
            Thread.currentThread().interrupt();
            errors.println("Error: backup failed: INTERRUPTED");
            return 1;
        } catch (Exception failed) {
            errors.println("Error: backup failed: IO_FAILURE");
            return 1;
        }
    }

    int verify(Path bundle) {
        try {
            RecoveryBundle.Verification verification = RecoveryBundle.verify(bundle);
            output.println("recovery bundle verified: version=2 files=" + verification.fileCount()
                    + " integrity=passed audit-chains=passed sqlite=passed authenticity=not-provided encryption=none");
            return 0;
        } catch (RecoveryBundle.BundleException failed) {
            errors.println("Error: verification refused: " + failed.reason());
            return 2;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            errors.println("Error: verification failed: INTERRUPTED");
            return 1;
        } catch (InterruptedIOException interrupted) {
            Thread.currentThread().interrupt();
            errors.println("Error: verification failed: INTERRUPTED");
            return 1;
        } catch (Exception failed) {
            errors.println("Error: verification failed: IO_FAILURE");
            return 1;
        }
    }

    int restore(BackupRestoreConfiguration configuration, Path source) {
        try (var maintenance = SqliteStoreMaintenanceLock.acquire(configuration.executionStoreLocation())) {
            new RecoveryRestoreTransaction(configuration, restoreFaults).install(source);
            output.println("recovery bundle restored: version=2 transaction=recoverable verification=passed");
            return 0;
        } catch (SqliteStoreMaintenanceLock.MaintenanceLockException failed) {
            errors.println("Error: restore refused: " + failed.failure());
            return 2;
        } catch (RecoveryBundle.BundleException failed) {
            errors.println("Error: restore refused: " + failed.reason());
            return 2;
        } catch (RecoveryRestoreTransaction.TransactionException failed) {
            errors.println("Error: restore refused: " + failed.reason());
            return 2;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            errors.println("Error: restore failed: INTERRUPTED");
            return 1;
        } catch (InterruptedIOException interrupted) {
            Thread.currentThread().interrupt();
            errors.println("Error: restore failed: INTERRUPTED");
            return 1;
        } catch (Exception failed) {
            errors.println("Error: restore failed: TRANSACTION_ROLLED_BACK_OR_RECOVERY_PENDING");
            return 1;
        }
    }
}
