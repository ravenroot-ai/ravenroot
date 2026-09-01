package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.persistence.JournalCursor;
import ai.ravenroot.api.persistence.JournalRecord;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.testkit.persistence.KillMatrixArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERS-07's documented requirements: <em>a crash between commit and publish, with neither loss nor logical
 * duplication</em>.
 *
 * <p>Both directions use a real {@code SIGKILL} of a real operating-system process, for the reason
 * {@link SqliteKillAtCommitBoundaryTest} already documents: {@link Process#destroyForcibly()} on a
 * POSIX host cannot be caught, blocked or handled, so no shutdown hook runs, no {@code finally}
 * executes and the file locks are released by the kernel rather than by SQLite. An in-JVM simulation
 * still gives the adapter a chance to run code, and "the adapter got to run code" is precisely the
 * assumption a crash test exists to remove.</p>
 *
 * <h2>What each direction proves, and why one is not enough</h2>
 * <ul>
 *   <li><strong>Killed after the commit, before any publish.</strong> The transition and its event
 *   committed together; nothing was delivered. After recovery the event is still in the journal, the
 *   destination cursor is untouched, and a publisher run delivers it exactly once. This is the
 *   <em>no loss</em> half, covering the commit-before-publish window directly.</li>
 *   <li><strong>Killed after delivering, before advancing the cursor.</strong> The consumer's effect
 *   is durably recorded and the cursor still says nothing was delivered. After recovery the publisher
 *   redelivers — which is the honest consequence of at-least-once and must be demonstrated rather
 *   than hoped away — and the inbox refuses the effect. This is the <em>no logical duplication</em>
 *   half.</li>
 * </ul>
 *
 * <p>Testing only the first would leave the claim half-made: an implementation that lost nothing and
 * duplicated every effect would pass it. Testing only the second would never exercise the window the
 * criterion actually names.</p>
 *
 * <p>What this does <em>not</em> prove: durability against host failure. A {@code SIGKILL} leaves the
 * page cache intact, so an unsynced commit would still reach the file. That limit is why
 * {@link SqliteStoreConfig.SynchronousMode#FULL} is the default rather than something a kill test was
 * trusted to catch.</p>
 */
class SqliteCrashBetweenCommitAndPublishTest {

    /**
     * Where a failing cell's {@code KillMatrixArtifact} is written. Deliberately <b>not</b>
     * {@code databaseDirectory}: that field is a JUnit {@code @TempDir}, deleted immediately after
     * the test method returns by default, which would delete the artifact at the exact moment a
     * reader needs it. {@code target/} survives until the next {@code mvn clean}, the same lifetime
     * surefire's own reports get.
     */
    private static final Path ARTIFACT_ROOT = Path.of("target");

    @TempDir
    Path databaseDirectory;

    @Test
    void anEventCommittedWithItsTransitionSurvivesACrashBeforeItIsEverPublished() throws Exception {
        Path file = databaseDirectory.resolve("crash-before-publish.db");
        long createdRevision = createInstance(file);
        String mode = KillBetweenCommitAndPublish.BEFORE_PUBLISH;

        KillResult killed = runUntilBoundaryThenKill(file, mode, createdRevision);
        assertTrue(killed.exitCode() != 0, "a SIGKILLed process must not report a clean exit; got "
                + killed.exitCode());
        Path snapshot = KillMatrixArtifact.snapshot(file);

        try {
            try (var recovered = openAt(file)) {
                StoredProcessInstance reloaded = await(recovered.load(KillBetweenCommitAndPublish.KEY));
                assertEquals(ProcessInstanceStatus.RUNNING, reloaded.state().status(),
                        "the transition committed");

                List<JournalRecord> journal =
                        await(recovered.readJournal(KillBetweenCommitAndPublish.TENANT, 0, 10));
                assertEquals(1, journal.size(),
                        "and its event committed with it: the whole point of one transaction is that "
                                + "these two facts cannot come apart");
                assertEquals(KillBetweenCommitAndPublish.EVENT_ID, journal.get(0).envelope().eventId());
                assertEquals(reloaded.revision(), journal.get(0).committedAtRevision(),
                        "the event names the exact revision the transition landed at");
                assertTrue(journal.get(0).envelope().digestMatchesContent(),
                        "and its digest still describes it after an unclean shutdown");
                assertEquals(KillBetweenCommitAndPublish.CAUSATION_ID, journal.get(0).envelope().causationId(),
                        "causality survives the crash, not merely the identity");

                assertEquals(0L, await(recovered.outboxCursor(KillBetweenCommitAndPublish.TENANT,
                        KillBetweenCommitAndPublish.DESTINATION)).deliveredThrough(),
                        "nothing was published, so nothing may claim to have been");
                assertEquals(0L, await(recovered.inboxRecordCount(KillBetweenCommitAndPublish.TENANT)));

                // Recovery: exactly one delivery, exactly one effect.
                assertEquals(1, publishOnce(recovered),
                        "the event lost in the crash window is delivered after recovery — no loss");
                assertEquals(1L, await(recovered.inboxRecordCount(KillBetweenCommitAndPublish.TENANT)));
                assertEquals(0, publishOnce(recovered), "and it is not delivered a second time");
            }
        } catch (AssertionError failure) {
            throw withArtifact(failure, "PUBLISH/" + mode, snapshot, killed, file, mode, createdRevision);
        }
    }

    @Test
    void aCrashBeforeTheCursorAdvancesRedeliversAndTheInboxRefusesTheDuplicateEffect() throws Exception {
        Path file = databaseDirectory.resolve("crash-before-advance.db");
        long createdRevision = createInstance(file);
        String mode = KillBetweenCommitAndPublish.BEFORE_CURSOR_ADVANCE;

        KillResult killed = runUntilBoundaryThenKill(file, mode, createdRevision);
        assertTrue(killed.exitCode() != 0, "a SIGKILLed process must not report a clean exit; got "
                + killed.exitCode());
        Path snapshot = KillMatrixArtifact.snapshot(file);

        try {
            try (var recovered = openAt(file)) {
                assertEquals(1L, await(recovered.inboxRecordCount(KillBetweenCommitAndPublish.TENANT)),
                        "the consumer's effect was applied before the kill and is durable");
                assertEquals(0L, await(recovered.outboxCursor(KillBetweenCommitAndPublish.TENANT,
                        KillBetweenCommitAndPublish.DESTINATION)).deliveredThrough(),
                        "but the cursor never advanced, which is exactly the state that forces a redelivery");

                JournalCursor cursor = await(recovered.outboxCursor(KillBetweenCommitAndPublish.TENANT,
                        KillBetweenCommitAndPublish.DESTINATION));
                List<JournalRecord> redelivered = await(recovered.readJournal(
                        KillBetweenCommitAndPublish.TENANT, cursor.deliveredThrough(), 10));
                assertEquals(1, redelivered.size(),
                        "the event IS redelivered: that is what at-least-once means, and pretending "
                                + "otherwise would be claiming a guarantee no publisher can keep");
                assertEquals(KillBetweenCommitAndPublish.EVENT_ID, redelivered.get(0).envelope().eventId());
                assertEquals(KillBetweenCommitAndPublish.envelope().digest(),
                        redelivered.get(0).envelope().digest(),
                        "and it digests identically, so the consumer can recognise it as the same event "
                                + "rather than as a new one that happens to look similar");

                assertFalse(await(recovered.recordInboxDelivery(KillBetweenCommitAndPublish.TENANT,
                        KillBetweenCommitAndPublish.CONSUMER, redelivered.get(0).envelope().eventId(),
                        KillBetweenCommitAndPublish.INBOX_RETENTION)),
                        "the inbox refuses it, so the crash cost a duplicate DELIVERY and not a duplicate "
                                + "EFFECT — which is the whole of 'without duplicate effects'");
                assertEquals(1L, await(recovered.inboxRecordCount(KillBetweenCommitAndPublish.TENANT)),
                        "still exactly one effect after the redelivery");

                await(recovered.advanceOutboxCursor(cursor, redelivered.get(0).journalOffset()));
                assertEquals(0, publishOnce(recovered), "and now the destination is genuinely caught up");
            }
        } catch (AssertionError failure) {
            throw withArtifact(failure, "PUBLISH/" + mode, snapshot, killed, file, mode, createdRevision);
        }
    }

    /** Appends the {@code KillMatrixArtifact} location to a failing assertion and rethrows it. */
    private AssertionError withArtifact(AssertionError failure, String cell, Path snapshot, KillResult killed,
                                        Path liveFile, String mode, long expectedRevision) {
        long observed = Fixtures.bestEffortRevision(snapshot,
                Clock.fixed(KillBetweenCommitAndPublish.NOW, ZoneOffset.UTC), KillBetweenCommitAndPublish.KEY);
        String artifactMessage = KillMatrixArtifact.write(ARTIFACT_ROOT, cell, snapshot, killed.transcript(),
                rerunCommand(liveFile, mode, expectedRevision), expectedRevision, observed);
        return new AssertionError(failure.getMessage() + artifactMessage, failure);
    }

    private static List<String> rerunCommand(Path file, String mode, long expectedRevision) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                KillBetweenCommitAndPublish.class.getName(), file.toString(), mode,
                Long.toString(expectedRevision));
    }

    /** One publisher pass: read from the cursor, deliver through the inbox, then advance. */
    private int publishOnce(SqliteExecutionStore store) {
        JournalCursor cursor = await(store.outboxCursor(KillBetweenCommitAndPublish.TENANT,
                KillBetweenCommitAndPublish.DESTINATION));
        List<JournalRecord> batch =
                await(store.readJournal(KillBetweenCommitAndPublish.TENANT, cursor.deliveredThrough(), 10));
        if (batch.isEmpty()) {
            return 0;
        }
        for (JournalRecord record : batch) {
            await(store.recordInboxDelivery(KillBetweenCommitAndPublish.TENANT,
                    KillBetweenCommitAndPublish.CONSUMER, record.envelope().eventId(),
                    KillBetweenCommitAndPublish.INBOX_RETENTION));
        }
        await(store.advanceOutboxCursor(cursor, batch.get(batch.size() - 1).journalOffset()));
        return batch.size();
    }

    private long createInstance(Path file) {
        try (var store = openAt(file)) {
            return await(store.apply(Fixtures.creationBatch(KillBetweenCommitAndPublish.KEY,
                    KillBetweenCommitAndPublish.TRAVERSAL_ID))).revision();
        }
    }

    private SqliteExecutionStore openAt(Path file) {
        return new SqliteExecutionStore(file, Clock.fixed(KillBetweenCommitAndPublish.NOW, ZoneOffset.UTC));
    }

    /**
     * Runs the child, waits for it to announce the boundary, kills it and returns its exit code.
     *
     * <p>Every step is asserted rather than assumed: the announcement must arrive, the child must
     * actually die, and it must not have printed {@code COMPLETED}. A kill test whose child finished
     * normally would otherwise report a pass having tested nothing.</p>
     */
    /** The child's exit code and full transcript, both needed later if the cell fails and an artifact is written. */
    private record KillResult(int exitCode, List<String> transcript) {
    }

    private KillResult runUntilBoundaryThenKill(Path file, String mode, long expectedRevision) throws Exception {
        var command = new ArrayList<>(rerunCommand(file, mode, expectedRevision));
        Process child = new ProcessBuilder(command).redirectErrorStream(true).start();

        var transcript = new ArrayList<String>();
        Optional<String> boundary;
        try (var output = new BufferedReader(new InputStreamReader(child.getInputStream(),
                StandardCharsets.UTF_8))) {
            boundary = readUntilBoundary(output, transcript);
        } finally {
            child.destroyForcibly();
        }

        assertTrue(boundary.isPresent(),
                "the child never reached the " + mode + " boundary; transcript: " + transcript);
        assertFalse(transcript.contains(KillBetweenCommitAndPublish.COMPLETED),
                "the child completed instead of parking at the boundary, so nothing was killed and "
                        + "this test would have proved nothing; transcript: " + transcript);
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the child survived destroyForcibly()");
        return new KillResult(child.exitValue(), List.copyOf(transcript));
    }

    private static Optional<String> readUntilBoundary(BufferedReader output, List<String> transcript)
            throws Exception {
        String line;
        while ((line = output.readLine()) != null) {
            transcript.add(line);
            if (KillBetweenCommitAndPublish.AT_BOUNDARY.equals(line)) {
                return Optional.of(line);
            }
        }
        return Optional.empty();
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
