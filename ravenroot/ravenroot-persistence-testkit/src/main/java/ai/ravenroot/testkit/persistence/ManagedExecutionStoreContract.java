package ai.ravenroot.testkit.persistence;

import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.execution.NodeCommand;
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
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.ResolvedOperationalPolicy;
import ai.ravenroot.api.persistence.ResolvedRuntimeProfile;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.TimerSchedule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    final void individualManagedClaimsRefuseMissingStaleAndLegacyAuthorityWithoutLeasing() {
        int live = bundle().executionStore().maxPayloadBytes();

        ExecutionKey missing = new ExecutionKey("claim-missing", UUID.randomUUID());
        var missingAuthority = new ExecutionPersistenceAuthority(manifest(missing, live).digest(), live);
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                failureOf(() -> await(bundle().executionStore().claimManaged(
                        missing, "worker-1", java.time.Duration.ofSeconds(5), missingAuthority))));
        assertTrue(await(bundle().executionStore().leases(missing.tenantId())).isEmpty());

        ManagedFixture stale = managedPendingWork("claim-stale");
        var staleAuthority = new ExecutionPersistenceAuthority(
                new ai.ravenroot.api.persistence.ExecutionManifestDigest("f".repeat(64)), live);
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                failureOf(() -> await(bundle().executionStore().claimManaged(
                        stale.key(), "worker-1", java.time.Duration.ofSeconds(5), staleAuthority))));
        assertTrue(await(bundle().executionStore().leases(stale.key().tenantId())).isEmpty());

        ExecutionKey legacy = new ExecutionKey("claim-legacy", UUID.randomUUID());
        ExecutionManifest legacyManifest = legacyManifest(legacy);
        await(bundle().manifestStore().pin(legacyManifest));
        await(bundle().executionStore().apply(creationBatch(legacy)));
        var legacyAuthority = new ExecutionPersistenceAuthority(legacyManifest.digest(), live);
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                failureOf(() -> await(bundle().executionStore().claimManaged(
                        legacy, "worker-1", java.time.Duration.ofSeconds(5), legacyAuthority))));
        assertTrue(await(bundle().executionStore().leases(legacy.tenantId())).isEmpty());
    }

    @Test
    final void individualManagedClaimRefusesReopenedCapacityDriftWithoutLeasing() {
        String id = "managed-claim-drift-" + UUID.randomUUID();
        ExecutionKey key = new ExecutionKey("claim-drift", UUID.randomUUID());
        ExecutionPersistenceAuthority authority;
        int acceptedCapacity;
        try (var accepted = open(id, Clock.systemUTC())) {
            acceptedCapacity = accepted.executionStore().maxPayloadBytes();
            var stored = await(accepted.manifestStore().pin(manifest(key, acceptedCapacity)));
            authority = ExecutionPersistenceAuthority.from(stored);
            await(accepted.executionStore().applyManaged(creationBatch(key), authority));
        }
        int changedCapacity = acceptedCapacity == Integer.MAX_VALUE ? acceptedCapacity - 1
                : acceptedCapacity + 1;
        try (var changed = open(id, Clock.systemUTC(), changedCapacity)) {
            assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                    failureOf(() -> await(changed.executionStore().claimManaged(
                            key, "worker-1", java.time.Duration.ofSeconds(5), authority))));
            assertTrue(await(changed.executionStore().leases(key.tenantId())).isEmpty());
        }
    }

    @Test
    final void restrictedPendingWorkClaimsAreAtomicAndExcludeUnverifiedNewKeys() {
        assertRestrictedClaimsAreAtomicAndExcludeUnverifiedNewKeys(false);
    }

    @Test
    final void restrictedDueTimerClaimsAreAtomicAndExcludeUnverifiedNewKeys() {
        assertRestrictedClaimsAreAtomicAndExcludeUnverifiedNewKeys(true);
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
        return creationBatch(key, UUID.randomUUID());
    }

    private static ExecutionBatch creationBatch(ExecutionKey key, UUID traversal) {
        ProcessInstance instance = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                Map.of(traversal, new Traversal(traversal, "start", TraversalStatus.ACCEPTED, Map.of())));
        return ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(instance, new GraphVersionPin("graph-v1"))).build();
    }

    private void assertRestrictedClaimsAreAtomicAndExcludeUnverifiedNewKeys(boolean timersOnly) {
        String kind = timersOnly ? "timer" : "work";
        ManagedFixture valid = managedClaimable(kind + "-negative", timersOnly);
        ManagedFixture wrongTenant = managedClaimable(kind + "-other-tenant", timersOnly);
        ManagedFixture stale = managedClaimable(kind + "-negative", timersOnly);
        ManagedFixture badCapacity = managedClaimable(kind + "-negative", timersOnly);
        int live = bundle().executionStore().maxPayloadBytes();

        assertRestrictedClaimRefuses(valid.key().tenantId(), timersOnly, Map.of(
                valid.key(), valid.authority(), wrongTenant.key(), wrongTenant.authority()));
        assertTrue(await(bundle().executionStore().leases(valid.key().tenantId())).isEmpty());
        assertTrue(await(bundle().executionStore().leases(wrongTenant.key().tenantId())).isEmpty());

        var staleAuthority = new ExecutionPersistenceAuthority(
                new ai.ravenroot.api.persistence.ExecutionManifestDigest("e".repeat(64)), live);
        assertRestrictedClaimRefuses(valid.key().tenantId(), timersOnly, Map.of(
                valid.key(), valid.authority(), stale.key(), staleAuthority));
        assertTrue(await(bundle().executionStore().leases(valid.key().tenantId())).isEmpty());

        int incompatible = live == Integer.MAX_VALUE ? live - 1 : live + 1;
        var badCapacityAuthority = new ExecutionPersistenceAuthority(
                badCapacity.authority().manifestDigest(), incompatible);
        assertRestrictedClaimRefuses(valid.key().tenantId(), timersOnly, Map.of(
                valid.key(), valid.authority(), badCapacity.key(), badCapacityAuthority));
        assertTrue(await(bundle().executionStore().leases(valid.key().tenantId())).isEmpty());

        String positiveTenant = kind + "-positive";
        ManagedFixture verified = managedClaimable(positiveTenant, timersOnly);
        var candidates = await(bundle().executionStore().managedClaimCandidates(
                positiveTenant, "worker-1", 10, java.time.Duration.ofSeconds(5), timersOnly, Optional.empty()));
        assertEquals(List.of(verified.key()), candidates.keys());
        ManagedFixture arrivedAfterVerification = managedClaimable(positiveTenant, timersOnly);

        List<? extends PendingWork> claimed = restrictedClaim(
                positiveTenant, timersOnly, Map.of(verified.key(), verified.authority()));
        assertEquals(List.of(verified.key()), claimed.stream().map(PendingWork::key).toList());
        assertTrue(await(bundle().executionStore().leases(positiveTenant)).stream()
                .noneMatch(lease -> lease.key().equals(arrivedAfterVerification.key())));

        List<? extends PendingWork> later = restrictedClaim(positiveTenant, timersOnly,
                Map.of(arrivedAfterVerification.key(), arrivedAfterVerification.authority()));
        assertEquals(List.of(arrivedAfterVerification.key()), later.stream().map(PendingWork::key).toList());
    }

    private void assertRestrictedClaimRefuses(String tenantId, boolean timersOnly,
                                               Map<ExecutionKey, ExecutionPersistenceAuthority> authorities) {
        assertInstanceOf(ExecutionStoreFailure.InvalidRequest.class,
                failureOf(() -> restrictedClaim(tenantId, timersOnly, authorities)));
    }

    private List<? extends PendingWork> restrictedClaim(
            String tenantId, boolean timersOnly,
            Map<ExecutionKey, ExecutionPersistenceAuthority> authorities) {
        if (timersOnly) {
            return await(bundle().executionStore().claimDueTimersAmong(
                    tenantId, "worker-1", 10, java.time.Duration.ofSeconds(5), authorities));
        }
        return await(bundle().executionStore().claimPendingWorkAmong(
                tenantId, "worker-1", 10, java.time.Duration.ofSeconds(5), authorities));
    }

    private ManagedFixture managedClaimable(String tenantId, boolean timersOnly) {
        return timersOnly ? managedDueTimer(tenantId) : managedPendingWork(tenantId);
    }

    private ManagedFixture managedPendingWork(String tenantId) {
        ExecutionKey key = new ExecutionKey(tenantId, UUID.randomUUID());
        UUID traversal = UUID.randomUUID();
        UUID invocation = UUID.randomUUID();
        var stored = await(bundle().manifestStore().pin(
                manifest(key, bundle().executionStore().maxPayloadBytes())));
        var authority = ExecutionPersistenceAuthority.from(stored);
        var created = await(bundle().executionStore().applyManaged(creationBatch(key, traversal), authority));
        await(bundle().executionStore().applyManaged(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversal, TraversalStatus.RUNNING))
                .apply(new ExecutionTransition.InvocationAdded(traversal,
                        new NodeInvocation(invocation, "work", Set.of(), NodeInvocationStatus.SCHEDULED,
                                List.of(), NodeCommand.PROCESS)))
                .apply(new ExecutionTransition.InvocationTransitioned(
                        traversal, invocation, NodeInvocationStatus.RUNNING))
                .apply(new ExecutionTransition.AttemptAdded(traversal, invocation,
                        new NodeAttempt(UUID.randomUUID(), 1, NodeAttemptStatus.SCHEDULED)))
                .build(), authority));
        return new ManagedFixture(key, authority);
    }

    private ManagedFixture managedDueTimer(String tenantId) {
        ExecutionKey key = new ExecutionKey(tenantId, UUID.randomUUID());
        UUID traversal = UUID.randomUUID();
        var stored = await(bundle().manifestStore().pin(
                manifest(key, bundle().executionStore().maxPayloadBytes())));
        var authority = ExecutionPersistenceAuthority.from(stored);
        var created = await(bundle().executionStore().applyManaged(creationBatch(key, traversal), authority));
        await(bundle().executionStore().applyManaged(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .scheduleTimer(new TimerSchedule(UUID.randomUUID(), NOW, traversal, null,
                        OpaquePayload.empty("application/octet-stream")))
                .build(), authority));
        return new ManagedFixture(key, authority);
    }

    private record ManagedFixture(ExecutionKey key, ExecutionPersistenceAuthority authority) {}

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
