package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.RevisionExpectation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/** Aggregate shapes shared by the adapter's own tests and by the JVMs they fork. */
final class Fixtures {

    private Fixtures() {
    }

    static ProcessInstance acceptedInstance(UUID instanceId, UUID traversalId) {
        return new ProcessInstance(instanceId, ProcessInstanceStatus.ACCEPTED,
                Map.of(traversalId, new Traversal(traversalId, "start", TraversalStatus.ACCEPTED, Map.of())));
    }

    static ExecutionBatch creationBatch(ExecutionKey key, UUID traversalId) {
        return ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(
                        acceptedInstance(key.processInstanceId(), traversalId), new GraphVersionPin("graph-v1")))
                .build();
    }

    static OpaquePayload fingerprint(String value) {
        return OpaquePayload.of(value.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    /**
     * The revision a snapshot database reports for {@code key}, or {@code -1} if it cannot be read.
     *
     * <p>Used only to fill in a {@code KillMatrixArtifact}'s "observed revision" field when an
     * assertion has already failed; a snapshot that cannot be read at all is itself informative (the
     * artifact's database file says so), so this must not throw and replace the real failure.</p>
     */
    static long bestEffortRevision(Path snapshotDatabase, Clock clock, ExecutionKey key) {
        try (var store = new SqliteExecutionStore(snapshotDatabase, clock)) {
            return store.load(key).toCompletableFuture().join().revision();
        } catch (RuntimeException unreadable) {
            return -1L;
        }
    }
}
