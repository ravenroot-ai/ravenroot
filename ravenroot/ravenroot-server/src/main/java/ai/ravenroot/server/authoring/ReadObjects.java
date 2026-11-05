package ai.ravenroot.server.authoring;

import ai.ravenroot.api.authoring.GraphAuthoringException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Bounded immutable bytes, with single-flight successful loads and independently cancellable
 * waiters.
 */
final class ReadObjects {
    private static final int ENTRIES = 2048;
    private static final long BYTES = 64 * 1024 * 1024L;
    private final LinkedHashMap<String, byte[]> values = new LinkedHashMap<>(16, .75f, true);
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> loading =
            new ConcurrentHashMap<>();
    private final Semaphore permits = new Semaphore(16, true);
    private long bytes;
    final LongAdder hits = new LongAdder(),
            misses = new LongAdder(),
            evictions = new LongAdder(),
            bypasses = new LongAdder();

    byte[] blob(
            String identity,
            GraphAuthoringReadIndex index,
            String repository,
            Supplier<byte[]> load) {
        synchronized (values) {
            byte[] value = values.get(identity);
            if (value != null) {
                hits.increment();
                return value.clone();
            }
        }
        var promise = new CompletableFuture<byte[]>();
        CompletableFuture<byte[]> prior;
        synchronized (loading) {
            synchronized (values) {
                byte[] raced = values.get(identity);
                if (raced != null) {
                    hits.increment();
                    return raced.clone();
                }
            }
            prior = loading.get(identity);
            if (prior == null) {
                if (loading.size() >= 64)
                    throw new GraphAuthoringException(
                            GraphAuthoringException.Failure.LIMIT_EXCEEDED);
                loading.put(identity, promise);
            }
        }
        if (prior != null) {
            hits.increment();
            try {
                return prior.get().clone();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw unavailable();
            } catch (ExecutionException failure) {
                throw propagate(failure.getCause());
            }
        }
        try {
            permits.acquire();
            try {
                misses.increment();
                byte[] value = null;
                try {
                    value = index.content(repository, identity).orElse(null);
                } catch (RuntimeException optionalCacheFailure) {
                    bypasses.increment();
                }
                if (value == null) value = load.get();
                verifyBlob(identity, value);
                try {
                    index.retainContent(repository, identity, value);
                } catch (RuntimeException optionalCacheFailure) {
                    bypasses.increment();
                }
                synchronized (values) {
                    if (value.length <= BYTES) {
                        byte[] replaced = values.put(identity, value.clone());
                        bytes += value.length - (replaced == null ? 0 : replaced.length);
                        while (values.size() > ENTRIES || bytes > BYTES) {
                            var iterator = values.entrySet().iterator();
                            var entry = iterator.next();
                            bytes -= entry.getValue().length;
                            iterator.remove();
                            evictions.increment();
                        }
                    } else bypasses.increment();
                }
                promise.complete(value.clone());
                return value.clone();
            } finally {
                permits.release();
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            promise.completeExceptionally(unavailable());
            throw unavailable();
        } catch (RuntimeException | Error failure) {
            promise.completeExceptionally(failure);
            throw failure;
        } finally {
            loading.remove(identity, promise);
        }
    }

    synchronized long retainedBytes() {
        synchronized (values) {
            return bytes;
        }
    }

    int inFlight() {
        return loading.size();
    }

    static String sha256(byte[] bytes) {
        return hash("SHA-256", bytes);
    }

    static String hash(String algorithm, byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static void verifyBlob(String identity, byte[] bytes) {
        if (!identity.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")) throw unavailable();
        var framed = new ByteArrayOutputStream();
        framed.writeBytes(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
        framed.writeBytes(bytes);
        if (!identity.equalsIgnoreCase(
                hash(identity.length() == 40 ? "SHA-1" : "SHA-256", framed.toByteArray())))
            throw unavailable();
    }

    private static RuntimeException propagate(Throwable failure) {
        return failure instanceof RuntimeException value ? value : unavailable();
    }

    private static GraphAuthoringException unavailable() {
        return new GraphAuthoringException(GraphAuthoringException.Failure.UNAVAILABLE);
    }
}
