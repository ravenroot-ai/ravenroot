package ai.ravenroot.persistence.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteStoreMaintenanceLockTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void leaseIsExclusiveReacquirableAndLeavesOnePersistentArtifact() {
        var location = SqliteStoreLocation.underDirectory(temporaryDirectory.resolve("store"));
        Path artifact = location.directory().resolve(SqliteStoreMaintenanceLock.FILE_NAME);

        try (var first = SqliteStoreMaintenanceLock.acquire(location)) {
            var failure = assertThrows(SqliteStoreMaintenanceLock.MaintenanceLockException.class,
                    () -> SqliteStoreMaintenanceLock.acquire(location));
            assertEquals(SqliteStoreMaintenanceLock.Failure.BUSY, failure.failure());
            assertFalse(failure.getMessage().contains(temporaryDirectory.toString()));
        }

        assertTrue(Files.isRegularFile(artifact));
        try (var reacquired = SqliteStoreMaintenanceLock.acquire(location)) {
            assertTrue(Files.isRegularFile(artifact));
        }
        assertTrue(Files.isRegularFile(artifact), "release must not replace/delete the lock inode");
    }

    @Test
    void symbolicLinkLockArtifactIsRejectedWithoutTouchingItsTarget() throws Exception {
        var location = SqliteStoreLocation.underDirectory(temporaryDirectory.resolve("store"));
        Files.createDirectories(location.directory());
        Path target = temporaryDirectory.resolve("target");
        Files.writeString(target, "unchanged");
        Files.createSymbolicLink(location.directory().resolve(SqliteStoreMaintenanceLock.FILE_NAME), target);

        var failure = assertThrows(SqliteStoreMaintenanceLock.MaintenanceLockException.class,
                () -> SqliteStoreMaintenanceLock.acquire(location));

        assertEquals(SqliteStoreMaintenanceLock.Failure.UNSAFE_LOCATION, failure.failure());
        assertEquals("unchanged", Files.readString(target));
    }

    @Test
    void directoryAliasesResolveToOneDeterministicLockAuthority() throws Exception {
        Path realDirectory = temporaryDirectory.resolve("real-store");
        Files.createDirectories(realDirectory);
        Path aliasDirectory = temporaryDirectory.resolve("store-alias");
        Files.createSymbolicLink(aliasDirectory, realDirectory);
        var realLocation = SqliteStoreLocation.underDirectory(realDirectory);
        var aliasLocation = SqliteStoreLocation.underDirectory(aliasDirectory);

        try (var realOwner = SqliteStoreMaintenanceLock.acquire(realLocation)) {
            var refused = assertThrows(SqliteStoreMaintenanceLock.MaintenanceLockException.class,
                    () -> SqliteStoreMaintenanceLock.acquire(aliasLocation));
            assertEquals(SqliteStoreMaintenanceLock.Failure.BUSY, refused.failure());
        }

        assertTrue(Files.isSameFile(realDirectory.resolve(SqliteStoreMaintenanceLock.FILE_NAME),
                aliasDirectory.resolve(SqliteStoreMaintenanceLock.FILE_NAME)));
    }
}
