package ai.ravenroot.testkit.persistence;

import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionManifest;
import ai.ravenroot.api.persistence.ExecutionManifestStore;
import ai.ravenroot.api.persistence.ExecutionPersistenceAuthority;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.IdempotencyWrite;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.ResolvedOperationalPolicy;
import ai.ravenroot.api.persistence.ResolvedRuntimeProfile;
import ai.ravenroot.api.persistence.RevisionExpectation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Executable contract for adapters that atomically bind managed writes to format-3 manifests. */
public abstract class ManagedExecutionStoreContract {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private Bundle bundle;

    /** Opens both ports over the same actual persistence resource. */
    protected abstract Bundle open(String id, Clock clock);

    /** Reopens the same resource with a different immutable generic payload capacity. */
    protected abstract Bundle open(String id, Clock clock, int maximumPayloadBytes);

    protected final Bundle bundle() {
        if (bundle == null) bundle = open("managed-" + UUID.randomUUID(), Clock.systemUTC());
        return bundle;
    }

    @AfterEach
    final void closeBundle() {
        if (bundle != null) bundle.close();
    }

    @Test
    final void exactFormatThreeAuthorityCreatesAndClaimsAnExecution() {
        ExecutionKey key = new ExecutionKey("acme", UUID.randomUUID());
        ExecutionManifest manifest = manifest(key, bundle().executionStore().maxPayloadBytes());
        var stored = await(bundle().manifestStore().pin(manifest));
        var authority = ExecutionPersistenceAuthority.from(stored);

        assertEquals(1L, await(bundle().executionStore().applyManaged(
                creationBatch(key), authority)).revision());
        assertEquals(key, await(bundle().executionStore().claimManaged(
                key, "worker-1", java.time.Duration.ofSeconds(5), authority)).key());
    }

    @Test
    final void missingStaleAndLegacyAuthoritiesRefuseBeforeCreatingAProcess() {
        ExecutionKey missing = new ExecutionKey("acme", UUID.randomUUID());
        var invented = new ExecutionPersistenceAuthority(
                manifest(missing, bundle().executionStore().maxPayloadBytes()).digest(),
                bundle().executionStore().maxPayloadBytes());
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                failureOf(() -> await(bundle().executionStore().applyManaged(creationBatch(missing), invented))));

