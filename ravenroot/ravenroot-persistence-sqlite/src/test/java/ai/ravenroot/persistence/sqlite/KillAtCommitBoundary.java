package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.IdempotencyWrite;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.TimerSchedule;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The program {@link SqliteKillAtCommitBoundaryTest} forks and then kills with {@code SIGKILL}.
 *
 * <p>It writes one batch that touches three different tables — the aggregate, the timer record and
 * the idempotency record — parks at the requested commit boundary, and announces on stdout that it is
 * there. Everything after that announcement is the parent's decision: this program never exits on its
 * own, so a run that reaches {@link #COMPLETED} means the kill did not happen and the test is invalid
 * rather than passing.</p>
 *
 * <p>Identities are constants rather than arguments so the parent can assert against them without
 * parsing anything the child printed. What the child says about its own state after being killed is
 * worth nothing; only the file is evidence.</p>
 */
public final class KillAtCommitBoundary {

    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    static final String TENANT = "acme";
    static final UUID INSTANCE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID TRAVERSAL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID TIMER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final String IDEMPOTENCY_KEY = "killed-batch";
    static final ExecutionKey KEY = new ExecutionKey(TENANT, INSTANCE_ID);

    static final String AT_BOUNDARY = "AT_BOUNDARY";
    static final String COMPLETED = "UNEXPECTEDLY_COMPLETED";
    static final String BEFORE_COMMIT = "before";
    static final String AFTER_COMMIT = "after";

    private KillAtCommitBoundary() {
    }

    /** The batch under test: three tables, one transaction, one commit. */
    static ExecutionBatch batch(long expectedRevision) {
        return ExecutionBatch.to(KEY)
                .expecting(RevisionExpectation.exactly(expectedRevision))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .scheduleTimer(new TimerSchedule(TIMER_ID, NOW, TRAVERSAL_ID, null,
                        OpaquePayload.of("wake".getBytes(StandardCharsets.UTF_8), "text/plain")))
                .recordIdempotency(new IdempotencyWrite(IDEMPOTENCY_KEY,
                        OpaquePayload.of("request".getBytes(StandardCharsets.UTF_8), "text/plain"),
                        OpaquePayload.of("outcome".getBytes(StandardCharsets.UTF_8), "text/plain"),
                        Duration.ofMinutes(10), NOW))
                .build();
    }

    public static void main(String[] args) {
        Path databaseFile = Path.of(args[0]);
        String mode = args[1];
        long expectedRevision = Long.parseLong(args[2]);

        CommitBoundary boundary = AFTER_COMMIT.equals(mode)
                ? new CommitBoundary() {
                    @Override
                    public void afterCommit() {
                        parkForever();
                    }
                }
                : new CommitBoundary() {
                    @Override
                    public void beforeCommit() {
                        parkForever();
                    }
                };

        var store = new SqliteExecutionStore(databaseFile, Clock.fixed(NOW, ZoneOffset.UTC),
                SqliteStoreConfig.defaults(), boundary);
        store.apply(batch(expectedRevision)).toCompletableFuture().join();
        System.out.println(COMPLETED);
        System.out.flush();
    }

    private static void parkForever() {
        System.out.println(AT_BOUNDARY);
        System.out.flush();
        try {
            // Long enough that a parent which failed to kill us hits its own timeout and reports
            // that, rather than this process quietly finishing the commit and inventing a pass.
            Thread.sleep(Duration.ofMinutes(10));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
