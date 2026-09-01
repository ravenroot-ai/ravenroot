package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Claims the fence from a genuinely separate operating-system process, commits {@code RUNNING} under
 * it (exactly what {@link GraphRunner}'s dispatch does before {@code engine.send}), announces the
 * boundary, then <strong>blocks and waits to be told to proceed</strong> rather than exiting or being
 * killed. This scenario needs a worker that stays alive the whole time: the defect is specifically
 * that the worker never crashes and never learns it lost the fence until it
 * tries to write again, which a killed process (see {@code HoldLeaseUntilKilledInAnotherProcess} in
 * {@code ravenroot-persistence-sqlite}) cannot demonstrate — a dead process makes no further attempt to
 * write at all.
 *
 * <p>The {@code PROCEED} signal on stdin stands in for the node's external call finally returning,
 * after the caller has arranged for another worker to take the fence away in between. Whatever this
 * process observes when it then tries to record completion — refused, or (if some future change broke
 * the guard) wrongly accepted — is printed as one of {@link #WRITE_REFUSED} or {@link #WRITE_SUCCEEDED}
 * and the process then exits normally. Exit code alone cannot carry this: a refusal caught by design is
 * not a crash, so both outcomes must exit cleanly and the transcript is what decides the test.
 */
public final class RecordAcrossFenceLossInAnotherProcess {

    static final String AT_BOUNDARY = "AT_BOUNDARY";
    static final String PROCEED = "PROCEED";
    static final String WRITE_REFUSED = "WRITE_REFUSED ";
    static final String WRITE_SUCCEEDED = "WRITE_SUCCEEDED";

    private RecordAcrossFenceLossInAnotherProcess() {
    }

    public static void main(String[] args) throws Exception {
        Path databaseFile = Path.of(args[0]);
        String tenantId = args[1];
        UUID processInstanceId = UUID.fromString(args[2]);
        UUID traversalId = UUID.fromString(args[3]);
        UUID invocationId = UUID.fromString(args[4]);
        UUID attemptId = UUID.fromString(args[5]);
        Instant now = Instant.parse(args[6]);
        Duration ttl = Duration.parse(args[7]);

        var key = new ExecutionKey(tenantId, processInstanceId);
        // Fixed, not advancing: this process's own clock never learns time has passed, exactly like a
        // live worker whose wall clock is perfectly fine -- what fences it out is not its own clock,
        // it is that another process's clock and the store's persisted lease disagree with it.
        var store = new SqliteExecutionStore(databaseFile, Clock.fixed(now, ZoneOffset.UTC));
        long revision = store.load(key).toCompletableFuture().join().revision();
        ExecutionRecorder recorder = ExecutionRecorder.open(store, key, "worker-in-another-process", ttl, revision);

        // Committed under a fresh, valid fence -- RUNNING before the engine send this
        // stands in for. This is the write ExecutionRecoveryService will later see as "sent, outcome
        // unknown".
        recorder.record(List.of(
                new ExecutionTransition.InvocationAdded(traversalId,
                        new NodeInvocation(invocationId, "work", null, NodeInvocationStatus.SCHEDULED)),
                new ExecutionTransition.InvocationTransitioned(traversalId, invocationId,
                        NodeInvocationStatus.RUNNING),
                new ExecutionTransition.AttemptAdded(traversalId, invocationId,
                        new NodeAttempt(attemptId, 1, NodeAttemptStatus.SCHEDULED)),
                new ExecutionTransition.AttemptTransitioned(traversalId, invocationId, attemptId,
                        NodeAttemptStatus.RUNNING)),
                List.of());

        System.out.println(AT_BOUNDARY);
        System.out.flush();

        try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String signal = reader.readLine();
            if (!PROCEED.equals(signal)) {
                throw new IllegalStateException("expected '" + PROCEED + "' on stdin, got '" + signal + "'");
            }
        }

        // The node's external effect, in this stand-in, has just "finished" -- and only now does this
        // process try to record it. The fence-loss interval lies between the boundary above and here.
        try {
            recorder.record(List.of(
                    new ExecutionTransition.AttemptTransitioned(traversalId, invocationId, attemptId,
                            NodeAttemptStatus.COMPLETED),
                    new ExecutionTransition.InvocationTransitioned(traversalId, invocationId,
                            NodeInvocationStatus.COMPLETED)),
                    List.of());
            System.out.println(WRITE_SUCCEEDED);
        } catch (RuntimeException refused) {
            System.out.println(WRITE_REFUSED + refused.getClass().getName() + ": " + refused.getMessage());
        }
        System.out.flush();
        recorder.close();
    }
}
