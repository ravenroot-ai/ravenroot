package ai.ravenroot.extensions.github;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.payload.PayloadJson;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared tenant/profile admission, durable operation ownership, cancellation and sanitized evidence. */
final class GithubRuntime {
    private static final ScheduledExecutorService LEASE_KEEPER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ravenroot-github-lease-keeper"); thread.setDaemon(true); return thread;
    });
    private final java.util.function.Supplier<GithubConfiguration> resolver;
    private volatile GithubConfiguration resolvedConfiguration;
    private volatile GithubOperationStore resolvedStore;
    private final ConcurrentHashMap<String, Gate> gates = new ConcurrentHashMap<>();

    GithubRuntime(GithubConfiguration configuration, GithubOperationStore store) {
        this.resolver = () -> configuration;
        this.resolvedConfiguration = java.util.Objects.requireNonNull(configuration);
        this.resolvedStore = java.util.Objects.requireNonNull(store);
    }

    GithubRuntime(java.util.function.Supplier<GithubConfiguration> resolver) {
        this.resolver = java.util.Objects.requireNonNull(resolver);
    }

    GithubConfiguration configuration() {
        GithubConfiguration current = resolvedConfiguration;
        if (current != null) return current;
        synchronized (this) {
            current = resolvedConfiguration;
            if (current == null) resolvedConfiguration = current = java.util.Objects.requireNonNull(resolver.get());
            return current;
        }
    }

    private GithubOperationStore store() {
        GithubOperationStore current = resolvedStore;
        if (current != null) return current;
        synchronized (this) {
            current = resolvedStore;
            if (current == null) resolvedStore = current = new SqliteGithubOperationStore(configuration().store());
            return current;
        }
    }

    GithubProfile requireProfile(String tenant, String name) {
        return configuration().profile(tenant, name).orElseThrow(() -> new GithubException(GithubException.Code.PROFILE_UNAVAILABLE));
    }

    CompletionStage<NodeResult> submit(NodeMessage message, NodePackageServices services, GithubProfile profile,
                                       String kind, String key, Map<String, Object> canonicalInput,
                                       long deadlineEpochMs, Work work) {
        return submit(message, services, profile, kind, key, canonicalInput, deadlineEpochMs,
                GithubOperationStore.BeginPolicy.ordinary(), work);
    }

    CompletionStage<NodeResult> submit(NodeMessage message, NodePackageServices services, GithubProfile profile,
                                       String kind, String key, Map<String, Object> canonicalInput,
                                       long deadlineEpochMs, GithubOperationStore.BeginPolicy beginPolicy, Work work) {
        String requestDigest = GithubValues.sha256(GithubValues.jsonBytes(canonicalInput));
        GithubOperationStore store = store();
        GithubOperationStore.Lease operation = store.begin(message.tenantId(), profile.name(), kind, key,
                requestDigest, deadlineEpochMs, beginPolicy);
        if (operation.owner().isEmpty()) {
            if (!operation.record().terminal() || operation.record().resultJson().isEmpty())
                return CompletableFuture.failedFuture(new GithubException(GithubException.Code.DURABILITY_UNAVAILABLE));
            Object replay = PayloadJson.read(operation.record().resultJson().getBytes(StandardCharsets.UTF_8),
                    GithubValues.LIMITS).toJava();
            Map<String, Object> value = GithubValues.object(replay);
            if (value.get("failureCode") instanceof String code) {
                try { return CompletableFuture.failedFuture(new GithubException(GithubException.Code.valueOf(code))); }
                catch (IllegalArgumentException invalid) {
                    return CompletableFuture.failedFuture(new GithubException(GithubException.Code.DURABILITY_UNAVAILABLE));
                }
            }
            return CompletableFuture.completedFuture(new NodeResult(outcome(operation.record().state()), value, Map.of()));
        }
        Gate gate = gates.compute(profile.tenantId() + "\u0000" + profile.name(), (ignored, current) ->
                current == null ? new Gate(profile.maxConcurrency()) : current.retain(profile.maxConcurrency()));
        if (!gate.permits.tryAcquire()) {
            store.release(operation); release(profile, gate); throw new GithubException(GithubException.Code.CAPACITY);
        }
        GithubApi.CallControl control = new GithubApi.CallControl();
        Task result = new Task(control);
        Thread worker = Thread.ofVirtual().name("ravenroot-github-" + kind).unstarted(() -> {
            LeaseKeeper keeper = new LeaseKeeper(store, operation, control, configuration().store().leaseMs());
            try {
                NodeResult completed;
                try {
                    completed = work.run(new GithubApi(services, message, profile, control,
                            () -> { control.check(); store.renew(operation); control.check(); }), operation, control);
                } catch (GithubException failure) {
                    if (failure.code() == GithubException.Code.CAS_LOST) {
                        result.completeExceptionally(failure);
                        return;
                    }
                    try { persistFailure(store, operation, deadlineEpochMs, kind, failure); result.completeExceptionally(failure); }
                    catch (RuntimeException persistence) {
                        result.completeExceptionally(new GithubException(GithubException.Code.DURABILITY_UNAVAILABLE));
                    }
                    return;
                } catch (RuntimeException failure) {
                    GithubException safe = new GithubException(GithubException.Code.RESPONSE_INVALID);
                    try { persistFailure(store, operation, deadlineEpochMs, kind, safe); result.completeExceptionally(safe); }
                    catch (RuntimeException persistence) {
                        result.completeExceptionally(new GithubException(GithubException.Code.DURABILITY_UNAVAILABLE));
                    }
                    return;
                }
                try {
                    Map<String, Object> output = GithubValues.object(completed.payload());
                    String state = state(completed.outcome(), output);
                    byte[] serialized = GithubValues.jsonBytes(output);
                    if (serialized.length > profile.maxResponseBytes()) throw GithubValues.invalid();
                    String json = new String(serialized, StandardCharsets.UTF_8);
                    String evidence = GithubValues.sha256(json);
                    boolean terminal = !"WAITING".equals(state);
                    store.save(operation, state, number(output.get("generation")), number(output.get("attempts")),
                            deadlineEpochMs, optional(output.get("remoteId")), evidence, json, terminal);
                    store.audit(operation, state, reason(output), evidence);
                    if (!terminal) store.release(operation);
                    result.complete(completed);
                } catch (RuntimeException persistence) {
                    result.completeExceptionally(new GithubException(GithubException.Code.DURABILITY_UNAVAILABLE));
                }
            } finally {
                keeper.close();
                gate.permits.release(); release(profile, gate);
            }
        });
        result.worker(worker); worker.start(); return result;
    }

    GithubOperationStore.DeliveryDecision bindDelivery(String tenant, String profile, String delivery,
                                                        String bindingDigest) {
        return store().bindDelivery(tenant, profile, delivery, bindingDigest);
    }

    private static void persistFailure(GithubOperationStore store, GithubOperationStore.Lease operation,
                                       long deadlineEpochMs, String kind, GithubException failure) {
        String state = failure.code() == GithubException.Code.CANCELLED ? "CANCELLED" : "FAILED";
        Map<String, Object> output = Map.of("version", "github.operation.failure.v1", "status",
                state.toLowerCase(java.util.Locale.ROOT), "failureCode", failure.code().name(),
                "generation", operation.record().generation(), "attempts", operation.record().attempts(),
                "remoteId", operation.record().remoteId());
        String json = new String(GithubValues.jsonBytes(output), StandardCharsets.UTF_8);
        String evidence = GithubValues.sha256(kind + ":" + failure.code());
        try {
            store.save(operation, state, operation.record().generation(), operation.record().attempts(),
                    deadlineEpochMs, operation.record().remoteId(), evidence, json, true);
            store.audit(operation, state, failure.code().name(), evidence);
        } catch (RuntimeException persistence) {
            throw new GithubException(GithubException.Code.DURABILITY_UNAVAILABLE);
        }
    }

    private void release(GithubProfile profile, Gate gate) {
        gates.compute(profile.tenantId() + "\u0000" + profile.name(), (ignored, current) -> {
            if (current != gate) return current;
            return gate.releaseReference() ? null : gate;
        });
    }

    private static long number(Object value) { return value instanceof Long number ? number : 0; }
    private static String optional(Object value) { return value instanceof String text ? text : ""; }
    private static String reason(Map<String, Object> output) { return optional(output.get("reason")).isEmpty() ? "NONE" : optional(output.get("reason")); }
    private static String state(String outcome, Map<String, Object> output) {
        String status = optional(output.get("status"));
        if ("timeout".equals(outcome)) return "TIMED_OUT";
        if ("failed".equals(outcome)) return "FAILED";
        if ("stale".equals(outcome)) return "STALE";
        if ("conflict".equals(outcome)) return "CONFLICT";
        if ("ambiguous".equals(outcome)) return "AMBIGUOUS";
        return status.equals("waiting") ? "WAITING" : "SUCCEEDED";
    }
    private static String outcome(String state) {
        return switch (state) {
            case "FAILED" -> "failed"; case "TIMED_OUT" -> "timeout"; case "STALE" -> "stale";
            case "CONFLICT" -> "conflict"; case "AMBIGUOUS" -> "ambiguous"; default -> "continue";
        };
    }

    @FunctionalInterface interface Work {
        NodeResult run(GithubApi api, GithubOperationStore.Lease operation, GithubApi.CallControl control);
    }

    private static final class Gate {
        final int maximum; final Semaphore permits; int references = 1;
        Gate(int maximum) { this.maximum = maximum; permits = new Semaphore(maximum, true); }
        synchronized Gate retain(int expected) { if (maximum != expected) throw new GithubException(GithubException.Code.CONFIGURATION); references++; return this; }
        synchronized boolean releaseReference() { return --references == 0; }
    }

    private static final class Task extends CompletableFuture<NodeResult> {
        private final GithubApi.CallControl control; private final AtomicBoolean cancellation = new AtomicBoolean();
        private volatile Thread worker;
        Task(GithubApi.CallControl control) { this.control = control; }
        void worker(Thread worker) { this.worker = worker; if (cancellation.get()) worker.interrupt(); }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            if (isDone() || !cancellation.compareAndSet(false, true)) return false;
            control.cancel(); Thread running = worker; if (running != null) running.interrupt();
            return completeExceptionally(new GithubException(GithubException.Code.CANCELLED));
        }
    }

    private static final class LeaseKeeper implements AutoCloseable {
        private final ScheduledFuture<?> task;
        LeaseKeeper(GithubOperationStore store, GithubOperationStore.Lease lease,
                    GithubApi.CallControl control, int leaseMs) {
            long interval = Math.max(100, leaseMs / 3L);
            task = LEASE_KEEPER.scheduleAtFixedRate(() -> {
                try { store.renew(lease); }
                catch (RuntimeException lost) { control.fail(new GithubException(GithubException.Code.CAS_LOST)); }
            }, interval, interval, TimeUnit.MILLISECONDS);
        }
        @Override public void close() { task.cancel(false); }
    }
}
