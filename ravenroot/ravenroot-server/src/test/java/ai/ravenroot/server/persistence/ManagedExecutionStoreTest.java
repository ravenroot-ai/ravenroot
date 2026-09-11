package ai.ravenroot.server.persistence;

import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionManifest;
import ai.ravenroot.api.persistence.ExecutionManifestStore;
import ai.ravenroot.api.persistence.ExecutionPersistenceAuthority;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.persistence.ManagedClaimCandidatePage;
import ai.ravenroot.api.persistence.ResolvedOperationalPolicy;
import ai.ravenroot.api.persistence.ResolvedRuntimeProfile;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoredExecutionManifest;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedExecutionStoreTest {
    @Test
    void everyExecutionStoreMethodHasAnExplicitManagedRoute() throws Exception {
        var field = ManagedExecutionStore.class.getDeclaredField("SAFE_DELEGATE_METHODS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var safe = (java.util.Set<String>) field.get(null);
        var managedPublic = java.util.Set.of(
                "protectsManagedPersistence", "apply", "claim", "claimPendingWork", "claimDueTimers");
        var managedInternal = java.util.Set.of(
                "applyManaged", "claimManaged", "claimPendingWorkAmong", "claimDueTimersAmong",
                "managedClaimCandidates");
        var routed = new java.util.HashSet<String>(safe);
        routed.addAll(managedPublic);
        routed.addAll(managedInternal);
        var declared = java.util.Arrays.stream(ExecutionStore.class.getMethods())
                .filter(method -> method.getDeclaringClass() != Object.class)
                .map(java.lang.reflect.Method::getName)
                .collect(java.util.stream.Collectors.toSet());

        org.junit.jupiter.api.Assertions.assertEquals(declared, routed,
                "a new port method must be consciously guarded, refused, or delegated");
    }

    @Test
    void matchingReplayAuthorityReachesAdapterBeforeLiveCapacityComparison() {
        ExecutionKey key = key(1);
        StoredExecutionManifest stored = stored(key, 1024, true);
        var called = new AtomicBoolean();
        ExecutionStore delegate = executionStore((method, arguments) -> switch (method.getName()) {
            case "maxPayloadBytes" -> 2048;
            case "applyManaged" -> {
                called.set(true);
                yield CompletableFuture.completedFuture(null);
            }
            default -> defaultStoreValue(method.getName());
        });
        ExecutionStore managed = ManagedExecutionStore.protect(delegate, manifestStore(Map.of(key, stored)));
        assertTrue(managed.protectsManagedPersistence());
        assertFalse(delegate.protectsManagedPersistence());

        managed.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(1))
                        .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING)).build())
                .toCompletableFuture().join();

        assertTrue(called.get(), "the adapter owns fencing/replay precedence before capacity drift");
    }

    @Test
    void invalidClaimIsRejectedBeforeEvenAnEmptyInventoryScan() {
        var scanned = new AtomicBoolean();
        ExecutionStore delegate = executionStore((method, arguments) -> switch (method.getName()) {
            case "maxPayloadBytes" -> 1024;
            case "maxLeaseTtl" -> Duration.ofMinutes(5);
            case "maxInventoryPageSize" -> 100;
            case "managedClaimCandidates" -> {
                scanned.set(true);
                yield CompletableFuture.completedFuture(new ManagedClaimCandidatePage(List.of(), Optional.empty()));
            }
            default -> defaultStoreValue(method.getName());
        });
        ExecutionStore managed = ManagedExecutionStore.protect(delegate, manifestStore(Map.of()));

        assertThrows(CompletionException.class, () -> managed.claimPendingWork(
                "acme", "worker", 0, Duration.ofSeconds(5)).toCompletableFuture().join());
        assertFalse(scanned.get());
    }

    @Test
    void boundedSweepsAdvancePastEightIncompatiblePages() {
        var keys = new ArrayList<ExecutionKey>();
        var manifests = new java.util.LinkedHashMap<ExecutionKey, StoredExecutionManifest>();
        for (int index = 1; index <= 257; index++) {
            ExecutionKey key = key(index);
            keys.add(key);
            manifests.put(key, stored(key, 1024, index == 257));
        }
        var amongCalled = new AtomicBoolean();
        ExecutionStore delegate = executionStore((method, arguments) -> switch (method.getName()) {
            case "maxPayloadBytes" -> 1024;
            case "maxLeaseTtl" -> Duration.ofMinutes(5);
            case "maxInventoryPageSize" -> 32;
            case "managedClaimCandidates" -> {
                @SuppressWarnings("unchecked") Optional<UUID> after = (Optional<UUID>) arguments[5];
                int start = after.map(uuid -> keys.indexOf(new ExecutionKey("acme", uuid)) + 1).orElse(0);
                int end = Math.min(keys.size(), start + (int) arguments[2]);
                List<ExecutionKey> page = List.copyOf(keys.subList(start, end));
                Optional<UUID> next = page.size() == (int) arguments[2]
                        ? Optional.of(page.get(page.size() - 1).processInstanceId()) : Optional.empty();
                yield CompletableFuture.completedFuture(new ManagedClaimCandidatePage(page, next));
            }
            case "claimPendingWorkAmong" -> {
                amongCalled.set(true);
                yield CompletableFuture.completedFuture(List.of());
            }
            default -> defaultStoreValue(method.getName());
        });
        ExecutionStore managed = ManagedExecutionStore.protect(delegate, manifestStore(manifests));

        assertThrows(CompletionException.class, () -> managed.claimPendingWork(
                "acme", "worker", 1, Duration.ofSeconds(5)).toCompletableFuture().join());
        assertFalse(amongCalled.get());
        managed.claimPendingWork("acme", "worker", 1, Duration.ofSeconds(5)).toCompletableFuture().join();
        assertTrue(amongCalled.get(), "the next bounded sweep must resume beyond incompatible keys");
    }

    private static ExecutionStore executionStore(Invocation invocation) {
        return (ExecutionStore) Proxy.newProxyInstance(ExecutionStore.class.getClassLoader(),
                new Class<?>[]{ExecutionStore.class}, (proxy, method, args) -> invocation.invoke(method,
                        args == null ? new Object[0] : args));
    }

    private static ExecutionManifestStore manifestStore(Map<ExecutionKey, StoredExecutionManifest> values) {
        return (ExecutionManifestStore) Proxy.newProxyInstance(ExecutionManifestStore.class.getClassLoader(),
                new Class<?>[]{ExecutionManifestStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("load")) {
                        StoredExecutionManifest stored = values.get(args[0]);
                        return stored == null ? CompletableFuture.failedFuture(new IllegalStateException("absent"))
                                : CompletableFuture.completedFuture(stored);
                    }
                    if (method.getName().equals("close")) return null;
                    throw new AssertionError("unexpected manifest method " + method.getName());
                });
    }

    private static Object defaultStoreValue(String name) {
        if (name.equals("capabilities")) return java.util.Set.of();
        if (name.equals("protectsManagedPersistence")) return false;
        if (name.equals("close")) return null;
        throw new AssertionError("unexpected store method " + name);
    }

    private static StoredExecutionManifest stored(ExecutionKey key, int cap, boolean versionThree) {
        ResolvedOperationalPolicy policy = new ResolvedOperationalPolicy(graph(),
                new ResolvedOperationalPolicy.ResultLimits(false, 4096), Optional.empty(), List.of(),
                versionThree ? Optional.of(new ResolvedOperationalPolicy.PersistenceLimits(cap))
                        : Optional.empty());
        ExecutionManifest manifest = new ExecutionManifest(versionThree
                ? ExecutionManifest.FORMAT_VERSION_3 : ExecutionManifest.FORMAT_VERSION_2, key,
                new GraphContentId("a".repeat(64)),
                new GraphDefinitionIdentity(GraphDefinitionIdentity.SUBMISSION_GRAPH_ID, "a".repeat(64)),
                new ResolvedRuntimeProfile(1, 1, "STANDARD", "pass-through", "1".repeat(64),
                        "2".repeat(64), "3".repeat(64), "4".repeat(64)), List.of(), Instant.EPOCH, policy);
        return new StoredExecutionManifest(manifest, manifest.digest(), Instant.EPOCH);
    }

    private static ResolvedOperationalPolicy.GraphLimits graph() {
        return new ResolvedOperationalPolicy.GraphLimits(1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
    }

    private static ExecutionKey key(int index) {
        return new ExecutionKey("acme", new UUID(0, index));
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] arguments) throws Throwable;
    }
}