        ExecutionKey stale = new ExecutionKey("acme", UUID.randomUUID());
        var stored = await(bundle().manifestStore().pin(
                manifest(stale, bundle().executionStore().maxPayloadBytes())));
        var wrong = new ExecutionPersistenceAuthority(new ai.ravenroot.api.persistence.ExecutionManifestDigest(
                "f".repeat(64)), bundle().executionStore().maxPayloadBytes());
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                failureOf(() -> await(bundle().executionStore().applyManaged(creationBatch(stale), wrong))));

        ExecutionKey legacy = new ExecutionKey("acme", UUID.randomUUID());
        await(bundle().manifestStore().pin(legacyManifest(legacy)));
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                failureOf(() -> await(bundle().executionStore().applyManaged(creationBatch(legacy), invented))));

        assertInstanceOf(ExecutionStoreFailure.NotFound.class,
                failureOf(() -> await(bundle().executionStore().load(missing))));
        assertInstanceOf(ExecutionStoreFailure.NotFound.class,
                failureOf(() -> await(bundle().executionStore().load(stale))));
        assertInstanceOf(ExecutionStoreFailure.NotFound.class,
                failureOf(() -> await(bundle().executionStore().load(legacy))));
    }

    @Test
    final void cleanupThatWinsBeforeCreationLeavesNoAuthorityToCreateTheProcess() {
        ExecutionKey key = new ExecutionKey("acme", UUID.randomUUID());
        var stored = await(bundle().manifestStore().pin(
                manifest(key, bundle().executionStore().maxPayloadBytes())));
        var authority = ExecutionPersistenceAuthority.from(stored);
        await(bundle().manifestStore().remove(key));

        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                failureOf(() -> await(bundle().executionStore().applyManaged(creationBatch(key), authority))));
        assertInstanceOf(ExecutionStoreFailure.NotFound.class,
                failureOf(() -> await(bundle().executionStore().load(key))));
    }

    @Test
    final void incompatibleCapacityAndEmptyVerifiedClaimSetNeverFallThrough() {
        ExecutionKey key = new ExecutionKey("acme", UUID.randomUUID());
        int live = bundle().executionStore().maxPayloadBytes();
        int incompatible = live == Integer.MAX_VALUE ? live - 1 : live + 1;
        var stored = await(bundle().manifestStore().pin(manifest(key, incompatible)));
        var authority = ExecutionPersistenceAuthority.from(stored);

        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                failureOf(() -> await(bundle().executionStore().applyManaged(creationBatch(key), authority))));
        assertEquals(List.of(), await(bundle().executionStore().claimPendingWorkAmong(
                "acme", "worker-1", 10, java.time.Duration.ofSeconds(5), Map.of())));
        assertInstanceOf(ExecutionStoreFailure.NotFound.class,
                failureOf(() -> await(bundle().executionStore().load(key))));
    }

    @Test
    final void fencingThenMatchingReplayPrecedeAChangedLiveCapacityCheck() {
        String id = "managed-replay-" + UUID.randomUUID();
        ExecutionKey key = new ExecutionKey("acme", UUID.randomUUID());
        int acceptedCapacity;
        ExecutionPersistenceAuthority authority;
        ExecutionBatch first;
        long fencingToken;
        try (var accepted = open(id, Clock.systemUTC())) {
            acceptedCapacity = accepted.executionStore().maxPayloadBytes();
            var stored = await(accepted.manifestStore().pin(manifest(key, acceptedCapacity)));
            authority = ExecutionPersistenceAuthority.from(stored);
            assertEquals(1L, await(accepted.executionStore().applyManaged(
                    creationBatch(key), authority)).revision());
            var lease = await(accepted.executionStore().claimManaged(
                    key, "worker-1", java.time.Duration.ofSeconds(5), authority));
            fencingToken = lease.fencingToken();
            var idempotency = new IdempotencyWrite("managed-replay",
                    OpaquePayload.of("request".getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain"),
                    OpaquePayload.of("outcome".getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain"),
                    java.time.Duration.ofMinutes(5), Instant.now());
            first = ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(1))
                    .fencedBy(lease).apply(new ExecutionTransition.ProcessTransitioned(
                            ProcessInstanceStatus.RUNNING)).recordIdempotency(idempotency).build();
            assertEquals(2L, await(accepted.executionStore().applyManaged(first, authority)).revision());
        }

        int changedCapacity = acceptedCapacity == Integer.MAX_VALUE ? acceptedCapacity - 1
                : acceptedCapacity + 1;
        try (var changed = open(id, Clock.systemUTC(), changedCapacity)) {
            assertEquals(2L, await(changed.executionStore().applyManaged(first, authority)).revision(),
                    "a matching committed replay must not be folded again or rejected as new work");

            ExecutionBatch staleFence = ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(1))
                    .fencedBy(fencingToken + 1).apply(new ExecutionTransition.ProcessTransitioned(
                            ProcessInstanceStatus.RUNNING)).recordIdempotency(first.idempotency().orElseThrow())
                    .build();
            assertInstanceOf(ExecutionStoreFailure.FencedOut.class,
                    failureOf(() -> await(changed.executionStore().applyManaged(staleFence, authority))));
        }
    }

    protected static ExecutionManifest manifest(ExecutionKey key, int persistenceCapacity) {
        ResolvedOperationalPolicy policy = new ResolvedOperationalPolicy(graph(),
                new ResolvedOperationalPolicy.ResultLimits(false, 4096), Optional.empty(), List.of(),
                Optional.of(new ResolvedOperationalPolicy.PersistenceLimits(persistenceCapacity)));
        return new ExecutionManifest(ExecutionManifest.FORMAT_VERSION_3, key,
                new GraphContentId("a".repeat(64)),
                new GraphDefinitionIdentity(GraphDefinitionIdentity.SUBMISSION_GRAPH_ID, "a".repeat(64)),
                profile(), List.of(), NOW, policy);
    }

    private static ExecutionManifest legacyManifest(ExecutionKey key) {
        return new ExecutionManifest(ExecutionManifest.FORMAT_VERSION_1, key,
                new GraphContentId("a".repeat(64)),
                new GraphDefinitionIdentity(GraphDefinitionIdentity.SUBMISSION_GRAPH_ID, "a".repeat(64)),
                profile(), List.of(), NOW);
    }

    protected static ExecutionBatch creationBatch(ExecutionKey key) {
        UUID traversal = UUID.randomUUID();
        ProcessInstance instance = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                Map.of(traversal, new Traversal(traversal, "start", TraversalStatus.ACCEPTED, Map.of())));
        return ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(instance, new GraphVersionPin("graph-v1"))).build();
    }

    private static ResolvedRuntimeProfile profile() {
        return new ResolvedRuntimeProfile(1, 1, "STANDARD", "pass-through",
                "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64));
    }

    private static ResolvedOperationalPolicy.GraphLimits graph() {
        return new ResolvedOperationalPolicy.GraphLimits(1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
    }

    protected static <T> T await(java.util.concurrent.CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    protected static ExecutionStoreFailure failureOf(Runnable operation) {
        RuntimeException thrown = assertThrows(RuntimeException.class, operation::run);
        ExecutionStoreException classified = ExecutionStoreException.unwrap(thrown);
        if (classified == null && thrown instanceof CompletionException wrapped) {
            classified = ExecutionStoreException.unwrap(wrapped);
        }
        if (classified == null) throw thrown;
        return classified.failure();
    }

    /** Both adapter ports opened over one physical schema/file. */
    public record Bundle(ExecutionStore executionStore, ExecutionManifestStore manifestStore)
            implements AutoCloseable {
        public Bundle {
            java.util.Objects.requireNonNull(executionStore, "executionStore");
            java.util.Objects.requireNonNull(manifestStore, "manifestStore");
        }

        @Override
        public void close() {
            try {
                manifestStore.close();
            } finally {
                executionStore.close();
            }
        }
    }
}
