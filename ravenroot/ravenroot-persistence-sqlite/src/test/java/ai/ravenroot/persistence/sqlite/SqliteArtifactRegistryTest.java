package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.programming.ArtifactProvenanceVerifier;
import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.ProgramArtifactIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteArtifactRegistryTest {
    @TempDir Path directory;

    @Test
    void reopensDurablyAndRejectsOneByteTampering() {
        String id;
        try (var registry = SqliteArtifactRegistry.openUnder(directory, ArtifactProvenanceVerifier.refusing())) {
            var artifact = registry.create("python", "print('ok')", Map.of("ravenroot.security.ownerTenant", "tenant-a"));
            id = artifact.id();
            registry.transition(id, ArtifactState.GENERATED, ArtifactState.VALIDATED, Map.of("check", "ok"));
        }
        try (var reopened = SqliteArtifactRegistry.openUnder(directory, ArtifactProvenanceVerifier.refusing())) {
            assertEquals(ArtifactState.VALIDATED, reopened.find(id).orElseThrow().state());
            assertThrows(IllegalStateException.class, () -> reopened.transition(id, ArtifactState.GENERATED, ArtifactState.TESTED));
        }
    }

    @Test
    void tenantDigestUniquenessIsScopedAndRetirementCancelsAnAdmittedExecution() {
        try (var registry = SqliteArtifactRegistry.openUnder(directory, artifact -> { })) {
            var first = registry.create("javascript", "return 1", owner("tenant-a"));
            assertThrows(IllegalStateException.class,
                    () -> registry.create("javascript", "return 1", owner("tenant-a")));
            var otherTenant = registry.create("javascript", "return 1", owner("tenant-b"));
            assertEquals(first.sha256(), otherTenant.sha256());
            assertEquals(first.id(), registry.findByTenantAndDigest("tenant-a", first.sha256()).orElseThrow().id());

            var active = activate(registry, first.id());
            var admission = registry.admitForExecution("tenant-a", active.id());
            var cancelled = new AtomicBoolean();
            admission.onRevoked(() -> cancelled.set(true));
            assertEquals(active.id(), admission.redeem().id());
            registry.transition(active.id(), ArtifactState.ACTIVE, ArtifactState.RETIRED,
                    Map.of("reason", "security revocation"));
            assertTrue(cancelled.get(), "retirement must cancel an already-admitted sandbox execution");
            assertThrows(SecurityException.class, admission::redeem);
            admission.close();
        }
    }

    @Test
    void sourceDigestCorruptionFailsTheNextOpenClosed() throws Exception {
        String id;
        try (var registry = SqliteArtifactRegistry.openUnder(directory, artifact -> { })) {
            id = registry.create("python", "print('safe')", owner("tenant-a")).id();
        }
        Path database = directory.resolve(SqliteArtifactRegistry.FILE_NAME);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var update = connection.prepareStatement("UPDATE program_artifact SET source_utf8=? WHERE id=?")) {
            update.setBytes(1, "print('evil')".getBytes(StandardCharsets.UTF_8));
            update.setString(2, id);
            assertEquals(1, update.executeUpdate());
        }
        var refused = assertThrows(IllegalStateException.class,
                () -> SqliteArtifactRegistry.openUnder(directory, artifact -> { }));
        assertTrue(refused.getMessage().contains("cannot open durable program artifact registry"));
    }

    @Test
    void migratesTheAttemptOneSchemaTransactionally() throws Exception {
        Path database = directory.resolve(SqliteArtifactRegistry.FILE_NAME);
        java.nio.file.Files.createDirectories(directory);
        Instant now = Instant.parse("2026-08-29T00:00:00Z");
        String metadata = encoded("ravenroot.security.ownerTenant") + ":" + encoded("tenant-a");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE program_artifact (
                      id TEXT NOT NULL PRIMARY KEY, language TEXT NOT NULL, sha256 TEXT NOT NULL,
                      source TEXT NOT NULL, state TEXT NOT NULL, revision INTEGER NOT NULL,
                      created_at TEXT NOT NULL, updated_at TEXT NOT NULL, metadata TEXT NOT NULL
                    ) WITHOUT ROWID
                    """);
            statement.execute("PRAGMA user_version=1");
            try (var insert = connection.prepareStatement("INSERT INTO program_artifact VALUES (?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1, "legacy-id");
                insert.setString(2, "javascript");
                insert.setString(3, ProgramArtifactIdentity.sha256("javascript", "return 1"));
                insert.setString(4, "return 1");
                insert.setString(5, ArtifactState.GENERATED.name());
                insert.setLong(6, 1);
                insert.setString(7, now.toString());
                insert.setString(8, now.toString());
                insert.setString(9, metadata);
                insert.executeUpdate();
            }
        }
        try (var migrated = SqliteArtifactRegistry.openUnder(directory, artifact -> { })) {
            var artifact = migrated.find("legacy-id").orElseThrow();
            assertEquals("tenant-a", artifact.metadata().get("ravenroot.security.ownerTenant"));
            assertEquals("return 1", artifact.source());
            assertEquals(artifact.id(), migrated.findByTenantAndDigest("tenant-a", artifact.sha256())
                    .orElseThrow().id());
        }
    }

    private static Map<String, String> owner(String tenant) {
        return Map.of("ravenroot.security.ownerTenant", tenant);
    }

    private static ai.ravenroot.api.programming.GeneratedArtifact activate(
            SqliteArtifactRegistry registry, String id) {
        registry.transition(id, ArtifactState.GENERATED, ArtifactState.VALIDATED);
        registry.transition(id, ArtifactState.VALIDATED, ArtifactState.TESTED);
        registry.transition(id, ArtifactState.TESTED, ArtifactState.APPROVED);
        return registry.transition(id, ArtifactState.APPROVED, ArtifactState.ACTIVE);
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
