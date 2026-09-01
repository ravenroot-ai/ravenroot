package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.JournalCursor;
import ai.ravenroot.api.persistence.JournalRecord;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two shapes PERS-07 was not designed around but must not break.
 *
 * <p>The first is an operator's existing PERS-03 database. Migration 2 is purely additive — it
 * creates five tables and alters none — but "purely additive" is a claim about DDL that a test should
 * confirm actually executes against a populated v1 file, because a migration that refuses to apply
 * turns an upgrade into an outage regardless of how additive it was.</p>
 *
 * <p>The second is more than one destination. Every compaction assertion in the conformance suite
 * uses a single cursor, so the minimum-over-destinations rule is exercised with a set of size one,
 * where a minimum and a maximum are the same number. An implementation that took the <em>highest</em>
 * cursor instead of the lowest would pass all of them and would discard events a lagging destination
 * had not yet received.</p>
 */
class SqliteJournalUpgradeAndFanOutTest {

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final String TENANT = "acme";

    @TempDir
    Path databaseDirectory;

    @Test
    void anExistingVersionOneDatabaseUpgradesInPlaceAndKeepsTheInstancesItAlreadyHeld() throws Exception {
        Path file = databaseDirectory.resolve("legacy.db");
        UUID instanceId = UUID.randomUUID();
        UUID traversalId = UUID.randomUUID();

        // A database as PERS-03 left it: migration 1 only, with a real instance in it.
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
            int installed = SqliteSchema.migrate(raw, SqliteSchema.migrations().subList(0, 1),
                    java.time.Clock.fixed(EPOCH, java.time.ZoneOffset.UTC));
            assertEquals(1, installed, "the fixture must actually be a version-1 database");
            insertLegacyInstance(raw, instanceId, traversalId);
        }

        var clock = new MutableClock(EPOCH);
        try (var store = new SqliteExecutionStore(file, clock)) {
            try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
                assertEquals(SqliteSchema.currentVersion(), SqliteSchema.versionOf(raw),
                        "opening the file upgraded it rather than refusing it");
            }

            var key = new ExecutionKey(TENANT, instanceId);
            StoredProcessInstance reloaded = await(store.load(key));
            assertEquals(ProcessInstanceStatus.ACCEPTED, reloaded.state().status(),
                    "the instance written under version 1 is still readable under version 2");
            assertTrue(reloaded.state().traversals().containsKey(traversalId));

