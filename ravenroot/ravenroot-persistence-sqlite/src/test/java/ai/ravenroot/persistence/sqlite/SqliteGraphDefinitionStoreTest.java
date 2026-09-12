package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.CanonicalGraphMl;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.persistence.GraphDefinitionKey;
import ai.ravenroot.api.persistence.GraphDefinitionReferences;
import ai.ravenroot.api.persistence.GraphDefinitionStoreException;
import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.api.persistence.GraphDefinitionStoreFailure;
import ai.ravenroot.api.persistence.Retryability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The assertions the shared conformance suite cannot make, because they require reaching past the
 * port into the file the adapter owns.
 *
 * <p>Two classes of assertion live here. Corruption is one: no conforming adapter can be driven into
 * a digest mismatch through the port's own operations, so proving that verification is real means
 * changing the stored bytes behind the adapter's back and then reading through it. Durable
 * reachability is the other: the conformance suite drives retention through an injected oracle,
 * which proves the adapter consults <em>an</em> oracle; only a store co-located with real execution
 * rows can prove it also refuses to remove a definition an accepted execution still pins.</p>
 */
class SqliteGraphDefinitionStoreTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String TENANT = "acme";
    private static final byte[] DOCUMENT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns"><graph id="a" edgedefault="directed"/></graphml>
            """.getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path directory;

    @Test
    void explicitBoundsCannotEscapeTheSharedSafetyCeiling() {
        var location = SqliteStoreLocation.ofFile(directory.resolve("bounded.db"));
        assertThrows(IllegalArgumentException.class, () -> new SqliteGraphDefinitionStore(
                location, CLOCK, GraphDefinitionReferences.NONE, 0));
        assertThrows(IllegalArgumentException.class, () -> new SqliteGraphDefinitionStore(
                location, CLOCK, GraphDefinitionReferences.NONE,
                GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES + 1));
    }

    @Test
    void theSchemaCarriesDefinitionsAtTheCurrentVersion() throws SQLException {
        Path database = directory.resolve("store.db");
        try (var store = open(database)) {
            await(store.put(TENANT, identity("orders", "1"), CanonicalGraphMl.of(DOCUMENT)));
        }
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = raw.createStatement()) {
            try (ResultSet rows = statement.executeQuery("PRAGMA user_version")) {
                assertTrue(rows.next());
                assertEquals(SqliteSchema.currentVersion(), rows.getInt(1),
                        "definitions and executions share one schema version, so a binary can never "
                                + "open a file whose executions it understands and whose definitions "
                                + "it does not");
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' "
                            + "AND name = 'idx_process_instance_pin'")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1), "retention asks, per candidate definition, whether "
                        + "any instance of the tenant still pins it; without this index that is a "
                        + "tenant-wide scan per candidate");
            }
        }
    }

    @Test
    void tamperingWithTheStoredBytesIsCaughtOnEveryRead() throws SQLException {
        Path database = directory.resolve("store.db");
        GraphDefinitionKey key;
        try (var store = open(database)) {
            key = await(store.put(TENANT, identity("orders", "1"), CanonicalGraphMl.of(DOCUMENT))).key();
        }
        overwrite(database, "UPDATE graph_definition SET definition_bytes = ?, byte_length = ?",
                statement -> {
                    byte[] tampered = DOCUMENT.clone();
                    tampered[tampered.length - 2] = ' ';
                    statement.setBytes(1, tampered);
                    statement.setLong(2, tampered.length);
                });

        try (var store = open(database)) {
            var byAddress = failureOf(() -> await(store.load(key)));
            var mismatch = assertInstanceOf(GraphDefinitionStoreFailure.DigestMismatch.class, byAddress,
                    "a runtime replays a graph against these bytes; returning them with a warning "
                            + "would mean executing them");
            assertEquals(Retryability.DETERMINISTIC_REJECT, mismatch.retryability());
            assertFalse(mismatch.describe().contains("<graphml"),
                    "and the diagnostic must not carry the document into a log");

            assertInstanceOf(GraphDefinitionStoreFailure.DigestMismatch.class,
                    failureOf(() -> await(store.resolve(TENANT, identity("orders", "1")))),
                    "resolving a version reads through the same verification, so the version route "
                            + "cannot be the one that hands out unverified bytes");
        }
    }

    @Test
    void aRowWhoseRecordedDigestDisagreesWithItsAddressIsCorruptRatherThanMismatched() throws SQLException {
        Path database = directory.resolve("store.db");
        GraphDefinitionKey key;
        try (var store = open(database)) {
            key = await(store.put(TENANT, identity("orders", "1"), CanonicalGraphMl.of(DOCUMENT))).key();
        }
        // The bytes still hash to the digest column, but the digest column no longer names the row's
        // address: nothing was tampered with, the row is internally inconsistent.
        overwrite(database, "UPDATE graph_definition SET definition_bytes = ?, digest = ?, byte_length = ?",
                statement -> {
                    byte[] other = "<graphml/>".getBytes(StandardCharsets.UTF_8);
                    statement.setBytes(1, other);
                    statement.setBytes(2, java.security.MessageDigest.getInstance("SHA-256").digest(other));
                    statement.setLong(3, other.length);
                });

        try (var store = open(database)) {
            assertInstanceOf(GraphDefinitionStoreFailure.Corrupted.class,
                    failureOf(() -> await(store.load(key))));
        }
    }

    @Test
    void aRowWhoseRecordedLengthDisagreesWithItsContentIsCorrupt() throws SQLException {
        Path database = directory.resolve("store.db");
        GraphDefinitionKey key;
        try (var store = open(database)) {
            key = await(store.put(TENANT, identity("orders", "1"), CanonicalGraphMl.of(DOCUMENT))).key();
        }
        overwrite(database, "UPDATE graph_definition SET byte_length = ?",
                statement -> statement.setLong(1, DOCUMENT.length + 1));

        try (var store = open(database)) {
            var failure = failureOf(() -> await(store.load(key)));
            assertInstanceOf(GraphDefinitionStoreFailure.Corrupted.class, failure);
            assertTrue(failure.describe().contains(String.valueOf(DOCUMENT.length)),
                    "an operator must be able to see the disagreement without opening the database");
        }
    }

    @Test
    void aDefinitionAnAcceptedExecutionPinsIsNeverRemoved() {
        Path database = directory.resolve("store.db");
        var canonical = CanonicalGraphMl.of(DOCUMENT);
        GraphDefinitionKey key = new GraphDefinitionKey(TENANT, canonical.contentId());

        try (var executions = new SqliteExecutionStore(database, CLOCK);
             var definitions = open(database)) {
            await(definitions.put(TENANT, GraphDefinitionIdentity.forSubmission(canonical.contentId()),
                    canonical));

            // The acceptance ordering the port requires: the definition is durable first, and only
            // then is an execution pinned to it.
            var executionKey = new ExecutionKey(TENANT, UUID.randomUUID());
            await(executions.apply(ai.ravenroot.api.persistence.ExecutionBatch.to(executionKey)
                    .expecting(ai.ravenroot.api.persistence.RevisionExpectation.notPresent())
                    .apply(new ai.ravenroot.api.persistence.ExecutionTransition.ProcessCreated(
                            Fixtures.acceptedInstance(executionKey.processInstanceId(), UUID.randomUUID()),
                            new ai.ravenroot.api.persistence.GraphVersionPin(canonical.contentId().value())))
                    .build()));

            assertEquals(0L, await(definitions.purgeUnreferencedDefinitions(TENANT)),
                    "retention must not remove a definition a recoverable execution still pins, and "
                            + "the reachability question is answered from the execution rows rather "
                            + "than from a count this store maintains for itself");
            assertInstanceOf(GraphDefinitionStoreFailure.StillReferenced.class,
                    failureOf(() -> await(definitions.remove(key))));
            assertArrayEquals(DOCUMENT, await(definitions.load(key)).canonical().bytes());
        }
    }

    @Test
    void aDefinitionNoExecutionPinsIsReclaimedEvenWhileOtherExecutionsExist() {
        Path database = directory.resolve("store.db");
        var pinned = CanonicalGraphMl.of(DOCUMENT);
        var orphan = CanonicalGraphMl.of("<graphml id=\"orphan\"/>".getBytes(StandardCharsets.UTF_8));

        try (var executions = new SqliteExecutionStore(database, CLOCK);
             var definitions = open(database)) {
            await(definitions.put(TENANT, identity("orders", "1"), pinned));
            await(definitions.put(TENANT, identity("orders", "2"), orphan));

            var executionKey = new ExecutionKey(TENANT, UUID.randomUUID());
            await(executions.apply(ai.ravenroot.api.persistence.ExecutionBatch.to(executionKey)
                    .expecting(ai.ravenroot.api.persistence.RevisionExpectation.notPresent())
                    .apply(new ai.ravenroot.api.persistence.ExecutionTransition.ProcessCreated(
                            Fixtures.acceptedInstance(executionKey.processInstanceId(), UUID.randomUUID()),
                            new ai.ravenroot.api.persistence.GraphVersionPin(pinned.contentId().value())))
                    .build()));

            assertEquals(1L, await(definitions.purgeUnreferencedDefinitions(TENANT)));
            assertTrue(await(definitions.contains(new GraphDefinitionKey(TENANT, pinned.contentId()))));
            assertFalse(await(definitions.contains(new GraphDefinitionKey(TENANT, orphan.contentId()))));
        }
    }

    @Test
    void aClosedStoreClassifiesRatherThanThrowingFromTheConnection() {
        Path database = directory.resolve("store.db");
        var store = open(database);
        store.close();
        store.close();

        var failure = failureOf(() -> await(store.load(
                new GraphDefinitionKey(TENANT, GraphContentId.of(DOCUMENT)))));
        assertInstanceOf(GraphDefinitionStoreFailure.Unavailable.class, failure);
        assertEquals(Retryability.RETRYABLE_NO_EFFECT, failure.retryability());
    }

    private SqliteGraphDefinitionStore open(Path database) {
        return new SqliteGraphDefinitionStore(database, CLOCK, GraphDefinitionReferences.NONE);
    }

    private static GraphDefinitionIdentity identity(String graphId, String versionId) {
        return new GraphDefinitionIdentity(graphId, versionId);
    }

    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException, java.security.NoSuchAlgorithmException;
    }

    private static void overwrite(Path database, String sql, Binder binder) throws SQLException {
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement statement = raw.prepareStatement(sql)) {
            binder.bind(statement);
            assertEquals(1, statement.executeUpdate());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private static GraphDefinitionStoreFailure failureOf(Runnable operation) {
        CompletionException thrown = assertThrows(CompletionException.class, operation::run);
        GraphDefinitionStoreException failure = GraphDefinitionStoreException.unwrap(thrown);
        assertNotNull(failure, "adapters must not leak non-store exceptions: " + thrown);
        return failure.failure();
    }
}
