package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.JournalRecord;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The event digest, driven against a database whose rows have actually been altered.
 *
 * <p>This is the treatment ADR 0010 section 12.4 blesses for {@code Corrupted}: a durable adapter
 * needs no fault-injection hook, because the failure mode is real and reachable — edit a row out of
 * band and read it back. The conformance suite cannot assert this against an in-memory adapter, whose
 * stored envelope is the very object the digest was computed from and therefore cannot disagree with
 * it.</p>
 *
 * <p>It is also the assertion that gives the digest a purpose. A digest that is only ever written and
 * read alongside its own content proves that the producer agrees with itself; a digest that survives
 * a rewritten row and reports the disagreement proves something about storage.</p>
 */
class SqliteJournalIntegrityTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String TENANT = "acme";

    @TempDir
    Path databaseDirectory;

    @Test
    void aJournalRowRewrittenOutOfBandIsReportedAsCorruptedRatherThanServedAsAuthentic() throws Exception {
        Path file = databaseDirectory.resolve("journal.db");
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();

        try (var store = openAt(file)) {
            StoredProcessInstance created = await(store.apply(Fixtures.creationBatch(key, traversalId)));
            await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(created.revision()))
                    .publish(envelope(key, traversalId, "node.started")).build()));
            List<JournalRecord> journal = await(store.readJournal(TENANT, 0, 10));
            assertEquals(1, journal.size());
            assertTrue(journal.get(0).envelope().digestMatchesContent());
        }

        // Exactly the tamper the digest exists to catch: the content changes, the digest does not.
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = raw.createStatement()) {
            assertEquals(1, statement.executeUpdate(
                    "UPDATE event_journal SET event_type = 'node.succeeded' WHERE tenant_id = '" + TENANT + "'"));
        }

        try (var store = openAt(file)) {
            ExecutionStoreFailure failure = failureOf(() -> await(store.readJournal(TENANT, 0, 10)));
            var corrupted = assertInstanceOf(ExecutionStoreFailure.Corrupted.class, failure,
                    "a rewritten journal row must not be delivered as an authentic event; a consumer "
                            + "that received it would build a projection on a claim nobody made");
            assertEquals(key.processInstanceId(), corrupted.key().processInstanceId());
        }
    }

    @Test
    void aJournalRowWhosePayloadIsRewrittenIsAlsoCaught() throws Exception {
        Path file = databaseDirectory.resolve("payload.db");
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();

        try (var store = openAt(file)) {
            StoredProcessInstance created = await(store.apply(Fixtures.creationBatch(key, traversalId)));
            await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(created.revision()))
                    .publish(envelope(key, traversalId, "node.started")).build()));
        }

        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = raw.createStatement()) {
            assertEquals(1, statement.executeUpdate(
                    "UPDATE event_journal SET payload_bytes = CAST('tampered' AS BLOB) WHERE tenant_id = '"
                            + TENANT + "'"));
        }

        try (var store = openAt(file)) {
            assertInstanceOf(ExecutionStoreFailure.Corrupted.class,
                    failureOf(() -> await(store.readJournal(TENANT, 0, 10))),
                    "the body is covered by the digest, not just the header");
        }
    }

    @Test
    void anIntactJournalSurvivesAReopenWithItsDigestAndCausalityUnchanged() {
        Path file = databaseDirectory.resolve("reopen.db");
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        EventEnvelope written = envelope(key, traversalId, "node.started");

        try (var store = openAt(file)) {
            StoredProcessInstance created = await(store.apply(Fixtures.creationBatch(key, traversalId)));
            await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(created.revision()))
                    .publish(written).build()));
            await(store.advanceOutboxCursor(await(store.outboxCursor(TENANT, "sse")),
                    await(store.readJournal(TENANT, 0, 10)).get(0).journalOffset()));
        }

        try (var store = openAt(file)) {
            List<JournalRecord> journal = await(store.readJournal(TENANT, 0, 10));
            assertEquals(1, journal.size());
            EventEnvelope reloaded = journal.get(0).envelope();
            assertEquals(written.digest(), reloaded.digest());
            assertEquals(written.causationId(), reloaded.causationId());
            assertEquals(written.correlationId(), reloaded.correlationId());
            assertEquals(written.payload(), reloaded.payload());
            assertTrue(reloaded.digestMatchesContent());
            assertTrue(await(store.outboxCursor(TENANT, "sse")).deliveredThrough() > 0,
                    "the destination cursor is durable too, or every restart would redeliver the "
                            + "entire retained journal to every destination");
        }
    }

    private SqliteExecutionStore openAt(Path file) {
        return new SqliteExecutionStore(file, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static EventEnvelope envelope(ExecutionKey key, UUID traversalId, String eventType) {
        return EventEnvelope.of(UUID.randomUUID(), key.tenantId(), eventType, key.processInstanceId(),
                traversalId, null, null, UUID.randomUUID(), "request-1", "graph-v1", NOW,
                OpaquePayload.of("body".getBytes(StandardCharsets.UTF_8), "application/json"));
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private static ExecutionStoreFailure failureOf(Runnable operation) {
        CompletionException thrown = assertThrows(CompletionException.class, operation::run);
        ExecutionStoreException failure = ExecutionStoreException.unwrap(thrown);
        assertNotNull(failure, "adapters must not leak non-store exceptions: " + thrown);
        return failure.failure();
    }
}
