package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.RevisionExpectation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Aggregate shapes and await helpers shared by this adapter's tests and by the JVMs they fork. */
final class PostgresExecutionStoreFixtures {

    private PostgresExecutionStoreFixtures() {
    }

    static ProcessInstance acceptedInstance(UUID instanceId, UUID traversalId) {
        return new ProcessInstance(instanceId, ProcessInstanceStatus.ACCEPTED,
                Map.of(traversalId, new Traversal(traversalId, "start", TraversalStatus.ACCEPTED, Map.of())));
    }

    static ExecutionBatch creationBatch(ExecutionKey key, UUID traversalId) {
        return ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(
                        acceptedInstance(key.processInstanceId(), traversalId),
                        new GraphVersionPin("graph-v1")))
                .build();
    }

    static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    /**
     * The classified failure of an operation that must fail, with the port's own no-leak rule asserted
     * on the way past: an adapter that let a driver exception escape would otherwise be discovered by
     * whichever caller happened to catch the wrong type.
     */
    static ExecutionStoreFailure failureOf(Runnable operation) {
        CompletionException thrown = assertThrows(CompletionException.class, operation::run);
        ExecutionStoreException failure = ExecutionStoreException.unwrap(thrown);
        assertNotNull(failure, "adapters must not leak non-store exceptions: " + thrown);
        return failure.failure();
    }
}
