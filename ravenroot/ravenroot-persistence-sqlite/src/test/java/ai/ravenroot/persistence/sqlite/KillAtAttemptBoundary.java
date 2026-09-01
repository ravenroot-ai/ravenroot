package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.RevisionExpectation;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The program {@link SqliteKillAtAttemptBoundaryTest} forks and kills with {@code SIGKILL}, once per
 * lifecycle transition of a node attempt (PERS-04 documented requirements: "process terminated at every
 * transition and restarted without loss").
 *
 * <p>{@link KillAtCommitBoundary} already proves the commit boundary itself, on a batch chosen to
 * span three tables. This one varies the <em>transition</em> rather than the table, because PERS-04's
 * guarantee is stated in terms of the four things it promises to persist — intention, dispatch,
 * result and the disposition of ambiguity — and a kill test that only ever interrupts one of them
 * proves the other three by analogy rather than by evidence.</p>
 *
 * <p>The parent drives the database to each stage's precondition in its own process and then forks,
 * so the child applies exactly one batch and parks at exactly one boundary. Identities are constants
 * so the parent asserts against them without parsing anything the child printed: what a killed
 * process says about its own state is worth nothing, and only the file is evidence.</p>
 */
public final class KillAtAttemptBoundary {

    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    static final String TENANT = "acme";
    static final UUID INSTANCE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    static final UUID TRAVERSAL_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    static final UUID INVOCATION_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    static final UUID ATTEMPT_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    static final ExecutionKey KEY = new ExecutionKey(TENANT, INSTANCE_ID);
    static final String PARK_CAUSE = "dispatched with unknown outcome";
    static final Duration TTL = Duration.ofSeconds(30);

    static final String AT_BOUNDARY = "AT_BOUNDARY";
    static final String COMPLETED = "UNEXPECTEDLY_COMPLETED";
    static final String BEFORE_COMMIT = "before";
    static final String AFTER_COMMIT = "after";

    /** The four things PERS-04 promises to persist, one batch each. */
    enum Stage {
        /** Intention: the invocation exists and its first attempt is SCHEDULED, therefore claimable. */
        INTENTION,
        /** Dispatch: the attempt goes RUNNING. This is the write that must precede the engine send. */
        DISPATCH,
        /** Ambiguity disposed of: the attempt is parked with its cause. */
        PARK,
        /** Result: the attempt completes and its invocation with it. */
        RESULT
    }

    private KillAtAttemptBoundary() {
    }

    /** The single batch the child applies, per stage. */
    static ExecutionBatch batch(Stage stage, long expectedRevision) {
        var builder = ExecutionBatch.to(KEY).expecting(RevisionExpectation.exactly(expectedRevision));
        return switch (stage) {
            case INTENTION -> builder
                    .apply(new ExecutionTransition.InvocationAdded(TRAVERSAL_ID,
                            new NodeInvocation(INVOCATION_ID, "mail.send", null,
                                    NodeInvocationStatus.SCHEDULED)))
                    .apply(new ExecutionTransition.InvocationTransitioned(TRAVERSAL_ID, INVOCATION_ID,
                            NodeInvocationStatus.RUNNING))
                    .apply(new ExecutionTransition.AttemptAdded(TRAVERSAL_ID, INVOCATION_ID,
                            new NodeAttempt(ATTEMPT_ID, 1, NodeAttemptStatus.SCHEDULED)))
                    .build();
            case DISPATCH -> builder
                    .apply(new ExecutionTransition.AttemptTransitioned(TRAVERSAL_ID, INVOCATION_ID,
                            ATTEMPT_ID, NodeAttemptStatus.RUNNING))
                    .build();
            case PARK -> builder
                    .apply(new ExecutionTransition.AttemptParked(TRAVERSAL_ID, INVOCATION_ID, ATTEMPT_ID,
                            PARK_CAUSE))
                    .build();
            case RESULT -> builder
                    .apply(new ExecutionTransition.AttemptTransitioned(TRAVERSAL_ID, INVOCATION_ID,
                            ATTEMPT_ID, NodeAttemptStatus.COMPLETED))
                    .apply(new ExecutionTransition.InvocationTransitioned(TRAVERSAL_ID, INVOCATION_ID,
                            NodeInvocationStatus.COMPLETED))
                    .build();
        };
    }

    /** Brings a fresh database to the precondition of {@code stage}; returns the revision then current. */
    static long driveToPreconditionOf(SqliteExecutionStore store, Stage stage) {
        long revision = store.apply(Fixtures.creationBatch(KEY, TRAVERSAL_ID))
                .toCompletableFuture().join().revision();
        revision = store.apply(ExecutionBatch.to(KEY)
                .expecting(RevisionExpectation.exactly(revision))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(TRAVERSAL_ID, TraversalStatus.RUNNING))
                .build()).toCompletableFuture().join().revision();
        if (stage == Stage.INTENTION) {
            return revision;
        }
        revision = store.apply(batch(Stage.INTENTION, revision)).toCompletableFuture().join().revision();
        if (stage == Stage.DISPATCH) {
            return revision;
        }
        revision = store.apply(batch(Stage.DISPATCH, revision)).toCompletableFuture().join().revision();
        return revision;
    }

    public static void main(String[] args) {
        Path databaseFile = Path.of(args[0]);
        Stage stage = Stage.valueOf(args[1]);
        String mode = args[2];
        long expectedRevision = Long.parseLong(args[3]);

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
        store.apply(batch(stage, expectedRevision)).toCompletableFuture().join();
        System.out.println(COMPLETED);
        System.out.flush();
    }

    private static void parkForever() {
        System.out.println(AT_BOUNDARY);
        System.out.flush();
        try {
            // Long enough that a parent which failed to kill us hits its own timeout and reports that,
            // rather than this process quietly finishing the commit and inventing a pass.
            Thread.sleep(Duration.ofMinutes(10));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
