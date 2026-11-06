package ai.ravenroot.server.personal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ProtectedConnectionStoreTest {
    @TempDir Path directory;
    @Test void encryptionAtomicRollbackRestartAndPrivatePermissions() throws Exception {
        Path key = directory.resolve("key");
        var store = new ProtectedConnectionStore(directory.resolve("store"), key);
        store.transaction(s -> { s.put("private", "test-provider-credential"); return null; });
        assertFalse(new String(Files.readAllBytes(directory.resolve("store/connections.enc"))).contains("test-provider-credential"));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(key));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve("store/connections.enc")));
        assertThrows(PersonalConnectionException.class, () -> store.transaction(s -> { s.put("private", "overwritten"); throw new PersonalConnectionException("cancelled"); }));
        var restarted = new ProtectedConnectionStore(directory.resolve("store"), key);
        assertEquals("test-provider-credential", restarted.transaction(s -> s.get("private")));
    }
    @Test void concurrentInstancesSerializeRotatingState() throws Exception {
        Path key = directory.resolve("key"); Path storage = directory.resolve("store");
        var first = new ProtectedConnectionStore(storage, key); var second = new ProtectedConnectionStore(storage, key);
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 40; i++) { var selected = i % 2 == 0 ? first : second; futures.add(pool.submit(() -> selected.transaction(s -> { long n = ((Number) s.getOrDefault("rotation", 0)).longValue(); s.put("rotation", n + 1); return null; }))); }
            for (var result : futures) result.get();
        }
        assertEquals(40L, ((Number) first.transaction(s -> s.get("rotation"))).longValue());
    }
    @Test void symlinkStoreKeyAndLockAreRejected() throws Exception {
        Path target = directory.resolve("target"); Files.createDirectory(target);
        Path alias = directory.resolve("alias"); Files.createSymbolicLink(alias, target);
        assertThrows(IllegalStateException.class, () -> new ProtectedConnectionStore(alias, directory.resolve("key")));
        Path key = directory.resolve("key"); Files.write(key, new byte[32]);
        Path keyAlias = directory.resolve("key-alias"); Files.createSymbolicLink(keyAlias, key);
        assertThrows(IllegalStateException.class, () -> new ProtectedConnectionStore(target, keyAlias));
        var store = new ProtectedConnectionStore(target, key);
        Files.createSymbolicLink(target.resolve("connections.lock"), key);
        assertThrows(PersonalConnectionException.class, () -> store.transaction(s -> null));
    }
    @Test void passphraseEncryptionRejectsWrongPasswordAndTampering() {
        byte[] salt = ProtectedConnectionStore.random(16);
        char[] raw = "a long testing passphrase".toCharArray();
        byte[] key = ProtectedConnectionStore.passwordKey(raw, salt);
        assertEquals('\0', raw[0]);
        byte[] encrypted = ProtectedConnectionStore.encrypt(key, new byte[]{1, 2, 3});
        assertArrayEquals(new byte[]{1, 2, 3}, ProtectedConnectionStore.decrypt(key, encrypted));
        assertThrows(PersonalConnectionException.class, () -> ProtectedConnectionStore.decrypt(new byte[32], encrypted));
        encrypted[15] ^= 1;
        assertThrows(PersonalConnectionException.class, () -> ProtectedConnectionStore.decrypt(key, encrypted));
    }
}