            // And the new machinery works on the upgraded file, starting from an empty journal.
            assertTrue(await(store.readJournal(TENANT, 0, 10)).isEmpty());
            assertEquals(1L, await(store.journalRetainedFrom(TENANT)),
                    "a tenant with no journal row yet reports the first offset it will ever issue, "
                            + "so an upgraded database is not mistaken for a truncated one");
            await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(reloaded.revision()))
                    .publish(envelope(key, traversalId, "post.upgrade")).build()));
            List<JournalRecord> journal = await(store.readJournal(TENANT, 0, 10));
            assertEquals(1, journal.size());
            assertEquals(1L, journal.get(0).journalOffset());
            assertEquals(1L, journal.get(0).streamSequence());
        }
    }

    @Test
    void compactionWaitsForTheSlowestDestinationRatherThanTheFastest() {
        Path file = databaseDirectory.resolve("fanout.db");
        var clock = new MutableClock(EPOCH);
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();

        try (var store = new SqliteExecutionStore(file, clock)) {
            StoredProcessInstance created = await(store.apply(Fixtures.creationBatch(key, traversalId)));
            await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(created.revision()))
                    .publish(envelope(key, traversalId, "a"))
                    .publish(envelope(key, traversalId, "b"))
                    .publish(envelope(key, traversalId, "c")).build()));
            List<JournalRecord> journal = await(store.readJournal(TENANT, 0, 10));
            assertEquals(3, journal.size());

            // "sse" is caught up; "kafka" has only taken the first record.
            await(store.advanceOutboxCursor(await(store.outboxCursor(TENANT, "sse")),
                    journal.get(2).journalOffset()));
            await(store.advanceOutboxCursor(await(store.outboxCursor(TENANT, "kafka")),
                    journal.get(0).journalOffset()));

            clock.advance(store.journalRetention().plusMinutes(1));
            assertEquals(1L, await(store.compactJournal(TENANT)),
                    "only the record BOTH destinations have taken may go; taking the highest cursor "
                            + "instead of the lowest would discard two events kafka never received");

            List<JournalRecord> survivors = await(store.readJournal(TENANT,
                    await(store.outboxCursor(TENANT, "kafka")).deliveredThrough(), 10));
            assertEquals(2, survivors.size(), "the lagging destination can still catch up");
            assertEquals(journal.get(1).journalOffset(), survivors.get(0).journalOffset());
        }
    }

    @Test
    void aDestinationAddedAfterTheEventsWereWrittenStillReceivesEverythingStillRetained() {
        Path file = databaseDirectory.resolve("late-joiner.db");
        var clock = new MutableClock(EPOCH);
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();

        try (var store = new SqliteExecutionStore(file, clock)) {
            StoredProcessInstance created = await(store.apply(Fixtures.creationBatch(key, traversalId)));
            await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(created.revision()))
                    .publish(envelope(key, traversalId, "a"))
                    .publish(envelope(key, traversalId, "b")).build()));

            // "sse" existed all along and is caught up.
            await(store.advanceOutboxCursor(await(store.outboxCursor(TENANT, "sse")),
                    await(store.readJournal(TENANT, 0, 10)).get(1).journalOffset()));

            // "kafka" is configured only now. Under a row-per-destination outbox it would have no
            // rows for these events and would never see them; under a cursor it starts at zero.
            JournalCursor late = await(store.outboxCursor(TENANT, "kafka"));
            assertEquals(0L, late.deliveredThrough());
            assertEquals(2, await(store.readJournal(TENANT, late.deliveredThrough(), 10)).size(),
                    "a destination added after the fact receives the whole retained journal");
        }
    }

    @Test
    void anEventOnlyBatchIsALegalCommitAndStillAdvancesTheRevision() {
        Path file = databaseDirectory.resolve("event-only.db");
        var clock = new MutableClock(EPOCH);
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();

        try (var store = new SqliteExecutionStore(file, clock)) {
            StoredProcessInstance created = await(store.apply(Fixtures.creationBatch(key, traversalId)));
            StoredProcessInstance after = await(store.apply(ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(created.revision()))
                    .publish(envelope(key, traversalId, "note")).build()));
            assertTrue(after.revision() > created.revision(),
                    "a batch carrying only events is still a commit, so it must move the revision or "
                            + "an event could name a revision indistinguishable from the one before it");
            assertEquals(ProcessInstanceStatus.ACCEPTED, after.state().status(), "and change nothing else");
            assertEquals(after.revision(),
                    await(store.readJournal(TENANT, 0, 10)).get(0).committedAtRevision());
        }
    }

    private void insertLegacyInstance(Connection raw, UUID instanceId, UUID traversalId) throws Exception {
        try (PreparedStatement statement = raw.prepareStatement(
                "INSERT INTO process_instance (tenant_id, process_instance_id, status, graph_version_pin, "
                        + "revision, fencing_token, updated_at_epoch_second, updated_at_nano) "
                        + "VALUES (?, ?, 'ACCEPTED', 'graph-v1', 1, 0, ?, 0)")) {
            statement.setString(1, TENANT);
            statement.setString(2, instanceId.toString());
            statement.setLong(3, EPOCH.getEpochSecond());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = raw.prepareStatement(
                "INSERT INTO traversal (tenant_id, process_instance_id, traversal_id, position, "
                        + "ingress_node_id, status) VALUES (?, ?, ?, 0, 'start', 'ACCEPTED')")) {
            statement.setString(1, TENANT);
            statement.setString(2, instanceId.toString());
            statement.setString(3, traversalId.toString());
            statement.executeUpdate();
        }
    }

    private static EventEnvelope envelope(ExecutionKey key, UUID traversalId, String eventType) {
        return EventEnvelope.of(UUID.randomUUID(), key.tenantId(), eventType, key.processInstanceId(),
                traversalId, null, null, null, "request-1", "graph-v1", EPOCH,
                OpaquePayload.of(eventType.getBytes(StandardCharsets.UTF_8), "application/json"));
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
