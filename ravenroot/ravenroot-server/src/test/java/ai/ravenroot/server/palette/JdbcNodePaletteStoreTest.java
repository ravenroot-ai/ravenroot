package ai.ravenroot.server.palette;

import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JdbcNodePaletteStoreTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void persistsAcrossReopenAndKeepsExactOwnerIsolation(@TempDir Path directory) {
        Path database = directory.resolve("execution.db");
        try (var ignored = new SqliteExecutionStore(database, CLOCK)) { }
        var first = JdbcNodePaletteStore.sqlite(database, CLOCK);
        var alice = new NodePaletteStore.Owner("tenant-a", "issuer-a", "subject-a");
        var siblingIssuer = new NodePaletteStore.Owner("tenant-a", "issuer-b", "subject-a");
        var siblingUser = new NodePaletteStore.Owner("tenant-a", "issuer-a", "subject-b");
        var siblingTenant = new NodePaletteStore.Owner("tenant-b", "issuer-a", "subject-a");
        var palette = first.createPalette(alice, "My nodes");
        var template = first.createTemplate(alice, palette.id(), "Probe", "BEHAVIOR", "{\"schemaVersion\":1}");

        var reopened = JdbcNodePaletteStore.sqlite(database, CLOCK);
        assertEquals(java.util.List.of(palette), reopened.listPalettes(alice));
        assertEquals(java.util.List.of(template), reopened.listTemplates(alice));
        assertTrue(reopened.listPalettes(siblingIssuer).isEmpty());
        for (var other : java.util.List.of(siblingIssuer, siblingUser, siblingTenant)) {
            assertTrue(reopened.listPalettes(other).isEmpty());
            assertThrows(NodePaletteStore.StoreException.class,
                    () -> reopened.findTemplate(other, template.id()));
        }
    }

    @Test
    void fencesConcurrentUpdatesWithVersions(@TempDir Path directory) {
        Path database = directory.resolve("execution.db");
        try (var ignored = new SqliteExecutionStore(database, CLOCK)) { }
        var store = JdbcNodePaletteStore.sqlite(database, CLOCK);
        var owner = new NodePaletteStore.Owner("tenant", "issuer", "subject");
        var palette = store.createPalette(owner, "First");
        store.renamePalette(owner, palette.id(), palette.version(), "Second");
        var failure = assertThrows(NodePaletteStore.StoreException.class,
                () -> store.renamePalette(owner, palette.id(), palette.version(), "Stale"));
        assertEquals(NodePaletteStore.Failure.CONFLICT, failure.failure());
    }
}
