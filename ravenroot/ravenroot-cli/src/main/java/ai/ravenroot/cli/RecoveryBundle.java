package ai.ravenroot.cli;

import ai.ravenroot.core.audit.FileAuditTrail;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.persistence.sqlite.SqliteStoreLocation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Versioned, bounded, complete, digest-verified recovery bundle. */
final class RecoveryBundle {
    static final String MANIFEST_FILE = "MANIFEST.txt";
    static final String STORE_FILE = "execution-store.db";
    static final String AUDIT_DIRECTORY = "audit";
    static final int VERSION = 2;

    // These are format-reader safety limits, not deployment sizing recommendations. Raising one is
    // a compatibility decision because an older reader will continue to reject the larger bundle.
    static final long MAX_MANIFEST_BYTES = 1024 * 1024;
    static final int MAX_MANIFEST_LINE_CHARS = 1024;
    static final int MAX_FILES = 1024;
    static final int MAX_LAYOUT_DEPTH = 2;
    static final long MAX_AUDIT_FILE_BYTES = 64L * 1024 * 1024;
    static final long MAX_AUDIT_TOTAL_BYTES = 256L * 1024 * 1024;
    static final long MAX_AUDIT_RECORDS = 1_000_000;
    static final long MAX_SQLITE_BYTES = 1024L * 1024 * 1024;
    static final long MAX_BUNDLE_BYTES = MAX_SQLITE_BYTES + MAX_AUDIT_TOTAL_BYTES + MAX_MANIFEST_BYTES;

    private static final Set<PosixFilePermission> OWNER_DIRECTORY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> OWNER_FILE = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_DIRECTORY_ATTRIBUTE =
            PosixFilePermissions.asFileAttribute(OWNER_DIRECTORY);
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_FILE_ATTRIBUTE =
            PosixFilePermissions.asFileAttribute(OWNER_FILE);

    private RecoveryBundle() {
    }

    static Path create(BackupRestoreConfiguration configuration, Path destination) throws Exception {
        return create(configuration, destination, AuditCopyObserver.NONE);
    }

