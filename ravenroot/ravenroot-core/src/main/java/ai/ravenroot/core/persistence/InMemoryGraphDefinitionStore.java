package ai.ravenroot.core.persistence;

import ai.ravenroot.api.persistence.CanonicalGraphMl;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.persistence.GraphDefinitionKey;
import ai.ravenroot.api.persistence.GraphDefinitionReferences;
import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.api.persistence.GraphDefinitionStoreException;
import ai.ravenroot.api.persistence.GraphDefinitionStoreFailure;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredGraphDefinition;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Reference in-memory {@link GraphDefinitionStore}, following the {@link InMemoryExecutionStore}
 * precedent of hosting an application-api port's default adapter in core.
 *
 * <p>It is deliberately <strong>not</strong> durable and deliberately does <strong>not</strong>
 * declare {@link StoreCapability#DURABLE}: it loses every definition with the JVM. Declaring
 * durability in order to skip a conformance assertion would defeat the purpose of the suite, and
 * here it would be a particularly bad trade, because the assertion it would skip is the one the
 * whole store exists to satisfy.</p>
 *
 * <p>Definitions are held in two maps rather than one. The content map is addressed by digest and
 * holds one copy of each document; the binding map maps an immutable logical version onto a content
 * address. That split is what makes deduplication exact: the same document bound under two versions
 * is two bindings and one copy, while rebinding one version to a second document is refused rather
 * than silently changing what an execution pinned to that version would replay.</p>
 *
 * <p>All state is guarded by one monitor, which makes each operation atomic by construction: a
 * rejected put cannot leave a binding without its content, or content without its binding.</p>
 *
 * <p>Reads verify the stored bytes against their address before returning them, exactly as a durable
 * adapter must. In this adapter the check can only fail if this class has a bug, since nothing
 * outside it can reach the bytes — which is precisely why it is worth running: it is the assertion
 * that the two adapters implement the same contract rather than two similar ones.</p>
 */
public final class InMemoryGraphDefinitionStore implements GraphDefinitionStore {

    /** Safe default shared with GraphML ingest; composition may supply another value within the ceiling. */
    public static final int DEFAULT_MAX_DEFINITION_BYTES =
            GraphDefinitionStore.DEFAULT_MAX_DEFINITION_BYTES;

    private final Object monitor = new Object();
    private final Map<GraphDefinitionKey, Entry> definitions = new LinkedHashMap<>();
    private final Map<BindingKey, GraphContentId> bindings = new LinkedHashMap<>();
    private final Clock clock;
    private final GraphDefinitionReferences references;
    private final int maxDefinitionBytes;

    /**
     * Creates a store that treats every definition as unreferenced, which is correct only when
     * nothing durable can reach one.
     *
     * @param clock time authority for the instant a definition first becomes stored.
     */
    public InMemoryGraphDefinitionStore(Clock clock) {
        this(clock, GraphDefinitionReferences.NONE, DEFAULT_MAX_DEFINITION_BYTES);
    }

    /**
     * Creates a store that consults {@code references} before removing anything.
     *
     * @param clock time authority for the instant a definition first becomes stored.
     * @param references oracle asked whether retained work still reaches a definition.
     */
    public InMemoryGraphDefinitionStore(Clock clock, GraphDefinitionReferences references) {
        this(clock, references, DEFAULT_MAX_DEFINITION_BYTES);
    }

    /**
     * Creates a store with an explicit definition bound, for tests that must reach the oversize path
     * without allocating a document at the production ceiling.
     *
     * @param clock time authority for the instant a definition first becomes stored.
     * @param references oracle asked whether retained work still reaches a definition.
     * @param maxDefinitionBytes largest canonical definition this instance accepts.
     */
    public InMemoryGraphDefinitionStore(Clock clock, GraphDefinitionReferences references,
                                        int maxDefinitionBytes) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.references = Objects.requireNonNull(references, "references");
        if (maxDefinitionBytes < 1) {
            throw new IllegalArgumentException("maxDefinitionBytes must be positive");
        }
        if (maxDefinitionBytes > GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES) {
            throw new IllegalArgumentException("maxDefinitionBytes exceeds the supported safety ceiling");
        }
        this.maxDefinitionBytes = maxDefinitionBytes;
    }

    @Override
    public Set<StoreCapability> capabilities() {
        // Empty, and honestly so. DURABLE is false for this adapter, and every other member of the
        // vocabulary describes an execution-store facility this port does not offer -- a batch it
        // cannot apply, a lease it does not hold, a journal it does not keep. Declaring one to look
        // better furnished would assert a behaviour no assertion could then check.
        return Set.of();
    }

    @Override
    public int maxDefinitionBytes() {
        return maxDefinitionBytes;
    }

    @Override
    public CompletionStage<StoredGraphDefinition> put(String tenantId, GraphDefinitionIdentity identity,
                                                      CanonicalGraphMl canonical) {
        return complete(() -> {
            requireTenantId(tenantId);
            require(identity != null, "identity cannot be null");
            require(canonical != null, "canonical GraphML cannot be null");
            // Decided from the request alone, so it happens before the monitor is entered and before
            // anything could have been written.
            if (canonical.size() > maxDefinitionBytes) {
                throw failure(new GraphDefinitionStoreFailure.DefinitionTooLarge(
                        canonical.size(), maxDefinitionBytes));
            }
            var key = new GraphDefinitionKey(tenantId, canonical.contentId());
            var binding = new BindingKey(tenantId, identity);
            synchronized (monitor) {
                GraphContentId bound = bindings.get(binding);
                if (bound != null && !bound.equals(canonical.contentId())) {
                    throw failure(new GraphDefinitionStoreFailure.IdentityConflict(
                            tenantId, identity, bound, canonical.contentId()));
                }
                Entry existing = definitions.get(key);
                if (existing == null) {
                    existing = new Entry(identity, canonical, clock.instant());
                    definitions.put(key, existing);
                } else {
                    // A repeated write must not overwrite corruption into looking healthy, and must
                    // not restamp content that was already durable.
                    verify(key, existing);
                }
                bindings.put(binding, canonical.contentId());
                return existing.stored(key, existing.identity);
            }
        });
    }

    @Override
    public CompletionStage<StoredGraphDefinition> load(GraphDefinitionKey key) {
        return complete(() -> {
            require(key != null, "key cannot be null");
            synchronized (monitor) {
                Entry entry = definitions.get(key);
                if (entry == null) {
                    throw failure(new GraphDefinitionStoreFailure.NotFound(key));
                }
                verify(key, entry);
                return entry.stored(key, entry.identity);
            }
        });
    }

    @Override
    public CompletionStage<StoredGraphDefinition> resolve(String tenantId, GraphDefinitionIdentity identity) {
        return complete(() -> {
            requireTenantId(tenantId);
            require(identity != null, "identity cannot be null");
            synchronized (monitor) {
                GraphContentId bound = bindings.get(new BindingKey(tenantId, identity));
                if (bound == null) {
                    // Nothing to name but the tenant and the version, and a version that is not bound
                    // is the absence of a definition rather than a different failure.
                    throw failure(new GraphDefinitionStoreFailure.NotFound(
                            new GraphDefinitionKey(tenantId, unboundAddress())));
                }
                var key = new GraphDefinitionKey(tenantId, bound);
                Entry entry = definitions.get(key);
                if (entry == null) {
                    throw failure(new GraphDefinitionStoreFailure.Corrupted(key,
                            "a graph version binding names content this store does not hold"));
                }
                verify(key, entry);
                return entry.stored(key, identity);
            }
        });
    }

    @Override
    public CompletionStage<Boolean> contains(GraphDefinitionKey key) {
        return complete(() -> {
            require(key != null, "key cannot be null");
            synchronized (monitor) {
                return definitions.containsKey(key);
            }
        });
    }

    @Override
    public CompletionStage<Void> remove(GraphDefinitionKey key) {
        return complete(() -> {
            require(key != null, "key cannot be null");
            synchronized (monitor) {
                if (!definitions.containsKey(key)) {
                    throw failure(new GraphDefinitionStoreFailure.NotFound(key));
                }
                if (references.isReferenced(key)) {
                    throw failure(new GraphDefinitionStoreFailure.StillReferenced(key));
                }
                discard(key);
                return null;
            }
        });
    }

    @Override
    public CompletionStage<Long> purgeUnreferencedDefinitions(String tenantId) {
        return complete(() -> {
            requireTenantId(tenantId);
            synchronized (monitor) {
                List<GraphDefinitionKey> removable = new ArrayList<>();
                for (GraphDefinitionKey key : definitions.keySet()) {
                    if (key.tenantId().equals(tenantId) && !references.isReferenced(key)) {
                        removable.add(key);
                    }
                }
                removable.forEach(this::discard);
                return (long) removable.size();
            }
        });
    }

    @Override
    public void close() {
        synchronized (monitor) {
            definitions.clear();
            bindings.clear();
        }
    }

    /** Removes the content and every binding that named it. Caller holds the monitor. */
    private void discard(GraphDefinitionKey key) {
        definitions.remove(key);
        bindings.entrySet().removeIf(entry -> entry.getKey().tenantId.equals(key.tenantId())
                && entry.getValue().equals(key.contentId()));
    }

    /** Caller holds the monitor. */
    private static void verify(GraphDefinitionKey key, Entry entry) {
        GraphContentId observed = GraphContentId.of(entry.canonical.bytes());
        if (!observed.equals(key.contentId())) {
            throw failure(new GraphDefinitionStoreFailure.DigestMismatch(key, observed.value()));
        }
    }

    /**
     * An address that is a legal digest but cannot be the address of any document, used only to name
     * the tenant in a {@code NotFound} raised for an unbound version. A version has no content
     * address by definition, and the alternative -- inventing the address of the empty document --
     * would name a real, reachable document in the diagnostic.
     */
    private static GraphContentId unboundAddress() {
        return new GraphContentId("0".repeat(64));
    }

    private static void requireTenantId(String tenantId) {
        require(tenantId != null && !tenantId.isBlank(), "tenantId cannot be blank");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) {
            throw failure(new GraphDefinitionStoreFailure.InvalidRequest(reason));
        }
    }

    private static GraphDefinitionStoreException failure(GraphDefinitionStoreFailure classified) {
        return new GraphDefinitionStoreException(classified);
    }

    private static <T> CompletionStage<T> complete(Supplier<T> operation) {
        try {
            return CompletableFuture.completedFuture(operation.get());
        } catch (GraphDefinitionStoreException expected) {
            return CompletableFuture.failedFuture(expected);
        }
    }

    private record BindingKey(String tenantId, GraphDefinitionIdentity identity) {
    }

    private record Entry(GraphDefinitionIdentity identity, CanonicalGraphMl canonical, Instant storedAt) {

        StoredGraphDefinition stored(GraphDefinitionKey key, GraphDefinitionIdentity under) {
            return new StoredGraphDefinition(key, under, canonical, storedAt);
        }
    }
}
