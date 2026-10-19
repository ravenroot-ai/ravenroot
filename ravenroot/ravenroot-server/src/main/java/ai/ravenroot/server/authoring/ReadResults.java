package ai.ravenroot.server.authoring;

import ai.ravenroot.api.authoring.GraphAuthoringException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * Entry/byte-bounded immutable derived projections, scoped by trusted tenant and exact revisions.
 */
final class ReadResults<T> {
    private final LinkedHashMap<List<String>, T> values = new LinkedHashMap<>(16, .75f, true);
    private final Map<List<String>, CompletableFuture<T>> loading = new HashMap<>();
    private final ToLongFunction<T> weight;
    private long retained;
    private final int maximumEntries;
    private final long maximumBytes;

    ReadResults(ToLongFunction<T> weight) {
        this(weight, 512, 8 * 1024 * 1024L);
    }

    ReadResults(ToLongFunction<T> weight, int maximumEntries, long maximumBytes) {
        this.weight = weight;
        this.maximumEntries = maximumEntries;
        this.maximumBytes = maximumBytes;
    }

    synchronized long retainedBytes() {
        return retained;
    }

    T get(List<String> key, Supplier<T> load) {
        CompletableFuture<T> promise;
        boolean owner;
        synchronized (this) {
            T value = values.get(key);
            if (value != null) return value;
            promise = loading.get(key);
            owner = promise == null;
            if (owner) {
                if (loading.size() >= 64) throw unavailable();
                promise = new CompletableFuture<>();
                loading.put(key, promise);
            }
        }
        if (owner)
            try {
                T value = load.get();
                if (value == null) {
                    promise.complete(null);
                    return null;
                }
                long bytes = weight.applyAsLong(value);
                synchronized (this) {
                    if (bytes <= maximumBytes) {
                        T old = values.put(key, value);
                        retained += bytes - (old == null ? 0 : weight.applyAsLong(old));
                        while (values.size() > maximumEntries || retained > maximumBytes) {
                            var iterator = values.entrySet().iterator();
                            retained -= weight.applyAsLong(iterator.next().getValue());
                            iterator.remove();
                        }
                    }
                }
                promise.complete(value);
                return value;
            } catch (RuntimeException | Error failure) {
                promise.completeExceptionally(failure);
                throw failure;
            } finally {
                synchronized (this) {
                    loading.remove(key, promise);
                }
            }
        else
            try {
                return promise.get();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw unavailable();
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof RuntimeException classified) throw classified;
                throw unavailable();
            }
    }

    private static GraphAuthoringException unavailable() {
        return new GraphAuthoringException(GraphAuthoringException.Failure.UNAVAILABLE);
    }
}