    static Path create(BackupRestoreConfiguration configuration, Path destination,
                       AuditCopyObserver observer) throws Exception {
        Objects.requireNonNull(observer, "observer");
        Path absolute = destination.toAbsolutePath().normalize();
        Path parent = requireParent(absolute);
        Files.createDirectories(parent);
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new BundleException(Reason.DESTINATION_EXISTS);
        }
        Path staging = parent.resolve(".ravenroot-bundle-stage-" + UUID.randomUUID());
        try {
            createPrivateDirectory(staging);
            Path audit = staging.resolve(AUDIT_DIRECTORY);
            createPrivateDirectory(audit);
            copyAudit(configuration.auditDirectory(), audit, observer);
            Path store = staging.resolve(STORE_FILE);
            requireUsableSpace(staging, currentStoreSize(configuration.executionStoreLocation()));
            try (var executionStore = new SqliteExecutionStore(
                    configuration.executionStoreLocation(), Clock.systemUTC())) {
                executionStore.backupTo(store).toCompletableFuture().get(60, TimeUnit.SECONDS);
            }
            enforceBound(store, MAX_SQLITE_BYTES);
            makeOwnerFile(store);
            forceFile(store);
            writeManifest(staging);
            verifyPrivate(staging, null);
            forceDirectory(staging);
            Files.move(staging, absolute, StandardCopyOption.ATOMIC_MOVE);
            forceDirectory(parent);
            return absolute;
        } catch (Exception failed) {
            deleteTree(staging);
            throw failed;
        }
    }

    /**
     * Standalone verification snapshots the untrusted source first. This gives the command the same
     * check-the-bytes-you-use property as restore, even if an operator path is concurrently replaced.
     */
    static Verification verify(Path source) throws Exception {
        Path parent = Files.createTempDirectory("ravenroot-bundle-verify-input-");
        makeOwnerDirectory(parent);
        try (Snapshot snapshot = snapshot(source, parent, SnapshotObserver.NONE)) {
            return verifySnapshot(snapshot);
        } finally {
            deleteTree(parent);
        }
    }

    /** Copies an untrusted bundle into a private generation and hashes each byte while copying. */
    static Snapshot snapshot(Path source, Path privateParent, SnapshotObserver observer) throws Exception {
        Objects.requireNonNull(observer, "observer");
        Path untrusted = source.toAbsolutePath().normalize();
        requireDirectory(untrusted);
        Path untrustedManifest = untrusted.resolve(MANIFEST_FILE);
        requireRegularFile(untrustedManifest);
        enforceBound(untrustedManifest, MAX_MANIFEST_BYTES);

        Path privateRoot = privateParent.resolve(".ravenroot-restore-input-" + UUID.randomUUID());
        createPrivateDirectory(privateRoot);
        try {
            Path privateAudit = privateRoot.resolve(AUDIT_DIRECTORY);
            createPrivateDirectory(privateAudit);
            Map<String, FileEvidence> copied = new LinkedHashMap<>();
            var aggregate = new MutableBytes();

            Path privateManifest = privateRoot.resolve(MANIFEST_FILE);
            copyBounded(untrustedManifest, privateManifest,
                    MAX_MANIFEST_BYTES, aggregate);
            observer.afterManifestCopied();
            detectLegacy(privateManifest);

            Inventory before = inventory(untrusted);
            requireUsableSpace(privateRoot, before.totalBytes);

            for (String relative : before.files.keySet().stream().sorted().toList()) {
                Path target = safeResolve(privateRoot, relative);
                copied.put(relative, copyBounded(safeResolve(untrusted, relative), target,
                        perFileLimit(relative), aggregate));
            }
            Inventory after = inventory(untrusted);
            if (!before.files.equals(after.files)) {
                throw new BundleException(Reason.SOURCE_CHANGED);
            }
            forceDirectory(privateAudit);
            forceDirectory(privateRoot);
            return new Snapshot(privateRoot, Map.copyOf(copied), aggregate.value);
        } catch (Exception failed) {
            deleteTree(privateRoot);
            throw failed;
        }
    }

    static Verification verifySnapshot(Snapshot snapshot) throws Exception {
        return verifyPrivate(snapshot.root, snapshot.copiedEvidence);
    }

    /** Copies one already-snapshotted payload and proves the bytes still match its copy evidence. */
    static void copyVerified(Snapshot snapshot, String relative, Path target) throws IOException {
        FileEvidence expected = snapshot.copiedEvidence.get(relative);
        if (expected == null) {
            throw new BundleException(Reason.INVENTORY_MISMATCH);
        }
        FileEvidence actual = copyBounded(safeResolve(snapshot.root, relative), target,
                perFileLimit(relative), new MutableBytes());
        if (!actual.equals(expected)) {
            throw new BundleException(Reason.SOURCE_CHANGED);
        }
    }

    static Set<String> auditPayloadPaths(Snapshot snapshot) {
        return snapshot.copiedEvidence.keySet().stream()
                .filter(path -> path.startsWith(AUDIT_DIRECTORY + "/"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    static long snapshotStoreBytes(Snapshot snapshot) {
        FileEvidence evidence = snapshot.copiedEvidence.get(STORE_FILE);
        if (evidence == null) {
            throw new BundleException(Reason.INVENTORY_MISMATCH);
        }
        return evidence.size;
    }

    static long snapshotAuditBytes(Snapshot snapshot) {
        return snapshot.copiedEvidence.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(AUDIT_DIRECTORY + "/"))
                .mapToLong(entry -> entry.getValue().size).sum();
    }

    private static Verification verifyPrivate(Path bundle, Map<String, FileEvidence> copiedEvidence)
            throws Exception {
        Inventory actual = inventory(bundle);
        Manifest manifest = readManifest(bundle.resolve(MANIFEST_FILE));
        if (!actual.files.keySet().equals(manifest.files.keySet())) {
            throw new BundleException(Reason.INVENTORY_MISMATCH);
        }
        for (var entry : manifest.files.entrySet()) {
            FileEvidence evidence = copiedEvidence == null
                    ? evidenceOf(safeResolve(bundle, entry.getKey()), perFileLimit(entry.getKey()))
                    : copiedEvidence.get(entry.getKey());
            if (evidence == null || !evidence.equals(entry.getValue())
                    || actual.files.get(entry.getKey()) != evidence.size) {
                throw new BundleException(Reason.DIGEST_MISMATCH);
            }
        }
        validateAudit(bundle.resolve(AUDIT_DIRECTORY), manifest.files.keySet());
        validateSqlite(bundle.resolve(STORE_FILE));
        return new Verification(manifest.createdAt, manifest.files.size());
    }

    private static void writeManifest(Path bundle) throws IOException {
        Inventory inventory = inventoryWithoutManifest(bundle);
        Path manifest = bundle.resolve(MANIFEST_FILE);
        createOwnerFile(manifest);
        try (var writer = Files.newBufferedWriter(manifest, StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            writer.write("ravenroot-recovery-bundle-version: " + VERSION + "\n");
            writer.write("created-at: " + Instant.now() + "\n");
            writer.write("digest-algorithm: SHA-256\n");
            writer.write("authenticity: not-provided\n");
            writer.write("encryption: none\n");
            writer.write("file-count: " + inventory.files.size() + "\n");
            int index = 0;
            for (String path : inventory.files.keySet().stream().sorted().toList()) {
                String key = String.format("file.%04d", index++);
                FileEvidence evidence = evidenceOf(safeResolve(bundle, path), perFileLimit(path));
                writer.write(key + ".path: " + path + "\n");
                writer.write(key + ".size: " + evidence.size + "\n");
                writer.write(key + ".sha256: " + evidence.sha256 + "\n");
            }
        }
        enforceBound(manifest, MAX_MANIFEST_BYTES);
        makeOwnerFile(manifest);
        forceFile(manifest);
    }

    private static Manifest readManifest(Path path) throws IOException {
        requireRegularFile(path);
        enforceBound(path, MAX_MANIFEST_BYTES);
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (Reader reader = new BufferedReader(new InputStreamReader(
                Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS), decoder))) {
            String first = boundedLine(reader);
            if ("ravenroot-backup-manifest-version: 1".equals(first)) {
                throw new BundleException(Reason.LEGACY_VERSION);
            }
            int version = canonicalInt(value(first, "ravenroot-recovery-bundle-version"));
            if (version != VERSION) {
                throw new BundleException(Reason.UNSUPPORTED_VERSION);
            }
            String createdText = value(boundedLine(reader), "created-at");
            Instant createdAt;
            try {
                createdAt = Instant.parse(createdText);
                if (!createdAt.toString().equals(createdText)) {
                    throw new IllegalArgumentException();
                }
            } catch (RuntimeException failed) {
                throw new BundleException(Reason.MALFORMED_MANIFEST);
            }
            exact(reader, "digest-algorithm: SHA-256");
            exact(reader, "authenticity: not-provided");
            exact(reader, "encryption: none");
            int count = canonicalInt(value(boundedLine(reader), "file-count"));
            if (count < 1 || count > MAX_FILES) {
                throw new BundleException(Reason.RESOURCE_LIMIT);
            }
            Map<String, FileEvidence> files = new LinkedHashMap<>();
            String previous = null;
            long total = 0;
            long auditTotal = 0;
            for (int index = 0; index < count; index++) {
                String key = String.format("file.%04d", index);
                String relative = value(boundedLine(reader), key + ".path");
                requireCanonicalPayloadPath(relative);
                if (previous != null && previous.compareTo(relative) >= 0) {
                    throw new BundleException(Reason.MALFORMED_MANIFEST);
                }
                previous = relative;
                long size = canonicalLong(value(boundedLine(reader), key + ".size"));
                String digest = value(boundedLine(reader), key + ".sha256");
                if (!digest.matches("[0-9a-f]{64}")) {
                    throw new BundleException(Reason.MALFORMED_MANIFEST);
                }
                if (size > perFileLimit(relative)) {
                    throw new BundleException(Reason.RESOURCE_LIMIT);
                }
                total = boundedAdd(total, size, MAX_BUNDLE_BYTES);
                if (relative.startsWith(AUDIT_DIRECTORY + "/")) {
                    auditTotal = boundedAdd(auditTotal, size, MAX_AUDIT_TOTAL_BYTES);
                }
                files.put(relative, new FileEvidence(size, digest));
            }
            if (boundedLine(reader) != null || !files.containsKey(STORE_FILE)) {
                throw new BundleException(Reason.MALFORMED_MANIFEST);
            }
            return new Manifest(version, createdAt, Map.copyOf(files));
        } catch (BundleException failed) {
            throw failed;
        } catch (IOException failed) {
            throw new BundleException(Reason.MALFORMED_MANIFEST);
        }
    }

    private static Inventory inventory(Path root) throws IOException {
        return inventory(root, true);
    }

    private static Inventory inventoryWithoutManifest(Path root) throws IOException {
        return inventory(root, false);
    }

    /** Fixed-depth traversal: root payloads plus direct audit files, with early cardinality abort. */
    private static Inventory inventory(Path root, boolean requireManifest) throws IOException {
        requireDirectory(root);
        Map<String, Long> files = new HashMap<>();
        boolean manifest = false;
        boolean auditDirectory = false;
        long total = 0;
        long auditTotal = 0;
        try (DirectoryStream<Path> roots = Files.newDirectoryStream(root)) {
            for (Path entry : roots) {
                String name = entry.getFileName().toString();
                if (MANIFEST_FILE.equals(name)) {
                    requireRegularFile(entry);
                    enforceBound(entry, MAX_MANIFEST_BYTES);
                    manifest = true;
                } else if (AUDIT_DIRECTORY.equals(name)) {
                    requireDirectory(entry);
                    auditDirectory = true;
                    try (DirectoryStream<Path> auditFiles = Files.newDirectoryStream(entry)) {
                        for (Path audit : auditFiles) {
                            if (Files.isDirectory(audit, LinkOption.NOFOLLOW_LINKS)) {
                                throw new BundleException(Reason.DEPTH_LIMIT);
                            }
                            requireRegularFile(audit);
                            String relative = AUDIT_DIRECTORY + "/" + audit.getFileName();
                            long size = boundedFileSize(audit, MAX_AUDIT_FILE_BYTES);
                            addInventory(files, relative, size);
                            total = boundedAdd(total, size, MAX_BUNDLE_BYTES);
                            auditTotal = boundedAdd(auditTotal, size, MAX_AUDIT_TOTAL_BYTES);
                        }
                    }
                } else if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new BundleException(Reason.DEPTH_LIMIT);
                } else {
                    requireRegularFile(entry);
                    long size = boundedFileSize(entry,
                            STORE_FILE.equals(name) ? MAX_SQLITE_BYTES : MAX_BUNDLE_BYTES);
                    addInventory(files, name, size);
                    total = boundedAdd(total, size, MAX_BUNDLE_BYTES);
                }
            }
        }
        if ((requireManifest && !manifest) || !auditDirectory) {
            throw new BundleException(Reason.INVENTORY_MISMATCH);
        }
        return new Inventory(Map.copyOf(files), total, auditTotal);
    }

    private static void addInventory(Map<String, Long> files, String relative, long size) {
        if (files.size() >= MAX_FILES) {
            throw new BundleException(Reason.RESOURCE_LIMIT);
        }
        if (files.put(relative, size) != null) {
            throw new BundleException(Reason.UNSAFE_ARTIFACT);
        }
    }

    static void validateAudit(Path audit, Set<String> payloadPaths) {
        requireDirectory(audit);
        Map<String, Set<String>> tenantParts = new HashMap<>();
        long records = 0;
        for (String path : payloadPaths) {
            if (!path.startsWith(AUDIT_DIRECTORY + "/")) {
                continue;
            }
            String name = path.substring((AUDIT_DIRECTORY + "/").length());
            String suffix;
            if (name.endsWith(".audit.jsonl")) {
                suffix = ".audit.jsonl";
                records = boundedAdd(records, countRecords(audit.resolve(name)),
                        MAX_AUDIT_RECORDS);
            } else if (name.endsWith(".audit.head")) {
                suffix = ".audit.head";
            } else {
                throw new BundleException(Reason.UNSAFE_ARTIFACT);
            }
            String encodedTenant = name.substring(0, name.length() - suffix.length());
            tenantParts.computeIfAbsent(encodedTenant, ignored -> new HashSet<>()).add(suffix);
        }
        try (var trail = new FileAuditTrail(audit, Clock.systemUTC(), Duration.ZERO)) {
            for (var entry : tenantParts.entrySet()) {
                if (!entry.getValue().equals(Set.of(".audit.jsonl", ".audit.head"))) {
                    throw new BundleException(Reason.AUDIT_PAIR_MISSING);
                }
                if (!trail.verify(decodeTenant(entry.getKey())).intact()) {
                    throw new BundleException(Reason.AUDIT_CHAIN_INVALID);
                }
            }
        } catch (BundleException failed) {
            throw failed;
        } catch (RuntimeException failed) {
            throw new BundleException(Reason.AUDIT_CHAIN_INVALID);
        }
    }

    static void validateSqlite(Path store) throws Exception {
        requireRegularFile(store);
        enforceBound(store, MAX_SQLITE_BYTES);
        Path temporary = Files.createTempDirectory("ravenroot-bundle-verify-sqlite-");
        makeOwnerDirectory(temporary);
        try {
            var location = SqliteStoreLocation.underDirectory(temporary);
            location.restoreFrom(store);
            try (var ignored = new SqliteExecutionStore(location, Clock.systemUTC())) {
                // Construction executes schema compatibility and SQLite integrity validation.
            }
        } catch (RuntimeException failed) {
            throw new BundleException(Reason.SQLITE_INVALID);
        } finally {
            deleteTree(temporary);
        }
    }

    static void copyAudit(Path source, Path destination) throws IOException {
        copyAudit(source, destination, AuditCopyObserver.NONE);
    }

    static void copyAudit(Path source, Path destination, AuditCopyObserver observer) throws IOException {
        Objects.requireNonNull(observer, "observer");
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            forceDirectory(destination);
            return;
        }
        requireDirectory(source);
        long total = 0;
        int count = 0;
        var logs = new java.util.ArrayList<Path>();
        var heads = new java.util.ArrayList<Path>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(source)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!name.endsWith(".audit.jsonl") && !name.endsWith(".audit.head")) {
                    continue;
                }
                if (++count > MAX_FILES) {
                    throw new BundleException(Reason.RESOURCE_LIMIT);
                }
                requireRegularFile(entry);
                (name.endsWith(".audit.jsonl") ? logs : heads).add(entry);
            }
        }
        // FileAuditTrail publishes each record to its log before advancing its head watermark. A
        // snapshot must therefore capture every watermark before its corresponding advancing log:
        // a concurrent append may leave the copied head behind the copied log (a valid crash-safe
        // prefix), but never ahead of it (which would falsely resemble a deleted tail).
        for (Path entry : heads.stream().sorted().toList()) {
            FileEvidence evidence = copyBoundedLiveSource(entry,
                    destination.resolve(entry.getFileName()), MAX_AUDIT_FILE_BYTES, new MutableBytes(total));
            total = boundedAdd(total, evidence.size, MAX_AUDIT_TOTAL_BYTES);
        }
        for (Path entry : logs.stream().sorted().toList()) {
            FileEvidence evidence = copyBoundedLiveSource(entry,
                    destination.resolve(entry.getFileName()), MAX_AUDIT_FILE_BYTES, new MutableBytes(total));
            total = boundedAdd(total, evidence.size, MAX_AUDIT_TOTAL_BYTES);
            observer.afterLogCopied(entry.getFileName().toString());
        }
        forceDirectory(destination);
    }

    static void requireUsableSpace(Path location, long required) throws IOException {
        Files.createDirectories(location);
        long usable = Files.getFileStore(location).getUsableSpace();
        if (required < 0 || required > MAX_BUNDLE_BYTES * 3 || usable < required) {
            throw new BundleException(Reason.INSUFFICIENT_SPACE);
        }
    }

    static void requireDirectory(Path path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new BundleException(Reason.UNSAFE_LOCATION);
        }
    }

    static void requireRegularFile(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new BundleException(Reason.UNSAFE_ARTIFACT);
        }
    }

    static Path safeResolve(Path root, String relative) {
        try {
            Path relativePath = Path.of(relative);
            Path candidate = root.resolve(relativePath).normalize();
            if (relative.isBlank() || relative.length() > 512 || relativePath.isAbsolute()
                    || !candidate.startsWith(root) || relative.contains("\\")) {
                throw new BundleException(Reason.MALFORMED_MANIFEST);
            }
            return candidate;
        } catch (RuntimeException failed) {
            if (failed instanceof BundleException bundleFailure) {
                throw bundleFailure;
            }
            throw new BundleException(Reason.MALFORMED_MANIFEST);
        }
    }

    static String sha256(Path path) throws IOException {
        return evidenceOf(path, MAX_BUNDLE_BYTES).sha256;
    }

    static void createPrivateDirectory(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new BundleException(Reason.UNSAFE_LOCATION);
        }
        if (supportsPosix(path.getParent())) {
            Files.createDirectory(path, OWNER_DIRECTORY_ATTRIBUTE);
        } else {
            Files.createDirectory(path);
        }
        makeOwnerDirectory(path);
    }

    static void createOwnerDirectory(Path path) throws IOException {
        Files.createDirectories(path);
        makeOwnerDirectory(path);
    }

    static void makeOwnerDirectory(Path path) throws IOException {
        enforcePermissions(path, OWNER_DIRECTORY);
    }

    static void createOwnerFile(Path path) throws IOException {
        if (supportsPosix(path.getParent())) {
            Files.createFile(path, OWNER_FILE_ATTRIBUTE);
        } else {
            Files.createFile(path);
        }
        makeOwnerFile(path);
    }

    static void makeOwnerFile(Path path) throws IOException {
        enforcePermissions(path, OWNER_FILE);
    }

    static void assertOwnerDirectory(Path path) throws IOException {
        assertPermissions(path, OWNER_DIRECTORY);
    }

    static void assertOwnerFile(Path path) throws IOException {
        assertPermissions(path, OWNER_FILE);
    }

    private static void enforcePermissions(Path path, Set<PosixFilePermission> expected) throws IOException {
        if (supportsPosix(path)) {
            Files.setPosixFilePermissions(path, expected);
            assertPermissions(path, expected);
        }
        // On non-POSIX providers Java exposes no portable owner-only ACL construction. The native
        // ACL policy remains authoritative and the runbook deliberately makes no stronger claim.
    }

    private static void assertPermissions(Path path, Set<PosixFilePermission> expected) throws IOException {
        if (supportsPosix(path) && !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(expected)) {
            throw new BundleException(Reason.PERMISSION_POLICY);
        }
    }

    private static boolean supportsPosix(Path path) {
        return Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS) != null;
    }

    static void forceFile(Path path) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }

    static void forceDirectory(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    static void deleteTree(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root, MAX_LAYOUT_DEPTH + 2)) {
            List<Path> found = paths.toList();
            for (Path path : found) {
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        makeOwnerDirectory(path);
                    } catch (IOException | RuntimeException ignored) {
                        // Continue to best-effort deletion.
                    }
                }
            }
            found.stream().sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Cleanup is best-effort; journal/startup guards keep incomplete state closed.
                }
            });
        } catch (IOException ignored) {
            // As above.
        }
    }

    private static FileEvidence copyBounded(Path source, Path target, long perFileLimit,
                                            MutableBytes aggregate) throws IOException {
        return copyBounded(source, target, perFileLimit, aggregate, true);
    }

    /** Live backup input may advance during capture; the copied generation is validated afterward. */
    private static FileEvidence copyBoundedLiveSource(Path source, Path target, long perFileLimit,
                                                      MutableBytes aggregate) throws IOException {
        return copyBounded(source, target, perFileLimit, aggregate, false);
    }

    private static FileEvidence copyBounded(Path source, Path target, long perFileLimit,
                                            MutableBytes aggregate, boolean requireStableSource)
            throws IOException {
        requireRegularFile(source);
        BasicFileAttributes before = Files.readAttributes(
                source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.size() > perFileLimit) {
            throw new BundleException(Reason.RESOURCE_LIMIT);
        }
        createOwnerFile(target);
        MessageDigest digest = sha256Digest();
        long copied = 0;
        try (var input = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             var output = FileChannel.open(target, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 1024);
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                copied = boundedAdd(copied, read, perFileLimit);
                aggregate.value = boundedAdd(aggregate.value, read, MAX_BUNDLE_BYTES);
                buffer.flip();
                digest.update(buffer.asReadOnlyBuffer());
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                buffer.clear();
            }
            output.force(true);
        } catch (Exception failed) {
            Files.deleteIfExists(target);
            throw failed;
        }
        BasicFileAttributes after = Files.readAttributes(
                source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (requireStableSource && (copied != before.size() || after.size() != before.size()
                || !Objects.equals(before.fileKey(), after.fileKey())
                || !before.lastModifiedTime().equals(after.lastModifiedTime()))) {
            Files.deleteIfExists(target);
            throw new BundleException(Reason.SOURCE_CHANGED);
        }
        makeOwnerFile(target);
        return new FileEvidence(copied, HexFormat.of().formatHex(digest.digest()));
    }

    private static FileEvidence evidenceOf(Path path, long limit) throws IOException {
        requireRegularFile(path);
        long size = boundedFileSize(path, limit);
        MessageDigest digest = sha256Digest();
        long readTotal = 0;
        try (var input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                readTotal = boundedAdd(readTotal, read, limit);
                digest.update(buffer, 0, read);
            }
        }
        if (readTotal != size) {
            throw new BundleException(Reason.SOURCE_CHANGED);
        }
        return new FileEvidence(size, HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static long countRecords(Path log) {
        long lines = 0;
        boolean any = false;
        boolean endedWithNewline = false;
        try (var input = Files.newInputStream(log, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                for (int index = 0; index < read; index++) {
                    any = true;
                    endedWithNewline = buffer[index] == '\n';
                    if (endedWithNewline) {
                        lines = boundedAdd(lines, 1, MAX_AUDIT_RECORDS);
                    }
                }
            }
        } catch (IOException failed) {
            throw new BundleException(Reason.AUDIT_CHAIN_INVALID);
        }
        return any && !endedWithNewline ? boundedAdd(lines, 1, MAX_AUDIT_RECORDS) : lines;
    }

    private static String decodeTenant(String encoded) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            String tenant = new String(decoded, StandardCharsets.UTF_8);
            String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    tenant.getBytes(StandardCharsets.UTF_8));
            if (tenant.isBlank() || !canonical.equals(encoded)) {
                throw new IllegalArgumentException();
            }
            return tenant;
        } catch (RuntimeException failed) {
            throw new BundleException(Reason.UNSAFE_ARTIFACT);
        }
    }

    private static void detectLegacy(Path manifest) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (Reader reader = new BufferedReader(new InputStreamReader(
                Files.newInputStream(manifest, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS), decoder))) {
            if ("ravenroot-backup-manifest-version: 1".equals(boundedLine(reader))) {
                throw new BundleException(Reason.LEGACY_VERSION);
            }
        }
    }

    private static void requireCanonicalPayloadPath(String relative) {
        safeResolve(Path.of(".").toAbsolutePath().normalize(), relative);
        if (STORE_FILE.equals(relative)) {
            return;
        }
        if (!relative.startsWith(AUDIT_DIRECTORY + "/")) {
            throw new BundleException(Reason.MALFORMED_MANIFEST);
        }
        String name = relative.substring((AUDIT_DIRECTORY + "/").length());
        if (name.isBlank() || name.contains("/")
                || (!name.endsWith(".audit.jsonl") && !name.endsWith(".audit.head"))) {
            throw new BundleException(Reason.MALFORMED_MANIFEST);
        }
    }

    private static String boundedLine(Reader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int character = reader.read();
            if (character < 0) {
                return line.isEmpty() ? null : line.toString();
            }
            if (character == '\n') {
                return line.toString();
            }
            if (character == '\r' || line.length() >= MAX_MANIFEST_LINE_CHARS) {
                throw new BundleException(Reason.RESOURCE_LIMIT);
            }
            line.append((char) character);
        }
    }

    private static String value(String line, String key) {
        String prefix = key + ": ";
        if (line == null || !line.startsWith(prefix) || line.length() == prefix.length()) {
            throw new BundleException(Reason.MALFORMED_MANIFEST);
        }
        return line.substring(prefix.length());
    }

    private static void exact(Reader reader, String expected) throws IOException {
        if (!expected.equals(boundedLine(reader))) {
            throw new BundleException(Reason.MALFORMED_MANIFEST);
        }
    }

    private static int canonicalInt(String value) {
        long parsed = canonicalLong(value);
        if (parsed > Integer.MAX_VALUE) {
            throw new BundleException(Reason.MALFORMED_MANIFEST);
        }
        return (int) parsed;
    }

    private static long canonicalLong(String value) {
        if (!value.matches("0|[1-9][0-9]*")) {
            throw new BundleException(Reason.MALFORMED_MANIFEST);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failed) {
            throw new BundleException(Reason.MALFORMED_MANIFEST);
        }
    }

    private static long boundedFileSize(Path path, long limit) throws IOException {
        long size = Files.size(path);
        if (size < 0 || size > limit) {
            throw new BundleException(Reason.RESOURCE_LIMIT);
        }
        return size;
    }

    private static void enforceBound(Path path, long limit) throws IOException {
        boundedFileSize(path, limit);
    }

    private static long boundedAdd(long left, long right, long limit) {
        if (left < 0 || right < 0 || left > limit - right) {
            throw new BundleException(Reason.RESOURCE_LIMIT);
        }
        return left + right;
    }

    private static long perFileLimit(String relative) {
        if (STORE_FILE.equals(relative)) {
            return MAX_SQLITE_BYTES;
        }
        if (relative.startsWith(AUDIT_DIRECTORY + "/")) {
            return MAX_AUDIT_FILE_BYTES;
        }
        return MAX_BUNDLE_BYTES;
    }

    private static Path requireParent(Path path) {
        if (path.getParent() == null) {
            throw new BundleException(Reason.UNSAFE_LOCATION);
        }
        return path.getParent();
    }

    private static long currentStoreSize(SqliteStoreLocation location) throws IOException {
        return Files.exists(location.databaseFile(), LinkOption.NOFOLLOW_LINKS)
                ? boundedFileSize(location.databaseFile(), MAX_SQLITE_BYTES) : 16L * 1024 * 1024;
    }

    record Verification(Instant createdAt, int fileCount) {
    }

    static final class Snapshot implements AutoCloseable {
        private final Path root;
        private final Map<String, FileEvidence> copiedEvidence;
        private final long copiedBytes;

        private Snapshot(Path root, Map<String, FileEvidence> copiedEvidence, long copiedBytes) {
            this.root = root;
            this.copiedEvidence = copiedEvidence;
            this.copiedBytes = copiedBytes;
        }

        Path root() {
            return root;
        }

        long copiedBytes() {
            return copiedBytes;
        }

        @Override
        public void close() {
            deleteTree(root);
        }
    }

    @FunctionalInterface
    interface SnapshotObserver {
        SnapshotObserver NONE = () -> { };

        void afterManifestCopied() throws IOException;
    }

    @FunctionalInterface
    interface AuditCopyObserver {
        AuditCopyObserver NONE = ignored -> { };

        void afterLogCopied(String fileName) throws IOException;
    }

    private record FileEvidence(long size, String sha256) {
    }

    private record Manifest(int version, Instant createdAt, Map<String, FileEvidence> files) {
    }

    private record Inventory(Map<String, Long> files, long totalBytes, long auditBytes) {
    }

    private static final class MutableBytes {
        private long value;

        private MutableBytes() {
        }

        private MutableBytes(long value) {
            this.value = value;
        }
    }

    enum Reason {
        LEGACY_VERSION,
        UNSUPPORTED_VERSION,
        MALFORMED_MANIFEST,
        INVENTORY_MISMATCH,
        DIGEST_MISMATCH,
        AUDIT_PAIR_MISSING,
        AUDIT_CHAIN_INVALID,
        SQLITE_INVALID,
        UNSAFE_LOCATION,
        UNSAFE_ARTIFACT,
        DESTINATION_EXISTS,
        SOURCE_CHANGED,
        RESOURCE_LIMIT,
        DEPTH_LIMIT,
        INSUFFICIENT_SPACE,
        PERMISSION_POLICY
    }

    static final class BundleException extends IllegalStateException {
        private final Reason reason;

        BundleException(Reason reason) {
            super("Recovery bundle refused: " + reason);
            this.reason = reason;
        }

        Reason reason() {
            return reason;
        }
    }
}
