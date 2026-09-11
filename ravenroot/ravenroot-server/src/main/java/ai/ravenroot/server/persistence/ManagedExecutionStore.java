package ai.ravenroot.server.persistence;

import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionManifestStore;
import ai.ravenroot.api.persistence.ExecutionPersistenceAuthority;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ManagedClaimCandidatePage;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protects every server-managed execution mutation with the immutable persistence capacity pinned
 * in that execution's manifest.
 *
 * <p>The returned store delegates static descriptions, reads, acknowledgements, lease renewal and
 * lease release unchanged. New process writes and claims first load a verified format-3 manifest,
 * require its generic payload capacity to equal the immutable delegate's capacity, and pass the
 * manifest digest and capacity into an adapter method that revalidates them atomically with the
 * mutation. An adapter without that atomic seam refuses through the port's default methods; this
 * wrapper never downgrades to an unmanaged call.</p>
 *
 * <p>Bulk recovery examines bounded pages in process-id order. It advances the adapter cursor past
 * executions whose manifests are incompatible, then submits only an explicit key-to-authority map
 * for an atomic claim. An empty map is returned as an empty claim and can never become an unfiltered
 * store call. Keys created after a page was read wait for the next sweep.</p>
 *
 * <p>The manifest store and execution-store delegate must describe the same physical persistence
 * composition. PostgreSQL and SQLite enforce that by reading and locking the manifest row inside
 * the transaction that first creates or claims the process. Custom adapters cannot claim this
 * protection merely by implementing the two ports independently.</p>
 */
public final class ManagedExecutionStore implements InvocationHandler {
    private static final int MIN_CANDIDATE_PAGE = 32;
    private static final int MAX_PAGES_PER_SWEEP = 8;
    private static final java.util.Set<String> SAFE_DELEGATE_METHODS = java.util.Set.of(
            "capabilities", "supports", "maxLeaseTtl", "maxPayloadBytes", "load", "renew", "release",
            "leases", "ack", "maxClockSkew", "forgottenBefore", "lookupIdempotency",
            "idempotencyRecordCount", "purgeExpiredIdempotencyRecords", "loadHandler", "findHandler",
            "handlers", "loadToolApproval", "toolApprovals", "loadAgentAuthorityBudget",
            "loadAgentAuthorityControl", "transitionAgentAuthorityControl", "maxHumanTaskPageSize",
            "maxHumanTaskAttentionPageSize", "maxHumanTaskAttentionNodeCounts",
            "maxHumanTaskResponsePayloadBytes", "loadHumanTask", "listHumanTasks",
            "listHumanTaskAttention", "findHumanTaskAttention", "loadExecutionPause", "executionPauses",
            "findHeldExecutionPause", "journalRetention", "readJournal", "journalRetainedFrom",
            "outboxCursor", "advanceOutboxCursor", "recordInboxDelivery", "inboxRecordCount",
            "compactJournal", "maxInventoryPageSize", "terminalRetention", "listProcessInstances",
            "findProcessInstance", "listTraversals", "inventoryRetainedFrom",
            "purgeExpiredProcessInstances", "executionResultRetention", "maxExecutionResultPayloadBytes",
            "recordExecutionResult", "loadExecutionResult", "executionResultsRetainedFrom",
            "purgeExpiredExecutionResults", "close");

    private final ExecutionStore delegate;
    private final ExecutionManifestStore manifests;
    private final ConcurrentHashMap<Sweep, UUID> sweepCursors = new ConcurrentHashMap<>();

