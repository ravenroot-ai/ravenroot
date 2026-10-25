package ai.ravenroot.server.personal;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Function;

/** Encrypted atomic state, serialized under a cross-process lock. The key belongs in operator storage. */
public final class ProtectedConnectionStore {
    static final PayloadLimits LIMITS = new PayloadLimits(8 * 1024 * 1024, 32, 100_000, 100_000, 1024 * 1024, 4096);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path directory;
    private final byte[] key;
    private static final Map<Path, Object> LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    public ProtectedConnectionStore(Path directory, Path keyFile) {
        try {
            this.directory = directory.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(this.directory)) throw new IllegalArgumentException();
            Files.createDirectories(this.directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            privatePermissions(this.directory, true);
            if (!Files.exists(keyFile)) {
                Files.createDirectories(keyFile.toAbsolutePath().getParent());
                try {
                    Files.createFile(keyFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    try (var output = FileChannel.open(keyFile, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                        ByteBuffer bytes = ByteBuffer.wrap(random(32));
                        while (bytes.hasRemaining()) output.write(bytes);
                        output.force(true);
                    }
                } catch (FileAlreadyExistsException raced) { /* another instance provisioned the key */ }
            }
            if (Files.isSymbolicLink(keyFile) || Files.size(keyFile) != 32) throw new IllegalArgumentException();
            privatePermissions(keyFile, false);
            key = Files.readAllBytes(keyFile);
        } catch (Exception failure) {
            throw new IllegalStateException("Personal connection storage is unavailable");
        }
    }

    /** Writes only when the callback succeeds; no partial token rotation can become visible. */
    @SuppressWarnings("unchecked")
    public <T> T transaction(Function<Map<String, Object>, T> action) {
        synchronized (LOCKS.computeIfAbsent(directory, ignored -> new Object())) {
            Path lock = directory.resolve("connections.lock");
            try {
                try { Files.createFile(lock, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
                catch (FileAlreadyExistsException existing) { /* lock is shared */ }
                if (Files.isSymbolicLink(lock) || Files.isSymbolicLink(directory.resolve("connections.enc"))) throw new IllegalArgumentException();
            } catch (Exception failure) { throw new PersonalConnectionException("storage-unavailable"); }
            try (var channel = FileChannel.open(lock, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var held = channel.lock()) {
                privatePermissions(lock, false);
                Path file = directory.resolve("connections.enc");
                Map<String, Object> state = Files.exists(file)
                        ? (Map<String, Object>) PayloadJson.read(decrypt(key, Files.readAllBytes(file)), LIMITS).toJava()
                        : new LinkedHashMap<>();
                // Payload maps are immutable; make a mutable tree within this lock.
                state = mutable(state);
                T result = action.apply(state);
                byte[] plaintext = json(state);
                byte[] encrypted;
                try { encrypted = encrypt(key, plaintext); }
                finally { Arrays.fill(plaintext, (byte) 0); }
                Path temporary = Files.createTempFile(directory, "connection-", ".pending",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                try {
                    privatePermissions(temporary, false);
                    try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                        ByteBuffer bytes = ByteBuffer.wrap(encrypted);
                        while (bytes.hasRemaining()) output.write(bytes);
                        output.force(true);
                    }
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    try (var parent = FileChannel.open(directory, StandardOpenOption.READ)) { parent.force(true); }
                } finally { Files.deleteIfExists(temporary); }
                return result;
            } catch (PersonalConnectionException refused) { throw refused; }
            catch (Exception failure) { throw new PersonalConnectionException("storage-unavailable"); }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutable(Map<String, Object> source) {
        var result = new LinkedHashMap<String, Object>();
        source.forEach((name, value) -> result.put(name, value instanceof Map<?, ?> map
                ? mutable((Map<String, Object>) map) : value instanceof List<?> list
                ? new ArrayList<>(list) : value));
        return result;
    }
    static byte[] json(Object value) { return PayloadJson.write(PayloadValue.fromJava(value, LIMITS)).getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    static byte[] random(int length) { byte[] bytes = new byte[length]; RANDOM.nextBytes(bytes); return bytes; }
    static String randomId() { return Base64.getUrlEncoder().withoutPadding().encodeToString(random(32)); }
    static byte[] passwordKey(char[] passphrase, byte[] salt) {
        if (passphrase.length < 12 || passphrase.length > 1024) {
            Arrays.fill(passphrase, '\0'); throw new PersonalConnectionException("passphrase-too-short");
        }
        var spec = new PBEKeySpec(passphrase, salt, 600_000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (Exception failure) { throw new PersonalConnectionException("unlock-unavailable"); }
        finally { spec.clearPassword(); Arrays.fill(passphrase, '\0'); }
    }
    static byte[] encrypt(byte[] key, byte[] plaintext) {
        try {
            byte[] nonce = random(12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD("ravenroot-personal-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            byte[] encrypted = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(12 + encrypted.length).put(nonce).put(encrypted).array();
        } catch (Exception failure) { throw new PersonalConnectionException("storage-unavailable"); }
    }
    static byte[] decrypt(byte[] key, byte[] encrypted) {
        try {
            if (encrypted.length < 28) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Arrays.copyOf(encrypted, 12)));
            cipher.updateAAD("ravenroot-personal-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return cipher.doFinal(Arrays.copyOfRange(encrypted, 12, encrypted.length));
        } catch (Exception failure) { throw new PersonalConnectionException("unlock-required"); }
    }
    private static void privatePermissions(Path path, boolean directory) throws java.io.IOException {
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")); }
        catch (UnsupportedOperationException unsupported) { throw new java.io.IOException("Owner-only storage permissions are required"); }
    }
}
