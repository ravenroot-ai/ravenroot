package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.JournalRecord;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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

/**
 * QA-07: Extends {@link SqliteJournalIntegrityTest}'s two hand-picked
 * out-of-band column tampers ({@code event_type}, {@code payload_bytes}) into a property over
 * which digest-covered column is tampered. Same scope boundary as {@link EventEnvelopeFuzzTest}:
 * the digest/serialization boundary, not crash-timing (that is PERS-07's own {@code Kill*} suite).
 *
 * <p>Not tagged onto {@code SqliteJournalIntegrityTest} itself and its two existing {@code @Test}
 * methods stay exactly as they are -- they are cheap, already-passing, already-load-bearing pins for
 * the two columns most likely to be hand-edited by an operator (the event type and the payload);
 * this class is the new, broader, {@code fuzz}-tagged coverage across the rest of the digest-covered
 * columns, additive rather than a replacement.
 *
 * <p>Each property try opens its own fresh SQLite file under a manually-created temp directory
 * (not {@code @TempDir}: that is a JUnit Jupiter extension and this class runs on jqwik's own
 * engine, which does not invoke it) and removes it afterward.
 */
class SqliteJournalTamperFuzzTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String TENANT = "acme";

    // ------------------------------------------------------------------------------------------
    // Tampering any one digest-covered TEXT column out of band is reported as Corrupted, not
    // served as authentic. event_type and payload_bytes are already pinned by
    // SqliteJournalIntegrityTest's own hand-picked tests; this property covers the remaining
    // digest-covered text columns those tests do not touch: correlation_id, graph_version,
    // payload_content_type, event_id and traversal_id.
    // ------------------------------------------------------------------------------------------

    @Tag("fuzz")
    @Property(tries = 40)
    void tamperingAnyDigestCoveredTextColumnIsReportedAsCorrupted(
            @ForAll("digestCoveredTextColumns") String column, @ForAll("shortTexts") String replacement)
            throws IOException {
        Path directory = Files.createTempDirectory("qa07-journal-tamper");
        try {
            Path file = directory.resolve("journal.db");
            var key = new ExecutionKey(TENANT, UUID.randomUUID());
            UUID traversalId = UUID.randomUUID();

            try (var store = openAt(file)) {
                StoredProcessInstance created = await(store.apply(Fixtures.creationBatch(key, traversalId)));
                await(store.apply(ExecutionBatch.to(key)
                        .expecting(RevisionExpectation.exactly(created.revision()))
                        .publish(envelope(key, traversalId)).build()));
            }

            try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
                 PreparedStatement statement = raw.prepareStatement(
                         "UPDATE event_journal SET " + column + " = ? WHERE tenant_id = ?")) {
                statement.setString(1, replacement);
                statement.setString(2, TENANT);
                int updated = statement.executeUpdate();
                assertEquals(1, updated, "expected exactly one row to tamper for column " + column);
            } catch (java.sql.SQLException sqlSetupFailure) {
                throw new AssertionError(sqlSetupFailure);
            }

            try (var store = openAt(file)) {
                ExecutionStoreFailure failure = failureOf(() -> await(store.readJournal(TENANT, 0, 10)));
                assertInstanceOf(ExecutionStoreFailure.Corrupted.class, failure,
                        "tampering column " + column + " to \"" + replacement + "\" must not be served as "
                                + "an authentic event");
            }
        } finally {
            deleteRecursively(directory);
        }
    }

    @Provide
    Arbitrary<String> digestCoveredTextColumns() {
        // event_type and payload_bytes are already covered by SqliteJournalIntegrityTest's own
        // hand-picked tests; tenant_id and process_instance_id are excluded because they are part
        // of the primary key / the readJournal WHERE clause, so tampering them changes which rows
        // are even visible rather than exercising the digest check this property targets.
        //
        // UUID columns are included now that the SQLite boundary maps malformed UUID text to the
        // same typed corruption failure as a digest mismatch. A direct UUID.fromString restoration
        // would make these cases leak IllegalArgumentException and fail this property.
        return Arbitraries.of("correlation_id", "graph_version", "payload_content_type",
                "event_id", "traversal_id");
    }

    @Provide
    Arbitrary<String> shortTexts() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(12);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static SqliteExecutionStore openAt(Path file) {
        return new SqliteExecutionStore(file, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static EventEnvelope envelope(ExecutionKey key, UUID traversalId) {
        return EventEnvelope.of(UUID.randomUUID(), key.tenantId(), "node.started", key.processInstanceId(),
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

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup; leaving a stray temp file behind is not worth failing the
                    // property over.
                }
            });
        }
    }
}