    private ManagedExecutionStore(ExecutionStore delegate, ExecutionManifestStore manifests) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.manifests = Objects.requireNonNull(manifests, "manifests");
    }

    /** Returns the fail-closed server view over one durable execution/manifest store composition. */
    public static ExecutionStore protect(ExecutionStore delegate, ExecutionManifestStore manifests) {
        return (ExecutionStore) Proxy.newProxyInstance(ExecutionStore.class.getClassLoader(),
                new Class<?>[]{ExecutionStore.class}, new ManagedExecutionStore(delegate, manifests));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "ManagedExecutionStore[" + delegate + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> throw new IllegalStateException("unsupported Object method " + method.getName());
            };
        }
        String name = method.getName();
        if (name.equals("protectsManagedPersistence") && method.getParameterCount() == 0) return true;
        if (name.equals("apply") && method.getParameterCount() == 1) {
            return apply((ExecutionBatch) arguments[0]);
        }
        if (name.equals("claim") && method.getParameterCount() == 3) {
            return claim((ExecutionKey) arguments[0], (String) arguments[1], (Duration) arguments[2]);
        }
        if (name.equals("claimPendingWork") && method.getParameterCount() == 4) {
            return claimAmong((String) arguments[0], (String) arguments[1], (int) arguments[2],
                    (Duration) arguments[3], false);
        }
        if (name.equals("claimDueTimers") && method.getParameterCount() == 4) {
            return claimAmong((String) arguments[0], (String) arguments[1], (int) arguments[2],
                    (Duration) arguments[3], true);
        }
        if (name.equals("applyManaged") || name.equals("claimManaged")
                || name.equals("claimPendingWorkAmong") || name.equals("claimDueTimersAmong")
                || name.equals("managedClaimCandidates")) {
            return failed("managed persistence internals are not public entry points");
        }
        if (!SAFE_DELEGATE_METHODS.contains(name)) {
            return failed("managed persistence does not expose this mutation");
        }
        return invokeDelegate(method, arguments);
    }

    private CompletionStage<?> apply(ExecutionBatch batch) {
        Objects.requireNonNull(batch, "batch");
        return authority(batch.key(), false).thenCompose(authority -> delegate.applyManaged(batch, authority));
    }

    private CompletionStage<?> claim(ExecutionKey key, String workerId, Duration ttl) {
        Objects.requireNonNull(key, "key");
        return authority(key, true).thenCompose(authority -> delegate.claimManaged(key, workerId, ttl, authority));
    }

    private CompletionStage<?> claimAmong(String tenantId, String workerId, int limit, Duration ttl,
                                           boolean timersOnly) {
        ExecutionStoreException invalid = invalidClaimRequest(tenantId, workerId, limit, ttl);
        if (invalid != null) return CompletableFuture.failedFuture(invalid);
        int pageSize = Math.min(delegate.maxInventoryPageSize(), Math.max(MIN_CANDIDATE_PAGE, limit));
        Sweep sweep = new Sweep(tenantId, timersOnly);
        Optional<UUID> start = Optional.ofNullable(sweepCursors.get(sweep));
        return collectAuthorities(tenantId, workerId, ttl, timersOnly, pageSize, start, limit)
                .thenCompose(scan -> {
                    if (scan.nextAfter().isPresent()) sweepCursors.put(sweep, scan.nextAfter().orElseThrow());
                    else sweepCursors.remove(sweep);
                    if (scan.authorities().isEmpty()) {
                        return scan.firstFailure() == null ? CompletableFuture.completedFuture(java.util.List.of())
                                : CompletableFuture.failedFuture(scan.firstFailure());
                    }
                    CompletionStage<? extends java.util.List<?>> claimed = timersOnly
                            ? delegate.claimDueTimersAmong(tenantId, workerId, limit, ttl, scan.authorities())
                            : delegate.claimPendingWorkAmong(tenantId, workerId, limit, ttl, scan.authorities());
                    return claimed.thenCompose(items -> items.isEmpty() && scan.firstFailure() != null
                            ? CompletableFuture.failedFuture(scan.firstFailure())
                            : CompletableFuture.completedFuture(items));
                });
    }

    private CompletionStage<AuthorityScan> collectAuthorities(
            String tenantId, String workerId, Duration ttl, boolean timersOnly, int pageSize,
            Optional<UUID> after, int required) {
        CompletionStage<AuthorityScan> stage = CompletableFuture.completedFuture(
                new AuthorityScan(Map.of(), null, after, false));
        for (int pageNumber = 0; pageNumber < MAX_PAGES_PER_SWEEP; pageNumber++) {
            stage = stage.thenCompose(scan -> {
                if (scan.authorities().size() >= required || scan.exhausted()) {
                    return CompletableFuture.completedFuture(scan);
                }
                return delegate.managedClaimCandidates(tenantId, workerId, pageSize, ttl, timersOnly,
                        scan.nextAfter()).thenCompose(page -> collectPageAuthorities(page.keys(), scan)
                                .thenApply(updated -> new AuthorityScan(updated.authorities(),
                                        updated.firstFailure(), page.nextAfter(), page.nextAfter().isEmpty())));
            });
        }
        return stage;
    }

    private CompletionStage<AuthorityScan> collectPageAuthorities(
            java.util.List<ExecutionKey> keys, AuthorityScan initial) {
        var found = new LinkedHashMap<>(initial.authorities());
        CompletionStage<Throwable> stage = CompletableFuture.completedFuture(initial.firstFailure());
        for (ExecutionKey key : keys) {
            stage = stage.thenCompose(firstFailure -> authority(key, true).handle((authority, failure) -> {
                if (failure == null) found.put(key, authority);
                return failure == null || firstFailure != null ? firstFailure : managedFailure(failure);
            }));
        }
        return stage.thenApply(failure -> new AuthorityScan(Map.copyOf(found), failure,
                initial.nextAfter(), initial.exhausted()));
    }

    private CompletionStage<ExecutionPersistenceAuthority> authority(ExecutionKey key,
                                                                      boolean requireCurrentCapacity) {
        return manifests.load(key).handle((stored, failure) -> {
            if (failure != null) throw new CompletionException(managedFailure(failure));
            try {
                ExecutionPersistenceAuthority authority = ExecutionPersistenceAuthority.from(stored);
                if (requireCurrentCapacity
                        && authority.maximumPayloadBytes() != delegate.maxPayloadBytes()) {
                    throw invalid("managed execution persistence capacity is incompatible");
                }
                return authority;
            } catch (IllegalArgumentException unavailable) {
                throw new CompletionException(invalid(
                        "managed execution persistence authority is unavailable"));
            }
        });
    }

    private static Throwable managedFailure(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        if (cause instanceof ExecutionStoreException) return cause;
        return invalid("managed execution persistence authority is unavailable");
    }

    private static CompletionStage<?> failed(String message) {
        return CompletableFuture.failedFuture(invalid(message));
    }

    private static ExecutionStoreException invalid(String message) {
        return new ExecutionStoreException(ExecutionStoreFailure.invalid(message));
    }

    private ExecutionStoreException invalidClaimRequest(String tenantId, String workerId, int limit,
                                                         Duration ttl) {
        if (tenantId == null || tenantId.isBlank()) return invalid("tenantId cannot be blank");
        if (workerId == null || workerId.isBlank()) return invalid("workerId cannot be blank");
        if (limit < 1) return invalid("limit must be positive");
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(delegate.maxLeaseTtl()) > 0) {
            return invalid("lease ttl is outside the store's supported range");
        }
        return null;
    }

    private Object invokeDelegate(Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(delegate, arguments);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause();
        }
    }

    private record AuthorityScan(Map<ExecutionKey, ExecutionPersistenceAuthority> authorities,
                                 Throwable firstFailure, Optional<UUID> nextAfter, boolean exhausted) {}

    private record Sweep(String tenantId, boolean timersOnly) {}
}
