package ai.ravenroot.cli;

import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditEnvelope;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.core.audit.FileAuditTrail;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.persistence.sqlite.SqliteStoreLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
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
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryBundleTest {
    private static final String TENANT = "tenant-a";

    @TempDir
    Path root;

    @Test
    void backupPublishesOwnerOnlyVersionTwoBundleWithCompleteCryptographicInventory() throws Exception {
        Path secretRoot = root.resolve("customer-secret-live-location");
        var configuration = populatedConfiguration(secretRoot);
        Path bundle = root.resolve("bundle");
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int result = new BackupRestoreCommand(new PrintStream(output), new PrintStream(errors))
                .backup(configuration, bundle);

        assertEquals(0, result, errors::toString);
        assertEquals(3, RecoveryBundle.verify(bundle).fileCount());
        String manifest = Files.readString(bundle.resolve(RecoveryBundle.MANIFEST_FILE));
        assertTrue(manifest.startsWith("ravenroot-recovery-bundle-version: 2\n"));
        assertTrue(manifest.contains("digest-algorithm: SHA-256\n"));
        assertTrue(manifest.contains("authenticity: not-provided\n"));
        assertTrue(manifest.contains("encryption: none\n"));
        assertTrue(manifest.contains("file-count: 3\n"));
        assertFalse(manifest.contains(secretRoot.toString()), "manifest must not disclose source paths");
        assertFalse(output.toString(StandardCharsets.UTF_8).contains(secretRoot.toString()));

        if (Files.getFileStore(bundle).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(bundle));
            try (var files = Files.walk(bundle)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    assertEquals(PosixFilePermissions.fromString("rw-------"),
                            Files.getPosixFilePermissions(file), file.toString());
                }
            }
        }
    }

    @Test
    void verifyRejectsCorruptionMissingExtraSymlinkAndPartialCopy() throws Exception {
        assertRefusedAfterMutation("corrupt", bundle -> Files.writeString(
                        bundle.resolve(RecoveryBundle.STORE_FILE), "tampered",
                        java.nio.file.StandardOpenOption.APPEND),
                RecoveryBundle.Reason.DIGEST_MISMATCH);
        assertRefusedAfterMutation("missing", bundle -> Files.delete(
                        bundle.resolve(RecoveryBundle.STORE_FILE)),
                RecoveryBundle.Reason.INVENTORY_MISMATCH);
        assertRefusedAfterMutation("extra", bundle -> Files.writeString(
                        bundle.resolve("unexpected"), "extra"),
                RecoveryBundle.Reason.INVENTORY_MISMATCH);
        assertRefusedAfterMutation("symlink", bundle -> {
            Path store = bundle.resolve(RecoveryBundle.STORE_FILE);
            Path moved = bundle.resolve("store-target");
            Files.move(store, moved);
            Files.createSymbolicLink(store, moved.getFileName());
        }, RecoveryBundle.Reason.UNSAFE_ARTIFACT);

        Path complete = createBundle(root.resolve("partial-source"), root.resolve("complete"));
        Path partial = root.resolve("partial");
        Files.createDirectories(partial.resolve(RecoveryBundle.AUDIT_DIRECTORY));
        Files.copy(complete.resolve(RecoveryBundle.MANIFEST_FILE),
                partial.resolve(RecoveryBundle.MANIFEST_FILE));
        assertReason(partial, RecoveryBundle.Reason.INVENTORY_MISMATCH);
    }

    @Test
    void semanticVerificationRejectsRehashedAuditAndSqliteCorruption() throws Exception {
        Path auditBundle = createBundle(root.resolve("audit-source"), root.resolve("audit-bundle"));
        Path auditLog;
        try (var entries = Files.list(auditBundle.resolve(RecoveryBundle.AUDIT_DIRECTORY))) {
            auditLog = entries.filter(path -> path.getFileName().toString().endsWith(".audit.jsonl"))
                    .findFirst().orElseThrow();
        }
        String original = Files.readString(auditLog);
        String encodedAction = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "EXECUTION_START".getBytes(StandardCharsets.UTF_8));
        String encodedTamper = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "EXECUTION_STXRT".getBytes(StandardCharsets.UTF_8));
        assertTrue(original.contains(encodedAction), "fixture must mutate a hash-covered audit field");
        Files.writeString(auditLog, original.replace(encodedAction, encodedTamper));
        refreshEvidence(auditBundle, RecoveryBundle.AUDIT_DIRECTORY + "/" + auditLog.getFileName());
        assertReason(auditBundle, RecoveryBundle.Reason.AUDIT_CHAIN_INVALID);

        Path sqliteBundle = createBundle(root.resolve("sqlite-source"), root.resolve("sqlite-bundle"));
        Files.writeString(sqliteBundle.resolve(RecoveryBundle.STORE_FILE), "not a sqlite database");
        refreshEvidence(sqliteBundle, RecoveryBundle.STORE_FILE);
        assertReason(sqliteBundle, RecoveryBundle.Reason.SQLITE_INVALID);

        Path futureBundle = createBundle(root.resolve("future-source"), root.resolve("future-bundle"));
        try (var connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + futureBundle.resolve(RecoveryBundle.STORE_FILE));
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 2147483647");
        }
        refreshEvidence(futureBundle, RecoveryBundle.STORE_FILE);
        assertReason(futureBundle, RecoveryBundle.Reason.SQLITE_INVALID);
    }

    @Test
    void legacyVersionIsIdentifiedButRestoreFailsClosedUntilItIsRebackedUp() throws Exception {
        Path legacy = root.resolve("legacy");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve(RecoveryBundle.MANIFEST_FILE),
                "ravenroot-backup-manifest-version: 1\n");
        assertReason(legacy, RecoveryBundle.Reason.LEGACY_VERSION);

        var errors = new ByteArrayOutputStream();
        var target = new BackupRestoreConfiguration(root.resolve("target/audit"),
                SqliteStoreLocation.underDirectory(root.resolve("target/store")));
        int result = new BackupRestoreCommand(new PrintStream(java.io.OutputStream.nullOutputStream()),
                new PrintStream(errors)).restore(target, legacy);
        assertEquals(2, result);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("LEGACY_VERSION"));
        assertFalse(Files.exists(target.auditDirectory()));
        assertFalse(Files.exists(target.executionStoreLocation().databaseFile()));
    }

    @Test
    void diagnosticsNeverEchoBundleOrConfiguredSecretPaths() throws Exception {
        Path secretBundle = root.resolve("secret-customer-bundle-name");
        Files.createDirectories(secretBundle);
        Path secretLive = root.resolve("secret-customer-live-name");
        var configuration = new BackupRestoreConfiguration(secretLive.resolve("audit"),
                SqliteStoreLocation.underDirectory(secretLive.resolve("store")));
        var errors = new ByteArrayOutputStream();

        int result = new BackupRestoreCommand(new PrintStream(java.io.OutputStream.nullOutputStream()),
                new PrintStream(errors)).restore(configuration, secretBundle);

        assertEquals(2, result);
        String diagnostic = errors.toString(StandardCharsets.UTF_8);
        assertFalse(diagnostic.contains(secretBundle.toString()));
        assertFalse(diagnostic.contains(secretLive.toString()));
    }

    @Test
    void verificationBoundsManifestLinesInventoryDepthAndPayloadSizesBeforeSemanticReaders()
            throws Exception {
        Path oversizedManifest = createBundle(root.resolve("manifest-source"),
                root.resolve("oversized-manifest"));
        makeSparse(oversizedManifest.resolve(RecoveryBundle.MANIFEST_FILE),
                RecoveryBundle.MAX_MANIFEST_BYTES + 1);
        assertReason(oversizedManifest, RecoveryBundle.Reason.RESOURCE_LIMIT);

        Path oversizedLine = createBundle(root.resolve("line-source"), root.resolve("oversized-line"));
        Files.writeString(oversizedLine.resolve(RecoveryBundle.MANIFEST_FILE),
                "x".repeat(RecoveryBundle.MAX_MANIFEST_LINE_CHARS + 1));
        assertReason(oversizedLine, RecoveryBundle.Reason.RESOURCE_LIMIT);

        Path excessiveInventory = createBundle(root.resolve("inventory-source"),
                root.resolve("excessive-inventory"));
        for (int index = 0; index <= RecoveryBundle.MAX_FILES; index++) {
            Files.createFile(excessiveInventory.resolve("extra-" + index));
        }
        assertReason(excessiveInventory, RecoveryBundle.Reason.RESOURCE_LIMIT);

        Path excessiveDepth = createBundle(root.resolve("depth-source"), root.resolve("excessive-depth"));
        Files.createDirectories(excessiveDepth.resolve(RecoveryBundle.AUDIT_DIRECTORY).resolve("nested"));
        assertReason(excessiveDepth, RecoveryBundle.Reason.DEPTH_LIMIT);

        Path oversizedAudit = createBundle(root.resolve("audit-limit-source"),
                root.resolve("oversized-audit"));
        Path auditLog;
        try (var entries = Files.list(oversizedAudit.resolve(RecoveryBundle.AUDIT_DIRECTORY))) {
            auditLog = entries.filter(path -> path.getFileName().toString().endsWith(".audit.jsonl"))
                    .findFirst().orElseThrow();
        }
        makeSparse(auditLog, RecoveryBundle.MAX_AUDIT_FILE_BYTES + 1);
        assertReason(oversizedAudit, RecoveryBundle.Reason.RESOURCE_LIMIT);

        Path excessiveAuditRecords = createBundle(root.resolve("audit-record-source"),
                root.resolve("excessive-audit-records"));
        try (var entries = Files.list(excessiveAuditRecords.resolve(RecoveryBundle.AUDIT_DIRECTORY))) {
            auditLog = entries.filter(path -> path.getFileName().toString().endsWith(".audit.jsonl"))
                    .findFirst().orElseThrow();
        }
        Files.writeString(auditLog, "\n".repeat((int) RecoveryBundle.MAX_AUDIT_RECORDS + 1));
        refreshEvidence(excessiveAuditRecords,
                RecoveryBundle.AUDIT_DIRECTORY + "/" + auditLog.getFileName());
        assertReason(excessiveAuditRecords, RecoveryBundle.Reason.RESOURCE_LIMIT);

        Path oversizedStore = createBundle(root.resolve("store-limit-source"),
                root.resolve("oversized-store"));
        makeSparse(oversizedStore.resolve(RecoveryBundle.STORE_FILE),
                RecoveryBundle.MAX_SQLITE_BYTES + 1);
        assertReason(oversizedStore, RecoveryBundle.Reason.RESOURCE_LIMIT);
    }

    @Test
    void manifestParserRequiresCanonicalNumericEncodingAndFieldOrder() throws Exception {
        Path nonCanonical = createBundle(root.resolve("numeric-source"), root.resolve("numeric-bundle"));
        Path manifest = nonCanonical.resolve(RecoveryBundle.MANIFEST_FILE);
        Files.writeString(manifest, Files.readString(manifest).replace("file-count: 3", "file-count: 03"));
        assertReason(nonCanonical, RecoveryBundle.Reason.MALFORMED_MANIFEST);

        Path reordered = createBundle(root.resolve("order-source"), root.resolve("order-bundle"));
        manifest = reordered.resolve(RecoveryBundle.MANIFEST_FILE);
        String text = Files.readString(manifest);
        text = text.replace("digest-algorithm: SHA-256\nauthenticity: not-provided\n",
                "authenticity: not-provided\ndigest-algorithm: SHA-256\n");
        Files.writeString(manifest, text);
        assertReason(reordered, RecoveryBundle.Reason.MALFORMED_MANIFEST);
    }

    private Path createBundle(Path source, Path destination) throws Exception {
        var errors = new ByteArrayOutputStream();
        int result = new BackupRestoreCommand(new PrintStream(java.io.OutputStream.nullOutputStream()),
                new PrintStream(errors)).backup(populatedConfiguration(source), destination);
        assertEquals(0, result, errors::toString);
        return destination;
    }

    private BackupRestoreConfiguration populatedConfiguration(Path directory) throws Exception {
        var configuration = new BackupRestoreConfiguration(directory.resolve("audit"),
                SqliteStoreLocation.underDirectory(directory.resolve("store")));
        try (var audit = new FileAuditTrail(configuration.auditDirectory(), Clock.systemUTC(), Duration.ZERO)) {
            audit.append(new AuditEnvelope(AuditEnvelope.CURRENT_VERSION, UUID.randomUUID(), TENANT,
                    "operator", AuditCategory.ACCESS, "EXECUTION_START", "execution", "one",
                    AuditOutcome.ALLOWED, "allowed", "request-one", Instant.now(),
                    OpaquePayload.empty("application/json")));
        }
        try (var ignored = new SqliteExecutionStore(configuration.executionStoreLocation(), Clock.systemUTC())) {
            // Create and validate the current schema.
        }
        return configuration;
    }

    private void assertRefusedAfterMutation(String name, Mutation mutation,
                                            RecoveryBundle.Reason expected) throws Exception {
        Path bundle = createBundle(root.resolve(name + "-source"), root.resolve(name + "-bundle"));
        mutation.apply(bundle);
        assertReason(bundle, expected);
    }

    private static void assertReason(Path bundle, RecoveryBundle.Reason expected) {
        var failed = assertThrows(RecoveryBundle.BundleException.class,
                () -> RecoveryBundle.verify(bundle));
        assertEquals(expected, failed.reason());
    }

    private static void refreshEvidence(Path bundle, String relative) throws Exception {
        Path manifest = bundle.resolve(RecoveryBundle.MANIFEST_FILE);
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        String key = lines.stream()
                .filter(line -> line.endsWith(".path: " + relative))
                .map(line -> line.substring(0, line.indexOf(".path: ")))
                .findFirst().orElseThrow();
        Path payload = bundle.resolve(relative);
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).startsWith(key + ".size: ")) {
                lines.set(index, key + ".size: " + Files.size(payload));
            } else if (lines.get(index).startsWith(key + ".sha256: ")) {
                lines.set(index, key + ".sha256: " + RecoveryBundle.sha256(payload));
            }
        }
        Files.write(manifest, lines, StandardCharsets.UTF_8);
    }

    private static void makeSparse(Path path, long size) throws Exception {
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.position(size - 1);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }
    }

    @FunctionalInterface
    private interface Mutation {
        void apply(Path bundle) throws Exception;
    }
}
