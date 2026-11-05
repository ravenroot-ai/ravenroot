package ai.ravenroot.server.authoring;

import ai.ravenroot.api.authoring.GraphAuthoringRepository;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Managed periodic recovery and a trusted, coalescing webhook signal seam. Embedders must
 * authenticate provider webhooks before calling {@link #signal(String)}. Signals are hints:
 * reconciliation always validates remote references and the independent catalog.
 */
public final class ReconciledGraphAuthoringRepository extends ForwardingGraphAuthoringRepository {
    private static final class Pending {
        long epoch;
        CompletableFuture<Void> completion;
    }

    private final Map<String, Pending> tenants;
    private final ScheduledExecutorService timer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong failures = new AtomicLong(),
            completed = new AtomicLong(),
            lastSuccessMillis = new AtomicLong();

    public ReconciledGraphAuthoringRepository(
            GraphAuthoringRepository repository, Set<String> tenants, Duration interval) {
        super(repository);
        if (interval.compareTo(Duration.ofSeconds(1)) < 0
                || interval.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException(
                    "Reconciliation interval must be 1 second through 1 hour");
        var scopes = new HashMap<String, Pending>();
        tenants.forEach(tenant -> scopes.put(tenant, new Pending()));
        this.tenants = Map.copyOf(scopes);
        timer =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "authoring-index-reconciliation");
                            thread.setDaemon(true);
                            return thread;
                        });
        timer.scheduleWithFixedDelay(
                () -> {
                    if (!closed.get()) this.tenants.keySet().forEach(this::signal);
                },
                interval.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /** Coalesces duplicate signals; an event during a read causes a subsequent fresh validation. */
    public CompletionStage<Void> signal(String tenant) {
        Pending state = tenants.get(tenant);
        if (state == null || closed.get())
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "Unknown or closed authoring reconciliation scope"));
        CompletableFuture<Void> result;
        boolean start;
        synchronized (state) {
            state.epoch++;
            start = state.completion == null || state.completion.isDone();
            if (start) state.completion = new CompletableFuture<>();
            result = state.completion;
        }
        if (start) refresh(tenant, state, result);
        return result.thenApply(ignored -> null);
    }

    private void refresh(String tenant, Pending state, CompletableFuture<Void> result) {
        long epoch;
        synchronized (state) {
            epoch = state.epoch;
        }
        CompletionStage<Page<DocumentSummary>> operation;
        try {
            operation = delegate.list(new Actor(tenant, "authoring-index-reconciler"), "");
        } catch (RuntimeException failure) {
            failed(state, result, failure);
            return;
        }
        operation.whenComplete(
                (page, failure) -> {
                    if (failure != null) {
                        failed(state, result, failure);
                        return;
                    }
                    boolean again;
                    synchronized (state) {
                        again = state.epoch != epoch && !closed.get();
                        if (!again && state.completion == result) state.completion = null;
                    }
                    if (again) {
                        refresh(tenant, state, result);
                    } else {
                        completed.incrementAndGet();
                        lastSuccessMillis.set(System.currentTimeMillis());
                        result.complete(null);
                    }
                });
    }

    private void failed(Pending state, CompletableFuture<Void> result, Throwable failure) {
        synchronized (state) {
            if (state.completion == result) state.completion = null;
        }
        failures.incrementAndGet();
        result.completeExceptionally(failure);
    }

    /** Fixed labels only; lag is observable and never used to serve an offline fallback. */
    public Map<String, Long> reconciliationStatistics() {
        long last = lastSuccessMillis.get();
        return Map.of(
                "completed",
                completed.get(),
                "failures",
                failures.get(),
                "lagMillis",
                last == 0 ? -1 : Math.max(0, System.currentTimeMillis() - last));
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            timer.shutdownNow();
            delegate.close();
        }
    }
}
