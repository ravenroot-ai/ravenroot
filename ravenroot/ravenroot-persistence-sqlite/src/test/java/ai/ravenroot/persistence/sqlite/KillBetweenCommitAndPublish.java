package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.JournalCursor;
import ai.ravenroot.api.persistence.JournalRecord;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.RevisionExpectation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * The program {@link SqliteCrashBetweenCommitAndPublishTest} forks and then kills with
 * {@code SIGKILL}, at the two points PERS-07's documented requirements names.
 *
 * <p>Nothing in this program uses a test hook inside the adapter, and that is deliberate. Both crash
 * points are between whole store operations rather than inside one, so the child can simply stop
 * where it wants to. A seam would have been evidence about a seam.</p>
 *
 * <p>Identities are constants so the parent asserts against them without parsing anything the child
 * printed. What the child claims about its own state after being killed is worth nothing; only the
 * database file is evidence.</p>
 */
public final class KillBetweenCommitAndPublish {

    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    static final String TENANT = "acme";
    static final String DESTINATION = "sse";
    static final String CONSUMER = "sse-projection";
    static final Duration INBOX_RETENTION = Duration.ofHours(1);
    static final UUID INSTANCE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    static final UUID TRAVERSAL_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    static final UUID EVENT_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    static final UUID CAUSATION_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    static final ExecutionKey KEY = new ExecutionKey(TENANT, INSTANCE_ID);

    static final String AT_BOUNDARY = "AT_BOUNDARY";
    static final String COMPLETED = "UNEXPECTEDLY_COMPLETED";
    /** Committed the transition and its event; not one byte has been published. */
    static final String BEFORE_PUBLISH = "before-publish";
    /** Delivered the event and recorded the consumer's effect; the cursor has not moved. */
    static final String BEFORE_CURSOR_ADVANCE = "before-cursor-advance";

    private KillBetweenCommitAndPublish() {
    }

    /** The event this run commits alongside its transition. */
    static EventEnvelope envelope() {
        return EventEnvelope.of(EVENT_ID, TENANT, "process.running", INSTANCE_ID, TRAVERSAL_ID, null, null,
                CAUSATION_ID, "request-1", "graph-v1", NOW,
                OpaquePayload.of("running".getBytes(StandardCharsets.UTF_8), "application/json"));
    }

    /** One batch: the transition and the publishable event, one transaction, one commit. */
    static ExecutionBatch batch(long expectedRevision) {
        return ExecutionBatch.to(KEY)
                .expecting(RevisionExpectation.exactly(expectedRevision))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .publish(envelope())
                .build();
    }

    public static void main(String[] args) {
        Path databaseFile = Path.of(args[0]);
        String mode = args[1];
        long expectedRevision = Long.parseLong(args[2]);

        var store = new SqliteExecutionStore(databaseFile, Clock.fixed(NOW, ZoneOffset.UTC));
        await(store.apply(batch(expectedRevision)));

        if (BEFORE_PUBLISH.equals(mode)) {
            // Committed and unpublished. Everything the documented requirements asks about happens after
            // this line, and this process will never reach it.
            parkForever();
        }

        JournalCursor cursor = await(store.outboxCursor(TENANT, DESTINATION));
        List<JournalRecord> batch = await(store.readJournal(TENANT, cursor.deliveredThrough(), 10));
        for (JournalRecord record : batch) {
            // The consumer's inbox record IS its durable effect here, which is how a consumer whose
            // state lives in the same store would write it: the dedup marker and the effect in one
            // transaction. A consumer that recorded the marker in one place and applied the effect in
            // another would have a loss window of its own, and this test would be measuring that
            // instead of the one it is about.
            await(store.recordInboxDelivery(TENANT, CONSUMER, record.envelope().eventId(), INBOX_RETENTION));
        }
        // Delivered but not acknowledged. The cursor has not moved, so a restart must redeliver.
        parkForever();

        await(store.advanceOutboxCursor(cursor, batch.get(batch.size() - 1).journalOffset()));
        System.out.println(COMPLETED);
        System.out.flush();
    }

    private static void parkForever() {
        System.out.println(AT_BOUNDARY);
        System.out.flush();
        try {
            // Long enough that a parent which failed to kill us hits its own timeout and reports
            // that, rather than this process quietly finishing and inventing a pass.
            Thread.sleep(Duration.ofMinutes(10));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
