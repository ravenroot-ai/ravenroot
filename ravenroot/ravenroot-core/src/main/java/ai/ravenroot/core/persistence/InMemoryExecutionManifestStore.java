package ai.ravenroot.core.persistence;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionManifest;
import ai.ravenroot.api.persistence.ExecutionManifestReferences;
import ai.ravenroot.api.persistence.ExecutionManifestStore;
import ai.ravenroot.api.persistence.ExecutionManifestStoreException;
import ai.ravenroot.api.persistence.ExecutionManifestStoreFailure;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredExecutionManifest;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Reference in-memory {@link ExecutionManifestStore}, following the
 * {@link InMemoryGraphDefinitionStore} precedent of hosting an application-api port's default
 * adapter in core.
 *
 * <p>It is deliberately <strong>not</strong> durable and deliberately does <strong>not</strong>
 * declare {@link StoreCapability#DURABLE}: it loses every manifest with the JVM. Declaring
 * durability to skip a conformance assertion would skip precisely the assertion this store exists to
 * satisfy.</p>
 *
 * <p>All state is guarded by one monitor, so each operation is atomic by construction: a refused pin
 * cannot leave a partially recorded manifest, and a removal cannot race the reference oracle it
 * consulted.</p>
 *
 * <p>Reads re-derive the digest from the held manifest before returning it. In this adapter that
 * check can only fail if this class has a bug, since nothing outside it can reach the fields — which
 * is exactly why it is worth running: it asserts that the two adapters implement one contract rather
 * than two similar ones.</p>
 */
public final class InMemoryExecutionManifestStore implements ExecutionManifestStore {

    private final Object monitor = new Object();
    private final Map<ExecutionKey, StoredExecutionManifest> manifests = new LinkedHashMap<>();
    private final Clock clock;
    private final ExecutionManifestReferences references;

    /**
     * Creates a store that treats every manifest as unreferenced, which is correct only when nothing
     * durable can reach one.
     *
     * @param clock time authority for the instant a manifest first becomes stored.
     */
    public InMemoryExecutionManifestStore(Clock clock) {
        this(clock, ExecutionManifestReferences.NONE);
    }

    /**
     * Creates a store that consults {@code references} before removing anything.
     *
     * @param clock time authority for the instant a manifest first becomes stored.
     * @param references oracle asked whether retained work still needs a manifest.
     */
    public InMemoryExecutionManifestStore(Clock clock, ExecutionManifestReferences references) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.references = Objects.requireNonNull(references, "references");
    }

    @Override
    public Set<StoreCapability> capabilities() {
        return Set.of(StoreCapability.TRANSACTIONAL_BATCH);
    }

    @Override
    public CompletionStage<StoredExecutionManifest> pin(ExecutionManifest manifest) {
        return complete(() -> {
            if (manifest == null) {
                throw failure(new ExecutionManifestStoreFailure.InvalidRequest("manifest cannot be null"));
            }
            var digest = manifest.digest();
            synchronized (monitor) {
                StoredExecutionManifest existing = manifests.get(manifest.key());
                if (existing != null) {
                    if (!existing.digest().equals(digest)) {
                        throw failure(new ExecutionManifestStoreFailure.ManifestConflict(
                                manifest.key(), existing.digest()));
                    }
                    return existing;
                }
                Instant now = Instant.now(clock);
                var stored = new StoredExecutionManifest(manifest, digest, now);
                manifests.put(manifest.key(), stored);
                return stored;
            }
        });
    }

    @Override
    public CompletionStage<StoredExecutionManifest> load(ExecutionKey key) {
        return complete(() -> {
            requireKey(key);
            synchronized (monitor) {
                StoredExecutionManifest stored = manifests.get(key);
                if (stored == null) {
                    throw failure(new ExecutionManifestStoreFailure.NotFound(key));
                }
                var observed = stored.manifest().digest();
                if (!observed.equals(stored.digest())) {
                    throw failure(new ExecutionManifestStoreFailure.DigestMismatch(key, observed.value()));
                }
                return stored;
            }
        });
    }

    @Override
    public CompletionStage<Boolean> contains(ExecutionKey key) {
        return complete(() -> {
            requireKey(key);
            synchronized (monitor) {
                return manifests.containsKey(key);
            }
        });
    }

    @Override
    public CompletionStage<Void> remove(ExecutionKey key) {
        return complete(() -> {
            requireKey(key);
            synchronized (monitor) {
                if (!manifests.containsKey(key)) {
                    throw failure(new ExecutionManifestStoreFailure.NotFound(key));
                }
                if (references.isReferenced(key)) {
                    throw failure(new ExecutionManifestStoreFailure.StillReferenced(key));
                }
                manifests.remove(key);
                return null;
            }
        });
    }

    @Override
    public void close() {
        synchronized (monitor) {
            manifests.clear();
        }
    }

    private static void requireKey(ExecutionKey key) {
        if (key == null) {
            throw failure(new ExecutionManifestStoreFailure.InvalidRequest("key cannot be null"));
        }
    }

    private static ExecutionManifestStoreException failure(ExecutionManifestStoreFailure classified) {
        return new ExecutionManifestStoreException(classified);
    }

    private static <T> CompletionStage<T> complete(Supplier<T> operation) {
        try {
            return CompletableFuture.completedFuture(operation.get());
        } catch (RuntimeException thrown) {
            return CompletableFuture.failedFuture(thrown);
        }
    }
}
